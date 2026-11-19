//! Deterministic operational false-positive Monte Carlo for the production solver.

use std::collections::{BTreeSet, HashSet};
use std::f64::consts::{PI, TAU};
use std::path::Path;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use clap::{Args, ValueEnum};
use rayon::prelude::*;
use serde::Serialize;
use tetra3::core::{
    Diagnostics, NoMatchReason, PixelCentroid, PixelObservation, SolveConfig, SolveOutcome,
    StarCatalog, UnitVec3,
};
use tetra3::production::{
    ProductionCamera, STAR_ENCODED_HEIGHT, STAR_ENCODED_WIDTH, STAR_WIRE_PIXEL_PHASE,
    production_solve_config as shared_production_config,
};
use tetra3_index::IndexView;

use crate::io_utils::write_json_atomic;
use crate::rng::{SPLITMIX_GAMMA, SplitMix64, mix64};
use crate::validation_common::{
    ProfileRenderer, StarCamera, fov_to_mdeg, open_mapped, projection_name, radians_to_arcseconds,
    timing_quantiles_ns,
};
use crate::{Error, Result};

const DEFAULT_SEED: u64 = 0x4f4d_312d_4650_5221;
const CONFIDENCE_ALPHA: f64 = 0.05;
const MAX_EXAMPLES: usize = 16;

/// Null distribution used to create false detections.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, ValueEnum)]
#[serde(rename_all = "snake_case")]
pub enum RandomDistribution {
    /// IID uniform solid-angle directions conditioned on the camera footprint,
    /// then forward-rendered and quantized onto unique STAR pixels.
    Sky,
    /// Unique integer STAR pixels sampled uniformly over the delivered raster.
    Pixels,
    /// Run an independent campaign for each of the two distributions.
    Both,
}

/// Options for the production random-field false-positive audit.
#[derive(Clone, Debug, Args)]
pub struct ValidateFprArgs {
    /// mmap-backed production .t3ix file.
    pub path: PathBuf,
    /// Independent random fields per FOV and distribution.
    #[arg(long, default_value_t = 10)]
    pub trials_per_fov: u64,
    /// First trial ordinal, for deterministic disjoint shards.
    #[arg(long, default_value_t = 0)]
    pub start_trial: u64,
    /// Random-field model. `both` doubles the number of solves.
    #[arg(long, value_enum, default_value_t = RandomDistribution::Sky)]
    pub distribution: RandomDistribution,
    /// Test only these horizontal FOVs instead of the default boundary/center matrix.
    #[arg(long)]
    pub fov_deg: Vec<f64>,
    /// Number of retained sources in each random field; repeat for strata.
    /// When omitted, the shared production observation maximum is tested.
    #[arg(long)]
    pub observations: Vec<usize>,
    /// Exact compiled lens database; all three lens options must be supplied together.
    #[arg(long)]
    pub lens_database: Option<PathBuf>,
    /// Exact, case-sensitive profile ID in --lens-database.
    #[arg(long)]
    pub lens_profile_id: Option<String>,
    /// Reported focal length used to prepare --lens-profile-id.
    #[arg(long)]
    pub focal_length_mm: Option<f64>,
    /// Cooperative production solve deadline in milliseconds; zero disables it.
    #[arg(long, default_value_t = 1_000)]
    pub timeout_ms: u64,
    /// Worker threads. One avoids deadline distortion from CPU/cache contention;
    /// zero explicitly selects host parallelism.
    #[arg(long, default_value_t = 1)]
    pub threads: usize,
    /// Optional fixed-alpha=1 positive-control fields per case. These are
    /// reported separately and never enter the production FPR estimate.
    #[arg(long, default_value_t = 0)]
    pub positive_control_trials_per_fov: u64,
    /// Reproducible counter-key seed.
    #[arg(long, default_value_t = DEFAULT_SEED)]
    pub seed: u64,
    /// Atomically write the complete pretty-JSON report to this path.
    #[arg(long)]
    pub output: Option<PathBuf>,
    #[arg(long)]
    pub json: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
enum CaseKind {
    LevelBoundary,
    LevelGeometricCenter,
    Explicit,
    LensProfile,
}

#[derive(Clone, Debug)]
struct CaseSpec {
    kind: CaseKind,
    requested_fov_deg: f64,
    routing_fov_mdeg: u32,
    nominal_level: Option<u16>,
}

#[derive(Clone)]
enum ForwardModel {
    Rectilinear(StarCamera),
    Profile(ProfileRenderer),
}

#[derive(Clone)]
struct CameraCase<'a> {
    spec: CaseSpec,
    camera: ProductionCamera<'a>,
    forward: ForwardModel,
    projection: &'static str,
}

#[derive(Clone, Copy, Debug)]
enum Mode {
    Production,
    PositiveControl,
}

#[derive(Clone, Copy, Debug)]
enum TrialClass {
    Solved {
        matches: usize,
        chance_probability: f64,
    },
    Ambiguous {
        best_matches: usize,
        best_chance_probability: f64,
        runner_up_matches: usize,
        runner_up_chance_probability: f64,
    },
    NoMatch(NoMatchReason),
    Error,
}

