#!/usr/bin/env bash
# No Android build or APK. Uses the Kotlin compiler in Gradle's cache.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:?isolated output directory}"
mkdir -p "$build/classes"
stdlib="${KOTLIN_STDLIB:-$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -name '*.jar' -print -quit)}"
compiler="${KOTLIN_COMPILER_CP:-}"
if [[ -z "$compiler" ]]; then
    cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
    for dependency in org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 org.jetbrains.kotlin/kotlin-script-runtime/2.0.21 org.jetbrains.kotlin/kotlin-reflect/2.0.21 org.jetbrains/annotations/13.0 org.jetbrains.intellij.deps/trove4j/1.0.20200330 org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4; do
        jar="$(find "$cache/$dependency" -name '*.jar' -print -quit)"
        compiler="${compiler:+$compiler:}$jar"
    done
    compiler="$compiler:$stdlib"
fi
java -cp "$compiler" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib" \
    -d "$build/classes" "$repo/app/src/main/java/com/dearded/spektralab/IccProfile.kt" \
    "$repo/tools/jvm/IccProfileContract.kt" "$repo/tools/jvm/AdobeIccContract.kt"
java -Djava.awt.headless=true -cp "$build/classes:$stdlib" IccProfileContractKt "$build/prophoto.icc"
java -Djava.awt.headless=true -cp "$build/classes:$stdlib" AdobeIccContractKt "$build/adobe.icc"
