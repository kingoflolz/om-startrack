//! Bounded lost-in-space search, verification, and attitude refinement.

use std::{
    cell::{Cell, RefCell},
    cmp::Ordering,
    collections::{BinaryHeap, HashSet},
    fmt,
    ops::ControlFlow,
    vec,
    vec::Vec,
};

use crate::{
    AttitudePair, CatalogQuery, CatalogQueryStats, CatalogStar, Invariant, MathError,
    PatternCandidate, PatternIndex, PatternQuery, Quat, QueryStats, StarCatalog, StarId, UnitVec3,
    estimate_attitude,
};

const PERMUTATIONS: [[usize; 4]; 24] = [
    [0, 1, 2, 3],
    [0, 1, 3, 2],
    [0, 2, 1, 3],
    [0, 2, 3, 1],
    [0, 3, 1, 2],
    [0, 3, 2, 1],
    [1, 0, 2, 3],
    [1, 0, 3, 2],
    [1, 2, 0, 3],
    [1, 2, 3, 0],
    [1, 3, 0, 2],
    [1, 3, 2, 0],
    [2, 0, 1, 3],
    [2, 0, 3, 1],
    [2, 1, 0, 3],
    [2, 1, 3, 0],
    [2, 3, 0, 1],
    [2, 3, 1, 0],
    [3, 0, 1, 2],
    [3, 0, 2, 1],
    [3, 1, 0, 2],
    [3, 1, 2, 0],
    [3, 2, 0, 1],
    [3, 2, 1, 0],
];

/// One extracted star-like source. `flux` is a positive relative weight, not a
/// calibrated magnitude; the solver only uses its ordering and square root.
#[derive(Clone, Copy, Debug)]
pub struct Observation {
    pub ray: UnitVec3,
    pub flux: f32,
}

/// Immutable inputs for one lost-in-space solve.
#[derive(Clone, Copy, Debug)]
pub struct SolveRequest<'a> {
    pub observations: &'a [Observation],
    /// Conservative maximum angle from camera +Z to any usable image point
    /// after every enabled candidate-local calibration adjustment. Catalog
    /// coverage and density estimation use this radius.
    pub field_radius_rad: f64,
    /// Fixed rectilinear plane-radius normalization for the residual radial
    /// model, expressed as an angle whose tangent is `R`. This is deliberately
    /// separate from `field_radius_rad`: a positive fitted radial coefficient
    /// can expand the calibrated footprint beyond the normalization radius.
    /// Callers that disable radial calibration should normally set this equal
    /// to `field_radius_rad`.
    pub rectilinear_radial_reference_rad: f64,
    /// Optional maximum angular separation of any two observations in a seed
    /// tetrahedron. This lets an image wider than the largest indexed field be
    /// searched as overlapping angular subfields without cropping or rotating
    /// its rays. It gates seed generation only: candidate verification and
    /// attitude refinement still use every selected observation.
    ///
    /// `None` preserves the ordinary whole-field quad order exactly. A
    /// storage adapter should include its edge quantization and lookup
    /// tolerance when deriving this bound so it cannot prune a pattern that
    /// the index query could otherwise return.
    pub max_pattern_edge_rad: Option<f64>,
}

/// Hard work/memory limits. Reaching one stops the search and sets
/// `budget_exhausted`. Online verification fails closed if a budget prevents
/// completion of the accepting seed query's same-pattern ambiguity group.
#[derive(Clone, Copy, Debug)]
pub struct SolveBudgets {
    /// Brightest observations retained for final verification.
    pub max_observations: usize,
    /// Brightest retained observations eligible for seed-pattern search.
    /// Keeping this below `max_observations` bounds search work while allowing
    /// additional faint detections to contribute independent verification.
    pub max_pattern_observations: usize,
    pub max_quads: u32,
    pub max_candidates: u32,
    pub max_hypotheses: u32,
    /// Maximum seed-consistent hypotheses in one atomic online index-query
    /// group. Reaching this bound fails closed before reporting uniqueness.
    pub max_pending_hypotheses: usize,
    pub max_index_records: u64,
    pub max_verification_pairs: usize,
    pub max_catalog_cells: u64,
    pub max_catalog_records: u64,
    pub max_catalog_bytes: u64,
    pub max_index_bytes: u64,
}

impl Default for SolveBudgets {
    fn default() -> Self {
        Self {
            max_observations: 32,
            max_pattern_observations: 32,
            max_quads: 512,
            max_candidates: 8_192,
            max_hypotheses: 32_768,
            max_pending_hypotheses: 4_096,
            max_index_records: 1_000_000,
            max_verification_pairs: 4_096,
            max_catalog_cells: 65_536,
            max_catalog_records: 65_536,
            max_catalog_bytes: 16 * 1024 * 1024,
            max_index_bytes: 32 * 1024 * 1024,
        }
    }
}

/// Statistical acceptance schedule for a candidate attitude.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VerificationPolicy {
    /// Verify one complete selected field. The optional rejecting screen is
    /// applied first when `validation_screen_observations` is nonzero.
    CompleteField,
    /// Advance seed-frozen hypotheses cumulatively in brightness order and
    /// retain the first crossing of an anytime-valid evidence process.
    /// A crossing for one fixed hypothesis is sticky: later observations
    /// cannot revoke it. Seed queries are handled in deterministic order and
    /// each query's complete sibling group is verified before the next query,
    /// so an appended tail can add work or let an earlier query preempt a
    /// solution from a later query.
    OnlineCumulative,
}

// The complete-field policy retains the legacy power-of-two spatial stages.
// Online cumulative search instead admits every successive brightness rank as
// one direct, append-stable frontier below.
const SPATIAL_PATTERN_PREFIXES: [usize; 6] = [4, 8, 16, 32, 64, 128];

fn is_online_pattern_stage(prefix: usize) -> bool {
    prefix >= 4
}

/// Geometric gates and false-match policy.
#[derive(Clone, Copy, Debug)]
pub struct SolveConfig {
    pub budgets: SolveBudgets,
    pub verification_policy: VerificationPolicy,
    /// Number of brightest observations used for the inexpensive candidate
    /// verification screen. The screen may reject a hypothesis, but it never
    /// supplies accepted matches, refinement pairs, or the reported chance
    /// probability. Every selected observation is used by the final
    /// verification pass. Like any staged screen, this trades some recall for
    /// speed when bright detections are unrepresentative. Zero disables it.
    pub validation_screen_observations: usize,
    /// Absolute tolerance per one of the five normalized tetra ratios.
    pub ratio_tolerance: f64,
    /// Relative tolerance for the unnormalized largest pattern edge.
    pub largest_edge_relative_tolerance: f64,
    /// Maximum candidate-specific adjustment to rectilinear image-plane
    /// scale. Zero preserves the caller-projected rays exactly. A positive
    /// value fits one scale in `[1 - delta, 1 + delta]` from each indexed
    /// quad's absolute angular edge before attitude fitting and verification.
    pub rectilinear_fov_scale_max_delta: f64,
    /// Maximum absolute normalized cubic radial coefficient fitted together
    /// with rectilinear scale from each four-star seed. For baseline plane
    /// radius `r` and fixed reference `R`, candidate rays use
    /// `p' = scale * (1 + k*r^2/R^2) * p` with `k` in this symmetric bound.
    /// This is a post-retrieval fit envelope, not an index-acquisition
    /// envelope: the uncalibrated seed must first reach the candidate through
    /// `ratio_tolerance` and `largest_edge_relative_tolerance`. Zero disables
    /// radial self-calibration and preserves scale-only behavior.
    pub rectilinear_radial_max_abs: f64,
    /// Maximum residual for every one of the four seed stars.
    pub seed_tolerance_rad: f64,
    /// Verification association radius.
    pub match_tolerance_rad: f64,
    /// Required one-to-one matches *excluding* all four seed observations.
    /// Four means a normal accepted solution has at least eight detections in
    /// total: four seed stars plus four independent checks.
    pub min_matches: usize,
    /// Maximum family-wise-adjusted binomial chance probability.
    pub max_chance_probability: f64,
    /// Used when a catalog does not expose a density bound. Online cumulative
    /// verification requires this to satisfy the same conditional upper-bound
    /// contract as [`StarCatalog::density_hint_per_steradian`].
    pub fallback_density_per_steradian: f64,
    /// Huber transition for refitting; zero disables robust down-weighting.
    pub huber_rad: f64,
    pub refinement_iterations: u8,
    /// Hypotheses closer than this are deduplicated as one solution cluster.
    pub same_solution_tolerance_rad: f64,
    /// Runner-up RMS may be this multiple of the winner and remain ambiguous.
    pub ambiguity_rms_ratio: f64,
    /// A runner-up within this many matches remains ambiguous.
    pub ambiguity_match_margin: usize,
    /// Additional multiplicity for caller-driven repeated solves, such as a
    /// FOV/focal grid search. Set this to at least the number of trials.
    pub external_trial_factor: f64,
}

impl Default for SolveConfig {
    fn default() -> Self {
        Self {
            budgets: SolveBudgets::default(),
            verification_policy: VerificationPolicy::CompleteField,
            validation_screen_observations: 32,
            ratio_tolerance: 3.0 / 255.0,
            largest_edge_relative_tolerance: 0.03,
            rectilinear_fov_scale_max_delta: 0.0,
            rectilinear_radial_max_abs: 0.0,
            seed_tolerance_rad: 90.0_f64.to_radians() / 3_600.0,
            match_tolerance_rad: 75.0_f64.to_radians() / 3_600.0,
            min_matches: 4,
            max_chance_probability: 1.0e-6,
            // Roughly Gaia DR3 G<=12 all-sky density. Catalog-provided local
            // density is preferred because the Milky Way is highly uneven.
            fallback_density_per_steradian: 250_000.0,
            huber_rad: 25.0_f64.to_radians() / 3_600.0,
            refinement_iterations: 2,
            same_solution_tolerance_rad: 120.0_f64.to_radians() / 3_600.0,
            ambiguity_rms_ratio: 1.5,
            ambiguity_match_margin: 0,
            external_trial_factor: 1.0,
        }
    }
}

/// One independent one-to-one observed/catalog association. Seed observations
/// are deliberately absent from `Solution::matches`.
#[derive(Clone, Copy, Debug)]
pub struct Match {
    pub observation_index: usize,
    pub star_id: StarId,
    pub catalog_direction: UnitVec3,
    pub residual_rad: f64,
}

/// Accepted attitude and its independently verified evidence.
#[derive(Clone, Debug)]
pub struct Solution {
    /// Active right-handed rotation: `world = camera_to_world * camera`.
    pub camera_to_world: Quat,
    /// Rectilinear image-plane scale for this solution. Direct ray solves use
    /// the incoming rays as scale one; pixel adapters compose their initial
    /// projection scale into this value before returning it.
    pub fov_scale: f64,
    /// Seed-fitted normalized cubic radial coefficient `k`. Zero means no
    /// residual radial adjustment beyond the caller-projected rays.
    pub rectilinear_radial_coefficient: f64,
    /// Angular reference whose tangent is `R` in the normalized radial model.
    pub rectilinear_radial_reference_rad: f64,
    /// The four associations used to seed this particular hypothesis. This is
    /// not independent verification evidence.
    pub seed_matches: [Match; 4],
    /// Held-out associations, excluding every entry in `seed_matches`.
    pub matches: Vec<Match>,
    pub rms_residual_rad: f64,
    pub max_residual_rad: f64,
    /// Raw coincidence probability for complete-field verification, or the
    /// reciprocal running-maximum e-value for online cumulative verification.
    pub raw_chance_probability: f64,
    /// Family-wise probability adjusted for every applicable multiplicity.
    pub chance_probability: f64,
}

/// Counters are saturating so even a malformed external index cannot wrap
/// accounting in release builds.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Diagnostics {
    pub quads_tested: u32,
    pub index_queries: u32,
    pub index_buckets_touched: u64,
    pub index_records_scanned: u64,
    pub index_bytes_read: u64,
    pub candidates_tested: u32,
    pub hypotheses_tested: u32,
    /// Peak number of seed-consistent models simultaneously retained by the
    /// online verifier, including the current atomic index-query group.
    pub pending_hypotheses_peak: usize,
    pub verification_pairs: u64,
    pub catalog_cells_scanned: u64,
    pub catalog_records_scanned: u64,
    pub catalog_bytes_read: u64,
    /// Brightness prefix available when the sticky online acceptance first
    /// became reachable. Zero means no online hypothesis was accepted.
    pub accepted_observation_prefix: usize,
    /// Canonical brightness prefix at which the evidence process crossed.
    /// This can be smaller than `accepted_observation_prefix` when a newly
    /// admitted seed catches up brighter non-seed observations.
    pub accepted_evidence_prefix: usize,
    /// The complete seed-pattern index query that produced the first crossing
    /// was evaluated through every admitted seed-consistent sibling.
    pub ambiguity_query_completed: bool,
    pub budget_exhausted: bool,
    /// Search stopped after completing the first observed quad's full pattern
    /// query that produced an accepted solution. Later observed quads were not
    /// examined for additional ambiguous attitudes.
    pub search_stopped_after_solution: bool,
    /// A cooperative deadline callback requested termination. Deadlines are
    /// deliberately checked only at natural solver boundaries, so callers
    /// should allow modest overshoot.
    pub deadline_exceeded: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NoMatchReason {
    InsufficientObservations,
    SearchExhausted,
    RejectedByVerification,
    BudgetExhausted,
    DeadlineExceeded,
}

/// A separated, comparably good runner-up is surfaced rather than hidden
/// behind one arbitrarily selected attitude.
#[derive(Clone, Debug)]
#[allow(clippy::large_enum_variant)]
pub enum SolveOutcome {
    Solved {
        solution: Solution,
        diagnostics: Diagnostics,
    },
    NoMatch {
        reason: NoMatchReason,
        diagnostics: Diagnostics,
    },
    Ambiguous {
        best: Solution,
        runner_up: Solution,
        diagnostics: Diagnostics,
    },
}

/// Fatal input or backing-store failure. A legitimate failure to find a plate
/// is represented by [`SolveOutcome::NoMatch`], not an error.
#[derive(Debug)]
pub enum SolveError<IndexError, CatalogError> {
    InvalidConfig(&'static str),
    InvalidFieldRadius,
    InvalidMaxPatternEdge,
    InvalidObservation { index: usize },
    Index(IndexError),
    Catalog(CatalogError),
    Math(MathError),
    InternalInvariant(&'static str),
}

impl SolveBudgets {
    fn remaining_index(self, diagnostics: &mut Diagnostics) -> Option<(u32, u64)> {
        let records = self
            .max_index_records
            .saturating_sub(diagnostics.index_records_scanned);
        let bytes = self
            .max_index_bytes
            .saturating_sub(diagnostics.index_bytes_read);
        if records == 0 || bytes == 0 {
            diagnostics.budget_exhausted = true;
            None
        } else {
            Some((records.min(u64::from(u32::MAX)) as u32, bytes))
        }
    }

    fn charge_index(self, diagnostics: &mut Diagnostics, stats: QueryStats) -> bool {
        diagnostics.index_buckets_touched = diagnostics
            .index_buckets_touched
            .saturating_add(u64::from(stats.buckets_touched));
        diagnostics.index_records_scanned = diagnostics
            .index_records_scanned
            .saturating_add(u64::from(stats.records_scanned));
        diagnostics.index_bytes_read = diagnostics
            .index_bytes_read
            .saturating_add(stats.bytes_read);

        let exhausted = stats.budget_exhausted
            || diagnostics.index_records_scanned >= self.max_index_records
            || diagnostics.index_bytes_read >= self.max_index_bytes;
        diagnostics.budget_exhausted |= exhausted;
        exhausted
    }

    fn remaining_catalog(self, diagnostics: &mut Diagnostics) -> Option<(u32, u32, u64)> {
        let cells = self
            .max_catalog_cells
            .saturating_sub(diagnostics.catalog_cells_scanned);
        let records = self
            .max_catalog_records
            .saturating_sub(diagnostics.catalog_records_scanned);
        let bytes = self
            .max_catalog_bytes
            .saturating_sub(diagnostics.catalog_bytes_read);
        if cells == 0 || records == 0 || bytes == 0 {
            diagnostics.budget_exhausted = true;
            None
        } else {
            Some((
                cells.min(u64::from(u32::MAX)) as u32,
                records.min(u64::from(u32::MAX)) as u32,
                bytes,
            ))
        }
    }

    fn charge_catalog(self, diagnostics: &mut Diagnostics, stats: CatalogQueryStats) {
        diagnostics.catalog_cells_scanned = diagnostics
            .catalog_cells_scanned
            .saturating_add(u64::from(stats.cells_scanned));
        diagnostics.catalog_records_scanned = diagnostics
            .catalog_records_scanned
            .saturating_add(u64::from(stats.records_scanned));
        diagnostics.catalog_bytes_read = diagnostics
            .catalog_bytes_read
            .saturating_add(stats.bytes_read);
        diagnostics.budget_exhausted |= stats.budget_exhausted;
    }
}

struct SearchState<'a, D> {
    request: SolveRequest<'a>,
    config: &'a SolveConfig,
    selected: &'a [usize],
    diagnostics: Diagnostics,
    best: Option<Solution>,
    runner_up: Option<Solution>,
    matching_seen_scratch: Vec<bool>,
    deadline_expired: D,
}

#[derive(Clone, Copy)]
struct SeedQuery {
    observation_indices: [usize; 4],
    rays: [UnitVec3; 4],
    fov_scale_min: f64,
    fov_scale_max: f64,
    largest_edge_min_rad: f64,
    largest_edge_max_rad: f64,
}

impl<'a, D: FnMut() -> bool> SearchState<'a, D> {
    fn new(
        request: SolveRequest<'a>,
        config: &'a SolveConfig,
        selected: &'a [usize],
        deadline_expired: D,
    ) -> Self {
        Self {
            request,
            config,
            selected,
            diagnostics: Diagnostics::default(),
            best: None,
            runner_up: None,
            matching_seen_scratch: Vec::new(),
            deadline_expired,
        }
    }

    fn deadline_reached(&mut self) -> bool {
        if self.diagnostics.deadline_exceeded {
            return true;
        }
        if (self.deadline_expired)() {
            self.diagnostics.deadline_exceeded = true;
            true
        } else {
            false
        }
    }
}

fn poll_shared_deadline<D: FnMut() -> bool>(
    deadline_expired: &RefCell<&mut D>,
    deadline_hit: &Cell<bool>,
) -> bool {
    if deadline_hit.get() {
        return true;
    }
    let expired = {
        let mut callback = deadline_expired.borrow_mut();
        (*callback)()
    };
    deadline_hit.set(expired);
    expired
}

/// Return at most `maximum` source indices ordered by descending flux, with
/// the original slice position breaking equal-flux ties.
///
/// Keeping this helper shared with the pixel projection adapter prevents the
/// adapter and the geometric solver from choosing different stars at the
/// observation budget boundary. The allocation is bounded by `maximum` even
/// when the caller supplies a much larger detection slice.
pub(crate) fn brightest_indices(
    length: usize,
    maximum: usize,
    mut flux_at: impl FnMut(usize) -> f32,
) -> Result<Vec<usize>, usize> {
    let mut selected = Vec::with_capacity(maximum.min(length));
    for observation_index in 0..length {
        let new_flux = flux_at(observation_index);
        if !new_flux.is_finite() || new_flux <= 0.0 {
            return Err(observation_index);
        }
        let insertion = selected
            .iter()
            .position(|existing| {
                let old_flux = flux_at(*existing);
                new_flux > old_flux || (new_flux == old_flux && observation_index < *existing)
            })
            .unwrap_or(selected.len());
        if insertion < maximum {
            // Remove the displaced tail before inserting so the vector never
            // needs capacity for K+1 elements, even transiently.
            if selected.len() == maximum {
                let _ = selected.pop();
            }
            selected.insert(insertion, observation_index);
        }
    }
    Ok(selected)
}

impl<I: fmt::Display, C: fmt::Display> fmt::Display for SolveError<I, C> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidConfig(message) => write!(f, "invalid solver configuration: {message}"),
            Self::InvalidFieldRadius => f.write_str("invalid field radius"),
            Self::InvalidMaxPatternEdge => f.write_str("invalid maximum pattern edge"),
            Self::InvalidObservation { index } => write!(f, "invalid observation at index {index}"),
            Self::Index(error) => write!(f, "pattern index error: {error}"),
            Self::Catalog(error) => write!(f, "star catalog error: {error}"),
            Self::Math(error) => write!(f, "attitude math error: {error}"),
            Self::InternalInvariant(message) => {
                write!(f, "internal solver invariant failed: {message}")
            }
        }
    }
}

impl<I, C> std::error::Error for SolveError<I, C>
where
    I: std::error::Error + 'static,
    C: std::error::Error + 'static,
{
}

/// Search a streaming pattern index and independently verify candidates using
/// local catalog cone reads. Neither the full index nor catalog is materialized.
pub fn solve<I, C>(
    index: &I,
    catalog: &C,
    request: SolveRequest<'_>,
    config: &SolveConfig,
) -> Result<SolveOutcome, SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
{
    solve_with_deadline(index, catalog, request, config, || false)
}

/// Equivalent to [`solve`], with cooperative deadline checks at natural
/// search, candidate, verification, and refinement boundaries.
///
/// Returning `true` from `deadline_expired` stops further work. If a fully
/// verified solution was already accepted it is returned with
/// [`Diagnostics::deadline_exceeded`] set; otherwise the result is
/// [`NoMatchReason::DeadlineExceeded`]. Once final verification accepts a
/// hypothesis, its configured all-match refinement is completed atomically
/// before the deadline is honored. The callback is intentionally independent
/// of a particular clock so callers can choose their own time source.
pub fn solve_with_deadline<I, C, D>(
    index: &I,
    catalog: &C,
    request: SolveRequest<'_>,
    config: &SolveConfig,
    deadline_expired: D,
) -> Result<SolveOutcome, SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
    D: FnMut() -> bool,
{
    validate_config(config).map_err(SolveError::InvalidConfig)?;
    if !request.field_radius_rad.is_finite()
        || !(0.0..=core::f64::consts::PI).contains(&request.field_radius_rad)
        || request.field_radius_rad == 0.0
    {
        return Err(SolveError::InvalidFieldRadius);
    }
    if !request.rectilinear_radial_reference_rad.is_finite()
        || request.rectilinear_radial_reference_rad <= 0.0
        || request.rectilinear_radial_reference_rad > request.field_radius_rad
        || (config.rectilinear_radial_max_abs != 0.0
            && request.rectilinear_radial_reference_rad >= core::f64::consts::FRAC_PI_2)
    {
        return Err(SolveError::InvalidFieldRadius);
    }
    if request.max_pattern_edge_rad.is_some_and(|maximum| {
        !maximum.is_finite() || maximum <= 0.0 || maximum > core::f64::consts::PI
    }) {
        return Err(SolveError::InvalidMaxPatternEdge);
    }

    // Maintain a bounded top-K instead of allocating once per raw detection.
    // A pathological detector input therefore cannot bypass the memory budget.
    let selected = brightest_indices(
        request.observations.len(),
        config.budgets.max_observations,
        |index| request.observations[index].flux,
    )
    .map_err(|index| SolveError::InvalidObservation { index })?;
    if config.rectilinear_radial_max_abs != 0.0 {
        for &index in selected.iter() {
            apply_rectilinear_calibration(
                request.observations[index].ray,
                1.0,
                config.rectilinear_radial_max_abs,
                request.rectilinear_radial_reference_rad,
            )
            .map_err(|_| SolveError::InvalidObservation { index })?;
        }
    }
    if selected.len() < 4 {
        return Ok(SolveOutcome::NoMatch {
            reason: NoMatchReason::InsufficientObservations,
            diagnostics: Diagnostics::default(),
        });
    }

    let selected = selected.as_slice();
    let mut state = SearchState::new(request, config, selected, deadline_expired);
    match config.verification_policy {
        VerificationPolicy::CompleteField => {
            let pattern_count = selected.len().min(config.budgets.max_pattern_observations);
            search_pattern_phase(index, catalog, &selected[..pattern_count], &mut state)?;
        }
        VerificationPolicy::OnlineCumulative => {
            search_online_brightness_sequence(index, catalog, &mut state)?;
        }
    }

    // Online acceptance and ambiguity are properties of the first complete
    // query-group prefix that crosses. Freeze that decision before optional
    // all-source enrichment: later, noisier detections may add support and
    // improve the local fit, but they must not revoke a solve or hide a
    // runner-up which was comparable at the acceptance boundary.
    let ambiguity_at_acceptance = state.best.as_ref().is_some_and(|best| {
        state
            .runner_up
            .as_ref()
            .is_some_and(|runner_up| comparable_runner(best, runner_up, config))
    });
    if config.verification_policy == VerificationPolicy::OnlineCumulative
        && !ambiguity_at_acceptance
    {
        if let Some(solution) = state.best.take() {
            state.best = Some(enrich_online_solution(catalog, solution, &mut state));
        }
    }

    let SearchState {
        diagnostics,
        best,
        runner_up,
        ..
    } = state;
    if let Some(best) = best {
        if let Some(runner_up) = runner_up {
            if ambiguity_at_acceptance {
                return Ok(SolveOutcome::Ambiguous {
                    best,
                    runner_up,
                    diagnostics,
                });
            }
        }
        return Ok(SolveOutcome::Solved {
            solution: best,
            diagnostics,
        });
    }

    let reason = if diagnostics.deadline_exceeded {
        NoMatchReason::DeadlineExceeded
    } else if diagnostics.budget_exhausted {
        NoMatchReason::BudgetExhausted
    } else if diagnostics.candidates_tested == 0 {
        NoMatchReason::SearchExhausted
    } else {
        NoMatchReason::RejectedByVerification
    };
    Ok(SolveOutcome::NoMatch {
        reason,
        diagnostics,
    })
}

