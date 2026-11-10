#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Only synthetic public DNG fixtures are required.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?candidate build}"
old="${OLD_B:?frozen previous build}"
out="${BUILD_DIR:?dedicated test directory}"
fixtures="${FIXTURES:?directory containing integer-bayer.dng and integer-linear.dng}"
mkdir -p "$out"
# LibRaw's parallel progress cadence is scheduling dependent. Enumerate all
# positions with one decoder worker; exercise callback concurrency separately.
export SPEKTRALAB_RAW_THREADS=1
export OMP_NUM_THREADS=4
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread -I"$up/engine/src" -I"$up/engine/src/io" -I"$lib")
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then flags+=(-fopenmp -DSPEKTRALAB_RAW_OPENMP=1); fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-pie -no-pie)
fi
"${CXX:-c++}" "${flags[@]}" -DSPK_CANCEL_BASELINE "$repo/tools/native/compatible_raw_cancellation.cpp" \
    -Wl,--start-group "$old/engine/libspk_image_io.a" "$old/engine/libspk_libraw.a" -Wl,--end-group -ldl -o "$out/baseline"
# Instrument the production adapter as well as the probe; LibRaw is the existing archive.
"${CXX:-c++}" "${flags[@]}" -c "$up/engine/src/io/raw_decoder.cpp" -o "$out/raw_decoder.cpp.o"
"${CXX:-c++}" "${flags[@]}" "$repo/tools/native/compatible_raw_cancellation.cpp" "$out/raw_decoder.cpp.o" \
    -Wl,--start-group "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" -Wl,--end-group -ldl -o "$out/candidate"
for source in integer-bayer integer-linear; do
    for half in 0 1; do
        for custom in 0 1; do
            name="$source-$half-$custom"
            "$out/baseline" "$fixtures/$source.dng" "$out/old-$name" "$half" "$custom"
            "$out/candidate" "$fixtures/$source.dng" "$out/new-$name" "$half" "$custom"
            cmp "$out/old-$name.rgb" "$out/new-$name.rgb"
            cmp "$out/old-$name.metadata" "$out/new-$name.metadata"
        done
    done
done
echo 'PASS: 8 frozen-decoder pixel/complete-metadata comparisons and cancellation/retry suites'