#[derive(Debug)]
struct TrialResult {
    ordinal: u64,
    class: TrialClass,
    diagnostics: Diagnostics,
    solve_ns: Option<u64>,
    quantization_uncertainty_arcsec: f64,
    effective_match_tolerance_arcsec: f64,
    random_match_probability: f64,
    error: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
struct EventExample {
    trial_ordinal: u64,
    kind: &'static str,
    validation_matches: Option<usize>,
    chance_probability: Option<f64>,
    detail: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct NoMatchCounts {
    insufficient_observations: u64,
    search_exhausted: u64,
    rejected_by_verification: u64,
    budget_exhausted: u64,
    deadline_exceeded: u64,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct MetricSummary {
    total: u64,
    mean: f64,
    maximum: u64,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct TimingSummary {
    median_ms: f64,
    p95_ms: f64,
    maximum_ms: f64,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct ScalarSummary {
    mean: f64,
    maximum: f64,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct WorkSummary {
    quads_tested: MetricSummary,
    candidates_tested: MetricSummary,
    hypotheses_tested: MetricSummary,
    index_records_scanned: MetricSummary,
    verification_pairs: MetricSummary,
    catalog_records_scanned: MetricSummary,
}

#[derive(Debug, Serialize)]
struct CaseReport {
    case_kind: CaseKind,
    distribution: RandomDistribution,
    observations: usize,
    requested_horizontal_fov_deg: f64,
    actual_horizontal_fov_deg: f64,
    full_field_radius_deg: f64,
    routing_fov_mdeg: u32,
    nominal_level: Option<u16>,
    selected_level: u8,
    over_index_wide_field: bool,
    projection: &'static str,
    verification_magnitude_limit_q4: Option<i8>,
    selected_verification_star_count: u32,
    density_hint_star_count: Option<u32>,
    catalog_density_source: &'static str,
    effective_catalog_density_per_steradian: f64,
    quantization_uncertainty_arcsec: ScalarSummary,
    effective_match_tolerance_arcsec: ScalarSummary,
    single_observation_random_match_probability: ScalarSummary,
    conservative_null_mean_validation_matches: ScalarSummary,
    trials: u64,
    false_accepts: u64,
    ambiguous: u64,
    gate_crossing_trials: u64,
    no_match: NoMatchCounts,
    solver_errors: u64,
    deadline_flagged_trials: u64,
    budget_flagged_trials: u64,
    false_accept_rate: f64,
    false_accept_upper_bound_95: Option<f64>,
    false_accept_simultaneous_upper_bound_95: Option<f64>,
    gate_crossing_trial_rate: f64,
    gate_crossing_trial_upper_bound_95: Option<f64>,
    solve_timing: TimingSummary,
    work: WorkSummary,
    examples: Vec<EventExample>,
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
struct AggregateReport {
    trials: u64,
    false_accepts: u64,
    ambiguous: u64,
    gate_crossing_trials: u64,
    solver_errors: u64,
    deadline_flagged_trials: u64,
    budget_flagged_trials: u64,
    false_accept_rate: f64,
    false_accept_upper_bound_95_if_pooled: Option<f64>,
    gate_crossing_trial_rate: f64,
    gate_crossing_trial_upper_bound_95_if_pooled: Option<f64>,
    maximum_case_false_accept_upper_bound_95: Option<f64>,
    maximum_case_simultaneous_false_accept_upper_bound_95: Option<f64>,
    quads_tested: u64,
    candidates_tested: u64,
    hypotheses_tested: u64,
}

#[derive(Debug, Serialize)]
struct FprReport {
    format: &'static str,
    index: PathBuf,
    mapped_bytes: usize,
    levels: u16,
    seed: u64,
    start_trial: u64,
    trials_per_fov: u64,
    observation_counts: Vec<usize>,
    validation_screen_observations: usize,
    timeout_ms: u64,
    worker_threads: usize,
    max_chance_probability: f64,
    fallback_density_per_steradian: f64,
    external_trial_factor: f64,
    confidence_level: f64,
    simultaneous_case_count: usize,
    elapsed_seconds: f64,
    production: AggregateReport,
    cases: Vec<CaseReport>,
    positive_control_max_chance_probability: Option<f64>,
    positive_control: Option<AggregateReport>,
    positive_control_cases: Vec<CaseReport>,
    zero_false_accepts_observed: bool,
    interpretation: &'static str,
    passed: bool,
}

#[derive(Debug, Default)]
struct Accumulator {
    trials: u64,
    solved: u64,
    ambiguous: u64,
    no_match: NoMatchCounts,
    errors: u64,
    deadline_flagged: u64,
    budget_flagged: u64,
    durations_ns: Vec<u64>,
    diagnostics: DiagnosticAccumulator,
    quantization_arcsec_total: f64,
    quantization_arcsec_max: f64,
    match_arcsec_total: f64,
    match_arcsec_max: f64,
    random_match_probability_total: f64,
    random_match_probability_max: f64,
    examples: Vec<EventExample>,
}

#[derive(Clone, Copy, Debug, Default)]
struct DiagnosticAccumulator {
    quads_total: u64,
    quads_max: u64,
    candidates_total: u64,
    candidates_max: u64,
    hypotheses_total: u64,
    hypotheses_max: u64,
    index_records_total: u64,
    index_records_max: u64,
    verification_pairs_total: u64,
    verification_pairs_max: u64,
    catalog_records_total: u64,
    catalog_records_max: u64,
}

impl DiagnosticAccumulator {
    fn add(&mut self, value: Diagnostics) {
        add_metric(
            &mut self.quads_total,
            &mut self.quads_max,
            u64::from(value.quads_tested),
        );
        add_metric(
            &mut self.candidates_total,
            &mut self.candidates_max,
            u64::from(value.candidates_tested),
        );
        add_metric(
            &mut self.hypotheses_total,
            &mut self.hypotheses_max,
            u64::from(value.hypotheses_tested),
        );
        add_metric(
            &mut self.index_records_total,
            &mut self.index_records_max,
            value.index_records_scanned,
        );
        add_metric(
            &mut self.verification_pairs_total,
            &mut self.verification_pairs_max,
            value.verification_pairs,
        );
        add_metric(
            &mut self.catalog_records_total,
            &mut self.catalog_records_max,
            value.catalog_records_scanned,
        );
    }

    fn merge(&mut self, other: Self) {
        self.quads_total = self.quads_total.saturating_add(other.quads_total);
        self.quads_max = self.quads_max.max(other.quads_max);
        self.candidates_total = self.candidates_total.saturating_add(other.candidates_total);
        self.candidates_max = self.candidates_max.max(other.candidates_max);
        self.hypotheses_total = self.hypotheses_total.saturating_add(other.hypotheses_total);
        self.hypotheses_max = self.hypotheses_max.max(other.hypotheses_max);
        self.index_records_total = self
            .index_records_total
            .saturating_add(other.index_records_total);
        self.index_records_max = self.index_records_max.max(other.index_records_max);
        self.verification_pairs_total = self
            .verification_pairs_total
            .saturating_add(other.verification_pairs_total);
        self.verification_pairs_max = self
            .verification_pairs_max
            .max(other.verification_pairs_max);
        self.catalog_records_total = self
            .catalog_records_total
            .saturating_add(other.catalog_records_total);
        self.catalog_records_max = self.catalog_records_max.max(other.catalog_records_max);
    }
}

impl Accumulator {
    fn add(&mut self, result: TrialResult) {
        self.trials = self.trials.saturating_add(1);
        self.deadline_flagged += u64::from(result.diagnostics.deadline_exceeded);
        self.budget_flagged += u64::from(result.diagnostics.budget_exhausted);
        self.diagnostics.add(result.diagnostics);
        self.quantization_arcsec_total += result.quantization_uncertainty_arcsec;
        self.quantization_arcsec_max = self
            .quantization_arcsec_max
            .max(result.quantization_uncertainty_arcsec);
        self.match_arcsec_total += result.effective_match_tolerance_arcsec;
        self.match_arcsec_max = self
            .match_arcsec_max
            .max(result.effective_match_tolerance_arcsec);
        self.random_match_probability_total += result.random_match_probability;
        self.random_match_probability_max = self
            .random_match_probability_max
            .max(result.random_match_probability);
        if let Some(duration) = result.solve_ns {
            self.durations_ns.push(duration);
        }
        let example = match result.class {
            TrialClass::Solved {
                matches,
                chance_probability,
            } => {
                self.solved += 1;
                Some(EventExample {
                    trial_ordinal: result.ordinal,
                    kind: "solved_false_accept",
                    validation_matches: Some(matches),
                    chance_probability: Some(chance_probability),
                    detail: None,
                })
            }
            TrialClass::Ambiguous {
                best_matches,
                best_chance_probability,
                runner_up_matches,
                runner_up_chance_probability,
            } => {
                self.ambiguous += 1;
                Some(EventExample {
                    trial_ordinal: result.ordinal,
                    kind: "ambiguous_rejected",
                    validation_matches: Some(best_matches),
                    chance_probability: Some(best_chance_probability),
                    detail: Some(format!(
                        "runner-up: {runner_up_matches} validation matches, chance {runner_up_chance_probability:.6e}"
                    )),
                })
            }
            TrialClass::NoMatch(reason) => {
                match reason {
                    NoMatchReason::InsufficientObservations => {
                        self.no_match.insufficient_observations += 1;
                    }
                    NoMatchReason::SearchExhausted => self.no_match.search_exhausted += 1,
                    NoMatchReason::RejectedByVerification => {
                        self.no_match.rejected_by_verification += 1;
                    }
                    NoMatchReason::BudgetExhausted => self.no_match.budget_exhausted += 1,
                    NoMatchReason::DeadlineExceeded => self.no_match.deadline_exceeded += 1,
                }
                None
            }
            TrialClass::Error => {
                self.errors += 1;
                Some(EventExample {
                    trial_ordinal: result.ordinal,
                    kind: "solver_error",
                    validation_matches: None,
                    chance_probability: None,
                    detail: result.error,
                })
            }
        };
        if let Some(example) = example {
            self.examples.push(example);
            self.examples
                .sort_unstable_by_key(|value| value.trial_ordinal);
            self.examples.truncate(MAX_EXAMPLES);
        }
    }

    fn merge(mut self, mut other: Self) -> Self {
        self.trials = self.trials.saturating_add(other.trials);
        self.solved = self.solved.saturating_add(other.solved);
        self.ambiguous = self.ambiguous.saturating_add(other.ambiguous);
        self.no_match.insufficient_observations = self
            .no_match
            .insufficient_observations
            .saturating_add(other.no_match.insufficient_observations);
        self.no_match.search_exhausted = self
            .no_match
            .search_exhausted
            .saturating_add(other.no_match.search_exhausted);
        self.no_match.rejected_by_verification = self
            .no_match
            .rejected_by_verification
            .saturating_add(other.no_match.rejected_by_verification);
        self.no_match.budget_exhausted = self
            .no_match
            .budget_exhausted
            .saturating_add(other.no_match.budget_exhausted);
        self.no_match.deadline_exceeded = self
            .no_match
            .deadline_exceeded
            .saturating_add(other.no_match.deadline_exceeded);
        self.errors = self.errors.saturating_add(other.errors);
        self.deadline_flagged = self.deadline_flagged.saturating_add(other.deadline_flagged);
        self.budget_flagged = self.budget_flagged.saturating_add(other.budget_flagged);
        self.durations_ns.append(&mut other.durations_ns);
        self.diagnostics.merge(other.diagnostics);
        self.quantization_arcsec_total += other.quantization_arcsec_total;
        self.quantization_arcsec_max = self
            .quantization_arcsec_max
            .max(other.quantization_arcsec_max);
        self.match_arcsec_total += other.match_arcsec_total;
        self.match_arcsec_max = self.match_arcsec_max.max(other.match_arcsec_max);
        self.random_match_probability_total += other.random_match_probability_total;
        self.random_match_probability_max = self
            .random_match_probability_max
            .max(other.random_match_probability_max);
        self.examples.append(&mut other.examples);
        self.examples
            .sort_unstable_by_key(|value| value.trial_ordinal);
        self.examples.truncate(MAX_EXAMPLES);
        self
    }
}

pub fn run(arguments: ValidateFprArgs) -> Result<()> {
    validate_arguments(&arguments)?;
    validate_output_separation(&arguments)?;
    let started = Instant::now();
    let mapped = open_mapped(&arguments.path)?;
    let view = mapped.view();

    let cases = build_camera_cases(view, &arguments)?;
    if cases.is_empty() {
        return Err(Error::InvalidIndex("index contains no FOV levels".into()));
    }
    let distributions = selected_distributions(arguments.distribution);
    let observation_counts = selected_observation_counts(&arguments);
    let simultaneous_case_count = cases
        .len()
        .checked_mul(distributions.len())
        .and_then(|count| count.checked_mul(observation_counts.len()))
        .ok_or_else(|| Error::InvalidArgument("too many FPR cases".into()))?;
    let worker_threads = if arguments.threads == 0 {
        std::thread::available_parallelism().map_or(1, usize::from)
    } else {
        arguments.threads
    };
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(worker_threads)
        .thread_name(|index| format!("tetra3-fpr-{index}"))
        .build()
        .map_err(|error| Error::Validation(format!("failed to create FPR worker pool: {error}")))?;

    let mut reports = Vec::with_capacity(simultaneous_case_count);
    for (case_index, camera) in cases.iter().enumerate() {
        for &distribution in distributions {
            for &observation_count in &observation_counts {
                reports.push(run_case(
                    &pool,
                    view,
                    camera,
                    case_index,
                    distribution,
                    observation_count,
                    Mode::Production,
                    arguments.trials_per_fov,
                    &arguments,
                    simultaneous_case_count,
                )?);
            }
        }
    }
    let production = aggregate_reports(&reports);

    let mut positive_control_cases = Vec::new();
    if arguments.positive_control_trials_per_fov != 0 {
        positive_control_cases.reserve(simultaneous_case_count);
        for (case_index, camera) in cases.iter().enumerate() {
            for &distribution in distributions {
                for &observation_count in &observation_counts {
                    positive_control_cases.push(run_case(
                        &pool,
                        view,
                        camera,
                        case_index,
                        distribution,
                        observation_count,
                        Mode::PositiveControl,
                        arguments.positive_control_trials_per_fov,
                        &arguments,
                        simultaneous_case_count,
                    )?);
                }
            }
        }
    }
    let positive_control =
        (!positive_control_cases.is_empty()).then(|| aggregate_reports(&positive_control_cases));
    let passed = production.false_accepts == 0
        && production.solver_errors == 0
        && production.budget_flagged_trials == 0;
    let production_config = production_solve_config(Mode::Production);
    let positive_control_config = production_solve_config(Mode::PositiveControl);
    let report = FprReport {
        format: "tetra3-fpr-validation-v1",
        index: arguments.path.clone(),
        mapped_bytes: view.as_bytes().len(),
        levels: view.level_count(),
        seed: arguments.seed,
        start_trial: arguments.start_trial,
        trials_per_fov: arguments.trials_per_fov,
        observation_counts,
        validation_screen_observations: production_config.validation_screen_observations,
        timeout_ms: arguments.timeout_ms,
        worker_threads,
        max_chance_probability: production_config.max_chance_probability,
        fallback_density_per_steradian: production_config.fallback_density_per_steradian,
        external_trial_factor: production_config.external_trial_factor,
        confidence_level: 1.0 - CONFIDENCE_ALPHA,
        simultaneous_case_count,
        elapsed_seconds: started.elapsed().as_secs_f64(),
        production,
        cases: reports,
        positive_control_max_chance_probability: positive_control
            .map(|_| positive_control_config.max_chance_probability),
        positive_control,
        positive_control_cases,
        zero_false_accepts_observed: production.false_accepts == 0,
        interpretation: "passed means zero observed production false accepts, solver errors, and budget flags; the reported confidence bounds, not passed, quantify the unresolved false-positive rate",
        passed,
    };
    if let Some(path) = arguments.output.as_deref() {
        write_json_atomic(path, &report)?;
    }
    crate::cli::print_value(&report, arguments.json)?;
    if report.passed {
        Ok(())
    } else {
        Err(Error::Validation(format!(
            "FPR audit failed: {} solved false accepts, {} solver errors, {} budget-flagged trials",
            report.production.false_accepts,
            report.production.solver_errors,
            report.production.budget_flagged_trials,
        )))
    }
}

fn validate_output_separation(arguments: &ValidateFprArgs) -> Result<()> {
    let Some(output) = arguments.output.as_deref() else {
        return Ok(());
    };
    let output_identity = canonical_output_identity(output)?;
    for input in std::iter::once(arguments.path.as_path()).chain(arguments.lens_database.as_deref())
    {
        let canonical_input =
            std::fs::canonicalize(input).map_err(|error| Error::io(input, error))?;
        if output_identity == canonical_input || existing_paths_are_same_file(output, input)? {
            return Err(Error::InvalidArgument(format!(
                "--output {} resolves to binary input {}; choose a distinct report path",
                output.display(),
                input.display()
            )));
        }
    }
    Ok(())
}

/// Resolve an existing path through its final symlink. For a not-yet-created
/// report, resolve its existing parent so `dir/../index.t3ix` cannot evade the
/// binary-input collision check.
fn canonical_output_identity(path: &Path) -> Result<PathBuf> {
    match std::fs::canonicalize(path) {
        Ok(value) => Ok(value),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let file_name = path
                .file_name()
                .ok_or_else(|| Error::InvalidArgument("--output must name a report file".into()))?;
            let parent = path
                .parent()
                .filter(|value| !value.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let canonical_parent =
                std::fs::canonicalize(parent).map_err(|error| Error::io(parent, error))?;
            Ok(canonical_parent.join(file_name))
        }
        Err(error) => Err(Error::io(path, error)),
    }
}

fn existing_paths_are_same_file(left: &Path, right: &Path) -> Result<bool> {
    let left_metadata = match std::fs::metadata(left) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(Error::io(left, error)),
    };
    let right_metadata = std::fs::metadata(right).map_err(|error| Error::io(right, error))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        Ok(left_metadata.dev() == right_metadata.dev()
            && left_metadata.ino() == right_metadata.ino())
    }
    #[cfg(not(unix))]
    {
        // Canonical path identity above still covers direct paths and symlinks.
        // Rust's portable Metadata API does not expose a hard-link file ID.
        let _ = (left_metadata, right_metadata);
        Ok(false)
    }
}

#[allow(clippy::too_many_arguments)]
fn run_case(
    pool: &rayon::ThreadPool,
    view: IndexView<'_>,
    camera: &CameraCase<'_>,
    case_index: usize,
    distribution: RandomDistribution,
    observation_count: usize,
    mode: Mode,
    trials: u64,
    arguments: &ValidateFprArgs,
    simultaneous_case_count: usize,
) -> Result<CaseReport> {
    let runtime = camera.camera.runtime();
    let verification_star_count_hint = runtime.verification_star_count_hint();
    let selected_verification_star_count = view
        .level_descriptor(u16::from(camera.camera.selected_level()))
        .map_err(|error| Error::InvalidIndex(error.to_string()))?
        .verification_star_count;
    let base_config = production_solve_config(mode);
    let density_hint = runtime
        .density_hint_per_steradian(UnitVec3::Z, camera.camera.field_radius_rad())
        .map_err(|error| Error::InvalidIndex(error.to_string()))?;
    let (catalog_density_source, effective_catalog_density) = match density_hint {
        Some(density) => ("index_density_hint", density),
        None => (
            "configured_fallback",
            base_config.fallback_density_per_steradian,
        ),
    };
    let domain = trial_domain(case_index, distribution, observation_count, mode);
    let timeout = Duration::from_millis(arguments.timeout_ms);
    let accumulator = pool.install(|| {
        (0..trials)
            .into_par_iter()
            .fold(Accumulator::default, |mut accumulator, local_ordinal| {
                let ordinal = arguments.start_trial.saturating_add(local_ordinal);
                let seed = counter_seed(arguments.seed, domain, ordinal);
                let result = run_trial(
                    camera,
                    distribution,
                    observation_count,
                    ordinal,
                    seed,
                    base_config,
                    effective_catalog_density,
                    timeout,
                    arguments.timeout_ms != 0,
                );
                accumulator.add(result);
                accumulator
            })
            .reduce(Accumulator::default, Accumulator::merge)
    });
    Ok(accumulator.into_report(
        camera,
        distribution,
        observation_count,
        runtime.verification_magnitude_limit_q4(),
        selected_verification_star_count,
        verification_star_count_hint,
        catalog_density_source,
        effective_catalog_density,
        CONFIDENCE_ALPHA / simultaneous_case_count as f64,
    ))
}

impl Accumulator {
    #[allow(clippy::too_many_arguments)]
    fn into_report(
        mut self,
        camera: &CameraCase<'_>,
        distribution: RandomDistribution,
        observation_count: usize,
        verification_magnitude_limit_q4: Option<i8>,
        selected_verification_star_count: u32,
        verification_star_count_hint: Option<u32>,
        catalog_density_source: &'static str,
        effective_catalog_density: f64,
        simultaneous_alpha: f64,
    ) -> CaseReport {
        self.durations_ns.sort_unstable();
        let completed = self.trials.saturating_sub(self.errors);
        let gate_crossing_trials = self.solved.saturating_add(self.ambiguous);
        let false_accept_upper_bound_95 = (self.errors == 0)
            .then(|| one_sided_binomial_upper(self.solved, completed, CONFIDENCE_ALPHA));
        let simultaneous = (self.errors == 0)
            .then(|| one_sided_binomial_upper(self.solved, completed, simultaneous_alpha));
        let gate_upper = (self.errors == 0)
            .then(|| one_sided_binomial_upper(gate_crossing_trials, completed, CONFIDENCE_ALPHA));
        let divisor = completed.max(1) as f64;
        let quantization_uncertainty_arcsec = ScalarSummary {
            mean: self.quantization_arcsec_total / divisor,
            maximum: self.quantization_arcsec_max,
        };
        let effective_match_tolerance_arcsec = ScalarSummary {
            mean: self.match_arcsec_total / divisor,
            maximum: self.match_arcsec_max,
        };
        let single_observation_random_match_probability = ScalarSummary {
            mean: self.random_match_probability_total / divisor,
            maximum: self.random_match_probability_max,
        };
        let independent_validation_observations = observation_count.saturating_sub(4) as f64;
        let conservative_null_mean_validation_matches = ScalarSummary {
            mean: single_observation_random_match_probability.mean
                * independent_validation_observations,
            maximum: single_observation_random_match_probability.maximum
                * independent_validation_observations,
        };
        let work = WorkSummary {
            quads_tested: metric(
                self.diagnostics.quads_total,
                self.diagnostics.quads_max,
                completed,
            ),
            candidates_tested: metric(
                self.diagnostics.candidates_total,
                self.diagnostics.candidates_max,
                completed,
            ),
            hypotheses_tested: metric(
                self.diagnostics.hypotheses_total,
                self.diagnostics.hypotheses_max,
                completed,
            ),
            index_records_scanned: metric(
                self.diagnostics.index_records_total,
                self.diagnostics.index_records_max,
                completed,
            ),
            verification_pairs: metric(
                self.diagnostics.verification_pairs_total,
                self.diagnostics.verification_pairs_max,
                completed,
            ),
            catalog_records_scanned: metric(
                self.diagnostics.catalog_records_total,
                self.diagnostics.catalog_records_max,
                completed,
            ),
        };
        CaseReport {
            case_kind: camera.spec.kind,
            distribution,
            observations: observation_count,
            requested_horizontal_fov_deg: camera.spec.requested_fov_deg,
            actual_horizontal_fov_deg: camera.camera.horizontal_fov_rad().to_degrees(),
            full_field_radius_deg: camera.camera.field_radius_rad().to_degrees(),
            routing_fov_mdeg: camera.spec.routing_fov_mdeg,
            nominal_level: camera.spec.nominal_level,
            selected_level: camera.camera.selected_level(),
            over_index_wide_field: camera.camera.is_over_index_wide_field(),
            projection: camera.projection,
            verification_magnitude_limit_q4,
            selected_verification_star_count,
            density_hint_star_count: verification_star_count_hint,
            catalog_density_source,
            effective_catalog_density_per_steradian: effective_catalog_density,
            quantization_uncertainty_arcsec,
            effective_match_tolerance_arcsec,
            single_observation_random_match_probability,
            conservative_null_mean_validation_matches,
            trials: self.trials,
            false_accepts: self.solved,
            ambiguous: self.ambiguous,
            gate_crossing_trials,
            no_match: self.no_match,
            solver_errors: self.errors,
            deadline_flagged_trials: self.deadline_flagged,
            budget_flagged_trials: self.budget_flagged,
            false_accept_rate: rate(self.solved, completed),
            false_accept_upper_bound_95,
            false_accept_simultaneous_upper_bound_95: simultaneous,
            gate_crossing_trial_rate: rate(gate_crossing_trials, completed),
            gate_crossing_trial_upper_bound_95: gate_upper,
            solve_timing: summarize_timing(&self.durations_ns),
            work,
            examples: self.examples,
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn run_trial(
    camera: &CameraCase<'_>,
    distribution: RandomDistribution,
    observation_count: usize,
    ordinal: u64,
    seed: u64,
    base_config: SolveConfig,
    effective_catalog_density: f64,
    timeout: Duration,
    deadline_enabled: bool,
) -> TrialResult {
    let pixels = match generate_random_pixels(camera, distribution, observation_count, seed) {
        Ok(value) => value,
        Err(error) => {
            return TrialResult {
                ordinal,
                class: TrialClass::Error,
                diagnostics: Diagnostics::default(),
                solve_ns: None,
                quantization_uncertainty_arcsec: 0.0,
                effective_match_tolerance_arcsec: 0.0,
                random_match_probability: 0.0,
                error: Some(error.to_string()),
            };
        }
    };
    let (config, quantization_error) = match camera
        .camera
        .solve_config_for_star_pixels(base_config, &pixels)
    {
        Ok(value) => value,
        Err(error) => {
            return TrialResult {
                ordinal,
                class: TrialClass::Error,
                diagnostics: Diagnostics::default(),
                solve_ns: None,
                quantization_uncertainty_arcsec: 0.0,
                effective_match_tolerance_arcsec: 0.0,
                random_match_probability: 0.0,
                error: Some(error.to_string()),
            };
        }
    };
    let match_probability =
        random_match_probability(effective_catalog_density, config.match_tolerance_rad);
    let quantization_uncertainty_arcsec = radians_to_arcseconds(quantization_error);
    let effective_match_tolerance_arcsec = radians_to_arcseconds(config.match_tolerance_rad);
    let started = Instant::now();
    let outcome = camera
        .camera
        .solve_pixels_with_deadline(&pixels, &config, || {
            deadline_enabled && started.elapsed() >= timeout
        });
    let solve_ns = Some(started.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64);
    match outcome {
        Ok(SolveOutcome::Solved {
            solution,
            diagnostics,
        }) => TrialResult {
            ordinal,
            class: TrialClass::Solved {
                matches: solution.matches.len(),
                chance_probability: solution.chance_probability,
            },
            diagnostics,
            solve_ns,
            quantization_uncertainty_arcsec,
            effective_match_tolerance_arcsec,
            random_match_probability: match_probability,
            error: None,
        },
        Ok(SolveOutcome::Ambiguous {
            best,
            runner_up,
            diagnostics,
        }) => TrialResult {
            ordinal,
            class: TrialClass::Ambiguous {
                best_matches: best.matches.len(),
                best_chance_probability: best.chance_probability,
                runner_up_matches: runner_up.matches.len(),
                runner_up_chance_probability: runner_up.chance_probability,
            },
            diagnostics,
            solve_ns,
            quantization_uncertainty_arcsec,
            effective_match_tolerance_arcsec,
            random_match_probability: match_probability,
            error: None,
        },
        Ok(SolveOutcome::NoMatch {
            reason,
            diagnostics,
        }) => TrialResult {
            ordinal,
            class: TrialClass::NoMatch(reason),
            diagnostics,
            solve_ns,
            quantization_uncertainty_arcsec,
            effective_match_tolerance_arcsec,
            random_match_probability: match_probability,
            error: None,
        },
        Err(error) => TrialResult {
            ordinal,
            class: TrialClass::Error,
            diagnostics: Diagnostics::default(),
            solve_ns,
            quantization_uncertainty_arcsec,
            effective_match_tolerance_arcsec,
            random_match_probability: match_probability,
            error: Some(error.to_string()),
        },
    }
}

fn production_solve_config(mode: Mode) -> SolveConfig {
    let mut config = shared_production_config();
    if matches!(mode, Mode::PositiveControl) {
        config.max_chance_probability = 1.0;
    }
    config
}

fn generate_random_pixels(
    camera: &CameraCase<'_>,
    distribution: RandomDistribution,
    observation_count: usize,
    seed: u64,
) -> Result<Vec<PixelObservation>> {
    let mut random = SplitMix64::new(seed);
    let geometry = camera.camera.lens().camera();
    let width = geometry.encoded_width();
    let height = geometry.encoded_height();
    let pixel_count = u64::from(width) * u64::from(height);
    if pixel_count < observation_count as u64 {
        return Err(Error::Validation(
            "camera raster has fewer unique STAR pixels than requested observations".into(),
        ));
    }
    let mut occupied = HashSet::with_capacity(observation_count * 2);
    let mut output = Vec::with_capacity(observation_count);
    let mut attempts = 0_usize;
    while output.len() < observation_count {
        attempts += 1;
        if attempts > observation_count * 1_000_000 {
            return Err(Error::Validation(
                "could not sample the requested unique random detections in camera footprint"
                    .into(),
            ));
        }
        let centroid = match distribution {
            RandomDistribution::Pixels => {
                let flat = random.uniform_u64(pixel_count);
                PixelCentroid {
                    x: f64::from((flat % u64::from(width)) as u32) - STAR_WIRE_PIXEL_PHASE,
                    y: f64::from((flat / u64::from(width)) as u32) - STAR_WIRE_PIXEL_PHASE,
                }
            }
            RandomDistribution::Sky => sample_sky_centroid(camera, &mut random)?,
            RandomDistribution::Both => unreachable!("both expands before field generation"),
        };
        let wire_x = (centroid.x + STAR_WIRE_PIXEL_PHASE).round() as u32;
        let wire_y = (centroid.y + STAR_WIRE_PIXEL_PHASE).round() as u32;
        let flat = u64::from(wire_y) * u64::from(width) + u64::from(wire_x);
        if !occupied.insert(flat) {
            continue;
        }
        output.push(PixelObservation {
            centroid,
            flux: (observation_count + 1 - output.len()) as f32,
        });
    }
    Ok(output)
}

fn sample_sky_centroid(camera: &CameraCase<'_>, random: &mut SplitMix64) -> Result<PixelCentroid> {
    match &camera.forward {
        ForwardModel::Rectilinear(renderer) => loop {
            let tangent_x = (0.5 * camera.camera.horizontal_fov_rad()).tan();
            let tangent_y =
                tangent_x * f64::from(STAR_ENCODED_HEIGHT) / f64::from(STAR_ENCODED_WIDTH);
            let x = (2.0 * random.next_f64() - 1.0) * tangent_x;
            let y = (2.0 * random.next_f64() - 1.0) * tangent_y;
            // dx*dy/(1+x^2+y^2)^(3/2) is solid angle, so this
            // rejection turns uniform image-plane proposals into uniform sky.
            if random.next_f64() > (1.0 + x * x + y * y).powf(-1.5) {
                continue;
            }
            let ray =
                UnitVec3::new(x, y, 1.0).map_err(|error| Error::Validation(error.to_string()))?;
            return renderer.encode_solver_centroid(ray);
        },
        ForwardModel::Profile(profile) => loop {
            let cosine = 1.0 - random.next_f64() * (1.0 - camera.camera.field_radius_rad().cos());
            let sine = (1.0 - cosine * cosine).max(0.0).sqrt();
            let azimuth = TAU * random.next_f64();
            let ray = UnitVec3::new(sine * azimuth.cos(), sine * azimuth.sin(), cosine)
                .map_err(|error| Error::Validation(error.to_string()))?;
            if let Some(centroid) = profile.render_centroid(ray)? {
                return Ok(centroid);
            }
        },
    }
}

fn build_camera_cases<'a>(
    view: IndexView<'a>,
    arguments: &ValidateFprArgs,
) -> Result<Vec<CameraCase<'a>>> {
    let specs = build_case_specs(view, &arguments.fov_deg)?;
    let mut output =
        Vec::with_capacity(specs.len() + usize::from(arguments.lens_database.is_some()));
    for spec in specs {
        output.push(build_rectilinear_case(view, spec)?);
    }
    if let (Some(database_path), Some(profile_id), Some(focal_length_mm)) = (
        arguments.lens_database.as_ref(),
        arguments.lens_profile_id.as_deref(),
        arguments.focal_length_mm,
    ) {
        output.push(build_profile_case(
            view,
            database_path,
            profile_id,
            focal_length_mm,
        )?);
    }
    Ok(output)
}

fn build_case_specs(view: IndexView<'_>, explicit: &[f64]) -> Result<Vec<CaseSpec>> {
    if !explicit.is_empty() {
        let mut output = Vec::with_capacity(explicit.len());
        for &fov_deg in explicit {
            let routing = fov_to_mdeg(fov_deg)?;
            let level = view
                .level_for_fov_mdeg(routing)
                .map_err(|error| Error::InvalidIndex(error.to_string()))?
                .ok_or_else(|| {
                    Error::InvalidArgument(format!(
                        "--fov-deg {fov_deg} (routing value {routing} mdeg) is not covered by the index"
                    ))
                })?;
            output.push(CaseSpec {
                kind: CaseKind::Explicit,
                requested_fov_deg: fov_deg,
                routing_fov_mdeg: routing,
                nominal_level: Some(level.number()),
            });
        }
        return Ok(output);
    }

    let mut boundaries = BTreeSet::new();
    let mut centers = Vec::with_capacity(view.level_count() as usize);
    for level in 0..view.level_count() {
        let descriptor = view
            .level_descriptor(level)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?;
        boundaries.insert(descriptor.min_fov_mdeg);
        boundaries.insert(descriptor.max_fov_mdeg);
        let center_deg = (f64::from(descriptor.min_fov_mdeg) * f64::from(descriptor.max_fov_mdeg))
            .sqrt()
            / 1_000.0;
        centers.push(CaseSpec {
            kind: CaseKind::LevelGeometricCenter,
            requested_fov_deg: center_deg,
            routing_fov_mdeg: fov_to_mdeg(center_deg)?,
            nominal_level: Some(level),
        });
    }
    let mut output = Vec::with_capacity(boundaries.len() + centers.len());
    for boundary in boundaries {
        let selected = view
            .level_for_fov_mdeg(boundary)
            .map_err(|error| Error::InvalidIndex(error.to_string()))?
            .ok_or_else(|| Error::InvalidIndex(format!("FOV boundary {boundary} is uncovered")))?;
        output.push(CaseSpec {
            kind: CaseKind::LevelBoundary,
            requested_fov_deg: f64::from(boundary) / 1_000.0,
            routing_fov_mdeg: boundary,
            nominal_level: Some(selected.number()),
        });
    }
    output.extend(centers);
    Ok(output)
}

fn build_rectilinear_case<'a>(view: IndexView<'a>, spec: CaseSpec) -> Result<CameraCase<'a>> {
    let fov_rad = spec.requested_fov_deg.to_radians();
    let renderer = StarCamera::for_horizontal_fov(fov_rad)?;
    let lens = renderer.lens;
    let camera =
        ProductionCamera::new(view, lens).map_err(|error| Error::Validation(error.to_string()))?;
    let routed = camera.horizontal_fov_mdeg();
    if routed != spec.routing_fov_mdeg {
        return Err(Error::Validation(format!(
            "rectilinear FOV routing drifted from {} to {routed} mdeg",
            spec.routing_fov_mdeg
        )));
    }
    Ok(CameraCase {
        spec,
        camera,
        forward: ForwardModel::Rectilinear(renderer),
        projection: "rectilinear",
    })
}

fn build_profile_case<'a>(
    view: IndexView<'a>,
    database_path: &Path,
    profile_id: &str,
    focal_length_mm: f64,
) -> Result<CameraCase<'a>> {
    let renderer = ProfileRenderer::load(database_path, profile_id, focal_length_mm)?;
    let lens = renderer.lens;
    let camera =
        ProductionCamera::new(view, lens).map_err(|error| Error::Validation(error.to_string()))?;
    Ok(CameraCase {
        spec: CaseSpec {
            kind: CaseKind::LensProfile,
            requested_fov_deg: camera.horizontal_fov_rad().to_degrees(),
            routing_fov_mdeg: camera.horizontal_fov_mdeg(),
            nominal_level: Some(u16::from(camera.selected_level())),
        },
        camera,
        projection: projection_name(renderer.profile.projection()),
        forward: ForwardModel::Profile(renderer),
    })
}

fn selected_distributions(value: RandomDistribution) -> &'static [RandomDistribution] {
    match value {
        RandomDistribution::Sky => &[RandomDistribution::Sky],
        RandomDistribution::Pixels => &[RandomDistribution::Pixels],
        RandomDistribution::Both => &[RandomDistribution::Sky, RandomDistribution::Pixels],
    }
}

fn selected_observation_counts(arguments: &ValidateFprArgs) -> Vec<usize> {
    if arguments.observations.is_empty() {
        vec![shared_production_config().budgets.max_observations]
    } else {
        let mut values = arguments.observations.clone();
        values.sort_unstable();
        values.dedup();
        values
    }
}

fn validate_arguments(arguments: &ValidateFprArgs) -> Result<()> {
    let production_config = shared_production_config();
    let min_observations = 4_usize.saturating_add(production_config.min_matches);
    let max_observations = production_config.budgets.max_observations;
    if arguments.trials_per_fov == 0 {
        return Err(Error::InvalidArgument(
            "--trials-per-fov must be greater than zero".into(),
        ));
    }
    arguments
        .start_trial
        .checked_add(
            arguments
                .trials_per_fov
                .max(arguments.positive_control_trials_per_fov),
        )
        .ok_or_else(|| Error::InvalidArgument("trial ordinal range overflows u64".into()))?;
    if arguments.threads > 4_096 {
        return Err(Error::InvalidArgument(
            "--threads must not exceed 4096".into(),
        ));
    }
    let lens_option_count = usize::from(arguments.lens_database.is_some())
        + usize::from(arguments.lens_profile_id.is_some())
        + usize::from(arguments.focal_length_mm.is_some());
    if lens_option_count != 0 && lens_option_count != 3 {
        return Err(Error::InvalidArgument(
            "--lens-database, --lens-profile-id, and --focal-length-mm must be supplied together"
                .into(),
        ));
    }
    if arguments
        .focal_length_mm
        .is_some_and(|value| !value.is_finite() || value <= 0.0)
    {
        return Err(Error::InvalidArgument(
            "--focal-length-mm must be positive and finite".into(),
        ));
    }
    for &fov in &arguments.fov_deg {
        let _ = fov_to_mdeg(fov)?;
    }
    if arguments
        .observations
        .iter()
        .any(|&count| !(min_observations..=max_observations).contains(&count))
    {
        return Err(Error::InvalidArgument(format!(
            "every --observations value must be between {min_observations} and {max_observations}"
        )));
    }
    Ok(())
}

fn aggregate_reports(reports: &[CaseReport]) -> AggregateReport {
    let mut output = AggregateReport::default();
    for report in reports {
        output.trials = output.trials.saturating_add(report.trials);
        output.false_accepts = output.false_accepts.saturating_add(report.false_accepts);
        output.ambiguous = output.ambiguous.saturating_add(report.ambiguous);
        output.gate_crossing_trials = output
            .gate_crossing_trials
            .saturating_add(report.gate_crossing_trials);
        output.solver_errors = output.solver_errors.saturating_add(report.solver_errors);
        output.deadline_flagged_trials = output
            .deadline_flagged_trials
            .saturating_add(report.deadline_flagged_trials);
        output.budget_flagged_trials = output
            .budget_flagged_trials
            .saturating_add(report.budget_flagged_trials);
        output.quads_tested = output
            .quads_tested
            .saturating_add(report.work.quads_tested.total);
        output.candidates_tested = output
            .candidates_tested
            .saturating_add(report.work.candidates_tested.total);
        output.hypotheses_tested = output
            .hypotheses_tested
            .saturating_add(report.work.hypotheses_tested.total);
    }
    let completed = output.trials.saturating_sub(output.solver_errors);
    output.false_accept_rate = rate(output.false_accepts, completed);
    output.gate_crossing_trial_rate = rate(output.gate_crossing_trials, completed);
    if output.solver_errors == 0 {
        output.false_accept_upper_bound_95_if_pooled = Some(one_sided_binomial_upper(
            output.false_accepts,
            completed,
            CONFIDENCE_ALPHA,
        ));
        output.gate_crossing_trial_upper_bound_95_if_pooled = Some(one_sided_binomial_upper(
            output.gate_crossing_trials,
            completed,
            CONFIDENCE_ALPHA,
        ));
    }
    output.maximum_case_false_accept_upper_bound_95 = maximum_complete_bounds(
        reports
            .iter()
            .map(|report| report.false_accept_upper_bound_95),
    );
    output.maximum_case_simultaneous_false_accept_upper_bound_95 = maximum_complete_bounds(
        reports
            .iter()
            .map(|report| report.false_accept_simultaneous_upper_bound_95),
    );
    output
}

/// Return the maximum only when every constituent case supplied a valid
/// confidence bound. An errored case has no evaluated denominator, so silently
/// dropping it would make the aggregate look more complete than it is.
fn maximum_complete_bounds(bounds: impl IntoIterator<Item = Option<f64>>) -> Option<f64> {
    let mut maximum = None;
    for bound in bounds {
        let bound = bound?;
        maximum = Some(maximum.map_or(bound, |known: f64| known.max(bound)));
    }
    maximum
}

fn random_match_probability(density_per_steradian: f64, tolerance_rad: f64) -> f64 {
    let half_sine = (0.5 * tolerance_rad).sin();
    let cap_area = 4.0 * PI * half_sine * half_sine;
    (density_per_steradian * cap_area).clamp(0.0, 1.0)
}

fn metric(total: u64, maximum: u64, trials: u64) -> MetricSummary {
    MetricSummary {
        total,
        mean: if trials == 0 {
            0.0
        } else {
            total as f64 / trials as f64
        },
        maximum,
    }
}

fn summarize_timing(values: &[u64]) -> TimingSummary {
    timing_quantiles_ns(values).map_or_else(TimingSummary::default, |(median, p95, maximum)| {
        TimingSummary {
            median_ms: median,
            p95_ms: p95,
            maximum_ms: maximum,
        }
    })
}

fn rate(events: u64, trials: u64) -> f64 {
    if trials == 0 {
        0.0
    } else {
        events as f64 / trials as f64
    }
}

/// Exact one-sided Clopper-Pearson upper confidence limit, found by
/// inverting P[X <= observed | n, p] = alpha.
fn one_sided_binomial_upper(observed: u64, trials: u64, alpha: f64) -> f64 {
    if trials == 0 || observed >= trials {
        return 1.0;
    }
    if observed == 0 {
        return -(alpha.ln() / trials as f64).exp_m1();
    }
    let mut low = 0.0;
    let mut high = 1.0;
    for _ in 0..100 {
        let middle = 0.5 * (low + high);
        if binomial_cdf(observed, trials, middle) > alpha {
            low = middle;
        } else {
            high = middle;
        }
    }
    0.5 * (low + high)
}

fn binomial_cdf(maximum: u64, trials: u64, probability: f64) -> f64 {
    if probability <= 0.0 {
        return 1.0;
    }
    if probability >= 1.0 {
        return f64::from(maximum >= trials);
    }
    let log_ratio = probability.ln() - (-probability).ln_1p();
    let mut log_term = trials as f64 * (-probability).ln_1p();
    let mut maximum_log = log_term;
    let mut logs = Vec::with_capacity(maximum.saturating_add(1).min(1_000_000) as usize);
    logs.push(log_term);
    for successes in 1..=maximum {
        log_term += ((trials - successes + 1) as f64).ln() - (successes as f64).ln() + log_ratio;
        maximum_log = maximum_log.max(log_term);
        logs.push(log_term);
    }
    let sum = logs
        .into_iter()
        .map(|value| (value - maximum_log).exp())
        .sum::<f64>();
    (maximum_log.exp() * sum).clamp(0.0, 1.0)
}

fn add_metric(total: &mut u64, maximum: &mut u64, value: u64) {
    *total = total.saturating_add(value);
    *maximum = (*maximum).max(value);
}

fn trial_domain(
    case_index: usize,
    distribution: RandomDistribution,
    observation_count: usize,
    mode: Mode,
) -> u64 {
    let distribution_tag = match distribution {
        RandomDistribution::Sky => 0x736b_792d_6e75_6c6c,
        RandomDistribution::Pixels => 0x7069_7865_6c2d_6e75,
        RandomDistribution::Both => unreachable!(),
    };
    let mode_tag = match mode {
        Mode::Production => 0x7072_6f64_7563_7469,
        Mode::PositiveControl => 0x706f_7369_7469_7665,
    };
    mix64(
        distribution_tag
            ^ mode_tag
            ^ (case_index as u64).wrapping_mul(SPLITMIX_GAMMA)
            ^ (observation_count as u64).wrapping_mul(0xa076_1d64_78bd_642f),
    )
}

fn counter_seed(seed: u64, domain: u64, ordinal: u64) -> u64 {
    mix64(seed ^ domain ^ ordinal.wrapping_mul(0xd1b5_4a32_d192_ed03))
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;
    use std::fs::File;
    use tetra3_index::MappedIndex;

    #[derive(Debug, Parser)]
    struct TestCommand {
        #[command(flatten)]
        arguments: ValidateFprArgs,
    }

    #[test]
    fn defaults_preserve_production_acceptance_policy() {
        let parsed = TestCommand::try_parse_from(["test", "index.t3ix"]).unwrap();
        assert_eq!(parsed.arguments.trials_per_fov, 10);
        assert_eq!(
            selected_observation_counts(&parsed.arguments),
            vec![shared_production_config().budgets.max_observations]
        );
        assert_eq!(parsed.arguments.distribution, RandomDistribution::Sky);
        assert_eq!(parsed.arguments.timeout_ms, 1_000);
        assert_eq!(parsed.arguments.threads, 1);
        let shared = shared_production_config();
        let production = production_solve_config(Mode::Production);
        assert_eq!(format!("{production:?}"), format!("{shared:?}"));

        let mut expected_positive_control = shared;
        expected_positive_control.max_chance_probability = 1.0;
        let positive_control = production_solve_config(Mode::PositiveControl);
        assert_eq!(
            format!("{positive_control:?}"),
            format!("{expected_positive_control:?}")
        );
    }

    #[test]
    fn zero_event_upper_bound_is_exact() {
        let bound = one_sided_binomial_upper(0, 100, 0.05);
        let expected = 1.0 - 0.05_f64.powf(1.0 / 100.0);
        assert!((bound - expected).abs() < 1.0e-14, "{bound} != {expected}");
    }

    #[test]
    fn aggregate_maximum_bound_fails_closed_when_any_case_is_missing() {
        assert_eq!(
            maximum_complete_bounds([Some(0.1), Some(0.3), Some(0.2)]),
            Some(0.3)
        );
        assert_eq!(maximum_complete_bounds([Some(0.1), None, Some(0.3)]), None);
        assert_eq!(maximum_complete_bounds([]), None);
    }

    #[test]
    fn nonzero_clopper_pearson_bound_matches_reference() {
        // scipy.stats.beta.ppf(0.95, 2, 99)
        let bound = one_sided_binomial_upper(1, 100, 0.05);
        assert!(
            (bound - 0.046_559_811_453_538_96).abs() < 1.0e-12,
            "{bound}"
        );
    }

    #[test]
    fn counter_seeds_are_shard_stable_and_domain_separated() {
        let domain_a = trial_domain(3, RandomDistribution::Sky, 256, Mode::Production);
        let domain_b = trial_domain(3, RandomDistribution::Pixels, 256, Mode::Production);
        assert_ne!(domain_a, domain_b);
        assert_eq!(
            counter_seed(7, domain_a, 1234),
            counter_seed(7, domain_a, 1000 + 234)
        );
        assert_ne!(
            counter_seed(7, domain_a, 1234),
            counter_seed(7, domain_b, 1234)
        );
    }

    #[test]
    fn generated_pixel_null_is_unique_and_brightness_ordered() {
        use tetra3_index::writer::{IndexBuilder, LevelInput};

        let mut builder = IndexBuilder::new();
        builder
            .add_level(LevelInput {
                min_fov_mdeg: 1_000,
                max_fov_mdeg: 45_000,
                default_tolerance_q8: 3,
                neighbor_level_radius: 0,
                max_verification_magnitude_q4: None,
                patterns: Vec::new(),
            })
            .unwrap();
        let bytes = builder.build().unwrap();
        let view = IndexView::parse(&bytes).unwrap();
        let renderer = StarCamera::for_horizontal_fov(30.0_f64.to_radians()).unwrap();
        let lens = renderer.lens;
        let camera = CameraCase {
            spec: CaseSpec {
                kind: CaseKind::Explicit,
                requested_fov_deg: 30.0,
                routing_fov_mdeg: 30_000,
                nominal_level: Some(0),
            },
            camera: ProductionCamera::new(view, lens).unwrap(),
            forward: ForwardModel::Rectilinear(renderer),
            projection: "rectilinear",
        };
        let pixels = generate_random_pixels(&camera, RandomDistribution::Pixels, 256, 19).unwrap();
        assert_eq!(pixels.len(), 256);
        let positions = pixels
            .iter()
            .map(|value| {
                (
                    (value.centroid.x + STAR_WIRE_PIXEL_PHASE) as u32,
                    (value.centroid.y + STAR_WIRE_PIXEL_PHASE) as u32,
                )
            })
            .collect::<BTreeSet<_>>();
        assert_eq!(positions.len(), 256);
        assert!(pixels.windows(2).all(|pair| pair[0].flux > pair[1].flux));
        assert!(pixels.iter().all(|value| {
            (value.centroid.x + STAR_WIRE_PIXEL_PHASE).fract() == 0.0
                && (value.centroid.y + STAR_WIRE_PIXEL_PHASE).fract() == 0.0
        }));
    }

    #[test]
    fn partial_lens_options_are_rejected() {
        let parsed =
            TestCommand::try_parse_from(["test", "index.t3ix", "--lens-database", "profiles.t3lf"])
                .unwrap();
        assert!(validate_arguments(&parsed.arguments).is_err());
    }

    #[test]
    fn observation_strata_are_checked_sorted_and_deduplicated() {
        let minimum = 4 + shared_production_config().min_matches;
        let maximum = shared_production_config().budgets.max_observations;
        let minimum_argument = minimum.to_string();
        let maximum_argument = maximum.to_string();
        let parsed = TestCommand::try_parse_from([
            "test",
            "index.t3ix",
            "--observations",
            maximum_argument.as_str(),
            "--observations",
            minimum_argument.as_str(),
            "--observations",
            minimum_argument.as_str(),
        ])
        .unwrap();
        assert!(validate_arguments(&parsed.arguments).is_ok());
        assert_eq!(
            selected_observation_counts(&parsed.arguments),
            vec![minimum, maximum]
        );

        let below_minimum = (minimum - 1).to_string();
        let invalid =
            TestCommand::try_parse_from(["test", "index.t3ix", "--observations", &below_minimum])
                .unwrap();
        assert!(validate_arguments(&invalid.arguments).is_err());
    }

    #[test]
    fn production_index_default_matrix_has_boundaries_and_centers_when_available() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let path = root.join("build/index/om1-300f4-to-45.t3ix");
        if !path.is_file() {
            return;
        }
        let file = File::open(path).unwrap();
        // SAFETY: this test owns the read-only mapping for the lifetime of the view.
        let mapping = unsafe { memmap2::MmapOptions::new().map(&file) }.unwrap();
        let mapped = MappedIndex::from_mmap(mapping).unwrap();
        let view = mapped.view();
        let specs = build_case_specs(view, &[]).unwrap();
        assert_eq!(view.level_count(), 8);
        assert_eq!(
            specs
                .iter()
                .filter(|value| value.kind == CaseKind::LevelBoundary)
                .count(),
            9
        );
        assert_eq!(
            specs
                .iter()
                .filter(|value| value.kind == CaseKind::LevelGeometricCenter)
                .count(),
            8
        );
        assert_eq!(specs.len(), 17);
        for spec in specs {
            assert!(
                view.level_for_fov_mdeg(spec.routing_fov_mdeg)
                    .unwrap()
                    .is_some()
            );
        }
    }

