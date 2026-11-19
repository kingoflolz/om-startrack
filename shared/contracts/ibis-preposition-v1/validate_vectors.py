#!/usr/bin/env python3
"""Validate the runtime-neutral IBIS pre-position contract and wire vectors."""

from __future__ import annotations

import json
from pathlib import Path
import struct
import sys
import uuid


ROOT = Path(__file__).resolve().parent
SCHEMA_PATH = ROOT / "schema.json"
GOLDEN_PATH = ROOT / "vectors" / "golden.json"
INVALID_PATH = ROOT / "vectors" / "invalid.json"
FIELDS = ("x_counts", "y_counts", "roll_counts", "action")


class ContractError(ValueError):
    """A schema or public wire value violates the contract."""


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ContractError(reason)


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    require(isinstance(value, dict), f"{path}: root must be an object")
    return value


def validate_schema(schema: dict) -> dict:
    require(schema.get("schema") == "olympus-ibis-preposition-v1-contract/1", "schema_version")
    require(schema.get("byte_order") == "little-endian", "byte_order")
    uuids = {
        "service": "7e6d7a50-2c56-4f17-9c51-9a5f64b02440",
        "preposition": "7e6d7a50-2c56-4f17-9c51-9a5f64b02442",
    }
    require(schema.get("uuids") == uuids, "uuids")
    for value in uuids.values():
        require(str(uuid.UUID(value)) == value, "uuid_encoding")
    require(schema.get("layout") == [
        {"name": "x_counts", "offset": 0, "type": "i16le"},
        {"name": "y_counts", "offset": 2, "type": "i16le"},
        {"name": "roll_counts", "offset": 4, "type": "i16le"},
        {"name": "action", "offset": 6, "type": "u16le"},
    ], "layout")
    require(schema.get("actions") == {
        "cancel": 0, "stage_next_star_capture": 1, "apply_during_app_half_press": 2,
    }, "actions")
    constants = schema.get("constants", {})
    for key, expected in {
        "value_size": 8,
        "struct_format": "<hhhH",
        "main_consumption_timeout_ms": 1000,
        "settle_delay_ms": 750,
        "nominal_control_hz": 2000,
    }.items():
        require(constants.get(key) == expected, f"constants.{key}")
    policy = schema.get("app_policy", {})
    for key, expected in {
        "planar_limit_counts": 2546,
        "planar_radius_counts": 2546,
        "roll_limit_counts": 5325,
        "coupled_planar_base_counts": 2700,
        "coupled_x_roll_coefficient": 1628,
        "coupled_y_roll_coefficient": 1571,
        "coupled_roll_divisor": 32768,
        "owner": "android_app",
        "firmware_enforces_app_geometry": False,
        "capability_autodetection": False,
    }.items():
        require(policy.get(key) == expected, f"app_policy.{key}")
    require(schema.get("admission", {}).get("native_representation_constraint") ==
            "all converted native axes and their negations fit signed int32", "native_representation_constraint")
    gatt = schema.get("gatt", {})
    require(gatt.get("write_procedure") == "att_write_request", "gatt.write_procedure")
    require(gatt.get("properties") == ["write"], "gatt.properties")
    require(gatt.get("readable") is False and gatt.get("cccd") is False, "gatt.write_only")
    mailbox = schema.get("internal_mailbox", {})
    for key, expected in {
        "struct_format": "<iiiH", "value_size": 14, "allocation_size": 21,
        "length_type": "u8", "published_body_length": 14, "publication_tag": 0x5051,
        "linux_physical_payload_start": "0x2a2985fa4",
        "linux_physical_payload_end": "0x2a2985fb1",
        "linux_physical_length": "0x2a2985fb8",
        "main_uncached_payload_start": "0xa2985fa4",
        "main_uncached_payload_end": "0xa2985fb1",
        "main_uncached_length": "0xa2985fb8",
        "staged_intent_is_linux_only": True,
        "in_flight_body_immutable_until_consumed": True,
        "coordination_linux_physical": "0x2a2985fbc", "coordination_type": "u8",
    }.items():
        require(mailbox.get(key) == expected, f"mailbox.{key}")
    require(mailbox.get("coordination_states") == {
        "empty": 0, "published": 1, "staged": 2, "requested": 3,
        "consumed": 4, "cancelled": 5,
    }, "mailbox.coordination_states")
    carrier = schema.get("main_to_cpu0", {})
    for key, expected in {
        "opcode": 0x37, "transaction_size": 15, "payload_size": 14,
        "publication_tag": 0x5051, "astro_rate_transaction_size": 21,
        "cpu0_copies_native_bias_bytes_before_tag": 12, "cpu0_commits_tag_u16_last": True,
    }.items():
        require(carrier.get(key) == expected, f"main_to_cpu0.{key}")
    lifecycle = schema.get("lifecycle", {})
    for key, expected in {
        "cpu0_capture_entry": "retain_active_offset_on_mode_3_to_1_and_retire_pending_command",
        "cpu0_capture_exit": "clear_offset_before_stock_exit_processing",
        "cpu0_offset_application": "add_before_native_integration_and_output_then_subtract_from_updated_accumulator",
        "cpu0_preposition_producer_bounds": {
            "detector_modes": [1, 3],
            "requires_nonzero_active_offset": True,
            "planar_B4_counts": 16384,
            "all_axis_B98_native": 1879048192,
            "restored_from_stock_each_tick": True,
        },
        "cpu0_preposition_geometry": "stock_coupled_builder_while_PRE_guard_active",
        "cpu0_software_slew": False,
        "cpu0_capture_commands_change_active_offset": False,
        "cpu0_pending_snapshot_atomic": False,
        "cpu0_pending_publication_races_accepted": True,
        "worker_coordination_atomic_compare_exchange": False,
    }.items():
        require(lifecycle.get(key) == expected, f"lifecycle.{key}")
    return constants


