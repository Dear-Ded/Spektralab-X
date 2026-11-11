param([string]$BuildDir = (Join-Path $PSScriptRoot '../../build/host-jvm'))
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = [IO.Path]::GetFullPath($BuildDir)
$deps = Join-Path $build 'host-tools'
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Force $deps, $classes | Out-Null
$java = Get-ChildItem -LiteralPath $deps -Filter java.exe -Recurse | Select-Object -First 1
if (!$java) {
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
    $package = $assets[0].binary.package
    $archive = Join-Path $deps $package.name
    Invoke-WebRequest $package.link -OutFile "$archive.download" -TimeoutSec 180
    Move-Item -LiteralPath "$archive.download" -Destination $archive -Force
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.checksum) {
        throw 'Temurin download checksum differs from the official release metadata'
    }
    Expand-Archive $archive $deps
    $java = Get-ChildItem -LiteralPath $deps -Filter java.exe -Recurse | Select-Object -First 1
}
$coordinates = @(
    'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar',
    'org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar',
    'org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar',
    'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar',
    'org/jetbrains/annotations/13.0/annotations-13.0.jar',
    'org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar',
    'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar',
    'org/json/json/20240303/json-20240303.jar',
    'junit/junit/4.13.2/junit-4.13.2.jar',
    'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'
)
foreach ($coordinate in $coordinates) {
    $destination = Join-Path $deps ($coordinate.Split('/')[-1])
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest "https://repo.maven.apache.org/maven2/$coordinate" -OutFile "$destination.download" -TimeoutSec 180
        Move-Item -LiteralPath "$destination.download" -Destination $destination -Force
    }
}
$classpath = (Get-ChildItem $deps -Filter '*.jar' | ForEach-Object FullName) -join ';'
$src = Join-Path $repo 'app/src/main/java/com/dearded/spektralab'
$editor = [IO.File]::ReadAllText((Join-Path $src 'EditorViewModel.kt'))
$queue = [IO.File]::ReadAllText((Join-Path $src 'ProcessingQueue.kt'))
$enums = [regex]::Match($editor, '(?s)enum class ExportFormat .*?(?=data class UiState\()').Value
$jobState = [regex]::Match($queue, '(?m)^enum class JobState .*').Value
$job = [regex]::Match($queue, '(?ms)^data class ProcessingJob\(.*?^}\r?$').Value
if (!$enums -or !$job -or !$jobState) { throw 'Production declaration boundaries changed' }
$fragment = Join-Path $build 'QueueSerialization.kt'
[IO.File]::WriteAllText($fragment, "package com.dearded.spektralab`nimport org.json.JSONObject`n$enums`n$jobState`n$job", [Text.UTF8Encoding]::new($false))
$sources = @($fragment, (Join-Path $repo 'app/src/test/java/com/dearded/spektralab/TiffColorSpaceTest.kt'), (Join-Path $src 'IccProfile.kt'), (Join-Path $PSScriptRoot 'IccProfileContract.kt'))
$adobeTest = Join-Path $PSScriptRoot 'AdobeIccContract.kt'
if (Test-Path -LiteralPath $adobeTest) { $sources += $adobeTest }
& $java.FullName -version
& $java.FullName -cp $classpath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $classes @sources
if ($LASTEXITCODE) { throw 'Kotlin compilation failed' }
$runtime = "$classes;$classpath"
& $java.FullName -cp $runtime org.junit.runner.JUnitCore com.dearded.spektralab.TiffColorSpaceTest
if ($LASTEXITCODE) { throw 'TIFF queue contract failed' }
& $java.FullName '-Djava.awt.headless=true' -cp $runtime IccProfileContractKt (Join-Path $build 'prophoto.icc')
if ($LASTEXITCODE) { throw 'ROMM profile contract failed' }
if (Test-Path -LiteralPath $adobeTest) {
    & $java.FullName '-Djava.awt.headless=true' -cp $runtime AdobeIccContractKt (Join-Path $build 'adobe.icc')
    if ($LASTEXITCODE) { throw 'Adobe profile contract failed' }
}
