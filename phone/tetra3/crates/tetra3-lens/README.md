# tetra3-lens

`tetra3-lens` is the phone-side pixel-to-ray adapter for the tetra3 solver. It
is pure Rust, forbids unsafe code, and performs no filesystem or native-library
calls. It uses the standard Rust floating-point operations and is suitable for
Android Rust targets without Lensfun's C++/GLib runtime.

The implementation independently follows the documented classic Lensfun
models:

- Poly3
- Poly5
- PTLens
- rectilinear and the Lensfun fisheye/map projection families

ACM is not implemented. Its format tag returns `UnsupportedAcm`; unknown tags,
failed inverse solves, and invalid projection domains are errors, never identity
fallbacks.

When no exact profile exists, construct an ideal fallback directly with
`PreparedLens::rectilinear_no_distortion(camera, focal_mm, pixel_pitch_um)`.
Callers decide whether absence permits that fallback; profile lookup and lens
construction remain separate operations.

The Android STAR caller supplies the captured, focus-breathed `FLact` value
for `focal_mm`; the IBIS `StartCorr opt_param` is a different quantity and is
never a projection input.

The fallback scale is `(pixel_pitch_um / 1000) / focal_mm`; it does not infer
pitch from crop factor. Pixel pitch describes the native calibration raster,
not a resized centroid image. The optical axis is assumed to pass through the
full calibration raster's geometric center, and the provided focal length is
used unchanged as the real focal length. Compatibility matching must happen
before exact lookup, and ambiguous matches remain errors.

## Point transform

For each camera mode, a profile records the full calibration raster, an active
crop in that raster, the delivered raster dimensions, and the EXIF orientation
that transformed the native crop into the delivered image. Runtime mapping:

1. checks the delivered sub-pixel coordinate against its outer pixel rims
   (`-0.5` through `size - 0.5`);
2. undoes orientation on pixel-edge coordinates;
3. maps the resized crop back into the full calibration raster;
4. subtracts the calibrated optical center and applies Lensfun's natural-coordinate scale;
5. inverts the focal-interpolated radial model with safeguarded `f64` Newton steps;
6. converts the corrected projection coordinate to a unit camera ray.

Camera rays use `+X` right, `+Y` down, `+Z` forward. Reflected EXIF modes are
undone before ray construction, preserving a right-handed camera frame.

Point conversion does not allocate. Focal interpolation and coefficient
rescaling are performed once by `LensProfile::prepare`.

## `.t3lf` v1

The binary format is deterministic and little-endian. It contains a 64-byte
checked header followed by:

- fixed 72-byte profile records, sorted by exact UTF-8 ID;
- fixed 20-byte focal-knot records;
- one tightly packed profile-ID byte pool.

The header and payload have CRC32C checksums. The parser also checks canonical
offsets, exact file length, all arithmetic, crop containment, resize aspect,
finite values, model-specific unused zero fields, strictly increasing focal
knots, strict ID ordering, reserved bytes, and enum tags. IDs are matched
byte-for-byte with binary search; there is no case folding, fuzzy match, or
Unicode normalization.

Each knot stores nominal focal length, resolved real focal length, and three
raw model terms. A host database compiler must resolve missing real-focal rules
and choose the Lensfun calibration set before writing `.t3lf`; the Android
runtime never consumes XML or an absent-value sentinel.

Check the crate with:

```sh
cargo check -p tetra3-lens
```
