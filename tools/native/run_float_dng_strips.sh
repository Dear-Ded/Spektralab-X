#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Compare against the frozen alpha-3 helper.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:?dedicated strip test directory}"
baseline="${BASELINE_INCLUDE:?directory containing alpha-3 float_dng_linear.hpp}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then flags+=(-fopenmp); fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-pie -no-pie)
fi
pids=()
for version in old new; do
    include="$repo/app/src/main/cpp"
    if [[ "$version" == old ]]; then include="$baseline"; fi
    for source in float_dng_linear float_dng_streaming; do
        "${CXX:-c++}" "${flags[@]}" -I"$include" -I"$up/engine/src" -I"$lib" \
            "$repo/tools/native/$source.cpp" "$b/engine/libspk_libraw.a" -ldl -lz -o "$build/$source-$version" &
        pids+=("$!")
    done
done
failed=0
for pid in "${pids[@]}"; do wait "$pid" || failed=1; done
[[ "$failed" == 0 ]]
python3 "$repo/tools/native/float_dng_linear_fixtures.py" "$build/historical" --probe "$build/float_dng_linear-new"
options=()
if [[ "${MEMORY:-0}" == 1 ]]; then options+=(--memory); fi
python3 "$repo/tools/native/float_dng_strips_fixtures.py" "$build/fixtures" \
    --old "$build/float_dng_linear-old" --new "$build/float_dng_linear-new" \
    --stream "$build/float_dng_streaming-new" "${options[@]}"
if [[ "${MEMORY:-0}" == 1 ]]; then
    python3 "$repo/tools/native/float_dng_strips_memory.py" "$build/fixtures" \
        --old "$build/float_dng_streaming-old" --new "$build/float_dng_streaming-new"
fi
