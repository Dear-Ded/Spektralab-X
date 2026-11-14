#!/usr/bin/env bash
# Cloud-only before/after storage regression, co-created with GPT (OpenAI).
set -uo pipefail
A=/data/work/gpt-alpha7-audit-20261114
G=/data/work/gpt-composable-styles-20261113/gradle-repo
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
export GLSLANG=/bin/glslangValidator
cd "$G" || exit 1
gradle=(./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m)
"${gradle[@]}" :app:testReleaseUnitTest --tests '*UserLibraryPersistenceTest' \
  :app:testDebugUnitTest --tests '*ManualRollPersistenceTest' > "$A/persistence-before.log" 2>&1
printf '%s\n' "$?" > "$A/persistence-before.exit"
mkdir -p "$A/persistence-before"
cp -a app/build/test-results/testReleaseUnitTest app/build/test-results/testDebugUnitTest "$A/persistence-before/"
tar -xf "$A/persistence-fix.tar" -C "$G" || exit 1
"${gradle[@]}" :app:testReleaseUnitTest :app:testDebugUnitTest > "$A/android-regression.log" 2>&1
rc=$?
printf '%s\n' "$rc" > "$A/android-regression.exit"
mkdir -p "$A/android-regression"
cp -a app/build/test-results/testReleaseUnitTest app/build/test-results/testDebugUnitTest "$A/android-regression/"
exit "$rc"
