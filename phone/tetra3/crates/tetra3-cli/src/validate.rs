//! Deterministic host-side validation of a production mmap-backed index.

use std::f64::consts::{PI, TAU};
use std::ops::ControlFlow;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use clap::Args;
use serde::Serialize;
use tetra3::RuntimeIndex;
use tetra3::core::{
    CatalogQuery, CatalogQueryStats, Diagnostics, Observation, PixelCentroid, PixelObservation,
    Quat, SolveConfig, SolveOutcome, StarCatalog, UnitVec3, VerificationPolicy,
};
use tetra3::production::{
    MAX_OBSERVATIONS, OM1_NATIVE_HEIGHT, OM1_NATIVE_WIDTH, PRODUCTION_FOV_SCALE, ProductionCamera,
    STAR_WIRE_PIXEL_PHASE, production_solve_config as shared_production_config,
};
use tetra3_index::IndexView;
use tetra3_lens::Pixel;

use crate::rng::{SPLITMIX_GAMMA, mix64};
use crate::validation_common::{
    ProfileRenderer, StarCamera, open_mapped, projection_name, radians_to_arcseconds,
    timing_quantiles_ns,
};
use crate::{Error, Result};

const DEFAULT_SEED: u64 = 5_573_589_319_909_091_187;
const MAX_REPORTED_FAILURES: usize = 64;

/// Options for the production-index synthetic solve audit.
#[derive(Clone, Debug, Args)]
pub struct ValidateSolvesArgs {
    /// mmap-backed .t3ix to exercise.
    pub path: PathBuf,
    /// Width divided by height for the synthetic rectilinear detector.
    #[arg(long, default_value_t = 4.0 / 3.0)]
    pub aspect_ratio: f64,
    /// Additional exact horizontal FOV to test; repeat for multiple values.
    #[arg(long)]
    pub additional_fov_deg: Vec<f64>,
    /// Render through the OM-1 native raster, encode integer 1296x972 STAR
    /// centroids, and solve through direct rectilinear fallback geometry.
    #[arg(long)]
    pub android_star_pixels: bool,
    /// Compiled .t3lf database used to render and solve a full wide-angle
    /// camera field. Requires --lens-profile-id and --focal-length-mm.
    #[arg(long)]
    pub lens_database: Option<PathBuf>,
    /// Exact, case-sensitive profile ID in --lens-database.
    #[arg(long)]
    pub lens_profile_id: Option<String>,
    /// Reported focal length used to prepare --lens-profile-id.
    #[arg(long)]
    pub focal_length_mm: Option<f64>,
    /// Deterministic all-sky pointings at every boundary and explicit FOV.
    #[arg(long, default_value_t = 2)]
    pub pointings_per_boundary: u32,
    /// Detector rolls in the unique [0, 180 degree) interval.
    #[arg(long, default_value_t = 2)]
    pub rolls_per_pointing: u32,
    /// Brightest real and false sources retained for pattern search and final validation.
    #[arg(
        long,
        default_value_t = shared_production_config().budgets.max_observations
    )]
    pub max_observations: usize,
    /// Brightest sources used by the inexpensive candidate-validation screen.
    #[arg(
        long,
        default_value_t = shared_production_config().validation_screen_observations
    )]
    pub validation_screen_observations: usize,
    /// Cooperative wall-clock solve deadline in milliseconds; zero disables it.
    #[arg(long, default_value_t = 1_000)]
    pub timeout_ms: u64,
    /// Maximum observation quads tested by each real solver invocation.
    #[arg(long, default_value_t = shared_production_config().budgets.max_quads)]
    pub max_quads: u32,
    /// Maximum decoded pattern candidates tested by each solve.
    #[arg(long, default_value_t = shared_production_config().budgets.max_candidates)]
    pub max_candidates: u32,
    /// Maximum vertex-permutation hypotheses tested by each solve.
    #[arg(long, default_value_t = shared_production_config().budgets.max_hypotheses)]
    pub max_hypotheses: u32,
    /// Independent deterministic probability of dropping a real source.
    #[arg(long, default_value_t = 0.0)]
    pub dropout_rate: f64,
    /// Isotropic one-sigma centroid noise applied to real camera rays.
    #[arg(long, default_value_t = 0.0)]
    pub centroid_noise_arcsec: f64,
    /// Bright synthetic false detections injected into each field.
    #[arg(long, default_value_t = 0)]
    pub false_detections: usize,
    /// Required fraction of all requested trials returning the correct attitude.
    #[arg(long, default_value_t = 0.95)]
    pub minimum_success_rate: f64,
    /// Maximum attitude error counted as a correct solve.
    #[arg(long, default_value_t = 60.0)]
    pub max_attitude_error_arcsec: f64,
    /// Required non-seed verification associations.
    #[arg(long, default_value_t = shared_production_config().min_matches)]
    pub min_matches: usize,
    /// Solver association radius.
    #[arg(
        long,
        default_value_t = radians_to_arcseconds(shared_production_config().match_tolerance_rad)
    )]
    pub match_tolerance_arcsec: f64,
    /// Four-star hypothesis residual gate.
    #[arg(
        long,
        default_value_t = radians_to_arcseconds(shared_production_config().seed_tolerance_rad)
    )]
    pub seed_tolerance_arcsec: f64,
    /// Family-wise chance-probability ceiling used by the real solver.
    #[arg(
        long,
        default_value_t = shared_production_config().max_chance_probability
    )]
    pub max_chance_probability: f64,
    /// Conservative catalog density used when the mmap index has no O(1)
    /// local-density hint.
    #[arg(
        long,
        default_value_t = shared_production_config().fallback_density_per_steradian
    )]
    pub fallback_density_per_steradian: f64,
    /// Reproducible perturbation seed.
    #[arg(long, default_value_t = DEFAULT_SEED)]
    pub seed: u64,
    #[arg(long)]
    pub json: bool,
}

#[derive(Clone, Copy, Debug)]
struct Boundary {
    level: u16,
    fov_deg: f64,
    edge: BoundaryEdge,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "snake_case")]
enum BoundaryEdge {
    Minimum,
    Maximum,
    Both,
    Explicit,
    LensProfile,
    WideProfile,
}

#[derive(Debug, Serialize)]
struct BoundarySummary {
    level: u16,
    fov_deg: f64,
    edge: BoundaryEdge,
    trials: u64,
    correct: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    focal_length_mm: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    wire_pixel_scale_arcsec: Option<f64>,
}

