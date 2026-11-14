#!/usr/bin/env bash
# Final replay and cloud regressions. Co-created with GPT (OpenAI).
set -euo pipefail
A=${A:-/data/work/gpt-alpha7-final-20261114}
G=/data/work/gpt-composable-styles-20261113/gradle-repo
S=/data/work/gpt-rank-selection-20261113
trap 'printf "%s\n" "$?" > "$A/final.exit"' EXIT
mkdir -p "$A/upstream" "$A/libraw"
# Replay every overlay on digest-verified pristine archives.
printf '%s  %s\n' \
    52ef254ffa7c70484a411c269f3f837f75d5a215ed7332d5fdab267317408f05 \
    /data/work/gpt-beta2-release-20261109/pinned-source.tar.gz \
    02275a04cf0d1477ab9fd7ee231ddea59e7b14497458da6e3ba957517d6655c7 \
    /data/work/main-qa/repo/upstream/build/deps/LibRaw-0.22.2.zip \
    | sha256sum --check > "$A/pristine-archives.log"
tar -xzf /data/work/gpt-beta2-release-20261109/pinned-source.tar.gz --strip-components=1 -C "$A/upstream"
for overlay in "$A/repo"/patches/*.patch; do
    patch -d "$A/upstream" -p1 --fuzz=0 --batch < "$overlay"
done > "$A/engine-patch.log"
mkdir -p "$A/upstream/build"
ln -s "$S/upstream/build/deps" "$A/upstream/build/deps"
unzip -q /data/work/main-qa/repo/upstream/build/deps/LibRaw-0.22.2.zip -d "$A/pristine"
cp -a "$A/pristine/LibRaw-0.22.2/." "$A/libraw/"
sed -i 's/\r$//' "$A/libraw/src/decoders/crx.cpp"
for overlay in "$A/repo"/patches/libraw/*.patch; do
    patch -d "$A/libraw" -p1 --fuzz=0 --batch < "$overlay"
done > "$A/libraw-replay.log"
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
export UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1
for compiler in g++ clang++-14; do
    LIBRAW_SOURCE="$A/libraw" BUILD_DIR="$A/bitstreams-${compiler//+/p}" CXX="$compiler" \
        bash "$A/repo/tools/native/run_libraw_bitstreams.sh" > "$A/bitstreams-${compiler//+/p}.log" 2>&1
    LIBRAW_SOURCE="$A/libraw" BUILD_DIR="$A/quantizer-${compiler//+/p}" CXX="$compiler" \
        bash "$A/repo/tools/native/run_libraw_crx_quantizer.sh" > "$A/quantizer-${compiler//+/p}.log" 2>&1
done
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM="$A/upstream" GLSLANG=/bin/glslangValidator
export PATH="$JAVA_HOME/bin:$PATH"
tar -C "$A/repo" -cf - . | tar -C "$G" -xf -
cd "$G"
sha256sum --check "$A/build-inputs.sha256" > "$A/source-check-before.log"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    :app:testReleaseUnitTest :app:testDebugUnitTest :app:lintRelease > "$A/android-final.log" 2>&1
sha256sum --check "$A/build-inputs.sha256" > "$A/source-check-after.log"
mkdir -p "$A/test-results"
cp -a app/build/test-results/testReleaseUnitTest app/build/test-results/testDebugUnitTest "$A/test-results/"
cp app/build/reports/lint-results-release.txt app/build/reports/lint-results-release.xml "$A/"
date -u +%FT%TZ > "$A/finished.txt"
