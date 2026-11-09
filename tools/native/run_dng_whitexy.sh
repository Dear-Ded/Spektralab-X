#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread -DSPK_DNG_WHITE_XY="${SPK_DNG_WHITE_XY:-1}")
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt" && flags+=(-fopenmp)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$up/engine/src" \
    -I"$up/engine/host" -I"$lib" "$repo/tools/native/dng_whitexy.cpp" \
    "$up/engine/host/white_balance.cpp" -Wl,--start-group \
    "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" -Wl,--end-group -ldl \
    -o "$build/dng_whitexy"
python3 "$repo/tools/native/dng_whitexy_fixtures.py" "$build/fixtures" \
    --probe "$build/dng_whitexy" "$@"
