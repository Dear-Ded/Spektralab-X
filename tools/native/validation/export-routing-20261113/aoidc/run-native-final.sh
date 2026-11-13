#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. Public source and synthetic inputs only.
set -uo pipefail
A=/data/work/gpt-export-routing-fixes-20261113
repo="$A/repo"
export UP=/data/work/gpt-rank-selection-20261113/upstream
export B=/data/work/gpt-rank-selection-20261113/host
export RAW_BUILD=/data/work/gpt-raw-storage-20261113/host
export MATH_DIR=/data/work/gpt-lut-gate-20261111/aoidc
export CODEC=/data/work/gpt-feedback-env-20261112/android-build/native/jpeg-install
export VULKAN_HEADERS=/usr/include
export LP_NUM_THREADS=2 SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
export UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
mkdir -p "$A/native"
cd "$repo"
printf 'test,exit_code\n' > "$A/native/cases.csv"
failed=0
run_case() {
    local name=$1
    shift
    export BUILD_DIR="$A/native/$name"
    timeout --kill-after=10s 900s "$@" > "$A/native/$name.log" 2>&1
    local result=$?
    printf '%s,%s\n' "$name" "$result" | tee -a "$A/native/cases.csv"
    tail -n 3 "$A/native/$name.log"
    if [[ "$result" != 0 ]]; then failed=1; fi
}
for compiler in g++ clang++-14; do
    for check in rgba16 float; do
        run_case "$compiler-$check" env CXX="$compiler" bash "tools/native/run_${check}_frame_check.sh"
        run_case "$compiler-$check-asan" env CXX="$compiler" SANITIZE=1 bash "tools/native/run_${check}_frame_check.sh"
    done
done
for check in rgba16 float; do
    g++ -std=c++20 -O2 -Wall -Wextra -Werror -I"$A/baseline/app/src/main/cpp" \
        "tools/native/${check}_frame_check.cpp" -o "$A/native/before-$check"
    "$A/native/before-$check" > "$A/native/before-$check.log" 2>&1
    result=$?
    printf 'before-%s,exit=%s (expected 1)\n' "$check" "$result"
    [[ "$result" == 1 ]] || failed=1
done
for version in before after; do
    includes="$repo/app/src/main/cpp"
    [[ "$version" == after ]] || includes="$A/baseline/app/src/main/cpp"
    g++ -std=c++20 -O2 -ffp-contract=off -I"$includes" tools/native/frame_check_benchmark.cpp \
        -o "$A/native/benchmark-$version"
    "$A/native/benchmark-$version" > "$A/native/benchmark-$version.csv"
done
run_case nofilm bash tools/native/run_nofilm_float.sh
run_case output env SANITIZE=1 bash tools/native/run_output.sh
run_case output-cancellation env SANITIZE=1 bash tools/native/run_output_cancellation.sh
run_case jni-after bash tools/native/run_jni_frame_check.sh
run_case jni-after-asan env CXX=clang++-14 SANITIZE=1 ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 bash tools/native/run_jni_frame_check.sh
export BUILD_DIR="$A/native/jni-before"
timeout --kill-after=10s 900s env APP_CPP="$A/baseline/app/src/main/cpp" \
    bash tools/native/run_jni_frame_check.sh > "$A/native/jni-before.log" 2>&1
result=$?
printf 'jni-before,exit=%s (expected 1)\n' "$result"
[[ "$result" == 1 ]] || failed=1
date -Iseconds > "$A/native/finished.txt"
printf '%s\n' "$failed" > "$A/native.exit"
exit "$failed"
