#!/usr/bin/env bash
# Isolated source replay and regressions. Co-created with GPT (OpenAI).
set -euo pipefail
A="${A:?isolated audit directory}"
ARCHIVE="${ARCHIVE:?digest-verified tracked source archive}"
engine_archive="${PRISTINE_ENGINE_ARCHIVE:?pinned pristine engine archive}"
libraw_archive="${PRISTINE_LIBRAW_ARCHIVE:?LibRaw 0.22.2 zip}"
dependencies="${DEPENDENCIES:?read-only upstream dependency cache}"
test -f "$ARCHIVE"
mkdir -p "$A/repo" "$A/reports" "$A/upstream" "$A/libraw"
trap 'printf "%s\n" "$?" > "$A/reports/final.exit"' EXIT
tar -xzf "$ARCHIVE" -C "$A/repo"
python3 "$A/full_inventory.py" "$ARCHIVE" "$A/repo" "$A/reports/inventory.json"
printf '%s  %s\n' \
    52ef254ffa7c70484a411c269f3f837f75d5a215ed7332d5fdab267317408f05 \
    "$engine_archive" \
    02275a04cf0d1477ab9fd7ee231ddea59e7b14497458da6e3ba957517d6655c7 \
    "$libraw_archive" \
    | sha256sum --check > "$A/reports/pristine-archives.log"
tar -xzf "$engine_archive" --strip-components=1 -C "$A/upstream"
for overlay in "$A/repo"/patches/*.patch; do
    patch -d "$A/upstream" -p1 --fuzz=0 --batch < "$overlay"
done > "$A/reports/engine-replay.log"
mkdir -p "$A/upstream/build"
ln -s "$dependencies" "$A/upstream/build/deps"
unzip -q "$libraw_archive" -d "$A/pristine"
cp -a "$A/pristine/LibRaw-0.22.2/." "$A/libraw/"
sed -i 's/\r$//' "$A/libraw/src/decoders/crx.cpp"
for overlay in "$A/repo"/patches/libraw/*.patch; do
    patch -d "$A/libraw" -p1 --fuzz=0 --batch < "$overlay"
done > "$A/reports/libraw-replay.log"
(cd "$A/upstream" && find engine -type f -print0 | sort -z | xargs -0 sha256sum) > "$A/reports/engine-bytes.sha256"
(cd "$A/libraw" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$A/reports/libraw-bytes.sha256"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM="$A/upstream" GLSLANG=/bin/glslangValidator
export SPK_LUT_EVIDENCE_DIR="$A/lut-evidence"
export PATH="$JAVA_HOME/bin:$PATH"
bash "$A/repo/scripts/stage-assets.sh" "$A/upstream" > "$A/reports/stage-assets.log" 2>&1
cd "$A/repo"
chmod +x gradlew
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    :app:testReleaseUnitTest :app:testDebugUnitTest :app:lintRelease > "$A/reports/android.log" 2>&1
cp -a app/build/test-results "$A/reports/"
cp app/build/reports/lint-results-release.* "$A/reports/"
python3 "$A/full_inventory.py" "$ARCHIVE" "$A/repo" "$A/reports/inventory-after.json"
date -u +%FT%TZ > "$A/reports/finished.txt"
