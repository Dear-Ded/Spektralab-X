#!/usr/bin/env bash
# Compile the actual CRX decoder before and after, co-created with GPT (OpenAI).
set -uo pipefail
A=/data/work/gpt-alpha7-audit-20261114
LR=/data/work/gpt-composable-styles-20261113/gradle-repo/app/.cxx/RelWithDebInfo/4x4c4s6b/arm64-v8a/LibRaw-0.22.2-spk
export ASAN_OPTIONS=detect_leaks=0:halt_on_error=1
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
flags=(-std=c++17 -O1 -g -ffunction-sections -fdata-sections -DLIBRAW_NO_CR3_MEMPOOL
       -fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all
       -fno-omit-frame-pointer -fno-pie -no-pie)
ln -sfn "$LR/internal" "$A/crx-source/internal"
ln -sfn "$LR/libraw" "$A/crx-source/libraw"
for variant in before after; do
    includes=(-I"$LR")
    if test "$variant" = after; then includes=(-I"$A/crx-source" -I"$LR"); fi
    for compiler in g++ clang++-14; do
        name="$A/crx-$variant-${compiler//+/p}"
        "$compiler" "${flags[@]}" "${includes[@]}" "$A/libraw_crx_quantizer.cpp" \
          -Wl,--gc-sections -o "$name" > "$name-build.log" 2>&1
        rc=$?
        if test "$rc" = 0; then "$name" > "$name.log" 2>&1; rc=$?; fi
        printf '%s\n' "$rc" > "$name.exit"
    done
done
