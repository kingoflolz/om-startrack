//! Compact, flash-oriented tetra3 index support.
//!
//! [`IndexView`] parses a `.t3ix` file directly from a byte slice. The parser
//! never casts the input to Rust structs: every integer is decoded explicitly
//! as little endian and all offsets and counts are checked before use.

#![deny(unsafe_op_in_unsafe_fn)]

mod crc;
mod format;
mod view;

#[cfg(feature = "mmap")]
mod mmap;
pub mod writer;

pub use format::{
    MAGIC, MAX_LEVELS, MAX_RATIO_TOLERANCE_Q8, PATTERN_RECORD_SIZE, VERIFICATION_STAR_SIZE,
    VERSION_MAJOR, VERSION_MINOR, crc32c,
};
#[cfg(feature = "mmap")]
pub use mmap::{MapError, MappedIndex};
pub(crate) use view::SectionKind;
pub use view::{
    CandidateRecord, Error, IndexView, LevelDescriptor, LevelView, QueryBudget, QueryStats,
    SpatialGrid, VerificationStar,
};
pub use view::{CapBudget, CapStats};
