"""Boundary and transport tests for the shared pre-position wire contract."""

import importlib.util
from pathlib import Path
import struct
import unittest
from itertools import product


SPEC = importlib.util.spec_from_file_location("ibis_preposition_contract",
                                            Path(__file__).with_name("validate_vectors.py"))
assert SPEC is not None and SPEC.loader is not None
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class IbisPrepositionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = contract.load_json(contract.SCHEMA_PATH)
        cls.constants = contract.validate_schema(cls.schema)

    def decode(self, x: int, y: int, roll: int, action: int = 1) -> dict:
        return contract.decode_value(struct.pack("<hhhH", x, y, roll, action), self.constants)

    def test_checked_in_vectors(self) -> None:
        self.assertEqual(contract.validate(), {"golden_vectors": 34, "invalid_vectors": 13})

    def test_native_representation_bounds_allow_negation(self) -> None:
        self.decode(28111, 28111, 32767)
        self.decode(-28111, -28111, -32768)
        # -28112 converts to INT32_MIN, which CPU0 cannot safely negate.
        for outside in (28112, -28112, 32767, -32768):
            with self.assertRaisesRegex(contract.ContractError, "x_counts_native_overflow"):
                self.decode(outside, 0, 0)
            with self.assertRaisesRegex(contract.ContractError, "y_counts_native_overflow"):
                self.decode(0, outside, 0)

    def test_wire_has_no_geometric_travel_policy(self) -> None:
        for axes in ((2547, 2547, 5326), (-2547, -2547, -5326),
                     (0, 0, 21299), (2437, 0, 5325), (0, 2446, -5325)):
            decoded = self.decode(*axes)
            self.assertFalse(contract.app_target_allowed(decoded, self.schema["app_policy"]))

    def test_app_geometry_boundaries_are_sign_symmetric(self) -> None:
        policy = self.schema["app_policy"]
        for sx, sy, sr in product((-1, 1), repeat=3):
            for valid in ((2546, 0, 0), (0, 2546, 0), (2436, 0, 5325),
                          (0, 2445, 5325), (1800, 1800, 5325)):
                axes = tuple(a * s for a, s in zip(valid, (sx, sy, sr)))
                self.assertTrue(contract.app_target_allowed(self.decode(*axes), policy), axes)
            for invalid in ((2547, 0, 0), (0, 2547, 0), (2546, 1, 0), (1, 2546, 0),
                            (2437, 0, 5325), (0, 2446, 5325), (1801, 1801, 0), (0, 0, 5326)):
                axes = tuple(a * s for a, s in zip(invalid, (sx, sy, sr)))
                self.assertFalse(contract.app_target_allowed(self.decode(*axes), policy), axes)

    def test_staged_and_immediate_commands_have_the_same_eventual_native_payload(self) -> None:
        staged = self.decode(-1, 1, -1, 1)
        immediate = self.decode(-1, 1, -1, 2)
        payload = struct.pack("<iiiH", -76390, 76391, -61, 0x5051)
        self.assertEqual(contract.encode_mailbox(staged), (payload, 14))
        self.assertEqual(contract.encode_mailbox(immediate), (payload, 14))
        self.assertEqual(contract.encode_cpu0_transaction(staged), b"\x37" + payload)
        self.assertTrue(self.schema["internal_mailbox"]["staged_intent_is_linux_only"])

    def test_cancel_and_zero_apply_have_the_same_valid_native_tag(self) -> None:
        cancelled = self.decode(0, 0, 0, 0)
        applied = self.decode(0, 0, 0, 2)
        native_zero = bytes(12) + b"\x51\x50"
        self.assertEqual(contract.encode_mailbox(cancelled), (native_zero, 14))
        self.assertEqual(contract.encode_mailbox(applied), (native_zero, 14))
        for axis in ((1, 0, 0), (0, -1, 0), (0, 0, 1)):
            with self.assertRaisesRegex(contract.ContractError, "cancel_axes"):
                self.decode(*axis, action=0)

    def test_native_conversion_uses_signed_ceiling_at_public_boundaries(self) -> None:
        for axes in ((28111, -28111, 32767), (-28111, 28111, -32768),
                     (2546, -2546, 5325), (-2546, 2546, -5325)):
            decoded = self.decode(*axes)
            native = struct.unpack("<iiiH", contract.encode_mailbox(decoded)[0])
            for value, logical, scale, divisor in zip(native, axes,
                                                     (1 << 27, 1 << 27, 1 << 18), (1757, 1757, 4295)):
                self.assertLess((value - 1) * divisor, logical * scale)
                self.assertGreaterEqual(value * divisor, logical * scale)
            self.assertEqual(native[3], 0x5051)

    def test_main_consumption_does_not_claim_motion_acknowledgement(self) -> None:
        lifecycle = self.schema["lifecycle"]
        self.assertIn("actuator_settled", lifecycle["att_success_does_not_prove"])
        self.assertIn("cpu0_admission", lifecycle["att_success_does_not_prove"])
        self.assertFalse(lifecycle["application_generation"])
        self.assertFalse(lifecycle["application_acknowledgement"])
        self.assertFalse(lifecycle["physical_settle_acknowledgement"])
        self.assertFalse(lifecycle["cpu0_pending_snapshot_atomic"])
        self.assertTrue(lifecycle["cpu0_pending_publication_races_accepted"])
        self.assertFalse(lifecycle["worker_coordination_atomic_compare_exchange"])


if __name__ == "__main__":
    unittest.main()
