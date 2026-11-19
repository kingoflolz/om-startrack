# tetra3 host tools

`tetra3` builds the static data consumed by the Android plate-solver core. It is
a host tool only; the Android runtime does not depend on Clap, Rayon, gzip, or
the catalog parser.

## Detector FOV

Calculate rectilinear FOV directly from active pixels, pixel pitch, and focal
length:

```sh
cargo run --release -p tetra3-cli -- \
  fov \
  --active-width-px 5184 \
  --active-height-px 3888 \
  --pixel-pitch-um 3.3 \
  --focal-length-mm 300 \
  --json
```

The tool computes the physical detector extents first, then evaluates
`2 * atan(extent / (2 * focal_length))` independently for the horizontal,
vertical, and diagonal axes. Inputs must be finite and positive. The tracked
[OM-1 + 300mm F4 recipe](../../targets/om1-300mm-f4.md) explains the production
index floor.

## Catalog input

`catalog convert` accepts plain or gzip-compressed CSV (detected by gzip magic).
It understands either native Gaia columns:

```text
source_id,ra,dec,phot_g_mean_mag,pmra,pmdec
```

or the repository's merged-catalog schema:

```text
catalog,source_id,ra_deg,dec_deg,ref_epoch_jyear,pmra_masyr,
pmdec_masyr,parallax_mas,magnitude,magnitude_band,color,flags
```

Extra columns are ignored. Missing, blank, `null`, or `NaN` optional proper
motions are zero. Hipparcos IDs are namespaced so they cannot collide with Gaia
IDs. `flags` containing `pattern_anchor=0` keeps a star in the deeper
verification catalog but prevents it from seeding a tetrahedron.

```sh
cargo run --release -p tetra3-cli -- \
  catalog convert \
  --input build/catalog/gaia-dr3-g12-hip2.csv.gz \
  --output build/catalog/gaia-dr3-g12-hip2-2026.t3cat \
  --epoch 2026 \
  --max-magnitude 12
```

The `.t3cat` intermediate is checksummed and deterministic. Each 17-byte record
contains the original/namespaced 64-bit source ID, a 2x24-bit octahedral unit
vector, signed millimagnitude, and flags. Positions are propagated before
deduplication and close-pair rejection.

## Multilevel index

```sh
cargo run --release -p tetra3-cli -- \
  build \
  --catalog build/catalog/gaia-dr3-g12-hip2-2026.t3cat \
  --output build/index/om1-300f4-to-45.t3ix \
  --min-fov-deg 3.2 \
  --max-fov-deg 45 \
  --levels 8 \
  --pattern-rolls-per-field 8 \
  --pattern-stars-per-field 12 \
  --patterns-per-field 64 \
  --target-verification-stars-per-field 128
```

The default levels are logarithmically spaced. FOV arguments are horizontal
rectilinear FOV; the builder derives the exact diagonal from `--aspect-ratio`
(4:3 by default) before bounding pattern edges. Fibonacci field sampling,
brightness ordering, adaptive isolation, quad enumeration, and global tuple
deduplication are deterministic. At each lattice center the builder performs one
diagonal-cap query, then filters it through the configured evenly spaced exact gnomonic
horizontal-by-vertical detector rectangles (rolls are unique modulo 180°). This
keeps locally bright anchors relevant to actual camera footprints while retaining
diagonal corner-to-corner pattern edges. Each level retains its complete tuple
membership so its edge window remains valid; the shared flash payload stores each
tuple once. Quad selection proceeds by faintest local rank,
rejects short-edge and near-collinear patterns. The production recipe keeps up
to 64 well-conditioned patterns per sampled field (`--patterns-per-field 0`
keeps all).
Each level derives a signed quarter-magnitude verification cutoff targeting 128
stars in its exact rectangular footprint; wide fields therefore do not verify
against the full G=12 density. A deterministic `OUTPUT.manifest.json` records
the catalog/index hashes and all output-affecting options. `--resume` skips only
when that manifest, the input hash, the output hash, and the options all match.

Every oriented lattice field (center times detector roll) must produce at least
one accepted quad. The build fails if catalog density or quality filtering leaves
a coverage hole, and reports centers, rolls, and uncovered fields per level in
its statistics. `--allow-uncovered-fields` is available only for synthetic
fixtures and exploratory non-production indexes.

## Inspection

These commands mmap `.t3ix` rather than deserializing it:

