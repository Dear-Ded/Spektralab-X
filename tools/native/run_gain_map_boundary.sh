#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Instrument the header; reuse existing LibRaw.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream source}"
b="${B:?existing LibRaw build}"
out="${BUILD_DIR:?dedicated output directory}"
mkdir -p "$out"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread -I"$up/engine/src" -I"$lib")
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then flags+=(-fopenmp); fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-pie -no-pie)
fi
"${CXX:-c++}" "${flags[@]}" "$repo/tools/native/gain_map_boundary.cpp" \
    "$b/engine/libspk_libraw.a" -ldl -o "$out/gain_map_boundary"
"$out/gain_map_boundary" "${MODE:---require-safe}"
fixture_flags=()
if [[ "${MODE:-}" == --observe ]]; then fixture_flags+=(--observe); fi
python3 "$repo/tools/native/gain_map_boundary_fixtures.py" "$out/fixtures" \
    --probe "$out/gain_map_boundary" "${fixture_flags[@]}"
