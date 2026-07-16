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
$openXrRoute = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialOpenXrRouteModule.kt"
$placement = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraHwbProjectionPlacementUpdateCoordinator.kt"
$native = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_latency_diagnostics.rs"
$probe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$stream = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_stream.rs"
$projection = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_projection_target.rs"
$projectionShader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\camera_hwb_raw_color.frag.glsl"
$publicMultiStackRuntime = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack_runtime.rs"
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
Assert-Contains $kotlin "openxr-locate-views" "Kotlin diagnostic module must expose estimated presentation-time OpenXR view location."
Assert-Contains $kotlin "presentation_lead_ms" "Kotlin diagnostic module must expose bounded presentation lead."
Assert-Contains $kotlin "reprojection_source_overscan_percent" "Kotlin diagnostic module must expose bounded real-source overscan."
Assert-Contains $kotlin "reprojection_guard_band_mode" "Kotlin diagnostic module must expose the explicit projection-footprint policy."
Assert-Contains $activity "nativeUpdateCameraLatencyDiagnostics" "Activity must bridge diagnostic settings into native code."
Assert-Contains $activity "cameraLatencyDiagnosticModule.projectionPlane" "Activity must route projection placement through the pose A/B module."
Assert-Contains $activity "nativeUpdateCameraLatencyViewerPose" "Activity must feed viewer-pose history to the native diagnostic."
Assert-Contains $activity "nativeConfigureCameraLatencyOpenXrHandles" "Activity must pass the SDK-owned OpenXR handles to the read-only view locator."
Assert-Contains $openXrRoute "XR_KHR_convert_timespec_time" "Spatial SDK instance creation must enable the monotonic-to-XrTime conversion extension."
Assert-Contains $placement "pollLatencyDiagnostics(reason, forceLog)" "Placement updates must poll live diagnostic revisions."
Assert-Contains $native "status=latency-summary" "Native diagnostics must emit bounded summary windows."
Assert-Contains $native "status=latency-source-summary" "Native diagnostics must split source cadence into a bounded correlated row."
Assert-Contains $native "status=latency-hold-summary" "Native diagnostics must split display holds into a bounded correlated row."
Assert-Contains $native "status=latency-age-summary" "Native diagnostics must split capture/presentation ages into a bounded correlated row."
Assert-Contains $native "status=latency-stage-summary" "Native diagnostics must split stage timing maxima into a bounded correlated row."
Assert-Contains $native "status=latency-config-summary" "Native diagnostics must split active settings into a bounded correlated row."
Assert-Contains $native "windowSequence" "All latency summary rows must carry a shared window sequence."
Assert-Contains $native "CAMERA_LATENCY_SUMMARY_MARKER_MAX_BYTES" "Latency summary rows must have an explicit Android log budget."
Assert-Contains $native "status=latency-summary-overflow" "Oversized latency evidence must fail visibly instead of being silently truncated."
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
Assert-Contains $probe "recoveryPolicy=discard-both-latest-candidates" "Display-aligned strict pairing must recover without chasing alternating one-period-old eye frames."
Assert-Contains $probe "recoveryPolicy=discard-unpaired-latest-candidate" "Display-aligned strict pairing must not retain a single latest eye across the next 45 Hz poll."
Assert-Contains $probe "status=strict-stereo-pair-presented" "Render loop must emit pair-generation presentation evidence."
Assert-Contains $probe "packedEyesRecordedInSingleCommandBuffer=true" "Strict-pair evidence must bind both eyes to one command buffer."
Assert-Contains $probe "MonoDuplicateLeft" "Render loop must implement the mono duplicate control."
Assert-Contains $probe "camera_projection_visible" "Render loop must record effective custom-projection pulse visibility."
Assert-Contains $native "cameraProjectionSuppressedPresents" "Native summaries must report suppressed held-frame presentations."
Assert-Contains $native "interpolated-bracket" "Capture-pose association must interpolate bracketing Scene samples."
Assert-Contains $native "openxr-locate-views-estimated-presentation-time" "Presentation pose diagnostics must identify OpenXR view location."
Assert-Contains $native "xrLocateViews" "Presentation-pose location must use xrLocateViews without owning the frame loop."
Assert-Contains $probe "status=camera-presentation-pose" "Render loop must bind each submitted frame to presentation-pose evidence."
Assert-Contains $probe "sidecarXrWaitFrame=false" "Render loop evidence must deny sidecar frame-loop ownership."
Assert-Contains $stream "AImageReader_acquireLatestImage" "Camera path must retain acquireLatestImage queue dropping."
Assert-Contains $stream "camera_latency_per_frame_log_enabled()" "High-rate camera markers must be opt-in."
Assert-Contains $stream "captureFpsApplyStatus" "Camera startup must report the capture-FPS request result."
Assert-Contains $stream "image-slot-held-through-vulkan-frame-fence" "Camera acquisition must expose the fence-held image lease."
Assert-Contains $stream "cameraSyncTransition" "Camera acquisition must log every live camera-sync transition."
Assert-Contains $stream "captureProcessingApplyStatus" "Camera startup must report support-gated ISP override application."
Assert-Contains $stream "capturePoseAssociation" "Camera acquisition must report the effective image-to-pose association."
Assert-Contains $native "calibrationScope=independent-per-eye" "Camera calibration must remain independently scoped to each eye."
Assert-Contains $projection "CameraHwbProjectionEyePush" "Camera projection must use one bounded push block per eye."
Assert-Contains $projection "size_of::<CameraHwbProjectionEyePush>() <= 128" "Per-eye camera push constants must fit the portable Vulkan minimum."
Assert-Contains $projectionShader "discard;" "Reprojection must discard invalid UVs instead of edge clamping."
Assert-Contains $projectionShader "camera_source_uv_for_presentation" "Reprojection must map the fixed output footprint into a retained central source crop."
Assert-Contains $projectionShader "sourceOverscanUv" "Reprojection must expose the configured real-camera margin to the shader."
Assert-Contains $projectionShader "presentationSourceUv" "Rotation reprojection must start from the central source crop."
Assert-Contains $projection "effective_rect(left_base_effective, footprint_scale" "Projection geometry must scale the retained-source footprint around each existing eye center."
Assert-Contains $probe "cameraAngularScalePolicy" "Presentation evidence must distinguish zoom-to-fill from preserved angular scale."
if ($projectionShader.Contains("stable_rotation_reprojected_uv")) {
    throw "Reprojection must not hide exhausted source coverage with an unwarped fallback image."
}
Assert-Contains $publicMultiStackRuntime "cameraPresentationReprojectionGuideIngress=private-guide-pass0-prewarped-camera-color" "The normal effect path must receive the presentation reprojection before private guide generation."
Assert-Contains $publicMultiStackRuntime "size_of::<OpaqueGuidePush>() <= 128" "The private guide reprojection push must fit the portable Vulkan minimum."
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
)
$revision = 9000L
foreach ($preset in $presets) {
    $json = & $toolPath -Preset $preset -Revision $revision -DryRun
    $plan = $json | ConvertFrom-Json
    if (@($plan.write_plan).Count -ne 22) {
        throw "Preset '$preset' must produce twenty-one payload writes and one commit write."
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
$presentationOpenXr11 = (& $toolPath -Preset PresentationOpenXr11 -Revision 99957 -DryRun | ConvertFrom-Json)
$presentationOpenXr11Pose = @($presentationOpenXr11.write_plan | Where-Object { $_.property -like "*.presentation_pose_mode" })
$presentationOpenXr11Lead = @($presentationOpenXr11.write_plan | Where-Object { $_.property -like "*.presentation_lead_ms" })
$presentationOpenXr11Cadence = @($presentationOpenXr11.write_plan | Where-Object { $_.property -like "*.adoption_cadence" })
if (
    $presentationOpenXr11.preset_requires_restart -or
    $presentationOpenXr11Pose.Count -ne 1 -or
    $presentationOpenXr11Pose[0].value -ne "openxr-locate-views" -or
    $presentationOpenXr11Lead.Count -ne 1 -or
    $presentationOpenXr11Lead[0].value -ne "11" -or
    $presentationOpenXr11Cadence.Count -ne 1 -or
    $presentationOpenXr11Cadence[0].value -ne "every-available"
) {
    throw "PresentationOpenXr11 must be a live-safe every-available estimated-presentation-time candidate."
}
$presentationOpenXr11Overscan0 = (& $toolPath -Preset PresentationOpenXr11Overscan0 -Revision 999571 -DryRun | ConvertFrom-Json)
$presentationOpenXr11Overscan10 = (& $toolPath -Preset PresentationOpenXr11Overscan10 -Revision 999572 -DryRun | ConvertFrom-Json)
$presentationOpenXr11GuardBand10 = (& $toolPath -Preset PresentationOpenXr11GuardBand10 -Revision 999573 -DryRun | ConvertFrom-Json)
$overscan0Write = @($presentationOpenXr11Overscan0.write_plan | Where-Object { $_.property -like "*.reprojection_source_overscan_percent" })
$overscan10Write = @($presentationOpenXr11Overscan10.write_plan | Where-Object { $_.property -like "*.reprojection_source_overscan_percent" })
$overscan10ModeWrite = @($presentationOpenXr11Overscan10.write_plan | Where-Object { $_.property -like "*.reprojection_guard_band_mode" })
$guardBand10Write = @($presentationOpenXr11GuardBand10.write_plan | Where-Object { $_.property -like "*.reprojection_source_overscan_percent" })
$guardBand10ModeWrite = @($presentationOpenXr11GuardBand10.write_plan | Where-Object { $_.property -like "*.reprojection_guard_band_mode" })
if (
    $presentationOpenXr11Overscan0.preset_requires_restart -or
    $overscan0Write.Count -ne 1 -or
    $overscan0Write[0].value -ne "0"
) {
    throw "PresentationOpenXr11Overscan0 must remain the live-safe no-margin visual control."
}
if (
    $presentationOpenXr11Overscan10.preset_requires_restart -or
    $overscan10Write.Count -ne 1 -or
    $overscan10Write[0].value -ne "10" -or
    $overscan10ModeWrite.Count -ne 1 -or
    $overscan10ModeWrite[0].value -ne "zoom-to-fill"
) {
    throw "PresentationOpenXr11Overscan10 must remain the live-safe zoom-to-fill real-camera-margin control."
}
if (
    $presentationOpenXr11GuardBand10.preset_requires_restart -or
    $guardBand10Write.Count -ne 1 -or
    $guardBand10Write[0].value -ne "10" -or
    $guardBand10ModeWrite.Count -ne 1 -or
    $guardBand10ModeWrite[0].value -ne "reduced-footprint"
) {
    throw "PresentationOpenXr11GuardBand10 must preserve source scale by coupling ten-percent margins to an eighty-percent target footprint."
}
$presentationOpenXr11Adoption45 = (& $toolPath -Preset PresentationOpenXr11Adoption45 -Revision 99958 -DryRun | ConvertFrom-Json)
$presentationOpenXr11Adoption45Cadence = @($presentationOpenXr11Adoption45.write_plan | Where-Object { $_.property -like "*.adoption_cadence" })
if (
    $presentationOpenXr11Adoption45.preset_requires_restart -or
    $presentationOpenXr11Adoption45Cadence.Count -ne 1 -or
    $presentationOpenXr11Adoption45Cadence[0].value -ne "display-aligned-45"
) {
    throw "PresentationOpenXr11Adoption45 must remain a live-safe 45 Hz image-adoption control."
}
$presentationOpenXr11Verbose = (& $toolPath -Preset PresentationOpenXr11Verbose -Revision 999581 -DryRun | ConvertFrom-Json)
$presentationOpenXr11VerboseFrameLog = @($presentationOpenXr11Verbose.write_plan | Where-Object { $_.property -like "*.frame_log" })
$presentationOpenXr11VerboseSummary = @($presentationOpenXr11Verbose.write_plan | Where-Object { $_.property -like "*.summary_ms" })
$presentationOpenXr11VerboseCadence = @($presentationOpenXr11Verbose.write_plan | Where-Object { $_.property -like "*.adoption_cadence" })
if (
    $presentationOpenXr11Verbose.preset_requires_restart -or
    $presentationOpenXr11VerboseFrameLog.Count -ne 1 -or
    $presentationOpenXr11VerboseFrameLog[0].value -ne "true" -or
    $presentationOpenXr11VerboseSummary.Count -ne 1 -or
    $presentationOpenXr11VerboseSummary[0].value -ne "500" -or
    $presentationOpenXr11VerboseCadence.Count -ne 1 -or
    $presentationOpenXr11VerboseCadence[0].value -ne "every-available"
) {
    throw "PresentationOpenXr11Verbose must preserve the 50 Hz candidate while enabling bounded motion evidence."
}
$presentationOpenXr11Adoption45Verbose = (& $toolPath -Preset PresentationOpenXr11Adoption45Verbose -Revision 999582 -DryRun | ConvertFrom-Json)
$presentationOpenXr11Adoption45VerboseFrameLog = @($presentationOpenXr11Adoption45Verbose.write_plan | Where-Object { $_.property -like "*.frame_log" })
$presentationOpenXr11Adoption45VerboseCadence = @($presentationOpenXr11Adoption45Verbose.write_plan | Where-Object { $_.property -like "*.adoption_cadence" })
if (
    $presentationOpenXr11Adoption45Verbose.preset_requires_restart -or
    $presentationOpenXr11Adoption45VerboseFrameLog.Count -ne 1 -or
    $presentationOpenXr11Adoption45VerboseFrameLog[0].value -ne "true" -or
    $presentationOpenXr11Adoption45VerboseCadence.Count -ne 1 -or
    $presentationOpenXr11Adoption45VerboseCadence[0].value -ne "display-aligned-45"
) {
    throw "PresentationOpenXr11Adoption45Verbose must preserve the 45 Hz control while enabling motion evidence."
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
