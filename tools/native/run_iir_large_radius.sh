#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
set -euo pipefail
: "${UP:?patched upstream through 0053 required}" "${B:?matching engine build required}"
: "${BUILD_DIR:?new isolated output directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
compile() {
  local source="$1" output="$2"; shift 2
  "${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -pthread "$@" \
    -I"$UP/engine/include" -I"$UP/engine/src" -I"$UP/engine/src/core" \
    "$source" -Wl,--start-group "$B/engine/libspektralab_engine.a" \
    "$B/engine/libspektralab_core.a" "$B/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$output"
}
compile "$ROOT/tools/native/iir_large_radius.cpp" "$BUILD_DIR/v2-probe" -DSPK_IIR_DIFFERENCE=1
compile "$ROOT/tools/native/iir_large_radius.cpp" "$BUILD_DIR/original-probe"
compile "$ROOT/tools/native/iir_pipeline.cpp" "$BUILD_DIR/pipeline-probe" -fno-access-control
compile "$ROOT/tools/native/iir_boundary_gpu.cpp" "$BUILD_DIR/mixed-probe"
unset SPEKTRAFILM_VULKAN_STRICT_FMA SPEKTRAFILM_VULKAN_FORCE_SPLIT
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
SHADERS="$B/engine/resources/vulkan"
for math in auto split; do
  if [[ "$math" == split ]]; then export SPEKTRAFILM_VULKAN_FORCE_SPLIT=1; fi
  "$BUILD_DIR/v2-probe" "$SHADERS" "$BUILD_DIR/$math" > "$BUILD_DIR/$math.log" 2>&1
  cat "$BUILD_DIR/$math.log"
  "$BUILD_DIR/mixed-probe" "$SHADERS" "$BUILD_DIR/mixed-$math" --triggs-v2 > "$BUILD_DIR/mixed-$math.log" 2>&1
  cat "$BUILD_DIR/mixed-$math.log"
done
unset SPEKTRAFILM_VULKAN_FORCE_SPLIT
if "$BUILD_DIR/original-probe" "$SHADERS" "$BUILD_DIR/original" > "$BUILD_DIR/original.log" 2>&1; then
  echo "FAIL: original direct-state shader passed the high-radius gate" >&2; exit 1
fi
python3 - "$BUILD_DIR/original/summary.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    result = json.load(f)
if result['passed'] or result['cases'] != 1040 or result['failures'] < 1:
    raise SystemExit('original shader did not complete with numerical failures')
PY
echo "PASS: original direct-state shader fails the same numerical gate"
for mode in v1 v2; do
  flags=(); if [[ "$mode" == v2 ]]; then flags+=(--triggs-v2); fi
  "$BUILD_DIR/pipeline-probe" "$SHADERS" "${RESOURCES:-$B/engine/resources}" \
    "$BUILD_DIR/pipeline-$mode" "${flags[@]}" > "$BUILD_DIR/pipeline-$mode.log" 2>&1
  cat "$BUILD_DIR/pipeline-$mode.log"
done
cmp "$BUILD_DIR/pipeline-v1/legacy.f32" "$BUILD_DIR/pipeline-v2/legacy.f32"
if [[ -n "${BASELINE_PIPELINE:-}" ]]; then
  cmp "$BUILD_DIR/pipeline-v1/legacy.f32" "$BASELINE_PIPELINE/legacy.f32"
  cmp "$BUILD_DIR/pipeline-v1/corrected.f32" "$BASELINE_PIPELINE/corrected.f32"
  echo "PASS: default and triggs-v1 complete stage bytes match frozen 0052"
fi
spirv-val --target-env vulkan1.0 "$SHADERS/spk_iir_difference_df_acc.spv"
