#!/usr/bin/env bash
# Build spk_android (spk_android.cpp) against an upstream engine build.
#
#   UP=<upstream tree> B=<upstream build dir> SRC=<dir with the app's colour files> build.sh
#
# B is a build of upstream's engine with SPEKTRALAB_BUILD_NATIVE_RAW=ON (the same
# configure that builds spektralab-host, see docs/PARITY.md). SRC holds this
# directory's spk_android.cpp plus the app's output_transform.*, layer2.* and
# resize.* and input_transform.* (app/src/main/cpp). upstream_output_math.inc
# and upstream_input_math.inc are generated here from upstream's output.cpp and
# image_io.{hpp,cpp}, with the markers app/src/main/cpp/CMakeLists.txt uses.
set -euo pipefail
UP="${UP:?upstream tree}"; B="${B:?upstream build dir}"; SRC="${SRC:-$(dirname "$0")}"
E="$B/engine"
slice() {  # file from to: the text from the first marker up to the second
    python3 - "$1" "$2" "$3" <<'PY'
import sys
t = open(sys.argv[1], encoding="utf-8").read()
a, b = t.find(sys.argv[2]), t.find(sys.argv[3])
assert a >= 0 and b > a, f"{sys.argv[1]} changed: cannot find markers"
sys.stdout.write(t[a:b])
PY
}
{ echo "// Generated from $UP/engine/host/output.cpp. Do not edit."
  slice "$UP/engine/host/output.cpp" "static inline float spow" "bool OutputTransform::fetch"
  slice "$UP/engine/host/output.cpp" "static inline void cam16ucs_into" "template <typename F>"
} > "$SRC/upstream_output_math.inc"
{ echo "// Generated from $UP/engine/host/image_io.{hpp,cpp}. Do not edit."
  slice "$UP/engine/host/image_io.hpp" "struct Trc {" "bool parse_icc("
  slice "$UP/engine/host/image_io.cpp" "double Trc::decode" "static uint32_t be32"
} > "$SRC/upstream_input_math.inc"
openmp=""
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$B/CMakeCache.txt" && openmp="-fopenmp"
c++ -std=c++20 -O2 $openmp \
    -I"$SRC" -I"$UP/engine/include" -I"$UP/engine/src" -I"$UP/engine/host" \
    -I"$UP/engine/third_party/stb" -I"$UP/build/deps/LibRaw-0.22.2" \
    "$SRC/spk_android.cpp" "$SRC/output_transform.cpp" "$SRC/input_transform.cpp" "$SRC/layer2.cpp" "$SRC/resize.cpp" \
    -Wl,--start-group "$E/libspektralab_engine.a" "$E/libspektralab_core.a" "$E/libspk_image_io.a" \
    "$E/libspk_libraw.a" "$E/libspk_volk.a" -Wl,--end-group \
    -lpthread -ldl -o "$SRC/spk_android"
echo "built $SRC/spk_android"
