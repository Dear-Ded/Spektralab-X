#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11. Isolated counterfactual research.
set -euo pipefail
scripts="$(cd "$(dirname "$0")" && pwd)"
base="${BASE:-/data/home/gpt-lut-gate-20261111}"
up="${UP:-$base/upstream}"
build="${B:-$base/build}"
binary="${BIN:-$base/signed/lut_offline}"
baseline="${BASELINE:-$base/signed}"
out="${EXPERIMENT:-/data/home/gpt-lut-negative-denom-20261111}"
metrics="${METRICS_DIR:-$base/repo/tools/lut_experiment}"
test -f "$metrics/signed_report.py"
test -f "$metrics/ciede2000_reference.py"
compiler="${GLSLANG:-}"
if test -z "$compiler"; then
  compiler="$(sed -n 's/^SPEKTRALAB_GLSLANG:FILEPATH=//p' "$build/CMakeCache.txt" | head -n 1)"
fi
test -n "$compiler"
test -x "$compiler"
python3 "$scripts/prepare_negative_denominator.py" "$up" "$build" "$binary" "$baseline" "$out"
out="$(realpath "$out")"
"$compiler" --version > "$out/compiler-version.txt"
flags=(-V --target-env vulkan1.0 "-I$up/engine/src/shaders/vulkan")
"$compiler" "${flags[@]}" -o "$out/control-spk_tc_b.spv" \
  "$up/engine/src/shaders/vulkan/spk_tc_b.comp" > "$out/compile-control.log" 2>&1
# Refuse a compiler/flag mismatch instead of attributing it to the intervention.
cmp "$out/control-spk_tc_b.spv" "$build/engine/resources/vulkan/spk_tc_b.spv"
"$compiler" "${flags[@]}" -o "$out/shaders/spk_tc_b.spv" \
  "$out/source/spk_tc_b.comp" > "$out/compile-counterfactual.log" 2>&1
if command -v spirv-val >/dev/null 2>&1; then
  spirv-val --target-env vulkan1.0 "$out/shaders/spk_tc_b.spv" > "$out/spirv-val.log" 2>&1
fi
sha256sum "$binary" "$out/control-spk_tc_b.spv" "$out/shaders/spk_tc_b.spv" > "$out/run-inputs.sha256"
LP_NUM_THREADS="${LP_NUM_THREADS:-4}" SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 \
  /usr/bin/time -v -o "$out/run.time" "$binary" "$out/shaders" \
  "$build/engine/resources" "$out/result" diagnostic > "$out/diagnostic.log" 2>&1
python3 "$metrics/signed_report.py" "$out/result" --output "$out/result/signed-report.json" \
  > "$out/signed-report.log"
python3 "$scripts/compare_negative_denominator.py" "$out" "$metrics" > "$out/comparison.log"
cat "$out/comparison.log"
