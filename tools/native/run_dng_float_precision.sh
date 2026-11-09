#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. No production library rebuild.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:?dedicated probe output directory}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt" && flags+=(-fopenmp)
"${CXX:-c++}" "${flags[@]}" -I"$up/engine/src" -I"$lib" \
    "$repo/tools/native/dng_float_precision.cpp" -Wl,--start-group \
    "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" -Wl,--end-group -ldl \
    -o "$build/dng_float_precision"
python3 "$repo/tools/native/dng_float_precision_fixtures.py" "$build/fixtures" --probe "$build/dng_float_precision"
