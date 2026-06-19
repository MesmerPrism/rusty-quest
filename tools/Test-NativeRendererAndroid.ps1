param(
    [switch]$Build,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Join-Path $repoRoot "apps\native-renderer-android"
$manifestPath = Join-Path $appRoot "AndroidManifest.xml"
$readmePath = Join-Path $appRoot "README.md"
$nativeCargoPath = Join-Path $appRoot "native\Cargo.toml"
$nativeBuildRsPath = Join-Path $appRoot "native\build.rs"
$nativeLibPath = Join-Path $appRoot "native\src\lib.rs"
$androidEventsPath = Join-Path $appRoot "native\src\android_events.rs"
$nativeCameraPath = Join-Path $appRoot "native\src\native_camera.rs"
$nativeCameraMetadataPath = Join-Path $appRoot "native\src\native_camera_metadata.rs"
$nativeCameraProfilesPath = Join-Path $appRoot "native\src\native_camera_profiles.rs"
$nativeCameraReaderSelectionPath = Join-Path $appRoot "native\src\native_camera_reader_selection.rs"
$acameraSysPath = Join-Path $appRoot "native\src\acamera_sys.rs"
$cameraProjectionPath = Join-Path $appRoot "native\src\camera_projection.rs"
$cameraProjectionMetadataPath = Join-Path $appRoot "native\src\camera_projection_metadata.rs"
$environmentDepthGeometryPath = Join-Path $appRoot "native\src\environment_depth_geometry.rs"
$environmentDepthParticlesPath = Join-Path $appRoot "native\src\gpu_environment_depth_particles.rs"
$openxrEnvironmentDepthPath = Join-Path $appRoot "native\src\openxr_environment_depth.rs"
$guideBlurGraphPath = Join-Path $appRoot "native\src\guide_blur_graph.rs"
$recordedHandReplayModulePath = Join-Path $appRoot "native\src\recorded_hand_replay.rs"
$liveHandCompactPath = Join-Path $appRoot "native\src\live_hand_compact.rs"
$nativeRendererOptionsPath = Join-Path $appRoot "native\src\native_renderer_options.rs"
$nativeRendererTimingPath = Join-Path $appRoot "native\src\native_renderer_timing.rs"
$privateExtensionSlotPath = Join-Path $appRoot "native\src\private_extension_slot.rs"
$handMeshGraftPath = Join-Path $appRoot "native\src\hand_mesh_graft.rs"
$gpuHandMeshVisualPath = Join-Path $appRoot "native\src\gpu_hand_mesh_visual.rs"
$gpuMeshReplayPath = Join-Path $appRoot "native\src\gpu_mesh_replay.rs"
$gpuSdfFieldPath = Join-Path $appRoot "native\src\gpu_sdf_field.rs"
$cameraProjectionFragmentPath = Join-Path $appRoot "native\shaders\camera_projection.frag.glsl"
$guideBlurDownsampleFragmentPath = Join-Path $appRoot "native\shaders\guide_blur_downsample.frag.glsl"
$guideBlurFragmentPath = Join-Path $appRoot "native\shaders\guide_blur_5tap.frag.glsl"
$guideProjectionFragmentPath = Join-Path $appRoot "native\shaders\guide_projection.frag.glsl"
$handMeshVisualVertexPath = Join-Path $appRoot "native\shaders\hand_mesh_visual.vert.glsl"
$handMeshVisualFragmentPath = Join-Path $appRoot "native\shaders\hand_mesh_visual.frag.glsl"
$gpuHandSkinningShaderPath = Join-Path $appRoot "native\shaders\gpu_hand_skinning.comp.glsl"
$gpuSdfFieldShaderPath = Join-Path $appRoot "native\shaders\gpu_sdf_field.comp.glsl"
$gpuSdfTileBinsShaderPath = Join-Path $appRoot "native\shaders\gpu_sdf_tile_bins.comp.glsl"
$gpuSdfOverlayShaderPath = Join-Path $appRoot "native\shaders\gpu_sdf_overlay.frag.glsl"
$cameraLumaDiagnosticShaderPath = Join-Path $appRoot "native\shaders\camera_luma_diagnostic.comp.glsl"
$environmentDepthParticlesComputeShaderPath = Join-Path $appRoot "native\shaders\environment_depth_particles_synthetic.comp.glsl"
$environmentDepthParticlesMetaComputeShaderPath = Join-Path $appRoot "native\shaders\environment_depth_particles_meta.comp.glsl"
$environmentDepthParticlesVertexShaderPath = Join-Path $appRoot "native\shaders\environment_depth_particles.vert.glsl"
$environmentDepthParticlesFragmentShaderPath = Join-Path $appRoot "native\shaders\environment_depth_particles.frag.glsl"
$xrVulkanPath = Join-Path $appRoot "native\src\xr_vulkan.rs"
$buildPath = Join-Path $PSScriptRoot "Build-NativeRendererAndroid.ps1"
$checkAllPath = Join-Path $PSScriptRoot "check_all.ps1"
$runtimeProfileToolPath = Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1"
$permissionPregrantToolPath = Join-Path $PSScriptRoot "Grant-NativeRendererPermissions.ps1"
$runtimeEvidenceToolPath = Join-Path $PSScriptRoot "Test-NativeRendererRuntimeEvidence.ps1"
$runtimeSmokeToolPath = Join-Path $PSScriptRoot "Invoke-NativeRendererReplaySmoke.ps1"
$environmentDepthMotionProofToolPath = Join-Path $PSScriptRoot "Invoke-NativeRendererEnvironmentDepthMotionProof.ps1"
$fixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-hwb-blur-sdf-public.plan.json"
$recordedHandReplayPath = Join-Path $repoRoot "fixtures\native-renderer\recorded-hand-replay-public-shape.json"
$runtimeEvidenceFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-replay-visual-proof.logcat.txt"
$liveHandDiagnosticPendingFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-live-hand-visual-diagnostic-pending.logcat.txt"
$environmentDepthParticlesEvidenceFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-meta-environment-depth-particles.logcat.txt"
$runtimeEvidenceDamagedPath = Join-Path $repoRoot "fixtures\damaged\native-renderer-replay-visual-missing-mesh.logcat.txt"
$runtimeEvidenceDamagedPerformancePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-replay-performance-budget-miss.logcat.txt"
$liveHandPrematureAcceptanceDamagedPath = Join-Path $repoRoot "fixtures\damaged\native-renderer-live-hand-visual-premature-acceptance.logcat.txt"
$replayVisualProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-replay-visual-proof.profile.json"
$directHwbCameraQualityProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality.profile.json"
$directHwbCameraQualityBt601UnormProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json"
$directHwbLowNoise30ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-30.profile.json"
$directHwbLowNoiseRecord30ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-record-30.profile.json"
$directHwbLowLatency60ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-latency-60.profile.json"
$directHwbHoldSyncProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync.profile.json"
$directHwbHoldSyncReader6ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json"
$directHwbHoldSyncReader8ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json"
$directHwb1280x960ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-1280x960.profile.json"
$hwbPeripheralStretchProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-hwb-peripheral-stretch.profile.json"
$liveHandVisualDiagnosticProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-live-hand-visual-diagnostic.profile.json"
$nativePassthroughGraftOnlyProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-graft-only.profile.json"
$nativePassthroughHandsAndGraftsProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-hands-and-grafts.profile.json"
$solidBlackHandsAndGraftsProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-solid-black-hands-and-grafts.profile.json"
$solidBlackOpenXrHandsAnchorParticlesProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json"
$environmentDepthStatusProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-environment-depth-status.profile.json"
$environmentDepthNativePassthroughParticlesProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-environment-depth-particles.profile.json"
$environmentDepthNativePassthroughMetaParticlesProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json"
$environmentDepthNativePassthroughMetaParticlesLayer1ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json"
$environmentDepthNativePassthroughMetaParticlesLowCapacityProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json"
$environmentDepthNativePassthroughMetaParticlesDebugColorsProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json"
$environmentDepthLayer0ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-layer0.profile.json"
$environmentDepthLayer1ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-layer1.profile.json"
$environmentDepthRawDepthDebugProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-raw-depth-debug.profile.json"
$environmentDepthLocalSpaceProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-local-space.profile.json"
$environmentDepthStageSpaceProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-stage-space.profile.json"
$environmentDepthCapacity65536ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-capacity-65536.profile.json"
$environmentDepthStride8ProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-stride-8.profile.json"
$environmentDepthHandRemovalProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-hand-removal.profile.json"
$environmentDepthLocalSurfelsProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-local-surfels.profile.json"
$environmentDepthGlobalSurfacesProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-global-surfaces.profile.json"
$environmentDepthHybridSurfacesProfilePath = Join-Path $repoRoot "fixtures\runtime-profiles\quest-native-renderer-envdepth-hybrid-surfaces.profile.json"
$environmentDepthHighRateJsonDamagedProfilePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-environment-depth-high-rate-json.profile.json"
$environmentDepthInvalidRangeDamagedProfilePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-environment-depth-invalid-range.profile.json"
$environmentDepthInvalidCapacityDamagedProfilePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-environment-depth-invalid-capacity.profile.json"
$environmentDepthInvalidDepthUnitsPolicyDamagedProfilePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-environment-depth-invalid-depth-units-policy.profile.json"
$environmentDepthInvalidSurfaceSupportDamagedProfilePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-environment-depth-invalid-surface-support.profile.json"

foreach ($path in @($manifestPath, $readmePath, $nativeCargoPath, $nativeBuildRsPath, $nativeLibPath, $androidEventsPath, $nativeCameraPath, $nativeCameraMetadataPath, $nativeCameraProfilesPath, $nativeCameraReaderSelectionPath, $acameraSysPath, $cameraProjectionPath, $cameraProjectionMetadataPath, $environmentDepthGeometryPath, $environmentDepthParticlesPath, $openxrEnvironmentDepthPath, $guideBlurGraphPath, $recordedHandReplayModulePath, $liveHandCompactPath, $nativeRendererOptionsPath, $nativeRendererTimingPath, $privateExtensionSlotPath, $handMeshGraftPath, $gpuHandMeshVisualPath, $gpuMeshReplayPath, $gpuSdfFieldPath, $cameraProjectionFragmentPath, $guideBlurDownsampleFragmentPath, $guideBlurFragmentPath, $guideProjectionFragmentPath, $handMeshVisualVertexPath, $handMeshVisualFragmentPath, $gpuHandSkinningShaderPath, $gpuSdfFieldShaderPath, $gpuSdfTileBinsShaderPath, $gpuSdfOverlayShaderPath, $cameraLumaDiagnosticShaderPath, $environmentDepthParticlesComputeShaderPath, $environmentDepthParticlesMetaComputeShaderPath, $environmentDepthParticlesVertexShaderPath, $environmentDepthParticlesFragmentShaderPath, $xrVulkanPath, $buildPath, $checkAllPath, $runtimeProfileToolPath, $permissionPregrantToolPath, $runtimeEvidenceToolPath, $runtimeSmokeToolPath, $environmentDepthMotionProofToolPath, $fixturePath, $recordedHandReplayPath, $runtimeEvidenceFixturePath, $liveHandDiagnosticPendingFixturePath, $environmentDepthParticlesEvidenceFixturePath, $runtimeEvidenceDamagedPath, $runtimeEvidenceDamagedPerformancePath, $liveHandPrematureAcceptanceDamagedPath, $replayVisualProfilePath, $directHwbCameraQualityProfilePath, $directHwbCameraQualityBt601UnormProfilePath, $directHwbLowNoise30ProfilePath, $directHwbLowNoiseRecord30ProfilePath, $directHwbLowLatency60ProfilePath, $directHwbHoldSyncProfilePath, $directHwbHoldSyncReader6ProfilePath, $directHwbHoldSyncReader8ProfilePath, $directHwb1280x960ProfilePath, $hwbPeripheralStretchProfilePath, $liveHandVisualDiagnosticProfilePath, $nativePassthroughGraftOnlyProfilePath, $nativePassthroughHandsAndGraftsProfilePath, $solidBlackHandsAndGraftsProfilePath, $solidBlackOpenXrHandsAnchorParticlesProfilePath, $environmentDepthStatusProfilePath, $environmentDepthNativePassthroughParticlesProfilePath, $environmentDepthNativePassthroughMetaParticlesProfilePath, $environmentDepthNativePassthroughMetaParticlesLayer1ProfilePath, $environmentDepthNativePassthroughMetaParticlesLowCapacityProfilePath, $environmentDepthNativePassthroughMetaParticlesDebugColorsProfilePath, $environmentDepthLayer0ProfilePath, $environmentDepthLayer1ProfilePath, $environmentDepthRawDepthDebugProfilePath, $environmentDepthLocalSpaceProfilePath, $environmentDepthStageSpaceProfilePath, $environmentDepthCapacity65536ProfilePath, $environmentDepthStride8ProfilePath, $environmentDepthHandRemovalProfilePath, $environmentDepthLocalSurfelsProfilePath, $environmentDepthGlobalSurfacesProfilePath, $environmentDepthHybridSurfacesProfilePath, $environmentDepthHighRateJsonDamagedProfilePath, $environmentDepthInvalidRangeDamagedProfilePath, $environmentDepthInvalidCapacityDamagedProfilePath, $environmentDepthInvalidDepthUnitsPolicyDamagedProfilePath, $environmentDepthInvalidSurfaceSupportDamagedProfilePath)) {
    if (-not (Test-Path $path)) {
        throw "Missing native renderer Android file: $path"
    }
}

$manifest = Get-Content -Raw -Path $manifestPath
$readme = Get-Content -Raw -Path $readmePath
$nativeCargo = Get-Content -Raw -Path $nativeCargoPath
$nativeBuildRs = Get-Content -Raw -Path $nativeBuildRsPath
$nativeLib = Get-Content -Raw -Path $nativeLibPath
$androidEvents = Get-Content -Raw -Path $androidEventsPath
$nativeCamera = Get-Content -Raw -Path $nativeCameraPath
$nativeCameraMetadata = Get-Content -Raw -Path $nativeCameraMetadataPath
$nativeCameraProfiles = Get-Content -Raw -Path $nativeCameraProfilesPath
$nativeCameraReaderSelection = Get-Content -Raw -Path $nativeCameraReaderSelectionPath
$acameraSys = Get-Content -Raw -Path $acameraSysPath
$cameraProjection = Get-Content -Raw -Path $cameraProjectionPath
$cameraProjectionMetadata = Get-Content -Raw -Path $cameraProjectionMetadataPath
$environmentDepthGeometry = Get-Content -Raw -Path $environmentDepthGeometryPath
$environmentDepthParticles = Get-Content -Raw -Path $environmentDepthParticlesPath
$openxrEnvironmentDepth = Get-Content -Raw -Path $openxrEnvironmentDepthPath
$guideBlurGraph = Get-Content -Raw -Path $guideBlurGraphPath
$recordedHandReplay = Get-Content -Raw -Path $recordedHandReplayPath
$recordedHandReplayModule = Get-Content -Raw -Path $recordedHandReplayModulePath
$liveHandCompact = Get-Content -Raw -Path $liveHandCompactPath
$nativeRendererOptions = Get-Content -Raw -Path $nativeRendererOptionsPath
$nativeRendererTiming = Get-Content -Raw -Path $nativeRendererTimingPath
$privateExtensionSlot = Get-Content -Raw -Path $privateExtensionSlotPath
$handMeshGraft = Get-Content -Raw -Path $handMeshGraftPath
$gpuHandMeshVisual = Get-Content -Raw -Path $gpuHandMeshVisualPath
$gpuMeshReplay = Get-Content -Raw -Path $gpuMeshReplayPath
$gpuSdfField = Get-Content -Raw -Path $gpuSdfFieldPath
$cameraProjectionFragment = Get-Content -Raw -Path $cameraProjectionFragmentPath
$guideBlurDownsampleFragment = Get-Content -Raw -Path $guideBlurDownsampleFragmentPath
$guideBlurFragment = Get-Content -Raw -Path $guideBlurFragmentPath
$guideProjectionFragment = Get-Content -Raw -Path $guideProjectionFragmentPath
$handMeshVisualVertex = Get-Content -Raw -Path $handMeshVisualVertexPath
$handMeshVisualFragment = Get-Content -Raw -Path $handMeshVisualFragmentPath
$gpuHandSkinningShader = Get-Content -Raw -Path $gpuHandSkinningShaderPath
$gpuSdfFieldShader = Get-Content -Raw -Path $gpuSdfFieldShaderPath
$gpuSdfTileBinsShader = Get-Content -Raw -Path $gpuSdfTileBinsShaderPath
$gpuSdfOverlayShader = Get-Content -Raw -Path $gpuSdfOverlayShaderPath
$cameraLumaDiagnosticShader = Get-Content -Raw -Path $cameraLumaDiagnosticShaderPath
$environmentDepthParticlesComputeShader = Get-Content -Raw -Path $environmentDepthParticlesComputeShaderPath
$environmentDepthParticlesMetaComputeShader = Get-Content -Raw -Path $environmentDepthParticlesMetaComputeShaderPath
$environmentDepthParticlesVertexShader = Get-Content -Raw -Path $environmentDepthParticlesVertexShaderPath
$environmentDepthParticlesFragmentShader = Get-Content -Raw -Path $environmentDepthParticlesFragmentShaderPath
$xrVulkan = Get-Content -Raw -Path $xrVulkanPath
$buildScriptText = Get-Content -Raw -Path $buildPath
$checkAllText = Get-Content -Raw -Path $checkAllPath
$runtimeProfileToolText = Get-Content -Raw -Path $runtimeProfileToolPath
$permissionPregrantToolText = Get-Content -Raw -Path $permissionPregrantToolPath
$runtimeEvidenceToolText = Get-Content -Raw -Path $runtimeEvidenceToolPath
$runtimeSmokeToolText = Get-Content -Raw -Path $runtimeSmokeToolPath
$environmentDepthMotionProofToolText = Get-Content -Raw -Path $environmentDepthMotionProofToolPath
$runtimeEvidenceFixtureText = Get-Content -Raw -Path $runtimeEvidenceFixturePath
$liveHandDiagnosticPendingFixtureText = Get-Content -Raw -Path $liveHandDiagnosticPendingFixturePath
$environmentDepthParticlesEvidenceFixtureText = Get-Content -Raw -Path $environmentDepthParticlesEvidenceFixturePath
$replayVisualProfile = Get-Content -Raw -Path $replayVisualProfilePath
$directHwbCameraQualityProfile = Get-Content -Raw -Path $directHwbCameraQualityProfilePath
$directHwbCameraQualityBt601UnormProfile = Get-Content -Raw -Path $directHwbCameraQualityBt601UnormProfilePath
$directHwbLowNoise30Profile = Get-Content -Raw -Path $directHwbLowNoise30ProfilePath
$directHwbLowNoiseRecord30Profile = Get-Content -Raw -Path $directHwbLowNoiseRecord30ProfilePath
$directHwbLowLatency60Profile = Get-Content -Raw -Path $directHwbLowLatency60ProfilePath
$directHwbHoldSyncProfile = Get-Content -Raw -Path $directHwbHoldSyncProfilePath
$directHwbHoldSyncReader6Profile = Get-Content -Raw -Path $directHwbHoldSyncReader6ProfilePath
$directHwbHoldSyncReader8Profile = Get-Content -Raw -Path $directHwbHoldSyncReader8ProfilePath
$directHwb1280x960Profile = Get-Content -Raw -Path $directHwb1280x960ProfilePath
$hwbPeripheralStretchProfile = Get-Content -Raw -Path $hwbPeripheralStretchProfilePath
$liveHandVisualDiagnosticProfile = Get-Content -Raw -Path $liveHandVisualDiagnosticProfilePath
$nativePassthroughGraftOnlyProfile = Get-Content -Raw -Path $nativePassthroughGraftOnlyProfilePath
$nativePassthroughHandsAndGraftsProfile = Get-Content -Raw -Path $nativePassthroughHandsAndGraftsProfilePath
$solidBlackHandsAndGraftsProfile = Get-Content -Raw -Path $solidBlackHandsAndGraftsProfilePath
$solidBlackOpenXrHandsAnchorParticlesProfile = Get-Content -Raw -Path $solidBlackOpenXrHandsAnchorParticlesProfilePath
$environmentDepthStatusProfile = Get-Content -Raw -Path $environmentDepthStatusProfilePath
$environmentDepthNativePassthroughParticlesProfile = Get-Content -Raw -Path $environmentDepthNativePassthroughParticlesProfilePath
$environmentDepthNativePassthroughMetaParticlesProfile = Get-Content -Raw -Path $environmentDepthNativePassthroughMetaParticlesProfilePath
$environmentDepthNativePassthroughMetaParticlesLayer1Profile = Get-Content -Raw -Path $environmentDepthNativePassthroughMetaParticlesLayer1ProfilePath
$environmentDepthNativePassthroughMetaParticlesLowCapacityProfile = Get-Content -Raw -Path $environmentDepthNativePassthroughMetaParticlesLowCapacityProfilePath
$environmentDepthNativePassthroughMetaParticlesDebugColorsProfile = Get-Content -Raw -Path $environmentDepthNativePassthroughMetaParticlesDebugColorsProfilePath
$environmentDepthLayer0Profile = Get-Content -Raw -Path $environmentDepthLayer0ProfilePath
$environmentDepthLayer1Profile = Get-Content -Raw -Path $environmentDepthLayer1ProfilePath
$environmentDepthRawDepthDebugProfile = Get-Content -Raw -Path $environmentDepthRawDepthDebugProfilePath
$environmentDepthLocalSpaceProfile = Get-Content -Raw -Path $environmentDepthLocalSpaceProfilePath
$environmentDepthStageSpaceProfile = Get-Content -Raw -Path $environmentDepthStageSpaceProfilePath
$environmentDepthCapacity65536Profile = Get-Content -Raw -Path $environmentDepthCapacity65536ProfilePath
$environmentDepthStride8Profile = Get-Content -Raw -Path $environmentDepthStride8ProfilePath
$environmentDepthHandRemovalProfile = Get-Content -Raw -Path $environmentDepthHandRemovalProfilePath
$environmentDepthLocalSurfelsProfile = Get-Content -Raw -Path $environmentDepthLocalSurfelsProfilePath
$environmentDepthGlobalSurfacesProfile = Get-Content -Raw -Path $environmentDepthGlobalSurfacesProfilePath
$environmentDepthHybridSurfacesProfile = Get-Content -Raw -Path $environmentDepthHybridSurfacesProfilePath
$environmentDepthHighRateJsonDamagedProfile = Get-Content -Raw -Path $environmentDepthHighRateJsonDamagedProfilePath
$environmentDepthInvalidRangeDamagedProfile = Get-Content -Raw -Path $environmentDepthInvalidRangeDamagedProfilePath
$environmentDepthInvalidCapacityDamagedProfile = Get-Content -Raw -Path $environmentDepthInvalidCapacityDamagedProfilePath
$environmentDepthInvalidDepthUnitsPolicyDamagedProfile = Get-Content -Raw -Path $environmentDepthInvalidDepthUnitsPolicyDamagedProfilePath
$environmentDepthInvalidSurfaceSupportDamagedProfile = Get-Content -Raw -Path $environmentDepthInvalidSurfaceSupportDamagedProfilePath
$fixture = Get-Content -Raw -Path $fixturePath

if ($manifest -notmatch 'package="io\.github\.mesmerprism\.rustyquest\.native_renderer"') {
    throw "Native renderer Android manifest has the wrong package."
}
foreach ($permission in @(
    'android\.permission\.CAMERA',
    'com\.oculus\.permission\.HAND_TRACKING',
    'horizonos\.permission\.HEADSET_CAMERA',
    'horizonos\.permission\.SPATIAL_CAMERA',
    'horizonos\.permission\.USE_SCENE',
    'org\.khronos\.openxr\.permission\.OPENXR',
    'org\.khronos\.openxr\.permission\.OPENXR_SYSTEM'
)) {
    if ($manifest -notmatch $permission) {
        throw "Native renderer Android manifest missing permission: $permission"
    }
}
foreach ($feature in @(
    'android\.hardware\.vr\.headtracking',
    'com\.oculus\.feature\.PASSTHROUGH',
    'oculus\.software\.handtracking',
    'android\.hardware\.camera2\.full'
)) {
    if ($manifest -notmatch $feature) {
        throw "Native renderer Android manifest missing feature: $feature"
    }
}
if ($manifest -notmatch 'com\.oculus\.intent\.category\.VR') {
    throw "Native renderer Android manifest does not expose the Quest VR launcher category."
}
if ($manifest -notmatch 'android\.app\.NativeActivity' -or $manifest -notmatch 'android\.app\.lib_name' -or $manifest -notmatch 'rusty_quest_native_renderer') {
    throw "Native renderer Android manifest must use Rust NativeActivity and the Rust native library."
}
if ($manifest -notmatch 'android:hasCode="false"') {
    throw "Native renderer Android manifest must declare hasCode=false for the no-dex NativeActivity APK."
}
if ($manifest -notmatch 'org\.khronos\.openxr\.runtime_broker') {
    throw "Native renderer Android manifest does not query the OpenXR runtime broker."
}

foreach ($token in @(
    'rusty\.quest\.native_renderer_plan\.v1',
    'quest-native-openxr-vulkan',
    'camera2-ahardwarebuffer-vulkan-external',
    'combined-immutable-sampler-ycbcr-conversion',
    'peripheral-stretch-border',
    'rusty\.optics\.peripheral_stretch_border\.layer\.v1'
)) {
    if ($fixture -notmatch $token -and $nativeLib -notmatch $token) {
        throw "Native renderer public plan path missing token: $token"
    }
}
if ($fixture -notmatch '"left": "50"' -or $fixture -notmatch '"right": "51"') {
    throw "Native renderer plan fixture does not name camera ids 50 and 51."
}
foreach ($token in @(
    'rusty\.quest\.native_renderer\.recorded_hand_replay_source\.v1',
    'public-recorded-hand-topology-shape',
    'openxr-fb-handmesh-v1-j26-v1360-i6942',
    'bind-mesh-plus-compact-joint-frame',
    '"topology_vertex_count": 1360'
)) {
    if ($recordedHandReplay -notmatch $token) {
        throw "Native renderer recorded hand replay fixture missing token: $token"
    }
}

foreach ($token in @(
    'RUSTY_QUEST_NATIVE_RENDERER',
    'android_main',
    'android_on_create',
    'android_activity::AndroidApp',
    'validate_native_renderer_plan',
    'rustNativeActivity=true',
    'javaPackaged=false',
    'requestPermissions',
    'rust-native-jni',
    'publicEffectLayers=blur-guide,peripheral-stretch-border',
    'privatePayloads=false',
    'minimal-projection-layer',
    'recordedHandReplayRequested=true',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1',
    'openxrProjectionLayer=runtime-submit',
    'openxrSubmitReady=false',
    'vulkanExternalImportReady=false'
)) {
    if ($nativeLib -notmatch $token) {
        throw "Rust NativeActivity scaffold missing token: $token"
    }
}

foreach ($token in @(
    'pump_activity_events',
    'MainEvent::InputAvailable',
    'input_events_iter',
    'InputStatus::Unhandled',
    'android-input',
    'event=drain'
)) {
    if ($androidEvents -notmatch $token) {
        throw "Rust NativeActivity input pump missing token: $token"
    }
}
if ($nativeLib -notmatch 'mod android_events' -or $xrVulkan -notmatch 'pump_activity_events') {
    throw "Rust NativeActivity input pump is not wired into both app and OpenXR loops."
}

foreach ($token in @(
    'RecordedHandReplaySummary',
    'RecordedHandReplaySet',
    'recorded_hand_replay_source\.v1',
    'recorded_hand_replay_source\.json',
    'RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR',
    'rig_json',
    'clip_jsonl',
    'validation_mesh_jsonl',
    'normalize_xy_points',
    'recordedInputEquivalent=true',
    'validationInputShape=bind-mesh-plus-compact-joint-frame',
    'vertex_blend_indices',
    'vertex_blend_weights',
    'bind_joint_sources',
    'parse_skinning_frames',
    'runtime_joint_poses',
    'tip_length_rows',
    'compactJointPoseUploadPerFrame=true',
    'gpuSkinningPayloadReady',
    'skinningFrameCount',
    'recordedHandReplayHandSetReady',
    'recordedHandReplayRightHandDistinct',
    'recordedHandReplayRightHandedness',
    'recordedHandReplayRightGpuSkinningPayloadReady',
    'meshVisualFrameCount',
    'meshComponentCount',
    'meshComponentRank0=hand-inside',
    'meshComponentRank1=hand-back',
    'meshComponentRank2=wrist-cap'
)) {
    if ("$recordedHandReplayModule`n$nativeBuildRs`n$xrVulkan" -notmatch $token) {
        throw "Native recorded hand replay route missing token: $token"
    }
}

foreach ($token in @(
    'mod native_renderer_options',
    'mod gpu_hand_mesh_visual',
    'GpuHandMeshVisualRenderer',
    'HandMeshVisualEyeProjection',
    'GpuHandMeshVisualFrameStats',
    'GpuHandMeshVisualFrameSetStats',
    'HandMeshVisualDiagnosticSettings',
    'hand_mesh_visual.vert.glsl',
    'hand_mesh_visual.frag.glsl',
    'handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw',
    'recordedSkinnedMeshFrameSource=compact_joint_gpu_skinning',
    'animatedHandMeshVisualReady',
    'animatedHandMeshVisualVisible',
    'handMeshVisualDiagnosticEnabled',
    'handMeshVisualDiagnosticOffsetUv',
    'liveHandMeshVisualAcceptance',
    'gpuTriangleDraw=true',
    'cpuProjection=false',
    'validationMeshUploadPerFrame=false',
    'skinnedPositionBufferResident=true',
    'skinnedPositionBufferCoordinateSpace=openxr-reference-space',
    'handMeshVisualProjectionSpace',
    'handMeshVisualClipY',
    'openxr-y-up-to-vulkan-positive-viewport',
    'liveHandMeshTargetLocalNormalized=false',
    'world_to_eye_clip',
    'screen_y = 1.0 -',
    'fov_tangents',
    'gpuNormalDepthComponentShading=true',
    'gpuNormalDepthComponentShadingMode=subtle',
    'handMeshCompactInputSource',
    'handMeshVisualSourceHandedness',
    'handMeshVisualSecondarySourceHandedness',
    'handMeshVisualMaterial=continuous-single-surface',
    'handMeshVisualSmoothSurfaceShading=true',
    'handMeshVisualComponentColoring=false',
    'HandMeshGraftParams',
    'prepare_graft_copies',
    'record_graft_overlay_eye',
    'handMeshGraftCopiesEnabled',
    'handMeshGraftCopiesVisible',
    'handMeshGraftScaleMultiplier',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'handMeshRealHandsVisible',
    'nativePassthroughRealHandMeshVisible',
    'handMeshGraftCopyPath=post-skinning-instanced-source-mesh-to-opposite-fingertips',
    'handMeshGraftSourceAnimationReuse=true',
    'handMeshGraftScaleBasis=source-wrist-radius-to-target-distal-radius',
    'target_position_scale',
    'gl_InstanceIndex',
    'liveHandMeshVisualBothHandsVisible',
    'handMeshVisualGpuSkinnedHandCount',
    'handMeshVisualPrimaryHand',
    'handMeshVisualSecondaryHand',
    'cmd_bind_descriptor_sets',
    'cmd_draw',
    'STORAGE_BUFFER',
    'PipelineBindPoint::GRAPHICS'
)) {
    if ("$nativeBuildRs`n$nativeLib`n$recordedHandReplayModule`n$nativeRendererOptions`n$gpuHandMeshVisual`n$handMeshVisualVertex`n$handMeshVisualFragment`n$xrVulkan" -notmatch $token) {
        throw "Native GPU hand mesh visual route missing token: $token"
    }
}

foreach ($token in @(
    'mod live_hand_compact',
    'LiveHandCompactInput',
    'LiveHandCompactStats',
    'LiveHandCompactFrameSet',
    'XR_EXT_hand_tracking',
    'create_hand_tracker',
    'locate_hand_joints',
    'supports_hand_tracking',
    'liveMetaHandCompactInputReady',
    'liveMetaHandCompactFrameReady',
    'liveMetaHandTrackingExtensionEnabled',
    'liveMetaHandTrackingSystemSupported',
    'liveMetaHandCompactUploadEquivalent=true',
    'liveMetaHandGpuInputPath=recorded-compatible-compact-joint-pose-tip-length',
    'liveMetaHandRuntimeJointPoseCount',
    'liveMetaHandTipLengthCount',
    'liveMetaHandCompactFrameUploadBytes',
    'liveMetaHandUsingBoth',
    'liveMetaHandActiveHandCount',
    'liveMetaHandVisualizableHandCount',
    'runtime_joint_poses',
    'tip_length_rows'
)) {
    if ("$nativeLib`n$liveHandCompact`n$xrVulkan" -notmatch $token) {
        throw "Native live Meta hand compact route missing token: $token"
    }
}

foreach ($token in @(
    'GpuMeshReplayResources',
    'GpuMeshReplayStats',
    'create_buffer',
    'STORAGE_BUFFER',
    'HOST_VISIBLE',
    'sourceMeshBuffersResident',
    'gpuMeshPath=native-vulkan-storage-buffer',
    'sourceMeshToSdfKernel=false',
    'cpuSdfPerFrame=false'
)) {
    if ("$gpuMeshReplay`n$xrVulkan" -notmatch $token) {
        throw "Native GPU mesh replay boundary missing token: $token"
    }
}

foreach ($token in @(
    'mod gpu_sdf_field',
    'GpuSdfFieldRenderer',
    'GpuSdfFieldFrameStats',
    'gpu_hand_skinning.comp.glsl',
    'gpu_sdf_field.comp.glsl',
    'gpu_sdf_tile_bins.comp.glsl',
    'gpu_sdf_overlay.frag.glsl',
    'create_compute_pipelines',
    'PipelineBindPoint::COMPUTE',
    'cmd_dispatch',
    'sdfFieldSource=recorded-compact-joint-skinned-mesh-gpu-field',
    'legacySdfFieldSource=recorded-validation-mesh-target-space-gpu-field',
    'sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field',
    'legacySdfComputePath=native-vulkan-compute-recorded-validation-mesh-sdf-field',
    'dynamicSdfReady=\{\}',
    'sdfVisualEffectVisible=\{\}',
    'skinning_ready: true',
    'field_update_dispatched',
    'field_reused',
    'gpuSdfFieldReady',
    'gpuSdfOverlayVisible',
    'gpuSdfMeshVertexCount',
    'gpuSdfMeshTriangleCount',
    'sdfUpdateCadenceFrames',
    'sdfFieldUpdateDispatched',
    'sdfFieldReused',
    'sdfFieldCacheHits',
    'sdfCompactInputSource',
    'liveSdfVisualAcceptance',
    'sdfTriangleBinsReady=false',
    'sdfNarrowBandReady=false',
    'sdfNarrowBandMode=tile-local-triangle-bin-band-cull',
    'debug.rustyquest.native_renderer.sdf.update_period_frames',
    'gpuSdfRuntimeJointPoseBufferBytes',
    'gpuSdfTipLengthBufferBytes',
    'gpuSdfCompactJointFrameUploadBytes',
    'gpuSdfJointMatrixBufferBytes',
    'gpuSdfSkinnedPositionBufferBytes',
    'cpuSdfPerFrame=false',
    'meshToSdfKernel=\{\}',
    'targetSpaceMeshToSdfKernelAvailable=true',
    'SkinnedWorldPositions',
    'target_space_uv',
    'sdfProjectionInputCoordinateSpace=openxr-reference-space',
    'sdfProjectionOutputCoordinateSpace=metadata-target-screen-uv',
    'fullSkinnedMeshSdfReady=\{\}',
    'compactJointSkinningKernel=\{\}',
    'jointMatrixSkinningKernel=false',
    'jointMatrixUploadPerFrame=false',
    'compactJointPoseUploadPerFrame=true',
    'sourceMeshToSdfKernel=\{\}',
    'record_sdf_field_update'
)) {
    if ("$nativeBuildRs`n$nativeLib`n$nativeCamera`n$nativeRendererOptions`n$gpuSdfField`n$gpuHandSkinningShader`n$gpuSdfFieldShader`n$gpuSdfTileBinsShader`n$gpuSdfOverlayShader`n$xrVulkan" -notmatch $token) {
        throw "Native GPU SDF field route missing token: $token"
    }
}

foreach ($token in @(
    'SDF_TILE_GRID_WIDTH',
    'SDF_MAX_TRIANGLES_PER_TILE',
    'coherent buffer SdfTileHeaders',
    'writeonly buffer SdfTileIndices',
    'writeonly buffer SdfTriangleBounds',
    'atomicAdd',
    'triangle_bounds\.bounds',
    'tile_indices\.indices',
    'band_radius',
    'min_tile',
    'max_tile'
)) {
    if ($gpuSdfTileBinsShader -notmatch $token) {
        throw "Native GPU SDF tile-bin shader missing token: $token"
    }
}
foreach ($token in @(
    'readonly buffer SdfTileHeaders',
    'readonly buffer SdfTileIndices',
    'readonly buffer SdfTriangleBounds',
    'tile_headers\.headers',
    'tile_indices\.indices',
    'triangle_bounds\.bounds',
    'tile_triangle_count',
    'for \(uint slot = 0u; slot < tile_triangle_count; slot\+\+\)'
)) {
    if ($gpuSdfFieldShader -notmatch $token) {
        throw "Native GPU SDF field shader missing tile-local token: $token"
    }
}

foreach ($token in @(
    'camera_projection_metadata',
    'rusty\.quest\.native_renderer\.camera_projection_metadata\.v1',
    'rusty\.optics\.target_screen_footprint\.v1',
    'target-local-raster',
    'RUSTY_QUEST_NATIVE_RENDERER_CAMERA_LEFT_TARGET_SCREEN_UV_RECT',
    'debug\.rustyquest\.native_renderer\.camera\.left\.target\.screen\.uv\.rect',
    'targetCoordinateSpace=display-eye-screen-uv',
    'metadataDrivenTargetFootprint=true',
    'sourceSampleYFlip',
    'sourceSampleTransform',
    'sourceSampleTransformStage=post-homography-pre-texture-sample'
)) {
    if ("$nativeLib`n$cameraProjection`n$cameraProjectionMetadata`n$xrVulkan" -notmatch $token) {
        throw "Native camera projection metadata route missing token: $token"
    }
}
foreach ($token in @(
    'target_rect',
    'local_uv',
    'mix\(local_uv\.y, 1\.0 - local_uv\.y, flip_y\)',
    'discard',
    'border_color'
)) {
    if ($cameraProjectionFragment -notmatch $token) {
        throw "Native camera projection shader missing metadata target token: $token"
    }
}
if ($cameraProjectionFragment -match 'vec2\(v_uv\.x,\s*1\.0\s*-\s*v_uv\.y\)') {
    throw "Native camera projection shader must not hard-code full-screen source Y flip."
}

foreach ($token in @(
    'mod guide_blur_graph',
    'GuideBlurGraphRenderer',
    'GuideBlurGraphFrameStats',
    'guide_blur_downsample.frag.glsl',
    'guide_blur_5tap.frag.glsl',
    'guide_projection.frag.glsl',
    'guideGraphPath=low-resolution-two-phase-5tap-blur',
    'guideGraphReady',
    'guideGraphDownsampleResolution=',
    'guideGraphHorizontalTaps',
    'guideGraphVerticalTaps',
    'guideGraphFinalProjectionSource=guide-texture',
    'guideGraphFinalExternalHwbSamples',
    'actualFinalExternalHwbSamples',
    'actualGuideTextureSamples',
    'cameraProjectionPath=metadata-target-guide-texture-final',
    'metadata-target-guide-texture-peripheral-stretch-final',
    'record_guide_graph_render',
    'record_guide_graph_cache_hit',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1'
)) {
    if ("$nativeBuildRs`n$nativeLib`n$nativeCamera`n$cameraProjection`n$guideBlurGraph`n$guideBlurDownsampleFragment`n$guideBlurFragment`n$guideProjectionFragment`n$xrVulkan" -notmatch $token) {
        throw "Native guide blur graph route missing token: $token"
    }
}
foreach ($token in @(
    'u_camera_left',
    'u_camera_right',
    'mix\(v_uv\.y, 1\.0 - v_uv\.y, flip_y\)'
)) {
    if ($guideBlurDownsampleFragment -notmatch $token) {
        throw "Native guide downsample shader missing token: $token"
    }
}
foreach ($token in @(
    'PROP_CAMERA_OUTPUT_MODE',
    'PROP_CAMERA_YCBCR_MODE',
    'PROP_CAMERA_RESOLUTION_PROFILE',
    'PROP_CAMERA_READER_MAX_IMAGES',
    'PROP_CAMERA_QUALITY_PROFILE',
    'PROP_CAMERA_SYNC_MODE',
    'PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED',
    'PROP_CAMERA_STEREO_PAIRING',
    'PROP_SWAPCHAIN_COLOR_FORMAT_MODE',
    'PROP_CAMERA_DIRECT_BORDER_OPACITY',
    'debug\.rustyquest\.native_renderer\.camera\.output',
    'debug\.rustyquest\.native_renderer\.camera\.ycbcr\.mode',
    'debug\.rustyquest\.native_renderer\.camera\.resolution',
    'debug\.rustyquest\.native_renderer\.camera\.reader_max_images',
    'debug\.rustyquest\.native_renderer\.camera\.quality_profile',
    'debug\.rustyquest\.native_renderer\.camera\.sync_mode',
    'debug\.rustyquest\.native_renderer\.camera\.luma_diagnostic\.enabled',
    'debug\.rustyquest\.native_renderer\.camera\.stereo_pairing',
    'debug\.rustyquest\.native_renderer\.swapchain\.color_format',
    'debug\.rustyquest\.native_renderer\.camera\.direct_border\.opacity',
    'NativeCameraOutputMode',
    'NativeCameraYcbcrMode',
    'NativeCameraResolutionProfile',
    'camera_reader_max_images',
    'NativeCameraQualityProfile',
    'NativeCameraSyncMode',
    'NativeSwapchainColorFormatMode',
    'forced-bt601-limited-cpuyuv-reference',
    'effectiveYcbcrModel=',
    'formatFeaturesRaw=',
    'chromaFilter=',
    'camera-request-profile',
    'camera_luma_diagnostic\.comp\.glsl',
    'camera_luma_diagnostic\.comp\.spv',
    'camera-luma-diagnostic',
    'cameraLumaDiagnosticRequested=',
    'cameraLumaDiagnosticReady=',
    'cameraLumaDiagnosticPath=native-vulkan-compute-direct-hwb-luma-range',
    'leftLumaMean=',
    'rightLumaHighFrequencyRatio=',
    'AImage_getDataSpace',
    'imageDataspace=',
    'imageDataspaceStatus=',
    'leftImageDataspace=',
    'rightImageDataspaceStatus=',
    'TEMPLATE_RECORD',
    'direct-low-noise-record-30',
    'template=',
    'template=record',
    'selectedAeFpsRange=',
    'nearest-supported',
    'ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS',
    'privateOutputMinFrameDurations=',
    'readerSelectionReason=',
    'readerMinFrameDurationNs=',
    'readerTargetFpsFeasible=',
    'camera-capture-result',
    'ACAMERA_SENSOR_TIMESTAMP',
    'ACAMERA_CONTROL_AE_STATE',
    'ACAMERA_CONTROL_AWB_STATE',
    'resultSensorTimestampNs=',
    'aeState=',
    'awbState=',
    'captureResultCorrelationStatus=',
    'captureResultDeltaNs=',
    'cameraCaptureResultCorrelationReady=',
    'ResultCorrelationStatus=',
    'scorecard_marker_fields\("left"\)',
    'scorecard_marker_fields\("right"\)',
    'cameraResolutionProfile=',
    'readerMaxImages=',
    'cameraSyncActive=',
    'NativeCameraStereoPairingPolicy',
    'nearest-timestamp',
    'latest-latest',
    'stereoPairingPolicy=',
    'stereoPairingProperty=',
    'AImageReader_acquireLatestImageAsync',
    'AImage_deleteAsync',
    'active-diagnostic-sync-fd-observed-vulkan-semaphore-pending',
    'async-acquire-fence-observed-vulkan-semaphore-pending',
    'acquireFenceFdPresent=',
    'gpu-frame-lease-tracked',
    'gpu-frame-lease-retired',
    'cacheEvictionApplied=',
    'cacheEvictionDeferred=',
    'inFlightSkipCount=',
    'protectedSkipCount=',
    'openxr-swapchain-format',
    'border_opacity',
    'cameraOutputMode=',
    'metadata-target-direct-hwb-forced',
    'directHwbProjectionDiagnostic',
    'privateLayerProjectionEnabled=false',
    'guideProjectionEnabled=false',
    'quest-native-renderer-direct-hwb-camera-quality\.profile\.json'
)) {
    if ("$nativeRendererOptions`n$nativeCamera`n$nativeCameraMetadata`n$nativeCameraProfiles`n$nativeCameraReaderSelection`n$acameraSys`n$cameraProjection`n$cameraProjectionFragment`n$cameraLumaDiagnosticShader`n$nativeBuildRs`n$xrVulkan`n$directHwbCameraQualityProfile`n$directHwbLowNoiseRecord30Profile" -notmatch $token) {
        throw "Native direct-HWB camera quality diagnostic route missing token: $token"
    }
}
foreach ($token in @(
    'layout\(local_size_x = 8, local_size_y = 8',
    'atomicAdd\(out_stats\.eye_stats',
    'atomicMax\(out_stats\.eye_stats',
    'quantized_luma',
    'u_camera_left',
    'u_camera_right'
)) {
    if ($cameraLumaDiagnosticShader -notmatch $token) {
        throw "Native camera luma diagnostic shader missing token: $token"
    }
}
foreach ($token in @(
    'u_source',
    'v_uv - 2\.0 \* step_uv',
    'rgb \* 0\.2'
)) {
    if ($guideBlurFragment -notmatch $token) {
        throw "Native guide 5-tap blur shader missing token: $token"
    }
}
foreach ($token in @(
    'NativeProjectionBorderStretchSettings',
    'debug\.rustyquest\.native_renderer\.processing\.layer',
    'debug\.rustyquest\.native_renderer\.projection\.border\.policy',
    'debug\.rustyquest\.native_renderer\.peripheral\.stretch\.inner\.blend\.uv',
    'peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band',
    'peripheralStretchProjectionExteriorMode=target-edge-stretch-with-inner-band-blend',
    'guideProjectionCoverage=full-eye-peripheral-stretch',
    'full_extent_scissor',
    'projection_area_rect_edge_uv',
    'peripheral_stretch_blend_weight',
    'target_stretch_effect_region'
)) {
    if ("$nativeLib`n$nativeRendererOptions`n$guideBlurGraph`n$guideProjectionFragment`n$xrVulkan`n$hwbPeripheralStretchProfile" -notmatch $token) {
        throw "Native peripheral stretch border route missing token: $token"
    }
}
foreach ($token in @(
    'u_guide',
    'target_rect',
    'discard',
    'border_color'
)) {
    if ($guideProjectionFragment -notmatch $token) {
        throw "Native guide projection shader missing token: $token"
    }
}
foreach ($token in @(
    'guide_blur_downsample\.frag\.glsl',
    'guide_blur_5tap\.frag\.glsl',
    'guide_projection\.frag\.glsl'
)) {
    if ($nativeBuildRs -notmatch $token) {
        throw "Native shader build script missing guide token: $token"
    }
}

foreach ($token in @(
    'ACameraManager_create',
    'ACameraManager_getCameraIdList',
    'ACameraManager_openCamera',
    'AImageReader_newWithUsage',
    'AIMAGE_FORMAT_PRIVATE',
    'AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE',
    'AImageReader_setImageListener',
    'AImage_getHardwareBuffer',
    'AHardwareBuffer_acquire',
    'AHardwareBuffer_describe',
    'AHardwareBuffer_getId',
    'AHardwareBuffer_release',
    'ACameraCaptureSession_setRepeatingRequest',
    'plan\.camera_source\.camera_ids\.left',
    'plan\.camera_source\.camera_ids\.right',
    'hwb-frame-acquired',
    'textureUpdateCadence=on-camera-frame',
    'releaseRetireCount',
    'descriptorShape=combined-immutable-sampler-ycbcr-conversion'
)) {
    if ($nativeCamera -notmatch $token -and $acameraSys -notmatch $token) {
        throw "Rust native camera scaffold missing token: $token"
    }
}

foreach ($token in @(
    'rusty-quest-native-renderer-contracts',
    'package = "rusty-quest-native-renderer"',
    'android-activity',
    'native-activity',
    'jni',
    'ndk-sys',
    'ash',
    'openxr',
    'crate-type = \["cdylib", "rlib"\]',
    'name = "rusty_quest_native_renderer"'
)) {
    if ($nativeCargo -notmatch $token) {
        throw "Rust native Cargo manifest missing token: $token"
    }
}

$xrVulkanTokens = @(
    'xr::Entry::load',
    'LoaderInitKHR',
    'InstanceCreateInfoAndroidKHR',
    'khr_android_create_instance',
    'khr_vulkan_enable2',
    'graphics_requirements::<xr::Vulkan>',
    'ash::Entry::load',
    'create_vulkan_instance',
    'vulkan_graphics_device',
    'external_memory_android_hardware_buffer',
    'sampler_ycbcr_conversion',
    'PhysicalDeviceSamplerYcbcrConversionFeatures',
    'combined-immutable-sampler-ycbcr-conversion',
    'vulkanExternalImportPrereqsReady',
    'openxrSubmitReady=false',
    'openxrSubmitReady=true',
    'vulkanExternalImportReady=false',
    'create_session::<xr::Vulkan>',
    'create_swapchain',
    'CompositionLayerProjection',
    'FrameCpuTimings',
    'cameraAcquireImportCpuMs',
    'guideGraphCpuMs',
    'liveHandLocateCpuMs',
    'handSdfPrepareCpuMs',
    'handMeshVisualCpuMs',
    'projectionCompositeCpuMs',
    'swapchainWaitCpuMs',
    'queueSubmitCpuMs',
    'openxrEndFrameCpuMs',
    'cpuTimingScope=host-recording-and-submit',
    'GpuTimestampTracker',
    'cmd_reset_query_pool',
    'cmd_write_timestamp',
    'get_query_pool_results',
    'gpu-timestamp-timing',
    'gpuTimestampQuerySupported',
    'gpuTimestampQueryReady',
    'cameraProjectionGpuMs',
    'guideGraphGpuMs',
    'handSdfGpuMs',
    'handMeshVisualGpuMs',
    'projectionCompositeGpuMs',
    'gpuTimingScope=vulkan-timestamp-query',
    'PrivateExtensionSlotRuntime',
    'private-extension-slot',
    'record_private_layer_invocation',
    'private_layer_payload_config.rs',
    'PRIVATE_LAYER_PAYLOAD_LINKED',
    'PRIVATE_LAYER_IMPLEMENTATION_PATH',
    '!PRIVATE_LAYER_PAYLOAD_LINKED',
    'privateLayerOutput=\{\}',
    'identity-public-abi-resource',
    'privateLayerVisualAcceptance=\{\}',
    'not-applicable-public-noop',
    'recordedHandReplayVisible=\{\}',
    'animatedHandMeshVisualReady=pending',
    'compactJointOverlayDefault=false',
    'compactJointOverlayVisible=false',
    'handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw',
    'CompactHandInputSourceMode',
    'recorded-replay-visual-proof',
    'NativeRendererRenderMode',
    'debug.rustyquest.native_renderer.render.mode',
    'native-passthrough-graft-only',
    'solid-black-hands-and-grafts',
    'solid-black-openxr-hands-anchor-particles',
    'customStereoProjectionEnabled',
    'nativePassthroughRequested',
    'solidBlackBackground',
    'openxrDefaultHandVisualRequested',
    'requests_openxr_default_hand_visual',
    'customHandMeshVisualRequested',
    'cameraRuntimeMode=',
    'cameraProjectionPath=',
    'skipped-native-passthrough',
    'disabled-native-passthrough-graft-only',
    'skipped-solid-black-hands-and-grafts',
    'disabled-solid-black-hands-and-grafts',
    'skipped-solid-black-openxr-hands-anchor-particles',
    'disabled-solid-black-openxr-hands-anchor-particles',
    'XR_FB_passthrough',
    'fb_passthrough',
    'NativePassthroughRuntime',
    'create_passthrough',
    'create_passthrough_layer',
    'CompositionLayerPassthroughFB',
    'PassthroughLayerPurposeFB::RECONSTRUCTION',
    'EnvironmentBlendMode::ALPHA_BLEND',
    'BLEND_TEXTURE_SOURCE_ALPHA',
    'nativePassthroughLayerActive',
    'projectionLayerAlphaBlend',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'compactHandInputSourceMode',
    'recordedReplayVisualProofEnabled',
    'recordedReplayVisualAcceptance=pending-headset-screenshot',
    'allowsRecordedFallback',
    'hand-mesh-visual-diagnostic',
    'leftHandMeshVisualScreenUvRect',
    'rightHandMeshVisualScreenUvRect',
    'leftSdfVisualScreenUvRect',
    'rightSdfVisualScreenUvRect',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'solidBlackRealHandMeshVisible',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'dynamicSdfReady=pending',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'status=skinning-active-sdf-overlay-deferred reason=property-disabled',
    'GpuSdfFieldRenderer',
    'GpuSdfFieldFrameStats',
    'cpuSdfPerFrame=false',
    'xr-vulkan-probe',
    'vulkan-probe'
)
foreach ($token in $xrVulkanTokens) {
    if ("$xrVulkan`n$nativeRendererOptions`n$nativeRendererTiming`n$privateExtensionSlot`n$nativeCamera" -notmatch $token) {
        throw "Rust OpenXR/Vulkan prerequisite probe missing token: $token"
    }
}

foreach ($counter in @(
    'camera_frames_acquired',
    'hardware_buffer_imports',
    'hardware_buffer_cache_hits',
    'hardware_buffer_cache_misses',
    'guide_graph_renders',
    'guide_graph_cache_hits',
    'sdf_field_updates',
    'private_layer_invocations',
    'xr_frames_submitted',
    'stale_frames'
)) {
    if ($nativeCamera -notmatch $counter -or $nativeLib -notmatch $counter) {
        throw "Rust native timing scaffold missing counter: $counter"
    }
}

foreach ($token in @(
    'hwbNativeImportReady=true',
    'vulkanExternalImportReady=false',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1',
    'openxrProjectionLayer=runtime-submit',
    'openxrSubmitReady=false'
)) {
    if ($nativeLib -notmatch $token -and $nativeCamera -notmatch $token) {
        throw "Rust native renderer scaffold missing token: $token"
    }
}

foreach ($token in @(
    'cargo build',
    'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER',
    'aarch64-linux-android29-clang\.cmd',
    'librusty_quest_native_renderer\.so',
    'libopenxr_loader\.so',
    'native-hwb-blur-sdf-public\.plan\.json',
    'recorded-hand-replay-public-shape\.json',
    'RecordedHandCaptureDir',
    'RequireRecordedHandCapture',
    'recorded_hand_capture_required',
    'RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR',
    'gpu-mesh-boundary',
    'rusty\.quest\.native_renderer_android\.build_manifest\.v1'
)) {
    if ($buildScriptText -notmatch $token) {
        throw "Native renderer build script missing token: $token"
    }
}
foreach ($token in @('javac', 'd8\.bat', 'classes\.dex', 'clang\+\+')) {
    if ($buildScriptText -match $token) {
        throw "Native renderer build script still carries Java/C++ packaging token: $token"
    }
}

$sourceCombined = "$manifest`n$nativeCargo`n$nativeBuildRs`n$nativeLib`n$androidEvents`n$nativeCamera`n$acameraSys`n$cameraProjection`n$cameraProjectionMetadata`n$environmentDepthGeometry`n$guideBlurGraph`n$guideBlurDownsampleFragment`n$guideBlurFragment`n$guideProjectionFragment`n$recordedHandReplayModule`n$liveHandCompact`n$nativeRendererOptions`n$handMeshGraft`n$gpuHandMeshVisual`n$handMeshVisualVertex`n$handMeshVisualFragment`n$gpuMeshReplay`n$gpuSdfField`n$gpuHandSkinningShader`n$gpuSdfFieldShader`n$gpuSdfTileBinsShader`n$gpuSdfOverlayShader`n$xrVulkan`n$buildScriptText"
$forbiddenTokens = @(
    ("RUSTY" + "_XR_"),
    ("rusty" + ".xr."),
    ("/rusty" + "xr/v1"),
    ("com.example." + "rustyxr.broker"),
    "Makepad",
    "NativeRendererStartActivity",
    "HardwareBuffer\.fromHardwareBuffer"
)
foreach ($token in $forbiddenTokens) {
    if ($sourceCombined -match $token) {
        throw "Native renderer Android scaffold contains forbidden route token: $token"
    }
}

foreach ($token in @('colorama', 'rusty-vision-colorama')) {
    if ($sourceCombined -match $token) {
        throw "Native renderer Android public scaffold exposes private effect token: $token"
    }
}

foreach ($token in @(
    'rusty.quest.native_renderer_runtime_evidence.v1',
    'Measure-ScreenshotContent',
    'Save-ScreenshotCropSet',
    'ConvertTo-ScreenshotUvRect',
    'Expand-ScreenshotTargetUvRectTexts',
    'Get-ScreenshotTargetUvRectTexts',
    'leftTargetScreenUvRect',
    'rightTargetScreenUvRect',
    'RequireNonFlatScreenshot',
    'RequireTargetNonFlatScreenshot',
    'RequireHandMeshVisualScreenshot',
    'RequireSdfVisualScreenshot',
    'ScreenshotTargetUvRects',
    'MinimumNonFlatScreenshotTargetRects',
    'MinimumNonFlatHandMeshVisualRects',
    'MinimumNonFlatSdfVisualRects',
    'MinimumOverlayColorFamilyPixels',
    'MinimumHandMeshVisualOverlayColorRatio',
    'MinimumSdfVisualOverlayColorRatio',
    'MinimumScreenshotUniqueColors',
    'MinimumScreenshotLumaRange',
    'screenshot_sampled_unique_colors',
    'screenshot_sampled_chroma_pixels',
    'screenshot_sampled_chroma_ratio',
    'overlay_color_family_pixels',
    'overlay_color_family_ratio',
    'screenshot_luma_range',
    'screenshot_target_rects',
    'screenshot_crop_out_dir',
    'screenshot_target_crop_artifacts',
    'screenshot_hand_mesh_visual_crop_artifacts',
    'screenshot_sdf_visual_crop_artifacts',
    'screenshot_target_non_flat_rects',
    'screenshot_hand_mesh_visual_rects',
    'screenshot_sdf_visual_rects',
    'screenshot_hand_mesh_visual_non_flat_rects',
    'screenshot_sdf_visual_non_flat_rects',
    'screenshot_hand_mesh_visual_overlay_color_rects',
    'screenshot_sdf_visual_overlay_color_rects',
    'RequireLiveVisualDiagnosticCaveat',
    'live_visual_diagnostic_caveat_checked',
    'RequireEnvironmentDepthParticles',
    'environment_depth_particles_checked',
    'ExpectedEnvironmentDepthParticleCount',
    'MinimumEnvironmentDepthSourceDepthSamples',
    'MinimumEnvironmentDepthHashProbeExhaustedCount',
    'MinimumEnvironmentDepthHeadMotionSamples',
    'MinimumEnvironmentDepthHeadMotionYawDeg',
    'MinimumEnvironmentDepthHeadMotionTranslationM',
    'environmentDepthRenderViewStateFlags',
    'environmentDepthCaptureToDisplayMs',
    'environmentDepthAcquireToRenderMs',
    'environmentDepthFrameAgeMs',
    'environmentDepthRepeatedCaptureTimeCount',
    'environmentDepthUnavailableStreak',
    'environmentDepthTextureTransformLabel',
    'environmentDepthRayUvPolicy',
    'environmentDepthSampleUvPolicy',
    'environmentDepthConfidenceFilter',
    'environmentDepthFreeSpaceRangePolicy',
    'environmentDepthFreeSpaceConfidenceSkippedCount',
    'environmentDepthWorldSpaceMotionEvidence',
    'environment_depth_head_motion_max_yaw_delta_deg',
    'environment_depth_head_motion_max_translation_delta_m',
    'nativePassthroughLayerActive=true',
    'passthroughCompositionLayer=CompositionLayerPassthroughFB',
    'environmentDepthSource=xr-meta-environment-depth',
    'environmentDepthAcquireStatus=acquired',
    'environmentDepthParticleSource=xr-meta-environment-depth',
    'environmentDepthParticleCpuUploadBytes=0',
    'environmentDepthGpuBuffersResident=true',
    'environmentDepthWorldSpaceReady=true',
    'compactHandInputSourceMode=live-meta-openxr-hand-tracking',
    'handMeshCompactInputSource=live-meta-openxr-hand-tracking',
    'sdfCompactInputSource=live-meta-openxr-hand-tracking',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'liveSdfVisualAcceptance=pending-repeat-headset-visual-proof',
    'RequirePerformanceBudget',
    'MinimumObservedOpenXrFps',
    'MaximumStaleFrames',
    'MaximumCameraAcquireImportCpuMs',
    'MaximumGuideGraphCpuMs',
    'MaximumHandSdfPrepareCpuMs',
    'MaximumHandMeshVisualCpuMs',
    'MaximumProjectionCompositeCpuMs',
    'performance_budget_cpu_metrics',
    'performance_budget_gpu_metrics',
    'RequireReplayVisualProof',
    'RequireGuideGraph',
    'RequireSdfVisual',
    'RequireGpuTimestampReady',
    'RequirePrivateSlotNoPayload',
    'animatedHandMeshVisualVisible=true',
    'gpuTimestampQueryReady=true',
    'privateLayerPayloadLinked=false'
)) {
    if ($runtimeEvidenceToolText -notmatch $token) {
        throw "Native renderer runtime evidence checker missing token: $token"
    }
}

foreach ($token in @(
    'leftTargetScreenUvRect=0.171875,0.218750,0.750000,0.656250',
    'rightTargetScreenUvRect=0.078125,0.218750,0.750000,0.671875',
    'leftHandMeshVisualScreenUvRect=',
    'rightHandMeshVisualScreenUvRect=',
    'leftSdfVisualScreenUvRect=',
    'rightSdfVisualScreenUvRect=',
    'targetCoordinateSpace=display-eye-screen-uv',
    'targetFootprintMetadataSource=native-direct-camera-target-screen-uv-runtime'
)) {
    if ($runtimeEvidenceFixtureText -notmatch [regex]::Escape($token)) {
        throw "Native renderer accepted runtime evidence fixture missing target metadata token: $token"
    }
}

foreach ($token in @(
    'compactHandInputSourceMode=live-meta-openxr-hand-tracking',
    'compactHandInputSelectsLiveFrame=true',
    'compactHandInputAllowsRecordedFallback=false',
    'handMeshCompactInputSource=live-meta-openxr-hand-tracking',
    'sdfCompactInputSource=live-meta-openxr-hand-tracking',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'liveSdfVisualAcceptance=pending-repeat-headset-visual-proof'
)) {
    if ($liveHandDiagnosticPendingFixtureText -notmatch [regex]::Escape($token)) {
        throw "Native renderer live-hand diagnostic pending fixture missing token: $token"
    }
}

foreach ($token in @(
    'RUSTY_QUEST_NATIVE_RENDERER channel=native-passthrough',
    'nativePassthroughLayerActive=true',
    'passthroughCompositionLayer=CompositionLayerPassthroughFB',
    'RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth',
    'environmentDepthSource=xr-meta-environment-depth',
    'environmentDepthProviderState=provider-running',
    'environmentDepthProviderAvailable=true',
    'environmentDepthRealProviderBound=true',
    'environmentDepthSupported=true',
    'environmentDepthAcquireStatus=acquired',
    'environmentDepthFormat=VK_FORMAT_D16_UNORM',
    'environmentDepthLayerCount=2',
    'environmentDepthSourceViewCount=1',
    'environmentDepthSampledLayerMask=0x1',
    'environmentDepthShaderLayerPolicy=mono-layer0',
    'environmentDepthDepthViewPoseValidMask=0x1',
    'environmentDepthDepthViewFovValidMask=0x1',
    'environmentDepthRenderViewStateFlags=orientation-valid+position-valid',
    'environmentDepthCaptureToDisplayMs=',
    'environmentDepthFrameAgeMs=',
    'environmentDepthRepeatedCaptureTimeCount=',
    'environmentDepthUnavailableStreak=',
    'environmentDepthTextureTransformLabel=rotate0+flipY',
    'environmentDepthRayUvPolicy=canonical-untransformed',
    'environmentDepthSampleUvPolicy=texture-transformed',
    'environmentDepthPoseValid=true',
    'RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth-particles',
    'environmentDepthParticleReady=true',
    'environmentDepthParticleVisible=true',
    'environmentDepthMode=scene-particle-map',
    'environmentDepthParticleSource=xr-meta-environment-depth',
    'environmentDepthParticleCoordinateSpace=openxr-reference-space',
    'environmentDepthWorldSpaceReady=true',
    'environmentDepthWorldSpaceMotionEvidence=render-view-pose-delta',
    'environmentDepthHeadMotionPoseSource=left-render-view',
    'environmentDepthHeadMotionSamples=',
    'environmentDepthHeadMotionMaxYawDeltaDeg=',
    'environmentDepthHeadMotionMaxTranslationDeltaM=',
    'environmentDepthParticleSourceDepthSamples=676',
    'environmentDepthParticleCpuUploadBytes=0',
    'environmentDepthGpuBuffersResident=true',
    'environmentDepthParticleBufferMemory=device-local',
    'environmentDepthSourceViewCount=1',
    'environmentDepthSampledLayerMask=0x1',
    'environmentDepthShaderLayerPolicy=mono-layer0',
    'environmentDepthRenderViewStateFlags=orientation-valid+position-valid',
    'environmentDepthCaptureToDisplayMs=',
    'environmentDepthAcquireToRenderMs=',
    'environmentDepthFrameAgeMs=',
    'environmentDepthTextureTransformLabel=rotate0+flipY',
    'environmentDepthRayUvPolicy=canonical-untransformed',
    'environmentDepthSampleUvPolicy=texture-transformed',
    'environmentDepthParticleDebugColorMode=depth-gradient',
    'environmentDepthConfidenceFilter=edge-aware-4tap-discontinuity-isolated-reject-v1',
    'environmentDepthSceneConfidenceThreshold=0.580',
    'environmentDepthFreeSpaceConfidenceThreshold=0.780',
    'environmentDepthGpuReconstructPath=native-vulkan-compute-depth-view-to-reference-space',
    'environmentDepthGpuDrawPath=native-vulkan-reference-space-billboard-overlay',
    'environmentDepthParticleRetention=scene-owned-spatial-particle-map',
    'environmentDepthParticleMapPolicy=spatial-hash-reference-space-cells',
    'environmentDepthSceneParticleMap=true',
    'environmentDepthInvalidSamplePolicy=preserve-existing-cells',
    'environmentDepthFreeSpaceCorrection=confidence-gated-visible-free-space-ray-clear',
    'environmentDepthFreeSpaceRangePolicy=near-plus-cell-step-cap',
    'environmentDepthFreeSpaceConfidenceSkippedCount='
)) {
    if ($environmentDepthParticlesEvidenceFixtureText -notmatch [regex]::Escape($token)) {
        throw "Native renderer Meta environment-depth particle fixture missing token: $token"
    }
}

$parseTokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($runtimeProfileToolPath, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw "Native renderer runtime profile tool has PowerShell parse errors: $($parseErrors[0].Message)"
}
$parseTokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($runtimeSmokeToolPath, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw "Native renderer replay smoke tool has PowerShell parse errors: $($parseErrors[0].Message)"
}
$parseTokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($environmentDepthMotionProofToolPath, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw "Native renderer environment-depth motion proof tool has PowerShell parse errors: $($parseErrors[0].Message)"
}

foreach ($token in @(
    'RUSTY_QUEST_ADB_SERVER_PORT',
    'AdbServerPort',
    'Resolve-AdbServerPortArgument',
    'adb_scope',
    'device-scoped-adb',
    'adb_serial_required',
    'adb_serial',
    'adb_server_port',
    '-P',
    '-s',
    '-Serial or RUSTY_QUEST_SERIAL is required with -Execute',
    'device-scoped ADB writes must not use an implicit target'
)) {
    if ($runtimeProfileToolText -notmatch [regex]::Escape($token)) {
        throw "Native renderer runtime profile tool missing serial-scoped ADB token: $token"
    }
}
foreach ($token in @(
    'rusty.quest.native_renderer_replay_smoke_run.v1',
    'Apply-RuntimeProfile.ps1',
    'Test-NativeRendererRuntimeEvidence.ps1',
    'quest-native-renderer-replay-visual-proof.profile.json',
    'quest-native-renderer-live-hand-visual-diagnostic.profile.json',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json',
    'EvidenceMode',
    'ReplayVisualProof',
    'LiveVisualDiagnosticCaveat',
    'EnvironmentDepthParticles',
    'previousErrorActionPreference',
    'NativeCommandError',
    'RUSTY_QUEST_ADB_SERVER_PORT',
    'AdbServerPort',
    'Resolve-AdbServerPortArgument',
    'device-scoped-adb',
    'adb_serial_required',
    'adb_server_port',
    'clear_logcat_requested',
    'pid-scoped-device-logcat',
    'pidof',
    '--pid',
    'refusing unscoped logcat evidence',
    'must pass adb -s <serial>',
    'replay_visual_proof_required',
    'live_visual_diagnostic_caveat_required',
    'environment_depth_particles_required',
    'rusty-quest-native-renderer.apk',
    'RUSTY_QUEST_NATIVE_RENDERER',
    'android.permission.CAMERA',
    'com.oculus.permission.HAND_TRACKING',
    'horizonos.permission.HEADSET_CAMERA',
    'horizonos.permission.SPATIAL_CAMERA',
    'horizonos.permission.USE_SCENE',
    'pm',
    'grant',
    'logcat',
    'screencap',
    '/data/local/tmp/rusty_quest_native_renderer_replay_smoke.png',
    'filtered-native-renderer-logcat.txt',
    'runtime-evidence-summary.json',
    'screenshot-crops',
    'run-summary.json',
    'AllowFlatScreenshot',
    'AllowPerformanceBudgetMiss',
    'RequireNonFlatScreenshot',
    'RequireTargetNonFlatScreenshot',
    'RequireHandMeshVisualScreenshot',
    'RequireSdfVisualScreenshot',
    'ScreenshotTargetUvRects',
    'ScreenshotCropOutDir',
    '-join "|"',
    'RequireReplayVisualProof',
    'RequireLiveVisualDiagnosticCaveat',
    'RequireEnvironmentDepthParticles',
    'ExpectedEnvironmentDepthParticleCount',
    'MinimumEnvironmentDepthSourceDepthSamples',
    'MinimumEnvironmentDepthHashProbeExhaustedCount',
    'MinimumEnvironmentDepthHeadMotionSamples',
    'MinimumEnvironmentDepthHeadMotionYawDeg',
    'MinimumEnvironmentDepthHeadMotionTranslationM',
    'RequireGuideGraph',
    'RequireSdfVisual',
    'RequirePrivateSlotNoPayload',
    'RequireGpuTimestampReady',
    'RequirePerformanceBudget',
    'StopAfterRun'
)) {
    if ("$runtimeSmokeToolText`n$permissionPregrantToolText" -notmatch [regex]::Escape($token)) {
        throw "Native renderer replay smoke tool missing token: $token"
    }
}
if ($runtimeSmokeToolText -notmatch '-Execute' -or $runtimeSmokeToolText -notmatch '-SummaryOut') {
    throw "Native renderer replay smoke tool must apply runtime properties and write evidence summaries."
}

foreach ($token in @(
    'Invoke-NativeRendererReplaySmoke.ps1',
    'EvidenceMode',
    'EnvironmentDepthParticles',
    'AllowFlatScreenshot',
    'AllowPerformanceBudgetMiss',
    'MinimumHeadMotionSamples',
    'MinimumYawDeg',
    'MinimumTranslationM',
    'MinimumEnvironmentDepthHeadMotionSamples',
    'MinimumEnvironmentDepthHeadMotionYawDeg',
    'MinimumEnvironmentDepthHeadMotionTranslationM',
    'native-renderer-envdepth-motion-proof',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json',
    'Serial',
    'AdbServerPort',
    'SkipInstall',
    'StopAfterRun'
)) {
    if ($environmentDepthMotionProofToolText -notmatch [regex]::Escape($token)) {
        throw "Native renderer environment-depth motion proof tool missing token: $token"
    }
}

foreach ($token in @(
    'profile.quest.native_renderer.replay_visual_proof',
    'debug.rustyquest.native_renderer.render.mode',
    'custom-stereo-projection',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'renderMode=custom-stereo-projection',
    'compactHandInputSourceMode=recorded-replay',
    'recordedReplayVisualAcceptance=pending-headset-screenshot'
)) {
    if ($replayVisualProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer replay visual proof profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_camera_quality',
    'quest-native-renderer-direct-hwb-camera-quality.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'custom-stereo-projection',
    'debug.rustyquest.native_renderer.camera.output',
    'direct-hwb',
    'debug.rustyquest.native_renderer.camera.ycbcr.mode',
    'android-suggested',
    'debug.rustyquest.native_renderer.camera.resolution',
    '1280x1280',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'debug.rustyquest.native_renderer.camera.quality_profile',
    'direct-baseline',
    'debug.rustyquest.native_renderer.camera.sync_mode',
    'early-delete-ahb-retained',
    'debug.rustyquest.native_renderer.camera.direct_border.opacity',
    'debug.rustyquest.native_renderer.swapchain.color_format',
    'unorm',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'debug.rustyquest.native_renderer.private_layer.enabled',
    'cameraOutputMode=direct-hwb',
    'cameraImportEnabled=true',
    'privateLayerProjectionEnabled=false',
    'guideProjectionEnabled=false',
    'directHwbForced=true',
    'cameraYcbcrMode=android-suggested',
    'conversionMode=android-suggested-ycbcr',
    'cameraResolutionProfile=1280x1280',
    'cameraQualityProfile=direct-baseline',
    'cameraSyncRequested=early-delete-ahb-retained',
    'cameraSyncActive=early-delete-ahb-retained',
    'swapchainColorFormatMode=unorm',
    'directBorderOpacity=0.000',
    'unormPreferred=true',
    'cameraProjectionPath=metadata-target-direct-hwb-forced',
    'directHwbProjectionDiagnostic=true',
    'actualFinalExternalHwbSamples=2',
    'actualGuideTextureSamples=0'
)) {
    if ($directHwbCameraQualityProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB camera quality profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_camera_quality_bt601_unorm',
    'quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'custom-stereo-projection',
    'debug.rustyquest.native_renderer.camera.output',
    'direct-hwb',
    'debug.rustyquest.native_renderer.camera.ycbcr.mode',
    'forced-bt601-narrow',
    'debug.rustyquest.native_renderer.camera.resolution',
    '1280x1280',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'debug.rustyquest.native_renderer.camera.quality_profile',
    'direct-baseline',
    'debug.rustyquest.native_renderer.camera.sync_mode',
    'early-delete-ahb-retained',
    'debug.rustyquest.native_renderer.camera.direct_border.opacity',
    'debug.rustyquest.native_renderer.swapchain.color_format',
    'unorm',
    'cameraOutputMode=direct-hwb',
    'cameraYcbcrMode=forced-bt601-narrow',
    'conversionMode=forced-bt601-limited-cpuyuv-reference',
    'cameraResolutionProfile=1280x1280',
    'cameraQualityProfile=direct-baseline',
    'cameraSyncRequested=early-delete-ahb-retained',
    'cameraSyncActive=early-delete-ahb-retained',
    'effectiveYcbcrModel=YCBCR_601',
    'effectiveYcbcrRange=ITU_NARROW',
    'swapchainColorFormatMode=unorm',
    'directBorderOpacity=0.000',
    'unormPreferred=true',
    'cameraProjectionPath=metadata-target-direct-hwb-forced',
    'directHwbProjectionDiagnostic=true',
    'actualFinalExternalHwbSamples=2',
    'actualGuideTextureSamples=0'
)) {
    if ($directHwbCameraQualityBt601UnormProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB BT601/UNORM camera quality profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_low_noise_30',
    'quest-native-renderer-direct-hwb-low-noise-30.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'custom-stereo-projection',
    'debug.rustyquest.native_renderer.camera.output',
    'direct-hwb',
    'debug.rustyquest.native_renderer.camera.ycbcr.mode',
    'android-suggested',
    'debug.rustyquest.native_renderer.camera.resolution',
    '1280x1280',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'debug.rustyquest.native_renderer.camera.quality_profile',
    'direct-low-noise-30',
    'debug.rustyquest.native_renderer.camera.sync_mode',
    'early-delete-ahb-retained',
    'debug.rustyquest.native_renderer.swapchain.color_format',
    'unorm',
    'cameraOutputMode=direct-hwb',
    'cameraYcbcrMode=android-suggested',
    'conversionMode=android-suggested-ycbcr',
    'cameraResolutionProfile=1280x1280',
    'cameraQualityProfile=direct-low-noise-30',
    'cameraSyncRequested=early-delete-ahb-retained',
    'cameraSyncActive=early-delete-ahb-retained',
    'swapchainColorFormatMode=unorm',
    'camera-request-profile',
    'requestedAeFpsRange=30-30',
    'requestedNoiseReductionMode=HIGH_QUALITY',
    'requestedEdgeMode=OFF',
    'cameraProjectionPath=metadata-target-direct-hwb-forced',
    'directHwbProjectionDiagnostic=true',
    'actualFinalExternalHwbSamples=2',
    'actualGuideTextureSamples=0'
)) {
    if ($directHwbLowNoise30Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB low-noise 30 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_low_noise_record_30',
    'quest-native-renderer-direct-hwb-low-noise-record-30.profile.json',
    'debug.rustyquest.native_renderer.camera.output',
    'direct-hwb',
    'debug.rustyquest.native_renderer.camera.ycbcr.mode',
    'android-suggested',
    'debug.rustyquest.native_renderer.camera.resolution',
    '1280x1280',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'debug.rustyquest.native_renderer.camera.quality_profile',
    'direct-low-noise-record-30',
    'cameraQualityProfile=direct-low-noise-record-30',
    'cameraSyncActive=early-delete-ahb-retained',
    'camera-request-profile',
    'profile=direct-low-noise-record-30',
    'template=record',
    'requestedAeFpsRange=30-30',
    'selectedAeFpsRange=',
    'requestedNoiseReductionMode=HIGH_QUALITY',
    'requestedEdgeMode=OFF',
    'cameraProjectionPath=metadata-target-direct-hwb-forced',
    'directHwbProjectionDiagnostic=true',
    'actualFinalExternalHwbSamples=2',
    'actualGuideTextureSamples=0'
)) {
    if ($directHwbLowNoiseRecord30Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB low-noise record 30 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_low_latency_60',
    'quest-native-renderer-direct-hwb-low-latency-60.profile.json',
    'debug.rustyquest.native_renderer.camera.output',
    'direct-hwb',
    'debug.rustyquest.native_renderer.camera.resolution',
    '1280x1280',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'debug.rustyquest.native_renderer.camera.quality_profile',
    'direct-low-latency-60',
    'cameraResolutionProfile=1280x1280',
    'cameraQualityProfile=direct-low-latency-60',
    'cameraSyncActive=early-delete-ahb-retained',
    'profile=direct-low-latency-60',
    'requestedAeFpsRange=60-60',
    'requestedNoiseReductionMode=FAST',
    'requestedEdgeMode=OFF'
)) {
    if ($directHwbLowLatency60Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB low-latency 60 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_hold_sync',
    'quest-native-renderer-direct-hwb-hold-sync.profile.json',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'debug.rustyquest.native_renderer.camera.sync_mode',
    'hold-image-until-gpu-fence',
    'cameraSyncRequested=hold-image-until-gpu-fence',
    'cameraSyncActive=hold-image-until-gpu-fence',
    'cameraSyncImplementation=active-diagnostic',
    'producerConsumerSync=image-slot-held-until-vulkan-frame-fence',
    'imageReleaseApi=AImage_delete-on-vulkan-frame-fence'
)) {
    if ($directHwbHoldSyncProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB hold-sync profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_hold_sync_reader6',
    'quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=6',
    'cameraSyncRequested=hold-image-until-gpu-fence',
    'cameraSyncActive=hold-image-until-gpu-fence',
    'cameraSyncImplementation=active-diagnostic',
    'producerConsumerSync=image-slot-held-until-vulkan-frame-fence'
)) {
    if ($directHwbHoldSyncReader6Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB hold-sync reader 6 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_hold_sync_reader8',
    'quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=8',
    'cameraSyncRequested=hold-image-until-gpu-fence',
    'cameraSyncActive=hold-image-until-gpu-fence',
    'cameraSyncImplementation=active-diagnostic',
    'producerConsumerSync=image-slot-held-until-vulkan-frame-fence'
)) {
    if ($directHwbHoldSyncReader8Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB hold-sync reader 8 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.direct_hwb_1280x960',
    'quest-native-renderer-direct-hwb-1280x960.profile.json',
    'debug.rustyquest.native_renderer.camera.resolution',
    '1280x960',
    'debug.rustyquest.native_renderer.camera.reader_max_images',
    'readerMaxImages=4',
    'cameraResolutionProfile=1280x960',
    'readerRequested=1280x960',
    'cameraQualityProfile=direct-baseline'
)) {
    if ($directHwb1280x960Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer direct-HWB 1280x960 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.hwb_peripheral_stretch',
    'quest-native-renderer-hwb-peripheral-stretch.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'custom-stereo-projection',
    'debug.rustyquest.native_renderer.processing.layer',
    'peripheral-stretch',
    'debug.rustyquest.native_renderer.projection.border.policy',
    'passthrough-underlay',
    'debug.rustyquest.native_renderer.projection.border.opacity',
    'debug.rustyquest.native_renderer.projection.area.opacity',
    'debug.rustyquest.native_renderer.peripheral.stretch.core.scale',
    'debug.rustyquest.native_renderer.peripheral.stretch.edge.inset.uv',
    'debug.rustyquest.native_renderer.peripheral.stretch.max.inset.uv',
    'debug.rustyquest.native_renderer.peripheral.stretch.curve',
    'debug.rustyquest.native_renderer.peripheral.stretch.inner.blend.uv',
    'debug.rustyquest.native_renderer.peripheral.stretch.blend.curve',
    'debug.rustyquest.native_renderer.peripheral.stretch.blend.mode',
    'debug.rustyquest.native_renderer.peripheral.stretch.debug',
    'processingLayer=peripheral-stretch',
    'projectionBorderPolicy=passthrough-underlay',
    'guideProjectionCoverage=full-eye-peripheral-stretch',
    'peripheralStretchProjectionExteriorMode=target-edge-stretch-with-inner-band-blend',
    'cameraProjectionPath=metadata-target-guide-texture-peripheral-stretch-final'
)) {
    if ($hwbPeripheralStretchProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer HWB peripheral stretch profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.live_hand_visual_diagnostic',
    'quest-native-renderer-live-hand-visual-diagnostic.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'custom-stereo-projection',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'live-meta-openxr-hand-tracking',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'renderMode=custom-stereo-projection',
    'handMeshGraftCopiesEnabled=false',
    'compactHandInputSourceMode=live-meta-openxr-hand-tracking',
    'selectsLiveFrame=true',
    'allowsRecordedFallback=false',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'liveSdfVisualAcceptance=pending-repeat-headset-visual-proof'
)) {
    if ($liveHandVisualDiagnosticProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer live hand visual diagnostic profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_graft_only',
    'quest-native-renderer-native-passthrough-graft-only.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'native-passthrough-graft-only',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'live-meta-openxr-hand-tracking',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'customStereoProjectionEnabled=false',
    'nativePassthroughRequested=true',
    'sdfVisualEnabled=false',
    'handMeshGraftCopiesEnabled=true',
    'handMeshGraftScaleMultiplier=0.85',
    'handMeshRealHandsVisible=false',
    'cameraRuntimeMode=skipped-native-passthrough',
    'cameraProjectionPath=disabled-native-passthrough-graft-only'
)) {
    if ($nativePassthroughGraftOnlyProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer native passthrough graft-only profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_hands_and_grafts',
    'quest-native-renderer-native-passthrough-hands-and-grafts.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'native-passthrough-graft-only',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'live-meta-openxr-hand-tracking',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'customStereoProjectionEnabled=false',
    'nativePassthroughRequested=true',
    'sdfVisualEnabled=false',
    'handMeshGraftCopiesEnabled=true',
    'handMeshGraftScaleMultiplier=0.85',
    'handMeshRealHandsVisible=true',
    'nativePassthroughRealHandMeshVisible=true',
    'cameraRuntimeMode=skipped-native-passthrough',
    'cameraProjectionPath=disabled-native-passthrough-graft-only'
)) {
    if ($nativePassthroughHandsAndGraftsProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer native passthrough hands-and-grafts profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.solid_black_hands_and_grafts',
    'quest-native-renderer-solid-black-hands-and-grafts.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'solid-black-hands-and-grafts',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'live-meta-openxr-hand-tracking',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'customStereoProjectionEnabled=false',
    'nativePassthroughRequested=false',
    'solidBlackBackground=true',
    'sdfVisualEnabled=false',
    'handMeshGraftCopiesEnabled=true',
    'handMeshGraftScaleMultiplier=0.85',
    'handMeshRealHandsVisible=true',
    'solidBlackRealHandMeshVisible=true',
    'cameraRuntimeMode=skipped-solid-black-hands-and-grafts',
    'cameraProjectionPath=disabled-solid-black-hands-and-grafts'
)) {
    if ($solidBlackHandsAndGraftsProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer solid black hands-and-grafts profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.solid_black_openxr_hands_anchor_particles',
    'quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json',
    'debug.rustyquest.native_renderer.render.mode',
    'solid-black-openxr-hands-anchor-particles',
    'debug.rustyquest.native_renderer.camera.output',
    'disabled',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'live-meta-openxr-hand-tracking',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'debug.rustyquest.native_renderer.hand_anchor_particles.enabled',
    'debug.rustyquest.native_renderer.hand_anchor_particles.per_hand',
    'debug.rustyquest.native_renderer.hand_anchor_particles.radius_m',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'customStereoProjectionEnabled=false',
    'nativePassthroughRequested=false',
    'solidBlackBackground=true',
    'openxrDefaultHandVisualRequested=true',
    'sdfVisualEnabled=false',
    'handMeshGraftCopiesEnabled=false',
    'handMeshRealHandsVisible=false',
    'customHandMeshVisualRequested=false',
    'handAnchorParticlesEnabled=true',
    'handAnchorParticleCoordinateSpace=openxr-reference-space',
    'handAnchorParticleCpuExpandedUploadPerFrame=false',
    'handAnchorParticleMeshUploadPerFrame=false'
)) {
    if ($solidBlackOpenXrHandsAnchorParticlesProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer solid black OpenXR hands anchor particles profile missing token: $token"
    }
}
foreach ($token in @(
    'mod environment_depth_geometry',
    'mod gpu_environment_depth_particles',
    'mod openxr_environment_depth',
    'reconstruct_reference_space_point',
    'project_reference_space_point_to_render_eye',
    'depth_view_pose_rotation_and_translation_are_applied_once',
    'retained_reference_point_projects_through_current_render_eye',
    'debug.rustyquest.native_renderer.environment_depth.mode',
    'debug.rustyquest.native_renderer.environment_depth.source',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'debug.rustyquest.native_renderer.environment_depth.depth_units_policy',
    'debug.rustyquest.native_renderer.environment_depth.debug_view',
    'debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled',
    'debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload',
    'debug.rustyquest.native_renderer.environment_depth.surface_model',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.radius_cells',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.min_observations',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.min_source_layers',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.normal_coherence',
    'debug.rustyquest.native_renderer.environment_depth.surface_support.free_space_decay',
    'environmentDepthDepthUnitsPolicy=projected-depth-from-near-far',
    'environmentDepthRawToMetersPolicy=projected-depth-from-near-far',
    'environmentDepthHandRemovalRequested',
    'environmentDepthHighRateJsonPayload=false',
    'environmentDepthSurfaceSupportRequested',
    'environmentDepthSurfaceSupportEnforced=false',
    'environmentDepthSurfaceSupportStatus'
)) {
    if ("$nativeLib`n$environmentDepthGeometry`n$environmentDepthParticles`n$openxrEnvironmentDepth`n$nativeRendererOptions" -notmatch [regex]::Escape($token)) {
        throw "Native renderer environment-depth source surface missing token: $token"
    }
}
foreach ($token in @(
    'GpuEnvironmentDepthParticleRenderer',
    'GpuEnvironmentDepthParticleFrameStats',
    'environment_depth_particles_synthetic.comp.glsl',
    'environment_depth_particles_meta.comp.glsl',
    'environment_depth_particles.vert.glsl',
    'environment_depth_particles.frag.glsl',
    'EnvironmentDepthRawDebugStats',
    'environmentDepthRawCenterD16',
    'environmentDepthRawCenterWindowMedianD16',
    'environmentDepthRawStatsStatus',
    'OpenXrEnvironmentDepthRuntime',
    'EnvironmentDepthMETA',
    'XR_META_environment_depth',
    'EnvironmentDepthHandRemovalSetInfoMETA',
    'xrSetEnvironmentDepthHandRemovalMETA',
    'acquire_environment_depth_image',
    'new_runtime_depth',
    'record_runtime_depth_frame',
    'PipelineBindPoint::COMPUTE',
    'cmd_dispatch',
    'PipelineBindPoint::GRAPHICS',
    'cmd_draw',
    'native-vulkan-compute-depth-view-to-reference-space',
    'native-vulkan-reference-space-billboard-overlay',
    'environmentDepthParticleCoordinateSpace=openxr-reference-space',
    'environmentDepthParticleCpuUploadBytes=0',
    'environmentDepthGpuBuffersResident=true',
    'environmentDepthParticleBufferMemory=device-local',
    'environmentDepthWorldSpaceReady',
    'DEPTH_FLAG_SOURCE_LAYER1',
    'environmentDepthRenderViewStateFlags',
    'environmentDepthCaptureToDisplayMs',
    'environmentDepthAcquireToRenderMs',
    'environmentDepthFrameAgeMs',
    'environmentDepthRepeatedCaptureTimeCount',
    'environmentDepthUnavailableStreak',
    'environmentDepthTextureTransformLabel',
    'environmentDepthRayUvPolicy',
    'environmentDepthSampleUvPolicy',
    'environmentDepthConfidenceFilter',
    'environmentDepthFreeSpaceRangePolicy',
    'environmentDepthFreeSpaceConfidenceSkippedCount',
    'environmentDepthParticleDebugColorMode',
    'environmentDepthSurfaceSupportRequested',
    'environmentDepthSurfaceSupportEnforced=false',
    'environmentDepthSurfaceSupportedCells',
    'environmentDepthSurfaceRejectedIsolatedCells',
    'environmentDepthSurfaceLargestComponentCells',
    'environmentDepthSurfaceSupportStatus',
    'particle_debug_color_mode',
    'particle_debug_color_code',
    'DEBUG_COLOR_FREE_SPACE_STATE',
    'debug_particle_color',
    'depth_source_layer_index',
    'raw_depth_to_meters',
    'write_center_raw_debug_window',
    'accumulate_raw_debug_stats',
    'syntheticGpuProofRequested',
    'runtimeProviderRequested',
    'environment-depth-particles'
)) {
    if ("$nativeBuildRs`n$nativeLib`n$environmentDepthParticles`n$openxrEnvironmentDepth`n$environmentDepthParticlesComputeShader`n$environmentDepthParticlesMetaComputeShader`n$environmentDepthParticlesVertexShader`n$environmentDepthParticlesFragmentShader`n$xrVulkan" -notmatch [regex]::Escape($token)) {
        throw "Native renderer environment-depth particle GPU proof route missing token: $token"
    }
}
foreach ($token in @(
    'Grant-NativeRendererPermissions.ps1',
    'permission-pregrant.json',
    'android.permission.CAMERA',
    'com.oculus.permission.HAND_TRACKING',
    'horizonos.permission.HEADSET_CAMERA',
    'horizonos.permission.SPATIAL_CAMERA',
    'horizonos.permission.USE_SCENE',
    'org.khronos.openxr.permission.OPENXR',
    'org.khronos.openxr.permission.OPENXR_SYSTEM',
    'pm',
    'grant',
    '-s'
)) {
    if ("$runtimeSmokeToolPath`n$permissionPregrantToolPath`n$runtimeSmokeToolText`n$permissionPregrantToolText" -notmatch [regex]::Escape($token)) {
        throw "Native renderer permission pregrant route missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.environment_depth_status',
    'quest-native-renderer-environment-depth-status.profile.json',
    'debug.rustyquest.native_renderer.environment_depth.mode',
    'status-only',
    'debug.rustyquest.native_renderer.environment_depth.source',
    'runtime-provider',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'mono-layer0',
    'debug.rustyquest.native_renderer.environment_depth.depth_units_policy',
    'projected-depth-from-near-far',
    'debug.rustyquest.native_renderer.environment_depth.debug_view',
    'normal',
    'debug.rustyquest.native_renderer.environment_depth.reference_space',
    'openxr-local',
    'debug.rustyquest.native_renderer.environment_depth.particle_capacity',
    '32768',
    'debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels',
    '12',
    'debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload',
    'false',
    'RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth',
    'environmentDepthProviderState=status-only-skeleton',
    'environmentDepthShaderLayerPolicy=mono-layer0',
    'environmentDepthDepthUnitsPolicy=projected-depth-from-near-far',
    'environmentDepthRawToMetersPolicy=projected-depth-from-near-far',
    'environmentDepthDebugView=normal',
    'environmentDepthHighRateJsonPayload=false'
)) {
    if ($environmentDepthStatusProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer environment-depth status profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_environment_depth_particles',
    'quest-native-renderer-native-passthrough-environment-depth-particles.profile.json',
    'native-passthrough-graft-only',
    'debug.rustyquest.native_renderer.environment_depth.mode',
    'retained-particles',
    'debug.rustyquest.native_renderer.environment_depth.source',
    'synthetic-gpu-proof',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'mono-layer0',
    'debug.rustyquest.native_renderer.environment_depth.depth_units_policy',
    'projected-depth-from-near-far',
    'debug.rustyquest.native_renderer.environment_depth.debug_view',
    'normal',
    'debug.rustyquest.native_renderer.environment_depth.particle_capacity',
    '4096',
    'debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload',
    'false',
    'RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth-particles',
    'environmentDepthParticleSource=synthetic-gpu-proof',
    'environmentDepthParticleCoordinateSpace=openxr-reference-space',
    'environmentDepthParticleCpuUploadBytes=0',
    'environmentDepthGpuBuffersResident=true',
    'environmentDepthParticleBufferMemory=device-local',
    'nativePassthroughLayerActive=true'
)) {
    if ($environmentDepthNativePassthroughParticlesProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer environment-depth native passthrough particle profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_meta_environment_depth_particles',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json',
    'native-passthrough-graft-only',
    'debug.rustyquest.native_renderer.environment_depth.mode',
    'scene-particle-map',
    'debug.rustyquest.native_renderer.environment_depth.source',
    'xr-meta-environment-depth',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'mono-layer0',
    'debug.rustyquest.native_renderer.environment_depth.depth_units_policy',
    'projected-depth-from-near-far',
    'debug.rustyquest.native_renderer.environment_depth.debug_view',
    'raw-d16',
    'debug.rustyquest.native_renderer.environment_depth.particle_capacity',
    '32768',
    'debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload',
    'false',
    'RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth',
    'environmentDepthProviderState=provider-running',
    'environmentDepthRealProviderBound=true',
    'environmentDepthAcquireStatus=acquired',
    'environmentDepthFormat=VK_FORMAT_D16_UNORM',
    'environmentDepthSourceViewCount=1',
    'environmentDepthSampledLayerMask=0x1',
    'environmentDepthShaderLayerPolicy=mono-layer0',
    'environmentDepthDepthUnitsPolicy=projected-depth-from-near-far',
    'environmentDepthRawToMetersPolicy=projected-depth-from-near-far',
    'environmentDepthDebugView=raw-d16',
    'environmentDepthParticleDebugColorMode=depth-gradient',
    'environmentDepthDepthViewPoseValidMask=0x1',
    'environmentDepthDepthViewFovValidMask=0x1',
    'environmentDepthRenderViewStateFlags=orientation-valid+position-valid',
    'environmentDepthCaptureToDisplayMs=',
    'environmentDepthFrameAgeMs=',
    'environmentDepthRepeatedCaptureTimeCount=',
    'environmentDepthUnavailableStreak=',
    'environmentDepthTextureTransformLabel=rotate0+flipY',
    'environmentDepthRayUvPolicy=canonical-untransformed',
    'environmentDepthSampleUvPolicy=texture-transformed',
    'RUSTY_QUEST_NATIVE_RENDERER channel=environment-depth-particles',
    'environmentDepthParticleSource=xr-meta-environment-depth',
    'environmentDepthParticleCoordinateSpace=openxr-reference-space',
    'environmentDepthParticleCpuUploadBytes=0',
    'environmentDepthGpuBuffersResident=true',
    'environmentDepthParticleBufferMemory=device-local',
    'environmentDepthSourceViewCount=1',
    'environmentDepthSampledLayerMask=0x1',
    'environmentDepthShaderLayerPolicy=mono-layer0',
    'environmentDepthDepthUnitsPolicy=projected-depth-from-near-far',
    'environmentDepthRawToMetersPolicy=projected-depth-from-near-far',
    'environmentDepthDebugView=raw-d16',
    'environmentDepthDepthViewPoseValidMask=0x1',
    'environmentDepthDepthViewFovValidMask=0x1',
    'environmentDepthRenderViewStateFlags=orientation-valid+position-valid',
    'environmentDepthCaptureToDisplayMs=',
    'environmentDepthAcquireToRenderMs=',
    'environmentDepthFrameAgeMs=',
    'environmentDepthTextureTransformLabel=rotate0+flipY',
    'environmentDepthRayUvPolicy=canonical-untransformed',
    'environmentDepthSampleUvPolicy=texture-transformed',
    'environmentDepthConfidenceFilter=edge-aware-4tap-discontinuity-isolated-reject-v1',
    'environmentDepthSceneConfidenceThreshold=0.580',
    'environmentDepthFreeSpaceConfidenceThreshold=0.780',
    'environmentDepthParticleRetention=scene-owned-spatial-particle-map',
    'environmentDepthParticleMapPolicy=spatial-hash-reference-space-cells',
    'environmentDepthSceneParticleMap=true',
    'environmentDepthInvalidSamplePolicy=preserve-existing-cells',
    'environmentDepthFreeSpaceCorrection=confidence-gated-visible-free-space-ray-clear',
    'environmentDepthFreeSpaceRangePolicy=near-plus-cell-step-cap',
    'environmentDepthFreeSpaceConfidenceSkippedCount=',
    'nativePassthroughLayerActive=true'
)) {
if ($environmentDepthNativePassthroughMetaParticlesProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer Meta environment-depth native passthrough particle profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_meta_environment_depth_particles_layer1',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'mono-layer1',
    'debug.rustyquest.native_renderer.environment_depth.depth_units_policy',
    'projected-depth-from-near-far',
    'debug.rustyquest.native_renderer.environment_depth.debug_view',
    'raw-d16',
    'environmentDepthSourceViewCount=1',
    'environmentDepthSampledLayerMask=0x2',
    'environmentDepthShaderLayerPolicy=mono-layer1',
    'environmentDepthDepthUnitsPolicy=projected-depth-from-near-far',
    'environmentDepthRawToMetersPolicy=projected-depth-from-near-far',
    'environmentDepthDebugView=raw-d16',
    'environmentDepthParticleDebugColorMode=depth-gradient',
    'environmentDepthDepthViewPoseValidMask=0x2',
    'environmentDepthDepthViewFovValidMask=0x2',
    'environmentDepthRenderViewStateFlags=orientation-valid+position-valid',
    'environmentDepthCaptureToDisplayMs=',
    'environmentDepthAcquireToRenderMs=',
    'environmentDepthFrameAgeMs=',
    'environmentDepthTextureTransformLabel=rotate0+flipY',
    'environmentDepthRayUvPolicy=canonical-untransformed',
    'environmentDepthSampleUvPolicy=texture-transformed',
    'environmentDepthConfidenceFilter=edge-aware-4tap-discontinuity-isolated-reject-v1',
    'environmentDepthFreeSpaceRangePolicy=near-plus-cell-step-cap',
    'environmentDepthParticleRetention=scene-owned-spatial-particle-map',
    'environmentDepthParticleMapPolicy=spatial-hash-reference-space-cells',
    'environmentDepthWorldSpaceReady=true'
)) {
    if ($environmentDepthNativePassthroughMetaParticlesLayer1Profile -notmatch [regex]::Escape($token)) {
        throw "Native renderer Meta environment-depth layer-1 profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_meta_environment_depth_particles_low_capacity',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'mono-layer0',
    'debug.rustyquest.native_renderer.environment_depth.particle_capacity',
    '64',
    'debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels',
    '4',
    'environmentDepthParticleCount=64',
    'environmentDepthParticleRetention=scene-owned-spatial-particle-map',
    'environmentDepthParticleMapPolicy=spatial-hash-reference-space-cells',
    'environmentDepthMapWritePolicy=atomic-slot-claim',
    'environmentDepthSceneHashProbeCount=8',
    'environmentDepthHashProbeExhaustedCount=',
    'environmentDepthRenderViewStateFlags=orientation-valid+position-valid',
    'environmentDepthCaptureToDisplayMs=',
    'environmentDepthAcquireToRenderMs=',
    'environmentDepthFrameAgeMs=',
    'environmentDepthTextureTransformLabel=rotate0+flipY',
    'environmentDepthRayUvPolicy=canonical-untransformed',
    'environmentDepthSampleUvPolicy=texture-transformed',
    'environmentDepthConfidenceFilter=edge-aware-4tap-discontinuity-isolated-reject-v1',
    'environmentDepthFreeSpaceRangePolicy=near-plus-cell-step-cap',
    'environmentDepthParticleDebugColorMode=depth-gradient',
    'environmentDepthWorldSpaceReady=true'
)) {
    if ($environmentDepthNativePassthroughMetaParticlesLowCapacityProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer Meta environment-depth low-capacity profile missing token: $token"
    }
}
foreach ($token in @(
    'profile.quest.native_renderer.native_passthrough_meta_environment_depth_particles_debug_colors',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json',
    'debug.rustyquest.native_renderer.environment_depth.debug_view',
    'free-space-state',
    'environmentDepthDebugView=free-space-state',
    'environmentDepthParticleDebugColorMode=free-space-state',
    'environmentDepthSourceViewCount=1',
    'environmentDepthSampledLayerMask=0x1',
    'environmentDepthShaderLayerPolicy=mono-layer0',
    'environmentDepthParticleRetention=scene-owned-spatial-particle-map',
    'environmentDepthParticleMapPolicy=spatial-hash-reference-space-cells',
    'environmentDepthFreeSpaceCorrection=confidence-gated-visible-free-space-ray-clear',
    'environmentDepthFreeSpaceRangePolicy=near-plus-cell-step-cap',
    'environmentDepthWorldSpaceReady=true'
)) {
    if ($environmentDepthNativePassthroughMetaParticlesDebugColorsProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer Meta environment-depth debug-colors profile missing token: $token"
    }
}

foreach ($profileCase in @(
    @{
        Text = $environmentDepthLayer0Profile
        Label = "layer0"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_layer0',
            'quest-native-renderer-envdepth-layer0.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.layer_policy',
            'mono-layer0',
            'environmentDepthSampledLayerMask=0x1',
            'environmentDepthShaderLayerPolicy=mono-layer0',
            'environmentDepthHandRemovalRequested=false'
        )
    },
    @{
        Text = $environmentDepthLayer1Profile
        Label = "layer1"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_layer1',
            'quest-native-renderer-envdepth-layer1.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.layer_policy',
            'mono-layer1',
            'environmentDepthSampledLayerMask=0x2',
            'environmentDepthShaderLayerPolicy=mono-layer1',
            'environmentDepthHandRemovalRequested=false'
        )
    },
    @{
        Text = $environmentDepthRawDepthDebugProfile
        Label = "raw-depth-debug"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_raw_depth_debug',
            'quest-native-renderer-envdepth-raw-depth-debug.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.debug_view',
            'raw-d16',
            'environmentDepthDebugView=raw-d16',
            'environmentDepthParticleDebugColorMode=depth-gradient'
        )
    },
    @{
        Text = $environmentDepthLocalSpaceProfile
        Label = "local-space"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_local_space',
            'quest-native-renderer-envdepth-local-space.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.reference_space',
            'openxr-local',
            'environmentDepthReferenceSpace=openxr-local'
        )
    },
    @{
        Text = $environmentDepthStageSpaceProfile
        Label = "stage-space"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_stage_space',
            'quest-native-renderer-envdepth-stage-space.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.reference_space',
            'openxr-stage',
            'environmentDepthReferenceSpace=openxr-stage'
        )
    },
    @{
        Text = $environmentDepthCapacity65536Profile
        Label = "capacity-65536"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_capacity_65536',
            'quest-native-renderer-envdepth-capacity-65536.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.particle_capacity',
            '65536',
            'environmentDepthParticleCapacity=65536'
        )
    },
    @{
        Text = $environmentDepthStride8Profile
        Label = "stride-8"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_stride_8',
            'quest-native-renderer-envdepth-stride-8.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels',
            '8',
            'environmentDepthSampleStridePixels=8'
        )
    },
    @{
        Text = $environmentDepthHandRemovalProfile
        Label = "hand-removal"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_hand_removal',
            'quest-native-renderer-envdepth-hand-removal.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled',
            'true',
            'environmentDepthHandRemovalRequested=true',
            'environmentDepthHandRemovalSupported=true',
            'environmentDepthHandRemovalEnabled=true'
        )
    }
)) {
    foreach ($token in $profileCase.Tokens) {
        if ($profileCase.Text -notmatch [regex]::Escape($token)) {
            throw "Native renderer Iteration 8 environment-depth $($profileCase.Label) profile missing token: $token"
        }
    }
}

