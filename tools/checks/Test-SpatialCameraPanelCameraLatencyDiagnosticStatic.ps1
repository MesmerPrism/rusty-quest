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
        throw "Missing camera latency diagnostic file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Text.Contains($Needle)) {
        throw $Message
    }
}

$kotlin = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraLatencyDiagnosticModule.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$placement = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraHwbProjectionPlacementUpdateCoordinator.kt"
$native = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_latency_diagnostics.rs"
$probe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$stream = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_stream.rs"
$build = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"
$toolPath = Join-Path $repoRootPath "tools\Set-SpatialCameraPanelCameraLatencyDiagnostic.ps1"
$tool = Read-RequiredText "tools\Set-SpatialCameraPanelCameraLatencyDiagnostic.ps1"

Assert-Contains $kotlin "transport=android-system-property-revision-last" "Kotlin diagnostic module must report revision-last transport."
Assert-Contains $kotlin "restartRequiredFields=present-mode,image-count,capture-fps,capture-processing" "Kotlin diagnostic module must distinguish restart-required fields."
Assert-Contains $kotlin "hold-image-until-gpu-fence" "Kotlin diagnostic module must expose fence-held camera images."
Assert-Contains $kotlin "noise-edge-off" "Kotlin diagnostic module must expose the support-gated ISP processing control."
Assert-Contains $kotlin "opaque-camera-only" "Kotlin diagnostic module must expose camera-only render isolation."
Assert-Contains $kotlin "fresh-frame-only-pulse" "Kotlin diagnostic module must expose fresh-frame-only hold isolation."
Assert-Contains $kotlin "display-aligned-45" "Kotlin diagnostic module must expose display-aligned 45 Hz image adoption."
Assert-Contains $kotlin "frozen-world" "Kotlin diagnostic module must expose the frozen-world pose A/B mode."
Assert-Contains $kotlin "rotation-only-raw-layer" "Kotlin diagnostic module must expose raw-layer rotation reprojection."
Assert-Contains $kotlin "rotation-only-sensor-timestamp" "Kotlin diagnostic module must expose direct sensor-timestamp rotation reprojection."
Assert-Contains $kotlin "rotation-only-sensor-timestamp-inverse" "Kotlin diagnostic module must expose the inverse-direction sensor-timestamp A/B."
Assert-Contains $kotlin "strict-timestamp-pair" "Kotlin diagnostic module must expose strict stereo pairing."
Assert-Contains $activity "nativeUpdateCameraLatencyDiagnostics" "Activity must bridge diagnostic settings into native code."
Assert-Contains $activity "cameraLatencyDiagnosticModule.projectionPlane" "Activity must route projection placement through the pose A/B module."
Assert-Contains $activity "nativeUpdateCameraLatencyViewerPose" "Activity must feed viewer-pose history to the native diagnostic."
Assert-Contains $placement "pollLatencyDiagnostics(reason, forceLog)" "Placement updates must poll live diagnostic revisions."
Assert-Contains $native "status=latency-summary" "Native diagnostics must emit bounded summary windows."
Assert-Contains $native "presentAgeSemantics=queue-present-call-not-photons" "Native diagnostics must label the present-age limitation."
Assert-Contains $native "launchSettingsPendingRestart" "Native diagnostics must report staged restart fields."
Assert-Contains $native "sourceTimestampIntervalSemantics=relative-valid-even-when-absolute-age-unavailable" "Native diagnostics must report relative source cadence."
Assert-Contains $native "leftDisplayHoldAvgFrames" "Native diagnostics must report display-frame holds."
Assert-Contains $native "callbackCounterSemantics=successfully-published-camera-frame" "Native diagnostics must bind callback counters to producer-published frames."
Assert-Contains $native "status=latency-stereo-summary" "Native diagnostics must emit a compact stereo atomicity summary."
Assert-Contains $native "strictAtomicImportInvariant" "Native diagnostics must report strict-pair single-eye import violations."
Assert-Contains $probe "effective_frame_wait_ms" "Render loop must hotload the camera-frame wait."
Assert-Contains $probe "should_adopt_camera_image(frames_presented)" "Render loop must gate camera-image adoption on display cadence."
Assert-Contains $probe "StrictTimestampPair" "Render loop must implement strict timestamp pairing."
Assert-Contains $probe "status=strict-stereo-pair-presented" "Render loop must emit pair-generation presentation evidence."
Assert-Contains $probe "packedEyesRecordedInSingleCommandBuffer=true" "Strict-pair evidence must bind both eyes to one command buffer."
Assert-Contains $probe "MonoDuplicateLeft" "Render loop must implement the mono duplicate control."
Assert-Contains $probe "camera_projection_visible" "Render loop must record effective custom-projection pulse visibility."
Assert-Contains $native "cameraProjectionSuppressedPresents" "Native summaries must report suppressed held-frame presentations."
Assert-Contains $stream "AImageReader_acquireLatestImage" "Camera path must retain acquireLatestImage queue dropping."
Assert-Contains $stream "camera_latency_per_frame_log_enabled()" "High-rate camera markers must be opt-in."
Assert-Contains $stream "captureFpsApplyStatus" "Camera startup must report the capture-FPS request result."
Assert-Contains $stream "image-slot-held-through-vulkan-frame-fence" "Camera acquisition must expose the fence-held image lease."
Assert-Contains $stream "cameraSyncTransition" "Camera acquisition must log every live camera-sync transition."
Assert-Contains $stream "captureProcessingApplyStatus" "Camera startup must report support-gated ISP override application."
Assert-Contains $stream "capturePoseAssociation" "Camera acquisition must report the effective image-to-pose association."
Assert-Contains $build 'camera_latency_diagnostic_module = "spatial-camera-latency-diagnostic-module"' "Build manifest must identify the diagnostic module."
Assert-Contains $build 'camera_latency_diagnostic_present_age_semantics = "queue-present-call-not-photons"' "Build manifest must preserve the present-age limitation."
Assert-Contains $tool "commit-revision-last" "Preset tool must write the revision last."
Assert-Contains $tool 'adb-explicit-serial-system-property-revision-last' "Preset tool must describe explicit serial routing."

