"""Shared, dependency-free helpers for the tetra3 catalogue build tools."""

from __future__ import annotations

import csv
import gzip
import hashlib
import http.client
import io
import json
import math
import os
import pathlib
import shutil
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterable, Iterator, Sequence
from typing import BinaryIO, TextIO


USER_AGENT = "olympus-tetra3-catalog/0.1 (+https://github.com/)"
MAS_TO_RAD = math.pi / (180.0 * 3_600_000.0)

CANONICAL_HEADER = (
    "catalog",
    "source_id",
    "ra_deg",
    "dec_deg",
    "ref_epoch_jyear",
    "pmra_masyr",
    "pmdec_masyr",
    "parallax_mas",
    "magnitude",
    "magnitude_band",
    "color",
    "flags",
)


def atomic_json(path: pathlib.Path, value: object) -> None:
    """Write stable JSON and replace *path* only after the write succeeds."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
            json.dump(value, output, sort_keys=True, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def md5_file(path: pathlib.Path) -> str:
    # MD5 is used here only to verify an upstream-published file checksum.
    digest = hashlib.md5(usedforsecurity=False)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_file(
    *,
    url: str,
    destination: pathlib.Path,
    timeout_seconds: float,
    retries: int,
    expected_md5: str | None = None,
    expected_sha256: str | None = None,
) -> None:
    """Download a fixed upstream artifact atomically, resuming a partial file."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(f".{destination.name}.part")
    retryable = {408, 425, 429, 500, 502, 503, 504}
    for attempt in range(retries + 1):
        # A process may have died after receiving the final byte but before the
        # atomic rename. Promote that complete partial instead of issuing an
        # invalid Range request starting at EOF.
        if partial.is_file():
            md5_matches = expected_md5 is None or md5_file(partial) == expected_md5
            sha_matches = (
                expected_sha256 is None or sha256_file(partial) == expected_sha256
            )
            if md5_matches and sha_matches:
                os.replace(partial, destination)
                return
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"User-Agent": USER_AGENT}
        if offset:
            headers["Range"] = f"bytes={offset}-"
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                status = getattr(response, "status", 200)
                if offset and status != 206:
                    # The origin ignored Range. Restart instead of duplicating bytes.
                    partial.unlink(missing_ok=True)
                    offset = 0
                mode = "ab" if offset and status == 206 else "wb"
                with partial.open(mode) as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
            if expected_md5 is not None:
                actual = md5_file(partial)
                if actual.lower() != expected_md5.lower():
                    raise RuntimeError(
                        f"MD5 mismatch for {url}: expected {expected_md5}, got {actual}"
                    )
            if expected_sha256 is not None:
                actual = sha256_file(partial)
                if actual.lower() != expected_sha256.lower():
                    raise RuntimeError(
                        f"SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual}"
                    )
            os.replace(partial, destination)
            return
        except urllib.error.HTTPError as error:
            detail = error.read(4096).decode("utf-8", "replace")
            if error.code == 416 and partial.exists():
                # A same-sized corrupt partial cannot be resumed. Restart from
                # byte zero, even when the caller requested zero normal retries.
                partial.unlink()
                if attempt == retries:
                    return download_file(
                        url=url,
                        destination=destination,
                        timeout_seconds=timeout_seconds,
                        retries=0,
                        expected_md5=expected_md5,
                        expected_sha256=expected_sha256,
                    )
                continue
            if error.code not in retryable or attempt == retries:
                raise RuntimeError(
                    f"HTTP {error.code} downloading {url}: {detail.strip()}"
                ) from error
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            http.client.IncompleteRead,
        ) as error:
            if attempt == retries:
                raise RuntimeError(f"download failed for {url}: {error}") from error
        except RuntimeError:
            partial.unlink(missing_ok=True)
            raise
        time.sleep(min(2**attempt, 30))


