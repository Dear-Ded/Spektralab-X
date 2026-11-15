#!/usr/bin/env bash
# Production JNI preview/main equality for every built-in recipe. GPT (OpenAI).
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream}"; b="${B:?host build}"
raw="${RAW_BUILD:?RAW host build}"; codec="${CODEC:?JPEG prefix}"
build="${BUILD_DIR:?isolated output}"; math="${MATH_DIR:?generated math}"
java="${JAVA_INCLUDE:?JDK include directory}"
lib="${LIBRAW_SOURCE:?patched LibRaw source}"
fixtures="${FIXTURES:?SPK_BUILTIN_EVIDENCE_DIR emitted by BuiltinStyleFidelityTest}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread -ffunction-sections -fdata-sections)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
"${CXX:-g++}" "${flags[@]}" -I"$repo/tools/native/jni_precision" -I"$repo/app/src/main/cpp" \
  -I"$math" -I"$java" -I"$java/linux" -I"$up/engine/src" -I"$up/engine/src/core" \
  -I"$up/engine/include" -I"$up/engine/host" -I"$up/engine/third_party/volk" -I"$lib" \
  -I"${VULKAN_HEADERS:?Vulkan header root}" -I"$codec/include" \
  "$repo/tools/native/builtin_scheme_probe.cpp" "$repo/app/src/main/cpp/layer2.cpp" \
  "$repo/app/src/main/cpp/output_transform.cpp" "$repo/app/src/main/cpp/raw_development.cpp" \
  "$repo/app/src/main/cpp/input_transform.cpp" "$repo/app/src/main/cpp/resize.cpp" \
  "$repo/app/src/main/cpp/tiff16.cpp" "$repo/app/src/main/cpp/jpeg444.cpp" "$up/engine/host/white_balance.cpp" \
  -Wl,--gc-sections -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
  "$raw/engine/libspk_image_io.a" "$raw/engine/libspk_libraw.a" "$b/engine/libspk_volk.a" "$codec/lib/libjpeg.a" \
  -Wl,--end-group -ldl -lz -fopenmp -o "$build/builtin_scheme_probe"
"$build/builtin_scheme_probe" "${RESOURCES:-$b/engine/resources}" "$fixtures" "$build/evidence"
