#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE for project terms.
# Uses patch 0025's upstream regression and the production engine archive.
# The test sets only a backend flag; it never requests a real GPU reset.
set -euo pipefail
UP="${UP:?patched upstream tree}"
B="${B:?host engine build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
e="$B/engine"
"${CXX:-c++}" -std=c++20 -O2 -pthread -I"$UP/engine/include" -I"$UP/engine/src" \
    "$UP/engine/tests/gpu_vulkan_device_lost.cpp" -Wl,--start-group \
    "$e/libspektralab_engine.a" "$e/libspektralab_core.a" "$e/libspk_volk.a" -Wl,--end-group \
    -ldl -o "$build/vulkan_device_lost"
# Strict limits avoid the unrelated, optional capacity probe. Cover the
# Android inline ring and the fallback staging path as separate processes.
for ring in 1 0; do
    printf 'device-lost regression: inline_ring=%s\n' "$ring"
    SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 SPEKTRAFILM_VULKAN_INLINE_RING="$ring" \
        "$build/vulkan_device_lost" "$e/resources"
done
