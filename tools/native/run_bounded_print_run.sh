#!/usr/bin/env bash
# Phase-two external-plane regression for patch 0040 (default print run, halo bands).
# Co-created with Claude (Anthropic), 2026-11-10. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree with 0039 and 0040}"
B="${B:?host engine build directory}"
resources="${RESOURCES_DIR:?directory holding spektrafilm_constants.bin}"
build="${BUILD_DIR:-$(mktemp -d)}"
scratch="${SCRATCH_DIR:-$build/scratch}"
mkdir -p "$build" "$scratch"
e="$B/engine"
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -pthread -I"$UP/engine/include" -I"$UP/engine/src" \
    -I"$UP/engine/src/core" -I"$UP/engine/tests" "$repo/tools/native/bounded_print_run.cpp" \
    -Wl,--start-group "$e/libspektralab_engine.a" "$e/libspektralab_core.a" \
    "$e/libspk_volk.a" -Wl,--end-group -ldl -o "$build/bounded_print_run"
sha256sum "$e/libspektralab_engine.a" "$build/bounded_print_run"
shaders="$e/resources/vulkan"
# Strict 128 MiB range: the whole 4097x3073 window must be refused, halo bands must pass.
SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 "$build/bounded_print_run" "$shaders" "$resources" "$scratch"
