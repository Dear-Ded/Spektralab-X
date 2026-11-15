#!/usr/bin/env python3
"""Research-only Canon TLV inventory through an external, hash-pinned codec.

The external implementation has no declared license and is deliberately not
bundled, imported by Android, or copied into this tool. Only read-only analysis
of publicly downloadable profiles is performed. No LUT/curve payload is exported.
"""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct

from inspect_profiles import parse_pf, span

REFERENCE_SHA256 = "4005569993a94853e77ea61df0d6f7d9b9fe396687d3711c6d36531bb35db75e"
LABELS = {0x1002: "name", 0x1009: "baseStyleIdentifier", 0x100a: "copyright",
          0x1018: "curveBlockA", 0x1019: "curveBlockB", 0x1021: "baseColourDataA",
          0x1029: "baseColourDataB", 0x1030: "sharpness", 0x1031: "contrast",
          0x1032: "saturation", 0x1033: "colourTone", 0x1070: "candidateLutA",
          0x1071: "candidateLutB", 0xfffe: "checksum", 0xffff: "end"}


def inspect_payload(payload):
    offset, rows, ended = 0, [], False
    while offset < len(payload):
        if len(rows) >= 1024:
            raise ValueError("Too many Canon TLV records")
        tag, typ, length = struct.unpack(">HHI", span(payload, offset, 8))
        value = span(payload, offset + 8, length)
        row = {"tag": f"0x{tag:04x}", "label": LABELS.get(tag, "uninterpreted"),
               "type": typ, "deobfuscatedPayloadOffset": offset, "valueBytes": length,
               "valueSha256": hashlib.sha256(value).hexdigest()}
        if tag in (0x1002, 0x100a) and typ == 2:
            row["text"] = value.split(b"\0", 1)[0].decode("latin1")
        elif tag == 0x1009 and typ == 3 and length == 2:
            row["baseStyleIdentifier"] = struct.unpack(">H", value)[0]
        elif tag in (0x1030, 0x1031, 0x1032, 0x1033) and length == 4:
            row["controlInteger"] = struct.unpack(">i", value)[0]
        if tag == 0xfffe:
            if length != 4:
                raise ValueError("Invalid Canon checksum length")
            row["checksumValid"] = struct.unpack(">I", value)[0] == (sum(payload[:offset]) & 0xffffffff)
            if not row["checksumValid"]:
                raise ValueError("Canon payload checksum mismatch")
        rows.append(row)
        offset += 8 + length
        if tag == 0xffff:
            if length:
                raise ValueError("Canon end marker has a payload")
            ended = True
            break
    if not ended or offset != len(payload):
        raise ValueError("Missing end marker or unaccounted trailing Canon payload")
    return rows


def run(reference, profiles, output):
    source = reference.read_bytes()
    if hashlib.sha256(source).hexdigest() != REFERENCE_SHA256:
        raise ValueError("Unexpected external reference; refusing to load")
    spec = importlib.util.spec_from_file_location("external_canon_analysis_codec", reference)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    rows = []
    for path in profiles:
        data = path.read_bytes()
        envelope = parse_pf(data)
        # Only the reference's byte transform is used. TLV bounds, end marker
        # and optional checksum are checked independently here.
        payload = module.crypt(data[12:])
        decoded = inspect_payload(payload)
        _, reference_records, _ = module.read_style(path)
        actual_tags = [(r["tag"], r["type"], r["valueBytes"]) for r in decoded]
        expected_tags = [(f"0x{tag:04x}", typ, len(value)) for tag, typ, value in reference_records]
        if actual_tags != expected_tags:
            raise AssertionError("Independent Canon TLV traversal disagreement")
        rows.append({"file": path.name, "sha256": envelope["sha256"], "bytes": len(data),
                     "version": envelope["version"], "payloadBytesAccounted": len(payload),
                     "records": decoded, "renderingMathVerified": False})
    result = {"externalCodecSha256": REFERENCE_SHA256,
              "externalCodecLicense": "no declared license; external analysis only",
              "testedScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "profiles": rows, "files": len(rows),
              "records": sum(len(r["records"]) for r in rows),
              "checksumsPresentAndValid": sum("checksumValid" in e for r in rows for e in r["records"]),
              "renderingStatus": "TLV structure/controls observed; base colour math, curve semantics and vendor JPEG parity unverified"}
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "profiles"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("profiles", nargs="+", type=Path)
    args = parser.parse_args()
    run(args.reference, args.profiles, args.output)