fn search_online_brightness_sequence<I, C, D>(
    index: &I,
    catalog: &C,
    state: &mut SearchState<'_, D>,
) -> Result<(), SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
    D: FnMut() -> bool,
{
    let pattern_limit = state
        .selected
        .len()
        .min(state.config.budgets.max_pattern_observations);
    // Search one seed query at a time and verify its complete candidate group
    // against the immutable selected batch before issuing the next query.
    // A rejected group has no future evidence in this batch solve, so it can
    // be discarded immediately instead of being retained across rank phases.
    let verification_prefix = state.selected.len();
    let mut previous_pattern_prefix = 0;
    for prefix in 4..=state.selected.len() {
        if state.deadline_reached() || state.diagnostics.budget_exhausted {
            break;
        }
        if prefix <= pattern_limit && is_online_pattern_stage(prefix) {
            if state.deadline_reached() {
                break;
            }
            if search_online_pattern_stage(
                index,
                catalog,
                prefix,
                previous_pattern_prefix,
                verification_prefix,
                state,
            )? {
                break;
            }
            previous_pattern_prefix = prefix;
        }
    }
    if !state.diagnostics.budget_exhausted && state.best.is_none() {
        let _ = state.deadline_reached();
    }
    Ok(())
}

fn search_online_pattern_stage<I, C, D>(
    index: &I,
    catalog: &C,
    prefix: usize,
    previous_prefix: usize,
    verification_prefix: usize,
    state: &mut SearchState<'_, D>,
) -> Result<bool, SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
    D: FnMut() -> bool,
{
    let request = state.request;
    let stage_selected = &state.selected[..prefix];
    let minimum_dot = request
        .max_pattern_edge_rad
        .map_or(-1.0, |maximum_edge| maximum_edge.cos());
    let mut neighborhoods = Vec::with_capacity(stage_selected.len());
    let mut maximum_local_frontier = 0;
    for anchor in 0..stage_selected.len() {
        let anchor_ray = request.observations[stage_selected[anchor]].ray;
        let neighbors: Vec<usize> = (anchor + 1..stage_selected.len())
            .filter(|&rank| {
                rays_within_edge(
                    request.observations[stage_selected[rank]].ray,
                    anchor_ray,
                    minimum_dot,
                )
            })
            .collect();
        maximum_local_frontier = maximum_local_frontier.max(neighbors.len());
        neighborhoods.push(neighbors);
    }
    if previous_prefix + 1 == prefix {
        let newly_admitted_rank = prefix - 1;
        // Only quads containing the one newly admitted rank are new. In the
        // newly admitted rank is necessarily the final (largest) neighbor. Visit anchors
        // and their prior neighbors in brightness order so likely mixed-bright
        // seeds precede faint-only quads, without rescanning any old
        // C(prefix-1, 4) combination.
        for (anchor, neighbors) in neighborhoods.iter().enumerate() {
            if state.deadline_reached() {
                return Ok(true);
            }
            let Some((&last_neighbor, prior_neighbors)) = neighbors.split_last() else {
                continue;
            };
            if last_neighbor != newly_admitted_rank || prior_neighbors.len() < 2 {
                continue;
            }
            for second_position in 1..prior_neighbors.len() {
                let second = prior_neighbors[second_position];
                for &first in &prior_neighbors[..second_position] {
                    if state.diagnostics.budget_exhausted {
                        return Ok(true);
                    }
                    let ranks = [anchor, first, second, newly_admitted_rank];
                    if !quad_within_edge(request.observations, stage_selected, ranks, minimum_dot) {
                        continue;
                    }
                    let seed = ranks.map(|rank| stage_selected[rank]);
                    if search_seed_online(index, catalog, seed, prefix, verification_prefix, state)?
                    {
                        return Ok(true);
                    }
                }
            }
        }
        return Ok(false);
    }
    for local_frontier in 2..maximum_local_frontier {
        if state.deadline_reached() {
            return Ok(true);
        }
        for (anchor, neighbors) in neighborhoods.iter().enumerate() {
            if state.deadline_reached() {
                return Ok(true);
            }
            let Some(&frontier) = neighbors.get(local_frontier) else {
                continue;
            };
            for second_position in 1..local_frontier {
                let second = neighbors[second_position];
                for &first in &neighbors[..second_position] {
                    if state.diagnostics.budget_exhausted {
                        return Ok(true);
                    }
                    let ranks = [anchor, first, second, frontier];
                    if ranks.iter().all(|rank| *rank < previous_prefix)
                        || !quad_within_edge(
                            request.observations,
                            stage_selected,
                            ranks,
                            minimum_dot,
                        )
                    {
                        continue;
                    }
                    let seed = ranks.map(|rank| stage_selected[rank]);
                    if search_seed_online(index, catalog, seed, prefix, verification_prefix, state)?
                    {
                        return Ok(true);
                    }
                }
            }
        }
    }
    Ok(false)
}

fn search_pattern_phase<I, C, D>(
    index: &I,
    catalog: &C,
    pattern_selected: &[usize],
    state: &mut SearchState<'_, D>,
) -> Result<(), SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
    D: FnMut() -> bool,
{
    let request = state.request;
    let config = state.config;
    // Complete every combination within the brightest fixed stage before
    // expanding. Unlike ordinary lexicographic order, this reaches quads that
    // exclude one or more bright false detections early.
    if request.max_pattern_edge_rad.is_some()
        || config.verification_policy == VerificationPolicy::OnlineCumulative
    {
        let minimum_dot = request
            .max_pattern_edge_rad
            .map_or(-1.0, |maximum_edge| maximum_edge.cos());
        let mut spatial_prefixes: Vec<usize> = SPATIAL_PATTERN_PREFIXES
            .iter()
            .copied()
            .filter(|prefix| *prefix <= pattern_selected.len())
            .collect();
        if config.verification_policy == VerificationPolicy::CompleteField
            && spatial_prefixes.last().copied() != Some(pattern_selected.len())
        {
            spatial_prefixes.push(pattern_selected.len());
        }
        let mut previous_prefix = 0;
        'search: for prefix in spatial_prefixes {
            let stage_selected = &pattern_selected[..prefix];
            let mut neighborhoods = Vec::with_capacity(stage_selected.len());
            let mut maximum_local_frontier = 0;
            for anchor in 0..stage_selected.len() {
                if state.deadline_reached() {
                    break 'search;
                }
                let anchor_ray = request.observations[stage_selected[anchor]].ray;
                let neighbors: Vec<usize> = (anchor + 1..stage_selected.len())
                    .filter(|&rank| {
                        rays_within_edge(
                            request.observations[stage_selected[rank]].ray,
                            anchor_ray,
                            minimum_dot,
                        )
                    })
                    .collect();
                maximum_local_frontier = maximum_local_frontier.max(neighbors.len());
                neighborhoods.push(neighbors);
            }
            for local_frontier in 2..maximum_local_frontier {
                for (anchor, neighbors) in neighborhoods.iter().enumerate() {
                    let Some(&frontier) = neighbors.get(local_frontier) else {
                        continue;
                    };
                    for second_position in 1..local_frontier {
                        let second = neighbors[second_position];
                        for &first in &neighbors[..second_position] {
                            if state.deadline_reached() {
                                break 'search;
                            }
                            let ranks = [anchor, first, second, frontier];
                            if ranks.iter().all(|rank| *rank < previous_prefix) {
                                continue;
                            }
                            if !quad_within_edge(
                                request.observations,
                                stage_selected,
                                ranks,
                                minimum_dot,
                            ) {
                                continue;
                            }
                            let seed = ranks.map(|rank| stage_selected[rank]);
                            if search_seed(index, catalog, seed, state)? {
                                break 'search;
                            }
                        }
                    }
                }
            }
            previous_prefix = prefix;
        }
    } else {
        'search: for d in 3..pattern_selected.len() {
            for c in 2..d {
                for b in 1..c {
                    for a in 0..b {
                        let seed = [
                            pattern_selected[a],
                            pattern_selected[b],
                            pattern_selected[c],
                            pattern_selected[d],
                        ];
                        if search_seed(index, catalog, seed, state)? {
                            break 'search;
                        }
                    }
                }
            }
        }
    }
    Ok(())
}

fn quad_within_edge(
    observations: &[Observation],
    selected: &[usize],
    ranks: [usize; 4],
    minimum_dot: f64,
) -> bool {
    let rays = ranks.map(|rank| observations[selected[rank]].ray);
    rays_within_edge(rays[0], rays[1], minimum_dot)
        && rays_within_edge(rays[0], rays[2], minimum_dot)
        && rays_within_edge(rays[0], rays[3], minimum_dot)
        && rays_within_edge(rays[1], rays[2], minimum_dot)
        && rays_within_edge(rays[1], rays[3], minimum_dot)
        && rays_within_edge(rays[2], rays[3], minimum_dot)
}

fn rays_within_edge(left: UnitVec3, right: UnitVec3, minimum_dot: f64) -> bool {
    left.dot(right).clamp(-1.0, 1.0) >= minimum_dot
}

fn rectilinear_scale_ray(ray: UnitVec3, scale: f64) -> Result<UnitVec3, MathError> {
    if scale == 1.0 {
        return Ok(ray);
    }
    let vector = ray.as_vec();
    UnitVec3::new(vector.x * scale, vector.y * scale, vector.z)
}

/// Apply the bounded residual rectilinear calibration used by the solver.
///
/// When `radial_coefficient` is zero this is exactly the legacy plane-scale
/// operation and `radial_reference_rad` is ignored. A nonzero radial term is
/// accepted only for forward rays inside the declared reference field; with
/// `|k| <= 0.25` this keeps the radial map monotone and fold-free.
pub fn apply_rectilinear_calibration(
    ray: UnitVec3,
    fov_scale: f64,
    radial_coefficient: f64,
    radial_reference_rad: f64,
) -> Result<UnitVec3, MathError> {
    if radial_coefficient == 0.0 {
        return rectilinear_scale_ray(ray, fov_scale);
    }
    if !fov_scale.is_finite()
        || fov_scale <= 0.0
        || !radial_coefficient.is_finite()
        || radial_coefficient.abs() > 0.25
        || !radial_reference_rad.is_finite()
        || radial_reference_rad <= 0.0
        || radial_reference_rad >= core::f64::consts::FRAC_PI_2
    {
        return Err(MathError::NonFinite);
    }
    let reference_squared = radial_reference_rad.tan().powi(2);
    let vector = ray.as_vec();
    if !vector.z.is_finite() || vector.z <= 0.0 {
        return Err(MathError::NonFinite);
    }
    let x = vector.x / vector.z;
    let y = vector.y / vector.z;
    let radius_squared = x * x + y * y;
    if !radius_squared.is_finite()
        || radius_squared > reference_squared * (1.0 + 32.0 * f64::EPSILON)
    {
        return Err(MathError::NonFinite);
    }
    let radial_scale = fov_scale * (1.0 + radial_coefficient * radius_squared / reference_squared);
    if !radial_scale.is_finite() || radial_scale <= 0.0 {
        return Err(MathError::NonFinite);
    }
    UnitVec3::new(x * radial_scale, y * radial_scale, 1.0)
}

#[derive(Clone, Copy, Debug)]
struct RectilinearCalibration {
    fov_scale: f64,
    radial_coefficient: f64,
    radial_reference_rad: f64,
}

impl RectilinearCalibration {
    fn calibrate_ray(self, ray: UnitVec3) -> Result<UnitVec3, MathError> {
        apply_rectilinear_calibration(
            ray,
            self.fov_scale,
            self.radial_coefficient,
            self.radial_reference_rad,
        )
    }
}

fn invariant_at_rectilinear_scale(rays: [UnitVec3; 4], scale: f64) -> Option<Invariant> {
    let scaled = rays.map(|ray| rectilinear_scale_ray(ray, scale));
    let scaled = [
        scaled[0].ok()?,
        scaled[1].ok()?,
        scaled[2].ok()?,
        scaled[3].ok()?,
    ];
    Invariant::from_rays(scaled).ok()
}

fn invariant_at_rectilinear_calibration(
    rays: [UnitVec3; 4],
    calibration: RectilinearCalibration,
) -> Option<Invariant> {
    let calibrated = rays.map(|ray| calibration.calibrate_ray(ray));
    let calibrated = [
        calibrated[0].ok()?,
        calibrated[1].ok()?,
        calibrated[2].ok()?,
        calibrated[3].ok()?,
    ];
    Invariant::from_rays(calibrated).ok()
}

fn invariant_chords(invariant: Invariant) -> [f64; 6] {
    let largest = invariant.largest_edge_rad();
    let ratios = invariant.ratios();
    let edges = [
        ratios[0] * largest,
        ratios[1] * largest,
        ratios[2] * largest,
        ratios[3] * largest,
        ratios[4] * largest,
        largest,
    ];
    edges.map(|edge| 2.0 * (0.5 * edge).sin())
}

/// Fit one absolute image-plane scale to an indexed quad. This is the bounded
/// equivalent of legacy ESA tetra3's candidate-local six-edge FOV fit:
/// normalized ratios retrieve the candidate, then its stored absolute angular
/// edges set the scale before correspondence and field verification.
fn fit_candidate_rectilinear_scale(
    rays: [UnitVec3; 4],
    catalog_invariant: Invariant,
    minimum_scale: f64,
    maximum_scale: f64,
) -> Option<(f64, Invariant)> {
    let catalog_chords = invariant_chords(catalog_invariant);
    let evaluate = |scale: f64| {
        let invariant = invariant_at_rectilinear_scale(rays, scale)?;
        let image_chords = invariant_chords(invariant);
        let objective = image_chords
            .iter()
            .zip(catalog_chords)
            .map(|(image, catalog)| {
                let residual = image - catalog;
                residual * residual
            })
            .sum::<f64>();
        objective.is_finite().then_some((objective, invariant))
    };

    if minimum_scale == maximum_scale {
        return evaluate(minimum_scale).map(|(_, invariant)| (minimum_scale, invariant));
    }

    const GOLDEN_RATIO_CONJUGATE: f64 = 0.618_033_988_749_894_9;
    // The production verification gate is only 75 arcseconds. With an eight-
    // percent acquisition interval, 16 golden iterations can leave enough
    // scale error to move a marginal held-out star across that boundary.
    const ITERATIONS: usize = 32;
    let mut lower = minimum_scale;
    let mut upper = maximum_scale;
    let mut left = upper - (upper - lower) * GOLDEN_RATIO_CONJUGATE;
    let mut right = lower + (upper - lower) * GOLDEN_RATIO_CONJUGATE;
    let mut left_value = evaluate(left)?;
    let mut right_value = evaluate(right)?;
    for _ in 0..ITERATIONS {
        if left_value.0 <= right_value.0 {
            upper = right;
            right = left;
            right_value = left_value;
            left = upper - (upper - lower) * GOLDEN_RATIO_CONJUGATE;
            left_value = evaluate(left)?;
        } else {
            lower = left;
            left = right;
            left_value = right_value;
            right = lower + (upper - lower) * GOLDEN_RATIO_CONJUGATE;
            right_value = evaluate(right)?;
        }
    }
    let (scale, (_, invariant)) = if left_value.0 <= right_value.0 {
        (left, left_value)
    } else {
        (right, right_value)
    };
    Some((scale, invariant))
}

/// Deterministically fit one bounded residual scale-plus-radial model using
/// only the indexed seed's six angular edges. Held-out observations never
/// enter model selection, so their strict verification remains independent.
fn fit_candidate_rectilinear_calibration(
    rays: [UnitVec3; 4],
    catalog_invariant: Invariant,
    minimum_scale: f64,
    maximum_scale: f64,
    radial_max_abs: f64,
    radial_reference_rad: f64,
) -> Option<(RectilinearCalibration, Invariant)> {
    if radial_max_abs == 0.0 {
        let (fov_scale, invariant) =
            fit_candidate_rectilinear_scale(rays, catalog_invariant, minimum_scale, maximum_scale)?;
        return Some((
            RectilinearCalibration {
                fov_scale,
                radial_coefficient: 0.0,
                radial_reference_rad,
            },
            invariant,
        ));
    }
    let reference_squared = radial_reference_rad.tan().powi(2);
    if !reference_squared.is_finite() || reference_squared <= 0.0 {
        return None;
    }
    let catalog_chords = invariant_chords(catalog_invariant);
    let evaluate = |fov_scale: f64, radial_coefficient: f64| {
        let calibration = RectilinearCalibration {
            fov_scale,
            radial_coefficient,
            radial_reference_rad,
        };
        let invariant = invariant_at_rectilinear_calibration(rays, calibration)?;
        let image_chords = invariant_chords(invariant);
        let residuals =
            core::array::from_fn::<_, 6, _>(|index| image_chords[index] - catalog_chords[index]);
        let objective: f64 = residuals.iter().map(|residual| residual * residual).sum();
        objective
            .is_finite()
            .then_some((objective, residuals, invariant))
    };

    // Begin at the deterministic scale-only optimum. Since every accepted LM
    // step must strictly improve its seed-edge objective, enabling the radial
    // degree of freedom can never make the selected seed geometry worse than
    // the already-supported candidate-local scale fit.
    let (mut fov_scale, scale_only_invariant) =
        fit_candidate_rectilinear_scale(rays, catalog_invariant, minimum_scale, maximum_scale)?;
    let scale_only_calibration = RectilinearCalibration {
        fov_scale,
        radial_coefficient: 0.0,
        radial_reference_rad,
    };
    let mut radial_coefficient = 0.0;
    let mut current = evaluate(fov_scale, radial_coefficient)?;
    let mut damping = 1.0e-6;
    const ITERATIONS: usize = 10;
    const SCALE_STEP: f64 = 1.0e-5;
    const RADIAL_STEP: f64 = 1.0e-5;
    for _ in 0..ITERATIONS {
        let bounded_step = |value: f64, lower: f64, upper: f64, preferred: f64| {
            let forward = upper - value;
            if forward > 0.0 {
                preferred.min(forward)
            } else {
                let backward = value - lower;
                if backward > 0.0 {
                    -preferred.min(backward)
                } else {
                    0.0
                }
            }
        };
        let scale_step = bounded_step(fov_scale, minimum_scale, maximum_scale, SCALE_STEP);
        let radial_step = bounded_step(
            radial_coefficient,
            -radial_max_abs,
            radial_max_abs,
            RADIAL_STEP,
        );
        if radial_step == 0.0 {
            break;
        }
        let scale_value = if scale_step == 0.0 {
            current
        } else {
            evaluate(fov_scale + scale_step, radial_coefficient)?
        };
        let radial_value = evaluate(fov_scale, radial_coefficient + radial_step)?;
        let mut normal_00 = 0.0_f64;
        let mut normal_01 = 0.0_f64;
        let mut normal_11 = 0.0_f64;
        let mut gradient_0 = 0.0_f64;
        let mut gradient_1 = 0.0_f64;
        for index in 0..6 {
            let derivative_scale = if scale_step == 0.0 {
                0.0
            } else {
                (scale_value.1[index] - current.1[index]) / scale_step
            };
            let derivative_radial = (radial_value.1[index] - current.1[index]) / radial_step;
            normal_00 += derivative_scale * derivative_scale;
            normal_01 += derivative_scale * derivative_radial;
            normal_11 += derivative_radial * derivative_radial;
            gradient_0 += derivative_scale * current.1[index];
            gradient_1 += derivative_radial * current.1[index];
        }

        // Equal- or nearly equal-radius seed rays make scale and cubic radial
        // response indistinguishable. Select the scale-only model before any
        // held-out observation is consulted instead of allowing LM damping to
        // manufacture an arbitrary radial coefficient along that flat ridge.
        let jacobian_product = normal_00 * normal_11;
        let jacobian_determinant = jacobian_product - normal_01 * normal_01;
        if scale_step != 0.0
            && (!jacobian_product.is_finite()
                || jacobian_product <= 1.0e-30
                || !jacobian_determinant.is_finite()
                || jacobian_determinant <= jacobian_product * 1.0e-8)
        {
            return Some((scale_only_calibration, scale_only_invariant));
        }

        let delta = if scale_step == 0.0 {
            let denominator = normal_11 * (1.0 + damping);
            (denominator > 1.0e-30).then_some((0.0, -gradient_1 / denominator))
        } else {
            let damped_00 = normal_00 + damping * normal_00.max(1.0e-12);
            let damped_11 = normal_11 + damping * normal_11.max(1.0e-12);
            let determinant = damped_00 * damped_11 - normal_01 * normal_01;
            (determinant.is_finite() && determinant > 1.0e-30).then_some((
                (-damped_11 * gradient_0 + normal_01 * gradient_1) / determinant,
                (normal_01 * gradient_0 - damped_00 * gradient_1) / determinant,
            ))
        };
        let Some((delta_scale, delta_radial)) =
            delta.filter(|(scale, radial)| scale.is_finite() && radial.is_finite())
        else {
            break;
        };

        let mut accepted = None;
        for fraction in [1.0, 0.5, 0.25] {
            let trial_scale =
                (fov_scale + fraction * delta_scale).clamp(minimum_scale, maximum_scale);
            let trial_radial = (radial_coefficient + fraction * delta_radial)
                .clamp(-radial_max_abs, radial_max_abs);
            let trial = evaluate(trial_scale, trial_radial)?;
            if trial.0 < current.0 {
                accepted = Some((trial_scale, trial_radial, trial));
                break;
            }
        }
        let Some((next_scale, next_radial, next)) = accepted else {
            damping = (damping * 10.0).min(1.0e12);
            continue;
        };
        let parameter_change = (next_scale - fov_scale)
            .abs()
            .max((next_radial - radial_coefficient).abs());
        fov_scale = next_scale;
        radial_coefficient = next_radial;
        current = next;
        damping = (damping * 0.3).max(1.0e-12);
        if parameter_change < 1.0e-10 {
            break;
        }
    }
    Some((
        RectilinearCalibration {
            fov_scale,
            radial_coefficient,
            radial_reference_rad,
        },
        current.2,
    ))
}

