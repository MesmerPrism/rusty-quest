param(
    [Parameter(Mandatory=$true)]
    [string]$NativeRendererLogcatPath,
    [Parameter(Mandatory=$true)]
    [string]$SpatialPanelLogcatPath,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Assert-MarkerSet {
    param(
        [string]$Label,
        [string]$Text,
        [string[]]$Tokens
    )
    foreach ($token in $Tokens) {
        if (-not $Text.Contains($token)) {
            throw "$Label evidence is missing '$token'."
        }
    }
}

$nativeText = Get-Content -Raw -LiteralPath $NativeRendererLogcatPath
$spatialText = Get-Content -Raw -LiteralPath $SpatialPanelLogcatPath
Assert-MarkerSet -Label "Native renderer" -Text $nativeText -Tokens @(
    "RUSTY_QUEST_NATIVE_RENDERER channel=particle-adapter",
    "status=accepted",
    "particleAdapterConsumer=native-renderer-android",
    "particleAdapterEnabled=true",
    "particleAdapterHighRateJson=false",
    "particleAdapterBackendPayloadAbsent=true",
    "lockBindingSchema=rusty.quest.lock_bound_activation.v1",
    "activationState=applied",
    "projectId=native-renderer",
    "featureId=particle-adapter-consumer",
    "conformanceLockRevision=1",
    "conformanceLockSha256=D51D97F6B01663F360E867EB01C3F27A3DB7C3204210F1C1D7634CA52DD276BC",
    "runtimeProfileId=profile.quest.native_renderer.particle_adapter_conformance",
    "activationRejectReason=none"
)
Assert-MarkerSet -Label "Spatial Camera Panel" -Text $spatialText -Tokens @(
    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=particle-adapter",
    "status=accepted",
    "particleAdapterConsumer=spatial-camera-panel",
    "particleAdapterEnabled=true",
    "particleAdapterHighRateJson=false",
    "particleAdapterBackendPayloadAbsent=true",
    "lockBindingSchema=rusty.quest.lock_bound_activation.v1",
    "activationState=applied",
    "projectId=spatial-camera-panel",
    "featureId=surface-particle-runtime",
    "conformanceLockRevision=1",
    "conformanceLockSha256=780814BE82C12A54036DE0259C6188E2D41813858C30E6B6C725EB8422F7301B",
    "runtimeProfileId=profile.quest.spatial_camera_panel.particle_adapter_conformance",
    "activationRejectReason=none"
)

$result = [ordered]@{
    schema = "rusty.quest.particle_adapter.device_scorecard.v1"
    status = "accepted"
    consumers = @(
        [ordered]@{ consumer_id = "native-renderer-android"; accepted = $true },
        [ordered]@{ consumer_id = "spatial-camera-panel"; accepted = $true }
    )
    source_contracts = @(
        "rusty.matter.particle.render_payload.v1",
        "rusty.lattice.situated_anchor.v1",
        "rusty.optics.particles.visual.frame.v1"
    )
    high_rate_json = $false
    backend_payload_absent = $true
    lock_bound_activation = $true
    lock_bindings = @(
        [ordered]@{ project_id = "native-renderer"; feature_id = "particle-adapter-consumer"; revision = 1; sha256 = "D51D97F6B01663F360E867EB01C3F27A3DB7C3204210F1C1D7634CA52DD276BC" },
        [ordered]@{ project_id = "spatial-camera-panel"; feature_id = "surface-particle-runtime"; revision = 1; sha256 = "780814BE82C12A54036DE0259C6188E2D41813858C30E6B6C725EB8422F7301B" }
    )
}
$json = $result | ConvertTo-Json -Depth 8
if (-not [string]::IsNullOrWhiteSpace($Out)) {
    $parent = Split-Path -Parent $Out
    if ($parent) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Set-Content -LiteralPath $Out -Value $json -Encoding utf8
}
$json
