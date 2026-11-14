#!/usr/bin/env bash
set -uo pipefail
# Co-created with GPT (OpenAI). Cloud-only lint and dependency inventory.
A=/data/work/gpt-alpha7-audit-20261114
G=/data/work/gpt-composable-styles-20261113/gradle-repo
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
export SPK_UPSTREAM=/data/work/gpt-rank-selection-20261113/upstream
export SPK_AUDIT_DIR="$A" GLSLANG=/bin/glslangValidator
cd "$G"
./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  -I "$A/runtime-components.init.gradle" :app:auditRuntimeComponents :app:lintRelease \
  > "$A/android-audit.log" 2>&1
rc=$?
printf '%s\n' "$rc" > "$A/android-audit.exit"
for report in app/build/reports/lint-results-release.xml app/build/reports/lint-results-release.txt; do
  if test -f "$report"; then cp "$report" "$A/"; fi
done
date -Iseconds > "$A/android-audit-finished.txt"
exit "$rc"
