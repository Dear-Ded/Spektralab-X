#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11. Isolated counterfactual research.
import hashlib
import json
from pathlib import Path
import shutil
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def prepare(up, build, binary, baseline, out):
    up, build, binary, baseline = [Path(p).resolve(strict=True) for p in (up, build, binary, baseline)]
    out = Path(out).resolve()
    require(out.is_absolute() and not out.exists(), "experiment path must be new")
    protected = [up, build, binary.parent, baseline]
    require(all(out != p and p not in out.parents and out not in p.parents for p in protected),
            "experiment path must be disjoint from source/build/binary/baseline")
    source = up / "engine/src/shaders/vulkan/spk_tc_b.comp"
    shaders = build / "engine/resources/vulkan"
    original = shaders / "spk_tc_b.spv"
    for path in (source, original, binary, baseline / "samples.csv", baseline / "diagnostic.json"):
        require(path.is_file(), f"missing input: {path}")
    require(digest(source) == "d459e5b4e30d0022d5cce248bb84ebab60ab5c815307dc543fb1988b960eca88",
            "source is not the inspected actual49 shader")
    text = source.read_text()
    old = "(one_minus_x > 1e-10 ? one_minus_x : 1e-10)"
    new = "(one_minus_x < -1e-10 ? one_minus_x : (one_minus_x > 1e-10 ? one_minus_x : 1e-10))"
    require(text.count(old) == 1, "denominator expression must match exactly once")
    require((up / "engine/CMakeLists.txt").is_file(), "missing actual CMake source")
    cmake = (up / "engine/CMakeLists.txt").read_text()
    require('"${SPEKTRALAB_GLSLANG}" -V --target-env vulkan1.0' in cmake,
            "unrecognized shader compilation contract")
    out.mkdir(parents=True)
    (out / "source").mkdir()
    # Follow input symlinks into independent regular files; never hardlink SPIR-V.
    shutil.copytree(shaders, out / "shaders", symlinks=False)
    (out / "source/spk_tc_b.comp").write_text(text.replace(old, new))
    (out / "result").mkdir()
    immutable = {str(p): digest(p) for p in [source, binary, original]}
    immutable.update({str(p): digest(p) for p in sorted(shaders.glob("*.spv"))})
    record = dict(scope="Counterfactual signed-denominator experiment; not correctness or merge approval",
                  change="Only preserve one_minus_x when strictly below -1e-10; all other branches unchanged",
                  upstream=str(up), build=str(build), binary=str(binary), baseline=str(baseline),
                  resources=str(build / "engine/resources"), original_shaders=str(shaders),
                  source_sha256=digest(source), immutable_inputs=immutable,
                  original_expression=old, counterfactual_expression=new,
                  output_byte_scope="Production P3 UNORM16 reconstructed from losslessly identifying decimal CSV; internal float buffers are not exported")
    (out / "experiment.json").write_text(json.dumps(record, indent=2) + "\n")
    print(out)


if __name__ == "__main__":
    require(len(sys.argv) == 6, "usage: prepare_negative_denominator.py UP BUILD BINARY BASELINE NEW_OUT")
    prepare(*sys.argv[1:])
