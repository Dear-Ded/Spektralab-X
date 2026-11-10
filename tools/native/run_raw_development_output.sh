#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream}"
b="${B:?existing host build}"
build="${BUILD_DIR:?probe build directory}"
math="${MATH_DIR:?upstream_output_math.inc directory}"
mkdir -p "$build"
app="$repo/app/src/main/cpp"
flags=(-std=c++20 -O2 -Wall -Wextra -ffp-contract=off -fopenmp -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$app" -I"$math" -I"$up/engine/include" -I"$up/engine/src" \
    "$repo/tools/native/raw_development_output.cpp" "$app/output_transform.cpp" "$app/layer2.cpp" "$app/raw_development.cpp" \
    -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" \
    -Wl,--end-group -ldl -o "$build/raw_development_output"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS="${SPEKTRAFILM_VULKAN_STRICT_LIMITS:-1}"
"$build/raw_development_output" "$b/engine/resources"
