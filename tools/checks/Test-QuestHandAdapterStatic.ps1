param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path

function Get-Sha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $cmd = Get-Command Get-FileHash -ErrorAction SilentlyContinue
    if ($cmd) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash }
    $stream = [IO.File]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace("-", "") }
    finally { $sha.Dispose(); $stream.Dispose() }
}

function Read-Json([string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing hand adapter artifact: $RelativePath" }
    Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

$fixture = Read-Json "fixtures\hand-adapter\two-consumer-conformance.json"
$damage = Read-Json "fixtures\damaged\hand-adapter-provider-substitution.json"
$appBuild = Read-Json "fixtures\native-app-builds\native-openxr-hand-lab.app.json"
$spatialProfile = Read-Json "fixtures\runtime-profiles\quest-spatial-camera-panel-hand-adapter-conformance.profile.json"
$nativeLockIndex = Read-Json "apps\native-renderer-android\morphospace\conformance-locks\index.json"
$spatialLockIndex = Read-Json "apps\spatial-camera-panel-android\morphospace\conformance-locks\index.json"
$workspace = Get-Content -Raw -LiteralPath (Join-Path $root "Cargo.toml")
$agents = Get-Content -Raw -LiteralPath (Join-Path $root "AGENTS.md")
$readme = Get-Content -Raw -LiteralPath (Join-Path $root "README.md")
$nativeCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\Cargo.toml")
$spatialCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\Cargo.toml")
$handAdapterSource = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-hand-adapter\src\lock_bound_activation.rs")
$handAdapterCargo = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-hand-adapter\Cargo.toml")
$featureActivationSource = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-feature-activation\src\lib.rs")
$nativeConsumerSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\src\hand_adapter_consumer.rs")
$spatialBridgeSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\src\live_hand_joint_bridge.rs")
$spatialAuthorityRustSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\src\adapter_lock_authority.rs")
$spatialAuthorityKotlinSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterNativeAuthority.kt")
$spatialLockBindingSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterLockBinding.kt")
$spatialKotlinBridgeSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialLiveHandJointBridge.kt")
$nativeRuntimeSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\src\lib.rs")
$spatialAlignmentSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialOpenXrHandAlignmentFeature.kt")
$spatialFlockSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialHandBillboardFlockFeature.kt")
$spatialCaptureSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialHandCaptureRecorderFeature.kt")

function Get-ProfileProperty([object]$RuntimeProfile, [string]$Name) {
    $matches = @($RuntimeProfile.set_properties | Where-Object name -eq $Name)
    if ($matches.Count -ne 1) { throw "Runtime profile '$($RuntimeProfile.profile_id)' must own exactly one '$Name' value." }
    return [string]$matches[0].value
}

if ($fixture.schema -ne "rusty.quest.hand_adapter.two_consumer_conformance.v1") { throw "Unexpected hand adapter conformance schema." }
$expectedSources = @("rusty.lattice.hand_joint_frame.v1", "rusty.matter.hand.substrate.v1", "rusty.optics.hand.visual_profile.v1") | Sort-Object
if ((@($fixture.source_contracts | Sort-Object) -join "|") -ne ($expectedSources -join "|")) { throw "Hand adapter source contracts drifted." }
if ((@($fixture.consumers.consumer_id | Sort-Object) -join "|") -ne "native-openxr-hand-lab|spatial-camera-panel") { throw "Hand adapter must retain exactly two named consumers." }
if (@($fixture.consumers | Where-Object { $_.default_enabled -ne $false }).Count -ne 0) { throw "Hand consumers must remain default-disabled." }
if ((@($fixture.hands | Sort-Object) -join "|") -ne "left|right" -or $fixture.coordinate_basis -ne "right_handed_y_up_negative_z_forward") { throw "Both-hand or coordinate-basis conformance drifted." }
if ($fixture.high_rate_json -ne $false -or $fixture.backend_payload_in_contract -ne $false) { throw "Hand adapter contract leaked payload policy." }
if ($damage.expected_provider_id -eq $damage.substitute_provider_id -or $damage.expected_result -ne "identity-mismatch-provider") { throw "Provider substitution damage fixture is not fail-closed." }
if ($workspace -notmatch 'rusty-quest-hand-adapter' -or $workspace -notmatch 'rusty-quest-feature-activation' -or $nativeCargo -notmatch 'rusty-quest-hand-adapter' -or $spatialCargo -notmatch 'rusty-quest-hand-adapter') { throw "Hand adapter crate is not wired into both consumers." }
if ($handAdapterCargo -notmatch 'rusty-quest-feature-activation' -or
    $handAdapterSource -notmatch 'pub struct HandAdapterLockActivationDecision' -or
    $handAdapterSource -notmatch 'inner: LockBoundActivationDecision' -or
    $handAdapterSource -cmatch 'LockBoundActivationDecision as HandAdapterLockActivationDecision' -or
    $handAdapterSource -cmatch 'serde::Deserialize|Sha256|struct WorkflowFeatureLock' -or
    (Get-Content -LiteralPath (Join-Path $root "crates\rusty-quest-hand-adapter\src\lock_bound_activation.rs")).Count -gt 280) {
    throw "Hand activation must remain a thin nominal facade over the generic closed engine."
}
if (-not $agents.Contains("crates/rusty-quest-feature-activation") -or
    -not $agents.Contains("docs/FEATURE_ACTIVATION.md") -or
    -not $readme.Contains("crates/rusty-quest-feature-activation") -or
    -not $readme.Contains("docs/FEATURE_ACTIVATION.md")) {
    throw "Quest first-hop instructions do not route the generic feature-activation owner."
}
foreach ($forbidden in @("native-renderer", "spatial-camera-panel", "debug.rustyquest", "io.github.mesmerprism")) {
    if ($featureActivationSource.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "Generic feature activation contains application-owned identity '$forbidden'."
    }
}
$genericDecision = [regex]::Match($featureActivationSource, '(?s)pub struct LockBoundActivationDecision\s*\{(?<body>.*?)\n\}')
if (-not $genericDecision.Success -or $genericDecision.Groups['body'].Value -cmatch '\bpub\s+') {
    throw "Generic activation decision fields are publicly forgeable."
}
if ($appBuild.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.enabled' -ne "true") { throw "Native hand app build does not explicitly enable the shared adapter." }
$nativeBinding = @($nativeLockIndex.locks | Where-Object feature_id -eq "hand-adapter-consumer")
$spatialBinding = @($spatialLockIndex.locks | Where-Object feature_id -eq "tracked-hand-surface")
if ($nativeBinding.Count -ne 1 -or $spatialBinding.Count -ne 1) { throw "Hand lock indexes must expose one app-specific binding each." }
if (@($fixture.consumers | Where-Object { $_.PSObject.Properties.Name -contains "lock_binding" }).Count -ne 0) {
    throw "Historical hand two-consumer fixture was retrofitted with MOD-006 lock authority."
}
if ($appBuild.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.project_id' -ne "native-renderer" -or
    $appBuild.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.feature_id' -ne "hand-adapter-consumer" -or
    $appBuild.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.lock_revision' -ne ([string]$nativeBinding[0].revision) -or
    $appBuild.runtime_profile.set.'debug.rustyquest.native_renderer.hand_adapter.lock_sha256' -ne ([string]$nativeBinding[0].sha256)) {
    throw "Native hand app build does not bind the indexed conformance lock."
}
if ((Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.hand_adapter.project_id") -ne "spatial-camera-panel" -or
    (Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.hand_adapter.feature_id") -ne "tracked-hand-surface" -or
    (Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_revision") -ne ([string]$spatialBinding[0].revision) -or
    (Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_sha256") -ne ([string]$spatialBinding[0].sha256)) {
    throw "Spatial hand runtime profile does not bind the indexed conformance lock."
}
foreach ($sourceCheck in @(
    [pscustomobject]@{label="shared hand adapter";text=$handAdapterSource;tokens=@("resolve_hand_adapter_activation","RuntimeDigestMismatch","FeatureNotSelected")},
    [pscustomobject]@{label="native hand consumer";text=$nativeConsumerSource;tokens=@("include_str!","ACCEPTED_LOCK_SHA256","resolve_activation(input)","handAdapterEnabled=false")},
    [pscustomobject]@{label="Spatial hand bridge";text=$spatialBridgeSource;tokens=@("activation_decision.is_applied()","return 0","runtime_lock_sha256")},
    [pscustomobject]@{label="Native hand effect-closure gate";text=$nativeRuntimeSource;tokens=@("hand_adapter_effect_request","effects_authorized","hand-effects-require-applied-lock","permissionsRequested=false","sceneStarted=false","inputStarted=false","mediaStarted=false")},
    [pscustomobject]@{label="Spatial Rust lock authority";text=$spatialAuthorityRustSource;tokens=@("hand_authority_receipt","resolve_activation(input)","nativeResolveHandAdapterActivation","spatial_adapter_lock_authority_receipt.v1")},
    [pscustomobject]@{label="Spatial Kotlin JNI authority";text=$spatialAuthorityKotlinSource;tokens=@("nativeResolveHandAdapterActivation","SpatialAdapterLockBinding.parseAuthorityReceipt","native-authority-library-unavailable")},
    [pscustomobject]@{label="Spatial Kotlin authority parser";text=$spatialLockBindingSource;tokens=@("AUTHORITY_RECEIPT_SCHEMA","native-authority-receipt-invalid","native-authority-receipt-input-mismatch","fields.size != 8")},
    [pscustomobject]@{label="Spatial stale-decision revocation";text=$spatialKotlinBridgeSource;tokens=@("lastActivationApplied && !decision.applied","nativeStopLiveHandJoints()","lastStartMask = 0L")},
    [pscustomobject]@{label="Spatial alignment scene gate";text=$spatialAlignmentSource;tokens=@("currentHandAdapterActivationDecision()","if (!adapterDecision.applied)","return")},
    [pscustomobject]@{label="Spatial hand flock scene gate";text=$spatialFlockSource;tokens=@("currentHandAdapterActivationDecision()","adapter-lock-rejected","destroyPool(disabledReason)")},
    [pscustomobject]@{label="Spatial capture effect gate";text=$spatialCaptureSource;tokens=@("currentHandAdapterActivationDecision()","adapter-lock-rejected","return")}
)) {
    foreach ($token in $sourceCheck.tokens) { if (-not $sourceCheck.text.Contains($token)) { throw "$($sourceCheck.label) is missing '$token'." } }
}
if ($spatialKotlinBridgeSource -match 'const\s+val\s+HAND_ADAPTER_LOCK_(REVISION|SHA256)\s*=') {
    throw "Spatial Kotlin still owns a hard-coded hand lock revision or digest."
}
$historicalHandFixtures = @(
    [pscustomobject]@{path="fixtures\hand-adapter\native-hand-accepted.txt";sha256="31150726693DF65FC5655564A33E44604A31209A119B50183A36BA6B6A0FC2F7"},
    [pscustomobject]@{path="fixtures\hand-adapter\spatial-hand-accepted.txt";sha256="80BC676E066897527ED4B90542FDB2F12B21AC5737CAF5B0F3A36F23163088B0"}
)
foreach ($entry in $historicalHandFixtures) {
    $path = Join-Path $root $entry.path
    if ((Get-Sha256 -Path $path) -ne $entry.sha256) { throw "Historical hand fixture '$($entry.path)' was rewritten." }
    if ((Get-Content -Raw -LiteralPath $path).Contains("lockBindingSchema=")) { throw "Historical hand fixture '$($entry.path)' was retrofitted with MOD-006 evidence." }
}
foreach ($token in @("handAdapterConsumer=native-openxr-hand-lab", "handAdapterBothHands=true", "handAdapterCpuPreparedParity=true", "activationState=applied", "projectId=native-renderer", "featureId=hand-adapter-consumer", "conformanceLockSha256=$($nativeBinding[0].sha256)")) {
    if (@($appBuild.expected_markers.required) -notcontains $token) { throw "Native hand app build is missing '$token'." }
}

Write-Host "Quest hand adapter static gate passed"
