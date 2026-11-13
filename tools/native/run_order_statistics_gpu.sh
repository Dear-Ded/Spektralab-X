#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Compare complete production film/print payloads.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
: "${UP:?patched upstream}" "${B:?matching engine}" "${BUILD_DIR:?isolated results}"
: "${BASELINE_UP:?frozen upstream}" "${BASELINE_B:?frozen matching engine}"
mkdir -p "$BUILD_DIR"
compile() {
    local source="$1" build="$2" output="$3"
    "${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -pthread \
        -I"$source/engine/src" -I"$source/engine/src/core" -I"$source/engine/include" \
        "$repo/tools/native/order_statistics_gpu.cpp" -Wl,--start-group \
        "$build/engine/libspektralab_engine.a" "$build/engine/libspektralab_core.a" \
        "$build/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$output"
}
compile "$UP" "$B" "$BUILD_DIR/candidate"
compile "$BASELINE_UP" "$BASELINE_B" "$BUILD_DIR/baseline"
for math in auto split; do
    unset SPEKTRAFILM_VULKAN_FORCE_SPLIT
    if [[ "$math" == split ]]; then export SPEKTRAFILM_VULKAN_FORCE_SPLIT=1; fi
    for side in baseline candidate; do
        build="$B"; if [[ "$side" == baseline ]]; then build="$BASELINE_B"; fi
        "$BUILD_DIR/$side" "$build/engine/resources/vulkan" "$build/engine/resources" \
            "$BUILD_DIR/$side-$math.bin" > "$BUILD_DIR/$side-$math.csv" 2> "$BUILD_DIR/$side-$math.log"
    done
    cmp "$BUILD_DIR/baseline-$math.csv" "$BUILD_DIR/candidate-$math.csv"
    cmp "$BUILD_DIR/baseline-$math.bin" "$BUILD_DIR/candidate-$math.bin"
    cat "$BUILD_DIR/candidate-$math.log"
done
printf 'PASS frozen/current complete Vulkan EV bits, negative and final float payloads\n'
