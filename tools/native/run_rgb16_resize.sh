#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Run only on the designated build host.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:?isolated test directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" "$repo/tools/native/rgb16_resize.cpp" \
    "$repo/tools/native/resize_reference.cpp" "$repo/app/src/main/cpp/resize.cpp" -o "$build/rgb16_resize"
"$build/rgb16_resize" "$@"
