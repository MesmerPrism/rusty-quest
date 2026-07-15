[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "Baseline",
        "FrozenWorld",
        "NonBlocking",
        "FrozenNonBlocking",
        "LowQueue",
        "ImmediateLowQueue",
        "Cadence30",
        "Cadence45",
        "Cadence50",
        "Cadence60",
        "Adoption45",
        "EarlyDelete",
        "FenceHeld",
        "FenceHeld45",
        "ProcessingOffFenceHeld",
        "OpaqueCameraOnlyFenceHeld",
        "FreshFrameOnlyPulseFenceHeld",
        "FreezeFrameFenceHeld",
        "StrictPair",
        "MonoLeft",
        "RotationWarp40",
        "RotationWarp60",
        "RotationWarp80",
        "SensorWarp",
        "SensorWarpInverse",
        "SensorWarpInverse70",
        "SensorWarpInverse110",
        "SensorWarpInverseRollFree70",
        "SensorWarpInverseYawOnly70",
        "SensorWarpCameraCalibrated",
        "PresentationLatest50",
        "PresentationSceneExtrapolated8",
        "PresentationSceneExtrapolated11",
        "PresentationSceneExtrapolated16",
        "PresentationOpenXr0",
        "PresentationOpenXr8",
        "PresentationOpenXr11",
        "PresentationOpenXr11Overscan0",
        "PresentationOpenXr11Overscan10",
        "PresentationOpenXr11GuardBand10",
        "PresentationOpenXr16",
        "PresentationOpenXr22",
        "PresentationOpenXr11Adoption45",
        "PresentationOpenXr11Verbose",
        "PresentationOpenXr11Adoption45Verbose",
        "SensorWarp70",
        "SensorWarp110",
        "VerboseFrameLog",
        "Off"
    )]
    [string]$Preset,

    [string]$Serial = $env:RUSTY_QUEST_SERIAL,

    [string]$AdbPath = $env:RUSTY_QUEST_ADB,

    [long]$Revision = 0,

    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",

    [switch]$RestartApp,

    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$properties = [ordered]@{
    enabled = "debug.rustyquest.spatial.camera_latency.enabled"
    pose_mode = "debug.rustyquest.spatial.camera_latency.pose_mode"
    frame_wait_ms = "debug.rustyquest.spatial.camera_latency.frame_wait_ms"
    summary_ms = "debug.rustyquest.spatial.camera_latency.summary_ms"
    frame_log = "debug.rustyquest.spatial.camera_latency.frame_log"
    present_mode = "debug.rustyquest.spatial.camera_latency.present_mode"
    image_count = "debug.rustyquest.spatial.camera_latency.image_count"
    capture_fps = "debug.rustyquest.spatial.camera_latency.capture_fps"
    camera_sync_mode = "debug.rustyquest.spatial.camera_latency.camera_sync_mode"
    capture_processing = "debug.rustyquest.spatial.camera_latency.capture_processing"
    adoption_cadence = "debug.rustyquest.spatial.camera_latency.adoption_cadence"
    stereo_policy = "debug.rustyquest.spatial.camera_latency.stereo_policy"
    isolation_mode = "debug.rustyquest.spatial.camera_latency.isolation_mode"
    freeze_frame = "debug.rustyquest.spatial.camera_latency.freeze_frame"
    reprojection_mode = "debug.rustyquest.spatial.camera_latency.reprojection_mode"
    assumed_capture_age_ms = "debug.rustyquest.spatial.camera_latency.assumed_capture_age_ms"
    reprojection_fov_degrees = "debug.rustyquest.spatial.camera_latency.reprojection_fov_degrees"
    reprojection_source_overscan_percent = "debug.rustyquest.spatial.camera_latency.reprojection_source_overscan_percent"
    reprojection_guard_band_mode = "debug.rustyquest.spatial.camera_latency.reprojection_guard_band_mode"
    presentation_pose_mode = "debug.rustyquest.spatial.camera_latency.presentation_pose_mode"
    presentation_lead_ms = "debug.rustyquest.spatial.camera_latency.presentation_lead_ms"
    revision = "debug.rustyquest.spatial.camera_latency.revision"
}

$settings = [ordered]@{
    enabled = "true"
    pose_mode = "current-viewer"
    frame_wait_ms = "2"
    summary_ms = "1000"
    frame_log = "false"
    present_mode = "fifo"
    image_count = "min-plus-one"
    capture_fps = "camera-default"
    camera_sync_mode = "early-delete-ahb-retained"
    capture_processing = "template-default"
    adoption_cadence = "every-available"
    stereo_policy = "independent-latest"
    isolation_mode = "normal-composite"
    freeze_frame = "false"
    reprojection_mode = "off"
    assumed_capture_age_ms = "40"
    reprojection_fov_degrees = "90"
    reprojection_source_overscan_percent = "0"
    reprojection_guard_band_mode = "zoom-to-fill"
    presentation_pose_mode = "scene-tick-latest"
    presentation_lead_ms = "0"
}