#[derive(Debug, Serialize)]
struct Failure {
    level: u16,
    fov_deg: f64,
    pointing: u32,
    roll: u32,
    observations: usize,
    kind: &'static str,
    detail: String,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct DiagnosticTotals {
    quads_tested: u64,
    index_queries: u64,
    index_buckets_touched: u64,
    index_records_scanned: u64,
    index_bytes_read: u64,
    candidates_tested: u64,
    hypotheses_tested: u64,
    verification_pairs: u64,
    catalog_cells_scanned: u64,
    catalog_records_scanned: u64,
    catalog_bytes_read: u64,
    solver_budget_exhaustions: u64,
    searches_stopped_after_solution: u64,
    deadline_exhaustions: u64,
}

impl DiagnosticTotals {
    fn add(&mut self, value: Diagnostics) {
        self.quads_tested += u64::from(value.quads_tested);
        self.index_queries += u64::from(value.index_queries);
        self.index_buckets_touched += value.index_buckets_touched;
        self.index_records_scanned += value.index_records_scanned;
        self.index_bytes_read += value.index_bytes_read;
        self.candidates_tested += u64::from(value.candidates_tested);
        self.hypotheses_tested += u64::from(value.hypotheses_tested);
        self.verification_pairs += value.verification_pairs;
        self.catalog_cells_scanned += value.catalog_cells_scanned;
        self.catalog_records_scanned += value.catalog_records_scanned;
        self.catalog_bytes_read += value.catalog_bytes_read;
        self.solver_budget_exhaustions += u64::from(value.budget_exhausted);
        self.searches_stopped_after_solution += u64::from(value.search_stopped_after_solution);
        self.deadline_exhaustions += u64::from(value.deadline_exceeded);
    }
}

#[derive(Debug, Serialize)]
struct ValidationSummary {
    format: &'static str,
    index: PathBuf,
    mapped_bytes: usize,
    levels: u16,
    seed: u64,
    aspect_ratio: f64,
    android_star_pixels: bool,
    input_mode: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    lens_database: Option<PathBuf>,
    #[serde(skip_serializing_if = "Option::is_none")]
    lens_profile_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    lens_projection: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    focal_length_mm: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    full_horizontal_fov_deg: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    full_field_radius_deg: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_index_horizontal_fov_deg: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_pattern_edge_deg: Option<f64>,
    additional_fov_deg: Vec<f64>,
    pointings_per_boundary: u32,
    rolls_per_pointing: u32,
    max_observations: usize,
    validation_screen_observations: usize,
    timeout_ms: u64,
    max_quads: u32,
    max_candidates: u32,
    max_hypotheses: u32,
    dropout_rate: f64,
    centroid_noise_arcsec: f64,
    false_detections: usize,
    minimum_success_rate: f64,
    max_attitude_error_arcsec: f64,
    fallback_density_per_steradian: f64,
    effective_catalog_density_per_steradian: ScalarSummary,
    catalog_density_fallback_trials: u64,
    quantization_uncertainty_arcsec: ScalarSummary,
    effective_match_tolerance_arcsec: ScalarSummary,
    effective_seed_tolerance_arcsec: ScalarSummary,
    effective_huber_arcsec: ScalarSummary,
    effective_same_solution_tolerance_arcsec: ScalarSummary,
    trials: u64,
    correct: u64,
    no_match: u64,
    deadline_no_match: u64,
    ambiguous: u64,
    wrong_attitude: u64,
    insufficient_observations: u64,
    catalog_budget_exhausted: u64,
    solver_errors: u64,
    success_rate: f64,
    passed: bool,
    elapsed_seconds: f64,
    solve_timing_ms: TimingSummary,
    attitude_error_arcsec: ScalarSummary,
    validation_matches: CountSummary,
    solutions_using_validation_beyond_screen: u64,
    wide_trials_with_nonlocal_brightest_four: u64,
    wide_correct_solutions_after_nonlocal_brightest_four: u64,
    solutions_using_validation_outside_index_fov: u64,
    solutions_using_validation_outside_pattern_edge: u64,
    wide_local_search_exercised: bool,
    wide_final_validation_exercised: bool,
    diagnostics: DiagnosticTotals,
    boundaries: Vec<BoundarySummary>,
    failures: Vec<Failure>,
    failures_omitted: u64,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct TimingSummary {
    median: f64,
    p95: f64,
    maximum: f64,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct ScalarSummary {
    mean: Option<f64>,
    maximum: Option<f64>,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct CountSummary {
    minimum: Option<usize>,
    mean: Option<f64>,
    maximum: Option<usize>,
}

#[derive(Clone, Copy, Debug)]
struct CameraFrame {
    right: UnitVec3,
    down: UnitVec3,
    forward: UnitVec3,
    camera_to_world: Quat,
}

impl CameraFrame {
    fn new(forward: UnitVec3, roll_rad: f64) -> Result<Self> {
        let reference = if forward.as_vec().z.abs() < 0.9 {
            UnitVec3::Z
        } else {
            UnitVec3::Y
        };
        let base_right = reference
            .cross(forward)
            .normalized()
            .map_err(|error| Error::Build(error.to_string()))?;
        let base_down = forward
            .cross(base_right)
            .normalized()
            .map_err(|error| Error::Build(error.to_string()))?;
        let cosine = roll_rad.cos();
        let sine = roll_rad.sin();
        let right = (base_right.as_vec() * cosine + base_down.as_vec() * sine)
            .normalized()
            .map_err(|error| Error::Build(error.to_string()))?;
        let down = (base_down.as_vec() * cosine - base_right.as_vec() * sine)
            .normalized()
            .map_err(|error| Error::Build(error.to_string()))?;
        let r = right.as_vec();
        let d = down.as_vec();
        let f = forward.as_vec();
        let camera_to_world =
            quat_from_rotation([[r.x, d.x, f.x], [r.y, d.y, f.y], [r.z, d.z, f.z]])?;
        Ok(Self {
            right,
            down,
            forward,
            camera_to_world,
        })
    }

    fn world_to_camera(self, direction: UnitVec3) -> Result<UnitVec3> {
        UnitVec3::new(
            self.right.dot(direction),
            self.down.dot(direction),
            self.forward.dot(direction),
        )
        .map_err(|error| Error::Build(error.to_string()))
    }
}

/// A production `.t3lf` profile prepared for a synthetic full-frame solve.
///
/// The forward renderer below is intentionally confined to this host-side
/// validator. Runtime correction continues to use `PreparedLens::pixel_to_ray`;
/// rendering stars through the inverse direction gives the acceptance test an
/// independent round trip through projection, distortion, raster geometry,
/// and the Android STAR pixel phase.
struct ProfileCamera<'a> {
    renderer: ProfileRenderer,
    production: ProductionCamera<'a>,
    max_pattern_edge_rad: f64,
}

impl<'a> ProfileCamera<'a> {
    fn load(view: IndexView<'a>, arguments: &ValidateSolvesArgs) -> Result<Option<Self>> {
        let (Some(database_path), Some(profile_id), Some(focal_length_mm)) = (
            arguments.lens_database.as_ref(),
            arguments.lens_profile_id.as_deref(),
            arguments.focal_length_mm,
        ) else {
            return Ok(None);
        };
        let renderer = ProfileRenderer::load(database_path, profile_id, focal_length_mm)?;
        let production = ProductionCamera::new(view, renderer.lens)
            .map_err(|error| Error::Validation(error.to_string()))?;
        let runtime = production.runtime();
        let relative_tolerance = shared_production_config().largest_edge_relative_tolerance;
        let max_pattern_edge_rad = runtime
            .maximum_observed_pattern_edge_rad(relative_tolerance)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?
            .ok_or_else(|| Error::InvalidIndex("widest index level has no pattern edge".into()))?;
        Ok(Some(Self {
            renderer,
            production,
            max_pattern_edge_rad,
        }))
    }

    fn projection_name(&self) -> &'static str {
        projection_name(self.renderer.profile.projection())
    }

    fn render_solver_centroid(&self, ray: UnitVec3) -> Result<Option<PixelCentroid>> {
        self.renderer.render_centroid(ray)
    }
}

#[derive(Clone, Copy, Debug)]
struct Detected {
    ray: UnitVec3,
    magnitude: f32,
    id: u32,
}

struct GeneratedField {
    observations: Vec<Observation>,
    pixels: Option<Vec<PixelObservation>>,
    catalog_stats: CatalogQueryStats,
}

pub fn run(arguments: ValidateSolvesArgs) -> Result<()> {
    validate_arguments(&arguments)?;
    let started = Instant::now();
    let mapped = open_mapped(&arguments.path)?;
    let view = mapped.view();
    let summary = validate_view(view, &arguments, started)?;
    let passed = summary.passed;
    let success_rate = summary.success_rate;
    let wide_local_search_exercised = summary.wide_local_search_exercised;
    let wide_final_validation_exercised = summary.wide_final_validation_exercised;
    crate::cli::print_value(&summary, arguments.json)?;
    if passed {
        Ok(())
    } else if !wide_local_search_exercised {
        Err(Error::Validation(
            "wide-angle solve did not succeed when the globally brightest four stars exceeded the index FOV"
                .into(),
        ))
    } else if !wide_final_validation_exercised {
        Err(Error::Validation(
            "wide-angle solve did not report any final validation association outside the expanded local pattern-edge span"
                .into(),
        ))
    } else {
        Err(Error::Validation(format!(
            "synthetic solve success rate {success_rate:.6} is below required {:.6}",
            arguments.minimum_success_rate
        )))
    }
}

fn validation_solve_config(arguments: &ValidateSolvesArgs) -> SolveConfig {
    let mut config = shared_production_config();
    config.budgets.max_observations = arguments.max_observations;
    config.validation_screen_observations = arguments.validation_screen_observations;
    if config.validation_screen_observations != 0 {
        config.verification_policy = VerificationPolicy::CompleteField;
    }
    config.budgets.max_quads = arguments.max_quads;
    config.budgets.max_candidates = arguments.max_candidates;
    config.budgets.max_hypotheses = arguments.max_hypotheses;
    config.min_matches = arguments.min_matches;
    config.match_tolerance_rad = arcseconds_to_radians(arguments.match_tolerance_arcsec);
    config.seed_tolerance_rad = arcseconds_to_radians(arguments.seed_tolerance_arcsec);
    config.max_chance_probability = arguments.max_chance_probability;
    config.fallback_density_per_steradian = arguments.fallback_density_per_steradian;
    config
}

fn runtime_for_validation_boundary<'a>(
    view: IndexView<'a>,
    level: u16,
    profile: Option<&ProfileCamera<'a>>,
) -> Result<RuntimeIndex<'a>> {
    if let Some(camera) = profile {
        return Ok(camera.production.runtime());
    }
    let level = u8::try_from(level)
        .map_err(|_| Error::InvalidIndex("index level does not fit the runtime ABI".into()))?;
    RuntimeIndex::for_level(view, level)
        .map(RuntimeIndex::without_verification_density_hint)
        .map_err(|error| Error::InvalidIndex(error.to_string()))
}

