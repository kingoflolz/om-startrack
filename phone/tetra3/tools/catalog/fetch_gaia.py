#!/usr/bin/env python3
"""Fetch a reproducible bright Gaia DR3 slice in resumable sky chunks."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any

from common import (
    atomic_json,
    deterministic_gzip,
    post_tap_csv,
    sha256_file,
    validate_csv,
)


DEFAULT_ENDPOINT = "https://gaia.ari.uni-heidelberg.de/tap/sync"
TABLE = "gaiadr3.gaia_source_lite"
GAIA_SOURCE_HEALPIX_ORDER = 12
SOURCE_ID_LOW_BITS = 35
HEADER = (
    "source_id",
    "ra",
    "dec",
    "parallax",
    "pmra",
    "pmdec",
    "astrometric_params_solved",
    "phot_g_mean_mag",
    "bp_rp",
    "ruwe",
    "ipd_frac_multi_peak",
    "non_single_star",
)
SELECT_COLUMNS = ", ".join(HEADER)


def source_id_bounds(chunk_order: int, chunk: int) -> tuple[int, int]:
    if not 0 <= chunk_order <= GAIA_SOURCE_HEALPIX_ORDER:
        raise ValueError("chunk order must be between 0 and 12")
    count = 12 * 4**chunk_order
    if not 0 <= chunk < count:
        raise ValueError(f"chunk must be between 0 and {count - 1}")
    scale = 4 ** (GAIA_SOURCE_HEALPIX_ORDER - chunk_order)
    low = (chunk * scale) << SOURCE_ID_LOW_BITS
    high = ((chunk + 1) * scale) << SOURCE_ID_LOW_BITS
    return low, high


def magnitude_sql(value: float) -> str:
    return format(value, ".8g")


def query_for(chunk_order: int, chunk: int, magnitude_limit: float) -> str:
    low, high = source_id_bounds(chunk_order, chunk)
    return (
        f"SELECT {SELECT_COLUMNS} FROM {TABLE} "
        f"WHERE source_id >= {low} AND source_id < {high} "
        f"AND phot_g_mean_mag <= {magnitude_sql(magnitude_limit)} "
        "ORDER BY source_id"
    )


def chunk_stem(chunk_order: int, chunk: int, magnitude_limit: float) -> str:
    magnitude = magnitude_sql(magnitude_limit).replace(".", "p")
    width = len(str(12 * 4**chunk_order - 1))
    return f"gaia-dr3-g{magnitude}-n{chunk_order}-{chunk:0{width}d}"


def load_valid_sidecar(
    data_path: pathlib.Path,
    sidecar_path: pathlib.Path,
    *,
    expected: dict[str, object],
) -> dict[str, Any] | None:
    if not data_path.is_file() or not sidecar_path.is_file():
        return None
    try:
        sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    for key, value in expected.items():
        if sidecar.get(key) != value:
            return None
    if sidecar.get("sha256") != sha256_file(data_path):
        return None
    return sidecar


def parse_chunks(specifications: list[str], total: int) -> list[int]:
    if not specifications:
        return list(range(total))
    result: set[int] = set()
    for specification in specifications:
        for item in specification.split(","):
            item = item.strip()
            if not item:
                continue
            if "-" in item:
                first_text, last_text = item.split("-", 1)
                first, last = int(first_text), int(last_text)
                if first > last:
                    raise ValueError(f"backwards chunk range: {item}")
                result.update(range(first, last + 1))
            else:
                result.add(int(item))
    if not result or min(result) < 0 or max(result) >= total:
        raise ValueError(f"chunks must be in 0..{total - 1}")
    return sorted(result)


def fetch_chunk(args: argparse.Namespace, chunk: int) -> dict[str, Any]:
    query = query_for(args.chunk_order, chunk, args.magnitude_limit)
    low, high = source_id_bounds(args.chunk_order, chunk)
    stem = chunk_stem(args.chunk_order, chunk, args.magnitude_limit)
    data_path = args.output_dir / f"{stem}.csv.gz"
    sidecar_path = args.output_dir / f"{stem}.json"
    expected = {
        "catalog": "Gaia DR3",
        "table": TABLE,
        "endpoint": args.endpoint,
        "chunk_order": args.chunk_order,
        "chunk": chunk,
        "source_id_low_inclusive": low,
        "source_id_high_exclusive": high,
        "magnitude_limit_g": args.magnitude_limit,
        "query": query,
    }
    sidecar = None if args.force else load_valid_sidecar(
        data_path, sidecar_path, expected=expected
    )
    if sidecar is not None:
        print(f"skip verified {data_path.name} ({sidecar['rows']} rows)")
        return sidecar

    if args.plan:
        return {**expected, "path": data_path.name, "status": "planned"}

    raw_path = args.output_dir / f".{stem}.csv.download"
    print(f"fetch chunk {chunk}: source_id [{low}, {high})")
    try:
        post_tap_csv(
            endpoint=args.endpoint,
            query=query,
            destination=raw_path,
            maxrec=args.maxrec,
            timeout_seconds=args.timeout,
            retries=args.retries,
        )
        statistics = validate_csv(
            raw_path,
            HEADER,
            numeric_id="source_id",
            lower_id=low,
            upper_id=high,
            magnitude_column="phot_g_mean_mag",
            magnitude_limit=args.magnitude_limit,
        )
        if int(statistics["rows"] or 0) >= args.maxrec:
            raise ValueError(
                f"chunk {chunk} reached MAXREC={args.maxrec}; increase --maxrec "
                "or use a finer --chunk-order so truncation cannot be silent"
            )
        deterministic_gzip(raw_path, data_path)
    finally:
        raw_path.unlink(missing_ok=True)

    sidecar = {
        **expected,
        **statistics,
        "path": data_path.name,
        "bytes": data_path.stat().st_size,
        "sha256": sha256_file(data_path),
    }
    atomic_json(sidecar_path, sidecar)
    print(f"wrote {data_path.name} ({statistics['rows']} rows)")
    return sidecar


def existing_chunks(args: argparse.Namespace) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    total = 12 * 4**args.chunk_order
    magnitude = magnitude_sql(args.magnitude_limit).replace(".", "p")
    pattern = f"gaia-dr3-g{magnitude}-n{args.chunk_order}-*.json"
    candidate_chunks: set[int] = set()
    for sidecar_path in args.output_dir.glob(pattern):
        try:
            candidate = json.loads(sidecar_path.read_text(encoding="utf-8"))
            chunk = int(candidate["chunk"])
        except (OSError, ValueError, KeyError, TypeError):
            continue
        if 0 <= chunk < total:
            candidate_chunks.add(chunk)
    for chunk in sorted(candidate_chunks):
        query = query_for(args.chunk_order, chunk, args.magnitude_limit)
        low, high = source_id_bounds(args.chunk_order, chunk)
        stem = chunk_stem(args.chunk_order, chunk, args.magnitude_limit)
        expected = {
            "catalog": "Gaia DR3",
            "table": TABLE,
            "endpoint": args.endpoint,
            "chunk_order": args.chunk_order,
            "chunk": chunk,
            "source_id_low_inclusive": low,
            "source_id_high_exclusive": high,
            "magnitude_limit_g": args.magnitude_limit,
            "query": query,
        }
        sidecar = load_valid_sidecar(
            args.output_dir / f"{stem}.csv.gz",
            args.output_dir / f"{stem}.json",
            expected=expected,
        )
        if sidecar is not None:
            records.append(sidecar)
    return records


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    parser.add_argument("--magnitude-limit", type=float, default=12.0)
    parser.add_argument(
        "--chunk-order",
        type=int,
        default=1,
        help="HEALPix NEST order used for resume chunks (default: 1, 48 chunks)",
    )
    parser.add_argument(
        "--chunks",
        action="append",
        default=[],
        metavar="LIST",
        help="fetch only comma/range selections such as 0,2,8-10 (repeatable)",
    )
    parser.add_argument("--maxrec", type=int, default=10_000_000)
    parser.add_argument("--timeout", type=float, default=1800.0)
    parser.add_argument("--retries", type=int, default=4)
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--plan", action="store_true", help="print queries without network access"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = argument_parser().parse_args(argv)
    if not 0.0 < args.magnitude_limit < 30.0:
        raise SystemExit("--magnitude-limit must be in (0, 30)")
    if not 0 <= args.chunk_order <= GAIA_SOURCE_HEALPIX_ORDER:
        raise SystemExit("--chunk-order must be in 0..12")
    if args.maxrec <= 0 or args.timeout <= 0 or args.retries < 0:
        raise SystemExit("--maxrec/--timeout must be positive and --retries nonnegative")
    total = 12 * 4**args.chunk_order
    try:
        chunks = parse_chunks(args.chunks, total)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    args.output_dir.mkdir(parents=True, exist_ok=True)
    records = [fetch_chunk(args, chunk) for chunk in chunks]
    completed_records = records if args.plan else existing_chunks(args)
    completed_chunks = [int(record["chunk"]) for record in completed_records]
    is_complete = len(completed_chunks) == total and all(
        chunk == index for index, chunk in enumerate(completed_chunks)
    )
    manifest = {
        "schema_version": 1,
        "catalog": "Gaia DR3",
        "data_release_doi": "https://doi.org/10.17876/gaia/dr.3/1",
        "reference_epoch_jyear": 2016.0,
        "license": "CC BY-NC 3.0 IGO",
        "attribution": (
            "ESA/Gaia/DPAC; funding acknowledgements are documented in NOTICE.md"
        ),
        "endpoint": args.endpoint,
        "table": TABLE,
        "magnitude_limit_g": args.magnitude_limit,
        "chunk_order": args.chunk_order,
        "requested_chunks": chunks,
        "completed_chunks": completed_chunks,
        "complete": is_complete,
        "chunks": completed_records,
    }
    if args.plan:
        json.dump(manifest, sys.stdout, sort_keys=True, indent=2)
        sys.stdout.write("\n")
    else:
        atomic_json(args.output_dir / "gaia-manifest.json", manifest)
        total_rows = sum(int(record["rows"]) for record in completed_records)
        state = "complete" if manifest["complete"] else "partial"
        print(f"{state}: {total_rows} rows in {len(completed_records)}/{total} chunks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
