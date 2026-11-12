#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
set -euo pipefail
: "${UP:?patched upstream required}" "${B:?engine build required}"
: "${BUILD_DIR:?isolated output directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
"${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -fno-access-control -pthread \
  -I"$UP/engine/include" -I"$UP/engine/src" -I"$UP/engine/src/core" -I"$UP/engine/tests" \
  "$ROOT/tools/native/feedback_dispatch.cpp" -Wl,--start-group \
  "$B/engine/libspektralab_engine.a" "$B/engine/libspektralab_core.a" \
  "$B/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$BUILD_DIR/probe"
"${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -pthread \
  -I"$UP/engine/include" -I"$UP/engine/src" -I"$UP/engine/src/core" -I"$ROOT/app/src/main/cpp" \
  "$ROOT/tools/native/feedback_session.cpp" "$ROOT/app/src/main/cpp/resize.cpp" \
  -Wl,--start-group "$B/engine/libspektralab_engine.a" "$B/engine/libspektralab_core.a" \
  "$B/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$BUILD_DIR/session-probe"
{
  "${CXX:-c++}" --version | head -1
  sha256sum "$ROOT/tools/native/feedback_dispatch.cpp" "$ROOT/tools/native/feedback_session.cpp" \
    "$ROOT/app/src/main/cpp/resize.cpp" \
    "$ROOT/app/src/main/cpp/rgba16_frame_check.hpp" \
    "$UP/engine/src/gpu/vulkan_gpu.cpp" "$B/engine/libspektralab_engine.a"
} > "$BUILD_DIR/build-inputs.txt"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
unset SPEKTRAFILM_VULKAN_STRICT_FMA SPEKTRAFILM_VULKAN_FORCE_SPLIT
"$BUILD_DIR/probe" "$B/engine/resources/vulkan" "${RESOURCES:-$B/engine/resources}" \
  "$BUILD_DIR/auto" > "$BUILD_DIR/auto.log" 2>&1
SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 "$BUILD_DIR/probe" \
  "$B/engine/resources/vulkan" "${RESOURCES:-$B/engine/resources}" \
  "$BUILD_DIR/split" > "$BUILD_DIR/split.log" 2>&1
"$BUILD_DIR/session-probe" "${RESOURCES:-$B/engine/resources}" --synthetic 1601 1031 \
  "$BUILD_DIR/session-auto" 1537 > "$BUILD_DIR/session-auto.log" 2>&1
SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 "$BUILD_DIR/session-probe" \
  "${RESOURCES:-$B/engine/resources}" --synthetic 1601 1031 \
  "$BUILD_DIR/session-split" 1537 > "$BUILD_DIR/session-split.log" 2>&1
cat "$BUILD_DIR/auto.log" "$BUILD_DIR/split.log"
cat "$BUILD_DIR/session-auto.log" "$BUILD_DIR/session-split.log"
