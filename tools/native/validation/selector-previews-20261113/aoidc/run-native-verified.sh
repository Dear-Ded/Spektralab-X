#!/usr/bin/env bash
set -uo pipefail
A=/data/work/gpt-selector-preview-20261113
G=/data/work/gpt-composable-styles-20261113/gradle-repo
mkdir -p "$A/attempts"
for mode in normal sanitized; do
    mv "$A/native-$mode.log" "$A/attempts/native-$mode-param-type.log"
    mv "$A/native-$mode.exit" "$A/attempts/native-$mode-param-type.exit"
done
export UP=/data/work/gpt-rank-selection-20261113/upstream
export B=/data/work/gpt-rank-selection-20261113/host
export RAW_BUILD=/data/work/gpt-raw-storage-20261113/host
export CODEC=/data/work/engine-next-20261110/build-host/engine/host/codec-install
export MATH_DIR=/data/work/gpt-lut-gate-20261111/aoidc VULKAN_HEADERS=/usr/include
export LP_NUM_THREADS=2 SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
export ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
cd "$G"
for mode in normal sanitized; do
    export BUILD_DIR="$A/native/$mode"
    if [[ "$mode" == normal ]]; then
        CXX=g++ SANITIZE=0 bash tools/native/run_jni_selection_preview.sh > "$A/native-$mode.log" 2>&1
    else
        CXX=clang++-14 SANITIZE=1 bash tools/native/run_jni_selection_preview.sh > "$A/native-$mode.log" 2>&1
    fi
    printf '%s\n' "$?" > "$A/native-$mode.exit"
done
date -Iseconds > "$A/native-finished.txt"
