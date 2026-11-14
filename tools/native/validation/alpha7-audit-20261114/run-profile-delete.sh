#!/usr/bin/env bash
# Verify the real two-location cleanup failure. Co-created with GPT (OpenAI).
set -euo pipefail
A=/data/work/gpt-alpha7-profile-delete-20261114
G=/data/work/gpt-composable-styles-20261113/gradle-repo
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-alpha7-final-r3-20261114/upstream
export GLSLANG=/bin/glslangValidator
trap 'printf "%s\n" "$?" > "$A/final.exit"' EXIT
cd "$G"
cp app/src/main/java/com/dearded/spektralab/FilmRecipe.kt "$A/before-FilmRecipe.kt"
cp "$A/UserLibraryPersistenceTest.kt" app/src/test/java/com/dearded/spektralab/
gradle=(./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m)
set +e
"${gradle[@]}" :app:testReleaseUnitTest --tests '*UserLibraryPersistenceTest.failedEngineProfileCleanupPreservesTheUserOriginal' > "$A/before.log" 2>&1
before=$?
set -e
printf '%s\n' "$before" > "$A/before.exit"
cp app/build/test-results/testReleaseUnitTest/TEST-com.dearded.spektralab.UserLibraryPersistenceTest.xml "$A/before.xml"
cp "$A/FilmRecipe.kt" app/src/main/java/com/dearded/spektralab/
test "$before" -ne 0
"${gradle[@]}" :app:testReleaseUnitTest --tests '*UserLibraryPersistenceTest' > "$A/after.log" 2>&1
cp app/build/test-results/testReleaseUnitTest/TEST-com.dearded.spektralab.UserLibraryPersistenceTest.xml "$A/after.xml"
sha256sum app/src/main/java/com/dearded/spektralab/FilmRecipe.kt app/src/test/java/com/dearded/spektralab/UserLibraryPersistenceTest.kt > "$A/after-inputs.sha256"
