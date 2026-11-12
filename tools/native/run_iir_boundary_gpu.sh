#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
set -euo pipefail
: "${UP:?patched upstream required}" "${B:?engine build required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT/build/iir-boundary-gpu}"
mkdir -p "$BUILD_DIR"
CXX="${CXX:-c++}"
compile() {
  local source="$1" up="$2" build="$3" output="$4"
  "$CXX" -std=c++20 -O2 -ffp-contract=off -pthread \
    -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/src/core" \
    "$source" -Wl,--start-group "$build/engine/libspektralab_engine.a" \
    "$build/engine/libspektralab_core.a" "$build/engine/libspk_volk.a" \
    -Wl,--end-group -ldl -o "$output"
}
compile "$ROOT/tools/native/iir_boundary_gpu.cpp" "$UP" "$B" "$BUILD_DIR/probe"
SHADERS="${SHADERS:-$B/engine/resources/vulkan}"
for mode in auto split; do
  if [[ "$mode" == split ]]; then export SPEKTRAFILM_VULKAN_FORCE_SPLIT=1
  else unset SPEKTRAFILM_VULKAN_FORCE_SPLIT; fi
  unset SPEKTRAFILM_VULKAN_STRICT_FMA
  "$BUILD_DIR/probe" "$SHADERS" "$BUILD_DIR/$mode" > "$BUILD_DIR/$mode.log" 2>&1
  cat "$BUILD_DIR/$mode.log"
done
if [[ -n "${BASELINE_UP:-}" || -n "${BASELINE_B:-}" ]]; then
  : "${BASELINE_UP:?baseline upstream required}" "${BASELINE_B:?baseline build required}"
  compile "$ROOT/tools/native/iir_boundary_gpu.cpp" "$BASELINE_UP" "$BASELINE_B" "$BUILD_DIR/baseline-probe"
  for mode in auto split; do
    if [[ "$mode" == split ]]; then export SPEKTRAFILM_VULKAN_FORCE_SPLIT=1
    else unset SPEKTRAFILM_VULKAN_FORCE_SPLIT; fi
    "$BUILD_DIR/baseline-probe" "$BASELINE_B/engine/resources/vulkan" \
      "$BUILD_DIR/baseline-$mode" --legacy-only > "$BUILD_DIR/baseline-$mode.log" 2>&1
    cmp "$BUILD_DIR/$mode/legacy.f32" "$BUILD_DIR/baseline-$mode/legacy.f32"
    cat "$BUILD_DIR/baseline-$mode.log"
    echo "PASS: $mode default bytes match the pre-0052 engine"
  done
  if "$BUILD_DIR/baseline-probe" "$BASELINE_B/engine/resources/vulkan" \
      "$BUILD_DIR/baseline-rejected" > "$BUILD_DIR/baseline-rejected.log" 2>&1; then
    echo "FAIL: pre-0052 engine passed the corrected boundary gate" >&2
    exit 1
  fi
  grep -q "GPU boundary disagrees with explicit-tail oracle" "$BUILD_DIR/baseline-rejected.log"
  echo "PASS: independent numerical gate rejects the pre-0052 boundary"
fi
if [[ -f "$ROOT/tools/native/iir_boundary_alias.cpp" ]]; then
  compile "$ROOT/tools/native/iir_boundary_alias.cpp" "$UP" "$B" "$BUILD_DIR/alias-probe"
  for mode in auto split; do
    if [[ "$mode" == split ]]; then export SPEKTRAFILM_VULKAN_FORCE_SPLIT=1
    else unset SPEKTRAFILM_VULKAN_FORCE_SPLIT; fi
    "$BUILD_DIR/alias-probe" "$SHADERS" "$BUILD_DIR/alias-$mode.json" \
      > "$BUILD_DIR/alias-$mode.log" 2>&1
    cat "$BUILD_DIR/alias-$mode.log"
  done
fi