```sh
cargo run --release -p tetra3-cli -- inspect build/index/om1-300f4-to-45.t3ix --json
cargo run --release -p tetra3-cli -- verify build/index/om1-300f4-to-45.t3ix --json
cargo run --release -p tetra3-cli -- bench build/index/om1-300f4-to-45.t3ix --lookups 100000 --json
```

`verify` runs the same bounds, CRC, directory, ordering, and reference checks as
the Android-side zero-copy reader.

## Synthetic solve validation

The production acceptance sweep uses the actual runtime adapter and bounded
core solver without loading the index into an object graph:

```sh
cargo run --release -p tetra3-cli -- \
  validate-solves build/index/om1-300f4-to-45.t3ix \
  --pointings-per-boundary 2 \
  --rolls-per-pointing 2 \
  --additional-fov-deg 3.2663496137060335 \
  --max-observations 256 \
  --validation-screen-observations 32 \
  --timeout-ms 0 \
  --minimum-success-rate 0.95 \
  --json
```

Every level's inclusive minimum and maximum horizontal-FOV boundary is tested
with deterministic all-sky pointings and detector rolls. The generator applies
the level's verification-magnitude cutoff and exact rectangular footprint.
Repeatable `--additional-fov-deg` values add exact in-range horizontal FOVs and
automatically use the containing level and its verification cutoff.
`--centroid-noise-arcsec`, `--dropout-rate`, and `--false-detections` enable
reproducible robustness scenarios. Output includes per-boundary results,
attitude errors, solve timings, bounded-work counters, and capped failure
details; the command exits nonzero when the requested success rate is missed.
The generator retains the brightest `--max-observations` detections (256 by
default). Every retained source is eligible for tetrahedron generation and the
final one-to-one validation/refinement pass. Only the inexpensive preliminary
candidate screen is restricted by `--validation-screen-observations` (32 by
default); screen matches are discarded and recomputed against the complete
retained set before a solution can be accepted. JSON reports the final match
count distribution and how many solutions used validation evidence below the
screening cutoff.

Production defaults make the quad, candidate, hypothesis, index, catalog, and
verification counters effectively unbounded and stop search by successful
early exit or the cooperative 1000 ms deadline. `--timeout-ms 0` deliberately
disables the deadline for deterministic offline coverage, as in the command
above. `--max-quads`, `--max-candidates`, and `--max-hypotheses` remain
available as explicit lower safety overrides and are recorded in JSON.

Add `--android-star-pixels` for the phone-input acceptance path. It projects
catalog rays onto the 5184x3888, 3.3-micrometre OM-1 active raster; rounds the
native centroids to detector Q8; applies the camera worker's exact
`(q8 + 512) / 1024` conversion and 1296x972 edge clamp; and calls
`solve_pixels` with direct ideal rectilinear fallback geometry. Before projection,
it subtracts 0.375 encoded pixel from each integer wire coordinate: the wire
value is `round(native / 4)`, while the pixel-rim-preserving resize transform
otherwise reconstructs `4 * wire + 1.5` instead of `4 * wire`. The reported focal
length for every boundary is calculated from sensor width and horizontal FOV,
so the fallback exercises that supplied focal length with zero distortion.
Per-boundary output includes the focal
length and angular width of one integer wire pixel.

```sh
cargo run --release -p tetra3-cli -- \
  validate-solves build/index/om1-300f4-to-45.t3ix \
  --android-star-pixels \
  --additional-fov-deg 3.2663496137060335 \
  --pointings-per-boundary 16 \
  --rolls-per-pointing 2 \
  --max-observations 256 \
  --validation-screen-observations 32 \
  --timeout-ms 1000 \
  --max-attitude-error-arcsec 180 \
  --minimum-success-rate 1
```

The 180-arcsecond full-quaternion audit gate accounts for roll uncertainty from
integer wire centroids, especially in narrow fields. It does not widen the
solver association gates: production validation retains the 75-arcsecond match
and 90-arcsecond seed defaults.

Append `--centroid-noise-arcsec 4 --dropout-rate 0.15
--false-detections 4` to either validation command for the deterministic
noise/dropout/false-source stress sweep used below.

To exercise a camera whose full field is wider than the largest index level,
render through an exact profile from the packaged lens database. This mode
binds the level with the greatest declared maximum FOV while retaining the
lens's real full field radius. That level may itself be empty only when its
configured direct neighbors contain patterns, matching runtime fallback
semantics. Quad search must therefore find a local star set within the reachable
index edge coverage, while final validation and attitude refinement still
receive every retained ray from the full image.

