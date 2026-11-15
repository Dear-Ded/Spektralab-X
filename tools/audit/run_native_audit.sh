#!/usr/bin/env bash
# Cross-compiler numerical and decoder checks. Co-created with GPT (OpenAI).
set -euo pipefail
A="${A:?audit directory}"
test -f "$A/reports/libraw-replay.log"
mkdir -p "$A/native-reports"
trap 'printf "%s\n" "$?" > "$A/native-reports/final.exit"' EXIT
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
export UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
for compiler in g++ clang++-14; do
    command -v "$compiler" > /dev/null
    name="${compiler//+/p}"
    "$compiler" --version | head -n 1 > "$A/native-reports/$name-version.txt"
    for strict in 1 0; do
        CXX="$compiler" STRICT="$strict" SANITIZE=1 UP="$A/upstream" \
            BUILD_DIR="$A/native-$name-$strict" bash "$A/repo/tools/native/run.sh" --large \
            > "$A/native-reports/$name-$strict.log" 2>&1
    done
    for check in bitstreams crx_quantizer; do
        CXX="$compiler" LIBRAW_SOURCE="$A/libraw" BUILD_DIR="$A/raw-$name-$check" \
            bash "$A/repo/tools/native/run_libraw_$check.sh" \
            > "$A/native-reports/$name-$check.log" 2>&1
    done
done
date -u +%FT%TZ > "$A/native-reports/finished.txt"