fn validate_view(
    view: IndexView<'_>,
    arguments: &ValidateSolvesArgs,
    started: Instant,
) -> Result<ValidationSummary> {
    let profile_camera = ProfileCamera::load(view, arguments)?;
    let wide_profile = profile_camera
        .as_ref()
        .is_some_and(|camera| camera.production.is_over_index_wide_field());
    let boundaries = if let Some(camera) = profile_camera.as_ref() {
        vec![Boundary {
            level: u16::from(camera.production.selected_level()),
            fov_deg: camera.production.horizontal_fov_rad().to_degrees(),
            edge: if camera.production.is_over_index_wide_field() {
                BoundaryEdge::WideProfile
            } else {
                BoundaryEdge::LensProfile
            },
        }]
    } else {
        fov_boundaries(view, &arguments.additional_fov_deg)?
    };
    if boundaries.is_empty() {
        return Err(Error::InvalidIndex("index contains no FOV levels".into()));
    }
    let point_count = boundaries
        .len()
        .checked_mul(arguments.pointings_per_boundary as usize)
        .ok_or_else(|| Error::InvalidArgument("too many requested pointings".into()))?;
    let trial_count = point_count
        .checked_mul(arguments.rolls_per_pointing as usize)
        .ok_or_else(|| Error::InvalidArgument("too many requested trials".into()))?;
    if trial_count > 100_000 {
        return Err(Error::InvalidArgument(
            "validation is limited to 100000 trials per invocation".into(),
        ));
    }
    let mut boundary_summaries = boundaries
        .iter()
        .map(|boundary| BoundarySummary {
            level: boundary.level,
            fov_deg: boundary.fov_deg,
            edge: boundary.edge,
            trials: 0,
            correct: 0,
            focal_length_mm: None,
            wire_pixel_scale_arcsec: None,
        })
        .collect::<Vec<_>>();

    let mut correct = 0_u64;
    let mut no_match = 0_u64;
    let mut deadline_no_match = 0_u64;
    let mut ambiguous = 0_u64;
    let mut wrong_attitude = 0_u64;
    let mut insufficient_observations = 0_u64;
    let mut catalog_budget_exhausted = 0_u64;
    let mut solver_errors = 0_u64;
    let mut failures = Vec::new();
    let mut failures_omitted = 0_u64;
    let mut timing_ns = Vec::with_capacity(trial_count);
    let mut attitude_errors_arcsec = Vec::new();
    let mut validation_match_counts = Vec::new();
    let mut quantization_uncertainties_arcsec = Vec::new();
    let mut effective_match_tolerances_arcsec = Vec::new();
    let mut effective_seed_tolerances_arcsec = Vec::new();
    let mut effective_huber_arcsec = Vec::new();
    let mut effective_same_solution_tolerances_arcsec = Vec::new();
    let mut effective_catalog_densities = Vec::new();
    let mut catalog_density_fallback_trials = 0_u64;
    let mut solutions_using_validation_beyond_screen = 0_u64;
    let mut wide_trials_with_nonlocal_brightest_four = 0_u64;
    let mut wide_correct_solutions_after_nonlocal_brightest_four = 0_u64;
    let mut solutions_using_validation_outside_index_fov = 0_u64;
    let mut solutions_using_validation_outside_pattern_edge = 0_u64;
    let mut diagnostics = DiagnosticTotals::default();
    let mut trial_ordinal = 0_usize;

    let solve_config = validation_solve_config(arguments);

    for (boundary_index, boundary) in boundaries.iter().copied().enumerate() {
        let fov_rad = boundary.fov_deg.to_radians();
        let android_camera = if arguments.android_star_pixels {
            let camera = StarCamera::for_horizontal_fov(fov_rad)?;
            let production = ProductionCamera::new(view, camera.lens)
                .map_err(|error| Error::Validation(error.to_string()))?;
            boundary_summaries[boundary_index].focal_length_mm = Some(camera.focal_length_mm);
            boundary_summaries[boundary_index].wire_pixel_scale_arcsec =
                Some(camera.wire_pixel_scale_arcsec());
            Some((camera, production))
        } else {
            None
        };
        let runtime =
            runtime_for_validation_boundary(view, boundary.level, profile_camera.as_ref())?;
        let tangent_x = (0.5 * fov_rad).tan();
        let tangent_y = tangent_x / arguments.aspect_ratio;
        let field_radius_rad = tangent_x.hypot(tangent_y).atan();
        let density_field_radius_rad = profile_camera.as_ref().map_or(field_radius_rad, |camera| {
            camera.production.field_radius_rad()
        });
        let catalog_density_hint = runtime
            .density_hint_per_steradian(UnitVec3::Z, density_field_radius_rad)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        for pointing in 0..arguments.pointings_per_boundary {
            // Give every boundary its own complete equal-area sequence. Slicing
            // one global sequence would put each FOV boundary in a latitude
            // band and could hide scale-dependent failures elsewhere on sky.
            let forward = deterministic_pointing(
                pointing as usize,
                arguments.pointings_per_boundary as usize,
            )?;
            let roll_sequence =
                boundary_index * arguments.pointings_per_boundary as usize + pointing as usize;
            let roll_phase = ((roll_sequence as f64 * 0.381_966_011_250_105_1).fract()) * PI;
            for roll in 0..arguments.rolls_per_pointing {
                boundary_summaries[boundary_index].trials += 1;
                let roll_rad = (roll_phase
                    + PI * f64::from(roll) / f64::from(arguments.rolls_per_pointing))
                    % PI;
                let frame = CameraFrame::new(forward, roll_rad)?;
                let trial_seed =
                    mix64(arguments.seed ^ (trial_ordinal as u64).wrapping_mul(SPLITMIX_GAMMA));
                trial_ordinal += 1;
                let generated_result = if let Some(camera) = profile_camera.as_ref() {
                    generate_profile_field(
                        &runtime,
                        frame,
                        trial_seed,
                        arguments,
                        &solve_config,
                        camera,
                    )
                } else {
                    generate_field(
                        &runtime,
                        frame,
                        tangent_x,
                        tangent_y,
                        field_radius_rad,
                        trial_seed,
                        arguments,
                        &solve_config,
                        android_camera.as_ref().map(|(camera, _)| camera),
                    )
                };
                let generated = match generated_result {
                    Ok(value) => value,
                    Err(error) => {
                        solver_errors += 1;
                        push_failure(
                            &mut failures,
                            &mut failures_omitted,
                            Failure {
                                level: boundary.level,
                                fov_deg: boundary.fov_deg,
                                pointing,
                                roll,
                                observations: 0,
                                kind: "field_generation_error",
                                detail: error.to_string(),
                            },
                        );
                        continue;
                    }
                };
                if generated.catalog_stats.budget_exhausted {
                    catalog_budget_exhausted += 1;
                    push_failure(
                        &mut failures,
                        &mut failures_omitted,
                        Failure {
                            level: boundary.level,
                            fov_deg: boundary.fov_deg,
                            pointing,
                            roll,
                            observations: generated.observations.len(),
                            kind: "catalog_budget_exhausted",
                            detail: format!(
                                "generation scanned {} cells, {} records, {} bytes",
                                generated.catalog_stats.cells_scanned,
                                generated.catalog_stats.records_scanned,
                                generated.catalog_stats.bytes_read
                            ),
                        },
                    );
                    continue;
                }
                if generated.observations.len() < 4 + arguments.min_matches {
                    insufficient_observations += 1;
                    push_failure(
                        &mut failures,
                        &mut failures_omitted,
                        Failure {
                            level: boundary.level,
                            fov_deg: boundary.fov_deg,
                            pointing,
                            roll,
                            observations: generated.observations.len(),
                            kind: "insufficient_observations",
                            detail: format!(
                                "need at least {} retained sources",
                                4 + arguments.min_matches
                            ),
                        },
                    );
                    continue;
                }
                let mut trial_config = solve_config;
                effective_catalog_densities
                    .push(catalog_density_hint.unwrap_or(arguments.fallback_density_per_steradian));
                if catalog_density_hint.is_none() {
                    catalog_density_fallback_trials += 1;
                }
                if let Some(camera) = profile_camera
                    .as_ref()
                    .filter(|camera| camera.production.is_over_index_wide_field())
                {
                    let (adjusted, quantization_uncertainty_rad) = camera
                        .production
                        .solve_config_for_star_pixels(
                            trial_config,
                            generated.pixels.as_deref().ok_or_else(|| {
                                Error::Validation(
                                    "wide-angle validation field has no pixels".into(),
                                )
                            })?,
                        )
                        .map_err(|error| Error::Validation(error.to_string()))?;
                    trial_config = adjusted;
                    quantization_uncertainties_arcsec
                        .push(radians_to_arcseconds(quantization_uncertainty_rad));
                    effective_match_tolerances_arcsec
                        .push(radians_to_arcseconds(trial_config.match_tolerance_rad));
                    effective_seed_tolerances_arcsec
                        .push(radians_to_arcseconds(trial_config.seed_tolerance_rad));
                    effective_huber_arcsec.push(radians_to_arcseconds(trial_config.huber_rad));
                    effective_same_solution_tolerances_arcsec.push(radians_to_arcseconds(
                        trial_config.same_solution_tolerance_rad,
                    ));
                } else if let Some((_, production)) = android_camera.as_ref() {
                    let pixels = generated.pixels.as_deref().ok_or_else(|| {
                        Error::Validation("pixel validation field has no pixels".into())
                    })?;
                    (trial_config, _) = production
                        .solve_config_for_star_pixels(trial_config, pixels)
                        .map_err(|error| Error::Validation(error.to_string()))?;
                }
                let brightest_four_are_nonlocal = profile_camera
                    .as_ref()
                    .filter(|camera| camera.production.is_over_index_wide_field())
                    .is_some_and(|camera| {
                        maximum_pair_separation(&generated.observations[..4])
                            > camera.max_pattern_edge_rad
                    });
                if brightest_four_are_nonlocal {
                    wide_trials_with_nonlocal_brightest_four += 1;
                }

                let solve_started = Instant::now();
                let timeout = Duration::from_millis(arguments.timeout_ms);
                let deadline_expired =
                    || arguments.timeout_ms != 0 && solve_started.elapsed() >= timeout;
                let outcome = if let Some(camera) = profile_camera.as_ref() {
                    runtime
                        .solve_pixels_with_deadline(
                            camera.production.lens(),
                            generated.pixels.as_deref().ok_or_else(|| {
                                Error::Validation(
                                    "wide-angle validation field has no pixels".into(),
                                )
                            })?,
                            PRODUCTION_FOV_SCALE,
                            &trial_config,
                            deadline_expired,
                        )
                        .map_err(|error| error.to_string())
                } else if let Some((_, production)) = android_camera.as_ref() {
                    runtime
                        .solve_pixels_with_deadline(
                            production.lens(),
                            generated.pixels.as_deref().ok_or_else(|| {
                                Error::Validation("pixel validation field has no pixels".into())
                            })?,
                            PRODUCTION_FOV_SCALE,
                            &trial_config,
                            deadline_expired,
                        )
                        .map_err(|error| error.to_string())
                } else {
                    runtime
                        .solve_rays_with_deadline(
                            &generated.observations,
                            field_radius_rad,
                            &trial_config,
                            deadline_expired,
                        )
                        .map_err(|error| error.to_string())
                };
                timing_ns.push(solve_started.elapsed().as_nanos() as u64);
                match outcome {
                    Ok(SolveOutcome::Solved {
                        solution,
                        diagnostics: solve_diagnostics,
                    }) => {
                        diagnostics.add(solve_diagnostics);
                        validation_match_counts.push(solution.matches.len());
                        if solution.matches.iter().any(|matched| {
                            matched.observation_index >= arguments.validation_screen_observations
                        }) {
                            solutions_using_validation_beyond_screen += 1;
                        }
                        let validation_outside_index_fov = profile_camera
                            .as_ref()
                            .filter(|camera| camera.production.is_over_index_wide_field())
                            .is_some_and(|camera| {
                                uses_validation_outside_seed_span(
                                    &solution,
                                    &generated.observations,
                                    (f64::from(camera.production.maximum_index_fov_mdeg())
                                        / 1_000.0)
                                        .to_radians(),
                                )
                            });
                        let validation_outside_pattern_edge = profile_camera
                            .as_ref()
                            .filter(|camera| camera.production.is_over_index_wide_field())
                            .is_some_and(|camera| {
                                uses_validation_outside_seed_span(
                                    &solution,
                                    &generated.observations,
                                    camera.max_pattern_edge_rad,
                                )
                            });
                        let error_arcsec = radians_to_arcseconds(
                            solution.camera_to_world.angle_to(frame.camera_to_world),
                        );
                        attitude_errors_arcsec.push(error_arcsec);
                        if error_arcsec <= arguments.max_attitude_error_arcsec {
                            correct += 1;
                            boundary_summaries[boundary_index].correct += 1;
                            if brightest_four_are_nonlocal {
                                wide_correct_solutions_after_nonlocal_brightest_four += 1;
                            }
                            if validation_outside_index_fov {
                                solutions_using_validation_outside_index_fov += 1;
                            }
                            if validation_outside_pattern_edge {
                                solutions_using_validation_outside_pattern_edge += 1;
                            }
                        } else {
                            wrong_attitude += 1;
                            push_failure(
                                &mut failures,
                                &mut failures_omitted,
                                Failure {
                                    level: boundary.level,
                                    fov_deg: boundary.fov_deg,
                                    pointing,
                                    roll,
                                    observations: generated.observations.len(),
                                    kind: "wrong_attitude",
                                    detail: format!(
                                        "attitude error {error_arcsec:.3} arcsec exceeds {:.3}; \
                                         {} verification matches, {:.3} arcsec RMS, {:.3} arcsec max residual",
                                        arguments.max_attitude_error_arcsec,
                                        solution.matches.len(),
                                        radians_to_arcseconds(solution.rms_residual_rad),
                                        radians_to_arcseconds(solution.max_residual_rad),
                                    ),
                                },
                            );
                        }
                    }
                    Ok(SolveOutcome::NoMatch {
                        reason,
                        diagnostics: value,
                    }) => {
                        diagnostics.add(value);
                        no_match += 1;
                        if matches!(reason, tetra3::core::NoMatchReason::DeadlineExceeded) {
                            deadline_no_match += 1;
                        }
                        push_failure(
                            &mut failures,
                            &mut failures_omitted,
                            Failure {
                                level: boundary.level,
                                fov_deg: boundary.fov_deg,
                                pointing,
                                roll,
                                observations: generated.observations.len(),
                                kind: "no_match",
                                detail: format!("{reason:?}"),
                            },
                        );
                    }
                    Ok(SolveOutcome::Ambiguous {
                        diagnostics: value, ..
                    }) => {
                        diagnostics.add(value);
                        ambiguous += 1;
                        push_failure(
                            &mut failures,
                            &mut failures_omitted,
                            Failure {
                                level: boundary.level,
                                fov_deg: boundary.fov_deg,
                                pointing,
                                roll,
                                observations: generated.observations.len(),
                                kind: "ambiguous",
                                detail: "solver returned two separated comparable attitudes".into(),
                            },
                        );
                    }
                    Err(error) => {
                        solver_errors += 1;
                        push_failure(
                            &mut failures,
                            &mut failures_omitted,
                            Failure {
                                level: boundary.level,
                                fov_deg: boundary.fov_deg,
                                pointing,
                                roll,
                                observations: generated.observations.len(),
                                kind: "solver_error",
                                detail: error.to_string(),
                            },
                        );
                    }
                }
            }
        }
    }

    let trials = trial_count as u64;
    let success_rate = correct as f64 / trials as f64;
    let wide_local_search_exercised =
        !wide_profile || wide_correct_solutions_after_nonlocal_brightest_four > 0;
    let wide_final_validation_exercised =
        !wide_profile || solutions_using_validation_outside_pattern_edge > 0;
    let passed = success_rate >= arguments.minimum_success_rate
        && wide_local_search_exercised
        && wide_final_validation_exercised;
    Ok(ValidationSummary {
        format: "tetra3-solve-validation-v4",
        index: arguments.path.clone(),
        mapped_bytes: view.as_bytes().len(),
        levels: view.level_count(),
        seed: arguments.seed,
        aspect_ratio: profile_camera
            .as_ref()
            .map_or(arguments.aspect_ratio, |camera| {
                let geometry = camera.renderer.lens.camera();
                f64::from(geometry.encoded_width()) / f64::from(geometry.encoded_height())
            }),
        android_star_pixels: arguments.android_star_pixels,
        input_mode: if wide_profile {
            "wide_lens_profile_star_pixels"
        } else if profile_camera.is_some() {
            "lens_profile_star_pixels"
        } else if arguments.android_star_pixels {
            "android_rectilinear_star_pixels"
        } else {
            "camera_rays"
        },
        lens_database: arguments.lens_database.clone(),
        lens_profile_id: arguments.lens_profile_id.clone(),
        lens_projection: profile_camera.as_ref().map(ProfileCamera::projection_name),
        focal_length_mm: arguments.focal_length_mm,
        full_horizontal_fov_deg: profile_camera
            .as_ref()
            .map(|camera| camera.production.horizontal_fov_rad().to_degrees()),
        full_field_radius_deg: profile_camera
            .as_ref()
            .map(|camera| camera.production.field_radius_rad().to_degrees()),
        max_index_horizontal_fov_deg: profile_camera
            .as_ref()
            .map(|camera| f64::from(camera.production.maximum_index_fov_mdeg()) / 1_000.0),
        max_pattern_edge_deg: profile_camera
            .as_ref()
            .map(|camera| camera.max_pattern_edge_rad.to_degrees()),
        additional_fov_deg: arguments.additional_fov_deg.clone(),
        pointings_per_boundary: arguments.pointings_per_boundary,
        rolls_per_pointing: arguments.rolls_per_pointing,
        max_observations: arguments.max_observations,
        validation_screen_observations: arguments.validation_screen_observations,
        timeout_ms: arguments.timeout_ms,
        max_quads: arguments.max_quads,
        max_candidates: arguments.max_candidates,
        max_hypotheses: arguments.max_hypotheses,
        dropout_rate: arguments.dropout_rate,
        centroid_noise_arcsec: arguments.centroid_noise_arcsec,
        false_detections: arguments.false_detections,
        minimum_success_rate: arguments.minimum_success_rate,
        max_attitude_error_arcsec: arguments.max_attitude_error_arcsec,
        fallback_density_per_steradian: arguments.fallback_density_per_steradian,
        effective_catalog_density_per_steradian: summarize_scalars(&effective_catalog_densities),
        catalog_density_fallback_trials,
        quantization_uncertainty_arcsec: summarize_scalars(&quantization_uncertainties_arcsec),
        effective_match_tolerance_arcsec: summarize_scalars(&effective_match_tolerances_arcsec),
        effective_seed_tolerance_arcsec: summarize_scalars(&effective_seed_tolerances_arcsec),
        effective_huber_arcsec: summarize_scalars(&effective_huber_arcsec),
        effective_same_solution_tolerance_arcsec: summarize_scalars(
            &effective_same_solution_tolerances_arcsec,
        ),
        trials,
        correct,
        no_match,
        deadline_no_match,
        ambiguous,
        wrong_attitude,
        insufficient_observations,
        catalog_budget_exhausted,
        solver_errors,
        success_rate,
        passed,
        elapsed_seconds: started.elapsed().as_secs_f64(),
        solve_timing_ms: summarize_timing(&mut timing_ns),
        attitude_error_arcsec: summarize_scalars(&attitude_errors_arcsec),
        validation_matches: summarize_counts(&validation_match_counts),
        solutions_using_validation_beyond_screen,
        wide_trials_with_nonlocal_brightest_four,
        wide_correct_solutions_after_nonlocal_brightest_four,
        solutions_using_validation_outside_index_fov,
        solutions_using_validation_outside_pattern_edge,
        wide_local_search_exercised,
        wide_final_validation_exercised,
        diagnostics,
        boundaries: boundary_summaries,
        failures,
        failures_omitted,
    })
}

