#!/usr/bin/env bash
set -uo pipefail
A=/data/work/gpt-save-preview-20261113
G=/data/work/gpt-composable-styles-20261113/gradle-repo
mkdir -p "$A/source" "$A/results/release" "$A/results/debug"
tar -xzf "$A/source.tgz" -C "$A/source" || exit 2
tar -xzf "$A/source.tgz" -C "$G" || exit 2
cd "$A/source" || exit 2
find app -type f -print0 | sort -z | xargs -0 sha256sum > "$A/source.sha256"
cd "$G" || exit 2
sha256sum --check "$A/source.sha256" > "$A/input-check.log" 2>&1 || exit 2
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testReleaseUnitTest > "$A/release.log" 2>&1
release_rc=$?
printf '%s\n' "$release_rc" > "$A/release.exit"
if [ "$release_rc" -ne 0 ]; then exit "$release_rc"; fi
cp app/build/test-results/testReleaseUnitTest/TEST-*.xml "$A/results/release/"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testDebugUnitTest --tests com.dearded.spektralab.FrameEditPersistenceTest \
  --tests com.dearded.spektralab.ProcessingQueueGrantTest \
  --tests com.dearded.spektralab.SelectionPreviewUiTest :app:assembleDebug > "$A/debug.log" 2>&1
debug_rc=$?
printf '%s\n' "$debug_rc" > "$A/debug.exit"
if [ "$debug_rc" -ne 0 ]; then exit "$debug_rc"; fi
cp app/build/test-results/testDebugUnitTest/TEST-*.xml "$A/results/debug/"
sha256sum --check "$A/source.sha256" > "$A/input-check-final.log" 2>&1
printf '%s\n' "$?" > "$A/input-check.exit"
sha256sum app/build/outputs/apk/debug/app-debug.apk > "$A/apk.sha256"
date -Iseconds > "$A/finished.txt"
