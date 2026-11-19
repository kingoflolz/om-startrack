#!/usr/bin/env python3
"""Focused regression tests for the portable STAR GATT contract."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "star_gatt_validate", ROOT / "validate_vectors.py"
)
assert SPEC is not None and SPEC.loader is not None
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class StarGattContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = contract.load_json(ROOT / "schema.json")
        cls.golden = contract.load_json(ROOT / "vectors" / "golden.json")
        cls.invalid = contract.load_json(ROOT / "vectors" / "invalid.json")
        cls.states = {
            item["name"]: bytes.fromhex(item["hex"])
            for item in cls.golden["states"]
        }

    def test_schema_and_all_portable_vectors(self) -> None:
        contract.validate_schema(self.schema)
        self.assertEqual(contract.validate_golden(self.golden), 11)
        self.assertEqual(contract.validate_invalid(self.invalid), 32)

    def test_state_maximum_size_exactly_fits_minimum_mtu(self) -> None:
        ready = contract.decode_state(self.states["complete_idle_state"])
        ready["lens"] = "X" * 32
        encoded = contract.encode_state(ready)
        self.assertEqual(len(encoded), contract.STATE_MAX_SIZE)
        self.assertEqual(len(encoded) + 3, self.schema["gatt"]["minimum_att_mtu"])

    def test_catalog_fence_accepts_only_same_available_generation(self) -> None:
        before = self.states["complete_idle_state"]
        same_generation = bytearray(before)
        same_generation[2:6] = (11).to_bytes(4, "little")
        changed_generation = bytearray(same_generation)
        changed_generation[10:14] = (8).to_bytes(4, "little")
        unavailable = self.states["cold_start_partial_state"]

        self.assertTrue(
            contract.catalog_bank_is_coherent(before, bytes(same_generation))
        )
        self.assertFalse(contract.catalog_bank_is_coherent(before, bytes(changed_generation)))
        self.assertFalse(contract.catalog_bank_is_coherent(before, unavailable))

    def test_exposure_telemetry_is_absent_and_bit_six_is_reserved(self) -> None:
        self.assertNotIn("exposure_flags", self.schema)
        self.assertNotIn("exposure_timer", self.schema)
        self.assertNotIn(
            "exposure_available", self.schema["state_flags"]
        )
        reserved = bytearray(self.states["complete_idle_state"])
        reserved[1] |= 0x40
        with self.assertRaisesRegex(contract.ContractError, "reserved bits"):
            contract.decode_state(bytes(reserved))

    def test_q10_and_lens_command_validity_are_independent(self) -> None:
        lens_only = contract.decode_state(self.states["lens_command_only_valid"])
        self.assertEqual(lens_only["ibis_flags"], 0x30)
        self.assertEqual(lens_only["ibis_q10"], (0, 0, 0))
        self.assertEqual(lens_only["lens_command_generation"], 2)
        self.assertEqual(lens_only["lens_movestep_raw"], (-32768, 32767))

        q10_only = contract.decode_state(
            self.states["q10_valid_lens_command_mapped_invalid"]
        )
        self.assertEqual(q10_only["ibis_flags"], 0x1F)
        self.assertEqual(q10_only["ibis_q10"], (100, -200, 300))
        self.assertEqual(q10_only["lens_command_generation"], 0)
        self.assertEqual(q10_only["lens_movestep_raw"], (0, 0))

    def test_lens_command_source_is_coherent_uncached_main_mirror(self) -> None:
        source = self.schema["lens_command_snapshot"]
        self.assertFalse(source["experimental"])
        self.assertFalse(source["freshness_or_attachment_guaranteed"])
        self.assertTrue(source["coherence_guaranteed"])
        self.assertTrue(source["main_uncached_visibility_to_linux"])
        mirror = source["uncached_main_mirror"]
        self.assertEqual(mirror["main_uncached"], "0xa0047af0")
        self.assertEqual(mirror["linux_physical"], "0x2a0047af0")
        self.assertEqual(mirror["magic_bytes_ascii"], "MOV1")
        self.assertEqual(
            (mirror["generation_offset"], mirror["packed_movestep_offset"]),
            (4, 8),
        )
        self.assertFalse(source["invalidated_on_lens_detach_or_error"])
        self.assertTrue(
            source["retains_last_success_across_detach_or_read_error"]
        )
        self.assertFalse(mirror["erased_boot_valid"])
        self.assertEqual(mirror["erased_boot_hex"], "ff" * 16)
        self.assertEqual(source["generation"]["source"],
                         "main_uncached_mirror_callback_commit")
        self.assertFalse(source["generation"]["same_pair_reuses_generation"])

    def test_current_lens_stub_preserves_body_q10_and_wire_layout(self) -> None:
        policy = self.schema["current_implementation"]
        self.assertFalse(policy["lens_movestep_main_publisher"])
        self.assertFalse(policy["lens_movestep_linux_sampling"])
        self.assertTrue(policy["lens_packet_fields_validated"])
        fields = contract.decode_state(self.states["q10_valid_lens_command_mapped_invalid"])
        fields["ibis_flags"] &= ~0x30
        fields["lens_command_generation"] = policy["lens_command_generation"]
        fields["lens_movestep_raw"] = policy["lens_movestep_raw"]
        value = contract.encode_state(fields)
        decoded = contract.decode_state(value)
        self.assertEqual(decoded["ibis_flags"], 0x0f)
        self.assertEqual(decoded["ibis_q10"], (100, -200, 300))
        self.assertEqual(value[57:65], bytes(8))
        self.assertEqual(decoded["lens_command_generation"], 0)
        self.assertEqual(decoded["lens_movestep_raw"], (0, 0))

    def test_unavailable_state_is_canonical_and_readable(self) -> None:
        decoded = contract.decode_state(self.states["cold_start_partial_state"])
        self.assertEqual(decoded["actual_indices"], (255, 255, 255))
        self.assertEqual(decoded["recipe_indices"], (255, 255, 255))

    def test_state_write_compare_and_set_admission(self) -> None:
        fields = {
            "expected_config_generation": 3,
            "expected_catalog_generation": 7,
            "live_target_indices": (1, 2, 3),
            "recipe_indices": (4, 2, 5),
            "detector": (1280, 3, 1024, 768, 230, 1),
        }
        encoded = contract.encode_state_write(fields)
        self.assertEqual(len(encoded), contract.STATE_WRITE_SIZE)
        admitted = contract.admit_state_write(
            encoded,
            {
                "config_generation": 3,
                "catalog_generation": 7,
                "catalog_counts": (10, 10, 10),
                "aperture_bounds": (0, 6),
            },
        )
        self.assertEqual(admitted["live_target_indices"], (1, 2, 3))

        with self.assertRaises(contract.AdmissionError) as raised:
            contract.admit_state_write(
                encoded,
                {
                    "config_generation": 4,
                    "catalog_generation": 7,
                    "catalog_counts": (10, 10, 10),
                    "aperture_bounds": (0, 6),
                },
            )
        self.assertEqual(raised.exception.att_status, 0x80)

    def test_catalog_unavailable_and_duplicate_are_distinct(self) -> None:
        self.assertEqual(
            contract.decode_catalog(b"\x00"),
            {"available": False, "labels": []},
        )
        with self.assertRaisesRegex(contract.ContractError, "duplicate label"):
            contract.decode_catalog(bytes.fromhex("0201410141"))

    def test_surface_requires_current_service(self) -> None:
        policy = self.schema["surface_policy"]
        self.assertEqual(
            policy["camera_exposes"],
            ["stock_olympus", "star", "astro_rate_v2"],
        )
        self.assertTrue(policy["requires_current_service"])


if __name__ == "__main__":
    unittest.main()
