#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
cxx="${CXX:-c++}"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
[[ -f "$lib/libraw/libraw.h" ]] || { echo "cannot locate built LibRaw source" >&2; exit 2; }
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt" && flags+=(-fopenmp)
"$cxx" "${flags[@]}" -I"$up/engine/src" "$repo/tools/native/dng_forward_matrix.cpp" -o "$build/dng_forward_matrix"
"$build/dng_forward_matrix"
"$cxx" "${flags[@]}" -I"$up/engine/src" -I"$up/engine/host" -I"$lib" \
    "$repo/tools/native/white_balance_temperature.cpp" "$up/engine/host/white_balance.cpp" \
    -o "$build/white_balance_temperature"
"$build/white_balance_temperature"
if [[ -f "$repo/tools/native/raw_white_balance.cpp" ]]; then
    # Default is metadata-only. RAW_DECODE=1 also checks four UI targets through
    # the real compatible decoder (Bayer half-size), using the existing build.
    raw_flags=()
    raw_libs=("$b/engine/libspk_libraw.a")
    if [[ "${RAW_DECODE:-0}" == 1 ]]; then
        [[ -f "$b/engine/libspk_image_io.a" ]] || { echo "RAW_DECODE requires built spk_image_io" >&2; exit 2; }
        raw_flags+=(-DSPK_RAW_DECODE_TEST=1)
        raw_libs=(-Wl,--start-group "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" -Wl,--end-group -ldl)
    fi
    "$cxx" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$up/engine/src" -I"$up/engine/host" -I"$lib" \
        "${raw_flags[@]}" \
        "$repo/tools/native/raw_white_balance.cpp" "$up/engine/host/white_balance.cpp" \
        "${raw_libs[@]}" -o "$build/raw_white_balance"
    "$build/raw_white_balance" "$@"
fi
