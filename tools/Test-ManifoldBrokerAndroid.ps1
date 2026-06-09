param(
    [switch]$Build,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Join-Path $repoRoot "apps\manifold-broker-android"
$manifestPath = Join-Path $appRoot "AndroidManifest.xml"
$activityPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\BrokerStartActivity.java"
$serverPath = Join-Path $appRoot "src\main\java\io\github\mesmerprism\rustymanifold\broker\LocalManifoldBrokerServer.java"

foreach ($path in @($manifestPath, $activityPath, $serverPath)) {
    if (-not (Test-Path $path)) {
        throw "Missing Manifold broker Android file: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$activity = Get-Content -Raw -Path $activityPath
$server = Get-Content -Raw -Path $serverPath

if ($manifest -notmatch 'package="io\.github\.mesmerprism\.rustymanifold\.broker"') {
    throw "Manifold broker Android manifest has the wrong package."
}
if ($activity -notmatch 'rusty\.quest\.manifold_broker_android\.launch_evidence\.v1') {
    throw "BrokerStartActivity does not emit launch evidence schema."
}
if ($server -notmatch '/manifold/v1/events') {
    throw "Local broker server does not expose /manifold/v1/events."
}
if ($server -notmatch 'rusty\.manifold\.command\.envelope\.v1') {
    throw "Local broker server does not recognize Manifold command envelopes."
}
if ($server -notmatch 'live_stream_events_synthesized') {
    throw "Local broker server must explicitly report that it does not synthesize live stream events."
}

$combined = "$manifest`n$activity`n$server"
$legacyTokens = @(
    ("RUSTY" + "_XR_"),
    ("rusty" + ".xr."),
    ("/rusty" + "xr/v1"),
    ("com.example." + "rustyxr.broker"),
    ("Rusty" + "XR")
)
foreach ($token in $legacyTokens) {
    if ($combined.Contains($token)) {
        throw "Manifold broker Android scaffold contains legacy token: $token"
    }
}

if ($Build) {
    & (Join-Path $PSScriptRoot "Build-ManifoldBrokerAndroid.ps1") -AndroidHome $AndroidHome -JavaHome $JavaHome | Out-Host
}

Write-Output "Rusty Quest Manifold broker Android validation passed"