def decode_value(value: bytes, constants: dict) -> dict:
    """Decode a public PREPOSITION write; travel policy is enforced by the app."""
    require(len(value) == constants["value_size"], "value_length")
    decoded = dict(zip(FIELDS, struct.unpack(constants["struct_format"], value)))
    x, y, roll, action = (decoded[name] for name in FIELDS)
    require(action in (0, 1, 2), "action")
    if action == 0:
        require(x == 0 and y == 0 and roll == 0, "cancel_axes")
    for field, native in zip(FIELDS, native_axes(decoded)):
        require(-(1 << 31) < native < (1 << 31), f"{field}_native_overflow")
    return decoded


def app_target_allowed(decoded: dict, policy: dict) -> bool:
    """The app's coupled still-travel policy, separate from valid wire values."""
    x, y, roll = (decoded[name] for name in FIELDS[:3])
    radius = policy["planar_radius_counts"]
    if (abs(x) > policy["planar_limit_counts"] or
            abs(y) > policy["planar_limit_counts"] or
            abs(roll) > policy["roll_limit_counts"] or
            x * x + y * y > radius * radius):
        return False
    return all(abs(component) <= policy["coupled_planar_base_counts"] -
               abs(roll) * coefficient // policy["coupled_roll_divisor"]
               for component, coefficient in (
                   (x, policy["coupled_x_roll_coefficient"]),
                   (y, policy["coupled_y_roll_coefficient"])))


def native_axes(decoded: dict) -> list[int]:
    axes = (decoded["x_counts"], decoded["y_counts"], decoded["roll_counts"])
    # Python floor division makes -((-n)//d) an exact signed ceiling.
    return [-((-axis * scale) // divisor) for axis, scale, divisor in
            zip(axes, (1 << 27, 1 << 27, 1 << 18), (1757, 1757, 4295))]


def encode_mailbox(decoded: dict) -> tuple[bytes, int]:
    """Return the eventual native body and published length; staging stays in Linux."""
    return struct.pack("<iiiH", *native_axes(decoded), 0x5051), 14

def encode_cpu0_transaction(decoded: dict) -> bytes:
    """Build the transaction MAIN emits when a validated command becomes ready."""
    return b"\x37" + encode_mailbox(decoded)[0]


def validate() -> dict:
    schema = load_json(SCHEMA_PATH)
    constants = validate_schema(schema)
    golden = load_json(GOLDEN_PATH)
    invalid = load_json(INVALID_PATH)
    require(golden.get("schema") == "olympus-ibis-preposition-v1-golden-vectors/1", "golden_schema")
    require(invalid.get("schema") == "olympus-ibis-preposition-v1-invalid-vectors/1", "invalid_schema")
    seen = set()
    for vector in golden["vectors"]:
        require(vector["id"] not in seen, "duplicate_vector_id")
        seen.add(vector["id"])
        value = bytes.fromhex(vector["hex"])
        decoded = decode_value(value, constants)
        require(decoded == vector["expect"], f"{vector['id']}: decoded value")
        require(app_target_allowed(decoded, schema["app_policy"]) == vector["app_admitted"],
                f"{vector['id']}: app travel policy")
        require(struct.pack("<hhhH", *(decoded[name] for name in FIELDS)) == value,
                f"{vector['id']}: public re-encoding")
        mailbox, length = encode_mailbox(decoded)
        require(mailbox.hex() == vector["mailbox_hex"], f"{vector['id']}: mailbox")
        require(length == vector["mailbox_length"], f"{vector['id']}: length")
        require(encode_cpu0_transaction(decoded).hex() == vector["cpu0_tx_hex"],
                f"{vector['id']}: CPU0 transaction")
    for vector in invalid["vectors"]:
        require(vector["id"] not in seen, "duplicate_vector_id")
        seen.add(vector["id"])
        try:
            decode_value(bytes.fromhex(vector["hex"]), constants)
        except ContractError as error:
            require(str(error) == vector["expected_error"], f"{vector['id']}: wrong rejection {error}")
        else:
            raise ContractError(f"{vector['id']}: invalid value accepted")
    return {"golden_vectors": len(golden["vectors"]), "invalid_vectors": len(invalid["vectors"])}


if __name__ == "__main__":
    try:
        print(json.dumps(validate(), sort_keys=True))
    except (ContractError, KeyError, ValueError) as error:
        print(f"IBIS pre-position contract failed: {error}", file=sys.stderr)
        sys.exit(1)
