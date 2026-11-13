#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Compile real production units, including sanitizers.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream}"
build="${BUILD_DIR:?isolated result directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -fno-access-control -ffunction-sections -fdata-sections -ffp-contract=off)
if [[ "${SANITIZE:-0}" == 1 ]]; then
    : "${B:?sanitizer globals require the engine support archives}"
    flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
fi
compile() {
    local source="$1" output="$2" candidate="$3" support="$4"
    local libraries=()
    if [[ -n "$support" ]]; then
        libraries=(-Wl,--start-group "$support/engine/libspektralab_engine.a"
            "$support/engine/libspektralab_core.a" "$support/engine/libspk_volk.a"
            -Wl,--end-group -ldl)
    fi
    "${CXX:-c++}" "${flags[@]}" -DSPK_SELECT_CANDIDATE="$candidate" \
        -I"$source/engine/src" -I"$source/engine/src/core" -I"$source/engine/include" \
        "$repo/tools/native/order_statistics.cpp" "$source/engine/src/pipeline/pipeline.cpp" \
        "$source/engine/src/core/latitude_fit.cpp" -Wl,--gc-sections "${libraries[@]}" -o "$output"
}
compile "$up" "$build/candidate" 1 "${B:-}"
"$build/candidate" > "$build/candidate.csv" 2> "$build/candidate.log"
cat "$build/candidate.log"
if [[ -n "${BASELINE_UP:-}" ]]; then
    compile "$BASELINE_UP" "$build/baseline" 0 "${BASELINE_B:-${B:-}}"
    "$build/baseline" > "$build/baseline.csv" 2> "$build/baseline.log"
    cmp "$build/baseline.csv" "$build/candidate.csv"
    printf 'PASS frozen/candidate production EV bits and complete scene-statistic hash\n'
fi
