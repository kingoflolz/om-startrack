#!/usr/bin/env python3
"""Patch official OM-1 1.9 / OM-1 Mark II 1.3 firmware using this repository.

Requires Python 3.10+ and squashfs-tools (unsquashfs/mksquashfs with LZO).
Reads custom binaries from binaries/; vendor files come only from the input.
This program creates an update file. It never connects to or flashes a camera.
"""
from __future__ import annotations

import argparse
import errno
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from firmware import container, rootfs
from firmware.startup import launcher
from firmware.input_data import populate

ROOT = Path(__file__).resolve().parent
TARGETS = json.loads((ROOT / "firmware/targets.json").read_text())
CHUNK = 1024 * 1024


class PatchError(ValueError):
    pass


def digest(stream):
    result = hashlib.sha256()
    while block := stream.read(CHUNK):
        result.update(block)
    return result.hexdigest()


def identify(stream):
    stream.seek(0, os.SEEK_END)
    size = stream.tell()
    stream.seek(0)
    if size not in {r["size"] for r in TARGETS.values()}:
        raise PatchError(f"unsupported firmware size: {size}; requires OM-1 1.9 or OM-1 Mark II 1.3")
    actual = digest(stream)
    for key, target in TARGETS.items():
        if size == target["size"] and actual == target["input_sha256"]:
            stream.seek(0)
            return key, target
    raise PatchError("unsupported firmware SHA-256: " + actual
                     + "; use the unmodified official OM-1 1.9 or OM-1 Mark II 1.3 binary")


def native_regions(directory, target):
    layout = json.loads((directory / "layout.json").read_text())
    regions, end = [], 0
    for record in layout:
        offset, size = record["offset"], record["size"]
        name = record["file"]
        if not isinstance(name, str) or Path(name).name != name:
            raise PatchError("invalid native binary path")
        if not isinstance(offset, int) or not isinstance(size, int) or size <= 0 or offset < end:
            raise PatchError("invalid or overlapping native regions")
        if not (0x20 <= offset < offset + size <= 0x01FFFFE0
                or target["cpu0_offset"] <= offset < offset + size <= target["cpu0_offset"] + target["cpu0_size"]):
            raise PatchError("native region exceeds MAIN/CPU0 code bounds")
        data = (directory / name).read_bytes()
        if len(data) != size:
            raise PatchError(f"native binary size mismatch: {name}")
        regions.append((offset, data))
        end = offset + size
    if not regions:
        raise PatchError("empty native layout")
    return regions


def publish(temporary, destination):
    """Create the output without replacing an existing file or symlink."""
    try:
        os.link(temporary, destination)
        return
    except OSError as error:
        if error.errno not in {errno.EPERM, errno.EACCES, errno.ENOSYS, errno.ENOTSUP, errno.EXDEV}:
            raise
    with destination.open("xb") as output:
        try:
            with temporary.open("rb") as source:
                shutil.copyfileobj(source, output, CHUNK)
            output.flush()
            os.fsync(output.fileno())
        except BaseException:
            output.close()
            destination.unlink()
            raise