/// Search one already spatially eligible seed. Returning `true` requests that
/// the outer brightness-frontier enumeration stop.
fn search_seed_online<I, C, D>(
    index: &I,
    catalog: &C,
    seed: [usize; 4],
    seed_release_prefix: usize,
    verification_prefix: usize,
    state: &mut SearchState<'_, D>,
) -> Result<bool, SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
    D: FnMut() -> bool,
{
    if state.deadline_reached() {
        return Ok(true);
    }
    if state.diagnostics.quads_tested >= state.config.budgets.max_quads {
        state.diagnostics.budget_exhausted = true;
        return Ok(true);
    }
    state.diagnostics.quads_tested = state.diagnostics.quads_tested.saturating_add(1);
    let request = state.request;
    let config = state.config;
    let rays = seed.map(|observation_index| request.observations[observation_index].ray);
    let invariant = match Invariant::from_rays(rays) {
        Ok(value) => value,
        Err(_) => return Ok(false),
    };
    let scale_delta = config.rectilinear_fov_scale_max_delta;
    let edge = invariant.largest_edge_rad();
    let relative = config.largest_edge_relative_tolerance;
    let edge_min = edge * (1.0 - relative);
    let edge_max =
        (edge * (1.0 + relative)).min(2.0 * request.field_radius_rad + config.seed_tolerance_rad);
    if edge_min > edge_max {
        return Ok(false);
    }
    let Some((max_records_to_scan, max_bytes_to_read)) =
        config.budgets.remaining_index(&mut state.diagnostics)
    else {
        return Ok(true);
    };
    let seed_query = SeedQuery {
        observation_indices: seed,
        rays,
        fov_scale_min: 1.0 - scale_delta,
        fov_scale_max: 1.0 + scale_delta,
        largest_edge_min_rad: edge_min,
        largest_edge_max_rad: edge_max,
    };
    let query = PatternQuery {
        invariant,
        ratio_tolerance: config.ratio_tolerance,
        largest_edge_min_rad: edge_min,
        largest_edge_max_rad: edge_max,
        max_records_to_scan,
        max_bytes_to_read,
    };
    state.diagnostics.index_queries = state.diagnostics.index_queries.saturating_add(1);
    let mut group = Vec::new();
    let mut group_overflow = false;
    let mut callback_error = None;
    let mut stop = false;
    let mut seen_patterns: HashSet<[StarId; 4]> = HashSet::new();
    let (query_stats, deadline_was_hit) = {
        let deadline_hit = Cell::new(state.diagnostics.deadline_exceeded);
        let deadline_expired = RefCell::new(&mut state.deadline_expired);
        let diagnostics = &mut state.diagnostics;
        let query_stats = index
            .visit_candidates_cancelable(
                query,
                &mut || poll_shared_deadline(&deadline_expired, &deadline_hit),
                &mut |candidate| {
                    if poll_shared_deadline(&deadline_expired, &deadline_hit) {
                        stop = true;
                        return ControlFlow::Break(());
                    }
                    if stop || diagnostics.candidates_tested >= config.budgets.max_candidates {
                        diagnostics.budget_exhausted = true;
                        stop = true;
                        return ControlFlow::Break(());
                    }
                    diagnostics.candidates_tested = diagnostics.candidates_tested.saturating_add(1);
                    let canonical = canonical_pattern(candidate.stars.map(|star| star.id));
                    if canonical.windows(2).any(|pair| pair[0] == pair[1]) {
                        return ControlFlow::Continue(());
                    }
                    if !seen_patterns.insert(canonical) {
                        return ControlFlow::Continue(());
                    }
                    match collect_pending_candidate::<I::Error, C>(
                        catalog,
                        candidate,
                        seed_query,
                        &mut group,
                        &mut group_overflow,
                        request,
                        config,
                        diagnostics,
                        &mut || poll_shared_deadline(&deadline_expired, &deadline_hit),
                    ) {
                        Ok(should_stop) => {
                            if should_stop {
                                stop = true;
                                ControlFlow::Break(())
                            } else {
                                ControlFlow::Continue(())
                            }
                        }
                        Err(error) => {
                            callback_error = Some(error);
                            stop = true;
                            ControlFlow::Break(())
                        }
                    }
                },
            )
            .map_err(SolveError::Index)?;
        (query_stats, deadline_hit.get())
    };
    state.diagnostics.deadline_exceeded |= deadline_was_hit;
    let index_exhausted = config
        .budgets
        .charge_index(&mut state.diagnostics, query_stats);
    if let Some(error) = callback_error {
        return Err(error);
    }
    state.diagnostics.pending_hypotheses_peak =
        state.diagnostics.pending_hypotheses_peak.max(group.len());
    if state.diagnostics.deadline_exceeded {
        return Ok(true);
    }
    if stop || index_exhausted || group_overflow {
        state.diagnostics.budget_exhausted = true;
        return Ok(true);
    }
    if group.is_empty() {
        return Ok(state.deadline_reached());
    }
    if state.deadline_reached() {
        return Ok(true);
    }
    if advance_new_query_group::<I::Error, C, D>(
        catalog,
        seed_release_prefix,
        verification_prefix,
        &mut group,
        state,
    )? {
        if state.best.is_some() {
            state.diagnostics.search_stopped_after_solution = true;
            state.diagnostics.ambiguity_query_completed = true;
        }
        return Ok(true);
    }
    debug_assert_eq!(verification_prefix, state.selected.len());
    Ok(false)
}

// Keeping the request/config/accounting borrows separate lets the index
// visitor and deadline predicate share only the disjoint state they need.
#[allow(clippy::too_many_arguments)]
fn collect_pending_candidate<IndexError, C: StarCatalog>(
    catalog: &C,
    candidate: PatternCandidate,
    seed_query: SeedQuery,
    group: &mut Vec<PendingHypothesis>,
    group_overflow: &mut bool,
    request: SolveRequest<'_>,
    config: &SolveConfig,
    diagnostics: &mut Diagnostics,
    should_stop: &mut dyn FnMut() -> bool,
) -> Result<bool, SolveError<IndexError, C::Error>> {
    let stars = candidate.stars;
    let catalog_invariant = match Invariant::from_rays(stars.map(|star| star.direction)) {
        Ok(value) => value,
        Err(_) => return Ok(false),
    };
    if !(seed_query.largest_edge_min_rad..=seed_query.largest_edge_max_rad)
        .contains(&catalog_invariant.largest_edge_rad())
    {
        return Ok(false);
    }
    let Some((calibration, fitted_invariant)) = fit_candidate_rectilinear_calibration(
        seed_query.rays,
        catalog_invariant,
        seed_query.fov_scale_min,
        seed_query.fov_scale_max,
        config.rectilinear_radial_max_abs,
        request.rectilinear_radial_reference_rad,
    ) else {
        return Ok(false);
    };
    if catalog_invariant.max_ratio_delta(fitted_invariant) > config.ratio_tolerance {
        return Ok(false);
    }

    if should_stop() {
        return Ok(true);
    }
    for permutation in PERMUTATIONS {
        if diagnostics.hypotheses_tested >= config.budgets.max_hypotheses {
            diagnostics.budget_exhausted = true;
            return Ok(true);
        }
        diagnostics.hypotheses_tested = diagnostics.hypotheses_tested.saturating_add(1);
        let ordinal = diagnostics.hypotheses_tested;
        let seed: [SeedAssociation; 4] = core::array::from_fn(|index| {
            let observation_index = seed_query.observation_indices[index];
            let star = stars[permutation[index]];
            SeedAssociation {
                observation_index,
                star_id: star.id,
                pair: AttitudePair {
                    camera: calibration
                        .calibrate_ray(request.observations[observation_index].ray)
                        .expect("validated candidate calibration preserves a finite seed ray"),
                    world: star.direction,
                    weight: observation_weight(request.observations[observation_index]),
                },
            }
        });
        let pairs = seed.map(|association| association.pair);
        let attitude = match estimate_attitude(&pairs) {
            Ok(value) => value,
            Err(MathError::DegenerateAttitude) => continue,
            Err(error) => return Err(SolveError::Math(error)),
        };
        if pairs.iter().any(|pair| {
            attitude.rotate_unit(pair.camera).angle_to(pair.world) > config.seed_tolerance_rad
        }) {
            continue;
        }
        if group.len() >= config.budgets.max_pending_hypotheses {
            *group_overflow = true;
            continue;
        }
        let boresight = attitude.rotate_unit(UnitVec3::Z);
        let density_per_steradian = catalog
            .density_hint_per_steradian(boresight, request.field_radius_rad)
            .map_err(SolveError::Catalog)?
            .filter(|value| value.is_finite() && *value > 0.0)
            .unwrap_or(config.fallback_density_per_steradian);
        group.push(PendingHypothesis {
            ordinal,
            required_log_e: config.external_trial_factor.ln()
                - online_hypothesis_weight(ordinal).ln()
                - config.max_chance_probability.ln(),
            processed_prefix: 0,
            attitude,
            seed,
            calibration,
            density_per_steradian,
            evidence: OnlineEvidence::new(),
            stars: Vec::new(),
            rows: Vec::new(),
            edges: Vec::new(),
        });
    }
    Ok(false)
}

fn search_seed<I, C, D>(
    index: &I,
    catalog: &C,
    seed: [usize; 4],
    state: &mut SearchState<'_, D>,
) -> Result<bool, SolveError<I::Error, C::Error>>
where
    I: PatternIndex,
    C: StarCatalog,
    D: FnMut() -> bool,
{
    if state.deadline_reached() {
        return Ok(true);
    }
    if state.diagnostics.quads_tested >= state.config.budgets.max_quads {
        state.diagnostics.budget_exhausted = true;
        return Ok(true);
    }
    state.diagnostics.quads_tested = state.diagnostics.quads_tested.saturating_add(1);
    let request = state.request;
    let config = state.config;
    let rays = seed.map(|observation_index| request.observations[observation_index].ray);
    let invariant = match Invariant::from_rays(rays) {
        Ok(value) => value,
        Err(_) => return Ok(false),
    };
    let scale_delta = config.rectilinear_fov_scale_max_delta;
    let fov_scale_min = 1.0 - scale_delta;
    let fov_scale_max = 1.0 + scale_delta;
    let edge = invariant.largest_edge_rad();
    let relative = config.largest_edge_relative_tolerance;
    let edge_min = edge * (1.0 - relative);
    let edge_max =
        (edge * (1.0 + relative)).min(2.0 * request.field_radius_rad + config.seed_tolerance_rad);
    if edge_min > edge_max {
        return Ok(false);
    }
    let Some((max_records_to_scan, max_bytes_to_read)) =
        config.budgets.remaining_index(&mut state.diagnostics)
    else {
        return Ok(true);
    };
    let seed_query = SeedQuery {
        observation_indices: seed,
        rays,
        fov_scale_min,
        fov_scale_max,
        largest_edge_min_rad: edge_min,
        largest_edge_max_rad: edge_max,
    };
    let query = PatternQuery {
        invariant,
        ratio_tolerance: config.ratio_tolerance,
        largest_edge_min_rad: edge_min,
        largest_edge_max_rad: edge_max,
        max_records_to_scan,
        max_bytes_to_read,
    };
    state.diagnostics.index_queries = state.diagnostics.index_queries.saturating_add(1);

    let mut callback_error = None;
    let mut stop = false;
    // Deduplicate records repeated across overlapping levels for this
    // observation quad. The same catalog pattern must be reconsidered for a
    // different quad because correspondence and seed residuals change.
    let mut seen_patterns: Vec<[StarId; 4]> = Vec::new();
    let query_stats = index
        .visit_candidates(query, &mut |candidate| {
            if stop || state.diagnostics.candidates_tested >= config.budgets.max_candidates {
                state.diagnostics.budget_exhausted = true;
                stop = true;
                return ControlFlow::Break(());
            }
            state.diagnostics.candidates_tested =
                state.diagnostics.candidates_tested.saturating_add(1);

            let canonical = canonical_pattern(candidate.stars.map(|star| star.id));
            if seen_patterns.contains(&canonical) {
                return ControlFlow::Continue(());
            }
            seen_patterns.push(canonical);

            match evaluate_candidate::<I::Error, C, D>(
                catalog, candidate, canonical, seed_query, state,
            ) {
                Ok(should_stop) => {
                    if should_stop {
                        stop = true;
                        ControlFlow::Break(())
                    } else {
                        ControlFlow::Continue(())
                    }
                }
                Err(error) => {
                    callback_error = Some(error);
                    stop = true;
                    ControlFlow::Break(())
                }
            }
        })
        .map_err(SolveError::Index)?;
    let index_exhausted = config
        .budgets
        .charge_index(&mut state.diagnostics, query_stats);
    if let Some(error) = callback_error {
        return Err(error);
    }
    if stop || index_exhausted {
        return Ok(true);
    }
    // A fully verified solution is anytime-valid. Finish the current observed
    // quad's complete index query first so a same-invariant alternative can
    // still be surfaced as an ambiguity, then avoid later image quads.
    if state.best.is_some() {
        state.diagnostics.search_stopped_after_solution = true;
        return Ok(true);
    }
    Ok(false)
}

fn validate_config(config: &SolveConfig) -> Result<(), &'static str> {
    let budgets = config.budgets;
    if budgets.max_observations < 4 {
        return Err("max_observations must be at least four");
    }
    if budgets.max_pattern_observations < 4 {
        return Err("max_pattern_observations must be at least four");
    }
    if config.verification_policy == VerificationPolicy::OnlineCumulative
        && !(8..=256).contains(&budgets.max_observations)
    {
        return Err("monotonic verification supports 8..=256 observations");
    }
    if budgets.max_quads == 0
        || budgets.max_candidates == 0
        || budgets.max_hypotheses == 0
        || budgets.max_pending_hypotheses == 0
        || budgets.max_index_records == 0
        || budgets.max_verification_pairs == 0
        || budgets.max_catalog_cells == 0
        || budgets.max_catalog_records == 0
        || budgets.max_catalog_bytes == 0
        || budgets.max_index_bytes == 0
    {
        return Err("all work budgets must be nonzero");
    }
    if !config.ratio_tolerance.is_finite()
        || !(0.0..=0.25).contains(&config.ratio_tolerance)
        || config.ratio_tolerance == 0.0
    {
        return Err("ratio_tolerance must be finite and in (0, 0.25]");
    }
    if !config.largest_edge_relative_tolerance.is_finite()
        || !(0.0..=0.5).contains(&config.largest_edge_relative_tolerance)
    {
        return Err("largest-edge tolerance must be finite and in [0, 0.5]");
    }
    if !config.rectilinear_fov_scale_max_delta.is_finite()
        || !(0.0..=config.largest_edge_relative_tolerance)
            .contains(&config.rectilinear_fov_scale_max_delta)
    {
        return Err(
            "rectilinear FOV scale delta must be finite and no greater than the largest-edge tolerance",
        );
    }
    if !config.rectilinear_radial_max_abs.is_finite()
        || !(0.0..=0.25).contains(&config.rectilinear_radial_max_abs)
    {
        return Err("rectilinear radial bound must be finite and in [0, 0.25]");
    }
    if !valid_positive_angle(config.seed_tolerance_rad)
        || !valid_positive_angle(config.match_tolerance_rad)
    {
        return Err("angular tolerances must be finite and in (0, pi)");
    }
    if config.min_matches == 0 || config.min_matches > budgets.max_observations.saturating_sub(4) {
        return Err("min_matches must fit outside the four-star seed");
    }
    if config.validation_screen_observations != 0
        && config.validation_screen_observations < config.min_matches.saturating_add(4)
    {
        return Err("validation screen must fit the seed and minimum independent matches");
    }
    if config.verification_policy == VerificationPolicy::OnlineCumulative
        && config.validation_screen_observations != 0
    {
        return Err("monotonic verification requires the rejecting screen to be disabled");
    }
    if config.verification_policy == VerificationPolicy::OnlineCumulative
        && budgets.max_pattern_observations > 256
    {
        return Err("online spatial pattern search supports at most 256 observations");
    }
    if !config.max_chance_probability.is_finite()
        || !(0.0..=1.0).contains(&config.max_chance_probability)
        || config.max_chance_probability == 0.0
    {
        return Err("max_chance_probability must be in (0, 1]");
    }
    if !config.fallback_density_per_steradian.is_finite()
        || config.fallback_density_per_steradian <= 0.0
    {
        return Err("fallback catalog density must be positive and finite");
    }
    if !config.huber_rad.is_finite() || config.huber_rad < 0.0 {
        return Err("Huber radius must be finite and nonnegative");
    }
    if !valid_positive_angle(config.same_solution_tolerance_rad) {
        return Err("same-solution tolerance must be finite and in (0, pi)");
    }
    if !config.ambiguity_rms_ratio.is_finite() || config.ambiguity_rms_ratio < 1.0 {
        return Err("ambiguity RMS ratio must be finite and at least one");
    }
    if !config.external_trial_factor.is_finite() || config.external_trial_factor < 1.0 {
        return Err("external trial factor must be finite and at least one");
    }
    Ok(())
}

fn valid_positive_angle(value: f64) -> bool {
    value.is_finite() && value > 0.0 && value < core::f64::consts::PI
}

fn canonical_pattern(mut stars: [StarId; 4]) -> [StarId; 4] {
    stars.sort_unstable();
    stars
}

fn evaluate_candidate<IndexError, C: StarCatalog, D: FnMut() -> bool>(
    catalog: &C,
    candidate: PatternCandidate,
    canonical: [StarId; 4],
    seed_query: SeedQuery,
    state: &mut SearchState<'_, D>,
) -> Result<bool, SolveError<IndexError, C::Error>> {
    if canonical.windows(2).any(|pair| pair[0] == pair[1]) {
        return Ok(false);
    }

    let request = state.request;
    let config = state.config;
    let stars = candidate.stars;
    // Reconstruct the exact invariant from decoded f64 catalog directions
    // before spending any attitude hypotheses.
    let catalog_invariant = match Invariant::from_rays(stars.map(|star| star.direction)) {
        Ok(value) => value,
        Err(_) => return Ok(false),
    };
    if !(seed_query.largest_edge_min_rad..=seed_query.largest_edge_max_rad)
        .contains(&catalog_invariant.largest_edge_rad())
    {
        return Ok(false);
    }
    let Some((calibration, fitted_invariant)) = fit_candidate_rectilinear_calibration(
        seed_query.rays,
        catalog_invariant,
        seed_query.fov_scale_min,
        seed_query.fov_scale_max,
        config.rectilinear_radial_max_abs,
        request.rectilinear_radial_reference_rad,
    ) else {
        return Ok(false);
    };
    if catalog_invariant.max_ratio_delta(fitted_invariant) > config.ratio_tolerance {
        return Ok(false);
    }

    for permutation in PERMUTATIONS {
        if state.diagnostics.hypotheses_tested >= config.budgets.max_hypotheses {
            state.diagnostics.budget_exhausted = true;
            return Ok(true);
        }
        state.diagnostics.hypotheses_tested = state.diagnostics.hypotheses_tested.saturating_add(1);
        let hypothesis_ordinal = state.diagnostics.hypotheses_tested;
        let seed: [SeedAssociation; 4] = core::array::from_fn(|index| {
            let observation_index = seed_query.observation_indices[index];
            let star = stars[permutation[index]];
            SeedAssociation {
                observation_index,
                star_id: star.id,
                pair: AttitudePair {
                    camera: calibration
                        .calibrate_ray(request.observations[observation_index].ray)
                        .expect("validated candidate calibration preserves a finite seed ray"),
                    world: star.direction,
                    weight: observation_weight(request.observations[observation_index]),
                },
            }
        });
        let pairs = seed.map(|association| association.pair);
        let attitude = match estimate_attitude(&pairs) {
            Ok(value) => value,
            Err(MathError::DegenerateAttitude) => continue,
            Err(error) => return Err(SolveError::Math(error)),
        };
        let mut seed_consistent = true;
        for pair in pairs {
            let predicted = attitude.rotate_unit(pair.camera);
            if predicted.angle_to(pair.world) > config.seed_tolerance_rad {
                seed_consistent = false;
                break;
            }
        }
        if !seed_consistent {
            continue;
        }
        if let Some(solution) = verify_and_refine::<IndexError, C, D>(
            catalog,
            attitude,
            &seed,
            calibration,
            hypothesis_ordinal,
            state,
        )? {
            insert_solution(solution, &mut state.best, &mut state.runner_up, config);
        }
        if state.deadline_reached() {
            return Ok(true);
        }
        if state.diagnostics.budget_exhausted {
            return Ok(true);
        }
    }
    Ok(false)
}

fn observation_weight(observation: Observation) -> f64 {
    f64::from(observation.flux).sqrt()
}

#[derive(Clone, Copy)]
struct SeedAssociation {
    observation_index: usize,
    star_id: StarId,
    pair: AttitudePair,
}

#[derive(Clone, Copy)]
struct Edge {
    star_index: usize,
    residual_rad: f64,
}

const ONLINE_E_PROCESS_COMPONENTS: usize = 16;

#[derive(Clone)]
struct OnlineEvidence {
    max_log_e: f64,
    trials: usize,
    matches: usize,
}

impl OnlineEvidence {
    fn new() -> Self {
        Self {
            max_log_e: 0.0,
            trials: 0,
            matches: 0,
        }
    }

    fn update(&mut self, matching_increment: bool, null_probability: f64) {
        self.trials = self.trials.saturating_add(1);
        self.matches = self.matches.saturating_add(usize::from(matching_increment));
        if null_probability <= 0.0 && matching_increment {
            self.max_log_e = f64::INFINITY;
            return;
        }
        // Every fixed alternative has q>p, so a failure decreases every
        // component and cannot establish a new running maximum. Reconstruct
        // the count-only mixture only after a matching increment.
        if !matching_increment || null_probability >= 1.0 {
            return;
        }
        let mut component_logs = [0.0; ONLINE_E_PROCESS_COMPONENTS];
        for (index, log_likelihood) in component_logs.iter_mut().enumerate() {
            let mixture_position = (index as f64 + 0.5) / ONLINE_E_PROCESS_COMPONENTS as f64;
            let alternative = mixture_position + null_probability * (1.0 - mixture_position);
            let success_log_ratio = alternative.ln() - null_probability.ln();
            let failure_log_ratio = (-mixture_position).ln_1p();
            *log_likelihood = self.matches as f64 * success_log_ratio
                + (self.trials - self.matches) as f64 * failure_log_ratio;
        }
        let maximum = component_logs
            .iter()
            .copied()
            .fold(f64::NEG_INFINITY, f64::max);
        let log_e = if maximum == f64::INFINITY {
            maximum
        } else {
            let scaled_sum: f64 = component_logs
                .iter()
                .map(|value| (*value - maximum).exp())
                .sum();
            maximum + scaled_sum.ln() - (ONLINE_E_PROCESS_COMPONENTS as f64).ln()
        };
        if log_e > self.max_log_e {
            self.max_log_e = log_e;
        }
    }

    fn raw_anytime_probability(&self) -> f64 {
        (-self.max_log_e).exp().min(1.0)
    }

