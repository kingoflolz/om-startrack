//! Android-oriented tetra3 plate-solving components.
//!
//! The facade deliberately keeps the catalog/index builder out of the runtime
//! dependency graph. Runtime callers provide an index as a byte slice (for
//! example, an mmap of an uncompressed Android asset), prepare a camera model,
//! and solve from already-extracted centroids.

#![forbid(unsafe_code)]

pub use tetra3_core as core;
pub use tetra3_index as index;
pub use tetra3_lens as lens;

mod runtime;

pub mod production;

pub use runtime::{
    LensProjectionError, LensProjector, PixelSolveError, RuntimeIndex, RuntimeIndexError,
    RuntimeSolveError,
};
