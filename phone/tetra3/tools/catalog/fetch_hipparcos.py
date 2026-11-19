#!/usr/bin/env python3
"""Fetch Hipparcos-2 and Gaia's official Hipparcos-2 cross-match."""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import math
import pathlib
import sys
from typing import Any

from common import atomic_json, download_file, md5_file, sha256_file


HIP2_URL = "https://cdsarc.cds.unistra.fr/ftp/I/311/hip2.dat.gz"
HIP2_README = "https://cdsarc.cds.unistra.fr/ftp/I/311/ReadMe"
HIP2_ROWS = 117_955
XMATCH_URL = (
    "https://cdn.gea.esac.esa.int/Gaia/gedr3/cross_match/"
    "hipparcos2_best_neighbour/Hipparcos2BestNeighbour.csv.gz"
)
XMATCH_ROWS = 99_525
XMATCH_HEADER = (
    "source_id",
    "original_ext_source_id",
    "angular_distance",
    "number_of_neighbours",
    "xm_flag",
)


def validate_hip2(path: pathlib.Path) -> dict[str, int | float]:
    rows = 0
    last_hip = 0
    bright = 0
    ordinary_five_parameter = 0
    with gzip.open(path, "rt", encoding="ascii", newline="") as source:
        for line_number, line in enumerate(source, start=1):
            record = line.rstrip("\r\n")
            if len(record) != 276:
                raise ValueError(
                    f"Hipparcos-2 record {line_number} has width {len(record)}, expected 276"
                )
            try:
                hip = int(record[0:6])
                solution = int(record[7:10])
                ra_rad = float(record[15:28])
                dec_rad = float(record[29:42])
                hp_mag = float(record[129:136])
            except ValueError as error:
                raise ValueError(f"invalid Hipparcos-2 record {line_number}") from error
            if hip <= last_hip:
                raise ValueError(f"Hipparcos IDs not increasing at record {line_number}")
            if not (-math.pi / 2 <= dec_rad <= math.pi / 2 and 0 <= ra_rad < 2 * math.pi):
                raise ValueError(f"invalid coordinates in Hipparcos record {line_number}")
            last_hip = hip
            rows += 1
            if hp_mag <= 8.0:
                bright += 1
                if solution % 10 == 5:
                    ordinary_five_parameter += 1
    if rows != HIP2_ROWS:
        raise ValueError(f"Hipparcos-2 row count {rows}, expected {HIP2_ROWS}")
    return {
        "rows": rows,
        "last_hip": last_hip,
        "rows_hp_le_8": bright,
        "rows_hp_le_8_five_parameter": ordinary_five_parameter,
    }


def validate_xmatch(path: pathlib.Path) -> dict[str, int | float]:
    rows = 0
    external_ids: set[int] = set()
    max_distance = 0.0
    with gzip.open(path, "rt", encoding="ascii", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(XMATCH_HEADER):
            raise ValueError(f"unexpected cross-match header: {reader.fieldnames!r}")
        for line_number, row in enumerate(reader, start=2):
            try:
                source_id = int(row["source_id"])
                hip = int(row["original_ext_source_id"])
                distance = float(row["angular_distance"])
                int(row["number_of_neighbours"])
                int(row["xm_flag"])
            except ValueError as error:
                raise ValueError(f"invalid cross-match row {line_number}") from error
            if source_id < 0 or hip <= 0 or not math.isfinite(distance) or distance < 0:
                raise ValueError(f"invalid cross-match values at row {line_number}")
            if hip in external_ids:
                raise ValueError(f"duplicate Hipparcos ID {hip} in best-neighbour table")
            external_ids.add(hip)
            max_distance = max(max_distance, distance)
            rows += 1
    if rows != XMATCH_ROWS:
        raise ValueError(f"cross-match row count {rows}, expected {XMATCH_ROWS}")
    return {"rows": rows, "max_angular_distance_arcsec": max_distance}


def fetch_one(
    *,
    url: str,
    path: pathlib.Path,
    timeout: float,
    retries: int,
    force: bool,
    expected_md5: str | None,
    expected_sha256: str | None,
) -> None:
    if force:
        path.unlink(missing_ok=True)
        path.with_name(f".{path.name}.part").unlink(missing_ok=True)
    if path.is_file():
        md5_matches = expected_md5 is None or md5_file(path) == expected_md5
        if md5_matches and (expected_sha256 is None or sha256_file(path) == expected_sha256):
            print(f"use existing {path.name}")
            return
        print(f"discard checksum-mismatched {path.name}")
        path.unlink()
    print(f"download {url}")
    download_file(
        url=url,
        destination=path,
        timeout_seconds=timeout,
        retries=retries,
        expected_md5=expected_md5,
        expected_sha256=expected_sha256,
    )


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--retries", type=int, default=4)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--plan", action="store_true", help="do not access the network")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = argument_parser().parse_args(argv)
    if args.timeout <= 0 or args.retries < 0:
        raise SystemExit("--timeout must be positive and --retries nonnegative")
    plan: dict[str, Any] = {
        "schema_version": 1,
        "catalog": "Hipparcos, the New Reduction (I/311)",
        "reference_epoch_jyear": 1991.25,
        "license": "CC BY-NC 3.0 IGO",
        "attribution": "Credit: ESA",
        "hipparcos2": {
            "url": HIP2_URL,
            "readme": HIP2_README,
            "path": "hip2.dat.gz",
        },
        "gaia_dr3_best_neighbour": {
            "url": XMATCH_URL,
            "doi": "https://doi.org/10.17876/gaia/dr.3/14",
            "path": "Hipparcos2BestNeighbour.csv.gz",
        },
    }
    if args.plan:
        json.dump(plan, sys.stdout, sort_keys=True, indent=2)
        sys.stdout.write("\n")
        return 0

    args.output_dir.mkdir(parents=True, exist_ok=True)
    hip_path = args.output_dir / "hip2.dat.gz"
    xmatch_path = args.output_dir / "Hipparcos2BestNeighbour.csv.gz"
    fetch_one(
        url=HIP2_URL,
        path=hip_path,
        timeout=args.timeout,
        retries=args.retries,
        force=args.force,
        expected_md5=None,
        expected_sha256=None,
    )
    fetch_one(
        url=XMATCH_URL,
        path=xmatch_path,
        timeout=args.timeout,
        retries=args.retries,
        force=args.force,
        expected_md5=None,
        expected_sha256=None,
    )
    hip_stats = validate_hip2(hip_path)
    xmatch_stats = validate_xmatch(xmatch_path)
    plan["hipparcos2"].update(
        {
            **hip_stats,
            "bytes": hip_path.stat().st_size,
            "sha256": sha256_file(hip_path),
        }
    )
    plan["gaia_dr3_best_neighbour"].update(
        {
            **xmatch_stats,
            "bytes": xmatch_path.stat().st_size,
            "sha256": sha256_file(xmatch_path),
        }
    )
    atomic_json(args.output_dir / "hipparcos-manifest.json", plan)
    print(
        f"complete: {hip_stats['rows']} Hipparcos rows and "
        f"{xmatch_stats['rows']} best neighbours"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
