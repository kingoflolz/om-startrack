//! Shared camera and STAR-wire policy for production solves.
//!
//! This module owns product policy which must remain identical between the
//! Android runtime and host-side validators. It deliberately stays above the
//! geometric solver: [`tetra3_core`] contains no Android or detector-format
//! knowledge.

use std::{error, fmt};

use tetra3_core::{PixelCentroid, PixelObservation, SolveConfig, SolveOutcome, VerificationPolicy};
use tetra3_index::IndexView;
use tetra3_lens::{CameraGeometry, CropRect, Orientation, PreparedLens, Projection};

use crate::{LensProjectionError, LensProjector, PixelSolveError, RuntimeIndex, RuntimeIndexError};

/// Maximum number of observations carried by and verified from one production
/// STAR request.
pub const MAX_OBSERVATIONS: usize = 256;
/// Brightest observations selected by the production solver.
pub const PRODUCTION_MAX_OBSERVATIONS: usize = 256;
/// Brightest observations eligible for production seed-pattern search.
/// Every rank after the initial four-source stage is a deterministic lazy
/// frontier, so no transported detection is hard-capped out of geometric
/// search. The normal deadline remains the tail work bound.
pub const PRODUCTION_MAX_PATTERN_OBSERVATIONS: usize = 256;
/// Production does not reject an index-query group based on its number of
/// seed-consistent sibling models. The caller's monotonic deadline is the
/// algorithmic work limit. This deliberately is not a byte-allocation bound;
/// allocation/overflow safety remains a separate implementation concern.
pub const PRODUCTION_MAX_PENDING_HYPOTHESES: usize = usize::MAX;
/// Production does not reject a solve based on accumulated verification
/// edges. The caller's monotonic deadline is the algorithmic work limit.
pub const PRODUCTION_MAX_VERIFICATION_PAIRS: usize = usize::MAX;

/// Native OM-1 detector width used by the production STAR pipeline.
pub const OM1_NATIVE_WIDTH: u32 = 5_184;
/// Native OM-1 detector height used by the production STAR pipeline.
pub const OM1_NATIVE_HEIGHT: u32 = 3_888;
/// OM-1 detector pixel pitch in micrometers.
pub const OM1_PIXEL_PITCH_UM: f64 = 3.3;
/// Width of the downsampled STAR centroid raster.
pub const STAR_ENCODED_WIDTH: u32 = 1_296;
/// Height of the downsampled STAR centroid raster.
pub const STAR_ENCODED_HEIGHT: u32 = 972;
/// Pixel-to-ray FOV scale used for production solves.
pub const PRODUCTION_FOV_SCALE: f64 = 1.0;
/// Candidate-local rectilinear focal/FOV correction allowed during indexed
/// lookup. Eight percent covers the scale correction measured in the retained
/// OM-1 captures while keeping the candidate search explicitly bounded.
pub const PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA: f64 = 0.08;
/// Maximum absolute dimensionless one-term radial self-calibration residual.
/// The residual uses the fixed full-raster plane radius as its reference, so
/// this remains independent of the detected stars.
pub const PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS: f64 = 0.25;
/// Raw normalized-ratio acquisition window used before candidate-local
/// self-calibration. This deliberately remains the compact index's native
/// three-Q8-bin contract; the exact decoded candidate must still pass the
/// fitted geometric gates before it can become a hypothesis.
pub const PRODUCTION_PATTERN_RATIO_TOLERANCE: f64 = 3.0 / 255.0;

/// Offset from an integer STAR wire coordinate to the solver's encoded pixel.
///
/// STAR publishes `q = round(native_pixel / 4)`. The normal pixel-rim mapping
/// from an encoded pixel `p` to the native raster is `4 * p + 1.5`, so
/// `p = q - 3/8` reconstructs the detector sampling phase.
pub const STAR_WIRE_PIXEL_PHASE: f64 = 3.0 / 8.0;

const STAR_WIRE_INTERIOR_HALF_CELL: f64 = 0.5;
// The final wire bin receives native values which would otherwise round into
// the out-of-range next bin. Its positive extent is three native pixels, or
// 0.75 encoded pixels, after the worker clamps to the last wire coordinate.
const STAR_WIRE_CLAMPED_UPPER_CELL: f64 = 0.75;

/// A prepared lens together with the production index route derived from its
/// physical horizontal field of view.
///
/// The value owns no index bytes. It retains a zero-copy runtime borrowing the
/// exact validated index view used to choose the route.
#[derive(Clone, Copy, Debug)]
pub struct ProductionCamera<'a> {
    lens: PreparedLens,
    runtime: RuntimeIndex<'a>,
    horizontal_fov_rad: f64,
    horizontal_fov_mdeg: u32,
    field_radius_rad: f64,
    maximum_index_fov_mdeg: u32,
    star_wire_quantization_error_rad: f64,
}

