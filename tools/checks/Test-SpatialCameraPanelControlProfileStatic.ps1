param([string]$RepoRoot)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing Spatial Camera control-profile file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )

    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

$contract = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraControlProfile.kt"
$hotloader = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraControlProfileHotloader.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$tests = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraControlProfileTest.kt"
$converter = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\HostessReplayControlStateConverter.kt"
$converterCli = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\HostessReplayControlStateCli.kt"
$converterTests = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\HostessReplayControlStateConverterTest.kt"
$converterTool = Read-RequiredText "tools\Convert-HostessReplayControlState.ps1"
$installer = Read-RequiredText "tools\Install-SpatialCameraPanelControlProfile.ps1"
$docs = Read-RequiredText "docs\SPATIAL_CAMERA_CONTROL_PROFILES.md"

Assert-Contains "Profile contract" $contract 'SCHEMA = "rusty.quest.spatial_camera_panel.control_profile.v1"'
Assert-Contains "Profile contract" $contract "MAX_PROFILE_BYTES = 64 * 1024"
Assert-Contains "Profile contract" $contract "unsupported_transparent_spatial_video_blend"
Assert-Contains "Profile contract" $contract 'APPLY_RECEIPT_FILE = "last-apply-receipt.json"'
Assert-Contains "Profile hotloader" $hotloader "staleProfileApplied=false"
Assert-Contains "Profile hotloader" $hotloader "status=pending-route"
Assert-Contains "Profile hotloader" $hotloader "previousEffectiveControlsRetained=true"
Assert-Contains "Activity integration" $activity "controlProfileHotloader.arm()"
Assert-Contains "Activity integration" $activity "controlProfileHotloader.poll()"
Assert-Contains "Activity integration" $activity "applyControlProfile"
Assert-Contains "Profile tests" $tests "validProfileCarriesStructuredDesktopControlsIntoQuestTypes"
Assert-Contains "Profile tests" $tests "damagedAndExpandedProfilesFailClosed"
Assert-Contains "Profile tests" $tests "unsampledOuterVideoRouteRejectsUnsupportedIncomingColor"
Assert-Contains "Profile tests" $tests "desktopPreviewMustBeAnObjectWhenPresent"
Assert-Contains "Profile tests" $tests "desktopPreviewRejectsUnknownMalformedAndOutOfRangeFields"
Assert-Contains "Hostess state converter" $converter 'INPUT_SCHEMA = "rusty.hostess.projection_replay_control_state.v2"'
Assert-Contains "Hostess state converter" $converter 'INPUT_V1_SCHEMA = "rusty.hostess.projection_replay_control_state.v1"'
Assert-Contains "Hostess state converter" $converter '"control_transport"'
Assert-Contains "Hostess state converter" $converter "SpatialCameraControlProfileContract.parse(encoded)"
Assert-Contains "Hostess state converter CLI" $converterCli "StandardCopyOption.ATOMIC_MOVE"
Assert-Contains "Hostess state converter tests" $converterTests "goldenHostessStateExportsSameEffectiveQuestControls"
Assert-Contains "Hostess state converter tests" $converterTests "damagedExpandedNonFiniteAndUnsupportedStatesFailClosed"
Assert-Contains "Hostess state converter tool" $converterTool ":app:convertHostessReplayControlState"
Assert-Contains "Manual installer" $installer "serial-scoped-adb-fallback"
Assert-Contains "Manual installer" $installer "profile_sha256"
Assert-Contains "Manual installer" $installer "last-apply-receipt.json"
Assert-Contains "Public documentation" $docs "Only an atomic replacement"
Assert-Contains "Public documentation" $docs "A successful file transfer without a matching"
Assert-Contains "Public documentation" $docs "not packaged into the APK"

Write-Output "Spatial Camera Panel control-profile static checks passed."
