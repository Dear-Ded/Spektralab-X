#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$up/engine/src" -I"$up/engine/src/core" \
    "$repo/tools/native/setup_cache_illuminant.cpp" "$up/engine/src/core/setup_cache.cpp" \
    "$b/engine/libspektralab_core.a" -o "$build/setup_cache_illuminant"
"$build/setup_cache_illuminant" "$b/engine/resources"