    fn adjusted_probability(&self, ordinal: u32, external_trial_factor: f64) -> f64 {
        let log_adjusted =
            external_trial_factor.ln() - online_hypothesis_weight(ordinal).ln() - self.max_log_e;
        if log_adjusted >= 0.0 {
            1.0
        } else {
            log_adjusted.exp()
        }
    }
}

/// Predeclared heavy-tail alpha allocation for the immutable geometric
/// hypothesis ordinal. Block `b` contains ordinals `[2^b, 2^(b+1))` and gets
/// mass `1/sqrt(b+1) - 1/sqrt(b+2)`, shared uniformly across the block. The
/// block masses telescope to one, so optional stopping in observation rank
/// and adaptive stopping in hypothesis order remain jointly family-wise safe.
fn online_hypothesis_weight(ordinal: u32) -> f64 {
    debug_assert_ne!(ordinal, 0);
    let block = u32::BITS - 1 - ordinal.leading_zeros();
    let block_mass =
        (f64::from(block) + 1.0).sqrt().recip() - (f64::from(block) + 2.0).sqrt().recip();
    block_mass / f64::from(1_u32 << block)
}

struct PendingStar {
    star: CatalogStar,
    matched_row: Option<usize>,
}

struct PendingRow {
    observation_index: usize,
    edge_start: usize,
    edge_end: usize,
    matched_star: Option<usize>,
}

#[derive(Clone, Copy)]
struct PendingEdge {
    star_index: usize,
    residual_rad: f64,
}

struct PendingHypothesis {
    ordinal: u32,
    required_log_e: f64,
    processed_prefix: usize,
    attitude: Quat,
    seed: [SeedAssociation; 4],
    calibration: RectilinearCalibration,
    density_per_steradian: f64,
    evidence: OnlineEvidence,
    stars: Vec<PendingStar>,
    rows: Vec<PendingRow>,
    edges: Vec<PendingEdge>,
}

impl PendingHypothesis {
    fn evidence_crosses(&self, config: &SolveConfig) -> bool {
        self.evidence.matches >= config.min_matches
            && (config.max_chance_probability >= 1.0
                || self.evidence.max_log_e >= self.required_log_e)
    }
}

fn advance_new_query_group<IndexError, C: StarCatalog, D: FnMut() -> bool>(
    catalog: &C,
    seed_release_prefix: usize,
    verification_prefix: usize,
    group: &mut [PendingHypothesis],
    state: &mut SearchState<'_, D>,
) -> Result<bool, SolveError<IndexError, C::Error>> {
    // Catch siblings up row-major. One seed-consistent decoy therefore cannot
    // consume every visible row before the remaining records from its same
    // index query have an equal chance to accumulate evidence.
    for prefix in 1..=verification_prefix {
        for (hypothesis_index, hypothesis) in group.iter_mut().enumerate() {
            if hypothesis_index & 0x3f == 0 && state.deadline_reached() {
                return Ok(true);
            }
            advance_pending_to::<IndexError, C, D>(catalog, prefix, hypothesis, state)?;
            if state.diagnostics.budget_exhausted {
                return Ok(true);
            }
        }
        if materialize_crossing_hypotheses::<IndexError, C::Error, D>(
            group,
            seed_release_prefix.max(prefix),
            prefix,
            true,
            state,
        )? {
            let _ = state.deadline_reached();
            return Ok(true);
        }
        if state.deadline_reached() {
            return Ok(true);
        }
    }
    Ok(state.deadline_reached())
}

fn materialize_crossing_hypotheses<IndexError, CatalogError, D: FnMut() -> bool>(
    hypotheses: &[PendingHypothesis],
    observation_prefix: usize,
    evidence_prefix: usize,
    ambiguity_query_completed: bool,
    state: &mut SearchState<'_, D>,
) -> Result<bool, SolveError<IndexError, CatalogError>> {
    let config = *state.config;
    let mut group_best = None;
    let mut group_runner_up = None;
    for hypothesis in hypotheses {
        if !hypothesis.evidence_crosses(&config) {
            continue;
        }
        if state.deadline_reached() {
            return Ok(false);
        }
        let Some(accepted) =
            accepted_online_verification(hypothesis, &config, &mut || state.deadline_reached())
                .map_err(SolveError::InternalInvariant)?
        else {
            if state.diagnostics.deadline_exceeded {
                return Ok(false);
            }
            continue;
        };
        let solution = refine_accepted_solution::<IndexError, CatalogError, D>(
            hypothesis.attitude,
            &hypothesis.seed,
            hypothesis.calibration,
            accepted.verification,
            accepted.adjusted_chance_probability,
            state,
        )?
        .expect("an accepted online verification has a nonempty matching");
        insert_solution(solution, &mut group_best, &mut group_runner_up, &config);
    }
    // Commit only after every crossing sibling in this atomic index-query
    // group has been materialized. A deadline cannot leave a partially checked
    // ambiguity group in the public solver state.
    let materialized = group_best.is_some();
    if materialized {
        if let Some(solution) = group_best {
            insert_solution(solution, &mut state.best, &mut state.runner_up, &config);
        }
        if let Some(solution) = group_runner_up {
            insert_solution(solution, &mut state.best, &mut state.runner_up, &config);
        }
        state.diagnostics.accepted_observation_prefix = observation_prefix;
        state.diagnostics.accepted_evidence_prefix = evidence_prefix;
        state.diagnostics.ambiguity_query_completed = ambiguity_query_completed;
    }
    Ok(materialized)
}

fn advance_pending_to<IndexError, C: StarCatalog, D: FnMut() -> bool>(
    catalog: &C,
    target_prefix: usize,
    pending: &mut PendingHypothesis,
    state: &mut SearchState<'_, D>,
) -> Result<(), SolveError<IndexError, C::Error>> {
    let request = state.request;
    let config = state.config;
    while pending.processed_prefix < target_prefix {
        if state.deadline_reached() {
            return Ok(());
        }
        let rank = pending.processed_prefix;
        let observation_index = state.selected[rank];
        if pending
            .seed
            .iter()
            .any(|association| association.observation_index == observation_index)
        {
            pending.processed_prefix += 1;
            continue;
        }
        let camera = pending
            .calibration
            .calibrate_ray(request.observations[observation_index].ray)
            .map_err(SolveError::Math)?;
        let predicted_world = pending.attitude.rotate_unit(camera);
        let Some((max_cells_to_scan, max_records_to_scan, max_bytes_to_read)) =
            config.budgets.remaining_catalog(&mut state.diagnostics)
        else {
            return Ok(());
        };
        let mut row_edges: Vec<(CatalogStar, f64)> = Vec::new();
        let mut overflow = false;
        let (catalog_stats, deadline_was_hit) = {
            let deadline_hit = Cell::new(state.diagnostics.deadline_exceeded);
            let deadline_expired = RefCell::new(&mut state.deadline_expired);
            let diagnostics = &mut state.diagnostics;
            let catalog_stats = catalog
                .visit_cap_cancelable(
                    CatalogQuery {
                        center: predicted_world,
                        radius_rad: config.match_tolerance_rad,
                        max_cells_to_scan,
                        max_records_to_scan,
                        max_bytes_to_read,
                    },
                    &mut || poll_shared_deadline(&deadline_expired, &deadline_hit),
                    &mut |star| {
                        if poll_shared_deadline(&deadline_expired, &deadline_hit) {
                            overflow = true;
                            return ControlFlow::Break(());
                        }
                        if pending
                            .seed
                            .iter()
                            .any(|association| association.star_id == star.id)
                        {
                            return ControlFlow::Continue(());
                        }
                        let residual = predicted_world.angle_to(star.direction);
                        if residual > config.match_tolerance_rad {
                            return ControlFlow::Continue(());
                        }
                        if diagnostics.verification_pairs as usize
                            >= config.budgets.max_verification_pairs
                        {
                            diagnostics.budget_exhausted = true;
                            overflow = true;
                            return ControlFlow::Break(());
                        }
                        diagnostics.verification_pairs =
                            diagnostics.verification_pairs.saturating_add(1);
                        if let Some((_, known_residual)) =
                            row_edges.iter_mut().find(|(known, _)| known.id == star.id)
                        {
                            *known_residual = known_residual.min(residual);
                        } else {
                            row_edges.push((star, residual));
                        }
                        if diagnostics.verification_pairs as usize
                            >= config.budgets.max_verification_pairs
                        {
                            diagnostics.budget_exhausted = true;
                            overflow = true;
                            ControlFlow::Break(())
                        } else {
                            ControlFlow::Continue(())
                        }
                    },
                )
                .map_err(SolveError::Catalog)?;
            (catalog_stats, deadline_hit.get())
        };
        state.diagnostics.deadline_exceeded |= deadline_was_hit;
        config
            .budgets
            .charge_catalog(&mut state.diagnostics, catalog_stats);
        if state.diagnostics.deadline_exceeded {
            return Ok(());
        }
        if overflow || catalog_stats.budget_exhausted {
            return Ok(());
        }

        row_edges.sort_unstable_by_key(|(star, _)| star.id);
        let edge_start = pending.edges.len();
        for (star, residual_rad) in row_edges {
            let star_index = match pending
                .stars
                .iter()
                .position(|known| known.star.id == star.id)
            {
                Some(index) => index,
                None => {
                    pending.stars.push(PendingStar {
                        star,
                        matched_row: None,
                    });
                    pending.stars.len() - 1
                }
            };
            pending.edges.push(PendingEdge {
                star_index,
                residual_rad,
            });
        }
        let edge_end = pending.edges.len();
        let matching_increment = if edge_start == edge_end {
            false
        } else {
            let row_index = pending.rows.len();
            pending.rows.push(PendingRow {
                observation_index,
                edge_start,
                edge_end,
                matched_star: None,
            });
            state
                .matching_seen_scratch
                .resize(pending.stars.len(), false);
            state.matching_seen_scratch.fill(false);
            augment_pending_matching(
                row_index,
                &mut pending.rows,
                &mut pending.stars,
                &pending.edges,
                &mut state.matching_seen_scratch,
            )
        };
        let null_probability =
            catalog_hit_probability(pending.density_per_steradian, config.match_tolerance_rad);
        pending
            .evidence
            .update(matching_increment, null_probability);
        pending.processed_prefix += 1;
    }
    Ok(())
}

fn augment_pending_matching(
    row_index: usize,
    rows: &mut [PendingRow],
    stars: &mut [PendingStar],
    edges: &[PendingEdge],
    seen_stars: &mut [bool],
) -> bool {
    let edge_range = rows[row_index].edge_start..rows[row_index].edge_end;
    for edge in &edges[edge_range] {
        let star_index = edge.star_index;
        if seen_stars[star_index] {
            continue;
        }
        seen_stars[star_index] = true;
        let displaced_row = stars[star_index].matched_row;
        if displaced_row
            .is_some_and(|row| !augment_pending_matching(row, rows, stars, edges, seen_stars))
        {
            continue;
        }
        let previous_star = rows[row_index].matched_star.replace(star_index);
        stars[star_index].matched_row = Some(row_index);
        if let Some(previous_star) = previous_star.filter(|old| *old != star_index) {
            if stars[previous_star].matched_row == Some(row_index) {
                stars[previous_star].matched_row = None;
            }
        }
        return true;
    }
    false
}

fn accepted_online_verification(
    pending: &PendingHypothesis,
    config: &SolveConfig,
    should_stop: &mut dyn FnMut() -> bool,
) -> Result<Option<AcceptedVerification>, &'static str> {
    if !pending.evidence_crosses(config) {
        return Ok(None);
    }
    let verification_indices: Vec<usize> = pending
        .rows
        .iter()
        .map(|row| row.observation_index)
        .collect();
    let stars: Vec<CatalogStar> = pending.stars.iter().map(|star| star.star).collect();
    let adjacency: Vec<Vec<Edge>> = pending
        .rows
        .iter()
        .map(|row| {
            pending.edges[row.edge_start..row.edge_end]
                .iter()
                .map(|edge| Edge {
                    star_index: edge.star_index,
                    residual_rad: edge.residual_rad,
                })
                .collect()
        })
        .collect();
    let mut verified = match raw_verification_from_edges_cancelable(
        &verification_indices,
        &stars,
        &adjacency,
        pending.density_per_steradian,
        config.match_tolerance_rad,
        should_stop,
    ) {
        Ok(Some(verified)) => verified,
        Ok(None) => return Err("crossed online evidence rematerialized without a matching"),
        Err(()) => return Ok(None),
    };
    if verified.matches.len() != pending.evidence.matches {
        return Err("incremental and rematerialized matching cardinalities differ");
    }
    verified.chance_probability = pending.evidence.raw_anytime_probability();
    Ok(Some(AcceptedVerification {
        verification: verified,
        adjusted_chance_probability: pending
            .evidence
            .adjusted_probability(pending.ordinal, config.external_trial_factor),
    }))
}

struct RawVerification {
    matches: Vec<Match>,
    chance_probability: f64,
}

struct AcceptedVerification {
    verification: RawVerification,
    adjusted_chance_probability: f64,
}

fn verify_and_refine<IndexError, C: StarCatalog, D: FnMut() -> bool>(
    catalog: &C,
    initial_attitude: Quat,
    seed: &[SeedAssociation; 4],
    calibration: RectilinearCalibration,
    hypothesis_ordinal: u32,
    state: &mut SearchState<'_, D>,
) -> Result<Option<Solution>, SolveError<IndexError, C::Error>> {
    let selected = state.selected;
    let config = state.config;
    let (final_verification, adjusted_chance) = match config.verification_policy {
        VerificationPolicy::CompleteField => {
            let screen_count = config.validation_screen_observations.min(selected.len());
            if screen_count != 0 && screen_count < selected.len() {
                let screened = match verify_once::<IndexError, C, D>(
                    catalog,
                    initial_attitude,
                    seed,
                    calibration,
                    &selected[..screen_count],
                    state,
                )? {
                    Some(value) => value,
                    None => return Ok(None),
                };
                let screened_chance = familywise_chance(
                    screened.chance_probability,
                    hypothesis_ordinal,
                    config.external_trial_factor,
                );
                if screened.matches.len() < config.min_matches
                    || screened_chance > config.max_chance_probability
                {
                    return Ok(None);
                }
            }

            // This second pass is the sole acceptance boundary. It rematches
            // every selected non-seed observation; screen evidence is never
            // reused as accepted evidence.
            let verified = match verify_once::<IndexError, C, D>(
                catalog,
                initial_attitude,
                seed,
                calibration,
                selected,
                state,
            )? {
                Some(value) => value,
                None => return Ok(None),
            };
            let adjusted = familywise_chance(
                verified.chance_probability,
                hypothesis_ordinal,
                config.external_trial_factor,
            );
            if verified.matches.len() < config.min_matches
                || adjusted > config.max_chance_probability
            {
                return Ok(None);
            }
            (verified, adjusted)
        }
        VerificationPolicy::OnlineCumulative => unreachable!(
            "online brightness verification freezes hypotheses before catalog evidence"
        ),
    };
    refine_accepted_solution(
        initial_attitude,
        seed,
        calibration,
        final_verification,
        adjusted_chance,
        state,
    )
}

fn refine_accepted_solution<IndexError, CatalogError, D: FnMut() -> bool>(
    initial_attitude: Quat,
    seed: &[SeedAssociation; 4],
    calibration: RectilinearCalibration,
    final_verification: RawVerification,
    adjusted_chance: f64,
    state: &mut SearchState<'_, D>,
) -> Result<Option<Solution>, SolveError<IndexError, CatalogError>> {
    let request = state.request;
    let config = state.config;
    let raw_chance = final_verification.chance_probability;
    let mut attitude = initial_attitude;
    let mut matches = final_verification.matches;
    let mut objective = fit_objective_from_residuals(
        attitude,
        seed,
        request.observations,
        &matches,
        config.huber_rad,
    )
    .map_err(SolveError::Math)?;
    for _ in 0..config.refinement_iterations {
        // Once final all-star verification accepts a hypothesis, refinement
        // is atomic: every configured iteration gets the complete match set.
        // A deadline may overshoot this small fixed phase, but cannot reduce
        // the returned attitude to the four-star seed fit.
        let refined = fit_seed_and_matches(
            attitude,
            seed,
            request.observations,
            &matches,
            calibration,
            config.huber_rad,
        )
        .map_err(SolveError::Math)?;
        let change = attitude.angle_to(refined);
        let mut refined_matches = matches.clone();
        let Some(refined_objective) = evaluate_refined_fit(
            refined,
            seed,
            request.observations,
            &mut refined_matches,
            calibration,
            config,
        )
        .map_err(SolveError::Math)?
        else {
            break;
        };
        let improvement_epsilon = 1.0e-15 * objective.max(1.0);
        if refined_objective + improvement_epsilon >= objective {
            break;
        }
        attitude = refined;
        matches = refined_matches;
        objective = refined_objective;
        if change < 1.0e-14 {
            break;
        }
    }
    let seed_matches = build_seed_matches(attitude, seed);
    let (rms, maximum) = residual_statistics(&matches);

    Ok(Some(Solution {
        camera_to_world: attitude,
        fov_scale: calibration.fov_scale,
        rectilinear_radial_coefficient: calibration.radial_coefficient,
        rectilinear_radial_reference_rad: calibration.radial_reference_rad,
        seed_matches,
        matches,
        rms_residual_rad: rms,
        max_residual_rad: maximum,
        raw_chance_probability: raw_chance,
        chance_probability: adjusted_chance,
    }))
}

/// Add associations which were not needed by the first anytime-valid
/// crossing, without changing that acceptance decision.
///
/// The accepted attitude, seed identities, and independent core matches are
/// immutable inputs to this phase. Catalog association is performed once
/// under that fixed model. Core observations and catalog stars are excluded
/// from the new bipartite graph, so a faint/noisy tail cannot displace any
/// evidence which established the solve. The enlarged fixed association set
/// may drive a robust local attitude refinement, but only inside the accepted
/// solution cluster and only when it improves the objective on that exact set.
/// A deadline, optional catalog/calibration failure, or configurable work cap
/// simply returns the accepted baseline.
fn enrich_online_solution<C: StarCatalog, D: FnMut() -> bool>(
    catalog: &C,
    baseline: Solution,
    state: &mut SearchState<'_, D>,
) -> Solution {
    if state.deadline_reached() {
        return baseline;
    }
    let request = state.request;
    let config = state.config;
    let calibration = RectilinearCalibration {
        fov_scale: baseline.fov_scale,
        radial_coefficient: baseline.rectilinear_radial_coefficient,
        radial_reference_rad: baseline.rectilinear_radial_reference_rad,
    };
    let seed: [SeedAssociation; 4] = baseline.seed_matches.map(|matched| {
        let camera = calibration
            .calibrate_ray(request.observations[matched.observation_index].ray)
            .expect("an accepted calibration preserves every accepted seed ray");
        SeedAssociation {
            observation_index: matched.observation_index,
            star_id: matched.star_id,
            pair: AttitudePair {
                camera,
                world: matched.catalog_direction,
                weight: observation_weight(request.observations[matched.observation_index]),
            },
        }
    });

    let enrichment_indices: Vec<usize> = state
        .selected
        .iter()
        .copied()
        .filter(|observation_index| {
            !seed
                .iter()
                .any(|association| association.observation_index == *observation_index)
                && !baseline
                    .matches
                    .iter()
                    .any(|matched| matched.observation_index == *observation_index)
        })
        .collect();
    if enrichment_indices.is_empty() {
        return baseline;
    }

    let mut stars: Vec<CatalogStar> = Vec::new();
    let mut adjacency: Vec<Vec<Edge>> = (0..enrichment_indices.len()).map(|_| Vec::new()).collect();
    for (local_observation, observation_index) in enrichment_indices.iter().copied().enumerate() {
        if state.deadline_reached() {
            return baseline;
        }
        let Ok(camera) = calibration.calibrate_ray(request.observations[observation_index].ray)
        else {
            return baseline;
        };
        let predicted_world = baseline.camera_to_world.rotate_unit(camera);
        let Some((max_cells_to_scan, max_records_to_scan, max_bytes_to_read)) =
            config.budgets.remaining_catalog(&mut state.diagnostics)
        else {
            return baseline;
        };
        let mut interrupted = false;
        let (catalog_stats, deadline_was_hit) = {
            let deadline_hit = Cell::new(state.diagnostics.deadline_exceeded);
            let deadline_expired = RefCell::new(&mut state.deadline_expired);
            let diagnostics = &mut state.diagnostics;
            let Ok(catalog_stats) = catalog.visit_cap_cancelable(
                CatalogQuery {
                    center: predicted_world,
                    radius_rad: config.match_tolerance_rad,
                    max_cells_to_scan,
                    max_records_to_scan,
                    max_bytes_to_read,
                },
                &mut || poll_shared_deadline(&deadline_expired, &deadline_hit),
                &mut |star| {
                    if poll_shared_deadline(&deadline_expired, &deadline_hit) {
                        interrupted = true;
                        return ControlFlow::Break(());
                    }
                    if seed
                        .iter()
                        .any(|association| association.star_id == star.id)
                        || baseline
                            .matches
                            .iter()
                            .any(|matched| matched.star_id == star.id)
                    {
                        return ControlFlow::Continue(());
                    }
                    let residual = predicted_world.angle_to(star.direction);
                    if residual > config.match_tolerance_rad {
                        return ControlFlow::Continue(());
                    }
                    if diagnostics.verification_pairs as usize
                        >= config.budgets.max_verification_pairs
                    {
                        diagnostics.budget_exhausted = true;
                        interrupted = true;
                        return ControlFlow::Break(());
                    }
                    diagnostics.verification_pairs =
                        diagnostics.verification_pairs.saturating_add(1);
                    let star_index = match stars.iter().position(|known| known.id == star.id) {
                        Some(index) => index,
                        None => {
                            stars.push(star);
                            stars.len() - 1
                        }
                    };
                    if let Some(edge) = adjacency[local_observation]
                        .iter_mut()
                        .find(|edge| edge.star_index == star_index)
                    {
                        edge.residual_rad = edge.residual_rad.min(residual);
                    } else {
                        adjacency[local_observation].push(Edge {
                            star_index,
                            residual_rad: residual,
                        });
                    }
                    ControlFlow::Continue(())
                },
            ) else {
                return baseline;
            };
            (catalog_stats, deadline_hit.get())
        };
        state.diagnostics.deadline_exceeded |= deadline_was_hit;
        config
            .budgets
            .charge_catalog(&mut state.diagnostics, catalog_stats);
        if state.diagnostics.deadline_exceeded || interrupted || catalog_stats.budget_exhausted {
            return baseline;
        }
    }
    if state.deadline_reached() {
        return baseline;
    }

    // Density/chance is intentionally ignored here: the sticky probability
    // and evidence prefix remain those of the first accepted crossing.
    let additional = raw_verification_from_edges_cancelable(
        &enrichment_indices,
        &stars,
        &adjacency,
        1.0,
        config.match_tolerance_rad,
        &mut || state.deadline_reached(),
    );
    let Ok(Some(additional)) = additional else {
        return baseline;
    };
    if state.deadline_reached() {
        return baseline;
    }
    let mut matches = baseline.matches.clone();
    matches.extend(additional.matches);
    matches.sort_unstable_by_key(|matched| matched.observation_index);
    if matches.len() <= baseline.matches.len() {
        return baseline;
    }

    let fixed_attitude = baseline.camera_to_world;
    let mut attitude = fixed_attitude;
    let Ok(mut objective) = fit_objective_from_residuals(
        attitude,
        &seed,
        request.observations,
        &matches,
        config.huber_rad,
    ) else {
        return baseline;
    };
    let Ok(locked_objective) = fit_objective_from_residuals(
        fixed_attitude,
        &seed,
        request.observations,
        &baseline.matches,
        config.huber_rad,
    ) else {
        return baseline;
    };
    for _ in 0..config.refinement_iterations {
        if state.deadline_reached() {
            return baseline;
        }
        let Ok(refined) = fit_seed_and_matches(
            attitude,
            &seed,
            request.observations,
            &matches,
            calibration,
            config.huber_rad,
        ) else {
            return baseline;
        };
        // Enrichment is local by construction. A different attitude cluster
        // is not an improvement of the accepted solve and is discarded.
        if refined.angle_to(fixed_attitude)
            > config
                .same_solution_tolerance_rad
                .min(config.match_tolerance_rad)
        {
            break;
        }
        let change = attitude.angle_to(refined);
        let mut refined_matches = matches.clone();
        let Ok(Some(refined_objective)) = evaluate_refined_fit(
            refined,
            &seed,
            request.observations,
            &mut refined_matches,
            calibration,
            config,
        ) else {
            break;
        };
        let mut refined_locked_matches = baseline.matches.clone();
        let Ok(Some(refined_locked_objective)) = evaluate_refined_fit(
            refined,
            &seed,
            request.observations,
            &mut refined_locked_matches,
            calibration,
            config,
        ) else {
            break;
        };
        let improvement_epsilon = 1.0e-15 * objective.max(1.0);
        // The accepted core is a small, noisy sample; demanding bitwise
        // non-increase would reject every statistically useful all-field
        // update. Permit only a small, explicit robust-loss envelope while
        // requiring strictly better support over the complete fixed match set.
        let locked_limit = locked_objective * 1.10 + 1.0e-15 * locked_objective.max(1.0);
        if refined_objective + improvement_epsilon >= objective
            || refined_locked_objective > locked_limit
        {
            break;
        }
        attitude = refined;
        matches = refined_matches;
        objective = refined_objective;
        if change < 1.0e-14 {
            break;
        }
    }

    // The graph excluded the accepted core on both sides. Check that invariant
    // explicitly before exposing the enriched match list.
    if !baseline.matches.iter().all(|locked| {
        matches.iter().any(|matched| {
            matched.observation_index == locked.observation_index
                && matched.star_id == locked.star_id
                && matched.residual_rad <= config.match_tolerance_rad
        })
    }) {
        return baseline;
    }
    let seed_matches = build_seed_matches(attitude, &seed);
    if seed_matches
        .iter()
        .any(|matched| matched.residual_rad > config.seed_tolerance_rad)
    {
        return baseline;
    }
    let (rms, maximum) = residual_statistics(&matches);
    Solution {
        camera_to_world: attitude,
        fov_scale: baseline.fov_scale,
        rectilinear_radial_coefficient: baseline.rectilinear_radial_coefficient,
        rectilinear_radial_reference_rad: baseline.rectilinear_radial_reference_rad,
        seed_matches,
        matches,
        rms_residual_rad: rms,
        max_residual_rad: maximum,
        raw_chance_probability: baseline.raw_chance_probability,
        chance_probability: baseline.chance_probability,
    }
}

