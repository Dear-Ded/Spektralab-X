#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Compare against a frozen pre-stream helper.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:?dedicated streaming test directory}"
baseline="${BASELINE_INCLUDE:?directory containing frozen pre-stream float_dng_linear.hpp}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then flags+=(-fopenmp); fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-pie -no-pie)
fi
for version in old new; do
    include="$repo/app/src/main/cpp"
    if [[ "$version" == old ]]; then include="$baseline"; fi
    for source in float_dng_linear float_dng_streaming; do
        "${CXX:-c++}" "${flags[@]}" -I"$include" -I"$up/engine/src" -I"$lib" \
            "$repo/tools/native/$source.cpp" "$b/engine/libspk_libraw.a" -ldl -o "$build/$source-$version"
    done
done
options=()
if [[ "${MEMORY:-0}" == 1 ]]; then options+=(--memory); fi
python3 "$repo/tools/native/float_dng_streaming_fixtures.py" "$build/fixtures" \
    --old "$build/float_dng_linear-old" --new "$build/float_dng_linear-new" \
    --old-stream "$build/float_dng_streaming-old" --new-stream "$build/float_dng_streaming-new" "${options[@]}"
