//! Android JNI owner for the mmap-backed tetra3 runtime.
//!
//! The public Java API lives in `dev.omshutter.plate.NativeTetra3Bridge`.
//! Handles are registry IDs rather than raw pointers, so close racing with an
//! in-flight solve remains memory safe.

#![deny(unsafe_op_in_unsafe_fn)]

mod engine;
mod error;
mod jni_bridge;
mod registry;
mod resolver;
