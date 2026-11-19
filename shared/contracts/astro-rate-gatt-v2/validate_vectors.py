#!/usr/bin/env python3
"""Validate the portable Astro Rate GATT v2 contract and vectors."""

from __future__ import annotations

import json
import math
from pathlib import Path
import struct
import sys
import uuid


ROOT = Path(__file__).resolve().parent
SCHEMA_PATH = ROOT / "schema.json"
GOLDEN_PATH = ROOT / "vectors" / "golden.json"
INVALID_PATH = ROOT / "vectors" / "invalid.json"


class ContractError(ValueError):
    pass


class CameraAdmissionError(ContractError):
    def __init__(self, reason: str, att_status: int) -> None:
        super().__init__(reason)
        self.att_status = att_status


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
        schema.get("schema") == "olympus-astro-rate-gatt-v2-contract/1",
        "schema",
        "unknown contract schema",
    )
    require(schema.get("byte_order") == "little-endian", "schema", "byte order")
    wanted_uuids = {
        "service": "7e6d7a50-2c56-4f17-9c51-9a5f64b02440",
        "rate": "7e6d7a50-2c56-4f17-9c51-9a5f64b02441",
    }
    require(schema.get("uuids") == wanted_uuids, "schema", "UUID registry")
    for name, value in wanted_uuids.items():
        require(str(uuid.UUID(value)) == value, "schema", f"UUID {name}")

    gatt = schema.get("gatt")
    require(isinstance(gatt, dict), "schema", "missing GATT description")
    require(gatt.get("service_handle_budget") == 4, "schema", "handle budget")
    rate_gatt = gatt.get("rate")
    require(isinstance(rate_gatt, dict), "schema", "missing RATE characteristic")
    require(rate_gatt.get("properties") == ["write"], "schema", "RATE properties")
    require(rate_gatt.get("property_bits") == 8, "schema", "RATE property bits")
    require(rate_gatt.get("permissions") == ["write"], "schema", "RATE permissions")
    require(rate_gatt.get("permission_bits") == 16, "schema", "RATE permission bits")
    require(
        rate_gatt.get("write_procedure") == "att_write_request",
        "schema",
        "RATE write procedure",
    )
    require(rate_gatt.get("readable") is False, "schema", "RATE must be write-only")
    require(rate_gatt.get("cccd") is False, "schema", "RATE must not have a CCCD")

    expected_runtime_service_set = {
        "stock_olympus_service_is_discovery_anchor": True,
        "required_star_contract": "../star-gatt/schema.json",
        "required_star_service_uuid": "7e6d7a50-2c56-4f17-9c51-9a5f64b02450",
        "required_rate_service_uuid": wanted_uuids["service"],
        "legacy_extension_services_registered": False,
        "legacy_runtime_ingress_supported": False,
    }
    require(
        schema.get("runtime_service_set") == expected_runtime_service_set,
        "schema",
        "runtime service set",
    )
    star_schema = load_json(
        (ROOT / expected_runtime_service_set["required_star_contract"]).resolve()
    )
    require(
        star_schema.get("schema") == "olympus-star-gatt-contract/1",
        "schema",
        "required STAR contract",
    )
    require(
        star_schema.get("uuids", {}).get("star_service")
        == expected_runtime_service_set["required_star_service_uuid"],
        "schema",
        "required STAR service UUID",
    )

    constants = schema.get("constants")
    require(isinstance(constants, dict), "schema", "missing constants")
    exact_constants = {'value_size': 18,
     'legacy_rate_unit_rad_per_second': 1e-07,
     'rate_fraction_bits': 16,
     'rate_scale': 65536,
     'rate_unit_rad_per_second': 1.52587890625e-12,
     'legacy_rate_min': -2917,
     'legacy_rate_max': 2917,
     'rate_min': -191168512,
     'rate_max': 191168512,
     'rate_limit_hex': '0x0b650000',
     'gave_offset_min': 0,
     'gave_offset_max': 0,
     'ttl_ticks_min': 1,
     'ttl_ticks_max': 65535,
     'integrated_ttl_ticks': 4095,
     'integrated_renewal_ms': 1000,
     'nominal_hook_hz': 2000,
     'att_status': {'success': 0,
                    'read_not_permitted': 2,
                    'request_not_supported': 6,
                    'invalid_offset': 7,
                    'invalid_attribute_length': 13,
                    'unlikely_error': 14,
                    'value_not_allowed': 19}}
    require(constants == exact_constants, "schema", "constant registry")
    require(
        constants["rate_unit_rad_per_second"]
        == constants["legacy_rate_unit_rad_per_second"] / constants["rate_scale"],
        "schema",
        "Q16.16 physical unit",
    )
    require(
        constants["rate_max"]
        == constants["legacy_rate_max"] << constants["rate_fraction_bits"],
        "schema",
        "positive Q16.16 bound",
    )
    require(
        constants["rate_min"]
        == constants["legacy_rate_min"] << constants["rate_fraction_bits"],
        "schema",
        "negative Q16.16 bound",
    )

    layout = schema.get("layout")
    require(isinstance(layout, list) and len(layout) == 6, "schema", "RATE layout")
    expected_layout = [{'name': 'yaw_rate_q16',
      'offset': 0,
      'type': 'i32le',
      'minimum': -191168512,
      'maximum': 191168512,
      'unit_rad_per_second': 1.52587890625e-12,
      'representation': 'signed_q16_16_legacy_counts',
      'positive_observed_content_motion': 'right'},
     {'name': 'pitch_rate_q16',
      'offset': 4,
      'type': 'i32le',
      'minimum': -191168512,
      'maximum': 191168512,
      'unit_rad_per_second': 1.52587890625e-12,
      'representation': 'signed_q16_16_legacy_counts',
      'positive_observed_content_motion': 'up'},
     {'name': 'roll_rate_q16',
      'offset': 8,
      'type': 'i32le',
      'minimum': -191168512,
      'maximum': 191168512,
      'unit_rad_per_second': 1.52587890625e-12,
      'representation': 'signed_q16_16_legacy_counts',
      'positive_observed_content_motion': 'counter_clockwise'},
     {'name': 'ttl_ticks',
      'offset': 12,
      'type': 'u16le',
      'minimum': 1,
      'maximum': 65535,
      'semantic': 'raw_firmware_hook_ticks_not_milliseconds'},
     {'name': 'gave0_offset',
      'offset': 14,
      'type': 'i16le',
      'minimum': 0,
      'maximum': 0,
      'representation': 'reserved_zero_unsupported_gave'},
     {'name': 'gave1_offset',
      'offset': 16,
      'type': 'i16le',
      'minimum': 0,
      'maximum': 0,
      'representation': 'reserved_zero_unsupported_gave'}]
    require(layout == expected_layout, "schema", "RATE layout fields")

    expected_gave = {'supported': False,
     'admitted_minimum': 0,
     'admitted_maximum': 0,
     'nonzero_usage': 'rejected_value_not_allowed',
     'integrated_correction_offsets': [0, 0],
     'main_hooks_installed': 0,
     'forwarded_to_cpu0': False,
     'exact_cancel_offsets': [0, 0]}
    require(schema.get("gave_offsets") == expected_gave, "schema", "GAVE offsets")

    expected_focal = {'source': 'latest_coherent_star_state',
     'source_field': 'focal_length_binary32',
     'source_field_semantic': 'focus_breathed_flact_mm',
     'source_snapshot': 'cached_worker_idle_hot_sampler',
     'nominal_sample_hz': 10,
     'required_state_for_initial_or_idle_replacement': {'state_valid': True,
                                                        'worker': 'running',
                                                        'operation': 'idle',
                                                        'staged_catalog': False},
     'sample_on_initial_or_idle_replacement': True,
     'capture_update_focal_policy': {'latest_valid_cached_star_focal_preferred': True,
                                     'worker_idle_and_operation_gate_bypassed': True,
                                     'current_connection_last_valid_fallback': True,
                                     'fallback_cleared_on_peer_detach': True,
                                     'no_current_or_fallback_result': 'reject',
                                     'no_current_or_fallback_att_status': 14},
     'caller_provided': False,
     'zero_or_no_coherent_state': {'result': 'reject', 'att_status': 14},
     'encoding_into_internal_mailbox': 'floor(mm * 32) used by Linux conversion; not transmitted',
     'internal_uq11_5_min': 1,
     'internal_uq11_5_max': 31368,
     'present_finite_out_of_range': {'result': 'reject', 'att_status': 19},
     'exact_cancel_bypasses_gate': True}
    require(
        schema.get("camera_focal_admission") == expected_focal,
        "schema",
        "camera focal admission",
    )

    expected_capture_active_update = {'eligible_operations': ['capture_pending', 'capture_stream'],
     'same_connected_peer_required': True,
     'preexisting_active_publication_required': False,
     'prior_public_value_comparison': False,
     'all_otherwise_valid_rate_fields_may_change': True,
     'ttl_change_allowed': True,
     'mailbox_publication': 'convert_candidate_body_with_selected_cached_focal_and_queue_native_command',
     'accepted_candidate_replaces_retained_active_value': True,
     'client_renews_latest_att_acknowledged_value': True,
     'exact_cancel_allowed': True,
     'exact_cancel_requires_active_publication': False,
     'cancel_clears_retained_active_value': True,
     'detach_worker_loss_shutdown_clear_retained_active_value': True,
     'same_connection_focal_fallback_survives_cancel_and_worker_loss': True,
     'peer_detach_clears_same_connection_focal_fallback': True,
     'write_without_att_response_is_ignored': True,
     'server_timer': False,
     'client_integrated_ttl_ticks': 4095,
     'client_integrated_renewal_ms': 1000,
     'mailbox_body_immutable_until_consumed': True,
     'cpu0_per_axis_increment_read_coherent': False,
     'cpu0_whole_vector_snapshot_atomic': False,
     'cpu0_refresh_torn_reads_accepted': True}
    require(
        schema.get("capture_active_update") == expected_capture_active_update,
        "schema",
        "capture active update",
    )

    expected_mailbox = {'name': 'native_command_mailbox',
     'public_gatt_surface': False,
     'value_size': 20,
     'struct_format': '<IIIhhhH',
     'linux_physical_payload_start': '0x2a2985fa4',
     'linux_physical_payload_end': '0x2a2985fb7',
     'linux_physical_length': '0x2a2985fb8',
     'main_uncached_payload_start': '0xa2985fa4',
     'main_uncached_payload_end': '0xa2985fb7',
     'main_uncached_length': '0xa2985fb8',
     'length_offset': 20,
     'length_type': 'u8',
     'empty_length': 0,
     'rate_body_length': 20,
     'preposition_body_length': 14,
     'in_flight_body_immutable': True,
     'staged_preposition_occupies_mailbox': False,
     'layout': [{'name': 'fractional_increment', 'offset': 0, 'type': 'u32le', 'count': 3},
                {'name': 'whole_increment', 'offset': 12, 'type': 'i16le', 'count': 3},
                {'name': 'ttl_ticks', 'offset': 18, 'type': 'u16le'}]}
    require(schema.get("internal_mailbox") == expected_mailbox, "schema", "mailbox20 layout")
    require(
        struct.calcsize(expected_mailbox["struct_format"].removesuffix(">")) == 20,
        "schema",
        "mailbox20 size",
    )

    expected_main_to_cpu0 = {'opcode': 55,
     'transaction_size': 21,
     'payload_size': 20,
     'payload_source': 'native_command_mailbox_body',
     'payload_layout': 'three_u32_fractions_three_i16_wholes_u16_ttl',
     'gave_offsets_forwarded': False,
     'cpu0_requires_exact_transaction_size': True,
     'cpu0_copies_increment_bytes_before_ttl': 18,
     'cpu0_commits_ttl_u16_last': True}
    require(
        schema.get("main_to_cpu0") == expected_main_to_cpu0,
        "schema",
        "main-to-CPU0 TX21 contract",
    )

    expected_semantics = {'active_requires_camera_live_focal': True,
     'gatt_carries_focal': False,
     'exact_cancel_hex': '000000000000000000000000000000000000',
     'idle_active_value_replaces_prior_publication': True,
     'publisher_waits_for_prior_ready_clear': True,
     'publication_is_end_to_end_retirement_barrier': False,
     'service_startup_publishes_cancel': True,
     'active_peer_detach_publishes_cancel': True,
     'active_worker_or_focal_authority_loss_publishes_cancel': True,
     'active_service_shutdown_publishes_cancel': True,
     'lifecycle_cancel_publication_is_best_effort': True,
     'att_success': 'validated_and_queued_for_main_mailbox',
     'att_success_does_not_prove': ['main_opcode_37_serialization',
                                    'cpu0_admission',
                                    'prior_correction_retirement',
                                    'sensor_motion'],
     'application_sequence': False,
     'application_checksum': False,
     'application_acknowledgement': False,
     'application_authentication_field': False,
     'active_requires_any_nonzero_body': True}
    require(schema.get("semantics") == expected_semantics, "schema", "publication semantics")

    return constants


