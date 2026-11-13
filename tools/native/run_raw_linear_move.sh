#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Link only the actual patched LibRaw archive.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
b="${B:?decoder build}"
build="${BUILD_DIR:?isolated test directory}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -pthread -DLIBRAW_NODLL -DUSE_ZLIB
    -DLIBRAW_MAX_PROFILE_SIZE_MB=16 -DLIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16 -DLIBRAW_MAX_TILE_STREAM_COUNT=131072)
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then flags+=(-fopenmp); fi
if [[ "${SANITIZE:-0}" == 1 ]]; then
    flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
fi
"${CXX:-c++}" "${flags[@]}" -I"$lib" "$repo/tools/native/raw_linear_move.cpp" \
    "$b/engine/libspk_libraw.a" -ldl -lz -o "$build/raw_linear_move"
"$build/raw_linear_move" "$@"
