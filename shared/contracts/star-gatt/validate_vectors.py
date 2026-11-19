#!/usr/bin/env python3
"""Validate the portable STAR GATT contract and canonical vectors."""

from __future__ import annotations

import json
import importlib.util
import math
from pathlib import Path
import struct
import sys
import uuid


ROOT = Path(__file__).resolve().parent
SCHEMA_PATH = ROOT / "schema.json"
GOLDEN_PATH = ROOT / "vectors" / "golden.json"
INVALID_PATH = ROOT / "vectors" / "invalid.json"

VERSION = 6
STATE_WRITE_SIZE = 27
STATE_FIXED_SIZE = 125
STATE_MAX_SIZE = 157
UNAVAILABLE_INDEX = 0xFF

FLAG_CATALOG = 0x01
FLAG_ACTUAL = 0x02
FLAG_RECIPE = 0x04
FLAG_DETECTOR = 0x08
FLAG_IBIS = 0x10
FLAG_ACCELERATION = 0x20
FLAG_RECOVERY_TARGET = 0x80
STATE_KNOWN_FLAGS = 0xBF

IBIS_SOURCE_MAPPED = 0x01
IBIS_MIRROR_ENABLED = 0x02
IBIS_DOUBLE_READ_STABLE = 0x04
IBIS_OBSERVED_CHANGE = 0x08
IBIS_LENS_COMMAND_SOURCE_MAPPED = 0x10
IBIS_LENS_COMMAND_SNAPSHOT_VALID = 0x20
IBIS_KNOWN = 0x3F

ACCEL_SOURCE_MAPPED = 0x01
ACCEL_LAYOUT_QUALIFIED = 0x02
ACCEL_SNAPSHOT_COHERENT = 0x04
ACCEL_SOURCE_FRESH = 0x08
ACCEL_KNOWN = 0x0F

class ContractError(ValueError):
    pass


class AdmissionError(ContractError):
    def __init__(self, reason: str, att_status: int) -> None:
        super().__init__(reason)
        self.att_status = att_status


_spec = importlib.util.spec_from_file_location("gyro_contract", ROOT.parent / "gyro-attitude-v1/validate_vectors.py")
_attitude = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_attitude)


def require(condition: bool, context: str, detail: str) -> None:
    if not condition:
        raise ContractError(f"{context}: {detail}")


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as source:
        value = json.load(source)
    require(isinstance(value, dict), str(path), "root must be an object")
    return value


