use std::{
    fs::File,
    os::fd::{BorrowedFd, RawFd},
    time::Instant,
};

use tetra3::{
    LensProjector,
    core::{
        PixelCentroid, PixelObservation, Solution, SolveConfig, SolveOutcome, UnitVec3, Vec3,
        apply_rectilinear_calibration,
    },
    index::MappedIndex,
    lens::{Database, PreparedLens},
    production::{
        MAX_OBSERVATIONS, OM1_PIXEL_PITCH_UM, PRODUCTION_MAX_OBSERVATIONS, ProductionCamera,
        ProductionCameraError, om1_star_camera_geometry, production_solve_config,
    },
};

use crate::{error::BridgeError, resolver::resolve_profile};

const MIN_MAX_OBSERVATIONS: usize = 8;
const MIN_TOLERANCE_ARCSEC: i32 = 15;
const MAX_TOLERANCE_ARCSEC: i32 = 300;
const MIN_REQUIRED_INDEPENDENT_MATCHES: usize = 1;
const MAX_REQUIRED_INDEPENDENT_MATCHES: usize = 16;

/// Validated Android-owned tuning for one solve. Chance-probability, ratio, edge, robust-fit, and
/// ambiguity policy stay in the shared production configuration.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct AndroidSolveConfig {
    max_observations: usize,
    seed_tolerance_arcsec: i32,
    match_tolerance_arcsec: i32,
    required_independent_matches: usize,
}

impl AndroidSolveConfig {
    pub(crate) fn new(
        max_observations: i32,
        seed_tolerance_arcsec: i32,
        match_tolerance_arcsec: i32,
        required_independent_matches: i32,
    ) -> Result<Self, BridgeError> {
        let max_observations = usize::try_from(max_observations)
            .map_err(|_| BridgeError::invalid("max observations is negative"))?;
        if !(MIN_MAX_OBSERVATIONS..=PRODUCTION_MAX_OBSERVATIONS).contains(&max_observations) {
            return Err(BridgeError::invalid(format!(
                "max observations must be in {MIN_MAX_OBSERVATIONS}..={PRODUCTION_MAX_OBSERVATIONS}"
            )));
        }
        if !(MIN_TOLERANCE_ARCSEC..=MAX_TOLERANCE_ARCSEC).contains(&seed_tolerance_arcsec) {
            return Err(BridgeError::invalid(format!(
                "seed tolerance must be in {MIN_TOLERANCE_ARCSEC}..={MAX_TOLERANCE_ARCSEC} arcsec"
            )));
        }
        if !(MIN_TOLERANCE_ARCSEC..=MAX_TOLERANCE_ARCSEC).contains(&match_tolerance_arcsec) {
            return Err(BridgeError::invalid(format!(
                "match tolerance must be in {MIN_TOLERANCE_ARCSEC}..={MAX_TOLERANCE_ARCSEC} arcsec"
            )));
        }
        let required_independent_matches = usize::try_from(required_independent_matches)
            .map_err(|_| BridgeError::invalid("required independent matches is negative"))?;
        if !(MIN_REQUIRED_INDEPENDENT_MATCHES..=MAX_REQUIRED_INDEPENDENT_MATCHES)
            .contains(&required_independent_matches)
        {
            return Err(BridgeError::invalid(format!(
                "required independent matches must be in {MIN_REQUIRED_INDEPENDENT_MATCHES}..={MAX_REQUIRED_INDEPENDENT_MATCHES}"
            )));
        }
        if required_independent_matches > max_observations.saturating_sub(4) {
            return Err(BridgeError::invalid(
                "required independent matches do not fit outside the four-star seed",
            ));
        }
        Ok(Self {
            max_observations,
            seed_tolerance_arcsec,
            match_tolerance_arcsec,
            required_independent_matches,
        })
    }

    fn production_config(self) -> SolveConfig {
        let mut config = production_solve_config();
        config.budgets.max_observations = self.max_observations;
        // Keep this operation order identical to SolveConfig::default so the default settings
        // reproduce the existing floating-point values bit for bit.
        config.seed_tolerance_rad = f64::from(self.seed_tolerance_arcsec).to_radians() / 3_600.0;
        config.match_tolerance_rad = f64::from(self.match_tolerance_arcsec).to_radians() / 3_600.0;
        config.min_matches = self.required_independent_matches;
        config
    }
}

