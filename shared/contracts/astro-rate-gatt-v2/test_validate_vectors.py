#!/usr/bin/env python3
"""Unit tests for the portable Astro Rate GATT v2 contract validator."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import struct
import unittest


MODULE_PATH = Path(__file__).with_name("validate_vectors.py")
SPEC = importlib.util.spec_from_file_location("astro_rate_gatt_v2_vectors", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class AstroRateGattV2ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = contract.load_json(contract.SCHEMA_PATH)
        cls.constants = contract.validate_schema(cls.schema)

    def test_checked_in_positive_and_negative_corpora(self) -> None:
        self.assertEqual(
            contract.validate(),
            {"golden_vectors": 6, "invalid_vectors": 16},
        )

    def test_high_resolution_equator_vector_is_exact_little_endian(self) -> None:
        decoded = contract.decode_value(
            bytes.fromhex("dbc926fd0000000000000000ff0f00000000"),
            self.constants,
        )
        self.assertEqual(decoded["yaw_rate_q16"], -47_789_605)
        self.assertEqual(decoded["ttl_ticks"], 4095)
        self.assertAlmostEqual(
            decoded["yaw_rate_q16"]
            * self.constants["rate_unit_rad_per_second"],
            -7.2921150e-5,
            delta=self.constants["rate_unit_rad_per_second"] / 2,
        )


    def test_only_eighteen_zero_bytes_cancel(self) -> None:
        self.assertTrue(contract.decode_value(bytes(18), self.constants)["cancel"])
        with self.assertRaisesRegex(contract.ContractError, "active_lanes"):
            contract.decode_value(
                bytes.fromhex("000000000000000000000000010000000000"),
                self.constants,
            )

    def test_component_and_ttl_bounds_are_inclusive(self) -> None:
        decoded = contract.decode_value(
            bytes.fromhex("0000650b00009bf4ffffffffffff00000000"),
            self.constants,
        )
        self.assertEqual(decoded["yaw_rate_q16"], 0x0B650000)
        self.assertEqual(decoded["pitch_rate_q16"], -0x0B650000)
        self.assertEqual(decoded["ttl_ticks"], 65_535)
        self.assertEqual(decoded["gave0_offset"], 0)
        self.assertEqual(decoded["gave1_offset"], 0)

    def test_nonzero_gave_is_rejected_even_with_valid_body_rate(self) -> None:
        for gave0, gave1 in ((1, 0), (-1, 0), (0, 1), (0, -1), (64, -64)):
            for yaw in (0, 1):
                with self.assertRaisesRegex(contract.ContractError, "gave[01]_offset"):
                    contract.decode_value(struct.pack("<iiiHhh", yaw, 0, 0, 1, gave0, gave1),
                                          self.constants)
        self.assertEqual(self.constants["gave_offset_min"], 0)
        self.assertEqual(self.constants["gave_offset_max"], 0)


    def test_initial_or_idle_replacement_uses_camera_live_focal(self) -> None:
        decoded = contract.decode_value(
            bytes.fromhex("dbc926fd0000000000000000ff0f00000000"),
            self.constants,
        )
        self.assertEqual(
            contract.camera_focal_for_publication(decoded, self.schema, 45.999),
            1471,
        )

        with self.assertRaises(contract.CameraAdmissionError) as unavailable:
            contract.camera_focal_for_publication(decoded, self.schema, None)
        self.assertEqual(str(unavailable.exception), "camera_live_focal_unavailable")
        self.assertEqual(unavailable.exception.att_status, 0x0E)

        with self.assertRaises(contract.CameraAdmissionError) as zero:
            contract.camera_focal_for_publication(decoded, self.schema, 0.0)
        self.assertEqual(str(zero.exception), "camera_live_focal_unavailable")
        self.assertEqual(zero.exception.att_status, 0x0E)

        with self.assertRaises(contract.CameraAdmissionError) as out_of_range:
            contract.camera_focal_for_publication(decoded, self.schema, 980.28125)
        self.assertEqual(
            str(out_of_range.exception), "camera_live_focal_out_of_range"
        )
        self.assertEqual(out_of_range.exception.att_status, 0x13)

    def test_cancel_bypasses_camera_live_focal_gate_and_zeroes_mailbox20(self) -> None:
        decoded = contract.decode_value(bytes(18), self.constants)
        focal = contract.camera_focal_for_publication(decoded, self.schema, None)
        self.assertEqual(focal, 0)
        self.assertEqual(contract.encode_internal_mailbox(decoded, focal), bytes(20))

    def test_native_q32_body_has_fraction_whole_and_ttl_layout(self) -> None:
        decoded = contract.decode_value(
            bytes.fromhex("dbc926fd0000000000000000ff0f00000000"), self.constants)
        mailbox = contract.encode_internal_mailbox(decoded, 1440)
        self.assertEqual(mailbox.hex(), "4a809d050000000000000000780100000000ff0f")
        self.assertEqual(struct.unpack("<IIIhhhH", mailbox),
                         (94208074, 0, 0, 376, 0, 0, 4095))
        internal = self.schema["internal_mailbox"]
        self.assertEqual(internal["linux_physical_payload_start"], "0x2a2985fa4")
        self.assertEqual(internal["linux_physical_length"], "0x2a2985fb8")
        self.assertTrue(internal["in_flight_body_immutable"])
        self.assertFalse(internal["staged_preposition_occupies_mailbox"])

    def test_negative_native_step_uses_floor_whole_unsigned_fraction(self) -> None:
        decoded = contract.decode_value(struct.pack("<iiiHhh", 1, -1, 1, 1, 0, 0),
                                        self.constants)
        body = struct.unpack("<IIIhhhH", contract.encode_internal_mailbox(decoded, 1440))
        self.assertEqual(body, (4294933502, 33794, 4294967202, -1, 0, -1, 1))
        self.assertEqual(body[0] + body[3] * (1 << 32), -33794)
        self.assertEqual(body[2] + body[5] * (1 << 32), -94)


    def test_att_success_is_queue_acceptance_not_motion_confirmation(self) -> None:
        semantics = self.schema["semantics"]
        self.assertEqual(semantics["att_success"], "validated_and_queued_for_main_mailbox")
        self.assertIn("cpu0_admission", semantics["att_success_does_not_prove"])
        self.assertIn("sensor_motion", semantics["att_success_does_not_prove"])
        self.assertFalse(semantics["publication_is_end_to_end_retirement_barrier"])
        self.assertTrue(semantics["publisher_waits_for_prior_ready_clear"])


    def test_lifecycle_boundaries_publish_exact_cancel(self) -> None:
        semantics = self.schema["semantics"]
        self.assertEqual(semantics["exact_cancel_hex"], "00" * 18)
        self.assertTrue(semantics["service_startup_publishes_cancel"])
        self.assertTrue(semantics["active_peer_detach_publishes_cancel"])
        self.assertTrue(
            semantics["active_worker_or_focal_authority_loss_publishes_cancel"]
        )
        self.assertTrue(semantics["active_service_shutdown_publishes_cancel"])
        self.assertTrue(semantics["lifecycle_cancel_publication_is_best_effort"])

    def test_capture_updates_accept_any_valid_value_with_cached_focal(self) -> None:
        renewal = self.schema["capture_active_update"]
        self.assertEqual(
            renewal["eligible_operations"], ["capture_pending", "capture_stream"]
        )
        self.assertFalse(renewal["preexisting_active_publication_required"])
        self.assertFalse(renewal["prior_public_value_comparison"])
        self.assertTrue(renewal["all_otherwise_valid_rate_fields_may_change"])
        self.assertTrue(renewal["ttl_change_allowed"])
        self.assertEqual(
            renewal["mailbox_publication"],
            "convert_candidate_body_with_selected_cached_focal_and_queue_native_command",
        )
        self.assertTrue(renewal["accepted_candidate_replaces_retained_active_value"])
        self.assertTrue(renewal["client_renews_latest_att_acknowledged_value"])
        self.assertFalse(renewal["exact_cancel_requires_active_publication"])
        self.assertTrue(renewal["cancel_clears_retained_active_value"])
        self.assertTrue(
            renewal["same_connection_focal_fallback_survives_cancel_and_worker_loss"]
        )
        self.assertTrue(renewal["peer_detach_clears_same_connection_focal_fallback"])
        self.assertFalse(renewal["server_timer"])
        self.assertEqual(renewal["client_integrated_ttl_ticks"], 4095)
        self.assertEqual(renewal["client_integrated_renewal_ms"], 1000)
        self.assertTrue(renewal["mailbox_body_immutable_until_consumed"])
        self.assertFalse(renewal["cpu0_per_axis_increment_read_coherent"])
        self.assertFalse(renewal["cpu0_whole_vector_snapshot_atomic"])
        self.assertTrue(renewal["cpu0_refresh_torn_reads_accepted"])
        focal = self.schema["camera_focal_admission"]["capture_update_focal_policy"]
        self.assertTrue(focal["latest_valid_cached_star_focal_preferred"])
        self.assertTrue(focal["worker_idle_and_operation_gate_bypassed"])
        self.assertTrue(focal["current_connection_last_valid_fallback"])
        self.assertEqual(focal["no_current_or_fallback_att_status"], 0x0E)

    def test_gave_is_stubbed_without_native_hooks(self) -> None:
        gave = self.schema["gave_offsets"]
        self.assertFalse(gave["supported"])
        self.assertEqual(gave["nonzero_usage"], "rejected_value_not_allowed")
        self.assertEqual(gave["main_hooks_installed"], 0)
        self.assertEqual(gave["integrated_correction_offsets"], [0, 0])
        self.assertFalse(gave["forwarded_to_cpu0"])


    def test_main_forwards_exact_tx21_with_ttl_published_last(self) -> None:
        carrier = self.schema["main_to_cpu0"]
        self.assertEqual(carrier["opcode"], 0x37)
        self.assertEqual(carrier["transaction_size"], 21)
        self.assertEqual(carrier["payload_size"], 20)
        self.assertFalse(carrier["gave_offsets_forwarded"])
        self.assertEqual(carrier["cpu0_copies_increment_bytes_before_ttl"], 18)
        self.assertTrue(carrier["cpu0_commits_ttl_u16_last"])



    def test_runtime_service_set_requires_v6_and_v2_without_legacy_ingress(self) -> None:
        service_set = self.schema["runtime_service_set"]
        self.assertEqual(
            service_set["required_star_service_uuid"],
            "7e6d7a50-2c56-4f17-9c51-9a5f64b02450",
        )
        self.assertEqual(
            service_set["required_rate_service_uuid"],
            "7e6d7a50-2c56-4f17-9c51-9a5f64b02440",
        )
        self.assertFalse(service_set["legacy_extension_services_registered"])
        self.assertFalse(service_set["legacy_runtime_ingress_supported"])


if __name__ == "__main__":
    unittest.main()