$presets = @(
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
    "SensorWarp70",
    "SensorWarp110",
    "VerboseFrameLog",
    "Off"
)
$revision = 9000L
foreach ($preset in $presets) {
    $json = & $toolPath -Preset $preset -Revision $revision -DryRun
    $plan = $json | ConvertFrom-Json
    if (@($plan.write_plan).Count -ne 18) {
        throw "Preset '$preset' must produce seventeen payload writes and one commit write."
    }
    $last = @($plan.write_plan)[-1]
    if ($last.transaction_role -ne "commit-revision-last" -or $last.property -notlike "*.revision") {
        throw "Preset '$preset' did not put its revision commit last."
    }
    $revision += 1
}

$lowQueue = (& $toolPath -Preset LowQueue -Revision 9991 -DryRun | ConvertFrom-Json)
if (-not $lowQueue.preset_requires_restart) {
    throw "LowQueue must be classified as a restart-required swapchain experiment."
}
$immediate = (& $toolPath -Preset ImmediateLowQueue -Revision 9992 -DryRun | ConvertFrom-Json)
if (-not $immediate.preset_requires_restart) {
    throw "ImmediateLowQueue must be classified as a restart-required swapchain experiment."
}
$cadence = (& $toolPath -Preset Cadence45 -Revision 9993 -DryRun | ConvertFrom-Json)
if (-not $cadence.preset_requires_restart) {
    throw "Cadence45 must be classified as a restart-required camera request experiment."
}
$adoption = (& $toolPath -Preset Adoption45 -Revision 9994 -DryRun | ConvertFrom-Json)
if ($adoption.preset_requires_restart) {
    throw "Adoption45 must remain live-safe."
}
$adoptionWrite = @($adoption.write_plan | Where-Object { $_.property -like "*.adoption_cadence" })
if ($adoptionWrite.Count -ne 1 -or $adoptionWrite[0].value -ne "display-aligned-45") {
    throw "Adoption45 must select the display-aligned-45 adoption cadence."
}
$warp = (& $toolPath -Preset RotationWarp60 -Revision 9995 -DryRun | ConvertFrom-Json)
if ($warp.preset_requires_restart) {
    throw "RotationWarp60 must remain live-safe."
}
$sensorWarp = (& $toolPath -Preset SensorWarp -Revision 99951 -DryRun | ConvertFrom-Json)
if ($sensorWarp.preset_requires_restart) {
    throw "SensorWarp must remain live-safe."
}
$sensorWarpMode = @($sensorWarp.write_plan | Where-Object { $_.property -like "*.reprojection_mode" })
$sensorWarpSync = @($sensorWarp.write_plan | Where-Object { $_.property -like "*.camera_sync_mode" })
$sensorWarpStereo = @($sensorWarp.write_plan | Where-Object { $_.property -like "*.stereo_policy" })
if ($sensorWarpMode.Count -ne 1 -or $sensorWarpMode[0].value -ne "rotation-only-sensor-timestamp") {
    throw "SensorWarp must select direct sensor-timestamp reprojection."
}
if ($sensorWarpSync.Count -ne 1 -or $sensorWarpSync[0].value -ne "hold-image-until-gpu-fence") {
    throw "SensorWarp must preserve the fence-held image lifetime."
}
if ($sensorWarpStereo.Count -ne 1 -or $sensorWarpStereo[0].value -ne "strict-timestamp-pair") {
    throw "SensorWarp must remove independent-eye adoption as a confound."
}
$sensorWarpInverse70 = (& $toolPath -Preset SensorWarpInverse70 -Revision 99952 -DryRun | ConvertFrom-Json)
$sensorWarpInverse70Mode = @($sensorWarpInverse70.write_plan | Where-Object { $_.property -like "*.reprojection_mode" })
$sensorWarpInverse70Fov = @($sensorWarpInverse70.write_plan | Where-Object { $_.property -like "*.reprojection_fov_degrees" })
if (
    $sensorWarpInverse70.preset_requires_restart -or
    $sensorWarpInverse70Mode.Count -ne 1 -or
    $sensorWarpInverse70Mode[0].value -ne "rotation-only-sensor-timestamp-inverse" -or
    $sensorWarpInverse70Fov.Count -ne 1 -or
    $sensorWarpInverse70Fov[0].value -ne "70"
) {
    throw "SensorWarpInverse70 must remain a live-safe inverse-direction 70-degree FOV test."
}
$sensorWarpInverse110 = (& $toolPath -Preset SensorWarpInverse110 -Revision 99953 -DryRun | ConvertFrom-Json)
$sensorWarpInverse110Mode = @($sensorWarpInverse110.write_plan | Where-Object { $_.property -like "*.reprojection_mode" })
$sensorWarpInverse110Fov = @($sensorWarpInverse110.write_plan | Where-Object { $_.property -like "*.reprojection_fov_degrees" })
if (
    $sensorWarpInverse110.preset_requires_restart -or
    $sensorWarpInverse110Mode.Count -ne 1 -or
    $sensorWarpInverse110Mode[0].value -ne "rotation-only-sensor-timestamp-inverse" -or
    $sensorWarpInverse110Fov.Count -ne 1 -or
    $sensorWarpInverse110Fov[0].value -ne "110"
) {
    throw "SensorWarpInverse110 must remain a live-safe inverse-direction 110-degree FOV test."
}
$sensorWarpInverseRollFree70 = (& $toolPath -Preset SensorWarpInverseRollFree70 -Revision 99954 -DryRun | ConvertFrom-Json)
$sensorWarpInverseRollFree70Mode = @($sensorWarpInverseRollFree70.write_plan | Where-Object { $_.property -like "*.reprojection_mode" })
$sensorWarpInverseRollFree70Fov = @($sensorWarpInverseRollFree70.write_plan | Where-Object { $_.property -like "*.reprojection_fov_degrees" })
if (
    $sensorWarpInverseRollFree70.preset_requires_restart -or
    $sensorWarpInverseRollFree70Mode.Count -ne 1 -or
    $sensorWarpInverseRollFree70Mode[0].value -ne "rotation-only-sensor-timestamp-inverse-roll-free" -or
    $sensorWarpInverseRollFree70Fov.Count -ne 1 -or
    $sensorWarpInverseRollFree70Fov[0].value -ne "70"
) {
    throw "SensorWarpInverseRollFree70 must remain a live-safe roll-free inverse 70-degree test."
}
$sensorWarpInverseYawOnly70 = (& $toolPath -Preset SensorWarpInverseYawOnly70 -Revision 99955 -DryRun | ConvertFrom-Json)
$sensorWarpInverseYawOnly70Mode = @($sensorWarpInverseYawOnly70.write_plan | Where-Object { $_.property -like "*.reprojection_mode" })
$sensorWarpInverseYawOnly70Fov = @($sensorWarpInverseYawOnly70.write_plan | Where-Object { $_.property -like "*.reprojection_fov_degrees" })
if (
    $sensorWarpInverseYawOnly70.preset_requires_restart -or
    $sensorWarpInverseYawOnly70Mode.Count -ne 1 -or
    $sensorWarpInverseYawOnly70Mode[0].value -ne "rotation-only-sensor-timestamp-inverse-yaw-only" -or
    $sensorWarpInverseYawOnly70Fov.Count -ne 1 -or
    $sensorWarpInverseYawOnly70Fov[0].value -ne "70"
) {
    throw "SensorWarpInverseYawOnly70 must remain a live-safe yaw-only inverse 70-degree test."
}
$sensorWarpCameraCalibrated = (& $toolPath -Preset SensorWarpCameraCalibrated -Revision 99956 -DryRun | ConvertFrom-Json)
$sensorWarpCameraCalibratedMode = @($sensorWarpCameraCalibrated.write_plan | Where-Object { $_.property -like "*.reprojection_mode" })
$sensorWarpCameraCalibratedFov = @($sensorWarpCameraCalibrated.write_plan | Where-Object { $_.property -like "*.reprojection_fov_degrees" })
if (
    $sensorWarpCameraCalibrated.preset_requires_restart -or
    $sensorWarpCameraCalibratedMode.Count -ne 1 -or
    $sensorWarpCameraCalibratedMode[0].value -ne "rotation-only-sensor-timestamp-camera-calibrated" -or
    $sensorWarpCameraCalibratedFov.Count -ne 1 -or
    $sensorWarpCameraCalibratedFov[0].value -ne "73"
) {
    throw "SensorWarpCameraCalibrated must remain a live-safe Camera2-calibrated reprojection test."
}
$fenceHeld = (& $toolPath -Preset FenceHeld -Revision 9996 -DryRun | ConvertFrom-Json)
if ($fenceHeld.preset_requires_restart) {
    throw "FenceHeld must remain live-safe."
}
$fenceWrite = @($fenceHeld.write_plan | Where-Object { $_.property -like "*.camera_sync_mode" })
if ($fenceWrite.Count -ne 1 -or $fenceWrite[0].value -ne "hold-image-until-gpu-fence") {
    throw "FenceHeld must select the fence-held AImage lifetime."
}
$processing = (& $toolPath -Preset ProcessingOffFenceHeld -Revision 9997 -DryRun | ConvertFrom-Json)
if (-not $processing.preset_requires_restart) {
    throw "ProcessingOffFenceHeld must restart the Camera2 request."
}
$freeze = (& $toolPath -Preset FreezeFrameFenceHeld -Revision 9998 -DryRun | ConvertFrom-Json)
if ($freeze.preset_requires_restart) {
    throw "FreezeFrameFenceHeld must remain live-safe."
}
$freshPulse = (& $toolPath -Preset FreshFrameOnlyPulseFenceHeld -Revision 9999 -DryRun | ConvertFrom-Json)
if ($freshPulse.preset_requires_restart) {
    throw "FreshFrameOnlyPulseFenceHeld must remain live-safe."
}
$freshPulseWrite = @($freshPulse.write_plan | Where-Object { $_.property -like "*.isolation_mode" })
if ($freshPulseWrite.Count -ne 1 -or $freshPulseWrite[0].value -ne "fresh-frame-only-pulse") {
    throw "FreshFrameOnlyPulseFenceHeld must select fresh-frame-only-pulse isolation."
}

Write-Host "Spatial Camera Panel camera latency diagnostic static validation passed"
