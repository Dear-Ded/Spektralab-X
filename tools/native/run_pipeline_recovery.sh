#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?upstream with recovery candidate applied}"
b="${B:?existing host engine build}"
build="${BUILD_DIR:?isolated output directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread -fno-access-control)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
includes=(-I"$up/engine/src" -I"$up/engine/src/pipeline" -I"$up/engine/src/core" -I"$up/engine/include" -I"$b/engine/generated")
# Override just this production translation unit, preserving existing archives.
"${CXX:-g++}" "${flags[@]}" -DSPK_VULKAN_BACKEND=1 "${includes[@]}" \
  -c "${PIPELINE_CPP:-$up/engine/src/pipeline/pipeline.cpp}" -o "$build/pipeline.o"
"${CXX:-g++}" "${flags[@]}" "${includes[@]}" "$repo/tools/native/pipeline_recovery.cpp" "$build/pipeline.o" \
  -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" \
  -Wl,--end-group -ldl -o "$build/pipeline_recovery"
args=("${SHADERS:-$b/engine/resources/vulkan}")
[[ "${PUBLICATION_ONLY:-0}" != 1 ]] || args+=(publication)
"$build/pipeline_recovery" "${args[@]}"
