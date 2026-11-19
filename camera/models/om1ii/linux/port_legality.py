"""Reviewed OM-1 II v1.3 direct-exposure evaluator source adaptation.

This edits a caller-owned source snapshot, never camera/linux's pinned sources.
The firmware-derived oracle is retained in the returned audit for verification.
"""
from __future__ import annotations

import struct
from pathlib import Path

BASE = 0x60021000


def _replace(text: str, old: str, new: str, count: int = 1) -> str:
    actual = text.count(old)
    if actual != count:
        raise ValueError(f"legality source anchor has {actual} matches, expected {count}: {old!r}")
    return text.replace(old, new)


def _fnv(data: bytes) -> int:
    h = 0x811C9DC5
    for b in data:
        h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    return h


def _slice(stock: bytes, address: int, length: int) -> bytes:
    offset = address - BASE
    if offset < 0 or offset + length > len(stock):
        raise ValueError("firmware evidence range outside decoded image")
    return stock[offset:offset + length]


def decode_iso_rules(stock: bytes, address: int, count: int) -> list[dict]:
    rules = []
    for index in range(count):
        row = address + index * 16
        conditions, nconditions, mapping, nmapping, default = struct.unpack(
            "<IIIHh", _slice(stock, row, 16))
        if nconditions > 10 or nmapping > 40:
            raise ValueError("unexpected ISO rule bounds")
        rules.append({
            "address": row,
            "conditions_address": conditions,
            "conditions": [list(struct.unpack("<HhI", _slice(stock, conditions + i * 8, 8)))
                           for i in range(nconditions)],
            "mapping_address": mapping,
            "mapping": [list(struct.unpack("<hh", _slice(stock, mapping + i * 4, 4)))
                        for i in range(nmapping)],
            "default": default,
        })
    return rules


ISO_1671_REPLACEMENT = r'''bool iso_1671_legal(const Snapshot &snapshot, int16_t value) {
  // OM-1 II v1.3, ordered rules at 0x605b8ca0 (16 records).
  const bool automatic = value == 0;
  const int32_t s24 = snapshot.context_values[7];
  const int32_t s1679 = snapshot.context_values[8];
  const int32_t s2138 = snapshot.iso2138;
  if (snapshot.iso598 == 1 || snapshot.iso598 == 2)
    return value == 1536;
  if ((s24 == 6 && snapshot.current1669 == INT16_MIN &&
       snapshot.iso434 == 0) ||
      (s24 == 6 && snapshot.current1669 == INT16_MAX &&
       snapshot.iso435 == 0)) {
    return !automatic;
  }
  if (s24 == 6 &&
      (snapshot.current1669 == INT16_MIN ||
       snapshot.current1669 == INT16_MAX ||
       snapshot.current1669 == INT16_MIN + 1)) {
    return !automatic && value <= 2816;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 0 && s2138 == 0 && snapshot.iso1712 == 5) {
    return !automatic && value <= 3072;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 0 && s2138 == 0) {
    return !automatic;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 1 && s2138 == 0) {
    return !automatic && value <= 2048;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 1 &&
      snapshot.iso1678 == 0 && s2138 == 0) {
    return !automatic && value <= 2304;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 0 && s2138 == 1) {
    return !automatic && value <= 2560;
  }
  if (snapshot.iso754 == 1 && snapshot.iso1678 == 0 && s2138 == 0)
    return automatic || value <= 2304;
  if (snapshot.iso754 == 0 && s1679 == 1 && s2138 == 0)
    return automatic || value <= 2048;
  if (snapshot.iso754 == 0 && s1679 == 0 && s2138 == 1)
    return automatic || value <= 2560;
  if (s2138 == 0 && snapshot.iso1712 == 5)
    return automatic || value <= 3072;
  return true;
}

'''


