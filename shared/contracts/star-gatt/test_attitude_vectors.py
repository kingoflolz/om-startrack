"""Current STATE attitude cases, including missing or malformed trailers."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('star_contract', ROOT / 'validate_vectors.py')
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class AttitudeVectorsTest(unittest.TestCase):
    def test_golden_states(self):
        for case in json.loads((ROOT / 'vectors/attitude-golden.json').read_text())['vectors']:
            with self.subTest(case=case['name']):
                actual = contract.decode_state(bytes.fromhex(case['hex']))
                self.assertEqual(json.loads(json.dumps(actual)), case['expected'])

    def test_malformed_states(self):
        for case in json.loads((ROOT / 'vectors/attitude-invalid.json').read_text())['vectors']:
            with self.subTest(case=case['name']), self.assertRaises(contract.ContractError):
                contract.decode_state(bytes.fromhex(case['hex']))