foreach ($profileCase in @(
    @{
        Text = $environmentDepthLocalSurfelsProfile
        Label = "local-surfels"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_local_surfels',
            'quest-native-renderer-envdepth-local-surfels.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.debug_view',
            'surface-support',
            'debug.rustyquest.native_renderer.environment_depth.surface_model',
            'local-surfels',
            'debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors',
            '"1"',
            'environmentDepthSurfaceModel=local-surfels',
            'environmentDepthSurfaceSupportRequested=true',
            'environmentDepthSurfaceSupportEnforced=false',
            'environmentDepthSurfaceSupportStatus=pending-gpu-support-pass'
        )
    },
    @{
        Text = $environmentDepthGlobalSurfacesProfile
        Label = "global-surfaces"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_global_surfaces',
            'quest-native-renderer-envdepth-global-surfaces.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.surface_model',
            'global-surfaces',
            'debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors',
            '"4"',
            'debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells',
            '"16"',
            'environmentDepthSurfaceModel=global-surfaces',
            'environmentDepthSurfaceMinNeighborCount=4',
            'environmentDepthSurfaceComponentMinCells=16',
            'environmentDepthSurfaceSupportStatus=pending-gpu-support-pass'
        )
    },
    @{
        Text = $environmentDepthHybridSurfacesProfile
        Label = "hybrid-surfaces"
        Tokens = @(
            'profile.quest.native_renderer.envdepth_hybrid_surfaces',
            'quest-native-renderer-envdepth-hybrid-surfaces.profile.json',
            'debug.rustyquest.native_renderer.environment_depth.surface_model',
            'hybrid',
            'debug.rustyquest.native_renderer.environment_depth.surface_support.free_space_decay',
            'hard',
            'environmentDepthSurfaceModel=hybrid',
            'environmentDepthSurfaceSupportMode=hybrid',
            'environmentDepthSurfaceFreeSpaceDecay=hard',
            'environmentDepthSurfaceSupportStatus=pending-gpu-support-pass'
        )
    }
)) {
    foreach ($token in $profileCase.Tokens) {
        if ($profileCase.Text -notmatch [regex]::Escape($token)) {
            throw "Native renderer environment-depth surface-support $($profileCase.Label) profile missing token: $token"
        }
    }
}

