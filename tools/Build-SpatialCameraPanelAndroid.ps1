param(
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$GradleVersion = "9.4.1",
    [string]$RecordedHandCaptureDir = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR,
    [int]$RecordedHandFrameLimit = 24,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @()
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-LatestDirectory {
    param(
        [Parameter(Mandatory=$true)][string]$Parent,
        [Parameter(Mandatory=$true)][string]$Pattern
    )
    $directory = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $directory) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $directory.FullName
}

function Test-ZipEntry {
    param(
        [Parameter(Mandatory=$true)][string]$ZipPath,
        [Parameter(Mandatory=$true)][string]$EntryName
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ZipPath).Path)
    try {
        return [bool]($zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1)
    } finally {
        $zip.Dispose()
    }
}

function Invoke-DownloadFile {
    param(
        [Parameter(Mandatory=$true)][string]$Uri,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $client = [System.Net.WebClient]::new()
    try {
        $client.DownloadFile($Uri, $OutFile)
    } finally {
        $client.Dispose()
    }
}

function Invoke-DownloadText {
    param([Parameter(Mandatory=$true)][string]$Uri)
    $client = [System.Net.WebClient]::new()
    try {
        return $client.DownloadString($Uri)
    } finally {
        $client.Dispose()
    }
}

function Resolve-Gradle {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$Version
    )
    $localRoot = Join-Path $RepoRoot "local-artifacts"
    $toolsRoot = Join-Path $localRoot "tools"
    $downloadsRoot = Join-Path $localRoot "downloads"
    $gradleHome = Join-Path $toolsRoot "gradle-$Version"
    $gradleBat = Join-Path $gradleHome "bin\gradle.bat"
    if (Test-Path -LiteralPath $gradleBat) {
        return $gradleBat
    }

    New-Item -ItemType Directory -Force -Path $toolsRoot, $downloadsRoot | Out-Null
    $zipPath = Join-Path $downloadsRoot "gradle-$Version-bin.zip"
    $distributionUrl = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
    if (-not (Test-Path -LiteralPath $zipPath)) {
        Invoke-DownloadFile -Uri $distributionUrl -OutFile $zipPath
    }

    $expectedSha = (Invoke-DownloadText -Uri "$distributionUrl.sha256").Trim().Split()[0].ToLowerInvariant()
    $actualSha = Get-FileSha256 -Path $zipPath
    if ($expectedSha -ne $actualSha) {
        throw "Gradle distribution SHA-256 mismatch for $zipPath. Expected $expectedSha but found $actualSha."
    }

    Expand-Archive -LiteralPath $zipPath -DestinationPath $toolsRoot -Force
    if (-not (Test-Path -LiteralPath $gradleBat)) {
        throw "Gradle distribution did not provide expected executable: $gradleBat"
    }
    return $gradleBat
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required. Activate the Quest/Android toolchain first."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required. Activate the Quest/Android toolchain first."
}
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    $ndkRoot = Join-Path $AndroidHome "ndk"
    if (Test-Path -LiteralPath $ndkRoot) {
        $NdkHome = Get-LatestDirectory -Parent $ndkRoot -Pattern "*"
    }
}
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    throw "ANDROID_NDK_HOME, -NdkHome, or an Android SDK ndk directory is required. Activate the Quest/Android toolchain first."
}
$resolvedRecordedHandCaptureDir = ""
if (-not [string]::IsNullOrWhiteSpace($RecordedHandCaptureDir)) {
    if (-not (Test-Path -LiteralPath $RecordedHandCaptureDir -PathType Container)) {
        throw "Recorded hand capture directory not found: $RecordedHandCaptureDir"
    }
    $resolvedRecordedHandCaptureDir = (Resolve-Path -LiteralPath $RecordedHandCaptureDir).Path
}
$resolvedRecordedHandFrameLimit = [Math]::Max(1, [Math]::Min(120, $RecordedHandFrameLimit))

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Join-Path $PSScriptRoot ".."
}
$repoRoot = Resolve-Path $RepoRoot
$appRoot = Resolve-Path (Join-Path $repoRoot "apps\spatial-camera-panel-android")
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "spatial-camera-panel-android"
}

