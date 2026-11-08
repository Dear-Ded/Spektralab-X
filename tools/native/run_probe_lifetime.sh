#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?upstream tree containing engine/third_party/volk}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
"${CXX:-c++}" -std=c++20 -O2 -I"$UP/engine/third_party/volk" \
    -I"$UP/build/deps/Vulkan-Headers/include" \
    "$repo/tools/native/storage_probe_lifetime.cpp" "$UP/engine/third_party/volk/volk.c" \
    -ldl -o "$build/storage_probe_lifetime"
"$build/storage_probe_lifetime" "$@"