impl<'a> ProductionCamera<'a> {
    /// Prepare the production route for a focal-specific lens.
    ///
    /// Covered fields bind to the first matching level. A field wider than the
    /// complete index binds to the first level with the greatest declared
    /// maximum FOV and may use its configured neighbor levels. Internal gaps
    /// and fields below the index remain unsupported.
    pub fn new(view: IndexView<'a>, lens: PreparedLens) -> Result<Self, ProductionCameraError> {
        let projector = LensProjector::new(&lens);
        let horizontal_fov_rad = projector.horizontal_fov_rad(PRODUCTION_FOV_SCALE)?;
        let horizontal_fov_mdeg = horizontal_fov_to_mdeg(horizontal_fov_rad)?;
        let field_radius_rad = projector.field_radius_rad(PRODUCTION_FOV_SCALE)?;

        let (selected_level, maximum_index_fov_mdeg) =
            route_horizontal_fov_mdeg(view, horizontal_fov_mdeg)?;
        let over_index_wide_field = horizontal_fov_mdeg > maximum_index_fov_mdeg;

        // The index stores the exact verification-catalog cardinality for the
        // selected magnitude cutoff. RuntimeIndex applies the predeclared 4x
        // all-sky safety factor, whose measured densest 45-degree field is
        // still below 2x. Retaining this conservative camera-independent hint
        // avoids replacing known catalog density with the much looser generic
        // fallback in ordinary narrow fields.
        let runtime = RuntimeIndex::for_level(view, selected_level)?;
        let star_wire_quantization_error_rad = if over_index_wide_field {
            star_wire_quantization_error_bound_rad(&lens)?
        } else {
            0.0
        };

        Ok(Self {
            lens,
            runtime,
            horizontal_fov_rad,
            horizontal_fov_mdeg,
            field_radius_rad,
            maximum_index_fov_mdeg,
            star_wire_quantization_error_rad,
        })
    }

    #[must_use]
    pub const fn lens(&self) -> &PreparedLens {
        &self.lens
    }

    #[must_use]
    pub fn selected_level(&self) -> u8 {
        self.runtime
            .selected_level()
            .expect("production runtime is always bound to a level")
    }

    #[must_use]
    pub const fn horizontal_fov_rad(&self) -> f64 {
        self.horizontal_fov_rad
    }

    /// Horizontal field of view after applying a solver-fitted image-plane
    /// scale to this already prepared lens.
    pub fn horizontal_fov_rad_at_scale(&self, fov_scale: f64) -> Result<f64, LensProjectionError> {
        LensProjector::new(&self.lens).horizontal_fov_rad(fov_scale)
    }

    #[must_use]
    pub const fn horizontal_fov_mdeg(&self) -> u32 {
        self.horizontal_fov_mdeg
    }

    #[must_use]
    pub const fn field_radius_rad(&self) -> f64 {
        self.field_radius_rad
    }

    #[must_use]
    pub const fn maximum_index_fov_mdeg(&self) -> u32 {
        self.maximum_index_fov_mdeg
    }

    #[must_use]
    pub const fn is_over_index_wide_field(&self) -> bool {
        self.horizontal_fov_mdeg > self.maximum_index_fov_mdeg
    }

    /// Return the runtime bound to the same validated index used for routing.
    #[must_use]
    pub const fn runtime(&self) -> RuntimeIndex<'a> {
        self.runtime
    }

    /// Apply STAR wire-cell uncertainty to a caller-supplied solve config.
    ///
    /// Rectilinear lenses enable the bounded candidate-local scale and radial
    /// fit. Wide fields also add a fixed lens/raster bound for every legal
    /// STAR quantization cell, uniformly over that complete parameter box, to
    /// the normal optical/catalog tolerances. It is independent of the
    /// reported detections, so appending a fainter observation cannot change
    /// the acceptance test for an earlier prefix. The returned scalar is the
    /// applied uncertainty in radians, for diagnostics and validation.
    pub fn solve_config_for_star_pixels(
        &self,
        mut base: SolveConfig,
        _observations: &[PixelObservation],
    ) -> Result<(SolveConfig, f64), LensProjectionError> {
        if self.lens.projection() == Projection::Rectilinear {
            base.largest_edge_relative_tolerance = base
                .largest_edge_relative_tolerance
                .max(PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA);
            base.rectilinear_fov_scale_max_delta = PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA;
            base.rectilinear_radial_max_abs = PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS;
        } else {
            base.rectilinear_fov_scale_max_delta = 0.0;
            base.rectilinear_radial_max_abs = 0.0;
        }
        if !self.is_over_index_wide_field() {
            return Ok((base, 0.0));
        }

        let quantization_error = self.star_wire_quantization_error_rad;
        base.seed_tolerance_rad =
            (base.seed_tolerance_rad + quantization_error).min(std::f64::consts::PI);
        base.match_tolerance_rad =
            (base.match_tolerance_rad + quantization_error).min(std::f64::consts::PI);
        base.huber_rad = base.huber_rad.max(base.match_tolerance_rad / 3.0);
        base.same_solution_tolerance_rad = base
            .same_solution_tolerance_rad
            .max((2.0 * base.match_tolerance_rad).min(std::f64::consts::PI));
        Ok((base, quantization_error))
    }

    /// Solve pixels with the lens, FOV scale, and index route already bound to
    /// this production camera.
    pub fn solve_pixels(
        &self,
        observations: &[PixelObservation],
        config: &SolveConfig,
    ) -> Result<SolveOutcome, PixelSolveError> {
        self.solve_pixels_with_deadline(observations, config, || false)
    }

    /// Solve pixels while cooperatively checking a caller-supplied deadline or
    /// cancellation predicate.
    pub fn solve_pixels_with_deadline(
        &self,
        observations: &[PixelObservation],
        config: &SolveConfig,
        deadline_expired: impl FnMut() -> bool,
    ) -> Result<SolveOutcome, PixelSolveError> {
        self.runtime.solve_pixels_with_deadline(
            &self.lens,
            observations,
            PRODUCTION_FOV_SCALE,
            config,
            deadline_expired,
        )
    }
}

