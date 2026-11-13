#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
set -euo pipefail
: "${UP:?patched upstream required}" "${BUILD_DIR:?isolated output directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
flags=(-std=c++20 -O2 -ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$UP/engine/src" "$ROOT/tools/native/view_curve.cpp" -o "$BUILD_DIR/view_curve"
"$BUILD_DIR/view_curve" > "$BUILD_DIR/curves.csv" 2> "$BUILD_DIR/curves.log"
cat "$BUILD_DIR/curves.csv" "$BUILD_DIR/curves.log"
sha256sum "$UP/engine/src/pipeline/view_curve.hpp" "$ROOT/tools/native/view_curve.cpp" > "$BUILD_DIR/inputs.sha256"