$presetRequiresRestart = $false
$setPresentationWarpBaseline = {
    param(
        [Parameter(Mandatory = $true)][string]$PoseMode,
        [Parameter(Mandatory = $true)][int]$LeadMs
    )

    $settings.frame_wait_ms = "0"
    $settings.camera_sync_mode = "hold-image-until-gpu-fence"
    $settings.adoption_cadence = "every-available"
    $settings.stereo_policy = "strict-timestamp-pair"
    $settings.reprojection_mode = "rotation-only-sensor-timestamp-camera-calibrated"
    $settings.reprojection_fov_degrees = "73"
    $settings.presentation_pose_mode = $PoseMode
    $settings.presentation_lead_ms = $LeadMs.ToString([Globalization.CultureInfo]::InvariantCulture)
}
switch ($Preset) {
    "FrozenWorld" {
        $settings.pose_mode = "frozen-world"
    }
    "NonBlocking" {
        $settings.frame_wait_ms = "0"
    }
    "FrozenNonBlocking" {
        $settings.pose_mode = "frozen-world"
        $settings.frame_wait_ms = "0"
    }
    "LowQueue" {
        $settings.frame_wait_ms = "0"
        $settings.present_mode = "mailbox-if-available"
        $settings.image_count = "min-safe"
        $presetRequiresRestart = $true
    }
    "ImmediateLowQueue" {
        $settings.frame_wait_ms = "0"
        $settings.present_mode = "immediate-if-available"
        $settings.image_count = "min-safe"
        $presetRequiresRestart = $true
    }
    "Cadence30" {
        $settings.frame_wait_ms = "0"
        $settings.capture_fps = "30"
        $presetRequiresRestart = $true
    }
    "Cadence45" {
        $settings.frame_wait_ms = "0"
        $settings.capture_fps = "45"
        $presetRequiresRestart = $true
    }
    "Cadence50" {
        $settings.frame_wait_ms = "0"
        $settings.capture_fps = "50"
        $presetRequiresRestart = $true
    }
    "Cadence60" {
        $settings.frame_wait_ms = "0"
        $settings.capture_fps = "60"
        $presetRequiresRestart = $true
    }
    "Adoption45" {
        $settings.frame_wait_ms = "0"
        $settings.adoption_cadence = "display-aligned-45"
    }
    "EarlyDelete" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "early-delete-ahb-retained"
    }
    "FenceHeld" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
    }
    "FenceHeld45" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.adoption_cadence = "display-aligned-45"
    }
    "ProcessingOffFenceHeld" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.capture_processing = "noise-edge-off"
        $presetRequiresRestart = $true
    }
    "OpaqueCameraOnlyFenceHeld" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.isolation_mode = "opaque-camera-only"
    }
    "FreshFrameOnlyPulseFenceHeld" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.isolation_mode = "fresh-frame-only-pulse"
    }
    "FreezeFrameFenceHeld" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.freeze_frame = "true"
    }
    "StrictPair" {
        $settings.frame_wait_ms = "0"
        $settings.stereo_policy = "strict-timestamp-pair"
    }
    "MonoLeft" {
        $settings.frame_wait_ms = "0"
        $settings.stereo_policy = "mono-duplicate-left"
    }
    "RotationWarp40" {
        $settings.frame_wait_ms = "0"
        $settings.reprojection_mode = "rotation-only-raw-layer"
        $settings.assumed_capture_age_ms = "40"
    }
    "RotationWarp60" {
        $settings.frame_wait_ms = "0"
        $settings.reprojection_mode = "rotation-only-raw-layer"
        $settings.assumed_capture_age_ms = "60"
    }
    "RotationWarp80" {
        $settings.frame_wait_ms = "0"
        $settings.reprojection_mode = "rotation-only-raw-layer"
        $settings.assumed_capture_age_ms = "80"
    }
    "SensorWarp" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp"
        $settings.reprojection_fov_degrees = "90"
    }
    "SensorWarpInverse" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp-inverse"
        $settings.reprojection_fov_degrees = "90"
    }
    "SensorWarpInverse70" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp-inverse"
        $settings.reprojection_fov_degrees = "70"
    }
    "SensorWarpInverse110" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp-inverse"
        $settings.reprojection_fov_degrees = "110"
    }
    "SensorWarpInverseRollFree70" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp-inverse-roll-free"
        $settings.reprojection_fov_degrees = "70"
    }
    "SensorWarpInverseYawOnly70" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp-inverse-yaw-only"
        $settings.reprojection_fov_degrees = "70"
    }
    "SensorWarpCameraCalibrated" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp-camera-calibrated"
        $settings.reprojection_fov_degrees = "73"
    }
    "PresentationLatest50" {
        & $setPresentationWarpBaseline -PoseMode "scene-tick-latest" -LeadMs 0
    }
    "PresentationSceneExtrapolated8" {
        & $setPresentationWarpBaseline -PoseMode "scene-extrapolated" -LeadMs 8
    }
    "PresentationSceneExtrapolated11" {
        & $setPresentationWarpBaseline -PoseMode "scene-extrapolated" -LeadMs 11
    }
    "PresentationSceneExtrapolated16" {
        & $setPresentationWarpBaseline -PoseMode "scene-extrapolated" -LeadMs 16
    }
    "PresentationOpenXr0" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 0
    }
    "PresentationOpenXr8" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 8
    }
    "PresentationOpenXr11" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
    }
    "PresentationOpenXr11Overscan0" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
        $settings.reprojection_source_overscan_percent = "0"
    }
    "PresentationOpenXr11Overscan10" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
        $settings.reprojection_source_overscan_percent = "10"
        $settings.reprojection_guard_band_mode = "zoom-to-fill"
    }
    "PresentationOpenXr11GuardBand10" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
        $settings.reprojection_source_overscan_percent = "10"
        $settings.reprojection_guard_band_mode = "reduced-footprint"
    }
    "PresentationOpenXr16" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 16
    }
    "PresentationOpenXr22" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 22
    }
    "PresentationOpenXr11Adoption45" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
        $settings.adoption_cadence = "display-aligned-45"
    }
    "PresentationOpenXr11Verbose" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
        $settings.summary_ms = "500"
        $settings.frame_log = "true"
    }
    "PresentationOpenXr11Adoption45Verbose" {
        & $setPresentationWarpBaseline -PoseMode "openxr-locate-views" -LeadMs 11
        $settings.adoption_cadence = "display-aligned-45"
        $settings.summary_ms = "500"
        $settings.frame_log = "true"
    }
    "SensorWarp70" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp"
        $settings.reprojection_fov_degrees = "70"
    }
    "SensorWarp110" {
        $settings.frame_wait_ms = "0"
        $settings.camera_sync_mode = "hold-image-until-gpu-fence"
        $settings.stereo_policy = "strict-timestamp-pair"
        $settings.reprojection_mode = "rotation-only-sensor-timestamp"
        $settings.reprojection_fov_degrees = "110"
    }
    "VerboseFrameLog" {
        $settings.summary_ms = "500"
        $settings.frame_log = "true"
    }
    "Off" {
        $settings.enabled = "false"
    }
}

