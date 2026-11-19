//! Compact, checked camera-lens correction for the tetra3 plate solver.
//!
//! The runtime is pure Rust and contains no filesystem or native-library calls.
//! The implemented distortion equations are the documented Lensfun Poly3,
//! Poly5, and PTLens models; Adobe Camera Model (ACM) profiles are deliberately
//! unsupported and fail while parsing.
//!
//! Pixel coordinates have a top-left origin. Camera rays use the right-handed
//! image convention `+x` right, `+y` down, and `+z` forward.

#![forbid(unsafe_code)]

mod distortion;
mod error;
mod format;
mod geometry;
mod profile;
mod projection;

pub use distortion::DistortionModel;
pub use error::{Error, Result};
pub use format::{Database, T3LF_MAGIC, T3LF_VERSION};
pub use geometry::{CameraGeometry, CropRect, Orientation, Pixel};
pub use profile::{
    CalibrationGeometry, CorrectedCoord, DistortionKnot, LensProfile, PreparedLens, ProfileId,
};
pub use projection::{Projection, UnitRay};
