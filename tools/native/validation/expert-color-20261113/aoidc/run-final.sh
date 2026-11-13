#!/usr/bin/env bash
set -euo pipefail
A=/data/work/gpt-expert-color-20261113
G=/data/work/gpt-composable-styles-20261113/gradle-repo
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
gradle=(./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -I /data/work/gpt-deep-audit-20261113/audit.init.gradle)
cd "$A/repo"
tr -d '\r' < "$A/source.sha256" | sha256sum -c - > "$A/source-verified-final.log"
sha256sum "$A/evidence/native-parity.bin" > "$A/native-fixture.sha256"
mkdir -p "$A/before"
tar -xf "$A/baseline.tar" -C "$G"
cd "$G"
set +e
"${gradle[@]}" :app:testReleaseUnitTest \
    --tests com.dearded.spektralab.LutBakeTest.smallSrgbTablesGetAFullResolutionComposedTransform \
    --tests com.dearded.spektralab.LutBakeTest.toeAndGamutBoundaryVerticesMatchIndependentReference \
    > "$A/before/gradle.log" 2>&1
before=$?
set -e
printf '%s\n' "$before" > "$A/before/gradle.exit"
cp app/build/test-results/testReleaseUnitTest/TEST-com.dearded.spektralab.LutBakeTest.xml "$A/before/"
tar -xzf "$A/source-final.tgz" -C "$G"
python3 - "$A/before/TEST-com.dearded.spektralab.LutBakeTest.xml" "$before" <<'PY'
import sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
assert sys.argv[2] != '0'
assert root.attrib['tests']=='2' and root.attrib['failures']=='2' and root.attrib['errors']=='0', root.attrib
print('negative control: both new numerical tests reject alpha-6 source')
PY
export SPK_LUT_EVIDENCE_DIR="$A/evidence"
set +e
"${gradle[@]}" :app:testReleaseUnitTest :app:assembleDebug > "$A/gradle-final.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$A/gradle-final.exit"
mkdir -p "$A/jvm"
cp app/build/test-results/testReleaseUnitTest/TEST-*.xml "$A/jvm/"
sha256sum -c "$A/native-fixture.sha256" > "$A/native-fixture-parity.log"
exit "$result"