def decode_value(value: bytes, constants: dict) -> dict:
    if len(value) != constants["value_size"]:
        raise ContractError("value_length")
    yaw, pitch, roll, ttl, gave0, gave1 = struct.unpack("<iiiHhh", value)
    decoded = {
        "cancel": value == bytes(constants["value_size"]),
        "yaw_rate_q16": yaw,
        "pitch_rate_q16": pitch,
        "roll_rate_q16": roll,
        "ttl_ticks": ttl,
        "gave0_offset": gave0,
        "gave1_offset": gave1,
    }
    if decoded["cancel"]:
        return decoded
    for name, component in (
        ("yaw_rate_q16", yaw),
        ("pitch_rate_q16", pitch),
        ("roll_rate_q16", roll),
    ):
        if component < constants["rate_min"] or component > constants["rate_max"]:
            raise ContractError(name)
    for name, component in (("gave0_offset", gave0), ("gave1_offset", gave1)):
        if (
            component < constants["gave_offset_min"]
            or component > constants["gave_offset_max"]
        ):
            raise ContractError(name)
    if yaw == 0 and pitch == 0 and roll == 0 and gave0 == 0 and gave1 == 0:
        raise ContractError("active_lanes")
    if not constants["ttl_ticks_min"] <= ttl <= constants["ttl_ticks_max"]:
        raise ContractError("ttl_ticks")
    return decoded



