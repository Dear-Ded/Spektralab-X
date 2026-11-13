#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
set -euo pipefail
: "${LIBRAW_SOURCE:?LibRaw 0.22.2 source required}" "${BUILD_DIR:?isolated output directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
for version in before after; do
  mkdir -p "$BUILD_DIR/$version/src/decompressors"
  cp -a "$LIBRAW_SOURCE/internal" "$LIBRAW_SOURCE/libraw" "$BUILD_DIR/$version/"
  cp "$LIBRAW_SOURCE/src/decompressors/losslessjpeg.cpp" "$BUILD_DIR/$version/src/decompressors/"
done
patch_file="$ROOT/patches/libraw/0024-sony-ycbcr-cache-index-mask.patch"
if patch -d "$BUILD_DIR/after" --dry-run --fuzz=0 --forward --batch -p1 < "$patch_file" > /dev/null 2>&1; then
  patch -d "$BUILD_DIR/after" --fuzz=0 --forward --batch -p1 < "$patch_file" > "$BUILD_DIR/patch.log"
else
  patch -d "$BUILD_DIR/before" --fuzz=0 --reverse --batch -p1 < "$patch_file" > "$BUILD_DIR/patch.log"
fi
flags=(-std=c++20 -O2 -DLIBRAW_NODLL)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -O1 -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer -fno-pie -no-pie)
for version in before after; do
  source="$BUILD_DIR/$version"
  "${CXX:-c++}" "${flags[@]}" -I"$source" \
    "$ROOT/tools/native/libraw_huffman_cache.cpp" "$source/src/decompressors/losslessjpeg.cpp" \
    -o "$BUILD_DIR/$version.bin"
  "$BUILD_DIR/$version.bin" --legal > "$BUILD_DIR/$version.log" 2>&1
done
cmp "$BUILD_DIR/before.log" "$BUILD_DIR/after.log"
"$BUILD_DIR/after.bin" --high > "$BUILD_DIR/high.log" 2>&1
if [[ "${SANITIZE:-0}" != 0 ]]; then
  set +e
  "$BUILD_DIR/before.bin" --high > "$BUILD_DIR/before-high.log" 2>&1
  status=$?
  set -e
  if [[ "$status" == 0 ]] || ! grep -q 'AddressSanitizer: heap-buffer-overflow' "$BUILD_DIR/before-high.log"; then
    cat "$BUILD_DIR/before-high.log" >&2
    printf 'missing pre-fix cache overflow negative control\n' >&2
    exit 1
  fi
  printf 'PASS pre-fix high-index negative control (exit %s)\n' "$status"
fi
cat "$BUILD_DIR/after.log" "$BUILD_DIR/high.log"
sha256sum "$BUILD_DIR/before/internal/losslessjpeg.h" "$BUILD_DIR/after/internal/losslessjpeg.h" \
  "$ROOT/tools/native/libraw_huffman_cache.cpp" > "$BUILD_DIR/inputs.sha256"
