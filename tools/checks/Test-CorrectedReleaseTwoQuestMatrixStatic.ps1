param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}

$runnerPath = Join-Path $RepoRoot "tools\Invoke-CorrectedReleaseTwoQuestMatrix.ps1"
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    throw "Corrected release two-Quest orchestrator is missing: $runnerPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $runnerPath,
    [ref]$tokens,
    [ref]$parseErrors
) | Out-Null
if (@($parseErrors).Count -ne 0) {
    throw "Corrected release orchestrator has PowerShell parser errors: $(@($parseErrors | ForEach-Object { $_.Message }) -join '; ')"
}

$text = Get-Content -Raw -LiteralPath $runnerPath
foreach ($token in @(
    "rusty.morphospace.corrected_release_device_matrix.v1",
    "rusty.quest.corrected_release_criterion_evidence.v1",
    "rusty.quest.manifold_peer_authority_two_quest_evidence.v1",
    "Exactly two distinct explicit Quest serials are required",
    "user_authorized_serial_scoped",
    "live_two_quest",
    "provider_execution",
    "RejectFixturePath",
    "ConfirmBoundedLogcatClear",
    "Invoke-NativeRendererReplaySmoke.ps1",
    "Invoke-SpatialCameraPanelAndroidParticleVisualSmoke.ps1",
    "Test-QuestParticleAdapterEvidence.ps1",
    "Invoke-MultiAppBrokerClientTwoQuest.ps1",
    "Invoke-ManifoldPeerAuthorityTwoQuest.ps1",
    "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1",
    "Invoke-NativeRendererDisplayCompositeSmoke.ps1",
    "Apply-RuntimeProfile.ps1",
    "on-device",
    "public_key_ed25519_base64",
    "reciprocal_signed_evidence",
    "current_enrollment_revision",
    "current_rendezvous_revision",
    "rusty.manifold.peer.topology_authorization.v1",
    "rusty.manifold.peer.direct_lane_lease.v1",
    "old_key_rejected",
    "revoked_key_rejected",
    "explicit_local_bind",
    "route_inactive",
    "cleanup_complete",
    "package_fatal_count",
    "app_fatal_count",
    "system_fatal_count",
    "ReplayValidate",
    "rusty.quest.corrected_release_replay_validation.v1",
    "reducer_only_replay",
    "changed_paths_since_matrix_revision",
    "ReplayValidate allows only validator/reducer script changes after matrix revision"
)) {
    if (-not $text.Contains($token)) {
        throw "Corrected release orchestrator is missing required contract token: $token"
    }
}

$criteria = @(
    "module_lock_selected",
    "module_lock_off_lock",
    "client_lifecycle_native",
    "client_lifecycle_spatial",
    "enrollment_pair",
    "enrollment_revoke",
    "media_camera2",
    "media_display_composite",
    "cleanup",
    "bounded_fatal"
)
foreach ($criterion in $criteria) {
    if (-not $text.Contains('"' + $criterion + '"')) {
        throw "Corrected release orchestrator is missing criterion: $criterion"
    }
}

if ($text -notmatch '&\s+\$script:AdbPath\s+-s\s+\$transport') {
    throw "Corrected release orchestrator does not centrally enforce adb -s <serial>."
}
if ($text -match '\$env:RUSTY_QUEST_SERIAL|\$env:ANDROID_SERIAL|(?im)\badb(?:\.exe)?\s+devices\b') {
    throw "Corrected release orchestrator contains an implicit/default ADB target route."
}
if ($text -match '(?i)agent-board|agent board') {
    throw "Corrected release orchestrator must not perform Agent Board coordination."
}
if ($text -match '(?im)\bStop-Computer\b|\bshutdown(?:\.exe)?\s|\bpoweroff\b|\badb(?:\.exe)?\s+(?:kill-server|start-server|reboot)\b') {
    throw "Corrected release orchestrator contains a shutdown, reboot, or disruptive ADB lifecycle command."
}
if ($text -match '(?im)\b(InputSummary|ExistingSummary|ReduceOnly|FixtureSummary)\b') {
    throw "Corrected release orchestrator exposes a pre-existing/fixture-only reduction mode."
}
if ($text -notmatch '"tools/Invoke-CorrectedReleaseTwoQuestMatrix\.ps1"' -or
    $text -notmatch '"tools/checks/Test-CorrectedReleaseTwoQuestMatrixStatic\.ps1"') {
    throw "ReplayValidate must be scoped to validator/reducer script and static-check-only changes."
}

$providerPreflightIndex = $text.IndexOf('$providerPath = Assert-MandatoryPeerProvider')
$adbPreflightIndex = $text.IndexOf('$script:AdbPath = Resolve-RequiredFile')
if ($providerPreflightIndex -lt 0 -or $adbPreflightIndex -lt 0 -or $providerPreflightIndex -gt $adbPreflightIndex) {
    throw "Mandatory peer-provider preflight is not ordered before ADB/APK resolution."
}

$result = & powershell -NoProfile -ExecutionPolicy Bypass -File $runnerPath -Mode SelfTest 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Corrected release orchestrator damaged self-test failed: $($result -join ' ')"
}
if (($result -join "`n") -notmatch '\[PASS\] corrected release two-Quest matrix damaged self-test') {
    throw "Corrected release orchestrator damaged self-test did not report its pass marker."
}

Write-Output "[PASS] corrected release two-Quest matrix static contract"
