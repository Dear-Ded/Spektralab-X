#!/usr/bin/env bash
# Phase-one external-plane regression for patch 0039 (print_linear pointwise chain).
# Co-created with Claude (Anthropic), 2026-11-10. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree with 0039}"
B="${B:?host engine build directory}"
resources="${RESOURCES_DIR:?directory holding spektrafilm_constants.bin}"
build="${BUILD_DIR:-$(mktemp -d)}"
scratch="${SCRATCH_DIR:-$build/scratch}"
mkdir -p "$build" "$scratch"
e="$B/engine"
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -pthread -I"$UP/engine/include" -I"$UP/engine/src" \
    -I"$UP/engine/src/core" -I"$UP/engine/tests" "$repo/tools/native/bounded_point_chain.cpp" \
    -Wl,--start-group "$e/libspektralab_engine.a" "$e/libspektralab_core.a" \
    "$e/libspk_volk.a" -Wl,--end-group -ldl -o "$build/bounded_point_chain"
sha256sum "$e/libspektralab_engine.a" "$build/bounded_point_chain"
shaders="$e/resources/vulkan"
# Strict 128 MiB range: the whole 4097x3073 plane must be refused, bands must pass.
SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 "$build/bounded_point_chain" "$shaders" "$resources" "$scratch"
if [[ "${MEMORY:-0}" == 1 ]]; then
    # Separate processes. Whole needs the CPU device's measured large range, so it
    # runs without the strict switch; bands run both ways.
    env -u SPEKTRAFILM_VULKAN_STRICT_LIMITS "$build/bounded_point_chain" "$shaders" "$resources" "$scratch" --memory-whole
    env -u SPEKTRAFILM_VULKAN_STRICT_LIMITS "$build/bounded_point_chain" "$shaders" "$resources" "$scratch" --memory-bands
    SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 "$build/bounded_point_chain" "$shaders" "$resources" "$scratch" --memory-bands
fi