def camera_focal_for_publication(
    decoded: dict,
    schema: dict,
    live_focal_mm: float | None,
) -> int:
    """Apply the camera-only focal gate and return the internal UQ11.5 word."""

    focal = schema["camera_focal_admission"]
    if decoded["cancel"]:
        return 0
    if live_focal_mm is None or live_focal_mm == 0 or not math.isfinite(live_focal_mm):
        raise CameraAdmissionError(
            "camera_live_focal_unavailable",
            focal["zero_or_no_coherent_state"]["att_status"],
        )
    scaled = live_focal_mm * 32
    if not math.isfinite(scaled):
        raise CameraAdmissionError(
            "camera_live_focal_out_of_range",
            focal["present_finite_out_of_range"]["att_status"],
        )
    raw = math.floor(scaled)
    if not focal["internal_uq11_5_min"] <= raw <= focal["internal_uq11_5_max"]:
        raise CameraAdmissionError(
            "camera_live_focal_out_of_range",
            focal["present_finite_out_of_range"]["att_status"],
        )
    return raw


def encode_internal_mailbox(decoded: dict, focal_uq11_5: int) -> bytes:
    """Convert admitted public rates to the private native Q32 command body."""

    if decoded["cancel"]:
        if focal_uq11_5 != 0:
            raise ContractError("cancel_focal")
        return bytes(20)
    if not 1 <= focal_uq11_5 <= 31368:
        raise ContractError("mailbox_focal")
    rates = [decoded[name] for name in ("yaw_rate_q16", "pitch_rate_q16", "roll_rate_q16")]
    gain = -(23 * focal_uq11_5 + ((30694 * focal_uq11_5 + 32768) >> 16))
    roll_product = rates[2] * -192253
    roll_step = (abs(roll_product) + 1024) // 2048
    if roll_product < 0:
        roll_step = -roll_step
    steps = [rates[0] * gain, rates[1] * gain, roll_step]
    return struct.pack("<IIIhhhH", *(step & 0xffffffff for step in steps),
                       *(step // (1 << 32) for step in steps), decoded["ttl_ticks"])



def validate_corpus(document: dict, wanted_schema: str) -> list[dict]:
    require(document.get("schema") == wanted_schema, "corpus", "unknown corpus schema")
    require(document.get("contract_schema") == "../schema.json", "corpus", "schema link")
    vectors = document.get("vectors")
    require(isinstance(vectors, list), "corpus", "vectors must be an array")
    identifiers = [vector.get("id") for vector in vectors if isinstance(vector, dict)]
    require(len(identifiers) == len(vectors), "corpus", "vector must be an object with id")
    require(len(set(identifiers)) == len(identifiers), "corpus", "duplicate vector id")
    return vectors


def validate() -> dict[str, int]:
    schema = load_json(SCHEMA_PATH)
    constants = validate_schema(schema)
    golden = validate_corpus(
        load_json(GOLDEN_PATH),
        "olympus-astro-rate-gatt-v2-golden-vectors/1",
    )
    invalid = validate_corpus(
        load_json(INVALID_PATH),
        "olympus-astro-rate-gatt-v2-invalid-vectors/1",
    )
    for vector in golden:
        require(vector.get("direction") == "phone_to_camera", vector["id"], "direction")
        require(vector.get("characteristic") == "rate", vector["id"], "characteristic")
        try:
            value = bytes.fromhex(vector.get("hex", ""))
        except ValueError as error:
            raise ContractError(f'{vector["id"]}: invalid hex') from error
        decoded = decode_value(value, constants)
        require(decoded == vector.get("expect"), vector["id"], "decoded expectation")
        gave_is_nonzero = bool(decoded["gave0_offset"] or decoded["gave1_offset"])
        if gave_is_nonzero:
            require(
                vector.get("gave_usage") == schema["gave_offsets"]["nonzero_usage"],
                vector["id"],
                "nonzero GAVE usage",
            )
        else:
            require("gave_usage" not in vector, vector["id"], "unexpected GAVE usage")
        require(
            vector.get("kind") == ("cancel" if decoded["cancel"] else "active"),
            vector["id"],
            "kind",
        )
    for vector in invalid:
        try:
            value = bytes.fromhex(vector.get("hex", ""))
        except ValueError as error:
            raise ContractError(f'{vector["id"]}: invalid hex') from error
        try:
            decode_value(value, constants)
        except ContractError as error:
            require(str(error) == vector.get("expected_error"), vector["id"], "error")
        else:
            raise ContractError(f'{vector["id"]}: invalid vector was accepted')

    return {"golden_vectors": len(golden), "invalid_vectors": len(invalid)}


def main() -> int:
    try:
        counts = validate()
    except (ContractError, OSError, json.JSONDecodeError, struct.error) as error:
        print(f"Astro Rate GATT v2 validation failed: {error}", file=sys.stderr)
        return 1
    print(
        "Astro Rate GATT v2 contract valid: "
        f'{counts["golden_vectors"]} golden vectors, '
        f'{counts["invalid_vectors"]} invalid vectors'
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
