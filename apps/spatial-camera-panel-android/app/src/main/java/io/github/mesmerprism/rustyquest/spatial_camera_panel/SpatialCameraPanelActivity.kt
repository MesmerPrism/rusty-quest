package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface as AndroidSurface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box as ComposeBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelSurface
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneQuadLayer
import com.meta.spatial.runtime.SceneSwapchain
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MediaPanelDisplayOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionControls
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

class SpatialCameraPanelActivity : AppSystemActivity() {
  private val store: SpatialCameraPanelStore by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraPanelStore(this)
  }
  private var nativeReceiptLibraryLoaded = false
  private var nativeReceiptLibraryError = "not-loaded"
  private var nativeSpatialControllerActionsStarted = false
  private var nativeSpatialControllerActionsStartMask = 0L
  private var spatialMultimodalInputRequested = false
  private var spatialMultimodalInputRequestMask = 0L
  private var panelEntity: Entity? = null
  private var privateLayerPanelEntity: Entity? = null
  private var privateLayerPanelSceneObject: PanelSceneObject? = null
  private var privateLayerPanelVisible = false
  private var privateLayerOverride = PrivateLayerControls.cycleOverride
  private var privateLayerDepthLayerPolicy = PrivateLayerControls.defaultDepthLayerPolicy
  private var privateLayerDepthAlignment = PrivateLayerDepthAlignment()
  private var panelLauncherEntity: Entity? = null
  private var panelPlacement = PanelPlacement(visible = !startInParticleView())
  private var privateLayerPanelPlacement = SpatialPanelPlacementModule.initialPrivateLayerPlacement()
  private var particleControls = SurfaceParticleControlState()
  private var questionnaireDueReopensPanel by mutableStateOf(true)
  private var particleLayerEntity: Entity? = null
  private var particleLayerPanelSceneObject: PanelSceneObject? = null
  private var particleLayerManualPanelSurface: AndroidSurface? = null
  private var panelRegistrationCount = 0
  private var particleSurfacePanelReady = false
  private var particleSurfaceConsumerCalled = false
  private var particleSurfaceConsumerSurfaceValid = false
  private var particleLayerStarted = false
  private var cameraStackSuppressesParticles = false
  private var nativeSurfaceStartRequested = false
  private var lastNativeSurfaceStartMask = 0L
  private var particleLayerProjectionMarkerCount = 0
  private var lastParticleLayerProjectionMarkerMs = 0L
  private var lastParticleLayerTargetDistanceMeters: Float? = null
  private var lastParticleLayerSurfaceOverscanScale: Float? = null
  private var lastParticleLayerPanelOpacity: Float? = null
  private var lastParticleLayerPanelLayerCheckMs = 0L
  private var particleLayerPanelLayerConfigured = false
  private var particleLayerSurfaceGeometryApplied = false
  private var remoteParticleLayerTargetDistanceMeters: Float? = null
  private var remoteParticleLayerViewYawDegrees: Float? = null
  private var polarSensorPanel: PolarSensorPanel? = null
  private var panelHeadlockMarkerCount = 0
  private var lastPanelHeadlockMarkerMs = 0L
  private var lastPanelHeadlockHotloadToken = ""
  private var lastPanelHeadlockJoystickMs = 0L
  private var lastPanelHeadlockJoystickMarkerMs = 0L
  private var lastPrivateLayerPanelGrabbableState: Boolean? = null
  private var lastPrivateLayerPanelGrabbableMarkerMs = 0L
  private var spatialControllerPrimaryDown = false
  private var spatialControllerRouteLogged = false
  private var lastSpatialControllerRouteMarkerMs = 0L
  private var lastSpatialControllerComponentCount = -1
  private var lastSpatialControllerActiveCount = -1
  private var lastSpatialControllerControllerTypeCount = -1
  private var lastSpatialControllerAllButtonState = -1
  private var spatialControllerSecondaryDown = false
  private var spatialControllerRightTriggerDown = false
  private var nativeControllerSecondaryDown = false
  private val pinnedSpatialGameControllerIds = mutableSetOf<Int>()
  private var lastSpatialInputRouteMarkerMs = 0L
  private var lastSpatialJoystickArbitrationMarkerMs = 0L
  private var androidControllerPrimaryKeyDown = false
  private var androidControllerPrimaryMotionDown = false
  private var androidControllerSecondaryKeyDown = false
  private var androidControllerSecondaryMotionDown = false
  private var androidControllerRightTriggerKeyDown = false
  private var androidControllerRightTriggerMotionDown = false
  private var externalSwapchainProbeStarted = false
  private var externalSwapchainProbeLayer: SceneQuadLayer? = null
  private var externalSwapchainProbeSceneObject: SceneObject? = null
  private var externalSwapchainProbeWrappedSwapchain: SceneSwapchain? = null
  private var externalSwapchainProbeExternalHandle = 0L
  private val externalSwapchainProbeSdkWrapRetainers = mutableListOf<SceneSwapchain>()
  private val externalSwapchainProbeExternalWrapRetainers = mutableListOf<SceneSwapchain>()
  private var sdkQuadSurfaceProbeStarted = false
  private var sdkQuadVulkanProbeStarted = false
  private var sdkQuadStereoAlphaProbeStarted = false
  private var sdkQuadStereoAlphaProbeZIndexChanged = false
  private var panelSurfaceMatrixProbeStarted = false
  private var cameraHwbProbeStarted = false
  private var cameraHwbProjectionProbeStarted = false
  private var spatialVideoProjectionProbeStarted = false
  private var spatialVideoProjectionSettings = SpatialVideoProjectionSettings.disabled()
  private var spatialVideoProjectionStarted = false
  private var nativeSpatialEnvironmentDepthStartMask = 0L
  private var cameraHwbProjectionEntity: Entity? = null
  private var cameraHwbProjectionPanelEntity: Entity? = null
  private var cameraHwbProjectionPanelSceneObject: PanelSceneObject? = null
  private var cameraHwbProjectionPanelSurface: AndroidSurface? = null
  private var cameraHwbProjectionPanelSurfaceConsumerCalled = false
  private var cameraHwbProjectionPanelReady = false
  private var cameraHwbProjectionPanelNativeStarted = false
  private var cameraHwbProjectionPanelStartMask = 0L
  private var cameraHwbProjectionSyntheticVisualPresented = false
  private var cameraHwbProjectionReaderMaxImages =
      CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES
  private var cameraHwbProjectionTargetScale = CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT
  private var cameraHwbProjectionStereoHorizontalOffsetUv =
      CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
  private var cameraHwbProjectionPlacementMode = CameraHwbProjectionPlacementMode.ViewerLocked
  private var cameraHwbProjectionCarrierMode = CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject
  private var lastCameraHwbProjectionPlacementToggleMs = 0L
  private var cameraHwbProjectionSecondaryToggleArmed = false
  private var cameraHwbProjectionMarkerCount = 0
  private var lastCameraHwbProjectionMarkerMs = 0L
  private var lastCameraHwbProjectionScaleJoystickMs = 0L
  private var lastCameraHwbProjectionScaleJoystickMarkerMs = 0L
  private var sdkQuadSurfaceProbeLayer: SceneQuadLayer? = null
  private var sdkQuadSurfaceProbeSceneObject: SceneObject? = null
  private var sdkQuadSurfaceProbeSwapchain: SceneSwapchain? = null
  private var sdkQuadSurfaceProbeSurface: AndroidSurface? = null
  private var sdkQuadSurfaceProbeAnchorMesh: SceneMesh? = null
  private var sdkQuadSurfaceProbeAnchorMaterial: SceneMaterial? = null
  private val stagedAssetModule = SpatialStagedAssetModule(::marker)
  private val activityScope = CoroutineScope(Dispatchers.Main)
  private val spatialVirtualRoomModule: SpatialVirtualRoomModule by lazy(LazyThreadSafetyMode.NONE) {
    SpatialVirtualRoomModule(
        context = applicationContext,
        scene = scene,
        activityScope = activityScope,
        loadGlxf = { uri, root, onLoaded ->
          glXFManager.inflateGLXF(uri, rootEntity = root, onLoaded = onLoaded)
        },
        marker = ::marker,
    )
  }
  private var spatialSceneReady = false

  override fun registerRequiredOpenXRExtensions(): List<String> {
    return (super.registerRequiredOpenXRExtensions() + spatialRequiredOpenXrExtensions())
        .distinct()
  }

  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(
        VRFeature(
            this,
            LocomotionControls.Right,
            currentSpatialShouldConsumeLeftRightInput(),
            currentSpatialVrInputSystemType(),
        ),
        SpatialAvatarHandVisualFeature(::marker),
        SpatialAvatarHandInvestigationFeature(::marker),
        SpatialHandBillboardFlockFeature(::marker) { store.snapshot().surfaceTargetId },
        SpatialHandCaptureRecorderFeature(this, ::marker) {
          SpatialNativeInteropProbe.capture(scene)
        },
        SpatialControllerInputLateFeature(::pollSpatialControllerInput),
    ) + SpatialPrivateFeatureLoader.load(::marker, this) + listOf(
        ComposeFeature(),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    loadNativeReceiptLibrary()
    suppressParticleLayerIfCameraProjectionRequested("activity-created")
    deactivateLegacyWorkflowPanelsForCameraStack("activity-created")
    deactivatePanelShellIfRequested("activity-created")
    if (shouldResetExperimentForPanelFirstLaunch(intent)) {
      store.resetForNewParticipant()
      marker(ExperimentPanelController.panelFirstLaunchResetMarker())
    }
    marker(
        "channel=activity status=created package=${BuildConfig.APPLICATION_ID} " +
            "sourceNamespace=io.github.mesmerprism.rustyquest.spatial_camera_panel " +
            "highRateJsonPayload=false hand_rendering_expected=false controller_rendering_expected=true " +
            "spatialPointerInputExpected=true nativeSurfaceParticleLayerExpected=true " +
            "spatialVrInputSystem=${currentSpatialVrInputSystemToken()} " +
            "spatialVrInputSystemProperty=$SPATIAL_VR_INPUT_SYSTEM_PROPERTY " +
            "spatialShouldConsumeLeftRightInput=${currentSpatialShouldConsumeLeftRightInput()} " +
            "spatialShouldConsumeLeftRightInputProperty=$SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY " +
            "spatialMultimodalInputProperty=$SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY " +
            "nativeSpatialControllerActionsProperty=$NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY " +
            "nativeSpatialControllerActionsDefaultEnabled=$NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED " +
            "spatialControllerOnlyMode=false spatialHandsAndControllersManifest=true " +
            "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()} " +
            "spatialSdk3dAssetModule=${SpatialStagedAssetModule.MODULE_ID} " +
            "spatialWorldHandBillboardFlock=spatial-sdk-world-hand-billboard-flock " +
            "spatialWorldHandBillboardFlockEnabledProperty=debug.rustyquest.spatial.hand_billboard_flock.enabled " +
            "spatialPrivateFeatureLoader=optional-reflection-source-set " +
            "spatialPrivateFeatureSourceEnv=RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR " +
            "spatialPrivateFeatureResourceEnv=RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR " +
            "nativeSurfaceParticleLayerEnabledProperty=$NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY " +
            "nativeSurfaceParticleLayerEnabled=${nativeSurfaceParticleLayerEnabled()} " +
            "privateSpatialEcsParticleRendererEnabledProperty=$PRIVATE_SPATIAL_ECS_PARTICLE_RENDERER_ENABLED_PROPERTY " +
            "privateSpatialEcsParticleRendererEnabled=${privateSpatialEcsParticleRendererEnabled()} " +
            "nativeSurfaceParticleLayerExclusiveRendererSuppressed=${nativeSurfaceParticleLayerSuppressedByPrivateRenderer()} " +
            "panelShellVisibleProperty=$PANEL_SHELL_VISIBLE_PROPERTY " +
            "panelShellVisible=${panelShellVisible()} " +
            "startInParticleViewProperty=$PANEL_START_IN_PARTICLE_VIEW_PROPERTY " +
            "startInParticleView=${startInParticleView()} " +
            "startInParticleViewDefault=${BuildConfig.START_IN_PARTICLE_VIEW_DEFAULT} " +
            "panelLauncherVisibleProperty=$PANEL_LAUNCHER_VISIBLE_PROPERTY " +
            "panelLauncherVisible=${panelLauncherVisible()} " +
            "panelLauncherVisibleDefault=${BuildConfig.PANEL_LAUNCHER_VISIBLE_DEFAULT} " +
            "spatialVirtualRoomModule=${SpatialVirtualRoomModule.MODULE_ID} " +
            "spatialVirtualRoomEnabledProperty=${SpatialVirtualRoomModule.ENABLED_PROPERTY} " +
            "spatialVirtualRoomDefaultEnabled=false " +
            "spatialSkyboxModule=${SpatialVirtualRoomModule.SKYBOX_MODULE_ID} " +
            "spatialSkyboxEnabledProperty=${SpatialVirtualRoomModule.SKYBOX_ENABLED_PROPERTY} " +
            "spatialSkyboxModeProperty=${SpatialVirtualRoomModule.SKYBOX_MODE_PROPERTY} " +
            "spatialSkyboxDefaultEnabled=false " +
            "spatialSkyboxDefaultMode=none " +
            "spatialSdk3dAssetHighRateJsonPayload=false " +
            "questionnaireDueReopensPanelDefault=true " +
            "remoteSurfaceTargetQuestionnaireAutoPanelSuppressed=true " +
            "spatialSdkLaneBoundaries=${SpatialSdkLaneBoundaries.summaryToken()}"
    )
    runSpatialVirtualRoomIfRequested("activity-created")
    scheduleParticleLayerLifecycleDiagnostics("activity-created")
    runValidationWorkflowIfRequested(intent)
    runPolarLiveValidationIfRequested(intent)
    runUiCommandIfRequested(intent)
    runSurfaceTargetActivationIfRequested(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    suppressParticleLayerIfCameraProjectionRequested("new-intent")
    deactivateLegacyWorkflowPanelsForCameraStack("new-intent")
    deactivatePanelShellIfRequested("new-intent")
    runValidationWorkflowIfRequested(intent)
    runPolarLiveValidationIfRequested(intent)
    runUiCommandIfRequested(intent)
    runSurfaceTargetActivationIfRequested(intent)
    runSpatialStagedAssetIfRequested(intent, "new-intent")
    runSpatialVirtualRoomIfRequested("new-intent")
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (handleControllerSecondaryButton(event)) {
      return true
    }
    if (handleControllerTrigger(event)) {
      return true
    }
    if (handleControllerPrimaryButton(event)) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    spatialSceneReady = true
    deactivateLegacyWorkflowPanelsForCameraStack("scene-ready")
    deactivatePanelShellIfRequested("scene-ready")
    configureSpatialVirtualRoomScene("scene-ready")
    enableSpatialControllerInputRoute("scene-ready", forceLog = true)
    runSpatialStagedAssetIfRequested(intent, "scene-ready")
    panelEntity =
        Entity.createPanelEntity(
            R.id.spatial_camera_panel,
            Transform(panelPose()),
            panelDimensions(),
            Visible(panelPlacement.visible),
        )
    privateLayerPanelEntity =
        Entity.createPanelEntity(
            R.id.spatial_private_layer_panel,
            Transform(privateLayerPanelPose()),
            privateLayerPanelDimensions(),
            privateLayerPanelGrabbable(enabled = privateLayerPanelVisible),
            Visible(privateLayerPanelVisible),
        )
    panelLauncherEntity =
        Entity.createPanelEntity(
            R.id.spatial_camera_panel_launcher,
            Transform(panelLauncherPose()),
            panelLauncherDimensions(),
            Visible(launcherPanelVisibleForPanelMode()),
        )
    particleLayerEntity =
        if (nativeSurfaceParticleLayerEnabled()) {
          if (particleLayerManualCustomMeshCarrierEnabled()) {
            createManualSurfaceParticleLayerPanel("scene-ready")
          } else {
            runCatching {
                  Entity.createPanelEntity(
                      R.id.spatial_camera_surface_panel,
                      Transform(particleLayerPose()),
                      particleLayerSurfacePanelDimensions(),
                      Visible(particleLayerVisibleForPanelMode()),
                  )
                }
                .getOrElse { throwable ->
                  marker(
                      SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelEntityCreateFailedMarker(
                          error = throwable.javaClass.simpleName,
                          message = throwable.message ?: "none",
                      )
                  )
                  null
                }
          }
        } else {
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelEntitySuppressedMarker(
                  source = nativeSurfaceParticleLayerSuppressionSource(),
                  privateSpatialEcsParticleRendererEnabled =
                      privateSpatialEcsParticleRendererEnabled(),
              )
          )
          null
        }
    applyPanelPlacement()
    updateWorkflowPanelHeadlockFromViewer(reason = "scene-ready", forceLog = true)
    updateParticleLayerProjectionFromViewer(reason = "scene-ready", forceLog = true)
    logNativeInteropProbe(phase = "scene-ready", probeSurface = false)
    marker(
        "channel=spatial-panel status=spawned panelRegistrationId=spatial_camera_panel " +
            "privateLayerPanelRegistrationId=spatial_private_layer_panel " +
            "launcherPanelRegistrationId=spatial_camera_panel_launcher " +
            "panelY=${panelPlacement.yMeters} panelZ=${panelPlacement.zMeters} panelScale=${panelPlacement.scale} " +
            "panelWidth=${panelPlacement.widthMeters} panelHeight=${panelPlacement.heightMeters} " +
            "workflowPanelVisible=${panelPlacement.visible} " +
            "privateLayerPanelVisible=$privateLayerPanelVisible " +
            "launcherPanelVisible=${launcherPanelVisibleForPanelMode()} " +
            "legacyLauncherPanelSuppressed=${legacyLauncherPanelSuppressedForCameraStack()} " +
            "particleLayerVisible=${particleLayerVisibleForPanelMode()} " +
            "visibleComponent=true panelDimensionsComponent=true diagnosticBackdrop=false contrastEnvironment=false " +
            "panelMode=${panelStateToken()} rendererAuthority=native-vulkan-wsi-surface-panel"
    )
    marker(
        ExperimentPanelController.panelFirstFlowReadyMarker(
            questionnaireDueReopensPanel = questionnaireDueReopensPanel,
            particleLayerVisible = particleLayerVisibleForPanelMode(),
        )
    )
    marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelEntitySpawnedMarker(
            placementMarkerFields = particleLayerPlacementMarkerFields(),
            stereoMarkerFields = particleLayerStereoMarkerFields(),
        )
    )
    scheduleParticleLayerLifecycleDiagnostics("scene-ready")
    runSpatialVideoProjectionProbeIfRequested("scene-ready")
    runCameraHwbProjectionProbeIfRequested("scene-ready")
  }

  override fun onVRReady() {
    super.onVRReady()
    enableSpatialControllerInputRoute("vr-ready", forceLog = true)
    updateWorkflowPanelHeadlockFromViewer(reason = "vr-ready", forceLog = true)
    updateParticleLayerProjectionFromViewer(reason = "vr-ready", forceLog = true)
    logNativeInteropProbe(phase = "vr-ready", probeSurface = true)
    runExternalSwapchainProbeIfRequested("vr-ready")
    runSdkQuadSurfaceProbeIfRequested("vr-ready")
    runSdkQuadVulkanProbeIfRequested("vr-ready")
    runSdkQuadStereoAlphaProbeIfRequested("vr-ready")
    runPanelSurfaceMatrixProbeIfRequested("vr-ready")
    runSpatialVideoProjectionProbeIfRequested("vr-ready")
    runCameraHwbProjectionProbeIfRequested("vr-ready")
    runCameraHwbProbeIfRequested("vr-ready")
  }

  override fun onSceneTick() {
    super.onSceneTick()
    updateWorkflowPanelHeadlockFromViewer(reason = "scene-tick", forceLog = false)
    updateParticleLayerProjectionFromViewer(reason = "scene-tick", forceLog = false)
    updateCameraHwbProjectionFromViewer(reason = "scene-tick", forceLog = false)
    enableSpatialControllerInputRoute("scene-tick", forceLog = false)
    pollNativeSpatialControllerProjectionScaleInput()
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (handleControllerSecondaryButton(event)) {
      return true
    }
    if (handleControllerTrigger(event)) {
      return true
    }
    if (handleControllerPrimaryButton(event)) {
      return true
    }
    if (handleSpatialJoystickMotion(event, "android-dispatch-generic-motion")) {
      return true
    }
    return super.dispatchGenericMotionEvent(event)
  }

  override fun onDestroy() {
    if (nativeReceiptLibraryLoaded) {
      runCatching { nativeStopSpatialControllerActions() }
      runCatching { nativeStopSpatialEnvironmentDepthProbe() }
      runCatching { nativeStopSpatialNativePassthrough() }
      runCatching { nativeStopSdkQuadVulkanProbe() }
      runCatching { nativeStopCameraHwbProbe() }
      runCatching { nativeStopSpatialVideoProjectionProbe() }
    }
    stopSpatialVideoProjection("activity-destroy")
    cleanupCameraHwbProjectionPanelCarrier("activity-destroy")
    cleanupSdkQuadSurfaceProbe("activity-destroy")
    cleanupExternalSwapchainProbe("activity-destroy")
    polarSensorPanel?.stop()
    polarSensorPanel = null
    stagedAssetModule.destroy("activity-destroy")
    destroySpatialVirtualRoom("activity-destroy")
    stopNativeSurfaceParticleLayer()
    super.onDestroy()
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    val permissionArray = Array(permissions.size) { index -> permissions[index] }
    polarSensorPanel?.onRequestPermissionsResult(
        requestCode,
        permissionArray,
        grantResults,
    )
  }

  private fun shouldResetExperimentForPanelFirstLaunch(intent: Intent?): Boolean {
    val action = intent?.action
    return action == null || action == Intent.ACTION_MAIN
  }

  override fun registerPanels(): List<PanelRegistration> {
    val composePanels =
        SpatialComposePanelRegistrationModule.registrations(
            workflow =
                SpatialWorkflowPanelRegistrationBindings(
                    store = store,
                    placement = panelPlacement,
                    particleControls = particleControls,
                    polarPanel = ensurePolarSensorPanel(),
                    questionnaireDueReopensPanel = questionnaireDueReopensPanel,
                    setWorkflowPanelVisible = { visible, focus, source ->
                      setWorkflowPanelVisible(visible, focus, source)
                    },
                    adjustPlacement = { dx, dy, dz, scaleDelta ->
                      adjustPanelPlacement(dx, dy, dz, scaleDelta)
                    },
                    setPanelHeadlocked = { enabled, source ->
                      setPanelHeadlocked(enabled, source)
                    },
                    resizePanel = { deltaWidth, deltaHeight ->
                      resizeWorkflowPanel(deltaWidth, deltaHeight)
                    },
                    resetPlacement = { resetWorkflowPanelPlacement() },
                    updateParticleControls = { controls ->
                      updateSurfaceParticleControls(controls)
                    },
                    applyDriverProfile = { block, source ->
                      applyDriverProfileToParticleControls(block, source)
                    },
                    setQuestionnaireDueReopensPanel = { enabled, source ->
                      setQuestionnaireDueReopensPanel(enabled, source)
                    },
                ),
            privateLayer =
                SpatialPrivateLayerPanelRegistrationBindings(
                    layerOverride = privateLayerOverride,
                    projectionScale = currentCameraHwbProjectionTargetScale(),
                    projectionScaleRange =
                        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE..CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
                    depthLayerPolicy = privateLayerDepthLayerPolicy,
                    depthAlignment = privateLayerDepthAlignment,
                    setLayerOverride = { override, source ->
                      updatePrivateLayerOverrideFromPanel(override, source)
                    },
                    updateProjectionScale = { scale, source ->
                      updateCameraHwbProjectionTargetScaleFromPanel(scale, source)
                    },
                    updateDepthLayerPolicy = { policy, source ->
                      updatePrivateLayerDepthLayerPolicyFromPanel(policy, source)
                    },
                    updateDepthAlignment = { alignment, source ->
                      updatePrivateLayerDepthAlignmentFromPanel(alignment, source)
                    },
                    closePanel = {
                      setPrivateLayerPanelVisible(
                          false,
                          focus = false,
                          source = "private-layer-panel-close",
                      )
                    },
                    settings = { _ -> privateLayerPanelSettings() },
                    onPanelSetup = { panel ->
                      privateLayerPanelSceneObject = panel
                      val layerUpdateStatus =
                          updatePrivateLayerPanelLayer("panel-setup", forceLog = false)
                      marker(
                          SpatialPanelPlacementModule.privateLayerPanelLayerReadyMarker(
                              layerUpdateStatus = layerUpdateStatus,
                              cameraVideoProjectionLayerZIndex =
                                  cameraHwbProjectionZIndexForPlacement(
                                      cameraHwbProjectionPlacementMode
                                  ),
                          )
                      )
                    },
                ),
            openWorkflowPanel = {
              setWorkflowPanelVisible(true, focus = true, source = "launcher-panel")
            },
        )
    val panels =
        composePanels +
            listOfNotNull(
        CameraHwbProjectionPanelCarrierModule.videoSurfacePanelRegistration(
            cameraHwbProjectionVideoPanelBindings()
        ),
        if (nativeSurfaceParticleLayerEnabled() && !particleLayerManualCustomMeshCarrierEnabled()) {
          SpatialSurfaceParticlePanelCarrierModule.videoSurfacePanelRegistration(
              particleLayerVideoPanelBindings()
          )
        } else {
          val manualCarrier = particleLayerManualCustomMeshCarrierEnabled()
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelRegistrationSuppressedMarker(
                  source =
                      if (manualCarrier) "manual-scene-object-carrier"
                      else nativeSurfaceParticleLayerSuppressionSource(),
                  nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
                  privateSpatialEcsParticleRendererEnabled = privateSpatialEcsParticleRendererEnabled(),
                  carrier = particleLayerCarrierToken(),
                  manualPanelSceneObjectCustomMesh = manualCarrier,
              )
          )
          null
        },
    )
    panelRegistrationCount = panels.size
    marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelRegistrationsCreatedMarker(
            panelRegistrationCount = panelRegistrationCount,
            particlePanelRegistrationId =
                if (nativeSurfaceParticleLayerEnabled() && !particleLayerManualCustomMeshCarrierEnabled()) {
                  "spatial_camera_surface_panel"
                } else {
                  "manual-scene-object"
                },
            carrier = particleLayerCarrierToken(),
            nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
        )
    )
    scheduleParticleLayerLifecycleDiagnostics("register-panels")
    return panels
  }

  private fun cameraHwbProjectionVideoPanelBindings():
      CameraHwbProjectionVideoPanelBindings =
      CameraHwbProjectionVideoPanelBindings(
          adoptSurface = { surface ->
            cameraHwbProjectionPanelSurfaceConsumerCalled = true
            cameraHwbProjectionPanelSurface = surface
          },
          settings = { _ -> cameraHwbProjectionPanelMediaSettings() },
          adoptPanel = { panel ->
            cameraHwbProjectionPanelSceneObject = panel
            cameraHwbProjectionPanelReady = true
            cameraHwbProjectionPanelSurface = panel.surface
          },
          planeForPlacement = ::cameraHwbProjectionPlaneForPlacement,
          updateLayer = { plane ->
            updateCameraHwbProjectionPanelCarrierLayer(plane, "panel-setup")
          },
          currentProjectionMarkerFields = { cameraHwbProjectionMarkerFields() },
          projectionMarkerFields = ::cameraHwbProjectionMarkerFields,
          stereoMarkerFields = ::cameraHwbProjectionStereoMarkerFields,
          videoProjectionMarkerFields = {
            spatialVideoProjectionMarkerFields(spatialVideoProjectionSettings)
          },
          startCarrier = ::startCameraHwbProjectionPanelCarrierIfReady,
          emitMarker = ::marker,
      )

  private fun particleLayerVideoPanelBindings(): SpatialSurfaceParticleVideoPanelBindings =
      SpatialSurfaceParticleVideoPanelBindings(
          adoptSurface = { surface ->
            particleSurfaceConsumerCalled = true
            particleSurfaceConsumerSurfaceValid = surface.isValid
          },
          settings = { _ -> particleLayerMediaSettings() },
          carrier = ::particleLayerCarrierToken,
          placementMarkerFields = ::particleLayerPlacementMarkerFields,
          stereoMarkerFields = ::particleLayerStereoMarkerFields,
          startLayer = ::startNativeSurfaceParticleLayer,
          adoptPanel = { panel ->
            particleLayerPanelSceneObject = panel
            particleSurfacePanelReady = true
          },
          updateLayer = {
            updateParticleLayerPanelLayer("panel-setup", forceLog = false)
          },
          emitMarker = ::marker,
      )

  private fun ensurePolarSensorPanel(): PolarSensorPanel {
    val existing = polarSensorPanel
    if (existing != null) {
      return existing
    }
    val created =
        PolarSensorPanel(
            this,
            object : PolarSensorPanel.Host {
              override fun closePanelAndReturnToImmersive() {
                setWorkflowPanelVisible(false, focus = false, source = "polar-panel-close")
              }

              override fun onPolarStreamEvent(event: JSONObject) {
                store.appendPolarEvent(event)
              }
            },
        )
    polarSensorPanel = created
    marker(
        "channel=polar-sensor-panel status=created owner=spatial-sdk-compose-panel " +
            "streamMirror=spatial-camera-panel-store"
    )
    return created
  }

  private fun runSpatialVirtualRoomIfRequested(reason: String) {
    if (!spatialVirtualRoomModule.enabled() || spatialVirtualRoomModule.isStarted()) {
      return
    }
    cameraHwbProjectionCarrierMode = currentCameraHwbProjectionCarrierMode()
    applyPanelPlacement()
    spatialVirtualRoomModule.runIfRequested(
        reason = reason,
        projectionState = spatialVirtualRoomProjectionState(),
        onLoaded = {
          runSpatialStagedAssetIfRequested(intent, "virtual-room-loaded")
          runSpatialVideoProjectionProbeIfRequested("virtual-room-loaded")
          runCameraHwbProjectionProbeIfRequested("virtual-room-loaded")
        },
    )
  }

  private fun configureSpatialVirtualRoomScene(reason: String) {
    if (!spatialVirtualRoomModule.shouldConfigureScene()) {
      return
    }
    cameraHwbProjectionCarrierMode = currentCameraHwbProjectionCarrierMode()
    spatialVirtualRoomModule.configureScene(reason, spatialVirtualRoomProjectionState())
  }

  private fun spatialVirtualRoomProjectionState(): SpatialVirtualRoomProjectionState =
      SpatialVirtualRoomProjectionState(
          placementModeToken = cameraHwbProjectionPlacementMode.markerToken,
          carrierToken = cameraHwbProjectionCarrierToken(),
          carrierProperty = CAMERA_HWB_PROJECTION_CARRIER_PROPERTY,
          roomRenderOrderToken = cameraHwbProjectionRoomRenderOrderToken(),
      )

  private fun destroySpatialVirtualRoom(reason: String) = spatialVirtualRoomModule.destroy(reason)

  private fun spatialVirtualRoomEnabled(): Boolean = spatialVirtualRoomModule.enabled()

  private fun spatialVirtualRoomLoaded(): Boolean = spatialVirtualRoomModule.loaded

  private fun spatialSkyboxEnabled(): Boolean = spatialVirtualRoomModule.skyboxEnabled()

  private fun logNativeInteropProbe(phase: String, probeSurface: Boolean) {
    val probe = SpatialNativeInteropProbe.capture(scene)
    val surfaceProbe =
        if (probeSurface) {
          createNoRenderSurfaceProbe()
        } else {
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "deferred-until-vr-ready",
              surfaceValid = false,
              error = "none",
          )
        }
    val nativeReceipt = recordNativeInteropReceipt(probe, surfaceProbe)
    marker(SpatialOpenXrRouteModule.nativeInteropProbeMarker(phase, probe, surfaceProbe))
    marker(
        SpatialOpenXrRouteModule.nativeInteropReceiptMarker(
            phase,
            nativeReceiptLibraryLoaded,
            nativeReceipt,
        )
    )
    requestSpatialMultimodalInputIfReady(probe, phase)
    startNativeSpatialControllerActionsIfReady(probe, phase)
  }

  private fun loadNativeReceiptLibrary() {
    val result = runCatching { System.loadLibrary(NATIVE_RECEIPT_LIBRARY) }
    nativeReceiptLibraryLoaded = result.isSuccess
    nativeReceiptLibraryError = result.exceptionOrNull()?.javaClass?.simpleName ?: "none"
    marker(
        SpatialOpenXrRouteModule.nativeReceiptLibraryLoadMarker(
            library = NATIVE_RECEIPT_LIBRARY,
            loaded = nativeReceiptLibraryLoaded,
            error = nativeReceiptLibraryError,
        )
    )
  }

  private fun recordNativeInteropReceipt(
      probe: SpatialNativeInteropProbe,
      surfaceProbe: NativeInteropSurfaceProbeResult,
  ): NativeInteropReceiptResult {
    if (!nativeReceiptLibraryLoaded) {
      return SpatialOpenXrRouteModule.nativeInteropReceiptUnavailable(nativeReceiptLibraryError)
    }
    return runCatching {
          val mask =
              nativeRecordNoRenderInteropReceipt(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  surfaceProbe.surfaceValid,
              )
          SpatialOpenXrRouteModule.nativeInteropReceiptReceived(mask)
        }
            .getOrElse { throwable ->
              SpatialOpenXrRouteModule.nativeInteropReceiptCallFailed(throwable.javaClass.simpleName)
            }
  }

  private fun startSpatialNativePassthroughForDepthPrerequisite(source: String): Long {
    if (!nativeReceiptLibraryLoaded) {
      marker(SpatialOpenXrRouteModule.nativePassthroughLibraryUnavailableMarker(source, nativeReceiptLibraryError))
      return 0L
    }
    val probe =
        runCatching { SpatialNativeInteropProbe.capture(scene) }
            .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }
    val requiredOpenXrExtensions = spatialRequiredOpenXrExtensionMarker()
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      marker(SpatialOpenXrRouteModule.nativePassthroughDeferredMarker(source, probe, requiredOpenXrExtensions))
      return 0L
    }
    val mask =
        runCatching {
              nativeStartSpatialNativePassthrough(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialOpenXrRouteModule.nativePassthroughStartCallFailedMarker(
                      source,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                      requiredOpenXrExtensions,
                  )
              )
              0L
            }
    marker(
        SpatialOpenXrRouteModule.nativePassthroughStartRequestedMarker(
            source,
            mask,
            probe,
            cameraHwbProjectionEntity != null,
            requiredOpenXrExtensions,
        )
    )
    return mask
  }

  private fun startSpatialEnvironmentDepthProbe(source: String): Long {
    if (!nativeReceiptLibraryLoaded) {
      marker(SpatialOpenXrRouteModule.spatialEnvironmentDepthLibraryUnavailableMarker(source, nativeReceiptLibraryError))
      nativeSpatialEnvironmentDepthStartMask = 0L
      return 0L
    }
    val probe =
        runCatching { SpatialNativeInteropProbe.capture(scene) }
            .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }
    val requiredOpenXrExtensions = spatialRequiredOpenXrExtensionMarker()
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      marker(SpatialOpenXrRouteModule.spatialEnvironmentDepthDeferredMarker(source, probe, requiredOpenXrExtensions))
      nativeSpatialEnvironmentDepthStartMask = 0L
      return 0L
    }
    val mask =
        runCatching {
              nativeStartSpatialEnvironmentDepthProbe(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialOpenXrRouteModule.spatialEnvironmentDepthStartCallFailedMarker(
                      source,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                      requiredOpenXrExtensions,
                  )
              )
              0L
            }
    nativeSpatialEnvironmentDepthStartMask = mask
    marker(
        SpatialOpenXrRouteModule.spatialEnvironmentDepthStartRequestedMarker(
            source,
            mask,
            probe,
            requiredOpenXrExtensions,
        )
    )
    return mask
  }

  private fun createNoRenderSurfaceProbe(): NativeInteropSurfaceProbeResult {
    var panelSurface: PanelSurface? = null
    return runCatching {
          panelSurface = PanelSurface(scene, 64, 64, 1, SamplerConfig(), true, false, "", false)
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "created-destroyed-no-render",
              surfaceValid = panelSurface?.surface?.isValid == true,
              error = "none",
          )
        }
        .getOrElse { throwable ->
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "unavailable",
              surfaceValid = false,
              error = throwable.javaClass.simpleName,
          )
        }
        .also {
          panelSurface?.destroy()
        }
  }

  private fun requestSpatialMultimodalInputIfReady(
      probe: SpatialNativeInteropProbe,
      phase: String,
  ) {
    if (spatialMultimodalInputRequested || !nativeReceiptLibraryLoaded) {
      return
    }
    val enabled = spatialMultimodalInputEnabled()
    if (!enabled) {
      spatialMultimodalInputRequested = true
      marker(SpatialOpenXrRouteModule.spatialMultimodalInputDisabledMarker(phase))
      return
    }
    if (
        !probe.openXrInstanceHandleNonZero ||
            !probe.openXrSessionHandleNonZero ||
            !probe.openXrGetInstanceProcAddrHandleNonZero
    ) {
      marker(SpatialOpenXrRouteModule.spatialMultimodalInputDeferredMarker(phase))
      return
    }
    val requestMask =
        runCatching {
              nativeRequestSpatialMultimodalInput(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialOpenXrRouteModule.spatialMultimodalInputErrorMarker(
                      phase,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                  )
              )
              0L
            }
    spatialMultimodalInputRequested = true
    spatialMultimodalInputRequestMask = requestMask
    marker(SpatialOpenXrRouteModule.spatialMultimodalInputResultMarker(phase, requestMask))
  }

  private fun runSdkQuadSurfaceProbeIfRequested(reason: String) {
    if (sdkQuadSurfaceProbeStarted) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeEnabled()) {
      return
    }
    sdkQuadSurfaceProbeStarted = true
    val holdMs = SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeHoldMs()
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeStartMarker(
            reason = reason,
            holdMs = holdMs,
        )
    )
    Handler(Looper.getMainLooper()).post { runSdkQuadSurfaceProbe(holdMs) }
  }

  private fun runSdkQuadVulkanProbeIfRequested(reason: String) {
    if (sdkQuadVulkanProbeStarted) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeEnabled()) {
      return
    }
    sdkQuadVulkanProbeStarted = true
    val holdMs = SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeHoldMs()
    val frameCount = SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeFrameCount()
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeStartMarker(
            reason = reason,
            holdMs = holdMs,
            frameCount = frameCount,
        )
    )
    Handler(Looper.getMainLooper()).post { runSdkQuadVulkanProbe(holdMs, frameCount) }
  }

  private fun runSdkQuadVulkanProbe(holdMs: Long, frameCount: Int) {
    cleanupSdkQuadSurfaceProbe("vulkan-pre-run")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
              sdkSwapchainCreated = false,
              surfaceValid = false,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              nativeVulkanProducer = false,
              firstFramePresented = "false",
              manualSceneQuadLayerViable = false,
              error = nativeReceiptLibraryError,
          )
      )
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  SDK_QUAD_SURFACE_PROBE_WIDTH_PX,
                  SDK_QUAD_SURFACE_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      sceneQuadLayerCreated = false,
                      nativeStartRequested = false,
                      nativeVulkanProducer = false,
                      firstFramePresented = "false",
                      manualSceneQuadLayerViable = false,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeGetSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-surface-invalid")
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              nativeVulkanProducer = false,
              firstFramePresented = "false",
              manualSceneQuadLayerViable = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated =
        createSdkQuadSurfaceProbeLayer(
            sdkSwapchain = sdkSwapchain,
            canvasDrawn = false,
            anchorMode = "generated-single-sided-quad",
        )
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeLayerCreatedMarker(
            layerCreated = layerCreated
        )
    )
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-layer-create-failed")
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              nativeVulkanProducer = false,
              firstFramePresented = "false",
              manualSceneQuadLayerViable = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val startMask =
        runCatching {
              nativeStartSdkQuadVulkanProbe(
                  renderSurface,
                  SDK_QUAD_SURFACE_PROBE_WIDTH_PX,
                  SDK_QUAD_SURFACE_PROBE_HEIGHT_PX,
                  frameCount,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-start-failed")
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = false,
                      nativeVulkanProducer = false,
                      firstFramePresented = "false",
                      manualSceneQuadLayerViable = true,
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeNativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            frameCount = frameCount,
            holdMs = holdMs,
        )
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (nativeReceiptLibraryLoaded) {
                runCatching { nativeStopSdkQuadVulkanProbe() }
              }
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-hold-complete")
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeHoldCompleteMarker(
                      surfaceValid = surfaceValid,
                      frameCount = frameCount,
                      cleanupStatus = cleanupStatus,
                  )
              )
            },
            holdMs,
        )
  }

  private fun runSdkQuadStereoAlphaProbeIfRequested(reason: String) {
    if (sdkQuadStereoAlphaProbeStarted) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeEnabled()) {
      return
    }
    sdkQuadStereoAlphaProbeStarted = true
    val holdMs = SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeHoldMs()
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeStartMarker(
            reason = reason,
            holdMs = holdMs,
        )
    )
    Handler(Looper.getMainLooper()).post { runSdkQuadStereoAlphaProbe(holdMs) }
  }

  private fun runPanelSurfaceMatrixProbeIfRequested(reason: String) {
    if (panelSurfaceMatrixProbeStarted) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeEnabled()) {
      return
    }
    panelSurfaceMatrixProbeStarted = true
    marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeStartMarker(reason)
    )
    Handler(Looper.getMainLooper()).post {
      runPanelSurfaceMatrixProbeVariant(
          variantIndex = 0,
          useSwapchain = true,
          useTexture = false,
      )
    }
  }

  private fun startNativeSpatialControllerActionsIfReady(
      probe: SpatialNativeInteropProbe,
      phase: String,
  ) {
    if (!nativeSpatialControllerActionsEnabled()) {
      marker(SpatialOpenXrRouteModule.nativeControllerActionsDisabledMarker(phase))
      return
    }
    if (nativeSpatialControllerActionsStarted || !nativeReceiptLibraryLoaded) {
      return
    }
    if (
        !probe.openXrInstanceHandleNonZero ||
            !probe.openXrSessionHandleNonZero ||
            !probe.openXrGetInstanceProcAddrHandleNonZero
    ) {
      marker(SpatialOpenXrRouteModule.nativeControllerActionsStartDeferredMarker(phase))
      return
    }
    val startMask =
        runCatching {
              nativeStartSpatialControllerActions(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialOpenXrRouteModule.nativeControllerActionsStartErrorMarker(
                      phase,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                  )
              )
              0L
            }
    nativeSpatialControllerActionsStartMask = startMask
    nativeSpatialControllerActionsStarted =
        SpatialOpenXrRouteModule.nativeSpatialControllerActionSetAttached(startMask)
    marker(
        SpatialOpenXrRouteModule.nativeControllerActionsStartResultMarker(
            phase,
            startMask,
            nativeSpatialControllerActionsStarted,
        )
    )
  }

  private fun runPanelSurfaceMatrixProbeVariant(
      variantIndex: Int,
      useSwapchain: Boolean,
      useTexture: Boolean,
  ) {
    cleanupSdkQuadSurfaceProbe("panel-surface-matrix-pre-variant-$variantIndex")
    if (nativeReceiptLibraryLoaded) {
      runCatching { nativeStopSdkQuadVulkanProbe() }
    }
    val variantName = "useSwapchain-$useSwapchain-useTexture-$useTexture"
    var panelSurface: PanelSurface? = null
    val created =
        runCatching {
              PanelSurface(
                  scene,
                  PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX,
                  PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX,
                  1,
                  SamplerConfig(),
                  useSwapchain,
                  useTexture,
                  "",
                  false,
              )
            }
            .onSuccess { panelSurface = it }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeVariantCreateFailedMarker(
                      variantName = variantName,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              scheduleNextPanelSurfaceMatrixVariant(variantIndex)
              return
            }
    val surface = runCatching { created.surface }.getOrNull()
    val swapchain = runCatching { created.swapchain }.getOrNull()
    val texture = runCatching { created.texture }.getOrNull()
    val surfaceValid = surface?.isValid == true
    val swapchainNonNull = swapchain != null
    val textureNonNull = texture != null
    marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeVariantCreatedMarker(
            variantName = variantName,
            surfaceValid = surfaceValid,
            swapchainNonNull = swapchainNonNull,
            textureNonNull = textureNonNull,
            widthPx = created.widthInPx,
            heightPx = created.heightInPx,
            mips = created.mips,
            reportedUseSwapchain = created.useSwapchain,
            reportedUseTexture = created.useTexture,
        )
    )

    val layerCreated =
        if (swapchain != null) {
          createSdkQuadSurfaceProbeLayer(
              sdkSwapchain = swapchain,
              canvasDrawn = false,
              anchorMode = "generated-single-sided-quad",
          )
        } else {
          false
        }
    marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeSceneQuadLayerAttemptedMarker(
            variantName = variantName,
            swapchainNonNull = swapchainNonNull,
            layerCreated = layerCreated,
        )
    )

    val nativeStartMask =
        if (surfaceValid && nativeReceiptLibraryLoaded) {
          runCatching {
                nativeStartSdkQuadVulkanProbe(
                    surface,
                    PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX,
                    PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX,
                    PANEL_SURFACE_MATRIX_PROBE_FRAME_COUNT,
                )
              }
              .getOrElse { throwable ->
                marker(
                    SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeNativeStartFailedMarker(
                        variantName = variantName,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
                0L
              }
        } else {
          0L
        }
    val nativeStartRequested = nativeStartMask != 0L
    marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeNativeStartAttemptedMarker(
            variantName = variantName,
            surfaceValid = surfaceValid,
            nativeReceiptLibraryLoaded = nativeReceiptLibraryLoaded,
            nativeStartRequested = nativeStartRequested,
            nativeStartMask = nativeStartMask,
        )
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (nativeReceiptLibraryLoaded) {
                runCatching { nativeStopSdkQuadVulkanProbe() }
              }
              val sceneCleanupStatus = cleanupSdkQuadSurfaceProbe("panel-surface-matrix-variant-$variantIndex")
              val panelSurfaceDestroyed =
                  runCatching {
                        panelSurface?.destroy()
                        true
                      }
                      .getOrDefault(false)
              marker(
                  SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeVariantCompleteMarker(
                      variantName = variantName,
                      surfaceValid = surfaceValid,
                      swapchainNonNull = swapchainNonNull,
                      textureNonNull = textureNonNull,
                      layerCreated = layerCreated,
                      nativeStartRequested = nativeStartRequested,
                      nativeStartMask = nativeStartMask,
                      sceneCleanupStatus = sceneCleanupStatus,
                      panelSurfaceDestroyed = panelSurfaceDestroyed,
                  )
              )
              scheduleNextPanelSurfaceMatrixVariant(variantIndex)
            },
            PANEL_SURFACE_MATRIX_PROBE_VARIANT_HOLD_MS,
        )
  }

  private fun scheduleNextPanelSurfaceMatrixVariant(variantIndex: Int) {
    if (variantIndex == 0) {
      Handler(Looper.getMainLooper())
          .postDelayed(
              {
                runPanelSurfaceMatrixProbeVariant(
                    variantIndex = 1,
                    useSwapchain = false,
                    useTexture = true,
                )
              },
              PANEL_SURFACE_MATRIX_PROBE_INTER_VARIANT_MS,
          )
      return
    }
    marker(SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeCompleteMarker())
  }

  private fun runCameraHwbProbeIfRequested(reason: String) {
    if (cameraHwbProbeStarted) {
      return
    }
    if (activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.cameraHwbProbeEnabled()) {
      return
    }
    cameraHwbProbeStarted = true
    val holdMs = SpatialDiagnosticProbeRouteModule.cameraHwbProbeHoldMs()
    val frameCount = SpatialDiagnosticProbeRouteModule.cameraHwbProbeFrameCount()
    val readerMaxImages = SpatialDiagnosticProbeRouteModule.cameraHwbProbeReaderMaxImages()
    marker(
        SpatialDiagnosticProbeRouteModule.cameraHwbProbeStartMarker(
            reason = reason,
            frameCount = frameCount,
            holdMs = holdMs,
            readerMaxImages = readerMaxImages,
            publicMultiStackMarkerFields = SpatialPublicMultiStack.inactiveMarkerFields(),
        )
    )
    Handler(Looper.getMainLooper()).post { runCameraHwbProbe(holdMs, frameCount, readerMaxImages) }
  }

  private fun runCameraHwbProbe(holdMs: Long, frameCount: Int, readerMaxImages: Int) {
    cleanupSdkQuadSurfaceProbe("camera-hwb-pre-run")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
              sdkSwapchainCreated = false,
              surfaceValid = false,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              sampledCameraTexture = "false",
              error = nativeReceiptLibraryError,
          )
      )
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  CAMERA_HWB_PROBE_WIDTH_PX,
                  CAMERA_HWB_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      sceneQuadLayerCreated = false,
                      nativeStartRequested = false,
                      sampledCameraTexture = "false",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeGetSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        SpatialDiagnosticProbeRouteModule.cameraHwbProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-surface-invalid")
      marker(
          SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              sampledCameraTexture = "false",
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated = createCameraHwbProbeLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-layer-create-failed")
      marker(
          SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              sampledCameraTexture = "false",
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val startMask =
        runCatching {
              nativeStartCameraHwbProbe(
                  renderSurface,
                  CAMERA_HWB_PROBE_WIDTH_PX,
                  CAMERA_HWB_PROBE_HEIGHT_PX,
                  frameCount,
                  readerMaxImages,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-start-failed")
              marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = false,
                      sampledCameraTexture = "false",
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    marker(
        SpatialDiagnosticProbeRouteModule.cameraHwbProbeNativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            frameCount = frameCount,
            readerMaxImages = readerMaxImages,
            holdMs = holdMs,
            publicMultiStackMarkerFields = SpatialPublicMultiStack.inactiveMarkerFields(),
        )
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (nativeReceiptLibraryLoaded) {
                runCatching { nativeStopCameraHwbProbe() }
              }
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-hold-complete")
              marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = true,
                      sampledCameraTexture = "see-native-logcat",
                      cleanupStatus = cleanupStatus,
                      firstCameraFramePresented = "see-native-logcat",
                  )
              )
            },
            holdMs,
        )
  }

  private fun runSpatialVideoProjectionProbeIfRequested(reason: String) {
    if (spatialVideoProjectionProbeStarted) {
      return
    }
    if (activityReadOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) != true) {
      return
    }
    if (!spatialSceneReady) {
      marker(SpatialVideoProjectionRouteModule.startDeferredForSceneMarker(reason))
      return
    }
    if (spatialVirtualRoomEnabled() && !spatialVirtualRoomLoaded()) {
      marker(
          SpatialVideoProjectionRouteModule.startDeferredForVirtualRoomMarker(
              reason,
              spatialSceneReady,
          )
      )
      return
    }
    spatialVideoProjectionProbeStarted = true
    val videoSettings = currentSpatialVideoProjectionSettings(intent)
    marker(
        SpatialVideoProjectionRouteModule.startMarker(
            reason = reason,
            widthPx = CAMERA_HWB_PROJECTION_WIDTH_PX,
            heightPx = CAMERA_HWB_PROJECTION_HEIGHT_PX,
            projectionMarkerFields = cameraHwbProjectionMarkerFields(),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            settings = videoSettings,
        )
    )
    Handler(Looper.getMainLooper()).post { runSpatialVideoProjectionProbe(videoSettings) }
  }

  private fun runSpatialVideoProjectionProbe(videoSettings: SpatialVideoProjectionSettings) {
    cleanupSdkQuadSurfaceProbe("spatial-video-projection-pre-run")
    spatialVideoProjectionSettings = videoSettings
    cameraHwbProjectionEntity = null
    cameraHwbProjectionStereoHorizontalOffsetUv =
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
    cameraHwbProjectionMarkerCount = 0
    lastCameraHwbProjectionMarkerMs = 0L
    suppressParticleLayerForCameraStack("spatial-video-projection-probe")
    setWorkflowPanelVisible(false, focus = false, source = "spatial-video-projection-probe")
    if (!videoSettings.active) {
      configureNativeSpatialVideoProjection(videoSettings, "video-only-inactive")
      marker(SpatialVideoProjectionRouteModule.inactiveCompleteMarker(videoSettings))
      return
    }
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialVideoProjectionRouteModule.nativeReceiptUnavailableCompleteMarker(
              nativeReceiptLibraryError
          )
      )
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialVideoProjectionRouteModule.sdkSwapchainCreateFailedCompleteMarker(
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  SpatialVideoProjectionRouteModule.getSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        SpatialVideoProjectionRouteModule.sdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
            widthPx = CAMERA_HWB_PROJECTION_WIDTH_PX,
            heightPx = CAMERA_HWB_PROJECTION_HEIGHT_PX,
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            settings = videoSettings,
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("spatial-video-projection-surface-invalid")
      marker(
          SpatialVideoProjectionRouteModule.completeMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated = createCameraHwbProjectionLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("spatial-video-projection-layer-create-failed")
      marker(
          SpatialVideoProjectionRouteModule.completeMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    configureNativeSpatialVideoProjection(videoSettings, "video-only-start")
    startSpatialVideoProjection(videoSettings, "video-only-start")
    val startMask =
        runCatching {
              nativeStartSpatialVideoProjectionProbe(
                  renderSurface,
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  SPATIAL_VIDEO_PROJECTION_FRAME_COUNT_UNBOUNDED,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("spatial-video-projection-start-failed")
              marker(
                  SpatialVideoProjectionRouteModule.completeMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = false,
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    marker(
        SpatialVideoProjectionRouteModule.nativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            settings = videoSettings,
        )
    )
    updateCameraHwbProjectionFromViewer(reason = "video-only-start", forceLog = true)
  }

  private fun runCameraHwbProjectionProbeIfRequested(reason: String) {
    if (cameraHwbProjectionProbeStarted) {
      return
    }
    if (activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) != true) {
      return
    }
    if (!spatialSceneReady) {
      marker(CameraHwbProjectionModule.rawProjectionStartDeferredForSceneMarker(reason))
      return
    }
    if (spatialVirtualRoomEnabled() && !spatialVirtualRoomLoaded()) {
      marker(
          CameraHwbProjectionModule.rawProjectionStartDeferredForVirtualRoomMarker(
              reason,
              spatialSceneReady,
          )
      )
      return
    }
    cameraHwbProjectionProbeStarted = true
    val readerMaxImages =
        activityReadIntSystemProperty(
            CAMERA_HWB_PROJECTION_READER_MAX_IMAGES_PROPERTY,
            CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES,
            CAMERA_HWB_PROJECTION_MIN_READER_MAX_IMAGES,
            CAMERA_HWB_PROJECTION_MAX_READER_MAX_IMAGES,
        )
    val videoSettings = currentSpatialVideoProjectionSettings(intent)
    cameraHwbProjectionCarrierMode = currentCameraHwbProjectionCarrierMode()
    val carrier = cameraHwbProjectionCarrierToken()
    marker(
        CameraHwbProjectionModule.rawProjectionStartMarker(
            reason = reason,
            startGateToken = cameraHwbProjectionStartGateToken(),
            readerMaxImages = readerMaxImages,
            carrier = carrier,
            projectionMarkerFields = cameraHwbProjectionMarkerFields(),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            videoProjectionMarkerFields = spatialVideoProjectionMarkerFields(videoSettings),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
        )
    )
    Handler(Looper.getMainLooper()).post { runCameraHwbProjectionProbe(readerMaxImages, videoSettings) }
  }

  private fun runCameraHwbProjectionProbe(
      readerMaxImages: Int,
      videoSettings: SpatialVideoProjectionSettings,
  ) {
    cleanupSdkQuadSurfaceProbe("camera-hwb-projection-pre-run")
    cleanupCameraHwbProjectionPanelCarrier("camera-hwb-projection-pre-run")
    spatialVideoProjectionSettings = videoSettings
    cameraHwbProjectionEntity = null
    cameraHwbProjectionCarrierMode = currentCameraHwbProjectionCarrierMode()
    cameraHwbProjectionReaderMaxImages = readerMaxImages
    cameraHwbProjectionTargetScale = initialCameraHwbProjectionTargetScale()
    cameraHwbProjectionStereoHorizontalOffsetUv =
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
    privateLayerDepthLayerPolicy = initialPrivateLayerDepthLayerPolicy()
    cameraHwbProjectionMarkerCount = 0
    lastCameraHwbProjectionMarkerMs = 0L
    lastCameraHwbProjectionScaleJoystickMs = 0L
    lastCameraHwbProjectionScaleJoystickMarkerMs = 0L
    cameraHwbProjectionSecondaryToggleArmed = false
    cameraHwbProjectionSyntheticVisualPresented = false
    suppressParticleLayerForCameraStack("camera-hwb-projection-probe")
    privateLayerPanelVisible = false
    setWorkflowPanelVisible(false, focus = false, source = "camera-hwb-projection-probe")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          CameraHwbProjectionModule.rawProjectionCompleteBeforeSwapchainMarker(
              nativeReceiptLibraryError
          )
      )
      return
    }
    if (cameraHwbProjectionScenePanelCarrierEnabled()) {
      runCameraHwbProjectionPanelCarrier(readerMaxImages, videoSettings)
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              marker(
                  CameraHwbProjectionModule.rawProjectionCompleteBeforeSwapchainMarker(
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  CameraHwbProjectionModule.rawProjectionGetSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        CameraHwbProjectionModule.rawProjectionSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
            carrier = cameraHwbProjectionCarrierToken(),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-projection-surface-invalid")
      marker(
          CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated = createCameraHwbProjectionLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-projection-layer-create-failed")
      marker(
          CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    if (cameraHwbProjectionSyntheticVisualProbeEnabled()) {
      val canvasDrawn =
          drawCameraHwbProjectionSyntheticVisual(
              renderSurface,
              "SceneQuadLayer",
      )
      cameraHwbProjectionSyntheticVisualPresented = canvasDrawn
      marker(
          CameraHwbProjectionModule.rawProjectionSyntheticVisualPresentedMarker(
              surfaceValid = surfaceValid,
              canvasDrawn = canvasDrawn,
              carrier = cameraHwbProjectionCarrierToken(),
          )
      )
      updateCameraHwbProjectionFromViewer(reason = "synthetic-visual-start", forceLog = true)
      return
    }

    val nativePassthroughStartMask =
        startSpatialNativePassthroughForDepthPrerequisite("raw-projection-start")
    val nativePassthroughLayerActive =
        SpatialOpenXrRouteModule.nativePassthroughLayerActive(nativePassthroughStartMask)
    val nativeEnvironmentDepthStartMask =
        startSpatialEnvironmentDepthProbe("raw-projection-start")
    val nativeEnvironmentDepthProviderBound =
        SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
            nativeEnvironmentDepthStartMask
        )
    updateNativeCameraHwbProjectionStereoOffset(
        reason = "raw-projection-start",
        forceLog = true,
    )
    updateNativeCameraHwbProjectionTargetScale(
        reason = "raw-projection-start",
        forceLog = true,
    )
    updatePrivateLayerOverrideFromPanel(
        privateLayerOverride,
        source = "raw-projection-start",
    )
    updatePrivateLayerDepthLayerPolicyFromPanel(
        privateLayerDepthLayerPolicy,
        source = "raw-projection-start",
    )
    updatePrivateLayerDepthAlignmentFromPanel(
        privateLayerDepthAlignment,
        source = "raw-projection-start",
    )
    configureNativeSpatialVideoProjection(videoSettings, "raw-projection-start")
    if (videoSettings.active) {
      startSpatialVideoProjection(videoSettings, "raw-projection-start")
    }
    val startMask =
        runCatching {
              nativeStartCameraHwbProjectionProbe(
                  renderSurface,
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED,
                  readerMaxImages,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-projection-start-failed")
              marker(
                  CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    marker(
        CameraHwbProjectionModule.rawProjectionNativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            readerMaxImages = readerMaxImages,
            carrier = cameraHwbProjectionCarrierToken(),
            projectionMarkerFields = cameraHwbProjectionMarkerFields(),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            videoProjectionMarkerFields = spatialVideoProjectionMarkerFields(videoSettings),
            publicMultiStackMarkerFields =
                SpatialPublicMultiStack.markerFields(
                    nativePassthroughLayerActive = nativePassthroughLayerActive,
                    nativeEnvironmentDepthProviderRequested = true,
                    nativeEnvironmentDepthProviderBound = nativeEnvironmentDepthProviderBound,
                ),
            nativePassthroughStartMask = nativePassthroughStartMask,
            nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
        )
    )
    updateCameraHwbProjectionFromViewer(reason = "raw-projection-start", forceLog = true)
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun runCameraHwbProjectionPanelCarrier(
      readerMaxImages: Int,
      videoSettings: SpatialVideoProjectionSettings,
  ) {
    val plane = cameraHwbProjectionPlaneForPlacement()
    cameraHwbProjectionPanelNativeStarted = false
    cameraHwbProjectionPanelStartMask = 0L
    cameraHwbProjectionPanelSurfaceConsumerCalled = false
    cameraHwbProjectionPanelReady = false
    cameraHwbProjectionPanelSurface = null
    cameraHwbProjectionSyntheticVisualPresented = false
    cameraHwbProjectionPanelSceneObject = null
    cameraHwbProjectionReaderMaxImages = readerMaxImages
    if (cameraHwbProjectionManualCustomMeshCarrierEnabled()) {
      cameraHwbProjectionEntity =
          createManualCameraHwbProjectionCustomMeshPanel(plane, videoSettings)
      cameraHwbProjectionPanelEntity = cameraHwbProjectionEntity
      if (cameraHwbProjectionPanelEntity != null) {
        startCameraHwbProjectionPanelCarrierIfReady("manual-custom-mesh-created")
      }
      return
    }
    cameraHwbProjectionEntity =
        when (
            val result =
                CameraHwbProjectionPanelCarrierModule.createVideoSurfacePanelEntity(
                    plane = plane,
                    carrier = cameraHwbProjectionCarrierToken(),
                )
        ) {
          is CameraHwbProjectionPanelEntityCreateResult.Ready -> result.entity
          is CameraHwbProjectionPanelEntityCreateResult.Failed -> {
            marker(result.marker)
            null
          }
        }
    cameraHwbProjectionPanelEntity = cameraHwbProjectionEntity
    val entityCreated = cameraHwbProjectionPanelEntity != null
    marker(
        CameraHwbProjectionPanelCarrierModule.scenePanelCarrierEntitySpawnedMarker(
            entityCreated = entityCreated,
            carrier = cameraHwbProjectionCarrierToken(),
            plane = plane,
            projectionMarkerFields = cameraHwbProjectionMarkerFields(plane),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            videoProjectionMarkerFields = spatialVideoProjectionMarkerFields(videoSettings),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
        )
    )
    if (entityCreated) {
      startCameraHwbProjectionPanelCarrierIfReady("entity-spawned")
    }
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun createManualCameraHwbProjectionCustomMeshPanel(
      plane: CameraHwbProjectionPlane,
      videoSettings: SpatialVideoProjectionSettings,
  ): Entity? {
    val carrierResult =
        CameraHwbProjectionPanelCarrierModule.createManualCustomMeshPanel(
            scene = scene,
            sceneObjectSystem = systemManager.findSystem<SceneObjectSystem>(),
            plane = plane,
            carrier = cameraHwbProjectionCarrierToken(),
        )
    val readyCarrier =
        when (carrierResult) {
          is CameraHwbProjectionManualPanelCarrierResult.Ready -> carrierResult
          is CameraHwbProjectionManualPanelCarrierResult.Failed -> {
            marker(carrierResult.marker)
            return null
          }
        }
    cameraHwbProjectionPanelSceneObject = readyCarrier.panelSceneObject
    cameraHwbProjectionPanelReady = true
    cameraHwbProjectionPanelSurface = readyCarrier.surface
    cameraHwbProjectionPanelSurfaceConsumerCalled = true
    val panelLayerUpdateStatus =
        updateCameraHwbProjectionPanelCarrierLayer(plane, "manual-custom-mesh-created")
    marker(
        CameraHwbProjectionPanelCarrierModule.manualPanelCarrierReadyMarker(
            surfaceValid = readyCarrier.surface.isValid,
            panelLayerUpdateStatus = panelLayerUpdateStatus,
            carrier = cameraHwbProjectionCarrierToken(),
            projectionMarkerFields = cameraHwbProjectionMarkerFields(plane),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            videoProjectionMarkerFields = spatialVideoProjectionMarkerFields(videoSettings),
        )
    )
    return readyCarrier.entity
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun startCameraHwbProjectionPanelCarrierIfReady(reason: String) {
    if (!cameraHwbProjectionScenePanelCarrierEnabled()) {
      return
    }
    if (cameraHwbProjectionPanelNativeStarted) {
      marker(
          CameraHwbProjectionModule.panelCarrierStartSkippedMarker(
              reason = reason,
              startMask = cameraHwbProjectionPanelStartMask,
              carrier = cameraHwbProjectionCarrierToken(),
          )
      )
      return
    }
    if (
        cameraHwbProjectionSyntheticVisualProbeEnabled() &&
            cameraHwbProjectionSyntheticVisualPresented
    ) {
      marker(
          CameraHwbProjectionModule.panelCarrierSyntheticVisualStartSkippedMarker(
              reason = reason,
              carrier = cameraHwbProjectionCarrierToken(),
          )
      )
      return
    }
    val entity = cameraHwbProjectionPanelEntity
    val surface = cameraHwbProjectionPanelSurface
    if (entity == null || !cameraHwbProjectionPanelReady || surface?.isValid != true) {
      marker(
          CameraHwbProjectionModule.panelCarrierStartDeferredMarker(
              reason = reason,
              entityPresent = entity != null,
              panelReady = cameraHwbProjectionPanelReady,
              surfacePresent = surface != null,
              surfaceValid = surface?.isValid == true,
              surfaceConsumerCalled = cameraHwbProjectionPanelSurfaceConsumerCalled,
              carrier = cameraHwbProjectionCarrierToken(),
          )
      )
      return
    }
    if (!nativeReceiptLibraryLoaded) {
      marker(
          CameraHwbProjectionModule.panelCarrierStartFailedMarker(
              reason = reason,
              carrier = cameraHwbProjectionCarrierToken(),
              error = nativeReceiptLibraryError,
          )
      )
      return
    }

    val plane = cameraHwbProjectionPlaneForPlacement()
    entity.setComponent(Transform(plane.pose))
    entity.setComponent(PanelDimensions(Vector2(plane.projectionWidthMeters, plane.projectionHeightMeters)))
    if (!cameraHwbProjectionManualCustomMeshCarrierEnabled()) {
      entity.setComponent(Hittable(MeshCollision.NoCollision))
    }
    entity.setComponent(Visible(true))
    val panelLayerUpdateStatus = updateCameraHwbProjectionPanelCarrierLayer(plane, reason)
    if (cameraHwbProjectionSyntheticVisualProbeEnabled()) {
      val canvasDrawn =
          drawCameraHwbProjectionSyntheticVisual(
              surface,
              if (cameraHwbProjectionManualCustomMeshCarrierEnabled()) {
                "ManualPanelSceneObjectCustomMesh"
              } else {
                "VideoSurfacePanel"
              },
          )
      cameraHwbProjectionSyntheticVisualPresented = canvasDrawn
      marker(
          CameraHwbProjectionModule.panelCarrierSyntheticVisualPresentedMarker(
              surfaceValid = surface.isValid,
              canvasDrawn = canvasDrawn,
              panelRegistrationId = cameraHwbProjectionPanelRegistrationId(),
              carrier = cameraHwbProjectionCarrierToken(),
              panelLayerUpdateStatus = panelLayerUpdateStatus,
          )
      )
      updateCameraHwbProjectionFromViewer(
          reason = "synthetic-visual-panel-carrier-start",
          forceLog = true,
      )
      return
    }
    val nativePassthroughStartMask =
        startSpatialNativePassthroughForDepthPrerequisite("raw-projection-panel-carrier-start")
    val nativePassthroughLayerActive =
        SpatialOpenXrRouteModule.nativePassthroughLayerActive(nativePassthroughStartMask)
    val nativeEnvironmentDepthStartMask =
        startSpatialEnvironmentDepthProbe("raw-projection-panel-carrier-start")
    val nativeEnvironmentDepthProviderBound =
        SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
            nativeEnvironmentDepthStartMask
        )
    updateNativeCameraHwbProjectionStereoOffset(
        reason = "raw-projection-panel-carrier-start",
        forceLog = true,
    )
    updateNativeCameraHwbProjectionTargetScale(
        reason = "raw-projection-panel-carrier-start",
        forceLog = true,
    )
    updatePrivateLayerOverrideFromPanel(
        privateLayerOverride,
        source = "raw-projection-panel-carrier-start",
    )
    updatePrivateLayerDepthLayerPolicyFromPanel(
        privateLayerDepthLayerPolicy,
        source = "raw-projection-panel-carrier-start",
    )
    updatePrivateLayerDepthAlignmentFromPanel(
        privateLayerDepthAlignment,
        source = "raw-projection-panel-carrier-start",
    )
    configureNativeSpatialVideoProjection(
        spatialVideoProjectionSettings,
        "raw-projection-panel-carrier-start",
    )
    if (spatialVideoProjectionSettings.active) {
      startSpatialVideoProjection(
          spatialVideoProjectionSettings,
          "raw-projection-panel-carrier-start",
      )
    }
    val startMask =
        runCatching {
              nativeStartCameraHwbProjectionProbe(
                  surface,
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED,
                  cameraHwbProjectionReaderMaxImages,
              )
            }
            .getOrElse { throwable ->
              marker(
                  CameraHwbProjectionModule.panelCarrierStartFailedMarker(
                      reason = reason,
                      carrier = cameraHwbProjectionCarrierToken(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                      panelLayerUpdateStatus = panelLayerUpdateStatus,
                  )
              )
              return
            }
    cameraHwbProjectionPanelNativeStarted = true
    cameraHwbProjectionPanelStartMask = startMask
    marker(
        CameraHwbProjectionModule.panelCarrierNativeStartRequestedMarker(
            surfaceValid = surface.isValid,
            startMask = startMask,
            readerMaxImages = cameraHwbProjectionReaderMaxImages,
            panelRegistrationId = cameraHwbProjectionPanelRegistrationId(),
            carrier = cameraHwbProjectionCarrierToken(),
            panelLayerUpdateStatus = panelLayerUpdateStatus,
            projectionMarkerFields = cameraHwbProjectionMarkerFields(plane),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            videoProjectionMarkerFields = spatialVideoProjectionMarkerFields(spatialVideoProjectionSettings),
            publicMultiStackMarkerFields =
                SpatialPublicMultiStack.markerFields(
                    nativePassthroughLayerActive = nativePassthroughLayerActive,
                    nativeEnvironmentDepthProviderRequested = true,
                    nativeEnvironmentDepthProviderBound = nativeEnvironmentDepthProviderBound,
                ),
            nativePassthroughStartMask = nativePassthroughStartMask,
            nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
        )
    )
    updateCameraHwbProjectionFromViewer(
        reason = "raw-projection-panel-carrier-start",
        forceLog = true,
    )
  }

  private fun runSdkQuadStereoAlphaProbe(holdMs: Long) {
    cleanupSdkQuadSurfaceProbe("stereo-alpha-pre-run")
    sdkQuadStereoAlphaProbeZIndexChanged = false
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX,
                  SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      canvasDrawn = false,
                      sceneQuadLayerCreated = false,
                      setClipApplied = false,
                      alphaBlendApplied = false,
                      zIndexChanged = false,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeGetSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("stereo-alpha-surface-invalid")
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              canvasDrawn = false,
              sceneQuadLayerCreated = false,
              setClipApplied = false,
              alphaBlendApplied = false,
              zIndexChanged = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val canvasDrawn = drawSdkQuadStereoAlphaPattern(surface)
    val layerCreated =
        createSdkQuadStereoAlphaProbeLayer(
            sdkSwapchain = sdkSwapchain,
            canvasDrawn = canvasDrawn,
        )
    val viable = surfaceValid && canvasDrawn && layerCreated
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeVisibleWindowMarker(
            surfaceValid = surfaceValid,
            canvasDrawn = canvasDrawn,
            sceneQuadLayerCreated = layerCreated,
            manualSceneQuadLayerViable = viable,
            holdMs = holdMs,
        )
    )
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("stereo-alpha-layer-create-failed")
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              canvasDrawn = canvasDrawn,
              sceneQuadLayerCreated = false,
              setClipApplied = false,
              alphaBlendApplied = false,
              zIndexChanged = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              sdkQuadSurfaceProbeLayer?.let { layer ->
                runCatching {
                      layer.setZIndex(SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH)
                    }
                    .onSuccess {
                      sdkQuadStereoAlphaProbeZIndexChanged = true
                      marker(SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeZIndexUpdatedMarker())
                    }
                    .onFailure { throwable ->
                      marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeZIndexUpdateFailedMarker(
                              error = throwable.javaClass.simpleName,
                              message = throwable.message ?: "none",
                          )
                      )
                    }
              }
            },
            SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_CHANGE_MS,
        )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              sdkQuadSurfaceProbeLayer?.let { layer ->
                runCatching {
                      layer.setColorScaleBias(
                          Vector4(1.0f, 1.0f, 1.0f, SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW),
                          Vector4(0.0f),
                      )
                    }
                    .onSuccess {
                      marker(SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaUpdatedMarker())
                    }
                    .onFailure { throwable ->
                      marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaUpdateFailedMarker(
                              error = throwable.javaClass.simpleName
                          )
                      )
                    }
              }
            },
            SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_CHANGE_MS,
        )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              sdkQuadSurfaceProbeLayer?.let { layer ->
                runCatching {
                      layer.setColorScaleBias(Vector4(1.0f), Vector4(0.0f))
                    }
                    .onSuccess {
                      marker(SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaRestoredMarker())
                    }
                    .onFailure { throwable ->
                      marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaRestoreFailedMarker(
                              error = throwable.javaClass.simpleName
                          )
                      )
                    }
              }
            },
            SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_RESTORE_MS,
        )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("stereo-alpha-hold-complete")
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      canvasDrawn = canvasDrawn,
                      sceneQuadLayerCreated = layerCreated,
                      setClipApplied = true,
                      alphaBlendApplied = true,
                      zIndexChanged = sdkQuadStereoAlphaProbeZIndexChanged,
                      manualSceneQuadLayerViable = viable,
                      colorScaleAlphaApplied = true,
                      cleanupStatus = cleanupStatus,
                      includeOperatorChecks = true,
                  )
              )
            },
            holdMs,
        )
  }

  private fun createSdkQuadStereoAlphaProbeLayer(
      sdkSwapchain: SceneSwapchain,
      canvasDrawn: Boolean,
  ): Boolean =
      runCatching {
            val pose = sdkQuadSurfaceProbePoseFromViewer()
            val entity = Entity.create(Transform(pose), Scale(Vector3(1.0f, 1.0f, 1.0f)), Visible(true))
            val material = SceneMaterial.passthrough()
            val mesh =
                SceneMesh.singleSidedQuad(
                    SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS,
                    SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS,
                    material,
                )
            sdkQuadSurfaceProbeAnchorMaterial = material
            sdkQuadSurfaceProbeAnchorMesh = mesh
            val sceneObject = SceneObject(scene, mesh, "sdk_quad_stereo_alpha_probe_anchor", entity)
            scene.addObject(sceneObject)
            sdkQuadSurfaceProbeSceneObject = sceneObject
            val layer =
                SceneQuadLayer(
                    scene,
                    sdkSwapchain,
                    SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS,
                    SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS,
                    0.5f,
                    0.5f,
                    StereoMode.LeftRight,
                    sceneObject,
                )
            layer.setZIndex(SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW)
            layer.setClip(
                Vector2(0.04f, 0.04f),
                Vector2(0.96f, 0.04f),
                Vector2(0.96f, 0.96f),
                Vector2(0.04f, 0.96f),
            )
            layer.setAlphaBlend(
                LayerAlphaBlend(
                    BlendFactor.SOURCE_ALPHA,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                    BlendFactor.ONE,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                )
            )
            layer.setColorScaleBias(
                Vector4(1.0f, 1.0f, 1.0f, SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH),
                Vector4(0.0f),
            )
            sdkQuadSurfaceProbeLayer = layer
            marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeLayerCreatedMarker(
                    canvasDrawn = canvasDrawn,
                    sceneObjectHandle = sceneObject.handle,
                    layerPositionM = activityVectorMarker(pose.t),
                    layerQuaternion = activityQuaternionMarker(pose.q),
                )
            )
            true
          }
          .getOrElse { throwable ->
            marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeLayerCreateFailedMarker(
                    canvasDrawn = canvasDrawn,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  private fun drawSdkQuadStereoAlphaPattern(surface: AndroidSurface): Boolean {
    if (!surface.isValid) {
      marker(SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCanvasDrawSkippedMarker())
      return false
    }
    var canvas: android.graphics.Canvas? = null
    return runCatching {
          canvas = surface.lockCanvas(null)
          val lockedCanvas = canvas ?: return@runCatching false
          lockedCanvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
          val paint = AndroidPaint().apply { isAntiAlias = true }
          val left = android.graphics.RectF(0f, 0f, lockedCanvas.width / 2f, lockedCanvas.height.toFloat())
          val right =
              android.graphics.RectF(
                  lockedCanvas.width / 2f,
                  0f,
                  lockedCanvas.width.toFloat(),
                  lockedCanvas.height.toFloat(),
              )
          paint.style = AndroidPaint.Style.FILL
          paint.color = AndroidColor.argb(230, 220, 24, 24)
          lockedCanvas.drawRect(left, paint)
          paint.color = AndroidColor.argb(230, 30, 90, 235)
          lockedCanvas.drawRect(right, paint)

          drawStereoGrid(
              lockedCanvas,
              paint,
              left.left,
              left.top,
              left.width(),
              left.height(),
              AndroidColor.WHITE,
              AndroidColor.YELLOW,
              "LEFT RED",
          )
          drawStereoGrid(
              lockedCanvas,
              paint,
              right.left,
              right.top,
              right.width(),
              right.height(),
              AndroidColor.WHITE,
              AndroidColor.CYAN,
              "RIGHT BLUE",
          )
          true
        }
        .onSuccess { drawn ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCanvasDrawCompleteMarker(
                  drawn = drawn
              )
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked ->
            runCatching { surface.unlockCanvasAndPost(locked) }
          }
          marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCanvasDrawFailedMarker(
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
        .getOrDefault(false)
  }

  private fun drawStereoGrid(
      canvas: android.graphics.Canvas,
      paint: AndroidPaint,
      x: Float,
      y: Float,
      width: Float,
      height: Float,
      gridColor: Int,
      accentColor: Int,
      label: String,
  ) {
    val cells = 8
    paint.style = AndroidPaint.Style.STROKE
    paint.strokeWidth = 3.0f
    paint.color = gridColor
    for (index in 0..cells) {
      val px = x + width * index / cells
      val py = y + height * index / cells
      canvas.drawLine(px, y, px, y + height, paint)
      canvas.drawLine(x, py, x + width, py, paint)
    }
    paint.strokeWidth = 8.0f
    paint.color = accentColor
    canvas.drawRect(x + 36f, y + 36f, x + width - 36f, y + height - 36f, paint)
    canvas.drawLine(x + width * 0.20f, y + height * 0.50f, x + width * 0.80f, y + height * 0.50f, paint)
    canvas.drawLine(x + width * 0.80f, y + height * 0.50f, x + width * 0.68f, y + height * 0.38f, paint)
    canvas.drawLine(x + width * 0.80f, y + height * 0.50f, x + width * 0.68f, y + height * 0.62f, paint)
    paint.style = AndroidPaint.Style.FILL
    paint.textSize = height * 0.075f
    paint.color = AndroidColor.WHITE
    canvas.drawText(label, x + width * 0.24f, y + height * 0.22f, paint)
    paint.textSize = height * 0.045f
    canvas.drawText("UV 0,0 -> top-left", x + width * 0.08f, y + height * 0.91f, paint)
  }

  private fun runSdkQuadSurfaceProbe(holdMs: Long) {
    cleanupSdkQuadSurfaceProbe("pre-run")
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  SDK_QUAD_SURFACE_PROBE_WIDTH_PX,
                  SDK_QUAD_SURFACE_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      canvasDrawn = false,
                      sceneQuadLayerCreated = false,
                      manualSceneQuadLayerViable = false,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeGetSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )

    val canvasDrawn = surface?.let { drawSdkQuadSurfaceCheckerboard(it) } ?: false
    val plainEntityLayerCreated =
        createSdkQuadSurfaceProbeLayer(
            sdkSwapchain = sdkSwapchain,
            canvasDrawn = canvasDrawn,
            anchorMode = "plain-entity",
        )
    val layerCreated =
        if (plainEntityLayerCreated) {
          true
        } else {
          cleanupSdkQuadSurfaceProbeSceneOnly("plain-entity-retry")
          createSdkQuadSurfaceProbeLayer(
              sdkSwapchain = sdkSwapchain,
              canvasDrawn = canvasDrawn,
              anchorMode = "generated-single-sided-quad",
          )
        }
    val anchorMode =
        if (plainEntityLayerCreated) {
          "plain-entity"
        } else if (layerCreated) {
          "generated-single-sided-quad"
        } else {
          "none"
        }

    val viable = surfaceValid && canvasDrawn && layerCreated
    marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeVisibleWindowMarker(
            surfaceValid = surfaceValid,
            canvasDrawn = canvasDrawn,
            sceneQuadLayerCreated = layerCreated,
            manualSceneQuadLayerViable = viable,
            plainEntitySceneObjectLayerCreated = plainEntityLayerCreated,
            anchorMode = anchorMode,
            holdMs = holdMs,
        )
    )
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("layer-create-failed")
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              canvasDrawn = canvasDrawn,
              sceneQuadLayerCreated = false,
              manualSceneQuadLayerViable = false,
              cleanupStatus = cleanupStatus,
              plainEntitySceneObjectLayerCreated = plainEntityLayerCreated,
              anchorMode = anchorMode,
              nativeVulkanProducer = false,
              visiblePatternConfirmed = false,
          )
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("hold-complete")
              marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      canvasDrawn = canvasDrawn,
                      sceneQuadLayerCreated = layerCreated,
                      manualSceneQuadLayerViable = viable,
                      cleanupStatus = cleanupStatus,
                      plainEntitySceneObjectLayerCreated = plainEntityLayerCreated,
                      anchorMode = anchorMode,
                      nativeVulkanProducer = false,
                      visiblePatternConfirmed = false,
                      humanVisiblePatternCheckRequired = true,
                  )
              )
            },
            holdMs,
        )
  }

  private fun createSdkQuadSurfaceProbeLayer(
      sdkSwapchain: SceneSwapchain,
      canvasDrawn: Boolean,
      anchorMode: String,
  ): Boolean =
      runCatching {
            val pose = sdkQuadSurfaceProbePoseFromViewer()
            val entity = Entity.create(Transform(pose), Scale(Vector3(1.0f, 1.0f, 1.0f)), Visible(true))
            val sceneObject =
                if (anchorMode == "generated-single-sided-quad") {
                  val material = SceneMaterial.passthrough()
                  val mesh =
                      SceneMesh.singleSidedQuad(
                          SDK_QUAD_SURFACE_PROBE_WIDTH_METERS,
                          SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS,
                          material,
                      )
                  sdkQuadSurfaceProbeAnchorMaterial = material
                  sdkQuadSurfaceProbeAnchorMesh = mesh
                  SceneObject(scene, mesh, "sdk_quad_surface_probe_anchor", entity)
                } else {
                  SceneObject(scene, entity)
                }
            scene.addObject(sceneObject)
            sdkQuadSurfaceProbeSceneObject = sceneObject
            val layer =
                SceneQuadLayer(
                    scene,
                    sdkSwapchain,
                    SDK_QUAD_SURFACE_PROBE_WIDTH_METERS,
                    SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS,
                    0.5f,
                    0.5f,
                    StereoMode.None,
                    sceneObject,
                )
            layer.setZIndex(SDK_QUAD_SURFACE_PROBE_Z_INDEX)
            sdkQuadSurfaceProbeLayer = layer
            marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeLayerCreatedMarker(
                    canvasDrawn = canvasDrawn,
                    anchorMode = anchorMode,
                    sceneObjectHandle = sceneObject.handle,
                    layerPositionM = activityVectorMarker(pose.t),
                    layerQuaternion = activityQuaternionMarker(pose.q),
                )
            )
            true
          }
          .getOrElse { throwable ->
            marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeLayerCreateFailedMarker(
                    canvasDrawn = canvasDrawn,
                    anchorMode = anchorMode,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  private fun createCameraHwbProbeLayer(sdkSwapchain: SceneSwapchain): Boolean =
      runCatching {
            val pose = sdkQuadSurfaceProbePoseFromViewer()
            val entity = Entity.create(Transform(pose), Scale(Vector3(1.0f, 1.0f, 1.0f)), Visible(true))
            val material = SceneMaterial.passthrough()
            val mesh =
                SceneMesh.singleSidedQuad(
                    CAMERA_HWB_PROBE_WIDTH_METERS,
                    CAMERA_HWB_PROBE_HEIGHT_METERS,
                    material,
                )
            sdkQuadSurfaceProbeAnchorMaterial = material
            sdkQuadSurfaceProbeAnchorMesh = mesh
            val sceneObject = SceneObject(scene, mesh, "camera_hwb_probe_anchor", entity)
            scene.addObject(sceneObject)
            sdkQuadSurfaceProbeSceneObject = sceneObject
            val layer =
                SceneQuadLayer(
                    scene,
                    sdkSwapchain,
                    CAMERA_HWB_PROBE_WIDTH_METERS,
                    CAMERA_HWB_PROBE_HEIGHT_METERS,
                    0.5f,
                    0.5f,
                    StereoMode.None,
                    sceneObject,
                )
            layer.setZIndex(CAMERA_HWB_PROBE_Z_INDEX)
            sdkQuadSurfaceProbeLayer = layer
            marker(
                SpatialDiagnosticProbeRouteModule.cameraHwbProbeLayerCreatedMarker(
                    sceneObjectHandle = sceneObject.handle,
                    layerPositionM = activityVectorMarker(pose.t),
                    layerQuaternion = activityQuaternionMarker(pose.q),
                )
            )
            true
          }
          .getOrElse { throwable ->
            marker(
                SpatialDiagnosticProbeRouteModule.cameraHwbProbeLayerCreateFailedMarker(
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun createCameraHwbProjectionLayer(sdkSwapchain: SceneSwapchain): Boolean =
      runCatching {
            val plane = cameraHwbProjectionPlaneForPlacement()
            val entity =
                Entity.create(
                    Transform(plane.pose),
                    Scale(Vector3(1.0f, 1.0f, 1.0f)),
                    Visible(true),
                )
            cameraHwbProjectionEntity = entity
            val material = SceneMaterial.passthrough()
            val mesh =
                SceneMesh.singleSidedQuad(
                    plane.projectionWidthMeters,
                    plane.projectionHeightMeters,
                    material,
                )
            sdkQuadSurfaceProbeAnchorMaterial = material
            sdkQuadSurfaceProbeAnchorMesh = mesh
            val sceneObject = SceneObject(scene, mesh, "camera_hwb_projection_anchor", entity)
            scene.addObject(sceneObject)
            sdkQuadSurfaceProbeSceneObject = sceneObject
            val layer =
                SceneQuadLayer(
                    scene,
                    sdkSwapchain,
                    plane.projectionWidthMeters,
                    plane.projectionHeightMeters,
                    0.5f,
                    0.5f,
                    StereoMode.LeftRight,
                    sceneObject,
                )
            val layerZIndex = cameraHwbProjectionZIndexForPlacement(plane.placementMode)
            layer.setZIndex(layerZIndex)
            sdkQuadSurfaceProbeLayer = layer
            marker(
                CameraHwbProjectionModule.rawProjectionLayerCreatedMarker(
                    sceneObjectHandle = sceneObject.handle,
                    plane = plane,
                    layerZIndex = layerZIndex,
                    carrier = cameraHwbProjectionCarrierToken(),
                    carrierMode = cameraHwbProjectionCarrierMode,
                    projectionMarkerFields = cameraHwbProjectionMarkerFields(plane),
                    stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
                    videoProjectionMarkerFields =
                        spatialVideoProjectionMarkerFields(spatialVideoProjectionSettings),
                    publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
                )
            )
            true
          }
          .getOrElse { throwable ->
            cameraHwbProjectionEntity = null
            marker(
                CameraHwbProjectionModule.rawProjectionLayerCreateFailedMarker(
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  private fun drawSdkQuadSurfaceCheckerboard(surface: AndroidSurface): Boolean {
    if (!surface.isValid) {
      marker(SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCanvasDrawSkippedMarker())
      return false
    }
    var canvas: android.graphics.Canvas? = null
    return runCatching {
          canvas = surface.lockCanvas(null)
          val lockedCanvas = canvas ?: return@runCatching false
          val paint = AndroidPaint()
          val cellWidth = lockedCanvas.width / SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS.toFloat()
          val cellHeight = lockedCanvas.height / SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS.toFloat()
          for (y in 0 until SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS) {
            for (x in 0 until SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS) {
              paint.color =
                  if ((x + y) % 2 == 0) {
                    AndroidColor.rgb(218, 24, 24)
                  } else {
                    AndroidColor.rgb(20, 190, 70)
                  }
              lockedCanvas.drawRect(
                  x * cellWidth,
                  y * cellHeight,
                  (x + 1) * cellWidth,
                  (y + 1) * cellHeight,
                  paint,
              )
            }
          }
          paint.color = AndroidColor.WHITE
          paint.textSize = lockedCanvas.height * 0.075f
          paint.isAntiAlias = true
          lockedCanvas.drawText("SDK Canvas", lockedCanvas.width * 0.18f, lockedCanvas.height * 0.52f, paint)
          true
        }
        .onSuccess { drawn ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          marker(SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCanvasDrawCompleteMarker(drawn))
        }
        .onFailure { throwable ->
          canvas?.let { locked ->
            runCatching { surface.unlockCanvasAndPost(locked) }
          }
          marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCanvasDrawFailedMarker(
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
        .getOrDefault(false)
  }

  private fun drawCameraHwbProjectionSyntheticVisual(
      surface: AndroidSurface,
      carrierLabel: String,
  ): Boolean {
    if (!surface.isValid) {
      marker(
          CameraHwbProjectionModule.syntheticVisualDrawSkippedMarker(carrierLabel)
      )
      return false
    }
    var canvas: android.graphics.Canvas? = null
    return runCatching {
          canvas = surface.lockCanvas(null)
          val lockedCanvas = canvas ?: return@runCatching false
          val paint = AndroidPaint()
          val cellsX = 8
          val cellsY = 8
          val cellWidth = lockedCanvas.width / cellsX.toFloat()
          val cellHeight = lockedCanvas.height / cellsY.toFloat()
          val colors =
              intArrayOf(
                  AndroidColor.rgb(226, 18, 18),
                  AndroidColor.rgb(18, 210, 58),
                  AndroidColor.rgb(18, 86, 232),
                  AndroidColor.rgb(242, 214, 20),
              )
          for (y in 0 until cellsY) {
            for (x in 0 until cellsX) {
              paint.color = colors[(x + y) % colors.size]
              lockedCanvas.drawRect(
                  x * cellWidth,
                  y * cellHeight,
                  (x + 1) * cellWidth,
                  (y + 1) * cellHeight,
                  paint,
              )
            }
          }
          paint.isAntiAlias = true
          paint.color = AndroidColor.BLACK
          lockedCanvas.drawRect(
              lockedCanvas.width * 0.18f,
              lockedCanvas.height * 0.40f,
              lockedCanvas.width * 0.82f,
              lockedCanvas.height * 0.60f,
              paint,
          )
          paint.color = AndroidColor.WHITE
          paint.textSize = lockedCanvas.height * 0.075f
          lockedCanvas.drawText(
              "SPATIAL SDK",
              lockedCanvas.width * 0.24f,
              lockedCanvas.height * 0.52f,
              paint,
          )
          true
        }
        .onSuccess { drawn ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          marker(
              CameraHwbProjectionModule.syntheticVisualDrawCompleteMarker(drawn, carrierLabel)
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked ->
            runCatching { surface.unlockCanvasAndPost(locked) }
          }
          marker(
              CameraHwbProjectionModule.syntheticVisualDrawFailedMarker(
                  carrierLabel = carrierLabel,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
        .getOrDefault(false)
  }

  private fun cleanupSdkQuadSurfaceProbeSceneOnly(reason: String): String {
    var layerDestroyed = sdkQuadSurfaceProbeLayer == null
    var sceneObjectDestroyed = sdkQuadSurfaceProbeSceneObject == null
    var meshDestroyed = sdkQuadSurfaceProbeAnchorMesh == null
    var materialDestroyed = sdkQuadSurfaceProbeAnchorMaterial == null

    sdkQuadSurfaceProbeLayer?.let { layer ->
      layerDestroyed =
          runCatching {
                layer.destroy()
                true
              }
              .getOrDefault(false)
    }
    sdkQuadSurfaceProbeLayer = null

    sdkQuadSurfaceProbeSceneObject?.let { sceneObject ->
      sceneObjectDestroyed =
          runCatching {
                scene.destroyObject(sceneObject)
                true
              }
              .recoverCatching {
                sceneObject.destroy()
                true
              }
              .getOrDefault(false)
    }
    sdkQuadSurfaceProbeSceneObject = null
    cameraHwbProjectionEntity = null

    sdkQuadSurfaceProbeAnchorMesh?.let { mesh ->
      meshDestroyed =
          runCatching {
                mesh.destroy()
                true
              }
              .getOrDefault(false)
    }
    sdkQuadSurfaceProbeAnchorMesh = null

    sdkQuadSurfaceProbeAnchorMaterial?.let { material ->
      materialDestroyed =
          runCatching {
                material.destroy()
                true
              }
              .getOrDefault(false)
    }
    sdkQuadSurfaceProbeAnchorMaterial = null

    val cleanupStatus =
        if (layerDestroyed && sceneObjectDestroyed && meshDestroyed && materialDestroyed) {
          "destroyed"
        } else {
          "incomplete"
    }
    if (!layerDestroyed || !sceneObjectDestroyed || !meshDestroyed || !materialDestroyed) {
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSceneAnchorDestroyedMarker(
              reason = reason,
              layerDestroyed = layerDestroyed,
              sceneObjectDestroyed = sceneObjectDestroyed,
              anchorMeshDestroyed = meshDestroyed,
              anchorMaterialDestroyed = materialDestroyed,
              cleanupStatus = cleanupStatus,
          )
      )
    }
    return cleanupStatus
  }

  private fun cleanupCameraHwbProjectionPanelCarrier(reason: String): String {
    var nativeStopped = true
    if (cameraHwbProjectionPanelNativeStarted && nativeReceiptLibraryLoaded) {
      nativeStopped =
          runCatching {
                nativeStopCameraHwbProbe()
                true
              }
              .getOrDefault(false)
    }
    var sceneObjectDestroyed =
        !cameraHwbProjectionManualCustomMeshCarrierEnabled() ||
            cameraHwbProjectionPanelSceneObject == null
    val manualPanelSceneObject = cameraHwbProjectionPanelSceneObject
    if (cameraHwbProjectionManualCustomMeshCarrierEnabled()) {
      manualPanelSceneObject?.let { sceneObject ->
        sceneObjectDestroyed =
            runCatching {
                  scene.destroyObject(sceneObject)
                  true
                }
                .recoverCatching {
                  sceneObject.destroy()
                  true
                }
                .getOrDefault(false)
      }
    }
    var entityDestroyed = cameraHwbProjectionPanelEntity == null
    val panelEntity = cameraHwbProjectionPanelEntity
    panelEntity?.let { entity ->
      entityDestroyed =
          runCatching {
                entity.destroy()
                true
              }
              .getOrDefault(false)
    }
    cameraHwbProjectionPanelEntity = null
    cameraHwbProjectionPanelSceneObject = null
    cameraHwbProjectionPanelSurface = null
    cameraHwbProjectionPanelSurfaceConsumerCalled = false
    cameraHwbProjectionPanelReady = false
    cameraHwbProjectionPanelNativeStarted = false
    cameraHwbProjectionPanelStartMask = 0L
    if (cameraHwbProjectionEntity == panelEntity) {
      cameraHwbProjectionEntity = null
    }

    val cleanupStatus =
        if (nativeStopped && entityDestroyed && sceneObjectDestroyed) {
          "destroyed"
        } else {
          "incomplete"
        }
    if (
        !nativeStopped ||
            !entityDestroyed ||
            !sceneObjectDestroyed ||
            reason != "camera-hwb-projection-pre-run"
    ) {
      marker(
          CameraHwbProjectionModule.scenePanelCarrierDestroyedMarker(
              reason = reason,
              nativeStopped = nativeStopped,
              entityDestroyed = entityDestroyed,
              sceneObjectDestroyed = sceneObjectDestroyed,
              carrier = cameraHwbProjectionCarrierToken(),
              cleanupStatus = cleanupStatus,
          )
      )
    }
    return cleanupStatus
  }

  private fun cleanupSdkQuadSurfaceProbe(reason: String): String {
    stopSpatialVideoProjection("sdk-quad-surface-$reason")
    if (nativeReceiptLibraryLoaded) {
      runCatching { nativeStopSpatialEnvironmentDepthProbe() }
      runCatching { nativeStopSpatialNativePassthrough() }
    }
    val sceneCleanupStatus = cleanupSdkQuadSurfaceProbeSceneOnly(reason)
    val sceneCleanupDestroyed = sceneCleanupStatus == "destroyed"
    var swapchainDestroyed = sdkQuadSurfaceProbeSwapchain == null

    sdkQuadSurfaceProbeSwapchain?.let { swapchain ->
      swapchainDestroyed =
          runCatching {
                swapchain.destroy()
                true
              }
              .getOrDefault(false)
    }
    sdkQuadSurfaceProbeSwapchain = null
    sdkQuadSurfaceProbeSurface = null

    val cleanupStatus =
        if (sceneCleanupDestroyed && swapchainDestroyed) {
          "destroyed"
        } else {
          "incomplete"
    }
    if (!sceneCleanupDestroyed || !swapchainDestroyed || reason != "pre-run") {
      marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeDestroyedMarker(
              reason = reason,
              sceneCleanupStatus = sceneCleanupStatus,
              swapchainDestroyed = swapchainDestroyed,
              cleanupStatus = cleanupStatus,
          )
      )
    }
    return cleanupStatus
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun sdkQuadSurfaceProbePoseFromViewer(): Pose {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    if (viewerPose == null) {
      return Pose(
          Vector3(0.0f, 1.20f, -SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS),
          Quaternion.fromDirection(Vector3(0.0f, 0.0f, -1.0f), Vector3(0.0f, 1.0f, 0.0f)),
      )
    }
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val center = viewerPose.t + forward * SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  private fun runExternalSwapchainProbeIfRequested(reason: String) {
    if (externalSwapchainProbeStarted) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.externalSwapchainProbeEnabled()) {
      return
    }
    externalSwapchainProbeStarted = true
    val cycles = SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycles()
    val cycleMs = SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycleMs()
    marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeStartMarker(
            reason = reason,
            cycles = cycles,
            cycleMs = cycleMs,
        )
    )
    Handler(Looper.getMainLooper()).post { runExternalSwapchainProbeCycle(1, cycles, cycleMs) }
  }

  private fun runExternalSwapchainProbeCycle(
      cycleIndex: Int,
      cycleCount: Int,
      cycleMs: Long,
  ) {
    cleanupExternalSwapchainProbe("cycle-$cycleIndex-pre-cleanup")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLibraryUnavailableCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              error = nativeReceiptLibraryError,
          )
      )
      return
    }

    val probe = SpatialNativeInteropProbe.capture(scene)
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeMissingOpenXrHandlesCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              openXrInstanceHandleNonZero = probe.openXrInstanceHandleNonZero,
              openXrSessionHandleNonZero = probe.openXrSessionHandleNonZero,
              openXrGetInstanceProcAddrHandleNonZero = probe.openXrGetInstanceProcAddrHandleNonZero,
          )
      )
      return
    }

    val sdkHandleWrapMode = probeSdkSceneSwapchainHandleWrapping(cycleIndex)
    val externalHandle =
        runCatching {
              nativeCreateExternalOpenXrSwapchain(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX,
                  EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeNativeCreateCallFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    externalSwapchainProbeExternalHandle = externalHandle
    if (externalHandle == 0L) {
      marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeZeroHandleCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              sdkHandleWrapMode = sdkHandleWrapMode,
          )
      )
      return
    }

    val wrapped =
        runCatching { SceneSwapchain(externalHandle) }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeExternalWrapFailedMarker(
                      cycleIndex = cycleIndex,
                      externalHandle = externalHandle,
                      sdkHandleWrapMode = sdkHandleWrapMode,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              val ownership = cleanupExternalSwapchainProbe("cycle-$cycleIndex-wrap-failed")
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeExternalWrapFailedCompleteMarker(
                      cycleIndex = cycleIndex,
                      cycleCount = cycleCount,
                      sdkHandleWrapMode = sdkHandleWrapMode,
                      destroyOwnership = ownership,
                  )
              )
              return
            }
    externalSwapchainProbeWrappedSwapchain = wrapped
    marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeExternalWrapResultMarker(
            cycleIndex = cycleIndex,
            externalHandle = externalHandle,
            wrapperHandle = wrapped.handle,
            wrapperNativeHandle = wrapped.nativeHandle(),
            wrapperPlatformHandle = wrapped.platformHandle(),
            platformHandleMatchesExternal = wrapped.platformHandle() == externalHandle,
            nativeHandleMatchesExternal = wrapped.nativeHandle() == externalHandle,
            handleMatchesExternal = wrapped.handle == externalHandle,
        )
    )

    val layerCreated =
        runCatching {
              val pose = externalSwapchainProbePoseFromViewer()
              val entity = Entity.create(Transform(pose), Scale(Vector3(1.0f, 1.0f, 1.0f)), Visible(true))
              val sceneObject = SceneObject(scene, entity)
              scene.addObject(sceneObject)
              externalSwapchainProbeSceneObject = sceneObject
              val layer =
                  SceneQuadLayer(
                      scene,
                      wrapped,
                      EXTERNAL_SWAPCHAIN_PROBE_WIDTH_METERS,
                      EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_METERS,
                      0.5f,
                      0.5f,
                      StereoMode.None,
                      sceneObject,
                  )
              layer.setZIndex(EXTERNAL_SWAPCHAIN_PROBE_Z_INDEX)
              externalSwapchainProbeLayer = layer
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLayerCreatedMarker(
                      cycleIndex = cycleIndex,
                      layerPositionM = activityVectorMarker(pose.t),
                      layerQuaternion = activityQuaternionMarker(pose.q),
                  )
              )
              true
            }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLayerCreateFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              false
            }

    marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycleVisibleMarker(
            cycleIndex = cycleIndex,
            cycleCount = cycleCount,
            sdkHandleWrapMode = sdkHandleWrapMode,
            layerCreated = layerCreated,
        )
    )
    if (!layerCreated) {
      val ownership = cleanupExternalSwapchainProbe("cycle-$cycleIndex-layer-create-failed")
      marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLayerCreateFailedCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              sdkHandleWrapMode = sdkHandleWrapMode,
              destroyOwnership = ownership,
          )
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val ownership = cleanupExternalSwapchainProbe("cycle-$cycleIndex-destroy")
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycleCompleteMarker(
                      cycleIndex = cycleIndex,
                      cycleCount = cycleCount,
                      sdkHandleWrapMode = sdkHandleWrapMode,
                      layerCreated = layerCreated,
                      destroyOwnership = ownership,
                  )
              )
              if (cycleIndex < cycleCount) {
                Handler(Looper.getMainLooper())
                    .postDelayed(
                        { runExternalSwapchainProbeCycle(cycleIndex + 1, cycleCount, cycleMs) },
                        EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS,
                    )
              } else {
                marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCompleteMarker(
                        cycleCount = cycleCount,
                        sdkHandleWrapMode = sdkHandleWrapMode,
                        layerCreated = layerCreated,
                        destroyOwnership = ownership,
                    )
                )
              }
            },
            cycleMs,
        )
  }

  private fun probeSdkSceneSwapchainHandleWrapping(cycleIndex: Int): String {
    val sdkSwap =
        runCatching { SceneSwapchain.create(EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX, EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX, 1) }
            .getOrElse { throwable ->
              marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkSwapchainCreateFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return "none"
            }
    val sdkSurfaceValid = runCatching { sdkSwap.getSurface()?.isValid == true }.getOrDefault(false)
    marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkSwapchainCreatedMarker(
            cycleIndex = cycleIndex,
            handle = sdkSwap.handle,
            nativeHandle = sdkSwap.nativeHandle(),
            platformHandle = sdkSwap.platformHandle(),
            surfaceValid = sdkSurfaceValid,
        )
    )
    var firstSuccess = "none"
    listOf(
            "handle" to sdkSwap.handle,
            "nativeHandle" to sdkSwap.nativeHandle(),
            "platformHandle" to sdkSwap.platformHandle(),
        )
        .forEach { (label, handle) ->
          if (handle == 0L) {
            marker(
                SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapZeroMarker(
                    cycleIndex = cycleIndex,
                    handleLabel = label,
                    sourceHandle = handle,
                )
            )
            return@forEach
          }
          runCatching { SceneSwapchain(handle) }
              .onSuccess { wrapper ->
                externalSwapchainProbeSdkWrapRetainers.add(wrapper)
                if (firstSuccess == "none") {
                  firstSuccess = label
                }
                val wrapperSurfaceValid =
                    runCatching { wrapper.getSurface()?.isValid == true }.getOrDefault(false)
                marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapSuccessMarker(
                        cycleIndex = cycleIndex,
                        handleLabel = label,
                        sourceHandle = handle,
                        wrapperHandle = wrapper.handle,
                        wrapperNativeHandle = wrapper.nativeHandle(),
                        wrapperPlatformHandle = wrapper.platformHandle(),
                        wrapperSurfaceValid = wrapperSurfaceValid,
                    )
                )
              }
              .onFailure { throwable ->
                marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapFailedMarker(
                        cycleIndex = cycleIndex,
                        handleLabel = label,
                        sourceHandle = handle,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
        }
    runCatching { sdkSwap.destroy() }
        .onFailure { throwable ->
          marker(
              SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkSwapchainDestroyFailedMarker(
                  cycleIndex = cycleIndex,
                  error = throwable.javaClass.simpleName,
              )
          )
        }
    marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapSummaryMarker(
            cycleIndex = cycleIndex,
            sdkHandleWrapMode = firstSuccess,
        )
    )
    return firstSuccess
  }

  private fun cleanupExternalSwapchainProbe(reason: String): String {
    var layerDestroyed = externalSwapchainProbeLayer == null
    var sceneObjectDestroyed = externalSwapchainProbeSceneObject == null
    var wrapperDestroyed = externalSwapchainProbeWrappedSwapchain == null
    var wrapperDestroySkipped = false
    var nativeDestroyResult = "not-run"
    var destroyOwnership = "unknown"

    externalSwapchainProbeLayer?.let { layer ->
      layerDestroyed =
          runCatching {
                layer.destroy()
                true
              }
              .getOrDefault(false)
    }
    externalSwapchainProbeLayer = null

    externalSwapchainProbeSceneObject?.let { sceneObject ->
      sceneObjectDestroyed =
          runCatching {
                scene.destroyObject(sceneObject)
                true
              }
              .recoverCatching {
                sceneObject.destroy()
                true
              }
              .getOrDefault(false)
    }
    externalSwapchainProbeSceneObject = null

    externalSwapchainProbeWrappedSwapchain?.let { wrapped ->
      externalSwapchainProbeExternalWrapRetainers.add(wrapped)
      wrapperDestroyed = false
      wrapperDestroySkipped = true
    }
    externalSwapchainProbeWrappedSwapchain = null

    val externalHandle = externalSwapchainProbeExternalHandle
    if (externalHandle != 0L && nativeReceiptLibraryLoaded) {
      val probe = SpatialNativeInteropProbe.capture(scene)
      val destroyCode =
          runCatching {
                nativeDestroyExternalOpenXrSwapchain(
                    probe.openXrInstanceHandle,
                    probe.openXrGetInstanceProcAddrHandle,
                    externalHandle,
                )
              }
              .getOrElse { throwable ->
                marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeNativeDestroyCallFailedMarker(
                        reason = reason,
                        externalHandle = externalHandle,
                        error = throwable.javaClass.simpleName,
                    )
                )
                Int.MIN_VALUE
              }
      nativeDestroyResult = destroyCode.toString()
      destroyOwnership =
          when (destroyCode) {
            0 -> "native"
            OPENXR_ERROR_HANDLE_INVALID -> "sdk"
            else -> "unknown"
          }
    }
    externalSwapchainProbeExternalHandle = 0L
    if (!layerDestroyed ||
        !sceneObjectDestroyed ||
        !wrapperDestroyed ||
        nativeDestroyResult != "not-run") {
      marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeDestroyedMarker(
              reason = reason,
              layerDestroyed = layerDestroyed,
              sceneObjectDestroyed = sceneObjectDestroyed,
              wrapperDestroyed = wrapperDestroyed,
              wrapperDestroySkipped = wrapperDestroySkipped,
              nativeDestroyResult = nativeDestroyResult,
              destroyOwnership = destroyOwnership,
          )
      )
    }
    return destroyOwnership
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun externalSwapchainProbePoseFromViewer(): Pose {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    if (viewerPose == null) {
      return Pose(
          Vector3(0.0f, 1.20f, -EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS),
          Quaternion.fromDirection(Vector3(0.0f, 0.0f, -1.0f), Vector3(0.0f, 1.0f, 0.0f)),
      )
    }
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val center = viewerPose.t + forward * EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun createManualSurfaceParticleLayerPanel(reason: String): Entity? {
    val targetDistanceMeters = currentParticleLayerTargetDistanceMeters()
    val surfaceOverscanScale = currentParticleLayerSurfaceOverscanScale()
    val surfaceWidthMeters =
        particleLayerSurfaceWidthMeters(targetDistanceMeters, surfaceOverscanScale)
    val surfaceHeightMeters =
        particleLayerSurfaceHeightMeters(targetDistanceMeters, surfaceOverscanScale)
    val carrierResult =
        SpatialSurfaceParticlePanelCarrierModule.createManualCustomMeshPanel(
            scene = scene,
            sceneObjectSystem = systemManager.findSystem<SceneObjectSystem>(),
            pose = particleLayerPose(),
            surfaceWidthMeters = surfaceWidthMeters,
            surfaceHeightMeters = surfaceHeightMeters,
            visible = particleLayerVisibleForPanelMode(),
            reason = reason,
            carrier = particleLayerCarrierToken(),
        )
    val readyCarrier =
        when (carrierResult) {
          is SpatialSurfaceParticleManualPanelCarrierResult.Ready -> carrierResult
          is SpatialSurfaceParticleManualPanelCarrierResult.Failed -> {
            marker(carrierResult.marker)
            return null
          }
        }
    particleLayerPanelSceneObject = readyCarrier.panelSceneObject
    particleLayerManualPanelSurface = readyCarrier.surface
    particleSurfacePanelReady = true
    particleSurfaceConsumerCalled = true
    particleSurfaceConsumerSurfaceValid = readyCarrier.surface.isValid
    val layerUpdateStatus = updateParticleLayerPanelLayer("manual-custom-mesh-created", false)
    marker(
        SpatialSurfaceParticlePanelCarrierModule.manualPanelCarrierReadyMarker(
            reason = reason,
            carrier = particleLayerCarrierToken(),
            surfaceValid = readyCarrier.surface.isValid,
            layerUpdateStatus = layerUpdateStatus,
            placementMarkerFields = particleLayerPlacementMarkerFields(),
            stereoMarkerFields = particleLayerStereoMarkerFields(),
        )
    )
    startNativeSurfaceParticleLayer(readyCarrier.surface)
    return readyCarrier.entity
  }

  private fun startNativeSurfaceParticleLayer(surface: AndroidSurface) {
    if (!nativeSurfaceParticleLayerEnabled()) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartSuppressedDisabledMarker(
              suppressionSource = nativeSurfaceParticleLayerSuppressionSource(),
              privateRendererEnabled = privateSpatialEcsParticleRendererEnabled(),
              particleLayerStarted = particleLayerStarted,
              nativeSurfaceStartRequested = nativeSurfaceStartRequested,
          )
      )
      return
    }
    if (cameraStackSuppressesParticles) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartSuppressedCameraStackMarker(
              particleLayerStarted = particleLayerStarted,
              nativeSurfaceStartRequested = nativeSurfaceStartRequested,
          )
      )
      return
    }
    if (particleLayerStarted) {
      marker(SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartSkippedAlreadyStartedMarker())
      return
    }
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLibraryUnavailableMarker(
              nativeReceiptLibraryError
          )
      )
      return
    }
    val surfaceValid = surface.isValid
    val openXrProbe = SpatialNativeInteropProbe.capture(scene)
    if (!surfaceValid) {
      marker(SpatialSurfaceParticleRouteModule.nativeSurfaceParticleSurfaceUnavailableMarker())
      return
    }
    runCatching {
          val startMask =
              nativeStartSurfaceParticleLayer(
                  surface,
                  PARTICLE_LAYER_WIDTH_PX,
                  PARTICLE_LAYER_HEIGHT_PX,
                  PARTICLE_LAYER_PARTICLE_COUNT,
                  PARTICLE_LAYER_FRAME_COUNT,
                  openXrProbe.openXrInstanceHandle,
                  openXrProbe.openXrSessionHandle,
                  openXrProbe.openXrGetInstanceProcAddrHandle,
              )
          particleLayerStarted = true
          nativeSurfaceStartRequested = true
          lastNativeSurfaceStartMask = startMask
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartRequestedMarker(
                  surfaceValid = surfaceValid,
                  startMask = startMask,
                  carrier = particleLayerCarrierToken(),
                  openXrInstanceHandleNonZero = openXrProbe.openXrInstanceHandleNonZero,
                  openXrSessionHandleNonZero = openXrProbe.openXrSessionHandleNonZero,
                  openXrGetInstanceProcAddrHandleNonZero =
                      openXrProbe.openXrGetInstanceProcAddrHandleNonZero,
                  placementMarkerFields = particleLayerPlacementMarkerFields(),
                  stereoMarkerFields = particleLayerStereoMarkerFields(),
              )
          )
          submitNativeSurfaceParticleParameters(source = "start")
        }
        .getOrElse { throwable ->
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartFailedMarker(
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
    }
  }

  private fun updateSurfaceParticleControls(
      driver0Value01: Float,
      driver1Value01: Float,
      pointScale: Float,
      source: String = "panel",
  ): SurfaceParticleControlState =
      updateSurfaceParticleControls(
          particleControls.copy(
              driver0Value01 = driver0Value01,
              driver1Value01 = driver1Value01,
              pointScale = pointScale,
          ),
          source,
      )

  private fun updateSurfaceParticleControls(
      controls: SurfaceParticleControlState,
      source: String = "panel",
  ): SurfaceParticleControlState {
    particleControls =
        controls.copy(
            driver0Value01 = controls.driver0Value01.coerceIn(0.0f, 1.0f),
            driver1Value01 = controls.driver1Value01.coerceIn(0.0f, 1.0f),
            driver2Value01 = controls.driver2Value01.coerceIn(0.0f, 1.0f),
            driver3Value01 = controls.driver3Value01.coerceIn(0.0f, 1.0f),
            driver4Value01 = controls.driver4Value01.coerceIn(0.0f, 1.0f),
            driver5Value01 = controls.driver5Value01.coerceIn(0.0f, 1.0f),
            driver6Value01 = controls.driver6Value01.coerceIn(0.0f, 1.0f),
            driver7Value01 = controls.driver7Value01.coerceIn(0.0f, 1.0f),
            pointScale = controls.pointScale.coerceIn(0.35f, 2.25f),
            tracerDrawSlotsPerOscillator =
                controls.tracerDrawSlotsPerOscillator.coerceIn(0.0f, 7.0f),
            tracerLifetimeSeconds = controls.tracerLifetimeSeconds.coerceIn(0.0f, 0.5f),
            tracerCopiesPerSecond = controls.tracerCopiesPerSecond.coerceIn(0.0f, 14.0f),
            transparencyOpacity = controls.transparencyOpacity.coerceIn(0.0f, 1.0f),
            projectionWorldScale = controls.projectionWorldScale.coerceIn(0.5f, 2.0f),
        )
    submitNativeSurfaceParticleParameters(source = source)
    return particleControls
  }

  private fun applyDriverProfileToParticleControls(
      block: ActiveBlockSnapshot,
      source: String,
  ): SurfaceParticleControlState {
    val updated =
        updateSurfaceParticleControls(
            block.driver0Value01.toFloat(),
            block.driver1Value01.toFloat(),
            particleControls.pointScale,
            source = source,
        )
    marker(
        "channel=spatial-camera-panel status=driver-profile-parameter-handoff " +
            "rendererAuthority=native-vulkan-wsi-surface-panel transport=jni-live-queue " +
            "panelMustNotBeAuthority=true highRatePayloadsAllowed=false " +
            "source=${activityMarkerToken(source)} driverProfileId=${activityMarkerToken(block.conditionId)} " +
            "profileId=${activityMarkerToken(block.profileId)} " +
            "workflowPanelVisibleAtHandoff=${panelPlacement.visible} " +
            "panelClosedBeforeHandoff=${!panelPlacement.visible} " +
            "profileDriver0Value01=${String.format(Locale.US, "%.3f", block.driver0Value01)} " +
            "profileDriver1Value01=${String.format(Locale.US, "%.3f", block.driver1Value01)} " +
            "driver0Value01=${activityMarkerFloat(updated.driver0Value01)} " +
            "driver1Value01=${activityMarkerFloat(updated.driver1Value01)} " +
            "pointScale=${activityMarkerFloat(updated.pointScale)}"
    )
    return updated
  }

  private fun submitNativeSurfaceParticleParameters(source: String) {
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleParameterSubmitSkippedMarker(source)
      )
      return
    }
    runCatching {
          val mask =
              nativeUpdateSurfaceParticleParameters(
                  particleControls.driver0Value01,
                  particleControls.driver1Value01,
                  particleControls.pointScale,
                  particleControls.driver2Value01,
                  particleControls.driver3Value01,
                  particleControls.driver4Value01,
                  particleControls.driver5Value01,
                  particleControls.driver6Value01,
                  particleControls.driver7Value01,
                  particleControls.tracerDrawSlotsPerOscillator,
                  particleControls.tracerLifetimeSeconds,
                  particleControls.tracerCopiesPerSecond,
                  particleControls.transparencyOpacity,
                  particleControls.projectionWorldScale,
              )
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleParametersSubmittedMarker(
                  source,
                  mask,
                  particleControls,
              )
          )
        }
        .getOrElse { throwable ->
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleParameterSubmitFailedMarker(
                  source,
                  throwable.javaClass.simpleName,
              )
          )
        }
  }

  private fun resolveSurfaceParticleAliasControl(intent: Intent, source: String) {
    val parameterId =
        intent.getStringExtra(EXTRA_PARTICLE_ALIAS_PARAMETER_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: ""
    val requestedValue = intent.getFloatExtra(EXTRA_PARTICLE_ALIAS_VALUE, 0.0f)
    val activationProfile =
        intent
            .getStringExtra(EXTRA_PARTICLE_ALIAS_VISUAL_DRIVER_ACTIVATION_PROFILE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "default"
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleAliasSubmitSkippedMarker(
              source,
              parameterId,
              activationProfile,
          )
      )
      return
    }
    runCatching {
          val mask =
              nativeResolveSurfaceParticleAliasParameter(
                  parameterId,
                  requestedValue,
                  activationProfile,
              )
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleAliasSubmittedMarker(
                  source,
                  parameterId,
                  activationProfile,
                  requestedValue,
                  mask,
              )
          )
        }
        .getOrElse { throwable ->
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleAliasSubmitFailedMarker(
                  source,
                  parameterId,
                  throwable.javaClass.simpleName,
              )
          )
        }
  }

  private fun suppressParticleLayerForCameraStack(source: String) {
    cameraStackSuppressesParticles = true
    particleLayerEntity?.setComponent(Visible(false))
    panelLauncherEntity?.setComponent(Visible(false))
    val wasStarted = particleLayerStarted
    val stopAttempted = nativeReceiptLibraryLoaded && wasStarted
    if (stopAttempted) {
      runCatching { nativeStopSurfaceParticleLayer() }
          .onSuccess {
            particleLayerStarted = false
            nativeSurfaceStartRequested = false
            marker(
                SpatialSurfaceParticleRouteModule.cameraStackParticleLayerSuppressedMarker(
                    source = source,
                    stopAttempted = true,
                    stopSucceeded = true,
                    launcherPanelVisible = launcherPanelVisibleForPanelMode(),
                    particleLayerStarted = particleLayerStarted,
                    nativeSurfaceStartRequested = nativeSurfaceStartRequested,
                )
            )
          }
          .onFailure { throwable ->
            marker(
                SpatialSurfaceParticleRouteModule.cameraStackParticleLayerSuppressFailedMarker(
                    source = source,
                    particleLayerStarted = particleLayerStarted,
                    nativeSurfaceStartRequested = nativeSurfaceStartRequested,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
      return
    }
    if (!wasStarted) {
      particleLayerStarted = false
      nativeSurfaceStartRequested = false
    }
    marker(
        SpatialSurfaceParticleRouteModule.cameraStackParticleLayerSuppressedMarker(
            source = source,
            stopAttempted = stopAttempted,
            stopSucceeded = true,
            launcherPanelVisible = launcherPanelVisibleForPanelMode(),
            particleLayerStarted = particleLayerStarted,
            nativeSurfaceStartRequested = nativeSurfaceStartRequested,
        )
    )
  }

  private fun suppressParticleLayerIfCameraProjectionRequested(source: String) {
    when {
      activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true ->
          suppressParticleLayerForCameraStack("$source-camera-hwb-projection-property")
      activityReadOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) == true ->
          suppressParticleLayerForCameraStack("$source-spatial-video-projection-property")
    }
  }

  private fun cameraStackOrRoomRequested(): Boolean =
      spatialVirtualRoomEnabled() ||
          activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true ||
          activityReadOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) == true ||
          currentSpatialVideoProjectionSettings(intent).active

  private fun deactivateLegacyWorkflowPanelsForCameraStack(source: String) {
    if (!cameraStackOrRoomRequested()) {
      return
    }
    cameraStackSuppressesParticles = true
    panelPlacement = panelPlacement.copy(visible = false)
    privateLayerPanelVisible = false
    privateLayerPanelPlacement = privateLayerPanelPlacement.copy(visible = false)
    panelEntity?.setComponent(Visible(false))
    privateLayerPanelEntity?.setComponent(Visible(false))
    panelLauncherEntity?.setComponent(Visible(false))
    particleLayerEntity?.setComponent(Visible(false))
    marker(SpatialPanelPlacementModule.legacyWorkflowPanelsDeactivatedMarker(source))
  }

  private fun deactivatePanelShellIfRequested(source: String) {
    if (panelShellVisible()) {
      return
    }
    panelPlacement = panelPlacement.copy(visible = false)
    privateLayerPanelVisible = false
    privateLayerPanelPlacement = privateLayerPanelPlacement.copy(visible = false)
    panelEntity?.setComponent(Visible(false))
    privateLayerPanelEntity?.setComponent(Visible(false))
    panelLauncherEntity?.setComponent(Visible(false))
    particleLayerEntity?.setComponent(Visible(particleLayerVisibleForPanelMode()))
    marker(
        SpatialPanelPlacementModule.panelShellHiddenMarker(
            SpatialPanelShellHiddenMarkerInput(
                source = source,
                panelShellVisibleProperty = PANEL_SHELL_VISIBLE_PROPERTY,
                particleLayerVisible = particleLayerVisibleForPanelMode(),
                cameraStackSuppressesParticles = cameraStackSuppressesParticles,
                nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
                privateSpatialEcsParticleRendererEnabled = privateSpatialEcsParticleRendererEnabled(),
                nativeSurfaceParticleLayerSuppressedByPrivateRenderer =
                    nativeSurfaceParticleLayerSuppressedByPrivateRenderer(),
            )
        )
    )
  }

  private fun stopNativeSurfaceParticleLayer(source: String = "lifecycle") {
    val wasStarted = particleLayerStarted
    if (nativeReceiptLibraryLoaded && wasStarted) {
      runCatching { nativeStopSurfaceParticleLayer() }
          .onSuccess {
            particleLayerStarted = false
            nativeSurfaceStartRequested = false
            marker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStoppedMarker(
                    source = source,
                    particleLayerStarted = particleLayerStarted,
                    nativeSurfaceStartRequested = nativeSurfaceStartRequested,
                )
            )
          }
          .onFailure { throwable ->
            marker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStopFailedMarker(
                    source = source,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
    } else if (!wasStarted) {
      nativeSurfaceStartRequested = false
    }
  }

  private fun adjustPanelPlacement(
      deltaX: Float,
      deltaY: Float,
      deltaZ: Float,
      deltaScale: Float,
  ): PanelPlacement {
    panelPlacement =
        SpatialPanelPlacementModule.adjustWorkflowPlacement(
            panelPlacement,
            deltaX,
            deltaY,
            deltaZ,
            deltaScale,
        )
    applyPanelPlacement()
    persistPanelHeadlockTuning("compose-placement-buttons")
    marker(
        SpatialPanelPlacementModule.workflowPlacementUpdatedMarker(
            panelMode = panelStateToken(),
            headlockMarkerFields = panelHeadlockMarkerFields(),
        )
    )
    return panelPlacement
  }

  private fun resizeWorkflowPanel(deltaWidth: Float, deltaHeight: Float): PanelPlacement {
    panelPlacement =
        SpatialPanelPlacementModule.resizeWorkflowPanel(panelPlacement, deltaWidth, deltaHeight)
    applyPanelPlacement()
    persistPanelHeadlockTuning("compose-panel-resize")
    marker(
        SpatialPanelPlacementModule.workflowPanelSizeUpdatedMarker(
            widthMeters = panelPlacement.widthMeters,
            heightMeters = panelPlacement.heightMeters,
            panelMode = panelStateToken(),
        )
    )
    return panelPlacement
  }

  private fun resetWorkflowPanelPlacement(): PanelPlacement {
    privateLayerPanelVisible = false
    privateLayerPanelPlacement = privateLayerPanelPlacement.copy(visible = false)
    panelPlacement = SpatialPanelPlacementModule.resetWorkflowPanelPlacement(panelPlacement)
    applyPanelPlacement()
    persistPanelHeadlockTuning("compose-panel-reset")
    recordPanelState("compose-panel-reset")
    marker(
        SpatialPanelPlacementModule.workflowPlacementResetMarker(
            panelMode = panelStateToken(),
            headlockMarkerFields = panelHeadlockMarkerFields(),
        )
    )
    return panelPlacement
  }

  private fun setPanelHeadlocked(enabled: Boolean, source: String): PanelPlacement {
    panelPlacement = SpatialPanelPlacementModule.setWorkflowHeadlocked(panelPlacement, enabled)
    applyPanelPlacement()
    persistPanelHeadlockTuning(source)
    marker(
        SpatialPanelPlacementModule.workflowHeadlockModeUpdatedMarker(
            source = source,
            headlockMarkerFields = panelHeadlockMarkerFields(),
        )
    )
    return panelPlacement
  }

  private fun setWorkflowPanelVisible(
      visible: Boolean,
      focus: Boolean,
      source: String,
  ): PanelPlacement {
    if (visible && !panelShellVisible()) {
      deactivatePanelShellIfRequested(source)
      marker(
          SpatialPanelPlacementModule.panelModeUpdateSuppressedMarker(
              channel = "spatial-panel",
              source = source,
              requestedPanel = "workflow-panel",
              panelShellVisibleProperty = PANEL_SHELL_VISIBLE_PROPERTY,
              particleLayerVisible = particleLayerVisibleForPanelMode(),
          )
      )
      return panelPlacement
    }
    if (visible) {
      privateLayerPanelVisible = false
      privateLayerPanelPlacement = privateLayerPanelPlacement.copy(visible = false)
    }
    panelPlacement =
        if (visible && focus) {
          if (panelPlacement.headlocked) {
            panelPlacement.copy(
                visible = true,
                xMeters = PANEL_HEADLOCK_OFFSET_X_METERS,
                yMeters = PANEL_HEADLOCK_OFFSET_Y_METERS,
                zMeters = PANEL_FRONT_OF_CAMERA_VIDEO_DISTANCE_METERS,
                scale = PANEL_FRONT_OF_CAMERA_VIDEO_SCALE,
            )
          } else {
            panelPlacement.copy(
                visible = true,
                yMeters = PANEL_FOCUS_Y_METERS,
                zMeters = PANEL_FOCUS_Z_METERS,
                scale = 1.0f,
            )
          }
        } else {
          panelPlacement.copy(visible = visible)
        }
    applyPanelPlacement()
    recordPanelState(source)
    marker(
        SpatialPanelPlacementModule.workflowPanelModeUpdatedMarker(
            SpatialPanelModeMarkerInput(
                source = source,
                panelMode = panelStateToken(),
                workflowPanelVisible = panelPlacement.visible,
                privateLayerPanelVisible = privateLayerPanelVisible,
                launcherPanelVisible = launcherPanelVisibleForPanelMode(),
                legacyLauncherPanelSuppressed = legacyLauncherPanelSuppressedForCameraStack(),
                particleLayerVisible = particleLayerVisibleForPanelMode(),
                headlockMarkerFields = panelHeadlockMarkerFields(),
            )
        )
    )
    return panelPlacement
  }

  private fun setQuestionnaireDueReopensPanel(enabled: Boolean, source: String) {
    if (questionnaireDueReopensPanel == enabled) {
      return
    }
    questionnaireDueReopensPanel = enabled
    marker(
        ExperimentPanelController.questionnaireAutoPanelPolicyUpdatedMarker(
            source = source,
            questionnaireDueReopensPanel = enabled,
        )
    )
  }

  private fun setPrivateLayerPanelVisible(
      visible: Boolean,
      focus: Boolean,
      source: String,
  ): PanelPlacement {
    if (visible && !panelShellVisible()) {
      deactivatePanelShellIfRequested(source)
      marker(
          SpatialPanelPlacementModule.panelModeUpdateSuppressedMarker(
              channel = "private-layer-panel",
              source = source,
              requestedPanel = "private-layer-panel",
              panelShellVisibleProperty = PANEL_SHELL_VISIBLE_PROPERTY,
              particleLayerVisible = particleLayerVisibleForPanelMode(),
              spatialPrivateLayerControlPanel = false,
          )
      )
      return panelPlacement
    }
    if (!visible && !PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) {
      syncPrivateLayerPanelPlacementFromEntity("private-layer-panel-close")
    }
    privateLayerPanelVisible = visible
    val inputForegroundActive = false
    val inputForegroundDistanceMeters =
        privateLayerPanelPlacement.zMeters.coerceIn(
            PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
            PANEL_HEADLOCK_DISTANCE_MAX_METERS,
        )
    val inputForegroundScale = PRIVATE_LAYER_PANEL_SCALE
    privateLayerPanelPlacement =
        if (visible && focus) {
          coercePrivateLayerPanelPlacement(
              privateLayerPanelPlacement.copy(
                  visible = true,
                  headlocked = true,
                  zMeters = inputForegroundDistanceMeters,
                  scale = inputForegroundScale,
                  widthMeters = PANEL_WIDTH_METERS,
                  heightMeters = PANEL_HEIGHT_METERS,
              )
          )
        } else {
          privateLayerPanelPlacement.copy(visible = false)
        }
    val privateLayerPanelSeedPose =
        if (visible && focus) {
          privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
        } else {
          null
        }
    if (visible && focus && PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) {
      privateLayerPanelPlacement = privateLayerPanelPlacement.copy(headlocked = false)
    }
    panelPlacement =
        panelPlacement.copy(visible = false)
    applyPanelPlacement(
        updatePrivateLayerPanelTransform =
            visible && focus && !PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM
    )
    privateLayerPanelSeedPose?.let { pose ->
      privateLayerPanelEntity?.setComponent(Transform(pose))
    }
    privateLayerPanelEntity?.setComponent(privateLayerPanelGrabbable(enabled = visible))
    val privateLayerPanelLayerUpdateStatus =
        updatePrivateLayerPanelLayer("private-layer-panel-visibility")
    updateCameraHwbProjectionFromViewer(
        reason = "private-layer-panel-visibility",
        forceLog = true,
    )
    marker(
        SpatialPanelPlacementModule.privateLayerPanelModeUpdatedMarker(
            SpatialPrivateLayerPanelModeMarkerInput(
                source = source,
                panelMode = panelStateToken(),
                workflowPanelVisible = panelPlacement.visible,
                privateLayerPanelVisible = privateLayerPanelVisible,
                launcherPanelVisible = launcherPanelVisibleForPanelMode(),
                legacyLauncherPanelSuppressed = legacyLauncherPanelSuppressedForCameraStack(),
                particleLayerVisible = particleLayerVisibleForPanelMode(),
                privateLayerPanelLayerUpdateStatus = privateLayerPanelLayerUpdateStatus,
                cameraVideoProjectionLayerZIndex =
                    cameraHwbProjectionZIndexForPlacement(cameraHwbProjectionPlacementMode),
                leftStickYPanelDistanceEnabled = currentLeftStickPanelDistanceEnabled(),
                panelOpensInFrontOfCameraVideo =
                    privateLayerPanelPlacement.zMeters < CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS,
                inputForegroundActive = inputForegroundActive,
                inputForegroundDistanceMeters = inputForegroundDistanceMeters,
                inputForegroundScale = inputForegroundScale,
                projectionPanelHittable = cameraHwbProjectionPanelHittableToken(),
                projectionPanelInputClearanceActive =
                    cameraHwbProjectionPrivatePanelInputClearanceActive(),
                projectionPanelInputBehindPrivateLayerPanel =
                    cameraHwbProjectionInputCarrierBehindPrivatePanel(),
                projectionPanelInputTargetDistanceMeters =
                    currentCameraHwbProjectionTargetDistanceMeters(),
                privateLayerOverride = privateLayerOverride,
                headlockMarkerFields = panelHeadlockMarkerFields(),
            )
        )
    )
    return panelPlacement
  }

  private fun privateLayerPanelLayerConfigEnabled(): Boolean = true

  private fun updatePrivateLayerPanelLayer(
      reason: String,
      forceLog: Boolean = true,
  ): String {
    if (!privateLayerPanelLayerConfigEnabled()) {
      return "disabled-mesh-render-mode"
    }
    val panel = privateLayerPanelSceneObject ?: return "panel-scene-object-missing"
    return runCatching {
          panel.layer?.setZIndex(PRIVATE_LAYER_PANEL_LAYER_Z_INDEX)
              ?: return "panel-layer-missing"
          "updated-private-layer-panel-z-index"
        }
        .getOrElse { throwable ->
          if (forceLog) {
            marker(
                SpatialPanelPlacementModule.privateLayerPanelLayerUpdateFailedMarker(
                    reason = reason,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
          "failed-${throwable.javaClass.simpleName}"
        }
  }

  private fun updateParticleLayerPanelLayer(
      reason: String,
      forceLog: Boolean = true,
  ): String {
    val panel = particleLayerPanelSceneObject ?: return "panel-scene-object-missing"
    val opacity = currentParticleLayerPanelOpacity()
    return runCatching {
          val layer = panel.layer ?: return "panel-layer-missing"
          val previousOpacity = lastParticleLayerPanelOpacity
          val opacityChanged = previousOpacity == null || abs(previousOpacity - opacity) >= 0.001f
          val layerConfigChanged = forceLog || !particleLayerPanelLayerConfigured
          if (layerConfigChanged) {
            layer.setZIndex(PARTICLE_LAYER_Z_INDEX)
            layer.setAlphaBlend(
                LayerAlphaBlend(
                    BlendFactor.SOURCE_ALPHA,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                    BlendFactor.ONE,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                )
            )
            particleLayerPanelLayerConfigured = true
          }
          if (opacityChanged) {
            layer.setColorScaleBias(Vector4(1.0f, 1.0f, 1.0f, opacity), Vector4(0.0f))
            lastParticleLayerPanelOpacity = opacity
          }
          if (forceLog || layerConfigChanged || opacityChanged) {
            marker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelLayerUpdatedMarker(
                    reason,
                    opacity,
                )
            )
          }
          if (layerConfigChanged || opacityChanged) {
            "updated-particle-layer-panel-alpha"
          } else {
            "unchanged-particle-layer-panel-alpha"
          }
        }
        .getOrElse { throwable ->
          if (forceLog) {
            marker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelLayerUpdateFailedMarker(
                    reason,
                    opacity,
                    throwable.javaClass.simpleName,
                    throwable.message ?: "none",
                )
            )
          }
          "failed-${throwable.javaClass.simpleName}"
        }
  }

  private fun applyPanelPlacement(updatePrivateLayerPanelTransform: Boolean = false) {
    val shellVisible = panelShellVisible()
    val pose = panelPose()
    panelEntity?.let { entity ->
      entity.setComponent(Transform(pose))
      entity.setComponent(Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale)))
      entity.setComponent(panelDimensions())
      entity.setComponent(Visible(shellVisible && panelPlacement.visible && !privateLayerPanelVisible))
    }
    privateLayerPanelEntity?.let { entity ->
      if (updatePrivateLayerPanelTransform) {
        entity.setComponent(Transform(privateLayerPanelPose()))
      }
      entity.setComponent(
          Scale(
              Vector3(
                  privateLayerPanelPlacement.scale,
                  privateLayerPanelPlacement.scale,
                  privateLayerPanelPlacement.scale,
              )
          )
      )
      entity.setComponent(privateLayerPanelDimensions())
      entity.setComponent(Visible(shellVisible && privateLayerPanelVisible && privateLayerPanelPlacement.visible))
    }
    panelLauncherEntity?.setComponent(Transform(panelLauncherPose()))
    panelLauncherEntity?.setComponent(panelLauncherDimensions())
    panelLauncherEntity?.setComponent(Visible(launcherPanelVisibleForPanelMode()))
    particleLayerEntity?.setComponent(Visible(particleLayerVisibleForPanelMode()))
    updateParticleLayerPanelLayer("apply-panel-placement", forceLog = false)
  }

  private fun particleLayerVisibleForPanelMode(): Boolean =
      SpatialPanelPlacementModule.particleLayerVisibleForPanelMode(
          workflowPanelVisible = panelPlacement.visible,
          privateLayerPanelVisible = privateLayerPanelVisible,
          cameraStackSuppressesParticles = cameraStackSuppressesParticles,
          nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
      )

  private fun launcherPanelVisibleForPanelMode(): Boolean =
      SpatialPanelPlacementModule.launcherPanelVisibleForPanelMode(
          panelShellVisible = panelShellVisible(),
          panelLauncherVisible = panelLauncherVisible(),
          workflowPanelVisible = panelPlacement.visible,
          privateLayerPanelVisible = privateLayerPanelVisible,
          cameraStackSuppressesParticles = cameraStackSuppressesParticles,
          spatialVirtualRoomEnabled = spatialVirtualRoomEnabled(),
      )

  private fun legacyLauncherPanelSuppressedForCameraStack(): Boolean =
      SpatialPanelPlacementModule.legacyLauncherPanelSuppressedForCameraStack(
          cameraStackSuppressesParticles,
          spatialVirtualRoomEnabled(),
      )

  private fun panelPose(): Pose =
      if (panelPlacement.headlocked) {
        headlockedPanelPoseFromViewer() ?: worldPanelPose()
      } else {
        worldPanelPose()
      }

  private fun privateLayerPanelPose(): Pose =
      if (privateLayerPanelPlacement.headlocked) {
        privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
      } else {
        privateLayerPanelWorldPose()
      }

  private fun worldPanelPose(): Pose =
      SpatialPanelPlacementModule.workflowWorldPose(panelPlacement)

  private fun panelLauncherPose(): Pose =
      SpatialPanelPlacementModule.panelLauncherPose()

  private fun privateLayerPanelWorldPose(): Pose =
      SpatialPanelPlacementModule.privateLayerPanelWorldPose(privateLayerPanelPlacement)

  private fun activeHeadlockedPanelPlacement(): PanelPlacement =
      if (privateLayerPanelVisible) privateLayerPanelPlacement else panelPlacement

  private fun privateLayerPanelGrabbable(enabled: Boolean): Grabbable =
      SpatialPanelPlacementModule.privateLayerPanelGrabbable(enabled)

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun syncPrivateLayerPanelPlacementFromEntity(reason: String): Boolean {
    val pose = privateLayerPanelEntity?.tryGetComponent<Transform>()?.transform ?: return false
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return false
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val viewerUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = activityCross(forward, viewerUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val up = activityCross(right, forward).activityNormalizedOr(viewerUp)
    val offset = activityVectorSubtract(pose.t, viewerPose.t)
    val distance =
        activityVectorLength(offset)
            .coerceIn(PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val previous = privateLayerPanelPlacement
    privateLayerPanelPlacement =
        coercePrivateLayerPanelPlacement(
            privateLayerPanelPlacement.copy(
                xMeters = activityDot(offset, right),
                yMeters = activityDot(offset, up),
                zMeters = distance,
                visible = privateLayerPanelVisible,
            )
        )
    if (!previous.headlockEquivalent(privateLayerPanelPlacement)) {
      marker(
          SpatialPanelPlacementModule.privateLayerPlacementSyncedFromSdkTransformMarker(
              reason = reason,
              previousDistanceMeters = previous.zMeters,
              headlockMarkerFields = panelHeadlockMarkerFields(),
          )
      )
    }
    return true
  }

  private fun logPrivateLayerPanelGrabbableState(reason: String, forceLog: Boolean) {
    val grabbable = privateLayerPanelEntity?.tryGetComponent<Grabbable>()
    val grabbed = grabbable?.isGrabbed ?: false
    val now = SystemClock.elapsedRealtime()
    val shouldLog =
        forceLog ||
            lastPrivateLayerPanelGrabbableState != grabbed ||
            now - lastPrivateLayerPanelGrabbableMarkerMs >=
                PRIVATE_LAYER_PANEL_GRABBABLE_MARKER_INTERVAL_MS
    if (!shouldLog) {
      return
    }
    lastPrivateLayerPanelGrabbableState = grabbed
    lastPrivateLayerPanelGrabbableMarkerMs = now
    marker(
        SpatialPanelPlacementModule.privateLayerGrabbableStateMarker(
            reason = reason,
            grabbed = grabbed,
            headlockMarkerFields = panelHeadlockMarkerFields(),
        )
    )
  }

  private fun coercePrivateLayerPanelPlacement(placement: PanelPlacement): PanelPlacement {
    return SpatialPanelPlacementModule.coercePrivateLayerPanelPlacement(placement)
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun headlockedPanelPoseFromViewer(): Pose? {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return null
    val rawForward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val rawUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val rawRight = activityCross(rawForward, rawUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val yawDegrees = currentParticleLayerViewYawDegrees()
    val rollStableBasis = activityRollStableParticleProjectionBasis(rawForward, yawDegrees)
    val forward = rollStableBasis.first
    val right = rollStableBasis.second
    val up = rollStableBasis.third
    val center =
        viewerPose.t +
            right * panelPlacement.xMeters +
            up * panelPlacement.yMeters +
            forward * panelPlacement.zMeters
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun privateLayerPanelPoseFromViewer(): Pose? {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return null
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val viewerUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = activityCross(forward, viewerUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val up = activityCross(right, forward).activityNormalizedOr(viewerUp)
    val placement = coercePrivateLayerPanelPlacement(privateLayerPanelPlacement)
    if (placement != privateLayerPanelPlacement) {
      privateLayerPanelPlacement = placement
    }
    val distance = placement.zMeters.coerceIn(PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val lateralSquared = placement.xMeters * placement.xMeters + placement.yMeters * placement.yMeters
    val forwardMeters = sqrt((distance * distance - lateralSquared).coerceAtLeast(0.0f).toDouble()).toFloat()
    val offset = right * placement.xMeters + up * placement.yMeters + forward * forwardMeters
    val direction = offset.activityNormalizedOr(forward)
    val panelUp = (up + direction * -activityDot(up, direction)).activityNormalizedOr(up)
    val center = viewerPose.t + direction * distance
    return Pose(center, Quaternion.fromDirection(direction, panelUp))
  }

  private fun updateWorkflowPanelHeadlockFromViewer(reason: String, forceLog: Boolean) {
    pollPanelHeadlockHotload(reason)
    var workflowPose: Pose? = null
    if (panelPlacement.headlocked) {
      workflowPose =
          headlockedPanelPoseFromViewer()
              ?: run {
                if (forceLog && panelPlacement.visible) {
                  marker(SpatialPanelPlacementModule.headlockedPoseUpdateSkippedMarker(reason))
                }
                null
              }
      workflowPose?.let { pose ->
        panelEntity?.let { entity ->
          entity.setComponent(Transform(pose))
          entity.setComponent(
              Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale))
          )
          entity.setComponent(panelDimensions())
          entity.setComponent(Visible(panelPlacement.visible && !privateLayerPanelVisible))
        }
      }
    }
    if (privateLayerPanelVisible) {
      privateLayerPanelEntity?.let { privatePanel ->
        if (privateLayerPanelIsGrabbed()) {
          syncPrivateLayerPanelPlacementFromEntity("private-layer-panel-grabbed")
        } else {
          privateLayerPanelPoseFromViewer()?.let { pose ->
            privatePanel.setComponent(Transform(pose))
          }
        }
        privatePanel.setComponent(Visible(privateLayerPanelPlacement.visible))
      }
      logPrivateLayerPanelGrabbableState(reason, forceLog)
    }
    val privatePose =
        if (privateLayerPanelVisible) {
          privateLayerPanelEntity?.tryGetComponent<Transform>()?.transform
        } else {
          null
        }

    val now = SystemClock.elapsedRealtime()
    val shouldLog =
        forceLog ||
            ((panelPlacement.visible || privateLayerPanelVisible) &&
                panelHeadlockMarkerCount < 4 &&
                now - lastPanelHeadlockMarkerMs >= PANEL_HEADLOCK_MARKER_INTERVAL_MS)
    if (!shouldLog) {
      return
    }
    panelHeadlockMarkerCount += 1
    lastPanelHeadlockMarkerMs = now
    marker(
        SpatialPanelPlacementModule.headlockedPoseUpdatedMarker(
            reason = reason,
            privateLayerPanelVisible = privateLayerPanelVisible,
            headlockMarkerFields = panelHeadlockMarkerFields(),
            panelPositionM = activityVectorMarker((privatePose ?: workflowPose)?.t ?: Vector3(0.0f)),
            panelQuaternion =
                activityQuaternionMarker(
                    (privatePose ?: workflowPose)?.q ?: Quaternion(1.0f, 0.0f, 0.0f, 0.0f)
                ),
        )
    )
  }

  private fun pollPanelHeadlockHotload(reason: String) {
    val updated = SpatialPanelPlacementModule.hotloadedWorkflowPlacement(panelPlacement)
    if (!panelPlacement.headlockEquivalent(updated)) {
      panelPlacement = updated
      applyPanelPlacement()
      persistPanelHeadlockTuning("runtime-hotload-android-property")
    }
    val token = panelHeadlockMarkerFields()
    if (token != lastPanelHeadlockHotloadToken) {
      lastPanelHeadlockHotloadToken = token
      marker(
          SpatialPanelPlacementModule.headlockHotloadUpdatedMarker(
              reason = reason,
              headlockMarkerFields = token,
          )
      )
    }
  }

  private fun persistPanelHeadlockTuning(source: String) {
    runCatching {
          val activePlacement = activeHeadlockedPanelPlacement()
          val row =
              JSONObject()
                  .put("schema_id", "rusty.quest.spatial_camera_panel.panel_headlock_tuning.v1")
                  .put("source", source)
                  .put("updated_at_unix_ms", System.currentTimeMillis())
                  .put(
                      "active_panel",
                      if (privateLayerPanelVisible) "private-layer-panel" else "workflow-panel",
                  )
                  .put("headlocked", activePlacement.headlocked)
                  .put("offset_x_m", activePlacement.xMeters.toDouble())
                  .put("offset_y_m", activePlacement.yMeters.toDouble())
                  .put("distance_m", activePlacement.zMeters.toDouble())
                  .put(
                      "distance_mode",
                      if (privateLayerPanelVisible) "left-stick-stored-placement"
                      else "viewer-forward-distance",
                  )
                  .put("scale", activePlacement.scale.toDouble())
                  .put("width_m", activePlacement.widthMeters.toDouble())
                  .put("height_m", activePlacement.heightMeters.toDouble())
                  .put(
                      "workflow_panel",
                      JSONObject()
                          .put("headlocked", panelPlacement.headlocked)
                          .put("offset_x_m", panelPlacement.xMeters.toDouble())
                          .put("offset_y_m", panelPlacement.yMeters.toDouble())
                          .put("distance_m", panelPlacement.zMeters.toDouble())
                          .put("distance_mode", "viewer-forward-distance")
                          .put("scale", panelPlacement.scale.toDouble())
                          .put("width_m", panelPlacement.widthMeters.toDouble())
                          .put("height_m", panelPlacement.heightMeters.toDouble()),
                  )
                  .put(
                      "private_layer_panel",
                      JSONObject()
                          .put("headlocked", privateLayerPanelPlacement.headlocked)
                          .put("offset_x_m", privateLayerPanelPlacement.xMeters.toDouble())
                          .put("offset_y_m", privateLayerPanelPlacement.yMeters.toDouble())
                          .put("distance_m", privateLayerPanelPlacement.zMeters.toDouble())
                          .put("distance_mode", "left-stick-stored-placement")
                          .put("render_mode", "spatial-sdk-mesh")
                          .put("layer_config", "disabled")
                          .put("layer_z_index", "none")
                          .put("scale", privateLayerPanelPlacement.scale.toDouble())
                          .put("width_m", privateLayerPanelPlacement.widthMeters.toDouble())
                          .put("height_m", privateLayerPanelPlacement.heightMeters.toDouble()),
                  )
          File(filesDir, PANEL_HEADLOCK_TUNING_FILE).writeText(row.toString(2), Charsets.UTF_8)
        }
        .getOrElse { throwable ->
          marker(
              SpatialPanelPlacementModule.headlockTuningPersistFailedMarker(
                  source = source,
                  error = throwable.javaClass.simpleName,
              )
          )
        }
  }

  private fun particleLayerPose(): Pose =
      Pose(
          Vector3(PARTICLE_LAYER_X_METERS, PARTICLE_LAYER_Y_METERS, PARTICLE_LAYER_Z_METERS),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun updateParticleLayerProjectionFromViewer(reason: String, forceLog: Boolean) {
    val entity = particleLayerEntity ?: return
    if (cameraStackSuppressesParticles) {
      entity.setComponent(Visible(false))
      if (forceLog) {
        marker(
            SpatialSurfaceParticleRouteModule.nativeSurfaceParticleProjectionPlaneUpdateSuppressedMarker(
                reason
            )
        )
      }
      return
    }
    val viewerPose =
        runCatching { scene.getViewerPose() }
            .getOrElse { throwable ->
              if (forceLog) {
                marker(
                    SpatialSurfaceParticleRouteModule.nativeSurfaceParticleProjectionPlaneUpdateSkippedMarker(
                        reason,
                        throwable.javaClass.simpleName,
                    )
                )
              }
              return
            }
    val rawForward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val rawUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val rawRight = activityCross(rawForward, rawUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val yawDegrees = currentParticleLayerViewYawDegrees()
    val rollStableBasis = activityRollStableParticleProjectionBasis(rawForward, yawDegrees)
    val forward = rollStableBasis.first
    val right = rollStableBasis.second
    val up = rollStableBasis.third
    val eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull()
    val leftEyeOffsetRightMeters = activityEyeOffsetRightMeters(eyeOffsets?.first)
    val rightEyeOffsetRightMeters = activityEyeOffsetRightMeters(eyeOffsets?.second)
    val leftEyeWorld = viewerPose.t + rawRight * leftEyeOffsetRightMeters
    val rightEyeWorld = viewerPose.t + rawRight * rightEyeOffsetRightMeters
    val targetDistanceMeters = currentParticleLayerTargetDistanceMeters()
    val projectionWidthMeters = particleLayerProjectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters = particleLayerProjectionHeightMeters(targetDistanceMeters)
    val surfaceOverscanScale = currentParticleLayerSurfaceOverscanScale()
    val surfaceWidthMeters =
        particleLayerSurfaceWidthMeters(targetDistanceMeters, surfaceOverscanScale)
    val surfaceHeightMeters =
        particleLayerSurfaceHeightMeters(targetDistanceMeters, surfaceOverscanScale)
    val projectionSurfaceMarkerFields =
        SpatialSurfaceParticleRouteModule.projectionSurfaceMarkerFields(
            projectionWidthMeters,
            projectionHeightMeters,
            surfaceWidthMeters,
            surfaceHeightMeters,
        )
    val previousTargetDistanceMeters = lastParticleLayerTargetDistanceMeters
    val previousSurfaceOverscanScale = lastParticleLayerSurfaceOverscanScale
    val surfaceGeometryChanged =
        previousTargetDistanceMeters == null ||
            abs(previousTargetDistanceMeters - targetDistanceMeters) >= 0.001f ||
            previousSurfaceOverscanScale == null ||
            abs(previousSurfaceOverscanScale - surfaceOverscanScale) >= 0.001f
    if (surfaceGeometryChanged) {
      lastParticleLayerTargetDistanceMeters = targetDistanceMeters
      lastParticleLayerSurfaceOverscanScale = surfaceOverscanScale
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleSurfaceGeometryHotloadUpdatedMarker(
              targetDistanceMeters,
              projectionWidthMeters,
              projectionHeightMeters,
              surfaceOverscanScale,
              surfaceWidthMeters,
              surfaceHeightMeters,
              projectionSurfaceMarkerFields,
          )
      )
    }
    val now = SystemClock.elapsedRealtime()
    val center = viewerPose.t + forward * targetDistanceMeters
    val planePose = Pose(center, Quaternion.fromDirection(forward, up))
    entity.setComponent(Transform(planePose))
    if (surfaceGeometryChanged || !particleLayerSurfaceGeometryApplied) {
      entity.setComponent(PanelDimensions(Vector2(surfaceWidthMeters, surfaceHeightMeters)))
      particleLayerSurfaceGeometryApplied = true
    }
    entity.setComponent(Visible(particleLayerVisibleForPanelMode()))
    if (
        forceLog ||
            surfaceGeometryChanged ||
            now - lastParticleLayerPanelLayerCheckMs >= PARTICLE_LAYER_PANEL_LAYER_CHECK_INTERVAL_MS
    ) {
      lastParticleLayerPanelLayerCheckMs = now
      updateParticleLayerPanelLayer("projection-plane-update", forceLog = false)
    }
    val nativePanelPoseUpdateMask =
        if (nativeReceiptLibraryLoaded) {
          runCatching {
                nativeUpdateSurfaceParticlePanelPose(
                    center.x,
                    center.y,
                    center.z,
                    right.x,
                    right.y,
                    right.z,
                    up.x,
                    up.y,
                    up.z,
                    surfaceWidthMeters,
                    surfaceHeightMeters,
                    targetDistanceMeters,
                    leftEyeOffsetRightMeters,
                    rightEyeOffsetRightMeters,
                )
              }
              .getOrElse { throwable ->
                if (forceLog) {
                  marker(
                      SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelPoseNativeUpdateFailedMarker(
                          reason,
                          throwable.javaClass.simpleName,
                      )
                  )
                }
                0L
              }
        } else {
          0L
        }
    val nativeViewerEyePoseUpdateMask =
        if (nativeReceiptLibraryLoaded) {
          runCatching {
                nativeUpdateSurfaceParticleViewerEyePose(
                    viewerPose.t.x,
                    viewerPose.t.y,
                    viewerPose.t.z,
                    rawRight.x,
                    rawRight.y,
                    rawRight.z,
                    rawUp.x,
                    rawUp.y,
                    rawUp.z,
                    rawForward.x,
                    rawForward.y,
                    rawForward.z,
                    leftEyeWorld.x,
                    leftEyeWorld.y,
                    leftEyeWorld.z,
                    rightEyeWorld.x,
                    rightEyeWorld.y,
                    rightEyeWorld.z,
                )
              }
              .getOrElse { throwable ->
                if (forceLog) {
                  marker(
                      SpatialSurfaceParticleRouteModule.nativeSurfaceParticleViewerEyePoseNativeUpdateFailedMarker(
                          reason,
                          throwable.javaClass.simpleName,
                      )
                  )
                }
                0L
              }
        } else {
          0L
        }
    val shouldLog =
        forceLog ||
            (particleLayerProjectionMarkerCount < 4 &&
                now - lastParticleLayerProjectionMarkerMs >=
                    PARTICLE_LAYER_PROJECTION_MARKER_INTERVAL_MS)
    if (!shouldLog) {
      return
    }
    particleLayerProjectionMarkerCount += 1
    lastParticleLayerProjectionMarkerMs = now
    marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticleProjectionPlaneUpdatedMarker(
            reason = reason,
            placementMarkerFields = particleLayerPlacementMarkerFields(),
            viewYawDegrees = yawDegrees,
            viewerPositionM = activityVectorMarker(viewerPose.t),
            viewerForward = activityVectorMarker(rawForward),
            viewerUp = activityVectorMarker(rawUp),
            viewerRight = activityVectorMarker(rawRight),
            panelForward = activityVectorMarker(forward),
            panelRight = activityVectorMarker(right),
            panelUp = activityVectorMarker(up),
            nativePanelPoseUpdateMask = nativePanelPoseUpdateMask,
            nativeViewerEyePoseUpdateMask = nativeViewerEyePoseUpdateMask,
            projectionSurfaceMarkerFields = projectionSurfaceMarkerFields,
            projectionWidthMeters = projectionWidthMeters,
            projectionHeightMeters = projectionHeightMeters,
            surfaceOverscanScale = surfaceOverscanScale,
            surfaceWidthMeters = surfaceWidthMeters,
            surfaceHeightMeters = surfaceHeightMeters,
            planeCenterM = activityVectorMarker(center),
            planeQuaternion = activityQuaternionMarker(planePose.q),
            leftEyeOffsetM = activityVectorMarker(eyeOffsets?.first ?: Vector3(0.0f)),
            rightEyeOffsetM = activityVectorMarker(eyeOffsets?.second ?: Vector3(0.0f)),
            leftEyeWorldM = activityVectorMarker(leftEyeWorld),
            rightEyeWorldM = activityVectorMarker(rightEyeWorld),
            leftEyeOffsetRightMeters = leftEyeOffsetRightMeters,
            rightEyeOffsetRightMeters = rightEyeOffsetRightMeters,
        )
    )
  }

  private fun particleLayerPlacementMarkerFields(): String {
    val targetDistanceMeters = currentParticleLayerTargetDistanceMeters()
    val surfaceOverscanScale = currentParticleLayerSurfaceOverscanScale()
    return SpatialSurfaceParticleRouteModule.placementMarkerFields(
        carrierMode = particleLayerCarrierMode(),
        targetDistanceMeters = targetDistanceMeters,
        viewYawDegrees = currentParticleLayerViewYawDegrees(),
        surfaceOverscanScale = surfaceOverscanScale,
        panelOpacity = currentParticleLayerPanelOpacity(),
    )
  }

  private fun currentParticleLayerTargetDistanceMeters(): Float =
      remoteParticleLayerTargetDistanceMeters
          ?: activityReadFloatSystemProperty(
              PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY,
              PARTICLE_LAYER_TARGET_DISTANCE_METERS,
              PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
              PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
          )

  private fun currentParticleLayerViewYawDegrees(): Float =
      remoteParticleLayerViewYawDegrees
          ?: activityReadFloatSystemProperty(
              PARTICLE_LAYER_VIEW_YAW_PROPERTY,
              PARTICLE_LAYER_VIEW_YAW_DEGREES,
              PARTICLE_LAYER_VIEW_YAW_MIN_DEGREES,
              PARTICLE_LAYER_VIEW_YAW_MAX_DEGREES,
          )

  private fun currentParticleLayerPanelOpacity(): Float =
      activityReadFloatSystemProperty(
          PARTICLE_LAYER_PANEL_OPACITY_PROPERTY,
          PARTICLE_LAYER_PANEL_OPACITY,
          PARTICLE_LAYER_PANEL_OPACITY_MIN,
          PARTICLE_LAYER_PANEL_OPACITY_MAX,
      )

  private fun applyRemoteParticleLayerTargetDistance(intent: Intent, source: String) {
    val requested =
        intent.getFloatExtra(
            EXTRA_PARTICLE_LAYER_TARGET_DISTANCE_METERS,
            currentParticleLayerTargetDistanceMeters(),
        )
    val clamped =
        requested.coerceIn(
            PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
            PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
        )
    remoteParticleLayerTargetDistanceMeters = clamped
    updateParticleLayerProjectionFromViewer(
        reason = "$source-particle-panel-distance",
        forceLog = true,
    )
    marker(
        SpatialSurfaceParticleRouteModule.particleLayerTargetDistanceCommandAppliedMarker(
            source,
            requested,
            clamped,
        )
    )
  }

  private fun applyRemoteParticleLayerViewYaw(intent: Intent, source: String) {
    val requested =
        intent.getFloatExtra(
            EXTRA_PARTICLE_LAYER_VIEW_YAW_DEGREES,
            currentParticleLayerViewYawDegrees(),
        )
    val clamped =
        requested.coerceIn(
            PARTICLE_LAYER_VIEW_YAW_MIN_DEGREES,
            PARTICLE_LAYER_VIEW_YAW_MAX_DEGREES,
        )
    remoteParticleLayerViewYawDegrees = clamped
    updateParticleLayerProjectionFromViewer(
        reason = "$source-particle-panel-view-yaw",
        forceLog = true,
    )
    marker(
        SpatialSurfaceParticleRouteModule.particleLayerViewYawCommandAppliedMarker(
            source,
            requested,
            clamped,
        )
    )
  }

  private fun currentParticleLayerSurfaceOverscanScale(): Float =
      activityReadFloatSystemProperty(
          PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY,
          PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE,
          PARTICLE_LAYER_SURFACE_OVERSCAN_MIN_SCALE,
          PARTICLE_LAYER_SURFACE_OVERSCAN_MAX_SCALE,
      )

  private fun particleLayerProjectionWidthMeters(targetDistanceMeters: Float): Float =
      SpatialSurfaceParticleRouteModule.projectionWidthMeters(targetDistanceMeters)

  private fun particleLayerProjectionHeightMeters(targetDistanceMeters: Float): Float =
      SpatialSurfaceParticleRouteModule.projectionHeightMeters(targetDistanceMeters)

  private fun particleLayerSurfaceWidthMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = currentParticleLayerSurfaceOverscanScale(),
  ): Float =
      SpatialSurfaceParticleRouteModule.surfaceWidthMeters(targetDistanceMeters, overscanScale)

  private fun particleLayerSurfaceHeightMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = currentParticleLayerSurfaceOverscanScale(),
  ): Float =
      SpatialSurfaceParticleRouteModule.surfaceHeightMeters(targetDistanceMeters, overscanScale)

  private fun particleLayerSurfacePanelDimensions(
      targetDistanceMeters: Float = currentParticleLayerTargetDistanceMeters(),
      overscanScale: Float = currentParticleLayerSurfaceOverscanScale(),
  ): PanelDimensions =
      SpatialSurfaceParticleRouteModule.surfacePanelDimensions(targetDistanceMeters, overscanScale)

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun updateCameraHwbProjectionFromViewer(reason: String, forceLog: Boolean) {
    val entity = cameraHwbProjectionEntity ?: return
    val plane = cameraHwbProjectionPlaneForPlacement()
    entity.setComponent(Transform(plane.pose))
    if (cameraHwbProjectionScenePanelCarrierEnabled()) {
      entity.setComponent(PanelDimensions(Vector2(plane.projectionWidthMeters, plane.projectionHeightMeters)))
      entity.setComponent(Hittable(MeshCollision.NoCollision))
    }
    entity.setComponent(Visible(true))
    val layerUpdateStatus = updateCameraHwbProjectionLayer(plane, reason)
    val panelCarrierUpdateStatus = updateCameraHwbProjectionPanelCarrierLayer(plane, reason)
    val nativePanelPoseUpdateMask = updateNativePanelProjectionFromCameraPlane(plane, reason, forceLog)
    val now = SystemClock.elapsedRealtime()
    val shouldLog =
        forceLog ||
            (cameraHwbProjectionMarkerCount < 4 &&
                now - lastCameraHwbProjectionMarkerMs >=
                    CAMERA_HWB_PROJECTION_MARKER_INTERVAL_MS)
    if (!shouldLog) {
      return
    }
    cameraHwbProjectionMarkerCount += 1
    lastCameraHwbProjectionMarkerMs = now
    marker(
        CameraHwbProjectionModule.rawProjectionPlaneUpdatedMarker(
            reason = reason,
            plane = plane,
            projectionMarkerFields = cameraHwbProjectionMarkerFields(plane),
            stereoMarkerFields = cameraHwbProjectionStereoMarkerFields(),
            videoProjectionMarkerFields =
                spatialVideoProjectionMarkerFields(spatialVideoProjectionSettings),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
            layerUpdateStatus = layerUpdateStatus,
            panelCarrierUpdateStatus = panelCarrierUpdateStatus,
            nativePanelPoseUpdateMask = nativePanelPoseUpdateMask,
        )
    )
  }

  private fun updateCameraHwbProjectionLayer(
      plane: CameraHwbProjectionPlane,
      reason: String,
  ): String {
    val layer = sdkQuadSurfaceProbeLayer ?: return "layer-missing"
    return runCatching {
          layer.updateLayer(
              plane.projectionWidthMeters,
              plane.projectionHeightMeters,
              0.5f,
              0.5f,
              StereoMode.LeftRight.ordinal,
          )
          layer.setZIndex(cameraHwbProjectionZIndexForPlacement(plane.placementMode))
          "updated-existing-scene-anchor"
        }
        .getOrElse { throwable ->
          marker(
              CameraHwbProjectionModule.rawProjectionLayerUpdateFailedMarker(
                  reason = reason,
                  plane = plane,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
          "failed-${throwable.javaClass.simpleName}"
        }
  }

  private fun updateCameraHwbProjectionPanelCarrierLayer(
      plane: CameraHwbProjectionPlane,
      reason: String,
  ): String {
    val panel = cameraHwbProjectionPanelSceneObject ?: return "panel-scene-object-missing"
    return runCatching {
          if (cameraHwbProjectionManualCustomMeshCarrierEnabled()) {
            panel.setPosition(plane.center)
            panel.setRotationQuat(plane.pose.q)
            panel.setScale(Vector3(1.0f, 1.0f, 1.0f))
            panel.setIsVisible(true)
            return "updated-manual-custom-mesh-scene-object-layer-skipped"
          }
          cameraHwbProjectionPanelEntity?.setComponent(Hittable(MeshCollision.NoCollision))
          panel.setPosition(plane.center)
          panel.setRotationQuat(plane.pose.q)
          panel.setScale(Vector3(1.0f, 1.0f, 1.0f))
          panel.layer?.setZIndex(cameraHwbProjectionZIndexForPlacement(plane.placementMode))
              ?: return "panel-layer-missing"
          panel.setIsVisible(true)
          "updated-panel-scene-object"
        }
        .getOrElse { throwable ->
          marker(
              CameraHwbProjectionModule.scenePanelCarrierUpdateFailedMarker(
                  reason = reason,
                  plane = plane,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
          "failed-${throwable.javaClass.simpleName}"
        }
  }

  private fun updateNativePanelProjectionFromCameraPlane(
      plane: CameraHwbProjectionPlane,
      reason: String,
      forceLog: Boolean,
  ): Long {
    if (!nativeReceiptLibraryLoaded) {
      if (forceLog) {
        marker(
            CameraHwbProjectionModule.nativePanelPoseUpdateSkippedMarker(
                reason = reason,
                error = nativeReceiptLibraryError,
            )
        )
      }
      return 0L
    }
    return runCatching {
          nativeUpdateSurfaceParticlePanelPose(
              plane.center.x,
              plane.center.y,
              plane.center.z,
              plane.right.x,
              plane.right.y,
              plane.right.z,
              plane.up.x,
              plane.up.y,
              plane.up.z,
              plane.projectionWidthMeters,
              plane.projectionHeightMeters,
              plane.targetDistanceMeters,
              activityEyeOffsetRightMeters(plane.leftEyeOffset),
              activityEyeOffsetRightMeters(plane.rightEyeOffset),
          )
        }
        .getOrElse { throwable ->
          if (forceLog) {
            marker(
                CameraHwbProjectionModule.nativePanelPoseUpdateFailedMarker(
                    reason = reason,
                    plane = plane,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
          0L
        }
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun cameraHwbProjectionPlaneForPlacement(): CameraHwbProjectionPlane =
      when (cameraHwbProjectionPlacementMode) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> cameraHwbProjectionPlaneFromViewer()
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> cameraHwbProjectionPlaneOnVirtualWall()
      }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun cameraHwbProjectionPlaneFromViewer(): CameraHwbProjectionPlane {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    val targetDistanceMeters = currentCameraHwbProjectionTargetDistanceMeters()
    return CameraHwbProjectionModule.viewerLockedProjectionPlane(
        viewerPosition = viewerPose?.t,
        viewerForward = viewerPose?.forward(),
        viewerUp = viewerPose?.up(),
        eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull(),
        targetDistanceMeters = targetDistanceMeters,
        projectionWidthMeters = cameraHwbProjectionWidthMeters(targetDistanceMeters),
        projectionHeightMeters = cameraHwbProjectionHeightMeters(targetDistanceMeters),
    )
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun cameraHwbProjectionPlaneOnVirtualWall(): CameraHwbProjectionPlane {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    return CameraHwbProjectionModule.virtualWallProjectionPlane(
        viewerPosition = viewerPose?.t,
        eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull(),
    )
  }

  private fun cameraHwbProjectionZIndexForPlacement(
      placementMode: CameraHwbProjectionPlacementMode,
  ): Int = CameraHwbProjectionModule.zIndexForPlacement(cameraHwbProjectionCarrierMode, placementMode)

  private fun cameraHwbProjectionDisplayRoleForPlacement(
      placementMode: CameraHwbProjectionPlacementMode,
  ): String = CameraHwbProjectionModule.displayRoleForPlacement(placementMode)

  private fun cameraHwbProjectionScenePanelCarrierEnabled(): Boolean =
      CameraHwbProjectionModule.scenePanelCarrierEnabled(cameraHwbProjectionCarrierMode)

  private fun cameraHwbProjectionManualCustomMeshCarrierEnabled(): Boolean =
      CameraHwbProjectionModule.manualCustomMeshCarrierEnabled(cameraHwbProjectionCarrierMode)

  private fun cameraHwbProjectionPanelRegistrationId(): String =
      CameraHwbProjectionModule.panelRegistrationId(cameraHwbProjectionCarrierMode)

  private fun cameraHwbProjectionSyntheticVisualProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_SYNTHETIC_VISUAL_PROPERTY) == true

  private fun cameraHwbProjectionCarrierToken(): String =
      CameraHwbProjectionModule.carrierToken(cameraHwbProjectionCarrierMode)

  private fun currentCameraHwbProjectionCarrierMode(): CameraHwbProjectionCarrierMode {
    val rawToken =
        (activityReadOptionalStringIntentExtra(intent, CAMERA_HWB_PROJECTION_CARRIER_EXTRA)
                ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_CARRIER_PROPERTY))
    return CameraHwbProjectionModule.carrierModeForToken(rawToken, spatialVirtualRoomEnabled())
  }

  private fun cameraHwbProjectionRoomRenderOrderToken(): String =
      CameraHwbProjectionModule.roomRenderOrderToken(
          spatialVirtualRoomEnabled(),
          cameraHwbProjectionCarrierMode,
      )

  private fun cameraHwbProjectionStartGateToken(): String =
      CameraHwbProjectionModule.startGateToken(spatialVirtualRoomEnabled())

  private fun cameraHwbProjectionCarrierTransportToken(): String =
      CameraHwbProjectionModule.carrierTransportToken(
          intent?.hasExtra(CAMERA_HWB_PROJECTION_CARRIER_EXTRA) == true
      )

  private fun currentSpatialVideoProjectionSettings(intent: Intent?): SpatialVideoProjectionSettings {
    return SpatialVideoProjectionRouteModule.currentSettings(intent)
  }

  private fun spatialVideoProjectionMarkerFields(settings: SpatialVideoProjectionSettings): String =
      SpatialVideoProjectionRouteModule.markerFields(settings)

  private fun configureNativeSpatialVideoProjection(
      settings: SpatialVideoProjectionSettings,
      reason: String,
  ): Long {
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialVideoProjectionRouteModule.nativeConfigureSkippedMarker(
              reason,
              settings,
          )
      )
      return 0L
    }
    val mask =
        runCatching {
              nativeConfigureSpatialVideoProjection(
                  settings.enabled,
                  settings.path,
                  settings.stereoLayout,
                  settings.width,
                  settings.height,
                  settings.maxImages,
                  settings.fpsCap,
                  settings.looping,
                  settings.opacity,
                  settings.highRateJsonPayload,
              )
            }
            .getOrElse { throwable ->
              marker(
                  SpatialVideoProjectionRouteModule.nativeConfigureFailedMarker(
                      reason = reason,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                      settings = settings,
                  )
              )
              return 0L
            }
    marker(SpatialVideoProjectionRouteModule.nativeConfiguredMarker(reason, mask, settings))
    return mask
  }

  private fun startSpatialVideoProjection(
      settings: SpatialVideoProjectionSettings,
      reason: String,
  ) {
    marker(SpatialVideoProjectionRouteModule.startRequestedMarker(reason, settings))
    SpatialStereoVideoPlayback.start(
        this,
        settings.path,
        settings.width,
        settings.height,
        settings.maxImages,
        settings.fpsCap,
        settings.looping,
    )
    spatialVideoProjectionStarted = true
  }

  private fun stopSpatialVideoProjection(reason: String) {
    if (!spatialVideoProjectionStarted && !spatialVideoProjectionSettings.enabled) {
      return
    }
    val previousSettings = spatialVideoProjectionSettings
    runCatching { SpatialStereoVideoPlayback.stop() }
    if (nativeReceiptLibraryLoaded) {
      runCatching { nativeStopSpatialVideoProjectionProbe() }
      runCatching {
        nativeConfigureSpatialVideoProjection(
            false,
            "",
            previousSettings.stereoLayout,
            previousSettings.width,
            previousSettings.height,
            previousSettings.maxImages,
            previousSettings.fpsCap,
            previousSettings.looping,
            previousSettings.opacity,
            previousSettings.highRateJsonPayload,
        )
      }
    }
    spatialVideoProjectionStarted = false
    spatialVideoProjectionSettings = SpatialVideoProjectionSettings.disabled()
    marker(SpatialVideoProjectionRouteModule.stoppedMarker(reason, previousSettings))
  }

  private fun cameraHwbProjectionPanelHittableToken(): String =
      CameraHwbProjectionModule.panelHittableToken(cameraHwbProjectionCarrierMode)

  private fun cameraHwbProjectionMarkerFields(plane: CameraHwbProjectionPlane? = null): String {
    val targetScale = currentCameraHwbProjectionTargetScale()
    val stereoHorizontalOffsetUv = currentCameraHwbProjectionStereoHorizontalOffsetUv()
    val placementMode = plane?.placementMode ?: cameraHwbProjectionPlacementMode
    val targetDistanceMeters = plane?.targetDistanceMeters ?: currentCameraHwbProjectionTargetDistanceMeters()
    val projectionWidthMeters =
        plane?.projectionWidthMeters ?: cameraHwbProjectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters =
        plane?.projectionHeightMeters ?: cameraHwbProjectionHeightMeters(targetDistanceMeters)
    return CameraHwbProjectionModule.markerFields(
        CameraHwbProjectionMarkerInput(
            carrierMode = cameraHwbProjectionCarrierMode,
            placementMode = placementMode,
            carrierTransportToken = cameraHwbProjectionCarrierTransportToken(),
            startGateToken = cameraHwbProjectionStartGateToken(),
            roomRenderOrderToken = cameraHwbProjectionRoomRenderOrderToken(),
            targetDistanceMeters = targetDistanceMeters,
            projectionWidthMeters = projectionWidthMeters,
            projectionHeightMeters = projectionHeightMeters,
            targetScale = targetScale,
            stereoHorizontalOffsetUv = stereoHorizontalOffsetUv,
            targetScaleJoystickRatePerSecond = currentCameraHwbProjectionTargetScaleJoystickRate(),
            legacyLauncherPanelSuppressed = legacyLauncherPanelSuppressedForCameraStack(),
            targetDistanceSource = cameraHwbProjectionTargetDistanceSource(),
            virtualRoomForegroundDistanceActive =
                cameraHwbProjectionVirtualRoomForegroundDistanceActive(placementMode),
            privatePanelInputClearanceActive =
                cameraHwbProjectionPrivatePanelInputClearanceActive(placementMode),
            inputCarrierBehindPrivatePanel =
                cameraHwbProjectionInputCarrierBehindPrivatePanel(placementMode, targetDistanceMeters),
            privatePanelInputClearanceTargetDistanceMeters =
                cameraHwbProjectionPrivatePanelInputClearanceDistanceMeters(),
            targetCoordinateSpace = PARTICLE_LAYER_TARGET_COORDINATE_SPACE,
            targetProjectionSpace = PARTICLE_LAYER_TARGET_PROJECTION_SPACE,
        )
    )
  }

  private fun cameraHwbProjectionStereoMarkerFields(): String =
      CameraHwbProjectionModule.stereoMarkerFields()

  private fun currentCameraHwbProjectionTargetDistanceMeters(): Float {
    val requestedDistance =
        if (cameraHwbProjectionVirtualRoomForegroundDistanceActive()) {
          CAMERA_HWB_PROJECTION_ROOM_FOREGROUND_TARGET_DISTANCE_METERS
        } else {
          CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS
        }
    return requestedDistance.coerceIn(
        PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
        PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
    )
  }

  private fun cameraHwbProjectionTargetDistanceSource(): String =
      if (cameraHwbProjectionVirtualRoomForegroundDistanceActive()) {
        "virtual-room-viewer-locked-foreground"
      } else {
        "fixed-camera-projection-default"
      }

  private fun cameraHwbProjectionPrivatePanelInputClearanceActive(
      placementMode: CameraHwbProjectionPlacementMode = cameraHwbProjectionPlacementMode,
  ): Boolean =
      false

  private fun cameraHwbProjectionPrivatePanelInputClearanceDistanceMeters(): Float =
      currentCameraHwbProjectionTargetDistanceMeters()

  private fun privateLayerPanelInputForegroundDistanceMeters(): Float =
      (CAMERA_HWB_PROJECTION_ROOM_FOREGROUND_TARGET_DISTANCE_METERS -
              CAMERA_HWB_PROJECTION_PRIVATE_PANEL_INPUT_CLEARANCE_METERS)
          .coerceIn(
              PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
              PANEL_HEADLOCK_DISTANCE_MAX_METERS,
          )

  private fun privateLayerPanelInputForegroundScale(distanceMeters: Float): Float =
      (PRIVATE_LAYER_PANEL_SCALE * (distanceMeters / PRIVATE_LAYER_PANEL_DISTANCE_METERS))
          .coerceIn(PRIVATE_LAYER_PANEL_SCALE_MIN, PANEL_HEADLOCK_SCALE_MAX)

  private fun cameraHwbProjectionInputCarrierBehindPrivatePanel(
      placementMode: CameraHwbProjectionPlacementMode = cameraHwbProjectionPlacementMode,
      targetDistanceMeters: Float = currentCameraHwbProjectionTargetDistanceMeters(),
  ): Boolean =
      cameraHwbProjectionPrivatePanelInputClearanceActive(placementMode) &&
          targetDistanceMeters > privateLayerPanelPlacement.zMeters

  private fun cameraHwbProjectionVirtualRoomForegroundDistanceActive(
      placementMode: CameraHwbProjectionPlacementMode = cameraHwbProjectionPlacementMode,
  ): Boolean =
      CameraHwbProjectionModule.virtualRoomForegroundDistanceActive(
          placementMode,
          spatialVirtualRoomEnabled(),
          cameraHwbProjectionScenePanelCarrierEnabled(),
      )

  private fun cameraHwbProjectionWidthMeters(targetDistanceMeters: Float): Float =
      particleLayerProjectionWidthMeters(targetDistanceMeters)

  private fun cameraHwbProjectionHeightMeters(targetDistanceMeters: Float): Float =
      particleLayerProjectionHeightMeters(targetDistanceMeters)

  private fun currentCameraHwbProjectionStereoHorizontalOffsetUv(): Float =
      cameraHwbProjectionStereoHorizontalOffsetUv.coerceIn(
          CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV,
          CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV,
      )

  private fun initialCameraHwbProjectionTargetScale(): Float =
      activityReadFloatSystemProperty(
          CAMERA_HWB_PROJECTION_TARGET_SCALE_PROPERTY,
          CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT,
          CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
          CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
      )

  private fun initialPrivateLayerDepthLayerPolicy(): Int =
      PrivateLayerControls.depthLayerPolicyForToken(
          activityReadSystemProperty(CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY)
      ) ?: PrivateLayerControls.defaultDepthLayerPolicy

  private fun currentCameraHwbProjectionTargetScale(): Float =
      cameraHwbProjectionTargetScale.coerceIn(
          CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
          CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
      )

  private fun currentCameraHwbProjectionTargetScaleJoystickRate(): Float =
      activityReadFloatSystemProperty(
          CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY,
          CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PER_SECOND,
          0.02f,
          1.25f,
      )

  private fun cameraHwbProjectionLeftEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.leftEffectiveTargetRectMarker(
          currentCameraHwbProjectionTargetScale(),
          currentCameraHwbProjectionStereoHorizontalOffsetUv(),
      )

  private fun cameraHwbProjectionRightEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.rightEffectiveTargetRectMarker(
          currentCameraHwbProjectionTargetScale(),
          currentCameraHwbProjectionStereoHorizontalOffsetUv(),
      )

  private fun cameraHwbProjectionLeftPackedEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.leftPackedEffectiveTargetRectMarker(
          currentCameraHwbProjectionTargetScale(),
          currentCameraHwbProjectionStereoHorizontalOffsetUv(),
      )

  private fun cameraHwbProjectionRightPackedEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.rightPackedEffectiveTargetRectMarker(
          currentCameraHwbProjectionTargetScale(),
          currentCameraHwbProjectionStereoHorizontalOffsetUv(),
      )

  private fun currentSpatialVrInputSystemToken(): String =
      SpatialControllerRoutingModule.spatialVrInputSystemToken(
          activityReadSystemProperty(SPATIAL_VR_INPUT_SYSTEM_PROPERTY)
      )

  private fun currentSpatialVrInputSystemType(): VrInputSystemType =
      SpatialControllerRoutingModule.spatialVrInputSystemType(currentSpatialVrInputSystemToken())

  private fun currentSpatialShouldConsumeLeftRightInput(): Boolean =
      SpatialControllerRoutingModule.shouldConsumeLeftRightInput(
          activityReadOptionalBooleanSystemProperty(SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY)
      )

  private fun panelHeadlockMarkerFields(): String {
    return SpatialPanelPlacementModule.headlockMarkerFields(
        SpatialPanelHeadlockMarkerInput(
            activePlacement = activeHeadlockedPanelPlacement(),
            privateLayerPanelVisible = privateLayerPanelVisible,
            cameraTargetDistanceMeters = currentCameraHwbProjectionTargetDistanceMeters(),
            projectionInputClearanceActive = cameraHwbProjectionPrivatePanelInputClearanceActive(),
            projectionInputCarrierBehindPrivatePanel = cameraHwbProjectionInputCarrierBehindPrivatePanel(),
            cameraProjectionLayerZIndex =
                cameraHwbProjectionZIndexForPlacement(cameraHwbProjectionPlacementMode),
        )
    )
  }

  private fun panelHeadlockPropertyMarkerFields(): String =
      SpatialPanelPlacementModule.headlockPropertyMarkerFields()

  private fun applyCameraHwbProjectionScaleJoystickInput(event: MotionEvent): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE || !isJoystickEvent(event)) {
      return false
    }
    if (privateLayerPanelVisible) {
      return false
    }
    if (!cameraHwbProjectionProbeStarted || cameraHwbProjectionEntity == null) {
      return false
    }

    val rightY = joystickAxis(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ)
    return applyCameraHwbProjectionScaleInput(
        rightY = rightY,
        inputSource = "android-generic-motion-joystick",
        controllerJoystickMapping = "right-stick-y-projection-target-scale",
        detail = "rightStickY=${activityMarkerFloat(rightY)}",
    )
  }

  private fun applyCameraHwbProjectionScaleInput(
      rightY: Float,
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
  ): Boolean {
    if (privateLayerPanelVisible) {
      return false
    }
    if (!cameraHwbProjectionProbeStarted || cameraHwbProjectionEntity == null) {
      return false
    }
    if (abs(rightY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      return false
    }

    val now = SystemClock.elapsedRealtime()
    val dtSeconds =
        if (lastCameraHwbProjectionScaleJoystickMs <= 0L) {
          1.0f / 60.0f
        } else {
          ((now - lastCameraHwbProjectionScaleJoystickMs).toFloat() / 1000.0f)
              .coerceIn(0.0f, 0.08f)
        }
    lastCameraHwbProjectionScaleJoystickMs = now
    val scaleRate = currentCameraHwbProjectionTargetScaleJoystickRate()
    val previousScale = currentCameraHwbProjectionTargetScale()
    val signedInput =
        if (rightY > 0.0f) {
          rightY - PANEL_HEADLOCK_JOYSTICK_DEADZONE
        } else {
          rightY + PANEL_HEADLOCK_JOYSTICK_DEADZONE
        }
    val updatedScale =
        (previousScale + signedInput * scaleRate * dtSeconds)
            .coerceIn(
                CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
                CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
            )
    if (abs(updatedScale - previousScale) < 0.00001f) {
      return false
    }
    cameraHwbProjectionTargetScale = updatedScale
    updateNativeCameraHwbProjectionTargetScale(
        reason = "right-stick-projection-target-scale",
        forceLog = false,
    )
    updateCameraHwbProjectionFromViewer(reason = "right-stick-projection-target-scale", forceLog = false)

    if (
        now - lastCameraHwbProjectionScaleJoystickMarkerMs >=
            CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_MARKER_INTERVAL_MS
    ) {
      lastCameraHwbProjectionScaleJoystickMarkerMs = now
      marker(
          CameraHwbProjectionModule.targetScaleJoystickAdjustedMarker(
              inputSource = inputSource,
              controllerJoystickMapping = controllerJoystickMapping,
              detail = detail,
              dtSeconds = dtSeconds,
              scaleRate = scaleRate,
              panelVisible = panelPlacement.visible,
              previousScale = previousScale,
              updatedScale = updatedScale,
              targetDistanceMeters = currentCameraHwbProjectionTargetDistanceMeters(),
              stereoHorizontalOffsetUv = currentCameraHwbProjectionStereoHorizontalOffsetUv(),
          )
      )
    }
    return true
  }

  private fun updateCameraHwbProjectionTargetScaleFromPanel(
      requestedScale: Float,
      source: String,
  ): Float {
    val previousScale = currentCameraHwbProjectionTargetScale()
    cameraHwbProjectionTargetScale =
        requestedScale.coerceIn(
            CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
            CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
        )
    val updatedScale = currentCameraHwbProjectionTargetScale()
    updateNativeCameraHwbProjectionTargetScale(reason = source, forceLog = false)
    updateCameraHwbProjectionFromViewer(reason = source, forceLog = false)
    marker(
        CameraHwbProjectionModule.targetScalePanelAdjustedMarker(
            source = source,
            previousScale = previousScale,
            updatedScale = updatedScale,
            stereoHorizontalOffsetUv = currentCameraHwbProjectionStereoHorizontalOffsetUv(),
        )
    )
    return updatedScale
  }

  private fun updatePrivateLayerOverrideFromPanel(
      requestedLayerOverride: Float,
      source: String,
  ): Float {
    val previousOverride = privateLayerOverride
    val updatedOverride =
        PrivateLayerPanelControlModule.normalizeLayerOverride(requestedLayerOverride)
    marker(
        PrivateLayerPanelControlModule.layerButtonSelectedMarker(
            source = source,
            requestedLayerOverride = requestedLayerOverride,
            previousOverride = previousOverride,
            updatedOverride = updatedOverride,
            placementMode = cameraHwbProjectionPlacementMode,
        )
    )
    privateLayerOverride = updatedOverride
    val updateMask =
        runCatching { nativeUpdatePrivateLayerOverride(updatedOverride) }
            .getOrElse { throwable ->
              marker(
                  PrivateLayerPanelControlModule.layerOverrideUpdateFailedMarker(
                      source = source,
                      requestedLayerOverride = requestedLayerOverride,
                      updatedOverride = updatedOverride,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    marker(
        PrivateLayerPanelControlModule.layerOverrideSubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousOverride = previousOverride,
            updatedOverride = updatedOverride,
            placementMode = cameraHwbProjectionPlacementMode,
            projectionTargetScale = currentCameraHwbProjectionTargetScale(),
        )
    )
    updateCameraHwbProjectionFromViewer(reason = "private-layer-override-panel", forceLog = true)
    return updatedOverride
  }

  private fun updatePrivateLayerDepthLayerPolicyFromPanel(
      requestedPolicy: Int,
      source: String,
  ): Int {
    val previousPolicy = privateLayerDepthLayerPolicy
    val updatedPolicy = PrivateLayerPanelControlModule.normalizeDepthLayerPolicy(requestedPolicy)
    privateLayerDepthLayerPolicy = updatedPolicy
    marker(
        PrivateLayerPanelControlModule.depthLayerPolicySelectedMarker(
            source = source,
            requestedPolicy = requestedPolicy,
            previousPolicy = previousPolicy,
            updatedPolicy = updatedPolicy,
        )
    )
    val updateMask =
        runCatching { nativeUpdatePrivateLayerDepthLayerPolicy(updatedPolicy) }
            .getOrElse { throwable ->
              marker(
                  PrivateLayerPanelControlModule.depthLayerPolicyUpdateFailedMarker(
                      source = source,
                      updatedPolicy = updatedPolicy,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    marker(
        PrivateLayerPanelControlModule.depthLayerPolicySubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousPolicy = previousPolicy,
            updatedPolicy = updatedPolicy,
        )
    )
    return updatedPolicy
  }

  private fun updatePrivateLayerDepthAlignmentFromPanel(
      requestedAlignment: PrivateLayerDepthAlignment,
      source: String,
  ): PrivateLayerDepthAlignment {
    val previousAlignment = privateLayerDepthAlignment
    val updatedAlignment = PrivateLayerPanelControlModule.coerceDepthAlignment(requestedAlignment)
    privateLayerDepthAlignment = updatedAlignment
    val updateMask =
        runCatching {
              nativeUpdatePrivateLayerDepthAlignment(
                  updatedAlignment.leftX,
                  updatedAlignment.leftY,
                  updatedAlignment.rightX,
                  updatedAlignment.rightY,
                  updatedAlignment.sampleScale,
              )
            }
            .getOrElse { throwable ->
              marker(
                  PrivateLayerPanelControlModule.depthAlignmentUpdateFailedMarker(
                      source = source,
                      updatedAlignment = updatedAlignment,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    marker(
        PrivateLayerPanelControlModule.depthAlignmentSubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousAlignment = previousAlignment,
            updatedAlignment = updatedAlignment,
        )
    )
    return updatedAlignment
  }

  private fun updateNativeCameraHwbProjectionStereoOffset(reason: String, forceLog: Boolean) {
    val stereoOffsetUv = currentCameraHwbProjectionStereoHorizontalOffsetUv()
    val updateMask =
        runCatching { nativeUpdateCameraHwbProjectionStereoOffsetUv(stereoOffsetUv) }
            .getOrElse { throwable ->
              if (forceLog) {
                marker(
                    CameraHwbProjectionModule.targetStereoHorizontalOffsetUpdateFailedMarker(
                        reason = reason,
                        stereoOffsetUv = stereoOffsetUv,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
              0L
            }
    if (forceLog) {
      marker(
          CameraHwbProjectionModule.targetStereoHorizontalOffsetNativeUpdatedMarker(
              reason = reason,
              updateMask = updateMask,
              targetScale = currentCameraHwbProjectionTargetScale(),
              stereoOffsetUv = stereoOffsetUv,
          )
      )
    }
  }

  private fun updateNativeCameraHwbProjectionTargetScale(reason: String, forceLog: Boolean) {
    val targetScale = currentCameraHwbProjectionTargetScale()
    val updateMask =
        runCatching { nativeUpdateCameraHwbProjectionTargetScale(targetScale) }
            .getOrElse { throwable ->
              if (forceLog) {
                marker(
                    CameraHwbProjectionModule.targetScaleUpdateFailedMarker(
                        reason = reason,
                        targetScale = targetScale,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
              0L
            }
    if (forceLog) {
      marker(
          CameraHwbProjectionModule.targetScaleNativeUpdatedMarker(
              reason = reason,
              updateMask = updateMask,
              targetScale = targetScale,
              stereoHorizontalOffsetUv = currentCameraHwbProjectionStereoHorizontalOffsetUv(),
          )
      )
    }
  }

  private fun handleSpatialJoystickMotion(event: MotionEvent, inputSource: String): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE || !isJoystickEvent(event)) {
      return false
    }

    val leftX = joystickAxis(event, MotionEvent.AXIS_X)
    val leftY = joystickAxis(event, MotionEvent.AXIS_Y)
    val rightX = joystickAxis(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z)
    val rightY = joystickAxis(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ)
    val observed =
        abs(leftX) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(rightX) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE
    if (!observed) {
      return false
    }

    val projectionScaleHandled = applyCameraHwbProjectionScaleJoystickInput(event)
    val panelPlacementHandled =
        if (projectionScaleHandled) {
          false
        } else {
          applyPanelHeadlockJoystickInput(event, inputSource)
        }
    val rightStickObserved =
        abs(rightX) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE
    val leftDistanceObserved = abs(leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE
    val rightStickSwallowedAsIgnored =
        rightStickObserved && !projectionScaleHandled && !panelPlacementHandled && !leftDistanceObserved
    val consumed = projectionScaleHandled || panelPlacementHandled || rightStickSwallowedAsIgnored
    val leftStickPanelDistanceEnabled = currentLeftStickPanelDistanceEnabled()
    val leftStickYDeliveredToPanelScroll =
        leftDistanceObserved && privateLayerPanelVisible && !leftStickPanelDistanceEnabled && !consumed
    val now = SystemClock.elapsedRealtime()
    if (
        now - lastSpatialJoystickArbitrationMarkerMs >=
            SPATIAL_JOYSTICK_ARBITRATION_MARKER_INTERVAL_MS
    ) {
      lastSpatialJoystickArbitrationMarkerMs = now
      marker(
          SpatialControllerRoutingModule.joystickArbitrationMarker(
              SpatialJoystickArbitrationMarkerInput(
                  inputSource = inputSource,
                  leftX = leftX,
                  leftY = leftY,
                  rightX = rightX,
                  rightY = rightY,
                  projectionScaleHandled = projectionScaleHandled,
                  panelPlacementHandled = panelPlacementHandled,
                  rightStickSwallowedAsIgnored = rightStickSwallowedAsIgnored,
                  leftStickYDeliveredToPanelScroll = leftStickYDeliveredToPanelScroll,
                  leftStickYPanelDistanceObserved = leftDistanceObserved,
                  consumedByActivity = consumed,
                  leftStickYPanelDistanceEnabled = leftStickPanelDistanceEnabled,
                  privateLayerPanelVisible = privateLayerPanelVisible,
                  panelMode = panelStateToken(),
                  projectionTargetLiveScale = currentCameraHwbProjectionTargetScale(),
                  headlockMarkerFields = panelHeadlockMarkerFields(),
              )
          )
      )
    }
    return consumed
  }

  private fun applyPanelHeadlockJoystickInput(event: MotionEvent, inputSource: String): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE || !isJoystickEvent(event)) {
      return false
    }
    val placement = activeHeadlockedPanelPlacement()
    val privateFreeTransformDistance =
        privateLayerPanelVisible && PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM
    if ((!panelPlacement.visible && !privateLayerPanelVisible) || !currentPanelHeadlockJoystickEnabled()) {
      return false
    }
    if (!privateFreeTransformDistance && !placement.headlocked) {
      return false
    }

    val leftX = joystickAxis(event, MotionEvent.AXIS_X)
    val leftY = joystickAxis(event, MotionEvent.AXIS_Y)
    val rightX = joystickAxis(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z)
    val rightY = joystickAxis(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ)
    return applyPanelHeadlockDistanceInput(
        leftY = leftY,
        inputSource = inputSource,
        controllerJoystickMapping = currentLeftStickPanelDistanceMapping(),
        detail =
            "leftStick=${activityMarkerFloat(leftX)};${activityMarkerFloat(leftY)} " +
                "rightStick=${activityMarkerFloat(rightX)};${activityMarkerFloat(rightY)} " +
                "rightStickXIgnored=true rightStickYPanelDistanceDisabled=true " +
                "rightStickXPanelScaleDisabled=true",
    )
  }

  private fun applyPanelHeadlockDistanceInput(
      leftY: Float,
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
  ): Boolean {
    if (privateLayerPanelVisible && PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) {
      return applyPrivateLayerPanelFreeTransformDistanceInput(leftY, inputSource, detail)
    }
    if (privateLayerPanelVisible) {
      syncPrivateLayerPanelPlacementFromEntity("controller-joystick-distance")
    }
    val placement = activeHeadlockedPanelPlacement()
    if (
        (!panelPlacement.visible && !privateLayerPanelVisible) ||
            !placement.headlocked ||
            !currentPanelHeadlockJoystickEnabled()
    ) {
      return false
    }
    if (abs(leftY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      return false
    }

    val now = SystemClock.elapsedRealtime()
    val dtSeconds =
        if (lastPanelHeadlockJoystickMs <= 0L) {
          1.0f / 60.0f
        } else {
          ((now - lastPanelHeadlockJoystickMs).toFloat() / 1000.0f).coerceIn(0.0f, 0.08f)
        }
    lastPanelHeadlockJoystickMs = now
    val distanceRate =
        activityReadFloatSystemProperty(
            PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY,
            PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND,
            0.02f,
            0.80f,
        )
    val previousDistance = placement.zMeters
    val signedInput =
        if (leftY > 0.0f) {
          leftY - PANEL_HEADLOCK_JOYSTICK_DEADZONE
        } else {
          leftY + PANEL_HEADLOCK_JOYSTICK_DEADZONE
        }
    val updatedDistance =
        (previousDistance - signedInput * distanceRate * dtSeconds)
            .coerceIn(
                if (privateLayerPanelVisible) PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS
                else PANEL_HEADLOCK_DISTANCE_MIN_METERS,
                PANEL_HEADLOCK_DISTANCE_MAX_METERS,
            )
    if (abs(updatedDistance - previousDistance) < 0.00001f) {
      return true
    }
    if (privateLayerPanelVisible) {
      privateLayerPanelPlacement =
          coercePrivateLayerPanelPlacement(privateLayerPanelPlacement.copy(zMeters = updatedDistance))
    } else {
      panelPlacement = panelPlacement.copy(zMeters = updatedDistance)
    }
    applyPanelPlacement(updatePrivateLayerPanelTransform = privateLayerPanelVisible)
    persistPanelHeadlockTuning("controller-joystick-distance")
    if (now - lastPanelHeadlockJoystickMarkerMs >= PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS) {
      lastPanelHeadlockJoystickMarkerMs = now
      marker(
          SpatialControllerRoutingModule.headlockDistanceJoystickAdjustedMarker(
              inputSource = inputSource,
              controllerJoystickMapping = controllerJoystickMapping,
              detail = detail,
              leftY = leftY,
              dtSeconds = dtSeconds,
              distanceRate = distanceRate,
              previousDistance = previousDistance,
              leftStickYPanelDistanceEnabled = currentLeftStickPanelDistanceEnabled(),
              panelDistanceControl = currentLeftStickPanelDistanceMapping(),
              headlockMarkerFields = panelHeadlockMarkerFields(),
          )
      )
    }
    return true
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun applyPrivateLayerPanelFreeTransformDistanceInput(
      leftY: Float,
      inputSource: String,
      detail: String,
  ): Boolean {
    if (!privateLayerPanelVisible || !currentPanelHeadlockJoystickEnabled()) {
      return false
    }
    if (abs(leftY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      return false
    }
    if (privateLayerPanelIsGrabbed()) {
      val now = SystemClock.elapsedRealtime()
      if (now - lastPanelHeadlockJoystickMarkerMs >= PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS) {
        lastPanelHeadlockJoystickMarkerMs = now
        marker(
            SpatialControllerRoutingModule.privateLayerFreeTransformDistanceJoystickSkippedMarker(
                inputSource = inputSource,
                detail = detail,
                leftY = leftY,
                headlockMarkerFields = panelHeadlockMarkerFields(),
            )
        )
      }
      return true
    }

    val entity = privateLayerPanelEntity ?: return false
    val previousDistance =
        privateLayerPanelPlacement.zMeters
            .coerceIn(PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val now = SystemClock.elapsedRealtime()
    val dtSeconds =
        if (lastPanelHeadlockJoystickMs <= 0L) {
          1.0f / 60.0f
        } else {
          ((now - lastPanelHeadlockJoystickMs).toFloat() / 1000.0f).coerceIn(0.0f, 0.08f)
        }
    lastPanelHeadlockJoystickMs = now
    val distanceRate =
        activityReadFloatSystemProperty(
            PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY,
            PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND,
            0.02f,
            0.80f,
        )
    val signedInput =
        if (leftY > 0.0f) {
          leftY - PANEL_HEADLOCK_JOYSTICK_DEADZONE
        } else {
          leftY + PANEL_HEADLOCK_JOYSTICK_DEADZONE
        }
    val updatedDistance =
        (previousDistance - signedInput * distanceRate * dtSeconds)
            .coerceIn(PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    if (abs(updatedDistance - previousDistance) < 0.00001f) {
      return true
    }
    privateLayerPanelPlacement =
        coercePrivateLayerPanelPlacement(
            privateLayerPanelPlacement.copy(
                visible = true,
                headlocked = false,
                zMeters = updatedDistance,
            )
        )
    val updatedPose = privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
    entity.setComponent(Transform(updatedPose))
    persistPanelHeadlockTuning("controller-joystick-private-free-transform-distance")
    if (now - lastPanelHeadlockJoystickMarkerMs >= PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS) {
      lastPanelHeadlockJoystickMarkerMs = now
      marker(
          SpatialControllerRoutingModule.privateLayerFreeTransformDistanceJoystickAdjustedMarker(
              inputSource = inputSource,
              detail = detail,
              leftY = leftY,
              dtSeconds = dtSeconds,
              distanceRate = distanceRate,
              previousDistance = previousDistance,
              updatedDistance = updatedDistance,
              leftStickYPanelDistanceEnabled = currentLeftStickPanelDistanceEnabled(),
              headlockMarkerFields = panelHeadlockMarkerFields(),
          )
      )
    }
    return true
  }

  private fun enableSpatialControllerInputRoute(reason: String, forceLog: Boolean) {
    val now = SystemClock.elapsedRealtime()
    val enableResult =
        runCatching {
              scene.spatialInterface.enableInput(true)
              true
            }
            .getOrElse { false }
    var newlyPinned = 0
    val gameControllerIds = getGameControllerDeviceIds().toList()
        gameControllerIds.forEach { deviceId ->
      if (pinnedSpatialGameControllerIds.add(deviceId)) {
        pinGameController(deviceId) { motionEvent: MotionEvent?, keyEvent: KeyEvent? ->
          keyEvent?.let {
            if (!handleControllerSecondaryButton(it) && !handleControllerTrigger(it)) {
              handleControllerPrimaryButton(it)
            }
          }
          motionEvent?.let { event ->
            if (
                !handleControllerSecondaryButton(event) &&
                    !handleControllerTrigger(event) &&
                    !handleControllerPrimaryButton(event)
            ) {
              handleSpatialJoystickMotion(event, "pinned-android-game-controller")
            }
          }
        }
        newlyPinned += 1
      }
    }

    if (
        forceLog ||
            newlyPinned > 0 ||
            now - lastSpatialInputRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
    ) {
      lastSpatialInputRouteMarkerMs = now
      marker(
          SpatialControllerRoutingModule.spatialInputEnabledMarker(
              reason = reason,
              spatialInterfaceEnableInput = enableResult,
              gameControllerDeviceCount = gameControllerIds.size,
              pinnedGameControllerCount = pinnedSpatialGameControllerIds.size,
              newlyPinnedGameControllerCount = newlyPinned,
          )
      )
    }
  }

  private fun pollNativeSpatialControllerProjectionScaleInput() {
    if (
        !nativeSpatialControllerActionsEnabled() ||
            !nativeReceiptLibraryLoaded ||
            !nativeSpatialControllerActionsStarted
    ) {
      return
    }
    val leftY =
        runCatching { nativePollSpatialControllerLeftThumbstickY() }
            .getOrElse { throwable ->
              nativeSpatialControllerActionsStarted = false
              marker(
                  SpatialControllerRoutingModule.nativeControllerActionPollErrorMarker(
                      controllerInput = "left-thumbstick-y",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              Float.NaN
            }
    if (leftY.isFinite() && abs(leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      applyPanelHeadlockDistanceInput(
          leftY = leftY,
          inputSource = "native-openxr-action",
          controllerJoystickMapping = currentLeftStickPanelDistanceMapping(),
          detail =
              "leftThumbstickY=${activityMarkerFloat(leftY)} " +
                  "nativeControllerActionStartMask=$nativeSpatialControllerActionsStartMask",
      )
    }

    val rightY =
        runCatching { nativePollSpatialControllerRightThumbstickY() }
            .getOrElse { throwable ->
              nativeSpatialControllerActionsStarted = false
              marker(
                  SpatialControllerRoutingModule.nativeControllerActionPollErrorMarker(
                      controllerInput = "right-thumbstick-y",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              Float.NaN
            }
    if (rightY.isFinite() && abs(rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      applyCameraHwbProjectionScaleInput(
          rightY = rightY,
          inputSource = "native-openxr-action",
          controllerJoystickMapping = "right-thumbstick-y-projection-target-scale",
          detail =
              "rightThumbstickY=${activityMarkerFloat(rightY)} " +
                  "nativeControllerActionStartMask=$nativeSpatialControllerActionsStartMask",
      )
    }

    val rightButtonBDown =
        runCatching { nativePollSpatialControllerRightButtonB() }
            .getOrElse { throwable ->
              nativeSpatialControllerActionsStarted = false
              nativeControllerSecondaryDown = false
              marker(
                  SpatialControllerRoutingModule.nativeControllerActionPollErrorMarker(
                      controllerInput = "right-button-b",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              false
            }
    val rightButtonBPressedEdge = rightButtonBDown && !nativeControllerSecondaryDown
    nativeControllerSecondaryDown = rightButtonBDown
    if (!rightButtonBDown) {
      armCameraHwbProjectionSecondaryToggle("native-openxr-action")
    }
    if (rightButtonBPressedEdge) {
      toggleCameraHwbProjectionPlacementMode(
          inputSource = "native-openxr-action",
          detail =
              "rightButtonBDown=true nativeRightButtonBAction=true " +
                  "nativeControllerActionStartMask=$nativeSpatialControllerActionsStartMask",
      )
    }
  }

  private fun pollSpatialControllerInput() {
    val now = SystemClock.elapsedRealtime()
    val snapshot =
        runCatching {
              SpatialControllerSnapshotAdapter.capture(scene)
            }
            .getOrElse { throwable ->
              spatialControllerPrimaryDown = false
              spatialControllerSecondaryDown = false
              spatialControllerRightTriggerDown = false
              if (
                  !spatialControllerRouteLogged ||
                      now - lastSpatialControllerRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
              ) {
                spatialControllerRouteLogged = true
                lastSpatialControllerRouteMarkerMs = now
                marker(
                    SpatialControllerRoutingModule.controllerInputRouteErrorMarker(
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
              return
            }

    val shouldLogRoute =
        !spatialControllerRouteLogged ||
            snapshot.componentCount != lastSpatialControllerComponentCount ||
            snapshot.activeCount != lastSpatialControllerActiveCount ||
            snapshot.controllerTypeCount != lastSpatialControllerControllerTypeCount ||
            snapshot.allControllerButtonState != lastSpatialControllerAllButtonState ||
            now - lastSpatialControllerRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
    if (shouldLogRoute) {
      spatialControllerRouteLogged = true
      lastSpatialControllerRouteMarkerMs = now
      lastSpatialControllerComponentCount = snapshot.componentCount
      lastSpatialControllerActiveCount = snapshot.activeCount
      lastSpatialControllerControllerTypeCount = snapshot.controllerTypeCount
      lastSpatialControllerAllButtonState = snapshot.allControllerButtonState
      marker(
          SpatialControllerRoutingModule.controllerInputRouteReadyMarker(
              snapshot = snapshot,
              leftStickPanelDistanceMapping = currentLeftStickPanelDistanceMapping(),
              leftStickYPanelDistanceEnabled = currentLeftStickPanelDistanceEnabled(),
              spatialVrInputSystem = currentSpatialVrInputSystemToken(),
          )
      )
    }

    if (snapshot.rightThumbY != 0.0f) {
      applyCameraHwbProjectionScaleInput(
          rightY = snapshot.rightThumbY,
          inputSource = snapshot.rightInputSource,
          controllerJoystickMapping = "right-thumb-up-down-projection-target-scale",
          detail = SpatialControllerRoutingModule.rightThumbProjectionScaleDetail(snapshot),
      )
    }
    if (snapshot.leftThumbY != 0.0f) {
      applyPanelHeadlockDistanceInput(
          leftY = snapshot.leftThumbY,
          inputSource = "spatial-sdk-avatar-body-controller",
          controllerJoystickMapping = currentLeftStickPanelDistanceMapping(),
          detail = SpatialControllerRoutingModule.leftThumbPanelDistanceDetail(snapshot),
      )
    }

    val triggerPressedEdge =
        snapshot.triggerPressed || (snapshot.triggerDown && !spatialControllerRightTriggerDown)
    spatialControllerRightTriggerDown = snapshot.triggerDown
    if (triggerPressedEdge) {
      if (
          recenterSurfaceParticleSphereOnViewer(
              inputSource = snapshot.rightInputSource,
              detail = SpatialControllerRoutingModule.rightTriggerParticleRecenterDetail(snapshot),
              requireParticleView = true,
          )
      ) {
        return
      }
    }

    val secondaryPressedEdge =
        snapshot.secondaryPressed || (snapshot.secondaryDown && !spatialControllerSecondaryDown)
    spatialControllerSecondaryDown = snapshot.secondaryDown
    if (!snapshot.secondaryDown) {
      armCameraHwbProjectionSecondaryToggle(snapshot.rightInputSource)
    }
    if (secondaryPressedEdge) {
      toggleCameraHwbProjectionPlacementMode(
          inputSource = snapshot.rightInputSource,
          detail = SpatialControllerRoutingModule.rightSecondaryPlacementToggleDetail(snapshot),
      )
      return
    }

    val pressedEdge = snapshot.pressed || (snapshot.down && !spatialControllerPrimaryDown)
    spatialControllerPrimaryDown = snapshot.down
    if (!pressedEdge) {
      return
    }
    openWorkflowPanelFromController(
        inputSource = snapshot.rightInputSource,
        detail = SpatialControllerRoutingModule.rightPrimaryPanelToggleDetail(snapshot),
    )
  }

  private fun handleControllerSecondaryButton(event: KeyEvent): Boolean {
    val rightSecondary =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_2
    if (!rightSecondary) {
      return false
    }
    val pressedEdge =
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            val firstDown = !androidControllerSecondaryKeyDown && event.repeatCount == 0
            androidControllerSecondaryKeyDown = true
            firstDown
          }
          KeyEvent.ACTION_UP -> {
            androidControllerSecondaryKeyDown = false
            armCameraHwbProjectionSecondaryToggle("android-key-event")
            false
          }
          else -> false
        }
    if (!pressedEdge) {
      return false
    }
    return toggleCameraHwbProjectionPlacementMode(
        inputSource = "android-key-event",
        detail = "keyCode=${event.keyCode} keyAction=${event.action} repeatCount=${event.repeatCount}",
    )
  }

  private fun handleControllerSecondaryButton(event: MotionEvent): Boolean {
    if (!isJoystickEvent(event)) {
      return false
    }
    val action = event.actionMasked
    if (
        action != MotionEvent.ACTION_BUTTON_PRESS &&
            action != MotionEvent.ACTION_BUTTON_RELEASE &&
            action != MotionEvent.ACTION_MOVE
    ) {
      return false
    }
    val secondaryDown = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
    val pressedEdge = secondaryDown && !androidControllerSecondaryMotionDown
    androidControllerSecondaryMotionDown = secondaryDown
    if (!secondaryDown) {
      armCameraHwbProjectionSecondaryToggle("android-generic-motion-button")
    }
    if (!pressedEdge) {
      return false
    }
    return toggleCameraHwbProjectionPlacementMode(
        inputSource = "android-generic-motion-button",
        detail =
            "motionAction=$action motionButtonState=${event.buttonState} " +
                "motionButtonBit=${MotionEvent.BUTTON_SECONDARY}",
    )
  }

  private fun handleControllerTrigger(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_BUTTON_R2) {
      return false
    }
    val pressedEdge =
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            val firstDown = !androidControllerRightTriggerKeyDown && event.repeatCount == 0
            androidControllerRightTriggerKeyDown = true
            firstDown
          }
          KeyEvent.ACTION_UP -> {
            androidControllerRightTriggerKeyDown = false
            false
          }
          else -> false
        }
    if (!pressedEdge) {
      return false
    }
    return recenterSurfaceParticleSphereOnViewer(
        inputSource = "android-key-event",
        detail = "keyCode=${event.keyCode} keyAction=${event.action} repeatCount=${event.repeatCount}",
        requireParticleView = true,
    )
  }

  private fun handleControllerTrigger(event: MotionEvent): Boolean {
    if (!isJoystickEvent(event)) {
      return false
    }
    val action = event.actionMasked
    if (
        action != MotionEvent.ACTION_BUTTON_PRESS &&
            action != MotionEvent.ACTION_BUTTON_RELEASE &&
            action != MotionEvent.ACTION_MOVE
    ) {
      return false
    }
    val rightTriggerValue =
        maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
    val triggerDown = rightTriggerValue >= CONTROLLER_TRIGGER_PRESS_THRESHOLD
    val pressedEdge = triggerDown && !androidControllerRightTriggerMotionDown
    androidControllerRightTriggerMotionDown = triggerDown
    if (!pressedEdge) {
      return false
    }
    return recenterSurfaceParticleSphereOnViewer(
        inputSource = "android-generic-motion-trigger",
        detail =
            "motionAction=$action motionButtonState=${event.buttonState} " +
                "rightTriggerAxis=${activityMarkerFloat(rightTriggerValue)} " +
                "rightTriggerThreshold=${activityMarkerFloat(CONTROLLER_TRIGGER_PRESS_THRESHOLD)}",
        requireParticleView = true,
    )
  }

  private fun recenterSurfaceParticleSphereOnViewer(
      inputSource: String,
      detail: String,
      requireParticleView: Boolean,
  ): Boolean {
    val surfaceTargetId = store.snapshot().surfaceTargetId
    val particleViewVisible = particleLayerVisibleForPanelMode()
    if (surfaceTargetId != "icosphere" || (requireParticleView && !particleViewVisible)) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterIgnoredMarker(
              inputSource = inputSource,
              detail = detail,
              surfaceTargetId = surfaceTargetId,
              particleLayerVisible = particleViewVisible,
              requireParticleView = requireParticleView,
              workflowPanelVisible = panelPlacement.visible,
              privateLayerPanelVisible = privateLayerPanelVisible,
          )
      )
      return false
    }
    if (!nativeReceiptLibraryLoaded) {
      marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterNativeUnavailableMarker(
              inputSource = inputSource,
              detail = detail,
              surfaceTargetId = surfaceTargetId,
          )
      )
      return true
    }
    return runCatching {
          val mask = nativeRecenterSurfaceParticleSphereOnViewer()
          val accepted = (mask and SURFACE_PARTICLE_RECENTER_ACCEPTED_BIT) != 0L
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterRequestedMarker(
                  inputSource = inputSource,
                  detail = detail,
                  surfaceTargetId = surfaceTargetId,
                  particleLayerVisible = particleViewVisible,
                  requireParticleView = requireParticleView,
                  nativeRecenterMask = mask,
                  nativeRecenterAccepted = accepted,
              )
          )
          true
        }
        .getOrElse { throwable ->
          marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterFailedMarker(
                  inputSource = inputSource,
                  detail = detail,
                  surfaceTargetId = surfaceTargetId,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
          true
        }
  }

  private fun toggleCameraHwbProjectionPlacementMode(inputSource: String, detail: String): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (!cameraHwbProjectionSecondaryToggleEnabled()) {
      marker(
          CameraHwbProjectionModule.projectionPlacementToggleDisabledMarker(
              inputSource = inputSource,
              detail = detail,
              placementMode = cameraHwbProjectionPlacementMode,
          )
      )
      return true
    }
    if (!cameraHwbProjectionSecondaryToggleArmed) {
      marker(
          CameraHwbProjectionModule.projectionPlacementToggleNotArmedMarker(
              inputSource = inputSource,
              detail = detail,
              placementMode = cameraHwbProjectionPlacementMode,
          )
      )
      return true
    }
    if (
        lastCameraHwbProjectionPlacementToggleMs > 0L &&
            now - lastCameraHwbProjectionPlacementToggleMs <
                CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS
    ) {
      marker(
          CameraHwbProjectionModule.projectionPlacementToggleDebouncedMarker(
              inputSource = inputSource,
              detail = detail,
              placementMode = cameraHwbProjectionPlacementMode,
          )
      )
      return true
    }
    lastCameraHwbProjectionPlacementToggleMs = now
    val previous = cameraHwbProjectionPlacementMode
    cameraHwbProjectionPlacementMode =
        when (previous) {
          CameraHwbProjectionPlacementMode.ViewerLocked ->
              CameraHwbProjectionPlacementMode.VirtualRoomWall
          CameraHwbProjectionPlacementMode.VirtualRoomWall ->
              CameraHwbProjectionPlacementMode.ViewerLocked
        }
    cameraHwbProjectionMarkerCount = 0
    updateCameraHwbProjectionFromViewer(reason = "controller-secondary-toggle", forceLog = true)
    val layerOverrideReapplyMask =
        if (nativeReceiptLibraryLoaded) {
          runCatching { nativeUpdatePrivateLayerOverride(privateLayerOverride) }
              .getOrElse { throwable ->
                marker(
                    SpatialPanelPlacementModule.privateLayerPanelLayerOverrideReapplyFailedMarker(
                        placementMode = cameraHwbProjectionPlacementMode,
                        privateLayerOverride = privateLayerOverride,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
                0L
              }
        } else {
          0L
        }
    marker(
        CameraHwbProjectionModule.projectionPlacementToggledMarker(
            inputSource = inputSource,
            detail = detail,
            previousPlacementMode = previous,
            placementMode = cameraHwbProjectionPlacementMode,
            projectionEntityPresent = cameraHwbProjectionEntity != null,
            carrierMode = cameraHwbProjectionCarrierMode,
            roomRenderOrderToken = cameraHwbProjectionRoomRenderOrderToken(),
            layerOverrideReapplied = nativeReceiptLibraryLoaded && layerOverrideReapplyMask != 0L,
            layerOverrideUpdateMask = layerOverrideReapplyMask,
            layerOverride = privateLayerOverride,
        )
    )
    return true
  }

  private fun cameraHwbProjectionSecondaryToggleEnabled(): Boolean = false

  private fun armCameraHwbProjectionSecondaryToggle(inputSource: String) {
    if (!cameraHwbProjectionProbeStarted || cameraHwbProjectionSecondaryToggleArmed) {
      return
    }
    cameraHwbProjectionSecondaryToggleArmed = true
    marker(CameraHwbProjectionModule.projectionPlacementToggleArmedMarker(inputSource))
  }

  private fun handleControllerPrimaryButton(event: KeyEvent): Boolean {
    val rightPrimary =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_1
    if (!rightPrimary) {
      return false
    }
    val pressedEdge =
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            val firstDown = !androidControllerPrimaryKeyDown && event.repeatCount == 0
            androidControllerPrimaryKeyDown = true
            firstDown
          }
          KeyEvent.ACTION_UP -> {
            val releaseWithoutSeenDown = !androidControllerPrimaryKeyDown
            androidControllerPrimaryKeyDown = false
            releaseWithoutSeenDown
          }
          else -> false
        }
    if (!pressedEdge) {
      return false
    }
    return openWorkflowPanelFromController(
        inputSource = "android-key-event",
        detail = "keyCode=${event.keyCode} keyAction=${event.action} repeatCount=${event.repeatCount}",
    )
  }

  private fun handleControllerPrimaryButton(event: MotionEvent): Boolean {
    if (!isJoystickEvent(event)) {
      return false
    }
    val action = event.actionMasked
    if (
        action != MotionEvent.ACTION_BUTTON_PRESS &&
            action != MotionEvent.ACTION_BUTTON_RELEASE &&
            action != MotionEvent.ACTION_MOVE
    ) {
      return false
    }
    val primaryDown = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
    val pressedEdge = primaryDown && !androidControllerPrimaryMotionDown
    androidControllerPrimaryMotionDown = primaryDown
    if (!pressedEdge) {
      return false
    }
    return openWorkflowPanelFromController(
        inputSource = "android-generic-motion-button",
        detail =
            "motionAction=$action motionButtonState=${event.buttonState} " +
                "motionButtonBit=${MotionEvent.BUTTON_PRIMARY}",
    )
  }

  private fun openWorkflowPanelFromController(inputSource: String, detail: String): Boolean {
    if (!SpatialControllerRoutingModule.isRightPrimaryPanelToggleSource(inputSource)) return false
    val opensPrivateLayerPanel =
        cameraStackSuppressesParticles || cameraHwbProjectionProbeStarted || spatialVideoProjectionStarted
    val panelToggleAction =
        SpatialControllerRoutingModule.panelToggleAction(
            privateLayerPanelVisible = privateLayerPanelVisible,
            workflowPanelVisible = panelPlacement.visible,
            opensPrivateLayerPanel = opensPrivateLayerPanel,
        )
    when (panelToggleAction) {
      SpatialControllerPanelToggleAction.ClosePrivateLayerPanel -> {
        setPrivateLayerPanelVisible(
            false,
            focus = false,
            source = "right-controller-primary-button-toggle-close",
        )
      }
      SpatialControllerPanelToggleAction.CloseWorkflowPanel -> {
        setWorkflowPanelVisible(
            false,
            focus = false,
            source = "right-controller-primary-button-toggle-close",
        )
      }
      SpatialControllerPanelToggleAction.OpenPrivateLayerPanel -> {
        setPrivateLayerPanelVisible(
            true,
            focus = true,
            source = "right-controller-primary-button",
        )
      }
      SpatialControllerPanelToggleAction.OpenWorkflowPanel -> {
        setWorkflowPanelVisible(true, focus = true, source = "right-controller-primary-button")
      }
    }
    marker(
        SpatialControllerRoutingModule.controllerPrimaryToggledPanelMarker(
            inputSource = inputSource,
            detail = detail,
            panelToggleAction = panelToggleAction,
            panelMode = panelStateToken(),
            workflowPanelVisible = panelPlacement.visible,
            privateLayerPanelVisible = privateLayerPanelVisible,
            opensPrivateLayerPanel = opensPrivateLayerPanel,
        )
    )
    return true
  }

  private fun isJoystickEvent(event: MotionEvent): Boolean =
      SpatialControllerRoutingModule.isJoystickEvent(event)

  private fun currentPanelHeadlockJoystickEnabled(): Boolean =
      SpatialControllerRoutingModule.panelHeadlockJoystickEnabled(
          activityReadOptionalBooleanSystemProperty(PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY)
      )

  private fun currentLeftStickPanelDistanceEnabled(): Boolean =
      SpatialControllerRoutingModule.leftStickPanelDistanceEnabled(
          joystickEnabled = currentPanelHeadlockJoystickEnabled(),
          privateLayerPanelVisible = privateLayerPanelVisible,
          privateLayerFreeTransform = PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM,
          privateLayerGrabbed = privateLayerPanelIsGrabbed(),
          privateLayerHeadlocked = privateLayerPanelPlacement.headlocked,
          workflowPanelVisible = panelPlacement.visible,
          workflowPanelHeadlocked = panelPlacement.headlocked,
      )

  private fun currentLeftStickPanelDistanceMapping(): String =
      SpatialControllerRoutingModule.leftStickPanelDistanceMapping(
          privateLayerPanelVisible = privateLayerPanelVisible,
          privateLayerFreeTransform = PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM,
      )

  private fun privateLayerPanelIsGrabbed(): Boolean =
      privateLayerPanelEntity?.tryGetComponent<Grabbable>()?.isGrabbed ?: false

  private fun joystickAxis(event: MotionEvent, primaryAxis: Int, fallbackAxis: Int? = null): Float {
    return SpatialControllerRoutingModule.joystickAxis(event, primaryAxis, fallbackAxis)
  }

  private fun spatialMultimodalInputEnabled(): Boolean =
      SpatialOpenXrRouteModule.spatialMultimodalInputEnabled(
          activityReadOptionalBooleanSystemProperty(SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY)
      )

  private fun nativeSpatialControllerActionsEnabled(): Boolean =
      SpatialControllerRoutingModule.nativeSpatialControllerActionsEnabled(
          activityReadOptionalBooleanSystemProperty(NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY)
      )

  private fun nativeSurfaceParticleLayerEnabled(): Boolean =
      SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLayerEnabled(
          activityReadOptionalBooleanSystemProperty(NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY),
          privateSpatialEcsParticleRendererEnabled(),
      )

  private fun nativeSurfaceParticleLayerSuppressedByPrivateRenderer(): Boolean =
      SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLayerSuppressedByPrivateRenderer(
          privateSpatialEcsParticleRendererEnabled()
      )

  private fun nativeSurfaceParticleLayerSuppressionSource(): String =
      SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLayerSuppressionSource(
          nativeSurfaceParticleLayerSuppressedByPrivateRenderer()
      )

  private fun privateSpatialEcsParticleRendererEnabled(): Boolean =
      SpatialSurfaceParticleRouteModule.privateSpatialEcsParticleRendererEnabled(
          activityReadOptionalBooleanSystemProperty(PRIVATE_SPATIAL_ECS_PARTICLE_RENDERER_ENABLED_PROPERTY)
      )

  private fun particleLayerCarrierMode(): SpatialSurfaceParticleCarrierMode =
      SpatialSurfaceParticleRouteModule.carrierMode(
          activityReadSystemProperty(PARTICLE_LAYER_CARRIER_PROPERTY),
          BuildConfig.PARTICLE_LAYER_CARRIER_DEFAULT,
      )

  private fun particleLayerManualCustomMeshCarrierEnabled(): Boolean =
      SpatialSurfaceParticleRouteModule.manualCustomMeshCarrierEnabled(particleLayerCarrierMode())

  private fun particleLayerCarrierToken(): String =
      SpatialSurfaceParticleRouteModule.carrierToken(particleLayerCarrierMode())

  private fun panelShellVisible(): Boolean =
      activityReadOptionalBooleanSystemProperty(PANEL_SHELL_VISIBLE_PROPERTY) ?: true

  private fun startInParticleView(): Boolean =
      SpatialSurfaceParticleRouteModule.startInParticleView(
          activityReadOptionalBooleanSystemProperty(PANEL_START_IN_PARTICLE_VIEW_PROPERTY),
          activityParseBuildConfigBoolean(BuildConfig.START_IN_PARTICLE_VIEW_DEFAULT, false),
      )

  private fun panelLauncherVisible(): Boolean =
      activityReadOptionalBooleanSystemProperty(PANEL_LAUNCHER_VISIBLE_PROPERTY)
          ?: activityParseBuildConfigBoolean(BuildConfig.PANEL_LAUNCHER_VISIBLE_DEFAULT, true)

  private fun spatialMultimodalRequiredOpenXrExtensions(): List<String> =
      SpatialOpenXrRouteModule.spatialMultimodalRequiredOpenXrExtensions(
          spatialMultimodalInputEnabled()
      )

  private fun spatialRequiredOpenXrExtensions(): List<String> =
      SpatialOpenXrRouteModule.spatialRequiredOpenXrExtensions(spatialMultimodalInputEnabled())

  private fun spatialRequiredOpenXrExtensionMarker(): String =
      SpatialOpenXrRouteModule.spatialRequiredOpenXrExtensionMarker(
          spatialMultimodalInputEnabled()
      )

  private fun particleLayerStereoMarkerFields(): String =
      SpatialSurfaceParticleRouteModule.stereoMarkerFields()

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun cameraHwbProjectionPanelMediaSettings(): MediaPanelSettings {
    val plane = cameraHwbProjectionPlaneForPlacement()
    return MediaPanelSettings(
        shape = QuadShapeOptions(plane.projectionWidthMeters, plane.projectionHeightMeters),
        display =
            FixedMediaPanelDisplayOptions(
                widthPx = CAMERA_HWB_PROJECTION_WIDTH_PX,
                heightPx = CAMERA_HWB_PROJECTION_HEIGHT_PX,
            ),
        rendering =
            MediaPanelRenderOptions(
                false,
                StereoMode.LeftRight,
                SamplerConfig(),
                0,
                cameraHwbProjectionZIndexForPlacement(plane.placementMode),
            ),
        style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
        input = PanelInputOptions(0),
    )
  }

  private fun particleLayerMediaSettings(): MediaPanelSettings =
      SpatialSurfaceParticleRouteModule.mediaSettings()

  private fun privateLayerPanelSettings(): PanelSettings =
      SpatialPanelPlacementModule.privateLayerPanelSettings()

  private fun panelDimensions(): PanelDimensions =
      SpatialPanelPlacementModule.panelDimensions(panelPlacement)

  private fun privateLayerPanelDimensions(): PanelDimensions =
      SpatialPanelPlacementModule.privateLayerPanelDimensions(privateLayerPanelPlacement)

  private fun panelLauncherDimensions(): PanelDimensions =
      SpatialPanelPlacementModule.panelLauncherDimensions()

  private fun panelStateToken(): String =
      SpatialPanelPlacementModule.panelStateToken(
          panelShellVisible = panelShellVisible(),
          privateLayerPanelVisible = privateLayerPanelVisible,
          workflowPanelVisible = panelPlacement.visible,
      )

  private fun recordPanelState(source: String) {
    runCatching { store.recordPanelForegroundState(panelStateToken(), source) }
        .getOrElse { throwable ->
          marker(
              SpatialPanelPlacementModule.panelStateRecordFailedMarker(
                  source = source,
                  error = throwable.javaClass.simpleName,
              )
          )
        }
  }

  private fun marker(detail: String) {
    val line = "$MARKER_PREFIX $detail"
    Log.i(TAG, line)
    runCatching {
      File(filesDir, ACTIVITY_MARKERS_FILE).appendText("${System.currentTimeMillis()} $line\n", Charsets.UTF_8)
    }
  }

  private external fun nativeRecordNoRenderInteropReceipt(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
      surfaceValid: Boolean,
  ): Long

  private external fun nativeStartSpatialNativePassthrough(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  private external fun nativeStopSpatialNativePassthrough(): Long

  private external fun nativeStartSpatialEnvironmentDepthProbe(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  private external fun nativeStopSpatialEnvironmentDepthProbe(): Long

  private external fun nativeStartSpatialControllerActions(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  private external fun nativeRequestSpatialMultimodalInput(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  private external fun nativePollSpatialControllerLeftThumbstickY(): Float

  private external fun nativePollSpatialControllerRightThumbstickY(): Float

  private external fun nativePollSpatialControllerRightButtonB(): Boolean

  private external fun nativeStopSpatialControllerActions()

  private external fun nativeStartSurfaceParticleLayer(
      surface: AndroidSurface,
      width: Int,
      height: Int,
      particleCount: Int,
      frameCount: Int,
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  private external fun nativeStopSurfaceParticleLayer()

  private external fun nativeRecenterSurfaceParticleSphereOnViewer(): Long

  private external fun nativeStartSdkQuadVulkanProbe(
      surface: AndroidSurface,
      width: Int,
      height: Int,
      frameCount: Int,
  ): Long

  private external fun nativeStopSdkQuadVulkanProbe()

  private external fun nativeStartCameraHwbProbe(
      surface: AndroidSurface,
      width: Int,
      height: Int,
      frameCount: Int,
      readerMaxImages: Int,
  ): Long

  private external fun nativeStartCameraHwbProjectionProbe(
      surface: AndroidSurface,
      width: Int,
      height: Int,
      frameCount: Int,
      readerMaxImages: Int,
  ): Long

  private external fun nativeStopCameraHwbProbe()

  private external fun nativeUpdateCameraHwbProjectionStereoOffsetUv(stereoOffsetUv: Float): Long

  private external fun nativeUpdateCameraHwbProjectionTargetScale(targetScale: Float): Long

  private external fun nativeUpdatePrivateLayerOverride(layerOverride: Float): Long

  private external fun nativeUpdatePrivateLayerDepthLayerPolicy(depthLayerPolicy: Int): Long

  private external fun nativeUpdatePrivateLayerDepthAlignment(
      leftOffsetX: Float,
      leftOffsetY: Float,
      rightOffsetX: Float,
      rightOffsetY: Float,
      sampleScale: Float,
  ): Long

  private external fun nativeStartSpatialVideoProjectionProbe(
      surface: AndroidSurface,
      width: Int,
      height: Int,
      frameCount: Int,
  ): Long

  private external fun nativeStopSpatialVideoProjectionProbe()

  private external fun nativeConfigureSpatialVideoProjection(
      enabled: Boolean,
      path: String,
      stereoLayout: String,
      width: Int,
      height: Int,
      maxImages: Int,
      fpsCap: Int,
      looping: Boolean,
      opacity: Float,
      highRateJsonPayload: Boolean,
  ): Long

  private external fun nativeUpdateSurfaceParticleParameters(
      driver0Value01: Float,
      driver1Value01: Float,
      pointScale: Float,
      driver2Value01: Float,
      driver3Value01: Float,
      driver4Value01: Float,
      driver5Value01: Float,
      driver6Value01: Float,
      driver7Value01: Float,
      tracerDrawSlotsPerOscillator: Float,
      tracerLifetimeSeconds: Float,
      tracerCopiesPerSecond: Float,
      transparencyOpacity: Float,
      projectionWorldScale: Float,
  ): Long

  private external fun nativeResolveSurfaceParticleAliasParameter(
      parameterId: String,
      value: Float,
      visualDriverActivationProfile: String,
  ): Long

  private external fun nativeUpdateSurfaceParticlePanelPose(
      centerX: Float,
      centerY: Float,
      centerZ: Float,
      rightX: Float,
      rightY: Float,
      rightZ: Float,
      upX: Float,
      upY: Float,
      upZ: Float,
      widthMeters: Float,
      heightMeters: Float,
      targetDistanceMeters: Float,
      leftEyeOffsetRightMeters: Float,
      rightEyeOffsetRightMeters: Float,
  ): Long

  private external fun nativeUpdateSurfaceParticleViewerEyePose(
      viewerX: Float,
      viewerY: Float,
      viewerZ: Float,
      viewerRightX: Float,
      viewerRightY: Float,
      viewerRightZ: Float,
      viewerUpX: Float,
      viewerUpY: Float,
      viewerUpZ: Float,
      viewerForwardX: Float,
      viewerForwardY: Float,
      viewerForwardZ: Float,
      leftEyeX: Float,
      leftEyeY: Float,
      leftEyeZ: Float,
      rightEyeX: Float,
      rightEyeY: Float,
      rightEyeZ: Float,
  ): Long

  private external fun nativeCreateExternalOpenXrSwapchain(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
      width: Int,
      height: Int,
  ): Long

  private external fun nativeDestroyExternalOpenXrSwapchain(
      openXrInstanceHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
      swapchainHandle: Long,
  ): Int

  private fun runSpatialStagedAssetIfRequested(intent: Intent?, reason: String) {
    if (spatialVirtualRoomEnabled() && !spatialVirtualRoomLoaded()) {
      marker(SpatialStagedAssetModule.startDeferredMarker(reason))
      return
    }
    stagedAssetModule.startIfRequested(intent, reason)
  }

  private fun runValidationWorkflowIfRequested(intent: Intent?) {
    if (intent?.action != ACTION_RUN_WORKFLOW_SELF_TEST) {
      return
    }
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SELF_TEST_PARTICIPANT_ID
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

    marker(SpatialValidationCommandModule.selfTestStartMarker(participantId, surfaceTargetId))
    scheduleParticleLayerLifecycleDiagnostics("self-test-start")
    try {
      store.resetForNewParticipant()
      store.beginParticipant(participantId)
      store.savePolarSetup(
          runLabel = "headset-self-test",
          operatorId = "codex",
          notes = "Meta Spatial SDK validation intent",
      )
      store.selectSurface(surfaceTargetId)
      store.prioritizeConditionForValidation(VALIDATION_DRIVER_PROFILE_ID)
      setWorkflowPanelVisible(false, focus = false, source = "self-test-particle-view")
      val block = store.startNextBlock()
      if (block != null) {
        applyDriverProfileToParticleControls(block, "self-test-driver-profile-start")
      }
      Handler(Looper.getMainLooper())
          .postDelayed(
              { setWorkflowPanelVisible(true, focus = true, source = "self-test-workflow-panel") },
              1500L,
          )
      marker(
          SpatialValidationCommandModule.selfTestBlockStartedMarker(
              participantId,
              surfaceTargetId,
              VALIDATION_DRIVER_PROFILE_ID,
          )
      )
      Handler(Looper.getMainLooper())
          .postDelayed(
              {
                try {
                  logParticleLayerLifecycleStatus("self-test-before-questionnaire")
                  store.syncElapsedBlock()
                  store.submitQuestionnaire(
                      comfortRating = 4,
                      intensityRating = 4,
                      engagementRating = 4,
                      notes = "Codex headset validation self-test",
                      signature = emptySignatureJson(),
                  )
                  marker(
                      SpatialValidationCommandModule.selfTestCompleteMarker(
                          participantId,
                          surfaceTargetId,
                          VALIDATION_DRIVER_PROFILE_ID,
                      )
                  )
                } catch (throwable: Throwable) {
                  marker(
                      SpatialValidationCommandModule.selfTestFailedMarker(
                          SpatialValidationCommandModule.throwableErrorToken(throwable)
                      )
                  )
                  Log.e(TAG, "Spatial Camera Panel validation workflow failed", throwable)
                }
              },
              SpatialCameraPanelStore.DEFAULT_BLOCK_DURATION_MS + 750L,
          )
    } catch (throwable: Throwable) {
      marker(
          SpatialValidationCommandModule.selfTestFailedMarker(
              SpatialValidationCommandModule.throwableErrorToken(throwable)
          )
      )
      Log.e(TAG, "Spatial Camera Panel validation workflow failed", throwable)
    }
  }

  private fun runUiCommandIfRequested(intent: Intent?) {
    if (intent?.action != ACTION_RUN_UI_COMMAND) {
      return
    }
    val uiAction =
        intent.getStringExtra(EXTRA_UI_ACTION)?.trim()?.takeIf { it.isNotBlank() }
            ?: "panel-open"
    val source = SpatialValidationCommandModule.remoteUiCommandSource(uiAction)
    marker(SpatialValidationCommandModule.uiCommandStartMarker(uiAction))
    try {
      when (uiAction) {
        "panel-open" -> setWorkflowPanelVisible(true, focus = true, source = source)
        "panel-close" -> setWorkflowPanelVisible(false, focus = false, source = source)
        "private-layer-panel-open" -> setPrivateLayerPanelVisible(true, focus = true, source = source)
        "private-layer-panel-close" -> setPrivateLayerPanelVisible(false, focus = false, source = source)
        "panel-reset" -> resetWorkflowPanelPlacement()
        "panel-headlock-on" -> setPanelHeadlocked(true, source)
        "panel-headlock-off" -> setPanelHeadlocked(false, source)
        "panel-headlock-toggle" -> setPanelHeadlocked(!panelPlacement.headlocked, source)
        "panel-adjust" ->
            adjustPanelPlacement(
                intent.getFloatExtra(EXTRA_DELTA_X, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_Y, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_Z, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_SCALE, 0.0f),
            )
        "panel-resize" ->
            resizeWorkflowPanel(
                intent.getFloatExtra(EXTRA_DELTA_WIDTH, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_HEIGHT, 0.0f),
            )
        "particle-controls" ->
            updateSurfaceParticleControls(
                particleControls.copy(
                    driver0Value01 = intent.getFloatExtra(EXTRA_DRIVER0, particleControls.driver0Value01),
                    driver1Value01 = intent.getFloatExtra(EXTRA_DRIVER1, particleControls.driver1Value01),
                    driver2Value01 = intent.getFloatExtra(EXTRA_DRIVER2, particleControls.driver2Value01),
                    driver3Value01 = intent.getFloatExtra(EXTRA_DRIVER3, particleControls.driver3Value01),
                    driver4Value01 = intent.getFloatExtra(EXTRA_DRIVER4, particleControls.driver4Value01),
                    driver5Value01 = intent.getFloatExtra(EXTRA_DRIVER5, particleControls.driver5Value01),
                    driver6Value01 = intent.getFloatExtra(EXTRA_DRIVER6, particleControls.driver6Value01),
                    driver7Value01 = intent.getFloatExtra(EXTRA_DRIVER7, particleControls.driver7Value01),
                    pointScale = intent.getFloatExtra(EXTRA_POINT_SCALE, particleControls.pointScale),
                    tracerDrawSlotsPerOscillator =
                        intent.getFloatExtra(
                            EXTRA_TRACER_DRAW_SLOTS,
                            particleControls.tracerDrawSlotsPerOscillator,
                        ),
                    tracerLifetimeSeconds =
                        intent.getFloatExtra(
                            EXTRA_TRACER_LIFETIME_SECONDS,
                            particleControls.tracerLifetimeSeconds,
                        ),
                    tracerCopiesPerSecond =
                        intent.getFloatExtra(
                            EXTRA_TRACER_COPIES_PER_SECOND,
                            particleControls.tracerCopiesPerSecond,
                        ),
                    transparencyOpacity =
                        intent.getFloatExtra(EXTRA_TRANSPARENCY_OPACITY, particleControls.transparencyOpacity),
                    projectionWorldScale =
                        intent.getFloatExtra(EXTRA_PROJECTION_WORLD_SCALE, particleControls.projectionWorldScale),
                ),
                source,
            )
        "particle-panel-distance" -> applyRemoteParticleLayerTargetDistance(intent, source)
        "particle-panel-view-yaw" -> applyRemoteParticleLayerViewYaw(intent, source)
        "particle-recenter" ->
            recenterSurfaceParticleSphereOnViewer(
                inputSource = source,
                detail = "remoteUiAction=particle-recenter controllerInputRequired=false",
                requireParticleView = false,
            )
        "particle-alias-control" -> resolveSurfaceParticleAliasControl(intent, source)
        "participant-reset" -> {
          store.resetForNewParticipant()
          setWorkflowPanelVisible(true, focus = true, source = source)
        }
        "participant-begin" -> {
          store.beginParticipant(remoteParticipantId(intent))
          setWorkflowPanelVisible(true, focus = true, source = source)
        }
        "polar-setup-save" -> {
          ensureRemoteParticipant(intent, source)
          store.savePolarSetup(
              runLabel = intent.getStringExtra(EXTRA_RUN_LABEL) ?: "remote-ui-command",
              operatorId = intent.getStringExtra(EXTRA_OPERATOR_ID) ?: "codex",
              notes = intent.getStringExtra(EXTRA_NOTES) ?: "Remote UI command",
          )
          setWorkflowPanelVisible(true, focus = true, source = source)
        }
        "surface-select" -> {
          ensureRemoteParticipantAndPolarSetup(intent, source)
          store.selectSurface(remoteSurfaceTargetId(intent))
          setWorkflowPanelVisible(true, focus = true, source = source)
        }
        "start-block" -> startRemoteSurfaceBlock(intent, source, resetSession = false)
        "surface-target-activate" -> startRemoteSurfaceBlock(intent, source, resetSession = true)
        "questionnaire-submit" -> {
          store.submitQuestionnaire(
              comfortRating = intent.getIntExtra(EXTRA_COMFORT_RATING, 4),
              intensityRating = intent.getIntExtra(EXTRA_INTENSITY_RATING, 4),
              engagementRating = intent.getIntExtra(EXTRA_ENGAGEMENT_RATING, 4),
              notes = intent.getStringExtra(EXTRA_NOTES) ?: "Remote UI command questionnaire",
              signature = emptySignatureJson(),
          )
          setWorkflowPanelVisible(true, focus = true, source = source)
        }
        else -> error("unknown_ui_action_$uiAction")
      }
      marker(
          SpatialValidationCommandModule.uiCommandCompleteMarker(
              uiAction,
              panelStateToken(),
              panelPlacement.visible,
              store.snapshot().surfaceTargetId,
          )
      )
    } catch (throwable: Throwable) {
      marker(
          SpatialValidationCommandModule.uiCommandFailedMarker(
              uiAction,
              SpatialValidationCommandModule.throwableErrorToken(throwable),
          )
      )
      Log.e(TAG, "Spatial Camera Panel UI command failed", throwable)
    }
  }

  private fun runSurfaceTargetActivationIfRequested(intent: Intent?) {
    if (intent?.action != ACTION_RUN_SURFACE_TARGET) {
      return
    }
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_PARTICIPANT_ID
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

    try {
      startRemoteSurfaceBlock(intent, "surface-target-activation", resetSession = true)
      marker(
          SpatialValidationCommandModule.surfaceTargetActivatedMarker(
              participantId,
              surfaceTargetId,
              VALIDATION_DRIVER_PROFILE_ID,
              panelStateToken(),
          )
      )
    } catch (throwable: Throwable) {
      marker(
          SpatialValidationCommandModule.surfaceTargetActivationFailedMarker(
              surfaceTargetId,
              SpatialValidationCommandModule.throwableErrorToken(throwable),
          )
      )
      Log.e(TAG, "Spatial Camera Panel surface target activation failed", throwable)
    }
  }

  private fun startRemoteSurfaceBlock(
      intent: Intent,
      source: String,
      resetSession: Boolean,
  ): ActiveBlockSnapshot? {
    marker(
        SpatialValidationCommandModule.surfaceTargetActivationStartMarker(
            remoteParticipantId(intent),
            remoteSurfaceTargetId(intent),
            source,
        )
    )
    scheduleParticleLayerLifecycleDiagnostics(source)
    if (resetSession) {
      store.resetForNewParticipant()
    }
    ensureRemoteParticipantAndPolarSetup(intent, source)
    store.selectSurface(remoteSurfaceTargetId(intent))
    store.prioritizeConditionForValidation(VALIDATION_DRIVER_PROFILE_ID)
    setQuestionnaireDueReopensPanel(false, source)
    setWorkflowPanelVisible(false, focus = false, source = "$source-particle-view")
    val block = store.startNextBlock()
    if (block != null) {
      applyDriverProfileToParticleControls(block, "$source-block-start")
    }
    return block
  }

  private fun ensureRemoteParticipantAndPolarSetup(intent: Intent, source: String) {
    ensureRemoteParticipant(intent, source)
    val snapshot = store.snapshot()
    if (snapshot.stage == "polar_setup") {
      store.savePolarSetup(
          runLabel = intent.getStringExtra(EXTRA_RUN_LABEL) ?: source,
          operatorId = intent.getStringExtra(EXTRA_OPERATOR_ID) ?: "codex",
          notes = intent.getStringExtra(EXTRA_NOTES) ?: "Remote UI command",
      )
    }
  }

  private fun ensureRemoteParticipant(intent: Intent, source: String) {
    val snapshot = store.snapshot()
    if (snapshot.sessionId.isBlank() || snapshot.stage == "participant") {
      store.beginParticipant(remoteParticipantId(intent))
      marker(
          SpatialValidationCommandModule.remoteParticipantCreatedMarker(
              source,
              remoteParticipantId(intent),
          )
      )
    }
  }

  private fun remoteParticipantId(intent: Intent): String =
      intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: SpatialValidationCommandModule.DEFAULT_UI_COMMAND_PARTICIPANT_ID

  private fun remoteSurfaceTargetId(intent: Intent): String =
      intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

  private fun runPolarLiveValidationIfRequested(intent: Intent?) {
    if (intent?.action != ACTION_RUN_POLAR_LIVE_VALIDATION) {
      return
    }
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_POLAR_LIVE_PARTICIPANT_ID
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID
    val scanDelayMs =
        intent.getIntExtra(EXTRA_POLAR_SCAN_SECONDS, 16).coerceIn(3, 60) * 1000L
    val connectDelayMs =
        intent.getIntExtra(EXTRA_POLAR_CONNECT_DELAY_SECONDS, 10).coerceIn(3, 60) * 1000L
    val ecgRunMs =
        intent.getIntExtra(EXTRA_POLAR_ECG_SECONDS, 14).coerceIn(3, 180) * 1000L
    val mainHandler = Handler(Looper.getMainLooper())

    marker(
        SpatialValidationCommandModule.polarLiveStartMarker(
            participantId,
            surfaceTargetId,
            scanDelayMs / 1000L,
            connectDelayMs / 1000L,
            ecgRunMs / 1000L,
        )
    )
    scheduleParticleLayerLifecycleDiagnostics("polar-live-validation-start")
    try {
      store.resetForNewParticipant()
      store.beginParticipant(participantId)
      store.savePolarSetup(
          runLabel = "polar-live-validation",
          operatorId = "codex",
          notes = "Meta Spatial SDK Polar H10 live validation intent",
      )
      store.selectSurface(surfaceTargetId)
      setWorkflowPanelVisible(true, focus = true, source = "polar-live-validation")
      val panel = ensurePolarSensorPanel()
      panel.buildView()
      marker(SpatialValidationCommandModule.polarPanelAutomationReadyMarker(participantId))
      panel.handleCommand("select_ecg")
      panel.handleCommand("scan")
      marker(SpatialValidationCommandModule.polarScanCommandIssuedMarker(participantId))
      mainHandler.postDelayed(
          {
            marker(SpatialValidationCommandModule.polarConnectRequestedMarker(panel.discoveredDeviceCount()))
            panel.connectBestDiscovered("ecg")
          },
          scanDelayMs,
      )
      mainHandler.postDelayed(
          {
            marker(SpatialValidationCommandModule.polarStartEcgRequestedMarker(panel.discoveredDeviceCount()))
            panel.handleCommand("start_ecg")
          },
          scanDelayMs + connectDelayMs,
      )
      mainHandler.postDelayed(
          {
            val ecgReceiving = panel.isEcgReceiving()
            marker(
                SpatialValidationCommandModule.polarCompleteMarker(
                    ecgReceiving,
                    panel.discoveredDeviceCount(),
                    panel.ecgExperimentStatusLine(true),
                )
            )
          },
          scanDelayMs + connectDelayMs + ecgRunMs,
      )
    } catch (throwable: Throwable) {
      marker(
          SpatialValidationCommandModule.polarFailedMarker(
              SpatialValidationCommandModule.throwableErrorToken(throwable)
          )
      )
      Log.e(TAG, "Spatial Camera Panel Polar live validation failed", throwable)
    }
  }

  private fun scheduleParticleLayerLifecycleDiagnostics(reason: String) {
    val mainHandler = Handler(Looper.getMainLooper())
    listOf(750L, 2500L, 6500L, 14000L).forEach { delayMs ->
      mainHandler.postDelayed({ logParticleLayerLifecycleStatus("$reason-$delayMs") }, delayMs)
    }
  }

  private fun logParticleLayerLifecycleStatus(phase: String) {
    val probe =
        runCatching { SpatialNativeInteropProbe.capture(scene) }
            .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }
    val snapshot = runCatching { store.snapshot() }.getOrNull()
    marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLifecycleCheckMarker(
            phase = phase,
            activityMarkersFile = ACTIVITY_MARKERS_FILE,
            panelRegistrationCount = panelRegistrationCount,
            panelMode = panelStateToken(),
            workflowPanelVisible = panelPlacement.visible,
            launcherPanelVisible = launcherPanelVisibleForPanelMode(),
            legacyLauncherPanelSuppressed = legacyLauncherPanelSuppressedForCameraStack(),
            particleLayerEntityCreated = particleLayerEntity != null,
            particleSurfacePanelReady = particleSurfacePanelReady,
            particleSurfaceConsumerCalled = particleSurfaceConsumerCalled,
            particleSurfaceConsumerSurfaceValid = particleSurfaceConsumerSurfaceValid,
            nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
            particleLayerStarted = particleLayerStarted,
            nativeSurfaceStartRequested = nativeSurfaceStartRequested,
            lastNativeSurfaceStartMask = lastNativeSurfaceStartMask,
            nativeReceiptLibraryLoaded = nativeReceiptLibraryLoaded,
            nativeReceiptLibraryError = nativeReceiptLibraryError,
            openXrInstanceHandleNonZero = probe.openXrInstanceHandleNonZero,
            openXrSessionHandleNonZero = probe.openXrSessionHandleNonZero,
            openXrGetInstanceProcAddrHandleNonZero = probe.openXrGetInstanceProcAddrHandleNonZero,
            currentDriverProfileId = snapshot?.currentConditionId ?: "none",
            currentProfileId = snapshot?.currentProfileId ?: "none",
            placementMarkerFields = particleLayerPlacementMarkerFields(),
            stereoMarkerFields = particleLayerStereoMarkerFields(),
        )
    )
  }

  companion object {
    private const val TAG = "RQSpatialCameraPanel"
    private const val MARKER_PREFIX = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL"
    private const val ACTIVITY_MARKERS_FILE = "spatial_camera_panel_activity_markers.log"
    private const val VALIDATION_DRIVER_PROFILE_ID = "profile-b"
    private const val ACTION_RUN_WORKFLOW_SELF_TEST =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_WORKFLOW_SELF_TEST"
    private const val ACTION_RUN_POLAR_LIVE_VALIDATION =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_POLAR_LIVE_VALIDATION"
    private const val ACTION_RUN_UI_COMMAND =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
    private const val ACTION_RUN_SURFACE_TARGET =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"
    private const val EXTRA_PARTICIPANT_ID = "participant_id"
    private const val EXTRA_SURFACE_TARGET_ID = "surface_target_id"
    private const val EXTRA_UI_ACTION = "ui_action"
    private const val EXTRA_DELTA_X = "delta_x"
    private const val EXTRA_DELTA_Y = "delta_y"
    private const val EXTRA_DELTA_Z = "delta_z"
    private const val EXTRA_DELTA_SCALE = "delta_scale"
    private const val EXTRA_DELTA_WIDTH = "delta_width"
    private const val EXTRA_DELTA_HEIGHT = "delta_height"
    private const val EXTRA_DRIVER0 = "driver0"
    private const val EXTRA_DRIVER1 = "driver1"
    private const val EXTRA_DRIVER2 = "driver2"
    private const val EXTRA_DRIVER3 = "driver3"
    private const val EXTRA_DRIVER4 = "driver4"
    private const val EXTRA_DRIVER5 = "driver5"
    private const val EXTRA_DRIVER6 = "driver6"
    private const val EXTRA_DRIVER7 = "driver7"
    private const val EXTRA_PARTICLE_ALIAS_PARAMETER_ID = "parameter_id"
    private const val EXTRA_PARTICLE_ALIAS_VALUE = "value"
    private const val EXTRA_PARTICLE_ALIAS_VISUAL_DRIVER_ACTIVATION_PROFILE =
        "visual_driver_activation_profile"
    private const val EXTRA_POINT_SCALE = "point_scale"
    private const val EXTRA_TRACER_DRAW_SLOTS = "tracer_draw_slots_per_oscillator"
    private const val EXTRA_TRACER_LIFETIME_SECONDS = "tracer_lifetime_seconds"
    private const val EXTRA_TRACER_COPIES_PER_SECOND = "tracer_copies_per_second"
    private const val EXTRA_TRANSPARENCY_OPACITY = "transparency_opacity"
    private const val EXTRA_PROJECTION_WORLD_SCALE = "projection_world_scale"
    private const val EXTRA_PARTICLE_LAYER_TARGET_DISTANCE_METERS =
        "particle_layer_target_distance_meters"
    private const val EXTRA_PARTICLE_LAYER_VIEW_YAW_DEGREES = "particle_layer_view_yaw_degrees"
    private const val EXTRA_RUN_LABEL = "run_label"
    private const val EXTRA_OPERATOR_ID = "operator_id"
    private const val EXTRA_NOTES = "notes"
    private const val EXTRA_COMFORT_RATING = "comfort_rating"
    private const val EXTRA_INTENSITY_RATING = "intensity_rating"
    private const val EXTRA_ENGAGEMENT_RATING = "engagement_rating"
    private const val EXTRA_POLAR_SCAN_SECONDS = "polar_scan_seconds"
    private const val EXTRA_POLAR_CONNECT_DELAY_SECONDS = "polar_connect_delay_seconds"
    private const val EXTRA_POLAR_ECG_SECONDS = "polar_ecg_seconds"
    private const val PANEL_SHELL_VISIBLE_PROPERTY =
        "debug.rustyquest.spatial.panel_shell.visible"
    private const val PANEL_LAUNCHER_VISIBLE_PROPERTY =
        "debug.rustyquest.spatial.panel_launcher.visible"
  }
}
