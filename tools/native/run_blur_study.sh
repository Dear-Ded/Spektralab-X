#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree}"
B="${B:?host engine build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
e="$B/engine"
"${CXX:-c++}" -std=c++20 -O2 -pthread -I"$UP/engine/include" -I"$UP/engine/src" -I"$UP/engine/src/core" \
    "$repo/tools/native/blur_boundary.cpp" -Wl,--start-group \
    "$e/libspektralab_engine.a" "$e/libspektralab_core.a" "$e/libspk_volk.a" -Wl,--end-group \
    -ldl -o "$build/blur_boundary"
"$build/blur_boundary" "$B/engine/resources/vulkan" "$@"
