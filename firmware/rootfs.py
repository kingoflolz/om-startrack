#!/usr/bin/env python3
"""Rebuild the OM-1 SquashFS slot reproducibly and apply file overlays.

The firmware stores the Linux root filesystem in a fixed-size SquashFS slot.
This helper starts from that authoritative slot, extracts ordinary files as an
unprivileged user, reconstructs numeric metadata and device nodes from
``unsquashfs -pf``, and refuses to apply an overlay unless an unmodified build
first reproduces every byte of the source slot.

Existing regular files can be replaced while preserving their metadata.  New
regular files require an explicit :class:`Install` carrying reviewed numeric
ownership, mode, and timestamp metadata; parent directories must already
exist.  The helper never creates directories implicitly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import NoReturn, Sequence


FORMAT = "om1-rootfs-build-v1"
OM1_ROOTFS_OFFSET = 0x08911000
OM1_ROOTFS_SLOT_SIZE = 0x00B44000
SQUASHFS_MAGIC = 0x73717368
SQUASHFS_LZO = 3
SQUASHFS_SUPPORTED_FLAGS = 0x00C0  # duplicate checking and NFS export table
SQUASHFS_SUPERBLOCK = struct.Struct("<5I6H8Q")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")


class RootfsError(ValueError):
    """Raised when a source, overlay, or generated filesystem is unsafe."""


@dataclass(frozen=True)
class Superblock:
    inode_count: int
    mkfs_time: int
    block_size: int
    fragment_count: int
    compression: int
    block_log: int
    flags: int
    id_count: int
    major: int
    minor: int
    root_inode: int
    bytes_used: int
    id_table_start: int
    xattr_id_table_start: int
    inode_table_start: int
    directory_table_start: int
    fragment_table_start: int
    export_table_start: int


@dataclass(frozen=True)
class PseudoRecord:
    path: str
    kind: str
    timestamp: int
    mode: str
    uid: int
    gid: int
    raw: str


@dataclass(frozen=True)
class Overlay:
    source: Path
    destination: str


@dataclass(frozen=True)
class Install:
    source: Path
    destination: str
    timestamp: int
    mode: str = "755"
    uid: int = 0
    gid: int = 0


@dataclass(frozen=True)
class OverlayResult:
    source: str
    destination: str
    original_size: int
    original_sha256: str
    replacement_size: int
    replacement_sha256: str
    preserved_timestamp: int
    preserved_mode: str
    preserved_uid: int
    preserved_gid: int


@dataclass(frozen=True)
class InstallResult:
    source: str
    destination: str
    size: int
    sha256: str
    timestamp: int
    mode: str
    uid: int
    gid: int


@dataclass(frozen=True)
class BuildResult:
    output_path: Path
    output_sha256: str
    output_size: int
    output_bytes_used: int
    manifest: dict[str, object]


def _fail(message: str) -> NoReturn:
    raise RootfsError(message)


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    try:
        with path.open("rb") as source:
            while block := source.read(1024 * 1024):
                size += len(block)
                digest.update(block)
    except OSError as error:
        _fail(f"cannot hash {path}: {error}")
    return size, digest.hexdigest()


def _parse_int(value: str) -> int:
    try:
        result = int(value, 0)
    except ValueError:
        raise argparse.ArgumentTypeError(
            f"expected a decimal integer or 0x-prefixed hexadecimal integer, got {value!r}"
        ) from None
    if result < 0:
        raise argparse.ArgumentTypeError("value must be non-negative")
    return result


def _parse_sha256(value: str, where: str) -> str:
    if not SHA256_RE.fullmatch(value):
        _fail(f"{where} must be exactly 64 hexadecimal characters")
    return value.lower()


def parse_superblock(data: bytes, *, slot_size: int) -> Superblock:
    """Parse and validate the subset of SquashFS used by the OM-1 image."""

    if len(data) < SQUASHFS_SUPERBLOCK.size:
        _fail("SquashFS slot is shorter than its 96-byte superblock")
    values = SQUASHFS_SUPERBLOCK.unpack_from(data)
    if values[0] != SQUASHFS_MAGIC:
        _fail(f"bad SquashFS magic {values[0]:#010x}")
    result = Superblock(*values[1:])
    if (result.major, result.minor) != (4, 0):
        _fail(
            f"unsupported SquashFS version {result.major}.{result.minor}; expected 4.0"
        )
    if result.compression != SQUASHFS_LZO:
        _fail(
            f"unsupported SquashFS compressor {result.compression}; OM-1 requires LZO"
        )
    if result.flags != SQUASHFS_SUPPORTED_FLAGS:
        _fail(
            f"unsupported SquashFS flags {result.flags:#x}; expected "
            f"{SQUASHFS_SUPPORTED_FLAGS:#x}"
        )
    if result.block_size != 1 << result.block_log:
        _fail(
            f"inconsistent block size/log {result.block_size}/{result.block_log}"
        )
    if result.block_size != 0x20000:
        _fail(f"unsupported OM-1 SquashFS block size {result.block_size:#x}")
    if result.bytes_used < SQUASHFS_SUPERBLOCK.size:
        _fail(f"invalid SquashFS bytes_used {result.bytes_used:#x}")
    if result.bytes_used > slot_size:
        _fail(
            f"SquashFS bytes_used {result.bytes_used:#x} exceeds slot {slot_size:#x}"
        )
    if result.xattr_id_table_start != 0xFFFFFFFFFFFFFFFF:
        _fail("rootfs contains xattrs; this metadata path has not been audited")
    return result


def _hash_and_read_slot(
    image: Path, offset: int, slot_size: int
) -> tuple[int, str, bytes]:
    """Hash one stable source-file pass while retaining the requested slot."""

    try:
        info = image.stat()
    except OSError as error:
        _fail(f"cannot stat source image {image}: {error}")
    if not stat.S_ISREG(info.st_mode):
        _fail(f"source image is not a regular file: {image}")
    end = offset + slot_size
    if end > info.st_size:
        _fail(
            f"rootfs slot {offset:#x}..{end:#x} exceeds source size {info.st_size:#x}"
        )
    digest = hashlib.sha256()
    slot = bytearray()
    cursor = 0
    try:
        with image.open("rb") as source:
            while block := source.read(1024 * 1024):
                block_end = cursor + len(block)
                overlap_start = max(cursor, offset)
                overlap_end = min(block_end, end)
                if overlap_start < overlap_end:
                    slot.extend(
                        block[overlap_start - cursor : overlap_end - cursor]
                    )
                digest.update(block)
                cursor = block_end
            final_info = os.fstat(source.fileno())
    except OSError as error:
        _fail(f"cannot read and hash source image {image}: {error}")
    if cursor != info.st_size or final_info.st_size != info.st_size:
        _fail("source image size changed while it was being read")
    if len(slot) != slot_size:
        _fail(f"short rootfs slot read: expected {slot_size}, got {len(slot)}")
    return cursor, digest.hexdigest(), bytes(slot)


def _run(command: list[str], *, what: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    except FileNotFoundError:
        _fail(f"required command is not installed: {command[0]}")
    except subprocess.CalledProcessError as error:
        output = error.stdout.strip()
        suffix = f": {output}" if output else ""
        _fail(f"{what} failed with exit status {error.returncode}{suffix}")


def _tool_version(command: str) -> str:
    try:
        result = subprocess.run(
            [command, "-version"],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    except FileNotFoundError:
        _fail(f"required command is not installed: {command}")
    lines = result.stdout.splitlines()
    if not lines or not lines[0].startswith(f"{command} version "):
        _fail(
            f"cannot determine {command} version (exit status {result.returncode})"
        )
    # squashfs-tools 4.7.5 prints a valid version and exits one for -version.
    return lines[0]


def _safe_pseudo_path(path: str, where: str) -> None:
    if path == "/":
        return
    pure = PurePosixPath(path)
    if pure.is_absolute() or not pure.parts or any(part in ("", ".", "..") for part in pure.parts):
        _fail(f"unsafe path {path!r} in {where}")
    if any(character.isspace() for character in path):
        _fail(f"whitespace in pseudo path {path!r} is unsupported")
    if "\\" in path or '"' in path:
        _fail(f"pseudo-file escape character in path {path!r} is unsupported")


def read_pseudo_records(path: Path, inode_count: int) -> list[PseudoRecord]:
    """Read the textual inode prefix of an ``unsquashfs -pf`` bundle."""

    records: list[PseudoRecord] = []
    names: set[str] = set()
    try:
        with path.open("rb") as source:
            for index in range(inode_count):
                encoded = source.readline()
                if not encoded:
                    _fail(
                        f"pseudo bundle ended after {index} record(s); expected {inode_count}"
                    )
                if b"\0" in encoded:
                    _fail(f"pseudo record {index} contains NUL data")
                try:
                    raw = encoded.decode("utf-8").rstrip("\n")
                except UnicodeDecodeError as error:
                    _fail(f"pseudo record {index} is not UTF-8: {error}")
                fields = raw.split()
                if len(fields) < 6:
                    _fail(f"malformed pseudo record {index}: {raw!r}")
                pseudo_path, kind, timestamp_text, mode, uid_text, gid_text = fields[:6]
                _safe_pseudo_path(pseudo_path, f"pseudo record {index}")
                if pseudo_path in names:
                    _fail(f"duplicate pseudo path {pseudo_path!r}")
                names.add(pseudo_path)
                if kind not in ("R", "D", "S", "B", "C", "I"):
                    _fail(f"unsupported pseudo inode type {kind!r} at {pseudo_path!r}")
                required_fields = {"R": 9, "D": 6, "S": 7, "B": 8, "C": 8}
                if kind in required_fields and len(fields) != required_fields[kind]:
                    _fail(f"malformed {kind} pseudo record for {pseudo_path!r}")
                if kind == "I" and len(fields) not in (6, 7):
                    _fail(f"malformed I pseudo record for {pseudo_path!r}")
                try:
                    timestamp = int(timestamp_text, 10)
                    uid = int(uid_text, 10)
                    gid = int(gid_text, 10)
                    int(mode, 8)
                except ValueError:
                    _fail(f"invalid numeric metadata for pseudo path {pseudo_path!r}")
                records.append(
                    PseudoRecord(
                        path=pseudo_path,
                        kind=kind,
                        timestamp=timestamp,
                        mode=mode,
                        uid=uid,
                        gid=gid,
                        raw=raw,
                    )
                )
    except OSError as error:
        _fail(f"cannot read pseudo bundle {path}: {error}")
    if "/" not in names:
        _fail("pseudo bundle has no root-directory record")
    return records


def _metadata_pseudo(
    records: list[PseudoRecord], root: Path, destination: Path
) -> PseudoRecord:
    """Write metadata modifiers and recreate special nodes without privileges."""

    root_record: PseudoRecord | None = None
    output: list[str] = []
    for record in records:
        if record.path == "/":
            if record.kind != "D":
                _fail("pseudo root record is not a directory")
            root_record = record
            continue
        target = root.joinpath(*PurePosixPath(record.path).parts)
        if record.kind in ("B", "C", "I"):
            try:
                info = target.lstat()
            except FileNotFoundError:
                pass
            except OSError as error:
                _fail(f"cannot inspect extracted special node {target}: {error}")
            else:
                if not (
                    stat.S_ISBLK(info.st_mode)
                    or stat.S_ISCHR(info.st_mode)
                    or stat.S_ISFIFO(info.st_mode)
                    or stat.S_ISSOCK(info.st_mode)
                ):
                    _fail(f"special-node path was extracted as another type: {target}")
                try:
                    target.unlink()
                except OSError as error:
                    _fail(f"cannot remove extracted special node {target}: {error}")
            output.append(record.raw)
        else:
            if not target.is_symlink() and not target.exists():
                _fail(f"ordinary inode was not extracted: {record.path}")
            output.append(
                f"{record.path} M {record.timestamp} {record.mode} "
                f"{record.uid} {record.gid}"
            )
    if root_record is None:  # pragma: no cover - checked above
        raise AssertionError("missing root record")
    try:
        destination.write_text("\n".join(output) + "\n", encoding="utf-8")
    except OSError as error:
        _fail(f"cannot write metadata pseudo file {destination}: {error}")
    return root_record


def _canonical_destination(value: str) -> str:
    value = value.removeprefix("/")
    _safe_pseudo_path(value, "overlay destination")
    if value == "/":
        _fail("overlay destination cannot be the root directory")
    return str(PurePosixPath(value))


def parse_overlay(value: str) -> Overlay:
    if "=" not in value:
        raise argparse.ArgumentTypeError("overlay must be SOURCE=ROOTFS_PATH")
    source_text, destination_text = value.split("=", 1)
    if not source_text or not destination_text:
        raise argparse.ArgumentTypeError("overlay must be SOURCE=ROOTFS_PATH")
    try:
        destination = _canonical_destination(destination_text)
    except RootfsError as error:
        raise argparse.ArgumentTypeError(str(error)) from None
    return Overlay(Path(source_text), destination)


def _apply_overlays(
    root: Path,
    overlays: Sequence[Overlay],
    records: list[PseudoRecord],
) -> list[OverlayResult]:
    by_path = {record.path: record for record in records}
    destinations: set[str] = set()
    results: list[OverlayResult] = []
    for overlay in overlays:
        destination = _canonical_destination(overlay.destination)
        if destination in destinations:
            _fail(f"duplicate overlay destination {destination!r}")
        destinations.add(destination)
        record = by_path.get(destination)
        if record is None:
            _fail(
                f"overlay destination {destination!r} is new; this helper only replaces "
                "existing regular files"
            )
        if record.kind != "R":
            _fail(
                f"overlay destination {destination!r} is pseudo type {record.kind}, not R"
            )
        try:
            source_info = overlay.source.stat()
        except OSError as error:
            _fail(f"cannot stat overlay source {overlay.source}: {error}")
        if not stat.S_ISREG(source_info.st_mode) or overlay.source.is_symlink():
            _fail(f"overlay source is not a regular, non-symlink file: {overlay.source}")

        target = root
        for part in PurePosixPath(destination).parts[:-1]:
            target /= part
            if target.is_symlink() or not target.is_dir():
                _fail(f"unsafe overlay ancestor in extracted rootfs: {target}")
        target /= PurePosixPath(destination).name
        try:
            target_info = target.lstat()
        except OSError as error:
            _fail(f"cannot inspect overlay destination {target}: {error}")
        if not stat.S_ISREG(target_info.st_mode):
            _fail(f"overlay destination is not a regular file: {target}")

        original_size, original_sha = _sha256_file(target)
        try:
            shutil.copyfile(overlay.source, target)
        except OSError as error:
            _fail(f"cannot install overlay {overlay.source} at {destination}: {error}")
        replacement_size, replacement_sha = _sha256_file(target)
        if original_sha == replacement_sha and original_size == replacement_size:
            _fail(f"overlay for {destination!r} is byte-identical to the original")
        results.append(
            OverlayResult(
                source=str(overlay.source),
                destination=destination,
                original_size=original_size,
                original_sha256=original_sha,
                replacement_size=replacement_size,
                replacement_sha256=replacement_sha,
                preserved_timestamp=record.timestamp,
                preserved_mode=record.mode,
                preserved_uid=record.uid,
                preserved_gid=record.gid,
            )
        )
    return results


def _apply_installs(
    root: Path,
    installs: Sequence[Install],
    records: list[PseudoRecord],
    pseudo: Path,
) -> list[InstallResult]:
    existing = {record.path for record in records}
    destinations: set[str] = set()
    results: list[InstallResult] = []
    metadata: list[str] = []
    for install in installs:
        destination = _canonical_destination(install.destination)
        if destination in destinations:
            _fail(f"duplicate install destination {destination!r}")
        destinations.add(destination)
        if destination in existing:
            _fail(
                f"install destination {destination!r} already exists; use an overlay"
            )
        try:
            source_info = install.source.stat()
        except OSError as error:
            _fail(f"cannot stat install source {install.source}: {error}")
        if not stat.S_ISREG(source_info.st_mode) or install.source.is_symlink():
            _fail(
                f"install source is not a regular, non-symlink file: {install.source}"
            )
        for label, value in (
            ("timestamp", install.timestamp),
            ("uid", install.uid),
            ("gid", install.gid),
        ):
            if isinstance(value, bool) or not isinstance(value, int):
                _fail(f"install {label} must be an integer for {destination!r}")
            if not (0 <= value <= 0xFFFFFFFF):
                _fail(
                    f"install {label} is outside uint32 range for {destination!r}"
                )
        if not isinstance(install.mode, str):
            _fail(f"install mode must be a string for {destination!r}")
        try:
            numeric_mode = int(install.mode, 8)
        except ValueError:
            _fail(f"invalid install mode {install.mode!r} for {destination!r}")
        if not (0 <= numeric_mode <= 0o7777) or format(numeric_mode, "o") != install.mode:
            _fail(f"non-canonical install mode {install.mode!r} for {destination!r}")

        target = root
        for part in PurePosixPath(destination).parts[:-1]:
            target /= part
            if target.is_symlink() or not target.is_dir():
                _fail(f"unsafe install ancestor in extracted rootfs: {target}")
        target /= PurePosixPath(destination).name
        if target.exists() or target.is_symlink():
            _fail(f"install destination unexpectedly exists: {target}")
        try:
            shutil.copyfile(install.source, target)
        except OSError as error:
            _fail(f"cannot install {install.source} at {destination}: {error}")
        size, digest = _sha256_file(target)
        metadata.append(
            f"{destination} M {install.timestamp} {install.mode} "
            f"{install.uid} {install.gid}"
        )
        results.append(
            InstallResult(
                source=str(install.source),
                destination=destination,
                size=size,
                sha256=digest,
                timestamp=install.timestamp,
                mode=install.mode,
                uid=install.uid,
                gid=install.gid,
            )
        )
    if metadata:
        try:
            with pseudo.open("a", encoding="utf-8") as destination_file:
                destination_file.write("\n".join(metadata) + "\n")
        except OSError as error:
            _fail(f"cannot append install metadata to {pseudo}: {error}")
    return results


def _mksquashfs_options(superblock: Superblock, root: PseudoRecord) -> list[str]:
    return [
        "-noappend",
        "-comp",
        "lzo",
        "-Xalgorithm",
        "lzo1x_999",
        "-Xcompression-level",
        "8",
        "-b",
        str(superblock.block_size),
        "-mkfs-time",
        str(superblock.mkfs_time),
        "-root-time",
        str(root.timestamp),
        "-root-uid",
        str(root.uid),
        "-root-gid",
        str(root.gid),
        "-root-mode",
        root.mode,
        "-processors",
        "1",
    ]


def _build_squashfs(
    root: Path,
    pseudo: Path,
    output: Path,
    superblock: Superblock,
    root_record: PseudoRecord,
    slot_size: int,
) -> tuple[bytes, Superblock, list[str]]:
    options = _mksquashfs_options(superblock, root_record)
    _run(
        [
            "mksquashfs",
            str(root),
            str(output),
            *options,
            "-pf",
            str(pseudo),
        ],
        what="mksquashfs rebuild",
    )
    try:
        result = output.read_bytes()
    except OSError as error:
        _fail(f"cannot read rebuilt SquashFS {output}: {error}")
    if len(result) > slot_size:
        _fail(
            f"rebuilt SquashFS occupies {len(result):#x} bytes, exceeding the "
            f"fixed {slot_size:#x}-byte slot"
        )
    if len(result) < slot_size:
        result += b"\0" * (slot_size - len(result))
    rebuilt = parse_superblock(result, slot_size=slot_size)
    if rebuilt.bytes_used > slot_size:
        _fail(
            f"rebuilt SquashFS bytes_used {rebuilt.bytes_used:#x} exceeds slot "
            f"{slot_size:#x}"
        )
    if any(result[rebuilt.bytes_used :]):
        _fail("rebuilt SquashFS has nonzero data after bytes_used")
    return result, rebuilt, options


def _preflight_output(path: Path, *, force: bool) -> None:
    if not path.parent.is_dir():
        _fail(f"output parent directory does not exist: {path.parent}")
    if path.exists() and not force:
        _fail(f"output already exists: {path} (pass --force to replace it)")


def _atomic_write(path: Path, data: bytes, *, force: bool) -> None:
    _preflight_output(path, force=force)
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            dir=path.parent, prefix=f".{path.name}.", suffix=".tmp"
        )
        temporary = Path(temporary_name)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except OSError as error:
        try:
            temporary.unlink()  # type: ignore[possibly-undefined]
        except (FileNotFoundError, UnboundLocalError):
            pass
        _fail(f"cannot atomically write {path}: {error}")


def rebuild_rootfs(
    image_path: str | os.PathLike[str],
    output_path: str | os.PathLike[str],
    *,
    slot_offset: int = OM1_ROOTFS_OFFSET,
    slot_size: int = OM1_ROOTFS_SLOT_SIZE,
    expected_image_sha256: str | None = None,
    overlays: Sequence[Overlay] = (),
    installs: Sequence[Install] = (),
    manifest_output: str | os.PathLike[str] | None = None,
    force: bool = False,
) -> BuildResult:
    """Rebuild one fixed rootfs slot, proving a byte-exact base first."""

    image = Path(image_path)
    output = Path(output_path)
    manifest_path = Path(manifest_output) if manifest_output is not None else None
    if slot_size < SQUASHFS_SUPERBLOCK.size:
        _fail("slot size is too small for a SquashFS superblock")
    if image.resolve() == output.resolve():
        _fail("source image and rootfs output must be different paths")
    if manifest_path is not None and manifest_path.resolve() in (
        image.resolve(),
        output.resolve(),
    ):
        _fail("manifest output must differ from source and rootfs output")
    _preflight_output(output, force=force)
    if manifest_path is not None:
        _preflight_output(manifest_path, force=force)

    image_size, image_sha, source_slot = _hash_and_read_slot(
        image, slot_offset, slot_size
    )
    if expected_image_sha256 is not None:
        required = _parse_sha256(expected_image_sha256, "expected image SHA-256")
        if image_sha != required:
            _fail(
                f"source image SHA-256 mismatch: expected {required}, got {image_sha}"
            )
    source_slot_sha = _sha256_bytes(source_slot)
    source_superblock = parse_superblock(source_slot, slot_size=slot_size)
    if any(source_slot[source_superblock.bytes_used :]):
        _fail("nonzero bytes follow SquashFS bytes_used inside the fixed slot")

    # Keep all build work beside the requested artifact. Besides making the
    # toolchain location auditable, this guarantees the final atomic rename is
    # on the same filesystem and avoids an implicit /tmp build dependency.
    with tempfile.TemporaryDirectory(
        prefix=f".{output.name}.rootfs-build.", dir=output.parent
    ) as temporary_name:
        temporary = Path(temporary_name)
        base_image = temporary / "base.squashfs"
        root = temporary / "root"
        bundle = temporary / "root.pseudo.bundle"
        pseudo = temporary / "metadata.pseudo"
        base_rebuilt_path = temporary / "base-rebuilt.squashfs"
        patched_path = temporary / "patched.squashfs"
        base_image.write_bytes(source_slot)

        _run(
            ["unsquashfs", "-no-exit-code", "-d", str(root), str(base_image)],
            what="rootfs extraction",
        )
        _run(
            ["unsquashfs", "-pf", str(bundle), str(base_image)],
            what="rootfs pseudo-metadata extraction",
        )
        records = read_pseudo_records(bundle, source_superblock.inode_count)
        root_record = _metadata_pseudo(records, root, pseudo)
        base_rebuilt, base_rebuilt_superblock, options = _build_squashfs(
            root,
            pseudo,
            base_rebuilt_path,
            source_superblock,
            root_record,
            slot_size,
        )
        if base_rebuilt != source_slot:
            _fail(
                "unmodified SquashFS rebuild is not byte-exact; refuse overlays "
                f"(source {_sha256_bytes(source_slot)}, rebuilt "
                f"{_sha256_bytes(base_rebuilt)})"
            )

        overlay_destinations = {
            _canonical_destination(overlay.destination) for overlay in overlays
        }
        install_destinations = {
            _canonical_destination(install.destination) for install in installs
        }
        collisions = overlay_destinations & install_destinations
        if collisions:
            _fail(
                "destination requested as both overlay and install: "
                + ", ".join(sorted(collisions))
            )
        overlay_results = _apply_overlays(root, overlays, records)
        install_results = _apply_installs(root, installs, records, pseudo)
        if overlay_results or install_results:
            result, result_superblock, _ = _build_squashfs(
                root,
                pseudo,
                patched_path,
                source_superblock,
                root_record,
                slot_size,
            )
        else:
            result = base_rebuilt
            result_superblock = base_rebuilt_superblock

    output_sha = _sha256_bytes(result)
    manifest: dict[str, object] = {
        "format": FORMAT,
        "base_firmware": {
            "path": str(image),
            "size": image_size,
            "sha256": image_sha,
            "required_sha256": expected_image_sha256.lower()
            if expected_image_sha256 is not None
            else None,
        },
        "source_slot": {
            "offset": slot_offset,
            "offset_hex": f"0x{slot_offset:08x}",
            "size": slot_size,
            "size_hex": f"0x{slot_size:x}",
            "sha256": source_slot_sha,
            "bytes_used": source_superblock.bytes_used,
            "bytes_used_hex": f"0x{source_superblock.bytes_used:x}",
        },
        "base_reproduction": {
            "byte_exact": True,
            "sha256": _sha256_bytes(base_rebuilt),
        },
        "toolchain": {
            "unsquashfs": _tool_version("unsquashfs"),
            "mksquashfs": _tool_version("mksquashfs"),
            "mksquashfs_options": options,
        },
        "overlays": [result.__dict__ for result in overlay_results],
        "installs": [result.__dict__ for result in install_results],
        "output": {
            "path": str(output),
            "size": len(result),
            "sha256": output_sha,
            "bytes_used": result_superblock.bytes_used,
            "bytes_used_hex": f"0x{result_superblock.bytes_used:x}",
        },
    }

    _atomic_write(output, result, force=force)
    if manifest_path is not None:
        manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
        _atomic_write(manifest_path, manifest_bytes, force=force)
    return BuildResult(
        output_path=output,
        output_sha256=output_sha,
        output_size=len(result),
        output_bytes_used=result_superblock.bytes_used,
        manifest=manifest,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="decoded firmware or isolated slot")
    parser.add_argument("output", type=Path, help="fixed-size rebuilt SquashFS slot")
    parser.add_argument(
        "--slot-offset",
        type=_parse_int,
        default=OM1_ROOTFS_OFFSET,
        help=f"rootfs offset in source (default: {OM1_ROOTFS_OFFSET:#x})",
    )
    parser.add_argument(
        "--slot-size",
        type=_parse_int,
        default=OM1_ROOTFS_SLOT_SIZE,
        help=f"fixed rootfs allocation (default: {OM1_ROOTFS_SLOT_SIZE:#x})",
    )
    parser.add_argument(
        "--expected-image-sha256",
        help="require this complete source-image SHA-256 before rebuilding",
    )
    parser.add_argument(
        "--overlay",
        type=parse_overlay,
        action="append",
        default=[],
        metavar="SOURCE=ROOTFS_PATH",
        help="replace an existing regular rootfs file; may be repeated",
    )
    parser.add_argument(
        "--manifest-output",
        type=Path,
        help="write a compact generated-artifact audit manifest",
    )
    parser.add_argument(
        "--force", action="store_true", help="atomically replace existing outputs"
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        result = rebuild_rootfs(
            arguments.source,
            arguments.output,
            slot_offset=arguments.slot_offset,
            slot_size=arguments.slot_size,
            expected_image_sha256=arguments.expected_image_sha256,
            overlays=arguments.overlay,
            manifest_output=arguments.manifest_output,
            force=arguments.force,
        )
    except RootfsError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(
        f"OK: wrote {result.output_path} ({result.output_size:#x} bytes, "
        f"bytes_used={result.output_bytes_used:#x}); SHA-256 {result.output_sha256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
