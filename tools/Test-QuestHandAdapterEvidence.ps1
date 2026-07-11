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
    "handAdapterBackendPayloadAbsent=true"
)
$native = Get-Content -Raw -LiteralPath $NativeHandLogcatPath
$spatial = Get-Content -Raw -LiteralPath $SpatialHandLogcatPath
Assert-Markers "Native hand lab" $native (@("RUSTY_QUEST_NATIVE_RENDERER channel=hand-adapter", "handAdapterConsumer=native-openxr-hand-lab") + $shared)
Assert-Markers "Spatial Camera Panel" $spatial (@("RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=hand-adapter", "handAdapterConsumer=spatial-camera-panel") + $shared)

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
}
$json = $result | ConvertTo-Json -Depth 8
if (-not [string]::IsNullOrWhiteSpace($Out)) {
    $parent = Split-Path -Parent $Out
    if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Set-Content -LiteralPath $Out -Value $json -Encoding utf8
}
$json
