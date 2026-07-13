param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
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
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing particle adapter artifact: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

$fixture = Read-Json "fixtures\particle-adapter\two-consumer-conformance.json"
$profile = Read-Json "fixtures\runtime-profiles\quest-native-renderer-particle-adapter-conformance.profile.json"
$spatialProfile = Read-Json "fixtures\runtime-profiles\quest-spatial-camera-panel-particle-adapter-conformance.profile.json"
$nativeLockIndex = Read-Json "apps\native-renderer-android\morphospace\conformance-locks\index.json"
$spatialLockIndex = Read-Json "apps\spatial-camera-panel-android\morphospace\conformance-locks\index.json"
$workspace = Get-Content -Raw -LiteralPath (Join-Path $root "Cargo.toml")
$agents = Get-Content -Raw -LiteralPath (Join-Path $root "AGENTS.md")
$readme = Get-Content -Raw -LiteralPath (Join-Path $root "README.md")
$nativeCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\Cargo.toml")
$spatialCargo = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\Cargo.toml")
$particleAdapterSource = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-particle-adapter\src\lock_bound_activation.rs")
$particleAdapterCargo = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-particle-adapter\Cargo.toml")
$featureActivationSource = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-feature-activation\src\lib.rs")
$nativeConsumerSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\src\particle_adapter_consumer.rs")
$spatialConsumerSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\src\particle_adapter_consumer.rs")
$spatialRouteSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSurfaceParticleRouteModule.kt")
$spatialRuntimeSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSurfaceParticleRuntimeCoordinator.kt")
$spatialAuthorityRustSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\native-receipt\src\adapter_lock_authority.rs")
$spatialAuthorityKotlinSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterNativeAuthority.kt")
$spatialLockBindingSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterLockBinding.kt")
$nativeRuntimeSource = Get-Content -Raw -LiteralPath (Join-Path $root "apps\native-renderer-android\native\src\lib.rs")