foreach ($token in @(
    'high_rate_json_payload',
    'true'
)) {
    if ($environmentDepthHighRateJsonDamagedProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer damaged environment-depth high-rate JSON profile missing token: $token"
    }
}
foreach ($token in @(
    'near_m',
    '2.0',
    'far_m',
    '1.0'
)) {
    if ($environmentDepthInvalidRangeDamagedProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer damaged environment-depth invalid-range profile missing token: $token"
    }
}
foreach ($token in @(
    'particle_capacity',
    '"0"'
)) {
    if ($environmentDepthInvalidCapacityDamagedProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer damaged environment-depth invalid-capacity profile missing token: $token"
    }
}
foreach ($token in @(
    'depth_units_policy',
    'metric-axial-meters'
)) {
    if ($environmentDepthInvalidDepthUnitsPolicyDamagedProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer damaged environment-depth invalid-depth-units-policy profile missing token: $token"
    }
}
foreach ($token in @(
    'surface_model',
    'global-surfaces',
    'surface_support.min_neighbors',
    '"99"'
)) {
    if ($environmentDepthInvalidSurfaceSupportDamagedProfile -notmatch [regex]::Escape($token)) {
        throw "Native renderer damaged environment-depth invalid surface-support profile missing token: $token"
    }
}
if ($checkAllText -notmatch 'quest-native-renderer-live-hand-visual-diagnostic\.profile\.json' -or $checkAllText -notmatch 'native-renderer-live-hand-visual-diagnostic-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer live hand diagnostic profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-camera-quality\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-camera-quality-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB camera quality profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-camera-quality-bt601-unorm\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-camera-quality-bt601-unorm-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB BT601/UNORM camera quality profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-low-noise-30\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-low-noise-30-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB low-noise 30 profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-low-noise-record-30\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-low-noise-record-30-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB low-noise record 30 profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-low-latency-60\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-low-latency-60-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB low-latency 60 profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-hold-sync\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-hold-sync-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB hold-sync profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-hold-sync-reader6\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-hold-sync-reader6-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB hold-sync reader 6 profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-hold-sync-reader8\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-hold-sync-reader8-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB hold-sync reader 8 profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-direct-hwb-1280x960\.profile\.json' -or $checkAllText -notmatch 'native-renderer-direct-hwb-1280x960-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer direct-HWB 1280x960 profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-hwb-peripheral-stretch\.profile\.json' -or $checkAllText -notmatch 'native-renderer-hwb-peripheral-stretch-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer HWB peripheral stretch profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-graft-only\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-graft-only-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough graft-only profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-hands-and-grafts\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-hands-and-grafts-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough hands-and-grafts profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-solid-black-hands-and-grafts\.profile\.json' -or $checkAllText -notmatch 'native-renderer-solid-black-hands-and-grafts-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer solid black hands-and-grafts profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-solid-black-openxr-hands-anchor-particles\.profile\.json' -or $checkAllText -notmatch 'native-renderer-solid-black-openxr-hands-anchor-particles-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer solid black OpenXR hands anchor particles profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-environment-depth-status\.profile\.json' -or $checkAllText -notmatch 'native-renderer-environment-depth-status-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer environment-depth status profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-environment-depth-particles\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-environment-depth-particles-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough environment-depth particle profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-meta-environment-depth-particles\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-meta-environment-depth-particles-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough Meta environment-depth particle profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-meta-environment-depth-particles-layer1-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough Meta environment-depth layer-1 particle profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough Meta environment-depth low-capacity particle profile."
}
if ($checkAllText -notmatch 'quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors\.profile\.json' -or $checkAllText -notmatch 'native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors-property-write-plan\.json') {
    throw "check_all.ps1 must dry-run the native renderer native passthrough Meta environment-depth debug-colors particle profile."
}
foreach ($profileFile in @(
    'quest-native-renderer-envdepth-layer0.profile.json',
    'quest-native-renderer-envdepth-layer1.profile.json',
    'quest-native-renderer-envdepth-raw-depth-debug.profile.json',
    'quest-native-renderer-envdepth-local-space.profile.json',
    'quest-native-renderer-envdepth-stage-space.profile.json',
    'quest-native-renderer-envdepth-capacity-65536.profile.json',
    'quest-native-renderer-envdepth-stride-8.profile.json',
    'quest-native-renderer-envdepth-hand-removal.profile.json'
)) {
    $outFile = $profileFile -replace '\.profile\.json$', '-property-write-plan.json'
    if ($checkAllText -notmatch [regex]::Escape($profileFile) -or $checkAllText -notmatch [regex]::Escape($outFile)) {
        throw "check_all.ps1 must dry-run the native renderer Iteration 8 environment-depth profile: $profileFile"
    }
}
foreach ($profileFile in @(
    'quest-native-renderer-envdepth-local-surfels.profile.json',
    'quest-native-renderer-envdepth-global-surfaces.profile.json',
    'quest-native-renderer-envdepth-hybrid-surfaces.profile.json'
)) {
    $outFile = $profileFile -replace '\.profile\.json$', '-property-write-plan.json'
    if ($checkAllText -notmatch [regex]::Escape($profileFile) -or $checkAllText -notmatch [regex]::Escape($outFile)) {
        throw "check_all.ps1 must dry-run the native renderer environment-depth surface-support profile: $profileFile"
    }
}