/// Canonical OM-1-to-STAR raster geometry used by production and validation.
#[must_use]
pub fn om1_star_camera_geometry() -> CameraGeometry {
    CameraGeometry::new(
        OM1_NATIVE_WIDTH,
        OM1_NATIVE_HEIGHT,
        CropRect::new(0, 0, OM1_NATIVE_WIDTH, OM1_NATIVE_HEIGHT),
        STAR_ENCODED_WIDTH,
        STAR_ENCODED_HEIGHT,
        Orientation::Normal,
        2.0,
    )
    .expect("the fixed production camera geometry is valid")
}

/// Validated production work schedule.
///
/// The signed packaged index and STAR request are finite and validated. A
/// monotonic deadline is the sole algorithmic compute terminator. Every one of
/// the 256 transported detections can contribute evidence or a lazy seed
/// frontier; production does not turn an intermediate work count into a
/// `BudgetExhausted` result.
#[must_use]
pub fn production_solve_config() -> SolveConfig {
    let mut config = SolveConfig::default();
    config.budgets.max_observations = PRODUCTION_MAX_OBSERVATIONS;
    config.budgets.max_pattern_observations = PRODUCTION_MAX_PATTERN_OBSERVATIONS;
    config.ratio_tolerance = PRODUCTION_PATTERN_RATIO_TOLERANCE;
    config.verification_policy = VerificationPolicy::OnlineCumulative;
    // A rejecting screen would prevent later observations from helping.
    config.validation_screen_observations = 0;
    config.budgets.max_quads = u32::MAX;
    config.budgets.max_candidates = u32::MAX;
    config.budgets.max_hypotheses = u32::MAX;
    config.budgets.max_pending_hypotheses = PRODUCTION_MAX_PENDING_HYPOTHESES;
    config.budgets.max_index_records = u64::MAX;
    config.budgets.max_verification_pairs = PRODUCTION_MAX_VERIFICATION_PAIRS;
    config.budgets.max_catalog_cells = u64::MAX;
    config.budgets.max_catalog_records = u64::MAX;
    config.budgets.max_catalog_bytes = u64::MAX;
    config.budgets.max_index_bytes = u64::MAX;
    config
}

fn horizontal_fov_to_mdeg(horizontal_fov_rad: f64) -> Result<u32, ProductionCameraError> {
    let value = (horizontal_fov_rad.to_degrees() * 1_000.0).round();
    if !value.is_finite() || value <= 0.0 {
        return Err(ProductionCameraError::InvalidHorizontalFov);
    }
    Ok(value as u32)
}

fn route_horizontal_fov_mdeg(
    view: IndexView<'_>,
    horizontal_fov_mdeg: u32,
) -> Result<(u8, u32), ProductionCameraError> {
    // A strict comparison preserves the Android production tie-break: if
    // maxima are equal, keep the first (lower-numbered) level.
    let mut widest = None;
    for level in 0..view.level_count() {
        let descriptor = view
            .level_descriptor(level)
            .map_err(RuntimeIndexError::from)?;
        if widest.is_none_or(|(_, current): (u16, tetra3_index::LevelDescriptor)| {
            descriptor.max_fov_mdeg > current.max_fov_mdeg
        }) {
            widest = Some((level, descriptor));
        }
    }
    let (widest_level, widest_descriptor) = widest.ok_or(ProductionCameraError::NoFovLevels)?;

    let containing_level = view
        .level_for_fov_mdeg(horizontal_fov_mdeg)
        .map_err(RuntimeIndexError::from)?;
    let selected_level = match containing_level {
        Some(level) => level.number(),
        None if horizontal_fov_mdeg > widest_descriptor.max_fov_mdeg => {
            ensure_reachable_patterns(view, widest_level, widest_descriptor)?;
            widest_level
        }
        None => {
            return Err(RuntimeIndexError::FovNotCovered {
                fov_mdeg: horizontal_fov_mdeg,
            }
            .into());
        }
    };
    debug_assert!(selected_level < tetra3_index::MAX_LEVELS);
    Ok((selected_level as u8, widest_descriptor.max_fov_mdeg))
}

fn ensure_reachable_patterns(
    view: IndexView<'_>,
    level: u16,
    descriptor: tetra3_index::LevelDescriptor,
) -> Result<(), ProductionCameraError> {
    let radius = u16::from(descriptor.neighbor_level_radius);
    let first = level.saturating_sub(radius);
    let last = level
        .saturating_add(radius)
        .min(view.level_count().saturating_sub(1));
    for reachable in first..=last {
        if view
            .level_descriptor(reachable)
            .map_err(RuntimeIndexError::from)?
            .pattern_count
            != 0
        {
            return Ok(());
        }
    }
    Err(ProductionCameraError::NoReachablePatterns { level })
}

