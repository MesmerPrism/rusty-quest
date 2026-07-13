param(
    [Parameter(Mandatory=$true)]
    [string]$NativeHandLogcatPath,
    [Parameter(Mandatory=$true)]
    [string]$SpatialHandLogcatPath,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Assert-Markers([string]$Label, [string]$Text, [string[]]$Tokens) {
    foreach ($token in $Tokens) {
        if (-not $Text.Contains($token)) {
            throw "$Label evidence is missing '$token'."
        }
    }
}

$shared = @(
    "status=accepted",
    "handAdapterEnabled=true",
    "handAdapterBothHands=true",
    "handAdapterCoordinateBasisPreserved=true",
    "handAdapterCpuPreparedParity=true",
    "handAdapterHighRateJson=false",
    "handAdapterBackendPayloadAbsent=true",
    "lockBindingSchema=rusty.quest.lock_bound_activation.v1",
    "activationState=applied",
    "conformanceLockRevision=1",
    "activationRejectReason=none"
)
$native = Get-Content -Raw -LiteralPath $NativeHandLogcatPath
$spatial = Get-Content -Raw -LiteralPath $SpatialHandLogcatPath
Assert-Markers "Native hand lab" $native (@(
    "RUSTY_QUEST_NATIVE_RENDERER channel=hand-adapter",
    "handAdapterConsumer=native-openxr-hand-lab",
    "projectId=native-renderer",
    "featureId=hand-adapter-consumer",
    "conformanceLockSha256=A1391A7EF2C41F072032283E485F5A9EB58CAB3B74681F150CE24CD9262CF91D",
    "runtimeProfileId=profile.quest.native_renderer.hand_adapter_conformance"
) + $shared)
Assert-Markers "Spatial Camera Panel" $spatial (@(
    "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=hand-adapter",
    "handAdapterConsumer=spatial-camera-panel",
    "projectId=spatial-camera-panel",
    "featureId=tracked-hand-surface",
    "conformanceLockSha256=FFB07E39C7290FDE8EBB154EFB94985CF4628CB2E4D098A81A5611849DFD32F1",
    "runtimeProfileId=profile.quest.spatial_camera_panel.hand_adapter_conformance"
) + $shared)

$result = [ordered]@{
    schema = "rusty.quest.hand_adapter.device_scorecard.v1"
    status = "accepted"
    consumers = @(
        [ordered]@{ consumer_id = "native-openxr-hand-lab"; accepted = $true },
        [ordered]@{ consumer_id = "spatial-camera-panel"; accepted = $true }
    )
    both_hands = $true
    coordinate_basis_preserved = $true
    cpu_prepared_parity = $true
    high_rate_json = $false
    backend_payload_absent = $true
    lock_bound_activation = $true
    lock_bindings = @(
        [ordered]@{ project_id = "native-renderer"; feature_id = "hand-adapter-consumer"; revision = 1; sha256 = "A1391A7EF2C41F072032283E485F5A9EB58CAB3B74681F150CE24CD9262CF91D" },
        [ordered]@{ project_id = "spatial-camera-panel"; feature_id = "tracked-hand-surface"; revision = 1; sha256 = "FFB07E39C7290FDE8EBB154EFB94985CF4628CB2E4D098A81A5611849DFD32F1" }
    )
}
$json = $result | ConvertTo-Json -Depth 8
if (-not [string]::IsNullOrWhiteSpace($Out)) {
    $parent = Split-Path -Parent $Out
    if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Set-Content -LiteralPath $Out -Value $json -Encoding utf8
}
$json