def validate_schema(schema: dict) -> dict:
    require(
        schema.get("schema") == "olympus-star-gatt-contract/1",
        "schema",
        "unknown schema identifier",
    )
    require(schema.get("byte_order") == "little-endian", "schema", "byte order")
    wanted_uuids = {
        "star_service": "7e6d7a50-2c56-4f17-9c51-9a5f64b02450",
        "control": "7e6d7a50-2c56-4f17-9c51-9a5f64b02451",
        "state": "7e6d7a50-2c56-4f17-9c51-9a5f64b02452",
        "shutter_catalog": "7e6d7a50-2c56-4f17-9c51-9a5f64b02453",
        "aperture_catalog": "7e6d7a50-2c56-4f17-9c51-9a5f64b02454",
        "iso_catalog": "7e6d7a50-2c56-4f17-9c51-9a5f64b02455",
        "lease": "7e6d7a50-2c56-4f17-9c51-9a5f64b02456",
        "cccd": "00002902-0000-1000-8000-00805f9b34fb",
    }
    require(schema.get("uuids") == wanted_uuids, "schema", "UUID registry")
    for name, value in wanted_uuids.items():
        require(str(uuid.UUID(value)) == value, "schema", f"UUID {name}")

    gatt = schema.get("gatt", {})
    require(gatt.get("service_handle_budget") == 16, "schema", "handle budget")
    require(gatt.get("minimum_att_mtu") == 160, "schema", "minimum ATT MTU")
    require(gatt.get("preferred_att_mtu") == 517, "schema", "preferred ATT MTU")
    require(gatt.get("attribute_value_max") == 512, "schema", "attribute maximum")
    require(
        gatt.get("state", {}).get("read_unavailable_att_status") is None,
        "schema",
        "STATE unavailable reads must succeed",
    )
    require(
        gatt.get("catalog", {}).get("unavailable_value_hex") == "00",
        "schema",
        "catalog unavailable encoding",
    )

    constants = schema.get("constants", {})
    exact_constants = {
        "api_version": 6,
        "state_read_version": 7,
        "state_prefix_size": 77,
        "attitude_size": 48,
        "state_write_size": 27,
        "state_fixed_size": 125,
        "state_min_size": 125,
        "state_max_size": 157,
        "lens_name_max": 32,
        "catalog_count_max": 128,
        "catalog_value_max_size": 512,
        "catalog_unavailable_count": 0,
        "unavailable_index": 255,
        "telemetry_period_ms": 100,
        "notification_max_hz": 10,
    }
    for name, value in exact_constants.items():
        require(constants.get(name) == value, "schema", f"constant {name}")
    require(
        constants["state_max_size"] + 3 == gatt["minimum_att_mtu"],
        "schema",
        "maximum STATE must exactly fit minimum MTU",
    )

    wanted_state_flags = {
        "catalog_available": FLAG_CATALOG,
        "actual_available": FLAG_ACTUAL,
        "recipe_available": FLAG_RECIPE,
        "detector_available": FLAG_DETECTOR,
        "ibis_available": FLAG_IBIS,
        "acceleration_available": FLAG_ACCELERATION,
        "recovery_target": FLAG_RECOVERY_TARGET,
        "known_mask": STATE_KNOWN_FLAGS,
    }
    require(schema.get("state_flags") == wanted_state_flags, "schema", "state flags")
    require(
        schema.get("ibis_flags")
        == {
            "q10_source_mapped": IBIS_SOURCE_MAPPED,
            "q10_mirror_enabled": IBIS_MIRROR_ENABLED,
            "q10_double_read_stable": IBIS_DOUBLE_READ_STABLE,
            "q10_observed_change_since_enable": IBIS_OBSERVED_CHANGE,
            "lens_command_source_mapped": IBIS_LENS_COMMAND_SOURCE_MAPPED,
            "lens_command_snapshot_valid": IBIS_LENS_COMMAND_SNAPSHOT_VALID,
            "known_mask": IBIS_KNOWN,
        },
        "schema",
        "IBIS flags",
    )
    state_layout = schema.get("layouts", {}).get("state")
    wanted_offsets = {
        "version": 0,
        "flags": 1,
        "publication_generation": 2,
        "config_generation": 6,
        "catalog_generation": 10,
        "worker_generation": 14,
        "actual_native": 18,
        "actual_indices": 24,
        "recipe_indices": 27,
        "aperture_bounds": 30,
        "focal_length_binary32": 32,
        "detector": 36,
        "ibis_flags": 48,
        "ibis_sequence": 49,
        "ibis_q10": 51,
        "lens_command_generation": 57,
        "lens_movestep_raw": 61,
        "acceleration_flags": 65,
        "acceleration_main_generation": 66,
        "acceleration_mg": 70,
        "lens_length": 76,
        "lens": 77,
        "gyro_attitude": "77 + lens_length",
    }
    require(isinstance(state_layout, list), "schema", "STATE layout")
    require(
        {field["name"]: field["offset"] for field in state_layout} == wanted_offsets,
        "schema",
        "STATE offsets",
    )
    write_layout = schema.get("layouts", {}).get("state_write")
    require(
        {field["name"]: field["offset"] for field in write_layout}
        == {
            "version": 0,
            "expected_config_generation": 1,
            "expected_catalog_generation": 5,
            "live_target_indices": 9,
            "recipe_indices": 12,
            "detector": 15,
        },
        "schema",
        "STATE write offsets",
    )

    coherence = schema.get("catalog_coherence", {})
    require(coherence.get("catalog_values_carry_generation") is False,
            "schema", "catalog generation placement")
    require(
        coherence.get("read_fence")
        == ["state_a", "shutter_catalog", "aperture_catalog", "iso_catalog", "state_b"],
        "schema",
        "catalog read fence",
    )
    implementation = schema.get("current_implementation", {})
    require(implementation == {
        "lens_gave_correction_supported": False,
        "lens_movestep_supported": False,
        "lens_movestep_main_publisher": False,
        "lens_movestep_linux_sampling": False,
        "lens_command_source_mapped": False,
        "lens_command_snapshot_valid": False,
        "lens_command_generation": 0,
        "lens_movestep_raw": [0, 0],
        "body_q10_validity_independent": True,
        "lens_packet_fields_validated": True,
    }, "schema", "current lens stub policy")
    lens_command = schema.get("lens_command_snapshot", {})
    require(lens_command.get("runtime_scale_available_from_qualified_source") is False,
            "schema", "lens command runtime scale qualification")
    require(
        lens_command.get("canonical_invalid")
        == {"snapshot_valid": False, "generation": 0, "lanes": [0, 0]},
        "schema",
        "canonical invalid lens command snapshot",
    )
    require(lens_command.get("experimental") is False,
            "schema", "qualified lens command mirror")
    require(lens_command.get("freshness_or_attachment_guaranteed") is False,
            "schema", "lens command freshness limitation")
    require(lens_command.get("coherence_guaranteed") is True,
            "schema", "lens command mirror coherence")
    require(lens_command.get("main_uncached_visibility_to_linux") is True,
            "schema", "lens command uncached visibility")
    require(lens_command.get("source_writer") ==
            "patched_MAIN_LensInfo_callback",
            "schema", "lens command source writer")
    require(lens_command.get("invalidated_on_lens_detach_or_error") is False,
            "schema", "lens command detach limitation")
    require(
        lens_command.get("retains_last_success_across_detach_or_read_error")
        is True,
        "schema",
        "lens command publish-only retention",
    )
    require(
        lens_command.get("generation")
        == {
            "source": "main_uncached_mirror_callback_commit",
            "initial": 2,
            "increment": 2,
            "advance_on": ["every_patched_LensInfo_callback_publication"],
            "same_pair_reuses_generation": False,
            "wraps_to": 2,
            "stock_callback_publication_counter": True,
        },
        "schema",
        "MAIN lens command generation",
    )
    require(
        lens_command.get("uncached_main_mirror")
        == {
            "main_uncached": "0xa0047af0",
            "linux_physical": "0x2a0047af0",
            "size": 16,
            "magic_offset": 0,
            "magic_u32le": "0x31564f4d",
            "magic_bytes_ascii": "MOV1",
            "generation_offset": 4,
            "generation_type": "u32le_seqlock",
            "packed_movestep_offset": 8,
            "scale_offset": 12,
            "scale_type": "u16le_required_zero",
            "reserved_offset": 14,
            "reserved_type": "u16le_required_zero",
            "erased_boot_hex": "ffffffffffffffffffffffffffffffff",
            "erased_boot_valid": False,
            "acceptance": "exact_magic_before_and_after_same_nonzero_even_generation_around_one_packed_pair_read_and_zero_tail",
            "transient_changed_magic_or_generation_or_odd_generation_retains_previous_snapshot": True,
            "stable_structural_invalidity_clears_public_snapshot": True,
            "passive_linux_sampling_period_ms": 100,
            "linux_sampling_does_not_issue_lens_requests": True,
        },
        "schema",
        "uncached MAIN lens command mirror",
    )
    require(
        schema.get("surface_policy", {}).get("requires_current_service") is True,
        "schema",
        "current service required",
    )
    return schema