fn star_wire_quantization_error_bound_rad(lens: &PreparedLens) -> Result<f64, LensProjectionError> {
    let camera = lens.camera();
    let width = f64::from(camera.encoded_width());
    let height = f64::from(camera.encoded_height());
    let (minimum_coordinate_scale, maximum_coordinate_scale, maximum_radial, reference_radius) =
        if lens.projection() == Projection::Rectilinear {
            let minimum_scale =
                PRODUCTION_FOV_SCALE * (1.0 - PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA);
            let maximum_scale =
                PRODUCTION_FOV_SCALE * (1.0 + PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA);
            let reference_radius = LensProjector::new(lens)
                .field_radius_rad(maximum_scale)?
                .tan();
            (
                minimum_scale,
                maximum_scale,
                PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS,
                reference_radius,
            )
        } else {
            (PRODUCTION_FOV_SCALE, PRODUCTION_FOV_SCALE, 0.0, 1.0)
        };
    let ordinary_minimum = -STAR_WIRE_PIXEL_PHASE;
    let final_minimum_x = width - 1.0 - STAR_WIRE_PIXEL_PHASE - STAR_WIRE_INTERIOR_HALF_CELL;
    let final_minimum_y = height - 1.0 - STAR_WIRE_PIXEL_PHASE - STAR_WIRE_INTERIOR_HALF_CELL;
    let final_maximum_x = width - 1.0 - STAR_WIRE_PIXEL_PHASE + STAR_WIRE_CLAMPED_UPPER_CELL;
    let final_maximum_y = height - 1.0 - STAR_WIRE_PIXEL_PHASE + STAR_WIRE_CLAMPED_UPPER_CELL;
    let mut maximum_error = 0.0_f64;
    let mut include = |minimum: PixelCentroid,
                       maximum: PixelCentroid,
                       maximum_delta_x: f64,
                       maximum_delta_y: f64|
     -> Result<(), LensProjectionError> {
        maximum_error = maximum_error.max(lens.pixel_region_angular_error_bound(
            tetra3_lens::Pixel::new(minimum.x, minimum.y),
            tetra3_lens::Pixel::new(maximum.x, maximum.y),
            maximum_delta_x,
            maximum_delta_y,
            minimum_coordinate_scale,
            maximum_coordinate_scale,
            maximum_radial,
            reference_radius,
        )?);
        Ok(())
    };

    // Ordinary bins never extend by more than half a pixel. Only the final
    // encoded row/column can receive the clamped +0.75-pixel tail, so keeping
    // those three thin regions separate avoids imposing their larger cell on
    // the optically sensitive image center.
    if camera.encoded_width() >= 2 && camera.encoded_height() >= 2 {
        include(
            PixelCentroid {
                x: ordinary_minimum,
                y: ordinary_minimum,
            },
            PixelCentroid {
                x: final_minimum_x,
                y: final_minimum_y,
            },
            STAR_WIRE_INTERIOR_HALF_CELL,
            STAR_WIRE_INTERIOR_HALF_CELL,
        )?;
    }
    if camera.encoded_height() >= 2 {
        include(
            PixelCentroid {
                x: final_minimum_x,
                y: ordinary_minimum,
            },
            PixelCentroid {
                x: final_maximum_x,
                y: final_minimum_y,
            },
            STAR_WIRE_CLAMPED_UPPER_CELL,
            STAR_WIRE_INTERIOR_HALF_CELL,
        )?;
    }
    if camera.encoded_width() >= 2 {
        include(
            PixelCentroid {
                x: ordinary_minimum,
                y: final_minimum_y,
            },
            PixelCentroid {
                x: final_minimum_x,
                y: final_maximum_y,
            },
            STAR_WIRE_INTERIOR_HALF_CELL,
            STAR_WIRE_CLAMPED_UPPER_CELL,
        )?;
    }
    include(
        PixelCentroid {
            x: final_minimum_x,
            y: final_minimum_y,
        },
        PixelCentroid {
            x: final_maximum_x,
            y: final_maximum_y,
        },
        STAR_WIRE_CLAMPED_UPPER_CELL,
        STAR_WIRE_CLAMPED_UPPER_CELL,
    )?;
    Ok(maximum_error)
}

#[cfg(test)]
fn maximum_star_wire_quantization_error_rad(
    lens: &PreparedLens,
    observations: &[PixelObservation],
    minimum_fov_scale: f64,
    maximum_fov_scale: f64,
) -> Result<f64, LensProjectionError> {
    let camera = lens.camera();
    let maximum_wire_x = f64::from(camera.encoded_width() - 1);
    let maximum_wire_y = f64::from(camera.encoded_height() - 1);
    let projector = LensProjector::new(lens);
    let mut maximum_error = 0.0_f64;
    for observation in observations {
        let centroid = observation.centroid;
        let wire_x = centroid.x + STAR_WIRE_PIXEL_PHASE;
        let wire_y = centroid.y + STAR_WIRE_PIXEL_PHASE;
        let minimum = PixelCentroid {
            x: if wire_x <= 0.0 {
                centroid.x
            } else {
                centroid.x - STAR_WIRE_INTERIOR_HALF_CELL
            },
            y: if wire_y <= 0.0 {
                centroid.y
            } else {
                centroid.y - STAR_WIRE_INTERIOR_HALF_CELL
            },
        };
        let maximum = PixelCentroid {
            x: centroid.x
                + if wire_x >= maximum_wire_x {
                    STAR_WIRE_CLAMPED_UPPER_CELL
                } else {
                    STAR_WIRE_INTERIOR_HALF_CELL
                },
            y: centroid.y
                + if wire_y >= maximum_wire_y {
                    STAR_WIRE_CLAMPED_UPPER_CELL
                } else {
                    STAR_WIRE_INTERIOR_HALF_CELL
                },
        };
        let error = if lens.projection() == Projection::Rectilinear {
            projector.pixel_box_error_rad_over_rectilinear_scale_range(
                centroid,
                minimum,
                maximum,
                minimum_fov_scale,
                maximum_fov_scale,
            )?
        } else {
            debug_assert_eq!(minimum_fov_scale, maximum_fov_scale);
            projector.pixel_box_error_rad(centroid, minimum, maximum, minimum_fov_scale)?
        };
        maximum_error = maximum_error.max(error);
    }
    Ok(maximum_error)
}

