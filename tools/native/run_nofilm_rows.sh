#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream}"
b="${B:?existing host build}"
build="${BUILD_DIR:?probe build directory}"
jpeg="${JPEG_DIR:?existing JPEG prefix}"
math="${MATH_DIR:?upstream_output_math.inc directory}"
mkdir -p "$build"
app="$repo/app/src/main/cpp"
flags=(-std=c++20 -O2 -fopenmp -pthread -ffp-contract=off -DLIBRAW_NODLL)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
raw=()
if [[ "${RAW:-1}" == 1 ]]; then
    raw+=("$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a")
else
    flags+=(-DSPK_ROWS_RAW=0)
fi
"${CXX:-c++}" "${flags[@]}" -I"$app" -I"$math" -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/src/core" \
    -I"$jpeg/include" "$repo/tools/native/nofilm_rows.cpp" "$app/output_transform.cpp" "$app/layer2.cpp" "$app/jpeg444.cpp" \
    -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
    "${raw[@]}" "$b/engine/libspk_volk.a" "$jpeg/lib/libjpeg.a" \
    -Wl,--end-group -ldl -o "$build/nofilm_rows"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
export SPEKTRALAB_RAW_THREADS=2
"$build/nofilm_rows" "$b/engine/resources" verify
if [[ "${MEMORY:-0}" == 1 ]]; then
    for mode in full rows; do
        SPEKTRAFILM_VULKAN_STRICT_LIMITS=0 /usr/bin/time -v "$build/nofilm_rows" "$b/engine/resources" memory "$mode" "${INPUT:-synthetic}" "$build/$mode.jpg" 2>"$build/$mode.time" | tee "$build/$mode.log"
    done
    cmp "$build/full.jpg" "$build/rows.jpg"
    sha256sum "$build/full.jpg" "$build/rows.jpg"
fi
