param(
    [Parameter(Mandatory = $true)][string]$BuildDir,
    [string]$ToolDir = (Join-Path $PSScriptRoot '../../build/host-jvm/host-tools'),
    [string]$CoroutinesJar,
    [string]$EditorSource,
    [string]$EditorRef
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = [IO.Path]::GetFullPath($BuildDir)
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$java = Get-ChildItem -LiteralPath $ToolDir -Filter java.exe -Recurse | Select-Object -First 1
if (!$java) { throw "No cached Java runtime in $ToolDir" }
if (!$CoroutinesJar) { $CoroutinesJar = Join-Path $build 'kotlinx-coroutines-core-jvm-1.9.0.jar' }
if (!(Test-Path -LiteralPath $CoroutinesJar)) { throw 'Pass -CoroutinesJar pointing to cached kotlinx-coroutines-core-jvm 1.9.0.' }
$classpath = ((Get-ChildItem -LiteralPath $ToolDir -Filter '*.jar' |
    Where-Object Name -notlike 'kotlinx-coroutines-*' | ForEach-Object FullName) + $CoroutinesJar) -join ';'
$src = Join-Path $repo 'app/src/main/java/com/dearded/spektralab'
if ($EditorRef) {
    $snapshot = & git -C $repo show "${EditorRef}:app/src/main/java/com/dearded/spektralab/EditorViewModel.kt"
    if ($LASTEXITCODE) { throw "Cannot read editor ref $EditorRef" }
    $EditorSource = Join-Path $build 'EditorViewModel.baseline.kt'
    [IO.File]::WriteAllText($EditorSource, ($snapshot -join "`n"), [Text.UTF8Encoding]::new($false))
}
if (!$EditorSource) { $EditorSource = Join-Path $src 'EditorViewModel.kt' }
$editor = [IO.File]::ReadAllText($EditorSource)
$patterns = @(
    '(?ms)^    private fun loadInspection\(.*?(?=^    private fun invalidateDetail\()',
    '(?ms)^    fun previewOverride\(.*?(?=^    private fun applyWanted\()',
    '(?ms)^    private fun applyWanted\(.*?(?=^    // True while)',
    '(?ms)^    private suspend fun repackLoop\(.*?(?=^    private suspend fun renderLoop\()',
    '(?ms)^    private fun renderPooled\(.*?(?=^    private fun retire\()',
    '(?ms)^    private fun retire\(.*?(?=^    private fun clearPool\()',
    '(?ms)^    private suspend fun renderOnce\(.*?(?=^    // --- export)'
)
$methods = ($patterns | ForEach-Object {
    $match = [regex]::Match($editor, $_)
    if (!$match.Success) { throw "Production declaration boundary changed: $_" }
    $match.Value
}) -join "`n"
$harness = Join-Path $build 'ProductionPreviewHarness.kt'
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'preview/PreviewHarness.kt.in'))
[IO.File]::WriteAllText($harness, $template.Replace('/* PRODUCTION_METHODS */', $methods), [Text.UTF8Encoding]::new($false))
$sources = @($harness, (Join-Path $src 'PreviewPublication.kt'),
    (Join-Path $repo 'app/src/test/java/com/dearded/spektralab/PreviewPublicationTest.kt')) +
    (Get-ChildItem (Join-Path $PSScriptRoot 'preview') -Filter '*.kt' | ForEach-Object FullName)
& $java.FullName '-Xmx512m' -cp $classpath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $classes @sources
if ($LASTEXITCODE) { throw 'Preview publication Kotlin compilation failed' }
$result = & $java.FullName '-Xmx256m' -cp "$classes;$classpath" org.junit.runner.JUnitCore com.dearded.spektralab.PreviewPublicationTest com.dearded.spektralab.PreviewEditorContract 2>&1
$result | Tee-Object (Join-Path $build 'junit-results.txt')
Get-FileHash -Algorithm SHA256 @($EditorSource, (Join-Path $src 'PreviewPublication.kt'), $harness) |
    Select-Object Path, Hash | ConvertTo-Json | Set-Content (Join-Path $build 'source-sha256.json')
if ($LASTEXITCODE) { throw 'Preview publication contracts failed' }
