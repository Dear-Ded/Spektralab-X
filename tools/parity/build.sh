#!/usr/bin/env bash
# Build spk_android (spk_android.cpp) against an upstream engine build.
#
#   UP=<upstream tree> B=<upstream build dir> SRC=<dir with the app's colour files> build.sh
#
# B is a build of upstream's engine with SPEKTRALAB_BUILD_NATIVE_RAW=ON (the same
# configure that builds spektralab-host, see docs/PARITY.md). SRC holds this
# directory's spk_android.cpp plus the app's output_transform.*, layer2.* and
# resize.* (app/src/main/cpp) and upstream_output_math.inc, generated the way
# app/src/main/cpp/CMakeLists.txt generates it.
set -euo pipefail
UP="${UP:?upstream tree}"; B="${B:?upstream build dir}"; SRC="${SRC:-$(dirname "$0")}"
E="$B/engine"
openmp=""
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$B/CMakeCache.txt" && openmp="-fopenmp"
c++ -std=c++20 -O2 $openmp \
    -I"$SRC" -I"$UP/engine/include" -I"$UP/engine/src" -I"$UP/engine/host" \
    -I"$UP/engine/third_party/stb" -I"$UP/build/deps/LibRaw-0.22.2" \
    "$SRC/spk_android.cpp" "$SRC/output_transform.cpp" "$SRC/layer2.cpp" "$SRC/resize.cpp" \
    -Wl,--start-group "$E/libspektralab_engine.a" "$E/libspektralab_core.a" "$E/libspk_image_io.a" \
    "$E/libspk_libraw.a" "$E/libspk_volk.a" -Wl,--end-group \
    -lpthread -ldl -o "$SRC/spk_android"
echo "built $SRC/spk_android"
