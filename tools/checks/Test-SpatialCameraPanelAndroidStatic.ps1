param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required Spatial Camera Panel file: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if ($Text.Contains($Needle)) {
        throw "$Label contains forbidden private-boundary token: $Needle"
    }
}

$appGradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$manifest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$laneBoundary = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSdkLaneBoundary.kt"
$publicMultiStack = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPublicMultiStack.kt"
$panelController = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ExperimentPanelController.kt"
$panelModels = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelModels.kt"
$avatarFeature = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAvatarHandVisualFeature.kt"
$store = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelStore.kt"
$nativeLib = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\lib.rs"
$nativeBuildScript = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\build.rs"
$cameraProbe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$cameraProjectionTarget = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_projection_target.rs"
$nativeMultiStack = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack.rs"
$nativeMultiStackRuntime = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack_runtime.rs"
$nativeControllerActions = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_controller_actions.rs"
$nativeMultimodalInput = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_multimodal_input.rs"
$publicGuideBlurShader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\public_guide_blur.frag.glsl"
$cameraStream = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_stream.rs"
$cameraWsi = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_wsi.rs"
$surfaceLayer = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\surface_particle_layer.rs"
$replayHands = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\replay_hands.rs"
$buildScript = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"
$cameraProjectionSmoke = Read-RequiredText "tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
$readme = Read-RequiredText "apps\spatial-camera-panel-android\README.md"
$notes = Read-RequiredText "docs\SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md"

