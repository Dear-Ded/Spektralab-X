#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree}"
B="${B:?host engine build directory}"
MATH_DIR="${MATH_DIR:?generated upstream_output_math.inc directory}"
JPEG_DIR="${JPEG_DIR:?libjpeg-turbo prefix with include and lib/libjpeg.a}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
app="$repo/app/src/main/cpp"
extra=()
if [[ -n "${BASELINE_JPEG_CPP:-}" ]]; then
    "${CXX:-c++}" "${flags[@]}" -I"$app" -I"$JPEG_DIR/include" -Dwrite_jpeg444=write_jpeg444_baseline \
        -c "$BASELINE_JPEG_CPP" -o "$build/baseline.o"
    extra+=(-DSPK_JPEG_BASELINE=1 "$build/baseline.o")
else
    extra+=(-DSPK_JPEG_BASELINE=0)
fi
"${CXX:-c++}" "${flags[@]}" "${extra[@]}" -I"$app" -I"$MATH_DIR" -I"$UP/engine/include" -I"$UP/engine/src" \
    -I"$JPEG_DIR/include" "$repo/tools/native/jpeg_rows.cpp" "$app/jpeg444.cpp" \
    "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" \
    -Wl,--start-group "$B/engine/libspektralab_engine.a" "$B/engine/libspektralab_core.a" \
    "$B/engine/libspk_volk.a" "$JPEG_DIR/lib/libjpeg.a" -Wl,--end-group -ldl -o "$build/jpeg_rows"
"$build/jpeg_rows" "$B/engine/resources" "$build" "$@"
