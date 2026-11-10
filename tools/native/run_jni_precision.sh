#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-10. Production TU with synthetic platform allocation shims.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host build}"
codec="${CODEC:?libjpeg-turbo install prefix}"
build="${BUILD_DIR:?isolated test directory}"
fixture="${FLOAT_DNG:?synthetic supported float LinearRaw fixture}"
java="${JAVA_INCLUDE:-/usr/lib/jvm/java-17-openjdk-amd64/include}"
mkdir -p "$build"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
math="${MATH_DIR:?directory containing generated input/output math}"
g++ -std=c++20 -O2 -ffp-contract=off -pthread -ffunction-sections -fdata-sections \
  -I"$repo/tools/native/jni_precision" -I"$repo/app/src/main/cpp" -I"$math" -I"$java" -I"$java/linux" \
  -I"$up/engine/src" -I"$up/engine/src/core" -I"$up/engine/include" -I"$up/engine/host" \
  -I"$up/engine/third_party/volk" -I"$lib" -I"${VULKAN_HEADERS:?Vulkan header root}" -I"$codec/include" \
  "$repo/tools/native/jni_precision.cpp" "$repo/app/src/main/cpp/output_transform.cpp" \
  "$repo/app/src/main/cpp/raw_development.cpp" "$repo/app/src/main/cpp/layer2.cpp" \
  "$repo/app/src/main/cpp/input_transform.cpp" "$repo/app/src/main/cpp/resize.cpp" \
  "$repo/app/src/main/cpp/tiff16.cpp" "$repo/app/src/main/cpp/jpeg444.cpp" "$up/engine/host/white_balance.cpp" \
  -Wl,--gc-sections -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
  "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" "$b/engine/libspk_volk.a" "$codec/lib/libjpeg.a" \
  -Wl,--end-group -ldl -lz -fopenmp -o "$build/jni_precision"
"$build/jni_precision" "$b/engine/resources" "$build/tmp" "$fixture"
