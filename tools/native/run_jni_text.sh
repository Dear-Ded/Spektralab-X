#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Optional real JVM CheckJNI validation.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -Wall -Wextra -Werror)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
if [[ "${REAL_JNI:-0}" == 1 ]]; then
    flags+=(-DSPK_REAL_JNI -I"${JAVA_HOME:?}/include" -I"$JAVA_HOME/include/linux"
        -L"$JAVA_HOME/lib/server" -Wl,-rpath,"$JAVA_HOME/lib/server")
    libs=(-ljvm)
else
    libs=()
fi
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" "$repo/tools/native/jni_text.cpp" "${libs[@]}" -o "$build/jni_text"
"$build/jni_text"