New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd("\")
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$nativeReceiptRoot = Join-Path $appRoot "native-receipt"
$nativeReceiptCargoManifest = Join-Path $nativeReceiptRoot "Cargo.toml"
$nativeReceiptTargetDir = Join-Path $targetRoot "spatial-camera-panel-native-receipt-cargo"
$nativeReceiptJniRoot = Join-Path $appRoot "app\build\generated\rustJniLibs"
$nativeReceiptJniAbiDir = Join-Path $nativeReceiptJniRoot "arm64-v8a"
$nativeReceiptJniLib = Join-Path $nativeReceiptJniAbiDir "libspatial_camera_panel_native_receipt.so"
$nativeReceiptApkEntry = "lib/arm64-v8a/libspatial_camera_panel_native_receipt.so"
$nativeReceiptLinker = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
$cargoCommand = Get-Command cargo -ErrorAction Stop
$rustupCommand = Get-Command rustup -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $nativeReceiptCargoManifest)) {
    throw "Missing Spatial Camera Panel native receipt Cargo manifest: $nativeReceiptCargoManifest"
}
if (-not (Test-Path -LiteralPath $nativeReceiptLinker)) {
    throw "Required Android NDK linker not found: $nativeReceiptLinker"
}
if ($null -ne $rustupCommand) {
    Invoke-Checked "rustup target add aarch64-linux-android" $rustupCommand.Source @(
        "target",
        "add",
        "aarch64-linux-android"
    )
}

New-Item -ItemType Directory -Force -Path $nativeReceiptJniAbiDir, $nativeReceiptTargetDir | Out-Null
$previousAndroidHomeForCargo = $env:ANDROID_HOME
$previousNdkHomeForCargo = $env:ANDROID_NDK_HOME
$previousLinkerForCargo = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$previousCcForCargo = $env:CC_aarch64_linux_android
$previousRecordedHandCaptureDir = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR
$previousRecordedHandFrameLimit = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_NDK_HOME = $NdkHome
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $nativeReceiptLinker
    $env:CC_aarch64_linux_android = $nativeReceiptLinker
    if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR -ErrorAction SilentlyContinue
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR = $resolvedRecordedHandCaptureDir
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT = $resolvedRecordedHandFrameLimit.ToString()
    }
    Invoke-Checked "Spatial Camera Panel native receipt cargo build" $cargoCommand.Source @(
        "build",
        "--manifest-path", $nativeReceiptCargoManifest,
        "--target", "aarch64-linux-android",
        "--release",
        "--target-dir", $nativeReceiptTargetDir
    )
} finally {
    if ($null -eq $previousAndroidHomeForCargo) {
        Remove-Item Env:\ANDROID_HOME -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_HOME = $previousAndroidHomeForCargo
    }
    if ($null -eq $previousNdkHomeForCargo) {
        Remove-Item Env:\ANDROID_NDK_HOME -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_NDK_HOME = $previousNdkHomeForCargo
    }
    if ($null -eq $previousLinkerForCargo) {
        Remove-Item Env:\CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER -ErrorAction SilentlyContinue
    } else {
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $previousLinkerForCargo
    }
    if ($null -eq $previousCcForCargo) {
        Remove-Item Env:\CC_aarch64_linux_android -ErrorAction SilentlyContinue
    } else {
        $env:CC_aarch64_linux_android = $previousCcForCargo
    }
    if ($null -eq $previousRecordedHandCaptureDir) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR = $previousRecordedHandCaptureDir
    }
    if ($null -eq $previousRecordedHandFrameLimit) {
        Remove-Item Env:\RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT = $previousRecordedHandFrameLimit
    }
}
$nativeReceiptBuiltLib = Join-Path $nativeReceiptTargetDir "aarch64-linux-android\release\libspatial_camera_panel_native_receipt.so"
if (-not (Test-Path -LiteralPath $nativeReceiptBuiltLib)) {
    throw "Cargo build did not produce native receipt library: $nativeReceiptBuiltLib"
}
Copy-Item -LiteralPath $nativeReceiptBuiltLib -Destination $nativeReceiptJniLib -Force
$nativeReceiptSha256 = Get-FileSha256 -Path $nativeReceiptJniLib

