#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream}"
b="${B:?existing host build}"
build="${BUILD_DIR:?test output directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$b/CMakeCache.txt" && flags+=(-fopenmp)
"${CXX:-c++}" "${flags[@]}" -DSPK_DNG_RENDER_METADATA="${SPK_DNG_RENDER_METADATA:-1}" \
    -I"$up/engine/src" "$repo/tools/native/dng_render_metadata.cpp" \
    -Wl,--start-group "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" \
    -Wl,--end-group -ldl -o "$build/dng_render_metadata"
if [[ "${SPK_DNG_RENDER_METADATA:-1}" == 1 ]]; then
    python3 "$repo/tools/native/dng_render_metadata_fixtures.py" "$build/fixtures" \
        --probe "$build/dng_render_metadata" "$@"
fi