    #[test]
    fn atomic_json_output_replaces_complete_document() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("report.json");
        write_json_atomic(&path, &serde_json::json!({"run": 1})).unwrap();
        write_json_atomic(&path, &serde_json::json!({"run": 2, "ok": true})).unwrap();
        let value: serde_json::Value =
            serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        assert_eq!(value, serde_json::json!({"run": 2, "ok": true}));
    }

    #[test]
    fn output_must_not_replace_index_or_lens_input() {
        let directory = tempfile::tempdir().unwrap();
        let index = directory.path().join("index.t3ix");
        let lens = directory.path().join("profiles.t3lf");
        std::fs::write(&index, b"index").unwrap();
        std::fs::write(&lens, b"lens").unwrap();

        let direct_index = TestCommand::try_parse_from([
            "test",
            index.to_str().unwrap(),
            "--output",
            index.to_str().unwrap(),
        ])
        .unwrap();
        assert!(validate_output_separation(&direct_index.arguments).is_err());

        let direct_lens = TestCommand::try_parse_from([
            "test",
            index.to_str().unwrap(),
            "--lens-database",
            lens.to_str().unwrap(),
            "--lens-profile-id",
            "profile",
            "--focal-length-mm",
            "8",
            "--output",
            lens.to_str().unwrap(),
        ])
        .unwrap();
        assert!(validate_output_separation(&direct_lens.arguments).is_err());

        let safe_output = directory.path().join("report.json");
        let safe = TestCommand::try_parse_from([
            "test",
            index.to_str().unwrap(),
            "--output",
            safe_output.to_str().unwrap(),
        ])
        .unwrap();
        assert!(validate_output_separation(&safe.arguments).is_ok());
    }

    #[cfg(unix)]
    #[test]
    fn output_rejects_symlink_and_hard_link_input_aliases() {
        use std::os::unix::fs::symlink;

        let directory = tempfile::tempdir().unwrap();
        let index = directory.path().join("index.t3ix");
        let symlink_alias = directory.path().join("index-symlink.json");
        let hard_link_alias = directory.path().join("index-hardlink.json");
        std::fs::write(&index, b"index").unwrap();
        symlink(&index, &symlink_alias).unwrap();
        std::fs::hard_link(&index, &hard_link_alias).unwrap();

        for output in [&symlink_alias, &hard_link_alias] {
            let parsed = TestCommand::try_parse_from([
                "test",
                index.to_str().unwrap(),
                "--output",
                output.to_str().unwrap(),
            ])
            .unwrap();
            assert!(validate_output_separation(&parsed.arguments).is_err());
        }
    }
}
