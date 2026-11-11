#!/usr/bin/env bash
# Compare unchanged intents with real engine metadata/CAM16 tables. No APK.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?fully patched upstream tree}"
b="${B:?matching full_engine_host build}"
build="${BUILD_DIR:?isolated outputs}"
baseline="${BASELINE_CPP:?baseline app/src/main/cpp extracted from Git}"
math="${MATH_DIR:?generated upstream_output_math.inc directory}"
mkdir -p "$build"
for state in before after; do
    app="$baseline"
    [[ "$state" != after ]] || app="$repo/app/src/main/cpp"
    "${CXX:-c++}" -std=c++20 -O2 -pthread -ffp-contract=off -I"$app" -I"$math" \
        -I"$up/engine/include" -I"$up/engine/src" \
        "$repo/tools/native/adobe_legacy.cpp" "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" \
        -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
        "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -lz -o "$build/$state"
    "$build/$state" "$b/engine/resources" "$build/$state.bin"
done
cmp "$build/before.bin" "$build/after.bin"
wc -c "$build/after.bin"
sha256sum "$build/before.bin" "$build/after.bin"
printf '%s\n' 'PASS 48 real-engine legacy target/intent/curve/source/grade cases; full and strip sample bytes identical'
