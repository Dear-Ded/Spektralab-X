#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
awk '/^struct Trc \{/ { copy=1 } /^bool parse_icc\(/ { copy=0 } copy' \
  "$up/engine/host/image_io.hpp" > "$build/upstream_input_math.inc"
awk '/^double Trc::decode/ { copy=1 } /^static uint32_t be32/ { copy=0 } copy' \
  "$up/engine/host/image_io.cpp" >> "$build/upstream_input_math.inc"
grep -q 'struct Trc' "$build/upstream_input_math.inc"
grep -q 'linear_prophoto_space' "$build/upstream_input_math.inc"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" -I"$build" \
  "$repo/tools/native/bitmap_f16.cpp" "$repo/app/src/main/cpp/input_transform.cpp" -o "$build/bitmap_f16"
"$build/bitmap_f16"
