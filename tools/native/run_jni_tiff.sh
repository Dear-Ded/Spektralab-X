#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host engine build}"
codec="${CODEC:?JPEG install prefix}"
build="${BUILD_DIR:?isolated output directory}"
math="${MATH_DIR:?generated upstream math includes}"
java="${JAVA_INCLUDE:-/usr/lib/jvm/java-17-openjdk-amd64/include}"
lib="$(sed -n 's/^SPEKTRALAB_LIBRAW_SOURCE:[^=]*=//p' "$b/CMakeCache.txt")"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread -ffunction-sections -fdata-sections)
source="$repo/tools/native/jni_tiff.cpp"
binary="$build/jni_tiff"
if [[ "${JVM:-0}" == 1 ]]; then
    flags+=(-fPIC -shared "-Wl,--version-script=$repo/tools/native/jni_tiff.exports")
    source="$repo/tools/native/jni_tiff_jvm.cpp"
    binary="$build/libspektralab_jni.so"
fi
[[ "${BEFORE_FIX:-0}" == 0 ]] || flags+=(-DSPK_TIFF_BEFORE_FIX)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
app="$repo/app/src/main/cpp"
[[ -z "${APP_CPP:-}" ]] || app="$APP_CPP"
"${CXX:-g++}" "${flags[@]}" -I"$repo/tools/native/jni_precision" -I"$app" -I"$math" \
  -I"$java" -I"$java/linux" -I"$up/engine/src" -I"$up/engine/src/core" -I"$up/engine/include" \
  -I"$up/engine/host" -I"$up/engine/third_party/volk" -I"$lib" -I"${VULKAN_HEADERS:?header root}" -I"$codec/include" \
  "$source" "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" \
  "$app/input_transform.cpp" "$app/resize.cpp" "$app/tiff16.cpp" "$app/jpeg444.cpp" "$up/engine/host/white_balance.cpp" \
  -Wl,--gc-sections -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
  "$b/engine/libspk_image_io.a" "$b/engine/libspk_libraw.a" "$b/engine/libspk_volk.a" "$codec/lib/libjpeg.a" \
  -Wl,--end-group -ldl -lz -fopenmp -o "$binary"
if [[ "${JVM:-0}" == 1 ]]; then
    mkdir -p "$build/classes" "$build/tmp"
    javac -d "$build/classes" "$repo/tools/jvm/stubs/android/graphics/Bitmap.java"
    cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
    compiler=""
    for dependency in org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 org.jetbrains.kotlin/kotlin-script-runtime/2.0.21 org.jetbrains.kotlin/kotlin-reflect/2.0.21 org.jetbrains/annotations/13.0 org.jetbrains.intellij.deps/trove4j/1.0.20200330 org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4; do
        jar="$(find "$cache/$dependency" -name '*.jar' -print -quit)"
        compiler="${compiler:+$compiler:}$jar"
    done
    stdlib="$(find "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -name '*.jar' -print -quit)"
    java -cp "$compiler:$stdlib" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
        -classpath "$stdlib:$build/classes" -d "$build/classes" \
        "$repo/app/src/main/java/com/dearded/spektralab/NativeEngine.kt" \
        "$repo/app/src/main/java/com/dearded/spektralab/IccProfile.kt" "$repo/tools/jvm/TiffJniContract.kt"
    java -Djava.library.path="$build" -cp "$build/classes:$stdlib" TiffJniContract "${RESOURCES:-$b/engine/resources}" "$build/tmp"
else
    args=("${RESOURCES:-$b/engine/resources}" "$build/tmp")
    [[ -z "${ADOBE_ICC:-}" ]] || args+=("$ADOBE_ICC")
    "$binary" "${args[@]}"
fi