def deterministic_gzip(source: pathlib.Path, destination: pathlib.Path) -> None:
    """Compress with a fixed header, producing byte-identical gzip output."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.part")
    try:
        with source.open("rb") as input_file, temporary.open("wb") as raw_output:
            with gzip.GzipFile(
                filename="", fileobj=raw_output, mode="wb", compresslevel=9, mtime=0
            ) as output:
                shutil.copyfileobj(input_file, output, length=1024 * 1024)
            raw_output.flush()
            os.fsync(raw_output.fileno())
        os.replace(temporary, destination)
    except BaseException:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        raise


def open_text(path: pathlib.Path) -> TextIO:
    if path.name.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", newline="")
    return path.open("r", encoding="utf-8", newline="")


def open_deterministic_gzip_text(path: pathlib.Path) -> tuple[TextIO, pathlib.Path]:
    """Open an atomic, deterministic gzip text output.

    The caller must close the text stream and then replace the returned temporary
    path. Prefer :func:`finish_gzip_text` to do that safely.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.part")
    raw = temporary.open("wb")
    compressed = gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0)
    text = io.TextIOWrapper(compressed, encoding="utf-8", newline="")
    # Keep the underlying raw object alive and accessible to finish_gzip_text.
    text._tetra3_raw = raw  # type: ignore[attr-defined]
    return text, temporary


def finish_gzip_text(stream: TextIO, temporary: pathlib.Path, output: pathlib.Path) -> None:
    raw = stream._tetra3_raw  # type: ignore[attr-defined]
    stream.flush()
    stream.close()
    raw.flush()
    os.fsync(raw.fileno())
    raw.close()
    os.replace(temporary, output)


def post_tap_csv(
    *,
    endpoint: str,
    query: str,
    destination: pathlib.Path,
    maxrec: int,
    timeout_seconds: float,
    retries: int,
) -> None:
    """POST an ADQL query and stream its CSV response to an atomic file."""
    body = urllib.parse.urlencode(
        {
            "REQUEST": "doQuery",
            "LANG": "ADQL",
            "FORMAT": "csv",
            "MAXREC": str(maxrec),
            "QUERY": query,
        }
    ).encode("ascii")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": USER_AGENT,
            "Accept": "text/csv",
        },
        method="POST",
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.part")
    retryable = {408, 425, 429, 500, 502, 503, 504}
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                content_type = response.headers.get_content_type()
                with temporary.open("wb") as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
            # Some TAP implementations label CSV as application/octet-stream.
            with temporary.open("rb") as probe:
                prefix = probe.read(256).lstrip()
            if prefix.startswith(b"<?xml") or prefix.startswith(b"<VOTABLE"):
                detail = prefix.decode("utf-8", "replace")
                raise RuntimeError(f"TAP returned XML instead of CSV: {detail}")
            if content_type not in {
                "text/csv",
                "text/plain",
                "application/octet-stream",
                "application/csv",
            }:
                raise RuntimeError(f"unexpected TAP content type: {content_type}")
            os.replace(temporary, destination)
            return
        except urllib.error.HTTPError as error:
            detail = error.read(4096).decode("utf-8", "replace")
            if error.code not in retryable or attempt == retries:
                raise RuntimeError(
                    f"TAP HTTP {error.code} from {endpoint}: {detail.strip()}"
                ) from error
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if attempt == retries:
                raise RuntimeError(f"TAP request failed for {endpoint}: {error}") from error
        except RuntimeError:
            temporary.unlink(missing_ok=True)
            raise
        finally:
            if temporary.exists() and attempt == retries:
                temporary.unlink()
        # Bounded exponential backoff with a deterministic delay.
        time.sleep(min(2**attempt, 30))


