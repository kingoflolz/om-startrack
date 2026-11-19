#!/usr/bin/env python3
"""Canonicalize Gaia DR3 and add unmatched bright Hipparcos-2 stars."""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import math
import os
import pathlib

from common import (
    CANONICAL_HEADER,
    atomic_json,
    finish_gzip_text,
    float_or_none,
    md5_file,
    open_deterministic_gzip_text,
    open_text,
    propagate_radec,
    sha256_file,
    stable_float,
)
from fetch_gaia import HEADER as GAIA_HEADER
from fetch_hipparcos import XMATCH_HEADER


def load_gaia_manifest(
    directory: pathlib.Path, allow_partial: bool
) -> tuple[dict[str, object], list[pathlib.Path]]:
    manifest_path = directory / "gaia-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ValueError(f"cannot read {manifest_path}: {error}") from error
    if manifest.get("catalog") != "Gaia DR3" or manifest.get("schema_version") != 1:
        raise ValueError(f"unsupported Gaia manifest: {manifest_path}")
    if not allow_partial and not manifest.get("complete"):
        raise ValueError(
            "Gaia shard set is incomplete; finish fetch_gaia.py or pass --allow-partial"
        )
    chunks = sorted(manifest.get("chunks", []), key=lambda item: int(item["chunk"]))
    paths: list[pathlib.Path] = []
    for chunk in chunks:
        path = directory / str(chunk["path"])
        if not path.is_file():
            raise ValueError(f"missing Gaia chunk: {path}")
        actual_hash = sha256_file(path)
        if actual_hash != chunk.get("sha256"):
            raise ValueError(f"Gaia chunk checksum mismatch: {path}")
        paths.append(path)
    if not paths:
        raise ValueError("Gaia manifest contains no completed chunks")
    return manifest, paths


