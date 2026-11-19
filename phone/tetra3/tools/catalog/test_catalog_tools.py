from __future__ import annotations

import csv
import gzip
import hashlib
import json
import math
import pathlib
import random
import tempfile
import unittest

import common
import fetch_gaia
import merge_catalogs
import validate_coverage


def fixed_record(**fields: str) -> str:
    record = [" "] * 276
    ranges = {
        "hip": (0, 6),
        "solution": (7, 10),
        "source": (11, 12),
        "components": (13, 14),
        "ra": (15, 28),
        "dec": (29, 42),
        "parallax": (43, 50),
        "pmra": (51, 59),
        "pmdec": (60, 68),
        "magnitude": (129, 136),
        "variability": (150, 151),
        "bv": (152, 158),
    }
    for name, value in fields.items():
        start, end = ranges[name]
        encoded = value.rjust(end - start)
        if len(encoded) != end - start:
            raise ValueError(f"fixture field {name} too wide")
        record[start:end] = encoded
    return "".join(record)


class CatalogToolsTest(unittest.TestCase):
    def test_source_id_bounds_match_gaia_encoding(self) -> None:
        stride = 1 << 55
        self.assertEqual(fetch_gaia.source_id_bounds(2, 0), (0, stride))
        self.assertEqual(fetch_gaia.source_id_bounds(2, 191), (191 * stride, 192 * stride))
        self.assertIn(
            f"source_id >= {17 * stride}", fetch_gaia.query_for(2, 17, 12.0)
        )

    def test_deterministic_gzip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "source.csv"
            source.write_bytes(b"a,b\n1,2\n")
            first, second = root / "first.gz", root / "second.gz"
            common.deterministic_gzip(source, first)
            common.deterministic_gzip(source, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(gzip.decompress(first.read_bytes()), source.read_bytes())

    def test_completed_download_partial_is_promoted_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            destination = root / "fixed.dat.gz"
            partial = root / ".fixed.dat.gz.part"
            payload = b"already complete"
            partial.write_bytes(payload)
            common.download_file(
                url="http://127.0.0.1:1/must-not-be-requested",
                destination=destination,
                timeout_seconds=0.01,
                retries=0,
                expected_sha256=hashlib.sha256(payload).hexdigest(),
            )
            self.assertEqual(destination.read_bytes(), payload)
            self.assertFalse(partial.exists())

    def test_proper_motion_uses_pmra_star_convention(self) -> None:
        ra, dec = common.propagate_radec(10.0, 60.0, 1000.0, 0.0, 2000.0, 2001.0)
        # mu_alpha* = 1 arcsec/year; at dec=60 this is nearly 2 arcsec in RA.
        self.assertAlmostEqual((ra - 10.0) * 3600.0, 2.0, places=5)
        self.assertAlmostEqual(dec, 60.0, places=7)

    def test_hipparcos_fixed_width_conversion_and_filter(self) -> None:
        record = fixed_record(
            hip="42",
            solution="5",
            source="0",
            components="1",
            ra=f"{math.radians(45):.10f}",
            dec=f"{math.radians(-20):.10f}",
            parallax="10.2",
            pmra="0",
            pmdec="0",
            magnitude="3.2",
            variability="0",
            bv="0.5",
        )
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "hip2.dat.gz"
            with gzip.open(path, "wt", encoding="ascii", newline="") as output:
                output.write(record + "\n")
            rows = list(
                merge_catalogs.iter_hipparcos(
                    path,
                    target_epoch=2030.0,
                    magnitude_limit=8.0,
                    mapping={},
                    present_gaia=set(),
                )
            )
        self.assertEqual(len(rows), 1)
        row = dict(zip(common.CANONICAL_HEADER, rows[0]))
        self.assertEqual(row["catalog"], "hipparcos")
        self.assertEqual(row["source_id"], "42")
        self.assertAlmostEqual(float(row["ra_deg"]), 45.0, places=7)
        self.assertAlmostEqual(float(row["dec_deg"]), -20.0, places=7)
        self.assertIn("pattern_anchor=1", row["flags"])

    def test_rectangular_field_counts_by_magnitude(self) -> None:
        grid = validate_coverage.SkyGrid(2.0)
        grid.add(10.0, 20.0, 8.0)
        grid.add(11.0, 20.0, 10.0)
        grid.add(20.0, 20.0, 7.0)
        counts = validate_coverage.count_field(
            grid,
            ra_deg=10.0,
            dec_deg=20.0,
            roll_deg=0.0,
            horizontal_fov_deg=3.0,
            vertical_fov_deg=2.25,
            limits=[8.0, 9.0, 12.0],
        )
        self.assertEqual(counts, [1, 1, 2])

    def test_sky_grid_never_loses_brute_force_field_members(self) -> None:
        generator = random.Random(0x7E7A3)
        grid = validate_coverage.SkyGrid(2.0)
        vectors: list[tuple[float, float, float]] = []
        for _ in range(500):
            ra = generator.random() * 360.0
            dec = math.degrees(math.asin(generator.uniform(-1.0, 1.0)))
            grid.add(ra, dec, 5.0)
            vectors.append(common.radec_to_vector(ra, dec))
        centers = [(359.8, 0.0), (0.2, -45.0), (123.0, 89.5), (250.0, -89.5)]
        for ra_deg, dec_deg in centers:
            for roll_deg in (0.0, 37.0, 90.0):
                indexed = validate_coverage.count_field(
                    grid,
                    ra_deg=ra_deg,
                    dec_deg=dec_deg,
                    roll_deg=roll_deg,
                    horizontal_fov_deg=20.0,
                    vertical_fov_deg=12.0,
                    limits=[12.0],
                )[0]
                ra, dec, roll = map(math.radians, (ra_deg, dec_deg, roll_deg))
                center = (
                    math.cos(dec) * math.cos(ra),
                    math.cos(dec) * math.sin(ra),
                    math.sin(dec),
                )
                east = (-math.sin(ra), math.cos(ra), 0.0)
                north = (
                    -math.sin(dec) * math.cos(ra),
                    -math.sin(dec) * math.sin(ra),
                    math.cos(dec),
                )
                horizontal = tuple(
                    math.cos(roll) * east[index] + math.sin(roll) * north[index]
                    for index in range(3)
                )
                vertical = tuple(
                    -math.sin(roll) * east[index] + math.cos(roll) * north[index]
                    for index in range(3)
                )
                max_x = math.tan(math.radians(10.0))
                max_y = math.tan(math.radians(6.0))
                brute = 0
                for vector in vectors:
                    denominator = sum(a * b for a, b in zip(vector, center))
                    if denominator <= 0:
                        continue
                    x = sum(a * b for a, b in zip(vector, horizontal)) / denominator
                    y = sum(a * b for a, b in zip(vector, vertical)) / denominator
                    brute += abs(x) <= max_x and abs(y) <= max_y
                self.assertEqual(indexed, brute, (ra_deg, dec_deg, roll_deg))

    def test_canonical_gaia_row_retains_missing_pm_epoch(self) -> None:
        row = dict.fromkeys(fetch_gaia.HEADER, "")
        row.update(
            source_id="123",
            ra="12.5",
            dec="-3.5",
            parallax="",
            phot_g_mean_mag="7.25",
        )
        canonical, source_id = merge_catalogs.canonical_gaia_row(row, 2030.0)
        output = dict(zip(common.CANONICAL_HEADER, canonical))
        self.assertEqual(source_id, 123)
        self.assertEqual(output["ref_epoch_jyear"], "2016")
        self.assertEqual(output["pmra_masyr"], "")
        self.assertIn("pattern_anchor=0", output["flags"])


if __name__ == "__main__":
    unittest.main()
