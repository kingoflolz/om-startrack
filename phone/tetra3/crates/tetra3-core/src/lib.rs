//! Format- and lens-model-independent tetra3 plate-solving primitives.
//!
//! The core deliberately consumes already-corrected, camera-frame unit rays.
//! Camera and lens adapters can use [`project_observations_bounded`] to turn
//! measured centroids into those rays. Catalog storage and pattern lookup are
//! abstracted behind callback-based traits, allowing Android callers to serve
//! records directly from mmap or bounded `pread` flash access.
//!
//! # Coordinate convention
//!
//! Camera rays look along positive Z, with positive X to image right and
//! positive Y toward image bottom. This is a right-handed, Android-native
//! image frame (`X cross Y = Z`). Every returned attitude is named
//! `camera_to_world`: applying it to a camera ray produces its catalog/world
//! direction. The catalog may use ICRS, J2000, or another frame as long as it
//! uses one frame consistently.

#![forbid(unsafe_code)]

mod camera;
mod index;
mod invariant;
mod math;
mod solver;

pub use camera::{
    BoundedProjectionError, PixelCentroid, PixelObservation, project_observations_bounded,
};
pub use index::{
    CatalogQuery, CatalogQueryStats, CatalogStar, PatternCandidate, PatternIndex, PatternQuery,
    QueryStats, StarCatalog, StarId,
};
pub use invariant::{Invariant, InvariantError};
pub use math::{AttitudePair, MathError, Quat, UnitVec3, Vec3, estimate_attitude};
pub use solver::{
    Diagnostics, Match, NoMatchReason, Observation, Solution, SolveBudgets, SolveConfig,
    SolveError, SolveOutcome, SolveRequest, VerificationPolicy, apply_rectilinear_calibration,
    solve, solve_with_deadline,
};
