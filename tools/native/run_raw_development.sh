#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:?standalone build directory}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" \
    "$repo/tools/native/raw_development.cpp" "$repo/app/src/main/cpp/raw_development.cpp" \
    -o "$build/raw_development"
"$build/raw_development"
