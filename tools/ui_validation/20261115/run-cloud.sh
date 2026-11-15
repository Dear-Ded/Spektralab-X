#!/usr/bin/env bash
set -uo pipefail
G=/dev/shm/spk-ui-20261115
cd "$G"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
mkdir -p evidence ui-test-tmp
export TMPDIR="$G/ui-test-tmp"
bash ./gradlew --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process \
  --init-script tools/ui_validation/20261115/test-runtime.init.gradle \
  :app:testDebugUnitTest \
  --tests com.dearded.spektralab.LaunchIntroTimingTest \
  --tests com.dearded.spektralab.PrecisionDialUiTest \
  --tests com.dearded.spektralab.ColorWheelUiTest \
  --tests com.dearded.spektralab.CurveEditorUiTest \
  --tests com.dearded.spektralab.Screenshots.editorEmpty \
  --tests com.dearded.spektralab.Screenshots.editorStyleAndPanel \
  --tests com.dearded.spektralab.Screenshots.editorDevelop \
  --tests com.dearded.spektralab.Screenshots.editorAdjust \
  --tests com.dearded.spektralab.Screenshots.editorColourWheel \
  --tests com.dearded.spektralab.Screenshots.optionalBoundaryCornersKeepThePhotoAndEditUnchanged \
  --tests com.dearded.spektralab.Screenshots.editorBorderAndCrop \
  --tests com.dearded.spektralab.Screenshots.queueSheet \
  --tests com.dearded.spektralab.Screenshots.compactEnglishWorkspace \
  --tests com.dearded.spektralab.Screenshots.landscapeWorkspace \
  --tests com.dearded.spektralab.Screenshots.settings \
  --tests com.dearded.spektralab.Screenshots.lightTableGrid \
  --tests com.dearded.spektralab.StyleWorkflowUiTest \
  > evidence/gradle.log 2>&1
result=$?
printf '%s\n' "$result" > evidence/gradle.exit
cp -a app/build/test-results/testDebugUnitTest evidence/results
date -Iseconds > evidence/finished.txt
exit "$result"
