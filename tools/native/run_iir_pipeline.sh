#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
set -euo pipefail
: "${UP:?patched upstream required}" "${B:?engine build required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${BUILD_DIR:?isolated output directory required}"
mkdir -p "$BUILD_DIR"
compile() {
  local source="$1" up="$2" b="$3" output="$4"
  "${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -fno-access-control -pthread \
    -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/src/core" \
    "$source" -Wl,--start-group "$b/engine/libspektralab_engine.a" \
    "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" \
    -Wl,--end-group -ldl -o "$output"
}
compile "$ROOT/tools/native/iir_pipeline.cpp" "$UP" "$B" "$BUILD_DIR/probe"
unset SPEKTRAFILM_VULKAN_STRICT_FMA SPEKTRAFILM_VULKAN_FORCE_SPLIT
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
"$BUILD_DIR/probe" "$B/engine/resources/vulkan" "${RESOURCES:-$B/engine/resources}" \
  "$BUILD_DIR/current" > "$BUILD_DIR/current.log" 2>&1
cat "$BUILD_DIR/current.log"
if [[ -n "${BASELINE_UP:-}" || -n "${BASELINE_B:-}" ]]; then
  : "${BASELINE_UP:?frozen upstream required}" "${BASELINE_B:?frozen build required}"
  compile "$ROOT/tools/native/iir_pipeline.cpp" "$BASELINE_UP" "$BASELINE_B" "$BUILD_DIR/baseline-probe"
  "$BUILD_DIR/baseline-probe" "$BASELINE_B/engine/resources/vulkan" \
    "${BASELINE_RESOURCES:-$BASELINE_B/engine/resources}" "$BUILD_DIR/baseline" --legacy-only \
    > "$BUILD_DIR/baseline.log" 2>&1
  cmp "$BUILD_DIR/current/legacy.f32" "$BUILD_DIR/baseline/legacy.f32"
  cat "$BUILD_DIR/baseline.log"
  echo "PASS: all default stage/negative/final bytes match the pre-0052 engine"
fi
if [[ -f "$ROOT/tools/native/iir_pipeline_domain.cpp" ]]; then
  compile "$ROOT/tools/native/iir_pipeline_domain.cpp" "$UP" "$B" "$BUILD_DIR/domain-probe"
  "$BUILD_DIR/domain-probe" "${RESOURCES:-$B/engine/resources}" "$BUILD_DIR/domain.json" \
    > "$BUILD_DIR/domain.log" 2>&1
  cat "$BUILD_DIR/domain.log"
fi