#[allow(clippy::too_many_arguments)]
fn generate_field(
    runtime: &RuntimeIndex<'_>,
    frame: CameraFrame,
    tangent_x: f64,
    tangent_y: f64,
    field_radius_rad: f64,
    seed: u64,
    arguments: &ValidateSolvesArgs,
    config: &SolveConfig,
    android_camera: Option<&StarCamera>,
) -> Result<GeneratedField> {
    let mut detected = Vec::<Detected>::with_capacity(arguments.max_observations);
    let noise_sigma = arcseconds_to_radians(arguments.centroid_noise_arcsec);
    let mut random = seed;
    let mut perturbation_failed = false;
    let catalog_stats = runtime
        .visit_cap(
            CatalogQuery {
                center: frame.forward,
                radius_rad: field_radius_rad,
                max_cells_to_scan: config.budgets.max_catalog_cells.min(u64::from(u32::MAX)) as u32,
                max_records_to_scan: config.budgets.max_catalog_records.min(u64::from(u32::MAX))
                    as u32,
                max_bytes_to_read: config.budgets.max_catalog_bytes,
            },
            &mut |star| {
                let camera = match frame.world_to_camera(star.direction) {
                    Ok(value) => value,
                    Err(_) => return ControlFlow::Continue(()),
                };
                let value = camera.as_vec();
                if value.z <= 0.0
                    || (value.x / value.z).abs() > tangent_x
                    || (value.y / value.z).abs() > tangent_y
                {
                    return ControlFlow::Continue(());
                }
                if uniform01(&mut random) < arguments.dropout_rate {
                    return ControlFlow::Continue(());
                }
                let ray = if noise_sigma > 0.0 {
                    match perturb_ray(camera, noise_sigma, &mut random) {
                        Some(value) => value,
                        None => {
                            perturbation_failed = true;
                            return ControlFlow::Break(());
                        }
                    }
                } else {
                    camera
                };
                insert_brightest(
                    &mut detected,
                    Detected {
                        ray,
                        magnitude: star.magnitude,
                        id: star.id.0,
                    },
                    arguments.max_observations,
                );
                ControlFlow::Continue(())
            },
        )
        .map_err(|error| Error::InvalidIndex(error.to_string()))?;
    if perturbation_failed {
        return Err(Error::Validation(
            "centroid-noise perturbation produced invalid geometry".into(),
        ));
    }

    let false_reference_magnitude = detected.first().map_or(0.0, |item| item.magnitude);
    for false_index in 0..arguments.false_detections {
        let x = (2.0 * uniform01(&mut random) - 1.0) * tangent_x;
        let y = (2.0 * uniform01(&mut random) - 1.0) * tangent_y;
        let ray = UnitVec3::new(x, y, 1.0).map_err(|error| Error::Build(error.to_string()))?;
        insert_brightest(
            &mut detected,
            Detected {
                ray,
                magnitude: false_reference_magnitude - 0.25 - false_index as f32 * 0.01,
                id: 0x8000_0000 | false_index.min(0x7fff_ffff) as u32,
            },
            arguments.max_observations,
        );
    }
    let brightest = detected.first().map_or(0.0, |item| item.magnitude);
    let flux = |magnitude: f32| {
        (10.0_f64
            .powf(-0.4 * f64::from(magnitude - brightest))
            .max(1.0e-12)) as f32
    };
    let observations = detected
        .iter()
        .map(|item| Observation {
            ray: item.ray,
            flux: flux(item.magnitude),
        })
        .collect();
    let pixels = android_camera
        .map(|camera| {
            detected
                .iter()
                .map(|item| {
                    Ok(PixelObservation {
                        centroid: camera.encode_solver_centroid(item.ray)?,
                        flux: flux(item.magnitude),
                    })
                })
                .collect::<Result<Vec<_>>>()
        })
        .transpose()?;
    Ok(GeneratedField {
        observations,
        pixels,
        catalog_stats,
    })
}

#[allow(clippy::too_many_arguments)]
fn generate_profile_field(
    runtime: &RuntimeIndex<'_>,
    frame: CameraFrame,
    seed: u64,
    arguments: &ValidateSolvesArgs,
    config: &SolveConfig,
    camera: &ProfileCamera<'_>,
) -> Result<GeneratedField> {
    let mut detected = Vec::<Detected>::with_capacity(arguments.max_observations);
    let noise_sigma = arcseconds_to_radians(arguments.centroid_noise_arcsec);
    let mut random = seed;
    let mut generation_error = None;
    let catalog_stats = runtime
        .visit_cap(
            CatalogQuery {
                center: frame.forward,
                radius_rad: camera.production.field_radius_rad(),
                max_cells_to_scan: config.budgets.max_catalog_cells.min(u64::from(u32::MAX)) as u32,
                max_records_to_scan: config.budgets.max_catalog_records.min(u64::from(u32::MAX))
                    as u32,
                max_bytes_to_read: config.budgets.max_catalog_bytes,
            },
            &mut |star| {
                let camera_ray = match frame.world_to_camera(star.direction) {
                    Ok(value) => value,
                    Err(error) => {
                        generation_error = Some(error);
                        return ControlFlow::Break(());
                    }
                };
                if uniform01(&mut random) < arguments.dropout_rate {
                    return ControlFlow::Continue(());
                }
                let ray = if noise_sigma > 0.0 {
                    match perturb_ray(camera_ray, noise_sigma, &mut random) {
                        Some(value) => value,
                        None => {
                            generation_error = Some(Error::Validation(
                                "centroid-noise perturbation produced invalid geometry".into(),
                            ));
                            return ControlFlow::Break(());
                        }
                    }
                } else {
                    camera_ray
                };
                match camera.render_solver_centroid(ray) {
                    Ok(Some(_)) => insert_brightest(
                        &mut detected,
                        Detected {
                            ray,
                            magnitude: star.magnitude,
                            id: star.id.0,
                        },
                        arguments.max_observations,
                    ),
                    Ok(None) => {}
                    Err(error) => {
                        generation_error = Some(error);
                        return ControlFlow::Break(());
                    }
                }
                ControlFlow::Continue(())
            },
        )
        .map_err(|error| Error::InvalidIndex(error.to_string()))?;
    if let Some(error) = generation_error {
        return Err(error);
    }

    let false_reference_magnitude = detected.first().map_or(0.0, |item| item.magnitude);
    let geometry = camera.renderer.lens.camera();
    for false_index in 0..arguments.false_detections {
        let wire_x = (uniform01(&mut random) * f64::from(geometry.encoded_width())).floor();
        let wire_y = (uniform01(&mut random) * f64::from(geometry.encoded_height())).floor();
        let centroid = Pixel::new(
            wire_x - STAR_WIRE_PIXEL_PHASE,
            wire_y - STAR_WIRE_PIXEL_PHASE,
        );
        let projected = camera
            .renderer
            .lens
            .pixel_to_ray(centroid)
            .map_err(|error| Error::Validation(error.to_string()))?;
        let ray = UnitVec3::new(projected.x, projected.y, projected.z)
            .map_err(|error| Error::Build(error.to_string()))?;
        insert_brightest(
            &mut detected,
            Detected {
                ray,
                magnitude: false_reference_magnitude - 0.25 - false_index as f32 * 0.01,
                id: 0x8000_0000 | false_index.min(0x7fff_ffff) as u32,
            },
            arguments.max_observations,
        );
    }

    let brightest = detected.first().map_or(0.0, |item| item.magnitude);
    let flux = |magnitude: f32| {
        (10.0_f64
            .powf(-0.4 * f64::from(magnitude - brightest))
            .max(1.0e-12)) as f32
    };
    let mut observations = Vec::with_capacity(detected.len());
    let mut pixels = Vec::with_capacity(detected.len());
    for item in detected {
        let centroid = camera.render_solver_centroid(item.ray)?.ok_or_else(|| {
            Error::Validation("retained wide-angle source left the rendered raster".into())
        })?;
        let projected = camera
            .renderer
            .lens
            .pixel_to_ray(Pixel::new(centroid.x, centroid.y))
            .map_err(|error| Error::Validation(error.to_string()))?;
        observations.push(Observation {
            ray: UnitVec3::new(projected.x, projected.y, projected.z)
                .map_err(|error| Error::Build(error.to_string()))?,
            flux: flux(item.magnitude),
        });
        pixels.push(PixelObservation {
            centroid,
            flux: flux(item.magnitude),
        });
    }
    Ok(GeneratedField {
        observations,
        pixels: Some(pixels),
        catalog_stats,
    })
}

