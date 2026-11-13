#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-13. Cloud compilation only.
set -euo pipefail
A=/data/work/gpt-export-routing-fixes-20261113
repo=/data/work/gpt-composable-styles-20261113/gradle-repo
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
mkdir -p "$A/jvm"
cd "$repo"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    -I /data/work/gpt-deep-audit-20261113/audit.init.gradle :app:testReleaseUnitTest
cp -a app/build/test-results/testReleaseUnitTest "$A/jvm/release"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    -I /data/work/gpt-deep-audit-20261113/audit.init.gradle :app:testDebugUnitTest \
    --tests '*ExternalFileRoutingTest' --tests '*StyleWorkflowUiTest' \
    --tests '*SourceGamutRoutingTest' --tests '*ProcessingQueueGrantTest' \
    --tests '*PhotoInputF16Test' --tests '*PhotoInputColorTest' \
    --tests '*PhotoGeometryTest' --tests '*OutputColorTagTest' \
    --tests '*FrameRateBoostTest' --tests '*FilmThumbnailColorTest'
cp -a app/build/test-results/testDebugUnitTest "$A/jvm/debug"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
    -I /data/work/gpt-deep-audit-20261113/audit.init.gradle :app:assembleDebug
date -Iseconds > "$A/jvm/finished.txt"
