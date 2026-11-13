#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. Existing cloud Android toolchains only.
set -euo pipefail
A=/data/work/gpt-export-routing-fixes-20261113
trap 'printf "%s\n" "$?" > "$A/build.exit"' EXIT
S=/data/work/gpt-rank-selection-20261113
cmake --build "$S/arm64" --target spektralab_jni -j2 > "$A/arm64.log" 2>&1
sha256sum "$S/arm64/libspektralab_jni.so" > "$A/arm64.sha256"
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export SPK_UPSTREAM="$S/upstream"
cd /data/work/gpt-composable-styles-20261113/gradle-repo
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    -I /data/work/gpt-deep-audit-20261113/audit.init.gradle :app:assembleDebug > "$A/x86-debug.log" 2>&1
sha256sum app/build/intermediates/cxx/Debug/4j2l38c4/obj/x86_64/libspektralab_jni.so \
    app/build/outputs/apk/debug/app-debug.apk > "$A/x86-debug.sha256"
date -Iseconds > "$A/build-finished.txt"
