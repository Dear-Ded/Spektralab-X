#!/usr/bin/env bash
# Exercise the actual RAW decoder readers. Co-created with GPT (OpenAI).
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
lib="${LIBRAW_SOURCE:?source with patches applied}"
build="${BUILD_DIR:?isolated output directory}"
mkdir -p "$build"
flags=(-std=c++17 -O1 -g -ffunction-sections -fdata-sections -DLIBRAW_NO_CR3_MEMPOOL)
if [[ "${SANITIZE:-1}" == 1 ]]; then
    flags+=(-fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all
            -fno-omit-frame-pointer -fno-pie -no-pie)
fi
for kind in iiq olympus; do
    options=()
    if [[ "$kind" == olympus ]]; then options=(-DSPK_TEST_OLYMPUS=1); fi
    "${CXX:-c++}" "${flags[@]}" "${options[@]}" -I"$lib" \
        "$repo/tools/native/libraw_bitstream_bounds.cpp" -Wl,--gc-sections -o "$build/$kind"
    "$build/$kind"
done
