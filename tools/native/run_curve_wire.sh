#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" \
    "$repo/tools/native/curve_wire.cpp" -o "$build/curve_wire"
"$build/curve_wire"
