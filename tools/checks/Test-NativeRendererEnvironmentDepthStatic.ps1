param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$appRoot = Join-Path $repoRootPath "apps\native-renderer-android"
$nativeRoot = Join-Path $appRoot "native"
$srcRoot = Join-Path $nativeRoot "src"
$shaderRoot = Join-Path $nativeRoot "shaders"
$toolsRoot = Join-Path $repoRootPath "tools"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer environment-depth static file ($Label): $Path"
    }
    return Get-Content -Raw -Path $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch [regex]::Escape($token)) {
            throw "Native renderer environment-depth static check failed for ${Label}: missing token: $token"
        }
    }
}

function Assert-PowerShellParses {
    param(
        [string]$Path,
        [string]$Label
    )
    $parseTokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count -gt 0) {
        throw "Native renderer environment-depth static check failed for ${Label}: $($parseErrors[0].Message)"
    }
}

$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$nativeBuildRs = Read-RequiredText (Join-Path $nativeRoot "build.rs") "native build script"
$environmentDepthGeometry = Read-RequiredText (Join-Path $srcRoot "environment_depth_geometry.rs") "environment depth geometry"
$environmentDepthParticleStats = Read-RequiredText (Join-Path $srcRoot "gpu_environment_depth_particle_stats.rs") "environment depth particle stats"
$environmentDepthParticles = Read-RequiredText (Join-Path $srcRoot "gpu_environment_depth_particles.rs") "environment depth particle renderer"
$openxrEnvironmentDepth = Read-RequiredText (Join-Path $srcRoot "openxr_environment_depth.rs") "OpenXR environment depth"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_environment_depth_options.rs") "environment depth options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"
$environmentDepthParticleSourceSurface = "$environmentDepthParticleStats`n$environmentDepthParticles"
$environmentDepthParticlesComputeShader = Read-RequiredText (Join-Path $shaderRoot "environment_depth_particles_synthetic.comp.glsl") "synthetic particle compute shader"
$environmentDepthParticlesMetaComputeShader = Read-RequiredText (Join-Path $shaderRoot "environment_depth_particles_meta.comp.glsl") "Meta environment depth particle compute shader"
$environmentDepthParticlesVertexShader = Read-RequiredText (Join-Path $shaderRoot "environment_depth_particles.vert.glsl") "environment depth particle vertex shader"
$environmentDepthParticlesFragmentShader = Read-RequiredText (Join-Path $shaderRoot "environment_depth_particles.frag.glsl") "environment depth particle fragment shader"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats")
) -join "`n"

$runtimeEvidenceToolText = Read-RequiredText (Join-Path $toolsRoot "Test-NativeRendererRuntimeEvidence.ps1") "runtime evidence checker"
$runtimeSmokeToolText = Read-RequiredText (Join-Path $toolsRoot "Invoke-NativeRendererReplaySmoke.ps1") "runtime smoke runner"
$environmentDepthMotionProofToolPath = Join-Path $toolsRoot "Invoke-NativeRendererEnvironmentDepthMotionProof.ps1"
$environmentDepthMotionProofToolText = Read-RequiredText $environmentDepthMotionProofToolPath "environment depth motion proof wrapper"
$environmentDepthParticlesEvidenceFixtureText = Read-RequiredText (Join-Path $repoRootPath "fixtures\native-renderer\native-renderer-meta-environment-depth-particles.logcat.txt") "environment depth particle logcat fixture"
$environmentDepthSurfaceSupportEvidenceFixtureText = Read-RequiredText (Join-Path $repoRootPath "fixtures\native-renderer\native-renderer-meta-environment-depth-surface-support.logcat.txt") "environment depth surface support logcat fixture"

Assert-PowerShellParses $environmentDepthMotionProofToolPath "environment depth motion proof wrapper"