def validate_csv(
    path: pathlib.Path,
    expected_header: Sequence[str],
    *,
    numeric_id: str,
    lower_id: int | None = None,
    upper_id: int | None = None,
    magnitude_column: str | None = None,
    magnitude_limit: float | None = None,
) -> dict[str, int | float | None]:
    """Fully parse a CSV, enforcing schema, ordering, and optional bounds."""
    rows = 0
    first_id: int | None = None
    last_id: int | None = None
    min_magnitude: float | None = None
    max_magnitude: float | None = None
    with path.open("r", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(expected_header):
            raise ValueError(
                f"unexpected CSV header in {path}: {reader.fieldnames!r}; "
                f"expected {list(expected_header)!r}"
            )
        for line_number, row in enumerate(reader, start=2):
            try:
                identifier = int(row[numeric_id])
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"invalid {numeric_id} at {path}:{line_number}") from error
            if lower_id is not None and identifier < lower_id:
                raise ValueError(f"id {identifier} below chunk at {path}:{line_number}")
            if upper_id is not None and identifier >= upper_id:
                raise ValueError(f"id {identifier} above chunk at {path}:{line_number}")
            if last_id is not None and identifier <= last_id:
                raise ValueError(f"ids not strictly increasing at {path}:{line_number}")
            if first_id is None:
                first_id = identifier
            last_id = identifier
            if magnitude_column is not None:
                try:
                    magnitude = float(row[magnitude_column])
                except (KeyError, TypeError, ValueError) as error:
                    raise ValueError(f"invalid magnitude at {path}:{line_number}") from error
                if not math.isfinite(magnitude):
                    raise ValueError(f"non-finite magnitude at {path}:{line_number}")
                if magnitude_limit is not None and magnitude > magnitude_limit + 1e-6:
                    raise ValueError(
                        f"magnitude {magnitude} exceeds limit at {path}:{line_number}"
                    )
                min_magnitude = (
                    magnitude if min_magnitude is None else min(min_magnitude, magnitude)
                )
                max_magnitude = (
                    magnitude if max_magnitude is None else max(max_magnitude, magnitude)
                )
            rows += 1
    return {
        "rows": rows,
        "first_id": first_id,
        "last_id": last_id,
        "min_magnitude": min_magnitude,
        "max_magnitude": max_magnitude,
    }


def float_or_none(value: str | None) -> float | None:
    if value is None or not value.strip():
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def stable_float(value: float | None, digits: int = 12) -> str:
    if value is None:
        return ""
    return format(value, f".{digits}g")


def radec_to_vector(ra_deg: float, dec_deg: float) -> tuple[float, float, float]:
    ra = math.radians(ra_deg)
    dec = math.radians(dec_deg)
    cos_dec = math.cos(dec)
    return cos_dec * math.cos(ra), cos_dec * math.sin(ra), math.sin(dec)


def vector_to_radec(vector: Sequence[float]) -> tuple[float, float]:
    x, y, z = vector
    norm = math.sqrt(x * x + y * y + z * z)
    if norm == 0.0:
        raise ValueError("zero direction vector")
    x, y, z = x / norm, y / norm, z / norm
    return math.degrees(math.atan2(y, x)) % 360.0, math.degrees(math.asin(z))


def propagate_radec(
    ra_deg: float,
    dec_deg: float,
    pmra_masyr: float | None,
    pmdec_masyr: float | None,
    from_epoch: float,
    to_epoch: float,
) -> tuple[float, float]:
    """Apply catalog proper motion on the unit sphere.

    ``pmra`` follows the Gaia/Hipparcos convention and already includes
    cos(dec). Radial velocity/perspective acceleration are intentionally not
    modeled; the approximation is ample for matching and plate-index epochs.
    """
    if pmra_masyr is None or pmdec_masyr is None or from_epoch == to_epoch:
        return ra_deg % 360.0, dec_deg
    ra = math.radians(ra_deg)
    dec = math.radians(dec_deg)
    cos_dec = math.cos(dec)
    sin_dec = math.sin(dec)
    x = cos_dec * math.cos(ra)
    y = cos_dec * math.sin(ra)
    z = sin_dec
    east = (-math.sin(ra), math.cos(ra), 0.0)
    north = (-sin_dec * math.cos(ra), -sin_dec * math.sin(ra), cos_dec)
    dt = to_epoch - from_epoch
    dra = pmra_masyr * MAS_TO_RAD * dt
    ddec = pmdec_masyr * MAS_TO_RAD * dt
    moved = (
        x + dra * east[0] + ddec * north[0],
        y + dra * east[1] + ddec * north[1],
        z + dra * east[2] + ddec * north[2],
    )
    return vector_to_radec(moved)


def angular_separation_arcsec(a: Sequence[float], b: Sequence[float]) -> float:
    dot = max(-1.0, min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]))
    # atan2 is well behaved for the tiny cross-match angles where acos loses
    # precision.
    cx = a[1] * b[2] - a[2] * b[1]
    cy = a[2] * b[0] - a[0] * b[2]
    cz = a[0] * b[1] - a[1] * b[0]
    cross = math.sqrt(cx * cx + cy * cy + cz * cz)
    return math.degrees(math.atan2(cross, dot)) * 3600.0


def iter_csv_paths(paths: Iterable[pathlib.Path]) -> Iterator[dict[str, str]]:
    for path in paths:
        with open_text(path) as source:
            yield from csv.DictReader(source)
