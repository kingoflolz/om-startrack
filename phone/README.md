# Android app

The app connects to the camera's STAR and Astro Rate BLE services, configures
and captures star detections, solves the field using the tetra3 Rust solver,
and controls gyro-assisted IBIS tracking. One app serves both cameras.
The ARM64 debug APK is attached to the repository's tagged GitHub releases;
APKs and generated data assets are excluded from Git. Android 8.0 (API 26) or
later is required.

## Regenerate and build

Install JDK 17, Android SDK platform 36, Android NDK 27.2.12479018, Rust 1.90.0,
and cargo-ndk 4.1.2. Set `ANDROID_SDK_ROOT` to the SDK directory.

```sh
rustup target add --toolchain 1.90.0 aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
python3 ../tools/build_app.py
```

The script fetches Gaia/Hipparcos catalog data and Lensfun XML, regenerates the
star index and lens profiles, runs the app tests, and builds the Java and Rust
code. It writes `../build/releases/om-startrack.apk`. Downloads, indexes,
profiles, and reports stay under `tetra3/build/`; nothing is extracted from an
APK. The initial run can take tens of minutes and several GiB of working space.

`--offline` uses a complete local source cache. `--catalog-dir` and
`--lensfun-dir` select source caches, and `--output` chooses a new APK filename.
Use `--ndk /path/to/android-ndk` when the NDK is outside the SDK directory.
After generating the assets, ordinary incremental builds can use
`./gradlew :app:assembleDebug`. JVM tests alone use
`./gradlew :app:testDebugUnitTest` and need neither the NDK nor star catalog.

The optional Gradle property `-Ptetra3Abis=arm64-v8a,x86_64` builds the native
solver for an emulator too; install the `x86_64-linux-android` Rust target first.
The debug signing key is local build state and is not tracked.

## Source layout

- `app/src/main/java/dev/omshutter/`: UI, BLE state machines, tracking, and
  attitude estimation. `protocol/StarProtocol.java` defines the current STAR
  service, including configuration writes and attitude-bearing STATE reads.
- `app/src/test/`: JVM tests for protocols, tracking, and UI decisions.
- `app/src/main/assets/ERFA-NOTICE.txt`: attitude-math attribution.
- `tetra3/crates/`: Rust solver, JNI bridge, index and lens-profile formats,
  and their host-side builders.
- `tetra3/tools/catalog/`: Gaia/Hipparcos acquisition and catalog preparation.
- `tetra3/profiles/`: Lensfun selection recipes and database attribution.
- `../shared/contracts/`: current wire schemas and cross-language vectors.
