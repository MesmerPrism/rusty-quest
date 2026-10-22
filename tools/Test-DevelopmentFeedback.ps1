[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = '',
    [string]$D8 = '',
    [string]$OutputRoot = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$whole = [Diagnostics.Stopwatch]::StartNew()
$root = Split-Path $PSScriptRoot -Parent
$module = Join-Path $root 'android/development-feedback'
if (-not $JavaHome) {
    $JavaHome = Split-Path (Split-Path (Get-Command javac -CommandType Application).Source -Parent) -Parent
}
$suffix = if ($IsWindows) { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (-not (Test-Path -LiteralPath $javac)) { throw 'JavaHome must contain a JDK with javac.' }
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17\.') { throw "This conformance runner requires JDK 17; found $version" }
if (-not $OutputRoot) { $OutputRoot = Join-Path $module ('.local/' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputRoot) { throw 'OutputRoot must be a new task-owned directory.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$steps = [Collections.Generic.List[object]]::new()
function Invoke-Step([string]$Name, [string]$File, [string[]]$Arguments) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $lines = @(& $File @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    $watch.Stop()
    $lines | Set-Content -LiteralPath (Join-Path $OutputRoot ($Name + '.txt')) -Encoding utf8
    $steps.Add([ordered]@{ name=$Name; executable=$File; arguments=$Arguments; elapsed_ms=$watch.Elapsed.TotalMilliseconds; exit_code=$exitCode })
    $lines | Write-Output
    if ($exitCode -ne 0) { throw "$Name failed ($exitCode). Evidence: $OutputRoot" }
}
try {
    $classes = Join-Path $OutputRoot 'host-classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $sources = @()
    foreach ($relative in @('src/main/java','src/host/java','host-tests')) {
        $sourceRoot = Join-Path $module $relative
        if (Test-Path -LiteralPath $sourceRoot) {
            $sources += @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object FullName)
        }
    }
    Invoke-Step 'host-compile' $javac (@('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-d',$classes) + $sources)
    Invoke-Step 'host-conformance' $java @('-cp',$classes,'FeedbackConformance',$OutputRoot,$javac)
    if ($AndroidJar) {
        $AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
        $androidClasses = Join-Path $OutputRoot 'android-classes'
        New-Item -ItemType Directory -Path $androidClasses | Out-Null
        $androidSources = @('src/main/java','src/android/java') | ForEach-Object {
            Get-ChildItem -LiteralPath (Join-Path $module $_) -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object FullName
        }
        Invoke-Step 'android-sdk-compile' $javac (@('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-classpath',$AndroidJar,'-d',$androidClasses) + $androidSources)
    }
    if ($D8) {
        if (-not $AndroidJar) { throw 'D8 validation also requires an explicit AndroidJar.' }
        $D8 = (Resolve-Path -LiteralPath $D8).Path
        $d8Runtime = Join-Path (Split-Path $D8 -Parent) 'lib/d8.jar'
        if (-not (Test-Path -LiteralPath $d8Runtime -PathType Leaf)) { throw 'D8 must name an installed Build Tools launcher with adjacent lib/d8.jar.' }
        # Select the requested JDK explicitly instead of allowing a batch launcher to use ambient Java.
        $d8Prefix = @('-cp',$d8Runtime,'com.android.tools.r8.D8')
        Invoke-Step 'd8-version' $java ($d8Prefix + @('--version'))
        foreach ($generation in @('A1','B2','A3','duplicate')) {
            $dexOut = Join-Path $OutputRoot ('dex-' + $generation)
            New-Item -ItemType Directory -Path $dexOut | Out-Null
            Invoke-Step ('d8-' + $generation) $java ($d8Prefix + @('--lib',$AndroidJar,'--classpath',(Join-Path $OutputRoot 'contract.jar'),'--min-api','26','--output',$dexOut,(Join-Path $OutputRoot ($generation + '.jar'))))
        }
        Invoke-Step 'dex-definition-conformance' $java @('-cp',$classes,'FeedbackConformance','--dex',$OutputRoot)
    }
    $result = 'passed'
} catch {
    $result = 'failed'
    throw
} finally {
    $whole.Stop()
    $sourceRecords = @()
    foreach ($relative in @('src/main/java','src/host/java','src/android/java','host-tests')) {
        $sourceRoot = Join-Path $module $relative
        if (Test-Path -LiteralPath $sourceRoot) {
            $sourceRecords += @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object {
                [ordered]@{ path=[IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
            })
        }
    }
    $sourceRecords += [ordered]@{ path='tools/Test-DevelopmentFeedback.ps1'; sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    $toolRecords = @($javac,$java)
    if ($AndroidJar) { $toolRecords += $AndroidJar }
    if ($D8) {
        $toolRecords += $D8
        $d8Jar = Join-Path (Split-Path $D8 -Parent) 'lib/d8.jar'
        if (Test-Path -LiteralPath $d8Jar) { $toolRecords += $d8Jar }
    }
    $toolRecords = @($toolRecords | ForEach-Object { [ordered]@{path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()} })
    [ordered]@{
        schema='rusty.quest.development_feedback.host_conformance.v1'; result=$result
        javac=$version; java_home=$JavaHome; android_jar=$AndroidJar; d8=$D8
        android_sdk_compiled=[bool]($AndroidJar -and $result -eq 'passed')
        dex_packaged=[bool]($D8 -and $result -eq 'passed')
        apk_built=$false; device_tested=$false; quest_patch_proven=$false
        enclosing_elapsed_ms=$whole.Elapsed.TotalMilliseconds; steps=$steps
        sources=$sourceRecords; tools=$toolRecords
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputRoot 'result.json') -Encoding utf8
    Write-Output "Development feedback evidence: $OutputRoot"
}