& $runtimeProfileToolPath `
    -ProfilePath $environmentDepthStatusProfilePath `
    -DryRun `
    -Out "local-artifacts\native-renderer-environment-depth-status-property-write-plan.json" | Out-Null

& $runtimeProfileToolPath `
    -ProfilePath $environmentDepthNativePassthroughParticlesProfilePath `
    -DryRun `
    -Out "local-artifacts\native-renderer-native-passthrough-environment-depth-particles-property-write-plan.json" | Out-Null

& $runtimeProfileToolPath `
    -ProfilePath $environmentDepthNativePassthroughMetaParticlesProfilePath `
    -DryRun `
    -Out "local-artifacts\native-renderer-native-passthrough-meta-environment-depth-particles-property-write-plan.json" | Out-Null

& $runtimeProfileToolPath `
    -ProfilePath $environmentDepthNativePassthroughMetaParticlesLayer1ProfilePath `
    -DryRun `
    -Out "local-artifacts\native-renderer-native-passthrough-meta-environment-depth-particles-layer1-property-write-plan.json" | Out-Null

& $runtimeProfileToolPath `
    -ProfilePath $environmentDepthNativePassthroughMetaParticlesLowCapacityProfilePath `
    -DryRun `
    -Out "local-artifacts\native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity-property-write-plan.json" | Out-Null

& $runtimeProfileToolPath `
    -ProfilePath $environmentDepthNativePassthroughMetaParticlesDebugColorsProfilePath `
    -DryRun `
    -Out "local-artifacts\native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors-property-write-plan.json" | Out-Null

