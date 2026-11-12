#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Frozen pixels and cooperative lens correction.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?candidate patched upstream}"
old_up="${OLD_UP:?frozen upstream before 0050}"
b="${B:?current decoder build with matching LibRaw headers}"
old_b="${OLD_B:-$b}"
out="${BUILD_DIR:?dedicated test directory}"
fixtures="${FIXTURES:?synthetic integer-bayer.dng and integer-linear.dng directory}"
mkdir -p "$out"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
old_lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$old_b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then
    flags+=(-fopenmp -DSPEKTRALAB_RAW_OPENMP=1)
fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-pie -no-pie)
fi
candidate=(-I"$up/engine/src" -I"$up/engine/src/io" -I"$lib")
baseline=(-I"$old_up/engine/src" -I"$old_up/engine/src/io" -I"$old_lib")
"${CXX:-c++}" "${flags[@]}" "${baseline[@]}" -DSPK_OL3_BASELINE \
    "$repo/tools/native/opcode_list3_cancellation.cpp" "$old_b/engine/libspk_libraw.a" -ldl -lz -o "$out/helper-old"
"${CXX:-c++}" "${flags[@]}" "${candidate[@]}" \
    "$repo/tools/native/opcode_list3_cancellation.cpp" "$b/engine/libspk_libraw.a" -ldl -lz -o "$out/helper-new"
for workers in 1 4; do
    mkdir -p "$out/old-$workers" "$out/new-$workers"
    OMP_NUM_THREADS="$workers" "$out/helper-old" "$out/old-$workers"
    OMP_NUM_THREADS="$workers" "$out/helper-new" "$out/new-$workers"
    for kind in 0 1 2; do cmp "$out/old-$workers/kind-$kind.rgb16" "$out/new-$workers/kind-$kind.rgb16"; done
done
if OMP_NUM_THREADS=1 "$out/helper-old" "$out/old-1" --require-cancellation > "$out/baseline-gap.log" 2>&1; then
    echo 'FAIL: baseline unexpectedly provided lens cancellation' >&2; exit 1
else
    test "$?" == 1
fi
grep -q 'lens correction has no cancellation checkpoints' "$out/baseline-gap.log"
"${CXX:-c++}" "${flags[@]}" "${candidate[@]}" "$up/engine/tests/dng_opcode_list3.cpp" \
    -o "$out/original-unit"
OMP_NUM_THREADS=4 "$out/original-unit"
"${CXX:-c++}" "${flags[@]}" "${candidate[@]}" -c "$up/engine/src/io/raw_decoder.cpp" -o "$out/raw_decoder.o"
source="$repo/tools/native/compatible_raw_cancellation.cpp"
"${CXX:-c++}" "${flags[@]}" "${baseline[@]}" -DSPK_CANCEL_BASELINE "$source" \
    -Wl,--start-group "$old_b/engine/libspk_image_io.a" "$old_b/engine/libspk_libraw.a" \
    -Wl,--end-group -ldl -lz -o "$out/decoder-old"
for mode in parity cancel; do
    extra=(); if [[ "$mode" == parity ]]; then extra+=(-DSPK_CANCEL_BASELINE); fi
    "${CXX:-c++}" "${flags[@]}" "${candidate[@]}" "${extra[@]}" "$source" "$out/raw_decoder.o" \
        -Wl,--start-group "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" \
        -Wl,--end-group -ldl -lz -o "$out/decoder-$mode"
done
python3 "$repo/tools/native/opcode_list3_fixtures.py" "$fixtures" "$out/fixtures"
export SPEKTRALAB_RAW_THREADS=1 OMP_NUM_THREADS=4
comparisons=0; cancellation_runs=0
for file in "$out/fixtures/"*.dng; do
    name="$(basename "$file" .dng)"
    for half in 0 1; do
        for custom in 0 1; do
            prefix="$out/$name-$half-$custom"
            "$out/decoder-old" "$file" "$prefix-old" "$half" "$custom"
            "$out/decoder-parity" "$file" "$prefix-new" "$half" "$custom"
            cmp "$prefix-old.rgb" "$prefix-new.rgb"
            cmp "$prefix-old.metadata" "$prefix-new.metadata"
            comparisons=$((comparisons+1))
            if [[ "$name" == *-combined-6 ]]; then
                "$out/decoder-cancel" "$file" "$prefix-cancel" "$half" "$custom"
                cmp "$prefix-new.rgb" "$prefix-cancel.rgb"
                cmp "$prefix-new.metadata" "$prefix-cancel.metadata"
                cancellation_runs=$((cancellation_runs+1))
            fi
        done
    done
done
echo "PASS: $comparisons production pixel/full-metadata comparisons; $cancellation_runs decoder cancellation/retry runs"