Assert-ContainsTokens $runtimeEvidenceToolText @(
    'RequireEnvironmentDepthParticles',
    'environment_depth_particles_checked',
    'RequireEnvironmentDepthSurfaceSupport',
    'environment_depth_surface_support_checked',
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
    'environmentDepthSource=xr-meta-environment-depth',
    'environmentDepthAcquireStatus=acquired',
    'environmentDepthParticleSource=xr-meta-environment-depth',
    'environmentDepthParticleCpuUploadBytes=0',
    'environmentDepthGpuBuffersResident=true',
    'environmentDepthWorldSpaceReady=true'
) "runtime evidence checker"

Assert-ContainsTokens $runtimeSmokeToolText @(
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json',
    'EnvironmentDepthParticles',
    'RequireEnvironmentDepthParticles',
    'RequireEnvironmentDepthSurfaceSupport',
    'ExpectedEnvironmentDepthParticleCount',
    'MinimumEnvironmentDepthSourceDepthSamples',
    'MinimumEnvironmentDepthHashProbeExhaustedCount',
    'MinimumEnvironmentDepthHeadMotionSamples',
    'MinimumEnvironmentDepthHeadMotionYawDeg',
    'MinimumEnvironmentDepthHeadMotionTranslationM',
    'environment_depth_particles_required'
) "runtime smoke environment-depth route"

Assert-ContainsTokens $environmentDepthParticlesEvidenceFixtureText @(
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
    'environmentDepthAcquireToRenderMs=',
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
) "Meta environment-depth particle fixture"

Assert-ContainsTokens $environmentDepthSurfaceSupportEvidenceFixtureText @(
    'environmentDepthDebugView=surface-support',
    'environmentDepthParticleDebugColorMode=surface-support',
    'environmentDepthSurfaceModel=local-surfels',
    'environmentDepthSurfaceSupportRequested=true',
    'environmentDepthSurfaceSupportEnforced=true',
    'environmentDepthSurfaceSupportStatus=enforced-local-depth-neighborhood-component-pending',
    'environmentDepthSurfaceLifecycleStatus=candidate-confirmed-local-depth-neighborhood',
    'environmentDepthSurfaceSupportedCells=552',
    'environmentDepthSurfaceRejectedIsolatedCells=36',
    'environmentDepthSourceLayerAgreementRequired=false',
    'environmentDepthSourceLayerAgreementCells=552',
    'environmentDepthSingleLayerOnlyCells=0',
    'environmentDepthSurfaceCandidateCells=0',
    'environmentDepthSurfaceConfirmedCells=552',
    'environmentDepthSurfacePromotedCells=0',
    'environmentDepthSurfaceCandidateRetiredCells=0'
) "Meta environment-depth surface-support fixture"

Assert-ContainsTokens "$nativeLib`n$environmentDepthGeometry`n$environmentDepthParticleSourceSurface`n$openxrEnvironmentDepth`n$nativeRendererOptionSurface" @(
    'mod environment_depth_geometry',
    'mod gpu_environment_depth_particle_stats',
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
    'environmentDepthSourceLayerAgreementRequired',
    'environmentDepthSourceLayerAgreementCells',
    'environmentDepthSingleLayerOnlyCells',
    'environmentDepthSurfaceSupportStatus',
    'environmentDepthSurfaceLifecycleStatus',
    'environmentDepthSurfaceCandidateCells'
) "environment-depth source surface"

