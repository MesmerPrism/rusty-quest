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

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer hand-visual static file ($Label): $Path"
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
        if ($Text -notmatch $token) {
            throw "Native renderer hand-visual static check failed for ${Label}: missing token: $token"
        }
    }
}

$nativeBuildRs = Read-RequiredText (Join-Path $nativeRoot "build.rs") "native build script"
$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$recordedHandReplayFixture = Read-RequiredText `
    (Join-Path $repoRootPath "fixtures\native-renderer\recorded-hand-replay-public-shape.json") `
    "recorded hand replay fixture"
$recordedHandReplayModule = Read-RequiredText `
    (Join-Path $srcRoot "recorded_hand_replay.rs") `
    "recorded hand replay source"
$liveHandCompact = Read-RequiredText (Join-Path $srcRoot "live_hand_compact.rs") "live hand compact input"
$liveHandJointCapture = Read-RequiredText (Join-Path $srcRoot "live_hand_joint_capture.rs") "live hand joint capture"
$liveHandMeshCapture = Read-RequiredText (Join-Path $srcRoot "live_hand_mesh_capture.rs") "live hand mesh capture"
$handJointCaptureTool = Read-RequiredText `
    (Join-Path $repoRootPath "tools\Invoke-NativeRendererHandJointCapture.ps1") `
    "hand joint capture CLI"
$handMeshCaptureTool = Read-RequiredText `
    (Join-Path $repoRootPath "tools\Invoke-NativeRendererHandMeshCapture.ps1") `
    "hand mesh capture CLI"
$gpuHandMeshVisual = Read-RequiredText (Join-Path $srcRoot "gpu_hand_mesh_visual.rs") "GPU hand mesh visual"
$gpuMeshReplay = Read-RequiredText (Join-Path $srcRoot "gpu_mesh_replay.rs") "GPU mesh replay"
$handMeshGraft = Read-RequiredText (Join-Path $srcRoot "hand_mesh_graft.rs") "hand mesh graft"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_camera_options.rs") "native renderer camera options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_visual_options.rs") "native renderer visual options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"
$handMeshVisualVertex = Read-RequiredText `
    (Join-Path $shaderRoot "hand_mesh_visual.vert.glsl") `
    "hand mesh visual vertex shader"
$handMeshVisualFragment = Read-RequiredText `
    (Join-Path $shaderRoot "hand_mesh_visual.frag.glsl") `
    "hand mesh visual fragment shader"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"

Assert-ContainsTokens $recordedHandReplayFixture @(
    'rusty\.quest\.native_renderer\.recorded_hand_replay_source\.v1',
    'public-recorded-hand-topology-shape',
    'openxr-fb-handmesh-v1-j26-v1360-i6942',
    'bind-mesh-plus-compact-joint-frame',
    '"topology_vertex_count": 1360'
) "recorded hand replay fixture"

Assert-ContainsTokens "$recordedHandReplayModule`n$nativeBuildRs`n$xrVulkanSurface" @(
    'RecordedHandReplaySummary',
    'RecordedHandReplaySet',
    'recorded_hand_replay_source\.v1',
    'recorded_hand_replay_source\.json',
    'RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR',
    'rig_json',
    'clip_jsonl',
    'validation_mesh_jsonl',
    'replayModes=recorded-mesh-validation-frames,recorded-joints-skin-live',
    'recordedMeshReplayMode=validation_mesh_jsonl',
    'recordedJointReplayMode=clip_jsonl-compact-joint-skinning',
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
) "recorded hand replay route"

