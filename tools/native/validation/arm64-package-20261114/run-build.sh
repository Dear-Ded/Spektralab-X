#!/usr/bin/env bash
set -euo pipefail
# Co-created with GPT (OpenAI). Research package; no publication step.
A=/data/work/gpt-arm64-package-20261114
G=/data/work/gpt-composable-styles-20261113/gradle-repo
S=/data/work/gpt-rank-selection-20261113
trap 'printf "%s\n" "$?" > "$A/build.exit"' EXIT
cd "$G"
sha256sum --check "$A/build-inputs.sha256" > "$A/input-check-before.log"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM="$S/upstream" GLSLANG=/bin/glslangValidator
export CMAKE_BUILD_PARALLEL_LEVEL=2
export PATH="$JAVA_HOME/bin:$PATH"
# Use the cloud debug certificate for this research candidate, never release keys.
unset SPK_KEYSTORE SPK_KEYSTORE_PASSWORD
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  :app:assembleRelease > "$A/gradle.log" 2>&1
sha256sum --check "$A/build-inputs.sha256" > "$A/input-check-after.log"
APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK"
cp "$APK" "$A/Spektralab-X-alpha-6-arm64-raw-51be487.apk"
cp app/build/outputs/apk/release/output-metadata.json "$A/output-metadata.json"
TOOLS=/opt/android/build-tools/36.0.0
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" > "$A/signature.log"
"$TOOLS/zipalign" -c -P 16 -v 4 "$APK" > "$A/alignment.log"
"$TOOLS/aapt2" dump badging "$APK" > "$A/badging.log"
find app/.cxx/RelWithDebInfo -path '*/arm64-v8a/CMakeCache.txt' -print \
  -exec grep -E 'ANDROID_ABI:|CMAKE_BUILD_TYPE:|CMAKE_CXX_FLAGS_(RELEASE|RELWITHDEBINFO):|CMAKE_HOME_DIRECTORY:' {} \; \
  > "$A/native-build-config.log"
find app/.cxx/RelWithDebInfo -path '*/arm64-v8a/compile_commands.json' -exec cp {} "$A/compile_commands.json" \;
sha256sum "$A/Spektralab-X-alpha-6-arm64-raw-51be487.apk" > "$A/apk.sha256"
date -Iseconds > "$A/build-finished.txt"
