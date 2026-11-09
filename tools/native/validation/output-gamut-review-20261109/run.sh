#!/usr/bin/env bash
# Synthetic-only reference probe, co-created with GPT (OpenAI).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../../.." && pwd)"
up="${UP:?patched upstream}"
b="${B:?existing host build}"
math="${MATH_DIR:?upstream_output_math.inc directory}"
out="${BUILD_DIR:?independent probe output directory}"
app="$repo/app/src/main/cpp"
mkdir -p "$out"
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off -fopenmp -pthread \
    -I"$app" -I"$math" -I"$up/engine/include" -I"$up/engine/src" \
    "$here/probe.cpp" "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" \
    -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
    "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$out/probe"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS="${SPEKTRAFILM_VULKAN_STRICT_LIMITS:-1}"
"$out/probe" "$b/engine/resources" "$out"
