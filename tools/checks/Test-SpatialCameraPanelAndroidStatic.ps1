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

function Assert-RegistrationUsesSettings {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$RegistrationNeedle,
        [Parameter(Mandatory=$true)][string]$NextRegistrationNeedle,
        [Parameter(Mandatory=$true)][string]$SettingsNeedle
    )
    $normalizedText = $Text.Replace("`r`n", "`n")
    $normalizedRegistrationNeedle = $RegistrationNeedle.Replace("`r`n", "`n")
    $normalizedNextRegistrationNeedle = $NextRegistrationNeedle.Replace("`r`n", "`n")
    $registrationIndex = $normalizedText.IndexOf($normalizedRegistrationNeedle)
    if ($registrationIndex -lt 0) {
        throw "$Label is missing registration token: $RegistrationNeedle"
    }
    $nextRegistrationIndex = $normalizedText.IndexOf($normalizedNextRegistrationNeedle, $registrationIndex + $normalizedRegistrationNeedle.Length)
    if ($nextRegistrationIndex -lt 0) {
        throw "$Label is missing next registration token: $NextRegistrationNeedle"
    }
    $settingsIndex = $normalizedText.IndexOf($SettingsNeedle, $registrationIndex)
    if ($settingsIndex -lt 0 -or $settingsIndex -gt $nextRegistrationIndex) {
        throw "$Label registration $RegistrationNeedle is missing settings token before ${NextRegistrationNeedle}: $SettingsNeedle"
    }
}

$appGradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$manifest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml"
$ids = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\res\values\ids.xml"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$stagedAssetModule = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialStagedAssetModule.kt"
$spatialStereoVideoPlayback = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialStereoVideoPlayback.java"
$laneBoundary = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSdkLaneBoundary.kt"
$publicMultiStack = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPublicMultiStack.kt"
$panelController = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\ExperimentPanelController.kt"
$privateLayerPanel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$panelModels = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelModels.kt"
$avatarFeature = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAvatarHandVisualFeature.kt"
$handBillboardFeature = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialHandBillboardFlockFeature.kt"
$store = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelStore.kt"
$nativeLib = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\lib.rs"
$nativeBuildScript = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\build.rs"
$cameraProbe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$cameraProjectionTarget = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_projection_target.rs"
$nativeMultiStack = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack.rs"
$nativeMultiStackRuntime = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_public_multistack_runtime.rs"
$nativeControllerActions = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_controller_actions.rs"
$nativeMultimodalInput = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_multimodal_input.rs"
$nativeEnvironmentDepth = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_environment_depth.rs"
$nativePassthrough = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_native_passthrough.rs"
$publicGuideBlurShader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\public_guide_blur.frag.glsl"
$cameraRawColorShader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\camera_hwb_raw_color.frag.glsl"
$cameraStream = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_stream.rs"
$cameraWsi = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_wsi.rs"
$spatialVideoSettings = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_settings.rs"
$spatialVideoStream = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_native_stream.rs"
$spatialVideoRenderer = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection.rs"
$spatialVideoMarker = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_marker.rs"
$spatialVideoProbe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_probe.rs"
$spatialVideoVertShader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\spatial_video_projection.vert.glsl"
$spatialVideoFragShader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\spatial_video_projection.frag.glsl"
$surfaceLayer = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\surface_particle_layer.rs"
$replayHands = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\replay_hands.rs"
$buildScript = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"
$cameraProjectionSmoke = Read-RequiredText "tools\Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
$layeringMatrix = Read-RequiredText "tools\Invoke-SpatialCameraPanelAndroidLayeringMatrix.ps1"
$stageSpatialAsset = Read-RequiredText "tools\Stage-SpatialCameraPanelAsset.ps1"
$spatialPermissionPregrant = Read-RequiredText "tools\Grant-SpatialCameraPanelAndroidPermissions.ps1"
$uiActionWrapper = Read-RequiredText "tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1"
$readme = Read-RequiredText "apps\spatial-camera-panel-android\README.md"
$notes = Read-RequiredText "docs\SPATIAL_SDK_PORT_IMPLEMENTATION_PLAN.md"
$carrierPlan = Read-RequiredText "docs\SPATIAL_LAYERING_CARRIER_PROBE_PLAN.md"
$roomIterationLog = Read-RequiredText "docs\SPATIAL_ROOM_WORLDSPACE_ITERATION_LOG.md"
$testScript = Read-RequiredText "tools\Test-SpatialCameraPanelAndroid.ps1"