impl Default for AndroidSolveConfig {
    fn default() -> Self {
        Self {
            max_observations: PRODUCTION_MAX_OBSERVATIONS,
            seed_tolerance_arcsec: 90,
            match_tolerance_arcsec: 75,
            required_independent_matches: 4,
        }
    }
}

#[derive(Debug)]
pub(crate) struct Engine {
    // The owner must outlive every IndexView created inside `solve`. Keeping
    // the view local avoids a self-referential struct and makes Engine Send +
    // Sync without lifetime tricks.
    index: MappedIndex,
    lenses: Database,
}

impl Engine {
    pub(crate) fn open_asset_region(
        raw_fd: RawFd,
        offset: u64,
        length: usize,
        lens_bytes: &[u8],
    ) -> Result<Self, BridgeError> {
        if raw_fd < 0 {
            return Err(BridgeError::invalid(
                "index asset file descriptor is negative",
            ));
        }
        if length == 0 {
            return Err(BridgeError::invalid("index asset region is empty"));
        }

        // Give Rust independent ownership. The Java AssetFileDescriptor may
        // close as soon as nativeCreate returns.
        // SAFETY: the caller lends a valid descriptor for this call. The
        // borrowed value is used only to create a new owned descriptor.
        let borrowed = unsafe { BorrowedFd::borrow_raw(raw_fd) };
        let file = File::from(borrowed.try_clone_to_owned()?);
        let file_length = file.metadata()?.len();
        let end = offset
            .checked_add(u64::try_from(length).map_err(|_| {
                BridgeError::invalid("index asset length does not fit in a file offset")
            })?)
            .ok_or_else(|| BridgeError::invalid("index asset region overflows"))?;
        if end > file_length {
            return Err(BridgeError::invalid(format!(
                "index asset region {offset}..{end} exceeds backing file length {file_length}"
            )));
        }

        // SAFETY: the APK asset is immutable and the checked region lies in
        // the backing file. Mmap retains its own kernel reference after `file`
        // is dropped.
        let index = unsafe { MappedIndex::map_trusted_region(&file, offset, length) }
            .map_err(|error| BridgeError::Index(error.to_string()))?;
        let lenses = if lens_bytes.is_empty() {
            Database::default()
        } else {
            Database::from_t3lf(lens_bytes).map_err(|error| BridgeError::Lens(error.to_string()))?
        };
        Ok(Self { index, lenses })
    }

    pub(crate) fn solve_until(
        &self,
        lens_identifier: &str,
        focal_mm: f64,
        observations: &[PixelObservation],
        android_config: AndroidSolveConfig,
        deadline: Instant,
    ) -> Result<AndroidSolveResult, BridgeError> {
        if observations.len() > MAX_OBSERVATIONS {
            return Err(BridgeError::invalid(format!(
                "{} observations exceed the Android wire maximum of {MAX_OBSERVATIONS}",
                observations.len()
            )));
        }
        // A profile is only an optional starting prior. Ambiguous identity or
        // a profile which cannot prepare at this focal must not take away the
        // deterministic profile-free solve path.
        let prepared_profile = resolve_profile(&self.lenses, lens_identifier)
            .ok()
            .flatten()
            .and_then(|profile| {
                profile
                    .prepare(focal_mm)
                    .ok()
                    .map(|lens| (lens, profile.id().as_str().to_owned()))
            });
        let (lens, profile_id) = match prepared_profile {
            Some((lens, profile_id)) => (lens, Some(profile_id)),
            None => (
                PreparedLens::rectilinear_no_distortion(
                    om1_star_camera_geometry(),
                    focal_mm,
                    OM1_PIXEL_PITCH_UM,
                )
                .map_err(|error| BridgeError::Lens(error.to_string()))?,
                None,
            ),
        };

        let view = self.index.view();
        let camera = ProductionCamera::new(view, lens).map_err(map_production_camera_error)?;
        let (solve_config, _) = camera
            .solve_config_for_star_pixels(android_config.production_config(), observations)
            .map_err(|error| BridgeError::Lens(error.to_string()))?;
        let outcome = camera
            .solve_pixels_with_deadline(observations, &solve_config, || Instant::now() >= deadline)
            .map_err(|error| BridgeError::Solve(error.to_string()))?;
        let (horizontal_fov_rad, solved_focal_mm) = match &outcome {
            SolveOutcome::Solved { solution, .. }
            | SolveOutcome::Ambiguous { best: solution, .. } => (
                fitted_horizontal_fov_rad(camera.lens(), solution)?,
                fitted_focal_mm(camera.lens(), solution),
            ),
            SolveOutcome::NoMatch { .. } => (camera.horizontal_fov_rad(), f64::NAN),
        };
        let horizontal_fov_deg = horizontal_fov_rad.to_degrees();

        Ok(AndroidSolveResult {
            outcome,
            profile_id,
            horizontal_fov_deg,
            solved_focal_mm,
        })
    }
}

