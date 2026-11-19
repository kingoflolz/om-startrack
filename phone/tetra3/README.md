# tetra3 for Android

This directory is a self-contained Rust implementation of the tetra3
lost-in-space plate-solving algorithm. It is designed for an Android phone but
keeps the solver core independent of Android. The small JNI adapter and the
app's reproducible native/asset packaging are documented in
[`android/README.md`](android/README.md).

The runtime accepts star centroids (or precomputed camera rays) and reads a
versioned index directly from a byte slice. The intended Android deployment is
an uncompressed, page-aligned APK asset mapped into the process; the large
pattern payload is never deserialized into a Rust object graph.

## Workspace

The Rust workspace targets the standard library used by both supported
consumers: the host CLI and the Android JNI library. Memory mapping remains an
optional feature so crates that do not open an index file avoid `memmap2`.

- `crates/tetra3-core`: invariant generation, attitude hypotheses, matching,
  statistical verification, and refinement.
- `crates/tetra3-index`: checked `.t3ix` parser, flash-local lookup, and the
  deterministic format writer used by host tools.
- `crates/tetra3-lens`: compact lens profiles and checked pixel-to-ray mapping.
  It independently implements only the required Lensfun database models; it
  does **not** depend on the Rust `lensfun` crate.
- `crates/tetra3-cli`: catalog conversion, index construction, inspection,
  verification, and benchmarking.
- `crates/tetra3`: small runtime facade that re-exports the three phone-side
  libraries without pulling in the builder.
- `crates/tetra3-android`: JNI adapter built as `libtetra3_android.so`.
- `tools/catalog`: reproducible Gaia DR3 and Hipparcos acquisition/merge tools.

## Coordinate contract

Input image coordinates use a top-left origin, +x right, and +y down. Camera
rays use the right-handed Android-native convention: +x right, +y down, and
+z along the optical axis. Image orientation,
crop, and resize transforms are applied before radial correction. Low-level
lens operations reject unsupported models, non-finite coordinates, and failed
inverse distortion rather than silently changing models. The Android product
uses a profile only when its optional database, identity resolution, and focal
preparation all succeed; otherwise it starts from an ideal rectilinear model
built from the shot's STAR-reported, focus-breathed `FLact` value, native
detector pixel pitch, and `CameraGeometry`. That value is not the IBIS
`StartCorr opt_param`.

The current one-focal wire contract also uses `FLact` as the interpolation
coordinate for an exact Lensfun profile. The profile's stored
`real_focal_mm` remains a distortion-coordinate normalization; it is unrelated
to IBIS parallax. A future two-coordinate profile lookup would need a separate
nominal/infinity zoom focal in addition to `FLact`; STAR does not carry one.

Production rectilinear solves therefore treat every prepared optical model as
a starting prior. After normalized quad ratios retrieve an index candidate,
the solver performs one bounded six-edge fit of image-plane scale and a
normalized cubic radial residual to that candidate's absolute angular edges.
It applies the complete fitted model consistently to seed attitude,
independent verification, refinement, and reported FOV.
This is a candidate-local continuous fit, not a repeated focal-grid solve; the
normal hypothesis probability still gates the resulting field.

Candidate acquisition intentionally keeps the compact index's native raw
ratio window at `3/255`. The scale-plus-radial fit runs only after that lookup,
so the full numerical self-calibration box is not a promise that an arbitrarily
misprojected seed is reachable. The retained mounted-camera corpus defines the
current acquisition contract: with the generic rectilinear focal prior, every
usable 19 mm, 25 mm, 40 mm, 42 mm, and 45 mm capture reached its accepted
candidate without a focal grid. The worst accepted raw ratio displacement was
`0.011224`, versus the `3/255 = 0.011765` gate; the worst raw largest-edge error
was 3.599%, inside the production 8% edge window. Broadening the ratio lookup
to `4/255` was rejected because it increased candidate/hypothesis work enough
to materially degrade the mounted-camera tail. A lens profile may improve the
starting prior and search latency, but absence or failure to resolve the
profile is not a solve-initialization error.