Assert-Contains "Gradle app" $appGradle 'namespace = "io.github.mesmerprism.rustyquest.spatial_camera_panel"'
Assert-Contains "Gradle app" $appGradle 'applicationId = "io.github.mesmerprism.rustyquest.spatial_camera_panel"'
Assert-Contains "Android manifest" $manifest 'android:name=".SpatialCameraPanelActivity"'
Assert-Contains "Android manifest" $manifest 'com.oculus.permission.HAND_TRACKING'
Assert-Contains "Android manifest" $manifest 'oculus.software.handtracking'
Assert-Contains "Android manifest" $manifest 'com.oculus.handtracking.version'
Assert-Contains "Android ids" $ids 'spatial_camera_projection_manual_custom_mesh_panel'
Assert-Contains "Panel models" $panelModels 'ManualPanelSceneObjectCustomMesh("manual-panel-scene-object-custom-mesh")'
Assert-Contains "Android manifest" $manifest 'com.oculus.feature.RENDER_MODEL'
Assert-Contains "Android manifest" $manifest 'com.oculus.permission.RENDER_MODEL'
Assert-Contains "Android manifest" $manifest 'horizonos.permission.USE_SCENE'
Assert-Contains "Android manifest" $manifest 'org.khronos.openxr.permission.OPENXR'
Assert-Contains "Android manifest" $manifest 'org.khronos.openxr.permission.OPENXR_SYSTEM'
Assert-Contains "Activity" $activity "class SpatialCameraPanelActivity : AppSystemActivity()"
Assert-Contains "Activity" $activity "SceneSwapchain.createAsAndroid"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe.depth.layer_policy"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.video_projection_probe"
Assert-Contains "Activity" $activity "stagedAssetModule = SpatialStagedAssetModule(::marker)"
Assert-Contains "Activity" $activity "SpatialHandBillboardFlockFeature(::marker)"
Assert-Contains "Activity" $activity "{ store.snapshot().surfaceTargetId }"
Assert-Contains "Activity" $activity "spatialWorldHandBillboardFlock=spatial-sdk-world-hand-billboard-flock"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.hand_billboard_flock.enabled"
Assert-Contains "Activity" $activity "runSpatialStagedAssetIfRequested"
Assert-Contains "Activity" $activity 'spatialSdk3dAssetModule=${SpatialStagedAssetModule.MODULE_ID}'
Assert-Contains "Activity" $activity 'spatialVirtualRoomModule=$SPATIAL_VIRTUAL_ROOM_MODULE_ID'
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.virtual_room.enabled"
Assert-Contains "Activity" $activity "runSpatialVirtualRoomIfRequested"
Assert-Contains "Activity" $activity 'SPATIAL_VIRTUAL_ROOM_SCENE_URI = "apk:///scenes/Composition.glxf"'
Assert-Contains "Activity" $activity "channel=spatial-virtual-room status=loaded"
Assert-Contains "Activity" $activity "spatialVirtualRoomLoaded"
Assert-Contains "Activity" $activity "deferredUntil=virtual-room-loaded"
Assert-Contains "Activity" $activity 'runSpatialStagedAssetIfRequested(intent, "virtual-room-loaded")'
Assert-Contains "Activity" $activity 'runCameraHwbProjectionProbeIfRequested("virtual-room-loaded")'
Assert-Contains "Activity" $activity "roomAssetSource=packaged-glxf"
Assert-Contains "Activity" $activity "sampleRoomAssetPolicy=local-launch-input"
Assert-Contains "Activity" $activity "outputMode=raw-color-target-rect"
Assert-Contains "Activity" $activity "runSpatialVideoProjectionProbeIfRequested"
Assert-Contains "Activity" $activity "nativeStartSpatialVideoProjectionProbe"
Assert-Contains "Activity" $activity "nativeStopSpatialVideoProjectionProbe"
Assert-Contains "Activity" $activity "videoOnlySpatialProjection=true"
Assert-Contains "Activity" $activity "SpatialSdkLaneBoundaries.summaryToken()"
Assert-Contains "Lane boundary" $laneBoundary "WorldHandBillboard"
Assert-Contains "Lane boundary" $laneBoundary "SpatialWorldHandBillboardFlockBoundary"
Assert-Contains "Hand billboard feature" $handBillboardFeature "class SpatialHandBillboardFlockFeature"
Assert-Contains "Hand billboard feature" $handBillboardFeature "SystemBase"
Assert-Contains "Hand billboard feature" $handBillboardFeature "Mesh(Uri.parse(`"mesh://box`"), MeshCollision.NoCollision)"
Assert-Contains "Hand billboard feature" $handBillboardFeature "SpatialHandBillboardCarrierMode"
Assert-Contains "Hand billboard feature" $handBillboardFeature "debug.rustyquest.spatial.hand_billboard_flock.carrier"
Assert-Contains "Hand billboard feature" $handBillboardFeature 'BatchedSceneMesh("batched-scene-mesh"'
Assert-Contains "Hand billboard feature" $handBillboardFeature 'EcsEntities("ecs-entities"'
Assert-Contains "Hand billboard feature" $handBillboardFeature "TriangleMesh(vertexCapacity, indexCapacity"
Assert-Contains "Hand billboard feature" $handBillboardFeature "SceneMesh.fromTriangleMesh"
Assert-Contains "Hand billboard feature" $handBillboardFeature "sceneMesh.updateWithTriangleMesh"
Assert-Contains "Hand billboard feature" $handBillboardFeature "visualParticleCount="
Assert-Contains "Hand billboard feature" $handBillboardFeature "carrierEntityCount="
Assert-Contains "Hand billboard feature" $handBillboardFeature "meshGeometryUpdates="
Assert-Contains "Hand billboard feature" $handBillboardFeature "persistentCarriers=true"
Assert-Contains "Hand billboard feature" $handBillboardFeature "projectionPlane=false"
Assert-Contains "Hand billboard feature" $handBillboardFeature "customGpuSkinning=false"
Assert-Contains "Hand billboard feature" $handBillboardFeature "couplingDynamics=false"
Assert-Contains "Hand billboard feature" $handBillboardFeature "highRateJsonPayload=false"
Assert-NotContains "Hand billboard feature" $handBillboardFeature "Kuramoto"
Assert-NotContains "Hand billboard feature" $handBillboardFeature "kuramoto"
Assert-NotContains "Hand billboard feature" $handBillboardFeature "private-coupling"
Assert-NotContains "Hand billboard feature" $handBillboardFeature "privateOscillator"
Assert-Contains "Hand billboard feature" $handBillboardFeature "spatial-sdk-avatar-body-hand-entities"
Assert-Contains "Hand billboard feature" $handBillboardFeature "surfaceTargetProvider: () -> String"
Assert-Contains "Hand billboard feature" $handBillboardFeature '"icosphere-surface-target"'
Assert-Contains "Activity" $activity "SpatialPublicMultiStack.markerFields()"
Assert-Contains "Activity" $activity "SpatialPublicMultiStack.inactiveMarkerFields()"
Assert-Contains "Activity" $activity "cameraStackSuppressesParticles"
Assert-Contains "Activity" $activity 'suppressParticleLayerIfCameraProjectionRequested("activity-created")'
Assert-Contains "Activity" $activity 'suppressParticleLayerIfCameraProjectionRequested("new-intent")'
Assert-Contains "Activity" $activity 'suppressParticleLayerForCameraStack("camera-hwb-projection-probe")'
Assert-Contains "Activity" $activity "applyCameraHwbProjectionScaleJoystickInput"
Assert-Contains "Activity" $activity "toggleCameraHwbProjectionPlacementMode"
Assert-Contains "Activity" $activity "ButtonBits.ButtonB"
Assert-Contains "Activity" $activity "controllerInput=right-secondary-button"
Assert-Contains "Activity" $activity "nativePollSpatialControllerRightButtonB"
Assert-Contains "Activity" $activity "rightControllerInactiveButtonStateAccepted=true"
Assert-Contains "Activity" $activity "cameraProjectionWallToggleInput=disabled-right-secondary-noop"
Assert-Contains "Activity" $activity "cameraProjectionWallToggleEnabled=false"
Assert-Contains "Activity" $activity "virtualRoomWallPlacementMode"
Assert-Contains "Activity" $activity "virtualRoomWallCenterM="
Assert-Contains "Activity" $activity "CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER"
Assert-Contains "Activity" $activity "launcherPanelVisibleForPanelMode"
Assert-Contains "Activity" $activity "legacy-workflow-panels-deactivated"
Assert-Contains "Activity" $activity "onlyRightPrimaryPrivateLayerPanel=true"
Assert-Contains "Activity" $activity "legacyLauncherPanelSuppressed=true"
Assert-Contains "Activity" $activity "projectionDefaultPlacementMode="
Assert-Contains "Activity" $activity "rightSecondaryTogglesFullFov=false"
Assert-Contains "Activity" $activity "projectionDisplaySurface=video-plus-custom-camera-stack"
Assert-Contains "Activity" $activity "projection-layer-over-virtual-room"
Assert-Contains "Activity" $activity "projectionStartGate="
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_projection_start_gate_virtual_room_loaded"
Assert-Contains "Activity" $activity 'SPATIAL_VIRTUAL_ROOM_SKYBOX_MESH_URI = "mesh://skybox"'
Assert-Contains "Activity" $activity "skyboxEntityCreateApi=toolkit-varargs-first-room-replay"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.skybox.mode"
Assert-Contains "Activity" $activity "SpatialSkyboxMode.CustomSceneMesh"
Assert-Contains "Models" $panelModels 'internal enum class SpatialSkyboxMode'
Assert-Contains "Activity" $activity "SceneMesh.skybox"
Assert-Contains "Activity" $activity "skyboxRenderer=custom-runtime-scene-mesh-skybox"
Assert-Contains "Activity" $activity "skyboxProjectionForegroundPolicy=scene-layer-over-background-skybox"
Assert-Contains "Activity" $activity "SPATIAL_CUSTOM_SKYBOX_RENDER_ORDER"
Assert-Contains "Activity" $activity "projectionCarrierProperty=`$CAMERA_HWB_PROJECTION_CARRIER_PROPERTY"
Assert-Contains "Activity" $activity "projectionAnchorHittable=none-first-room-diagnostic"
Assert-Contains "Activity" $activity "projectionAnchorMaterialRenderOrder=default-first-room-diagnostic"
Assert-Contains "Activity" $activity "cameraHwbProjectionZIndexForPlacement"
Assert-Contains "Activity" $activity "sceneQuadLayerRebuildStatus="
Assert-Contains "Activity" $activity "sceneQuadLayerRebuildStatus=not-rebuilt-existing-scene-anchor-updated"
Assert-Contains "Activity" $activity "projection-placement-toggle-ignored"
Assert-Contains "Activity" $activity "projection-placement-toggle-armed"
Assert-Contains "Activity" $activity "toggleGuard=wait-for-secondary-release-after-projection-start"
Assert-Contains "Activity" $activity "CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS"
Assert-Contains "Activity" $activity "updateCameraHwbProjectionTargetScaleFromPanel"
Assert-Contains "Activity" $activity "PRIVATE_LAYER_PANEL_DISTANCE_METERS = 1.0f"
Assert-Contains "Activity" $activity "PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS = 2.00f"
Assert-Contains "Activity" $activity "CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS = 2.0f"
Assert-Contains "Activity" $activity 'CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER = "2.00"'
Assert-Contains "Activity" $activity 'targetDistanceDefaultMeters=$CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER'
Assert-Contains "Activity" $activity "targetDistanceJoystickControlsEnabled=false"
Assert-Contains "Activity" $activity "projectionTargetScaleJoystickControlsEnabled=true"
Assert-Contains "Activity" $activity "projectionTargetScaleJoystickInput=android-right-stick-y;spatial-sdk-avatar-body-right-thumb-up-down;native-openxr-right-thumbstick-y;panel-control"
Assert-Contains "Activity" $activity "stereoHorizontalOffsetJoystickControlsEnabled=false"
Assert-Contains "Activity" $activity "stereoHorizontalOffsetJoystickInput=disabled-default-locked-left-stick-y-controls-workflow-or-private-panel-distance-only"
Assert-Contains "Activity" $activity "cameraHwbProjectionStereoHorizontalOffsetIgnoresPanelVisibility=true"
Assert-Contains "Activity" $activity "projectionTargetStereoHorizontalOffsetUv="
Assert-Contains "Activity" $activity "projectionTargetStereoHorizontalOffsetDefaultUv="
Assert-Contains "Activity" $activity "CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV = 0.046320f"
Assert-Contains "Activity" $activity "projectionTargetLeftOffsetUv="
Assert-Contains "Activity" $activity "projectionTargetRightOffsetUv="
Assert-Contains "Activity" $activity "nativeUpdateCameraHwbProjectionStereoOffsetUv"
Assert-Contains "Activity" $activity "nativeUpdateCameraHwbProjectionTargetScale"
Assert-Contains "Activity" $activity "nativeUpdatePrivateLayerOverride"
Assert-Contains "Activity" $activity "nativeUpdatePrivateLayerDepthLayerPolicy"
Assert-Contains "Activity" $activity "nativeUpdatePrivateLayerDepthAlignment"
Assert-Contains "Activity" $activity "spatial_private_layer_panel"
Assert-Contains "Activity" $activity "PrivateLayerControlPanel"
Assert-Contains "Activity" $activity "spatial-sdk-private-layer-panel-open"
Assert-Contains "Activity" $activity "spatialPrivateLayerControlPanel=true"
Assert-Contains "Activity" $activity "publicMultiStackOpaqueProjectionLayerOverride"
Assert-Contains "Activity" $activity "layerOverrideAppliesToWallAndFullFov=true"
Assert-Contains "Activity" $activity "cameraProjectionPlacementIndependentLayerControl=true"
Assert-Contains "Activity" $activity "layerOverrideReappliedOnPlacementToggle="
Assert-Contains "Activity" $activity "publicMultiStackDepthLayerPolicy"
Assert-Contains "Activity" $activity "publicMultiStackDepthLayerCompareMode"
Assert-Contains "Activity" $activity "publicMultiStackDepthAlignmentControl=true"
Assert-Contains "Activity" $activity "publicMultiStackDepthAlignmentLeftOffsetUv="
Assert-Contains "Activity" $activity "panelRenderOrder=spatial-sdk-quad-layer-z-index"
Assert-Contains "Activity" $activity "panelOpensInFrontOfCameraVideo="
Assert-Contains "Activity" $activity "type = GrabbableType.PIVOT_Y"
Assert-Contains "Activity" $activity "privateLayerPanelGrabbable=true"
Assert-Contains "Activity" $activity "privateLayerPanelGrabType=PIVOT_Y"
Assert-Contains "Activity" $activity "privateLayerPanelGrabMinHeightMeters="
Assert-Contains "Activity" $activity "privateLayerPanelGrabMaxHeightMeters="
Assert-Contains "Activity" $activity "privateLayerPanelTransformAuthority=app-stored-placement-unless-grabbed"
Assert-Contains "Activity" $activity "composeDragPanelMovement=false"
Assert-Contains "Activity" $activity "sdk-grabbable-state"
Assert-Contains "Activity" $activity "PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM"
Assert-Contains "Activity" $activity "tryGetComponent<Transform>()"
Assert-NotContains "Private layer panel" $privateLayerPanel "detectDragGestures"
Assert-Contains "Private layer panel" $privateLayerPanel "PanelGrabHandle"
Assert-NotContains "Private layer panel" $privateLayerPanel "dragPanel("
Assert-Contains "Activity" $activity "privateLayerPanelRenderMode=spatial-sdk-layer"
Assert-Contains "Activity" $activity "privateLayerPanelWorldSpace=true"
Assert-Contains "Activity" $activity "privateLayerPanelPoseSource=initial-headset-facing-world-space-then-stored-placement-unless-grabbed"
Assert-Contains "Activity" $activity "privateLayerPanelDistanceMode=left-stick-stored-placement"
Assert-Contains "Activity" $activity "privateLayerPanelForcedDistanceDisabled=false"
Assert-Contains "Activity" $activity "privateLayerPanelDistanceControl=left-stick-y-private-panel-free-transform-distance"
Assert-Contains "Activity" $activity "privateLayerPanelDistancePersistsAcrossToggle=true"
Assert-Contains "Activity" $activity "rightStickSideFlickPanelMoveDisabled=true"
Assert-Contains "Activity" $activity "PanelInputOptions("
Assert-Contains "Activity" $activity "ButtonBits.ButtonA or ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR"
Assert-Contains "Activity" $activity "privateLayerPanelInputButtons=button-a+trigger-l+trigger-r"
Assert-Contains "Activity" $activity "privateLayerPanelTriggerSelectEnabled=true"
Assert-Contains "Activity" $activity "privateLayerPanelGrabButton=controller-squeeze"
Assert-Contains "Activity" $activity "privateLayerPanelLayerZIndex="
Assert-Contains "Activity" $activity "privateLayerPanelAboveCameraProjectionLayer="
Assert-Contains "Activity" $activity "updatePrivateLayerPanelLayer"
Assert-Contains "Activity" $activity "privateLayerPanelLayerConfig=enabled"
Assert-Contains "Activity" $activity "PanelRenderMode.Layer"
Assert-Contains "Activity" $activity "PRIVATE_LAYER_PANEL_LAYER_Z_INDEX = 99"
Assert-Contains "Activity" $activity "projectionPanelInputClearanceActive="
Assert-Contains "Activity" $activity "projectionPanelInputBehindPrivateLayerPanel="
Assert-Contains "Activity" $activity "val inputForegroundActive = false"
Assert-Contains "Activity" $activity 'privateLayerPanelInputForegroundActive=$inputForegroundActive'
Assert-Contains "Activity" $activity "projectionPanelInputPassThrough=true"
Assert-Contains "Activity" $activity 'CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject -> "NoCollision"'
Assert-Contains "Activity" $activity 'projectionPanelHittable=$projectionPanelHittable'
Assert-Contains "Activity" $activity '"none-manual-custom-mesh-noninteractive"'
Assert-Contains "Activity" $activity "manualPanelNoHittable=true"
Assert-Contains "Activity" $activity "manualPanelNoIsdkGrabbable=true"
Assert-Contains "Activity" $activity "manualPanelForceSceneTexture=true"
Assert-Contains "Activity" $activity "forceSceneTexture = true"
Assert-Contains "Activity" $activity "panelInputOptionsClickButtons=0"
Assert-Contains "Activity" $activity "PanelInputOptions(0)"
Assert-Contains "Activity" $activity "PanelSceneObject("
Assert-Contains "Activity" $activity "SceneObjectSystem"
Assert-Contains "Activity" $activity "CompletableFuture<SceneObject>()"
Assert-Contains "Activity" $activity "SceneMesh.singleSidedQuad"
Assert-Contains "Activity" $activity "manual-panel-scene-object-custom-mesh"
Assert-Contains "Activity" $activity "privateLayerPanelDefaultReachDistancePreserved=true"
Assert-Contains "Activity" $activity "privateLayerPanelScaleAdjustedForForeground=false"
Assert-Contains "Activity" $activity "Hittable(MeshCollision.NoCollision)"
Assert-Contains "Activity" $activity "updateCameraHwbProjectionFromViewer("
Assert-Contains "Activity" $activity "controller-primary-toggled-panel"
Assert-Contains "Activity" $activity "panelToggleAction="
Assert-Contains "Activity" $activity "layerOverrideForcedProjectionRefresh=true"
Assert-Contains "Activity" $activity "environmentMaterialPolicy=sample-authored-normal-materials"
Assert-Contains "Build manifest" $buildScript "spatial_private_layer_panel_projection_input_order"
Assert-Contains "Activity" $activity "privateLayerPanelPoseFromViewer"
Assert-Contains "Activity" $activity "coercePrivateLayerPanelPlacement"
Assert-Contains "Activity" $activity "activeHeadlockedPanelPlacement"
Assert-RegistrationUsesSettings "Activity" $activity "ComposeViewPanelRegistration(`r`n            R.id.spatial_private_layer_panel" "ComposeViewPanelRegistration(`r`n            R.id.spatial_camera_panel_launcher" "privateLayerPanelSettings()"
Assert-Contains "Activity" $activity '"private-layer-panel-open" -> setPrivateLayerPanelVisible(true, focus = true, source = source)'
Assert-Contains "Activity" $activity '"private-layer-panel-close" -> setPrivateLayerPanelVisible(false, focus = false, source = source)'
Assert-Contains "Activity" $activity "nativePanelPoseAuthority=camera-hwb-projection-plane"
Assert-Contains "Activity" $activity "projection-plane-update-suppressed"
Assert-Contains "Activity" $activity "updateNativePanelProjectionFromCameraPlane"
Assert-Contains "Activity" $activity "ButtonThumbLU"
Assert-Contains "Activity" $activity "ButtonThumbLD"
Assert-Contains "Activity" $activity "ButtonThumbRU"
Assert-Contains "Activity" $activity "ButtonThumbRD"
Assert-Contains "Activity" $activity 'leftThumbYPanelDistanceEnabled=${currentLeftStickPanelDistanceEnabled()}'
Assert-Contains "Activity" $activity "leftThumbYPanelScrollReserved=false"
Assert-Contains "Activity" $activity "leftThumbYProjectionHorizontalOffsetDisabled=true"
Assert-Contains "Activity" $activity "currentLeftStickPanelDistanceMapping()"
Assert-Contains "Activity" $activity "left-stick-y-private-panel-free-transform-distance"
Assert-Contains "Activity" $activity "right-thumb-up-down-projection-target-scale"
Assert-Contains "Activity" $activity "SPATIAL_VR_INPUT_SYSTEM_PROPERTY"
Assert-Contains "Activity" $activity "SpatialControllerInputLateFeature(::pollSpatialControllerInput)"
Assert-Contains "Activity" $activity "nativeStartSpatialControllerActions"
Assert-Contains "Activity" $activity "nativePollSpatialControllerLeftThumbstickY"
Assert-Contains "Activity" $activity "nativePollSpatialControllerRightThumbstickY"
Assert-Contains "Activity" $activity "nativeStartSpatialNativePassthrough"
Assert-Contains "Activity" $activity "nativeStopSpatialNativePassthrough"
Assert-Contains "Activity" $activity "startSpatialNativePassthroughForDepthPrerequisite"
Assert-Contains "Activity" $activity "nativeStartSpatialEnvironmentDepthProbe"
Assert-Contains "Activity" $activity "nativeStopSpatialEnvironmentDepthProbe"
Assert-Contains "Activity" $activity "startSpatialEnvironmentDepthProbe"
Assert-Contains "Activity" $activity "SPATIAL_NATIVE_PASSTHROUGH_LAYER_ACTIVE_BIT"
Assert-Contains "Activity" $activity "SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_STARTED_BIT"
Assert-Contains "Activity" $activity "SPATIAL_ENVIRONMENT_DEPTH_ACQUIRE_THREAD_STARTED_BIT"
Assert-Contains "Activity" $activity "XR_FB_passthrough"
Assert-Contains "Activity" $activity "XR_META_environment_depth"
Assert-Contains "Activity" $activity "nativeEnvironmentDepthStartMask"
Assert-Contains "Activity" $activity "nativeEnvironmentDepthProviderBound"
Assert-Contains "Activity" $activity "nativeEnvironmentDepthProviderBound = nativeEnvironmentDepthProviderBound"
Assert-Contains "Activity" $activity "nativeSpatialControllerActionsEnabled()"
Assert-Contains "Activity" $activity "NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED = false"
Assert-Contains "Activity" $activity "native-openxr-action"
Assert-Contains "Activity" $activity "right-stick-y-projection-target-scale"
Assert-Contains "Activity" $activity "status=joystick-input-arbitrated"
Assert-Contains "Activity" $activity "rightStickXIgnored=true"
Assert-Contains "Activity" $activity "rightStickXPanelScaleDisabled=true"
Assert-Contains "Activity" $activity "rightStickSwallowedAsIgnored="
Assert-Contains "Activity" $activity "currentLeftStickPanelDistanceEnabled()"
Assert-Contains "Activity" $activity "leftStickYPanelScrollReserved=false"
Assert-Contains "Activity" $activity "leftStickYDeliveredToPanelScroll="
Assert-Contains "Activity" $activity "rightStickYProjectionScaleSuppressedByPrivateLayerPanel="
Assert-Contains "Activity" $activity "headlock-distance-joystick-adjusted"
Assert-Contains "Activity" $activity "leftStickUpIncreasesPanelDistance=true"
Assert-Contains "Activity" $activity "target-scale-joystick-adjusted"
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
Assert-Contains "Activity" $activity "!panelPlacement.visible && !privateLayerPanelVisible && !cameraStackSuppressesParticles"
Assert-Contains "Activity" $activity "status=particle-layer-suppressed"
Assert-Contains "Activity" $activity "status=start-suppressed"
Assert-Contains "Activity" $activity "cameraHwbProjectionRightPackedEffectiveTargetRectMarker"
Assert-Contains "Activity" $activity 'CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_X = 0.078125f'
Assert-Contains "Activity" $activity "currentSpatialVideoProjectionSettings"
Assert-Contains "Activity" $activity "nativeConfigureSpatialVideoProjection"
Assert-Contains "Activity" $activity "SpatialStereoVideoPlayback.start"
Assert-Contains "Activity" $activity "SpatialStereoVideoPlayback.stop"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe.video.path"
Assert-Contains "Activity" $activity "rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
Assert-Contains "Activity" $activity "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual"
Assert-Contains "Activity" $activity "syntheticCarrierVisualProbe=true"
Assert-Contains "Activity" $activity "high-contrast-red-green-blue-yellow-checkerboard"
Assert-Contains "Activity" $activity "videoProjectionNoPackagedMedia=true"
Assert-Contains "Activity" $activity "videoProjectionControlPlane=spatial-activity-runtime-property-or-intent-extra"
Assert-Contains "Activity" $activity "videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer"
Assert-Contains "Activity" $activity "spatialVideoProjectionSameSurfaceComposition=true"
Assert-Contains "Activity" $activity "videoProjectionComposedBeforeCamera=true"
Assert-Contains "Activity" $activity "cameraProjectionAlignmentPreserved=true"
Assert-Contains "Activity" $activity "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback "public final class SpatialStereoVideoPlayback"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback 'System.loadLibrary("spatial_camera_panel_native_receipt")'
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback "MediaExtractor"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback "MediaCodec.createDecoderByType"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback "nativeCreateStereoVideoSurface"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback "nativeStopStereoVideoStream"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback "resolvePath"
Assert-Contains "Spatial stereo video playback" $spatialStereoVideoPlayback 'return "";'
Assert-NotContains "Spatial stereo video playback" $spatialStereoVideoPlayback "noodletest"
Assert-NotContains "Spatial stereo video playback" $spatialStereoVideoPlayback ".mp4"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SpatialSdkLayerCarrier"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object ExperimentPanelControllerBoundary"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object CameraProjectionProbeController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object PublicMultiStackController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "kind = SpatialSdkLaneKind.PublicMultiStack"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SurfaceParticleLayerController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "kind = SpatialSdkLaneKind.StagedAsset"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SpatialStagedAssetBoundary"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "source asset provenance"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "kind = SpatialSdkLaneKind.VirtualRoom"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SpatialVirtualRoomBoundary"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "spatial-sdk-packaged-virtual-room"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "MRUK real-room placement"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary "internal object SpatialDebugProbeController"
Assert-Contains "Spatial SDK lane boundary" $laneBoundary 'mustNotOwn = setOf("surface particles", "driver-profile dynamics", "questionnaire state")'
Assert-Contains "Spatial staged asset module" $stagedAssetModule 'MODULE_ID = "spatial-sdk-staged-3d-asset"'
Assert-Contains "Spatial staged asset module" $stagedAssetModule "debug.rustyquest.spatial.asset_model.mesh_uri"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "MeshCollision.NoCollision"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "SceneMaterial.UNLIT_SHADER"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "assetVisibilityBias=headset-visible-test-placement"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "Entity.create("
Assert-Contains "Spatial staged asset module" $stagedAssetModule "Grabbable(type = GrabbableType.PIVOT_Y)"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "fbxConversionRequired=true"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "privateSourceAssetPackaged=false"
Assert-Contains "Spatial staged asset module" $stagedAssetModule "highRateJsonPayload=false"
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
Assert-Contains "Private layer panel" $privateLayerPanel "internal fun PrivateLayerControlPanel("
Assert-Contains "Private layer panel" $privateLayerPanel "Layer Selection Panel"
Assert-Contains "Private layer panel" $privateLayerPanel "Active Rendering"
Assert-Contains "Private layer panel" $privateLayerPanel "Projection Area"
Assert-Contains "Private layer panel" $privateLayerPanel "Depth Source"
Assert-Contains "Private layer panel" $privateLayerPanel "Depth Alignment"
Assert-Contains "Private layer panel" $privateLayerPanel "PrivateLayerControls.layers"
Assert-Contains "Private layer panel" $privateLayerPanel "PrivateLayerControls.depthSourcePolicies"
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(0, "Final", "final")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(1, "Opaque analysis 0", "opaque-analysis0-slot")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(2, "Public guide blur", "public-guide-blur")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(3, "Opaque analysis 1", "opaque-analysis1-slot")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(4, "Public post-blur guide", "public-post-blur-guide")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(5, "Opaque projection", "opaque-projection-slot")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerChoice(6, "Public depth diagnostic", "public-depth-diagnostic")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerDepthSourceChoice(depthPolicyMonoLayer0, "Mono 0", "mono-layer0")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerDepthSourceChoice(depthPolicyMonoLayer1, "Mono 1", "mono-layer1")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerDepthSourceChoice(depthPolicyEyeIndex, "Per eye", "eye-index")'
Assert-Contains "Private layer panel" $privateLayerPanel 'PrivateLayerDepthSourceChoice(depthPolicyCompare, "Compare", "compare")'
Assert-Contains "Private layer panel" $privateLayerPanel "updateDepthLayerPolicy"
Assert-Contains "Private layer panel" $privateLayerPanel "Depth sample scale"
$privateLayerLabelNeedles = @(
  ("Raw " + "brightness"),
  ("Pre" + "blur brightness"),
  ("Raw " + "strength"),
  ("Blurred " + "strength"),
  ("Dis" + "placement"),
  ("Depth " + "gradient")
)
foreach ($needle in $privateLayerLabelNeedles) {
  Assert-NotContains "Private layer panel" $privateLayerPanel $needle
}
Assert-NotContains "Activity" $activity ("1:" + "raw" + "-brightness")
Assert-Contains "Activity" $activity "publicMultiStackLayerManifest=0:final,1:opaque-analysis0-slot,2:public-guide-blur,3:opaque-analysis1-slot,4:public-post-blur-guide,5:opaque-projection-slot,6:public-depth-diagnostic"
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
Assert-Contains "Native receipt" $nativeLib "mod spatial_video_projection"
Assert-Contains "Native receipt" $nativeLib "mod spatial_video_projection_native_stream"
Assert-Contains "Native receipt" $nativeLib "mod spatial_video_projection_settings"
Assert-Contains "Native receipt build script" $nativeBuildScript "public_guide_blur.frag.glsl"
Assert-Contains "Native receipt build script" $nativeBuildScript "spatial_video_projection.vert.glsl"
Assert-Contains "Native receipt build script" $nativeBuildScript "spatial_video_projection.frag.glsl"
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
Assert-Contains "Camera HWB probe" $cameraProbe "nativeUpdatePrivateLayerOverride"
Assert-Contains "Camera HWB probe" $cameraProbe "nativeUpdatePrivateLayerDepthLayerPolicy"
Assert-Contains "Camera HWB probe" $cameraProbe "nativeUpdatePrivateLayerDepthAlignment"
Assert-Contains "Camera HWB probe" $cameraProbe "update_spatial_public_depth_layer_policy"
Assert-Contains "Camera HWB probe" $cameraProbe "update_spatial_public_depth_alignment"
Assert-Contains "Camera HWB probe" $cameraProbe "status=private-layer-override-updated"
Assert-Contains "Camera HWB probe" $cameraProbe "status=private-layer-depth-layer-policy-updated"
Assert-Contains "Camera HWB probe" $cameraProbe "status=private-layer-depth-alignment-updated"
Assert-Contains "Camera HWB probe" $cameraProbe "spatialPrivateLayerControlPanel=true"
Assert-Contains "Camera HWB probe" $cameraProbe "status=public-multistack-guide-targets-ready"
Assert-Contains "Camera HWB probe" $cameraProbe "status=public-multistack-guide-targets-skipped"
Assert-Contains "Camera HWB probe" $cameraProbe "SpatialVideoProjectionRenderer"
Assert-Contains "Camera HWB probe" $cameraProbe "latest_spatial_video_projection_frame"
Assert-Contains "Camera HWB probe" $cameraProbe "status=spatial-video-projection-frame-composed"
Assert-Contains "Camera HWB probe" $cameraProbe "videoComposedBeforeCamera=true"
Assert-Contains "Camera HWB probe" $cameraProbe "sameSurfaceComposition=true"
Assert-Contains "Camera HWB probe" $cameraProbe "cameraProjectionAlignmentPreserved=true"
Assert-Contains "Spatial video settings" $spatialVideoSettings "nativeConfigureSpatialVideoProjection"
Assert-Contains "Spatial video settings" $spatialVideoSettings "SpatialVideoProjectionSettings"
Assert-Contains "Spatial video settings" $spatialVideoSettings "videoProjectionEnabled={}"
Assert-Contains "Spatial video settings" $spatialVideoSettings "videoProjectionPathProvided={}"
Assert-Contains "Spatial video settings" $spatialVideoSettings "videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer"
Assert-Contains "Spatial video settings" $spatialVideoSettings "videoProjectionControlPlane=spatial-activity-runtime-property-or-intent-extra"
Assert-Contains "Spatial video settings" $spatialVideoSettings "videoProjectionLeftTargetPackedUvRect={}"
Assert-Contains "Spatial video settings" $spatialVideoSettings "videoProjectionRightTargetPackedUvRect={}"
Assert-Contains "Spatial video settings" $spatialVideoSettings "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false"
Assert-Contains "Spatial video stream" $spatialVideoStream "AImageReader_newWithUsage"
Assert-Contains "Spatial video stream" $spatialVideoStream "ANativeWindow_toSurface"
Assert-Contains "Spatial video stream" $spatialVideoStream "AImage_getHardwareBuffer"
Assert-Contains "Spatial video stream" $spatialVideoStream "nativeCreateStereoVideoSurface"
Assert-Contains "Spatial video stream" $spatialVideoStream "nativeStereoVideoLifecycleEvent"
Assert-Contains "Spatial video stream" $spatialVideoStream "mediaCodecStarted={}"
Assert-Contains "Spatial video stream" $spatialVideoStream "status=decoded-frame-acquired"
Assert-Contains "Spatial video stream" $spatialVideoStream "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "import_ahb_sampled_image"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "transition_ahb_sampled_image_to_shader_read"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "record_video_eye"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "videoProjectionRendered"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "spatialVideoProjectionRendered"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "videoProjectionGpuImportReady"
Assert-Contains "Spatial video renderer" $spatialVideoRenderer "status=ahardware-buffer-import-ready"
Assert-Contains "Spatial video marker" $spatialVideoMarker "SPATIAL_VIDEO_PROJECTION_CHANNEL"
Assert-Contains "Spatial video vertex shader" $spatialVideoVertShader "positions[3]"
Assert-Contains "Spatial video fragment shader" $spatialVideoFragShader "SpatialVideoProjectionPush"
Assert-Contains "Spatial video fragment shader" $spatialVideoFragShader "u_video_projection"
Assert-Contains "Spatial video fragment shader" $spatialVideoFragShader "source_uv_rect"
Assert-Contains "Spatial video probe" $spatialVideoProbe "nativeStartSpatialVideoProjectionProbe"
Assert-Contains "Spatial video probe" $spatialVideoProbe "nativeStopSpatialVideoProjectionProbe"
Assert-Contains "Spatial video probe" $spatialVideoProbe "videoOnlySpatialProjection=true"
Assert-Contains "Spatial video probe" $spatialVideoProbe "cameraRuntimeStarted=false"
Assert-Contains "Spatial video probe" $spatialVideoProbe "status=render-loop-ready"
Assert-Contains "Spatial video probe" $spatialVideoProbe "status=video-frame-presented"
Assert-Contains "Spatial video probe" $spatialVideoProbe "producerPath=MediaCodec-AImageReader-AHardwareBuffer-Vulkan-WSI"
Assert-Contains "Spatial video probe" $spatialVideoProbe "latest_spatial_video_projection_frame"
Assert-Contains "Spatial video probe" $spatialVideoProbe "SpatialVideoProjectionRenderer"
Assert-Contains "Native receipt lib" $nativeLib "mod spatial_video_projection_probe"
Assert-Contains "Camera raw color shader" $cameraRawColorShader "discard;"
Assert-Contains "Camera raw color shader" $cameraRawColorShader "fallback_layer_debug"
Assert-Contains "Camera raw color shader" $cameraRawColorShader "pc.params.y"
Assert-Contains "Camera HWB projection target" $cameraProjectionTarget "fallbackProjectionLayerOverrideDiagnostic=true"
Assert-Contains "Camera projection target" $cameraProjectionTarget "projectionContentMappingMode=target-local-raster"
Assert-Contains "Camera projection target" $cameraProjectionTarget "update_camera_hwb_projection_stereo_horizontal_offset_uv"
Assert-Contains "Camera projection target" $cameraProjectionTarget "update_camera_hwb_projection_target_live_scale"
Assert-Contains "Camera projection target" $cameraProjectionTarget "projectionTargetStereoHorizontalOffsetUv="
Assert-Contains "Camera projection target" $cameraProjectionTarget "projectionTargetLiveScale="
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
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "update_spatial_public_opaque_projection_layer_override"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "update_spatial_public_depth_layer_policy"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "current_spatial_public_depth_layer_policy"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "update_spatial_public_depth_alignment"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "current_spatial_public_depth_alignment"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "depth_uv_transform_for_eye"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthAlignmentLeftOffsetUv="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthAlignmentSampleScale="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthLayerPolicy="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthLayerCompareMode="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "shader-samples-layer0-and-layer1-at-same-depth-uv"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "OPAQUE_PROJECTION_EFFECT"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "frame_marker_fields"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "compact_projection_evidence_marker_fields"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackProjectionApplied="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackLayerCycleEnabled=true"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackLayerCycleElapsedSeconds="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueProjectionPipelineReady="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthFallbackReady="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "spatial_environment_depth_marker_fields"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthFallbackDescriptorBound="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthSource=spatial-fallback-depth-descriptor"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackDepthRealProviderBound=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "environmentDepthAcquireStatus=not-attempted-provider-not-bound"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "environmentDepthDebugValidSampleCount=0"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackPassExecutionReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicGuideBlurRuntimeReady=false"
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaqueProjectionPayloadExecutionReady="
Assert-Contains "Native public multi-stack runtime" $nativeMultiStackRuntime "publicMultiStackOpaquePayloadExecutionReady=false"
Assert-NotContains "Native public multi-stack runtime" $nativeMultiStackRuntime "CameraProbeRuntime"
Assert-NotContains "Native public multi-stack runtime" $nativeMultiStackRuntime "surface_particle_layer"
Assert-Contains "Native controller actions" $nativeControllerActions "nativeStartSpatialControllerActions"
Assert-Contains "Native controller actions" $nativeControllerActions "nativePollSpatialControllerLeftThumbstickY"
Assert-Contains "Native controller actions" $nativeControllerActions "nativePollSpatialControllerRightThumbstickY"
Assert-Contains "Native controller actions" $nativeControllerActions "nativePollSpatialControllerRightButtonB"
Assert-Contains "Native controller actions" $nativeControllerActions "xrAttachSessionActionSets"
Assert-Contains "Native controller actions" $nativeControllerActions "/user/hand/left/input/thumbstick/y"
Assert-Contains "Native controller actions" $nativeControllerActions "/user/hand/right/input/thumbstick/y"
Assert-Contains "Native controller actions" $nativeControllerActions "/user/hand/right/input/b/click"
Assert-Contains "Native controller actions" $nativeControllerActions "xrGetActionStateBoolean"
Assert-Contains "Native controller actions" $nativeControllerActions "spatial-controller-actions"
Assert-Contains "Native controller actions" $nativeControllerActions "actionSetAttached=true"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "nativeRequestSpatialMultimodalInput"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "XR_META_simultaneous_hands_and_controllers"
Assert-Contains "Native multimodal input" $nativeMultimodalInput "XR_META_detached_controllers"
Assert-Contains "Native passthrough" $nativePassthrough "nativeStartSpatialNativePassthrough"
Assert-Contains "Native passthrough" $nativePassthrough "xrCreatePassthroughFB"
Assert-Contains "Native passthrough" $nativePassthrough "xrCreatePassthroughLayerFB"
Assert-Contains "Native passthrough" $nativePassthrough "xrPassthroughLayerResumeFB"
Assert-Contains "Native passthrough" $nativePassthrough "PassthroughFlagsFB::IS_RUNNING_AT_CREATION"
Assert-Contains "Native passthrough" $nativePassthrough "PassthroughLayerPurposeFB::RECONSTRUCTION"
Assert-Contains "Native passthrough" $nativePassthrough "nativePassthroughLayerActive=true"
Assert-Contains "Native passthrough" $nativePassthrough "environmentDepthPassthroughPrerequisite=active"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "nativeStartSpatialEnvironmentDepthProbe"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "XR_META_environment_depth"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "xrCreateEnvironmentDepthProviderMETA"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "xrCreateEnvironmentDepthSwapchainMETA"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "xrStartEnvironmentDepthProviderMETA"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "xrAcquireEnvironmentDepthImageMETA"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "EnvironmentDepthImageTimestampMETA"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "environmentDepthAcquireDisplayTimePolicy=diagnostic-zero-time"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "environmentDepthRealProviderBound=true"
Assert-Contains "Native environment depth" $nativeEnvironmentDepth "environmentDepthValidData=true"
Assert-Contains "Native lib" $nativeLib "mod spatial_environment_depth"
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
Assert-Contains "Camera HWB WSI" $cameraWsi "SpatialVideoProjectionRenderer"
Assert-Contains "Camera HWB WSI" $cameraWsi "CameraHwbRecordResult"
Assert-Contains "Camera HWB WSI" $cameraWsi "begin_camera_hwb_final_render_pass"
Assert-Contains "Camera HWB WSI" $cameraWsi "record_video_eye"
Assert-Contains "Camera HWB WSI" $cameraWsi "record_spatial_public_projection_in_open_render_pass"
Assert-Contains "Camera HWB WSI" $cameraWsi "video_stats"
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
Assert-Contains "Build script" $buildScript 'spatial_scene_permission_declared = $true'
Assert-Contains "Build script" $buildScript 'spatial_environment_depth_permission_surface = "horizonos.permission.USE_SCENE+USE_SCENE_DATA"'
Assert-Contains "Build script" $buildScript 'spatial_environment_depth_real_provider_bound = $false'
Assert-Contains "Build script" $buildScript 'spatial_environment_depth_data_source = "spatial-fallback-depth-descriptor"'
Assert-Contains "Build script" $buildScript 'spatial_multimodal_input_default_enabled = $false'
Assert-Contains "Build script" $buildScript 'native_spatial_controller_actions_default_enabled = $false'
Assert-Contains "Build script" $buildScript '$PrivateLayerProfilePath = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE'
Assert-Contains "Build script" $buildScript '$OpaqueGuideShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER'
Assert-Contains "Build script" $buildScript '$OpaqueProjectionShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER'
Assert-Contains "Build script" $buildScript '$OpaqueProjectionEffect = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT'
Assert-Contains "Build script" $buildScript 'Both -OpaqueGuideShader and -OpaqueProjectionShader are required'
Assert-Contains "Build script" $buildScript 'spatial_public_multistack_private_shader_inputs'
Assert-Contains "Build script" $buildScript 'spatial_public_multistack_opaque_guide_shader_configured'
Assert-Contains "Build script" $buildScript 'spatial_public_multistack_opaque_projection_shader_configured'
Assert-Contains "Build script" $buildScript 'spatial_public_multistack_opaque_projection_effect'
Assert-Contains "Test script" $testScript '$PrivateLayerProfilePath = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE'
Assert-Contains "Test script" $testScript '-PrivateLayerProfilePath $PrivateLayerProfilePath'
Assert-Contains "Test script" $testScript '-OpaqueGuideShader $OpaqueGuideShader'
Assert-Contains "Test script" $testScript '-OpaqueProjectionShader $OpaqueProjectionShader'
Assert-Contains "Test script" $testScript '-OpaqueProjectionEffect $OpaqueProjectionEffect'
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_hwb_projection_smoke"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.camera_hwb_projection_probe"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "foreground_validation_passed"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "foreground-proof.json"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "dumpsys"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "screenshot_valid"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "Measure-SyntheticSurfacePixels"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "SyntheticVisualProbe"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "synthetic_visual_visible"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "SkyboxMode"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.skybox.mode"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_skybox_custom_scene_mesh"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_skybox_custom_background_order"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "manual-panel-scene-object-custom-mesh"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "manual_panel_scene_object_custom_mesh_ready"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "projection_panel_hittable_manual_noninteractive"
Assert-Contains "Layering matrix wrapper" $layeringMatrix "scenequadlayer-custom-skybox-only"
Assert-Contains "Layering matrix wrapper" $layeringMatrix "scenequadlayer-room-custom-skybox"
Assert-Contains "Layering matrix wrapper" $layeringMatrix "video-panel-room-custom-skybox"
Assert-Contains "Layering matrix wrapper" $layeringMatrix '"manual-carrier"'
Assert-Contains "Layering matrix wrapper" $layeringMatrix "manual-panel-custom-mesh-room-sample-skybox"
Assert-Contains "Layering matrix wrapper" $layeringMatrix "manual-panel-custom-mesh-room-custom-skybox"
Assert-Contains "Layering matrix wrapper" $layeringMatrix "SkyboxMode"
Assert-Contains "Layering matrix wrapper" $layeringMatrix "debug.rustyquest.spatial.skybox.mode"
Assert-Contains "Carrier probe plan" $carrierPlan "spatialsdkpanels.txt"
Assert-Contains "Carrier probe plan" $carrierPlan "readable-producer-scene-material-quad"
Assert-Contains "Carrier probe plan" $carrierPlan "SceneTexture"
Assert-Contains "Carrier probe plan" $carrierPlan "SceneMaterial"
Assert-Contains "Carrier probe plan" $carrierPlan "PanelInputOptions(clickButtons = 0)"
Assert-Contains "Carrier probe plan" $carrierPlan "panelConfig.layerConfig = null"
Assert-Contains "Carrier probe plan" $carrierPlan "manual-panel-scene-object-custom-mesh"
Assert-Contains "Carrier probe plan" $carrierPlan "manual-carrier"
Assert-Contains "Carrier probe plan" $carrierPlan "forceSceneTexture = true"
Assert-Contains "Carrier probe plan" $carrierPlan "QuadLayerConfig(zIndex = 99)"
Assert-Contains "Room/worldspace iteration log" $roomIterationLog "spatialsdkpanels.txt"
Assert-Contains "Room/worldspace iteration log" $roomIterationLog "readable media producer"
Assert-Contains "Room/worldspace iteration log" $roomIterationLog "panelConfig.layerConfig = null"
Assert-Contains "Room/worldspace iteration log" $roomIterationLog "manualPanelNoHittable=true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.camera_hwb_projection_probe.depth.layer_policy"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "DepthLayerPolicy"
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
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "RequireSpatialVideoProjection"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "VideoOnly"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "VideoPath"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "VideoSourcePath"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "Stage-NativeRendererVideo.ps1"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "Grant-SpatialCameraPanelAndroidPermissions.ps1"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "permission-pregrant.json"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_scene_permission_declared"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_scene_data_appop_mode"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "app-private-staged-source"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_source_path"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_path_transport"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial-video-stage.json"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.video_projection_probe"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.camera_hwb_projection_probe.video.path"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_mediacodec_started"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_decoded_frame_acquired"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_ahb_import_ready"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_rendered"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_video_only_presented"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_runtime_absent_when_video_only"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_video_projection_no_cpu_copy"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "public_multistack_projection_applied"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "public_multistack_layer_cycle_enabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "public_multistack_depth_layer_policy_marker"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "public_multistack_depth_layer_compare_visual_shader"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_particle_layer_suppressed"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_particle_layer_started_false"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_particle_renderer_ready_false"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_stack_surface_layer_mode_absent"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackProjectionApplied=true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackLayerCycleEnabled=true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackDepthLayerPolicy="
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "publicMultiStackDepthLayerCompareMode="
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_required_openxr_includes_environment_depth"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_environment_depth_provider_created"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_environment_depth_acquire_thread_started"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "environmentDepthAcquireStatus=acquired"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "environmentDepthDebugValidSampleCount=([1-9][0-9]*)"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_native_passthrough_layer_active"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_native_passthrough_layer_inactive"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "environmentDepthPassthroughPrerequisite=active"
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
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "stereoHorizontalOffsetJoystickInput=disabled-default-locked-left-stick-y-controls-workflow-or-private-panel-distance-only"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "projection_panel_input_pass_through"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "projection_panel_hittable_no_collision"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_default_reach_distance_preserved"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_left_stick_distance_enabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_distance_persists_across_toggle"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "right_stick_side_flick_panel_move_disabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_button_selected"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_override_submitted"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_override_native_updated"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_panel_projection_refresh_forced"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "adb_serial_required = `$true"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "RequireSpatialAssetModel"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "EnableVirtualRoom"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "RequireSpatialVirtualRoom"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "Stage-SpatialCameraPanelAsset.ps1"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.asset_model.mesh_uri"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "debug.rustyquest.spatial.virtual_room.enabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_asset_model_entity_created"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_asset_model_private_source_not_packaged"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "spatial_virtual_room_loaded"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_projection_wall_toggle_disabled"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "legacy_launcher_panel_suppressed"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_projection_initial_full_fov_mode"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "camera_projection_room_render_order"
Assert-Contains "Camera projection smoke wrapper" $cameraProjectionSmoke "private_layer_controls_apply_to_wall_and_full_fov"
Assert-Contains "Build script" $buildScript 'spatial_sdk_3d_asset_module = "spatial-sdk-staged-3d-asset"'
Assert-Contains "Build script" $buildScript 'spatial_sdk_3d_asset_module_source_policy = "no-source-model-packaged-or-committed"'
Assert-Contains "Build script" $buildScript 'spatial_sdk_3d_asset_supported_runtime_mesh_formats = @("glb", "gltf")'
Assert-Contains "Build script" $buildScript 'spatial_sdk_virtual_room_module = "spatial-sdk-packaged-virtual-room"'
Assert-Contains "Build script" $buildScript 'spatial_sdk_virtual_room_runtime_property = "debug.rustyquest.spatial.virtual_room.enabled"'
Assert-Contains "Build script" $buildScript 'spatial_sdk_virtual_room_mruk_policy = "disabled-not-real-room-placement"'
Assert-Contains "Stage Spatial asset script" $stageSpatialAsset "rusty.quest.spatial_camera_panel.staged_asset.v1"
Assert-Contains "Stage Spatial asset script" $stageSpatialAsset "Raw FBX is a local source format only"
Assert-Contains "Stage Spatial asset script" $stageSpatialAsset "ConvertedMeshPath"
Assert-Contains "Stage Spatial asset script" $stageSpatialAsset "sdk_loadable_mesh_uri"
Assert-Contains "Spatial permission pregrant" $spatialPermissionPregrant "rusty.quest.spatial_camera_panel_permission_pregrant.v1"
Assert-Contains "Spatial permission pregrant" $spatialPermissionPregrant "horizonos.permission.USE_SCENE"
Assert-Contains "Spatial permission pregrant" $spatialPermissionPregrant "USE_SCENE_DATA"
Assert-Contains "Spatial permission pregrant" $spatialPermissionPregrant "skipped_undeclared_permissions"
Assert-Contains "Spatial public multi-stack" $publicMultiStack 'publicMultiStackDepthSource=$depthSource'
Assert-Contains "Spatial public multi-stack" $publicMultiStack "spatial-fallback-depth-descriptor"
Assert-Contains "Spatial public multi-stack" $publicMultiStack "xr-meta-environment-depth"
Assert-Contains "Spatial public multi-stack" $publicMultiStack "nativeEnvironmentDepthProviderBound"
Assert-Contains "Spatial public multi-stack" $publicMultiStack 'environmentDepthAcquireStatus=$depthAcquireStatus'
Assert-Contains "Spatial public multi-stack" $publicMultiStack "not-attempted-provider-not-bound"
Assert-Contains "Spatial public multi-stack" $publicMultiStack "nativePassthroughRequested=true"
Assert-Contains "Spatial public multi-stack" $publicMultiStack "nativePassthroughLayerActive=`$nativePassthroughLayerActive"
Assert-Contains "Native public multi-stack" $nativeMultiStack "publicMultiStackDepthSource=spatial-fallback-depth-descriptor"
Assert-Contains "Native public multi-stack" $nativeMultiStack "spatial_environment_depth_marker_fields"
Assert-Contains "Native public multi-stack" $nativeMultiStack "environmentDepthAcquireStatus=not-attempted-provider-not-bound"
Assert-Contains "Native public multi-stack" $nativeMultiStack "spatial_native_passthrough_marker_fields"
Assert-Contains "UI action wrapper" $uiActionWrapper "private-layer-panel-open"
Assert-Contains "UI action wrapper" $uiActionWrapper "private-layer-panel-close"
Assert-Contains "README" $readme "Raw Camera2/AHardwareBuffer projection probes"
Assert-Contains "README" $readme "Spatial SDK Lane Source Map"
Assert-Contains "README" $readme "public seven-slot camera guide multi-stack contract"
Assert-Contains "README" $readme "official Spatial SDK panel sample"
Assert-Contains "README" $readme "normal path is"
Assert-Contains "README" $readme '`interaction_sdk`'
Assert-Contains "README" $readme "registerRequiredOpenXRExtensions()"
Assert-Contains "README" $readme "XR_META_detached_controllers"
Assert-Contains "README" $readme "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1"
Assert-Contains "README" $readme "right-stick-y-projection-target-scale"
Assert-Contains "README" $readme "controls workflow panel distance"
Assert-Contains "README" $readme "Right-stick X is intentionally"
Assert-Contains "README" $readme "spatial_private_layer_panel"
Assert-Contains "README" $readme "Grabbable(type = PIVOT_Y)"
Assert-Contains "README" $readme "Compose drag-driven movement"
Assert-Contains "README" $readme "app reapplies the stored placement"
Assert-Contains "README" $readme "thumbstick-driven projection scale is suppressed"
Assert-Contains "README" $readme "A/trigger select"
Assert-Contains "README" $readme "movement authority"
Assert-Contains "README" $readme "scenequadlayer-room-object"
Assert-Contains "README" $readme "debug.rustyquest.spatial.camera_hwb_projection_probe.carrier"
Assert-Contains "README" $readme "spatial-sdk-layer"
Assert-Contains "README" $readme "layer z-index"
Assert-Contains "README" $readme "0.22m"
Assert-Contains "README" $readme "projectionPanelInputPassThrough=true"
Assert-Contains "README" $readme "projectionPanelHittable=none-manual-custom-mesh-noninteractive"
Assert-Contains "README" $readme "forceSceneTexture=true"
Assert-Contains "README" $readme "VideoSourcePath"
Assert-Contains "README" $readme "Spatial SDK staged 3D asset"
Assert-Contains "README" $readme "packaged virtual room"
Assert-Contains "README" $readme "debug.rustyquest.spatial.virtual_room.enabled"
Assert-Contains "README" $readme "Spatial SDK ECS world-space hand billboard flock"
Assert-Contains "README" $readme "debug.rustyquest.spatial.hand_billboard_flock.enabled=true"
Assert-Contains "README" $readme "right secondary/B button"
Assert-Contains "README" $readme "not MRUK"
Assert-Contains "README" $readme "Stage-SpatialCameraPanelAsset.ps1"
Assert-Contains "README" $readme "RequireSpatialAssetModel"
Assert-Contains "README" $readme "/sdcard/Android/data/io.github.mesmerprism.rustyquest.spatial_camera_panel/files/v.mp4"
Assert-Contains "README" $readme "nativeUpdatePrivateLayerOverride"
Assert-Contains "README" $readme "nativeUpdatePrivateLayerDepthAlignment"
Assert-Contains "Implementation notes" $notes "Private effect formulas"
Assert-Contains "Implementation notes" $notes "Public seven-slot camera guide multi-stack contract"
Assert-Contains "Implementation notes" $notes "public multi-stack receipts"
Assert-Contains "Implementation notes" $notes "right-stick-y-projection-target-scale"
Assert-Contains "Implementation notes" $notes "Left-stick Y controls workflow-panel"
Assert-Contains "Implementation notes" $notes "layer-control panel is open it controls that panel's stored distance"
Assert-Contains "Implementation notes" $notes "spatial_private_layer_panel"
Assert-Contains "Implementation notes" $notes "scenequadlayer-room-object"
Assert-Contains "Implementation notes" $notes "Grabbable(type = PIVOT_Y)"
Assert-Contains "Implementation notes" $notes "Compose drag deltas"
Assert-Contains "Implementation notes" $notes "free world-space grabbable"
Assert-Contains "Implementation notes" $notes "forced radial placement"
Assert-Contains "Implementation notes" $notes "thumbstick-driven projection"
Assert-Contains "Implementation notes" $notes "projectionPanelInputPassThrough=true"
Assert-Contains "Implementation notes" $notes "A/trigger select"
Assert-Contains "Implementation notes" $notes "nativeUpdatePrivateLayerOverride"
Assert-Contains "Implementation notes" $notes "nativeUpdatePrivateLayerDepthAlignment"
Assert-Contains "Implementation notes" $notes "Spatial SDK staged 3D asset"
Assert-Contains "Implementation notes" $notes "spatial-sdk-staged-3d-asset"
Assert-Contains "Implementation notes" $notes "spatial-sdk-packaged-virtual-room"
Assert-Contains "Implementation notes" $notes "ECS World-Space Hand Billboard Flock"
Assert-Contains "Implementation notes" $notes "spatial-sdk-world-hand-billboard-flock"
Assert-Contains "Implementation notes" $notes "debug.rustyquest.spatial.hand_billboard_flock.enabled=false"
Assert-Contains "Implementation notes" $notes "status=world-space-updated"
Assert-Contains "Implementation notes" $notes "RequireSpatialVirtualRoom"

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