/// Reproduce the accepted scale-plus-radial model at the delivered image's
/// horizontal pixel rims. `Solution` has already been composed back into this
/// prepared lens's coordinate system by the pixel runtime.
fn fitted_horizontal_fov_rad(lens: &PreparedLens, solution: &Solution) -> Result<f64, BridgeError> {
    let camera = lens.camera();
    let middle_y = f64::from(camera.encoded_height() - 1) * 0.5;
    let projector = LensProjector::new(lens);
    let calibrated_rim = |x| {
        let baseline = projector
            .pixel_to_ray(PixelCentroid { x, y: middle_y }, 1.0)
            .map_err(|error| BridgeError::Lens(error.to_string()))?;
        apply_rectilinear_calibration(
            baseline,
            solution.fov_scale,
            solution.rectilinear_radial_coefficient,
            solution.rectilinear_radial_reference_rad,
        )
        .map_err(|error| BridgeError::Solve(error.to_string()))
    };
    let left = calibrated_rim(-0.5)?;
    let right = calibrated_rim(f64::from(camera.encoded_width()) - 0.5)?;
    Ok(left.angle_to(right))
}

/// Central effective focal length in the assumed physical OM-1 sensor units.
/// The residual radial term has unit central derivative, so only the accepted
/// scale changes this metric. Using the prepared angular derivative also
/// accounts for a profile's geometric pitch and projection family.
fn fitted_focal_mm(lens: &PreparedLens, solution: &Solution) -> f64 {
    OM1_PIXEL_PITCH_UM
        / 1_000.0
        / (lens.central_angular_scale_per_calibration_pixel() * solution.fov_scale)
}

fn map_production_camera_error(error: ProductionCameraError) -> BridgeError {
    match &error {
        ProductionCameraError::Projection(_) => BridgeError::Lens(error.to_string()),
        ProductionCameraError::InvalidHorizontalFov => BridgeError::invalid(error.to_string()),
        _ => BridgeError::Index(error.to_string()),
    }
}

#[derive(Debug)]
pub(crate) struct AndroidSolveResult {
    pub(crate) outcome: SolveOutcome,
    pub(crate) profile_id: Option<String>,
    pub(crate) horizontal_fov_deg: f64,
    pub(crate) solved_focal_mm: f64,
}

pub(crate) fn solution_metrics(
    solution: &Solution,
    horizontal_fov_deg: f64,
    solved_focal_mm: f64,
) -> [f64; 6] {
    let quaternion = solution.camera_to_world;
    let center = quaternion.rotate_unit(UnitVec3::Z);
    let image_up =
        quaternion.rotate_unit(UnitVec3::new(0.0, -1.0, 0.0).expect("constant is unit length"));
    let (center_ra_deg, center_dec_deg, roll_deg) = sky_angles(center, image_up);
    [
        center_ra_deg,
        center_dec_deg,
        roll_deg,
        horizontal_fov_deg,
        solution.rms_residual_rad,
        solved_focal_mm,
    ]
}

fn sky_angles(center: UnitVec3, image_up: UnitVec3) -> (f64, f64, f64) {
    let center = center.as_vec();
    let ra_rad = center.y.atan2(center.x).rem_euclid(std::f64::consts::TAU);
    let dec_rad = center.z.clamp(-1.0, 1.0).asin();
    let (sin_ra, cos_ra) = ra_rad.sin_cos();
    let (sin_dec, cos_dec) = dec_rad.sin_cos();
    let east = Vec3::new(-sin_ra, cos_ra, 0.0);
    let north = Vec3::new(-cos_ra * sin_dec, -sin_ra * sin_dec, cos_dec);
    let up = image_up.as_vec();
    let roll_rad = up.dot(east).atan2(up.dot(north));
    (
        ra_rad.to_degrees(),
        dec_rad.to_degrees(),
        roll_rad.to_degrees().rem_euclid(360.0),
    )
}