def detector_valid(words: tuple[int, ...] | list[int]) -> bool:
    if len(words) != 6:
        return False
    sigma, minimum, maximum, elongation, sharpness, border = words
    return (
        1 <= sigma <= 16384
        and 1 <= minimum <= maximum <= 0xFFFF
        and (elongation == 0 or 256 <= elongation <= 0xFFFF)
        and 0 <= sharpness <= 256
        and 0 <= border <= 1943
    )


def decode_catalog(value: bytes) -> dict:
    require(1 <= len(value) <= 512, "catalog", "length outside 1..512")
    count = value[0]
    require(count <= 128, "catalog", "count exceeds 128")
    if count == 0:
        require(len(value) == 1, "catalog", "unavailable catalog has trailing bytes")
        return {"available": False, "labels": []}
    labels: list[str] = []
    offset = 1
    for _ in range(count):
        require(offset < len(value), "catalog", "truncated label length")
        length = value[offset]
        offset += 1
        require(1 <= length <= 31, "catalog", "label length outside 1..31")
        require(offset + length <= len(value), "catalog", "truncated label")
        raw = value[offset : offset + length]
        require(all(0x21 <= byte <= 0x7E for byte in raw),
                "catalog", "label is not canonical ASCII")
        labels.append(raw.decode("ascii"))
        offset += length
    require(offset == len(value), "catalog", "trailing bytes")
    require(len(set(labels)) == len(labels), "catalog", "duplicate label")
    return {"available": True, "labels": labels}


