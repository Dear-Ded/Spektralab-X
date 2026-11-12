#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
# Synthetic cloud-only conditions; does not emulate a proprietary phone driver.
set -euo pipefail
: "${UP:?patched upstream required}" "${B:?matching host build required}"
: "${BUILD_DIR:?new isolated results directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
"${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -pthread \
  -I"$UP/engine/include" -I"$ROOT/app/src/main/cpp" \
  "$ROOT/tools/native/feedback_session.cpp" "$ROOT/app/src/main/cpp/resize.cpp" \
  -Wl,--start-group "$B/engine/libspektralab_engine.a" "$B/engine/libspektralab_core.a" \
  "$B/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$BUILD_DIR/session-probe"
{
  uname -a
  "${CXX:-c++}" --version | head -1
  sha256sum "$ROOT/tools/native/feedback_session.cpp" "$ROOT/app/src/main/cpp/resize.cpp" \
    "$UP/engine/src/gpu/vulkan_gpu.cpp" "$B/engine/libspektralab_engine.a"
  free -m
} > "$BUILD_DIR/build-inputs.txt"
scope=(systemd-run --user --scope --quiet)
if [[ $(id -u) == 0 ]]; then scope=(systemd-run --scope --quiet); fi
# Verify that per-process scopes work before claiming any memory limit.
"${scope[@]}" -p MemoryMax=5G -p MemorySwapMax=0 true
printf 'case,memory_max_bytes,working_edge,exit_code\n' > "$BUILD_DIR/cases.csv"
run_case() {
  local name=$1 memory=$2 edge=$3
  shift 3
  mkdir -p "$BUILD_DIR/$name"
  set +e
  "${scope[@]}" -p "MemoryMax=$memory" -p MemorySwapMax=0 \
    bash -c 'cg=$(cut -d: -f3 /proc/self/cgroup); for f in memory.max memory.swap.max; do printf "%s=" "$f"; cat "/sys/fs/cgroup$cg/$f"; done; exec "$@"' \
    feedback-environment timeout --kill-after=15s 900s /usr/bin/time -v \
    env -u SPEKTRAFILM_VULKAN_STRICT_LIMITS -u SPEKTRAFILM_VULKAN_STRICT_FMA \
      -u SPEKTRAFILM_VULKAN_INLINE_RING -u SPEKTRAFILM_VULKAN_STAGING_BYTES \
      LP_NUM_THREADS=2 SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 "$@" \
      "$BUILD_DIR/session-probe" "${RESOURCES:-$B/engine/resources}" \
      --synthetic 7028 4688 "$BUILD_DIR/$name" "$edge" --first-pass \
    > "$BUILD_DIR/$name.log" 2>&1
  local status=$?
  set -e
  printf '%s,%s,%s,%s\n' "$name" "$memory" "$edge" "$status" >> "$BUILD_DIR/cases.csv"
  tail -8 "$BUILD_DIR/$name.log"
}
run_case phone-available 5368709120 6709
run_case tighter-memory 2147483648 6709
run_case transfer-chunks 5368709120 6709 \
  SPEKTRAFILM_VULKAN_INLINE_RING=0 SPEKTRAFILM_VULKAN_STAGING_BYTES=1048576
run_case smaller-working 5368709120 3400
run_case advertised-capacity 5368709120 6709 SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
cat "$BUILD_DIR/cases.csv"
