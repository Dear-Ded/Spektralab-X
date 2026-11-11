#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11. Isolated counterfactual research.
import csv
import hashlib
import json
import math
from pathlib import Path
import struct
import sys


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def triplet(row, prefix):
    values = tuple(float(row[prefix + c]) for c in "rgb")
    require(all(math.isfinite(v) for v in values), "nonfinite CSV RGB")
    return values


def input_bits(row):
    return struct.pack("<3f", *triplet(row, "in_"))


def reference_bytes(row):
    codes = []
    for linear in triplet(row, "ref_"):
        require(0 <= linear <= 1, "production P3 output outside UNORM range")
        encoded = 12.92 * linear if linear <= .0031308 else 1.055 * linear ** (1 / 2.4) - .055
        code = min(65535, max(0, math.floor(encoded * 65535 + .5)))
        require(abs(encoded * 65535 - code) < .01, "CSV does not identify a UNORM16 code")
        codes.append(code)
    return struct.pack("<4H", *codes, 65535)


def compare(out, metrics):
    out, metrics = Path(out).resolve(), Path(metrics).resolve()
    sys.path.insert(0, str(metrics))
    from ciede2000_reference import delta_e00, verify
    from gate_report import linear_p3_lab
    record = json.loads((out / "experiment.json").read_text())
    before, after = Path(record["baseline"]), out / "result"
    require(json.loads((before / "diagnostic.json").read_text()) ==
            json.loads((after / "diagnostic.json").read_text()), "diagnostic contract changed")
    require(json.loads((before / "contract.json").read_text()) ==
            json.loads((after / "contract.json").read_text()), "output/default contract changed")
    for path, expected in record["immutable_inputs"].items():
        require(digest(Path(path)) == expected, f"original input changed: {path}")
    original_shaders = Path(record["original_shaders"])
    original_files = {p.name for p in original_shaders.iterdir() if p.is_file()}
    isolated_files = {p.name for p in (out / "shaders").iterdir() if p.is_file()}
    require(original_files == isolated_files, "isolated shader file set changed")
    for name in original_files - {"spk_tc_b.spv"}:
        require((original_shaders / name).read_bytes() == (out / "shaders" / name).read_bytes(),
                f"unrelated shader changed: {name}")
    require(digest(original_shaders / "spk_tc_b.spv") != digest(out / "shaders/spk_tc_b.spv"),
            "intervention SPIR-V did not change")
    old_rows, new_rows = load(before / "samples.csv"), load(after / "samples.csv")
    require(len(old_rows) == len(new_rows), "sample count changed")
    identity = ("set", "size", "method", "group", "fallback", "in_r", "in_g", "in_b")
    for old, new in zip(old_rows, new_rows):
        require(all(old[k] == new[k] for k in identity), "sample identity/order changed")
        for row in (old, new):
            triplet(row, "ref_"); triplet(row, "got_")
    positive = {}
    for n in (33, 65):
        coords, changed = load(before / f"coordinates-{n}.csv"), load(after / f"coordinates-{n}.csv")
        require(len(coords) == len(changed) == 10244, "unexpected held-out count")
        pairs = [(a, b) for a, b in zip(old_rows, new_rows)
                 if a["set"] == "interpolation16" and a["size"] == str(n) and a["method"] == "trilinear"]
        tetra_pairs = [(a, b) for a, b in zip(old_rows, new_rows)
                       if a["set"] == "interpolation16" and a["size"] == str(n) and a["method"] == "tetrahedral"]
        require(len(pairs) == len(coords), "missing 16-bit held-out cohort")
        require(len(tetra_pairs) == len(pairs), "missing tetrahedral reference cohort")
        old_bytes, new_bytes = bytearray(), bytearray()
        changed_elsewhere = 0
        for (old, new), (old_tet, new_tet), a, b in zip(pairs, tetra_pairs, coords, changed):
            require(input_bits(old) == input_bits(a) == input_bits(b) and old["group"] == a["group"] == b["group"],
                    "coordinate identity mismatch")
            require(a["positive_xyz_cell"] == b["positive_xyz_cell"], "cell classifier changed")
            if a["positive_xyz_cell"] == "1":
                require(all(old["ref_" + c] == new["ref_" + c] for c in "rgb"),
                        "positive XYZ cell reference decimal values changed")
                require(input_bits(old) == input_bits(old_tet) == input_bits(new_tet) and
                        all(old["ref_" + c] == old_tet["ref_" + c] and new["ref_" + c] == new_tet["ref_" + c] for c in "rgb"),
                        "interpolation methods disagree on positive-cell actual reference")
                old_bytes.extend(reference_bytes(old)); new_bytes.extend(reference_bytes(new))
            else:
                changed_elsewhere += reference_bytes(old) != reference_bytes(new)
        require(old_bytes and old_bytes == new_bytes, "positive XYZ production bytes changed or cohort empty")
        for label, data in (("before", old_bytes), ("after", new_bytes)):
            (out / f"positive-xyz-{n}-{label}.rgba16").write_bytes(data)
        positive[str(n)] = dict(count=len(old_bytes) // 8, byte_count=len(old_bytes), byte_identical=True,
                                sha256=hashlib.sha256(old_bytes).hexdigest(), changed_unsafe_references=changed_elsewhere)
    crossing = {}
    tc_changes = 0
    for anchor in range(4):
        old_coords = load(before / f"local-coordinates-{anchor}.csv")
        new_coords = load(after / f"local-coordinates-{anchor}.csv")
        require(len(old_coords) == len(new_coords), "local coordinate count changed")
        for a, b in zip(old_coords, new_coords):
            require(a["group"] == b["group"] and input_bits(a) == input_bits(b), "local identity changed")
            for field in ("gpu_tc_x", "gpu_tc_y", "gpu_brightness"):
                require(math.isfinite(float(a[field])) and math.isfinite(float(b[field])), "nonfinite tc")
            require(a["gpu_brightness"] == b["gpu_brightness"] and a["gpu_tc_x"] == b["gpu_tc_x"],
                    "intervention changed brightness or tc-x")
            tc_changes += a["gpu_tc_y"] != b["gpu_tc_y"]
        label = f"Y-cross-{anchor}"
        pairs = [(a, b) for a, b in zip(old_rows, new_rows) if a["set"] == "local16" and a["group"] == label]
        if not pairs:
            continue
        require(len(pairs) == 65, "unexpected Y-cross count")
        def distance(a, b):
            return delta_e00(linear_p3_lab(triplet(a, "ref_")), linear_p3_lab(triplet(b, "ref_")))
        old_steps = [distance(pairs[i][0], pairs[i + 1][0]) for i in range(64)]
        old_peak = max(range(64), key=old_steps.__getitem__)
        old_cross_coords = [r for r in old_coords if r["group"] == label]
        new_cross_coords = [r for r in new_coords if r["group"] == label]
        require(len(old_cross_coords) == len(new_cross_coords) == 65, "missing Y-cross coordinates")
        witness = []
        for i in (old_peak, old_peak + 1):
            a, b = pairs[i]
            require(input_bits(a) == input_bits(old_cross_coords[i]) == input_bits(new_cross_coords[i]), "Y-cross witness identity mismatch")
            witness.append(dict(input=triplet(a, "in_"), reference_before=triplet(a, "ref_"), reference_after=triplet(b, "ref_"),
                                tc_before=[float(old_cross_coords[i][k]) for k in ("gpu_tc_x", "gpu_tc_y")],
                                tc_after=[float(new_cross_coords[i][k]) for k in ("gpu_tc_x", "gpu_tc_y")]))
        crossing[label] = dict(count=len(pairs), changed_outputs=sum(reference_bytes(a) != reference_bytes(b) for a, b in pairs),
                               max_counterfactual_change=max(distance(a, b) for a, b in pairs),
                               max_adjacent_before=max(old_steps),
                               max_adjacent_after=max(distance(pairs[i][1], pairs[i + 1][1]) for i in range(64)),
                               old_maximum_adjacent_witness=witness)
    require(tc_changes > 0 and crossing, "intervention did not reach local branch probes")
    report = dict(scope=record["scope"], conclusion_limit="An isolated intervention measures causal sensitivity; it does not establish a correct signed-domain renderer, natural-image support, or a mergeable algorithm",
                  output_byte_scope=record["output_byte_scope"], sharma_reference=verify(delta_e00),
                  positive_xyz_cells=positive, y_cross=crossing, changed_local_tc_y_count=tc_changes,
                  untouched_input_count=len(record["immutable_inputs"]))
    (out / "comparison.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps(report, allow_nan=False))


if __name__ == "__main__":
    require(len(sys.argv) == 3, "usage: compare_negative_denominator.py EXPERIMENT METRICS_DIR")
    compare(*sys.argv[1:])