def catalog_bank_is_coherent(state_a: bytes, state_b: bytes) -> bool:
    """Return whether two STATE fence reads authorize one catalog bank."""
    first = decode_state(state_a)
    second = decode_state(state_b)
    first_available = bool(first["flags"] & FLAG_CATALOG)
    second_available = bool(second["flags"] & FLAG_CATALOG)
    generation = first["catalog_generation"]
    return (
        first_available
        and second_available
        and generation != 0
        and generation == second["catalog_generation"]
    )


def _get_u16(value: bytes, offset: int) -> int:
    return int.from_bytes(value[offset : offset + 2], "little")


def _get_i16(value: bytes, offset: int) -> int:
    return int.from_bytes(value[offset : offset + 2], "little", signed=True)


def _get_u32(value: bytes, offset: int) -> int:
    return int.from_bytes(value[offset : offset + 4], "little")


def _validate_focal(bits: int) -> None:
    if bits == 0:
        return
    focal = struct.unpack("<f", bits.to_bytes(4, "little"))[0]
    require(math.isfinite(focal) and 0.1 <= focal <= 2047.96875,
            "STATE", "focal is not canonical")


def _validate_ibis(
    flags: int,
    sequence: int,
    lanes: tuple[int, int, int],
    lens_generation: int,
    lens_movestep_raw: tuple[int, int],
) -> None:
    require((flags & ~IBIS_KNOWN) == 0, "STATE", "IBIS flags")
    source = bool(flags & IBIS_SOURCE_MAPPED)
    enabled = bool(flags & IBIS_MIRROR_ENABLED)
    stable = bool(flags & IBIS_DOUBLE_READ_STABLE)
    changed = bool(flags & IBIS_OBSERVED_CHANGE)
    lens_source = bool(flags & IBIS_LENS_COMMAND_SOURCE_MAPPED)
    lens_valid = bool(flags & IBIS_LENS_COMMAND_SNAPSHOT_VALID)
    require(not enabled or source, "STATE", "IBIS enabled without source")
    require(not stable or (source and enabled), "STATE", "IBIS stable implication")
    require(not changed or (source and enabled), "STATE", "IBIS change implication")
    require(all(-1024 <= lane <= 1024 for lane in lanes), "STATE", "IBIS Q10 range")
    if not source:
        require((flags & 0x0F) == 0 and sequence == 0 and lanes == (0, 0, 0),
                "STATE", "unavailable Q10 sub-block")
    require(not lens_valid or lens_source,
            "STATE", "lens command valid without source")
    if lens_valid:
        require(lens_generation != 0 and lens_generation % 2 == 0,
                "STATE", "lens command generation")
    else:
        require(lens_generation == 0 and lens_movestep_raw == (0, 0),
                "STATE", "invalid lens command sub-block")


