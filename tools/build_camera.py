#!/usr/bin/env python3
"""Compile the shared sources for either camera; export only custom binaries."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from firmware import container
from patch_firmware import identify

LAYERS = (
    ("main", ("main.S", "ptp_local.S")),
    ("rate_bridge", ("rate_bridge.S",)),
    ("rate_control", ("rate_control.S",)),
    ("gyro", ("gyro.c", "gyro_hooks.S")),
    ("reply", ("reply.S",)),
    ("gyro_main", ("gyro_main.S", "gyro_acquisition.S")),
    ("preposition", ("preposition.S",)),
)


def sections(path):
    data = path.read_bytes()
    h = struct.unpack_from("<16sHHIIIIIHHHHHH", data)
    if h[0][:7] != b"\x7fELF\x01\x01\x01" or h[1:4] != (2, 89, 1):
        raise ValueError("expected linked little-endian MN10300 ELF32")
    if h[7] not in (0x00810000, 0x00830000) or h[11] != 40:
        raise ValueError("unexpected ELF ISA or section layout")
    headers = [struct.unpack_from("<10I", data, h[6] + i * 40) for i in range(h[12])]
    names = headers[h[13]]
    strings = data[names[4]:names[4] + names[5]]
    result = {}
    for section in headers:
        name = strings[section[0]:].split(b"\0", 1)[0].decode("ascii")
        if section[1] in (4, 9) and section[5]:
            raise ValueError("unresolved relocation")
        if section[2] & 2 and section[5]:
            if section[1] != 1 or section[2] not in (2, 3, 6):
                raise ValueError(f"unexpected allocated section {name}")
            result[name] = section[3], data[section[4]:section[4] + section[5]]
    return result


def run(command, log, *, cwd, env=None):
    with log.open("w") as output:
        subprocess.run([str(x) for x in command], cwd=cwd, env=env,
                       stdout=output, stderr=subprocess.STDOUT, check=True)


def build(stock, work, toolchains, destination):
    with stock.open("rb") as f:
        model, target = identify(f)
    work = work.absolute()
    work.mkdir(parents=True, exist_ok=False)
    source = work / "source"
    shutil.copytree(ROOT / "camera", source / "camera", ignore=shutil.ignore_patterns("__pycache__"))
    (source / ".toolchains").symlink_to(toolchains.resolve(strict=True), target_is_directory=True)
    native = source / "camera/native"
    model_dir = source / "camera/models" / model
    substitutions = json.loads((model_dir / "native.json").read_text())
    for name, changes in substitutions.items():
        path = native / name
        text = path.read_text()
        for before, after, count in changes:
            if text.count(before) != count:
                raise ValueError(f"model source anchor changed: {name}: {before!r}")
            text = text.replace(before, after)
        path.write_text(text)
    destination = destination.absolute()
    destination.mkdir(parents=True, exist_ok=False)
    native_output = destination / "native"
    native_output.mkdir()
    prefix = toolchains.resolve() / "mn10300-elf-binutils-2.46.1/bin/mn10300-elf-"
    gcc = toolchains.resolve() / "mn10300-elf-gcc-16.1.0/bin/mn10300-elf-gcc"
    bytes_by_offset = {}
    layer_report = {}
    for layer, filenames in LAYERS:
        layer_work = work / layer
        layer_work.mkdir()
        paths = [native / name for name in filenames]
        if model == "om1ii" and layer == "main":
            paths.append(model_dir / "ptp_station.S")
        objects = []
        for path in paths:
            obj = layer_work / (path.stem + ".o")
            if path.suffix == ".c":
                command = [gcc, "-B", str(prefix.parent) + "/", "-mno-am33", "-mno-am33-2", "-mno-liw", "-Os",
                    "-ffreestanding", "-fno-builtin", "-fno-common", "-fno-asynchronous-unwind-tables",
                    "-fno-unwind-tables", "-fno-strict-aliasing", "-Wall", "-Wextra", "-Werror",
                    "-c", path, "-o", obj]
            else:
                command = [str(prefix) + "as", "-o", obj, path]
            run(command, obj.with_suffix(".log"), cwd=source)
            objects.append(obj)
        elf = native_output / (layer + ".elf")
        run([str(prefix) + "ld", *( ["--no-relax"] if layer in ("main", "gyro_main") else []),
             "-T", model_dir / (layer + ".ld"), "-o", elf, *objects], layer_work / "link.log", cwd=source)
        linked = sections(elf)
        layer_report[layer] = []
        for name, (vma, data) in linked.items():
            if 0x40002800 <= vma < vma + len(data) <= 0x40002800 + target["cpu0_size"]:
                offset = target["cpu0_offset"] + vma - 0x40002800
            elif 0x60021020 <= vma < vma + len(data) <= 0x62020FE0:
                offset = vma - 0x60021000
            else:
                raise ValueError(f"native section outside loaded firmware: {name}")
            for index, value in enumerate(data):
                bytes_by_offset[offset + index] = value
            layer_report[layer].append({"section": name, "vma": vma, "size": len(data)})
    # Collapse overlaid ELF sections using compiled bytes only. No stock byte
    # or firmware-derived data is read or included in these binary segments.
    intervals = []
    for offset in sorted(bytes_by_offset):
        if intervals and intervals[-1][1] == offset:
            intervals[-1] = (intervals[-1][0], offset + 1)
        else:
            intervals.append((offset, offset + 1))
    layout = []
    for start, end in intervals:
        filename = f"{start:08x}.bin"
        (native_output / filename).write_bytes(bytes(bytes_by_offset[i] for i in range(start, end)))
        layout.append({"offset": start, "size": end - start, "file": filename})
    (native_output / "layout.json").write_text(json.dumps(layout, indent=2) + "\n")
    (native_output / "sections.json").write_text(json.dumps(layer_report, indent=2) + "\n")

    decoded = work / "stock.decoded.BIN"
    container.transform_file(stock, decoded, container.DECODE_PERM)
    rootfs = work / "stock.squashfs"
    with decoded.open("rb") as f:
        f.seek(target["rootfs_offset"])
        rootfs.write_bytes(f.read(target["rootfs_size"]))
    sysroot = work / "sysroot"
    run(["unsquashfs", "-no-exit-code", "-no-progress", "-d", sysroot, rootfs], work / "extract.log", cwd=source)
    if model == "om1ii":
        spec = importlib.util.spec_from_file_location("model_linux", model_dir / "linux/prepare_sources.py")
        adapter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(adapter)
        adapter.prepare_sources(source, decoded, sysroot)
    arm = toolchains.resolve() / "gcc-arm-10.3-2021.07-x86_64-arm-none-linux-gnueabihf"
    env = dict(os.environ, OM1_ARM_TOOLCHAIN=str(arm), OM1_CAMERA_ROOTFS=str(sysroot))
    preload, worker = work / "preload", work / "worker"
    run([ROOT / "tools/build_linux.sh", source, preload], work / "preload.log", cwd=source, env=env)
    arm_prefix = arm / "bin/arm-none-linux-gnueabihf-"
    assignments = [f"{name}={arm_prefix}{tool}" for name, tool in
                   (("CXX", "g++"), ("STRIP", "strip"), ("READELF", "readelf"), ("NM", "nm"), ("SIZE", "size"))]
    run(["make", "-C", source / "camera/linux/star-worker", *assignments,
         "CPPFLAGS=", "CXXFLAGS=", "LDFLAGS=", f"OUTPUT_DIR={worker}", "all", "audit"],
        work / "worker.log", cwd=source, env=env)
    linux_output = destination / "linux"
    linux_output.mkdir()
    for path in (preload / "libbsa_gatt_shim.so", preload / "star_gatt_server", worker / "om1-stars"):
        shutil.copy2(path, linux_output / path.name)
    print(f"Built {target['model']} components in {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--toolchains", type=Path, required=True)
    args = parser.parse_args()
    build(args.firmware, args.work_dir, args.toolchains, args.output_dir)
