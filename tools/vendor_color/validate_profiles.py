#!/usr/bin/env python3
"""Independent profile-byte checks and a bounded HSV table-component experiment."""

import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path
import random
import struct
import subprocess
import tempfile
import unittest

from inspect_profiles import (compact, fingerprint, inspect_file, lens_inventory,
                              parse_dcp, parse_np3, parse_pf, sample_hsv_table)
from fetch_sources import PINS, wanted
from inspect_canon_reference import inspect_payload


def tiff_fixture(endian="<", entries=None):
    entries = entries if entries is not None else [
        (50937, 4, [2, 2, 1]), (50938, 11, [0, 1, 1] * 4),
        (50941, 4, [3]), (65000, 7, list(b"unknown")),
    ]
    size = 8 + 2 + 12 * len(entries) + 4
    payload, rows = bytearray(), []
    for tag, typ, values in entries:
        fmt = {4: "I", 11: "f", 7: "B"}[typ]
        raw = struct.pack(endian + fmt * len(values), *values)
        field = raw.ljust(4, b"\0") if len(raw) <= 4 else struct.pack(endian + "I", size + len(payload))
        rows.append(struct.pack(endian + "HHI", tag, typ, len(values)) + field)
        if len(raw) > 4:
            payload.extend(raw)
    return ((b"II" if endian == "<" else b"MM") + struct.pack(endian + "HIH", 0x4352, 8, len(entries))
            + b"".join(rows) + struct.pack(endian + "I", 0) + payload)