def _validate_acceleration(flags: int, main_generation: int) -> None:
    require((flags & ~ACCEL_KNOWN) == 0, "STATE", "acceleration flags")
    source = bool(flags & ACCEL_SOURCE_MAPPED)
    layout = bool(flags & ACCEL_LAYOUT_QUALIFIED)
    coherent = bool(flags & ACCEL_SNAPSHOT_COHERENT)
    fresh = bool(flags & ACCEL_SOURCE_FRESH)
    require(not layout or source, "STATE", "acceleration layout implication")
    require(not coherent or layout, "STATE", "acceleration coherent implication")
    require(not fresh or coherent, "STATE", "acceleration fresh implication")
    require(not coherent or main_generation != 0,
            "STATE", "coherent acceleration has zero generation")


def decode_state(value: bytes) -> dict:
    require(STATE_FIXED_SIZE <= len(value) <= STATE_MAX_SIZE,
            "STATE", "length outside 125..157")
    require(value[0] == 7, "STATE", "unsupported version")
    lens_length = value[76]
    require(lens_length <= 32, "STATE", "lens length exceeds 32")
    require(len(value) == STATE_FIXED_SIZE + lens_length,
            "STATE", "length does not match lens length")
    lens_raw = value[77:77 + lens_length]
    require(all(0x20 <= byte <= 0x7E for byte in lens_raw),
            "STATE", "lens is not printable ASCII")

    try:
        gyro = _attitude.decode_value(value[77 + lens_length:])
    except _attitude.ContractError as error:
        raise ContractError("STATE: attitude") from error

    flags = value[1]
    actual_native = tuple(_get_i16(value, offset) for offset in (18, 20, 22))
    actual_indices = tuple(value[24:27])
    recipe_indices = tuple(value[27:30])
    aperture_bounds = tuple(value[30:32])
    focal_bits = _get_u32(value, 32)
    detector = tuple(_get_u16(value, offset) for offset in range(36, 48, 2))
    ibis_flags = value[48]
    ibis_sequence = _get_u16(value, 49)
    ibis_q10 = tuple(_get_i16(value, offset) for offset in (51, 53, 55))
    lens_command_generation = _get_u32(value, 57)
    lens_movestep_raw = tuple(_get_i16(value, offset) for offset in (61, 63))
    acceleration_flags = value[65]
    acceleration_main_generation = _get_u32(value, 66)
    acceleration_mg = tuple(_get_i16(value, offset) for offset in (70, 72, 74))
    catalog_generation = _get_u32(value, 10)
    worker_generation = _get_u32(value, 14)

    catalog_available = bool(flags & FLAG_CATALOG)
    actual_available = bool(flags & FLAG_ACTUAL)
    recipe_available = bool(flags & FLAG_RECIPE)
    detector_available = bool(flags & FLAG_DETECTOR)
    ibis_available = bool(flags & FLAG_IBIS)
    acceleration_available = bool(flags & FLAG_ACCELERATION)

    require((flags & ~STATE_KNOWN_FLAGS) == 0,
            "STATE", "state flags contain reserved bits")

    require(catalog_available == (catalog_generation != 0),
            "STATE", "catalog flag/generation mismatch")
    if actual_available:
        require(catalog_available, "STATE", "actual settings without catalog")
        require(worker_generation != 0, "STATE", "actual settings without worker")
        require(all(index <= 127 for index in actual_indices),
                "STATE", "actual index outside 0..127")
        require(all(index <= 127 for index in aperture_bounds),
                "STATE", "aperture bound outside 0..127")
        require(aperture_bounds[0] <= actual_indices[1] <= aperture_bounds[1],
                "STATE", "actual aperture outside bounds")
        _validate_focal(focal_bits)
    else:
        require(actual_native == (0, 0, 0), "STATE", "unavailable actual native")
        require(actual_indices == (255, 255, 255), "STATE", "unavailable actual indices")
        require(aperture_bounds == (255, 255), "STATE", "unavailable aperture bounds")
        require(focal_bits == 0, "STATE", "unavailable focal")
        require(lens_length == 0, "STATE", "unavailable actual lens")
    if recipe_available:
        require(catalog_available, "STATE", "recipe without catalog")
        require(all(index <= 127 for index in recipe_indices),
                "STATE", "recipe index outside 0..127")
        if actual_available:
            require(aperture_bounds[0] <= recipe_indices[1] <= aperture_bounds[1],
                    "STATE", "recipe aperture outside bounds")
    else:
        require(recipe_indices == (255, 255, 255),
                "STATE", "unavailable recipe indices")
    if detector_available:
        require(detector_valid(detector), "STATE", "invalid detector")
    else:
        require(detector == (0, 0, 0, 0, 0, 0),
                "STATE", "unavailable detector")
    if flags & FLAG_RECOVERY_TARGET:
        require(catalog_available and actual_available,
                "STATE", "recovery target lacks actual catalog state")

    _validate_ibis(ibis_flags, ibis_sequence, ibis_q10,
                   lens_command_generation, lens_movestep_raw)
    require(ibis_available == bool(ibis_flags &
                                   (IBIS_SOURCE_MAPPED |
                                    IBIS_LENS_COMMAND_SOURCE_MAPPED)),
            "STATE", "IBIS availability mismatch")

    _validate_acceleration(acceleration_flags, acceleration_main_generation)
    require(acceleration_available == bool(acceleration_flags & ACCEL_SOURCE_MAPPED),
            "STATE", "acceleration availability mismatch")
    if not acceleration_available:
        require(acceleration_flags == 0 and acceleration_main_generation == 0
                and acceleration_mg == (0, 0, 0),
                "STATE", "unavailable acceleration block")

    complete_config = actual_available and recipe_available and detector_available
    config_generation = _get_u32(value, 6)
    require(complete_config == (config_generation != 0),
            "STATE", "config generation/completeness mismatch")

    return {
        "gyro_attitude": gyro,
        "version": 7,
        "flags": flags,
        "publication_generation": _get_u32(value, 2),
        "config_generation": config_generation,
        "catalog_generation": catalog_generation,
        "worker_generation": worker_generation,
        "actual_native": actual_native,
        "actual_indices": actual_indices,
        "recipe_indices": recipe_indices,
        "aperture_bounds": aperture_bounds,
        "focal_bits": focal_bits,
        "detector": detector,
        "ibis_flags": ibis_flags,
        "ibis_sequence": ibis_sequence,
        "ibis_q10": ibis_q10,
        "lens_command_generation": lens_command_generation,
        "lens_movestep_raw": lens_movestep_raw,
        "acceleration_flags": acceleration_flags,
        "acceleration_main_generation": acceleration_main_generation,
        "acceleration_mg": acceleration_mg,
        "lens": lens_raw.decode("ascii"),
    }


