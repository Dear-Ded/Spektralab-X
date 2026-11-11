#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-11. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
build="${BUILD_DIR:?isolated output directory}"
mkdir -p "$build/scratch"
flags=(-std=c++20 -O2 -Wall -Wextra -pthread)
[[ "${BEFORE_FIX:-0}" == 0 ]] || flags+=(-DSPK_PLANE_BEFORE_FIX)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
"${CXX:-g++}" "${flags[@]}" -I"$up/engine/src" \
  "$repo/tools/native/external_plane_contract.cpp" -Wl,--wrap=pwrite,--wrap=pread,--wrap=__pread_chk,--wrap=posix_fallocate \
  -Wl,--export-dynamic-symbol=pthread_create -ldl \
  -o "$build/external_plane_contract"
"$build/external_plane_contract" "$build/scratch"