Assert-Contains "Gradle app" $appGradle 'namespace = "io.github.mesmerprism.rustyquest.spatial_camera_panel"'
Assert-Contains "Gradle app" $appGradle 'applicationId = "io.github.mesmerprism.rustyquest.spatial_camera_panel"'
Assert-Contains "Android manifest" $manifest 'android:name=".SpatialCameraPanelActivity"'
Assert-Contains "Android manifest" $manifest 'com.oculus.permission.HAND_TRACKING'
Assert-Contains "Android manifest" $manifest 'oculus.software.handtracking'
Assert-Contains "Android manifest" $manifest 'com.oculus.handtracking.version'
Assert-Contains "Android manifest" $manifest 'com.oculus.feature.RENDER_MODEL'
Assert-Contains "Android manifest" $manifest 'com.oculus.permission.RENDER_MODEL'
Assert-Contains "Activity" $activity "class SpatialCameraPanelActivity : AppSystemActivity()"
Assert-Contains "Activity" $activity "SceneSwapchain.createAsAndroid"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe"
Assert-Contains "Activity" $activity "outputMode=raw-color-target-rect"
Assert-Contains "Activity" $activity "SpatialSdkLaneBoundaries.summaryToken()"
Assert-Contains "Activity" $activity "SpatialPublicMultiStack.markerFields()"
Assert-Contains "Activity" $activity "SpatialPublicMultiStack.inactiveMarkerFields()"
Assert-Contains "Activity" $activity "cameraStackSuppressesParticles"
Assert-Contains "Activity" $activity 'suppressParticleLayerIfCameraProjectionRequested("activity-created")'
Assert-Contains "Activity" $activity 'suppressParticleLayerIfCameraProjectionRequested("new-intent")'
Assert-Contains "Activity" $activity 'suppressParticleLayerForCameraStack("camera-hwb-projection-probe")'
Assert-Contains "Activity" $activity "applyCameraHwbProjectionStereoHorizontalOffsetJoystickInput"
Assert-Contains "Activity" $activity "applyCameraHwbProjectionStereoHorizontalOffsetInput"
Assert-Contains "Activity" $activity 'CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER = "1.00"'
Assert-Contains "Activity" $activity 'targetDistanceDefaultMeters=$CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER'
Assert-Contains "Activity" $activity "targetDistanceJoystickControlsEnabled=false"
Assert-Contains "Activity" $activity "stereoHorizontalOffsetJoystickInput=spatial-sdk-avatar-body-left-thumb-up-down-or-android-left-stick-y;panel-visibility-independent;native-openxr-diagnostic-opt-in"
Assert-Contains "Activity" $activity "cameraHwbProjectionStereoHorizontalOffsetIgnoresPanelVisibility=true"
Assert-Contains "Activity" $activity "projectionTargetStereoHorizontalOffsetUv="
Assert-Contains "Activity" $activity "projectionTargetStereoHorizontalOffsetDefaultUv="
Assert-Contains "Activity" $activity "CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV = 0.046320f"
Assert-Contains "Activity" $activity "projectionTargetLeftOffsetUv="
Assert-Contains "Activity" $activity "projectionTargetRightOffsetUv="
Assert-Contains "Activity" $activity "nativeUpdateCameraHwbProjectionStereoOffsetUv"
Assert-Contains "Activity" $activity "nativePanelPoseAuthority=camera-hwb-projection-plane"
Assert-Contains "Activity" $activity "projection-plane-update-suppressed"
Assert-Contains "Activity" $activity "updateNativePanelProjectionFromCameraPlane"
Assert-Contains "Activity" $activity "ButtonThumbLU"
Assert-Contains "Activity" $activity "ButtonThumbLD"
Assert-Contains "Activity" $activity "left-thumb-up-down-stereo-horizontal-offset"
Assert-Contains "Activity" $activity "SPATIAL_VR_INPUT_SYSTEM_PROPERTY"
Assert-Contains "Activity" $activity "SpatialControllerInputLateFeature(::pollSpatialControllerInput)"
Assert-Contains "Activity" $activity "nativeStartSpatialControllerActions"
Assert-Contains "Activity" $activity "nativePollSpatialControllerLeftThumbstickY"
Assert-Contains "Activity" $activity "nativeSpatialControllerActionsEnabled()"
Assert-Contains "Activity" $activity "NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED = false"
Assert-Contains "Activity" $activity "native-openxr-action"
Assert-Contains "Activity" $activity "left-thumbstick-y-stereo-horizontal-offset"
Assert-Contains "Activity" $activity "override fun registerRequiredOpenXRExtensions()"
Assert-Contains "Activity" $activity "XR_META_simultaneous_hands_and_controllers"
Assert-Contains "Activity" $activity "XR_META_detached_controllers"
Assert-Contains "Activity" $activity "spatialRequiredOpenXrExtensions="
Assert-Contains "Activity" $activity 'SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN = "interaction_sdk"'
Assert-Contains "Activity" $activity "SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_DEFAULT = false"
Assert-Contains "Activity" $activity "SPATIAL_MULTIMODAL_INPUT_DEFAULT_ENABLED = false"
Assert-Contains "Activity" $activity "spatialControllerOnlyMode=false"
Assert-Contains "Activity" $activity "spatialHandsAndControllersManifest=true"
Assert-Contains "Activity" $activity "spatialPointerInputExpected=true"
Assert-Contains "Activity" $activity "NATIVE_SPATIAL_CONTROLLER_ACTION_SET_ATTACHED_BIT"
Assert-Contains "Activity" $activity "enableSpatialControllerInputRoute"
Assert-Contains "Activity" $activity "scene.spatialInterface.enableInput(true)"
Assert-Contains "Activity" $activity "pinGameController"
Assert-Contains "Activity" $activity "status=spatial-input-enabled"
Assert-Contains "Activity" $activity "eyeSpaceTargetRectPreserved=true"
Assert-Contains "Activity" $activity "projectionPlaneAngularCoveragePreserved=true"
Assert-Contains "Activity" $activity "layer.updateLayer"
Assert-Contains "Activity" $activity "!panelPlacement.visible && !cameraStackSuppressesParticles"
Assert-Contains "Activity" $activity "status=particle-layer-suppressed"
Assert-Contains "Activity" $activity "status=start-suppressed"
Assert-Contains "Activity" $activity "cameraHwbProjectionRightPackedEffectiveTargetRectMarker"
Assert-Contains "Activity" $activity 'CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_X = 0.078125f'
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SpatialSdkLayerCarrier"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object ExperimentPanelControllerBoundary"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object CameraProjectionProbeController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object PublicMultiStackController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "kind = SpatialSdkLaneKind.PublicMultiStack"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SurfaceParticleLayerController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SpatialDebugProbeController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary 'mustNotOwn = setOf("surface particles", "driver-profile dynamics", "questionnaire state")'
Assert-Contains "Public multi-stack" $publicMultiStack 'rusty.quest.spatial_camera_panel.public_multistack.v1'
Assert-Contains "Public multi-stack" $publicMultiStack 'publicMultiStackLayerCount=$LAYER_COUNT'
Assert-Contains "Public multi-stack" $publicMultiStack 'publicMultiStackGuidePasses=$GUIDE_PASS_COUNT'
Assert-Contains "Public multi-stack" $publicMultiStack 'publicMultiStackPublicBlurPasses=$PUBLIC_BLUR_PASS_COUNT'
Assert-Contains "Public multi-stack" $publicMultiStack "publicMultiStackGuideTargetManifest"
Assert-Contains "Public multi-stack" $publicMultiStack "publicMultiStackGuidePassManifest"
Assert-Contains "Public multi-stack" $publicMultiStack "publicGuideBlurShader=public_guide_blur.frag.glsl"
Assert-Contains "Public multi-stack" $publicMultiStack "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER"
Assert-Contains "Public multi-stack" $publicMultiStack "public-guide-blur"
Assert-Contains "Public multi-stack" $publicMultiStack "publicMultiStackDownstreamPayloadActive=false"
Assert-Contains "Experiment panel controller" $panelController "internal object ExperimentPanelController"
Assert-Contains "Experiment panel controller" $panelController 'highRatePayloadPolicy: String = "forbidden"'
Assert-Contains "Experiment panel controller" $panelController "internal fun SpatialCameraPanel("
Assert-Contains "Experiment panel controller" $panelController "internal fun SpatialCameraPanelLauncher("
Assert-Contains "Panel models" $panelModels "data class PanelPlacement"
Assert-Contains "Panel models" $panelModels "data class SurfaceParticleControlState"
Assert-Contains "Panel models" $panelModels "data class SpatialNativeInteropProbe"
Assert-Contains "Avatar feature" $avatarFeature "internal class SpatialAvatarHandVisualFeature"
Assert-Contains "Store" $store 'SESSION_SCHEMA = "rusty.quest.spatial_camera_panel.session.v1"'
Assert-Contains "Store" $store 'EVENT_SCHEMA = "rusty.quest.spatial_camera_panel.event.v1"'
Assert-Contains "Store" $store 'QUESTIONNAIRE_SCHEMA = "rusty.quest.spatial_camera_panel.questionnaire.v1"'
Assert-Contains "Store" $store "driver0_value01"
Assert-Contains "Store" $store "driver1_value01"
Assert-Contains "Store" $store "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1"
Assert-Contains "Store" $store "rusty.quest.spatial_camera_panel.driver_profile.profile-d.v1"
Assert-Contains "Native receipt" $nativeLib "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordNoRenderInteropReceipt"
Assert-Contains "Native receipt" $nativeLib "mod spatial_controller_actions"
Assert-Contains "Native receipt" $nativeLib "mod spatial_public_multistack"
Assert-Contains "Native receipt" $nativeLib "mod spatial_public_multistack_runtime"
Assert-Contains "Native receipt build script" $nativeBuildScript "public_guide_blur.frag.glsl"
Assert-Contains "Native receipt build script" $nativeBuildScript "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER"
Assert-Contains "Native receipt build script" $nativeBuildScript "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER"
Assert-Contains "Native receipt build script" $nativeBuildScript "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT"
Assert-Contains "Native receipt build script" $nativeBuildScript "compile_optional_shader_env"
Assert-Contains "Native receipt build script" $nativeBuildScript "compile_optional_guide_shader_env"
Assert-Contains "Native receipt build script" $nativeBuildScript "PRIVATE_LAYER_GUIDE_PASS_MODE"
Assert-Contains "Native receipt build script" $nativeBuildScript "spatial_opaque_guide_pass_"
Assert-Contains "Native receipt build script" $nativeBuildScript "write_spatial_multistack_build_metadata"
Assert-Contains "Native receipt build script" $nativeBuildScript "spatial_public_multistack_build.rs"
Assert-Contains "Native receipt build script" $nativeBuildScript "PUBLIC_GUIDE_BLUR_SHADER_BYTE_COUNT"
Assert-Contains "Native receipt build script" $nativeBuildScript "OPAQUE_GUIDE_SHADER_PASS_BYTE_COUNTS"
Assert-Contains "Native receipt build script" $nativeBuildScript "OPAQUE_PROJECTION_EFFECT"
Assert-Contains "Camera HWB probe" $cameraProbe "outputMode=raw-color-target-rect"
Assert-Contains "Camera HWB probe" $cameraProbe "privateShaderStack=false"
Assert-Contains "Camera HWB probe" $cameraProbe "projection_contract_marker_fields"
Assert-Contains "Camera HWB probe" $cameraProbe "public_multistack_marker_fields"
Assert-Contains "Camera HWB probe" $cameraProbe "allocate_spatial_public_guide_targets"
Assert-Contains "Camera HWB probe" $cameraProbe "status=public-multistack-guide-targets-ready"
Assert-Contains "Camera HWB probe" $cameraProbe "status=public-multistack-guide-targets-skipped"
Assert-Contains "Camera projection target" $cameraProjectionTarget "projectionContentMappingMode=target-local-raster"
Assert-Contains "Camera projection target" $cameraProjectionTarget "update_camera_hwb_projection_stereo_horizontal_offset_uv"
Assert-Contains "Camera projection target" $cameraProjectionTarget "projectionTargetStereoHorizontalOffsetUv="
Assert-Contains "Camera projection target" $cameraProjectionTarget "CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV: f32 = 0.046320"
Assert-Contains "Native public multi-stack" $nativeMultiStack 'rusty.quest.spatial_camera_panel.public_multistack.v1'
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackLayerCount=7"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackGuidePasses=6"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackPublicBlurPasses=4"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackGuideTargetManifest"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackGuidePassManifest"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicGuideBlurShader=public_guide_blur.frag.glsl"
Assert-Contains "Native public multi-stack" $nativeMultiStack 'include!(concat!'
Assert-Contains "Native public multi-stack" $nativeMultiStack 'spatial_public_multistack_build.rs'
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicGuideBlurShaderBytes="
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackPassExecutionReady=false"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackOpaqueGuideShaderCompiled="
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackOpaqueProjectionShaderCompiled="
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackOpaqueGuideShaderPassCount="
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackOpaqueGuideShaderPassVariantsCompiled="
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackOpaqueGuideShaderPassByteCounts="
Assert-Contains "Native public multi-stack" $nativeMultiStack "OPAQUE_GUIDE_SHADER_PASS_COUNT"
Assert-Contains "Native public multi-stack" $nativeMultiStack "OPAQUE_GUIDE_SHADER_PASS_BYTE_COUNTS"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackOpaquePayloadExecutionReady=false"
Assert-Contains "Native public multi-stack" $nativeMultiStack "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER"
Assert-Contains "Native public multi-stack" $nativeMultiStack "public-guide-blur"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackDownstreamPayloadActive=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "SPATIAL_PUBLIC_GUIDE_TARGET_COUNT"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "SPATIAL_PUBLIC_GUIDE_TARGET_FORMAT"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "allocate_spatial_public_guide_targets"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "SpatialPublicGuideTargets"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuideTargetsAllocated=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuideTargetsAllocated=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackPackedStereoGuides=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "record_spatial_public_guide_passes"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuidePassSchedule="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuidePassResourcesReady=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuidePassResourcesReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuideFramebuffers="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackGuideSampleDescriptorSets="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_guide_render_pass"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_sample_descriptor_set_layout"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_public_blur_pipeline"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "record_public_blur_pass"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "PublicGuideBlurDirection"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "source_rect"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "packed_eye_source_rect"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "packed_projection_target_rect"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "set_packed_projection_target_view"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "target_rect_to_scissor"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "camera_hwb_projection_push"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "packed_projection_target_rects_match_camera_projection_push"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "packed_projection_scissors_clip_to_native_target_footprint"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueProjectionTargetSpace=packed-stereo-surface-uv"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicGuideBlurPipelineReady=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicGuideBlurPipelineReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicGuideBlurRecordFunctionReady=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicGuideBlurRecordFunctionReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueGuideDescriptorReady=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueGuideDescriptorReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueGuideDescriptorBindings=4,5,6,7,8"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_opaque_guide_descriptor_set_layout"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "write_opaque_guide_descriptor_set"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_opaque_guide_pipeline_layout"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_opaque_guide_pipelines"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "OPAQUE_GUIDE_PASS_SPIRV"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "OPAQUE_GUIDE_SHADER_COMPILED"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "SpatialPublicDepthFallback"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_depth_descriptor_set_layout"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_opaque_projection_pipeline_layout"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "create_opaque_projection_pipeline"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "record_spatial_public_projection"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "OPAQUE_PROJECTION_EFFECT"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "frame_marker_fields"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "compact_projection_evidence_marker_fields"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackProjectionApplied="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackLayerCycleEnabled=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackLayerCycleElapsedSeconds="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueProjectionPipelineReady="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthFallbackReady="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackPassExecutionReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicGuideBlurRuntimeReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueProjectionPayloadExecutionReady="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaquePayloadExecutionReady=false"
Assert-NotContains "Native public multi-stack runtime" $nativeMultiStackRuntime "CameraProbeRuntime"
Assert-NotContains "Native public multi-stack runtime" $nativeMultiStackRuntime "surface_particle_layer"
Assert-Contains "Native controller actions" $nativeControllerActions "nativeStartSpatialControllerActions"
Assert-Contains "Native controller actions" $nativeControllerActions "nativePollSpatialControllerLeftThumbstickY"
Assert-Contains "Native controller actions" $nativeControllerActions "xrAttachSessionActionSets"
Assert-Contains "Native controller actions" $nativeControllerActions "/user/hand/left/input/thumbstick/y"
Assert-Contains "Native controller actions" $nativeControllerActions "spatial-controller-actions"
Assert-Contains "Native controller actions" $nativeControllerActions "actionSetAttached=true"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "nativeRequestSpatialMultimodalInput"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "XR_META_simultaneous_hands_and_controllers"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "XR_META_detached_controllers"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "xrResumeSimultaneousHandsAndControllersTrackingMETA"
Assert-Contains "Public guide blur shader" $publicGuideBlurShader "PublicGuideBlurPush"
Assert-Contains "Public guide blur shader" $publicGuideBlurShader "stepAndScale"
Assert-Contains "Public guide blur shader" $publicGuideBlurShader "sourceRect"
Assert-Contains "Public guide blur shader" $publicGuideBlurShader "guideTexture"
Assert-Contains "Camera HWB stream" $cameraStream "AImageReader_newWithUsage"
Assert-Contains "Camera HWB stream" $cameraStream "AImage_getHardwareBuffer"
Assert-Contains "Camera HWB stream" $cameraStream "StereoCamera50_51"
Assert-Contains "Camera HWB stream" $cameraStream "public_multistack_marker_fields"
Assert-Contains "Camera HWB probe" $cameraProbe "status=public-multistack-projection-evidence"
Assert-Contains "Camera HWB WSI" $cameraWsi "create_ahb_sampler_ycbcr_conversion"
Assert-Contains "Camera HWB WSI" $cameraWsi "record_camera_hwb_probe_command_buffer"
Assert-Contains "Camera HWB WSI" $cameraWsi "select_camera_surface_device"
Assert-Contains "Camera HWB WSI" $cameraWsi "public_multistack_marker_fields"
Assert-Contains "Camera HWB WSI" $cameraWsi "record_spatial_public_guide_passes"
Assert-Contains "Camera HWB WSI" $cameraWsi "record_spatial_public_projection"
Assert-Contains "Surface particle layer" $surfaceLayer "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSurfaceParticleLayer"
Assert-Contains "Surface particle layer" $surfaceLayer "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateSurfaceParticleParameters"
Assert-Contains "Replay hands" $replayHands "surfaceLayerMode=native-hand-anchor-particles"
Assert-Contains "Replay hands" $replayHands "rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1"
Assert-Contains "Replay hands" $replayHands "driverProfileSchemaId={}"
Assert-Contains "Build script" $buildScript "libspatial_camera_panel_native_receipt.so"
Assert-Contains "Build script" $buildScript 'driver_profile_mapping = "driver0_value01-to-native-driver0;driver1_value01-to-native-driver1"'
Assert-Contains "Build script" $buildScript 'questionnaire_schema = "rusty.quest.spatial_camera_panel.questionnaire.v1"'
Assert-Contains "Build script" $buildScript 'spatial_input_mode = "interaction-sdk-hands-and-controllers"'
Assert-Contains "Build script" $buildScript 'spatial_handtracking_manifest_declared = $true'
Assert-Contains "Build script" $buildScript 'spatial_render_model_manifest_declared = $true'
Assert-Contains "Build script" $buildScript 'spatial_multimodal_input_default_enabled = $false'
Assert-Contains "Build script" $buildScript 'native_spatial_controller_actions_default_enabled = $false'
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_hwb_projection_smoke"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.camera_hwb_projection_probe"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial_camera_panel.vr_input_system"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.multimodal_input.enabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_hands_and_controllers_manifest"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_interaction_sdk_backend"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_pointer_input_expected"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_controller_actions_disabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_multimodal_disabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "projectionTargetStereoHorizontalOffsetDefaultUv=0.046320"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "RQSpatialCameraPanelNative:D"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "RequirePublicMultiStackProjection"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "public_multistack_projection_applied"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "public_multistack_layer_cycle_enabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_particle_layer_suppressed"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_particle_layer_started_false"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_particle_renderer_ready_false"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_surface_layer_mode_absent"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackProjectionApplied=true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackLayerCycleEnabled=true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackOpaqueProjectionPipelineReady=true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackOpaqueProjectionTargetSpace=packed-stereo-surface-uv"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackOpaqueProjectionLeftTargetRect=0.062777;0.218750;0.375000;0.656250"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackOpaqueProjectionRightTargetRect=0.562222;0.218750;0.375000;0.671875"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "leftPackedEffectiveTargetScreenUvRect=0.062777;0.218750;0.375000;0.656250"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "rightPackedEffectiveTargetScreenUvRect=0.562222;0.218750;0.375000;0.671875"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "status=particle-layer-suppressed"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "particleLayerStarted=false"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "status=first-camera-frame-presented"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "stereoSource=camera50-51"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "outputMode=raw-color-target-rect"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "adb_serial_required = `$true"
Assert-Contains "README" $readme "Raw Camera2/AHardwareBuffer projection probes"
Assert-Contains "README" $readme "Spatial SDK Lane Source Map"
Assert-Contains "README" $readme "public seven-slot camera guide multi-stack contract"
Assert-Contains "README" $readme "official Spatial SDK panel sample"
Assert-Contains "README" $readme "normal path is"
Assert-Contains "README" $readme '`interaction_sdk`'
Assert-Contains "README" $readme "registerRequiredOpenXRExtensions()"
Assert-Contains "README" $readme "XR_META_detached_controllers"
Assert-Contains "README" $readme "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
Assert-Contains "Implementation notes" $notes "Private effect formulas"
Assert-Contains "Implementation notes" $notes "Public seven-slot camera guide multi-stack contract"
Assert-Contains "Implementation notes" $notes "public multi-stack receipts"

