#!/usr/bin/env bash
set -euo pipefail
A=/data/work/gpt-raw-algorithm-20261113
G=/data/work/gpt-composable-styles-20261113/gradle-repo
S=/data/work/gpt-rank-selection-20261113
trap 'printf "%s\n" "$?" > "$A/build.exit"' EXIT
python3 "$A/verify-ab.py" > "$A/verify-ab.log"
cd "$G"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM="$S/upstream"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /data/work/gpt-deep-audit-20261113/audit.init.gradle \
  :app:testReleaseUnitTest :app:assembleDebug > "$A/gradle.log" 2>&1
printf '0\n' > "$A/gradle.exit"
mkdir -p "$A/results/release"
cp app/build/test-results/testReleaseUnitTest/TEST-*.xml "$A/results/release/"
cp app/src/main/cpp/raw_development.cpp "$S/repo/app/src/main/cpp/raw_development.cpp"
diff -qr app/src/main/cpp "$S/repo/app/src/main/cpp" > "$A/arm64-source-check.log"
cmake --build "$S/arm64" --target spektralab_jni -- -j2 > "$A/arm64.log" 2>&1
printf '0\n' > "$A/arm64.exit"
sha256sum app/build/outputs/apk/debug/app-debug.apk > "$A/apk.sha256"
sha256sum "$S/arm64/libspektralab_jni.so" > "$A/arm64.sha256"
find app/src/main/cpp -type f -print0 | sort -z | xargs -0 sha256sum > "$A/native-inputs.sha256"
sha256sum --check "$A/source.sha256" > "$A/build-input-check.log"
cd "$A/baseline"
find app -type f -print0 | sort -z | xargs -0 sha256sum > "$A/baseline.sha256"
date -Iseconds > "$A/build-finished.txt"