#[cfg(test)]
mod tests {
    use std::os::fd::AsRawFd;

    use tetra3::LensProjector;
    use tetra3::core::{Diagnostics, Match, NoMatchReason, PixelCentroid, Quat, StarId};
    use tetra3::lens::{
        CalibrationGeometry, CameraGeometry, CropRect, DistortionKnot, DistortionModel,
        LensProfile, Orientation, PreparedLens, ProfileId, Projection,
    };
    use tetra3::production::{
        PRODUCTION_FOV_SCALE, PRODUCTION_MAX_PENDING_HYPOTHESES, PRODUCTION_MAX_VERIFICATION_PAIRS,
        STAR_WIRE_PIXEL_PHASE,
    };

    use super::*;

    #[test]
    fn roll_is_image_up_position_angle_east_of_north() {
        // Rotate the camera boresight to RA=0, Dec=0. Image-up remains -Y,
        // which is 90 degrees west (270 east) of celestial north (+Z).
        let attitude = Quat::from_axis_angle(UnitVec3::Y, 90.0_f64.to_radians()).unwrap();
        let center = attitude.rotate_unit(UnitVec3::Z);
        let up = attitude.rotate_unit(UnitVec3::new(0.0, -1.0, 0.0).unwrap());
        let (ra, dec, roll) = sky_angles(center, up);
        assert_eq!(ra, 0.0);
        assert!(dec.abs() < 1.0e-12);
        assert_eq!(roll, 270.0);
    }

    #[test]
    fn observation_cap_matches_wire_contract() {
        assert_eq!(MAX_OBSERVATIONS, 256);
        assert_eq!(PRODUCTION_MAX_OBSERVATIONS, MAX_OBSERVATIONS);
    }

    #[test]
    fn android_setting_defaults_are_bit_exact_with_existing_production_policy() {
        let expected = production_solve_config();
        let actual = AndroidSolveConfig::default().production_config();

        assert_eq!(
            actual.budgets.max_observations,
            expected.budgets.max_observations
        );
        assert_eq!(
            actual.seed_tolerance_rad.to_bits(),
            expected.seed_tolerance_rad.to_bits()
        );
        assert_eq!(
            actual.match_tolerance_rad.to_bits(),
            expected.match_tolerance_rad.to_bits()
        );
        assert_eq!(actual.min_matches, expected.min_matches);
        assert_fixed_policy_unchanged(&actual, &expected);
    }

    #[test]
    fn android_settings_override_only_the_four_native_solve_controls() {
        let expected = production_solve_config();
        let actual = AndroidSolveConfig::new(64, 120, 45, 8)
            .unwrap()
            .production_config();

        assert_eq!(actual.budgets.max_observations, 64);
        assert_eq!(
            actual.seed_tolerance_rad.to_bits(),
            (120.0_f64.to_radians() / 3_600.0).to_bits()
        );
        assert_eq!(
            actual.match_tolerance_rad.to_bits(),
            (45.0_f64.to_radians() / 3_600.0).to_bits()
        );
        assert_eq!(actual.min_matches, 8);
        assert_fixed_policy_unchanged(&actual, &expected);
    }

    #[test]
    fn android_settings_reject_out_of_range_and_impossible_combinations() {
        assert!(AndroidSolveConfig::new(8, 15, 300, 4).is_ok());
        assert!(AndroidSolveConfig::new(256, 300, 15, 16).is_ok());
        for invalid in [
            (7, 90, 75, 3),
            (257, 90, 75, 4),
            (256, 14, 75, 4),
            (256, 301, 75, 4),
            (256, 90, 14, 4),
            (256, 90, 301, 4),
            (256, 90, 75, 0),
            (256, 90, 75, 17),
            (8, 90, 75, 5),
        ] {
            assert!(
                AndroidSolveConfig::new(invalid.0, invalid.1, invalid.2, invalid.3).is_err(),
                "accepted invalid Android settings {invalid:?}"
            );
        }
    }

