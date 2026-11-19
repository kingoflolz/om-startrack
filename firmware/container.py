#!/usr/bin/env python3
"""Inspect, descramble, and extract OM-1 and OM-1 Mark II firmware containers.

The transform implemented here was derived from the local firmware image.  It
does not use a key: each 16-byte payload block is byte-permuted and inverted.
The clear 32-byte top-level headers and trailers are never transformed;
nested record framing lives inside an outer payload and is transformed once.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Sequence


MAGIC = b"OE\x14\x30"
OM1II_MAGIC = b"OE\x14\x60"
SUPPORTED_MAGICS = (MAGIC, OM1II_MAGIC)
HEADER_SIZE = 0x20
BLOCK_SIZE = 0x10
HEADER = struct.Struct("<4s7I")
DIRECTORY_ENTRY = struct.Struct("<4I")

# plain[16*q+i] = stored[16*q+DECODE_PERM[i]] ^ 0xff
DECODE_PERM = (1, 3, 0, 2, 6, 4, 7, 5, 11, 10, 9, 8, 13, 14, 12, 15)

# stored[16*q+j] = plain[16*q+ENCODE_PERM[j]] ^ 0xff
ENCODE_PERM = (2, 0, 3, 1, 5, 7, 4, 6, 11, 10, 9, 8, 14, 12, 13, 15)
INVERT_TABLE = bytes.maketrans(bytes(range(256)), bytes(range(255, -1, -1)))
UINT32_MASK = 0xFFFFFFFF


class FormatError(ValueError):
    """Raised when framing invariants do not hold."""


@dataclass(frozen=True)
class Record:
    offset: int
    reserved_04: int
    load_address: int
    size_field: int
    payload_size: int
    version: int
    component: int
    metadata_18: int
    header_value: int
    trailer_value: int
    framing: str = "standard"
    magic: bytes = MAGIC

    @property
    def payload_offset(self) -> int:
        return self.offset + HEADER_SIZE

    @property
    def trailer_offset(self) -> int:
        return self.payload_offset + self.payload_size

    @property
    def total_size(self) -> int:
        return self.payload_size + 2 * HEADER_SIZE

    @property
    def end(self) -> int:
        return self.offset + self.total_size

    def as_dict(self) -> dict[str, int | str]:
        return {
            "offset": self.offset,
            "offset_hex": f"0x{self.offset:08x}",
            "total_size": self.total_size,
            "total_size_hex": f"0x{self.total_size:x}",
            "payload_offset": self.payload_offset,
            "payload_size": self.payload_size,
            "payload_size_hex": f"0x{self.payload_size:x}",
            "size_field": self.size_field,
            "size_field_hex": f"0x{self.size_field:x}",
            "load_address": self.load_address,
            "load_address_hex": f"0x{self.load_address:08x}",
            "version": self.version,
            "version_hex": f"0x{self.version:x}",
            "component": self.component,
            "component_hex": f"0x{self.component:x}",
            "reserved_04": self.reserved_04,
            "metadata_18": self.metadata_18,
            "metadata_18_hex": f"0x{self.metadata_18:08x}",
            "header_value": self.header_value,
            "header_value_hex": f"0x{self.header_value:08x}",
            "trailer_value": self.trailer_value,
            "trailer_value_hex": f"0x{self.trailer_value:08x}",
            "framing": self.framing,
        }


@dataclass(frozen=True)
class DirectoryEntry:
    index: int
    address: int
    size: int
    record_relative_offset: int
    file_offset: int

    def as_dict(self) -> dict[str, int | str]:
        return {
            "index": self.index,
            "address": self.address,
            "address_hex": f"0x{self.address:08x}",
            "size": self.size,
            "size_hex": f"0x{self.size:x}",
            "record_relative_offset": self.record_relative_offset,
            "record_relative_offset_hex": f"0x{self.record_relative_offset:x}",
            "file_offset": self.file_offset,
            "file_offset_hex": f"0x{self.file_offset:08x}",
        }


def transform_blocks(data: bytes | bytearray | memoryview, permutation: Sequence[int]) -> bytes:
    """Apply one direction of the 16-byte NOT+lane transform."""

    raw = bytes(data)
    if len(raw) % BLOCK_SIZE:
        raise FormatError(f"transform length {len(raw):#x} is not a multiple of 16")
    out = bytearray(len(raw))
    for destination_lane, source_lane in enumerate(permutation):
        out[destination_lane::BLOCK_SIZE] = raw[source_lane::BLOCK_SIZE].translate(
            INVERT_TABLE
        )
    return bytes(out)


def descramble(data: bytes | bytearray | memoryview) -> bytes:
    return transform_blocks(data, DECODE_PERM)


def scramble(data: bytes | bytearray | memoryview) -> bytes:
    return transform_blocks(data, ENCODE_PERM)


def sum_le32(data: bytes | bytearray | memoryview, initial: int = 0) -> int:
    """Return the modulo-2^32 sum of little-endian 32-bit words."""

    view = memoryview(data)
    if len(view) % 4:
        raise FormatError(f"checksum length {len(view):#x} is not word-aligned")
    if sys.byteorder == "little":
        word_sum = sum(view.cast("I"))
    else:  # pragma: no cover - the analysis host is little-endian
        word_sum = sum(word[0] for word in struct.iter_unpack("<I", view))
    return (initial + word_sum) & UINT32_MASK


def checksum_word(*chunks: bytes | bytearray | memoryview) -> int:
    """Generate the final word that makes the concatenated word sum zero."""

    total = 0
    for chunk in chunks:
        total = sum_le32(chunk, total)
    return (-total) & UINT32_MASK


def checksum_details(
    frame: bytes | bytearray | memoryview, trailer_value: int
) -> dict[str, int | str | bool]:
    total = sum_le32(frame)
    expected = checksum_word(memoryview(frame)[:-4])
    return _checksum_result(total, expected, trailer_value)


def checksum_details_for_parts(
    header: bytes,
    payload: bytes | bytearray | memoryview,
    trailer: bytes,
    trailer_value: int,
) -> dict[str, int | str | bool]:
    total = sum_le32(header)
    total = sum_le32(payload, total)
    total = sum_le32(trailer, total)
    expected = checksum_word(header, payload, trailer[:28])
    return _checksum_result(total, expected, trailer_value)


def _checksum_result(
    total: int, expected: int, trailer_value: int
) -> dict[str, int | str | bool]:
    if trailer_value == 0:
        status = "omitted"
    elif total == 0:
        status = "valid"
    else:
        status = "mismatch"
    return {
        "algorithm": "sum-le32-twos-complement",
        "status": status,
        "valid": status == "valid",
        "sum": total,
        "sum_hex": f"0x{total:08x}",
        "expected_value": expected,
        "expected_value_hex": f"0x{expected:08x}",
    }


def _record_from_headers(
    header: bytes,
    trailer: bytes,
    offset: int,
    *,
    payload_size: int | None = None,
    framing: str = "standard",
) -> Record:
    if len(header) != HEADER_SIZE or len(trailer) != HEADER_SIZE:
        raise FormatError(f"truncated record framing at {offset:#x}")
    fields = HEADER.unpack(header)
    trailer_fields = HEADER.unpack(trailer)
    if fields[0] not in SUPPORTED_MAGICS:
        raise FormatError(f"bad magic at {offset:#x}: {fields[0].hex(' ')}")
    if header[:28] != trailer[:28]:
        raise FormatError(f"header/trailer prefix mismatch at {offset:#x}")
    size_field = fields[3]
    if size_field % BLOCK_SIZE:
        raise FormatError(
            f"size field at {offset:#x} is not block-aligned: {size_field:#x}"
        )
    if payload_size is None:
        payload_size = size_field
    if payload_size < 0 or payload_size % BLOCK_SIZE:
        raise FormatError(
            f"payload at {offset:#x} has invalid size {payload_size:#x}"
        )
    return Record(
        offset=offset,
        reserved_04=fields[1],
        load_address=fields[2],
        size_field=size_field,
        payload_size=payload_size,
        version=fields[4],
        component=fields[5],
        metadata_18=fields[6],
        header_value=fields[7],
        trailer_value=trailer_fields[7],
        framing=framing,
        magic=fields[0],
    )


def read_outer_records(path: Path) -> list[Record]:
    """Parse the concatenated top-level records without loading their payloads."""

    file_size = path.stat().st_size
    records: list[Record] = []
    offset = 0
    with path.open("rb") as source:
        while offset < file_size:
            source.seek(offset)
            header = source.read(HEADER_SIZE)
            if len(header) != HEADER_SIZE:
                raise FormatError(f"truncated outer header at {offset:#x}")
            if header[:4] not in SUPPORTED_MAGICS:
                raise FormatError(f"outer record at {offset:#x} has no OE magic")
            # The official Mark II image retains the OM-1 model tag on its
            # shared component 0x107. Every other outer record must agree.
            if records and header[:4] != records[0].magic:
                shared_component = (
                    records[0].magic == OM1II_MAGIC and header[:4] == MAGIC
                    and struct.unpack_from("<I", header, 20)[0] == 0x107
                    and struct.unpack_from("<I", header, 8)[0] == 0x08000000
                )
                if not shared_component:
                    raise FormatError(f"mixed camera models at outer record {offset:#x}")
            payload_size = struct.unpack_from("<I", header, 12)[0]
            end = offset + payload_size + 2 * HEADER_SIZE
            if end > file_size:
                raise FormatError(
                    f"outer record at {offset:#x} ends beyond EOF ({end:#x})"
                )
            source.seek(offset + HEADER_SIZE + payload_size)
            trailer = source.read(HEADER_SIZE)
            record = _record_from_headers(header, trailer, offset)
            records.append(record)
            offset = record.end
    if offset != file_size:
        raise FormatError(f"outer records end at {offset:#x}, file ends at {file_size:#x}")
    if not records:
        raise FormatError("container has no outer records")
    return records


def parse_record_in_buffer(
    data: bytes | bytearray | memoryview, local_offset: int, global_base: int
) -> Record:
    view = memoryview(data)
    if local_offset < 0 or local_offset + HEADER_SIZE > len(view):
        raise FormatError("record header is outside its parent buffer")
    header = bytes(view[local_offset : local_offset + HEADER_SIZE])
    if header[:4] not in SUPPORTED_MAGICS:
        raise FormatError("candidate does not start with OE magic")
    size_field = struct.unpack_from("<I", header, 12)[0]

    # Normal records store the payload byte count.  One locally observed nested
    # component 0x0e (under outer component 0x10e) instead stores the distance
    # from its header to its trailer, so its actual payload is 0x20 bytes
    # shorter.  Require a complete duplicated trailer in either case; this
    # keeps the compatibility path from accepting a bare magic string.
    candidates = [
        (local_offset + HEADER_SIZE + size_field, size_field, "standard"),
    ]
    if size_field >= HEADER_SIZE:
        candidates.append(
            (local_offset + size_field, size_field - HEADER_SIZE, "trailer-distance")
        )
    errors: list[FormatError] = []
    for trailer_offset, payload_size, framing in candidates:
        end = trailer_offset + HEADER_SIZE
        if end > len(view):
            continue
        trailer = bytes(view[trailer_offset:end])
        try:
            return _record_from_headers(
                header,
                trailer,
                global_base + local_offset,
                payload_size=payload_size,
                framing=framing,
            )
        except FormatError as error:
            errors.append(error)
    if errors:
        raise errors[-1]
    raise FormatError("candidate extends beyond its parent buffer")


def find_nested_records(
    decoded_payload: bytes | bytearray, global_payload_offset: int, *, magic: bytes = MAGIC
) -> list[Record]:
    """Find structurally valid framed records inside one decoded outer payload."""

    if magic not in SUPPORTED_MAGICS:
        raise FormatError("unsupported camera model for nested records")
    records: list[Record] = []
    position = 0
    while True:
        position = decoded_payload.find(magic, position)
        if position < 0:
            break
        try:
            candidate = parse_record_in_buffer(
                decoded_payload, position, global_payload_offset
            )
        except FormatError:
            position += 1
            continue
        records.append(candidate)
        position += len(magic)
    return records


def parse_directory(record: Record, decoded_payload: bytes) -> list[DirectoryEntry]:
    """Parse a leading, terminated array of {address,size,0,0} entries."""

    entries: list[DirectoryEntry] = []
    previous_end = HEADER_SIZE
    terminated = False
    for index in range(min(len(decoded_payload) // DIRECTORY_ENTRY.size, 1024)):
        address, size, reserved_08, reserved_0c = DIRECTORY_ENTRY.unpack_from(
            decoded_payload, index * DIRECTORY_ENTRY.size
        )
        # Most terminators are all zero.  Component 0x102 uses an end-address
        # sentinel with a zero size and zero reserved words.
        if size == reserved_08 == reserved_0c == 0:
            terminated = True
            break
        if reserved_08 or reserved_0c or size == 0 or address < record.load_address:
            break
        relative = address - record.load_address
        # The address space is based at the record header, while decoded_payload
        # starts 0x20 bytes later.
        if relative < HEADER_SIZE:
            break
        if relative + size > record.total_size - HEADER_SIZE:
            break
        if relative < previous_end:
            break
        entries.append(
            DirectoryEntry(
                index=index,
                address=address,
                size=size,
                record_relative_offset=relative,
                file_offset=record.offset + relative,
            )
        )
        previous_end = relative + size
    if not entries:
        return []
    table_end = HEADER_SIZE + (len(entries) + 1) * DIRECTORY_ENTRY.size
    if not terminated or entries[0].record_relative_offset < table_end:
        return []
    return entries


def _iter_transformed_outer(
    path: Path, permutation: Sequence[int], chunk_size: int = 4 * 1024 * 1024
) -> Iterator[tuple[Record, bytes, bytes, Iterator[bytes]]]:
    """Internal streaming iterator used by decode/encode.

    The yielded tuple contains the record, header, trailer, and an iterator over
    transformed payload chunks.  The consumer must exhaust each chunk iterator
    before advancing to the next record.
    """

    if chunk_size % BLOCK_SIZE:
        raise ValueError("chunk_size must be 16-byte aligned")
    source = path.open("rb")
    try:
        for record in read_outer_records(path):
            source.seek(record.offset)
            header = source.read(HEADER_SIZE)
            source.seek(record.trailer_offset)
            trailer = source.read(HEADER_SIZE)

            def chunks(rec: Record = record) -> Iterator[bytes]:
                source.seek(rec.payload_offset)
                remaining = rec.payload_size
                while remaining:
                    amount = min(chunk_size, remaining)
                    block = source.read(amount)
                    if len(block) != amount:
                        raise FormatError(f"short payload read at {source.tell():#x}")
                    yield transform_blocks(block, permutation)
                    remaining -= amount

            yield record, header, trailer, chunks()
    finally:
        source.close()


def transform_file(source_path: Path, output_path: Path, permutation: Sequence[int]) -> None:
    if source_path.resolve() == output_path.resolve() or (
        output_path.exists() and source_path.samefile(output_path)
    ):
        raise FormatError("input and output paths must be different")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as output:
        for _record, header, trailer, chunks in _iter_transformed_outer(
            source_path, permutation
        ):
            output.write(header)
            for chunk in chunks:
                output.write(chunk)
            output.write(trailer)


def encode_file(source_path: Path, output_path: Path) -> None:
    """Scramble a decoded container and refresh every present checksum."""

    if source_path.resolve() == output_path.resolve() or (
        output_path.exists() and source_path.samefile(output_path)
    ):
        raise FormatError("input and output paths must be different")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    records = read_outer_records(source_path)
    with source_path.open("rb") as source, output_path.open("wb") as output:
        for outer in records:
            source.seek(outer.offset)
            header = source.read(HEADER_SIZE)
            payload = bytearray(source.read(outer.payload_size))
            trailer = source.read(HEADER_SIZE)
            if len(payload) != outer.payload_size or len(trailer) != HEADER_SIZE:
                raise FormatError(f"short decoded record at {outer.offset:#x}")

            # Nested records with a nonzero trailer carry the same checksum.
            # Zero-valued component-6 trailers omit it and must remain zero.
            for nested in find_nested_records(payload, outer.payload_offset, magic=outer.magic):
                if nested.trailer_value == 0:
                    continue
                start = nested.offset - outer.payload_offset
                end = nested.end - outer.payload_offset
                frame = memoryview(payload)[start:end]
                struct.pack_into("<I", payload, end - 4, checksum_word(frame[:-4]))

            refreshed_trailer = trailer[:28] + struct.pack(
                "<I", checksum_word(header, payload, trailer[:28])
            )
            output.write(header)
            output.write(scramble(payload))
            output.write(refreshed_trailer)


def iter_decoded_payloads(path: Path) -> Iterator[tuple[Record, bytes]]:
    records = read_outer_records(path)
    with path.open("rb") as source:
        for record in records:
            source.seek(record.payload_offset)
            encoded = source.read(record.payload_size)
            if len(encoded) != record.payload_size:
                raise FormatError(f"short read for outer record at {record.offset:#x}")
            yield record, descramble(encoded)


def _artifact_scan(payload: bytes, global_base: int) -> list[dict[str, int | str]]:
    """Report high-confidence structures with locally checkable headers."""

    artifacts: list[dict[str, int | str]] = []

    def add(kind: str, local: int, size: int | None = None, **extra: int | str) -> None:
        item: dict[str, int | str] = {
            "kind": kind,
            "offset": global_base + local,
            "offset_hex": f"0x{global_base + local:08x}",
        }
        if size is not None:
            item["size"] = size
            item["size_hex"] = f"0x{size:x}"
        item.update(extra)
        artifacts.append(item)

    # Flattened Device Tree: big-endian magic and total size.
    position = 0
    while True:
        position = payload.find(b"\xd0\x0d\xfe\xed", position)
        if position < 0:
            break
        if position + 8 <= len(payload):
            total = struct.unpack_from(">I", payload, position + 4)[0]
            if 0x28 <= total <= len(payload) - position:
                add("dtb", position, total)
        position += 4

    squashfs_ranges: list[tuple[int, int]] = []

    # SquashFS v4 superblock, little endian.  Find this before looking for
    # signatures so compressed bytes inside it are not reported as children.
    position = 0
    while True:
        position = payload.find(b"hsqs", position)
        if position < 0:
            break
        if position + 0x60 <= len(payload):
            major, minor = struct.unpack_from("<HH", payload, position + 28)
            bytes_used = struct.unpack_from("<Q", payload, position + 40)[0]
            if major == 4 and bytes_used <= len(payload) - position:
                add(
                    "squashfs",
                    position,
                    bytes_used,
                    major=major,
                    minor=minor,
                )
                squashfs_ranges.append((position, position + bytes_used))
        position += 4

    def inside_squashfs(position: int) -> bool:
        return any(start <= position < end for start, end in squashfs_ranges)

    # ELF: require the fixed header sizes and table entry sizes, not just magic.
    position = 0
    while True:
        position = payload.find(b"\x7fELF", position)
        if position < 0:
            break
        if (
            not inside_squashfs(position)
            and position + 0x40 <= len(payload)
            and payload[position + 5] == 1
            and payload[position + 6] == 1
            and struct.unpack_from("<I", payload, position + 20)[0] == 1
        ):
            elf_class = payload[position + 4]
            if elf_class == 2:
                machine = struct.unpack_from("<H", payload, position + 18)[0]
                shoff = struct.unpack_from("<Q", payload, position + 40)[0]
                ehsize, phentsize, _phnum, shentsize, shnum = struct.unpack_from(
                    "<5H", payload, position + 52
                )
                extent = shoff + shentsize * shnum if shoff and shnum else None
                if (
                    ehsize == 64
                    and phentsize in (0, 56)
                    and shentsize in (0, 64)
                    and (extent is None or extent <= len(payload) - position)
                ):
                    add(
                        "elf64",
                        position,
                        extent,
                        machine=machine,
                        machine_hex=f"0x{machine:x}",
                    )
            elif elf_class == 1:
                machine = struct.unpack_from("<H", payload, position + 18)[0]
                ehsize, phentsize, _phnum, shentsize, _shnum = struct.unpack_from(
                    "<5H", payload, position + 40
                )
                if ehsize == 52 and phentsize in (0, 32) and shentsize in (0, 40):
                    add(
                        "elf32",
                        position,
                        None,
                        machine=machine,
                        machine_hex=f"0x{machine:x}",
                    )
        position += 4

    # AArch64 Linux Image header.  ARMd appears at header offset 0x38.
    position = 0
    while True:
        position = payload.find(b"ARMd", position)
        if position < 0:
            break
        start = position - 0x38
        if start >= 0 and start + 0x40 <= len(payload):
            text_offset, image_size, flags = struct.unpack_from(
                "<QQQ", payload, start + 8
            )
            if image_size and image_size < 0x40000000:
                add(
                    "arm64-linux-image",
                    start,
                    None,
                    text_offset=text_offset,
                    text_offset_hex=f"0x{text_offset:x}",
                    image_size=image_size,
                    image_size_hex=f"0x{image_size:x}",
                    flags=flags,
                    flags_hex=f"0x{flags:x}",
                )
        position += 4

    # A newc archive is accepted only if all headers parse and it reaches a
    # TRAILER!!! entry.  This rejects the kernel's literal "070701" strings.
    def newc_extent(start: int) -> int | None:
        cursor = start
        for _ in range(100000):
            if cursor + 110 > len(payload) or payload[cursor : cursor + 6] != b"070701":
                return None
            fields = payload[cursor + 6 : cursor + 110]
            try:
                values = [int(fields[i : i + 8], 16) for i in range(0, 104, 8)]
            except ValueError:
                return None
            file_size = values[6]
            name_size = values[11]
            if not (1 <= name_size <= 0x10000):
                return None
            name_start = cursor + 110
            name_end = name_start + name_size
            if name_end > len(payload) or payload[name_end - 1] != 0:
                return None
            name = payload[name_start : name_end - 1]
            data_start = (name_end + 3) & ~3
            data_end = data_start + file_size
            cursor = (data_end + 3) & ~3
            if cursor > len(payload):
                return None
            if name == b"TRAILER!!!":
                return cursor - start
        return None

    position = 0
    while True:
        position = payload.find(b"070701", position)
        if position < 0:
            break
        if not inside_squashfs(position):
            extent = newc_extent(position)
            if extent is not None:
                add("cpio-newc", position, extent)
                position += extent
                continue
        position += 6

    # Gzip: inflate through the trailer so both DEFLATE structure and CRC/ISIZE
    # are checked, and report the exact compressed extent.
    position = 0
    while True:
        position = payload.find(b"\x1f\x8b\x08", position)
        if position < 0:
            break
        if not inside_squashfs(position):
            try:
                inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
                uncompressed = inflater.decompress(payload[position:])
            except zlib.error:
                pass
            else:
                if inflater.eof:
                    extent = len(payload) - position - len(inflater.unused_data)
                    add(
                        "gzip",
                        position,
                        extent,
                        uncompressed_size=len(uncompressed),
                        uncompressed_size_hex=f"0x{len(uncompressed):x}",
                    )
        position += 3

    artifacts.sort(key=lambda item: int(item["offset"]))
    return artifacts


def analyze(path: Path) -> dict[str, object]:
    source_hash = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
            source_hash.update(block)

    manifest: dict[str, object] = {
        "source": str(path),
        "size": path.stat().st_size,
        "sha256": source_hash.hexdigest(),
        "magic": read_outer_records(path)[0].magic.hex(),
        "descramble_permutation": list(DECODE_PERM),
        "rescramble_permutation": list(ENCODE_PERM),
        "outer_records": [],
        "nested_records": [],
        "artifacts": [],
    }
    outer_items: list[dict[str, object]] = manifest["outer_records"]  # type: ignore[assignment]
    nested_items: list[dict[str, object]] = manifest["nested_records"]  # type: ignore[assignment]
    artifacts: list[dict[str, int | str]] = manifest["artifacts"]  # type: ignore[assignment]

    for outer_index, (outer, payload) in enumerate(iter_decoded_payloads(path)):
        outer_item: dict[str, object] = outer.as_dict()
        outer_item["index"] = outer_index
        outer_item["directory"] = [entry.as_dict() for entry in parse_directory(outer, payload)]
        with path.open("rb") as framing_source:
            framing_source.seek(outer.offset)
            header = framing_source.read(HEADER_SIZE)
            framing_source.seek(outer.trailer_offset)
            trailer = framing_source.read(HEADER_SIZE)
        outer_item["integrity"] = checksum_details_for_parts(
            header, payload, trailer, outer.trailer_value
        )
        outer_items.append(outer_item)
        artifacts.extend(_artifact_scan(payload, outer.payload_offset))

        for nested_index, nested in enumerate(
            find_nested_records(payload, outer.payload_offset, magic=outer.magic)
        ):
            local = nested.payload_offset - outer.payload_offset
            nested_payload = payload[local : local + nested.payload_size]
            nested_item: dict[str, object] = nested.as_dict()
            nested_item["outer_index"] = outer_index
            nested_item["nested_index"] = nested_index
            nested_item["directory"] = [
                entry.as_dict() for entry in parse_directory(nested, nested_payload)
            ]
            frame_start = nested.offset - outer.payload_offset
            frame_end = nested.end - outer.payload_offset
            nested_item["integrity"] = checksum_details(
                payload[frame_start:frame_end], nested.trailer_value
            )
            nested_items.append(nested_item)

    artifacts.sort(key=lambda item: int(item["offset"]))
    return manifest


def _record_label(prefix: str, record: Record) -> str:
    return (
        f"{prefix}_comp{record.component:04x}_load{record.load_address:08x}"
        f"_off{record.offset:08x}"
    )


def extract(path: Path, output_dir: Path, include_containers: bool) -> dict[str, object]:
    """Extract non-overlapping leaf images described by decoded directories."""

    if output_dir.exists():
        if not output_dir.is_dir():
            raise FormatError(f"extraction target is not a directory: {output_dir}")
        if any(output_dir.iterdir()):
            raise FormatError(f"extraction target is not empty: {output_dir}")
    else:
        output_dir.mkdir(parents=True)
    leaves_dir = output_dir / "leaves"
    leaves_dir.mkdir(exist_ok=True)
    containers_dir = output_dir / "containers"
    if include_containers:
        containers_dir.mkdir(exist_ok=True)
    artifacts_dir = output_dir / "artifacts"
    artifacts_dir.mkdir(exist_ok=True)

    manifest = analyze(path)
    extracted: list[dict[str, object]] = []
    manifest["extracted"] = extracted
    extracted_artifacts: list[dict[str, object]] = []
    manifest["extracted_artifacts"] = extracted_artifacts

    artifact_extensions = {
        "dtb": ".dtb",
        "elf32": ".elf",
        "elf64": ".elf",
        "gzip": ".gz",
        "cpio-newc": ".cpio",
        "squashfs": ".squashfs",
    }

    for outer_index, (outer, outer_payload) in enumerate(iter_decoded_payloads(path)):
        nested_records = find_nested_records(outer_payload, outer.payload_offset, magic=outer.magic)
        nested_by_offset = {record.offset: record for record in nested_records}

        for artifact_index, artifact in enumerate(manifest["artifacts"]):  # type: ignore[index]
            if "size" not in artifact:
                continue
            artifact_offset = int(artifact["offset"])
            artifact_size = int(artifact["size"])
            local = artifact_offset - outer.payload_offset
            if local < 0 or local + artifact_size > len(outer_payload):
                continue
            kind = str(artifact["kind"])
            extension = artifact_extensions.get(kind, ".bin")
            name = (
                f"artifact{artifact_index:03d}_{kind}_off{artifact_offset:08x}"
                f"_size{artifact_size:x}{extension}"
            )
            destination = artifacts_dir / name
            destination.write_bytes(outer_payload[local : local + artifact_size])
            relative_path = str(destination.relative_to(output_dir))
            artifact["path"] = relative_path
            extracted_artifacts.append(
                {
                    "path": relative_path,
                    "kind": kind,
                    "offset": artifact_offset,
                    "size": artifact_size,
                }
            )

        if include_containers:
            name = _record_label(f"outer{outer_index:02d}", outer) + ".payload.bin"
            destination = containers_dir / name
            destination.write_bytes(outer_payload)

        def payload_for(record: Record) -> bytes:
            if record.offset == outer.offset:
                return outer_payload
            local = record.payload_offset - outer.payload_offset
            return outer_payload[local : local + record.payload_size]

        def visit(record: Record, label: str) -> None:
            record_payload = payload_for(record)
            entries = parse_directory(record, record_payload)
            if include_containers and record.offset != outer.offset:
                destination = containers_dir / (_record_label(label, record) + ".payload.bin")
                destination.write_bytes(record_payload)
            if not entries:
                destination = leaves_dir / (_record_label(label, record) + ".bin")
                destination.write_bytes(record_payload)
                extracted.append(
                    {
                        "path": str(destination.relative_to(output_dir)),
                        "kind": "record-payload",
                        "record_offset": record.offset,
                        "size": len(record_payload),
                    }
                )
                return

            for entry in entries:
                nested = nested_by_offset.get(entry.file_offset)
                child_label = f"{label}_child{entry.index:03d}"
                if nested is not None and nested.end <= entry.file_offset + entry.size:
                    visit(nested, child_label)
                    continue
                local = entry.record_relative_offset - HEADER_SIZE
                data = record_payload[local : local + entry.size]
                name = (
                    f"{child_label}_addr{entry.address:08x}_off{entry.file_offset:08x}"
                    f"_size{entry.size:x}.bin"
                )
                destination = leaves_dir / name
                destination.write_bytes(data)
                extracted.append(
                    {
                        "path": str(destination.relative_to(output_dir)),
                        "kind": "directory-image",
                        "parent_record_offset": record.offset,
                        "address": entry.address,
                        "file_offset": entry.file_offset,
                        "size": entry.size,
                    }
                )

        visit(outer, f"outer{outer_index:02d}")

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def print_info(manifest: dict[str, object]) -> None:
    print(
        f"{manifest['source']}: {manifest['size']} bytes, SHA-256 {manifest['sha256']}"
    )
    print("descramble: decoded[i] = stored[perm[i]] ^ 0xff in each 16-byte payload block")
    print(f"perm: {manifest['descramble_permutation']}")
    print("\nOuter records:")
    for item in manifest["outer_records"]:  # type: ignore[index]
        print(
            f"  [{item['index']}] off={item['offset_hex']} total={item['total_size_hex']} "
            f"load={item['load_address_hex']} payload={item['payload_size_hex']} "
            f"ver={item['version_hex']} comp={item['component_hex']} "
            f"field18={item['metadata_18_hex']} trailer={item['trailer_value_hex']} "
            f"checksum={item['integrity']['status']} dir={len(item['directory'])}"
        )
    print("\nNested records:")
    for item in manifest["nested_records"]:  # type: ignore[index]
        framing = (
            f" framing={item['framing']}" if item["framing"] != "standard" else ""
        )
        print(
            f"  outer={item['outer_index']}[{item['nested_index']}] "
            f"off={item['offset_hex']} total={item['total_size_hex']} "
            f"load={item['load_address_hex']} comp={item['component_hex']} "
            f"field18={item['metadata_18_hex']} trailer={item['trailer_value_hex']} "
            f"checksum={item['integrity']['status']} "
            f"dir={len(item['directory'])}{framing}"
        )
    print("\nHigh-confidence decoded artifacts:")
    for item in manifest["artifacts"]:  # type: ignore[index]
        suffix = f" size={item['size_hex']}" if "size_hex" in item else ""
        machine = f" machine={item['machine_hex']}" if "machine_hex" in item else ""
        print(f"  {item['offset_hex']} {item['kind']}{suffix}{machine}")


def verify_roundtrip(path: Path) -> None:
    source_hash = hashlib.sha256()
    roundtrip_hash = hashlib.sha256()
    outer_checks = 0
    with path.open("rb") as source:
        records = read_outer_records(path)
        cursor = 0
        for record in records:
            source.seek(record.offset)
            header = source.read(HEADER_SIZE)
            source_hash.update(header)
            roundtrip_hash.update(header)
            record_sum = sum_le32(header)
            cursor += len(header)
            remaining = record.payload_size
            while remaining:
                amount = min(4 * 1024 * 1024, remaining)
                raw = source.read(amount)
                if len(raw) != amount:
                    raise FormatError(f"short payload at {source.tell():#x}")
                source_hash.update(raw)
                plain = descramble(raw)
                record_sum = sum_le32(plain, record_sum)
                restored = scramble(plain)
                if restored != raw:
                    raise FormatError(f"round-trip mismatch in record at {record.offset:#x}")
                roundtrip_hash.update(restored)
                remaining -= amount
                cursor += amount
            trailer = source.read(HEADER_SIZE)
            source_hash.update(trailer)
            roundtrip_hash.update(trailer)
            record_sum = sum_le32(trailer, record_sum)
            if record_sum != 0:
                raise FormatError(
                    f"decoded additive checksum mismatch at {record.offset:#x}: "
                    f"sum={record_sum:#010x}"
                )
            outer_checks += 1
            cursor += len(trailer)
    if cursor != path.stat().st_size:
        raise FormatError("round-trip verifier did not consume the whole file")
    if source_hash.digest() != roundtrip_hash.digest():
        raise FormatError("round-trip SHA-256 mismatch")

    nested_checks = 0
    for outer, payload in iter_decoded_payloads(path):
        for nested in find_nested_records(payload, outer.payload_offset, magic=outer.magic):
            if nested.trailer_value == 0:
                continue
            start = nested.offset - outer.payload_offset
            end = nested.end - outer.payload_offset
            total = sum_le32(payload[start:end])
            if total != 0:
                raise FormatError(
                    f"decoded nested checksum mismatch at {nested.offset:#x}: "
                    f"sum={total:#010x}"
                )
            nested_checks += 1
    print(
        f"OK: {cursor} bytes; decode+encode is byte-exact; "
        f"{outer_checks} outer + {nested_checks} present nested checksums valid; "
        f"SHA-256 {source_hash.hexdigest()}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    info_parser = subparsers.add_parser("info", help="show outer/nested map and signatures")
    info_parser.add_argument("input", type=Path)
    info_parser.add_argument("--json", action="store_true", help="emit JSON")
    info_parser.add_argument(
        "--json-output", type=Path, help="also write the JSON analysis to this path"
    )

    decode_parser = subparsers.add_parser("decode", help="descramble outer payloads")
    decode_parser.add_argument("input", type=Path)
    decode_parser.add_argument("output", type=Path)

    encode_parser = subparsers.add_parser(
        "encode", help="apply the inverse transform and refresh present checksums"
    )
    encode_parser.add_argument("input", type=Path)
    encode_parser.add_argument("output", type=Path)

    extract_parser = subparsers.add_parser(
        "extract", help="extract non-overlapping leaf images and write manifest.json"
    )
    extract_parser.add_argument("input", type=Path)
    extract_parser.add_argument("output_dir", type=Path)
    extract_parser.add_argument(
        "--containers",
        action="store_true",
        help="also write decoded outer/nested payload containers (uses more space)",
    )

    verify_parser = subparsers.add_parser("verify", help="verify framing and exact round-trip")
    verify_parser.add_argument("input", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "info":
            manifest = analyze(args.input)
            if args.json_output:
                args.json_output.parent.mkdir(parents=True, exist_ok=True)
                args.json_output.write_text(
                    json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
                )
            if args.json:
                json.dump(manifest, sys.stdout, indent=2)
                print()
            elif not args.json_output:
                print_info(manifest)
        elif args.command == "decode":
            transform_file(args.input, args.output, DECODE_PERM)
        elif args.command == "encode":
            encode_file(args.input, args.output)
        elif args.command == "extract":
            manifest = extract(args.input, args.output_dir, args.containers)
            print(
                f"wrote {len(manifest['extracted'])} leaf images, "
                f"{len(manifest['extracted_artifacts'])} bounded artifacts, and "
                f"{args.output_dir / 'manifest.json'}"
            )
        elif args.command == "verify":
            verify_roundtrip(args.input)
        else:  # pragma: no cover - argparse enforces a known command
            raise AssertionError(args.command)
    except (FormatError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
