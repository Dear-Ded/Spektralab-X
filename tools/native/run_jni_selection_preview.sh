#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Cloud-only production JNI selection preview integration.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host build}"
raw_build="${RAW_BUILD:-$b}"
codec="${CODEC:?Linux libjpeg-turbo prefix}"
build="${BUILD_DIR:?isolated test directory}"
math="${MATH_DIR:?generated input/output math}"
java="${JAVA_INCLUDE:-/usr/lib/jvm/java-17-openjdk-amd64/include}"
lib="${LIBRAW_SOURCE:-$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$raw_build/CMakeCache.txt")}"
test -f "$lib/libraw/libraw.h"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread -ffunction-sections -fdata-sections)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
"${CXX:-g++}" "${flags[@]}" \
  -I"$repo/tools/native/jni_precision" -I"$repo/app/src/main/cpp" -I"$math" -I"$java" -I"$java/linux" \
  -I"$up/engine/src" -I"$up/engine/src/core" -I"$up/engine/include" -I"$up/engine/host" \
  -I"$up/engine/third_party/volk" -I"$lib" -I"${VULKAN_HEADERS:?Vulkan header root}" -I"$codec/include" \
  "$repo/tools/native/jni_selection_preview.cpp" "$repo/app/src/main/cpp/output_transform.cpp" \
  "$repo/app/src/main/cpp/raw_development.cpp" "$repo/app/src/main/cpp/layer2.cpp" \
  "$repo/app/src/main/cpp/input_transform.cpp" "$repo/app/src/main/cpp/resize.cpp" \
  "$repo/app/src/main/cpp/tiff16.cpp" "$repo/app/src/main/cpp/jpeg444.cpp" "$up/engine/host/white_balance.cpp" \
  -Wl,--gc-sections -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
  "$raw_build/engine/libspk_image_io.a" "$raw_build/engine/libspk_libraw.a" "$b/engine/libspk_volk.a" "$codec/lib/libjpeg.a" \
  -Wl,--end-group -ldl -lz -fopenmp -o "$build/jni_selection_preview"
"$build/jni_selection_preview" "${RESOURCES:-$b/engine/resources}" "$build/evidence"