    fn assert_fixed_policy_unchanged(actual: &SolveConfig, expected: &SolveConfig) {
        assert_eq!(actual.budgets.max_quads, expected.budgets.max_quads);
        assert_eq!(
            actual.budgets.max_candidates,
            expected.budgets.max_candidates
        );
        assert_eq!(
            actual.budgets.max_hypotheses,
            expected.budgets.max_hypotheses
        );
        assert_eq!(
            actual.budgets.max_pending_hypotheses,
            expected.budgets.max_pending_hypotheses
        );
        assert_eq!(
            actual.budgets.max_index_records,
            expected.budgets.max_index_records
        );
        assert_eq!(
            actual.budgets.max_verification_pairs,
            expected.budgets.max_verification_pairs
        );
        assert_eq!(
            actual.budgets.max_catalog_cells,
            expected.budgets.max_catalog_cells
        );
        assert_eq!(
            actual.budgets.max_catalog_records,
            expected.budgets.max_catalog_records
        );
        assert_eq!(
            actual.budgets.max_catalog_bytes,
            expected.budgets.max_catalog_bytes
        );
        assert_eq!(
            actual.budgets.max_index_bytes,
            expected.budgets.max_index_bytes
        );
        assert_eq!(
            actual.validation_screen_observations,
            expected.validation_screen_observations
        );
        assert_eq!(
            actual.ratio_tolerance.to_bits(),
            expected.ratio_tolerance.to_bits()
        );
        assert_eq!(
            actual.largest_edge_relative_tolerance.to_bits(),
            expected.largest_edge_relative_tolerance.to_bits()
        );
        assert_eq!(
            actual.rectilinear_fov_scale_max_delta.to_bits(),
            expected.rectilinear_fov_scale_max_delta.to_bits()
        );
        assert_eq!(
            actual.rectilinear_radial_max_abs.to_bits(),
            expected.rectilinear_radial_max_abs.to_bits()
        );
        assert_eq!(
            actual.max_chance_probability.to_bits(),
            expected.max_chance_probability.to_bits()
        );
        assert_eq!(
            actual.fallback_density_per_steradian.to_bits(),
            expected.fallback_density_per_steradian.to_bits()
        );
        assert_eq!(actual.huber_rad.to_bits(), expected.huber_rad.to_bits());
        assert_eq!(actual.refinement_iterations, expected.refinement_iterations);
        assert_eq!(
            actual.same_solution_tolerance_rad.to_bits(),
            expected.same_solution_tolerance_rad.to_bits()
        );
        assert_eq!(
            actual.ambiguity_rms_ratio.to_bits(),
            expected.ambiguity_rms_ratio.to_bits()
        );
        assert_eq!(
            actual.ambiguity_match_margin,
            expected.ambiguity_match_margin
        );
        assert_eq!(
            actual.external_trial_factor.to_bits(),
            expected.external_trial_factor.to_bits()
        );
    }

    fn synthetic_solution(catalog_base: u32) -> Solution {
        let direction = UnitVec3::Z;
        Solution {
            camera_to_world: Quat::identity(),
            fov_scale: 1.0,
            rectilinear_radial_coefficient: 0.0,
            rectilinear_radial_reference_rad: 1.0,
            seed_matches: core::array::from_fn(|index| Match {
                observation_index: index,
                star_id: StarId(catalog_base + index as u32),
                catalog_direction: direction,
                residual_rad: index as f64 * 1.0e-6,
            }),
            matches: vec![Match {
                observation_index: 4,
                star_id: StarId(catalog_base + 4),
                catalog_direction: direction,
                residual_rad: 4.0e-6,
            }],
            rms_residual_rad: 4.0e-6,
            max_residual_rad: 4.0e-6,
            raw_chance_probability: 1.0e-8,
            chance_probability: 2.0e-8,
        }
    }

    #[test]
    fn ambiguous_result_preserves_each_hypotheses_pattern_associations() {
        let result = AndroidSolveResult {
            outcome: SolveOutcome::Ambiguous {
                best: synthetic_solution(100),
                runner_up: synthetic_solution(200),
                diagnostics: Diagnostics::default(),
            },
            profile_id: None,
            horizontal_fov_deg: 3.25,
            solved_focal_mm: 300.0,
        };

        let SolveOutcome::Ambiguous {
            best, runner_up, ..
        } = result.outcome
        else {
            panic!("expected ambiguous outcome");
        };
        assert_eq!(best.seed_matches.len(), 4);
        assert_eq!(best.seed_matches[0].observation_index, 0);
        assert_eq!(best.matches[0].observation_index, 4);
        assert_eq!(best.seed_matches[0].star_id, StarId(100));
        assert_eq!(runner_up.seed_matches.len(), 4);
        assert_eq!(runner_up.seed_matches[0].observation_index, 0);
        assert_eq!(runner_up.matches[0].observation_index, 4);
        assert_eq!(runner_up.seed_matches[0].star_id, StarId(200));
    }