fn uses_validation_outside_seed_span(
    solution: &tetra3::core::Solution,
    observations: &[Observation],
    max_index_fov_rad: f64,
) -> bool {
    solution.matches.iter().any(|matched| {
        let Some(validation) = observations.get(matched.observation_index) else {
            return false;
        };
        solution.seed_matches.iter().all(|seed| {
            observations
                .get(seed.observation_index)
                .is_some_and(|pattern| pattern.ray.angle_to(validation.ray) > max_index_fov_rad)
        })
    })
}

fn maximum_pair_separation(observations: &[Observation]) -> f64 {
    let mut maximum = 0.0_f64;
    for left in 0..observations.len() {
        for right in left + 1..observations.len() {
            maximum = maximum.max(observations[left].ray.angle_to(observations[right].ray));
        }
    }
    maximum
}

fn insert_brightest(output: &mut Vec<Detected>, value: Detected, limit: usize) {
    let insertion = output
        .binary_search_by(|existing| {
            existing
                .magnitude
                .total_cmp(&value.magnitude)
                .then(existing.id.cmp(&value.id))
        })
        .unwrap_or_else(|index| index);
    if insertion < limit {
        output.insert(insertion, value);
        if output.len() > limit {
            output.pop();
        }
    }
}

fn perturb_ray(ray: UnitVec3, sigma: f64, state: &mut u64) -> Option<UnitVec3> {
    let reference = if ray.as_vec().z.abs() < 0.9 {
        UnitVec3::Z
    } else {
        UnitVec3::Y
    };
    let tangent_a = reference.cross(ray).normalized().ok()?;
    let tangent_b = ray.cross(tangent_a).normalized().ok()?;
    let radius = (-2.0 * uniform01(state).max(f64::MIN_POSITIVE).ln()).sqrt();
    let angle = TAU * uniform01(state);
    let offset_a = sigma * radius * angle.cos();
    let offset_b = sigma * radius * angle.sin();
    (ray.as_vec() + tangent_a.as_vec() * offset_a + tangent_b.as_vec() * offset_b)
        .normalized()
        .ok()
}

fn fov_boundaries(view: IndexView<'_>, additional_fov_deg: &[f64]) -> Result<Vec<Boundary>> {
    let mut output = Vec::with_capacity(view.level_count() as usize * 2 + additional_fov_deg.len());
    for level in 0..view.level_count() {
        let descriptor = view
            .level_descriptor(level)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        if descriptor.min_fov_mdeg == descriptor.max_fov_mdeg {
            output.push(Boundary {
                level,
                fov_deg: f64::from(descriptor.min_fov_mdeg) / 1_000.0,
                edge: BoundaryEdge::Both,
            });
        } else {
            output.push(Boundary {
                level,
                fov_deg: f64::from(descriptor.min_fov_mdeg) / 1_000.0,
                edge: BoundaryEdge::Minimum,
            });
            output.push(Boundary {
                level,
                fov_deg: f64::from(descriptor.max_fov_mdeg) / 1_000.0,
                edge: BoundaryEdge::Maximum,
            });
        }
    }
    for &fov_deg in additional_fov_deg {
        let mut containing_level = None;
        for level in 0..view.level_count() {
            let descriptor = view
                .level_descriptor(level)
                .map_err(|error| Error::InvalidIndex(error.to_string()))?;
            let minimum = f64::from(descriptor.min_fov_mdeg) / 1_000.0;
            let maximum = f64::from(descriptor.max_fov_mdeg) / 1_000.0;
            if (minimum..=maximum).contains(&fov_deg) {
                containing_level = Some(level);
                break;
            }
        }
        let level = containing_level.ok_or_else(|| {
            Error::InvalidArgument(format!(
                "--additional-fov-deg {fov_deg} is not covered by any index level"
            ))
        })?;
        output.push(Boundary {
            level,
            fov_deg,
            edge: BoundaryEdge::Explicit,
        });
    }
    Ok(output)
}

fn deterministic_pointing(index: usize, count: usize) -> Result<UnitVec3> {
    let z = 1.0 - 2.0 * (index as f64 + 0.5) / count as f64;
    let longitude = TAU * (index as f64 * 0.618_033_988_749_894_9).fract();
    let radius = (1.0 - z * z).max(0.0).sqrt();
    UnitVec3::new(radius * longitude.cos(), radius * longitude.sin(), z)
        .map_err(|error| Error::Build(error.to_string()))
}

fn quat_from_rotation(matrix: [[f64; 3]; 3]) -> Result<Quat> {
    let trace = matrix[0][0] + matrix[1][1] + matrix[2][2];
    let (w, x, y, z) = if trace > 0.0 {
        let scale = (trace + 1.0).sqrt() * 2.0;
        (
            0.25 * scale,
            (matrix[2][1] - matrix[1][2]) / scale,
            (matrix[0][2] - matrix[2][0]) / scale,
            (matrix[1][0] - matrix[0][1]) / scale,
        )
    } else if matrix[0][0] > matrix[1][1] && matrix[0][0] > matrix[2][2] {
        let scale = (1.0 + matrix[0][0] - matrix[1][1] - matrix[2][2]).sqrt() * 2.0;
        (
            (matrix[2][1] - matrix[1][2]) / scale,
            0.25 * scale,
            (matrix[0][1] + matrix[1][0]) / scale,
            (matrix[0][2] + matrix[2][0]) / scale,
        )
    } else if matrix[1][1] > matrix[2][2] {
        let scale = (1.0 + matrix[1][1] - matrix[0][0] - matrix[2][2]).sqrt() * 2.0;
        (
            (matrix[0][2] - matrix[2][0]) / scale,
            (matrix[0][1] + matrix[1][0]) / scale,
            0.25 * scale,
            (matrix[1][2] + matrix[2][1]) / scale,
        )
    } else {
        let scale = (1.0 + matrix[2][2] - matrix[0][0] - matrix[1][1]).sqrt() * 2.0;
        (
            (matrix[1][0] - matrix[0][1]) / scale,
            (matrix[0][2] + matrix[2][0]) / scale,
            (matrix[1][2] + matrix[2][1]) / scale,
            0.25 * scale,
        )
    };
    Quat::new(w, x, y, z).map_err(|error| Error::Build(error.to_string()))
}

fn summarize_timing(values: &mut [u64]) -> TimingSummary {
    values.sort_unstable();
    timing_quantiles_ns(values).map_or_else(TimingSummary::default, |(median, p95, maximum)| {
        TimingSummary {
            median,
            p95,
            maximum,
        }
    })
}

fn summarize_scalars(values: &[f64]) -> ScalarSummary {
    if values.is_empty() {
        return ScalarSummary::default();
    }
    ScalarSummary {
        mean: Some(values.iter().sum::<f64>() / values.len() as f64),
        maximum: values.iter().copied().reduce(f64::max),
    }
}

fn summarize_counts(values: &[usize]) -> CountSummary {
    if values.is_empty() {
        return CountSummary::default();
    }
    CountSummary {
        minimum: values.iter().copied().min(),
        mean: Some(values.iter().sum::<usize>() as f64 / values.len() as f64),
        maximum: values.iter().copied().max(),
    }
}

fn push_failure(failures: &mut Vec<Failure>, omitted: &mut u64, failure: Failure) {
    if failures.len() < MAX_REPORTED_FAILURES {
        failures.push(failure);
    } else {
        *omitted += 1;
    }
}