/// Failure while deriving a production route from a lens and index.
#[derive(Clone, Debug, PartialEq)]
#[non_exhaustive]
pub enum ProductionCameraError {
    Projection(LensProjectionError),
    Index(RuntimeIndexError),
    InvalidHorizontalFov,
    NoFovLevels,
    NoReachablePatterns { level: u16 },
}

impl fmt::Display for ProductionCameraError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Projection(error) => write!(formatter, "camera projection failed: {error}"),
            Self::Index(error) => error.fmt(formatter),
            Self::InvalidHorizontalFov => formatter.write_str("computed horizontal FOV is invalid"),
            Self::NoFovLevels => formatter.write_str("index has no FOV levels"),
            Self::NoReachablePatterns { level } => write!(
                formatter,
                "index level {level} and its configured neighbors contain no patterns"
            ),
        }
    }
}

impl error::Error for ProductionCameraError {
    fn source(&self) -> Option<&(dyn error::Error + 'static)> {
        match self {
            Self::Projection(error) => Some(error),
            Self::Index(error) => Some(error),
            _ => None,
        }
    }
}

impl From<LensProjectionError> for ProductionCameraError {
    fn from(error: LensProjectionError) -> Self {
        Self::Projection(error)
    }
}

impl From<RuntimeIndexError> for ProductionCameraError {
    fn from(error: RuntimeIndexError) -> Self {
        Self::Index(error)
    }
}

#[cfg(test)]
mod tests {
    use tetra3_core::UnitVec3;
    use tetra3_index::{IndexView, writer};
    use tetra3_lens::{CameraGeometry, CropRect, Orientation, PreparedLens};

    use super::*;

    #[derive(Clone, Copy)]
    struct LevelFixture {
        min_fov_mdeg: u32,
        max_fov_mdeg: u32,
        neighbor_level_radius: u8,
        verification_limit_q4: Option<i8>,
        has_pattern: bool,
    }

    fn build_index(levels: &[LevelFixture]) -> Vec<u8> {
        let mut builder = writer::IndexBuilder::new();
        builder.set_spatial_grid(8, 16).unwrap();
        let verification = [
            ([1.0, 0.0, 0.0], 1.0),
            ([0.0, 1.0, 0.0], 2.0),
            ([0.0, 0.0, 1.0], 3.0),
            ([-1.0, 0.0, 0.0], 4.0),
        ]
        .map(|(direction, magnitude)| {
            builder
                .add_verification_star(writer::VerificationStarInput {
                    direction,
                    magnitude,
                    flags: 0,
                })
                .unwrap()
        });
        let pattern = writer::PatternInput {
            ratios: [32, 64, 96, 128, 160],
            verification_stars: verification,
            largest_edge_q16: 1_000,
        };
        for level in levels {
            builder
                .add_level(writer::LevelInput {
                    min_fov_mdeg: level.min_fov_mdeg,
                    max_fov_mdeg: level.max_fov_mdeg,
                    default_tolerance_q8: 3,
                    neighbor_level_radius: level.neighbor_level_radius,
                    max_verification_magnitude_q4: level.verification_limit_q4,
                    patterns: if level.has_pattern {
                        vec![pattern]
                    } else {
                        vec![]
                    },
                })
                .unwrap();
        }
        builder.build().unwrap()
    }

    fn rectilinear_lens(horizontal_fov_deg: f64) -> PreparedLens {
        const WIDTH: u32 = 4_000;
        const HEIGHT: u32 = 3_000;
        const PITCH_UM: f64 = 3.3;
        let geometry = CameraGeometry::new(
            WIDTH,
            HEIGHT,
            CropRect::new(0, 0, WIDTH, HEIGHT),
            400,
            300,
            Orientation::Normal,
            2.0,
        )
        .unwrap();
        let width_mm = f64::from(WIDTH) * PITCH_UM / 1_000.0;
        let focal_mm = width_mm / (2.0 * (0.5 * horizontal_fov_deg.to_radians()).tan());
        PreparedLens::rectilinear_no_distortion(geometry, focal_mm, PITCH_UM).unwrap()
    }