def encode_state(fields: dict) -> bytes:
    lens = fields.get("lens", "").encode("ascii")
    require(len(lens) <= 32, "STATE", "lens length exceeds 32")
    value = bytearray(STATE_FIXED_SIZE + len(lens))
    value[0] = 7
    value[1] = fields.get("flags", 0)
    for offset, name in (
        (2, "publication_generation"),
        (6, "config_generation"),
        (10, "catalog_generation"),
        (14, "worker_generation"),
        (32, "focal_bits"),
        (57, "lens_command_generation"),
        (66, "acceleration_main_generation"),
    ):
        value[offset : offset + 4] = int(fields.get(name, 0)).to_bytes(4, "little")
    for offset, item in zip((18, 20, 22), fields.get("actual_native", (0, 0, 0))):
        value[offset : offset + 2] = int(item).to_bytes(2, "little", signed=True)
    value[24:27] = bytes(fields.get("actual_indices", (255, 255, 255)))
    value[27:30] = bytes(fields.get("recipe_indices", (255, 255, 255)))
    value[30:32] = bytes(fields.get("aperture_bounds", (255, 255)))
    for offset, item in zip(range(36, 48, 2), fields.get("detector", (0,) * 6)):
        value[offset : offset + 2] = int(item).to_bytes(2, "little")
    value[48] = fields.get("ibis_flags", 0)
    value[49:51] = int(fields.get("ibis_sequence", 0)).to_bytes(2, "little")
    for offset, item in zip((51, 53, 55), fields.get("ibis_q10", (0, 0, 0))):
        value[offset : offset + 2] = int(item).to_bytes(2, "little", signed=True)
    for offset, item in zip((61, 63), fields.get("lens_movestep_raw", (0, 0))):
        value[offset : offset + 2] = int(item).to_bytes(2, "little", signed=True)
    value[65] = fields.get("acceleration_flags", 0)
    for offset, item in zip((70, 72, 74), fields.get("acceleration_mg", (0, 0, 0))):
        value[offset : offset + 2] = int(item).to_bytes(2, "little", signed=True)
    value[76] = len(lens)
    value[77:77 + len(lens)] = lens
    value[77 + len(lens)] = 1
    encoded = bytes(value)
    decode_state(encoded)
    return encoded