if ($Revision -le 0) {
    $Revision = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

$writePlan = [System.Collections.Generic.List[object]]::new()
foreach ($key in $settings.Keys) {
    $writePlan.Add([ordered]@{
        property = $properties[$key]
        value = $settings[$key]
        transaction_role = "payload"
    })
}
$writePlan.Add([ordered]@{
    property = $properties.revision
    value = $Revision.ToString([Globalization.CultureInfo]::InvariantCulture)
    transaction_role = "commit-revision-last"
})

$result = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.camera_latency_diagnostic_set.v1"
    preset = $Preset
    serial = $Serial
    dry_run = [bool]$DryRun
    revision = $Revision
    transport = "adb-explicit-serial-system-property-revision-last"
    live_safe_fields = @("pose_mode", "frame_wait_ms", "summary_ms", "frame_log", "camera_sync_mode", "adoption_cadence", "stereo_policy", "isolation_mode", "freeze_frame", "reprojection_mode", "assumed_capture_age_ms", "reprojection_fov_degrees", "reprojection_source_overscan_percent", "reprojection_guard_band_mode", "presentation_pose_mode", "presentation_lead_ms")
    restart_required_fields = @("present_mode", "image_count", "capture_fps", "capture_processing")
    preset_requires_restart = $presetRequiresRestart
    restart_requested = [bool]$RestartApp
    write_plan = $writePlan
    readback = [ordered]@{}
}

if ($DryRun) {
    $result | ConvertTo-Json -Depth 8
    return
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set RUSTY_QUEST_SERIAL. Device selection must be explicit."
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $AdbPath = if ($env:ANDROID_HOME) {
        Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    } else {
        "adb"
    }
}

if (-not (Test-Path -LiteralPath $AdbPath)) {
    $adbCommand = Get-Command $AdbPath -ErrorAction SilentlyContinue
    if ($null -eq $adbCommand) {
        throw "adb not found: $AdbPath"
    }
    $AdbPath = $adbCommand.Source
} else {
    $AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
}

function Invoke-SerialAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb -s $Serial $($Arguments -join ' ') failed ($LASTEXITCODE): $($output -join "`n")"
    }
    return $output
}

foreach ($entry in $writePlan) {
    Invoke-SerialAdb -Arguments @("shell", "setprop", $entry.property, $entry.value) | Out-Null
}

foreach ($entry in $writePlan) {
    $readback = (Invoke-SerialAdb -Arguments @("shell", "getprop", $entry.property) | Out-String).Trim()
    $result.readback[$entry.property] = $readback
    if ($readback -ne $entry.value) {
        throw "Property readback mismatch for $($entry.property): expected '$($entry.value)', got '$readback'."
    }
}

if ($RestartApp) {
    Invoke-SerialAdb -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
    Invoke-SerialAdb -Arguments @(
        "shell",
        "am",
        "start",
        "-n",
        "$PackageName/.SpatialCameraPanelActivity"
    ) | Out-Null
    $result.restart_performed = $true
} else {
    $result.restart_performed = $false
}

$result | ConvertTo-Json -Depth 8