$cameraBoundaryFiles = @{
    "Camera HWB probe" = $cameraProbe
    "Camera HWB stream" = $cameraStream
    "Camera HWB WSI" = $cameraWsi
    "Camera projection target" = $cameraProjectionTarget
    "Native public multi-stack runtime" = $nativeMultiStackRuntime
}
foreach ($entry in $cameraBoundaryFiles.GetEnumerator()) {
    Assert-NotContains $entry.Key $entry.Value "surface_particle_layer"
    Assert-NotContains $entry.Key $entry.Value "ReplayHandsRenderer"
    Assert-NotContains $entry.Key $entry.Value "SurfaceParticleControlState"
    Assert-NotContains $entry.Key $entry.Value "driverProfileDynamics"
}

$particleBoundaryFiles = @{
    "Surface particle layer" = $surfaceLayer
    "Replay hands" = $replayHands
}
foreach ($entry in $particleBoundaryFiles.GetEnumerator()) {
    Assert-NotContains $entry.Key $entry.Value "camera_hwb_probe"
    Assert-NotContains $entry.Key $entry.Value "CameraProbeRuntime"
    Assert-NotContains $entry.Key $entry.Value "AImageReader_newWithUsage"
    Assert-NotContains $entry.Key $entry.Value "raw-color-target-rect"
}