def decode_state_write(value: bytes) -> dict:
    require(len(value) == STATE_WRITE_SIZE, "STATE write", "length must be 27")
    require(value[0] == VERSION, "STATE write", "unsupported version")
    config_generation = _get_u32(value, 1)
    catalog_generation = _get_u32(value, 5)
    require(config_generation != 0, "STATE write", "zero config generation")
    require(catalog_generation != 0, "STATE write", "zero catalog generation")
    live = tuple(value[9:12])
    recipe = tuple(value[12:15])
    require(all(index <= 127 for index in live),
            "STATE write", "live index outside 0..127")
    require(all(index <= 127 for index in recipe),
            "STATE write", "recipe index outside 0..127")
    detector = tuple(_get_u16(value, offset) for offset in range(15, 27, 2))
    require(detector_valid(detector), "STATE write", "invalid detector")
    return {
        "version": VERSION,
        "expected_config_generation": config_generation,
        "expected_catalog_generation": catalog_generation,
        "live_target_indices": live,
        "recipe_indices": recipe,
        "detector": detector,
    }


def encode_state_write(fields: dict) -> bytes:
    value = bytearray(STATE_WRITE_SIZE)
    value[0] = VERSION
    value[1:5] = int(fields["expected_config_generation"]).to_bytes(4, "little")
    value[5:9] = int(fields["expected_catalog_generation"]).to_bytes(4, "little")
    value[9:12] = bytes(fields["live_target_indices"])
    value[12:15] = bytes(fields["recipe_indices"])
    for offset, item in zip(range(15, 27, 2), fields["detector"]):
        value[offset : offset + 2] = int(item).to_bytes(2, "little")
    encoded = bytes(value)
    decode_state_write(encoded)
    return encoded


