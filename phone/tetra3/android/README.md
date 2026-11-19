# Android native library and assets

Gradle builds `libtetra3_android.so` from `crates/tetra3-android` and its shared
Rust dependencies. The library and the two data assets are stored uncompressed
and aligned in the APK so Android can map them directly.

- `assets/tetra3/om1-300f4-to-45.t3ix`: star-pattern index for 3.2–45 degree fields.
- `assets/tetra3/om1-all-mft.t3lf`: native Micro Four Thirds lens profiles.

Run `python3 tools/build_app.py` from the repository root to regenerate both
assets and build the app. See `phone/README.md` for dependencies and options.
The APK is a tagged release asset; it is not committed to the repository.
