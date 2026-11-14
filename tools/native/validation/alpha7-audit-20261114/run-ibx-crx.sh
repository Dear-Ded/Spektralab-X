#!/usr/bin/env bash
# Independent cloud computer / GCC 14 validation. Co-created with GPT (OpenAI).
set -euo pipefail
A=/tmp/gpt-alpha7-audit-20261114
LR=/data/work/engine-deepdive/dng-render-metadata-0035/libraw
mkdir -p "$A/source/src/decoders"
cp "$LR/src/decoders/crx.cpp" "$A/source/src/decoders/crx.cpp"
ln -sfn "$LR/internal" "$A/source/internal"
ln -sfn "$LR/libraw" "$A/source/libraw"
if ! grep -q 'MAX(0, band->qParam % 6)' "$A/source/src/decoders/crx.cpp"; then
    patch -d "$A/source" -p1 --fuzz=0 --batch < "$A/patches/libraw/0020-crx-signed-quantizer-index.patch"
fi
patch -d "$A/source" -p1 --fuzz=0 --batch < "$A/patches/libraw/0027-crx-defined-quantizer-arithmetic.patch"
sha256sum "$A/source/src/decoders/crx.cpp" > "$A/crx-source.sha256"
g++-14 --version | head -n 1 > "$A/toolchain.txt"
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
g++-14 -std=c++17 -O1 -g -ffunction-sections -fdata-sections -DLIBRAW_NO_CR3_MEMPOOL \
    -fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all \
    -fno-omit-frame-pointer -fno-pie -no-pie -I"$A/source" -I"$LR" \
    "$A/tools/native/libraw_crx_quantizer.cpp" -Wl,--gc-sections -o "$A/crx-gcc14"
"$A/crx-gcc14" > "$A/crx-gcc14.log" 2>&1
