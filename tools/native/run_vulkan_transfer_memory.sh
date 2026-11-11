#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?rebuilt native engine}"
build="${BUILD_DIR:?isolated test output}"
mkdir -p "$build"
"${CXX:-g++}" -std=c++20 -O2 -pthread -I"$up/engine/include" -I"$up/engine/src" \
  "$repo/tools/native/vulkan_transfer_memory.cpp" -Wl,--start-group \
  "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" \
  -Wl,--end-group -ldl -o "$build/vulkan_transfer_memory"
for ring in 1 0; do
  printf 'inline_ring=%s\n' "$ring"
  SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 SPEKTRAFILM_VULKAN_INLINE_RING="$ring" \
    "$build/vulkan_transfer_memory" "$b/engine/resources"
done