def load_best_neighbours(path: pathlib.Path) -> tuple[dict[int, int], set[int]]:
    if not path.is_file():
        raise ValueError(f"missing Gaia/Hipparcos best-neighbour table: {path}")
    mapping: dict[int, int] = {}
    with gzip.open(path, "rt", encoding="ascii", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(XMATCH_HEADER):
            raise ValueError(f"unexpected cross-match header: {reader.fieldnames!r}")
        for row in reader:
            hip = int(row["original_ext_source_id"])
            gaia = int(row["source_id"])
            if hip in mapping:
                raise ValueError(f"duplicate Hipparcos best neighbour for HIP {hip}")
            mapping[hip] = gaia
    return mapping, set(mapping.values())


def validate_hipparcos_inputs(directory: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    manifest_path = directory / "hipparcos-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        hip_info = manifest["hipparcos2"]
        xmatch_info = manifest["gaia_dr3_best_neighbour"]
        hip_path = directory / hip_info["path"]
        xmatch_path = directory / xmatch_info["path"]
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ValueError(f"cannot read {manifest_path}: {error}") from error
    for path, info in ((hip_path, hip_info), (xmatch_path, xmatch_info)):
        if not path.is_file():
            raise ValueError(f"missing Hipparcos input: {path}")
        if sha256_file(path) != info.get("sha256"):
            raise ValueError(f"Hipparcos input checksum mismatch: {path}")
    return hip_path, xmatch_path


def flags(**values: object) -> str:
    return ";".join(
        f"{key}={value}"
        for key, value in values.items()
        if value is not None and value != ""
    )


def canonical_gaia_row(
    row: dict[str, str], target_epoch: float
) -> tuple[list[str], int]:
    source_id = int(row["source_id"])
    ra = float(row["ra"])
    dec = float(row["dec"])
    pmra = float_or_none(row["pmra"])
    pmdec = float_or_none(row["pmdec"])
    # DR3 gaia_source_lite omits ref_epoch; all DR3 astrometry is J2016.0.
    source_epoch = 2016.0
    output_epoch = source_epoch
    if pmra is not None and pmdec is not None:
        ra, dec = propagate_radec(ra, dec, pmra, pmdec, source_epoch, target_epoch)
        output_epoch = target_epoch
    astrometric_solution = row.get("astrometric_params_solved", "").strip()
    pattern_anchor = int(
        pmra is not None
        and pmdec is not None
        and astrometric_solution in {"31", "95"}
    )
    output = [
        "gaia_dr3",
        str(source_id),
        stable_float(ra, 14),
        stable_float(dec, 14),
        stable_float(output_epoch),
        stable_float(pmra),
        stable_float(pmdec),
        stable_float(float_or_none(row["parallax"])),
        stable_float(float(row["phot_g_mean_mag"])),
        "G",
        stable_float(float_or_none(row["bp_rp"])),
        flags(
            astrometric_params_solved=astrometric_solution,
            ruwe=row.get("ruwe"),
            ipd_frac_multi_peak=row.get("ipd_frac_multi_peak"),
            non_single_star=row.get("non_single_star"),
            pattern_anchor=pattern_anchor,
        ),
    ]
    return output, source_id


def parse_int_field(record: str, start: int, end: int) -> int | None:
    value = record[start:end].strip()
    return int(value) if value else None


def parse_float_field(record: str, start: int, end: int) -> float | None:
    value = record[start:end].strip()
    if not value:
        return None
    parsed = float(value)
    return parsed if math.isfinite(parsed) else None


def iter_hipparcos(
    path: pathlib.Path,
    *,
    target_epoch: float,
    magnitude_limit: float,
    mapping: dict[int, int],
    present_gaia: set[int],
):
    with gzip.open(path, "rt", encoding="ascii", newline="") as source:
        for line_number, line in enumerate(source, start=1):
            record = line.rstrip("\r\n")
            if len(record) != 276:
                raise ValueError(f"Hipparcos record {line_number} has invalid width")
            hip = parse_int_field(record, 0, 6)
            solution = parse_int_field(record, 7, 10)
            components = parse_int_field(record, 13, 14)
            ra_rad = parse_float_field(record, 15, 28)
            dec_rad = parse_float_field(record, 29, 42)
            parallax = parse_float_field(record, 43, 50)
            pmra = parse_float_field(record, 51, 59)
            pmdec = parse_float_field(record, 60, 68)
            hp_mag = parse_float_field(record, 129, 136)
            variability_annex = parse_int_field(record, 150, 151)
            bv = parse_float_field(record, 152, 158)
            if (
                hip is None
                or solution is None
                or solution % 10 != 5
                or ra_rad is None
                or dec_rad is None
                or pmra is None
                or pmdec is None
                or hp_mag is None
                or hp_mag > magnitude_limit
            ):
                continue
            matched_gaia = mapping.get(hip)
            if matched_gaia is not None and matched_gaia in present_gaia:
                continue
            ra_deg, dec_deg = propagate_radec(
                math.degrees(ra_rad),
                math.degrees(dec_rad),
                pmra,
                pmdec,
                1991.25,
                target_epoch,
            )
            pattern_anchor = int(
                solution == 5 and components == 1 and variability_annex == 0
            )
            yield [
                "hipparcos",
                str(hip),
                stable_float(ra_deg, 14),
                stable_float(dec_deg, 14),
                stable_float(target_epoch),
                stable_float(pmra),
                stable_float(pmdec),
                stable_float(parallax),
                stable_float(hp_mag),
                "Hp",
                stable_float(bv),
                flags(
                    release="hip2",
                    solution_type=solution,
                    components=components,
                    variability_annex=variability_annex,
                    pattern_anchor=pattern_anchor,
                    best_neighbour_gaia_source_id=matched_gaia,
                ),
            ]


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gaia-dir", type=pathlib.Path, required=True)
    parser.add_argument("--hipparcos-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--target-epoch", type=float, default=2026.0)
    parser.add_argument("--hipparcos-hp-limit", type=float, default=8.0)
    parser.add_argument("--allow-partial", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = argument_parser().parse_args(argv)
    if not 1900.0 <= args.target_epoch <= 2200.0:
        raise SystemExit("--target-epoch must be in 1900..2200")
    if not -5.0 < args.hipparcos_hp_limit < 30.0:
        raise SystemExit("invalid --hipparcos-hp-limit")
    if not args.output.name.endswith(".csv.gz"):
        raise SystemExit("--output must end in .csv.gz")

    gaia_manifest, gaia_paths = load_gaia_manifest(args.gaia_dir, args.allow_partial)
    hip_path, xmatch_path = validate_hipparcos_inputs(args.hipparcos_dir)
    mapping, interesting_gaia_ids = load_best_neighbours(xmatch_path)
    present_gaia: set[int] = set()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    stream, temporary = open_deterministic_gzip_text(args.output)
    gaia_rows = 0
    hipparcos_rows = 0
    previous_gaia_id = -1
    try:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(CANONICAL_HEADER)
        for path in gaia_paths:
            with open_text(path) as source:
                reader = csv.DictReader(source)
                if reader.fieldnames != list(GAIA_HEADER):
                    raise ValueError(f"unexpected Gaia header in {path}")
                for row in reader:
                    canonical, source_id = canonical_gaia_row(row, args.target_epoch)
                    if source_id <= previous_gaia_id:
                        raise ValueError(f"Gaia source IDs are not globally increasing at {path}")
                    previous_gaia_id = source_id
                    writer.writerow(canonical)
                    gaia_rows += 1
                    if source_id in interesting_gaia_ids:
                        present_gaia.add(source_id)
        for canonical in iter_hipparcos(
            hip_path,
            target_epoch=args.target_epoch,
            magnitude_limit=args.hipparcos_hp_limit,
            mapping=mapping,
            present_gaia=present_gaia,
        ):
            writer.writerow(canonical)
            hipparcos_rows += 1
        finish_gzip_text(stream, temporary, args.output)
    except BaseException:
        try:
            stream.close()
        finally:
            temporary.unlink(missing_ok=True)
        raise

    manifest = {
        "schema_version": 1,
        "format": "tetra3 canonical star catalog CSV gzip",
        "columns": list(CANONICAL_HEADER),
        "output": args.output.name,
        "sha256": sha256_file(args.output),
        "bytes": args.output.stat().st_size,
        "rows": gaia_rows + hipparcos_rows,
        "gaia_rows": gaia_rows,
        "hipparcos_supplement_rows": hipparcos_rows,
        "target_epoch_jyear": args.target_epoch,
        "hipparcos_hp_limit": args.hipparcos_hp_limit,
        "gaia_manifest_sha256": sha256_file(args.gaia_dir / "gaia-manifest.json"),
        "gaia_complete": bool(gaia_manifest.get("complete")),
        "hipparcos2_sha256": sha256_file(hip_path),
        "best_neighbour_sha256": sha256_file(xmatch_path),
        "licenses": ["CC BY-NC 3.0 IGO"],
        "attribution": "ESA/Gaia/DPAC and Credit: ESA; see NOTICE.md",
    }
    atomic_json(args.output.with_suffix(args.output.suffix + ".json"), manifest)
    print(
        f"wrote {args.output}: {gaia_rows} Gaia + "
        f"{hipparcos_rows} unmatched Hipparcos-2 rows"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
