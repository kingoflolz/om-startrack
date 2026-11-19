"""Shared packets must agree with the portable C and C++ codec."""

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import validate_vectors as contract


class GyroAttitudeContractTest(unittest.TestCase):
    def test_shared_schema_and_vectors(self):
        self.assertEqual((10, 13), contract.validate())

    def test_portable_c_and_cpp(self):
        header = contract.ROOT.parents[2] / "camera/linux/bluetooth/preload/include"
        golden = json.loads((contract.ROOT / "vectors/golden.json").read_text())["vectors"]
        invalid = json.loads((contract.ROOT / "vectors/invalid.json").read_text())["vectors"]
        code = ['#include "gyro_attitude_protocol.h"', '#include <assert.h>',
                '#include <string.h>', 'int main(void) {',
                'struct gyro_attitude_value value, before;',
                'uint8_t output[GYRO_ATTITUDE_VALUE_SIZE];']
        for vector in golden:
            wire = bytes.fromhex(vector["hex"])
            expected = vector["expected"]
            code += ['{ const uint8_t wire[] = {' + ','.join(map(str, wire)) + '};',
                     'assert(gyro_attitude_decode(wire, sizeof wire, &value));']
            for field in ("flags", "tick", "capture_sequence", "capture_tick"):
                code.append(f'assert(value.{field} == UINT32_C({expected[field]}));')
            for field in ("current_q30", "capture_q30"):
                for index, lane in enumerate(expected[field]):
                    literal = f'INT64_C({lane})' if lane >= 0 else f'-INT64_C({-lane})'
                    code.append(f'assert(value.{field}[{index}] == {literal});')
            code += ['assert(gyro_attitude_encode(output, sizeof output, &value));',
                     'assert(memcmp(output, wire, sizeof wire) == 0); }']
        for vector in invalid:
            wire = bytes.fromhex(vector["hex"])
            # Keep the backing array legal for C even for a logical zero-byte value.
            code += ['{ const uint8_t wire[] = {' + ','.join(map(str, wire or b'\0')) + '};',
                     'memset(&value, 0x5a, sizeof value); memcpy(&before, &value, sizeof value);',
                     f'assert(!gyro_attitude_decode(wire, {len(wire)}, &value));',
                     'assert(memcmp(&value, &before, sizeof value) == 0); }']
        code += ['assert(!gyro_attitude_decode(0, 48, &value));',
                 'assert(!gyro_attitude_decode(output, 48, 0));',
                 'memset(output, 0x5a, sizeof output);',
                 'memset(&value, 0, sizeof value); value.flags = 1;',
                 'assert(!gyro_attitude_encode(output, sizeof output, &value));',
                 'assert(output[0] == 0x5a && output[47] == 0x5a);',
                 'assert(!gyro_attitude_encode(0, 48, &value));',
                 'assert(!gyro_attitude_encode(output, 48, 0));',
                 'return 0; }']
        tested = 0
        with tempfile.TemporaryDirectory(prefix="gyro-attitude-contract-") as temporary:
            for compiler, standard, suffix in (("cc", "c99", ".c"), ("c++", "c++11", ".cpp")):
                if shutil.which(compiler) is None:
                    continue
                tested += 1
                source = Path(temporary) / ("contract" + suffix)
                executable = Path(temporary) / ("contract-" + standard)
                source.write_text("\n".join(code) + "\n")
                with self.subTest(compiler=compiler):
                    subprocess.run([compiler, "-std=" + standard, "-Wall", "-Wextra", "-Werror",
                                    "-I", str(header), str(source), "-o", str(executable)], check=True)
                    subprocess.run([str(executable)], check=True)
        if not tested:
            self.skipTest("No C or C++ compiler available")


if __name__ == "__main__":
    unittest.main()