$spatialAppPath = Join-Path $repoRootPath "apps\spatial-camera-panel-android"
$forbiddenMediaExtensions = @(".mp4", ".mov", ".mkv", ".webm", ".avi")
$packagedVideoAssets = Get-ChildItem -LiteralPath $spatialAppPath -Recurse -File |
    Where-Object { $forbiddenMediaExtensions -contains $_.Extension.ToLowerInvariant() }
if ($packagedVideoAssets.Count -gt 0) {
    $paths = ($packagedVideoAssets | ForEach-Object { $_.FullName }) -join "`n"
    throw "Spatial Camera Panel must not package video assets. Found:`n$paths"
}

$forbiddenRawModelExtensions = @(".fbx")
$packagedRawModelAssets = Get-ChildItem -LiteralPath $spatialAppPath -Recurse -File |
    Where-Object { $forbiddenRawModelExtensions -contains $_.Extension.ToLowerInvariant() }
if ($packagedRawModelAssets.Count -gt 0) {
    $paths = ($packagedRawModelAssets | ForEach-Object { $_.FullName }) -join "`n"
    throw "Spatial Camera Panel must not package raw source model assets. Found:`n$paths"
}

$spatialSourceRoots = @(
    "apps\spatial-camera-panel-android\app\src\main",
    "apps\spatial-camera-panel-android\native-receipt\src",
    "apps\spatial-camera-panel-android\native-receipt\shaders"
)
$spatialMediaScanSuffixes = @(".kt", ".java", ".rs", ".glsl", ".kts", ".xml")
$mediaBoundaryNeedles = @(
    "noodletest",
    ".mp4",
    ".mov",
    ".mkv",
    ".webm",
    "C:\Users\",
    "S:\Work\"
)
foreach ($root in $spatialSourceRoots) {
    $path = Join-Path $repoRootPath $root
    $files = Get-ChildItem -LiteralPath $path -Recurse -File |
        Where-Object { $spatialMediaScanSuffixes -contains $_.Extension }
    foreach ($file in $files) {
        $relative = $file.FullName -replace ("^" + [regex]::Escape($repoRootPath) + "[\\/]*"), ""
        $text = Get-Content -Raw -LiteralPath $file.FullName
        foreach ($needle in $mediaBoundaryNeedles) {
            Assert-NotContains $relative $text $needle
        }
    }
}

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
    "tools\Stage-SpatialCameraPanelAsset.ps1",
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