    #[test]
    fn deadline_is_a_structured_no_match_instead_of_a_bridge_error() {
        let diagnostics = Diagnostics {
            deadline_exceeded: true,
            ..Diagnostics::default()
        };
        let result = AndroidSolveResult {
            outcome: SolveOutcome::NoMatch {
                reason: NoMatchReason::DeadlineExceeded,
                diagnostics,
            },
            profile_id: None,
            horizontal_fov_deg: 3.25,
            solved_focal_mm: f64::NAN,
        };

        let SolveOutcome::NoMatch {
            reason,
            diagnostics,
        } = result.outcome
        else {
            panic!("expected no-match outcome");
        };
        assert_eq!(reason, NoMatchReason::DeadlineExceeded);
        assert!(diagnostics.deadline_exceeded);
    }

    #[test]
    fn android_schedule_uses_full_verification_and_lazy_full_pattern_population() {
        let android = production_solve_config();
        assert_eq!(MAX_OBSERVATIONS, 256);
        assert_eq!(
            android.budgets.max_observations,
            PRODUCTION_MAX_OBSERVATIONS
        );
        assert_eq!(android.budgets.max_pattern_observations, MAX_OBSERVATIONS);
        assert_eq!(android.budgets.max_quads, u32::MAX);
        assert_eq!(android.budgets.max_candidates, u32::MAX);
        assert_eq!(android.budgets.max_hypotheses, u32::MAX);
        assert_eq!(
            android.budgets.max_pending_hypotheses,
            PRODUCTION_MAX_PENDING_HYPOTHESES
        );
        assert_eq!(android.budgets.max_index_records, u64::MAX);
        assert_eq!(
            android.budgets.max_verification_pairs,
            PRODUCTION_MAX_VERIFICATION_PAIRS
        );
        assert_eq!(android.budgets.max_catalog_cells, u64::MAX);
        assert_eq!(android.budgets.max_catalog_records, u64::MAX);
        assert_eq!(android.budgets.max_catalog_bytes, u64::MAX);
        assert_eq!(android.budgets.max_index_bytes, u64::MAX);
    }

    #[test]
    fn fallback_fov_uses_full_active_sensor_pixel_rims() {
        let camera = om1_star_camera_geometry();
        let lens =
            PreparedLens::rectilinear_no_distortion(camera, 300.0, OM1_PIXEL_PITCH_UM).unwrap();
        assert!(
            (LensProjector::new(&lens)
                .horizontal_fov_rad(PRODUCTION_FOV_SCALE)
                .unwrap()
                .to_degrees()
                - 3.266_349_613_706_033)
                .abs()
                < 1.0e-12
        );
    }

    #[test]
    fn fitted_focal_uses_central_scale_independently_of_raster_width_and_resize() {
        let mut solution = synthetic_solution(100);
        solution.fov_scale = 0.96;
        for (width, height, encoded_width, encoded_height) in [
            (5184, 3888, 5184, 3888),
            (5184, 3888, 1296, 972),
            (2592, 1944, 1296, 972),
        ] {
            let camera = CameraGeometry::new(
                width,
                height,
                CropRect::new(0, 0, width, height),
                encoded_width,
                encoded_height,
                Orientation::Normal,
                2.0,
            )
            .unwrap();
            let lens =
                PreparedLens::rectilinear_no_distortion(camera, 300.0, OM1_PIXEL_PITCH_UM).unwrap();
            assert!((fitted_focal_mm(&lens, &solution) - 312.5).abs() < 1.0e-12);
        }
    }

    #[test]
    fn fitted_profile_focal_converts_geometric_pitch_to_assumed_physical_pitch() {
        let camera = om1_star_camera_geometry();
        let geometric_pitch_mm = 36.0_f64.hypot(24.0)
            / f64::from(camera.crop_factor())
            / f64::from(camera.calibration_width()).hypot(f64::from(camera.calibration_height()));
        let mut solution = synthetic_solution(100);
        solution.fov_scale = 0.96;
        for (projection, focal_gain) in [
            (Projection::Rectilinear, 1.0),
            (Projection::FisheyeThoby, 1.47 * 0.713),
        ] {
            let lens = LensProfile::new(
                ProfileId::new("test.focal").unwrap(),
                camera,
                CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
                projection,
                vec![
                    DistortionKnot::new(300.0, 290.0, DistortionModel::Poly3 { k1: 0.03 }).unwrap(),
                ],
            )
            .unwrap()
            .prepare(300.0)
            .unwrap();
            let expected = 290.0 / solution.fov_scale
                * (OM1_PIXEL_PITCH_UM / 1_000.0 / geometric_pitch_mm)
                * focal_gain;
            let actual = fitted_focal_mm(&lens, &solution);
            assert!((actual - expected).abs() < 1.0e-12);
            assert!((actual - 290.0 / solution.fov_scale).abs() > 1.0);
        }
    }

