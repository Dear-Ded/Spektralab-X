#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Full production decoder and precision dispatch.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream}"
b="${B:?host IO build}"
build="${BUILD_DIR:?isolated tests}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
flags=(-std=c++20 -O2 -pthread -ffp-contract=off -DLIBRAW_NODLL -DUSE_ZLIB
    -DLIBRAW_MAX_PROFILE_SIZE_MB=16 -DLIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16 -DLIBRAW_MAX_TILE_STREAM_COUNT=131072)
if grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt"; then
    flags+=(-fopenmp -DSPEKTRALAB_RAW_OPENMP=1)
fi
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
io=("$b/engine/libspk_image_io.a")
if [[ "${SANITIZE:-0}" == 1 ]]; then
    # Instrument the changed decoder as well as the adapter. LibRaw is the
    # existing production archive; this is not a fully instrumented LibRaw.
    io=("$up/engine/src/io/raw_decoder.cpp" "$up/engine/src/io/raw_headroom.cpp")
fi
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$up/engine/src" -I"$lib" \
    "$repo/tools/native/raw_working_decode.cpp" "$repo/app/src/main/cpp/resize.cpp" \
    -Wl,--start-group "${io[@]}" "$b/engine/libspk_libraw.a" -Wl,--end-group \
    -ldl -lz -o "$build/raw_working_decode"
"$build/raw_working_decode" "$@"
