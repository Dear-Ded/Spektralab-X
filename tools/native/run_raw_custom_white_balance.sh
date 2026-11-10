#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
[[ -f "$lib/libraw/libraw.h" && -f "$b/engine/libspk_image_io.a" ]] || {
    echo "requires an existing LibRaw and spk_image_io build" >&2; exit 2;
}
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt" && flags+=(-fopenmp)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$up/engine/src" \
    -I"$up/engine/host" -I"$lib" "$repo/tools/native/raw_custom_white_balance.cpp" \
    "$up/engine/host/white_balance.cpp" -Wl,--start-group \
    "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" -Wl,--end-group -ldl \
    -o "$build/raw_custom_white_balance"
# First optional argument has missing source WB; remaining fixtures have valid
# source WB. Only the first fixture runs a full-size headroom decode.
"$build/raw_custom_white_balance" "$@"
