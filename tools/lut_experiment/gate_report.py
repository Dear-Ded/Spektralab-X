#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Report the synthetic 33-cube gate; never infer Android/App performance.

Usage: gate_report.py RUN_DIRECTORY METRICS_JSON [--output REPORT_JSON]
METRICS_JSON is produced by tools/native/lut_metrics.py from samples.csv.
The 16-bit common-group mean <= 3 is the requested quality decision. Other
quantiles, 8-bit output, signed/wide inputs and spatial deletion are separate.
"""
import argparse
import csv
import json
import math
from pathlib import Path
import statistics
import sys
from collections import defaultdict

sys.dont_write_bytecode = True
from ciede2000_reference import delta_e00, verify

COMMON = ("common-srgb", "common-neutral", "common-warm", "common-dark")
STRESS = ("neutral", "dark", "shaped", "linear", "saturated", "signed")
METHODS = ("trilinear", "tetrahedral")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def linear_p3_lab(rgb):
    """D65 Lab from already decoded, quantized SDR P3; no hidden clipping."""
    require(all(math.isfinite(v) and 0 <= v <= 1 for v in rgb),
            "expected finite decoded SDR P3 in [0,1]")
    r, g, b = rgb
    xyz = (.4865709486482162*r + .2656676931690931*g + .1982172852343625*b,
           .2289745640697488*r + .6917385218365064*g + .079286914093745*b,
           .0451133818589026*g + 1.043944368900976*b)
    def f(value):
        return value**(1/3) if value > (6/29)**3 else value/(3*(6/29)**2) + 4/29
    x, y, z = (f(v/w) for v, w in zip(xyz, (.9504559270516716, 1., 1.0890577507598784)))
    return 116*y - 16, 500*(x-y), 200*(y-z)


def summary(values):
    require(bool(values), "empty metric group")
    values = sorted(values)
    def quantile(p):
        index = (len(values)-1)*p
        lower = int(index)
        return values[lower] + (values[min(lower+1, len(values)-1)]-values[lower])*(index-lower)
    return dict(count=len(values), mean=sum(values)/len(values), p95=quantile(.95),
                p99=quantile(.99), max=values[-1],
                count_gt3=sum(v > 3 for v in values), fraction_gt3=sum(v > 3 for v in values)/len(values),
                count_gt5=sum(v > 5 for v in values), fraction_gt5=sum(v > 5 for v in values)/len(values))


def read_samples(path):
    groups, fallbacks, inputs = defaultdict(list), defaultdict(int), defaultdict(list)
    rows = 0
    with path.open(newline="", encoding="utf-8") as stream:
        for line, row in enumerate(csv.DictReader(stream), 2):
            rows += 1
            key = (row["set"], row["size"], row["method"], row["group"])
            rgb = [tuple(float(row[prefix+c]) for c in "rgb") for prefix in ("in_", "ref_", "got_")]
            require(all(math.isfinite(v) for triple in rgb for v in triple),
                    f"nonfinite samples.csv value at line {line}")
            require(row["fallback"] in ("0", "1"), f"invalid fallback flag at line {line}")
            inp, ref, got = rgb
            # Every gate output is quantized SDR P3, including spatial ablations.
            lab_ref, lab_got = linear_p3_lab(ref), linear_p3_lab(got)
            if row["fallback"] == "1":
                require(ref == got, f"fallback differs from actual reference at line {line}")
                require(row["group"] == "outside", f"unexpected fallback group at line {line}")
                fallbacks[key[:3]] += 1
                continue
            require(row["group"] != "outside", f"outside point silently interpolated at line {line}")
            if row["set"] in ("interpolation16", "interpolation8"):
                require(all(-.25 <= v <= 16 for v in inp), f"interpolated out-of-domain input at line {line}")
            groups[key].append(delta_e00(lab_ref, lab_got))
            inputs[key].append(inp)
    return groups, fallbacks, inputs, rows


def read_timing(path):
    wall = defaultdict(list)
    with path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            ms = float(row["ms"])
            require(math.isfinite(ms) and ms >= 0, "nonfinite/negative timing")
            if row["stage"] == "wall":
                wall[row["render"]].append(ms)
    def repetitions(prefix):
        values = [wall.get(prefix+str(i), []) for i in range(3)]
        require(all(len(value) == 1 for value in values), f"need three measured {prefix} repeats")
        result = [value[0] for value in values]
        return dict(repeats_ms=result, median_ms=statistics.median(result))
    exact = repetitions("preview-exact-end-to-end-")
    lut = repetitions("preview-cpu-lut-end-to-end-")
    require(lut["median_ms"] > 0, "zero LUT duration")
    result = dict(point_spectral_end_to_end=exact, cpu_tetrahedral_lut_end_to_end=lut,
                  host_point_spectral_to_cpu_lut_ratio=exact["median_ms"]/lut["median_ms"],
                  scope="Cloud CPU llvmpipe versus CPU tetrahedral interpolation; not Android shader or App speedup",
                  end_to_end_includes="input flatten/upload for spectral; interpolation for CPU LUT; both include host P3 packing and P3 decoding for Lab",
                  working_boundary="The spectral probe reads float32 and reproduces the UNORM16 boundary on CPU; GPU-working parity is a separate native control, not a timed JNI fetch",
                  excluded="pipeline construction, LUT baking, file/JNI/image decoding, UI, and per-image auto exposure")
    for name in ("grid-33", "preview-full-spatial", "preview-full-spatial-end-to-end"):
        if name in wall:
            result[name+"_ms"] = wall[name]
    result["full_spatial_caution"] = "preview-full-spatial wall excludes source upload and display conversion; only an explicitly recorded end-to-end row is comparable"
    return result


def build_report(directory, metrics_path):
    contract = json.loads((directory/"contract.json").read_text(encoding="utf-8"))
    require(contract.get("paper") == "kodak_supra_endura" and contract.get("film") == "kodak_portra_400",
            "expected gate default stock pair, not old smoke/full output")
    require(contract.get("lut_values") == "encoded ProPhoto RGB float32" and
            contract.get("film_format_mm") == 36 and contract.get("live_edge") == 1024 and
            contract.get("domain") == [-.25, 16], "unexpected gate output/geometry/domain contract")
    existing = json.loads(metrics_path.read_text(encoding="utf-8"))
    groups, fallbacks, inputs, rows = read_samples(directory/"samples.csv")
    for key, values in groups.items():
        label = "/".join(key)
        require(label in existing["groups"], f"existing metrics missing {label}")
        old = existing["groups"][label]
        new = summary(values)
        require(old["count"] == new["count"], f"existing count mismatch {label}")
        for stat in ("mean", "p95", "p99", "max"):
            require(math.isfinite(old["delta_e00"][stat]) and
                    abs(old["delta_e00"][stat]-new[stat]) <= 1e-9,
                    f"independent DE00 mismatch {label}/{stat}")
    primary, secondary, stress = {}, {}, {}
    heldout = {}
    for output in ("interpolation16", "interpolation8"):
        for method in METHODS:
            require(fallbacks[(output, "33", method)] == 4, "expected four actual out-of-domain fallbacks per method/output")
            for group in COMMON+STRESS:
                key = (output, "33", method, group)
                require(len(groups[key]) == 1024, f"expected 1024 held-out points: {'/'.join(key)}")
                original = ("interpolation16", "33", "trilinear", group)
                require(inputs[key] == inputs[original], f"held-out inputs differ by method/output: {group}")
                value = summary(groups[key])
                label = "/".join(key)
                if group in COMMON:
                    value["mean_le3"] = value["mean"] <= 3
                    (primary if output == "interpolation16" else secondary)[label] = value
                else:
                    stress[label] = value
                heldout[group] = len(groups[key])
    decisions = {}
    for method in METHODS:
        selected = [primary[f"interpolation16/33/{method}/{group}"] for group in COMMON]
        decisions[method] = "KEEP_COMMON_SDR_CANDIDATE" if all(v["mean_le3"] for v in selected) else "REJECT_COMMON_SDR_MEAN"
    separate = {"/".join(key): summary(value) for key, value in groups.items()
                if key[0] not in ("interpolation16", "interpolation8")}
    for output, size, method, group in groups:
        if output in ("interpolation16", "interpolation8"):
            require(size == "33" and method in METHODS and group in COMMON+STRESS,
                    "unexpected interpolation cohort; do not mix training or other grids into gate")
    require(("preview16", "33", "tetrahedral", "synthetic-1024-scene") in groups,
            "missing held-out preview scene")
    return dict(decisions=decisions, threshold="Each common group independently: mean CIEDE2000 <= 3; no tail hard threshold",
                scope="Synthetic fixed-exposure point approximation only; spatial deletion and signed/wide support are separate limitations",
                primary_interpolation16=primary, secondary_interpolation8=secondary,
                stress_signed_wide=stress, preview_and_spatial_ablation=separate,
                finite_csv=dict(rows=rows, all_input_reference_approximate_finite=True,
                                prequantization_caution="CSV cannot establish pre-pack finiteness; the native probe must check floats before production clamping/packing"),
                sampling=dict(heldout_per_original_group=heldout, training_nodes_in_metrics=False,
                              provenance="native probe emits held-out samples only; CSV alone cannot prove disjointness from LUT baking"),
                independent_formula_check=verify(delta_e00), timing=read_timing(directory/"timing.csv"), contract=contract)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("metrics", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = build_report(args.directory, args.metrics)
    except (ValueError, KeyError, OSError, TypeError) as exc:
        parser.exit(2, f"INVALID GATE EVIDENCE: {exc}\n")
    text = json.dumps(result, indent=2, allow_nan=False)+"\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
        print(json.dumps(result["decisions"]))
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
