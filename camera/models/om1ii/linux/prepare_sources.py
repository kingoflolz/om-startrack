#!/usr/bin/env python3
"""Prepare target-qualified Linux sources in a private OM-1 II build snapshot.

Shared Linux code is adapted only in a private build directory. Exact text
anchors guard the target address and data-layout substitutions.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import re
import tempfile


HERE = Path(__file__).resolve().parent
MAPPING_FILES = ("settings-tables.json", "lens-map.json", "runtime-map.json", "legality-map.json", "orf-map.json")
HEX_TOKEN = re.compile(r"(?<![A-Za-z0-9_])0x[0-9a-fA-F]+(?![A-Za-z0-9_])")
TELEMETRY_MAP = {
    0x2A2985FA4: 0x2A29AF620,  # Native command mailbox.
    0x2A2985FB8: 0x2A29AF634,  # Mailbox ready byte.
    0x2A2985FBC: 0x2A29AF638,  # Capture/preposition coordinator.
    0x2A320B984: 0x2A320F97C,  # Paired PTP initiator slot.
    0x2A3209010: 0x2A320CFFC,  # Stock IBIS telemetry enable.
    0x2A320B108: 0x2A320F0FA,  # Stock IBIS telemetry mirror.
    0x2A034E170: 0x2A034E2C4,  # per_slope mapper + two-sample ring.
    0x2A0080D98: 0x2A008284E,  # Publisher and remapper code anchors.
}


def _read_json(name: str):
    return json.loads((HERE / name).read_text())


def _replace(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    if text.count(old) != count:
        raise ValueError(f"{label}: expected {count} exact source occurrences, got {text.count(old)}")
    return text.replace(old, new)


def _translate(text: str, mapping: dict[int, int]) -> str:
    return HEX_TOKEN.sub(lambda m: (f"0x{mapping[int(m.group(), 16)]:x}"
                                  if int(m.group(), 16) in mapping else m.group()), text)


def _target_file(root: Path, name: str) -> bytes:
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root.resolve(strict=True)):
        raise ValueError(f"rootfs ABI input escapes root: {name}")
    return path.read_bytes()


def prepare_sources(source_snapshot: Path, stock: Path, target_rootfs: Path) -> dict:
    source_snapshot = source_snapshot.resolve(strict=True)
    if (source_snapshot / ".git").exists() or not (source_snapshot / "camera/linux").is_dir():
        raise ValueError("Linux adapter requires a private source snapshot, never the working repository")
    target = stock.read_bytes()
    originals = {}
    for name in _read_json("files.json"):
        originals[name] = (source_snapshot / name).read_text()

    mapping = dict(TELEMETRY_MAP)
    changes: list[dict] = []
    records = []
    orf_template_count = 0
    for name in MAPPING_FILES:
        record = _read_json(name)
        for old, new in record.get("hex_replacements", {}).items():
            a, b = int(old, 16), int(new, 16)
            if a in mapping and mapping[a] != b:
                raise ValueError(f"conflicting native address mappings for {a:#x}")
            mapping[a] = b
        changes.extend(record.get("text_replacements", []))
        records.append(name)

    # Apply numeric translations simultaneously. Exact-text changes are translated
    # by the same map before matching, so no replacement is accidentally cascaded.
    prepared = {name: _translate(text, mapping) for name, text in originals.items()}
    for change in changes:
        name = change["path"]
        if name not in prepared:
            raise ValueError(f"unknown file in target mapping: {name}")
        old = _translate(change["old"], mapping)
        new = _translate(change["new"], mapping)
        prepared[name] = _replace(prepared[name], old, new, f"{name} mapping", change.get("count", 1))

    # Public setting 2138 selects the new LiveGND exposure branch. Every provider
    # participates in the existing paired strict/hot snapshots before use.
    name = "camera/linux/star-worker/devmem_state.cpp"
    prepared[name] = _replace(prepared[name], "  kProviderIso1673,\n", "  kProviderIso1673,\n  kProviderIso2138,\n", "LiveGND provider index")
    prepared[name] = _replace(prepared[name], "kDirectProviderCount == 36", "kDirectProviderCount == 37", "LiveGND provider count")
    prepared[name] = _replace(prepared[name], "    {-1, 0x4501,", "    {2138, 0xa941, 0, 1, 0x6294d3b8, UINT64_C(0x2a31f41a4)},\n    {-1, 0x4501,", "LiveGND provider descriptor")
    prepared[name] = _replace(prepared[name], "  snapshot->iso1673 = first.value[kProviderIso1673];\n", "  snapshot->iso1673 = first.value[kProviderIso1673];\n  snapshot->iso2138 = first.value[kProviderIso2138];\n", "LiveGND snapshot field")

    spec = importlib.util.spec_from_file_location("_om1ii_legality", HERE / "port_legality.py")
    if spec is None or spec.loader is None:
        raise ValueError("cannot load target legality adapter")
    legality = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(legality)
    with tempfile.TemporaryDirectory(prefix=".om1ii-legality-", dir=source_snapshot) as directory:
        worker = Path(directory)
        names = ("camera/linux/star-worker/setting_catalog_direct.h",
                 "camera/linux/star-worker/setting_catalog_direct.cpp")
        for name in names:
            (worker / Path(name).name).write_text(prepared[name])
        legality_audit = legality.apply_legality(worker, target)
        for name in names:
            prepared[name] = (worker / Path(name).name).read_text()

    for name in ("camera/linux/star-worker/camera.cpp", "camera/linux/star-worker/devmem_state.cpp"):
        prepared[name] = prepared[name].replace("OM-1 v1.9", "OM-1 Mark II v1.3")

    for name, text in prepared.items():
        (source_snapshot / name).write_text(text)
    return {"model": "om1ii", "files": list(prepared)}