Assert-ContainsTokens "$nativeBuildRs`n$nativeLib`n$environmentDepthParticleSourceSurface`n$openxrEnvironmentDepth`n$environmentDepthParticlesComputeShader`n$environmentDepthParticlesMetaComputeShader`n$environmentDepthParticlesVertexShader`n$environmentDepthParticlesFragmentShader`n$xrVulkanSurface" @(
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
    'environmentDepthSurfaceSupportEnforced',
    'environmentDepthSurfaceSupportedCells',
    'environmentDepthSurfaceRejectedIsolatedCells',
    'environmentDepthSurfaceLargestComponentCells',
    'environmentDepthSourceLayerAgreementRequired',
    'environmentDepthSourceLayerAgreementCells',
    'environmentDepthSingleLayerOnlyCells',
    'environmentDepthSurfaceSupportStatus',
    'environmentDepthSurfaceLifecycleStatus',
    'environmentDepthSurfaceCandidateCells',
    'environmentDepthSurfaceConfirmedCells',
    'environmentDepthSurfacePromotedCells',
    'environmentDepthSurfaceCandidateRetiredCells',
    'DEPTH_FLAG_SURFACE_SUPPORT_ENFORCED',
    'DEPTH_FLAG_SURFACE_SUPPORT_MIN_OBSERVATION_SHIFT',
    'RAW_DEBUG_SURFACE_SUPPORTED_CELLS',
    'RAW_DEBUG_SURFACE_REJECTED_ISOLATED_CELLS',
    'RAW_DEBUG_SURFACE_CANDIDATE_CELLS',
    'RAW_DEBUG_SURFACE_CONFIRMED_CELLS',
    'RAW_DEBUG_SURFACE_PROMOTED_CELLS',
    'RAW_DEBUG_SURFACE_CANDIDATE_RETIRED_CELLS',
    'RAW_DEBUG_SOURCE_LAYER_AGREEMENT_CELLS',
    'RAW_DEBUG_SINGLE_LAYER_ONLY_CELLS',
    'DEPTH_FLAG_SURFACE_SUPPORT_MIN_SOURCE_LAYERS_TWO',
    'surface_support_runtime_enforced',
    'candidate-confirmed-local-depth-neighborhood',
    'enforced-local-depth-neighborhood-component-pending',
    'particle_debug_color_mode',
    'particle_debug_color_code',
    'DEBUG_COLOR_FREE_SPACE_STATE',
    'DEBUG_COLOR_SURFACE_SUPPORT',
    'debug_particle_color',
    'depth_source_layer_index',
    'raw_depth_to_meters',
    'write_center_raw_debug_window',
    'accumulate_raw_debug_stats',
    'syntheticGpuProofRequested',
    'runtimeProviderRequested',
    'environment-depth-particles'
) "environment-depth particle GPU route"

Assert-ContainsTokens $environmentDepthMotionProofToolText @(
    'Invoke-NativeRendererReplaySmoke.ps1',
    'EvidenceMode',
    'EnvironmentDepthParticles',
    'AllowFlatScreenshot',
    'AllowPerformanceBudgetMiss',
    'MinimumHeadMotionSamples',
    'MinimumYawDeg',
    'MinimumTranslationM',
    'RequireEnvironmentDepthSurfaceSupport',
    'MinimumEnvironmentDepthHeadMotionSamples',
    'MinimumEnvironmentDepthHeadMotionYawDeg',
    'MinimumEnvironmentDepthHeadMotionTranslationM',
    'native-renderer-envdepth-motion-proof',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json',
    'Serial',
    'AdbServerPort',
    'SkipInstall',
    'StopAfterRun'
) "environment-depth motion proof tool"

$profilesRoot = Join-Path $repoRootPath "fixtures\runtime-profiles"
$damagedRoot = Join-Path $repoRootPath "fixtures\damaged"
$environmentDepthStatusProfile = Read-RequiredText (Join-Path $profilesRoot "quest-native-renderer-environment-depth-status.profile.json") "environment depth status profile"
$environmentDepthNativePassthroughParticlesProfile = Read-RequiredText (Join-Path $profilesRoot "quest-native-renderer-native-passthrough-environment-depth-particles.profile.json") "synthetic particle profile"
$environmentDepthNativePassthroughMetaParticlesProfile = Read-RequiredText (Join-Path $profilesRoot "quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json") "Meta particle profile"
$environmentDepthNativePassthroughMetaParticlesLayer1Profile = Read-RequiredText (Join-Path $profilesRoot "quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json") "Meta particle layer1 profile"
$environmentDepthNativePassthroughMetaParticlesLowCapacityProfile = Read-RequiredText (Join-Path $profilesRoot "quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json") "Meta particle low capacity profile"
$environmentDepthNativePassthroughMetaParticlesDebugColorsProfile = Read-RequiredText (Join-Path $profilesRoot "quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json") "Meta particle debug colors profile"

