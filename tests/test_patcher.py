"""Patcher failure handling and artifact checks; no firmware fixtures required."""
import errno
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import patch_firmware as pf
from firmware import container, input_data
from firmware.startup import launcher


class InputTests(unittest.TestCase):
    def test_only_complete_matching_input_is_accepted(self):
        data = b'synthetic test input'
        target = {'size': len(data), 'input_sha256': hashlib.sha256(data).hexdigest()}
        with patch.object(pf, 'TARGETS', {'test': target}):
            stream = io.BytesIO(data)
            self.assertEqual(pf.identify(stream), ('test', target))
            self.assertEqual(stream.tell(), 0)
            for bad in (data[:-1], data + b'0', b'!' + data[1:]):
                with self.subTest(bad=bad), self.assertRaises(pf.PatchError):
                    pf.identify(io.BytesIO(bad))

    def test_container_permutation_round_trip(self):
        data = bytes(range(256)) * 64
        self.assertNotEqual(container.scramble(data), data)
        self.assertEqual(container.descramble(container.scramble(data)), data)


class OutputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'temporary'
        self.source.write_bytes(b'complete result')
        self.destination = self.root / 'destination'

    def test_publish_leaves_source_unchanged(self):
        pf.publish(self.source, self.destination)
        self.assertEqual(self.destination.read_bytes(), self.source.read_bytes())

    def test_existing_file_and_dangling_symlink_are_preserved(self):
        self.destination.write_bytes(b'keep')
        with self.assertRaises(FileExistsError):
            pf.publish(self.source, self.destination)
        self.assertEqual(self.destination.read_bytes(), b'keep')
        self.destination.unlink()
        self.destination.symlink_to(self.root / 'absent')
        with self.assertRaises(FileExistsError):
            pf.publish(self.source, self.destination)
        self.assertTrue(self.destination.is_symlink())

    def test_fat_filesystem_fallback(self):
        with patch.object(pf.os, 'link', side_effect=OSError(errno.EPERM, 'no hardlinks')):
            pf.publish(self.source, self.destination)
        self.assertEqual(self.destination.read_bytes(), b'complete result')

    def test_failed_copy_cleans_partial_output(self):
        def fail_copy(source, destination, size):
            destination.write(b'partial')
            raise OSError('disk full')
        with patch.object(pf.os, 'link', side_effect=OSError(errno.EPERM, 'no hardlinks')), \
                patch.object(pf.shutil, 'copyfileobj', side_effect=fail_copy):
            with self.assertRaisesRegex(OSError, 'disk full'):
                pf.publish(self.source, self.destination)
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.source.read_bytes(), b'complete result')


class PayloadTests(unittest.TestCase):
    def test_all_committed_native_payloads_fit_code_regions(self):
        for model, target in pf.TARGETS.items():
            with self.subTest(model=model):
                regions = pf.native_regions(pf.ROOT / 'binaries' / model / 'native', target)
                self.assertGreater(len(regions), 10)
                self.assertLess(sum(len(data) for _, data in regions), 32 * 1024)

    def test_rejects_native_payload_escape_overlap_and_truncation(self):
        target = pf.TARGETS['om1']
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'code.bin').write_bytes(b'code')
            good = {'offset': 0x30, 'size': 4, 'file': 'code.bin'}
            bad_layouts = [
                [dict(good, offset=0)],
                [dict(good, file='../code.bin')],
                [dict(good, size=5)],
                [good, good],
                [dict(good, offset=target['rootfs_offset'])],
                [],
            ]
            for records in bad_layouts:
                with self.subTest(records=records):
                    (root / 'layout.json').write_text(json.dumps(records))
                    with self.assertRaises(pf.PatchError):
                        pf.native_regions(root, target)

    def test_vendor_data_sections_are_empty_and_populated_only_from_input(self):
        class SyntheticInput:
            def seek(self, offset):
                self.offset = offset
            def read(self, size):
                return bytes((self.offset + i) % 255 + 1 for i in range(size))
        for model in pf.TARGETS:
            for program in ('star_gatt_server', 'om1-stars'):
                with self.subTest(model=model, program=program):
                    original = (pf.ROOT / 'binaries' / model / 'linux' / program).read_bytes()
                    populated = input_data.populate(original, SyntheticInput(), model, program)
                    self.assertEqual(len(original), len(populated))
                    self.assertNotEqual(original, populated)
                    # All substituted bytes occupied zero-filled reserved data.
                    self.assertTrue(all(a == 0 for a, b in zip(original, populated) if a != b))
                    with self.assertRaisesRegex(ValueError, 'already contains'):
                        input_data.populate(populated, SyntheticInput(), model, program)
                    with self.assertRaisesRegex(ValueError, 'truncated'):
                        input_data.populate(original, io.BytesIO(), model, program)

    def test_startup_preserves_unrelated_input_and_uses_model_mailboxes(self):
        stock = 'input banner\nwifi_func() {\n\t/usr/bin/wifi_mdns &\n}\nble_func() {\n\t/usr/bin/blebridge &\n}\n'
        for model, address in [('om1', '0x2a2985fb8'), ('om1ii', '0x2a29af634')]:
            result = launcher(stock, 'custom_func() { :; }\n', model)
            self.assertTrue(result.startswith('input banner\n'))
            self.assertIn(address, result)
            self.assertIn('LD_PRELOAD=/usr/lib/libbsa_gatt_shim.so', result)
        with self.assertRaises(ValueError):
            launcher(stock + stock, '', 'om1')


if __name__ == '__main__':
    unittest.main()
