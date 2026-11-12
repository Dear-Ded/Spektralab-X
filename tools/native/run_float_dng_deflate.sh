#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Frozen float reader vs streaming Deflate.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
build="${BUILD_DIR:?dedicated Deflate validation directory}"
baseline="${BASELINE_INCLUDE:?directory containing b7ec00e float_dng_linear.hpp}"
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
    "${CXX:-c++}" "${flags[@]}" -I"$include" -I"$up/engine/src" -I"$lib" \
        "$repo/tools/native/float_dng_linear.cpp" "$b/engine/libspk_libraw.a" -ldl -lz -o "$build/helper-$version" &
    pids+=("$!")
done
failed=0
for pid in "${pids[@]}"; do wait "$pid" || failed=1; done
[[ "$failed" == 0 ]]
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$up/engine/src" -I"$lib" \
    "$repo/tools/native/float_dng_streaming.cpp" "$b/engine/libspk_libraw.a" -ldl -lz -o "$build/stream"
"${CXX:-c++}" "${flags[@]}" -I"$lib" "$repo/tools/native/libraw_deflate.cpp" \
    "$b/engine/libspk_libraw.a" -ldl -lz -o "$build/unpack"
BUILD_DIR="$build/dispatch" bash "$repo/tools/native/run_raw_precision_input.sh"
python3 "$repo/tools/native/float_dng_linear_fixtures.py" "$build/historical" --probe "$build/helper-new"
options=()
if [[ "${MEMORY:-0}" == 1 ]]; then options+=(--memory); fi
"${PYTHON:-python3}" "$repo/tools/native/float_dng_deflate_fixtures.py" "$build/fixtures" \
    --old "$build/helper-old" --new "$build/helper-new" \
    --dispatcher "$build/dispatch/raw_precision_input" --stream "$build/stream" \
    --unpack "$build/unpack" "${options[@]}"
if [[ "${MEMORY:-0}" == 1 ]]; then
    python3 "$repo/tools/native/float_dng_deflate_memory.py" "$build/fixtures" --probe "$build/stream"
fi
