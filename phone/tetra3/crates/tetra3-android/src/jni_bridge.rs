use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    time::{Duration, Instant},
};

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JObject, JString, JValue},
    sys::{jdouble, jint, jlong, jobject},
};
use tetra3::{
    core::{NoMatchReason, PixelObservation, Solution, SolveOutcome},
    production::{
        MAX_OBSERVATIONS, STAR_ENCODED_HEIGHT, STAR_ENCODED_WIDTH, STAR_WIRE_PIXEL_PHASE,
    },
};

use crate::{
    engine::{AndroidSolveConfig, AndroidSolveResult, Engine, solution_metrics},
    error::BridgeError,
    registry,
};

const EXCEPTION_CLASS: &str = "dev/omshutter/plate/NativeTetra3Exception";
const RESULT_CLASS: &str = "dev/omshutter/plate/NativeSolveResult";
const RESULT_CONSTRUCTOR: &str = "(IILjava/lang/String;[D[I[I[D)V";

const STATUS_SOLVED: i32 = 0;
const STATUS_NO_MATCH: i32 = 1;
const STATUS_AMBIGUOUS: i32 = 2;
const NO_MATCH_NONE: i32 = 0;
const NO_MATCH_INSUFFICIENT_OBSERVATIONS: i32 = 1;
const NO_MATCH_SEARCH_EXHAUSTED: i32 = 2;
const NO_MATCH_REJECTED_BY_VERIFICATION: i32 = 3;
const NO_MATCH_BUDGET_EXHAUSTED: i32 = 4;
const NO_MATCH_DEADLINE_EXCEEDED: i32 = 5;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_omshutter_plate_NativeTetra3Bridge_nativeCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    index_fd: jint,
    asset_offset: jlong,
    asset_length: jlong,
    lens_database: JByteArray<'_>,
) -> jlong {
    jni_call(&mut env, 0, |env| {
        if asset_offset < 0 {
            return Err(BridgeError::invalid("index asset offset is negative"));
        }
        if asset_length <= 0 {
            return Err(BridgeError::invalid("index asset length must be positive"));
        }
        let length = usize::try_from(asset_length)
            .map_err(|_| BridgeError::invalid("index asset is too large for this device"))?;
        let lens_bytes = if lens_database.is_null() {
            Vec::new()
        } else {
            env.convert_byte_array(&lens_database)?
        };
        let engine = Engine::open_asset_region(index_fd, asset_offset as u64, length, &lens_bytes)?;
        registry::insert(engine)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_omshutter_plate_NativeTetra3Bridge_nativeClose(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    jni_call(&mut env, (), |_env| {
        registry::close(handle);
        Ok(())
    });
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_omshutter_plate_NativeTetra3Bridge_nativeSolve(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    lens_name: JString<'_>,
    focal_mm: jdouble,
    xy: JDoubleArray<'_>,
    flux: JFloatArray<'_>,
    max_observations: jint,
    seed_tolerance_arcsec: jint,
    match_tolerance_arcsec: jint,
    required_independent_matches: jint,
    timeout_millis: jlong,
) -> jobject {
    jni_call(&mut env, JObject::null().into_raw(), |env| {
        let android_config = AndroidSolveConfig::new(
            max_observations,
            seed_tolerance_arcsec,
            match_tolerance_arcsec,
            required_independent_matches,
        )?;
        let timeout_millis = u64::try_from(timeout_millis)
            .map_err(|_| BridgeError::invalid("solve timeout must be nonnegative"))?;
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(timeout_millis))
            .ok_or_else(|| BridgeError::invalid("solve timeout is too large"))?;
        let engine = registry::get(handle)?;
        if lens_name.is_null() {
            return Err(BridgeError::invalid("lens name is null"));
        }
        let lens_name: String = env.get_string(&lens_name)?.into();

        let count = env.get_array_length(&flux)?;
        if count < 0 || count as usize > MAX_OBSERVATIONS {
            return Err(BridgeError::invalid(format!(
                "observation count must be at most {MAX_OBSERVATIONS}"
            )));
        }
        if env.get_array_length(&xy)? != count * 2 {
            return Err(BridgeError::invalid(
                "xy and flux arrays have inconsistent lengths",
            ));
        }
        let count = count as usize;
        let mut xy_values = vec![0.0_f64; count * 2];
        let mut flux_values = vec![0.0_f32; count];
        env.get_double_array_region(&xy, 0, &mut xy_values)?;
        env.get_float_array_region(&flux, 0, &mut flux_values)?;

        let observations = wire_observations(&xy_values, &flux_values)?;

        let result = engine.solve_until(
            &lens_name,
            focal_mm,
            &observations,
            android_config,
            deadline,
        )?;
        result_to_java(env, result)
    })
}

fn wire_to_encoded_pixel(value: f64) -> f64 {
    value - STAR_WIRE_PIXEL_PHASE
}

fn wire_observations(
    xy_values: &[f64],
    flux_values: &[f32],
) -> Result<Vec<PixelObservation>, BridgeError> {
    let count = flux_values.len();
    if xy_values.len() != count.saturating_mul(2) {
        return Err(BridgeError::invalid(
            "xy and flux arrays have inconsistent lengths",
        ));
    }
    if count > MAX_OBSERVATIONS {
        return Err(BridgeError::invalid(format!(
            "observation count must be at most {MAX_OBSERVATIONS}"
        )));
    }

    let maximum_x = f64::from(STAR_ENCODED_WIDTH - 1);
    let maximum_y = f64::from(STAR_ENCODED_HEIGHT - 1);
    let mut observations = Vec::with_capacity(count);
    for index in 0..count {
        let x = xy_values[index * 2];
        let y = xy_values[index * 2 + 1];
        let weight = flux_values[index];
        if !x.is_finite() || !y.is_finite() || x < 0.0 || x > maximum_x || y < 0.0 || y > maximum_y
        {
            return Err(BridgeError::invalid(format!(
                "centroid {index} is outside the encoded image"
            )));
        }
        if !weight.is_finite() || weight <= 0.0 {
            return Err(BridgeError::invalid(format!(
                "flux {index} must be finite and positive"
            )));
        }
        observations.push(PixelObservation {
            centroid: tetra3::core::PixelCentroid {
                x: wire_to_encoded_pixel(x),
                y: wire_to_encoded_pixel(y),
            },
            flux: weight,
        });
    }
    Ok(observations)
}

fn jni_call<T: Copy>(
    env: &mut JNIEnv<'_>,
    failure: T,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<T, BridgeError>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            throw(env, &error.to_string());
            failure
        }
        Err(_) => {
            throw(env, &BridgeError::Panic.to_string());
            failure
        }
    }
}

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    if env.exception_check().unwrap_or(true) {
        return;
    }
    if env.throw_new(EXCEPTION_CLASS, message).is_err() && !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", message);
    }
}