    #[test]
    fn production_solve_config_is_exact_contract() {
        let config = production_solve_config();
        let arcseconds = |radians: f64| radians.to_degrees() * 3_600.0;
        assert_eq!(MAX_OBSERVATIONS, 256);
        assert_eq!(PRODUCTION_MAX_OBSERVATIONS, 256);
        assert_eq!(PRODUCTION_MAX_PATTERN_OBSERVATIONS, 256);
        assert_eq!(config.budgets.max_observations, 256);
        assert_eq!(config.budgets.max_pattern_observations, 256);
        assert_eq!(config.ratio_tolerance, 3.0 / 255.0);
        assert_eq!(
            config.verification_policy,
            VerificationPolicy::OnlineCumulative
        );
        assert_eq!(config.budgets.max_quads, u32::MAX);
        assert_eq!(config.budgets.max_candidates, u32::MAX);
        assert_eq!(config.budgets.max_hypotheses, u32::MAX);
        assert_eq!(
            config.budgets.max_pending_hypotheses,
            PRODUCTION_MAX_PENDING_HYPOTHESES
        );
        assert_eq!(config.budgets.max_index_records, u64::MAX);
        assert_eq!(
            config.budgets.max_verification_pairs,
            PRODUCTION_MAX_VERIFICATION_PAIRS
        );
        assert_eq!(config.budgets.max_catalog_cells, u64::MAX);
        assert_eq!(config.budgets.max_catalog_records, u64::MAX);
        assert_eq!(config.budgets.max_catalog_bytes, u64::MAX);
        assert_eq!(config.budgets.max_index_bytes, u64::MAX);
        assert_eq!(config.validation_screen_observations, 0);
        assert!((arcseconds(config.seed_tolerance_rad) - 90.0).abs() < 1.0e-12);
        assert!((arcseconds(config.match_tolerance_rad) - 75.0).abs() < 1.0e-12);
        assert!((arcseconds(config.huber_rad) - 25.0).abs() < 1.0e-12);
        assert!((arcseconds(config.same_solution_tolerance_rad) - 120.0).abs() < 1.0e-12);
        assert_eq!(config.min_matches, 4);
        assert_eq!(config.max_chance_probability, 1.0e-6);
        assert_eq!(config.fallback_density_per_steradian, 250_000.0);
        assert_eq!(config.refinement_iterations, 2);
        assert_eq!(config.external_trial_factor, 1.0);
    }

    #[test]
    fn routing_contract_covers_tie_gap_wide_and_density() {
        let fixtures = [
            LevelFixture {
                min_fov_mdeg: 3_000,
                max_fov_mdeg: 5_000,
                neighbor_level_radius: 0,
                verification_limit_q4: Some(20),
                has_pattern: true,
            },
            LevelFixture {
                min_fov_mdeg: 10_000,
                max_fov_mdeg: 20_000,
                neighbor_level_radius: 1,
                verification_limit_q4: Some(12),
                has_pattern: false,
            },
            LevelFixture {
                min_fov_mdeg: 15_000,
                max_fov_mdeg: 20_000,
                neighbor_level_radius: 0,
                verification_limit_q4: Some(16),
                has_pattern: false,
            },
        ];
        let bytes = build_index(&fixtures);
        let view = IndexView::parse(&bytes).unwrap();

        let (level, maximum) = route_horizontal_fov_mdeg(view, 4_000).unwrap();
        assert_eq!((level, maximum), (0, 20_000));
        let covered = ProductionCamera::new(view, rectilinear_lens(4.0)).unwrap();
        assert_eq!(covered.runtime().verification_star_count_hint(), Some(4));

        assert!(matches!(
            route_horizontal_fov_mdeg(view, 7_000),
            Err(ProductionCameraError::Index(
                RuntimeIndexError::FovNotCovered { fov_mdeg: 7_000 }
            ))
        ));

        let (level, maximum) = route_horizontal_fov_mdeg(view, 25_000).unwrap();
        assert_eq!((level, maximum), (1, 20_000));
        let wide_camera = ProductionCamera::new(view, rectilinear_lens(25.0)).unwrap();
        let runtime = wide_camera.runtime();
        assert_eq!(runtime.verification_star_count_hint(), Some(3));
        assert!(
            runtime
                .maximum_observed_pattern_edge_rad(0.03)
                .unwrap()
                .is_some()
        );

        let mut unreachable = fixtures;
        unreachable[1].neighbor_level_radius = 0;
        let unreachable = build_index(&unreachable);
        assert!(matches!(
            route_horizontal_fov_mdeg(IndexView::parse(&unreachable).unwrap(), 25_000),
            Err(ProductionCameraError::NoReachablePatterns { level: 1 })
        ));
    }