Assert-ContainsTokens $environmentDepthStatusProfile @(
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
) "environment-depth status profile"

Assert-ContainsTokens $environmentDepthNativePassthroughParticlesProfile @(
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
) "synthetic particle profile"

Assert-ContainsTokens $environmentDepthNativePassthroughMetaParticlesProfile @(
    'profile.quest.native_renderer.native_passthrough_meta_environment_depth_particles',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json',
    'native-passthrough-graft-only',
    'scene-particle-map',
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
    'environmentDepthAcquireToRenderMs=',
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
) "Meta particle profile"

Assert-ContainsTokens $environmentDepthNativePassthroughMetaParticlesLayer1Profile @(
    'profile.quest.native_renderer.native_passthrough_meta_environment_depth_particles_layer1',
    'quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json',
    'debug.rustyquest.native_renderer.environment_depth.layer_policy',
    'mono-layer1',
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
) "Meta particle layer1 profile"

Assert-ContainsTokens $environmentDepthNativePassthroughMetaParticlesLowCapacityProfile @(
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
) "Meta particle low capacity profile"

Assert-ContainsTokens $environmentDepthNativePassthroughMetaParticlesDebugColorsProfile @(
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
) "Meta particle debug-colors profile"

foreach ($profileCase in @(
    @{ File = "quest-native-renderer-envdepth-layer0.profile.json"; Label = "layer0"; Tokens = @('profile.quest.native_renderer.envdepth_layer0','mono-layer0','environmentDepthSampledLayerMask=0x1','environmentDepthShaderLayerPolicy=mono-layer0','environmentDepthHandRemovalRequested=false') },
    @{ File = "quest-native-renderer-envdepth-layer1.profile.json"; Label = "layer1"; Tokens = @('profile.quest.native_renderer.envdepth_layer1','mono-layer1','environmentDepthSampledLayerMask=0x2','environmentDepthShaderLayerPolicy=mono-layer1','environmentDepthHandRemovalRequested=false') },
    @{ File = "quest-native-renderer-envdepth-raw-depth-debug.profile.json"; Label = "raw-depth-debug"; Tokens = @('profile.quest.native_renderer.envdepth_raw_depth_debug','raw-d16','environmentDepthDebugView=raw-d16','environmentDepthParticleDebugColorMode=depth-gradient') },
    @{ File = "quest-native-renderer-envdepth-local-space.profile.json"; Label = "local-space"; Tokens = @('profile.quest.native_renderer.envdepth_local_space','openxr-local','environmentDepthReferenceSpace=openxr-local') },
    @{ File = "quest-native-renderer-envdepth-stage-space.profile.json"; Label = "stage-space"; Tokens = @('profile.quest.native_renderer.envdepth_stage_space','openxr-stage','environmentDepthReferenceSpace=openxr-stage') },
    @{ File = "quest-native-renderer-envdepth-capacity-65536.profile.json"; Label = "capacity-65536"; Tokens = @('profile.quest.native_renderer.envdepth_capacity_65536','65536','environmentDepthParticleCapacity=65536') },
    @{ File = "quest-native-renderer-envdepth-stride-8.profile.json"; Label = "stride-8"; Tokens = @('profile.quest.native_renderer.envdepth_stride_8','8','environmentDepthSampleStridePixels=8') },
    @{ File = "quest-native-renderer-envdepth-hand-removal.profile.json"; Label = "hand-removal"; Tokens = @('profile.quest.native_renderer.envdepth_hand_removal','debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled','true','environmentDepthHandRemovalRequested=true','environmentDepthHandRemovalSupported=true','environmentDepthHandRemovalEnabled=true') },
    @{ File = "quest-native-renderer-envdepth-local-surfels.profile.json"; Label = "local-surfels"; Tokens = @('profile.quest.native_renderer.envdepth_local_surfels','surface-support','local-surfels','"1"','environmentDepthSurfaceModel=local-surfels','environmentDepthSurfaceSupportRequested=true','environmentDepthSurfaceSupportEnforced=false','environmentDepthSourceLayerAgreementRequired=false','environmentDepthSourceLayerAgreementCells=0','environmentDepthSingleLayerOnlyCells=0','environmentDepthSurfaceSupportStatus=pending-gpu-support-pass','environmentDepthSurfaceLifecycleStatus=pending-runtime-support','environmentDepthSurfaceCandidateCells=0') },
    @{ File = "quest-native-renderer-envdepth-global-surfaces.profile.json"; Label = "global-surfaces"; Tokens = @('profile.quest.native_renderer.envdepth_global_surfaces','global-surfaces','"4"','"16"','environmentDepthSurfaceModel=global-surfaces','environmentDepthSurfaceMinNeighborCount=4','environmentDepthSourceLayerAgreementRequired=false','environmentDepthSurfaceComponentMinCells=16','environmentDepthSurfaceSupportStatus=pending-gpu-support-pass','environmentDepthSurfaceLifecycleStatus=pending-runtime-support') },
    @{ File = "quest-native-renderer-envdepth-hybrid-surfaces.profile.json"; Label = "hybrid-surfaces"; Tokens = @('profile.quest.native_renderer.envdepth_hybrid_surfaces','hybrid','hard','environmentDepthSurfaceModel=hybrid','environmentDepthSurfaceSupportMode=hybrid','environmentDepthSurfaceFreeSpaceDecay=hard','environmentDepthSourceLayerAgreementRequired=false','environmentDepthSurfaceSupportStatus=pending-gpu-support-pass','environmentDepthSurfaceLifecycleStatus=pending-runtime-support') },
    @{ File = "quest-native-renderer-envdepth-source-layer-agreement.profile.json"; Label = "source-layer-agreement"; Tokens = @('profile.quest.native_renderer.envdepth_source_layer_agreement','source-layer','"2"','environmentDepthSurfaceModel=global-surfaces','environmentDepthSurfaceMinSourceLayerCount=2','environmentDepthSourceLayerAgreementRequired=true','environmentDepthSourceLayerAgreementCells=0','environmentDepthSingleLayerOnlyCells=0','environmentDepthSurfaceSupportStatus=pending-gpu-support-pass','environmentDepthSurfaceLifecycleStatus=pending-runtime-support') }
)) {
    $profileText = Read-RequiredText (Join-Path $profilesRoot $profileCase.File) $profileCase.Label
    Assert-ContainsTokens $profileText $profileCase.Tokens "environment-depth profile $($profileCase.Label)"
}