fn result_to_java(
    env: &mut JNIEnv<'_>,
    result: AndroidSolveResult,
) -> Result<jobject, BridgeError> {
    let AndroidSolveResult {
        outcome,
        profile_id,
        horizontal_fov_deg,
        solved_focal_mm,
    } = result;
    let (status, no_match_reason, solution, ambiguity_probabilities) = match &outcome {
        SolveOutcome::Solved {
            solution,
            diagnostics: _,
        } => (STATUS_SOLVED, NO_MATCH_NONE, Some(solution), Vec::new()),
        SolveOutcome::NoMatch { reason, .. } => {
            (STATUS_NO_MATCH, no_match_code(*reason), None, Vec::new())
        }
        SolveOutcome::Ambiguous {
            best,
            runner_up,
            diagnostics: _,
        } => (
            STATUS_AMBIGUOUS,
            NO_MATCH_NONE,
            None,
            vec![best.chance_probability, runner_up.chance_probability],
        ),
    };
    let (solution_metrics, pattern_indices, matched_indices) =
        solution_arrays(solution, horizontal_fov_deg, solved_focal_mm);

    let solution_metrics = double_array(env, &solution_metrics)?;
    let pattern_indices = int_array(env, &pattern_indices)?;
    let matched_indices = int_array(env, &matched_indices)?;
    let ambiguity_probabilities = double_array(env, &ambiguity_probabilities)?;
    let profile_id = env.new_string(profile_id.as_deref().unwrap_or(""))?;

    let object = env.new_object(
        RESULT_CLASS,
        RESULT_CONSTRUCTOR,
        &[
            JValue::Int(status),
            JValue::Int(no_match_reason),
            JValue::Object(profile_id.as_ref()),
            JValue::Object(solution_metrics.as_ref()),
            JValue::Object(pattern_indices.as_ref()),
            JValue::Object(matched_indices.as_ref()),
            JValue::Object(ambiguity_probabilities.as_ref()),
        ],
    )?;
    Ok(object.into_raw())
}

fn no_match_code(reason: NoMatchReason) -> i32 {
    match reason {
        NoMatchReason::InsufficientObservations => NO_MATCH_INSUFFICIENT_OBSERVATIONS,
        NoMatchReason::SearchExhausted => NO_MATCH_SEARCH_EXHAUSTED,
        NoMatchReason::RejectedByVerification => NO_MATCH_REJECTED_BY_VERIFICATION,
        NoMatchReason::BudgetExhausted => NO_MATCH_BUDGET_EXHAUSTED,
        NoMatchReason::DeadlineExceeded => NO_MATCH_DEADLINE_EXCEEDED,
    }
}

type SolutionArrays = (Vec<f64>, Vec<i32>, Vec<i32>);

fn solution_arrays(
    solution: Option<&Solution>,
    horizontal_fov_deg: f64,
    solved_focal_mm: f64,
) -> SolutionArrays {
    let Some(solution) = solution else {
        return (Vec::new(), Vec::new(), Vec::new());
    };
    let metrics = solution_metrics(solution, horizontal_fov_deg, solved_focal_mm).to_vec();
    let pattern_indices = match_indices(
        solution
            .seed_matches
            .iter()
            .map(|matched| matched.observation_index),
    );
    let indices = match_indices(
        solution
            .matches
            .iter()
            .map(|matched| matched.observation_index),
    );
    (metrics, pattern_indices, indices)
}

