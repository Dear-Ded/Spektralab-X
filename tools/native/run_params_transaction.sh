#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?complete host build with matching GPU ABI}"
build="${BUILD_DIR:?isolated test directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
"${CXX:-c++}" "${flags[@]}" -I"$up/engine/include" "$repo/tools/native/params_transaction.cpp" \
  -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
  "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$build/params_transaction"
"$build/params_transaction" "${RESOURCES:-$b/engine/resources}" "$build/accepted-output.rgba16"
