param(
    [Parameter(Mandatory = $true)][string]$BuildDir,
    [string]$ToolDir = (Join-Path $PSScriptRoot '../../build/host-jvm/host-tools')
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = [IO.Path]::GetFullPath($BuildDir)
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$java = Get-ChildItem -LiteralPath $ToolDir -Filter java.exe -Recurse | Select-Object -First 1
if (!$java) { throw "No cached Java runtime in $ToolDir" }
$coroutines = Join-Path $build 'kotlinx-coroutines-core-jvm-1.9.0.jar'
if (!(Test-Path -LiteralPath $coroutines)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar' -OutFile $coroutines -TimeoutSec 120
}
$classpath = ((Get-ChildItem -LiteralPath $ToolDir -Filter '*.jar' |
    Where-Object Name -notlike 'kotlinx-coroutines-*' | ForEach-Object FullName) + $coroutines) -join ';'
$src = Join-Path $repo 'app/src/main/java/com/dearded/spektralab'
$queue = [IO.File]::ReadAllText((Join-Path $src 'ProcessingQueue.kt'))
$editor = [IO.File]::ReadAllText((Join-Path $src 'EditorViewModel.kt'))
function Extract([string]$Text, [string]$Pattern) {
    $match = [regex]::Match($Text, $Pattern)
    if (!$match.Success) { throw "Production declaration boundary changed: $Pattern" }
    return $match.Value
}
$declarations = (Extract $editor '(?s)enum class ExportFormat .*?(?=data class UiState\()') +
    (Extract $queue '(?m)^enum class JobState .*') + "`n" +
    (Extract $queue '(?ms)^data class ProcessingJob\(.*?^}\r?$')
$patterns = @(
    '(?ms)^    fun cancel\(.*?(?=^    fun retry\()',
    '(?ms)^    fun retry\(.*?(?=^    fun clearFinished\()',
    '(?ms)^    fun clearFinished\(.*?(?=^    fun nextId\()',
    '(?m)^    fun nextId\(.*',
    '(?ms)^    private suspend fun runOne\(.*?(?=^    private suspend fun finishUnsuccessful\()',
    '(?ms)^    private suspend fun finishUnsuccessful\(.*?(?=^    /\*\*)',
    '(?ms)^    private suspend fun runAdmitted\(.*?(?=^    private fun recoverable\()',
    '(?ms)^    private fun markPending\(.*?(?=^    /\*\*)',
    '(?ms)^    private fun recoverInterrupted\(.*?(?=^    /\*\*)',
    '(?ms)^    private fun settle\(.*?(?=^    /\*\*)',
    '(?ms)^    private fun set\(.*?(?=^    /\*\*)',
    '(?ms)^    private fun persist\(.*?(?=^    private fun load\()',
    '(?ms)^    private fun load\(.*?(?=^    private fun noticedInit\()',
    '(?ms)^    private fun noticedInit\(.*?(?=^    private fun now\()'
)
$methods = ($patterns | ForEach-Object { Extract $queue $_ }) -join "`n"
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'gallery/QueueHarness.kt.in'))
$harness = Join-Path $build 'ProductionQueueHarness.kt'
[IO.File]::WriteAllText($harness, $template.Replace('/* PRODUCTION_DECLARATIONS */', $declarations).Replace('/* PRODUCTION_METHODS */', $methods), [Text.UTF8Encoding]::new($false))
$sources = @(
    $harness,
    (Join-Path $src 'GalleryTransaction.kt'),
    (Join-Path $src 'MediaSaver.kt'),
    (Join-Path $repo 'app/src/test/java/com/dearded/spektralab/GalleryTransactionTest.kt')
) + (Get-ChildItem (Join-Path $PSScriptRoot 'gallery') -Filter '*.kt' | ForEach-Object FullName)
& $java.FullName -version
& $java.FullName '-Xmx768m' -cp $classpath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $classes @sources
if ($LASTEXITCODE) { throw 'Gallery transaction Kotlin compilation failed' }
$result = & $java.FullName '-Xmx512m' -cp "$classes;$classpath" org.junit.runner.JUnitCore com.dearded.spektralab.GalleryTransactionTest com.dearded.spektralab.GalleryQueueContract 2>&1
$result | Tee-Object (Join-Path $build 'junit-results.txt')
if ($LASTEXITCODE) { throw 'Gallery transaction contracts failed' }
Get-FileHash -Algorithm SHA256 @((Join-Path $src 'GalleryTransaction.kt'), (Join-Path $src 'MediaSaver.kt'), (Join-Path $src 'ProcessingQueue.kt'), $harness) |
    Select-Object Path, Hash | ConvertTo-Json | Set-Content (Join-Path $build 'source-sha256.json')
