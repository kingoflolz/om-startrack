//! Storage-neutral pattern-index and verification-catalog interfaces.

use core::ops::ControlFlow;

use crate::{Invariant, UnitVec3};

/// Logical catalog identifier. On-disk indexes may pack this into fewer bits.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct StarId(pub u32);

/// One catalog direction used for hypothesis construction or verification.
#[derive(Clone, Copy, Debug)]
pub struct CatalogStar {
    pub id: StarId,
    pub direction: UnitVec3,
    /// Catalog magnitude, where lower is brighter. NaN is permitted when the
    /// source catalog does not provide photometry; solver geometry ignores it.
    pub magnitude: f32,
}

/// Exact query presented to a flash-backed pattern index.
#[derive(Clone, Copy, Debug)]
pub struct PatternQuery {
    pub invariant: Invariant,
    /// Maximum absolute difference for every normalized ratio.
    pub ratio_tolerance: f64,
    /// Inclusive largest-edge acceptance interval in radians.
    pub largest_edge_min_rad: f64,
    pub largest_edge_max_rad: f64,
    /// Hard per-call scan limits. Implementations must stop before exceeding
    /// either value, even when a bucket contains no matching candidates.
    pub max_records_to_scan: u32,
    pub max_bytes_to_read: u64,
}

/// Candidate returned by an approximate/quantized lookup.
#[derive(Clone, Copy, Debug)]
pub struct PatternCandidate {
    /// Fully decoded vertices. Flash-backed adapters should charge these point
    /// reads to the enclosing pattern query before invoking the callback.
    pub stars: [CatalogStar; 4],
}

/// I/O/accounting information returned by a lookup implementation.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct QueryStats {
    pub buckets_touched: u32,
    pub records_scanned: u32,
    pub bytes_read: u64,
    /// The implementation stopped because one of `PatternQuery`'s scan limits
    /// was reached, rather than because the candidate range was exhausted.
    pub budget_exhausted: bool,
}

/// Bounded spatial-catalog query. Limits apply to underlying work, including
/// records that a conservative cell lookup examines but does not emit.
#[derive(Clone, Copy, Debug)]
pub struct CatalogQuery {
    pub center: UnitVec3,
    pub radius_rad: f64,
    pub max_cells_to_scan: u32,
    pub max_records_to_scan: u32,
    pub max_bytes_to_read: u64,
}

/// Work performed by one spatial-catalog query.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CatalogQueryStats {
    pub cells_scanned: u32,
    pub records_scanned: u32,
    pub bytes_read: u64,
    pub budget_exhausted: bool,
}

/// Approximate tetra-pattern lookup. Returning `Break` lets the core stop a
/// page scan immediately when a work budget has been reached.
pub trait PatternIndex {
    type Error;

    fn visit_candidates(
        &self,
        query: PatternQuery,
        visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
    ) -> Result<QueryStats, Self::Error>;

    /// Cancellation-aware variant used by deadline-bounded production solves.
    /// The default preserves compatibility for small/in-memory adapters by
    /// polling between emitted candidates. Backing stores with potentially
    /// large non-emitting scans should override this and poll inside their raw
    /// record traversal.
    fn visit_candidates_cancelable(
        &self,
        query: PatternQuery,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut dyn FnMut(PatternCandidate) -> ControlFlow<()>,
    ) -> Result<QueryStats, Self::Error> {
        self.visit_candidates(query, &mut |candidate| {
            if should_stop() {
                ControlFlow::Break(())
            } else {
                visitor(candidate)
            }
        })
    }
}

/// Spatial lookup used to verify an attitude. Pattern vertices arrive already
/// decoded in [`PatternCandidate`], avoiding a second set of unbudgeted point
/// reads. `visit_cap` may conservatively emit extras; the core applies the exact
/// angular gate.
pub trait StarCatalog {
    type Error;

    fn visit_cap(
        &self,
        query: CatalogQuery,
        visitor: &mut dyn FnMut(CatalogStar) -> ControlFlow<()>,
    ) -> Result<CatalogQueryStats, Self::Error>;

    /// Cancellation-aware variant used by deadline-bounded production solves.
    /// The default polls between emitted stars. File-backed catalogs should
    /// override it so conservative cell/record scans which emit nothing are
    /// interruptible as well.
    fn visit_cap_cancelable(
        &self,
        query: CatalogQuery,
        should_stop: &mut dyn FnMut() -> bool,
        visitor: &mut dyn FnMut(CatalogStar) -> ControlFlow<()>,
    ) -> Result<CatalogQueryStats, Self::Error> {
        self.visit_cap(query, &mut |star| {
            if should_stop() {
                ControlFlow::Break(())
            } else {
                visitor(star)
            }
        })
    }

    /// Optional O(1), precomputed density bound in stars per steradian.
    /// Implementations must not scan catalog records here; return `None` to use
    /// [`crate::SolveConfig`]'s conservative fallback. For
    /// [`crate::VerificationPolicy::OnlineCumulative`], the returned value must
    /// conservatively upper-bound the conditional probability of a fresh
    /// held-out observation landing inside any eligible catalog association
    /// cap, after conditioning on seed construction, search order, and earlier
    /// rows. The anytime false-lock guarantee relies on that stronger contract;
    /// a merely descriptive local average is insufficient.
    fn density_hint_per_steradian(
        &self,
        _center: UnitVec3,
        _field_radius_rad: f64,
    ) -> Result<Option<f64>, Self::Error> {
        Ok(None)
    }
}