function Get-ProfileProperty([object]$RuntimeProfile, [string]$Name) {
    $matches = @($RuntimeProfile.set_properties | Where-Object name -eq $Name)
    if ($matches.Count -ne 1) { throw "Runtime profile '$($RuntimeProfile.profile_id)' must own exactly one '$Name' value." }
    return [string]$matches[0].value
}

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
    $workspace -notmatch '"crates/rusty-quest-feature-activation"' -or
    $nativeCargo -notmatch 'rusty-quest-particle-adapter' -or
    $spatialCargo -notmatch 'rusty-quest-particle-adapter') {
    throw "Particle adapter crate is not wired into both app consumers."
}
if ($particleAdapterCargo -notmatch 'rusty-quest-feature-activation' -or
    $particleAdapterSource -notmatch 'pub struct ParticleAdapterLockActivationDecision' -or
    $particleAdapterSource -notmatch 'inner: LockBoundActivationDecision' -or
    $particleAdapterSource -cmatch 'LockBoundActivationDecision as ParticleAdapterLockActivationDecision' -or
    $particleAdapterSource -cmatch 'serde::Deserialize|Sha256|struct WorkflowFeatureLock' -or
    (Get-Content -LiteralPath (Join-Path $root "crates\rusty-quest-particle-adapter\src\lock_bound_activation.rs")).Count -gt 260) {
    throw "Particle activation must remain a thin nominal facade over the generic closed engine."
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
if ($profile.profile_id -ne "profile.quest.native_renderer.particle_adapter_conformance" -or
    @($profile.set_properties | Where-Object {
        $_.name -eq "debug.rustyquest.native_renderer.particle_adapter.enabled" -and $_.value -eq "true"
    }).Count -ne 1) {
    throw "Native renderer particle adapter profile is not explicit."
}
$nativeBinding = @($nativeLockIndex.locks | Where-Object feature_id -eq "particle-adapter-consumer")
$spatialBinding = @($spatialLockIndex.locks | Where-Object feature_id -eq "surface-particle-runtime")
if ($nativeBinding.Count -ne 1 -or $spatialBinding.Count -ne 1) { throw "Particle lock indexes must expose one app-specific binding each." }
if (@($consumers | Where-Object { $_.PSObject.Properties.Name -contains "lock_binding" }).Count -ne 0) {
    throw "Historical particle two-consumer fixture was retrofitted with MOD-006 lock authority."
}
if ((Get-ProfileProperty $profile "debug.rustyquest.native_renderer.particle_adapter.project_id") -ne "native-renderer" -or
    (Get-ProfileProperty $profile "debug.rustyquest.native_renderer.particle_adapter.feature_id") -ne "particle-adapter-consumer" -or
    (Get-ProfileProperty $profile "debug.rustyquest.native_renderer.particle_adapter.lock_revision") -ne ([string]$nativeBinding[0].revision) -or
    (Get-ProfileProperty $profile "debug.rustyquest.native_renderer.particle_adapter.lock_sha256") -ne ([string]$nativeBinding[0].sha256)) {
    throw "Native particle runtime profile does not bind the indexed conformance lock."
}
if ((Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.particle_adapter.project_id") -ne "spatial-camera-panel" -or
    (Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.particle_adapter.feature_id") -ne "surface-particle-runtime" -or
    (Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.particle_adapter.lock_revision") -ne ([string]$spatialBinding[0].revision) -or
    (Get-ProfileProperty $spatialProfile "debug.rustyquest.spatial_camera_panel.particle_adapter.lock_sha256") -ne ([string]$spatialBinding[0].sha256)) {
    throw "Spatial particle runtime profile does not bind the indexed conformance lock."
}
foreach ($sourceCheck in @(
    [pscustomobject]@{label="shared particle adapter";text=$particleAdapterSource;tokens=@("resolve_particle_adapter_activation","RuntimeDigestMismatch","FeatureNotSelected")},
    [pscustomobject]@{label="native particle consumer";text=$nativeConsumerSource;tokens=@("include_str!","ACCEPTED_LOCK_SHA256","resolve_activation(input)","ParticleAdapterEffectRequest","effects_authorized","disabling_guard_property_cannot_authorize_requested_particle_effects")},
    [pscustomobject]@{label="Spatial particle consumer";text=$spatialConsumerSource;tokens=@("include_str!","resolve_activation(input)","particleAdapterEnabled")},
    [pscustomobject]@{label="Spatial early scene gate";text=$spatialRouteSource;tokens=@("activationDecision.applied","SpatialAdapterNativeAuthority.resolveParticle","adapter-lock-rejected")},
    [pscustomobject]@{label="Spatial Rust lock authority";text=$spatialAuthorityRustSource;tokens=@("particle_authority_receipt","resolve_activation(input)","nativeResolveParticleAdapterActivation","spatial_adapter_lock_authority_receipt.v1")},
    [pscustomobject]@{label="Spatial Kotlin JNI authority";text=$spatialAuthorityKotlinSource;tokens=@("nativeResolveParticleAdapterActivation","SpatialAdapterLockBinding.parseAuthorityReceipt","native-authority-library-unavailable")},
    [pscustomobject]@{label="Spatial Kotlin authority parser";text=$spatialLockBindingSource;tokens=@("AUTHORITY_RECEIPT_SCHEMA","native-authority-receipt-invalid","native-authority-receipt-input-mismatch","fields.size != 8")},
    [pscustomobject]@{label="Spatial rejected-start and revocation closure";text=$spatialRuntimeSource;tokens=@("reconcileAdapterAdmission","nativeSurfaceParticleStartApplied","nativeSurfaceParticleStartRejectedMarker","particleLayerStarted = false","nativeSurfaceStartRequested = false")},
    [pscustomobject]@{label="Native particle effect-closure gate";text=$nativeRuntimeSource;tokens=@("particle_adapter_effect_request","environment_depth_settings","mode_draws_particles","hand_anchor_particle_settings.enabled","requests_private_particle_recenter_input","particle-effects-require-applied-lock","permissionsRequested=false","sceneStarted=false","inputStarted=false","mediaStarted=false")}
)) {
    foreach ($token in $sourceCheck.tokens) { if (-not $sourceCheck.text.Contains($token)) { throw "$($sourceCheck.label) is missing '$token'." } }
}
if ($spatialRouteSource -match 'const\s+val\s+PARTICLE_ADAPTER_LOCK_(REVISION|SHA256)\s*=') {
    throw "Spatial Kotlin still owns a hard-coded particle lock revision or digest."
}
if ($spatialRouteSource.Contains("rawValue ?: true")) { throw "Spatial particle scene route still defaults on without a selected lock." }
$historicalParticleFixtures = @(
    [pscustomobject]@{path="fixtures\particle-adapter\native-renderer-accepted.txt";sha256="1CD8C5E68BEB4B0391E81BA9216D8200CCE97D6A082A48E0E85BD62EB87B1492"},
    [pscustomobject]@{path="fixtures\particle-adapter\spatial-panel-accepted.txt";sha256="A10CC0561747F942A64C888AABA2E3BFEF9105E6AA0BD5D85C10A78E7FC1513C"}
)
foreach ($entry in $historicalParticleFixtures) {
    $path = Join-Path $root $entry.path
    if ((Get-Sha256 -Path $path) -ne $entry.sha256) { throw "Historical particle fixture '$($entry.path)' was rewritten." }
    if ((Get-Content -Raw -LiteralPath $path).Contains("lockBindingSchema=")) { throw "Historical particle fixture '$($entry.path)' was retrofitted with MOD-006 evidence." }
}
$expectedMarkers = @($profile.expected_markers)
foreach ($token in @(
    "particleAdapterConsumer=native-renderer-android",
    "particleAdapterEnabled=true",
    "particleAdapterHighRateJson=false",
    "particleAdapterBackendPayloadAbsent=true",
    "activationState=applied",
    "projectId=native-renderer",
    "featureId=particle-adapter-consumer",
    "conformanceLockSha256=$($nativeBinding[0].sha256)"
)) {
    if ($expectedMarkers -notcontains $token) {
        throw "Native renderer profile is missing expected marker '$token'."
    }
}

Write-Host "Quest particle adapter static gate passed"