    #[test]
    fn reported_fov_applies_the_accepted_nonzero_radial_model() {
        let lens = PreparedLens::rectilinear_no_distortion(
            om1_star_camera_geometry(),
            45.0,
            OM1_PIXEL_PITCH_UM,
        )
        .unwrap();
        let projector = LensProjector::new(&lens);
        let reference = projector
            .field_radius_rad(1.0 + tetra3::production::PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA)
            .unwrap();
        let mut solution = synthetic_solution(100);
        solution.fov_scale = 1.03;
        solution.rectilinear_radial_coefficient = -0.12;
        solution.rectilinear_radial_reference_rad = reference;

        let actual = fitted_horizontal_fov_rad(&lens, &solution).unwrap();
        let camera = lens.camera();
        let middle_y = f64::from(camera.encoded_height() - 1) * 0.5;
        let edge = projector
            .pixel_to_ray(
                PixelCentroid {
                    x: -0.5,
                    y: middle_y,
                },
                1.0,
            )
            .unwrap()
            .as_vec();
        let x = (edge.x / edge.z).abs();
        let y = edge.y / edge.z;
        let factor = solution.fov_scale
            * (1.0
                + solution.rectilinear_radial_coefficient * (x * x + y * y)
                    / reference.tan().powi(2));
        let expected = 2.0 * (factor * x).atan();
        assert!((actual - expected).abs() < 1.0e-14);

        let scale_only = projector.horizontal_fov_rad(solution.fov_scale).unwrap();
        assert!((actual - scale_only).abs() > 1.0e-4);
        let focal_mm = fitted_focal_mm(&lens, &solution);
        let metrics = solution_metrics(&solution, actual.to_degrees(), focal_mm);
        assert_eq!(metrics[3], actual.to_degrees());
        assert_eq!(metrics[4], solution.rms_residual_rad);
        assert_eq!(metrics[5], focal_mm);
        solution.rectilinear_radial_coefficient = 0.0;
        assert_eq!(fitted_focal_mm(&lens, &solution), focal_mm);
        assert!((focal_mm - 45.0 / 1.03).abs() < 1.0e-12);
    }

    #[test]
    fn production_index_opens_and_solves_without_a_lens_database_when_available() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let index_path = root.join("build/index/om1-300f4-to-45.t3ix");
        if !index_path.is_file() {
            return;
        }
        let index_file = std::fs::File::open(index_path).unwrap();
        let index_length = index_file.metadata().unwrap().len() as usize;
        let engine = Engine::open_asset_region(index_file.as_raw_fd(), 0, index_length, &[])
            .expect("the index must not require a Lensfun database");
        assert!(engine.lenses.profiles().is_empty());