```sh
cargo run --release -p tetra3-cli -- \
  validate-solves build/index/om1-300f4-to-45.t3ix \
  --lens-database build/profiles/om1-all-mft.t3lf \
  --lens-profile-id \
  'lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro' \
  --focal-length-mm 8 \
  --pointings-per-boundary 8 \
  --rolls-per-pointing 4 \
  --timeout-ms 1000 \
  --max-attitude-error-arcsec 600 \
  --minimum-success-rate 1 \
  --json
```

The production Olympus profile is equisolid and spans approximately 131.585°
horizontally with an 86.108° corner radius at 8 mm, compared with the index's
45° maximum horizontal FOV. The local seed diameter is slightly larger than
that metadata (about 54.31° for the production file) because it conservatively
includes stored-edge quantization and query tolerance; both values are reported
separately. Synthetic catalog rays are forward-projected
through the compiled distortion model, rounded to the integer STAR transport,
and corrected through the ordinary pixel solver. For each over-index field the
validator uses the same asymmetric STAR quantization cells as Android, adds the
maximum angular cell radius to the 75/90-arcsecond match/seed allowances, and
widens the robust-fit and same-solution scales consistently. The 600-arcsecond
command-line value is only a post-solve attitude audit threshold; it does not
widen matching beyond that per-field quantization calculation. The index
supplies the selected magnitude-limited catalog population for the wide-mode
chance calculation. Covered ray, rectilinear STAR, and lens-profile modes keep
the established fixed gates and conservative
`--fallback-density-per-steradian`, even when population metadata is present.
JSON records trials where the
globally brightest four stars already span more than the expanded seed edge,
plus
correct solves that continue to a later local four-star set. It also includes
`solutions_using_validation_outside_pattern_edge`; wide-profile validation only
passes after at least one accepted solution associates a held-out star more
than the expanded seed edge from every seed star. Together these checks prove
that only seed generation is localized and the final pass is not silently
restricted to the pattern-search neighborhood.

Reference production-profile results (2026-08-19) were 64/64 clean fields and
64/64 fields with 4-arcsecond centroid noise, 15% source dropout, and four
bright false detections. Neither sweep produced a deadline, ambiguity, or
wrong attitude. The clean median/p95/maximum host solve time was
1.45/3.12/9.86 ms; the stressed result was 1.28/4.06/10.06 ms. Both used the
index density metadata directly, without a fallback trial. Every accepted
solution used final validation outside the expanded pattern edge.

## Random-field false-positive validation

`validate-fpr` sends catalog-independent random detections through the real
mmap runtime, production level selection, Android STAR coordinate phase, and
production solver configuration. By default it tests 256 IID sky directions
at every unique level boundary and every level's geometric-center FOV. The
directions are uniform in solid angle conditioned on the rectangular camera
footprint, then rendered and quantized to unique integer 1296x972 STAR pixels.
Use `--distribution pixels` for the separate uniform-detector-artifact null or
`--distribution both` to run both campaigns.

```sh
cargo run --release -p tetra3-cli -- \
  validate-fpr build/index/om1-300f4-to-45.t3ix \
  --distribution both \
  --observations 8 --observations 16 --observations 32 \
  --observations 64 --observations 128 --observations 256 \
  --trials-per-fov 100 \
  --lens-database build/profiles/om1-all-mft.t3lf \
  --lens-profile-id \
  'lensfun-v2.om1.active-1296x972|lens=7:Olympus48:Olympus M.Zuiko Digital ED 8mm f/1.8 Fisheye Pro' \
  --focal-length-mm 8 \
  --json
```

Repeatable `--fov-deg` values replace the default boundary/center matrix, which
is useful for testing the eight nominal build FOVs directly. Repeatable
`--observations` values create independent 8-to-256-source strata; omitting the
option selects 256. Seeds are counter-keyed by camera case, distribution,
observation count, and absolute trial ordinal. `--start-trial` therefore makes
non-overlapping reproducible shards without replaying preceding trials.

Covered FOVs use the Android policy: the selected level's verification cutoff,
fixed 75/90-arcsecond match/seed gates, and the conservative 250000/sr density
fallback. An over-index exact lens binds the widest level, retains index density
metadata, and applies the same field-specific maximum STAR-cell quantization
expansion as Android. Output exposes the cutoff, density source, effective
match tolerance, per-observation random-match probability, expected accidental
matches under that conservative null model, quads/candidates/hypotheses, all no-match reasons, deadlines, timings,
and solved versus product-rejected ambiguous results.

