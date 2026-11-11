#!/usr/bin/env bash
# Compiles production queue serialization declarations, without Android or an APK.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:?isolated output directory}"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
mkdir -p "$build/classes"
jar_for() { find "$cache/$1" -name '*.jar' -print -quit; }
stdlib="${KOTLIN_STDLIB:-$(jar_for org.jetbrains.kotlin/kotlin-stdlib/2.0.21)}"
compiler="${KOTLIN_COMPILER_CP:-}"
if [[ -z "$compiler" ]]; then
    for dependency in org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 org.jetbrains.kotlin/kotlin-script-runtime/2.0.21 org.jetbrains.kotlin/kotlin-reflect/2.0.21 org.jetbrains/annotations/13.0 org.jetbrains.intellij.deps/trove4j/1.0.20200330 org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4; do
        jar="$(jar_for "$dependency")"
        compiler="${compiler:+$compiler:}$jar"
    done
    compiler="$compiler:$stdlib"
fi
json="${JSON_JAR:-$(jar_for org.json/json/20240303)}"
junit="${JUNIT_JAR:-$(jar_for junit/junit/4.13.2)}"
hamcrest="${HAMCREST_JAR:-$(jar_for org.hamcrest/hamcrest-core/1.3)}"
src="$repo/app/src/main/java/com/dearded/spektralab"
# Extract the complete declarations verbatim; do not duplicate serializer logic.
{
    printf 'package com.dearded.spektralab\nimport org.json.JSONObject\n'
    awk '/^enum class ExportFormat / { active=1 } /^data class UiState\(/ { exit } active' "$src/EditorViewModel.kt"
    awk '/^enum class JobState / { print } /^data class ProcessingJob\(/ { active=1 } active { print } active && /^}\r?$/ { exit }' "$src/ProcessingQueue.kt"
} > "$build/QueueSerialization.kt"
cp="$stdlib:$json:$junit:$hamcrest"
java -cp "$compiler" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$cp" \
    -d "$build/classes" "$build/QueueSerialization.kt" \
    "$repo/app/src/test/java/com/dearded/spektralab/TiffColorSpaceTest.kt"
java -cp "$build/classes:$cp" org.junit.runner.JUnitCore com.dearded.spektralab.TiffColorSpaceTest
