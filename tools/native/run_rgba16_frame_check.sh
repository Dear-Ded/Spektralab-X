#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${BUILD_DIR:?isolated cloud test output required}"
mkdir -p "$BUILD_DIR"
flags=(-std=c++20 -O2 -Wall -Wextra -Werror)
[[ ${SANITIZE:-0} == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$ROOT/app/src/main/cpp" \
  "$ROOT/tools/native/rgba16_frame_check.cpp" -o "$BUILD_DIR/rgba16_frame_check"
"$BUILD_DIR/rgba16_frame_check"
