#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree}"
B="${B:?host engine build directory}"
resources="${RESOURCES_DIR:?staged Android engine resources directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
e="$B/engine"
"${CXX:-c++}" -std=c++20 -O2 -pthread -I"$UP/engine/include" -I"$UP/engine/src" \
    -I"$UP/engine/src/core" -I"$UP/engine/tests" "$repo/tools/native/vulkan_fusion.cpp" \
    -Wl,--start-group "$e/libspektralab_engine.a" "$e/libspektralab_core.a" \
    "$e/libspk_volk.a" -Wl,--end-group -ldl -o "$build/vulkan_fusion"
sha256sum "$e/libspektralab_engine.a" "$e/resources/vulkan/spk_dir_finish.spv" \
    "$e/resources/vulkan/spk_xyz_cam16_compress.spv"
SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 "$build/vulkan_fusion" "$e/resources/vulkan" "$resources"
