#!/usr/bin/env python3
"""Sample all-sky rectangular fields and report sparse catalog coverage."""

from __future__ import annotations

import argparse
import bisect
import csv
import json
import math
import pathlib
from array import array
from collections.abc import Sequence
from typing import TypedDict

from common import CANONICAL_HEADER, atomic_json, open_text, radec_to_vector, sha256_file


GOLDEN_ANGLE = math.pi * (3.0 - math.sqrt(5.0))


class FieldReport(TypedDict):
    ra_deg: float
    dec_deg: float
    roll_deg: float
    counts: dict[str, int]


def parse_limits(text: str) -> list[float]:
    limits = sorted({float(value) for value in text.split(",") if value.strip()})
    if not limits or not all(-5.0 < value < 30.0 for value in limits):
        raise ValueError("magnitude limits must be a nonempty comma list in (-5, 30)")
    return limits


def percentile(sorted_values: list[int], fraction: float) -> int:
    if not sorted_values:
        raise ValueError("cannot summarize empty values")
    index = round(fraction * (len(sorted_values) - 1))
    return sorted_values[index]


class SkyGrid:
    def __init__(self, cell_degrees: float) -> None:
        self.cell_degrees = cell_degrees
        self.ra_cells = math.ceil(360.0 / cell_degrees)
        self.dec_cells = math.ceil(180.0 / cell_degrees)
        self.cells = [array("I") for _ in range(self.ra_cells * self.dec_cells)]
        self.x = array("f")
        self.y = array("f")
        self.z = array("f")
        self.magnitude = array("f")

    def _indices(self, ra_deg: float, dec_deg: float) -> tuple[int, int]:
        ra_index = min(self.ra_cells - 1, int((ra_deg % 360.0) / 360.0 * self.ra_cells))
        dec_index = min(
            self.dec_cells - 1, int((dec_deg + 90.0) / 180.0 * self.dec_cells)
        )
        return ra_index, dec_index

    def add(self, ra_deg: float, dec_deg: float, magnitude: float) -> None:
        index = len(self.x)
        if index >= 2**32:
            raise ValueError("coverage validator supports fewer than 2^32 stars")
        x, y, z = radec_to_vector(ra_deg, dec_deg)
        self.x.append(x)
        self.y.append(y)
        self.z.append(z)
        self.magnitude.append(magnitude)
        ra_index, dec_index = self._indices(ra_deg, dec_deg)
        self.cells[dec_index * self.ra_cells + ra_index].append(index)

    def candidates(self, ra_deg: float, dec_deg: float, radius_deg: float):
        dec_low = max(-90.0, dec_deg - radius_deg)
        dec_high = min(90.0, dec_deg + radius_deg)
        first_dec = max(0, int((dec_low + 90.0) / 180.0 * self.dec_cells))
        last_dec = min(
            self.dec_cells - 1,
            int((dec_high + 90.0) / 180.0 * self.dec_cells),
        )
        extreme_dec = min(90.0, abs(dec_deg) + radius_deg)
        ra_indices: Sequence[int]
        if extreme_dec >= 90.0:
            ra_indices = range(self.ra_cells)
        else:
            ratio = min(
                1.0,
                math.sin(math.radians(radius_deg))
                / max(1e-12, math.cos(math.radians(extreme_dec))),
            )
            ra_radius = math.degrees(math.asin(ratio))
            if ra_radius >= 180.0:
                ra_indices = range(self.ra_cells)
            else:
                center, _ = self._indices(ra_deg, dec_deg)
                span = math.ceil(ra_radius / 360.0 * self.ra_cells) + 1
                ra_indices = sorted(
                    {(center + offset) % self.ra_cells for offset in range(-span, span + 1)}
                )
        for dec_index in range(first_dec, last_dec + 1):
            base = dec_index * self.ra_cells
            for ra_index in ra_indices:
                yield from self.cells[base + ra_index]


def load_catalog(
    path: pathlib.Path,
    max_magnitude: float,
    cell_degrees: float,
    pattern_anchors_only: bool = False,
) -> SkyGrid:
    grid = SkyGrid(cell_degrees)
    with open_text(path) as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(CANONICAL_HEADER):
            raise ValueError(f"unexpected canonical catalog header: {reader.fieldnames!r}")
        for line_number, row in enumerate(reader, start=2):
            if pattern_anchors_only and "pattern_anchor=0" in row["flags"].split(";"):
                continue
            try:
                magnitude = float(row["magnitude"])
                ra = float(row["ra_deg"])
                dec = float(row["dec_deg"])
            except ValueError as error:
                raise ValueError(f"invalid canonical row {line_number}") from error
            if magnitude <= max_magnitude:
                grid.add(ra, dec, magnitude)
    return grid


def fibonacci_centers(count: int):
    for index in range(count):
        z = 1.0 - 2.0 * (index + 0.5) / count
        yield math.degrees(GOLDEN_ANGLE * index) % 360.0, math.degrees(math.asin(z))


