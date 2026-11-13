#!/usr/bin/env bash
set -uo pipefail
A=/data/work/gpt-frame-autosave-20261113
G=/data/work/gpt-composable-styles-20261113/gradle-repo
mkdir -p "$A/source" "$A/results/release" "$A/results/debug"
tar -xzf "$A/source.tgz" -C "$A/source"
tar -xzf "$A/source.tgz" -C "$G"
cd "$A/source"
find app -type f -print0 | sort -z | xargs -0 sha256sum > "$A/source.sha256"
cd "$G"
sha256sum --check "$A/source.sha256" > "$A/input-check.log" 2>&1
printf '%s\n' "$?" > "$A/input-check.exit"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testReleaseUnitTest > "$A/release.log" 2>&1
printf '%s\n' "$?" > "$A/release.exit"
cp app/build/test-results/testReleaseUnitTest/TEST-*.xml "$A/results/release/" 2>/dev/null || true
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testDebugUnitTest --tests com.dearded.spektralab.FrameEditPersistenceTest \
  --tests com.dearded.spektralab.ProcessingQueueGrantTest :app:assembleDebug > "$A/debug.log" 2>&1
printf '%s\n' "$?" > "$A/debug.exit"
cp app/build/test-results/testDebugUnitTest/TEST-*.xml "$A/results/debug/" 2>/dev/null || true
sha256sum app/build/outputs/apk/debug/app-debug.apk > "$A/apk.sha256"
date -Iseconds > "$A/finished.txt"
