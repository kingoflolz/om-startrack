#!/usr/bin/env python3
"""Portable validation for gyro attitude v1; no camera or Android dependencies."""

from __future__ import annotations

import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent
FORMAT = struct.Struct("<BBHIII8i")


class ContractError(ValueError):
    pass


def decode_value(value: bytes) -> dict:
    if len(value) != 48:
        raise ContractError("length")
    version, flags, reserved, tick, sequence, capture_tick, *quaternions = FORMAT.unpack(value)
    if version != 1:
        raise ContractError("version")
    if flags & ~7:
        raise ContractError("flags")
    if reserved:
        raise ContractError("reserved")
    if flags & 4 and not flags & 2:
        raise ContractError("preparation_without_capture")
    for flag, offset, name in ((1, 0, "current"), (2, 4, "capture")):
        if flags & flag:
            norm_squared = sum((lane / 2**30) ** 2 for lane in quaternions[offset:offset + 4])
            if not 0.98**2 <= norm_squared <= 1.02**2:
                raise ContractError(name + "_norm")
    return {
        "flags": flags, "tick": tick,
        "capture_sequence": sequence, "capture_tick": capture_tick,
        "current_q30": quaternions[:4], "capture_q30": quaternions[4:],
    }


def encode_value(value: dict) -> bytes:
    encoded = FORMAT.pack(1, value["flags"], 0, value["tick"],
                          value["capture_sequence"], value["capture_tick"],
                          *value["current_q30"], *value["capture_q30"])
    decode_value(encoded)
    return encoded


def validate() -> tuple[int, int]:
    schema = json.loads((ROOT / "schema.json").read_text())
    assert schema["schema"] == "olympus-gyro-attitude-v1-contract/1"
    assert schema["byte_order"] == "little-endian"
    assert schema["container"]["separate_characteristic"] is False
    assert schema["container"]["contract"] == "../star-gatt/schema.json"
    assert schema["constants"] == {
        "version": 1, "value_size": FORMAT.size, "nominal_tick_hz": 2000,
        "quaternion_fraction_bits": 30, "quaternion_norm_min": 0.98,
        "quaternion_norm_max": 1.02, "known_flags": 7,
    }
    assert [(f["name"], f["offset"], f["type"], f.get("count", 1))
            for f in schema["layout"]] == [
        ("version", 0, "u8", 1), ("flags", 1, "u8", 1),
        ("reserved", 2, "u16le", 1), ("tick", 4, "u32le", 1),
        ("capture_sequence", 8, "u32le", 1), ("capture_tick", 12, "u32le", 1),
        ("current_q30", 16, "i32le", 4), ("capture_q30", 32, "i32le", 4),
    ]
    golden = json.loads((ROOT / "vectors/golden.json").read_text())["vectors"]
    invalid = json.loads((ROOT / "vectors/invalid.json").read_text())["vectors"]
    names = [vector["name"] for vector in golden + invalid]
    assert len(names) == len(set(names)), "duplicate vector names"
    for vector in golden:
        wire = bytes.fromhex(vector["hex"])
        assert decode_value(wire) == vector["expected"], vector["name"]
        assert encode_value(vector["expected"]) == wire, vector["name"]
    for vector in invalid:
        try:
            decode_value(bytes.fromhex(vector["hex"]))
        except ContractError as error:
            assert str(error) == vector["error"], vector["name"]
        else:
            raise AssertionError("accepted invalid vector " + vector["name"])
    return len(golden), len(invalid)


if __name__ == "__main__":
    counts = validate()
    print(f"Gyro attitude v1 valid: {counts[0]} golden, {counts[1]} invalid packets")
