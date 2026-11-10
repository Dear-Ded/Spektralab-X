#!/usr/bin/env bash
# Linux archive comparison: rebuild only the patched CRX translation unit.
# The caller supplies the archive built from BEFORE_SOURCE with production flags.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
before="${BEFORE_SOURCE:?}"
after="${AFTER_SOURCE:?}"
archive="${BEFORE_LIBRARY:?}"
build="${BUILD_DIR:?}"
mkdir -p "$build"
cxx="${CXX:-c++}"
cp "$archive" "$build/lib-before.a"
cp "$archive" "$build/lib-after.a"
"$cxx" -O3 -DNDEBUG -fPIC -w -fopenmp -DLIBRAW_NODLL \
    -DSPEKTRALAB_RAW_OPENMP=1 -DSPK_VULKAN_BACKEND=1 -I"$after" \
    -c "$after/src/decoders/crx.cpp" -o "$build/crx.cpp.o"
ar r "$build/lib-after.a" "$build/crx.cpp.o"
for v in before after; do
    source="$before"
    [[ "$v" == after ]] && source="$after"
    "$cxx" -O2 -fopenmp -DLIBRAW_NODLL -I"$source" \
        "$repo/tools/native/libraw_maintenance_file.cpp" "$build/lib-$v.a" \
        -o "$build/file-$v"
done
export OMP_NUM_THREADS=2
for path in "$@"; do
    name="$(basename "$path")"
    sha256sum "$path"
    for v in before after; do
        "$build/file-$v" "$path" "$build/$v-$name.raw" "$build/$v-$name.rgb"
    done
    for ext in raw rgb; do
        cmp "$build/before-$name.$ext" "$build/after-$name.$ext"
        sha256sum "$build/before-$name.$ext"
    done
    echo "BYTE IDENTICAL: $name raw + RGB16"
done