def count_field(
    grid: SkyGrid,
    *,
    ra_deg: float,
    dec_deg: float,
    roll_deg: float,
    horizontal_fov_deg: float,
    vertical_fov_deg: float,
    limits: list[float],
) -> list[int]:
    ra = math.radians(ra_deg)
    dec = math.radians(dec_deg)
    roll = math.radians(roll_deg)
    cos_dec, sin_dec = math.cos(dec), math.sin(dec)
    center = (cos_dec * math.cos(ra), cos_dec * math.sin(ra), sin_dec)
    east = (-math.sin(ra), math.cos(ra), 0.0)
    north = (-sin_dec * math.cos(ra), -sin_dec * math.sin(ra), cos_dec)
    cos_roll, sin_roll = math.cos(roll), math.sin(roll)
    horizontal = tuple(
        cos_roll * east[index] + sin_roll * north[index] for index in range(3)
    )
    vertical = tuple(
        -sin_roll * east[index] + cos_roll * north[index] for index in range(3)
    )
    max_x = math.tan(math.radians(horizontal_fov_deg) / 2.0)
    max_y = math.tan(math.radians(vertical_fov_deg) / 2.0)
    radius = math.degrees(math.atan(math.hypot(max_x, max_y)))
    starts = [0] * (len(limits) + 1)
    for index in grid.candidates(ra_deg, dec_deg, radius):
        x, y, z = grid.x[index], grid.y[index], grid.z[index]
        denominator = x * center[0] + y * center[1] + z * center[2]
        if denominator <= 0.0:
            continue
        projected_x = (x * horizontal[0] + y * horizontal[1] + z * horizontal[2]) / denominator
        if abs(projected_x) > max_x:
            continue
        projected_y = (x * vertical[0] + y * vertical[1] + z * vertical[2]) / denominator
        if abs(projected_y) > max_y:
            continue
        first_limit = bisect.bisect_left(limits, grid.magnitude[index])
        if first_limit < len(limits):
            starts[first_limit] += 1
    counts: list[int] = []
    running = 0
    for value in starts[:-1]:
        running += value
        counts.append(running)
    return counts


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--horizontal-fov", type=float, default=3.0)
    parser.add_argument("--aspect", type=float, default=4.0 / 3.0)
    parser.add_argument("--vertical-fov", type=float)
    parser.add_argument("--magnitude-limits", default="8,9,10,11,12")
    parser.add_argument("--centers", type=int, default=49_152)
    parser.add_argument("--roll-step", type=float, default=30.0)
    parser.add_argument("--cell-degrees", type=float, default=2.0)
    parser.add_argument("--minimum-stars", type=int, default=8)
    parser.add_argument("--worst-fields", type=int, default=20)
    parser.add_argument(
        "--pattern-anchors-only",
        action="store_true",
        help="exclude rows explicitly marked pattern_anchor=0",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = argument_parser().parse_args(argv)
    try:
        limits = parse_limits(args.magnitude_limits)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if args.horizontal_fov <= 0 or args.horizontal_fov >= 180 or args.aspect <= 0:
        raise SystemExit("invalid field of view/aspect")
    vertical_fov = args.vertical_fov
    if vertical_fov is None:
        vertical_fov = math.degrees(
            2.0 * math.atan(math.tan(math.radians(args.horizontal_fov) / 2.0) / args.aspect)
        )
    if vertical_fov <= 0 or vertical_fov >= 180:
        raise SystemExit("invalid vertical field of view")
    if (
        args.centers <= 0
        or not 0 < args.roll_step <= 180
        or args.cell_degrees <= 0
        or args.worst_fields < 0
    ):
        raise SystemExit("invalid sampling parameters")

    print(f"load {args.catalog} through magnitude {limits[-1]}")
    grid = load_catalog(
        args.catalog,
        limits[-1],
        args.cell_degrees,
        args.pattern_anchors_only,
    )
    rolls = []
    roll = 0.0
    while roll < 180.0 - 1e-9:
        rolls.append(roll)
        roll += args.roll_step
    values: list[list[int]] = [[] for _ in limits]
    worst: list[FieldReport] = []
    tested = 0
    for center_index, (ra, dec) in enumerate(fibonacci_centers(args.centers)):
        if center_index and center_index % 4096 == 0:
            print(f"sampled {center_index}/{args.centers} centers")
        for roll in rolls:
            counts = count_field(
                grid,
                ra_deg=ra,
                dec_deg=dec,
                roll_deg=roll,
                horizontal_fov_deg=args.horizontal_fov,
                vertical_fov_deg=vertical_fov,
                limits=limits,
            )
            for index, count in enumerate(counts):
                values[index].append(count)
            field: FieldReport = {
                "ra_deg": ra,
                "dec_deg": dec,
                "roll_deg": roll,
                "counts": {format(limit, "g"): count for limit, count in zip(limits, counts)},
            }
            worst.append(field)
            worst.sort(
                key=lambda item: (
                    list(item["counts"].values())[-1],
                    item["ra_deg"],
                    item["roll_deg"],
                )
            )
            del worst[args.worst_fields :]
            tested += 1

    summaries: dict[str, object] = {}
    for limit, counts in zip(limits, values):
        ordered = sorted(counts)
        summaries[format(limit, "g")] = {
            "minimum": ordered[0],
            "p0.1": percentile(ordered, 0.001),
            "p1": percentile(ordered, 0.01),
            "p5": percentile(ordered, 0.05),
            "median": percentile(ordered, 0.5),
            "mean": sum(ordered) / len(ordered),
            "fields_below_minimum_stars": sum(
                value < args.minimum_stars for value in ordered
            ),
        }
    report = {
        "schema_version": 1,
        "catalog": str(args.catalog),
        "catalog_sha256": sha256_file(args.catalog),
        "stars_loaded": len(grid.x),
        "pattern_anchors_only": args.pattern_anchors_only,
        "horizontal_fov_deg": args.horizontal_fov,
        "vertical_fov_deg": vertical_fov,
        "aspect": args.aspect,
        "centers": args.centers,
        "rolls_deg": rolls,
        "fields_tested": tested,
        "minimum_stars_target": args.minimum_stars,
        "magnitude_summaries": summaries,
        "worst_fields_at_faintest_limit": worst,
        "sampling_note": (
            "Fibonacci centers and discrete rolls are a deterministic stress sample, "
            "not a mathematical proof over continuous pointing/roll space."
        ),
    }
    atomic_json(args.output, report)
    print(json.dumps(summaries, sort_keys=True, indent=2))
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
