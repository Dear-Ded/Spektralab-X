#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
set -euo pipefail
: "${UP:?patched upstream required}" "${B:?matching engine build required}"
: "${BUILD_DIR:?isolated output directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
compile() {
  local up=$1 build=$2 name=$3
  "${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -pthread -DSPK_VULKAN_BACKEND=1 \
    -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/src/core" \
    -I"$up/engine/tests" -I"$build/engine/generated" \
    "$ROOT/tools/native/view_curve_gpu.cpp" -Wl,--start-group \
    "$build/engine/libspektralab_engine.a" "$build/engine/libspektralab_core.a" \
    "$build/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$BUILD_DIR/$name"
}
if [[ -n "${BASELINE_UP:-}" || -n "${BASELINE_B:-}" ]]; then
  : "${BASELINE_UP:?pre-0055 upstream required}" "${BASELINE_B:?pre-0055 build required}"
  compile "$BASELINE_UP" "$BASELINE_B" baseline
  "$BUILD_DIR/baseline" "$BASELINE_B/engine/resources/vulkan" "$BUILD_DIR/baseline.bin" \
    > "$BUILD_DIR/baseline.csv" 2> "$BUILD_DIR/baseline.log"
fi
compile "$UP" "$B" candidate
"$BUILD_DIR/candidate" "$B/engine/resources/vulkan" "$BUILD_DIR/candidate.bin" \
  > "$BUILD_DIR/candidate.csv" 2> "$BUILD_DIR/candidate.log"
if [[ -n "${BASELINE_UP:-}" ]]; then
  cmp "$BUILD_DIR/baseline.csv" "$BUILD_DIR/candidate.csv"
  cmp "$BUILD_DIR/baseline.bin" "$BUILD_DIR/candidate.bin"
  printf 'PASS byte-identical Vulkan view, geometry and proof outputs\n'
fi
cat "$BUILD_DIR/candidate.log"
sha256sum "$UP/engine/src/pipeline/view_curve.hpp" "$UP/engine/src/pipeline/view_process.cpp" \
  "$ROOT/tools/native/view_curve_gpu.cpp" "$BUILD_DIR/candidate.csv" "$BUILD_DIR/candidate.bin" \
  > "$BUILD_DIR/inputs.sha256"
