#!/usr/bin/env bash
set -uo pipefail
A=/data/work/gpt-selector-preview-20261113
G=/data/work/gpt-composable-styles-20261113/gradle-repo
mkdir -p "$A/source" "$A/results"
tar -xzf "$A/source.tgz" -C "$A/source"
tar -xzf "$A/source.tgz" -C "$G"
cd "$A/source"
find app tools -type f -print0 | sort -z | xargs -0 sha256sum > "$A/source.sha256"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
cd "$G"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testReleaseUnitTest :app:testDebugUnitTest \
  --tests com.dearded.spektralab.SelectionPreviewUiTest \
  --tests com.dearded.spektralab.StyleWorkflowUiTest \
  --tests com.dearded.spektralab.FilmThumbnailColorTest \
  --tests com.dearded.spektralab.PhotoGeometryTest \
  --tests com.dearded.spektralab.OutputColorTagTest \
  :app:assembleDebug > "$A/gradle.log" 2>&1
printf '%s\n' "$?" > "$A/gradle.exit"
mkdir -p "$A/results/debug" "$A/results/release"
cp app/build/test-results/testDebugUnitTest/TEST-*.xml "$A/results/debug/" 2>/dev/null || true
cp app/build/test-results/testReleaseUnitTest/TEST-*.xml "$A/results/release/" 2>/dev/null || true
# Test filters are task-specific in Gradle; run the complete release suite explicitly.
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testReleaseUnitTest > "$A/release.log" 2>&1
printf '%s\n' "$?" > "$A/release.exit"
cp app/build/test-results/testReleaseUnitTest/TEST-*.xml "$A/results/release/" 2>/dev/null || true
sha256sum app/build/outputs/apk/debug/app-debug.apk > "$A/apk.sha256"
cp app/build/outputs/roborazzi/selection-preview-393.png "$A/" 2>/dev/null || true

export UP="$SPK_UPSTREAM" B=/data/work/gpt-rank-selection-20261113/host
export RAW_BUILD=/data/work/gpt-raw-storage-20261113/host
export CODEC=/data/work/engine-next-20261110/build-host/engine/host/codec-install
export MATH_DIR=/data/work/gpt-lut-gate-20261111/aoidc VULKAN_HEADERS=/usr/include
export LP_NUM_THREADS=2 SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
export ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
for mode in normal sanitized; do
    export BUILD_DIR="$A/native/$mode"
    if [[ "$mode" == normal ]]; then
        CXX=g++ SANITIZE=0 bash tools/native/run_jni_selection_preview.sh > "$A/native-$mode.log" 2>&1
    else
        CXX=clang++-14 SANITIZE=1 bash tools/native/run_jni_selection_preview.sh > "$A/native-$mode.log" 2>&1
    fi
    printf '%s\n' "$?" > "$A/native-$mode.exit"
done
date -Iseconds > "$A/finished.txt"