def admit_state_write(value: bytes, current: dict) -> dict:
    decoded = decode_state_write(value)
    if current.get("busy") or current.get("recovery_target"):
        raise AdmissionError("STATE write: operation busy", 0x80)
    if (
        decoded["expected_config_generation"] != current.get("config_generation")
        or decoded["expected_catalog_generation"] != current.get("catalog_generation")
    ):
        raise AdmissionError("STATE write: stale generation", 0x80)
    counts = current.get("catalog_counts")
    if not isinstance(counts, (tuple, list)) or len(counts) != 3:
        raise AdmissionError("STATE write: catalog unavailable", 0x80)
    for indices in (decoded["live_target_indices"], decoded["recipe_indices"]):
        if any(index >= count for index, count in zip(indices, counts)):
            raise AdmissionError("STATE write: index outside current catalog", 0x13)
    bounds = current.get("aperture_bounds")
    if not isinstance(bounds, (tuple, list)) or len(bounds) != 2:
        raise AdmissionError("STATE write: aperture bounds unavailable", 0x80)
    for name in ("live_target_indices", "recipe_indices"):
        aperture = decoded[name][1]
        if not bounds[0] <= aperture <= bounds[1]:
            raise AdmissionError("STATE write: aperture outside current bounds", 0x13)
    return decoded


def decode_lease(value: bytes) -> int:
    require(len(value) == 2, "LEASE", "length must be two")
    seconds = _get_u16(value, 0)
    require(seconds == 0 or 1 <= seconds <= 120, "LEASE", "TTL outside 0..120")
    return seconds


def _hex(value: str, context: str) -> bytes:
    try:
        return bytes.fromhex(value)
    except ValueError as error:
        raise ContractError(f"{context}: invalid hex") from error


def validate_golden(vectors: dict) -> int:
    require(vectors.get("schema") == "olympus-star-gatt-golden/1",
            "golden", "schema")
    count = 0
    for item in vectors.get("catalogs", []):
        decoded = decode_catalog(_hex(item["hex"], item["name"]))
        require(decoded == item["decoded"], item["name"], "decoded catalog")
        count += 1
    for item in vectors.get("states", []):
        decoded = decode_state(_hex(item["hex"], item["name"]))
        for name, expected in item["expected"].items():
            actual = decoded[name]
            if isinstance(actual, tuple):
                actual = list(actual)
            require(actual == expected, item["name"], f"STATE field {name}")
        count += 1
    for item in vectors.get("writes", []):
        value = _hex(item["hex"], item["name"])
        decoded = admit_state_write(value, item["current"])
        for name, expected in item["expected"].items():
            actual = decoded[name]
            if isinstance(actual, tuple):
                actual = list(actual)
            require(actual == expected, item["name"], f"write field {name}")
        count += 1
    for item in vectors.get("leases", []):
        require(decode_lease(_hex(item["hex"], item["name"])) == item["seconds"],
                item["name"], "lease seconds")
        count += 1
    return count


def validate_invalid(vectors: dict) -> int:
    require(vectors.get("schema") == "olympus-star-gatt-invalid/1",
            "invalid", "schema")
    count = 0
    decoders = {
        "catalog": decode_catalog,
        "state": decode_state,
        "state_write": decode_state_write,
        "lease": decode_lease,
    }
    for item in vectors.get("values", []):
        try:
            decoders[item["kind"]](_hex(item["hex"], item["name"]))
        except ContractError as error:
            require(item["error"] in str(error), item["name"], "error classification")
        else:
            raise ContractError(f"{item['name']}: invalid value accepted")
        count += 1
    for item in vectors.get("admission", []):
        try:
            admit_state_write(_hex(item["hex"], item["name"]), item["current"])
        except AdmissionError as error:
            require(error.att_status == item["att_status"], item["name"], "ATT status")
            require(item["error"] in str(error), item["name"], "admission error")
        else:
            raise ContractError(f"{item['name']}: invalid admission accepted")
        count += 1
    return count


def main() -> int:
    try:
        validate_schema(load_json(SCHEMA_PATH))
        golden_count = validate_golden(load_json(GOLDEN_PATH))
        invalid_count = validate_invalid(load_json(INVALID_PATH))
    except (ContractError, KeyError, TypeError, OverflowError) as error:
        print(f"STAR GATT contract invalid: {error}", file=sys.stderr)
        return 1
    print(
        f"STAR GATT contract valid: {golden_count} golden vectors, "
        f"{invalid_count} invalid vectors"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