def firmware_evidence(stock: bytes) -> dict:
    specs = [
        ("dispatch", 0x605A5E88, 0x3D68),
        ("postfilter", 0x605AACD4, 0x224),
        ("aperture", 0x606C7ACC, 0xA2),
        ("shutter", 0x606D1DC4, 0xE20),
        ("iso_registry", 0x605C8C6C, 0x1A54),
        ("iso598_conditions", 0x605ACADC, 0x10),
        ("iso754_conditions", 0x605B869C, 0x18),
        ("iso1712_conditions", 0x605B86E4, 0x10),
        ("iso_rules", 0x605B856C, 0xB74),
    ]
    spans = []
    for name, address, length in specs:
        data = _slice(stock, address, length)
        spans.append({"name": name, "address": address, "length": length,
                      "fnv1a": _fnv(data)})
    if struct.unpack("<5I", _slice(stock, 0x605C95E0, 20)) != (
            0x0C800687, 0x605B8CA0, 16, 0, 0):
        raise ValueError("unexpected source-1671 registry record")
    if struct.unpack("<5I", _slice(stock, 0x605C95F4, 20)) != (
            0x0C800688, 0x605B9040, 10, 0, 0):
        raise ValueError("unexpected source-1672 registry record")
    return {"spans": spans,
            "iso1671": decode_iso_rules(stock, 0x605B8CA0, 16),
            "iso1672": decode_iso_rules(stock, 0x605B9040, 10),
            "normal_shutter_requires_setting2138_zero": True,
            "setting2139_read_unnecessary_when_2138_zero": True}


def apply_legality(source_root: Path, stock: bytes | Path) -> dict:
    """Adapt <source_root>/star-worker or a direct star-worker source directory."""
    source_root = Path(source_root)
    worker = source_root / "star-worker" if (source_root / "star-worker").is_dir() else source_root
    if not isinstance(stock, bytes):
        stock = Path(stock).read_bytes()
    audit = firmware_evidence(stock)
    header_path = worker / "setting_catalog_direct.h"
    cpp_path = worker / "setting_catalog_direct.cpp"
    header = header_path.read_text()
    cpp = cpp_path.read_text()
    header = _replace(header, "  int32_t iso1673;", "  int32_t iso1673;\n  int32_t iso2138;")
    header = _replace(header, "  int32_t signed_fields[11];", "  int32_t signed_fields[12];")
    start = cpp.index("bool iso_1671_legal(")
    end = cpp.index("bool iso_1672_legal(", start)
    cpp = cpp[:start] + ISO_1671_REPLACEMENT + cpp[end:]
    cpp = _replace(cpp, "      snapshot.context_values[8] != 0 || snapshot.mode_helper == 62 ||",
                   "      snapshot.context_values[8] != 0 || snapshot.iso2138 != 0 ||\n"
                   "      snapshot.mode_helper == 62 ||")
    cpp = _replace(cpp, "      snapshot.iso1606,     snapshot.iso1673};",
                   "      snapshot.iso1606,     snapshot.iso1673, snapshot.iso2138};")
    cpp = _replace(cpp, "      snapshot->iso1606,     snapshot->iso1673};",
                   "      snapshot->iso1606,     snapshot->iso1673, snapshot->iso2138};")
    cpp = _replace(cpp, "index < 11; ++index", "index < 12; ++index", 2)
    cpp = _replace(cpp, "  if (!native) return false;",
                   "  if (!native) return false;\n"
                   "  if (setting == kShutter && snapshot->iso2138 != 0) return false;")
    cpp = _replace(cpp, "  if (!signed16(snapshot->current1669)) return Status::kInvalid;",
                   "  if (!signed16(snapshot->current1669)) return Status::kInvalid;\n"
                   "  // OM-1 II's additional shutter callback for setting 2138 is\n"
                   "  // outside the reviewed normal-still capture domain.\n"
                   "  if (snapshot->iso2138 != 0) return Status::kUnsupported;")
    header_path.write_text(header)
    cpp_path.write_text(cpp)
    return audit