class StructuralTests(unittest.TestCase):
    def test_canon_tlv_checksum_and_end_marker(self):
        body = struct.pack(">HHI", 0x1002, 2, 5) + b"test\0"
        body += struct.pack(">HHII", 0xfffe, 4, 4, sum(body))
        body += struct.pack(">HHI", 0xffff, 0xffff, 0)
        records = inspect_payload(body)
        self.assertEqual(records[0]["text"], "test")
        self.assertTrue(records[1]["checksumValid"])
        for length in range(len(body)):
            with self.assertRaises(ValueError):
                inspect_payload(body[:length])
        for bad in [body + b"\0", body.replace(b"test", b"best"),
                    struct.pack(">HHI", 0xffff, 0xffff, 1) + b"a"]:
            with self.assertRaises(ValueError):
                inspect_payload(bad)

    def test_real_lensfun_doctype_without_external_resolution(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.xml"
            path.write_text('<!DOCTYPE lensdatabase SYSTEM "lensfun-database.dtd">\n'
                            '<lensdatabase version="2"><camera/><lens><maker>Zeiss</maker>'
                            '<calibration><distortion model="poly3" focal="35" k1="0.01"/>'
                            '</calibration></lens></lensdatabase>')
            result = lens_inventory([path])
            self.assertEqual(result["lensEntries"], 1)
            self.assertEqual(result["calibrationRecordsByTypeAndModel"], {"distortion:poly3": 1})
            self.assertFalse(result["colourTransmissionData"])

    def test_both_byte_orders_and_unknown_data_accounting(self):
        for endian in ("<", ">"):
            data = tiff_fixture(endian)
            result = parse_dcp(data)
            self.assertEqual(result["entries"][0]["values"], [2, 2, 1])
            accounting = result["byteAccounting"]
            self.assertEqual(accounting["decodedOrFramedBytes"] + sum(r["bytes"] for r in accounting["uninterpretedRanges"]), len(data))
            self.assertEqual(accounting["uninterpretedRanges"][-1]["bytes"], 7)

    def test_truncated_dcp(self):
        data = tiff_fixture()
        for length in range(len(data)):
            with self.assertRaises(ValueError):
                parse_dcp(data[:length])

    def test_dcp_cycles_and_duplicate_tags(self):
        data = bytearray(tiff_fixture())
        struct.pack_into("<I", data, 8 + 2 + 12 * 4, 8)
        with self.assertRaises(ValueError):
            parse_dcp(data)
        with self.assertRaises(ValueError):
            parse_dcp(tiff_fixture(entries=[(50941, 4, [1]), (50941, 4, [2])]))

    def test_dcp_invalid_shape_nonfinite_and_counts(self):
        for entries in [
            [(50937, 4, [2, 2, 1]), (50938, 11, [0, 1, 1])],
            [(50938, 11, [0, 1, 1])],
            [(50938, 11, [float("nan")])],
            [(50937, 4, [2, 1, 1]), (50938, 11, [0, 1, 1] * 2)],
            [(50940, 11, [0, 0, 0, 1])],
        ]:
            with self.assertRaises(ValueError):
                parse_dcp(tiff_fixture(entries=entries))
        data = bytearray(tiff_fixture())
        struct.pack_into("<I", data, 14, 0xffffffff)
        with self.assertRaises(ValueError):
            parse_dcp(data)

    def test_np3_short_variant_and_truncated_comment(self):
        for data in [b"", b"NCP\0" + bytes(388), b"NCP\0" + bytes(8) + b"0410" + bytes(376)]:
            with self.assertRaises(ValueError):
                parse_np3(data)
        data = bytearray(b"NCP\0" + bytes(8) + b"0310" + bytes(376))
        data[0x185:0x187] = b"\1\1"
        for payload in [b"", struct.pack(">I", 0xffffffff), struct.pack(">I", 2) + b"ab" + bytes(4)]:
            with self.assertRaises(ValueError):
                parse_np3(data + payload)

    def test_np3_invalid_tone_points(self):
        data = bytearray(b"NCP\0" + bytes(8) + b"0310" + bytes(962))
        data[0x187] = 2
        data[0x194] = 28
        with self.assertRaises(ValueError):
            parse_np3(data)

    def test_canon_envelope_size(self):
        body = bytes(range(64))
        data = struct.pack(">I4sI", 0x2000, b"PSP\0", len(body)) + body
        self.assertEqual(parse_pf(data)["declaredBodyBytes"], 64)
        for bad in [data[:-1], data + b"\0", bytes(12)]:
            with self.assertRaises(ValueError):
                parse_pf(bad)

    def test_table_identity_single_hue_and_boundaries(self):
        for dims in [[1, 2, 1], [6, 4, 1], [6, 4, 3]]:
            values = [0.0, 1.0, 1.0] * math.prod(dims)
            for h in [-720, -0.00001, 0, 59.99, 359.99999, 360, 720]:
                for s in [0, 0.5, 1]:
                    for v in [0, 0.0031308, 0.5, 1]:
                        for enc in [0, 1]:
                            for actual, expected in zip(sample_hsv_table(dims, values, h, s, v, enc), [0, 1, 1]):
                                self.assertAlmostEqual(actual, expected, places=12)

    def test_table_rejects_undefined_inputs(self):
        for args in [([2, 1, 1], [0] * 6, 0, 0, 0, 0),
                     ([1, 2, 1], [0] * 6, 0, -1, 0, 0),
                     ([1, 2, 1], [0] * 6, 0, 0, 2, 0),
                     ([1, 2, 1], [0] * 6, float("nan"), 0, 0, 0),
                     ([1, 2, 1], [0] * 6, 0, 0, 0, 2),
                     ([1, 2, 1], [0] * 5, 0, 0, 0, 0)]:
            with self.assertRaises(ValueError):
                sample_hsv_table(*args)

    def test_next_representable_cyclic_hue_boundary(self):
        for h in [1, 2, 6, 7, 90, 100, 129, 256]:
            values = [component for hi in range(h) for _ in range(2)
                      for component in [float(hi), 0.8 + hi / h * 0.1, 1.0]]
            expected = sample_hsv_table([h, 2, 1], values, 0, 1, 1)
            for hue in [math.nextafter(360, 0), math.nextafter(0, -1), -360, 360]:
                actual = sample_hsv_table([h, 2, 1], values, hue, 1, 1)
                for a, b in zip(actual, expected):
                    self.assertAlmostEqual(a, b, delta=1e-9)


def analytic_table_check():
    """An independently evaluated multilinear analytic function, including H wrap.

    Tests axis order, trilinear cross terms and encoded V coordinates. This is
    not an oracle for dual illuminants, RGB rendering, or manufacturer output.
    """
    rng = random.Random(20261115)
    dims = [7, 5, 4]
    table = []
    for vi in range(dims[2]):
        z = vi / (dims[2] - 1)
        for hi in range(dims[0]):
            x = math.sin(hi * 2 * math.pi / dims[0])
            for si in range(dims[1]):
                y = si / (dims[1] - 1)
                table.extend([12 * x + 3 * y * z, 1 + 0.2 * y + 0.1 * x * z, 1 + 0.3 * z + 0.1 * x * y])
    max_error = 0.0
    samples = 8192
    for i in range(samples):
        hue, sat, val = rng.uniform(-720, 720), rng.random(), rng.random()
        encoding = i % 2
        h = (hue % 360) / 360 * dims[0]
        index, fraction = math.floor(h), h % 1
        x = ((1 - fraction) * math.sin(index * 2 * math.pi / dims[0]) +
             fraction * math.sin(((index + 1) % dims[0]) * 2 * math.pi / dims[0]))
        z = (12.92 * val if val <= 0.0031308 else 1.055 * val ** (1 / 2.4) - 0.055) if encoding else val
        expected = [12 * x + 3 * sat * z, 1 + 0.2 * sat + 0.1 * x * z, 1 + 0.3 * z + 0.1 * x * sat]
        actual = sample_hsv_table(dims, table, hue, sat, val, encoding)
        max_error = max(max_error, *(abs(a - b) for a, b in zip(actual, expected)))
    if max_error > 2e-13:
        raise AssertionError(f"HSV component interpolation error: {max_error}")
    return {"samples": samples, "maximumAbsoluteComponentError": max_error,
            "reference": "independent analytic multilinear function; not a full DCP renderer"}


def check_exiftool(path, result):
    # -m requests actual large-array decoding; otherwise ExifTool reports only a placeholder.
    requested = ["-" + e["name"] for e in result["entries"] if not e["name"].startswith("UnknownTag")]
    reference = json.loads(subprocess.check_output(
        ["exiftool", "-m", "-j", "-n", "-s", "-b", *requested, str(path)], stderr=subprocess.PIPE))[0]
    compared, numeric, max_error, missing = 0, 0, 0.0, []
    for entry in result["entries"]:
        if entry["name"].startswith("UnknownTag"):
            continue
        key = entry["name"]
        if key not in reference:
            missing.append(key)
            continue
        expected = entry["values"]
        actual = reference[key]
        if isinstance(expected, str):
            if expected != str(actual):
                raise AssertionError(f"ExifTool text mismatch: {path.name}/{key}")
        else:
            values = [float(actual)] if isinstance(actual, (int, float)) else [float(x) for x in actual.split()]
            if len(values) != len(expected):
                raise AssertionError(f"ExifTool count mismatch: {path.name}/{key}")
            for a, b in zip(values, expected):
                if not math.isclose(a, b, rel_tol=2e-8, abs_tol=2e-7):
                    raise AssertionError(f"ExifTool numeric mismatch: {path.name}/{key}: {a} != {b}")
                max_error = max(max_error, abs(a - b))
            numeric += len(values)
        compared += 1
    if missing:
        raise AssertionError(f"Known tags missing in independent parser: {path.name}: {missing}")
    return {"tagsCompared": compared, "numericValuesCompared": numeric,
            "maxPrintedNumericDifference": max_error}


def run(corpus, expected_path, output):
    output.mkdir(parents=True, exist_ok=True)
    source_counts = {}
    for repo in PINS:
        tree = json.loads((corpus / (repo.replace("/", "_") + "-tree.json")).read_text())
        rows = [row for row in tree["tree"] if row["type"] == "blob" and wanted(repo, row["path"])]
        for row in rows:
            data = (corpus / repo / row["path"]).read_bytes()
            blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            if blob != row["sha"]:
                raise AssertionError(f"Pinned corpus byte mismatch: {repo}/{row['path']}")
        source_counts[repo] = len(rows)
    stream = io.StringIO()
    unit = unittest.TextTestRunner(stream=stream, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(StructuralTests))
    (output / "structural-tests.log").write_text(stream.getvalue())
    if not unit.wasSuccessful():
        raise AssertionError(stream.getvalue())
    summary = {"pinnedSourceFilesVerified": source_counts,
               "structuralTests": unit.testsRun, "structuralFailures": len(unit.failures) + len(unit.errors),
               "analyticHsvComponent": analytic_table_check(),
               "exiftoolVersion": subprocess.check_output(["exiftool", "-ver"], text=True).strip()}
    np3_rows = json.loads(expected_path.read_text())
    published = {Path(row["file"]).name for row in np3_rows if row["kind"] == "published-sample"}
    available = {p.name for p in (corpus / "ssssota/nikon-flexible-color-picture-control/samples").glob("*.NP3")}
    if published != available or len(np3_rows) - len(published) != 96:
        raise AssertionError("NP3 oracle coverage is incomplete or stale")
    np3 = []
    for row in np3_rows:
        data = Path(row["file"]).read_bytes()
        actual = parse_np3(data)
        if actual["decoded"] != row["expected"]:
            raise AssertionError(f"NP3 reference disagreement: {Path(row['file']).name}")
        np3.append({"file": Path(row["file"]).name, "kind": row["kind"], **fingerprint(data),
                    "byteAccounting": actual["byteAccounting"]})
    summary["np3"] = {"publishedSamples": sum(r["kind"] == "published-sample" for r in np3),
                      "referenceGenerated": sum(r["kind"] == "reference-generated" for r in np3), "fieldDisagreements": 0}
    (output / "np3-comparison.json").write_text(json.dumps(np3, indent=2) + "\n")
    dcp = []
    for path in sorted((corpus / "RawTherapee/RawTherapee/rtdata/dcpprofiles").glob("*.dcp")):
        result = parse_dcp(path.read_bytes())
        comparison = check_exiftool(path, result)
        dcp.append({"file": path.name, **compact(result), "independentComparison": comparison})
    if not dcp:
        raise AssertionError("No DCP files available")
    summary["dcp"] = {"profiles": len(dcp), "tagsCompared": sum(r["independentComparison"]["tagsCompared"] for r in dcp),
                      "numericValuesCompared": sum(r["independentComparison"]["numericValuesCompared"] for r in dcp),
                      "maxPrintedNumericDifference": max(r["independentComparison"]["maxPrintedNumericDifference"] for r in dcp),
                      "filenameFirstWords": dict(Counter(r["file"].split()[0] for r in dcp))}
    (output / "dcp-inventory.json").write_text(json.dumps(dcp, indent=2) + "\n")
    canon = [{"file": path.name, **inspect_file(path)} for path in sorted((corpus / "canon-official").glob("*.pf2"))]
    if len(canon) != 8:
        raise AssertionError("Expected all eight acquired official Canon files")
    summary["canon"] = {"officialFiles": len(canon), "decodedRenderingBodies": 0}
    (output / "canon-inventory.json").write_text(json.dumps(canon, indent=2) + "\n")
    lens = lens_inventory((corpus / "lensfun/lensfun/data/db").glob("*.xml"))
    summary["lensfun"] = {k: v for k, v in lens.items() if k != "files"}
    summary["lensfun"]["xmlFiles"] = len(lens["files"])
    (output / "lens-inventory.json").write_text(json.dumps(lens, indent=2) + "\n")
    summary["sourceFingerprints"] = [{"file": p.name, **fingerprint(p.read_bytes())}
                                     for p in sorted(Path(__file__).parent.glob("*")) if p.suffix in (".py", ".mjs")]
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--np3-expected", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    run(args.corpus, args.np3_expected, args.output)
