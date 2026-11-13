#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. Correct host ABI and shim linkage.
set -uo pipefail
A=/data/work/gpt-export-routing-fixes-20261113
repo="$A/repo"
export UP=/data/work/gpt-rank-selection-20261113/upstream
export B=/data/work/gpt-rank-selection-20261113/host
export RAW_BUILD=/data/work/gpt-raw-storage-20261113/host
export CODEC=/data/work/engine-next-20261110/build-host/engine/host/codec-install
export MATH_DIR=/data/work/gpt-lut-gate-20261111/aoidc
export VULKAN_HEADERS=/usr/include LP_NUM_THREADS=2 SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
export UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
export ASAN_OPTIONS=detect_leaks=0:halt_on_error=1
cd "$repo"
mkdir -p "$A/jni-final"
printf 'test,exit_code\n' > "$A/jni-final/cases.csv"
failed=0
for mode in normal asan before; do
    export BUILD_DIR="$A/jni-final/$mode"
    case "$mode" in
        normal) command=(env CXX=g++);;
        asan) command=(env CXX=clang++-14 SANITIZE=1);;
        before) command=(env CXX=g++ APP_CPP="$A/baseline/app/src/main/cpp");;
    esac
    timeout --kill-after=10s 900s "${command[@]}" bash tools/native/run_jni_frame_check.sh > "$A/jni-final/$mode.log" 2>&1
    result=$?
    printf '%s,%s\n' "$mode" "$result" | tee -a "$A/jni-final/cases.csv"
    tail -n 8 "$A/jni-final/$mode.log"
    if [[ "$mode" == before ]]; then
        [[ "$result" == 1 ]] && grep -q 'FAIL Bitmap non-flat acceptance / true-flat refusal: export check:' "$A/jni-final/$mode.log" || failed=1
    else
        [[ "$result" == 0 ]] || failed=1
    fi
done
printf '%s\n' "$failed" > "$A/jni-final.exit"
date -Iseconds > "$A/jni-final/finished.txt"
exit "$failed"
