#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree}"
B="${B:?host engine build directory}"
MATH_DIR="${MATH_DIR:?directory containing generated upstream_output_math.inc}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
app="$repo/app/src/main/cpp"
e="$B/engine"
"${CXX:-c++}" "${flags[@]}" -I"$app" -I"$MATH_DIR" -I"$UP/engine/include" -I"$UP/engine/src" \
    "$repo/tools/native/output_rows.cpp" "$app/output_transform.cpp" "$app/layer2.cpp" "$app/tiff16.cpp" \
    -Wl,--start-group "$e/libspektralab_engine.a" "$e/libspektralab_core.a" "$e/libspk_volk.a" \
    -Wl,--end-group -ldl -lz -o "$build/output_rows"
"$build/output_rows" "$B/engine/resources" "$@"
