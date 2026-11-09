#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
lib="${LIBRAW_SOURCE:?source with patches applied}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++17 -O1 -g -ffunction-sections -fdata-sections -DLIBRAW_NO_CR3_MEMPOOL)
if [[ "${SANITIZE:-1}" == 1 ]]; then
    # Discarded full-decoder methods have no linked LibRaw RTTI in this probe.
    flags+=(-fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all -fno-omit-frame-pointer)
fi
"${CXX:-c++}" "${flags[@]}" -I"$lib" \
    "$repo/tools/native/libraw_crx_quantizer.cpp" -Wl,--gc-sections \
    -o "$build/libraw_crx_quantizer"
"$build/libraw_crx_quantizer" "${MODE:-all}"
