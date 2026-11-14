#!/usr/bin/env bash
# Real decoder source, before/after sanitizers. Co-created with GPT (OpenAI).
set -uo pipefail
A=/data/work/gpt-alpha7-audit-20261114
LR=/data/work/gpt-composable-styles-20261113/gradle-repo/app/.cxx/RelWithDebInfo/4x4c4s6b/arm64-v8a/LibRaw-0.22.2-spk
mkdir -p "$A/bitstream-source/src/decoders"
cp "$LR/src/decoders/decoders_libraw.cpp" "$LR/src/decoders/olympus14.cpp" "$A/bitstream-source/src/decoders/"
ln -sfn "$LR/internal" "$A/bitstream-source/internal"
ln -sfn "$LR/libraw" "$A/bitstream-source/libraw"
for patch in 0028-phaseone-s-bitstream-bounds.patch 0029-olympus-bitstream-bounds.patch; do
    patch -d "$A/bitstream-source" -p1 --fuzz=0 --batch < "$A/$patch" || exit 1
done
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
flags=(-std=c++17 -O1 -g -ffunction-sections -fdata-sections -DLIBRAW_NO_CR3_MEMPOOL
       -fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all
       -fno-omit-frame-pointer -fno-pie -no-pie)
for variant in before after; do
    includes=(-I"$LR")
    if test "$variant" = after; then includes=(-I"$A/bitstream-source" -I"$LR"); fi
    for kind in iiq olympus; do
        options=()
        if test "$kind" = olympus; then options=(-DSPK_TEST_OLYMPUS=1); fi
        for compiler in g++ clang++-14; do
            name="$A/$kind-$variant-${compiler//+/p}"
            "$compiler" "${flags[@]}" "${includes[@]}" "${options[@]}" "$A/libraw_bitstream_bounds.cpp" \
                -Wl,--gc-sections -o "$name" > "$name-build.log" 2>&1
            rc=$?
            if test "$rc" = 0; then "$name" > "$name.log" 2>&1; rc=$?; fi
            printf '%s\n' "$rc" > "$name.exit"
        done
    done
done