Assert-ContainsTokens "$nativeBuildRs`n$nativeLib`n$recordedHandReplayModule`n$nativeRendererOptionSurface`n$gpuHandMeshVisual`n$handMeshGraft`n$handMeshVisualVertex`n$handMeshVisualFragment`n$xrVulkanSurface" @(
    'mod native_renderer_visual_options',
    'mod native_renderer_options',
    'mod native_renderer_options_tests',
    'mod gpu_hand_mesh_visual',
    'GpuHandMeshVisualRenderer',
    'HandMeshVisualEyeProjection',
    'GpuHandMeshVisualFrameStats',
    'GpuHandMeshVisualFrameSetStats',
    'HandMeshVisualDiagnosticSettings',
    'HandMeshVisualMaterialSettings',
    'HandMeshVisualMaterialProfile',
    'HandMeshVisualMeshSource',
    'hand_mesh_visual.vert.glsl',
    'hand_mesh_visual.frag.glsl',
    'handMeshVisualPath=compact-joint-gpu-skinned-resident-selected-mesh-triangle-draw',
    'recordedSkinnedMeshFrameSource=compact_joint_gpu_skinning',
    'animatedHandMeshVisualReady',
    'animatedHandMeshVisualVisible',
    'handMeshVisualMeshSourceProperty',
    'handMeshVisualMeshSourceSelection',
    'handMeshVisualResolvedMeshSource',
    'handMeshVisualMeshSourceAvailable',
    'handMeshVisualReadinessReason',
    'handMeshVisualHotload=true',
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
    'handMeshVisualMaterial=unity-basic-procedural-surface',
    'handMeshVisualMaterialProfile=unity-basic-reference',
    'handMeshVisualMaterialProfiles=unity-basic-reference,mint-rim,flat-gray',
    'handMeshVisualMaterialSource=procedural-reference-not-unity-asset',
    'handMeshVisualUnityReference=BasicHandMaterial',
    'handMeshVisualUnityTextureReference=HandTracking_uvmap_2048',
    'handMeshVisualTextureImported=false',
    'handMeshVisualMaterialBaseColor',
    'handMeshVisualMaterialAlpha',
    'handMeshVisualMaterialRimStrength',
    'handMeshVisualWireframeAvailable=true',
    'handMeshVisualWireframeEnabled',
    'handMeshVisualWireframeWidthPx',
    'handMeshVisualWireframeMode=shader-barycentric-triangle-edges',
    'handMeshVisualWireframeLinePath=fragment-derivative-anti-aliased',
    'handMeshVisualWireframeTopologySource=resident-selected-mesh-triangle-indices',
    'handMeshVisualWireframeHotloadProperties',
    'vulkanWideLinesRequired=false',
    'handMeshVisualFresnelApproximation=normal-facing-rim',
    'handMeshVisualDepthPolicy=overlay-no-depth',
    'handMeshVisualDepthTest=false',
    'handMeshVisualDepthWrite=false',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.profile',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.r',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.g',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.b',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.rim_strength',
    'debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source',
    'debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.width_px',
    'vec4 material',
    'v_barycentric',
    'wireframe_edge_alpha',
    'fwidth',
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
) "GPU hand mesh visual route"

Assert-ContainsTokens "$nativeLib`n$liveHandCompact`n$xrVulkanSurface" @(
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
) "live Meta hand compact route"

Assert-ContainsTokens "$nativeLib`n$xrVulkanSurface`n$liveHandJointCapture`n$handJointCaptureTool" @(
    'mod live_hand_joint_capture',
    'LiveHandJointCaptureRecorder',
    'update_and_record',
    'finish_active',
    'hand-joint-capture-control\.json',
    'hand-joint-captures',
    'rusty\.quest\.native_renderer\.hand_joint_capture_control\.v1',
    'rusty\.quest\.native_renderer\.hand_joint_capture_manifest\.v1',
    'rusty\.quest\.native_renderer\.hand_joint_frame\.v1',
    'replay_mode = "recorded-joints-skin-live"',
    'companion_mesh_replay = "validation_mesh_jsonl"',
    'left\.clip\.jsonl',
    'right\.clip\.jsonl',
    'runtime_provider": "XR_EXT_hand_tracking"',
    'requires_hand_mesh_rig_for_skinning',
    'hand_material',
    'wireframe_enabled',
    'WireframeWidthPx',
    'Invoke-AdbCommand',
    'Prepare-MaterialLiveRun',
    'PullAndInspect',
    'hand-joint-capture-inspection\.json',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.visual\.material\.profile',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.visual\.mesh_source',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.visual\.wireframe\.enabled',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.visual\.wireframe\.width_px',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.real_hands\.visible',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.input\.source'
) "live hand joint capture route"

Assert-ContainsTokens "$nativeLib`n$xrVulkanSurface`n$liveHandMeshCapture`n$handMeshCaptureTool" @(
    'mod live_hand_mesh_capture',
    'LiveHandMeshCaptureRecorder',
    'XR_FB_hand_tracking_mesh',
    'fb_hand_tracking_mesh',
    'xrGetHandMeshFB',
    'hand-mesh-capture-control\.json',
    'hand-mesh-captures',
    'rusty\.quest\.native_renderer\.hand_mesh_capture_control\.v1',
    'rusty\.quest\.native_renderer\.hand_mesh_capture_manifest\.v1',
    'rusty\.matter\.hand_mesh_rig\.v1',
    'rusty\.matter\.hand_joint_frame\.v1',
    'rusty\.matter\.hand_validation_frame\.v1',
    'left\.rig\.json',
    'right\.rig\.json',
    'left\.validation_mesh\.jsonl',
    'right\.validation_mesh\.jsonl',
    'recorded-mesh-validation-frames',
    'recorded-joints-skin-live',
    'hand_visual_mesh_source',
    'debug\.rustyquest\.native_renderer\.hand_mesh\.visual\.mesh_source',
    'hand-mesh-capture-inspection\.json'
) "live OpenXR FB hand mesh capture route"

Assert-ContainsTokens "$gpuMeshReplay`n$xrVulkanSurface" @(
    'GpuMeshReplayResources',
    'GpuMeshReplayStats',
    'create_buffer',
    'STORAGE_BUFFER',
    'HOST_VISIBLE',
    'sourceMeshBuffersResident',
    'gpuMeshPath=native-vulkan-storage-buffer',
    'sourceMeshToSdfKernel=false',
    'cpuSdfPerFrame=false'
) "GPU mesh replay boundary"

Write-Host "Rusty Quest native renderer hand-visual static validation passed"
