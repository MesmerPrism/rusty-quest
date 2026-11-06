[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$AndroidJar,
    [string]$D8Jar = '',
    [string]$JdkBin = '',
    [string]$OutputRoot = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$moduleRoot = Join-Path $repoRoot 'apps/shell-recenter-android'
if (-not (Test-Path -LiteralPath $AndroidJar -PathType Leaf)) { throw 'AndroidJar must name an installed SDK android.jar.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $repoRoot ('local-artifacts/shell-recenter/' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputRoot) { throw 'OutputRoot must be a fresh task-owned directory.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
function Get-JavaTool([string]$Name) {
    if ($JdkBin) { return Join-Path $JdkBin ($Name + $(if ($IsWindows) { '.exe' } else { '' })) }
    return (Get-Command $Name -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
}
function Invoke-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Tool failed ($LASTEXITCODE): $File" }
}
$javac = Get-JavaTool 'javac'; $java = Get-JavaTool 'java'; $jar = Get-JavaTool 'jar'
$inputPaths = @($PSCommandPath, $AndroidJar, $javac, $java, $jar)
if ($D8Jar) { $inputPaths += $D8Jar }
$jdkRoot = Split-Path (Split-Path $java -Parent) -Parent
foreach ($relative in @('release', 'lib/modules')) { $candidate = Join-Path $jdkRoot $relative; if (Test-Path -LiteralPath $candidate -PathType Leaf) { $inputPaths += $candidate } }
$toolInputs = @($inputPaths | ForEach-Object { [ordered]@{path=(Resolve-Path -LiteralPath $_).Path; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()} })
$sources = @(Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'src/main/java') -Recurse -File -Filter '*.java' | Sort-Object FullName)
$inventory = @($sources | ForEach-Object { [ordered]@{path=[IO.Path]::GetRelativePath($repoRoot, $_.FullName).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
$sourceText = (($inventory | ForEach-Object { $_.path + ' ' + $_.sha256 }) -join "`n") + "`n"
$sourceHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($sourceText))).ToLowerInvariant()
$generated = Join-Path $OutputRoot 'generated'; $production = Join-Path $OutputRoot 'production'
New-Item -ItemType Directory -Path $generated, $production | Out-Null
$identity = Join-Path $generated 'SourceIdentity.java'
[IO.File]::WriteAllText($identity, ('package io.github.mesmerprism.rustyquest.shellrecenter; final class SourceIdentity { static final String SHA256 = "' + $sourceHash + '"; }'))
$arguments = @('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-classpath',$AndroidJar,'-d',$production) + @($sources.FullName) + @($identity)
Invoke-Checked $javac $arguments
$classesJar = Join-Path $OutputRoot 'classes.jar'
Invoke-Checked $jar @('cf',$classesJar,'-C',$production,'.')
$staging = Join-Path $OutputRoot 'aar'
New-Item -ItemType Directory -Path $staging | Out-Null
Copy-Item -LiteralPath $classesJar -Destination $staging
Copy-Item -LiteralPath (Join-Path $moduleRoot 'src/main/AndroidManifest.xml') -Destination $staging
Copy-Item -LiteralPath (Join-Path $moduleRoot 'consumer-rules.pro') -Destination (Join-Path $staging 'proguard.txt')
[IO.File]::WriteAllText((Join-Path $staging 'R.txt'), '')
$aar = Join-Path $OutputRoot 'rusty-quest-shell-recenter.aar'
Invoke-Checked $jar @('cf',$aar,'-C',$staging,'.')
$dexJar = $null
if ($D8Jar) {
    if (-not (Test-Path -LiteralPath $D8Jar -PathType Leaf)) { throw 'D8Jar must name an installed d8.jar.' }
    $dexJar = Join-Path $OutputRoot 'rusty-quest-shell-recenter.dex.jar'
    Invoke-Checked $java @('-cp',$D8Jar,'com.android.tools.r8.D8','--min-api','34','--lib',$AndroidJar,'--output',$dexJar,$classesJar)
}
foreach ($entry in $inventory) {
    if ((Get-FileHash -LiteralPath (Join-Path $repoRoot $entry.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Source changed during build.' }
}
foreach ($entry in $toolInputs) {
    if ((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Build tool/input changed during build.' }
}
$sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve source revision.' }
$sourceTree = (& git -C $repoRoot rev-parse 'HEAD^{tree}').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve source tree.' }
$sourceDirty = -not [string]::IsNullOrWhiteSpace((& git -C $repoRoot status --porcelain | Out-String))
$result = [ordered]@{
    schema='rusty.quest.shell_recenter.build.v1'; source_sha256=$sourceHash; source_id=$sourceHash; sources=$inventory
    observed_git_revision=$sourceRevision; observed_git_tree=$sourceTree; source_worktree_dirty=$sourceDirty; tool_inputs=$toolInputs
    classes_jar=$classesJar; classes_jar_sha256=(Get-FileHash -LiteralPath $classesJar -Algorithm SHA256).Hash.ToLowerInvariant()
    aar=$aar; aar_sha256=(Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash.ToLowerInvariant()
    dex_jar=$dexJar; dex_jar_sha256=$(if ($dexJar) { (Get-FileHash -LiteralPath $dexJar -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null })
    android_jar_sha256=(Get-FileHash -LiteralPath $AndroidJar -Algorithm SHA256).Hash.ToLowerInvariant()
    main_class='io.github.mesmerprism.rustyquest.shellrecenter.ShellRecenterMain'
    device_tested=$false; apk_built=$false
}
$result | ConvertTo-Json -Depth 6 | Tee-Object -FilePath (Join-Path $OutputRoot 'source-manifest.json')
