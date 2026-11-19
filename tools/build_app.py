#!/usr/bin/env python3
"""Regenerate astronomical assets and build the Android APK from source.

Requires Python, Git, Rust 1.90 + cargo-ndk 4.1.2, JDK 17, Android SDK 36
and NDK 27.2.12479018. Downloads are resumable; all data stays under build/.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PHONE = ROOT / 'phone'
SOLVER = PHONE / 'tetra3'


def run(*command, cwd=SOLVER, env=None):
    print('+', ' '.join(str(item) for item in command), flush=True)
    subprocess.run([str(item) for item in command], cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog-dir', type=Path, default=SOLVER / 'build/catalog',
                        help='cache for Gaia/Hipparcos downloads and merged catalog')
    parser.add_argument('--lensfun-dir', type=Path, default=SOLVER / 'build/lensfun-source',
                        help='Lensfun database checkout; cloned when absent')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/releases/om-startrack.apk')
    parser.add_argument('--offline', action='store_true', help='require cached source data and build dependencies')
    parser.add_argument('--ndk', type=Path, help='NDK directory if outside ANDROID_SDK_ROOT')
    parser.add_argument('--gradle', type=Path, default=PHONE / 'gradlew', help='Gradle executable')
    args = parser.parse_args()
    catalog = args.catalog_dir.resolve()
    lensfun = args.lensfun_dir.resolve()
    if args.output.exists():
        parser.error(f'output exists; choose a new path: {args.output}')
    catalog.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    if args.offline:
        env['CARGO_NET_OFFLINE'] = 'true'
        for path in (catalog / 'gaia/gaia-manifest.json', catalog / 'hipparcos/hipparcos-manifest.json',
                     lensfun / 'data/db'):
            if not path.exists():
                parser.error(f'offline source cache is missing: {path}')
    else:
        run(sys.executable, 'tools/catalog/fetch_gaia.py', '--output-dir', catalog / 'gaia',
            '--magnitude-limit', '12', '--chunk-order', '1')
        run(sys.executable, 'tools/catalog/fetch_hipparcos.py', '--output-dir', catalog / 'hipparcos')
        if not lensfun.exists():
            lensfun.parent.mkdir(parents=True, exist_ok=True)
            run('git', 'clone', '--depth', '1', 'https://github.com/lensfun/lensfun.git', lensfun)
    run('cargo', 'build', '--locked', '--release', '-p', 'tetra3-cli', env=env)
    # Cargo may be configured with a target directory outside the workspace.
    metadata = subprocess.check_output(['cargo', 'metadata', '--format-version', '1', '--no-deps'],
                                       cwd=SOLVER, env=env, text=True)
    cli = Path(json.loads(metadata)['target_directory']) / 'release/tetra3'
    merged = catalog / 'gaia-dr3-g12-hip2.csv.gz'
    run(sys.executable, 'tools/catalog/merge_catalogs.py', '--gaia-dir', catalog / 'gaia',
        '--hipparcos-dir', catalog / 'hipparcos', '--target-epoch', '2026',
        '--hipparcos-hp-limit', '8', '--output', merged)
    converted = catalog / 'gaia-dr3-g12-hip2-2026.t3cat'
    run(cli, 'catalog', 'convert', '--input', merged, '--output', converted,
        '--epoch', '2026', '--max-magnitude', '12')
    index = SOLVER / 'build/index/om1-300f4-to-45.t3ix'
    profiles = SOLVER / 'build/profiles/om1-all-mft.t3lf'
    index.parent.mkdir(parents=True, exist_ok=True)
    profiles.parent.mkdir(parents=True, exist_ok=True)
    run(cli, 'build', '--catalog', converted, '--output', index,
        '--min-fov-deg', '3.2', '--max-fov-deg', '45', '--levels', '8',
        '--aspect-ratio', '1.3333333333333333', '--pattern-rolls-per-field', '8',
        '--pattern-stars-per-field', '12', '--patterns-per-field', '64',
        '--target-verification-stars-per-field', '128', '--resume')
    run(cli, 'verify', index)
    run(cli, 'lensfun', 'compile', '--xml', lensfun / 'data/db',
        '--profiles', SOLVER / 'profiles/om1-all-mft.json', '--output', profiles,
        '--report', profiles.with_suffix('.manifest.json'), '--json')
    command = [args.gradle.resolve(), '--no-daemon']
    if args.offline:
        command.append('--offline')
    if args.ndk:
        command.append(f'-Ptetra3NdkDir={args.ndk.resolve()}')
    command.extend([':app:testDebugUnitTest', ':app:assembleDebug'])
    run(*command, cwd=PHONE, env=env)
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with (PHONE / 'app/build/outputs/apk/debug/app-debug.apk').open('rb') as source, output.open('xb') as dest:
        shutil.copyfileobj(source, dest)
    print(f'Built {output}', flush=True)


if __name__ == '__main__':
    main()