Assert-NotContains "Experiment panel controller" $panelController "SceneSwapchain"
Assert-NotContains "Experiment panel controller" $panelController "SceneQuadLayer"
Assert-NotContains "Experiment panel controller" $panelController "nativeStart"
Assert-NotContains "Experiment panel controller" $panelController "AImageReader"

$scanSuffixes = @(".kt", ".java", ".rs", ".glsl", ".kts", ".xml", ".md", ".ps1", ".toml")
$skipScanDirs = @(".gradle", ".kotlin", "build")
$scanRoots = @(
    "apps\spatial-camera-panel-android",
    "tools\Build-SpatialCameraPanelAndroid.ps1",
    "tools\Test-SpatialCameraPanelAndroid.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidSelfTest.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidPolarLive.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1",
    "tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1",
    "docs\SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md"
)
$forbidden = @(
    ("Kura" + "moto"),
    ("kura" + "moto"),
    ("KURA" + "MOTO"),
    ("movement" + "_coupling"),
    ("movement" + "_base_frequency_hz"),
    ("movement" + "BaseFrequencyHz"),
    ("movement" + "Coupling"),
    ("private" + ("Kura" + "moto")),
    ("private" + "_" + ("kura" + "moto")),
    ("PRIVATE" + "_" + ("KURA" + "MOTO")),
    ("native" + "-" + ("kura" + "moto")),
    ("l" + "che"),
    ("h" + "che"),
    ("l" + "cle"),
    ("h" + "cle"),
    ("low" + "-energy"),
    ("high" + "-energy"),
    ("movement" + "-only"),
    ("Rusty" + "-Symmetric-" + "Morpho" + "vision"),
    ("Morpho" + "vision"),
    "privateShaderStack=true",
    "customProjectionStack=true"
)

foreach ($root in $scanRoots) {
    $path = Join-Path $repoRootPath $root
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Static scan root is missing: $root"
    }
    $item = Get-Item -LiteralPath $path
    $files = if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $path -Recurse -File | Where-Object { $scanSuffixes -contains $_.Extension }
    } else {
        @($item)
    }
    foreach ($file in $files) {
        $relative = $file.FullName -replace ("^" + [regex]::Escape($repoRootPath) + "[\\/]*"), ""
        $parts = $relative -split "[\\/]"
        if ($parts | Where-Object { $skipScanDirs -contains $_ }) {
            continue
        }
        $text = Get-Content -Raw -LiteralPath $file.FullName
        foreach ($token in $forbidden) {
            Assert-NotContains $relative $text $token
        }
    }
}

Write-Host "Spatial Camera Panel Android static gate passed"
