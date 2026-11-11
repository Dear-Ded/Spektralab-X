#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?host engine build directory}"
build="${BUILD_DIR:?isolated output directory}"
mkdir -p "$build/scratch"
flags=(-std=c++20 -O2 -Wall -Wextra -pthread)
[[ "${BEFORE_FIX:-0}" == 0 ]] || flags+=(-DSPK_BUDGET_BEFORE)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
objects=()
[[ -z "${BLUR_OBJECT:-}" ]] || objects+=("$BLUR_OBJECT")
"${CXX:-g++}" "${flags[@]}" -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/src/core" \
  "$repo/tools/native/bounded_budget.cpp" "${objects[@]}" \
  -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
  "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$build/bounded_budget"
SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 "$build/bounded_budget" "$b/engine/resources/vulkan" "$build/scratch"
