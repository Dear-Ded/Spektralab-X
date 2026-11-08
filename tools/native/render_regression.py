#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
"""Re-render an existing parity manifest and fail on any changed output byte.

Use one Vulkan device/driver and deterministic fresh-process seeds on both sides.
The baseline is a completed render_android_study manifest, not a perceptual grade.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import time


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True, help="directory containing manifest.json and input/output fixtures")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--raw-root", type=Path, help="also verify RAW decode/resize against the baseline inputs")
    args = parser.parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "parity"))
    import host_parity as hp

    manifest = json.loads((args.baseline / "manifest.json").read_text())
    args.out.mkdir(parents=True, exist_ok=True)
    result_path = args.out / "comparison.json"
    if result_path.exists():
        raise RuntimeError("choose a fresh output directory; do not overwrite previous evidence")
    report = {"baseline_manifest_sha256": digest(args.baseline / "manifest.json"),
              "candidate_binary_sha256": digest(args.binary), "cases": {}, "raw_preparation": {}, "all_exact": True,
              "scope": "same-driver Linux native chain; not execution on an Android phone"}
    env = {**os.environ, **manifest["env"], "OPENBLAS_NUM_THREADS": "1"}
    if args.raw_root:
        for scene, entry in manifest["inputs"].items():
            raw = args.raw_root / entry["raw"]
            if digest(raw) != entry["raw_sha256"]:
                raise RuntimeError(f"RAW source hash changed: {scene}")
            prepared = args.out / f"{scene}.f32"
            subprocess.run([str(args.binary), "prep", str(raw), str(manifest["edge"]), str(prepared)], env=env, check=True)
            sha = digest(prepared)
            exact = sha == entry["f32_sha256"]
            report["raw_preparation"][scene] = {"exact": exact, "sha256": sha, "raw_sha256": entry["raw_sha256"]}
            report["all_exact"] &= exact
            result_path.write_text(json.dumps(report, indent=2) + "\n")
            if not exact:
                raise SystemExit(f"RAW preparation bytes changed: {scene}")
    for name, entry in manifest["cases"].items():
        scene = name.split("_")[0]
        source = args.baseline / "inputs" / f"{scene}.f32"
        if digest(source) != manifest["inputs"][scene]["f32_sha256"]:
            raise RuntimeError(f"input hash changed: {scene}")
        out = args.out / name
        out.mkdir()
        command = [str(args.binary), str(args.resources), "f32", str(source), str(out), json.dumps(entry["params"])]
        if entry["layer2"]:
            command.append(",".join(f"{v:g}" for v in hp.LOOK_VALUES + hp.CURVE_TAIL))
        start = time.monotonic()
        process = subprocess.run(command, env=env, text=True, capture_output=True)
        (out / "native.log").write_text(process.stdout + process.stderr)
        process.check_returncode()
        files = {}
        for filename in ("print.rgb", "export_srgb.rgb", "export_p3.rgb", "view_srgb.rgb", "view_p3.rgb"):
            before = (args.baseline / name / filename).read_bytes()
            after = (out / filename).read_bytes()
            same = before == after
            header = struct.unpack_from("<3I", after)
            first = next((i for i, (a, b) in enumerate(zip(before, after)) if a != b), None) if not same else None
            files[filename] = {"exact": same, "width": header[0], "height": header[1], "bits": header[2],
                               "bytes_including_header": len(after), "first_different_byte": first,
                               "before_sha256": hashlib.sha256(before).hexdigest(),
                               "after_sha256": hashlib.sha256(after).hexdigest()}
        exact = all(f["exact"] for f in files.values())
        report["cases"][name] = {"all_exact": exact, "seconds": time.monotonic() - start, "files": files,
                                  "fresh_process_seed": True, "stochastic_effects": entry["stochastic"]}
        report["all_exact"] &= exact
        result_path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"{name}: exact={exact}", flush=True)
    if not report["all_exact"]:
        raise SystemExit("render regression: output bytes changed")
    print(f"render regression: {len(report['cases'])} cases, all five output planes byte-identical", flush=True)


if __name__ == "__main__":
    main()