foreach ($profileCase in @(
    @{ Path = $environmentDepthLayer0ProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-layer0-property-write-plan.json" },
    @{ Path = $environmentDepthLayer1ProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-layer1-property-write-plan.json" },
    @{ Path = $environmentDepthRawDepthDebugProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-raw-depth-debug-property-write-plan.json" },
    @{ Path = $environmentDepthLocalSpaceProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-local-space-property-write-plan.json" },
    @{ Path = $environmentDepthStageSpaceProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-stage-space-property-write-plan.json" },
    @{ Path = $environmentDepthCapacity65536ProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-capacity-65536-property-write-plan.json" },
    @{ Path = $environmentDepthStride8ProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-stride-8-property-write-plan.json" },
    @{ Path = $environmentDepthHandRemovalProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-hand-removal-property-write-plan.json" },
    @{ Path = $environmentDepthLocalSurfelsProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-local-surfels-property-write-plan.json" },
    @{ Path = $environmentDepthGlobalSurfacesProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-global-surfaces-property-write-plan.json" },
    @{ Path = $environmentDepthHybridSurfacesProfilePath; Out = "local-artifacts\quest-native-renderer-envdepth-hybrid-surfaces-property-write-plan.json" }
)) {
    & $runtimeProfileToolPath `
        -ProfilePath $profileCase.Path `
        -DryRun `
        -Out $profileCase.Out | Out-Null
}

foreach ($damagedProfile in @($environmentDepthHighRateJsonDamagedProfilePath, $environmentDepthInvalidRangeDamagedProfilePath, $environmentDepthInvalidCapacityDamagedProfilePath, $environmentDepthInvalidDepthUnitsPolicyDamagedProfilePath, $environmentDepthInvalidSurfaceSupportDamagedProfilePath)) {
    try {
        & $runtimeProfileToolPath `
            -ProfilePath $damagedProfile `
            -DryRun `
            -Out "local-artifacts\native-renderer-damaged-environment-depth-property-write-plan.json" | Out-Null
        throw "Damaged environment-depth runtime profile was accepted: $damagedProfile"
    } catch {
        if ($_.Exception.Message -like "Damaged environment-depth runtime profile was accepted:*") {
            throw
        }
    }
}

& $runtimeEvidenceToolPath `
    -LogcatPath $runtimeEvidenceFixturePath `
    -RequireCameraProjection `
    -RequireReplayVisualProof `
    -RequireGuideGraph `
    -RequireSdfVisual `
    -RequireGpuTimestampReady `
    -RequirePerformanceBudget `
    -RequirePrivateSlotNoPayload | Out-Null

& $runtimeEvidenceToolPath `
    -LogcatPath $environmentDepthParticlesEvidenceFixturePath `
    -RequireEnvironmentDepthParticles `
    -ExpectedEnvironmentDepthParticleCount 32768 `
    -MinimumEnvironmentDepthSourceDepthSamples 1 `
    -RequirePrivateSlotNoPayload | Out-Null

try {
    & $runtimeEvidenceToolPath `
        -LogcatPath $runtimeEvidenceDamagedPath `
        -RequireReplayVisualProof | Out-Null
    throw "Damaged native renderer runtime evidence fixture was accepted."
} catch {
    if ($_.Exception.Message -eq "Damaged native renderer runtime evidence fixture was accepted.") {
        throw
    }
}

try {
    & $runtimeEvidenceToolPath `
        -LogcatPath $runtimeEvidenceDamagedPerformancePath `
        -RequirePerformanceBudget | Out-Null
    throw "Damaged native renderer performance fixture was accepted."
} catch {
    if ($_.Exception.Message -eq "Damaged native renderer performance fixture was accepted.") {
        throw
    }
}

& $runtimeEvidenceToolPath `
    -LogcatPath $liveHandDiagnosticPendingFixturePath `
    -RequireLiveVisualDiagnosticCaveat | Out-Null

try {
    & $runtimeEvidenceToolPath `
        -LogcatPath $liveHandPrematureAcceptanceDamagedPath `
        -RequireLiveVisualDiagnosticCaveat | Out-Null
    throw "Damaged native renderer live-hand visual acceptance fixture was accepted."
} catch {
    if ($_.Exception.Message -eq "Damaged native renderer live-hand visual acceptance fixture was accepted.") {
        throw
    }
}

if ($readme -notmatch 'Rust NativeActivity' -or $readme -notmatch 'no app Java is packaged' -or $readme -notmatch 'real submitted OpenXR' -or $readme -notmatch 'input queue') {
    throw "Native renderer app README must document the Rust-native route and first-scaffold caveat."
}

if ($Build) {
    & (Join-Path $PSScriptRoot "Build-NativeRendererAndroid.ps1") -AndroidHome $AndroidHome -JavaHome $JavaHome -NdkHome $NdkHome | Out-Host
}

Write-Output "Rusty Quest native renderer Android validation passed"