    #[test]
    fn star_saturated_edge_quantization_is_covered_by_the_fixed_bound() {
        let bytes = build_index(&[LevelFixture {
            min_fov_mdeg: 1_000,
            max_fov_mdeg: 3_000,
            neighbor_level_radius: 0,
            verification_limit_q4: None,
            has_pattern: true,
        }]);
        let geometry = om1_star_camera_geometry();
        let lens = PreparedLens::rectilinear_no_distortion(geometry, 300.0, 3.3).unwrap();
        let camera = ProductionCamera::new(IndexView::parse(&bytes).unwrap(), lens).unwrap();
        assert!(camera.is_over_index_wide_field());
        let observation = PixelObservation {
            centroid: PixelCentroid {
                x: 1_295.0 - STAR_WIRE_PIXEL_PHASE,
                y: 971.0 - STAR_WIRE_PIXEL_PHASE,
            },
            flux: 1.0,
        };
        let base = production_solve_config();
        let (adjusted, error) = camera
            .solve_config_for_star_pixels(base, &[observation])
            .unwrap();
        assert_eq!(
            adjusted.rectilinear_fov_scale_max_delta,
            PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA
        );
        assert_eq!(
            adjusted.rectilinear_radial_max_abs,
            PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS
        );
        assert_eq!(
            adjusted.largest_edge_relative_tolerance,
            PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA
        );
        let scale = 3.3e-3 / 300.0 * (1.0 + PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA);
        let center = UnitVec3::new(2_588.5 * scale, 1_940.5 * scale, 1.0).unwrap();
        let saturated = UnitVec3::new(2_591.5 * scale, 1_943.5 * scale, 1.0).unwrap();
        let expected = center.angle_to(saturated);
        assert!(error >= expected);
        assert!(error / expected < 2.0);
        assert!((adjusted.seed_tolerance_rad - (base.seed_tolerance_rad + error)).abs() < 1.0e-15);
        assert!(
            (adjusted.match_tolerance_rad - (base.match_tolerance_rad + error)).abs() < 1.0e-15
        );
        assert_eq!(adjusted.huber_rad, adjusted.match_tolerance_rad / 3.0);
        assert_eq!(
            adjusted.same_solution_tolerance_rad,
            2.0 * adjusted.match_tolerance_rad
        );

        let mut capped = base;
        capped.seed_tolerance_rad = std::f64::consts::PI;
        capped.match_tolerance_rad = std::f64::consts::PI;
        capped.huber_rad = 0.0;
        capped.same_solution_tolerance_rad = 0.0;
        let (capped, _) = camera
            .solve_config_for_star_pixels(capped, &[observation])
            .unwrap();
        assert_eq!(capped.seed_tolerance_rad, std::f64::consts::PI);
        assert_eq!(capped.match_tolerance_rad, std::f64::consts::PI);
        assert_eq!(capped.huber_rad, std::f64::consts::PI / 3.0);
        assert_eq!(capped.same_solution_tolerance_rad, std::f64::consts::PI);

        let covered_bytes = build_index(&[LevelFixture {
            min_fov_mdeg: 1_000,
            max_fov_mdeg: 4_000,
            neighbor_level_radius: 0,
            verification_limit_q4: None,
            has_pattern: true,
        }]);
        let covered_lens = PreparedLens::rectilinear_no_distortion(geometry, 300.0, 3.3).unwrap();
        let covered =
            ProductionCamera::new(IndexView::parse(&covered_bytes).unwrap(), covered_lens).unwrap();
        let (unchanged, error) = covered
            .solve_config_for_star_pixels(base, &[observation])
            .unwrap();
        assert_eq!(error, 0.0);
        assert_eq!(
            unchanged.rectilinear_fov_scale_max_delta,
            PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA
        );
        assert_eq!(
            unchanged.rectilinear_radial_max_abs,
            PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS
        );
        assert_eq!(
            unchanged.largest_edge_relative_tolerance,
            PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA
        );
        assert_eq!(unchanged.seed_tolerance_rad, base.seed_tolerance_rad);
        assert_eq!(unchanged.match_tolerance_rad, base.match_tolerance_rad);
        assert_eq!(unchanged.huber_rad, base.huber_rad);
        assert_eq!(
            unchanged.same_solution_tolerance_rad,
            base.same_solution_tolerance_rad
        );
    }

    #[test]
    fn wide_quantization_config_is_observation_independent() {
        let bytes = build_index(&[LevelFixture {
            min_fov_mdeg: 1_000,
            max_fov_mdeg: 3_000,
            neighbor_level_radius: 0,
            verification_limit_q4: None,
            has_pattern: true,
        }]);
        let lens = PreparedLens::rectilinear_no_distortion(om1_star_camera_geometry(), 300.0, 3.3)
            .unwrap();
        let camera = ProductionCamera::new(IndexView::parse(&bytes).unwrap(), lens).unwrap();
        let center = PixelObservation {
            centroid: PixelCentroid { x: 648.0, y: 486.0 },
            flux: 100.0,
        };
        let faint_edge = PixelObservation {
            centroid: PixelCentroid {
                x: 1_295.0 - STAR_WIRE_PIXEL_PHASE,
                y: 971.0 - STAR_WIRE_PIXEL_PHASE,
            },
            flux: 1.0,
        };
        let (empty, empty_error) = camera
            .solve_config_for_star_pixels(production_solve_config(), &[])
            .unwrap();
        let (prefix, prefix_error) = camera
            .solve_config_for_star_pixels(production_solve_config(), &[center])
            .unwrap();
        let (appended, appended_error) = camera
            .solve_config_for_star_pixels(production_solve_config(), &[center, faint_edge])
            .unwrap();
        assert_eq!(empty_error, prefix_error);
        assert_eq!(prefix_error, appended_error);
        for value in [
            empty.rectilinear_fov_scale_max_delta,
            prefix.rectilinear_fov_scale_max_delta,
            appended.rectilinear_fov_scale_max_delta,
        ] {
            assert_eq!(value, PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA);
        }
        for value in [
            empty.rectilinear_radial_max_abs,
            prefix.rectilinear_radial_max_abs,
            appended.rectilinear_radial_max_abs,
        ] {
            assert_eq!(value, PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS);
        }
        for value in [
            empty.largest_edge_relative_tolerance,
            prefix.largest_edge_relative_tolerance,
            appended.largest_edge_relative_tolerance,
        ] {
            assert_eq!(value, empty.largest_edge_relative_tolerance);
        }
        for value in [
            empty.seed_tolerance_rad,
            prefix.seed_tolerance_rad,
            appended.seed_tolerance_rad,
        ] {
            assert_eq!(value, empty.seed_tolerance_rad);
        }
        for value in [
            empty.match_tolerance_rad,
            prefix.match_tolerance_rad,
            appended.match_tolerance_rad,
        ] {
            assert_eq!(value, empty.match_tolerance_rad);
        }
        for value in [empty.huber_rad, prefix.huber_rad, appended.huber_rad] {
            assert_eq!(value, empty.huber_rad);
        }
        for value in [
            empty.same_solution_tolerance_rad,
            prefix.same_solution_tolerance_rad,
            appended.same_solution_tolerance_rad,
        ] {
            assert_eq!(value, empty.same_solution_tolerance_rad);
        }
    }