        let result = engine
            .solve_until(
                "OLYMPUS M.12-45mm F4.0",
                45.0,
                &[],
                AndroidSolveConfig::default(),
                Instant::now() + std::time::Duration::from_secs(1),
            )
            .unwrap();
        assert!(matches!(
            result.outcome,
            SolveOutcome::NoMatch {
                reason: NoMatchReason::InsufficientObservations,
                ..
            }
        ));
        assert!(result.profile_id.is_none());
    }

    #[test]
    fn production_assets_open_and_fallback_without_materializing_index_when_available() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let index_path = root.join("build/index/om1-300f4-to-45.t3ix");
        let lens_path = root.join("build/profiles/om1-all-mft.t3lf");
        if !(index_path.is_file() && lens_path.is_file()) {
            return;
        }
        let index_file = std::fs::File::open(index_path).unwrap();
        let index_length = index_file.metadata().unwrap().len() as usize;
        let lens_bytes = std::fs::read(lens_path).unwrap();
        let engine =
            Engine::open_asset_region(index_file.as_raw_fd(), 0, index_length, &lens_bytes)
                .unwrap();
        drop(index_file); // the engine's mapping must not depend on the Java-side fd owner
        let handle = crate::registry::insert(engine).unwrap();
        let retained_by_in_flight_solve = crate::registry::get(handle).unwrap();
        crate::registry::close(handle);
        crate::registry::close(handle);
        assert!(matches!(
            crate::registry::get(handle),
            Err(BridgeError::StaleHandle)
        ));

        // This Arc was obtained before close, exactly like an in-flight JNI
        // solve. It remains valid even though new lookups fail.
        let result = retained_by_in_flight_solve
            .solve_until(
                "OLYMPUS M.300mm F4.0",
                300.0,
                &[],
                AndroidSolveConfig::default(),
                Instant::now() + std::time::Duration::from_secs(1),
            )
            .unwrap();
        assert!(matches!(
            result.outcome,
            SolveOutcome::NoMatch {
                reason: NoMatchReason::InsufficientObservations,
                ..
            }
        ));
        assert!(result.profile_id.is_none());

        let camera_name = "OLYMPUS M.8mm F1.8";
        let profile = resolve_profile(&retained_by_in_flight_solve.lenses, camera_name)
            .unwrap()
            .unwrap();
        assert_eq!(profile.projection(), Projection::FisheyeEquisolid);
        let fisheye = profile.prepare(8.0).unwrap();
        let view = retained_by_in_flight_solve.index.view();
        let fisheye_camera = ProductionCamera::new(view, fisheye).unwrap();
        let fisheye_horizontal_fov_deg = fisheye_camera.horizontal_fov_rad().to_degrees();
        assert!((fisheye_horizontal_fov_deg - 131.584_924).abs() < 1.0e-5);

        let edge_observation = PixelObservation {
            centroid: PixelCentroid {
                x: 1_295.0 - STAR_WIRE_PIXEL_PHASE,
                y: 971.0 - STAR_WIRE_PIXEL_PHASE,
            },
            flux: 1.0,
        };
        let mut invalid_rectilinear_fit = production_solve_config();
        invalid_rectilinear_fit.largest_edge_relative_tolerance = 0.05;
        invalid_rectilinear_fit.rectilinear_fov_scale_max_delta = 0.05;
        invalid_rectilinear_fit.rectilinear_radial_max_abs = 0.20;
        let (quantized_config, quantization_error) = fisheye_camera
            .solve_config_for_star_pixels(invalid_rectilinear_fit, &[edge_observation])
            .unwrap();
        let quantization_error_arcsec = quantization_error.to_degrees() * 3_600.0;
        assert!(
            (500.0..530.0).contains(&quantization_error_arcsec),
            "{quantization_error_arcsec}"
        );
        assert!(
            (quantized_config.match_tolerance_rad.to_degrees() * 3_600.0
                - (75.0 + quantization_error_arcsec))
                .abs()
                < 1.0e-9
        );
        assert!(
            (quantized_config.seed_tolerance_rad.to_degrees() * 3_600.0
                - (90.0 + quantization_error_arcsec))
                .abs()
                < 1.0e-9
        );
        assert!(
            (quantized_config.huber_rad - quantized_config.match_tolerance_rad / 3.0).abs()
                < 1.0e-15
        );
        assert!(
            (quantized_config.same_solution_tolerance_rad
                - 2.0 * quantized_config.match_tolerance_rad)
                .abs()
                < 1.0e-15
        );
        assert_eq!(quantized_config.rectilinear_fov_scale_max_delta, 0.0);
        assert_eq!(quantized_config.rectilinear_radial_max_abs, 0.0);

        let runtime = fisheye_camera.runtime();
        assert_eq!(runtime.selected_level(), Some(7));
        assert!(fisheye_camera.is_over_index_wide_field());

        let wide_result = retained_by_in_flight_solve
            .solve_until(
                camera_name,
                8.0,
                &[],
                AndroidSolveConfig::default(),
                Instant::now() + std::time::Duration::from_secs(1),
            )
            .unwrap();
        assert!(matches!(
            wide_result.outcome,
            SolveOutcome::NoMatch {
                reason: NoMatchReason::InsufficientObservations,
                ..
            }
        ));
        assert_eq!(
            wide_result.profile_id.as_deref(),
            Some(
                "lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro"
            )
        );
    }
}