fn match_indices(matches: impl Iterator<Item = usize>) -> Vec<i32> {
    matches
        .map(|index| i32::try_from(index).expect("Android input is capped below i32::MAX"))
        .collect()
}

fn double_array<'local>(
    env: &mut JNIEnv<'local>,
    values: &[f64],
) -> Result<JDoubleArray<'local>, BridgeError> {
    let output = env.new_double_array(values.len() as i32)?;
    env.set_double_array_region(&output, 0, values)?;
    Ok(output)
}

fn int_array<'local>(
    env: &mut JNIEnv<'local>,
    values: &[i32],
) -> Result<JIntArray<'local>, BridgeError> {
    let output = env.new_int_array(values.len() as i32)?;
    env.set_int_array_region(&output, 0, values)?;
    Ok(output)
}

#[cfg(test)]
mod tests {
    use tetra3::core::{Match, Quat, StarId, UnitVec3};

    use super::*;

    fn solution() -> Solution {
        Solution {
            camera_to_world: Quat::identity(),
            fov_scale: 1.0,
            rectilinear_radial_coefficient: 0.0,
            rectilinear_radial_reference_rad: 1.0,
            seed_matches: core::array::from_fn(|index| Match {
                observation_index: index,
                star_id: StarId(100 + index as u32),
                catalog_direction: UnitVec3::Z,
                residual_rad: (index + 1) as f64 * 1.0e-6,
            }),
            matches: vec![
                Match {
                    observation_index: 4,
                    star_id: StarId(104),
                    catalog_direction: UnitVec3::Z,
                    residual_rad: 5.0e-6,
                },
                Match {
                    observation_index: 5,
                    star_id: StarId(105),
                    catalog_direction: UnitVec3::Z,
                    residual_rad: 6.0e-6,
                },
            ],
            rms_residual_rad: 5.0,
            max_residual_rad: 6.0,
            raw_chance_probability: 7.0,
            chance_probability: 8.0,
        }
    }

    #[test]
    fn solution_wire_layout_keeps_only_pattern_and_validation_indices() {
        let solution = solution();
        let (metrics, pattern_indices, indices) = solution_arrays(Some(&solution), 4.0, 312.5);

        assert_eq!(metrics.len(), 6);
        assert_eq!(&metrics[3..], [4.0, 5.0, 312.5]);
        assert_eq!(pattern_indices, [0, 1, 2, 3]);
        assert_eq!(indices, [4, 5]);
        assert_eq!(RESULT_CONSTRUCTOR, "(IILjava/lang/String;[D[I[I[D)V");
    }

    #[test]
    fn absent_solution_has_no_association_arrays() {
        let arrays = solution_arrays(None, 4.0, f64::NAN);
        assert!(arrays.0.is_empty());
        assert!(arrays.1.is_empty());
        assert!(arrays.2.is_empty());
    }

    #[test]
    fn star_wire_sampling_phase_is_removed_before_lens_projection() {
        assert_eq!(wire_to_encoded_pixel(0.0), -0.375);
        assert_eq!(wire_to_encoded_pixel(648.0), 647.625);
        assert_eq!(wire_to_encoded_pixel(1_295.0), 1_294.625);
    }

    #[test]
    fn wire_observations_use_the_fixed_star_plane() {
        let observations = wire_observations(&[0.0, 0.0, 1_295.0, 971.0], &[1.0, 2.0]).unwrap();
        assert_eq!(observations[0].centroid.x, -0.375);
        assert_eq!(observations[0].centroid.y, -0.375);
        assert_eq!(observations[1].centroid.x, 1_294.625);
        assert_eq!(observations[1].centroid.y, 970.625);
        assert!(wire_observations(&[1_295.1, 0.0], &[1.0]).is_err());
        assert!(wire_observations(&[0.0, 971.1], &[1.0]).is_err());
    }

    #[test]
    fn flux_must_remain_strictly_positive() {
        assert!(wire_observations(&[0.0, 0.0], &[0.0]).is_err());
        assert!(wire_observations(&[0.0, 0.0], &[-1.0]).is_err());
        assert!(wire_observations(&[0.0, 0.0], &[f32::NAN]).is_err());
    }

    #[test]
    fn wire_and_production_solve_accept_256() {
        assert_eq!(tetra3::production::MAX_OBSERVATIONS, 256);
        assert_eq!(tetra3::production::PRODUCTION_MAX_OBSERVATIONS, 256);
        assert_eq!(tetra3::production::PRODUCTION_MAX_PATTERN_OBSERVATIONS, 256);
        let xy = vec![0.0; MAX_OBSERVATIONS * 2];
        let flux = vec![1.0; MAX_OBSERVATIONS];
        assert_eq!(wire_observations(&xy, &flux).unwrap().len(), 256);

        let oversized_xy = vec![0.0; (MAX_OBSERVATIONS + 1) * 2];
        let oversized_flux = vec![1.0; MAX_OBSERVATIONS + 1];
        assert!(wire_observations(&oversized_xy, &oversized_flux).is_err());
    }
}
