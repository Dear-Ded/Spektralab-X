#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Recheck compact signed local17 evidence, without full samples.csv or a GPU.

Usage: check_signed_evidence.py HOST [HOST ...] [--self-test] [--counterfactual]
Each HOST contains local-probes.csv, local-coordinates-{0..3}.csv and
signed-report.json. Hosts are checked independently, not byte-compared.
This checks recorded evidence consistency, not execution provenance, global
33/65 statistics, a shipping LUT policy, or Android performance.
"""
import argparse
from collections import Counter, defaultdict
import copy
import csv
import json
import math
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
from ciede2000_reference import delta_e00, verify
from gate_report import linear_p3_lab, require, summary

ABS_TOL = 1e-9
REL_TOL = 1e-10
TC_FIELDS = ("gpu_tc_x", "gpu_tc_y")
COORD_FIELDS = TC_FIELDS + ("gpu_brightness", "cpu_X", "cpu_Y", "cpu_Z")
EXPECTED = {f"{kind}-{index}": count for index in range(4)
            for kind, count in (("anchor", 1), ("ulp", 6), ("cell-line", 257))}
EXPECTED["Y-cross-0"] = 65


def read_csv(path):
    with path.open(newline="", encoding="utf-8-sig") as stream:
        return list(csv.DictReader(stream))


def load(directory):
    return dict(samples=read_csv(directory / "local-probes.csv"),
                coordinates=[read_csv(directory / f"local-coordinates-{i}.csv") for i in range(4)],
                report=json.loads((directory / "signed-report.json").read_text(encoding="utf-8-sig")))


def rgb(row, prefix):
    values = tuple(float(row[prefix + channel]) for channel in "rgb")
    require(all(math.isfinite(value) for value in values), f"nonfinite {prefix}RGB")
    return values


def bits(value):
    return struct.unpack("!I", struct.pack("!f", value))[0]


def f32(value):
    return struct.unpack("!f", struct.pack("!f", value))[0]


def finite_json(value, label="report"):
    if isinstance(value, dict):
        for key, child in value.items():
            finite_json(child, f"{label}/{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            finite_json(child, f"{label}/{index}")
    elif isinstance(value, (int, float)):
        require(not isinstance(value, bool) and math.isfinite(value), f"nonfinite/boolean metric {label}")


def compare(actual, stored, label):
    """Counts/identities exact; recomputed floating statistics tolerate libm noise."""
    if isinstance(actual, dict):
        require(isinstance(stored, dict) and actual.keys() == stored.keys(), f"keys differ: {label}")
        for key, value in actual.items():
            compare(value, stored[key], f"{label}/{key}")
    elif isinstance(actual, (tuple, list)):
        require(isinstance(stored, (tuple, list)) and len(actual) == len(stored), f"length differs: {label}")
        for index, value in enumerate(actual):
            compare(value, stored[index], f"{label}/{index}")
    elif isinstance(actual, int):
        require(type(stored) is int and actual == stored, f"integer differs: {label}")
    elif isinstance(actual, float):
        require(type(stored) in (int, float) and math.isfinite(stored) and
                math.isclose(actual, stored, abs_tol=ABS_TOL, rel_tol=REL_TOL), f"metric differs: {label}")
    else:
        require(actual == stored, f"identity differs: {label}")


def describe(rows):
    errors = [(delta_e00(linear_p3_lab(rgb(row, "ref_")), linear_p3_lab(rgb(row, "got_"))), row)
              for row in rows]
    result = summary([value for value, _ in errors])
    result["worst"] = [dict(delta_e00=value, group=row["group"], input=rgb(row, "in_"),
                            reference=rgb(row, "ref_"), approximate=rgb(row, "got_"))
                       for value, row in sorted(errors, key=lambda item: item[0], reverse=True)[:3]]
    return result


def crossing(rows, coordinates, expect_baseline):
    require(len(rows) == len(coordinates) == 65, "Y-cross-0 needs all 65 rows")
    inputs = [rgb(row, "in_") for row in rows]
    require(len({bits(value[0]) for value in inputs}) == len({bits(value[2]) for value in inputs}) == 1,
            "Y-cross-0 must hold R/B fixed")
    green = [f32(value[1]) for value in inputs]
    green_bits = [bits(value) for value in green]
    require(all(a < b and abs(ba - bb) == 1
                for a, b, ba, bb in zip(green, green[1:], green_bits, green_bits[1:])),
            "Y-cross-0 G is not 65 consecutive increasing float32 values")
    require(float(coordinates[0]["cpu_Y"]) < 0 < float(coordinates[-1]["cpu_Y"]),
            "Y-cross-0 no longer brackets CPU Y=0")
    candidates = []
    for index, (before, after) in enumerate(zip(rows, rows[1:])):
        ref_before, ref_after = rgb(before, "ref_"), rgb(after, "ref_")
        candidates.append(dict(
            delta_e00=delta_e00(linear_p3_lab(ref_before), linear_p3_lab(ref_after)),
            green_step=green[index + 1] - green[index],
            green_float32_bits=green_bits[index:index + 2],
            input_before=inputs[index], input_after=inputs[index + 1],
            reference_before=ref_before, reference_after=ref_after,
            gpu_tc_before=[float(coordinates[index][key]) for key in TC_FIELDS],
            gpu_tc_after=[float(coordinates[index + 1][key]) for key in TC_FIELDS]))
    largest = max(candidates, key=lambda pair: pair["delta_e00"])
    # This fixed evidence diagnoses the observed TC branch, not a CPU matrix surrogate.
    if expect_baseline:
        require(largest["gpu_tc_before"] == [1.0, 0.0] and largest["gpu_tc_after"] == [1.0, 1.0],
                "largest full-reference jump lacks the recorded GPU TC (1,0)->(1,1) transition")
    return largest


def check(evidence, expect_baseline=True):
    samples, tables, report = (evidence[key] for key in ("samples", "coordinates", "report"))
    finite_json(report)
    contract = report["contract"]
    require(contract.get("mode") == "diagnostic" and contract.get("local_subgrid") == 17 and
            contract.get("global_grids") == [33, 65], "not the fixed signed local17 diagnostic")
    require(Counter(row["group"] for row in samples) == Counter(EXPECTED), "local sample groups/counts differ")
    require(len(tables) == 4, "need four coordinate tables")
    for index, table in enumerate(tables):
        expected = {name: count for name, count in EXPECTED.items() if name.endswith(f"-{index}")}
        require(Counter(row["group"] for row in table) == Counter(expected),
                f"coordinate groups/counts differ: {index}")
    coordinates = [row for table in tables for row in table]
    require(len(coordinates) == len(samples), "sample/coordinate count differs")
    groups, coords = defaultdict(list), defaultdict(list)
    for index, (row, coordinate) in enumerate(zip(samples, coordinates)):
        require((row["set"], row["size"], row["method"], row["fallback"]) ==
                ("local16", "17", "tetrahedral", "0"), f"unexpected local sample contract at {index}")
        inputs = rgb(row, "in_")
        coordinate_inputs = rgb(coordinate, "in_")
        require(row["group"] == coordinate["group"] and
                tuple(map(bits, inputs)) == tuple(map(bits, coordinate_inputs)),
                f"sample/coordinate float32 identity mismatch at {index}")
        rgb(row, "ref_")
        rgb(row, "got_")
        for key in COORD_FIELDS:
            require(math.isfinite(float(coordinate[key])), f"nonfinite coordinate {key} at {index}")
        require(all(0 <= float(coordinate[key]) <= 1 for key in TC_FIELDS), f"GPU TC outside [0,1] at {index}")
        require(coordinate["positive_xyz_cell"] in ("0", "1"), f"invalid cell flag at {index}")
        groups[row["group"]].append(row)
        coords[row["group"]].append(coordinate)
    # Validate adjacency before report statistics, so a coherently edited pair
    # of sample/coordinate tables cannot hide a skipped representable input.
    largest = crossing(groups["Y-cross-0"], coords["Y-cross-0"], expect_baseline)
    local = {name: describe(rows) for name, rows in groups.items()}
    compare(local, report["local17_in_original33_cell"], "local17")
    compare({"Y-cross-0": largest}, report["largest_adjacent_reference_change"], "adjacent_reference")
    formula = verify(delta_e00)
    require(formula["pairs"] == 34 and formula["max_rounded_reference_error"] <= 5e-5 + 1e-12,
            "independent DE00 formula reference failed")
    compare(formula, report["formula_check"], "formula_check")
    return dict(rows=len(samples), groups=len(local), baseline_branch_required=expect_baseline,
                all_local17_summaries_match=True,
                all_sample_coordinate_float32_identities_match=True,
                recorded_gpu_tc_finite_and_in_unit_square=True,
                local_max_de00={name: values["max"] for name, values in local.items()},
                Y_cross_0=dict(count=65, consecutive_float32_g=True, adjacent_bit_difference=1,
                               max_full_reference_de00=largest["delta_e00"],
                               green_float32_bits=largest["green_float32_bits"],
                               gpu_tc_before=largest["gpu_tc_before"], gpu_tc_after=largest["gpu_tc_after"]))


def self_test(original, expect_baseline=True):
    def delete_crossing_row(evidence):
        for rows in (evidence["samples"], evidence["coordinates"][0]):
            positions = [i for i, row in enumerate(rows) if row["group"] == "Y-cross-0"]
            del rows[positions[32]]

    def skip_float_neighbor(evidence):
        for rows in (evidence["samples"], evidence["coordinates"][0]):
            crossing_rows = [row for row in rows if row["group"] == "Y-cross-0"]
            # Keep 65 rows, matching both CSV identities and strict ordering,
            # but make a two-ULP gap at the branch instead of deleting a row.
            for row in crossing_rows[32:]:
                shifted = struct.unpack("!f", struct.pack("!I", bits(float(row["in_g"])) - 1))[0]
                row["in_g"] = repr(shifted)

    def mismatch_coordinate(evidence):
        rows = evidence["coordinates"][0]
        rows[1], rows[2] = rows[2], rows[1]

    def nonfinite(evidence):
        evidence["samples"][0]["ref_g"] = "NaN"

    def forge_summary(evidence):
        evidence["report"]["local17_in_original33_cell"]["cell-line-0"]["max"] += 1

    def forge_gpu_tc(evidence):
        for row in evidence["coordinates"][0]:
            if row["group"] == "Y-cross-0":
                row["gpu_tc_y"] = "0"

    results = {}
    for name, mutate in (("mismatched_coordinate", mismatch_coordinate),
                         ("deleted_zero_crossing_row", delete_crossing_row),
                         ("skipped_float32_neighbor", skip_float_neighbor),
                         ("NaN", nonfinite), ("forged_stored_summary", forge_summary),
                         ("forged_recorded_gpu_tc", forge_gpu_tc)):
        changed = copy.deepcopy(original)
        mutate(changed)
        try:
            check(changed, expect_baseline)
        except ValueError as error:
            results[name] = str(error)
        else:
            raise ValueError(f"self-test accepted corruption: {name}")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hosts", type=Path, nargs="+")
    parser.add_argument("--self-test", action="store_true", help="reject in-memory mutations of each real host")
    parser.add_argument("--counterfactual", action="store_true",
                        help="check isolated modified-projection evidence without requiring the baseline TC jump")
    args = parser.parse_args()
    try:
        for directory in args.hosts:
            evidence = load(directory)
            result = dict(host=str(directory), **check(evidence, not args.counterfactual))
            if args.self_test:
                result["self_test_rejections"] = self_test(evidence, not args.counterfactual)
            print(json.dumps(result, allow_nan=False))
    except (ValueError, KeyError, OSError, OverflowError, struct.error, AssertionError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
