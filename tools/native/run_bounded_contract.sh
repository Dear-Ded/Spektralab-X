#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?upstream with bounded headers}"
b="${B:?existing host engine build}"
build="${BUILD_DIR:?isolated output directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
"${CXX:-g++}" "${flags[@]}" -I"$up/engine/src" -I"$up/engine/src/pipeline" -I"$up/engine/src/core" \
  -I"$up/engine/include" "$repo/tools/native/bounded_contract.cpp" \
  -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" \
  -Wl,--end-group -ldl -o "$build/bounded_contract"
"$build/bounded_contract" "$build/scratch"
