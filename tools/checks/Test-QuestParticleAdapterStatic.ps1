param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$root = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-Json([string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing particle adapter artifact: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

$fixture = Read-Json "fixtures\particle-adapter\two-consumer-conformance.json"
$profile = Read-Json "fixtures\runtime-profiles\quest-native-renderer-particle-adapter-conformance.profile.json"
$workspace = Get-Content -Raw -LiteralPath (Join-Path $root "Cargo.toml")
$nativeCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\Cargo.toml")
$spatialCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\Cargo.toml")

if ($fixture.schema -ne "rusty.quest.particle_adapter.two_consumer_conformance.v1") {
    throw "Unexpected particle adapter conformance schema."
}
$expectedSources = @(
    "rusty.matter.particle.render_payload.v1",
    "rusty.lattice.situated_anchor.v1",
    "rusty.optics.particles.visual.frame.v1"
)
$actualSources = @($fixture.source_contracts | Sort-Object)
if (($actualSources -join "|") -ne (($expectedSources | Sort-Object) -join "|")) {
    throw "Particle adapter source contract set drifted."
}
$consumers = @($fixture.consumers)
if ($consumers.Count -ne 2 -or
    @($consumers.consumer_id | Sort-Object) -join "|" -ne "native-renderer-android|spatial-camera-panel") {
    throw "Particle adapter must keep exactly the Spatial and native-renderer consumers."
}
if (@($consumers | Where-Object { $_.default_enabled -ne $false }).Count -ne 0) {
    throw "Particle adapter consumers must remain disabled by default."
}
if ($fixture.high_rate_json -ne $false -or $fixture.backend_payload_in_contract -ne $false) {
    throw "Particle adapter fixture must exclude high-rate JSON and backend payloads."
}
if ($workspace -notmatch '"crates/rusty-quest-particle-adapter"' -or
    $nativeCargo -notmatch 'rusty-quest-particle-adapter' -or
    $spatialCargo -notmatch 'rusty-quest-particle-adapter') {
    throw "Particle adapter crate is not wired into both app consumers."
}
if ($profile.profile_id -ne "profile.quest.native_renderer.particle_adapter_conformance" -or
    @($profile.set_properties | Where-Object {
        $_.name -eq "debug.rustyquest.native_renderer.particle_adapter.enabled" -and $_.value -eq "true"
    }).Count -ne 1) {
    throw "Native renderer particle adapter profile is not explicit."
}
$expectedMarkers = @($profile.expected_markers)
foreach ($token in @(
    "particleAdapterConsumer=native-renderer-android",
    "particleAdapterEnabled=true",
    "particleAdapterHighRateJson=false",
    "particleAdapterBackendPayloadAbsent=true"
)) {
    if ($expectedMarkers -notcontains $token) {
        throw "Native renderer profile is missing expected marker '$token'."
    }
}

$evidenceArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $root "tools\Test-QuestParticleAdapterEvidence.ps1"),
    "-NativeRendererLogcatPath", (Join-Path $root "fixtures\particle-adapter\native-renderer-accepted.txt"),
    "-SpatialPanelLogcatPath", (Join-Path $root "fixtures\particle-adapter\spatial-panel-accepted.txt"),
    "-Out", (Join-Path $root "local-artifacts\particle-adapter-fixture-scorecard.json")
)
& powershell @evidenceArgs | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Particle adapter evidence fixture validation failed."
}

Write-Host "Quest particle adapter static gate passed"