fn validate_arguments(arguments: &ValidateSolvesArgs) -> Result<()> {
    if !arguments.aspect_ratio.is_finite() || arguments.aspect_ratio <= 0.0 {
        return Err(Error::InvalidArgument(
            "--aspect-ratio must be finite and positive".into(),
        ));
    }
    if arguments.android_star_pixels
        && (arguments.aspect_ratio - f64::from(OM1_NATIVE_WIDTH) / f64::from(OM1_NATIVE_HEIGHT))
            .abs()
            > 1.0e-12
    {
        return Err(Error::InvalidArgument(
            "--android-star-pixels requires the fixed OM-1 4:3 aspect ratio".into(),
        ));
    }
    let profile_argument_count = usize::from(arguments.lens_database.is_some())
        + usize::from(arguments.lens_profile_id.is_some())
        + usize::from(arguments.focal_length_mm.is_some());
    if profile_argument_count != 0 && profile_argument_count != 3 {
        return Err(Error::InvalidArgument(
            "--lens-database, --lens-profile-id, and --focal-length-mm must be supplied together"
                .into(),
        ));
    }
    if profile_argument_count != 0 && arguments.android_star_pixels {
        return Err(Error::InvalidArgument(
            "wide-angle lens-profile validation cannot be combined with --android-star-pixels"
                .into(),
        ));
    }
    if profile_argument_count != 0 && !arguments.additional_fov_deg.is_empty() {
        return Err(Error::InvalidArgument(
            "--additional-fov-deg is not used with a fixed lens profile".into(),
        ));
    }
    if let Some(focal_length_mm) = arguments.focal_length_mm
        && (!focal_length_mm.is_finite() || focal_length_mm <= 0.0)
    {
        return Err(Error::InvalidArgument(
            "--focal-length-mm must be finite and positive".into(),
        ));
    }
    if arguments.additional_fov_deg.len() > 256 {
        return Err(Error::InvalidArgument(
            "at most 256 --additional-fov-deg values may be requested".into(),
        ));
    }
    if arguments
        .additional_fov_deg
        .iter()
        .any(|value| !value.is_finite() || *value <= 0.0 || *value >= 180.0)
    {
        return Err(Error::InvalidArgument(
            "--additional-fov-deg must be finite and in (0, 180)".into(),
        ));
    }
    if arguments.pointings_per_boundary == 0 || arguments.rolls_per_pointing == 0 {
        return Err(Error::InvalidArgument(
            "pointing and roll counts must be positive".into(),
        ));
    }
    if arguments.max_observations < 4 + arguments.min_matches {
        return Err(Error::InvalidArgument(format!(
            "--max-observations must be at least 4 + --min-matches ({})",
            4 + arguments.min_matches
        )));
    }
    if arguments.max_observations > MAX_OBSERVATIONS {
        return Err(Error::InvalidArgument(format!(
            "--max-observations must not exceed {MAX_OBSERVATIONS}"
        )));
    }
    if arguments.validation_screen_observations > arguments.max_observations {
        return Err(Error::InvalidArgument(
            "--validation-screen-observations must not exceed --max-observations".into(),
        ));
    }
    if arguments.validation_screen_observations != 0
        && arguments.validation_screen_observations < 4 + arguments.min_matches
    {
        return Err(Error::InvalidArgument(format!(
            "--validation-screen-observations must be zero or at least 4 + --min-matches ({})",
            4 + arguments.min_matches
        )));
    }
    if arguments.max_quads == 0 || arguments.max_candidates == 0 || arguments.max_hypotheses == 0 {
        return Err(Error::InvalidArgument(
            "solver schedule budgets must be positive".into(),
        ));
    }
    if arguments.false_detections > arguments.max_observations {
        return Err(Error::InvalidArgument(
            "--false-detections must not exceed --max-observations".into(),
        ));
    }
    if !arguments.dropout_rate.is_finite() || !(0.0..1.0).contains(&arguments.dropout_rate) {
        return Err(Error::InvalidArgument(
            "--dropout-rate must be in [0, 1)".into(),
        ));
    }
    for (name, value) in [
        ("--centroid-noise-arcsec", arguments.centroid_noise_arcsec),
        (
            "--max-attitude-error-arcsec",
            arguments.max_attitude_error_arcsec,
        ),
        ("--match-tolerance-arcsec", arguments.match_tolerance_arcsec),
        ("--seed-tolerance-arcsec", arguments.seed_tolerance_arcsec),
    ] {
        if !value.is_finite() || value < 0.0 {
            return Err(Error::InvalidArgument(format!(
                "{name} must be finite and nonnegative"
            )));
        }
        if !arcseconds_to_radians(value).is_finite() || arcseconds_to_radians(value) > PI {
            return Err(Error::InvalidArgument(format!(
                "{name} must not exceed 180 degrees"
            )));
        }
    }
    if arguments.centroid_noise_arcsec > 3_600.0 {
        return Err(Error::InvalidArgument(
            "--centroid-noise-arcsec must not exceed 3600".into(),
        ));
    }
    if arguments.max_attitude_error_arcsec == 0.0
        || arguments.match_tolerance_arcsec == 0.0
        || arguments.seed_tolerance_arcsec == 0.0
    {
        return Err(Error::InvalidArgument(
            "attitude, match, and seed tolerances must be positive".into(),
        ));
    }
    if !arguments.minimum_success_rate.is_finite()
        || !(0.0..=1.0).contains(&arguments.minimum_success_rate)
    {
        return Err(Error::InvalidArgument(
            "--minimum-success-rate must be in [0, 1]".into(),
        ));
    }
    if !arguments.max_chance_probability.is_finite()
        || !(0.0..=1.0).contains(&arguments.max_chance_probability)
        || arguments.max_chance_probability == 0.0
    {
        return Err(Error::InvalidArgument(
            "--max-chance-probability must be in (0, 1]".into(),
        ));
    }
    if !arguments.fallback_density_per_steradian.is_finite()
        || arguments.fallback_density_per_steradian <= 0.0
    {
        return Err(Error::InvalidArgument(
            "--fallback-density-per-steradian must be finite and positive".into(),
        ));
    }
    Ok(())
}

fn uniform01(state: &mut u64) -> f64 {
    *state = mix64(state.wrapping_add(SPLITMIX_GAMMA));
    ((*state >> 11) as f64) * (1.0 / ((1_u64 << 53) as f64))
}

fn arcseconds_to_radians(value: f64) -> f64 {
    value.to_radians() / 3_600.0
}

#[cfg(test)]
mod tests {
    use super::*;
    use tetra3::LensProjector;
    use tetra3::production::{STAR_ENCODED_HEIGHT, STAR_ENCODED_WIDTH, om1_star_camera_geometry};
    use tetra3_lens::{LensProfile, Projection};

    use crate::validation_common::encode_star_wire_axis;

    #[test]
    fn pointings_are_deterministic_and_on_the_unit_sphere() {
        let first = (0..17)
            .map(|index| deterministic_pointing(index, 17).unwrap())
            .collect::<Vec<_>>();
        let second = (0..17)
            .map(|index| deterministic_pointing(index, 17).unwrap())
            .collect::<Vec<_>>();
        assert_eq!(first, second);
        for direction in first {
            assert!((direction.as_vec().norm() - 1.0).abs() < 1.0e-14);
        }
    }

    #[test]
    fn camera_frame_quaternion_matches_its_basis() {
        for index in 0..11 {
            let forward = deterministic_pointing(index, 11).unwrap();
            let frame = CameraFrame::new(forward, index as f64 * 0.19).unwrap();
            for (camera, world) in [
                (UnitVec3::X, frame.right),
                (UnitVec3::Y, frame.down),
                (UnitVec3::Z, frame.forward),
            ] {
                assert!(frame.camera_to_world.rotate_unit(camera).angle_to(world) < 1.0e-14);
                assert!(frame.world_to_camera(world).unwrap().angle_to(camera) < 1.0e-14);
            }
        }
    }

    #[test]
    fn perturbations_and_timing_are_reproducible() {
        let mut left = 42;
        let mut right = 42;
        let first = perturb_ray(UnitVec3::Z, arcseconds_to_radians(5.0), &mut left).unwrap();
        let second = perturb_ray(UnitVec3::Z, arcseconds_to_radians(5.0), &mut right).unwrap();
        assert_eq!(first, second);
        assert!(first.angle_to(UnitVec3::Z) < arcseconds_to_radians(25.0));

        let mut values = [3_000_000, 1_000_000, 4_000_000, 2_000_000];
        let summary = summarize_timing(&mut values);
        assert_eq!(summary.median, 3.0);
        assert_eq!(summary.p95, 4.0);
        assert_eq!(summary.maximum, 4.0);
    }

    #[test]
    fn bounded_brightness_selection_keeps_stable_top_k() {
        let mut output = Vec::new();
        for (id, magnitude) in [(3, 7.0), (2, 3.0), (1, 3.0), (4, 5.0)] {
            insert_brightest(
                &mut output,
                Detected {
                    ray: UnitVec3::Z,
                    magnitude,
                    id,
                },
                3,
            );
        }
        assert_eq!(
            output.iter().map(|item| item.id).collect::<Vec<_>>(),
            [1, 2, 4]
        );
    }

    #[test]
    fn tiny_index_runs_through_real_runtime_and_solver() {
        use tetra3::core::Invariant;
        use tetra3_index::writer::{IndexBuilder, LevelInput, PatternInput, VerificationStarInput};

        let forward = deterministic_pointing(0, 1).unwrap();
        let frame = CameraFrame::new(forward, 0.0).unwrap();
        let camera_rays = [
            (-0.18, -0.11),
            (0.15, -0.13),
            (-0.10, 0.16),
            (0.19, 0.12),
            (-0.03, -0.17),
            (0.05, 0.18),
            (-0.21, 0.02),
            (0.22, -0.01),
            (-0.14, 0.07),
            (0.11, 0.09),
        ]
        .map(|(x, y)| UnitVec3::new(x, y, 1.0).unwrap());
        let mut builder = IndexBuilder::new();
        builder.set_spatial_grid(18, 36).unwrap();
        let verification_ids = camera_rays.map(|camera| {
            let world = frame.camera_to_world.rotate_unit(camera).as_vec();
            builder
                .add_verification_star(VerificationStarInput {
                    direction: [world.x, world.y, world.z],
                    magnitude: 2.0,
                    flags: 0,
                })
                .unwrap()
        });
        let pattern_stars = core::array::from_fn(|index| verification_ids[index]);
        let invariant = Invariant::from_rays(camera_rays[..4].try_into().unwrap()).unwrap();
        builder
            .add_level(LevelInput {
                min_fov_mdeg: 30_000,
                max_fov_mdeg: 30_000,
                default_tolerance_q8: 3,
                neighbor_level_radius: 0,
                max_verification_magnitude_q4: Some(16),
                patterns: vec![
                    PatternInput::from_floats(
                        invariant.ratios(),
                        pattern_stars,
                        invariant.largest_edge_rad(),
                    )
                    .unwrap(),
                ],
            })
            .unwrap();
        let bytes = builder.build().unwrap();
        let view = IndexView::parse(&bytes).unwrap();
        let hinted_runtime = RuntimeIndex::for_level(view, 0).unwrap();
        assert!(
            hinted_runtime
                .density_hint_per_steradian(UnitVec3::Z, 30.0_f64.to_radians())
                .unwrap()
                .is_some(),
            "fixture must carry v1.1 metadata so covered-mode suppression is exercised"
        );
        let cases = fov_boundaries(view, &[30.0]).unwrap();
        assert_eq!(cases.len(), 2);
        assert_eq!(cases[1].level, 0);
        assert_eq!(cases[1].fov_deg, 30.0);
        assert!(matches!(cases[1].edge, BoundaryEdge::Explicit));
        let arguments = ValidateSolvesArgs {
            path: PathBuf::from("tiny.t3ix"),
            aspect_ratio: 4.0 / 3.0,
            additional_fov_deg: vec![30.0],
            android_star_pixels: false,
            lens_database: None,
            lens_profile_id: None,
            focal_length_mm: None,
            pointings_per_boundary: 1,
            rolls_per_pointing: 1,
            max_observations: 32,
            validation_screen_observations: 8,
            timeout_ms: 0,
            max_quads: 512,
            max_candidates: 8_192,
            max_hypotheses: 32_768,
            dropout_rate: 0.0,
            centroid_noise_arcsec: 0.0,
            false_detections: 0,
            minimum_success_rate: 1.0,
            max_attitude_error_arcsec: 60.0,
            min_matches: 4,
            match_tolerance_arcsec: 75.0,
            seed_tolerance_arcsec: 90.0,
            max_chance_probability: 1.0,
            fallback_density_per_steradian: 250_000.0,
            seed: DEFAULT_SEED,
            json: true,
        };
        let summary = validate_view(view, &arguments, Instant::now()).unwrap();
        assert_eq!(summary.trials, 2);
        assert_eq!(summary.correct, 2, "{:?}", summary.failures);
        assert_eq!(summary.additional_fov_deg, [30.0]);
        assert!(matches!(summary.boundaries[1].edge, BoundaryEdge::Explicit));
        assert_eq!(summary.boundaries[1].fov_deg, 30.0);
        assert_eq!(summary.validation_matches.minimum, Some(4));
        assert_eq!(summary.validation_matches.maximum, Some(6));
        assert_eq!(summary.solutions_using_validation_beyond_screen, 1);
        assert_eq!(summary.catalog_density_fallback_trials, 2);
        assert_eq!(
            summary.effective_catalog_density_per_steradian.mean,
            Some(arguments.fallback_density_per_steradian)
        );
        assert!(summary.passed);

        let observations = camera_rays
            .iter()
            .enumerate()
            .map(|(index, &ray)| Observation {
                ray,
                flux: (camera_rays.len() - index) as f32,
            })
            .collect::<Vec<_>>();
        let runtime = RuntimeIndex::for_level(view, 0).unwrap();
        let mut deadline_config = SolveConfig::default();
        deadline_config.budgets.max_observations = observations.len();
        deadline_config.validation_screen_observations = 8;
        deadline_config.max_chance_probability = 1.0;
        let deadline_outcome = runtime
            .solve_rays_with_deadline(
                &observations,
                30.0_f64.to_radians(),
                &deadline_config,
                || true,
            )
            .unwrap();
        match deadline_outcome {
            SolveOutcome::NoMatch {
                reason: tetra3::core::NoMatchReason::DeadlineExceeded,
                diagnostics,
            } => {
                assert!(diagnostics.deadline_exceeded);
                assert!(!diagnostics.budget_exhausted);
                assert_eq!(diagnostics.quads_tested, 0);
            }
            other => panic!("expected immediate deadline, got {other:?}"),
        }
    }

