# OM Startrack

> **Warning: use at your own risk.** This firmware could permanently brick your
> camera or cause physical hardware damage. Everything in this repository is
> provided **as is, without warranty of any kind**. To the fullest extent
> permitted by law, the authors and contributors disclaim all liability for any
> claims, losses, or damages arising from the use of anything in this repository.

Note, this repo is just a snapshot of a side project. Don't expect any support or new features.

All the code is AI generated, but it does work and I have tested the firmware files generated
by this repo on my real cameras ¯\\\_(ツ)\_/¯

See blog post describing this project [here](https://benwang.dev/2026/11/18/OM1-In-Body-Star-Tracker.html).

Star detection, phone-side plate solving, gyro attitude telemetry, and IBIS
sidereal tracking for **OM-1 firmware 1.9** and **OM-1 Mark II firmware 1.3**.
These are the only supported inputs. `patch_firmware.py --list` prints their
exact sizes and SHA-256 values.

## Create a firmware update

Use Linux, Python 3.10 or newer, and `squashfs-tools` with LZO compression:

```sh
sudo apt install python3 squashfs-tools
python3 patch_firmware.py /path/to/official-firmware.BIN --check
python3 patch_firmware.py /path/to/official-firmware.BIN -o /path/to/startrack.BIN
```

The patcher identifies the model by the complete input SHA-256, decodes the
container, verifies a byte-exact unmodified filesystem rebuild, installs the
custom components, applies the compiled native patches, and regenerates all
container checksums. It verifies the final decode/encode round trip before
publishing the output. It creates a file; it does not flash a camera.


## Repository structure

```text
patch_firmware.py              End-user firmware patcher
firmware/
  targets.json                Supported input versions, hashes, and offsets
  container.py                Decode, encode, and verify firmware containers
  rootfs.py                   Rebuild SquashFS while preserving metadata
  startup.py                  Extend the input firmware's startup script
  input_data.py, .json         Fill reserved data from the verified input
binaries/
  om1/, om1ii/
    native/                   Custom MN10300 ELF files and patch segments
    linux/                    Three custom ARM Linux binaries per model
camera/
  native/                     Shared MN10300 assembly and C implementation
  linux/
    bluetooth/                BLE shim, STAR service, and reverse-engineered ABI
    star-worker/              Capture, star detection, and camera control
    startup-functions.sh      Custom service startup functions
  models/om1/, models/om1ii/   Linker layouts and model-specific adaptations
phone/
  app/                        Android Java app, resources, and tests
  tetra3/                     Rust plate solver, JNI, and asset builders
shared/contracts/             Current wire schemas and cross-language vectors
tools/
  build_camera.py             Rebuild custom components for either model
  build_linux.sh              ARM linking and ABI checks used by that builder
  build_app.py                Regenerate data assets and build the Android APK
tests/                        Patcher and artifact regression tests
build/                        Ignored temporary inputs, outputs, and release APKs
```

Both models use the outgoing CPU0 reply guard, keeping the shared CPU0/CPU1
copier intact. The local PTP exception requires loopback command **and** event
connections; ordinary network PTP still uses camera pairing. Mark II also has
the model-specific station startup hooks it requires.

## Rebuild camera components

Rebuilding is optional. It requires `make`, `squashfs-tools`, and these GNU
cross toolchains under one directory:

```text
TOOLCHAINS/
  mn10300-elf-binutils-2.46.1/bin/mn10300-elf-{as,ld,...}
  mn10300-elf-gcc-16.1.0/bin/mn10300-elf-gcc
  gcc-arm-10.3-2021.07-x86_64-arm-none-linux-gnueabihf/bin/arm-none-linux-gnueabihf-*
```

```sh
python3 tools/build_camera.py /path/to/official-firmware.BIN \
  --toolchains /path/to/TOOLCHAINS \
  --work-dir build/camera --output-dir build/components
```

Both output directories must be new. The model is detected from the input.
The builder extracts linking libraries into the ignored build directory,
compiles the shared sources, audits the ARM binaries, and exports custom
components only. Replace the corresponding `binaries/MODEL/` with
`build/components/` after reviewing and testing the changes.

## Rebuild the app

```sh
python3 tools/build_app.py
```

This downloads Gaia/Hipparcos catalog inputs and the Lensfun database, builds
the star index and lens profiles, runs the app tests, and compiles the Java and
Rust app into `build/releases/om-startrack.apk`. No prebuilt APK is needed.
Downloads and generated data are cached under `phone/tetra3/build/`.
Use `--offline` with a complete source cache. See `phone/README.md` for the
Android SDK, NDK, JDK, Rust, and cargo-ndk requirements. Initial asset generation
takes substantially longer than firmware patching.

## Checks

```sh
python3 -m unittest discover -s tests -v
cd phone
./gradlew :app:testDebugUnitTest
cd tetra3
cargo test --workspace --locked
```

The firmware integration check requires the user's official input: run the
patch command above for each model. Generated firmware and build directories
are ignored by Git. Third-party app and data notices are retained under
`phone/`; no license to redistribute vendor firmware is implied.

## License

Unless otherwise noted, original code and documentation in this repository
are licensed under the [Apache License 2.0](LICENSE). Third-party code,
dependencies, and data retain their respective licenses and notices.