fn build_seed_matches(attitude: Quat, seed: &[SeedAssociation; 4]) -> [Match; 4] {
    let mut matches = seed.map(|association| Match {
        observation_index: association.observation_index,
        star_id: association.star_id,
        catalog_direction: association.pair.world,
        residual_rad: 0.0,
    });
    for matched in &mut matches {
        let association = seed
            .iter()
            .find(|association| association.observation_index == matched.observation_index)
            .expect("seed match retains its association");
        let predicted = attitude.rotate_unit(association.pair.camera);
        matched.residual_rad = predicted.angle_to(association.pair.world);
    }
    // Hypothesis construction order is an internal invariant/permutation
    // detail. Expose a deterministic order tied to the caller's observations.
    matches.sort_unstable_by_key(|matched| matched.observation_index);
    matches
}

fn fit_seed_and_matches(
    current_attitude: Quat,
    seed: &[SeedAssociation; 4],
    observations: &[Observation],
    matches: &[Match],
    calibration: RectilinearCalibration,
    huber_rad: f64,
) -> Result<Quat, MathError> {
    let mut pairs = Vec::with_capacity(seed.len() + matches.len());
    for association in seed {
        let seed_pair = association.pair;
        let residual = current_attitude
            .rotate_unit(seed_pair.camera)
            .angle_to(seed_pair.world);
        pairs.push(AttitudePair {
            camera: seed_pair.camera,
            world: seed_pair.world,
            weight: seed_pair.weight * huber_weight(residual, huber_rad),
        });
    }
    for matched in matches {
        let camera = calibration.calibrate_ray(observations[matched.observation_index].ray)?;
        pairs.push(AttitudePair {
            camera,
            world: matched.catalog_direction,
            weight: observation_weight(observations[matched.observation_index])
                * huber_weight(matched.residual_rad, huber_rad),
        });
    }
    estimate_attitude(&pairs)
}

fn huber_weight(residual_rad: f64, huber_rad: f64) -> f64 {
    if huber_rad > 0.0 && residual_rad > huber_rad {
        huber_rad / residual_rad
    } else {
        1.0
    }
}

fn fit_objective_from_residuals(
    attitude: Quat,
    seed: &[SeedAssociation; 4],
    observations: &[Observation],
    matches: &[Match],
    huber_rad: f64,
) -> Result<f64, MathError> {
    let mut objective = 0.0;
    for association in seed {
        let pair = association.pair;
        let residual = attitude.rotate_unit(pair.camera).angle_to(pair.world);
        objective += pair.weight * huber_loss(residual, huber_rad);
    }
    for matched in matches {
        objective += observation_weight(observations[matched.observation_index])
            * huber_loss(matched.residual_rad, huber_rad);
    }
    if objective.is_finite() {
        Ok(objective)
    } else {
        Err(MathError::NonFinite)
    }
}

fn evaluate_refined_fit(
    attitude: Quat,
    seed: &[SeedAssociation; 4],
    observations: &[Observation],
    matches: &mut [Match],
    calibration: RectilinearCalibration,
    config: &SolveConfig,
) -> Result<Option<f64>, MathError> {
    let mut objective = 0.0;
    for association in seed {
        let pair = association.pair;
        let residual = attitude.rotate_unit(pair.camera).angle_to(pair.world);
        if residual > config.seed_tolerance_rad {
            return Ok(None);
        }
        objective += pair.weight * huber_loss(residual, config.huber_rad);
    }
    for matched in matches {
        let camera = calibration.calibrate_ray(observations[matched.observation_index].ray)?;
        let predicted = attitude.rotate_unit(camera);
        matched.residual_rad = predicted.angle_to(matched.catalog_direction);
        if matched.residual_rad > config.match_tolerance_rad {
            return Ok(None);
        }
        objective += observation_weight(observations[matched.observation_index])
            * huber_loss(matched.residual_rad, config.huber_rad);
    }
    if objective.is_finite() {
        Ok(Some(objective))
    } else {
        Err(MathError::NonFinite)
    }
}

fn huber_loss(residual_rad: f64, huber_rad: f64) -> f64 {
    if huber_rad > 0.0 && residual_rad > huber_rad {
        2.0 * huber_rad * residual_rad - huber_rad * huber_rad
    } else {
        residual_rad * residual_rad
    }
}

fn residual_statistics(matches: &[Match]) -> (f64, f64) {
    if matches.is_empty() {
        return (0.0, 0.0);
    }
    let squared_sum: f64 = matches
        .iter()
        .map(|matched| matched.residual_rad * matched.residual_rad)
        .sum();
    let maximum = matches
        .iter()
        .fold(0.0_f64, |value, matched| value.max(matched.residual_rad));
    ((squared_sum / matches.len() as f64).sqrt(), maximum)
}

fn verify_once<IndexError, C: StarCatalog, D: FnMut() -> bool>(
    catalog: &C,
    attitude: Quat,
    seed: &[SeedAssociation; 4],
    calibration: RectilinearCalibration,
    selected: &[usize],
    state: &mut SearchState<'_, D>,
) -> Result<Option<RawVerification>, SolveError<IndexError, C::Error>> {
    let request = state.request;
    let config = state.config;
    let mut stars: Vec<CatalogStar> = Vec::new();
    let verification_indices: Vec<usize> = selected
        .iter()
        .copied()
        .filter(|index| {
            !seed
                .iter()
                .any(|association| association.observation_index == *index)
        })
        .collect();
    if verification_indices.is_empty() {
        return Ok(None);
    }
    let mut adjacency: Vec<Vec<Edge>> = (0..verification_indices.len())
        .map(|_| Vec::new())
        .collect();

    for (local_observation, observation_index) in verification_indices.iter().copied().enumerate() {
        if state.deadline_reached() {
            return Ok(None);
        }
        let camera = calibration
            .calibrate_ray(request.observations[observation_index].ray)
            .map_err(SolveError::Math)?;
        let predicted_world = attitude.rotate_unit(camera);
        let Some((max_cells_to_scan, max_records_to_scan, max_bytes_to_read)) =
            config.budgets.remaining_catalog(&mut state.diagnostics)
        else {
            return Ok(None);
        };
        let mut overflow = false;
        let catalog_stats = catalog
            .visit_cap(
                CatalogQuery {
                    center: predicted_world,
                    radius_rad: config.match_tolerance_rad,
                    max_cells_to_scan,
                    max_records_to_scan,
                    max_bytes_to_read,
                },
                &mut |star| {
                    if seed
                        .iter()
                        .any(|association| association.star_id == star.id)
                    {
                        return ControlFlow::Continue(());
                    }
                    let residual = predicted_world.angle_to(star.direction);
                    if residual > config.match_tolerance_rad {
                        return ControlFlow::Continue(());
                    }
                    if state.diagnostics.verification_pairs as usize
                        >= config.budgets.max_verification_pairs
                    {
                        state.diagnostics.budget_exhausted = true;
                        overflow = true;
                        return ControlFlow::Break(());
                    }
                    state.diagnostics.verification_pairs =
                        state.diagnostics.verification_pairs.saturating_add(1);
                    let pair_limit_reached = state.diagnostics.verification_pairs as usize
                        >= config.budgets.max_verification_pairs;
                    let star_index = match stars.iter().position(|known| known.id == star.id) {
                        Some(index) => index,
                        None => {
                            stars.push(star);
                            stars.len() - 1
                        }
                    };
                    if !adjacency[local_observation]
                        .iter()
                        .any(|edge| edge.star_index == star_index)
                    {
                        adjacency[local_observation].push(Edge {
                            star_index,
                            residual_rad: residual,
                        });
                    }
                    if pair_limit_reached {
                        state.diagnostics.budget_exhausted = true;
                        overflow = true;
                        ControlFlow::Break(())
                    } else {
                        ControlFlow::Continue(())
                    }
                },
            )
            .map_err(SolveError::Catalog)?;
        config
            .budgets
            .charge_catalog(&mut state.diagnostics, catalog_stats);
        if overflow || catalog_stats.budget_exhausted {
            return Ok(None);
        }
    }

    let boresight = attitude.rotate_unit(UnitVec3::Z);
    let density = catalog
        .density_hint_per_steradian(boresight, request.field_radius_rad)
        .map_err(SolveError::Catalog)?
        .filter(|value| value.is_finite() && *value > 0.0)
        .unwrap_or(config.fallback_density_per_steradian);
    Ok(raw_verification_from_edges(
        &verification_indices,
        &stars,
        &adjacency,
        density,
        config.match_tolerance_rad,
    ))
}

fn raw_verification_from_edges(
    verification_indices: &[usize],
    stars: &[CatalogStar],
    adjacency: &[Vec<Edge>],
    density_per_steradian: f64,
    match_tolerance_rad: f64,
) -> Option<RawVerification> {
    raw_verification_from_edges_cancelable(
        verification_indices,
        stars,
        adjacency,
        density_per_steradian,
        match_tolerance_rad,
        &mut || false,
    )
    .expect("a disabled cancellation predicate cannot stop verification")
}

fn raw_verification_from_edges_cancelable(
    verification_indices: &[usize],
    stars: &[CatalogStar],
    adjacency: &[Vec<Edge>],
    density_per_steradian: f64,
    match_tolerance_rad: f64,
    should_stop: &mut dyn FnMut() -> bool,
) -> Result<Option<RawVerification>, ()> {
    if should_stop() {
        return Err(());
    }
    // Canonicalize catalog column order so equal-cost ties do not depend on a
    // backing store's cell visitation order.
    let mut star_order: Vec<usize> = (0..stars.len()).collect();
    star_order.sort_unstable_by_key(|index| stars[*index].id);
    if should_stop() {
        return Err(());
    }
    let mut remap = vec![0; stars.len()];
    for (new_index, old_index) in star_order.iter().copied().enumerate() {
        if new_index & 0xff == 0 && should_stop() {
            return Err(());
        }
        remap[old_index] = new_index;
    }
    let mut ordered_stars = Vec::with_capacity(stars.len());
    for (number, index) in star_order.iter().copied().enumerate() {
        if number & 0xff == 0 && should_stop() {
            return Err(());
        }
        ordered_stars.push(stars[index]);
    }
    let mut remapped_adjacency = Vec::with_capacity(adjacency.len());
    let mut edge_work = 0_usize;
    for edges in adjacency {
        if should_stop() {
            return Err(());
        }
        let mut remapped_edges = Vec::with_capacity(edges.len());
        for edge in edges {
            edge_work = edge_work.wrapping_add(1);
            if edge_work & 0xff == 0 && should_stop() {
                return Err(());
            }
            remapped_edges.push(Edge {
                star_index: remap[edge.star_index],
                residual_rad: edge.residual_rad,
            });
        }
        remapped_adjacency.push(remapped_edges);
    }

    // Hungarian assignment with explicit unmatched columns first maximizes
    // cardinality, then minimizes total angular residual among those matchings.
    let observation_assignment = minimum_cost_maximum_matching_cancelable(
        &remapped_adjacency,
        ordered_stars.len(),
        match_tolerance_rad,
        should_stop,
    )
    .ok_or(())?;

    let mut matches = Vec::new();
    for (local_observation, star_index) in observation_assignment.into_iter().enumerate() {
        let Some(star_index) = star_index else {
            continue;
        };
        let Some(edge) = remapped_adjacency[local_observation]
            .iter()
            .find(|edge| edge.star_index == star_index)
        else {
            continue;
        };
        let observation_index = verification_indices[local_observation];
        matches.push(Match {
            observation_index,
            star_id: ordered_stars[star_index].id,
            catalog_direction: ordered_stars[star_index].direction,
            residual_rad: edge.residual_rad,
        });
    }
    matches.sort_unstable_by_key(|matched| matched.observation_index);
    if matches.is_empty() {
        return Ok(None);
    }
    let chance = chance_probability(
        verification_indices.len(),
        matches.len(),
        density_per_steradian,
        match_tolerance_rad,
    );
    Ok(Some(RawVerification {
        matches,
        chance_probability: chance,
    }))
}

#[cfg(test)]
fn minimum_cost_maximum_matching(
    adjacency: &[Vec<Edge>],
    star_count: usize,
    maximum_residual: f64,
) -> Vec<Option<usize>> {
    minimum_cost_maximum_matching_cancelable(adjacency, star_count, maximum_residual, &mut || false)
        .expect("a disabled cancellation predicate cannot stop matching")
}

const DENSE_MATCHING_MAX_BYTES: usize = 8 * 1024 * 1024;

fn dense_minimum_cost_maximum_matching_cancelable(
    adjacency: &[Vec<Edge>],
    star_count: usize,
    maximum_residual: f64,
    should_stop: &mut dyn FnMut() -> bool,
) -> Option<Vec<Option<usize>>> {
    if should_stop() {
        return None;
    }
    let rows = adjacency.len();
    let columns = star_count.checked_add(rows)?;
    let unmatched_cost = (rows as f64 + 1.0) * maximum_residual + 1.0;
    let missing_cost = (rows as f64 + 1.0) * unmatched_cost;
    let mut costs = vec![vec![missing_cost; columns]; rows];
    for (row, edges) in adjacency.iter().enumerate() {
        if should_stop() {
            return None;
        }
        for edge in edges {
            if edge.star_index < star_count {
                costs[row][edge.star_index] = edge.residual_rad;
            }
        }
        for dummy in star_count..columns {
            costs[row][dummy] = unmatched_cost;
        }
    }

    let mut row_potential = vec![0.0_f64; rows + 1];
    let mut column_potential = vec![0.0_f64; columns + 1];
    let mut column_row = vec![0_usize; columns + 1];
    let mut predecessor = vec![0_usize; columns + 1];
    let mut minimum = vec![f64::INFINITY; columns + 1];
    let mut used = vec![false; columns + 1];
    for row in 1..=rows {
        if should_stop() {
            return None;
        }
        column_row[0] = row;
        let mut column = 0;
        minimum.fill(f64::INFINITY);
        used.fill(false);
        predecessor.fill(0);
        loop {
            used[column] = true;
            let active_row = column_row[column];
            let mut delta = f64::INFINITY;
            let mut next_column = 0;
            for candidate in 1..=columns {
                // Monotonic wall-clock polling is intentionally lazy here;
                // one dense chunk is allocation-bounded above and exact
                // deadline firing is not part of the solve contract.
                if candidate & 0x3fff == 0 && should_stop() {
                    return None;
                }
                if used[candidate] {
                    continue;
                }
                let reduced = costs[active_row - 1][candidate - 1]
                    - row_potential[active_row]
                    - column_potential[candidate];
                if reduced < minimum[candidate] {
                    minimum[candidate] = reduced;
                    predecessor[candidate] = column;
                }
                if minimum[candidate] < delta {
                    delta = minimum[candidate];
                    next_column = candidate;
                }
            }
            for candidate in 0..=columns {
                if used[candidate] {
                    row_potential[column_row[candidate]] += delta;
                    column_potential[candidate] -= delta;
                } else {
                    minimum[candidate] -= delta;
                }
            }
            column = next_column;
            if column_row[column] == 0 {
                break;
            }
        }
        loop {
            let previous = predecessor[column];
            column_row[column] = column_row[previous];
            column = previous;
            if column == 0 {
                break;
            }
        }
    }

    let mut assignment = vec![None; rows];
    for (column, row) in column_row.iter().copied().enumerate().skip(1) {
        if row != 0 && column - 1 < star_count {
            let star = column - 1;
            if costs[row - 1][star] < missing_cost {
                assignment[row - 1] = Some(star);
            }
        }
    }
    Some(assignment)
}

#[derive(Clone, Copy)]
struct FlowEdge {
    to: usize,
    reverse: usize,
    capacity: u8,
    cost: f64,
}

#[derive(Clone, Copy)]
struct HeapNode {
    cost: f64,
    node: usize,
}

impl PartialEq for HeapNode {
    fn eq(&self, other: &Self) -> bool {
        self.node == other.node && self.cost.total_cmp(&other.cost) == Ordering::Equal
    }
}

impl Eq for HeapNode {}