$gradleBat = Resolve-Gradle -RepoRoot ([string]$repoRoot) -Version $GradleVersion
$gradleUserHome = Join-Path $repoRoot "local-artifacts\gradle-user-home"
New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null

$previousAndroidHome = $env:ANDROID_HOME
$previousJavaHome = $env:JAVA_HOME
$previousGradleUserHome = $env:GRADLE_USER_HOME
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    Invoke-Checked "Spatial Camera Panel Gradle build" $gradleBat @(
        "--no-daemon",
        "--console=plain",
        "-p", ([string]$appRoot),
        ":app:assembleDebug"
    )
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:JAVA_HOME = $previousJavaHome
    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:\GRADLE_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }
}

$apkSource = Join-Path $appRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apkSource)) {
    throw "Gradle build did not produce expected APK: $apkSource"
}

$apkOut = Join-Path $OutDir "rusty-quest-spatial-camera-panel.apk"
Copy-Item -LiteralPath $apkSource -Destination $apkOut -Force
$sha256 = Get-FileSha256 -Path $apkOut
$nativeReceiptLibraryPackaged = Test-ZipEntry -ZipPath $apkOut -EntryName $nativeReceiptApkEntry
if (-not $nativeReceiptLibraryPackaged) {
    throw "APK is missing native receipt library entry: $nativeReceiptApkEntry"
}