    #[test]
    fn solver_schedule_arguments_parse_and_reject_zero() {
        use clap::Parser;

        #[derive(Parser)]
        struct TestCommand {
            #[command(flatten)]
            arguments: ValidateSolvesArgs,
        }

        let defaults = TestCommand::try_parse_from(["test", "index.t3ix"])
            .unwrap()
            .arguments;
        let production_config = shared_production_config();
        assert_eq!(
            defaults.max_observations,
            production_config.budgets.max_observations
        );
        assert_eq!(
            defaults.validation_screen_observations,
            production_config.validation_screen_observations
        );
        assert_eq!(defaults.timeout_ms, 1_000);
        assert_eq!(defaults.max_quads, production_config.budgets.max_quads);
        assert_eq!(
            defaults.max_candidates,
            production_config.budgets.max_candidates
        );
        assert_eq!(
            defaults.max_hypotheses,
            production_config.budgets.max_hypotheses
        );
        assert!(defaults.lens_database.is_none());
        assert!(defaults.lens_profile_id.is_none());
        assert!(defaults.focal_length_mm.is_none());
        let validation_config = validation_solve_config(&defaults);
        // Debug covers every field while SolveConfig deliberately has no
        // semantic PartialEq implementation.
        assert_eq!(
            format!("{validation_config:?}"),
            format!("{production_config:?}")
        );

        let parsed = TestCommand::try_parse_from([
            "test",
            "index.t3ix",
            "--max-quads",
            "4096",
            "--max-candidates",
            "16384",
            "--max-hypotheses",
            "65536",
            "--max-observations",
            "256",
            "--validation-screen-observations",
            "32",
            "--timeout-ms",
            "1000",
            "--additional-fov-deg",
            "3.2",
            "--additional-fov-deg",
            "3.2663496137060335",
        ])
        .unwrap()
        .arguments;
        assert_eq!(parsed.max_quads, 4_096);
        assert_eq!(parsed.max_candidates, 16_384);
        assert_eq!(parsed.max_hypotheses, 65_536);
        assert_eq!(parsed.max_observations, MAX_OBSERVATIONS);
        assert_eq!(parsed.validation_screen_observations, 32);
        assert_eq!(parsed.timeout_ms, 1_000);
        assert_eq!(parsed.additional_fov_deg, [3.2, 3.266_349_613_706_033_5]);
        assert!(!parsed.android_star_pixels);

        let mut invalid = parsed;
        invalid.max_quads = 0;
        assert!(validate_arguments(&invalid).is_err());
        invalid.max_quads = 4_096;
        invalid.centroid_noise_arcsec = 1.0e308;
        assert!(validate_arguments(&invalid).is_err());
        invalid.centroid_noise_arcsec = 0.0;
        invalid.additional_fov_deg = vec![f64::NAN];
        assert!(validate_arguments(&invalid).is_err());
        invalid.additional_fov_deg.clear();
        invalid.validation_screen_observations = 257;
        assert!(validate_arguments(&invalid).is_err());
        invalid.validation_screen_observations = 7;
        assert!(validate_arguments(&invalid).is_err());
        invalid.validation_screen_observations = 0;
        assert!(validate_arguments(&invalid).is_ok());

        let wide = TestCommand::try_parse_from([
            "test",
            "index.t3ix",
            "--lens-database",
            "om1-all-mft.t3lf",
            "--lens-profile-id",
            "lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro",
            "--focal-length-mm",
            "8",
        ])
        .unwrap()
        .arguments;
        assert_eq!(
            wide.lens_database.as_deref(),
            Some(std::path::Path::new("om1-all-mft.t3lf"))
        );
        assert_eq!(wide.focal_length_mm, Some(8.0));
        assert!(validate_arguments(&wide).is_ok());

        let mut incomplete_wide = wide;
        incomplete_wide.focal_length_mm = None;
        assert!(validate_arguments(&incomplete_wide).is_err());
    }

    #[test]
    fn android_star_wire_encoding_matches_camera_worker_integer_path() {
        assert_eq!(encode_star_wire_axis(0.5, 5_184, 1_296).unwrap(), 0);
        assert_eq!(encode_star_wire_axis(3.5, 5_184, 1_296).unwrap(), 1);
        assert_eq!(encode_star_wire_axis(4.5, 5_184, 1_296).unwrap(), 1);
        assert_eq!(encode_star_wire_axis(5_183.0, 5_184, 1_296).unwrap(), 1_295);
        assert_eq!(encode_star_wire_axis(-0.5, 5_184, 1_296).unwrap(), 0);
        assert_eq!(encode_star_wire_axis(5_183.5, 5_184, 1_296).unwrap(), 1_295);
        assert!(encode_star_wire_axis(f64::NAN, 5_184, 1_296).is_err());
    }

    #[test]
    fn android_star_camera_uses_direct_rectilinear_fallback() {
        let camera = StarCamera::for_horizontal_fov(45.0_f64.to_radians()).unwrap();
        let expected_focal = 17.1072 / (2.0 * 22.5_f64.to_radians().tan());
        assert!((camera.focal_length_mm - expected_focal).abs() < 1.0e-12);
        assert_eq!(
            camera.encode_wire_centroid(UnitVec3::Z).unwrap(),
            (648, 486)
        );
        assert_eq!(
            camera.encode_solver_centroid(UnitVec3::Z).unwrap(),
            PixelCentroid {
                x: 647.625,
                y: 485.625
            }
        );
        assert!(camera.wire_pixel_scale_arcsec() > 130.0);
        assert!(camera.wire_pixel_scale_arcsec() < 133.0);
    }

    #[test]
    fn overlapping_boundary_forces_requested_validation_level() {
        use tetra3_index::writer::{IndexBuilder, LevelInput};

        let mut builder = IndexBuilder::new();
        for (minimum, maximum) in [(10_000, 30_000), (30_000, 45_000)] {
            builder
                .add_level(LevelInput {
                    min_fov_mdeg: minimum,
                    max_fov_mdeg: maximum,
                    default_tolerance_q8: 3,
                    neighbor_level_radius: 0,
                    max_verification_magnitude_q4: None,
                    patterns: Vec::new(),
                })
                .unwrap();
        }
        let bytes = builder.build().unwrap();
        let view = IndexView::parse(&bytes).unwrap();
        let star = StarCamera::for_horizontal_fov(30.0_f64.to_radians()).unwrap();
        let production = ProductionCamera::new(view, star.lens).unwrap();
        assert_eq!(production.horizontal_fov_mdeg(), 30_000);
        assert_eq!(production.selected_level(), 0);

        let validation = runtime_for_validation_boundary(view, 1, None).unwrap();
        assert_eq!(validation.selected_level(), Some(1));
        assert!(validation.verification_star_count_hint().is_none());
    }

    fn olympus_8mm_fisheye_profile() -> LensProfile {
        use tetra3_lens::{CalibrationGeometry, DistortionKnot, DistortionModel, ProfileId};

        LensProfile::new(
            ProfileId::new(
                "lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro",
            )
            .unwrap(),
            om1_star_camera_geometry(),
            CalibrationGeometry::new(2.0, 4.0 / 3.0, 0.0, 0.0).unwrap(),
            Projection::FisheyeEquisolid,
            vec![
                DistortionKnot::new(
                    8.0,
                    8.042_48,
                    DistortionModel::Poly3 { k1: -0.00531 },
                )
                .unwrap(),
            ],
        )
        .unwrap()
    }

    fn wide_test_index() -> Vec<u8> {
        use tetra3::core::Invariant;
        use tetra3_index::writer::{IndexBuilder, LevelInput, PatternInput, VerificationStarInput};

        let rays = [
            UnitVec3::new(-0.1, -0.1, 1.0).unwrap(),
            UnitVec3::new(0.1, -0.1, 1.0).unwrap(),
            UnitVec3::new(-0.1, 0.1, 1.0).unwrap(),
            UnitVec3::new(0.1, 0.1, 1.0).unwrap(),
        ];
        let mut builder = IndexBuilder::new();
        let verification = rays.map(|ray| {
            let value = ray.as_vec();
            builder
                .add_verification_star(VerificationStarInput {
                    direction: [value.x, value.y, value.z],
                    magnitude: 2.0,
                    flags: 0,
                })
                .unwrap()
        });
        let stars = verification;
        let invariant = Invariant::from_rays(rays).unwrap();
        builder
            .add_level(LevelInput {
                min_fov_mdeg: 1_000,
                max_fov_mdeg: 45_000,
                default_tolerance_q8: 3,
                neighbor_level_radius: 0,
                max_verification_magnitude_q4: Some(16),
                patterns: vec![
                    PatternInput::from_floats(
                        invariant.ratios(),
                        stars,
                        invariant.largest_edge_rad(),
                    )
                    .unwrap(),
                ],
            })
            .unwrap();
        builder.build().unwrap()
    }

    #[test]
    fn production_8mm_profile_renderer_round_trips_full_fisheye() {
        let profile = olympus_8mm_fisheye_profile();
        let lens = profile.prepare(8.0).unwrap();
        let projector = LensProjector::new(&lens);
        let center_y = (f64::from(STAR_ENCODED_HEIGHT) - 1.0) * 0.5;
        let left = projector
            .pixel_to_ray(
                PixelCentroid {
                    x: -0.5,
                    y: center_y,
                },
                1.0,
            )
            .unwrap();
        let right = projector
            .pixel_to_ray(
                PixelCentroid {
                    x: f64::from(STAR_ENCODED_WIDTH) - 0.5,
                    y: center_y,
                },
                1.0,
            )
            .unwrap();
        let horizontal_fov_rad = left.angle_to(right);
        let field_radius_rad = projector.field_radius_rad(1.0).unwrap();
        assert!((horizontal_fov_rad.to_degrees() - 131.584_924).abs() < 1.0e-5);
        assert!((field_radius_rad.to_degrees() - 86.107_520).abs() < 1.0e-5);

        let index = wide_test_index();
        let production = ProductionCamera::new(IndexView::parse(&index).unwrap(), lens).unwrap();
        let camera = ProfileCamera {
            renderer: ProfileRenderer::new(profile, lens),
            production,
            max_pattern_edge_rad: 54.31_f64.to_radians(),
        };
        assert_eq!(camera.projection_name(), "fisheye_equisolid");
        for pixel in [
            Pixel::new(647.625, 485.625),
            Pixel::new(12.625, 20.625),
            Pixel::new(1_280.625, 940.625),
            Pixel::new(1_000.625, 300.625),
        ] {
            let ray = camera.renderer.lens.pixel_to_ray(pixel).unwrap();
            let ray = UnitVec3::new(ray.x, ray.y, ray.z).unwrap();
            let rendered = camera.render_solver_centroid(ray).unwrap().unwrap();
            let round_trip = camera
                .renderer
                .lens
                .pixel_to_ray(Pixel::new(rendered.x, rendered.y))
                .unwrap();
            let round_trip = UnitVec3::new(round_trip.x, round_trip.y, round_trip.z).unwrap();
            assert!(
                ray.angle_to(round_trip) < 0.001,
                "{pixel:?} -> {rendered:?}"
            );
        }

        let bottom_right = PixelObservation {
            centroid: PixelCentroid {
                x: f64::from(STAR_ENCODED_WIDTH - 1) - STAR_WIRE_PIXEL_PHASE,
                y: f64::from(STAR_ENCODED_HEIGHT - 1) - STAR_WIRE_PIXEL_PHASE,
            },
            flux: 1.0,
        };
        let (_, quantization_rad) = camera
            .production
            .solve_config_for_star_pixels(SolveConfig::default(), &[bottom_right])
            .unwrap();
        let quantization_arcsec = radians_to_arcseconds(quantization_rad);
        assert!(
            (500.0..530.0).contains(&quantization_arcsec),
            "{quantization_arcsec}"
        );
    }

    #[test]
    fn wide_final_validation_metric_requires_a_match_outside_seed_span() {
        use tetra3::core::{Match, Solution, StarId};

        let ray = |degrees: f64| {
            let radians = degrees.to_radians();
            UnitVec3::new(radians.sin(), 0.0, radians.cos()).unwrap()
        };
        let observations = [0.0, 5.0, 10.0, 15.0, 70.0]
            .into_iter()
            .map(|degrees| Observation {
                ray: ray(degrees),
                flux: 1.0,
            })
            .collect::<Vec<_>>();
        let seed_matches = core::array::from_fn(|index| Match {
            observation_index: index,
            star_id: StarId(index as u32),
            catalog_direction: observations[index].ray,
            residual_rad: 0.0,
        });
        let solution = Solution {
            camera_to_world: Quat::identity(),
            fov_scale: 1.0,
            rectilinear_radial_coefficient: 0.0,
            rectilinear_radial_reference_rad: 0.0,
            seed_matches,
            matches: vec![Match {
                observation_index: 4,
                star_id: StarId(4),
                catalog_direction: observations[4].ray,
                residual_rad: 0.0,
            }],
            rms_residual_rad: 0.0,
            max_residual_rad: 0.0,
            raw_chance_probability: 0.0,
            chance_probability: 0.0,
        };
        assert!(uses_validation_outside_seed_span(
            &solution,
            &observations,
            45.0_f64.to_radians()
        ));
        assert!(!uses_validation_outside_seed_span(
            &solution,
            &observations,
            90.0_f64.to_radians()
        ));
    }

    #[test]
    fn fisheye_pixel_solve_skips_nonlocal_brightest_four_and_validates_full_field() {
        use tetra3::core::Invariant;
        use tetra3_index::writer::{IndexBuilder, LevelInput, PatternInput, VerificationStarInput};

        let profile = olympus_8mm_fisheye_profile();
        let lens = profile.prepare(8.0).unwrap();
        let index = wide_test_index();
        let production = ProductionCamera::new(IndexView::parse(&index).unwrap(), lens).unwrap();
        let camera = ProfileCamera {
            renderer: ProfileRenderer::new(profile, lens),
            production,
            max_pattern_edge_rad: 55.0_f64.to_radians(),
        };

        // The first four sources occupy opposite parts of the ~132-degree
        // image. The actual indexed seed appears later in brightness order on
        // the left, while several held-out validation stars are on the right.
        let wire_pixels = [
            (60.0, 60.0),
            (1_235.0, 70.0),
            (70.0, 900.0),
            (1_225.0, 905.0),
            (220.0, 410.0),
            (300.0, 400.0),
            (235.0, 500.0),
            (320.0, 520.0),
            (1_040.0, 390.0),
            (1_100.0, 440.0),
            (1_020.0, 520.0),
            (1_115.0, 560.0),
            (650.0, 250.0),
            (700.0, 700.0),
            (520.0, 500.0),
            (800.0, 480.0),
        ];
        let pixels = wire_pixels
            .iter()
            .enumerate()
            .map(|(index, &(x, y))| PixelObservation {
                centroid: PixelCentroid {
                    x: x - STAR_WIRE_PIXEL_PHASE,
                    y: y - STAR_WIRE_PIXEL_PHASE,
                },
                flux: (wire_pixels.len() - index) as f32,
            })
            .collect::<Vec<_>>();
        let observations = pixels
            .iter()
            .map(|observation| {
                let ray = camera
                    .renderer
                    .lens
                    .pixel_to_ray(Pixel::new(observation.centroid.x, observation.centroid.y))
                    .unwrap();
                Observation {
                    ray: UnitVec3::new(ray.x, ray.y, ray.z).unwrap(),
                    flux: observation.flux,
                }
            })
            .collect::<Vec<_>>();
        assert!(
            maximum_pair_separation(&observations[..4])
                > (f64::from(camera.production.maximum_index_fov_mdeg()) / 1_000.0).to_radians()
        );
        assert!(maximum_pair_separation(&observations[4..8]) < 25.0_f64.to_radians());

        let mut builder = IndexBuilder::new();
        builder.set_spatial_grid(18, 36).unwrap();
        let verification_ids = observations
            .iter()
            .map(|observation| {
                let direction = observation.ray.as_vec();
                builder
                    .add_verification_star(VerificationStarInput {
                        direction: [direction.x, direction.y, direction.z],
                        magnitude: 2.0,
                        flags: 0,
                    })
                    .unwrap()
            })
            .collect::<Vec<_>>();
        let local_pattern_stars = core::array::from_fn(|offset| verification_ids[4 + offset]);
        let local_invariant = Invariant::from_rays(
            observations[4..8]
                .iter()
                .map(|item| item.ray)
                .collect::<Vec<_>>()
                .try_into()
                .unwrap(),
        )
        .unwrap();

        // A second, unobserved pattern gives the fixture a production-like
        // stored edge near the 45-degree level's diagonal coverage.
        let dummy_rays = [
            spherical_about_negative_z(26.0, 0.0),
            spherical_about_negative_z(26.0, 180.0),
            spherical_about_negative_z(18.0, 70.0),
            spherical_about_negative_z(22.0, 245.0),
        ];
        let dummy_verification = dummy_rays.map(|ray| {
            let direction = ray.as_vec();
            builder
                .add_verification_star(VerificationStarInput {
                    direction: [direction.x, direction.y, direction.z],
                    magnitude: 2.0,
                    flags: 0,
                })
                .unwrap()
        });
        let dummy_pattern_stars = dummy_verification;
        let dummy_invariant = Invariant::from_rays(dummy_rays).unwrap();
        assert!(dummy_invariant.largest_edge_rad() > 50.0_f64.to_radians());
        builder
            .add_level(LevelInput {
                min_fov_mdeg: 37_257,
                max_fov_mdeg: 45_000,
                default_tolerance_q8: 3,
                neighbor_level_radius: 0,
                max_verification_magnitude_q4: Some(22),
                patterns: vec![
                    PatternInput::from_floats(
                        local_invariant.ratios(),
                        local_pattern_stars,
                        local_invariant.largest_edge_rad(),
                    )
                    .unwrap(),
                    PatternInput::from_floats(
                        dummy_invariant.ratios(),
                        dummy_pattern_stars,
                        dummy_invariant.largest_edge_rad(),
                    )
                    .unwrap(),
                ],
            })
            .unwrap();
        let bytes = builder.build().unwrap();
        let view = IndexView::parse(&bytes).unwrap();
        let runtime = RuntimeIndex::for_level(view, 0).unwrap();
        let expanded_edge = runtime
            .maximum_observed_pattern_edge_rad(
                SolveConfig::default().largest_edge_relative_tolerance,
            )
            .unwrap()
            .unwrap();
        assert!(maximum_pair_separation(&observations[..4]) > expanded_edge);

        let mut config = SolveConfig::default();
        config.budgets.max_observations = observations.len();
        config.budgets.max_quads = u32::MAX;
        config.budgets.max_candidates = u32::MAX;
        config.budgets.max_hypotheses = u32::MAX;
        config.budgets.max_index_records = u64::MAX;
        config.budgets.max_index_bytes = u64::MAX;
        config.budgets.max_catalog_cells = u64::MAX;
        config.budgets.max_catalog_records = u64::MAX;
        config.budgets.max_catalog_bytes = u64::MAX;
        config.budgets.max_verification_pairs = usize::MAX;
        config.validation_screen_observations = 8;
        config.min_matches = 4;
        config.max_chance_probability = 1.0;
        let outcome = runtime
            .solve_pixels(&camera.renderer.lens, &pixels, 1.0, &config)
            .unwrap();
        let SolveOutcome::Solved { solution, .. } = outcome else {
            panic!("expected wide fisheye solve, got {outcome:?}");
        };
        assert!(
            solution
                .seed_matches
                .iter()
                .all(|matched| (4..8).contains(&matched.observation_index))
        );
        assert!(uses_validation_outside_seed_span(
            &solution,
            &observations,
            45.0_f64.to_radians(),
        ));
        assert!(solution.matches.len() >= 8);
    }

    fn spherical_about_negative_z(radius_deg: f64, azimuth_deg: f64) -> UnitVec3 {
        let radius = radius_deg.to_radians();
        let azimuth = azimuth_deg.to_radians();
        UnitVec3::new(
            radius.sin() * azimuth.cos(),
            radius.sin() * azimuth.sin(),
            -radius.cos(),
        )
        .unwrap()
    }
}
