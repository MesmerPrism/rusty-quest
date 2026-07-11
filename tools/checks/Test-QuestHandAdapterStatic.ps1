param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-Json([string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing hand adapter artifact: $RelativePath" }
    Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

$fixture = Read-Json "fixtures\hand-adapter\two-consumer-conformance.json"
$damage = Read-Json "fixtures\damaged\hand-adapter-provider-substitution.json"
$appBuild = Read-Json "fixtures\native-app-builds\native-openxr-hand-lab.app.json"
$workspace = Get-Content -Raw -LiteralPath (Join-Path $root "Cargo.toml")
$nativeCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\Cargo.toml")
$spatialCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\Cargo.toml")

if ($fixture.schema -ne "rusty.quest.hand_adapter.two_consumer_conformance.v1") { throw "Unexpected hand adapter conformance schema." }
$expectedSources = @("rusty.lattice.hand_joint_frame.v1", "rusty.matter.hand.substrate.v1", "rusty.optics.hand.visual_profile.v1") | Sort-Object
if ((@($fixture.source_contracts | Sort-Object) -join "|") -ne ($expectedSources -join "|")) { throw "Hand adapter source contracts drifted." }
if ((@($fixture.consumers.consumer_id | Sort-Object) -join "|") -ne "native-openxr-hand-lab|spatial-camera-panel") { throw "Hand adapter must retain exactly two named consumers." }
if (@($fixture.consumers | Where-Object { $_.default_enabled -ne $false }).Count -ne 0) { throw "Hand consumers must remain default-disabled." }
if ((@($fixture.hands | Sort-Object) -join "|") -ne "left|right" -or $fixture.coordinate_basis -ne "right_handed_y_up_negative_z_forward") { throw "Both-hand or coordinate-basis conformance drifted." }
if ($fixture.high_rate_json -ne $false -or $fixture.backend_payload_in_contract -ne $false) { throw "Hand adapter contract leaked payload policy." }
if ($damage.expected_provider_id -eq $damage.substitute_provider_id -or $damage.expected_result -ne "identity-mismatch-provider") { throw "Provider substitution damage fixture is not fail-closed." }
if ($workspace -notmatch 'rusty-quest-hand-adapter' -or $nativeCargo -notmatch 'rusty-quest-hand-adapter' -or $spatialCargo -notmatch 'rusty-quest-hand-adapter') { throw "Hand adapter crate is not wired into both consumers." }
if ($appBuild.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.enabled' -ne "true") { throw "Native hand app build does not explicitly enable the shared adapter." }
foreach ($token in @("handAdapterConsumer=native-openxr-hand-lab", "handAdapterBothHands=true", "handAdapterCpuPreparedParity=true")) {
    if (@($appBuild.expected_markers.required) -notcontains $token) { throw "Native hand app build is missing '$token'." }
}

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "tools\Test-QuestHandAdapterEvidence.ps1") `
    -NativeHandLogcatPath (Join-Path $root "fixtures\hand-adapter\native-hand-accepted.txt") `
    -SpatialHandLogcatPath (Join-Path $root "fixtures\hand-adapter\spatial-hand-accepted.txt") `
    -Out (Join-Path $root "local-artifacts\hand-adapter-fixture-scorecard.json") | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Hand adapter evidence fixture validation failed." }

Write-Host "Quest hand adapter static gate passed"
