#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Describe signed-input failures; no automatic LUT acceptance or domain policy."""
import argparse
import csv
import hashlib
import json
import math
import struct
from pathlib import Path
from collections import defaultdict

from gate_report import linear_p3_lab, require, summary
from ciede2000_reference import delta_e00, verify


def load(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def rgb(row, prefix):
    result = tuple(float(row[prefix+c]) for c in "rgb")
    require(all(math.isfinite(v) for v in result), "nonfinite diagnostic input")
    return result


def error(row):
    return delta_e00(linear_p3_lab(rgb(row, "ref_")), linear_p3_lab(rgb(row, "got_")))


def describe(rows):
    require(bool(rows), "empty diagnostic cohort")
    values = [(error(row), row) for row in rows]
    result = summary([value for value, _ in values])
    result["worst"] = [dict(delta_e00=value, group=row["group"],
                            input=rgb(row, "in_"), reference=rgb(row, "ref_"),
                            approximate=rgb(row, "got_"))
                       for value, row in sorted(values, key=lambda pair: pair[0], reverse=True)[:3]]
    return result


def compare_gate(directory, reference):
    require(json.loads((directory/"contract.json").read_text()) ==
            json.loads((reference/"contract.json").read_text()), "reference gate contract differs")
    def selected(path):
        return [row for row in load(path/"samples.csv") if row["size"] == "33" and
                row["set"] in ("interpolation16", "interpolation8")]
    actual, expected = selected(directory), selected(reference)
    require(len(expected) == 10244*4 and actual == expected,
            "original 33-cube held-out CSV changed")
    actual_grid = (directory/"lut-33.f32").read_bytes()
    require(len(actual_grid) == 33**3*3*4 and actual_grid == (reference/"lut-33.f32").read_bytes(),
            "original 33-cube float32 lattice changed")
    return dict(serialized_held_out_rows_identical=len(actual), lattice_bytes_identical=len(actual_grid),
                lattice_sha256=hashlib.sha256(actual_grid).hexdigest())


def compact_evidence(directory, destination):
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("contract.json", "diagnostic.json", "run.time", "timing.csv"):
        (destination/name).write_bytes((directory/name).read_bytes())
    rows = [row for row in load(directory/"samples.csv") if row["set"] == "local16"]
    require(bool(rows), "missing local probe evidence")
    with (destination/"local-probes.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    for index in range(4):
        name = f"local-coordinates-{index}.csv"
        (destination/name).write_bytes((directory/name).read_bytes())


def build(directory):
    contract = json.loads((directory/"diagnostic.json").read_text())
    require(contract.get("mode") == "diagnostic" and contract.get("global_grids") == [33, 65],
            "expected signed diagnostic run")
    samples = load(directory/"samples.csv")
    global_rows, local_rows = defaultdict(list), defaultdict(list)
    for row in samples:
        rgb(row, "in_")
        error(row)
        require(row["fallback"] in ("0", "1"), "invalid fallback flag")
        if row["fallback"] == "1":
            require(rgb(row, "ref_") == rgb(row, "got_"), "fallback differs")
        if row["set"] == "interpolation16":
            global_rows[(int(row["size"]), row["method"])].append(row)
        elif row["set"] == "local16":
            local_rows[row["group"]].append(row)
    result = {}
    for n in (33, 65):
        coordinates = load(directory/f"coordinates-{n}.csv")
        require(len(coordinates) == 10244, "unexpected held-out coordinate count")
        for method in ("trilinear", "tetrahedral"):
            rows = global_rows[(n, method)]
            require(len(rows) == len(coordinates), "coordinate/sample count mismatch")
            cohorts = defaultdict(list)
            for row, coordinate in zip(rows, coordinates):
                inp = rgb(row, "in_")
                require(row["group"] == coordinate["group"] and all(
                    abs(a-b) <= 2e-8*max(1, abs(a), abs(b))
                    for a, b in zip(inp, rgb(coordinate, "in_"))), "coordinate/sample identity mismatch")
                for name in ("gpu_tc_x", "gpu_tc_y", "gpu_brightness", "cpu_X", "cpu_Y", "cpu_Z"):
                    require(math.isfinite(float(coordinate[name])), "nonfinite coordinate")
                require(coordinate["positive_xyz_cell"] in ("0", "1"), "invalid cell flag")
                if row["fallback"] == "1":
                    continue
                signed = min(inp) < 0
                positive = coordinate["positive_xyz_cell"] == "1"
                cohorts[row["group"]].append(row)
                cohorts["all"].append(row)
                cohorts["signed_input" if signed else "nonnegative_input"].append(row)
                cohorts["positive_xyz_cell" if positive else "unsafe_xyz_cell"].append(row)
                if signed:
                    cohorts["signed_positive_xyz_cell" if positive else "signed_unsafe_xyz_cell"].append(row)
            require(sum(row["fallback"] == "1" for row in rows) == 4, "missing actual fallbacks")
            result[f"{n}/{method}"] = {name: describe(values) for name, values in cohorts.items()}
    local = {name: describe(rows) for name, rows in local_rows.items()}
    crossings = {}
    for name, rows in local_rows.items():
        if not name.startswith("Y-cross-"):
            continue
        require(len(rows) == 65, "expected 65 consecutive float inputs")
        coordinates = load(directory/f"local-coordinates-{name.split('-')[-1]}.csv")
        coords = [row for row in coordinates if row["group"] == name]
        require(len(coords) == len(rows), "missing local coordinates")
        for row, coordinate in zip(rows, coords):
            require(row["group"] == coordinate["group"] and all(
                abs(a-b) <= 2e-8*max(1, abs(a), abs(b))
                for a, b in zip(rgb(row, "in_"), rgb(coordinate, "in_"))),
                "local coordinate/sample identity mismatch")
        candidates = []
        for index in range(len(rows)-1):
            before, after = rows[index:index+2]
            a, b = rgb(before, "in_"), rgb(after, "in_")
            require(a[0] == b[0] and a[2] == b[2] and a[1] < b[1], "invalid branch line order")
            bits = [struct.unpack("!I", struct.pack("!f", value))[0] for value in (a[1], b[1])]
            require(abs(bits[1]-bits[0]) == 1, "branch pair is not adjacent float32")
            exact = [struct.unpack("!f", struct.pack("!f", value))[0] for value in (a[1], b[1])]
            change = delta_e00(linear_p3_lab(rgb(before, "ref_")), linear_p3_lab(rgb(after, "ref_")))
            candidates.append(dict(delta_e00=change, green_step=exact[1]-exact[0], green_float32_bits=bits,
                                   input_before=a, input_after=b,
                                   reference_before=rgb(before, "ref_"), reference_after=rgb(after, "ref_"),
                                   gpu_tc_before=[float(coords[index][key]) for key in ("gpu_tc_x", "gpu_tc_y")],
                                   gpu_tc_after=[float(coords[index+1][key]) for key in ("gpu_tc_x", "gpu_tc_y")]))
        crossings[name] = max(candidates, key=lambda pair: pair["delta_e00"])
    return dict(scope="Fixed default-pair diagnostic; existing held-out colors and four known failure cells, not a new natural-image acceptance test",
                classification_caution="Positive XYZ at all cell vertices diagnoses one projection boundary; it does not certify a DE00 bound or validate a shipping fallback",
                contract=contract, formula_check=verify(delta_e00), global16=result,
                local17_in_original33_cell=local, largest_adjacent_reference_change=crossings,
                note="A finite lattice remains continuous within a cell; a sampled steep branch cannot be assumed solved by increasing global grid size")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reference-gate", type=Path, help="require unchanged original 33-cube held-out rows and lattice")
    parser.add_argument("--evidence-dir", type=Path, help="save compact local branch evidence; not a replacement for full-run CSV")
    args = parser.parse_args()
    report = build(args.directory)
    if args.reference_gate:
        report["prior_gate_equivalence"] = compare_gate(args.directory, args.reference_gate)
    if args.evidence_dir:
        compact_evidence(args.directory, args.evidence_dir)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False)+"\n", encoding="utf-8")
    print(json.dumps({"global": {name: group["all"]["max"] for name, group in report["global16"].items()},
                      "adjacent_reference_change": report["largest_adjacent_reference_change"]}))


if __name__ == "__main__":
    main()
