param([string]$RepoRoot = ".")
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path $RepoRoot).Path
$nativeSpec = Get-Content -Raw -LiteralPath (Join-Path $repo "fixtures\broker-clients\native-renderer.client.json") | ConvertFrom-Json
$spatialSpec = Get-Content -Raw -LiteralPath (Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.client.json") | ConvertFrom-Json
$nativeManifest = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\native-renderer-android\AndroidManifest.xml")
$spatialManifest = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml")
$sharedClient = Get-Content -Raw -LiteralPath (Join-Path $repo "crates\rusty-quest-broker-client\android\io\github\mesmerprism\rustyquest\broker_client\BrokerClientProbeActivity.java")
$brokerBuild = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Build-ManifoldBrokerAndroid.ps1")
$nativeBuild = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Build-NativeRendererAndroid.ps1")
$spatialBuild = Get-Content -Raw -LiteralPath (Join-Path $repo "apps\spatial-camera-panel-android\app\build.gradle.kts")
$deviceSuite = Get-Content -Raw -LiteralPath (Join-Path $repo "tools\Invoke-MultiAppBrokerClientTwoQuest.ps1")

foreach ($spec in @($nativeSpec, $spatialSpec)) {
    if ($spec.schema -ne "rusty.quest.broker_client_spec.v1") { throw "Broker client spec schema drifted." }
    if (@($spec.contract_families).Count -ne 2 -or
        @($spec.contract_families) -notcontains "rusty.manifold.peer.session_descriptor.v1" -or
        @($spec.contract_families) -notcontains "rusty.manifold.media.session_descriptor.v1") {
        throw "Broker client shared contract family closure drifted."
    }
    if (@($spec.runtime_properties).Count -ne 0 -or @($spec.application_defaults).Count -ne 0) {
        throw "Broker client spec contains property/default bleed."
    }
}
if ($nativeSpec.client_id -eq $spatialSpec.client_id -or
    $nativeSpec.package_name -eq $spatialSpec.package_name -or
    $nativeSpec.feature_lock_id -eq $spatialSpec.feature_lock_id -or
    $nativeSpec.marker_namespace -eq $spatialSpec.marker_namespace) {
    throw "Native and Spatial broker client identity/lock/marker must be distinct."
}
foreach ($token in @($nativeSpec.client_id,$nativeSpec.feature_lock_id,$nativeSpec.marker_namespace,"BrokerClientProbeActivity","BROKER_ADMISSION")) {
    if ($nativeManifest -notmatch [regex]::Escape([string]$token)) { throw "Native broker client manifest missing $token" }
}
foreach ($token in @($spatialSpec.client_id,$spatialSpec.feature_lock_id,$spatialSpec.marker_namespace,"BrokerClientProbeActivity","BROKER_ADMISSION")) {
    if ($spatialManifest -notmatch [regex]::Escape([string]$token)) { throw "Spatial broker client manifest missing $token" }
}
if ($nativeManifest -match [regex]::Escape([string]$spatialSpec.marker_namespace) -or
    $spatialManifest -match [regex]::Escape([string]$nativeSpec.marker_namespace)) {
    throw "Broker client marker namespace crossed app manifests."
}
foreach ($token in @("expected_authority_revision","capability.media.session.observe","replayed_request","token_revoked","localPolicy=false","cleanupRequested=true")) {
    if ($sharedClient -notmatch [regex]::Escape($token)) { throw "Shared broker client adapter missing $token" }
}
foreach ($token in @($nativeSpec.package_name,$spatialSpec.package_name,"capability.sink.native-openxr","capability.sink.spatial-sdk")) {
    if ($brokerBuild -notmatch [regex]::Escape([string]$token)) { throw "Broker product grant generation missing $token" }
}
if ($nativeBuild -notmatch "rusty-quest-broker-client" -or $spatialBuild -notmatch "rusty-quest-broker-client") {
    throw "Both app builds must compile the shared broker client adapter."
}
foreach ($token in @("expected_authority_revision","client_uid_not_distinct","marker_bleed","qcl100_generic_fold","cleanup_complete","system_fatal_count")) {
    if ($deviceSuite -notmatch [regex]::Escape($token)) { throw "Multi-app device suite missing $token" }
}
& cargo test -p rusty-quest-broker-client
if ($LASTEXITCODE -ne 0) { throw "Broker client Rust contract tests failed." }
Write-Host "Rusty Quest multi-app broker client static gate passed"
