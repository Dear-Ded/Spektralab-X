#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
# Rebuild the two translation units including losslessjpeg.h in a known archive.
set -euo pipefail
: "${BEFORE_SOURCE:?}" "${AFTER_SOURCE:?}" "${BEFORE_LIBRARY:?}" "${BUILD_DIR:?}"
[[ "$#" -gt 0 ]] || { printf 'supply public Bayer RAW control files\n' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
cp "$BEFORE_LIBRARY" "$BUILD_DIR/lib-before.a"
cp "$BEFORE_LIBRARY" "$BUILD_DIR/lib-after.a"
flags=(-O3 -DNDEBUG -fPIC -w -fopenmp -DLIBRAW_NODLL -DSPEKTRALAB_RAW_OPENMP=1
  -DUSE_ZLIB -DLIBRAW_MAX_PROFILE_SIZE_MB=16 -DLIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16
  -DLIBRAW_MAX_TILE_STREAM_COUNT=131072)
for unit in decoders/sonycc decompressors/losslessjpeg; do
  object="${unit##*/}.cpp.o"
  "${CXX:-c++}" "${flags[@]}" -I"$AFTER_SOURCE" -c "$AFTER_SOURCE/src/$unit.cpp" -o "$BUILD_DIR/$object"
  ar r "$BUILD_DIR/lib-after.a" "$BUILD_DIR/$object"
done
for version in before after; do
  source="$BEFORE_SOURCE"
  [[ "$version" == before ]] || source="$AFTER_SOURCE"
  "${CXX:-c++}" -O2 -fopenmp -DLIBRAW_NODLL -I"$source" \
    "$ROOT/tools/native/libraw_maintenance_file.cpp" "$BUILD_DIR/lib-$version.a" -lz \
    -o "$BUILD_DIR/file-$version"
done
export OMP_NUM_THREADS=2
for path in "$@"; do
  name="$(basename "$path")"
  sha256sum "$path"
  for version in before after; do
    "$BUILD_DIR/file-$version" "$path" "$BUILD_DIR/$version-$name.raw" "$BUILD_DIR/$version-$name.rgb" \
      > "$BUILD_DIR/$version-$name.log"
  done
  cmp "$BUILD_DIR/before-$name.log" "$BUILD_DIR/after-$name.log"
  cat "$BUILD_DIR/after-$name.log"
  for ext in raw rgb; do
    cmp "$BUILD_DIR/before-$name.$ext" "$BUILD_DIR/after-$name.$ext"
    sha256sum "$BUILD_DIR/after-$name.$ext"
  done
  printf 'PASS byte-identical unpacked RAW and RGB16: %s\n' "$name"
done
sha256sum "$BEFORE_LIBRARY" "$BUILD_DIR/lib-after.a" \
  "$AFTER_SOURCE/internal/losslessjpeg.h" "$ROOT/tools/native/run_libraw_huffman_files.sh" \
  > "$BUILD_DIR/inputs.sha256"
