#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Reuses LibRaw without changing its source/build.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:?dedicated helper test directory}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then flags+=(-fopenmp); fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-pie -no-pie)
fi
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$up/engine/src" -I"$lib" \
    "$repo/tools/native/float_dng_linear.cpp" "$b/engine/libspk_libraw.a" -ldl -o "$build/float_dng_linear"
python3 "$repo/tools/native/float_dng_linear_fixtures.py" "$build/fixtures" --probe "$build/float_dng_linear"