    #[test]
    fn fixed_quantization_bound_dominates_every_cell_and_selfcal_parameter_sample() {
        let geometry = CameraGeometry::new(
            400,
            300,
            CropRect::new(0, 0, 400, 300),
            4,
            3,
            Orientation::Normal,
            2.0,
        )
        .unwrap();
        let lens = PreparedLens::rectilinear_no_distortion(geometry, 8.0, 3.3).unwrap();
        let bound = star_wire_quantization_error_bound_rad(&lens).unwrap();
        let minimum_scale = 1.0 - PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA;
        let maximum_scale = 1.0 + PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA;
        let projector = LensProjector::new(&lens);
        let reference_radius = projector.field_radius_rad(maximum_scale).unwrap().tan();
        let transform = |centroid: PixelCentroid, scale: f64, radial: f64| {
            let baseline = projector.pixel_to_ray(centroid, 1.0).unwrap().as_vec();
            let radius2 =
                (baseline.x * baseline.x + baseline.y * baseline.y) / (baseline.z * baseline.z);
            let factor = scale * (1.0 + radial * radius2 / reference_radius.powi(2));
            UnitVec3::new(baseline.x * factor, baseline.y * factor, baseline.z).unwrap()
        };
        for wire_y in 0..geometry.encoded_height() {
            for wire_x in 0..geometry.encoded_width() {
                let observation = PixelObservation {
                    centroid: PixelCentroid {
                        x: f64::from(wire_x) - STAR_WIRE_PIXEL_PHASE,
                        y: f64::from(wire_y) - STAR_WIRE_PIXEL_PHASE,
                    },
                    flux: 1.0,
                };
                let exact_corner_bound = maximum_star_wire_quantization_error_rad(
                    &lens,
                    &[observation],
                    minimum_scale,
                    maximum_scale,
                )
                .unwrap();
                assert!(bound >= exact_corner_bound);

                let centroid = observation.centroid;
                let minimum = PixelCentroid {
                    x: if wire_x == 0 {
                        centroid.x
                    } else {
                        centroid.x - STAR_WIRE_INTERIOR_HALF_CELL
                    },
                    y: if wire_y == 0 {
                        centroid.y
                    } else {
                        centroid.y - STAR_WIRE_INTERIOR_HALF_CELL
                    },
                };
                let maximum = PixelCentroid {
                    x: centroid.x
                        + if wire_x + 1 == geometry.encoded_width() {
                            STAR_WIRE_CLAMPED_UPPER_CELL
                        } else {
                            STAR_WIRE_INTERIOR_HALF_CELL
                        },
                    y: centroid.y
                        + if wire_y + 1 == geometry.encoded_height() {
                            STAR_WIRE_CLAMPED_UPPER_CELL
                        } else {
                            STAR_WIRE_INTERIOR_HALF_CELL
                        },
                };
                for scale_step in 0..=8 {
                    let scale = minimum_scale
                        + (maximum_scale - minimum_scale) * f64::from(scale_step) / 8.0;
                    for radial_step in 0..=8 {
                        let radial = -PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS
                            + 2.0 * PRODUCTION_RECTILINEAR_RADIAL_MAX_ABS * f64::from(radial_step)
                                / 8.0;
                        let center_ray = transform(centroid, scale, radial);
                        for y_step in 0..=8 {
                            for x_step in 0..=8 {
                                let point = PixelCentroid {
                                    x: minimum.x
                                        + (maximum.x - minimum.x) * f64::from(x_step) / 8.0,
                                    y: minimum.y
                                        + (maximum.y - minimum.y) * f64::from(y_step) / 8.0,
                                };
                                assert!(
                                    center_ray.angle_to(transform(point, scale, radial)) <= bound
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn rectilinear_quantization_maximizes_the_complete_scale_interval() {
        let geometry = om1_star_camera_geometry();
        let lens = PreparedLens::rectilinear_no_distortion(geometry, 8.0, 3.3).unwrap();
        let observation = PixelObservation {
            centroid: PixelCentroid {
                x: 1_295.0 - STAR_WIRE_PIXEL_PHASE,
                y: 971.0 - STAR_WIRE_PIXEL_PHASE,
            },
            flux: 1.0,
        };
        let minimum_scale = 1.0 - PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA;
        let maximum_scale = 1.0 + PRODUCTION_RECTILINEAR_FOV_SCALE_MAX_DELTA;
        let minimum_error = maximum_star_wire_quantization_error_rad(
            &lens,
            &[observation],
            minimum_scale,
            minimum_scale,
        )
        .unwrap();
        let maximum_error = maximum_star_wire_quantization_error_rad(
            &lens,
            &[observation],
            maximum_scale,
            maximum_scale,
        )
        .unwrap();

        let ranged_error = maximum_star_wire_quantization_error_rad(
            &lens,
            &[observation],
            minimum_scale,
            maximum_scale,
        )
        .unwrap();
        let mut sampled_maximum = 0.0_f64;
        for step in 0..=1_024 {
            let scale = minimum_scale + (maximum_scale - minimum_scale) * f64::from(step) / 1_024.0;
            sampled_maximum = sampled_maximum.max(
                maximum_star_wire_quantization_error_rad(&lens, &[observation], scale, scale)
                    .unwrap(),
            );
        }
        assert!(ranged_error >= minimum_error);
        assert!(ranged_error >= maximum_error);
        assert!(ranged_error >= sampled_maximum);
        assert!((ranged_error - sampled_maximum).abs() < 1.0e-10);
    }
}