impl PartialOrd for HeapNode {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for HeapNode {
    fn cmp(&self, other: &Self) -> Ordering {
        // BinaryHeap is a max-heap; reverse both keys so the least distance
        // and then least canonical node is visited first.
        other
            .cost
            .total_cmp(&self.cost)
            .then_with(|| other.node.cmp(&self.node))
    }
}

fn add_flow_edge(graph: &mut [Vec<FlowEdge>], from: usize, to: usize, cost: f64) -> usize {
    let forward = graph[from].len();
    let reverse = graph[to].len();
    graph[from].push(FlowEdge {
        to,
        reverse,
        capacity: 1,
        cost,
    });
    graph[to].push(FlowEdge {
        to: from,
        reverse: forward,
        capacity: 0,
        cost: -cost,
    });
    forward
}

/// Sparse successive-shortest-path assignment. This has the same objective
/// as the former dense Hungarian matrix (maximum cardinality, then minimum
/// total residual) while retaining only O(rows + stars + emitted edges)
/// memory. Returning `None` means the caller's deadline/cancellation predicate
/// fired before an atomic assignment was available.
fn minimum_cost_maximum_matching_cancelable(
    adjacency: &[Vec<Edge>],
    star_count: usize,
    maximum_residual: f64,
    should_stop: &mut dyn FnMut() -> bool,
) -> Option<Vec<Option<usize>>> {
    let rows = adjacency.len();
    if rows == 0 {
        return Some(Vec::new());
    }
    let dense_bytes = rows
        .checked_mul(star_count.checked_add(rows)?)
        .and_then(|cells| cells.checked_mul(core::mem::size_of::<f64>()));
    if dense_bytes.is_some_and(|bytes| bytes <= DENSE_MATCHING_MAX_BYTES) {
        return dense_minimum_cost_maximum_matching_cancelable(
            adjacency,
            star_count,
            maximum_residual,
            should_stop,
        );
    }
    let star_start = 1_usize.checked_add(rows)?;
    let sink = star_start.checked_add(star_count)?;
    let node_count = sink.checked_add(1)?;
    let source = 0_usize;
    let mut graph: Vec<Vec<FlowEdge>> = (0..node_count).map(|_| Vec::new()).collect();
    let mut row_edges: Vec<Vec<(usize, usize)>> = (0..rows).map(|_| Vec::new()).collect();
    let mut edge_work = 0_usize;
    for (row, edges) in adjacency.iter().enumerate() {
        if should_stop() {
            return None;
        }
        let row_node = 1 + row;
        add_flow_edge(&mut graph, source, row_node, 0.0);
        for edge in edges {
            edge_work = edge_work.wrapping_add(1);
            if edge_work & 0xff == 0 && should_stop() {
                return None;
            }
            if edge.star_index >= star_count || !edge.residual_rad.is_finite() {
                continue;
            }
            let edge_index = add_flow_edge(
                &mut graph,
                row_node,
                star_start + edge.star_index,
                edge.residual_rad,
            );
            row_edges[row].push((edge.star_index, edge_index));
        }
    }
    for star in 0..star_count {
        if should_stop() {
            return None;
        }
        add_flow_edge(&mut graph, star_start + star, sink, 0.0);
    }

    let mut potential = vec![0.0_f64; node_count];
    let mut distance = vec![f64::INFINITY; node_count];
    let mut predecessor = vec![None::<(usize, usize)>; node_count];
    loop {
        if should_stop() {
            return None;
        }
        distance.fill(f64::INFINITY);
        predecessor.fill(None);
        distance[source] = 0.0;
        let mut heap = BinaryHeap::new();
        heap.push(HeapNode {
            cost: 0.0,
            node: source,
        });
        let mut pops = 0_usize;
        while let Some(HeapNode { cost, node }) = heap.pop() {
            if cost.total_cmp(&distance[node]) == Ordering::Greater {
                continue;
            }
            pops += 1;
            if pops & 0xff == 0 && should_stop() {
                return None;
            }
            for (edge_index, edge) in graph[node].iter().copied().enumerate() {
                if edge_index & 0xff == 0 && should_stop() {
                    return None;
                }
                if edge.capacity == 0 {
                    continue;
                }
                let reduced = (edge.cost + potential[node] - potential[edge.to]).max(0.0);
                let candidate = cost + reduced;
                if candidate.total_cmp(&distance[edge.to]) == Ordering::Less {
                    distance[edge.to] = candidate;
                    predecessor[edge.to] = Some((node, edge_index));
                    heap.push(HeapNode {
                        cost: candidate,
                        node: edge.to,
                    });
                }
            }
        }
        if !distance[sink].is_finite() {
            break;
        }
        for node in 0..node_count {
            if node & 0xff == 0 && should_stop() {
                return None;
            }
            if distance[node].is_finite() {
                potential[node] += distance[node];
            }
        }
        let mut node = sink;
        while node != source {
            let (from, edge_index) = predecessor[node]?;
            let reverse = graph[from][edge_index].reverse;
            graph[from][edge_index].capacity -= 1;
            graph[node][reverse].capacity += 1;
            node = from;
        }
    }

    let mut assignment = vec![None; rows];
    for row in 0..rows {
        if row & 0x3f == 0 && should_stop() {
            return None;
        }
        let row_node = 1 + row;
        for (edge_number, &(star, edge_index)) in row_edges[row].iter().enumerate() {
            if edge_number & 0xff == 0 && should_stop() {
                return None;
            }
            if graph[row_node][edge_index].capacity == 0 {
                assignment[row] = Some(star);
                break;
            }
        }
    }
    Some(assignment)
}

fn chance_probability(
    observation_count: usize,
    match_count: usize,
    density_per_steradian: f64,
    tolerance_rad: f64,
) -> f64 {
    if match_count == 0 {
        return 1.0;
    }
    if match_count > observation_count {
        return 0.0;
    }
    let probability = catalog_hit_probability(density_per_steradian, tolerance_rad);
    binomial_tail(observation_count, match_count, probability)
}

fn catalog_hit_probability(density_per_steradian: f64, tolerance_rad: f64) -> f64 {
    let half_sine = (0.5 * tolerance_rad).sin();
    let cap_area = 4.0 * core::f64::consts::PI * half_sine * half_sine;
    // Union-bound the chance that a random detection lies near any catalog
    // star. This is conservative without assuming catalog directions form a
    // spatial Poisson process.
    (density_per_steradian * cap_area).clamp(0.0, 1.0)
}

/// Anytime-valid alpha spending for sequentially tested hypotheses. The
/// thresholds `alpha/(k(k+1))` sum to alpha because
/// `sum(1/(k(k+1))) == 1`, so callers may stop at any work budget without
/// invalidating the family-wise false-association bound.
fn familywise_chance(
    raw_probability: f64,
    hypothesis_ordinal: u32,
    external_trial_factor: f64,
) -> f64 {
    let ordinal = f64::from(hypothesis_ordinal.max(1));
    (raw_probability * ordinal * (ordinal + 1.0) * external_trial_factor).clamp(0.0, 1.0)
}

fn binomial_tail(trials: usize, successes: usize, probability: f64) -> f64 {
    if successes == 0 {
        return 1.0;
    }
    if successes > trials || probability <= 0.0 {
        return 0.0;
    }
    if probability >= 1.0 {
        return 1.0;
    }
    let log_p = probability.ln();
    let log_q = (-probability).ln_1p();
    let mut maximum_log = f64::NEG_INFINITY;
    let mut logs = Vec::with_capacity(trials - successes + 1);
    for count in successes..=trials {
        let value =
            log_binomial(trials, count) + count as f64 * log_p + (trials - count) as f64 * log_q;
        maximum_log = maximum_log.max(value);
        logs.push(value);
    }
    if !maximum_log.is_finite() {
        return 0.0;
    }
    let scaled_sum: f64 = logs.iter().map(|value| (*value - maximum_log).exp()).sum();
    (maximum_log.exp() * scaled_sum).clamp(0.0, 1.0)
}

fn log_binomial(n: usize, k: usize) -> f64 {
    let k = k.min(n - k);
    let mut result = 0.0;
    for index in 1..=k {
        result += ((n - k + index) as f64).ln() - (index as f64).ln();
    }
    result
}

fn insert_solution(
    solution: Solution,
    best: &mut Option<Solution>,
    runner_up: &mut Option<Solution>,
    config: &SolveConfig,
) {
    let Some(current_best) = best.as_ref() else {
        *best = Some(solution);
        return;
    };
    if solution
        .camera_to_world
        .angle_to(current_best.camera_to_world)
        <= config.same_solution_tolerance_rad
    {
        if solution_is_better(&solution, current_best) {
            *best = Some(solution);
        }
        return;
    }
    if solution_is_better(&solution, current_best) {
        let old_best = best.replace(solution);
        if let Some(old_best) = old_best {
            *runner_up = Some(old_best);
        }
        return;
    }
    match runner_up {
        Some(current_runner)
            if solution
                .camera_to_world
                .angle_to(current_runner.camera_to_world)
                <= config.same_solution_tolerance_rad =>
        {
            if solution_is_better(&solution, current_runner) {
                *current_runner = solution;
            }
        }
        Some(current_runner) if solution_is_better(&solution, current_runner) => {
            *current_runner = solution;
        }
        None => *runner_up = Some(solution),
        Some(_) => {}
    }
}

fn solution_is_better(left: &Solution, right: &Solution) -> bool {
    left.matches.len() > right.matches.len()
        || (left.matches.len() == right.matches.len()
            && (left.raw_chance_probability < right.raw_chance_probability
                || (left.raw_chance_probability == right.raw_chance_probability
                    && left.rms_residual_rad < right.rms_residual_rad)))
}

fn comparable_runner(best: &Solution, runner: &Solution, config: &SolveConfig) -> bool {
    runner
        .matches
        .len()
        .saturating_add(config.ambiguity_match_margin)
        >= best.matches.len()
        && runner.rms_residual_rad
            <= (best.rms_residual_rad * config.ambiguity_rms_ratio)
                .max(config.match_tolerance_rad * 0.05)
        && runner.camera_to_world.angle_to(best.camera_to_world)
            > config.same_solution_tolerance_rad
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Vec3;
    use core::{convert::Infallible, ops::ControlFlow};

    struct SyntheticDatabase {
        stars: Vec<CatalogStar>,
        pattern: PatternCandidate,
        alternate_pattern: Option<PatternCandidate>,
        conservative_extras: usize,
    }

    impl PatternIndex for SyntheticDatabase {
        type Error = Infallible;

        fn visit_candidates(
            &self,
            _query: PatternQuery,
            visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
        ) -> Result<QueryStats, Self::Error> {
            let mut records_scanned = 1;
            let stop = visitor(self.pattern).is_break();
            if !stop {
                if let Some(pattern) = self.alternate_pattern {
                    records_scanned += 1;
                    let _ = visitor(pattern);
                }
            }
            Ok(QueryStats {
                buckets_touched: 1,
                records_scanned,
                bytes_read: u64::from(records_scanned) * 32,
                budget_exhausted: false,
            })
        }
    }

    impl StarCatalog for SyntheticDatabase {
        type Error = Infallible;

        fn visit_cap(
            &self,
            query: CatalogQuery,
            visitor: &mut dyn FnMut(CatalogStar) -> ControlFlow<()>,
        ) -> Result<CatalogQueryStats, Self::Error> {
            let mut stats = CatalogQueryStats {
                cells_scanned: 1,
                ..CatalogQueryStats::default()
            };
            for index in 0..self.conservative_extras {
                if stats.records_scanned >= query.max_records_to_scan
                    || stats.bytes_read.saturating_add(8) > query.max_bytes_to_read
                {
                    stats.budget_exhausted = true;
                    return Ok(stats);
                }
                stats.records_scanned = stats.records_scanned.saturating_add(1);
                stats.bytes_read = stats.bytes_read.saturating_add(8);
                let extra = CatalogStar {
                    id: StarId(10_000 + index as u32),
                    direction: UnitVec3::X,
                    magnitude: f32::NAN,
                };
                if visitor(extra).is_break() {
                    return Ok(stats);
                }
            }
            for star in &self.stars {
                if stats.records_scanned >= query.max_records_to_scan
                    || stats.bytes_read.saturating_add(8) > query.max_bytes_to_read
                {
                    stats.budget_exhausted = true;
                    return Ok(stats);
                }
                stats.records_scanned = stats.records_scanned.saturating_add(1);
                stats.bytes_read = stats.bytes_read.saturating_add(8);
                if query.center.angle_to(star.direction) <= query.radius_rad
                    && visitor(*star).is_break()
                {
                    break;
                }
            }
            Ok(stats)
        }

        fn density_hint_per_steradian(
            &self,
            _center: UnitVec3,
            _field_radius_rad: f64,
        ) -> Result<Option<f64>, Self::Error> {
            Ok(Some(10.0))
        }
    }

    fn synthetic_case(
        noise: f64,
        add_outlier: bool,
    ) -> (SyntheticDatabase, Vec<Observation>, Quat) {
        let expected = Quat::from_axis_angle(UnitVec3::new(0.31, -0.42, 0.85).expect("axis"), 0.63)
            .expect("rotation");
        let base = [
            (-0.22, -0.16),
            (0.27, -0.14),
            (-0.13, 0.28),
            (0.24, 0.25),
            (-0.36, 0.04),
            (0.38, 0.05),
            (0.02, -0.32),
            (0.01, 0.38),
        ];
        let clean_rays: Vec<_> = base
            .iter()
            .map(|(x, y)| UnitVec3::new(*x, *y, 1.0).expect("ray"))
            .collect();
        let stars: Vec<_> = clean_rays
            .iter()
            .enumerate()
            .map(|(index, ray)| CatalogStar {
                id: StarId(index as u32),
                direction: expected.rotate_unit(*ray),
                magnitude: index as f32,
            })
            .collect();
        let mut observations: Vec<_> = clean_rays
            .iter()
            .enumerate()
            .map(|(index, ray)| {
                let perturbation = Vec3::new(
                    noise * ((index % 3) as f64 - 1.0),
                    noise * (((index * 2) % 5) as f64 - 2.0),
                    0.0,
                );
                Observation {
                    ray: (ray.as_vec() + perturbation)
                        .normalized()
                        .expect("noisy ray"),
                    flux: 100.0 - index as f32,
                }
            })
            .collect();
        if add_outlier {
            observations.push(Observation {
                ray: UnitVec3::new(0.62, -0.51, 1.0).expect("outlier"),
                flux: 25.0,
            });
        }
        let pattern = PatternCandidate {
            stars: [stars[0], stars[1], stars[2], stars[3]],
        };
        (
            SyntheticDatabase {
                stars,
                pattern,
                alternate_pattern: None,
                conservative_extras: 0,
            },
            observations,
            expected,
        )
    }

    fn permissive_config() -> SolveConfig {
        SolveConfig {
            ratio_tolerance: 0.02,
            seed_tolerance_rad: 0.003,
            match_tolerance_rad: 0.004,
            min_matches: 4,
            max_chance_probability: 1.0,
            same_solution_tolerance_rad: 0.005,
            ..SolveConfig::default()
        }
    }

    fn assert_same_online_acceptance_trace(left: Diagnostics, right: Diagnostics) {
        assert_eq!(left.quads_tested, right.quads_tested);
        assert_eq!(left.index_queries, right.index_queries);
        assert_eq!(left.index_buckets_touched, right.index_buckets_touched);
        assert_eq!(left.index_records_scanned, right.index_records_scanned);
        assert_eq!(left.index_bytes_read, right.index_bytes_read);
        assert_eq!(left.candidates_tested, right.candidates_tested);
        assert_eq!(left.hypotheses_tested, right.hypotheses_tested);
        assert_eq!(left.pending_hypotheses_peak, right.pending_hypotheses_peak);
        assert_eq!(
            left.accepted_observation_prefix,
            right.accepted_observation_prefix
        );
        assert_eq!(
            left.accepted_evidence_prefix,
            right.accepted_evidence_prefix
        );
        assert_eq!(
            left.ambiguity_query_completed,
            right.ambiguity_query_completed
        );
        assert_eq!(left.budget_exhausted, right.budget_exhausted);
        assert_eq!(
            left.search_stopped_after_solution,
            right.search_stopped_after_solution
        );
        assert_eq!(left.deadline_exceeded, right.deadline_exceeded);
        // Catalog/pair counters include optional all-source enrichment and may
        // therefore grow when a fainter input tail is present.
    }

    #[test]
    fn solves_known_rotation_with_noise_and_outlier() {
        let (database, observations, expected) = synthetic_case(2.0e-5, true);
        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.6,
                rectilinear_radial_reference_rad: 0.6,
                max_pattern_edge_rad: None,
            },
            &permissive_config(),
        )
        .expect("solver succeeds");
        let SolveOutcome::Solved {
            solution,
            diagnostics,
        } = outcome
        else {
            panic!("expected unique solution, got {outcome:?}");
        };
        assert_eq!(
            solution
                .seed_matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id.0))
                .collect::<Vec<_>>(),
            vec![(0, 0), (1, 1), (2, 2), (3, 3)]
        );
        for matched in solution.seed_matches {
            let predicted = solution
                .camera_to_world
                .rotate_unit(observations[matched.observation_index].ray);
            assert_eq!(
                matched.residual_rad,
                predicted.angle_to(matched.catalog_direction)
            );
            assert!(matched.residual_rad <= permissive_config().seed_tolerance_rad);
            assert!(
                solution
                    .matches
                    .iter()
                    .all(|verification| verification.observation_index
                        != matched.observation_index)
            );
        }
        assert_eq!(solution.matches.len(), 4);
        assert!(solution.camera_to_world.angle_to(expected) < 1.0e-4);
        assert!(solution.rms_residual_rad < 1.0e-4);
        assert!(solution.max_residual_rad <= permissive_config().match_tolerance_rad);
        assert!(solution.chance_probability >= solution.raw_chance_probability);
        assert!(
            solution
                .matches
                .iter()
                .all(|matched| matched.observation_index != observations.len() - 1)
        );
        assert_eq!(diagnostics.quads_tested, 1);
        assert!(diagnostics.search_stopped_after_solution);
    }

    #[test]
    fn candidate_local_scale_recovers_a_miscalibrated_rectilinear_field() {
        let (database, mut observations, expected) = synthetic_case(0.0, false);
        for observation in &mut observations {
            observation.ray = rectilinear_scale_ray(observation.ray, 1.04).unwrap();
        }
        let request = SolveRequest {
            observations: &observations,
            field_radius_rad: 0.7,
            rectilinear_radial_reference_rad: 0.7,
            max_pattern_edge_rad: None,
        };
        let mut fixed = permissive_config();
        fixed.seed_tolerance_rad = 5.0e-5;
        fixed.match_tolerance_rad = 5.0e-5;
        assert!(matches!(
            solve(&database, &database, request, &fixed).unwrap(),
            SolveOutcome::NoMatch { .. }
        ));

        let mut fitted = fixed;
        fitted.largest_edge_relative_tolerance = 0.08;
        fitted.rectilinear_fov_scale_max_delta = 0.08;
        let SolveOutcome::Solved { solution, .. } =
            solve(&database, &database, request, &fitted).unwrap()
        else {
            panic!("candidate-local scale should recover the field");
        };
        assert!((solution.fov_scale - 1.0 / 1.04).abs() < 1.0e-6);
        assert!(solution.camera_to_world.angle_to(expected) < 1.0e-5);
        assert!(solution.max_residual_rad <= fitted.match_tolerance_rad);
    }

    #[test]
    fn seed_only_scale_and_radial_fit_recovers_a_distorted_field() {
        let expected =
            Quat::from_axis_angle(UnitVec3::new(0.31, -0.42, 0.85).unwrap(), 0.63).unwrap();
        let raw_rays = [
            UnitVec3::new(-0.08, -0.06, 1.0).unwrap(),
            UnitVec3::new(0.31, -0.04, 1.0).unwrap(),
            UnitVec3::new(-0.14, 0.27, 1.0).unwrap(),
            UnitVec3::new(0.38, 0.22, 1.0).unwrap(),
            UnitVec3::new(-0.34, 0.03, 1.0).unwrap(),
            UnitVec3::new(0.02, -0.36, 1.0).unwrap(),
            UnitVec3::new(0.11, 0.16, 1.0).unwrap(),
            UnitVec3::new(-0.25, -0.23, 1.0).unwrap(),
        ];
        let reference = 0.5_f64;
        let expected_scale = 1.035;
        let expected_radial = -0.12;
        let calibrated = raw_rays.map(|ray| {
            apply_rectilinear_calibration(ray, expected_scale, expected_radial, reference).unwrap()
        });
        let stars: Vec<_> = calibrated
            .iter()
            .enumerate()
            .map(|(index, ray)| CatalogStar {
                id: StarId(index as u32),
                direction: expected.rotate_unit(*ray),
                magnitude: index as f32,
            })
            .collect();
        let database = SyntheticDatabase {
            stars: stars.clone(),
            pattern: PatternCandidate {
                stars: [stars[0], stars[1], stars[2], stars[3]],
            },
            alternate_pattern: None,
            conservative_extras: 0,
        };
        let observations: Vec<_> = raw_rays
            .into_iter()
            .enumerate()
            .map(|(index, ray)| Observation {
                ray,
                flux: 100.0 - index as f32,
            })
            .collect();
        let request = SolveRequest {
            observations: &observations,
            field_radius_rad: 0.6,
            rectilinear_radial_reference_rad: reference,
            max_pattern_edge_rad: None,
        };
        let mut scale_only = permissive_config();
        scale_only.ratio_tolerance = 0.25;
        scale_only.largest_edge_relative_tolerance = 0.08;
        scale_only.rectilinear_fov_scale_max_delta = 0.08;
        scale_only.seed_tolerance_rad = 1.0e-5;
        scale_only.match_tolerance_rad = 1.0e-5;
        assert!(matches!(
            solve(&database, &database, request, &scale_only).unwrap(),
            SolveOutcome::NoMatch { .. }
        ));

        let mut self_calibrating = scale_only;
        self_calibrating.rectilinear_radial_max_abs = 0.25;
        let SolveOutcome::Solved { solution, .. } =
            solve(&database, &database, request, &self_calibrating).unwrap()
        else {
            panic!("seed-only scale-plus-radial fit should recover the field");
        };
        assert!((solution.fov_scale - expected_scale).abs() < 2.0e-5);
        assert!((solution.rectilinear_radial_coefficient - expected_radial).abs() < 2.0e-4);
        assert_eq!(solution.rectilinear_radial_reference_rad, reference);
        assert!(solution.camera_to_world.angle_to(expected) < 2.0e-5);
        assert_eq!(solution.matches.len(), 4);
        assert!(solution.max_residual_rad <= self_calibrating.match_tolerance_rad);

        // The model is trained only on the four seed rays. Once the fixed
        // eight-observation look accepts, appending lower-flux unmatched rays
        // must leave the fitted model and accepted evidence byte-for-byte
        // unchanged.
        let mut monotonic = self_calibrating;
        monotonic.verification_policy = VerificationPolicy::OnlineCumulative;
        monotonic.validation_screen_observations = 0;
        monotonic.budgets.max_observations = 16;
        monotonic.budgets.max_pattern_observations = 16;
        let SolveOutcome::Solved {
            solution: base_solution,
            diagnostics: base_diagnostics,
        } = solve(&database, &database, request, &monotonic).unwrap()
        else {
            panic!("base radial prefix must solve");
        };
        let mut tailed = observations.clone();
        for (index, (x, y)) in [
            (-0.04, 0.11),
            (0.18, -0.08),
            (-0.21, 0.13),
            (0.26, 0.09),
            (-0.29, -0.12),
            (0.16, 0.31),
            (-0.11, -0.30),
            (0.32, -0.18),
        ]
        .into_iter()
        .enumerate()
        {
            tailed.push(Observation {
                ray: UnitVec3::new(x, y, 1.0).unwrap(),
                flux: 10.0 - index as f32,
            });
        }
        let SolveOutcome::Solved {
            solution: tail_solution,
            diagnostics: tail_diagnostics,
        } = solve(
            &database,
            &database,
            SolveRequest {
                observations: &tailed,
                ..request
            },
            &monotonic,
        )
        .unwrap()
        else {
            panic!("faint unmatched tail must not revoke the radial prefix");
        };
        assert_eq!(
            tail_solution.fov_scale.to_bits(),
            base_solution.fov_scale.to_bits()
        );
        assert_eq!(
            tail_solution.rectilinear_radial_coefficient.to_bits(),
            base_solution.rectilinear_radial_coefficient.to_bits()
        );
        assert_eq!(
            tail_solution.rectilinear_radial_reference_rad.to_bits(),
            base_solution.rectilinear_radial_reference_rad.to_bits()
        );
        assert_eq!(tail_solution.matches.len(), base_solution.matches.len());
        for (tail, base) in tail_solution.matches.iter().zip(&base_solution.matches) {
            assert_eq!(tail.observation_index, base.observation_index);
            assert_eq!(tail.star_id, base.star_id);
            assert_eq!(tail.residual_rad.to_bits(), base.residual_rad.to_bits());
        }
        assert_eq!(
            tail_solution.raw_chance_probability.to_bits(),
            base_solution.raw_chance_probability.to_bits()
        );
        assert_eq!(
            tail_solution.chance_probability.to_bits(),
            base_solution.chance_probability.to_bits()
        );
        assert_eq!(
            tail_solution
                .camera_to_world
                .angle_to(base_solution.camera_to_world),
            0.0
        );
        assert_same_online_acceptance_trace(tail_diagnostics, base_diagnostics);
    }

    #[test]
    fn radial_fit_is_bounded_and_falls_back_on_an_unidentifiable_ring() {
        let reference = 0.5;
        let radius = 0.3;
        let ring = [
            UnitVec3::new(radius, 0.0, 1.0).unwrap(),
            UnitVec3::new(0.0, radius, 1.0).unwrap(),
            UnitVec3::new(-radius, 0.0, 1.0).unwrap(),
            UnitVec3::new(0.0, -radius, 1.0).unwrap(),
        ];
        let catalog = Invariant::from_rays(
            ring.map(|ray| apply_rectilinear_calibration(ray, 1.03, -0.2, reference).unwrap()),
        )
        .unwrap();
        let (calibration, _) =
            fit_candidate_rectilinear_calibration(ring, catalog, 0.92, 1.08, 0.25, reference)
                .unwrap();
        assert_eq!(calibration.radial_coefficient, 0.0);

        // A positive bound smaller than the preferred finite-difference step
        // must never evaluate outside the caller's declared parameter box.
        let tiny_bound = 1.0e-7;
        let (tiny, _) =
            fit_candidate_rectilinear_calibration(ring, catalog, 0.92, 1.08, tiny_bound, reference)
                .unwrap();
        assert!(tiny.radial_coefficient.abs() <= tiny_bound);
    }

    #[test]
    fn normalized_radial_map_enforces_its_fold_free_reference_domain() {
        let reference = 0.5_f64;
        let inner = UnitVec3::new(0.5 * reference.tan(), 0.0, 1.0).unwrap();
        let rim = UnitVec3::new(reference.tan(), 0.0, 1.0).unwrap();
        for radial in [-0.25, 0.25] {
            let inner_calibrated =
                apply_rectilinear_calibration(inner, 1.0, radial, reference).unwrap();
            let rim_calibrated =
                apply_rectilinear_calibration(rim, 1.0, radial, reference).unwrap();
            assert!(inner_calibrated.angle_to(UnitVec3::Z) < rim_calibrated.angle_to(UnitVec3::Z));
        }
        let outside = UnitVec3::new(1.01 * reference.tan(), 0.0, 1.0).unwrap();
        assert!(apply_rectilinear_calibration(outside, 1.0, 0.1, reference).is_err());
        let rear = UnitVec3::new(0.0, 0.0, -1.0).unwrap();
        assert!(apply_rectilinear_calibration(rear, 1.0, 0.1, reference).is_err());

        let (database, mut observations, _) = synthetic_case(0.0, false);
        observations[7].ray = outside;
        let mut config = permissive_config();
        config.rectilinear_radial_max_abs = 0.25;
        let result = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.7,
                rectilinear_radial_reference_rad: reference,
                max_pattern_edge_rad: None,
            },
            &config,
        );
        assert!(matches!(
            result,
            Err(SolveError::InvalidObservation { index: 7 })
        ));
    }

    #[test]
    fn success_finishes_current_pattern_query_to_detect_ambiguity() {
        let (mut database, observations, _) = synthetic_case(0.0, false);
        let alternative =
            Quat::from_axis_angle(UnitVec3::new(-0.37, 0.81, 0.24).expect("axis"), 1.1)
                .expect("rotation");
        let alternate_stars: Vec<_> = observations
            .iter()
            .enumerate()
            .map(|(index, observation)| CatalogStar {
                id: StarId(1_000 + index as u32),
                direction: alternative.rotate_unit(observation.ray),
                magnitude: index as f32,
            })
            .collect();
        database.alternate_pattern = Some(PatternCandidate {
            stars: [
                alternate_stars[0],
                alternate_stars[1],
                alternate_stars[2],
                alternate_stars[3],
            ],
        });
        database.stars.extend(alternate_stars);

        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.8,
                rectilinear_radial_reference_rad: 0.8,
                max_pattern_edge_rad: None,
            },
            &permissive_config(),
        )
        .expect("ambiguous query succeeds");
        let SolveOutcome::Ambiguous {
            best,
            runner_up,
            diagnostics,
        } = outcome
        else {
            panic!("both candidates in the accepted query must be evaluated");
        };
        assert_eq!(best.matches.len(), 4);
        assert_eq!(runner_up.matches.len(), 4);
        assert!(
            best.camera_to_world.angle_to(runner_up.camera_to_world)
                > permissive_config().same_solution_tolerance_rad
        );
        assert_eq!(diagnostics.quads_tested, 1);
        assert_eq!(diagnostics.candidates_tested, 2);
        assert!(diagnostics.search_stopped_after_solution);

        let mut appended = observations.clone();
        let false_ray = UnitVec3::new(0.0, 0.0, -1.0).unwrap();
        while appended.len() < 256 {
            appended.push(Observation {
                ray: false_ray,
                flux: 1.0 / appended.len() as f32,
            });
        }
        let mut monotonic = permissive_config();
        monotonic.budgets.max_observations = 256;
        monotonic.budgets.max_pattern_observations = 128;
        monotonic.verification_policy = VerificationPolicy::OnlineCumulative;
        monotonic.validation_screen_observations = 0;
        let request = |slice| SolveRequest {
            observations: slice,
            field_radius_rad: 0.8,
            rectilinear_radial_reference_rad: 0.8,
            max_pattern_edge_rad: None,
        };
        let base = solve(&database, &database, request(&observations), &monotonic).unwrap();
        let tail = solve(&database, &database, request(&appended), &monotonic).unwrap();
        let (
            SolveOutcome::Ambiguous {
                best: base_best,
                runner_up: base_runner_up,
                diagnostics: base_diagnostics,
            },
            SolveOutcome::Ambiguous {
                best: tail_best,
                runner_up: tail_runner_up,
                diagnostics: tail_diagnostics,
            },
        ) = (base, tail)
        else {
            panic!("the same-query ambiguity must be tail-append stable");
        };
        assert_same_online_acceptance_trace(tail_diagnostics, base_diagnostics);
        assert_eq!(tail_best.camera_to_world, base_best.camera_to_world);
        assert_eq!(
            tail_runner_up.camera_to_world,
            base_runner_up.camera_to_world
        );
        assert_eq!(tail_best.chance_probability, base_best.chance_probability);
        assert_eq!(
            tail_runner_up.chance_probability,
            base_runner_up.chance_probability
        );
    }

    #[test]
    fn pattern_search_and_final_verification_use_stars_beyond_the_screen() {
        let (mut database, mut observations, expected) = synthetic_case(0.0, false);
        let extra_rays = [
            UnitVec3::new(-0.31, -0.27, 1.0).expect("ray"),
            UnitVec3::new(0.33, -0.29, 1.0).expect("ray"),
            UnitVec3::new(-0.29, 0.34, 1.0).expect("ray"),
            UnitVec3::new(0.35, 0.31, 1.0).expect("ray"),
        ];
        for (offset, ray) in extra_rays.into_iter().enumerate() {
            let index = observations.len();
            database.stars.push(CatalogStar {
                id: StarId(index as u32),
                direction: expected.rotate_unit(ray),
                magnitude: index as f32,
            });
            observations.push(Observation {
                ray,
                flux: 92.0 - offset as f32,
            });
        }

        // The fourth pattern observation is deliberately fainter than the
        // eight-observation validation screen. It remains eligible for quad
        // enumeration because the pattern search uses the complete selected
        // set, not the screen prefix.
        observations[0].flux = 200.0;
        observations[1].flux = 190.0;
        observations[2].flux = 180.0;
        observations[3].flux = 1.0;
        let mut config = permissive_config();
        config.validation_screen_observations = 8;
        config.budgets.max_observations = observations.len();
        config.budgets.max_quads = 1_000;

        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.8,
                rectilinear_radial_reference_rad: 0.8,
                max_pattern_edge_rad: None,
            },
            &config,
        )
        .expect("full-observation search succeeds");
        let SolveOutcome::Solved {
            solution,
            diagnostics,
        } = outcome
        else {
            panic!("expected unique solution, got {outcome:?}");
        };
        assert!(
            solution
                .seed_matches
                .iter()
                .any(|matched| matched.observation_index == 3)
        );
        assert_eq!(solution.matches.len(), observations.len() - 4);
        assert!(
            solution
                .matches
                .iter()
                .any(|matched| matched.observation_index == 11)
        );
        assert!(diagnostics.quads_tested > 1);
        assert_eq!(
            solution.raw_chance_probability,
            chance_probability(
                observations.len() - 4,
                observations.len() - 4,
                10.0,
                config.match_tolerance_rad,
            )
        );
        assert!(solution.camera_to_world.angle_to(expected) < 1.0e-10);
    }

    #[test]
    fn disabled_screen_lets_the_complete_field_establish_significance() {
        // Shape this like the retained P8214607 failure: the correct fixed
        // hypothesis has 13/28 independent matches in the brightest-32
        // prefix, but 69/142 over the complete selected field. At the
        // production null probability the prefix alone is not significant,
        // while the complete field is decisive.
        let expected = Quat::from_axis_angle(UnitVec3::new(0.31, -0.42, 0.85).expect("axis"), 0.63)
            .expect("rotation");
        let golden_angle = core::f64::consts::PI * (3.0 - 5.0_f64.sqrt());
        let true_rays: Vec<_> = (0..73)
            .map(|index| {
                let z = 0.35 + 0.60 * (index as f64 + 0.5) / 73.0;
                let radius = (1.0 - z * z).sqrt();
                let longitude = golden_angle * index as f64;
                UnitVec3::new(radius * longitude.cos(), radius * longitude.sin(), z)
                    .expect("unit Fibonacci ray")
            })
            .collect();
        let stars: Vec<_> = true_rays
            .iter()
            .enumerate()
            .map(|(index, ray)| CatalogStar {
                id: StarId(index as u32),
                direction: expected.rotate_unit(*ray),
                magnitude: index as f32,
            })
            .collect();
        let pattern = PatternCandidate {
            stars: [stars[0], stars[1], stars[2], stars[3]],
        };
        let database = SyntheticDatabase {
            stars,
            pattern,
            alternate_pattern: None,
            conservative_extras: 0,
        };

        let mut observations = Vec::with_capacity(146);
        for ray in &true_rays[..4] {
            observations.push(Observation {
                ray: *ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        for screen_rank in 0..28 {
            let ray = if screen_rank < 13 {
                true_rays[4 + screen_rank]
            } else {
                UnitVec3::Z
            };
            observations.push(Observation {
                ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        for ray in &true_rays[17..] {
            observations.push(Observation {
                ray: *ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        for _ in 0..58 {
            observations.push(Observation {
                ray: UnitVec3::Z,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        assert_eq!(observations.len(), 146);

        let match_tolerance_rad = 0.0575;
        let mut config = permissive_config();
        config.budgets.max_observations = observations.len();
        config.budgets.max_quads = 1;
        config.validation_screen_observations = 32;
        config.seed_tolerance_rad = 1.0e-8;
        config.match_tolerance_rad = match_tolerance_rad;
        config.max_chance_probability = 1.0e-6;
        let screen_raw = chance_probability(28, 13, 10.0, match_tolerance_rad);
        assert!(familywise_chance(screen_raw, 1, 1.0) > config.max_chance_probability);

        let screened_outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 1.3,
                rectilinear_radial_reference_rad: 1.3,
                max_pattern_edge_rad: None,
            },
            &config,
        )
        .expect("screened solve completes");
        assert!(matches!(screened_outcome, SolveOutcome::NoMatch { .. }));

        config.validation_screen_observations = 0;
        let full_outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 1.3,
                rectilinear_radial_reference_rad: 1.3,
                max_pattern_edge_rad: None,
            },
            &config,
        )
        .expect("full-field solve completes");
        let SolveOutcome::Solved { solution, .. } = full_outcome else {
            panic!("complete field should establish significance: {full_outcome:?}");
        };
        assert_eq!(solution.matches.len(), 69);
        let expected_raw = chance_probability(142, 69, 10.0, match_tolerance_rad);
        assert_eq!(solution.raw_chance_probability, expected_raw);
        assert_eq!(
            solution.chance_probability,
            familywise_chance(expected_raw, 1, 1.0)
        );
        assert!(solution.chance_probability <= config.max_chance_probability);
    }

    #[test]
    fn online_cumulative_evidence_lets_later_rows_help_without_revoking_a_pass() {
        let expected =
            Quat::from_axis_angle(UnitVec3::new(0.31, -0.42, 0.85).unwrap(), 0.63).unwrap();
        let golden_angle = core::f64::consts::PI * (3.0 - 5.0_f64.sqrt());
        let true_rays: Vec<_> = (0..52)
            .map(|index| {
                let z = 0.35 + 0.60 * (index as f64 + 0.5) / 52.0;
                let radius = (1.0 - z * z).sqrt();
                let longitude = golden_angle * index as f64;
                UnitVec3::new(radius * longitude.cos(), radius * longitude.sin(), z).unwrap()
            })
            .collect();
        let stars: Vec<_> = true_rays
            .iter()
            .enumerate()
            .map(|(index, ray)| CatalogStar {
                id: StarId(index as u32),
                direction: expected.rotate_unit(*ray),
                magnitude: index as f32,
            })
            .collect();
        let database = SyntheticDatabase {
            pattern: PatternCandidate {
                stars: [stars[0], stars[1], stars[2], stars[3]],
            },
            stars,
            alternate_pattern: None,
            conservative_extras: 0,
        };

        // Cumulative held-out matches along the brightness sequence are
        // 1/4, 5/12, 9/20, 13/28, 18/44, 22/60, 25/76,
        // 28/92, 34/124, then 48/188. The exact first crossing is determined
        // by the anytime-valid evidence process, not a predeclared look.
        let cumulative = [
            (4, 1),
            (12, 5),
            (20, 9),
            (28, 13),
            (44, 18),
            (60, 22),
            (76, 25),
            (92, 28),
            (124, 34),
            (188, 48),
        ];
        let mut is_match = vec![false; 188];
        let mut previous_trials = 0;
        let mut previous_matches = 0;
        for (trials, matches) in cumulative {
            for offset in 0..matches - previous_matches {
                let span = trials - previous_trials;
                let position = previous_trials + offset * span / (matches - previous_matches);
                is_match[position] = true;
            }
            previous_trials = trials;
            previous_matches = matches;
        }
        assert_eq!(is_match.iter().filter(|value| **value).count(), 48);

        let mut observations = Vec::with_capacity(256);
        for ray in &true_rays[..4] {
            observations.push(Observation {
                ray: *ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        let false_ray = UnitVec3::new(0.0, 0.0, -1.0).unwrap();
        let mut next_true = 4;
        for matched in is_match {
            let ray = if matched {
                let ray = true_rays[next_true];
                next_true += 1;
                ray
            } else {
                false_ray
            };
            observations.push(Observation {
                ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        while observations.len() < 256 {
            observations.push(Observation {
                ray: false_ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }

        let match_tolerance_rad = 0.0575;
        let mut config = permissive_config();
        config.budgets.max_observations = 256;
        config.budgets.max_pattern_observations = 4;
        config.budgets.max_quads = 1;
        config.verification_policy = VerificationPolicy::OnlineCumulative;
        config.validation_screen_observations = 0;
        config.seed_tolerance_rad = 1.0e-8;
        config.match_tolerance_rad = match_tolerance_rad;
        config.max_chance_probability = 1.0e-6;
        let request = |slice| SolveRequest {
            observations: slice,
            field_radius_rad: 1.3,
            rectilinear_radial_reference_rad: 1.3,
            max_pattern_edge_rad: None,
        };

        assert!(matches!(
            solve(&database, &database, request(&observations[..128]), &config).unwrap(),
            SolveOutcome::NoMatch { .. }
        ));
        let SolveOutcome::Solved {
            solution: at_full,
            diagnostics: diagnostics_full,
        } = solve(&database, &database, request(&observations), &config).unwrap()
        else {
            panic!("the online evidence should eventually establish significance");
        };
        let crossing = diagnostics_full.accepted_evidence_prefix;
        assert!(crossing > 128 && crossing <= 192);
        assert!(matches!(
            solve(
                &database,
                &database,
                request(&observations[..crossing - 1]),
                &config,
            )
            .unwrap(),
            SolveOutcome::NoMatch { .. }
        ));
        let SolveOutcome::Solved {
            solution: at_crossing,
            diagnostics: diagnostics_crossing,
        } = solve(
            &database,
            &database,
            request(&observations[..crossing]),
            &config,
        )
        .unwrap()
        else {
            panic!("the exact first-crossing prefix should solve");
        };
        assert!(at_crossing.matches.len() >= config.min_matches);
        assert!(at_crossing.raw_chance_probability <= at_crossing.chance_probability);
        assert!(at_crossing.chance_probability <= config.max_chance_probability);
        assert_eq!(
            at_full.raw_chance_probability,
            at_crossing.raw_chance_probability
        );
        assert_eq!(at_full.chance_probability, at_crossing.chance_probability);
        assert!(at_full.matches.len() >= at_crossing.matches.len());
        assert!(at_crossing.matches.iter().all(|locked| {
            at_full.matches.iter().any(|matched| {
                matched.observation_index == locked.observation_index
                    && matched.star_id == locked.star_id
            })
        }));
        assert_same_online_acceptance_trace(diagnostics_full, diagnostics_crossing);
    }

    #[test]
    fn global_phases_keep_a_decoy_before_the_winner_append_stable() {
        let (mut database, seed_observations, _) = synthetic_case(0.0, false);
        let true_pattern = database.pattern;
        let decoy_attitude =
            Quat::from_axis_angle(UnitVec3::new(0.73, -0.19, 0.66).unwrap(), 1.71).unwrap();
        let decoy_stars = core::array::from_fn(|index| CatalogStar {
            id: StarId(1_000 + index as u32),
            direction: decoy_attitude.rotate_unit(seed_observations[index].ray),
            magnitude: index as f32,
        });
        // The first emitted candidate is an exact seed geometry under a wrong
        // attitude. It consumes a full rejected verification phase before the
        // real candidate, which is precisely the ordering that made an
        // appended tail consume extra work in the old hypothesis-major loop.
        database.pattern = PatternCandidate { stars: decoy_stars };
        database.alternate_pattern = Some(true_pattern);

        let false_ray = UnitVec3::new(0.0, 0.0, -1.0).unwrap();
        let mut observations = Vec::with_capacity(256);
        observations.extend(seed_observations[..4].iter().copied());
        while observations.len() < 128 {
            observations.push(Observation {
                ray: false_ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        observations.extend(seed_observations[4..8].iter().copied());
        while observations.len() < 256 {
            observations.push(Observation {
                ray: false_ray,
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        // Preserve the explicit vector order as the brightness order.
        for (rank, observation) in observations.iter_mut().enumerate() {
            observation.flux = 1_000.0 - rank as f32;
        }

        let mut config = permissive_config();
        config.budgets.max_observations = 256;
        config.budgets.max_pattern_observations = 4;
        config.budgets.max_quads = 10;
        config.budgets.max_catalog_cells = 10_000;
        config.budgets.max_catalog_records = 100_000;
        config.budgets.max_catalog_bytes = 10_000_000;
        config.verification_policy = VerificationPolicy::OnlineCumulative;
        config.validation_screen_observations = 0;
        config.seed_tolerance_rad = 1.0e-8;
        config.match_tolerance_rad = 1.0e-6;
        config.max_chance_probability = 1.0e-6;
        let request = |slice| SolveRequest {
            observations: slice,
            field_radius_rad: 1.3,
            rectilinear_radial_reference_rad: 1.3,
            max_pattern_edge_rad: None,
        };

        assert!(matches!(
            solve(&database, &database, request(&observations[..128]), &config).unwrap(),
            SolveOutcome::NoMatch { .. }
        ));

        let reference_calls = core::cell::Cell::new(0_usize);
        let reference = solve_with_deadline(
            &database,
            &database,
            request(&observations[..192]),
            &config,
            || {
                reference_calls.set(reference_calls.get() + 1);
                false
            },
        )
        .unwrap();
        let SolveOutcome::Solved {
            solution: reference_solution,
            diagnostics: reference_diagnostics,
        } = reference
        else {
            panic!("the true candidate must pass at the 192-source phase");
        };
        assert_eq!(reference_solution.matches.len(), 4);

        // Pin the seed/index search budgets to the completed shorter-input
        // trace. Optional all-source enrichment is deliberately not pinned:
        // an appended tail may add catalog work after acceptance.
        config.budgets.max_quads = reference_diagnostics.quads_tested;
        config.budgets.max_candidates = reference_diagnostics.candidates_tested;
        config.budgets.max_hypotheses = reference_diagnostics.hypotheses_tested;
        // Index accounting treats equality as exhaustion, so retain one
        // sentinel record/byte beyond the exact completed trace.
        config.budgets.max_index_records = reference_diagnostics
            .index_records_scanned
            .saturating_add(1);
        config.budgets.max_index_bytes = reference_diagnostics.index_bytes_read.saturating_add(1);
        let base_calls = core::cell::Cell::new(0_usize);
        let base = solve_with_deadline(
            &database,
            &database,
            request(&observations[..192]),
            &config,
            || {
                base_calls.set(base_calls.get() + 1);
                false
            },
        )
        .unwrap();
        let tail_calls = core::cell::Cell::new(0_usize);
        let tail = solve_with_deadline(
            &database,
            &database,
            request(&observations),
            &config,
            || {
                tail_calls.set(tail_calls.get() + 1);
                false
            },
        )
        .unwrap();
        let (
            SolveOutcome::Solved {
                solution: base_solution,
                diagnostics: base_diagnostics,
            },
            SolveOutcome::Solved {
                solution: tail_solution,
                diagnostics: tail_diagnostics,
            },
        ) = (base, tail)
        else {
            panic!("the exact shorter-prefix work cap must also solve with an appended tail");
        };
        assert!(tail_calls.get() >= base_calls.get());
        assert_same_online_acceptance_trace(tail_diagnostics, base_diagnostics);
        assert_same_online_acceptance_trace(base_diagnostics, reference_diagnostics);
        assert_eq!(tail_solution.camera_to_world, base_solution.camera_to_world);
        assert_eq!(tail_solution.fov_scale, base_solution.fov_scale);
        assert_eq!(
            tail_solution.rectilinear_radial_coefficient,
            base_solution.rectilinear_radial_coefficient
        );
        assert_eq!(
            tail_solution.rectilinear_radial_reference_rad,
            base_solution.rectilinear_radial_reference_rad
        );
        let match_signature = |matched: &Match| {
            let direction = matched.catalog_direction.as_vec();
            (
                matched.observation_index,
                matched.star_id,
                direction.x.to_bits(),
                direction.y.to_bits(),
                direction.z.to_bits(),
                matched.residual_rad.to_bits(),
            )
        };
        assert_eq!(
            tail_solution
                .seed_matches
                .iter()
                .map(match_signature)
                .collect::<Vec<_>>(),
            base_solution
                .seed_matches
                .iter()
                .map(match_signature)
                .collect::<Vec<_>>()
        );
        assert_eq!(
            tail_solution
                .matches
                .iter()
                .map(match_signature)
                .collect::<Vec<_>>(),
            base_solution
                .matches
                .iter()
                .map(match_signature)
                .collect::<Vec<_>>()
        );
        assert_eq!(
            tail_solution.raw_chance_probability,
            base_solution.raw_chance_probability
        );
        assert_eq!(
            tail_solution.chance_probability,
            base_solution.chance_probability
        );
    }

    #[test]
    fn spatial_search_order_is_stable_when_a_faint_stage_is_appended() {
        let (database, original, _) = synthetic_case(0.0, false);
        // The indexed seed occupies brightness ranks 0, 2, 5, and 7, so the
        // stage-eight local search must deepen beyond its first frontier.
        let order = [0, 4, 1, 5, 6, 2, 7, 3];
        let mut observations: Vec<_> = order
            .iter()
            .enumerate()
            .map(|(rank, index)| Observation {
                ray: original[*index].ray,
                flux: 100.0 - rank as f32,
            })
            .collect();
        let base_len = observations.len();
        let clustered_false = UnitVec3::new(1.8, 1.7, 1.0).unwrap();
        while observations.len() < 32 {
            observations.push(Observation {
                ray: clustered_false,
                flux: 10.0 - observations.len() as f32 * 0.1,
            });
        }

        let mut config = permissive_config();
        config.budgets.max_observations = 256;
        config.budgets.max_pattern_observations = 128;
        config.verification_policy = VerificationPolicy::OnlineCumulative;
        config.validation_screen_observations = 0;
        config.max_chance_probability = 1.0e-6;
        let request = |slice| SolveRequest {
            observations: slice,
            field_radius_rad: 1.3,
            rectilinear_radial_reference_rad: 1.3,
            max_pattern_edge_rad: Some(0.8),
        };

        let reference = solve(
            &database,
            &database,
            request(&observations[..base_len]),
            &config,
        )
        .unwrap();
        let SolveOutcome::Solved {
            diagnostics: reference_diagnostics,
            ..
        } = reference
        else {
            panic!("stage-eight reference must solve");
        };
        assert!(reference_diagnostics.quads_tested > 1);
        config.budgets.max_quads = reference_diagnostics.quads_tested;

        let base_calls = core::cell::Cell::new(0_usize);
        let base = solve_with_deadline(
            &database,
            &database,
            request(&observations[..base_len]),
            &config,
            || {
                base_calls.set(base_calls.get() + 1);
                false
            },
        )
        .unwrap();
        let tail_calls = core::cell::Cell::new(0_usize);
        let tail = solve_with_deadline(
            &database,
            &database,
            request(&observations),
            &config,
            || {
                tail_calls.set(tail_calls.get() + 1);
                false
            },
        )
        .unwrap();
        let (
            SolveOutcome::Solved {
                solution: base_solution,
                diagnostics: base_diagnostics,
            },
            SolveOutcome::Solved {
                solution: tail_solution,
                diagnostics: tail_diagnostics,
            },
        ) = (base, tail)
        else {
            panic!("both the base and appended-tail solve must succeed");
        };
        assert!(tail_calls.get() >= base_calls.get());
        assert_same_online_acceptance_trace(tail_diagnostics, base_diagnostics);
        assert_eq!(
            tail_solution
                .seed_matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id))
                .collect::<Vec<_>>(),
            base_solution
                .seed_matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id))
                .collect::<Vec<_>>()
        );
        assert_eq!(
            tail_solution
                .matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id))
                .collect::<Vec<_>>(),
            base_solution
                .matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id))
                .collect::<Vec<_>>()
        );
        assert_eq!(
            tail_solution.raw_chance_probability,
            base_solution.raw_chance_probability
        );
        assert_eq!(
            tail_solution.chance_probability,
            base_solution.chance_probability
        );
        assert_eq!(tail_solution.camera_to_world, base_solution.camera_to_world);
    }

    #[test]
    fn online_tail_rank_beyond_128_is_pattern_eligible_and_append_stable() {
        assert!((0..4).all(|prefix| !is_online_pattern_stage(prefix)));
        assert!((4..=256).all(is_online_pattern_stage));

        let expected = Quat::from_axis_angle(UnitVec3::new(0.3, -0.4, 0.5).unwrap(), 0.4).unwrap();
        let seed_rays = [
            UnitVec3::new(0.0, 0.0, 1.0).unwrap(),
            UnitVec3::new(0.002, 0.0, 1.0).unwrap(),
            UnitVec3::new(0.0, 0.003, 1.0).unwrap(),
            UnitVec3::new(0.002, 0.003, 1.0).unwrap(),
        ];
        let heldout_rays = [
            UnitVec3::new(-0.001, 0.001, 1.0).unwrap(),
            UnitVec3::new(0.003, 0.001, 1.0).unwrap(),
            UnitVec3::new(0.001, -0.002, 1.0).unwrap(),
            UnitVec3::new(0.001, 0.004, 1.0).unwrap(),
        ];
        let stars: Vec<_> = seed_rays
            .iter()
            .chain(&heldout_rays)
            .enumerate()
            .map(|(index, ray)| CatalogStar {
                id: StarId(index as u32),
                direction: expected.rotate_unit(*ray),
                magnitude: index as f32,
            })
            .collect();
        let database = SyntheticDatabase {
            pattern: PatternCandidate {
                stars: core::array::from_fn(|index| stars[index]),
            },
            stars,
            alternate_pattern: None,
            conservative_extras: 0,
        };

        let mut observations = Vec::with_capacity(256);
        observations.extend(
            seed_rays[..3]
                .iter()
                .enumerate()
                .map(|(rank, ray)| Observation {
                    ray: *ray,
                    flux: 1_000.0 - rank as f32,
                }),
        );
        for index in 0..125 {
            let longitude = 2.0 * core::f64::consts::PI * index as f64 / 125.0;
            observations.push(Observation {
                ray: UnitVec3::new(1.5 * longitude.cos(), 1.5 * longitude.sin(), 1.0).unwrap(),
                flux: 1_000.0 - observations.len() as f32,
            });
        }
        assert_eq!(observations.len(), 128);
        observations.push(Observation {
            ray: seed_rays[3],
            flux: 1_000.0 - observations.len() as f32,
        });
        let heldout_start = observations.len();
        observations.extend(
            heldout_rays
                .iter()
                .enumerate()
                .map(|(offset, ray)| Observation {
                    ray: *ray,
                    flux: 1_000.0 - (heldout_start + offset) as f32,
                }),
        );
        let crossing_input_len = observations.len();
        while observations.len() < 256 {
            observations.push(Observation {
                ray: UnitVec3::new(0.0, 0.0, -1.0).unwrap(),
                flux: 1_000.0 - observations.len() as f32,
            });
        }

        let mut config = permissive_config();
        config.budgets.max_observations = 256;
        config.budgets.max_pattern_observations = 256;
        config.verification_policy = VerificationPolicy::OnlineCumulative;
        config.validation_screen_observations = 0;
        config.seed_tolerance_rad = 1.0e-8;
        config.match_tolerance_rad = 1.0e-8;
        config.min_matches = 4;
        config.max_chance_probability = 1.0;
        let request = |slice| SolveRequest {
            observations: slice,
            field_radius_rad: 1.3,
            rectilinear_radial_reference_rad: 1.3,
            max_pattern_edge_rad: Some(0.01),
        };

        let base = solve(
            &database,
            &database,
            request(&observations[..crossing_input_len]),
            &config,
        )
        .unwrap();
        let tail = solve(&database, &database, request(&observations), &config).unwrap();
        let (
            SolveOutcome::Solved {
                solution: base_solution,
                diagnostics: base_diagnostics,
            },
            SolveOutcome::Solved {
                solution: tail_solution,
                diagnostics: tail_diagnostics,
            },
        ) = (base, tail)
        else {
            panic!("the rank-128 seed must solve before its unmatched tail");
        };
        assert_eq!(
            base_solution
                .seed_matches
                .map(|matched| matched.observation_index),
            [0, 1, 2, 128]
        );
        assert_eq!(tail_solution.camera_to_world, base_solution.camera_to_world);
        assert_eq!(
            tail_solution
                .matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id))
                .collect::<Vec<_>>(),
            base_solution
                .matches
                .iter()
                .map(|matched| (matched.observation_index, matched.star_id))
                .collect::<Vec<_>>()
        );
        assert_same_online_acceptance_trace(tail_diagnostics, base_diagnostics);
        assert!(base_diagnostics.quads_tested < 100);
    }

    #[test]
    fn cooperative_deadline_is_a_normal_no_match_outcome() {
        let (database, observations, _) = synthetic_case(0.0, false);
        let outcome = solve_with_deadline(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.6,
                rectilinear_radial_reference_rad: 0.6,
                max_pattern_edge_rad: None,
            },
            &permissive_config(),
            || true,
        )
        .expect("deadline is not a fatal solver error");
        let SolveOutcome::NoMatch {
            reason,
            diagnostics,
        } = outcome
        else {
            panic!("expired solve must not search");
        };
        assert_eq!(reason, NoMatchReason::DeadlineExceeded);
        assert!(diagnostics.deadline_exceeded);
        assert_eq!(diagnostics.quads_tested, 0);
    }

    #[test]
    fn deadline_after_final_verification_waits_for_all_star_refinement() {
        let (database, mut observations, expected) = synthetic_case(0.0, false);
        let seed_bias =
            Quat::from_axis_angle(UnitVec3::new(0.41, -0.17, 0.73).expect("axis"), 0.001)
                .expect("seed bias");
        for observation in &mut observations[..4] {
            observation.ray = seed_bias.rotate_unit(observation.ray);
        }
        let seed_pairs: [AttitudePair; 4] = core::array::from_fn(|index| AttitudePair {
            camera: observations[index].ray,
            world: database.pattern.stars[index].direction,
            weight: observation_weight(observations[index]),
        });
        let seed_only = estimate_attitude(&seed_pairs).expect("seed attitude");
        let mut checks = 0_u32;
        let outcome = solve_with_deadline(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.6,
                rectilinear_radial_reference_rad: 0.6,
                max_pattern_edge_rad: None,
            },
            &permissive_config(),
            || {
                checks += 1;
                // Expiry is observed at the first natural boundary after the
                // atomic all-star refinement has already produced a solution.
                checks >= 6
            },
        )
        .expect("deadline is not a fatal solver error");
        let SolveOutcome::Solved {
            solution,
            diagnostics,
        } = outcome
        else {
            panic!("accepted verification must survive refinement timeout");
        };
        assert_eq!(solution.matches.len(), 4);
        assert!(diagnostics.deadline_exceeded);
        assert!(
            solution.camera_to_world.angle_to(expected) < seed_only.angle_to(expected),
            "all-star refinement must improve on the four-seed attitude"
        );
        assert!(solution.camera_to_world.angle_to(seed_only) > 1.0e-8);
    }

    #[test]
    fn diversified_quad_order_tolerates_two_brighter_outliers() {
        let (database, mut observations, expected) = synthetic_case(1.0e-5, false);
        observations.push(Observation {
            ray: UnitVec3::new(0.65, -0.42, 1.0).expect("outlier"),
            flux: 200.0,
        });
        observations.push(Observation {
            ray: UnitVec3::new(-0.58, 0.49, 1.0).expect("outlier"),
            flux: 190.0,
        });
        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.8,
                rectilinear_radial_reference_rad: 0.8,
                max_pattern_edge_rad: None,
            },
            &permissive_config(),
        )
        .expect("bounded search succeeds");
        let SolveOutcome::Solved { solution, .. } = outcome else {
            panic!("expected unique solution, got {outcome:?}");
        };
        assert_eq!(solution.matches.len(), 4);
        assert!(solution.camera_to_world.angle_to(expected) < 1.0e-4);
    }

    #[test]
    fn wide_field_search_uses_local_seed_and_all_stars_for_final_fit() {
        let (mut database, mut observations, expected) = synthetic_case(0.0, false);
        // A dense unrelated region supplies twenty globally brighter stars.
        // A global faintest-rank frontier would enumerate C(20,4) quads here
        // before reaching the indexed pattern. Fixed power-of-two local stages
        // reach the fainter region without making append order data-dependent.
        for offset in 0..20 {
            let angle = offset as f64 * 0.31;
            let ray = UnitVec3::new(2.0 + 0.05 * angle.cos(), 0.05 * angle.sin(), 1.0).unwrap();
            observations.push(Observation {
                ray,
                flux: 200.0 - offset as f32,
            });
        }

        // A real, deliberately faint validation star lies outside the seed's
        // index-sized angular subfield. It must still participate in the sole
        // final all-observation verification and refinement pass.
        let remote_ray = UnitVec3::new(1.7, 0.0, 1.0).unwrap();
        let remote_index = observations.len();
        database.stars.push(CatalogStar {
            id: StarId(remote_index as u32),
            direction: expected.rotate_unit(remote_ray),
            magnitude: 8.0,
        });
        observations.push(Observation {
            ray: remote_ray,
            flux: 1.0,
        });

        let maximum_edge = 0.7;
        let mut config = permissive_config();
        config.budgets.max_observations = observations.len();
        config.budgets.max_quads = 10_000;
        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 1.2,
                rectilinear_radial_reference_rad: 1.2,
                max_pattern_edge_rad: Some(maximum_edge),
            },
            &config,
        )
        .expect("wide local-field solve succeeds");
        let SolveOutcome::Solved {
            solution,
            diagnostics,
        } = outcome
        else {
            panic!("expected unique wide-field solution, got {outcome:?}");
        };
        for left in 0..solution.seed_matches.len() {
            for right in left + 1..solution.seed_matches.len() {
                let left_ray = observations[solution.seed_matches[left].observation_index].ray;
                let right_ray = observations[solution.seed_matches[right].observation_index].ray;
                assert!(left_ray.angle_to(right_ray) <= maximum_edge);
            }
        }
        assert!(
            solution
                .seed_matches
                .iter()
                .all(|matched| matched.observation_index < 4)
        );
        assert!(
            solution
                .matches
                .iter()
                .any(|matched| matched.observation_index == remote_index)
        );
        assert!(solution.seed_matches.iter().all(|seed| {
            observations[seed.observation_index]
                .ray
                .angle_to(remote_ray)
                > maximum_edge
        }));
        assert!(solution.camera_to_world.angle_to(expected) < 1.0e-10);
        assert!(
            diagnostics.quads_tested <= 2_000,
            "fixed local brightness frontiers should bypass the dense brighter region: {:?}",
            diagnostics
        );
    }

    #[test]
    fn recomputes_candidate_invariant_from_catalog_vectors() {
        let (mut database, mut observations, _) = synthetic_case(0.0, false);
        observations.truncate(4);
        // A coarse lookup routed this candidate, but its decoded fourth vertex
        // belongs to a different tetrahedron.
        database.pattern.stars[3] = database.stars[4];
        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.8,
                rectilinear_radial_reference_rad: 0.8,
                max_pattern_edge_rad: None,
            },
            &SolveConfig {
                ratio_tolerance: 1.0e-6,
                min_matches: 1,
                max_chance_probability: 1.0,
                ..permissive_config()
            },
        )
        .expect("candidate is safely rejected");
        let SolveOutcome::NoMatch { diagnostics, .. } = outcome else {
            panic!("false metadata must not solve");
        };
        assert_eq!(diagnostics.hypotheses_tested, 0);
    }

    #[test]
    fn conservative_catalog_emissions_are_hard_bounded() {
        let (mut database, observations, _) = synthetic_case(0.0, false);
        database.conservative_extras = 100;
        let mut config = permissive_config();
        config.budgets.max_catalog_records = 3;
        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.8,
                rectilinear_radial_reference_rad: 0.8,
                max_pattern_edge_rad: None,
            },
            &config,
        )
        .expect("budget stop is a normal outcome");
        let SolveOutcome::NoMatch {
            reason,
            diagnostics,
        } = outcome
        else {
            panic!("budgeted verification cannot solve");
        };
        assert_eq!(reason, NoMatchReason::BudgetExhausted);
        assert_eq!(diagnostics.catalog_records_scanned, 3);
    }

    #[test]
    fn rejects_seed_only_evidence_by_default() {
        let (database, mut observations, _) = synthetic_case(0.0, false);
        observations.truncate(4);
        let outcome = solve(
            &database,
            &database,
            SolveRequest {
                observations: &observations,
                field_radius_rad: 0.6,
                rectilinear_radial_reference_rad: 0.6,
                max_pattern_edge_rad: None,
            },
            &SolveConfig {
                min_matches: 1,
                max_chance_probability: 1.0,
                ..permissive_config()
            },
        )
        .expect("solver succeeds");
        assert!(matches!(
            outcome,
            SolveOutcome::NoMatch {
                reason: NoMatchReason::RejectedByVerification,
                ..
            }
        ));
    }

    #[test]
    fn malformed_inputs_are_errors_not_panics() {
        let (database, mut observations, _) = synthetic_case(0.0, false);
        let request = SolveRequest {
            observations: &observations,
            field_radius_rad: 0.6,
            rectilinear_radial_reference_rad: 0.6,
            max_pattern_edge_rad: None,
        };
        let mut invalid_monotonic = permissive_config();
        invalid_monotonic.verification_policy = VerificationPolicy::OnlineCumulative;
        invalid_monotonic.validation_screen_observations = 0;
        invalid_monotonic.min_matches = 1;
        invalid_monotonic.budgets.max_observations = 7;
        invalid_monotonic.budgets.max_pattern_observations = 4;
        assert!(matches!(
            solve(&database, &database, request, &invalid_monotonic),
            Err(SolveError::InvalidConfig(_))
        ));
        invalid_monotonic.budgets.max_observations = 257;
        invalid_monotonic.budgets.max_pattern_observations = 128;
        assert!(matches!(
            solve(&database, &database, request, &invalid_monotonic),
            Err(SolveError::InvalidConfig(_))
        ));
        invalid_monotonic.budgets.max_observations = 256;
        invalid_monotonic.budgets.max_pattern_observations = 257;
        assert!(matches!(
            solve(&database, &database, request, &invalid_monotonic),
            Err(SolveError::InvalidConfig(_))
        ));
        assert!(matches!(
            solve(
                &database,
                &database,
                SolveRequest {
                    observations: &observations,
                    field_radius_rad: 0.6,
                    rectilinear_radial_reference_rad: 0.6,
                    max_pattern_edge_rad: Some(0.0),
                },
                &permissive_config(),
            ),
            Err(SolveError::InvalidMaxPatternEdge)
        ));
        observations[2].flux = f32::NAN;
        assert!(matches!(
            solve(
                &database,
                &database,
                SolveRequest {
                    observations: &observations,
                    field_radius_rad: 0.6,
                    rectilinear_radial_reference_rad: 0.6,
                    max_pattern_edge_rad: None,
                },
                &permissive_config(),
            ),
            Err(SolveError::InvalidObservation { index: 2 })
        ));
    }

    #[test]
    fn binomial_tail_is_bounded_and_monotonic() {
        let loose = binomial_tail(10, 4, 0.1);
        let strict = binomial_tail(10, 7, 0.1);
        assert!((0.0..=1.0).contains(&strict));
        assert!(strict < loose);
        assert!((binomial_tail(10, 4, 0.1) - 0.012_795_198_4).abs() < 1.0e-13);
        assert!((binomial_tail(10, 7, 0.1) - 9.121_6e-6).abs() < 1.0e-15);
        assert!((binomial_tail(20, 6, 0.01) - 3.436_415_985_755_714e-8).abs() < 1.0e-19);
    }

    #[test]
    fn familywise_alpha_spending_includes_external_trials() {
        assert!((familywise_chance(0.001, 2, 3.0) - 0.018).abs() < 1.0e-15);
        assert_eq!(familywise_chance(0.5, u32::MAX, 10.0), 1.0);
    }

    #[test]
    fn online_e_process_matches_fixed_mixture_vectors() {
        let mut evidence = OnlineEvidence::new();
        for _ in 0..8 {
            evidence.update(true, 0.104);
        }
        assert!((evidence.max_log_e - 16.010_089_216_124_516).abs() < 1.0e-12);
        assert!((evidence.raw_anytime_probability() - 1.114_054_914_121_623e-7).abs() < 1e-18);
        assert!((evidence.adjusted_probability(1, 1.0) - 3.803_621_397_022_438e-7).abs() < 1e-18);

        let mut delayed = OnlineEvidence::new();
        for _ in 0..13 {
            delayed.update(false, 0.104);
        }
        for _ in 0..15 {
            delayed.update(true, 0.104);
        }
        assert!((delayed.max_log_e - 14.682_270_091_068_56).abs() < 1.0e-12);

        let mut impossible = OnlineEvidence::new();
        for _ in 0..20 {
            impossible.update(false, 0.0);
        }
        assert_eq!(impossible.max_log_e, 0.0);
        impossible.update(true, 0.0);
        assert_eq!(impossible.max_log_e, f64::INFINITY);

        let mut certain = OnlineEvidence::new();
        for _ in 0..20 {
            certain.update(true, 1.0);
        }
        assert_eq!(certain.max_log_e, 0.0);
    }

    #[test]
    fn online_hypothesis_weights_are_predeclared_and_block_normalized() {
        for block in 0..=30 {
            let first = 1_u32 << block;
            let last = (1_u32 << (block + 1)) - 1;
            let expected_mass =
                (f64::from(block) + 1.0).sqrt().recip() - (f64::from(block) + 2.0).sqrt().recip();
            assert_eq!(
                online_hypothesis_weight(first),
                online_hypothesis_weight(last)
            );
            assert!(
                (online_hypothesis_weight(first) * f64::from(1_u32 << block) - expected_mass).abs()
                    < 1.0e-16
            );
        }
        assert!(online_hypothesis_weight(1) > online_hypothesis_weight(2));
        assert_eq!(
            online_hypothesis_weight(1_u32 << 31),
            online_hypothesis_weight(u32::MAX)
        );
        let final_block_mass = 32.0_f64.sqrt().recip() - 33.0_f64.sqrt().recip();
        assert!(
            (online_hypothesis_weight(1_u32 << 31) * f64::from(1_u32 << 31) - final_block_mass)
                .abs()
                < 1.0e-16
        );
    }

    #[test]
    fn matching_is_minimum_residual_among_maximum_cardinality() {
        let adjacency = vec![
            vec![
                Edge {
                    star_index: 0,
                    residual_rad: 0.001,
                },
                Edge {
                    star_index: 1,
                    residual_rad: 0.99,
                },
            ],
            vec![
                Edge {
                    star_index: 0,
                    residual_rad: 0.002,
                },
                Edge {
                    star_index: 1,
                    residual_rad: 0.003,
                },
            ],
        ];
        assert_eq!(
            minimum_cost_maximum_matching(&adjacency, 2, 1.0),
            vec![Some(0), Some(1)]
        );
    }

    #[test]
    fn sparse_matching_avoids_the_dense_matrix_without_changing_the_objective() {
        // 252 * (4_000 + 252) * 8 exceeds the dense matching arena. Keep the
        // actual graph sparse so this is also a regression test for the
        // production memory shape on a catalog cap with many distinct stars.
        let mut adjacency = (0..252).map(|_| Vec::new()).collect::<Vec<_>>();
        adjacency[0] = vec![
            Edge {
                star_index: 0,
                residual_rad: 0.001,
            },
            Edge {
                star_index: 1,
                residual_rad: 0.99,
            },
        ];
        adjacency[1] = vec![
            Edge {
                star_index: 0,
                residual_rad: 0.002,
            },
            Edge {
                star_index: 1,
                residual_rad: 0.003,
            },
        ];
        assert_eq!(
            minimum_cost_maximum_matching(&adjacency, 4_000, 1.0)[..2],
            [Some(0), Some(1)]
        );
    }

    #[test]
    fn incremental_matching_reroutes_and_matches_exhaustive_cardinality() {
        fn maximum_cardinality(row_masks: &[u8], row: usize, used: u8) -> usize {
            let Some(&mask) = row_masks.get(row) else {
                return 0;
            };
            let mut best = maximum_cardinality(row_masks, row + 1, used);
            for star in 0..3 {
                let bit = 1_u8 << star;
                if mask & bit != 0 && used & bit == 0 {
                    best = best.max(1 + maximum_cardinality(row_masks, row + 1, used | bit));
                }
            }
            best
        }

        for graph in 0_u16..(1_u16 << 9) {
            let row_masks =
                core::array::from_fn::<_, 3, _>(|row| ((graph >> (row * 3)) & 0b111) as u8);
            let mut stars: Vec<_> = (0..3)
                .map(|index| PendingStar {
                    star: CatalogStar {
                        id: StarId(index),
                        direction: UnitVec3::Z,
                        magnitude: index as f32,
                    },
                    matched_row: None,
                })
                .collect();
            let mut rows = Vec::new();
            let mut edges = Vec::new();
            let mut incremental = 0;
            for (observation_index, mask) in row_masks.iter().copied().enumerate() {
                let edge_start = edges.len();
                for star_index in 0..3 {
                    if mask & (1_u8 << star_index) != 0 {
                        edges.push(PendingEdge {
                            star_index,
                            residual_rad: star_index as f64,
                        });
                    }
                }
                let edge_end = edges.len();
                if edge_start == edge_end {
                    continue;
                }
                let row_index = rows.len();
                rows.push(PendingRow {
                    observation_index,
                    edge_start,
                    edge_end,
                    matched_star: None,
                });
                let mut seen = [false; 3];
                incremental += usize::from(augment_pending_matching(
                    row_index, &mut rows, &mut stars, &edges, &mut seen,
                ));
            }
            assert_eq!(incremental, maximum_cardinality(&row_masks, 0, 0));
            assert_eq!(
                rows.iter().filter(|row| row.matched_star.is_some()).count(),
                incremental
            );
            assert_eq!(
                stars
                    .iter()
                    .filter(|star| star.matched_row.is_some())
                    .count(),
                incremental
            );
        }

        let mut stars: Vec<_> = (0..2)
            .map(|index| PendingStar {
                star: CatalogStar {
                    id: StarId(index),
                    direction: UnitVec3::Z,
                    magnitude: index as f32,
                },
                matched_row: None,
            })
            .collect();
        let edges = [
            PendingEdge {
                star_index: 0,
                residual_rad: 0.0,
            },
            PendingEdge {
                star_index: 1,
                residual_rad: 1.0,
            },
            PendingEdge {
                star_index: 0,
                residual_rad: 0.0,
            },
        ];
        let mut rows = vec![
            PendingRow {
                observation_index: 0,
                edge_start: 0,
                edge_end: 2,
                matched_star: None,
            },
            PendingRow {
                observation_index: 1,
                edge_start: 2,
                edge_end: 3,
                matched_star: None,
            },
        ];
        let mut seen = [false; 2];
        assert!(augment_pending_matching(
            0, &mut rows, &mut stars, &edges, &mut seen,
        ));
        seen.fill(false);
        assert!(augment_pending_matching(
            1, &mut rows, &mut stars, &edges, &mut seen,
        ));
        assert_eq!(rows[0].matched_star, Some(1));
        assert_eq!(rows[1].matched_star, Some(0));
    }
}