$manifest = [ordered]@{
    '$schema' = "rusty.quest.spatial_camera_panel_sdk_android.build_manifest.v1"
    package_name = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    activity = "io.github.mesmerprism.rustyquest.spatial_camera_panel/.SpatialCameraPanelActivity"
    app_lane = "spatial-camera-panel-android"
    authority = "rusty.quest.spatial_camera_panel_sdk_panel"
    target_runtime = "quest-spatial-sdk-appsystemactivity-panel"
    spatial_input_mode = "interaction-sdk-hands-and-controllers"
    spatial_vr_input_system_default = "interaction_sdk"
    spatial_should_consume_left_right_input_default = $false
    spatial_handtracking_manifest_declared = $true
    spatial_handtracking_permission_declared = $true
    spatial_render_model_manifest_declared = $true
    spatial_render_model_permission_declared = $true
    spatial_multimodal_input_default_enabled = $false
    native_spatial_controller_actions_default_enabled = $false
    spatial_controller_launch_policy = "app-owned-readiness-prompt-if-no-active-avatarbody-controller"
    spatial_sdk_version = "0.13.1"
    android_gradle_plugin_version = "8.11.1"
    kotlin_version = "2.1.0"
    gradle_version = $GradleVersion
    native_renderer_package_preserved = "io.github.mesmerprism.rustyquest.native_renderer"
    native_renderer_spatial_sdk_packaged = $false
    native_interop_probe = "spatial-sdk-openxr-handles-and-panelsurface-capability"
    native_interop_probe_rendering = "no-render"
    native_interop_probe_runtime_handles = @(
        "Scene.getOpenXrInstanceHandle",
        "Scene.getOpenXrSessionHandle",
        "Scene.getOpenXrGetInstanceProcAddrHandle"
    )
    native_interop_probe_surface = "PanelSurface-create-destroy"
    native_receipt_probe = "rust-jni-openxr-handle-and-panelsurface-receipt"
    native_receipt_rendering = "no-render"
    native_receipt_openxr_probe = "xrGetInstanceProperties-vulkan-requirements-and-no-present-vulkan-objects-through-sdk-getInstanceProcAddr"
    native_receipt_vulkan_object_probe = "no-present-instance-device-queue-create-destroy"
    native_receipt_jni_bridge = "SpatialCameraPanelActivity.nativeRecordNoRenderInteropReceipt"
    native_receipt_mask_bits = @(
        "received",
        "openxr-instance-nonzero",
        "openxr-session-nonzero",
        "openxr-getInstanceProcAddr-nonzero",
        "panel-surface-valid",
        "openxr-getInstanceProcAddr-callable",
        "xrGetInstanceProperties-resolved",
        "xrGetInstanceProperties-succeeded",
        "xrGetSystem-resolved",
        "xrGetSystem-succeeded",
        "xrGetVulkanGraphicsRequirements2KHR-resolved",
        "xrGetVulkanGraphicsRequirements2KHR-succeeded",
        "xrCreateVulkanInstanceKHR-resolved",
        "xrGetVulkanGraphicsDevice2KHR-resolved",
        "xrCreateVulkanDeviceKHR-resolved",
        "vk-instance-created",
        "vk-graphics-device-obtained",
        "vk-graphics-compute-queue-found",
        "vk-device-created",
        "vk-queue-obtained",
        "vk-objects-destroyed"
    )
    native_receipt_library = $nativeReceiptApkEntry
    native_receipt_library_packaged = $nativeReceiptLibraryPackaged
    native_receipt_library_sha256 = $nativeReceiptSha256
    native_receipt_generated_jni_libs = "app/build/generated/rustJniLibs/arm64-v8a"
    native_surface_particle_layer = "VideoSurfacePanelRegistration-native-vulkan-wsi-surface-panel"
    native_surface_particle_layer_rendering = "native-vulkan-wsi-surface-panel-live-openxr-gpu-skinned-resident-rig-hand-anchor-particles-packed-stereo-left-right"
    native_surface_particle_layer_jni_bridge = "SpatialCameraPanelActivity.nativeStartSurfaceParticleLayer"
    native_surface_particle_layer_stop_bridge = "SpatialCameraPanelActivity.nativeStopSurfaceParticleLayer"
    native_surface_particle_layer_parameter_bridge = "SpatialCameraPanelActivity.nativeUpdateSurfaceParticleParameters"
    native_surface_particle_layer_parameter_transport = "jni-live-queue"
    driver_profile_parameter_bridge = "SpatialCameraPanelActivity.applyDriverProfileToParticleControls-to-nativeUpdateSurfaceParticleParameters"
    driver_profile_mapping = "driver0_value01-to-native-driver0;driver1_value01-to-native-driver1"
    panel_flow = "panel-first-workflow-then-explicit-panel-close-starts-driver-profile-block"
    driver_profile_panel_transition = "setWorkflowPanelVisible(false,false,source=block-start)-before-startNextBlock"
    questionnaire_next_block_policy = "questionnaire-submit-keeps-panel-open-ready_next_block-explicit-start"
    surface_modes = @("real-hands", "gpu-replay-hands", "icosphere")
    driver_profile_high_rate_policy = "profile-metadata-and-bounded-scalars-only"
    native_surface_particle_layer_hotload_property = "debug.rustyquest.spatial_camera_panel.live_hand_depth_offset_meters"
    native_surface_particle_layer_live_hand_depth_offset_default_meters = 0.0
    native_surface_particle_layer_live_hand_scene_transform = "viewer-relative-openxr-to-spatial-sdk-panel-basis"
    native_surface_particle_layer_live_hand_scene_fallback_transform = "raw-openxr-local-floor-to-spatial-sdk-scene"
    native_surface_particle_layer_live_hand_scene_transform_source = "runtime-hotload-android-property"
    native_surface_particle_layer_live_hand_scene_transform_properties = @(
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_x_m",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_y_m",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.offset_z_m",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.yaw_degrees",
        "debug.rustyquest.spatial_camera_panel.live_hand_scene.horizontal_sign"
    )
    native_surface_particle_layer_live_hand_scene_offset_default_meters = "0.0;0.0;2.0"
    native_surface_particle_layer_live_hand_scene_yaw_default_degrees = 180.0
    native_surface_particle_layer_live_hand_scene_horizontal_sign_default = -1.0
    native_surface_particle_layer_target_distance_hotload_property = "debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters"
    native_surface_particle_layer_target_distance_default_meters = 0.72
    native_surface_particle_layer_target_distance_range_meters = "0.20..1.50"
    camera_hwb_projection_quad_default_target_distance_meters = 1.0
    camera_hwb_projection_quad_target_distance_control = "fixed-default"
    camera_hwb_projection_stereo_horizontal_offset_control = "left-controller-joystick-y"
    camera_hwb_projection_stereo_horizontal_offset_joystick_rate_property = "debug.rustyquest.spatial.camera_hwb_projection_probe.stereo_horizontal_offset.joystick.rate_uvps"
    camera_hwb_projection_stereo_horizontal_offset_default_rate_uv_per_second = 0.08
    camera_hwb_projection_stereo_horizontal_offset_default_uv = 0.046320
    camera_hwb_projection_stereo_horizontal_offset_default_source = "quest-live-headset-readback-20260628"
    camera_hwb_projection_stereo_horizontal_offset_range_uv = "-0.12..0.12"
    camera_hwb_projection_stereo_horizontal_offset_sign = "positive-increases-separation"
    camera_hwb_projection_quad_angular_coverage_policy = "preserve-current-plane-fov-by-scaling-width-and-height-with-distance"
    camera_hwb_projection_eye_space_target_rect_policy = "preserve-packed-eye-uv-target-rects-plus-live-opposed-horizontal-offset"
    camera_hwb_projection_native_panel_pose_authority = "camera-hwb-projection-plane"
    camera_hwb_projection_suppresses_particle_panel_pose_authority = $true
    forced_replay_hand_source_mode = $(if ([string]::IsNullOrWhiteSpace($resolvedRecordedHandCaptureDir)) { "public-shape-fallback" } else { "external-recorded-capture-build-env" })
    forced_replay_hand_frame_limit = $resolvedRecordedHandFrameLimit
    native_surface_particle_layer_markers = @(
        "panel-entity-spawned",
        "surface-panel-ready",
        "started",
        "render-loop-ready",
        "surfaceLayerMode=native-hand-anchor-particles",
        "native-hand-anchor-mesh-components",
        "native-hand-anchor-left-hand-mesh-components",
        "native-hand-anchor-right-hand-mesh-components",
        "forcedReplayHands=true",
        "forcedReplayMeshVisible=false",
        "diagnosticParticlesVisible=false",
        "publicHandAnchorParticlesVisible=true",
        "handAnchorParticlesVisible=true",
        "gpuReplayHandsResident=true",
        "handAnchorParticlePath=resident-recorded-rig-gpu-skinned-mesh-coordinate-anchor-billboards",
        "handAnchorParticleCoordinateSource=live-openxr-world-joints-gpu-skinned-resident-mesh-with-forced-replay-fallback",
        "liveHandJointFrameSource=XR_EXT_hand_tracking",
        "liveHandJointGpuInputPath=recorded-compatible-compact-joint-pose-gpu-skinning",
        "liveHandCompactUploadEquivalent=true",
        "liveHandCompactFrameGate=native-equivalent-21-runtime-5-tip",
        "liveHandRuntimeJointPoseCount=",
        "liveHandTipLengthCount=",
        "liveHandJointPlacementMode=viewer-relative-openxr-to-spatial-sdk-panel-plane",
        "liveHandCoordinateTransform=viewer-relative-openxr-to-spatial-sdk-panel-basis",
        "liveHandViewPoseSource=xrLocateViews",
        "liveHandPanelBasisSource=Scene.getViewerPose-panel-plane",
        "liveHandSceneTransformSource=runtime-hotload-android-property",
        "liveHandSceneOffsetDefaultM=0.000;0.000;2.000",
        "liveHandSceneYawDefaultDegrees=180.000",
        "liveHandSceneHorizontalSignDefault=-1.000",
        "liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning",
        "liveMeshSurfacePolicy=keep_two_largest_components_drop_wrist_bridge_boundaries_v1",
        "liveMeshComponentRank0=hand-inside",
        "liveMeshComponentRank1=hand-back",
        "liveMeshComponentRank2=wrist-cap",
        "liveMeshWristCapPolicy=drop-component-rank-2",
        "liveMeshNormalFallbackPolicy=skinned-bind-normal-for-small-triangle-area",
        "liveMeshTriangleValidationAttempts=6",
        "liveHandCorrectPositionSizeProof=spatial-sdk-panel-plane-projection",
        "liveHandJointStatusY=pose-valid",
        "liveHandSkinningValidityPolicy=native-compact-frame-gate-trust-all-weights",
        "liveHandDepthOffsetParameterSource=runtime-hotload-android-property",
        "liveHandDepthOffsetProperty=debug.rustyquest.spatial_camera_panel.live_hand_depth_offset_meters",
        "particleDiagnosticModeProperty=debug.rustyquest.spatial_camera_panel.particle_layer.diagnostic_mode",
        "particleDiagnosticModeName=",
        "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property",
        "particleLayerTargetDistanceProperty=debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters",
        "privatePayloadActive=false",
        "driverProfileDynamicsActive=true",
        "driverProfileId=profile-b",
        "driverProfileSchemaId=rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1",
        "driverBaseHz=0.88",
        "driverMix01=0.0",
        "properStereoHandAnchorParticles=true",
        "replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection",
        "computeParticleStateBuffer=true",
        "computeShaderDispatchReady=true",
        "computeParameterBridge=true",
        "native-surface-compute-stereo-proof=true",
        "sideBySideStereoProof=true",
        "stereoMode=LeftRight",
        "cameraFacingParticleSurface=true",
        "projectionLockedParticleSurface=true",
        "placementMode=viewer-pose-projection-locked-quad",
        "targetProjectionSpace=spatial-sdk-panel-plane-perspective-projection",
        "projectionContentMappingMode=world-to-spatial-sdk-panel-plane-left-right",
        "first-frame-presented"
    )
    native_surface_particle_layer_shape = [ordered]@{
        width_px = 2048
        per_eye_width_px = 1024
        height_px = 1024
        stereo_mode = "StereoMode.LeftRight"
        packed_stereo_layout = "left-right"
        particles = 2048
        width_meters = 1.44
        height_meters = 1.44
        target_distance_meters = 0.72
        x_meters = 0.0
        y_meters = 1.22
        z_meters = -0.72
        placement_mode = "viewer-pose-projection-locked-quad"
        placement_authority = "spatial-sdk-viewer-pose-scene-tick"
        target_coordinate_space = "spatial-sdk-surface-panel-eye-uv"
        target_projection_space = "spatial-sdk-panel-plane-perspective-projection"
        target_fov_tangents = "panel-plane-derived"
        projection_content_mapping_mode = "world-to-spatial-sdk-panel-plane-left-right"
        left_target_surface_uv_rect = "0.0;0.0;1.0;1.0"
        right_target_surface_uv_rect = "0.0;0.0;1.0;1.0"
        view_origin_meters = "0.0;0.0;2.0"
        view_origin_yaw_degrees = 180.0
    }
    panel_registration_id = "spatial_camera_panel"
    panel_launcher_registration_id = "spatial_camera_panel_launcher"
    particle_surface_panel_registration_id = "spatial_camera_surface_panel"
    polar_sensor_panel = "spatial-sdk-direct-ble-panel"
    polar_sensor_permissions = @(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN"
    )
    polar_sensor_streams = @(
        "stream.polar_h10.hr_rr",
        "stream.polar_h10.ecg",
        "stream.polar_h10.acc",
        "stream.polar_h10.device_status"
    )
    polar_sensor_event_mirror = "SpatialCameraPanelStore.appendPolarEvent-to-polar_events-jsonl-and-ecg_events-jsonl"
    polar_sensor_high_rate_policy = "ble-stream-decoded-in-panel-not-renderer-json"
    polar_live_validation_action = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_POLAR_LIVE_VALIDATION"
    polar_live_validation_wrapper = "tools/Invoke-SpatialCameraPanelAndroidPolarLive.ps1"
    polar_live_validation_required_markers = @(
        "polar-live-validation status=start",
        "polar-live-validation status=polar-panel-automation-ready",
        "polar-live-validation status=scan-command-issued",
        "polar-sensor-panel status=device-found",
        "polar-sensor-panel status=connected",
        "polar-sensor-panel status=pmd-started mode=ecg",
        "polar-sensor-panel status=ecg-frame",
        "experiment status=polar-stream-event-recorded streamId=stream.polar_h10.ecg ecgMirrored=true",
        "polar-live-validation status=complete ecgReceiving=true"
    )
    polar_live_validation_app_private_files = @(
        "polar_sensor_status.json",
        "polar_stream_events.jsonl",
        "spatial_camera_panel_session.json",
        "polar_events.jsonl",
        "ecg_events.jsonl"
    )
    spatial_panel_mode = "workflow-panel-open-or-particle-view-panel-closed"
    spatial_panel_mode_transition = "Visible(false)-workflow-panel-with-launcher-reopen"
    spatial_panel_mode_renderer_continuity = "native-vulkan-surface-particle-layer-kept-running"
    spatial_panel_focus_pose_meters = "0.0;1.1;0.475"
    spatial_panel_surface_target_activation_action = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"
    spatial_panel_ui_action = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
    spatial_panel_ui_action_wrapper = "tools/Invoke-SpatialCameraPanelAndroidUiAction.ps1"
    spatial_panel_ui_actions = @(
        "panel-open",
        "panel-close",
        "panel-reset",
        "panel-headlock-on",
        "panel-headlock-off",
        "panel-headlock-toggle",
        "panel-adjust",
        "panel-resize",
        "particle-controls",
        "participant-reset",
        "participant-begin",
        "polar-setup-save",
        "surface-select",
        "start-block",
        "surface-target-activate",
        "questionnaire-submit"
    )
    spatial_panel_debug_controller_reopen = "right-controller-primary-button-SpatialSDK-Controller-ButtonA-plus-Android-KeyEvent-and-motion-fallback-opens-workflow-panel-when-closed"
    spatial_panel_headlock_mode = "enabled-by-default-viewer-relative-while-workflow-panel-open"
    spatial_panel_headlock_default_pose_meters = "0.0;0.0;1.40"
    spatial_panel_headlock_default_scale = 0.65
    spatial_panel_headlock_hotload_tool = "tools/Set-SpatialCameraPanelHeadlock.ps1"
    spatial_panel_headlock_hotload_properties = @(
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.enabled",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_x_m",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_y_m",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.distance_meters",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.width_meters",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.height_meters",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.scale",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.enabled",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.translate_rate_mps",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.distance_rate_mps",
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.scale_rate_per_second"
    )
    spatial_panel_headlock_joystick_controls = "android-generic-motion-left-stick-x-horizontal-left-stick-y-panel-scroll-right-stick-y-projection-scale-right-stick-x-ignored"
    spatial_camera_projection_distance_controls = "fixed-1m-default; no joystick distance control"
    spatial_camera_projection_scale_controls = "android-right-stick-y; spatial-sdk-avatar-body-right-thumb-up-down; native-openxr-right-thumbstick-y diagnostic; panel-control"
    spatial_camera_projection_stereo_offset_controls = "disabled-default-locked; left-stick-y-reserved-for-panel-scroll"
    spatial_camera_projection_distance_vr_input_system_property = "debug.rustyquest.spatial_camera_panel.vr_input_system"
    spatial_panel_headlock_tuning_file = "files/spatial_camera_panel_headlock_tuning.json"
    panel_shape_meters = [ordered]@{
        width = 1.20
        height = 1.254
    }
    panel_display = [ordered]@{
        option = "DpPerMeterDisplayOptions"
        dp_per_meter = 720
    }
    panel_launcher_shape_meters = [ordered]@{
        width = 0.78
        height = 0.30
    }
    panel_transform_runtime_controls = @("Transform(Pose(Vector3, Quaternion))", "Scale(Vector3)", "PanelDimensions(Vector2)", "Visible(panelPlacement.visible)", "Visible(!panelPlacement.visible)-launcher")
    diagnostic_backdrop = "disabled-vulkan-carrier-is-user-facing-surface"
    panel_content_probe = "sample-quaternion-opaque-yellow-background-teal-banner-orange-button"
    questionnaire_schema = "rusty.quest.spatial_camera_panel.questionnaire.v1"
    high_rate_json_payload = $false
    hand_rendering_expected = $false
    controller_rendering_expected = $true
    spatial_pointer_input_expected = $true
    apk_path = $apkOut
    apk_sha256 = $sha256
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkOut
