#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree with 0033}"
b="${B:?host build with 0033}"
math="${MATH_DIR:?generated upstream_output_math.inc directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
app="$repo/app/src/main/cpp"
"${CXX:-c++}" "${flags[@]}" -I"$app" -I"$math" -I"$up/engine/include" \
    -I"$up/engine/src" -I"$up/engine/src/core" \
    "$repo/tools/native/output_cancellation.cpp" "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" "$app/tiff16.cpp" \
    -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
    "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -lz -o "$build/output_cancellation"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
"$build/output_cancellation" "$b/engine/resources" "$build"
