#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
e="$b/engine"
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
sha256sum "$up/engine/src/core/params.cpp" "$up/engine/src/pipeline/scene_latitude.cpp" \
    "$up/engine/src/shaders/vulkan/spk_scene_curve.comp" "$e/libspektralab_engine.a" "$e/libspektralab_core.a"
"${CXX:-c++}" "${flags[@]}" -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/tests" \
    "$repo/tools/native/scene_curve.cpp" -Wl,--start-group \
    "$e/libspektralab_engine.a" "$e/libspektralab_core.a" "$e/libspk_volk.a" -Wl,--end-group \
    -ldl -o "$build/scene_curve"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
"$build/scene_curve" "$e/resources"
"$build/scene_curve" "$e/resources" --api