This is therefore an explicit, measured acquisition envelope rather than a
claim of complete retrieval over every synthetic coefficient at the physical
self-calibration bounds. Extending that envelope requires a separately
budgeted multi-query acquisition design and its own false-positive and latency
validation; it must not silently widen this scalar lookup.

STAR, JNI, and the production solver accept 256 detections. All are eligible
for pattern acquisition: after the initial four-source stage, each successive
brightness rank contributes only its unique append-stable quad frontier rather
than rescanning an exhaustive 256-source expansion. The verifier processes each
seed query's complete candidate-sibling group against the full selected
population before issuing the next query. Within a fixed hypothesis, anytime
evidence retains the first crossing, so fainter detections may help but cannot
revoke that crossing. This is deterministic query-priority selection, not
global earliest-crossing selection: an appended tail may let an earlier query
preempt a later query's result or add work before it.

Catalog vectors and reported attitudes use ICRS. Index metadata records the
catalog reference epoch and the epoch at which proper motion was applied.

## Build

```sh
cd tetra3
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo build --release -p tetra3-cli
```

The tracked [`profiles/om1-all-mft.json`](profiles/om1-all-mft.json) recipe
compiles every exact native Micro Four Thirds lens identity supported by the
pinned Lensfun XML database into one compact `.t3lf`. The compiler is an
independent Rust XML/model implementation; Lensfun's library and the Rust
`lensfun` crate are not linked. See the
[`profiles` README](profiles/README.md) for the reproducible command, profile
counts, hashes, and the explicit 300mm f/4 database absence.

Generated catalogs, indexes, reports, and benchmarks belong under the
workspace's `build/` directory (`phone/tetra3/build/` from the repository
root) and are intentionally ignored. Before deployment, reopen an
index through the runtime parser with `tetra3 verify`, retain the conservative
3° catalog coverage report, and use `tetra3 bench` to exercise mmap-backed
lookup across every level. `tetra3 validate-solves` then runs deterministic rectangular
camera fields through the real mmap-backed runtime and solver at both FOV
boundaries of every level; use its success-rate threshold as the final host-side
acceptance gate. Its `--android-star-pixels` mode also reproduces the camera
worker's integer 1296x972 centroid wire encoding and solves those measurements
from the supplied-focal-length, ideal-rectilinear starting prior.

The narrow-field deployment target is derived from detector geometry rather
than a published lens angle of view. The tracked
[OM-1 + 300mm F4 calculation](targets/om1-300mm-f4.md) uses the 5184 x 3888
active raster, 3.3 micrometre nominal pixel pitch, and 300 mm focal length to
obtain a 3.266349614° horizontal FOV. The production index starts at 3.2° to
leave a small coverage margin.

The production artifact is `build/index/om1-300f4-to-45.t3ix`. It contains
eight horizontal-FOV levels spanning 3.2° through 45°, uses eight detector
rolls and up to 64 accepted patterns per sampled field, and keeps the full
verification catalog and pattern payload mmap-backed.

The 45° ceiling limits a seed pattern, not the camera's physical field. For a
wider lens, the solver enumerates brightness-ordered local four-star sets whose
angular span fits the widest reachable index level, while staged verification
and final attitude refinement still use all retained stars across the complete
image. The packaged Olympus 8 mm equisolid profile exercises this path over its
approximately 131.585° horizontal field.

## Licensing

The Rust and Python project code is licensed under the
[Apache License 2.0](LICENSE). Generated data has separate terms: see the
[Gaia and Hipparcos notice](tools/catalog/NOTICE.md) and the
[Lensfun database notice](profiles/LENSFUN-DATABASE-NOTICE.md) before shipping
catalog, index, or compiled lens-profile artifacts.
