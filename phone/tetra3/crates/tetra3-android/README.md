# tetra3-android

JNI ownership layer for the Android plate solver. The produced library is
`libtetra3_android.so`; Java loads it as `tetra3_android` through
`dev.omshutter.plate.NativeTetra3Bridge`.

`create` accepts the file descriptor, start offset, and exact length returned
by `AssetManager.openFd()` for an uncompressed `.t3ix` APK asset. Native code
duplicates the descriptor, checks the region against the backing file, and
maps only that region. The mapping owns the index; lookups remain demand-paged
and no index section is materialized. A compact `.t3lf` database may be
supplied as a byte array and is parsed once per engine. Null, missing, or empty
profile data is valid and leaves the engine in profile-free mode.

Handles are monotonically assigned registry IDs, never native pointers. Each
solve clones an `Arc` before releasing the registry lock, so an idempotent
`close` can race with a solve without blocking on it or invalidating its mmap.
Stale handles fail with `NativeTetra3Exception`.

For each shot the application passes the camera's lens name. Name resolution
uses deterministic normalized equality and never ranks fuzzy candidates or
treats generated profile IDs as camera names. A missing, ambiguous, or
unpreparable profile prepares an ideal rectilinear starting camera from the
STAR-reported, focus-breathed `FLact` value in millimetres and native pixel
pitch; it never substitutes the IBIS `StartCorr opt_param`. Both paths then
fit bounded residual geometry from the indexed seed. The structured result
records whether that fit was profile-assisted or profile-free and includes the
selected profile ID only when a prior was actually used.

For an exact match, the current one-focal STAR contract also supplies `FLact`
as the profile interpolation coordinate. Stored Lensfun `real_focal_mm` is a
distortion-coordinate normalization, not the IBIS parallax term. STAR does
not provide a second nominal/infinity zoom focal for two-coordinate profile
selection.

For rectilinear lenses, production lookup treats the initial projection as an
estimate. Each retrieved catalog quad supplies six absolute angular edges to
one bounded scale-plus-radial fit before attitude and independent verification.
JNI reports horizontal FOV from that complete winning model, including the
radial term. No external focal scan or repeated solve is performed.
Non-rectilinear projections retain their prepared profile geometry unchanged.

OM-1 STAR coordinates are the fixed 1296x972 wire plane. After validating that
raw domain, JNI subtracts 0.375 from each coordinate so CameraGeometry's 4:1
pixel-rim mapping recovers the firmware sampling phase (`native = 4 * wire`).
Horizontal FOV is separately measured between the encoded image's outer rims,
so a profile-free 300 mm starting model uses the complete 5184-pixel active
width.

Result roll is the position angle of image-up, measured in degrees east of
celestial north and normalized to `[0, 360)`. The result exposes the input
observation indices used by the four-star pattern separately from the
independent validation indices.

The Android worker uses the shared production solve policy and cooperative
deadline from `om-tetra3`, so native, validation, and FPR paths exercise the
same search configuration.