def patch(source, destination, model, target):
    for executable in ("unsquashfs", "mksquashfs"):
        if shutil.which(executable) is None:
            raise PatchError(f"missing {executable}; install squashfs-tools with LZO support")
    if os.path.lexists(destination):
        raise PatchError(f"output already exists (refusing to overwrite): {destination}")
    binary_dir = ROOT / "binaries" / model
    regions = native_regions(binary_dir / "native", target)
    installs = [(binary_dir / "linux/libbsa_gatt_shim.so", "usr/lib/libbsa_gatt_shim.so"),
                (binary_dir / "linux/star_gatt_server", "usr/bin/star_gatt_server"),
                (binary_dir / "linux/om1-stars", "usr/bin/om1-stars")]
    for path, _ in installs:
        if not path.is_file():
            raise PatchError(f"missing custom binary: {path}")
    with tempfile.TemporaryDirectory(prefix=".om-startrack-", dir=destination.parent) as temporary:
        work = Path(temporary)
        stock = work / "stock.BIN"
        source.seek(0)
        with stock.open("wb") as output:
            shutil.copyfileobj(source, output, CHUNK)
        with stock.open("rb") as snapshot:
            if digest(snapshot) != target["input_sha256"]:
                raise PatchError("input changed while being read")
        decoded = work / "decoded.BIN"
        print("Decoding firmware and rebuilding its Linux filesystem...", flush=True)
        container.transform_file(stock, decoded, container.DECODE_PERM)
        # Keep firmware lookup tables and runtime check windows out of the
        # distributed binaries; fill their reserved sections from this input.
        with decoded.open("rb") as image:
            for index, (path, name) in enumerate(installs):
                if path.name in ("om1-stars", "star_gatt_server"):
                    populated = work / path.name
                    populated.write_bytes(populate(path.read_bytes(), image, model, path.name))
                    installs[index] = (populated, name)
        stock_fs = work / "stock.squashfs"
        with decoded.open("rb") as image:
            image.seek(target["rootfs_offset"])
            stock_fs.write_bytes(image.read(target["rootfs_size"]))
        stock_wifi = subprocess.check_output(["unsquashfs", "-cat", str(stock_fs), "etc/init.d/wifi"])
        wifi = work / "wifi"
        wifi.write_text(launcher(stock_wifi.decode(), (ROOT / "camera/linux/startup-functions.sh").read_text(), model))
        overlays = [(wifi, "etc/init.d/wifi"), (ROOT / "camera/linux/gdbserver.sh", "usr/bin/gdbserver")]
        rebuilt = work / "rootfs.squashfs"
        rootfs.rebuild_rootfs(decoded, rebuilt, slot_offset=target["rootfs_offset"], slot_size=target["rootfs_size"],
            overlays=tuple(rootfs.Overlay(path, name) for path, name in overlays),
            installs=tuple(rootfs.Install(path, name, timestamp=1712578276, mode="755", uid=0, gid=0)
                           for path, name in installs))
        for path, name in installs + overlays:
            actual = subprocess.check_output(["unsquashfs", "-cat", str(rebuilt), name])
            if actual != path.read_bytes():
                raise PatchError(f"rebuilt filesystem differs from custom binary: {name}")
        with decoded.open("r+b") as image:
            for offset, data in regions:
                image.seek(offset)
                image.write(data)
            image.seek(target["rootfs_offset"])
            image.write(rebuilt.read_bytes())
        output = work / "patched.BIN"
        container.encode_file(decoded, output)
        container.verify_roundtrip(output)
        if output.stat().st_size != target["size"]:
            raise PatchError("firmware size changed")
        with output.open("rb") as result:
            result_hash = digest(result)
        publish(output, destination)
    return result_hash


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path, nargs="?")
    parser.add_argument("-o", "--output", type=Path, help="new output file; defaults to model/version name in the current directory")
    parser.add_argument("--check", action="store_true", help="identify and verify the input without writing")
    parser.add_argument("--list", action="store_true", help="list supported input firmware and SHA-256 values")
    args = parser.parse_args(argv)
    if args.list:
        if args.firmware or args.output or args.check:
            parser.error("--list cannot be combined with other arguments")
        for t in TARGETS.values():
            print(f'{t["model"]} {t["version"]}: {t["size"]} bytes\n  SHA-256 {t["input_sha256"]}')
        return 0
    if args.firmware is None:
        parser.error("provide the official firmware .BIN file, or use --list")
    if args.check and args.output:
        parser.error("--check cannot be combined with --output")
    try:
        if not args.firmware.is_file():
            raise PatchError(f"input is not a regular file: {args.firmware}")
        with args.firmware.open("rb") as source:
            model, target = identify(source)
            print(f'Verified {target["model"]} firmware {target["version"]}.', flush=True)
            if args.check:
                return 0
            destination = args.output or Path(target["output_filename"])
            if args.firmware.resolve() == destination.resolve():
                raise PatchError("input and output must be different files")
            result_hash = patch(source, destination, model, target)
        print(f"Created {destination.resolve()}\nSHA-256 {result_hash}")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("error: interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