foreach ($damagedCase in @(
    @{ File = "native-renderer-environment-depth-high-rate-json.profile.json"; Label = "high-rate JSON"; Tokens = @('high_rate_json_payload','true') },
    @{ File = "native-renderer-environment-depth-invalid-range.profile.json"; Label = "invalid range"; Tokens = @('near_m','2.0','far_m','1.0') },
    @{ File = "native-renderer-environment-depth-invalid-capacity.profile.json"; Label = "invalid capacity"; Tokens = @('particle_capacity','"0"') },
    @{ File = "native-renderer-environment-depth-invalid-depth-units-policy.profile.json"; Label = "invalid depth-units policy"; Tokens = @('depth_units_policy','metric-axial-meters') },
    @{ File = "native-renderer-environment-depth-invalid-surface-support.profile.json"; Label = "invalid surface support"; Tokens = @('surface_model','global-surfaces','surface_support.min_neighbors','"99"') }
)) {
    $damagedText = Read-RequiredText (Join-Path $damagedRoot $damagedCase.File) "damaged environment-depth $($damagedCase.Label) profile"
    Assert-ContainsTokens $damagedText $damagedCase.Tokens "damaged environment-depth $($damagedCase.Label) profile"
}

Write-Host "Rusty Quest native renderer environment-depth static validation passed"
