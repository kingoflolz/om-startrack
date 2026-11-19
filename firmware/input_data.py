"""Populate reserved custom ELF data from the user's verified firmware.

The checked-in executables contain zero-filled sections instead of copies of
the camera's lookup tables and instruction windows used for runtime checks.
"""
import json
from pathlib import Path
import struct


def populate(binary: bytes, decoded, model: str, program: str) -> bytes:
    ranges = json.loads(Path(__file__).with_name("input_data.json").read_text())[model][program]
    expected_size = sum(record["size"] for record in ranges)
    if len(binary) < 52:
        raise ValueError("truncated custom executable")
    header = struct.unpack_from("<16sHHIIIIIHHHHHH", binary)
    if header[0][:7] != b"\x7fELF\x01\x01\x01" or header[2] != 40 or header[11] != 40:
        raise ValueError("expected ARM ELF32 custom executable")
    if header[6] + 40 * header[12] > len(binary) or header[13] >= header[12]:
        raise ValueError("truncated custom executable section table")
    sections = [struct.unpack_from("<10I", binary, header[6] + 40 * i)
                for i in range(header[12])]
    strings = sections[header[13]]
    names = binary[strings[4]:strings[4] + strings[5]]
    matches = [s for s in sections if names[s[0]:].split(b"\0", 1)[0] == b".omstar_input"]
    if len(matches) != 1:
        raise ValueError("custom executable is missing its input-data section")
    section = matches[0]
    start, size = section[4:6]
    if section[1] != 1 or section[2] != 2 or size != expected_size or start + size > len(binary):
        raise ValueError("invalid input-data section")
    if any(binary[start:start + size]):
        raise ValueError("custom executable already contains input firmware data")
    result = bytearray(binary)
    for record in ranges:
        decoded.seek(record["input_offset"])
        data = decoded.read(record["size"])
        if len(data) != record["size"]:
            raise ValueError("input firmware data range is truncated")
        result[start:start + len(data)] = data
        start += len(data)
    return bytes(result)
