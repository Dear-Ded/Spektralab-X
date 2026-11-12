#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
# Builds a diagnostic x86_64 JNI library and dex, never a distributable APK.
set -euo pipefail
: "${UP:?upstream through overlay 0053 required}" "${ANDROID_HOME:?SDK required}"
: "${BUILD_DIR:?new isolated Android build directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NDK="${NDK:-$ANDROID_HOME/ndk/27.2.12479018}"
cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR/native" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DSPK_UPSTREAM="$UP"
cmake --build "$BUILD_DIR/native" --target spektralab_jni -j4
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android29-clang++" \
  -std=c++20 -O2 -fPIC -shared -static-libstdc++ "$ROOT/tools/native/android_feedback/fixture.cpp" \
  -ljnigraphics -o "$BUILD_DIR/libfeedback_fixture.so"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex"
javac -source 17 -target 17 -classpath "$ANDROID_HOME/platforms/android-36/android.jar" \
  -d "$BUILD_DIR/classes" "$ROOT/tools/native/android_feedback/FeedbackAndroid.java"
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 29 \
  --lib "$ANDROID_HOME/platforms/android-36/android.jar" \
  --output "$BUILD_DIR/dex" "$BUILD_DIR"/classes/com/dearded/spektralab/*.class
jar cf "$BUILD_DIR/feedback.jar" -C "$BUILD_DIR/dex" classes.dex
{
  java -version 2>&1 | head -3
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" --version | head -1
  sha256sum "$BUILD_DIR/native/libspektralab_jni.so" "$BUILD_DIR/feedback.jar" \
    "$BUILD_DIR/libfeedback_fixture.so" "$ROOT/tools/native/android_feedback/fixture.cpp" \
    "$ROOT/tools/native/android_feedback/FeedbackAndroid.java" "$UP/engine/src/gpu/vulkan_gpu.cpp"
  find "$ROOT/app/src/main/cpp" -type f -print0 | sort -z | xargs -0 sha256sum
} > "$BUILD_DIR/build-inputs.txt"
