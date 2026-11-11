#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?candidate source}"
b="${B:?fully rebuilt engine with matching Gpu ABI}"
build="${BUILD_DIR:?isolated test output}"
mkdir -p "$build" "$build/scratch"
flags=(-std=c++20 -O2 -pthread -fno-access-control)
[[ -z "${PIPELINE_CPP:-}" ]] || flags+=(-DSPK_TAIL_BASELINE=1)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
includes=(-I"$up/engine/src" -I"$up/engine/src/pipeline" -I"$up/engine/src/core" -I"$up/engine/include" -I"$b/engine/generated")
runtime=()
[[ -z "${SANITIZER_CXX_RUNTIME:-}" ]] || runtime+=("$SANITIZER_CXX_RUNTIME")
"${CXX:-g++}" "${flags[@]}" -DSPK_VULKAN_BACKEND=1 "${includes[@]}" \
  -c "${PIPELINE_CPP:-$up/engine/src/pipeline/pipeline.cpp}" -o "$build/pipeline.o"
"${CXX:-g++}" "${flags[@]}" "${includes[@]}" "$repo/tools/native/pipeline_print_tail.cpp" "$build/pipeline.o" \
  -Wl,--wrap=pread -Wl,--wrap=__pread_chk -Wl,--wrap=pwrite -Wl,--start-group "$b/engine/libspektralab_engine.a" \
  "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" -Wl,--end-group "${runtime[@]}" -ldl -o "$build/pipeline_print_tail"
sha256sum "$build/pipeline.o" "$build/pipeline_print_tail" "$b/engine/libspektralab_engine.a"
ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=0}" SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 \
  "$build/pipeline_print_tail" "$b/engine/resources/vulkan" "${RESOURCES_DIR:-$b/engine/resources}" "$build/scratch"
