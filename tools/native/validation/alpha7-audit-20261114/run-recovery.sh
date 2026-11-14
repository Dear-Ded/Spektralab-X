#!/usr/bin/env bash
# Full production recovery paths with both sanitizer compilers. GPT (OpenAI).
set -uo pipefail
A=/data/work/gpt-alpha7-audit-20261114
export UP=/data/work/gpt-rank-selection-20261113/upstream
export B=/data/work/gpt-rank-selection-20261113/host
export ASAN_OPTIONS=detect_leaks=0:halt_on_error=1
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
export SANITIZE=1
export PIPELINE_CPP="$A/pipeline-sequenced.cpp"
for compiler in g++ clang++-14; do
    export CXX="$compiler" BUILD_DIR="$A/recovery-${compiler//+/p}"
    bash /data/work/gpt-rank-selection-20261113/repo/tools/native/run_pipeline_recovery.sh \
        > "$BUILD_DIR.log" 2>&1
    printf '%s\n' "$?" > "$BUILD_DIR.exit"
done