Each case reports exact pointwise and Bonferroni simultaneous one-sided 95%
Clopper-Pearson upper bounds. A successful command only means that it observed
no solved false accept, solver error, or supposedly unreachable numeric-budget
termination. It does **not** establish a 1e-6 FPR: with zero events, roughly
3.0 million trials per case are needed for a pointwise 95% upper bound of
1e-6, and more for a simultaneous multi-case claim.

The production deadline is 1000 ms. A 256-source random field commonly uses
that full second, so the small default of ten trials per case is only a wiring
smoke test. One worker is the default because parallel CPU and page-cache
contention reduces the hypotheses reached before a wall-clock deadline.
`--threads 0` opts into host parallelism; its deadline-limited result must be
interpreted as that contended operating condition. Do not replace or upgrade
the mapped index while a campaign is running. `--positive-control-trials-per-fov`
runs a separately labelled fixed-alpha=1 harness control and never changes or
contributes to the production FPR results.

## Lensfun XML

The tool does not link or depend on Lensfun. It accepts only Lensfun XML database
version 2, parses selected entries with a small pure-Rust compiler, and rejects
ACM or unknown distortion models. Supply one or more database XML files (or a
directory containing the split database) and a selection JSON document:

```sh
cargo run --release -p tetra3-cli -- \
  lensfun compile \
  --xml build/lensfun-source/data/db \
  --profiles profiles/om1-all-mft.json \
  --output build/profiles/om1-all-mft.t3lf \
  --report build/profiles/om1-all-mft.manifest.json
```

`profiles.json` has this shape (multiple entries are allowed):

```json
{
  "profiles": [{
    "profile_id": "om1.20mm.raw-quarter",
    "camera_maker": "OM Digital Solutions",
    "camera_model": "OM-1",
    "camera_variant": null,
    "lens_maker": "OM Digital Solutions",
    "lens_model": "OM 20mm F1.4",
    "calibration_width": 5184,
    "calibration_height": 3888,
    "crop_x": 0,
    "crop_y": 0,
    "crop_width": 5184,
    "crop_height": 3888,
    "encoded_width": 1296,
    "encoded_height": 972,
    "orientation": 1,
    "calibration_crop_factor": 2.0,
    "calibration_aspect_ratio": 1.3333334
  }]
}
```

Selection is exact and case-sensitive. The compiled `.t3lf` preserves raster
crop/orientation, calibration crop/aspect/center, projection, nominal and real
focal lengths, and Poly3/Poly5/PTLens coefficients. `inspect` and `verify` also
accept `.t3lf` files.

To compile a camera geometry for all compatible lenses, use the separate
`compatible_lenses` recipe list:

```json
{
  "compatible_lenses": [{
    "all_compatible_lenses": true,
    "profile_id_prefix": "lensfun-v2.om1.active-1296x972",
    "camera_maker": "OM Digital Solutions",
    "camera_model": "OM-1",
    "lens_mount": "Micro 4/3 System",
    "calibration_width": 5184,
    "calibration_height": 3888,
    "crop_x": 0,
    "crop_y": 0,
    "crop_width": 5184,
    "crop_height": 3888,
    "encoded_width": 1296,
    "encoded_height": 972,
    "orientation": 1
  }],
  "audit_lenses": [{
    "lens_maker": "Olympus",
    "lens_model": "Olympus M.Zuiko Digital ED 300mm f/4.0 IS PRO",
    "do_not_substitute": true
  }]
}
```

Compatibility follows Lensfun mount edges transitively. The optional exact
`lens_mount` filter restricts the result after that traversal; the OM-1 recipe
uses it to select native MFT records rather than adapted M42/T2 lenses. Bulk
output IDs contain the canonical maker/model byte strings, so callers construct
the same ID and perform an exact runtime lookup. Ambiguous or unmatched
calibrations and lenses without distortion data are listed in the report.
ACM or a future unknown model aborts the compilation rather than being skipped.
The report also contains SHA-256 hashes for the output, exact recipe bytes,
aggregate source set, and each source document. `audit_lenses` performs exact,
case-sensitive presence checks and can document identities which must never be
replaced by a superficially similar profile.
