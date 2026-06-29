package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.NetworkedAssetLoader
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
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.GLXFInfo
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
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionControls
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
  private var privateLayerPanelVisible = false
  private var privateLayerOverride = PrivateLayerControls.cycleOverride
  private var privateLayerDepthLayerPolicy = PrivateLayerControls.defaultDepthLayerPolicy
  private var privateLayerDepthAlignment = PrivateLayerDepthAlignment()
  private var panelLauncherEntity: Entity? = null
  private var panelPlacement = PanelPlacement()
  private var privateLayerPanelPlacement =
      PanelPlacement(
          visible = false,
          headlocked = true,
          xMeters = PRIVATE_LAYER_PANEL_OFFSET_X_METERS,
          yMeters = PRIVATE_LAYER_PANEL_OFFSET_Y_METERS,
          zMeters = PRIVATE_LAYER_PANEL_DISTANCE_METERS,
          scale = PRIVATE_LAYER_PANEL_SCALE,
      )
  private var particleControls = SurfaceParticleControlState()
  private var particleLayerEntity: Entity? = null
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
  private var nativeControllerSecondaryDown = false
  private val pinnedSpatialGameControllerIds = mutableSetOf<Int>()
  private var lastSpatialInputRouteMarkerMs = 0L
  private var lastSpatialJoystickArbitrationMarkerMs = 0L
  private var androidControllerPrimaryKeyDown = false
  private var androidControllerPrimaryMotionDown = false
  private var androidControllerSecondaryKeyDown = false
  private var androidControllerSecondaryMotionDown = false
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
  private var cameraHwbProjectionTargetScale = CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT
  private var cameraHwbProjectionStereoHorizontalOffsetUv =
      CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
  private var cameraHwbProjectionPlacementMode = CameraHwbProjectionPlacementMode.ViewerLocked
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
  private var spatialVirtualRoomEntity: Entity? = null
  private var spatialVirtualRoomSkyboxEntity: Entity? = null
  private var spatialVirtualRoomLoadJob: Job? = null
  private var spatialVirtualRoomConfigured = false
  private var spatialVirtualRoomLoaded = false
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
        SpatialControllerInputLateFeature(::pollSpatialControllerInput),
        ComposeFeature(),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    loadNativeReceiptLibrary()
    suppressParticleLayerIfCameraProjectionRequested("activity-created")
    deactivateLegacyWorkflowPanelsForCameraStack("activity-created")
    if (shouldResetExperimentForPanelFirstLaunch(intent)) {
      store.resetForNewParticipant()
      marker(
          "channel=experiment-panel status=panel-first-launch-reset " +
              "freshSpatialActivityLaunch=true initialStage=participant " +
              "validationIntent=false panelFirstExperimentFlow=true"
      )
    }
    marker(
        "channel=activity status=created package=io.github.mesmerprism.rustyquest.spatial_camera_panel " +
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
            "spatialVirtualRoomModule=$SPATIAL_VIRTUAL_ROOM_MODULE_ID " +
            "spatialVirtualRoomEnabledProperty=$SPATIAL_VIRTUAL_ROOM_ENABLED_PROPERTY " +
            "spatialVirtualRoomDefaultEnabled=false " +
            "spatialSkyboxModule=$SPATIAL_SKYBOX_MODULE_ID " +
            "spatialSkyboxEnabledProperty=$SPATIAL_SKYBOX_ENABLED_PROPERTY " +
            "spatialSkyboxDefaultEnabled=false " +
            "spatialSdk3dAssetHighRateJsonPayload=false " +
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
                  "channel=native-surface-particle-layer status=panel-entity-create-failed " +
                      "renderPolicy=native-vulkan-wsi-surface-panel error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")}"
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
        "channel=experiment-panel status=panel-first-flow-ready " +
            "panelFirstExperimentFlow=true blockStartRequiresPanelClose=true " +
            "questionnaireSubmitAutoStartsNextBlock=false questionnaireDueReopensPanel=true " +
            "particleLayerVisible=${particleLayerVisibleForPanelMode()} " +
            "icosphereSurfaceAvailable=true rendererAuthority=native-vulkan-wsi-surface-panel"
    )
    marker(
        "channel=native-surface-particle-layer status=panel-entity-spawned " +
            "renderPolicy=native-vulkan-wsi-surface-panel panelRegistrationId=spatial_camera_surface_panel " +
            particleLayerPlacementMarkerFields() + " " +
            particleLayerStereoMarkerFields()
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
    val panels =
        listOf(
        ComposeViewPanelRegistration(
            R.id.spatial_camera_panel,
            composeViewCreator = { _, context ->
              ComposeView(context).apply {
                setBackgroundColor(android.graphics.Color.rgb(255, 243, 176))
                alpha = 1.0f
                setWillNotDraw(false)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setContent {
                  MaterialTheme(
                      colorScheme =
                          lightColorScheme(
                              primary = PanelProbeHeader,
                              onPrimary = Color.White,
                              background = PanelProbeBackground,
                              onBackground = PanelProbeInk,
                              surface = PanelProbeBackground,
                              onSurface = PanelProbeInk,
                          )
                  ) {
                    SpatialCameraPanel(
                        store = store,
                        placement = panelPlacement,
                        particleControls = particleControls,
                        setWorkflowPanelVisible = { visible, focus, source ->
                          setWorkflowPanelVisible(visible, focus, source = source)
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
                        updateParticleControls = { driver0, driver1, pointScale ->
                          updateSurfaceParticleControls(driver0, driver1, pointScale)
                        },
                        applyDriverProfile = { block, source ->
                          applyDriverProfileToParticleControls(block, source)
                        },
                        polarPanel = ensurePolarSensorPanel(),
                    )
                  }
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
                  display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
              )
            },
        ),
        ComposeViewPanelRegistration(
            R.id.spatial_private_layer_panel,
            composeViewCreator = { _, context ->
              ComposeView(context).apply {
                setBackgroundColor(AndroidColor.rgb(20, 24, 32))
                alpha = 1.0f
                setWillNotDraw(false)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setContent {
                  MaterialTheme(
                      colorScheme =
                          lightColorScheme(
                              primary = Color(0xFF63D2FF),
                              onPrimary = Color(0xFF04111A),
                              background = Color(0xFF141820),
                              onBackground = Color(0xFFF4F7FA),
                              surface = Color(0xFF202634),
                              onSurface = Color(0xFFF4F7FA),
                          )
                  ) {
                    PrivateLayerControlPanel(
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
                    )
                  }
                }
              }
            },
            settingsCreator = {
              privateLayerPanelSettings()
            },
        ),
        ComposeViewPanelRegistration(
            R.id.spatial_camera_panel_launcher,
            composeViewCreator = { _, context ->
              ComposeView(context).apply {
                setBackgroundColor(android.graphics.Color.rgb(15, 95, 111))
                alpha = 1.0f
                setWillNotDraw(false)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setContent {
                  MaterialTheme(
                      colorScheme =
                          lightColorScheme(
                              primary = PanelProbeButton,
                              onPrimary = Color.White,
                              background = PanelProbeHeader,
                              onBackground = Color.White,
                              surface = PanelProbeHeader,
                              onSurface = Color.White,
                          )
                  ) {
                    SpatialCameraPanelLauncher {
                      setWorkflowPanelVisible(true, focus = true, source = "launcher-panel")
                    }
                  }
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape =
                      QuadShapeOptions(
                          width = PANEL_LAUNCHER_WIDTH_METERS,
                          height = PANEL_LAUNCHER_HEIGHT_METERS,
                      ),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
                  display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_LAUNCHER_DP_PER_METER),
              )
            },
        ),
        VideoSurfacePanelRegistration(
            R.id.spatial_camera_surface_panel,
            surfaceConsumer = { _, surface ->
              particleSurfaceConsumerCalled = true
              particleSurfaceConsumerSurfaceValid = surface.isValid
              marker(
                  "channel=native-surface-particle-layer status=surface-consumer-called " +
                      "renderPolicy=native-vulkan-wsi-surface-panel surfaceValid=${surface.isValid} " +
                      particleLayerPlacementMarkerFields() + " " +
                      particleLayerStereoMarkerFields()
              )
              startNativeSurfaceParticleLayer(surface)
            },
            settingsCreator = { particleLayerMediaSettings() },
            panelSetup = { panel, _ ->
              particleSurfacePanelReady = true
              marker(
                  "channel=native-surface-particle-layer status=surface-panel-ready " +
                      "renderPolicy=native-vulkan-wsi-surface-panel panelHandle=${panel.handle} " +
                      "surfaceValid=${panel.surface.isValid} " +
                      particleLayerPlacementMarkerFields() + " " +
                      particleLayerStereoMarkerFields()
              )
            },
        ),
    )
    panelRegistrationCount = panels.size
    marker(
        "channel=native-surface-particle-layer status=panel-registrations-created " +
            "renderPolicy=native-vulkan-wsi-surface-panel panelRegistrationCount=$panelRegistrationCount " +
            "workflowPanelRegistrationId=spatial_camera_panel " +
            "launcherPanelRegistrationId=spatial_camera_panel_launcher " +
            "particlePanelRegistrationId=spatial_camera_surface_panel"
    )
    scheduleParticleLayerLifecycleDiagnostics("register-panels")
    return panels
  }

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
    if (!spatialVirtualRoomEnabled()) {
      return
    }
    if (spatialVirtualRoomLoadJob != null || spatialVirtualRoomEntity != null) {
      return
    }
    applyPanelPlacement()
    runCatching {
          NetworkedAssetLoader.init(
              File(applicationContext.cacheDir.canonicalPath),
              OkHttpAssetFetcher(),
          )
        }
        .onFailure { throwable ->
          marker(
              "channel=spatial-virtual-room status=asset-loader-init-failed " +
                  "module=$SPATIAL_VIRTUAL_ROOM_MODULE_ID reason=${markerToken(reason)} " +
                  "error=${markerToken(throwable.javaClass.simpleName)} " +
                  "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
          )
          return
        }
    val root = Entity.create()
    spatialVirtualRoomEntity = root
    marker(
        "channel=spatial-virtual-room status=load-requested " +
            "module=$SPATIAL_VIRTUAL_ROOM_MODULE_ID reason=${markerToken(reason)} " +
            "sceneUri=${markerToken(SPATIAL_VIRTUAL_ROOM_SCENE_URI)} " +
            "roomAssetSource=packaged-glxf virtualRoomSceneAuthoring=meta-spatial-editor " +
            "sampleRoomAssetPolicy=local-launch-input genericModuleSupport=true " +
            "projectionDefaultPlacementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
            "rightSecondaryTogglesFullFov=true projectionDisplaySurface=video-plus-custom-camera-stack " +
            "legacyLauncherPanelSuppressed=true " +
            "mrukPlacement=false passthroughRoomPlacement=false highRateJsonPayload=false"
    )
    spatialVirtualRoomLoadJob =
        activityScope.launch {
          runCatching {
                glXFManager.inflateGLXF(
                    Uri.parse(SPATIAL_VIRTUAL_ROOM_SCENE_URI),
                    rootEntity = root,
                    onLoaded = { composition -> onSpatialVirtualRoomLoaded(composition) },
                )
              }
              .onFailure { throwable ->
                marker(
                    "channel=spatial-virtual-room status=load-failed " +
                        "module=$SPATIAL_VIRTUAL_ROOM_MODULE_ID " +
                        "sceneUri=${markerToken(SPATIAL_VIRTUAL_ROOM_SCENE_URI)} " +
                        "error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
                )
                destroySpatialVirtualRoom("load-failed")
              }
        }
  }

  private fun onSpatialVirtualRoomLoaded(composition: GLXFInfo) {
    spatialVirtualRoomLoaded = true
    val environmentEntity =
        runCatching { composition.getNodeByName(SPATIAL_VIRTUAL_ROOM_ENVIRONMENT_NODE).entity }
            .getOrNull()
    val environmentMesh = environmentEntity?.tryGetComponent<Mesh>()
    if (environmentEntity != null && environmentMesh != null) {
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)
    }
    marker(
        "channel=spatial-virtual-room status=loaded " +
            "module=$SPATIAL_VIRTUAL_ROOM_MODULE_ID " +
            "sceneUri=${markerToken(SPATIAL_VIRTUAL_ROOM_SCENE_URI)} " +
            "environmentNode=${markerToken(SPATIAL_VIRTUAL_ROOM_ENVIRONMENT_NODE)} " +
            "environmentNodeFound=${environmentEntity != null} " +
            "environmentMeshUnlit=${environmentMesh != null} " +
            "roomAssetSource=packaged-glxf genericModuleSupport=true " +
            "privateSourceAssetPackaged=false highRateJsonPayload=false"
    )
    runSpatialStagedAssetIfRequested(intent, "virtual-room-loaded")
    runSpatialVideoProjectionProbeIfRequested("virtual-room-loaded")
    runCameraHwbProjectionProbeIfRequested("virtual-room-loaded")
  }

  private fun configureSpatialVirtualRoomScene(reason: String) {
    val virtualRoomEnabled = spatialVirtualRoomEnabled()
    val skyboxEnabled = spatialSkyboxEnabled()
    if ((!virtualRoomEnabled && !skyboxEnabled) || spatialVirtualRoomConfigured) {
      return
    }
    spatialVirtualRoomConfigured = true
    val lightingConfigured =
        runCatching {
              scene.setLightingEnvironment(
                  ambientColor = Vector3(0.0f),
                  sunColor = Vector3(7.0f, 7.0f, 7.0f),
                  sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
                  environmentIntensity = 0.3f,
              )
              true
            }
            .getOrDefault(false)
    val iblConfigured =
        runCatching {
              scene.updateIBLEnvironment(SPATIAL_VIRTUAL_ROOM_IBL_ASSET)
              true
            }
            .getOrDefault(false)
    val skydomeResourceId =
        resources.getIdentifier(SPATIAL_VIRTUAL_ROOM_SKYDOME_RESOURCE, "drawable", packageName)
    val skyboxCreated =
        if (skydomeResourceId != 0 && spatialVirtualRoomSkyboxEntity == null) {
          runCatching {
                spatialVirtualRoomSkyboxEntity =
                    Entity.create(
                        Mesh(
                            Uri.parse(SPATIAL_VIRTUAL_ROOM_SKYBOX_MESH_URI),
                            hittable = MeshCollision.NoCollision,
                        ),
                        Material().apply {
                          baseTextureAndroidResourceId = skydomeResourceId
                          unlit = true
                        },
                        Transform(Pose(Vector3(0.0f, 0.0f, 0.0f))),
                    )
                true
              }
              .getOrDefault(false)
        } else {
          false
        }
    val channel = if (virtualRoomEnabled) "spatial-virtual-room" else "spatial-skybox"
    val module = if (virtualRoomEnabled) SPATIAL_VIRTUAL_ROOM_MODULE_ID else SPATIAL_SKYBOX_MODULE_ID
    marker(
        "channel=$channel status=scene-configured " +
            "module=$module reason=${markerToken(reason)} " +
            "virtualRoomEnabled=$virtualRoomEnabled skyboxOnly=${skyboxEnabled && !virtualRoomEnabled} " +
            "lightingConfigured=$lightingConfigured iblConfigured=$iblConfigured " +
            "iblAsset=${markerToken(SPATIAL_VIRTUAL_ROOM_IBL_ASSET)} " +
            "skydomeResource=${markerToken(SPATIAL_VIRTUAL_ROOM_SKYDOME_RESOURCE)} " +
            "skydomeResourceFound=${skydomeResourceId != 0} skyboxCreated=$skyboxCreated " +
            "referenceSpace=LOCAL_FLOOR viewOrigin=0.0;0.0;2.0 yawDegrees=180.0 " +
            "projectionDefaultPlacementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
            "rightSecondaryTogglesFullFov=true projectionRoomRenderOrder=projection-layer-over-virtual-room " +
            "legacyLauncherPanelSuppressed=true " +
            "roomAssetSource=packaged-glxf roomMeshLoaded=$virtualRoomEnabled " +
            "mrukPlacement=false passthroughRoomPlacement=false " +
            "runtimeCrash=false"
    )
  }

  private fun destroySpatialVirtualRoom(reason: String) {
    spatialVirtualRoomLoadJob?.cancel()
    spatialVirtualRoomLoadJob = null
    spatialVirtualRoomEntity?.let { entity -> runCatching { entity.destroy() } }
    spatialVirtualRoomSkyboxEntity?.let { entity -> runCatching { entity.destroy() } }
    val hadRoom = spatialVirtualRoomEntity != null || spatialVirtualRoomSkyboxEntity != null
    spatialVirtualRoomEntity = null
    spatialVirtualRoomSkyboxEntity = null
    spatialVirtualRoomConfigured = false
    spatialVirtualRoomLoaded = false
    if (hadRoom) {
      marker(
          "channel=spatial-virtual-room status=destroyed " +
              "module=$SPATIAL_VIRTUAL_ROOM_MODULE_ID reason=${markerToken(reason)}"
      )
    }
  }

  private fun spatialVirtualRoomEnabled(): Boolean =
      readOptionalBooleanSystemProperty(SPATIAL_VIRTUAL_ROOM_ENABLED_PROPERTY) ?: false

  private fun spatialSkyboxEnabled(): Boolean =
      readOptionalBooleanSystemProperty(SPATIAL_SKYBOX_ENABLED_PROPERTY) ?: false

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
    marker(
        "channel=native-interop-probe status=observed phase=$phase renderPolicy=${probe.renderPolicy} " +
            "runtimeName=${markerToken(probe.runtimeName)} " +
            "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
            "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
            "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
            "surfaceCapability=${surfaceProbe.capability} surfaceProbeStatus=${surfaceProbe.status} " +
            "surfaceValid=${surfaceProbe.surfaceValid} surfaceError=${markerToken(surfaceProbe.error)}"
    )
    marker(
        "channel=native-interop-receipt status=${nativeReceipt.status} phase=$phase renderPolicy=no-render " +
            "libraryLoaded=$nativeReceiptLibraryLoaded nativeReceiptMask=${nativeReceipt.mask} " +
            "nativeReceiptOpenXrInstanceHandleNonZero=${nativeReceipt.openXrInstanceHandleNonZero} " +
            "nativeReceiptOpenXrSessionHandleNonZero=${nativeReceipt.openXrSessionHandleNonZero} " +
            "nativeReceiptOpenXrGetInstanceProcAddrHandleNonZero=${nativeReceipt.openXrGetInstanceProcAddrHandleNonZero} " +
            "nativeReceiptOpenXrGetInstanceProcAddrCallable=${nativeReceipt.openXrGetInstanceProcAddrCallable} " +
            "nativeReceiptXrGetInstancePropertiesResolved=${nativeReceipt.xrGetInstancePropertiesResolved} " +
            "nativeReceiptXrGetInstancePropertiesSucceeded=${nativeReceipt.xrGetInstancePropertiesSucceeded} " +
            "nativeReceiptXrGetSystemResolved=${nativeReceipt.xrGetSystemResolved} " +
            "nativeReceiptXrGetSystemSucceeded=${nativeReceipt.xrGetSystemSucceeded} " +
            "nativeReceiptXrVulkanGraphicsRequirements2Resolved=${nativeReceipt.xrVulkanGraphicsRequirements2Resolved} " +
            "nativeReceiptXrVulkanGraphicsRequirements2Succeeded=${nativeReceipt.xrVulkanGraphicsRequirements2Succeeded} " +
            "nativeReceiptXrCreateVulkanInstanceResolved=${nativeReceipt.xrCreateVulkanInstanceResolved} " +
            "nativeReceiptXrGetVulkanGraphicsDevice2Resolved=${nativeReceipt.xrGetVulkanGraphicsDevice2Resolved} " +
            "nativeReceiptXrCreateVulkanDeviceResolved=${nativeReceipt.xrCreateVulkanDeviceResolved} " +
            "nativeReceiptVkInstanceCreated=${nativeReceipt.vkInstanceCreated} " +
            "nativeReceiptVkGraphicsDeviceObtained=${nativeReceipt.vkGraphicsDeviceObtained} " +
            "nativeReceiptVkGraphicsComputeQueueFound=${nativeReceipt.vkGraphicsComputeQueueFound} " +
            "nativeReceiptVkDeviceCreated=${nativeReceipt.vkDeviceCreated} " +
            "nativeReceiptVkQueueObtained=${nativeReceipt.vkQueueObtained} " +
            "nativeReceiptVkObjectsDestroyed=${nativeReceipt.vkObjectsDestroyed} " +
            "nativeReceiptSurfaceValid=${nativeReceipt.surfaceValid} error=${markerToken(nativeReceipt.error)}"
    )
    requestSpatialMultimodalInputIfReady(probe, phase)
    startNativeSpatialControllerActionsIfReady(probe, phase)
  }

  private fun loadNativeReceiptLibrary() {
    val result = runCatching { System.loadLibrary(NATIVE_RECEIPT_LIBRARY) }
    nativeReceiptLibraryLoaded = result.isSuccess
    nativeReceiptLibraryError = result.exceptionOrNull()?.javaClass?.simpleName ?: "none"
    marker(
        "channel=native-interop-receipt status=library-load library=$NATIVE_RECEIPT_LIBRARY " +
            "loaded=$nativeReceiptLibraryLoaded error=${markerToken(nativeReceiptLibraryError)}"
    )
  }

  private fun recordNativeInteropReceipt(
      probe: SpatialNativeInteropProbe,
      surfaceProbe: NativeInteropSurfaceProbeResult,
  ): NativeInteropReceiptResult {
    if (!nativeReceiptLibraryLoaded) {
      return NativeInteropReceiptResult(
          status = "library-unavailable",
          mask = 0L,
          openXrInstanceHandleNonZero = false,
          openXrSessionHandleNonZero = false,
          openXrGetInstanceProcAddrHandleNonZero = false,
          openXrGetInstanceProcAddrCallable = false,
          xrGetInstancePropertiesResolved = false,
          xrGetInstancePropertiesSucceeded = false,
          xrGetSystemResolved = false,
          xrGetSystemSucceeded = false,
          xrVulkanGraphicsRequirements2Resolved = false,
          xrVulkanGraphicsRequirements2Succeeded = false,
          xrCreateVulkanInstanceResolved = false,
          xrGetVulkanGraphicsDevice2Resolved = false,
          xrCreateVulkanDeviceResolved = false,
          vkInstanceCreated = false,
          vkGraphicsDeviceObtained = false,
          vkGraphicsComputeQueueFound = false,
          vkDeviceCreated = false,
          vkQueueObtained = false,
          vkObjectsDestroyed = false,
          surfaceValid = false,
          error = nativeReceiptLibraryError,
      )
    }
    return runCatching {
          val mask =
              nativeRecordNoRenderInteropReceipt(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  surfaceProbe.surfaceValid,
              )
          NativeInteropReceiptResult(
              status = "received",
              mask = mask,
              openXrInstanceHandleNonZero = mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_INSTANCE_BIT),
              openXrSessionHandleNonZero = mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_SESSION_BIT),
              openXrGetInstanceProcAddrHandleNonZero =
                  mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_GET_PROC_BIT),
              openXrGetInstanceProcAddrCallable =
                  mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_GET_PROC_CALLABLE_BIT),
              xrGetInstancePropertiesResolved =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED_BIT),
              xrGetInstancePropertiesSucceeded =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED_BIT),
              xrGetSystemResolved = mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_SYSTEM_RESOLVED_BIT),
              xrGetSystemSucceeded =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_SYSTEM_SUCCEEDED_BIT),
              xrVulkanGraphicsRequirements2Resolved =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_RESOLVED_BIT),
              xrVulkanGraphicsRequirements2Succeeded =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED_BIT),
              xrCreateVulkanInstanceResolved =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED_BIT),
              xrGetVulkanGraphicsDevice2Resolved =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_VULKAN_GRAPHICS_DEVICE2_RESOLVED_BIT),
              xrCreateVulkanDeviceResolved =
                  mask.hasReceiptBit(NATIVE_RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED_BIT),
              vkInstanceCreated = mask.hasReceiptBit(NATIVE_RECEIPT_VK_INSTANCE_CREATED_BIT),
              vkGraphicsDeviceObtained =
                  mask.hasReceiptBit(NATIVE_RECEIPT_VK_GRAPHICS_DEVICE_OBTAINED_BIT),
              vkGraphicsComputeQueueFound =
                  mask.hasReceiptBit(NATIVE_RECEIPT_VK_GRAPHICS_COMPUTE_QUEUE_FOUND_BIT),
              vkDeviceCreated = mask.hasReceiptBit(NATIVE_RECEIPT_VK_DEVICE_CREATED_BIT),
              vkQueueObtained = mask.hasReceiptBit(NATIVE_RECEIPT_VK_QUEUE_OBTAINED_BIT),
              vkObjectsDestroyed = mask.hasReceiptBit(NATIVE_RECEIPT_VK_OBJECTS_DESTROYED_BIT),
              surfaceValid = mask.hasReceiptBit(NATIVE_RECEIPT_PANEL_SURFACE_BIT),
              error = "none",
          )
        }
            .getOrElse { throwable ->
              NativeInteropReceiptResult(
              status = "call-failed",
              mask = 0L,
              openXrInstanceHandleNonZero = false,
              openXrSessionHandleNonZero = false,
              openXrGetInstanceProcAddrHandleNonZero = false,
              openXrGetInstanceProcAddrCallable = false,
              xrGetInstancePropertiesResolved = false,
              xrGetInstancePropertiesSucceeded = false,
              xrGetSystemResolved = false,
              xrGetSystemSucceeded = false,
              xrVulkanGraphicsRequirements2Resolved = false,
              xrVulkanGraphicsRequirements2Succeeded = false,
              xrCreateVulkanInstanceResolved = false,
              xrGetVulkanGraphicsDevice2Resolved = false,
              xrCreateVulkanDeviceResolved = false,
              vkInstanceCreated = false,
              vkGraphicsDeviceObtained = false,
              vkGraphicsComputeQueueFound = false,
              vkDeviceCreated = false,
              vkQueueObtained = false,
              vkObjectsDestroyed = false,
              surfaceValid = false,
              error = throwable.javaClass.simpleName,
          )
        }
  }

  private fun startSpatialNativePassthroughForDepthPrerequisite(source: String): Long {
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=spatial-native-passthrough status=library-unavailable " +
              "source=${markerToken(source)} nativePassthroughRequested=true " +
              "nativePassthroughLayerActive=false error=${markerToken(nativeReceiptLibraryError)}"
      )
      return 0L
    }
    val probe =
        runCatching { SpatialNativeInteropProbe.capture(scene) }
            .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      marker(
          "channel=spatial-native-passthrough status=deferred " +
              "source=${markerToken(source)} nativePassthroughRequested=true " +
              "nativePassthroughLayerActive=false openXrHandlesReady=false " +
              "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
              "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
              "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
              "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
      )
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
                  "channel=spatial-native-passthrough status=start-call-failed " +
                      "source=${markerToken(source)} nativePassthroughRequested=true " +
                      "nativePassthroughLayerActive=false error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
              )
              0L
            }
    marker(
        "channel=spatial-native-passthrough status=start-requested " +
            "source=${markerToken(source)} nativePassthroughRequested=true " +
            "nativePassthroughStartMask=$mask " +
            "nativePassthroughLayerActive=${mask.hasReceiptBit(SPATIAL_NATIVE_PASSTHROUGH_LAYER_ACTIVE_BIT)} " +
            "nativePassthroughActivationPath=spatial-native-receipt-xr-fb-passthrough " +
            "nativePassthroughCompositionLayerSubmission=spatial-sdk-owned-end-frame " +
            "spatialScenePassthroughMaterialActive=${cameraHwbProjectionEntity != null} " +
            "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
            "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
            "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
            "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
    )
    return mask
  }

  private fun startSpatialEnvironmentDepthProbe(source: String): Long {
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=spatial-environment-depth status=library-unavailable " +
              "source=${markerToken(source)} environmentDepthProviderRequested=true " +
              "environmentDepthRealProviderBound=false error=${markerToken(nativeReceiptLibraryError)}"
      )
      nativeSpatialEnvironmentDepthStartMask = 0L
      return 0L
    }
    val probe =
        runCatching { SpatialNativeInteropProbe.capture(scene) }
            .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      marker(
          "channel=spatial-environment-depth status=deferred " +
              "source=${markerToken(source)} environmentDepthProviderRequested=true " +
              "environmentDepthRealProviderBound=false openXrHandlesReady=false " +
              "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
              "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
              "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
              "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
      )
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
                  "channel=spatial-environment-depth status=start-call-failed " +
                      "source=${markerToken(source)} environmentDepthProviderRequested=true " +
                      "environmentDepthRealProviderBound=false error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
              )
              0L
            }
    nativeSpatialEnvironmentDepthStartMask = mask
    marker(
        "channel=spatial-environment-depth status=start-requested " +
            "source=${markerToken(source)} environmentDepthProviderRequested=true " +
            "nativeEnvironmentDepthStartMask=$mask " +
            "environmentDepthRealProviderBound=${mask.hasReceiptBit(SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_STARTED_BIT)} " +
            "environmentDepthAcquireThreadStarted=${mask.hasReceiptBit(SPATIAL_ENVIRONMENT_DEPTH_ACQUIRE_THREAD_STARTED_BIT)} " +
            "environmentDepthAcquireStatus=see-native-logcat " +
            "environmentDepthAcquireDisplayTimePolicy=diagnostic-zero-time " +
            "spatialSdkOwnsFrameLoop=true " +
            "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
            "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
            "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
            "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
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
      marker(
          "channel=spatial-multimodal-input status=disabled-by-property phase=$phase " +
              "spatialMultimodalInputRequest=false " +
              "spatialMultimodalRequiredOpenXrExtensions=none " +
              "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()} " +
              "property=$SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY"
      )
      return
    }
    if (
        !probe.openXrInstanceHandleNonZero ||
            !probe.openXrSessionHandleNonZero ||
            !probe.openXrGetInstanceProcAddrHandleNonZero
    ) {
      marker(
          "channel=spatial-multimodal-input status=request-deferred phase=$phase " +
              "spatialMultimodalInputRequest=true openXrHandlesReady=false " +
              "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()}"
      )
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
                  "channel=spatial-multimodal-input status=request-error phase=$phase " +
                      "spatialMultimodalInputRequest=true " +
                      "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")}"
              )
              0L
            }
    spatialMultimodalInputRequested = true
    spatialMultimodalInputRequestMask = requestMask
    marker(
        "channel=spatial-multimodal-input status=request-result phase=$phase " +
            "spatialMultimodalInputRequest=true requestMask=$requestMask " +
            "supportsSimultaneousHandsAndControllers=${requestMask.hasReceiptBit(SPATIAL_MULTIMODAL_INPUT_SUPPORTED_BIT)} " +
            "resumeFunctionResolved=${requestMask.hasReceiptBit(SPATIAL_MULTIMODAL_INPUT_RESUME_RESOLVED_BIT)} " +
            "resumeSucceeded=${requestMask.hasReceiptBit(SPATIAL_MULTIMODAL_INPUT_RESUME_SUCCEEDED_BIT)} " +
            "inputOwnership=spatial-sdk-interaction-sdk " +
            "spatialRequiredOpenXrExtensions=${spatialRequiredOpenXrExtensionMarker()} " +
            "property=$SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY"
    )
  }

  private fun runSdkQuadSurfaceProbeIfRequested(reason: String) {
    if (sdkQuadSurfaceProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(SDK_QUAD_SURFACE_PROBE_PROPERTY) != true) {
      return
    }
    sdkQuadSurfaceProbeStarted = true
    val holdMs =
        readLongSystemProperty(
            SDK_QUAD_SURFACE_PROBE_HOLD_MS_PROPERTY,
            SDK_QUAD_SURFACE_PROBE_DEFAULT_HOLD_MS,
            SDK_QUAD_SURFACE_PROBE_MIN_HOLD_MS,
            SDK_QUAD_SURFACE_PROBE_MAX_HOLD_MS,
        )
    marker(
        "channel=sdk-owned-quad-surface-probe status=start sdkQuadSurfaceProbe=true " +
            "reason=${markerToken(reason)} debugProperty=$SDK_QUAD_SURFACE_PROBE_PROPERTY " +
            "widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX " +
            "holdMs=$holdMs producer=android-canvas nativeVulkanProducer=false " +
            "videoSurfacePanelRegistration=false externalSwapchain=false privateShaderStack=false"
    )
    Handler(Looper.getMainLooper()).post { runSdkQuadSurfaceProbe(holdMs) }
  }

  private fun runSdkQuadVulkanProbeIfRequested(reason: String) {
    if (sdkQuadVulkanProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(SDK_QUAD_VULKAN_PROBE_PROPERTY) != true) {
      return
    }
    sdkQuadVulkanProbeStarted = true
    val holdMs =
        readLongSystemProperty(
            SDK_QUAD_VULKAN_PROBE_HOLD_MS_PROPERTY,
            SDK_QUAD_VULKAN_PROBE_DEFAULT_HOLD_MS,
            SDK_QUAD_VULKAN_PROBE_MIN_HOLD_MS,
            SDK_QUAD_VULKAN_PROBE_MAX_HOLD_MS,
        )
    val frameCount =
        readIntSystemProperty(
            SDK_QUAD_VULKAN_PROBE_FRAME_COUNT_PROPERTY,
            SDK_QUAD_VULKAN_PROBE_DEFAULT_FRAME_COUNT,
            1,
            SDK_QUAD_VULKAN_PROBE_MAX_FRAME_COUNT,
        )
    marker(
        "channel=sdk-owned-quad-vulkan-probe status=start sdkQuadVulkanProbe=true " +
            "reason=${markerToken(reason)} debugProperty=$SDK_QUAD_VULKAN_PROBE_PROPERTY " +
            "widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX " +
            "holdMs=$holdMs requestedFrames=$frameCount producer=native-vulkan-wsi " +
            "renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi " +
            "videoSurfacePanelRegistration=false externalSwapchain=false privateShaderStack=false"
    )
    Handler(Looper.getMainLooper()).post { runSdkQuadVulkanProbe(holdMs, frameCount) }
  }

  private fun runSdkQuadVulkanProbe(holdMs: Long, frameCount: Int) {
    cleanupSdkQuadSurfaceProbe("vulkan-pre-run")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
              "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
              "nativeStartRequested=false nativeVulkanProducer=false firstFramePresented=false " +
              "manualSceneQuadLayerViable=false error=${markerToken(nativeReceiptLibraryError)} " +
              "runtimeCrash=false"
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
                  "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
                      "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
                      "nativeStartRequested=false nativeVulkanProducer=false firstFramePresented=false " +
                      "manualSceneQuadLayerViable=false error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  "channel=sdk-owned-quad-vulkan-probe status=get-surface-failed " +
                      "sdkQuadVulkanProbe=true handle=${sdkSwapchain.handle} " +
                      "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        "channel=sdk-owned-quad-vulkan-probe status=sdk-swapchain-created " +
            "sdkQuadVulkanProbe=true sdkSwapchainCreated=true handle=${sdkSwapchain.handle} " +
            "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
            "surfaceValid=$surfaceValid widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX " +
            "heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX"
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-surface-invalid")
      marker(
          "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false nativeVulkanProducer=false firstFramePresented=false " +
              "manualSceneQuadLayerViable=false cleanupStatus=$cleanupStatus runtimeCrash=false"
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
        "channel=sdk-owned-quad-vulkan-probe status=layer-created " +
            "sdkQuadVulkanProbe=true sceneQuadLayerCreated=$layerCreated " +
            "manualSceneQuadLayerViable=$layerCreated anchorMode=generated-single-sided-quad " +
            "stereoMode=None producer=native-vulkan-wsi"
    )
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-layer-create-failed")
      marker(
          "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false nativeVulkanProducer=false firstFramePresented=false " +
              "manualSceneQuadLayerViable=false cleanupStatus=$cleanupStatus runtimeCrash=false"
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
                  "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
                      "nativeStartRequested=false nativeVulkanProducer=false firstFramePresented=false " +
                      "manualSceneQuadLayerViable=true cleanupStatus=$cleanupStatus " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    marker(
        "channel=sdk-owned-quad-vulkan-probe status=native-start-requested " +
            "sdkQuadVulkanProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
            "sceneQuadLayerCreated=true manualSceneQuadLayerViable=true nativeStartRequested=true " +
            "nativeVulkanProducer=true startMask=$startMask requestedFrames=$frameCount " +
            "holdMs=$holdMs renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi"
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (nativeReceiptLibraryLoaded) {
                runCatching { nativeStopSdkQuadVulkanProbe() }
              }
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("vulkan-hold-complete")
              marker(
                  "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
                      "manualSceneQuadLayerViable=true nativeStartRequested=true nativeVulkanProducer=true " +
                      "firstFramePresented=see-native-logcat requestedFrames=$frameCount " +
                      "cleanupStatus=$cleanupStatus runtimeCrash=false"
              )
            },
            holdMs,
        )
  }

  private fun runSdkQuadStereoAlphaProbeIfRequested(reason: String) {
    if (sdkQuadStereoAlphaProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY) != true) {
      return
    }
    sdkQuadStereoAlphaProbeStarted = true
    val holdMs =
        readLongSystemProperty(
            SDK_QUAD_STEREO_ALPHA_PROBE_HOLD_MS_PROPERTY,
            SDK_QUAD_STEREO_ALPHA_PROBE_DEFAULT_HOLD_MS,
            SDK_QUAD_STEREO_ALPHA_PROBE_MIN_HOLD_MS,
            SDK_QUAD_STEREO_ALPHA_PROBE_MAX_HOLD_MS,
        )
    marker(
        "channel=sdk-owned-quad-stereo-alpha-probe status=start " +
            "sdkQuadStereoAlphaProbe=true reason=${markerToken(reason)} " +
            "debugProperty=$SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY " +
            "widthPx=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX " +
            "heightPx=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX " +
            "perEyeExtentPx=${SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX}x$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX " +
            "stereoMode=LeftRight producer=android-canvas nativeVulkanProducer=false " +
            "setClipPlanned=true alphaBlendPlanned=true colorScaleAlphaPlanned=true " +
            "zIndexChangePlanned=true holdMs=$holdMs"
    )
    Handler(Looper.getMainLooper()).post { runSdkQuadStereoAlphaProbe(holdMs) }
  }

  private fun runPanelSurfaceMatrixProbeIfRequested(reason: String) {
    if (panelSurfaceMatrixProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(PANEL_SURFACE_MATRIX_PROBE_PROPERTY) != true) {
      return
    }
    panelSurfaceMatrixProbeStarted = true
    marker(
        "channel=panel-surface-matrix-probe status=start panelSurfaceMatrixProbe=true " +
            "reason=${markerToken(reason)} debugProperty=$PANEL_SURFACE_MATRIX_PROBE_PROPERTY " +
            "widthPx=$PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX heightPx=$PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX " +
            "variants=useSwapchain-true-useTexture-false,useSwapchain-false-useTexture-true " +
            "sceneQuadLayerBackedByPanelSurfaceSwapchainPlanned=true nativeVulkanProducerPlanned=true"
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
      marker(
          "channel=spatial-controller-actions status=disabled-by-property phase=$phase " +
              "nativeControllerActionBridge=false property=$NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY " +
              "reason=spatial-sdk-vrfeature-owns-openxr-action-sets"
      )
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
      marker(
          "channel=spatial-controller-actions status=start-deferred phase=$phase " +
              "nativeControllerActionBridge=true openXrHandlesReady=false"
      )
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
                  "channel=spatial-controller-actions status=start-error phase=$phase " +
                      "nativeControllerActionBridge=true error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      "actionSetAttached=false"
              )
              0L
            }
    nativeSpatialControllerActionsStartMask = startMask
    nativeSpatialControllerActionsStarted =
        startMask.hasReceiptBit(NATIVE_SPATIAL_CONTROLLER_ACTION_SET_ATTACHED_BIT)
    marker(
        "channel=spatial-controller-actions status=start-result phase=$phase " +
            "nativeControllerActionBridge=true startMask=$startMask " +
            "actionSetAttached=$nativeSpatialControllerActionsStarted " +
            "leftThumbstickYAction=$nativeSpatialControllerActionsStarted " +
            "leftControllerThumbstickY=/user/hand/left/input/thumbstick/y"
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
                  "channel=panel-surface-matrix-probe status=variant-complete " +
                      "panelSurfaceMatrixProbe=true variant=$variantName panelSurfaceCreated=false " +
                      "surfaceValid=false swapchainNonNull=false textureNonNull=false " +
                      "swapchainBacksSceneQuadLayer=false nativeVulkanStartRequested=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
        "channel=panel-surface-matrix-probe status=variant-created " +
            "panelSurfaceMatrixProbe=true variant=$variantName panelSurfaceCreated=true " +
            "surfaceValid=$surfaceValid swapchainNonNull=$swapchainNonNull textureNonNull=$textureNonNull " +
            "widthPx=${created.widthInPx} heightPx=${created.heightInPx} mips=${created.mips} " +
            "reportedUseSwapchain=${created.useSwapchain} reportedUseTexture=${created.useTexture}"
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
        "channel=panel-surface-matrix-probe status=scenequadlayer-attempted " +
            "panelSurfaceMatrixProbe=true variant=$variantName swapchainNonNull=$swapchainNonNull " +
            "swapchainBacksSceneQuadLayer=$layerCreated anchorMode=generated-single-sided-quad"
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
                    "channel=panel-surface-matrix-probe status=native-start-failed " +
                        "panelSurfaceMatrixProbe=true variant=$variantName nativeVulkanStartRequested=false " +
                        "error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
                )
                0L
              }
        } else {
          0L
        }
    val nativeStartRequested = nativeStartMask != 0L
    marker(
        "channel=panel-surface-matrix-probe status=native-start-attempted " +
            "panelSurfaceMatrixProbe=true variant=$variantName surfaceValid=$surfaceValid " +
            "nativeReceiptLibraryLoaded=$nativeReceiptLibraryLoaded nativeVulkanStartRequested=$nativeStartRequested " +
            "nativeStartMask=$nativeStartMask"
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
                  "channel=panel-surface-matrix-probe status=variant-complete " +
                      "panelSurfaceMatrixProbe=true variant=$variantName panelSurfaceCreated=true " +
                      "surfaceValid=$surfaceValid swapchainNonNull=$swapchainNonNull textureNonNull=$textureNonNull " +
                      "swapchainBacksSceneQuadLayer=$layerCreated nativeVulkanStartRequested=$nativeStartRequested " +
                      "nativeStartMask=$nativeStartMask sceneCleanupStatus=$sceneCleanupStatus " +
                      "panelSurfaceDestroyed=$panelSurfaceDestroyed runtimeCrash=false"
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
    marker(
        "channel=panel-surface-matrix-probe status=complete panelSurfaceMatrixProbe=true " +
        "variantsTested=2 runtimeCrash=false"
    )
  }

  private fun runCameraHwbProbeIfRequested(reason: String) {
    if (cameraHwbProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true) {
      return
    }
    if (readOptionalBooleanSystemProperty(CAMERA_HWB_PROBE_PROPERTY) != true) {
      return
    }
    cameraHwbProbeStarted = true
    val holdMs =
        readLongSystemProperty(
            CAMERA_HWB_PROBE_HOLD_MS_PROPERTY,
            CAMERA_HWB_PROBE_DEFAULT_HOLD_MS,
            CAMERA_HWB_PROBE_MIN_HOLD_MS,
            CAMERA_HWB_PROBE_MAX_HOLD_MS,
        )
    val frameCount =
        readIntSystemProperty(
            CAMERA_HWB_PROBE_FRAME_COUNT_PROPERTY,
            CAMERA_HWB_PROBE_DEFAULT_FRAME_COUNT,
            1,
            CAMERA_HWB_PROBE_MAX_FRAME_COUNT,
        )
    val readerMaxImages =
        readIntSystemProperty(
            CAMERA_HWB_PROBE_READER_MAX_IMAGES_PROPERTY,
            CAMERA_HWB_PROBE_DEFAULT_READER_MAX_IMAGES,
            CAMERA_HWB_PROBE_MIN_READER_MAX_IMAGES,
            CAMERA_HWB_PROBE_MAX_READER_MAX_IMAGES,
        )
    marker(
        "channel=camera-hwb-spatial-probe status=start cameraHwbProbe=true " +
            "reason=${markerToken(reason)} debugProperty=$CAMERA_HWB_PROBE_PROPERTY " +
            "widthPx=$CAMERA_HWB_PROBE_WIDTH_PX heightPx=$CAMERA_HWB_PROBE_HEIGHT_PX " +
            "requestedFrames=$frameCount holdMs=$holdMs readerMaxImages=$readerMaxImages " +
            "cameraPreference=50-then-51 carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
            "outputMode=luma-checker ${SpatialPublicMultiStack.inactiveMarkerFields()} " +
            "privateShaderStack=false customProjectionStack=false"
    )
    Handler(Looper.getMainLooper()).post { runCameraHwbProbe(holdMs, frameCount, readerMaxImages) }
  }

  private fun runCameraHwbProbe(holdMs: Long, frameCount: Int, readerMaxImages: Int) {
    cleanupSdkQuadSurfaceProbe("camera-hwb-pre-run")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true " +
              "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
              "nativeStartRequested=false sampledCameraTexture=false " +
              "error=${markerToken(nativeReceiptLibraryError)} runtimeCrash=false"
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
                  "channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true " +
                      "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
                      "nativeStartRequested=false sampledCameraTexture=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  "channel=camera-hwb-spatial-probe status=get-surface-failed " +
                      "cameraHwbProbe=true handle=${sdkSwapchain.handle} " +
                      "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        "channel=camera-hwb-spatial-probe status=sdk-swapchain-created cameraHwbProbe=true " +
            "sdkSwapchainCreated=true handle=${sdkSwapchain.handle} " +
            "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
            "surfaceValid=$surfaceValid widthPx=$CAMERA_HWB_PROBE_WIDTH_PX heightPx=$CAMERA_HWB_PROBE_HEIGHT_PX"
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-surface-invalid")
      marker(
          "channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false sampledCameraTexture=false cleanupStatus=$cleanupStatus runtimeCrash=false"
      )
      return
    }

    val layerCreated = createCameraHwbProbeLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-layer-create-failed")
      marker(
          "channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false sampledCameraTexture=false cleanupStatus=$cleanupStatus runtimeCrash=false"
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
                  "channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
                      "nativeStartRequested=false sampledCameraTexture=false cleanupStatus=$cleanupStatus " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    marker(
        "channel=camera-hwb-spatial-probe status=native-start-requested cameraHwbProbe=true " +
            "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
            "nativeStartRequested=true startMask=$startMask requestedFrames=$frameCount " +
            "readerMaxImages=$readerMaxImages holdMs=$holdMs " +
            "carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
            "${SpatialPublicMultiStack.inactiveMarkerFields()} " +
            "privateShaderStack=false customProjectionStack=false"
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (nativeReceiptLibraryLoaded) {
                runCatching { nativeStopCameraHwbProbe() }
              }
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-hold-complete")
              marker(
                  "channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
                      "nativeStartRequested=true firstCameraFramePresented=see-native-logcat " +
                      "sampledCameraTexture=see-native-logcat cleanupStatus=$cleanupStatus runtimeCrash=false"
              )
            },
            holdMs,
        )
  }

  private fun runSpatialVideoProjectionProbeIfRequested(reason: String) {
    if (spatialVideoProjectionProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) != true) {
      return
    }
    if (!spatialSceneReady) {
      marker(
          "channel=spatial-video-projection status=start-deferred " +
              "reason=${markerToken(reason)} deferredUntil=scene-ready " +
              "sceneReady=false runtimeCrash=false"
      )
      return
    }
    if (spatialVirtualRoomEnabled() && !spatialVirtualRoomLoaded) {
      marker(
          "channel=spatial-video-projection status=start-deferred " +
              "reason=${markerToken(reason)} deferredUntil=virtual-room-loaded " +
              "sceneReady=$spatialSceneReady spatialVirtualRoomLoaded=false runtimeCrash=false"
      )
      return
    }
    spatialVideoProjectionProbeStarted = true
    val videoSettings = currentSpatialVideoProjectionSettings(intent)
    marker(
        "channel=spatial-video-projection status=start videoOnlySpatialProjection=true " +
            "reason=${markerToken(reason)} debugProperty=$SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY " +
            "widthPx=$CAMERA_HWB_PROJECTION_WIDTH_PX heightPx=$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
            "requestedFrames=0 frameLimit=none carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
            "cameraRuntimeStarted=false rawCameraProjectionProbe=false " +
            cameraHwbProjectionMarkerFields() + " " +
            cameraHwbProjectionStereoMarkerFields() + " " +
            spatialVideoProjectionMarkerFields(videoSettings) + " " +
            "outputMode=video-only-full-sbs sampledCameraTexture=false " +
            "privateShaderStack=false customProjectionStack=false"
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
      marker(
          "channel=spatial-video-projection status=complete videoOnlySpatialProjection=true " +
              "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
              "nativeStartRequested=false cameraRuntimeStarted=false " +
              "error=video-path-missing " +
              spatialVideoProjectionMarkerFields(videoSettings) + " runtimeCrash=false"
      )
      return
    }
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=spatial-video-projection status=complete videoOnlySpatialProjection=true " +
              "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
              "nativeStartRequested=false cameraRuntimeStarted=false " +
              "error=${markerToken(nativeReceiptLibraryError)} runtimeCrash=false"
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
                  "channel=spatial-video-projection status=complete videoOnlySpatialProjection=true " +
                      "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
                      "nativeStartRequested=false cameraRuntimeStarted=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  "channel=spatial-video-projection status=get-surface-failed " +
                      "videoOnlySpatialProjection=true handle=${sdkSwapchain.handle} " +
                      "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        "channel=spatial-video-projection status=sdk-swapchain-created videoOnlySpatialProjection=true " +
            "sdkSwapchainCreated=true handle=${sdkSwapchain.handle} " +
            "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
            "surfaceValid=$surfaceValid widthPx=$CAMERA_HWB_PROJECTION_WIDTH_PX " +
            "heightPx=$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
            "carrier=scenequadlayer-createAsAndroid-vulkan-wsi cameraRuntimeStarted=false " +
            cameraHwbProjectionStereoMarkerFields() + " " +
            spatialVideoProjectionMarkerFields(videoSettings)
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("spatial-video-projection-surface-invalid")
      marker(
          "channel=spatial-video-projection status=complete videoOnlySpatialProjection=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false cameraRuntimeStarted=false cleanupStatus=$cleanupStatus " +
              "runtimeCrash=false"
      )
      return
    }

    val layerCreated = createCameraHwbProjectionLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("spatial-video-projection-layer-create-failed")
      marker(
          "channel=spatial-video-projection status=complete videoOnlySpatialProjection=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false cameraRuntimeStarted=false cleanupStatus=$cleanupStatus " +
              "runtimeCrash=false"
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
                  "channel=spatial-video-projection status=complete videoOnlySpatialProjection=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
                      "nativeStartRequested=false cameraRuntimeStarted=false cleanupStatus=$cleanupStatus " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    marker(
        "channel=spatial-video-projection status=native-start-requested videoOnlySpatialProjection=true " +
            "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
            "nativeStartRequested=true startMask=$startMask requestedFrames=0 frameLimit=none " +
            "carrier=scenequadlayer-createAsAndroid-vulkan-wsi cameraRuntimeStarted=false " +
            "sampledCameraTexture=false outputMode=video-only-full-sbs " +
            spatialVideoProjectionMarkerFields(videoSettings) + " runtimeCrash=false"
    )
    updateCameraHwbProjectionFromViewer(reason = "video-only-start", forceLog = true)
  }

  private fun runCameraHwbProjectionProbeIfRequested(reason: String) {
    if (cameraHwbProjectionProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) != true) {
      return
    }
    if (!spatialSceneReady) {
      marker(
          "channel=camera-hwb-spatial-probe status=start-deferred " +
              "reason=${markerToken(reason)} deferredUntil=scene-ready " +
              "sceneReady=false runtimeCrash=false"
      )
      return
    }
    if (spatialVirtualRoomEnabled() && !spatialVirtualRoomLoaded) {
      marker(
          "channel=camera-hwb-spatial-probe status=start-deferred " +
              "reason=${markerToken(reason)} deferredUntil=virtual-room-loaded " +
              "sceneReady=$spatialSceneReady spatialVirtualRoomLoaded=false runtimeCrash=false"
      )
      return
    }
    cameraHwbProjectionProbeStarted = true
    val readerMaxImages =
        readIntSystemProperty(
            CAMERA_HWB_PROJECTION_READER_MAX_IMAGES_PROPERTY,
            CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES,
            CAMERA_HWB_PROJECTION_MIN_READER_MAX_IMAGES,
            CAMERA_HWB_PROJECTION_MAX_READER_MAX_IMAGES,
        )
    val videoSettings = currentSpatialVideoProjectionSettings(intent)
    marker(
        "channel=camera-hwb-spatial-probe status=start rawCameraProjectionProbe=true " +
            "reason=${markerToken(reason)} debugProperty=$CAMERA_HWB_PROJECTION_PROBE_PROPERTY " +
            "widthPx=$CAMERA_HWB_PROJECTION_WIDTH_PX heightPx=$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
            "requestedFrames=0 frameLimit=none holdMs=none readerMaxImages=$readerMaxImages " +
            "cameraPreference=50-then-51 carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
            cameraHwbProjectionMarkerFields() + " " +
            cameraHwbProjectionStereoMarkerFields() + " " +
            spatialVideoProjectionMarkerFields(videoSettings) + " " +
            SpatialPublicMultiStack.markerFields() + " " +
            "outputMode=raw-color-target-rect sampledCameraTexture=true " +
            "sampledLeftCameraTexture=true sampledRightCameraTexture=true monoDuplicated=false " +
            "sampledCameraTextureSource=native-camera-hwb-pending-first-frame " +
            "privateShaderStack=false " +
            "customProjectionStack=false"
    )
    Handler(Looper.getMainLooper()).post { runCameraHwbProjectionProbe(readerMaxImages, videoSettings) }
  }

  private fun runCameraHwbProjectionProbe(
      readerMaxImages: Int,
      videoSettings: SpatialVideoProjectionSettings,
  ) {
    cleanupSdkQuadSurfaceProbe("camera-hwb-projection-pre-run")
    spatialVideoProjectionSettings = videoSettings
    cameraHwbProjectionEntity = null
    cameraHwbProjectionTargetScale = initialCameraHwbProjectionTargetScale()
    cameraHwbProjectionStereoHorizontalOffsetUv =
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
    privateLayerDepthLayerPolicy = initialPrivateLayerDepthLayerPolicy()
    cameraHwbProjectionMarkerCount = 0
    lastCameraHwbProjectionMarkerMs = 0L
    lastCameraHwbProjectionScaleJoystickMs = 0L
    lastCameraHwbProjectionScaleJoystickMarkerMs = 0L
    cameraHwbProjectionSecondaryToggleArmed = false
    suppressParticleLayerForCameraStack("camera-hwb-projection-probe")
    privateLayerPanelVisible = false
    setWorkflowPanelVisible(false, focus = false, source = "camera-hwb-projection-probe")
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=camera-hwb-spatial-probe status=complete rawCameraProjectionProbe=true " +
              "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
              "nativeStartRequested=false sampledCameraTexture=false " +
              "error=${markerToken(nativeReceiptLibraryError)} runtimeCrash=false"
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
                  "channel=camera-hwb-spatial-probe status=complete rawCameraProjectionProbe=true " +
                      "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
                      "nativeStartRequested=false sampledCameraTexture=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  "channel=camera-hwb-spatial-probe status=get-surface-failed " +
                      "rawCameraProjectionProbe=true handle=${sdkSwapchain.handle} " +
                      "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        "channel=camera-hwb-spatial-probe status=sdk-swapchain-created rawCameraProjectionProbe=true " +
            "sdkSwapchainCreated=true handle=${sdkSwapchain.handle} " +
            "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
            "surfaceValid=$surfaceValid widthPx=$CAMERA_HWB_PROJECTION_WIDTH_PX " +
            "heightPx=$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
            "carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
            cameraHwbProjectionStereoMarkerFields() + " " +
            SpatialPublicMultiStack.markerFields()
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-projection-surface-invalid")
      marker(
          "channel=camera-hwb-spatial-probe status=complete rawCameraProjectionProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false sampledCameraTexture=false cleanupStatus=$cleanupStatus " +
              "runtimeCrash=false"
      )
      return
    }

    val layerCreated = createCameraHwbProjectionLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("camera-hwb-projection-layer-create-failed")
      marker(
          "channel=camera-hwb-spatial-probe status=complete rawCameraProjectionProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=false " +
              "nativeStartRequested=false sampledCameraTexture=false cleanupStatus=$cleanupStatus " +
              "runtimeCrash=false"
      )
      return
    }

    val nativePassthroughStartMask =
        startSpatialNativePassthroughForDepthPrerequisite("raw-projection-start")
    val nativePassthroughLayerActive =
        nativePassthroughStartMask.hasReceiptBit(SPATIAL_NATIVE_PASSTHROUGH_LAYER_ACTIVE_BIT)
    val nativeEnvironmentDepthStartMask =
        startSpatialEnvironmentDepthProbe("raw-projection-start")
    val nativeEnvironmentDepthProviderBound =
        nativeEnvironmentDepthStartMask.hasReceiptBit(SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_STARTED_BIT)
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
                  "channel=camera-hwb-spatial-probe status=complete rawCameraProjectionProbe=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
                      "nativeStartRequested=false sampledCameraTexture=false cleanupStatus=$cleanupStatus " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    marker(
        "channel=camera-hwb-spatial-probe status=native-start-requested rawCameraProjectionProbe=true " +
            "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
            "nativeStartRequested=true startMask=$startMask requestedFrames=0 frameLimit=none " +
            "readerMaxImages=$readerMaxImages carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
            cameraHwbProjectionMarkerFields() + " " +
            cameraHwbProjectionStereoMarkerFields() + " " +
            spatialVideoProjectionMarkerFields(videoSettings) + " " +
            SpatialPublicMultiStack.markerFields(
                nativePassthroughLayerActive = nativePassthroughLayerActive,
                nativeEnvironmentDepthProviderRequested = true,
                nativeEnvironmentDepthProviderBound = nativeEnvironmentDepthProviderBound,
            ) + " " +
            "nativePassthroughStartMask=$nativePassthroughStartMask " +
            "nativeEnvironmentDepthStartMask=$nativeEnvironmentDepthStartMask " +
            "outputMode=raw-color-target-rect sampledCameraTexture=see-native-logcat " +
            "sampledLeftCameraTexture=see-native-logcat sampledRightCameraTexture=see-native-logcat " +
            "monoDuplicated=false " +
            "privateShaderStack=false customProjectionStack=false runtimeCrash=false"
    )
    updateCameraHwbProjectionFromViewer(reason = "raw-projection-start", forceLog = true)
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
                  "channel=sdk-owned-quad-stereo-alpha-probe status=complete " +
                      "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=false surfaceValid=false " +
                      "canvasDrawn=false sceneQuadLayerCreated=false stereoMode=LeftRight " +
                      "setClipApplied=false alphaBlendApplied=false zIndexChanged=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  "channel=sdk-owned-quad-stereo-alpha-probe status=get-surface-failed " +
                      "sdkQuadStereoAlphaProbe=true handle=${sdkSwapchain.handle} " +
                      "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        "channel=sdk-owned-quad-stereo-alpha-probe status=sdk-swapchain-created " +
            "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true handle=${sdkSwapchain.handle} " +
            "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
            "surfaceValid=$surfaceValid widthPx=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX " +
            "heightPx=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX"
    )
    if (!surfaceValid) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("stereo-alpha-surface-invalid")
      marker(
          "channel=sdk-owned-quad-stereo-alpha-probe status=complete " +
              "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
              "canvasDrawn=false sceneQuadLayerCreated=false stereoMode=LeftRight " +
              "setClipApplied=false alphaBlendApplied=false zIndexChanged=false " +
              "cleanupStatus=$cleanupStatus runtimeCrash=false"
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
        "channel=sdk-owned-quad-stereo-alpha-probe status=visible-window " +
            "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
            "canvasDrawn=$canvasDrawn sceneQuadLayerCreated=$layerCreated " +
            "manualSceneQuadLayerViable=$viable stereoMode=LeftRight " +
            "leftEyePattern=red-grid rightEyePattern=blue-grid " +
            "expectedUvOrientation=left-half-to-left-eye-right-half-to-right-eye " +
            "eyeLeakageCheck=operator-visible-required croppingCheck=operator-visible-required " +
            "alphaConventionCheck=operator-visible-required holdMs=$holdMs runtimeCrash=false"
    )
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("stereo-alpha-layer-create-failed")
      marker(
          "channel=sdk-owned-quad-stereo-alpha-probe status=complete " +
              "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
              "canvasDrawn=$canvasDrawn sceneQuadLayerCreated=false stereoMode=LeftRight " +
              "setClipApplied=false alphaBlendApplied=false zIndexChanged=false " +
              "cleanupStatus=$cleanupStatus runtimeCrash=false"
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
                      marker(
                          "channel=sdk-owned-quad-stereo-alpha-probe status=z-index-updated " +
                              "sdkQuadStereoAlphaProbe=true zIndexChanged=true " +
                              "zIndex=${SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH} runtimeCrash=false"
                      )
                    }
                    .onFailure { throwable ->
                      marker(
                          "channel=sdk-owned-quad-stereo-alpha-probe status=z-index-update-failed " +
                              "sdkQuadStereoAlphaProbe=true zIndexChanged=false " +
                              "error=${markerToken(throwable.javaClass.simpleName)} " +
                              "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
                      marker(
                          "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-updated " +
                              "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=true " +
                              "alpha=${markerFloat(SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW)} " +
                              "alphaConvention=premultiplied-unknown-source-alpha-blend-factors runtimeCrash=false"
                      )
                    }
                    .onFailure { throwable ->
                      marker(
                          "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-update-failed " +
                              "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=false " +
                              "error=${markerToken(throwable.javaClass.simpleName)} runtimeCrash=false"
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
                      marker(
                          "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-restored " +
                              "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=true " +
                              "alpha=1.0000 runtimeCrash=false"
                      )
                    }
                    .onFailure { throwable ->
                      marker(
                          "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-restore-failed " +
                              "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=false " +
                              "error=${markerToken(throwable.javaClass.simpleName)} runtimeCrash=false"
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
                  "channel=sdk-owned-quad-stereo-alpha-probe status=complete " +
                      "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
                      "canvasDrawn=$canvasDrawn sceneQuadLayerCreated=$layerCreated " +
                      "manualSceneQuadLayerViable=$viable stereoMode=LeftRight " +
                      "setClipApplied=true alphaBlendApplied=true colorScaleAlphaApplied=true " +
                      "zIndexChanged=$sdkQuadStereoAlphaProbeZIndexChanged cleanupStatus=$cleanupStatus " +
                      "eyeLeakageCheck=operator-visible-required " +
                      "uvOrientationCheck=operator-visible-required " +
                      "alphaConventionCheck=operator-visible-required runtimeCrash=false"
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
                "channel=sdk-owned-quad-stereo-alpha-probe status=layer-created " +
                    "sdkQuadStereoAlphaProbe=true sceneQuadLayerCreated=true canvasDrawn=$canvasDrawn " +
                    "anchorMode=generated-single-sided-quad sceneObjectHandle=${sceneObject.handle} " +
                    "widthMeters=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS " +
                    "heightMeters=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS " +
                    "zIndex=${SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW} stereoMode=LeftRight " +
                    "setClipApplied=true clipUv=0.04;0.04;0.96;0.96 " +
                    "alphaBlendApplied=true sourceFactorColor=SOURCE_ALPHA " +
                    "destinationFactorColor=ONE_MINUS_SOURCE_ALPHA sourceFactorAlpha=ONE " +
                    "destinationFactorAlpha=ONE_MINUS_SOURCE_ALPHA " +
                    "colorScaleAlphaApplied=true alpha=${markerFloat(SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH)} " +
                    "poseSource=Scene.getViewerPose layerPositionM=${vectorMarker(pose.t)} " +
                    "layerQuaternion=${quaternionMarker(pose.q)}"
            )
            true
          }
          .getOrElse { throwable ->
            marker(
                "channel=sdk-owned-quad-stereo-alpha-probe status=layer-create-failed " +
                    "sdkQuadStereoAlphaProbe=true sceneQuadLayerCreated=false canvasDrawn=$canvasDrawn " +
                    "stereoMode=LeftRight error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
            )
            false
          }

  private fun drawSdkQuadStereoAlphaPattern(surface: AndroidSurface): Boolean {
    if (!surface.isValid) {
      marker(
          "channel=sdk-owned-quad-stereo-alpha-probe status=canvas-draw-skipped " +
              "sdkQuadStereoAlphaProbe=true reason=surface-invalid canvasDrawn=false"
      )
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
              "channel=sdk-owned-quad-stereo-alpha-probe status=canvas-draw-complete " +
                  "sdkQuadStereoAlphaProbe=true canvasDrawn=$drawn widthPx=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX " +
                  "heightPx=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX " +
                  "leftEyePattern=red-grid rightEyePattern=blue-grid " +
                  "perEyeExtentPx=${SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX}x$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX"
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked ->
            runCatching { surface.unlockCanvasAndPost(locked) }
          }
          marker(
              "channel=sdk-owned-quad-stereo-alpha-probe status=canvas-draw-failed " +
                  "sdkQuadStereoAlphaProbe=true canvasDrawn=false " +
                  "error=${markerToken(throwable.javaClass.simpleName)} " +
                  "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
                  "channel=sdk-owned-quad-surface-probe status=complete sdkQuadSurfaceProbe=true " +
                      "sdkSwapchainCreated=false surfaceValid=false canvasDrawn=false " +
                      "sceneQuadLayerCreated=false manualSceneQuadLayerViable=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              return
            }
    sdkQuadSurfaceProbeSwapchain = sdkSwapchain
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              marker(
                  "channel=sdk-owned-quad-surface-probe status=get-surface-failed " +
                      "sdkQuadSurfaceProbe=true handle=${sdkSwapchain.handle} " +
                      "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              null
            }
    sdkQuadSurfaceProbeSurface = surface
    val surfaceValid = surface?.isValid == true
    marker(
        "channel=sdk-owned-quad-surface-probe status=sdk-swapchain-created " +
            "sdkQuadSurfaceProbe=true sdkSwapchainCreated=true handle=${sdkSwapchain.handle} " +
            "nativeHandle=${sdkSwapchain.nativeHandle()} platformHandle=${sdkSwapchain.platformHandle()} " +
            "surfaceValid=$surfaceValid widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX " +
            "heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX"
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
        "channel=sdk-owned-quad-surface-probe status=visible-window sdkQuadSurfaceProbe=true " +
            "sdkSwapchainCreated=true surfaceValid=$surfaceValid canvasDrawn=$canvasDrawn " +
            "sceneQuadLayerCreated=$layerCreated manualSceneQuadLayerViable=$viable " +
            "plainEntitySceneObjectLayerCreated=$plainEntityLayerCreated anchorMode=$anchorMode " +
            "nativeVulkanProducer=false visiblePatternConfirmed=false " +
            "humanVisiblePatternCheckRequired=true holdMs=$holdMs runtimeCrash=false"
    )
    if (!layerCreated) {
      val cleanupStatus = cleanupSdkQuadSurfaceProbe("layer-create-failed")
      marker(
          "channel=sdk-owned-quad-surface-probe status=complete sdkQuadSurfaceProbe=true " +
              "sdkSwapchainCreated=true surfaceValid=$surfaceValid canvasDrawn=$canvasDrawn " +
              "sceneQuadLayerCreated=false manualSceneQuadLayerViable=false cleanupStatus=$cleanupStatus " +
              "plainEntitySceneObjectLayerCreated=$plainEntityLayerCreated anchorMode=$anchorMode " +
              "nativeVulkanProducer=false visiblePatternConfirmed=false runtimeCrash=false"
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val cleanupStatus = cleanupSdkQuadSurfaceProbe("hold-complete")
              marker(
                  "channel=sdk-owned-quad-surface-probe status=complete sdkQuadSurfaceProbe=true " +
                      "sdkSwapchainCreated=true surfaceValid=$surfaceValid canvasDrawn=$canvasDrawn " +
                      "sceneQuadLayerCreated=$layerCreated manualSceneQuadLayerViable=$viable " +
                      "plainEntitySceneObjectLayerCreated=$plainEntityLayerCreated anchorMode=$anchorMode " +
                      "cleanupStatus=$cleanupStatus nativeVulkanProducer=false " +
                      "visiblePatternConfirmed=false humanVisiblePatternCheckRequired=true runtimeCrash=false"
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
                "channel=sdk-owned-quad-surface-probe status=layer-created " +
                    "sdkQuadSurfaceProbe=true sceneQuadLayerCreated=true " +
                    "canvasDrawn=$canvasDrawn anchorMode=$anchorMode " +
                    "sceneObjectHandle=${sceneObject.handle} " +
                    "widthMeters=$SDK_QUAD_SURFACE_PROBE_WIDTH_METERS " +
                    "heightMeters=$SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS zIndex=$SDK_QUAD_SURFACE_PROBE_Z_INDEX " +
                    "stereoMode=None poseSource=Scene.getViewerPose " +
                    "layerPositionM=${vectorMarker(pose.t)} layerQuaternion=${quaternionMarker(pose.q)}"
            )
            true
          }
          .getOrElse { throwable ->
            marker(
                "channel=sdk-owned-quad-surface-probe status=layer-create-failed " +
                    "sdkQuadSurfaceProbe=true sceneQuadLayerCreated=false canvasDrawn=$canvasDrawn " +
                    "anchorMode=$anchorMode error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
                "channel=camera-hwb-spatial-probe status=layer-created cameraHwbProbe=true " +
                    "sceneQuadLayerCreated=true anchorMode=generated-single-sided-quad " +
                    "sceneObjectHandle=${sceneObject.handle} widthMeters=$CAMERA_HWB_PROBE_WIDTH_METERS " +
                    "heightMeters=$CAMERA_HWB_PROBE_HEIGHT_METERS zIndex=$CAMERA_HWB_PROBE_Z_INDEX " +
                    "stereoMode=None carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
                    "poseSource=Scene.getViewerPose layerPositionM=${vectorMarker(pose.t)} " +
                    "layerQuaternion=${quaternionMarker(pose.q)}"
            )
            true
          }
          .getOrElse { throwable ->
            marker(
                "channel=camera-hwb-spatial-probe status=layer-create-failed cameraHwbProbe=true " +
                    "sceneQuadLayerCreated=false anchorMode=generated-single-sided-quad " +
                    "error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
            )
            false
          }

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
                    "channel=camera-hwb-spatial-probe status=raw-camera-projection-layer-created " +
                    "rawCameraProjectionProbe=true sceneQuadLayerCreated=true " +
                    "anchorMode=generated-single-sided-quad sceneObjectHandle=${sceneObject.handle} " +
                    "widthMeters=${markerFloat(plane.projectionWidthMeters)} " +
                    "heightMeters=${markerFloat(plane.projectionHeightMeters)} " +
                    "zIndex=$layerZIndex " +
                    "carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
                    cameraHwbProjectionMarkerFields(plane) + " " +
                    cameraHwbProjectionStereoMarkerFields() + " " +
                    spatialVideoProjectionMarkerFields(spatialVideoProjectionSettings) + " " +
                    SpatialPublicMultiStack.markerFields() + " " +
                    "poseSource=${cameraHwbProjectionPoseSourceToken(plane)} viewerPositionM=${vectorMarker(plane.viewerPosition)} " +
                    "viewerForward=${vectorMarker(plane.forward)} viewerUp=${vectorMarker(plane.up)} " +
                    "viewerRight=${vectorMarker(plane.right)} planeCenterM=${vectorMarker(plane.center)} " +
                    "planeQuaternion=${quaternionMarker(plane.pose.q)} " +
                    "leftEyeOffsetM=${vectorMarker(plane.leftEyeOffset)} " +
                    "rightEyeOffsetM=${vectorMarker(plane.rightEyeOffset)} " +
                    "outputMode=raw-color-target-rect sampledCameraTexture=true " +
                    "sampledLeftCameraTexture=true sampledRightCameraTexture=true monoDuplicated=false " +
                    "sampledCameraTextureSource=native-camera-hwb-pending-first-frame " +
                    "privateShaderStack=false " +
                    "customProjectionStack=false runtimeCrash=false"
            )
            true
          }
          .getOrElse { throwable ->
            cameraHwbProjectionEntity = null
            marker(
                "channel=camera-hwb-spatial-probe status=layer-create-failed " +
                    "rawCameraProjectionProbe=true sceneQuadLayerCreated=false " +
                    "anchorMode=generated-single-sided-quad " +
                    "error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
            )
            false
          }

  private fun drawSdkQuadSurfaceCheckerboard(surface: AndroidSurface): Boolean {
    if (!surface.isValid) {
      marker(
          "channel=sdk-owned-quad-surface-probe status=canvas-draw-skipped " +
              "sdkQuadSurfaceProbe=true reason=surface-invalid canvasDrawn=false"
      )
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
          marker(
              "channel=sdk-owned-quad-surface-probe status=canvas-draw-complete " +
                  "sdkQuadSurfaceProbe=true canvasDrawn=$drawn checkerCells=$SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS " +
                  "producer=android-canvas widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX " +
                  "heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX"
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked ->
            runCatching { surface.unlockCanvasAndPost(locked) }
          }
          marker(
              "channel=sdk-owned-quad-surface-probe status=canvas-draw-failed " +
                  "sdkQuadSurfaceProbe=true canvasDrawn=false " +
                  "error=${markerToken(throwable.javaClass.simpleName)} " +
                  "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
          "channel=sdk-owned-quad-surface-probe status=scene-anchor-destroyed " +
              "sdkQuadSurfaceProbe=true reason=${markerToken(reason)} " +
              "layerDestroyed=$layerDestroyed sceneObjectDestroyed=$sceneObjectDestroyed " +
              "anchorMeshDestroyed=$meshDestroyed anchorMaterialDestroyed=$materialDestroyed " +
              "cleanupStatus=$cleanupStatus runtimeCrash=false"
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
          "channel=sdk-owned-quad-surface-probe status=destroyed sdkQuadSurfaceProbe=true " +
              "reason=${markerToken(reason)} sceneCleanupStatus=$sceneCleanupStatus " +
              "swapchainDestroyed=$swapchainDestroyed " +
              "cleanupStatus=$cleanupStatus runtimeCrash=false"
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
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val center = viewerPose.t + forward * SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  private fun runExternalSwapchainProbeIfRequested(reason: String) {
    if (externalSwapchainProbeStarted) {
      return
    }
    if (readOptionalBooleanSystemProperty(EXTERNAL_SWAPCHAIN_PROBE_PROPERTY) != true) {
      return
    }
    externalSwapchainProbeStarted = true
    val cycles =
        readIntSystemProperty(
            EXTERNAL_SWAPCHAIN_PROBE_CYCLES_PROPERTY,
            EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLES,
            1,
            EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLES,
        )
    val cycleMs =
        readLongSystemProperty(
            EXTERNAL_SWAPCHAIN_PROBE_CYCLE_MS_PROPERTY,
            EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLE_MS,
            EXTERNAL_SWAPCHAIN_PROBE_MIN_CYCLE_MS,
            EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLE_MS,
        )
    marker(
        "channel=external-xr-swapchain-wrap-probe status=start externalSwapchainProbe=true " +
            "reason=${markerToken(reason)} cycles=$cycles cycleMs=$cycleMs " +
            "debugProperty=$EXTERNAL_SWAPCHAIN_PROBE_PROPERTY rendererAuthority=spatial-sdk-openxr-session " +
            "nativeFrameLoop=false customProjectionStack=false camera2Stack=false privateShaderStack=false"
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
          "channel=external-xr-swapchain-wrap-probe status=complete externalSwapchainProbe=true " +
              "cycleIndex=$cycleIndex cycleCount=$cycleCount sdkHandleWrapMode=none " +
              "xrCreateSwapchainResult=library-unavailable wrappedExternalSwapchain=false " +
              "sceneQuadLayerCreated=false swapchainImagesEnumerated=0 nativeCanRenderIntoImages=false " +
              "visiblePatternConfirmed=false destroyOwnership=unknown deviceLost=false runtimeCrash=false " +
              "error=${markerToken(nativeReceiptLibraryError)}"
      )
      return
    }

    val probe = SpatialNativeInteropProbe.capture(scene)
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      marker(
          "channel=external-xr-swapchain-wrap-probe status=complete externalSwapchainProbe=true " +
              "cycleIndex=$cycleIndex cycleCount=$cycleCount sdkHandleWrapMode=none " +
              "xrCreateSwapchainResult=missing-openxr-handles wrappedExternalSwapchain=false " +
              "sceneQuadLayerCreated=false swapchainImagesEnumerated=0 nativeCanRenderIntoImages=false " +
              "visiblePatternConfirmed=false destroyOwnership=unknown deviceLost=false runtimeCrash=false " +
              "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
              "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
              "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero}"
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
                  "channel=external-xr-swapchain-wrap-probe status=native-create-call-failed " +
                      "externalSwapchainProbe=true cycleIndex=$cycleIndex " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              0L
            }
    externalSwapchainProbeExternalHandle = externalHandle
    if (externalHandle == 0L) {
      marker(
          "channel=external-xr-swapchain-wrap-probe status=complete externalSwapchainProbe=true " +
              "cycleIndex=$cycleIndex cycleCount=$cycleCount sdkHandleWrapMode=$sdkHandleWrapMode " +
              "xrCreateSwapchainResult=failed-or-zero-handle wrappedExternalSwapchain=false " +
              "sceneQuadLayerCreated=false swapchainImagesEnumerated=0 nativeCanRenderIntoImages=false " +
              "visiblePatternConfirmed=false destroyOwnership=unknown deviceLost=false runtimeCrash=false"
      )
      return
    }

    val wrapped =
        runCatching { SceneSwapchain(externalHandle) }
            .getOrElse { throwable ->
              marker(
                  "channel=external-xr-swapchain-wrap-probe status=external-wrap-failed " +
                      "externalSwapchainProbe=true cycleIndex=$cycleIndex externalHandle=$externalHandle " +
                      "sdkHandleWrapMode=$sdkHandleWrapMode wrappedExternalSwapchain=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              val ownership = cleanupExternalSwapchainProbe("cycle-$cycleIndex-wrap-failed")
              marker(
                  "channel=external-xr-swapchain-wrap-probe status=complete externalSwapchainProbe=true " +
                      "cycleIndex=$cycleIndex cycleCount=$cycleCount sdkHandleWrapMode=$sdkHandleWrapMode " +
                      "xrCreateSwapchainResult=success wrappedExternalSwapchain=false " +
                      "sceneQuadLayerCreated=false swapchainImagesEnumerated=see-native-marker " +
                      "nativeCanRenderIntoImages=false visiblePatternConfirmed=false " +
                      "destroyOwnership=$ownership deviceLost=false runtimeCrash=false"
              )
              return
            }
    externalSwapchainProbeWrappedSwapchain = wrapped
    marker(
        "channel=external-xr-swapchain-wrap-probe status=external-wrap-result " +
            "externalSwapchainProbe=true cycleIndex=$cycleIndex externalHandle=$externalHandle " +
            "wrappedExternalSwapchain=true wrapperHandle=${wrapped.handle} " +
            "wrapperNativeHandle=${wrapped.nativeHandle()} wrapperPlatformHandle=${wrapped.platformHandle()} " +
            "wrapperSurfaceValid=false wrapperSurfaceProbe=skipped-raw-external-getSurface-crashes " +
            "platformHandleMatchesExternal=${wrapped.platformHandle() == externalHandle} " +
            "nativeHandleMatchesExternal=${wrapped.nativeHandle() == externalHandle} " +
            "handleMatchesExternal=${wrapped.handle == externalHandle}"
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
                  "channel=external-xr-swapchain-wrap-probe status=layer-created " +
                      "externalSwapchainProbe=true cycleIndex=$cycleIndex sceneQuadLayerCreated=true " +
                      "widthMeters=$EXTERNAL_SWAPCHAIN_PROBE_WIDTH_METERS " +
                      "heightMeters=$EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_METERS " +
                      "stereoMode=None poseSource=Scene.getViewerPose " +
                      "layerPositionM=${vectorMarker(pose.t)} layerQuaternion=${quaternionMarker(pose.q)}"
              )
              true
            }
            .getOrElse { throwable ->
              marker(
                  "channel=external-xr-swapchain-wrap-probe status=layer-create-failed " +
                      "externalSwapchainProbe=true cycleIndex=$cycleIndex sceneQuadLayerCreated=false " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              false
            }

    marker(
        "channel=external-xr-swapchain-wrap-probe status=cycle-visible externalSwapchainProbe=true " +
            "cycleIndex=$cycleIndex cycleCount=$cycleCount sdkHandleWrapMode=$sdkHandleWrapMode " +
            "xrCreateSwapchainResult=success wrappedExternalSwapchain=true " +
            "sceneQuadLayerCreated=$layerCreated swapchainImagesEnumerated=see-native-marker " +
            "nativeCanRenderIntoImages=false visiblePatternConfirmed=false " +
            "renderBlockReason=missing-spatial-sdk-vulkan-device-queue " +
            "destroyOwnership=pending deviceLost=false runtimeCrash=false"
    )
    if (!layerCreated) {
      val ownership = cleanupExternalSwapchainProbe("cycle-$cycleIndex-layer-create-failed")
      marker(
          "channel=external-xr-swapchain-wrap-probe status=complete externalSwapchainProbe=true " +
              "cycleIndex=$cycleIndex cycleCount=$cycleCount sdkHandleWrapMode=$sdkHandleWrapMode " +
              "xrCreateSwapchainResult=success wrappedExternalSwapchain=true sceneQuadLayerCreated=false " +
              "swapchainImagesEnumerated=see-native-marker nativeCanRenderIntoImages=false " +
              "visiblePatternConfirmed=false destroyOwnership=$ownership deviceLost=false " +
              "runtimeCrash=false lifecycleTortureSkipped=scene-quad-layer-create-failed"
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val ownership = cleanupExternalSwapchainProbe("cycle-$cycleIndex-destroy")
              marker(
                  "channel=external-xr-swapchain-wrap-probe status=cycle-complete " +
                      "externalSwapchainProbe=true cycleIndex=$cycleIndex cycleCount=$cycleCount " +
                      "sdkHandleWrapMode=$sdkHandleWrapMode xrCreateSwapchainResult=success " +
                      "wrappedExternalSwapchain=true sceneQuadLayerCreated=$layerCreated " +
                      "swapchainImagesEnumerated=see-native-marker nativeCanRenderIntoImages=false " +
                      "visiblePatternConfirmed=false destroyOwnership=$ownership " +
                      "deviceLost=false runtimeCrash=false"
              )
              if (cycleIndex < cycleCount) {
                Handler(Looper.getMainLooper())
                    .postDelayed(
                        { runExternalSwapchainProbeCycle(cycleIndex + 1, cycleCount, cycleMs) },
                        EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS,
                    )
              } else {
                marker(
                    "channel=external-xr-swapchain-wrap-probe status=complete " +
                        "externalSwapchainProbe=true cycleCount=$cycleCount sdkHandleWrapMode=$sdkHandleWrapMode " +
                        "xrCreateSwapchainResult=success wrappedExternalSwapchain=true " +
                        "sceneQuadLayerCreated=$layerCreated swapchainImagesEnumerated=see-native-marker " +
                        "nativeCanRenderIntoImages=false visiblePatternConfirmed=false " +
                        "destroyOwnership=$ownership deviceLost=false runtimeCrash=false"
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
                  "channel=external-xr-swapchain-wrap-probe status=sdk-swapchain-create-failed " +
                      "externalSwapchainProbe=true cycleIndex=$cycleIndex sdkHandleWrapMode=none " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")}"
              )
              return "none"
            }
    val sdkSurfaceValid = runCatching { sdkSwap.getSurface()?.isValid == true }.getOrDefault(false)
    marker(
        "channel=external-xr-swapchain-wrap-probe status=sdk-swapchain-created " +
            "externalSwapchainProbe=true cycleIndex=$cycleIndex handle=${sdkSwap.handle} " +
            "nativeHandle=${sdkSwap.nativeHandle()} platformHandle=${sdkSwap.platformHandle()} " +
            "surfaceValid=$sdkSurfaceValid"
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
                "channel=external-xr-swapchain-wrap-probe status=sdk-handle-wrap-result " +
                    "externalSwapchainProbe=true cycleIndex=$cycleIndex handleLabel=$label " +
                    "sourceHandle=$handle wrapped=false error=zero-handle sdkWrapDestroySkipped=true"
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
                    "channel=external-xr-swapchain-wrap-probe status=sdk-handle-wrap-result " +
                        "externalSwapchainProbe=true cycleIndex=$cycleIndex handleLabel=$label " +
                        "sourceHandle=$handle wrapped=true wrapperHandle=${wrapper.handle} " +
                        "wrapperNativeHandle=${wrapper.nativeHandle()} " +
                        "wrapperPlatformHandle=${wrapper.platformHandle()} " +
                        "wrapperSurfaceValid=$wrapperSurfaceValid sdkWrapDestroySkipped=true"
                )
              }
              .onFailure { throwable ->
                marker(
                    "channel=external-xr-swapchain-wrap-probe status=sdk-handle-wrap-result " +
                        "externalSwapchainProbe=true cycleIndex=$cycleIndex handleLabel=$label " +
                        "sourceHandle=$handle wrapped=false " +
                        "error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} sdkWrapDestroySkipped=true"
                )
              }
        }
    runCatching { sdkSwap.destroy() }
        .onFailure { throwable ->
          marker(
              "channel=external-xr-swapchain-wrap-probe status=sdk-swapchain-destroy-failed " +
                  "externalSwapchainProbe=true cycleIndex=$cycleIndex " +
                  "error=${markerToken(throwable.javaClass.simpleName)}"
          )
        }
    marker(
        "channel=external-xr-swapchain-wrap-probe status=sdk-handle-wrap-summary " +
            "externalSwapchainProbe=true cycleIndex=$cycleIndex sdkHandleWrapMode=$firstSuccess"
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
                    "channel=external-xr-swapchain-wrap-probe status=native-destroy-call-failed " +
                        "externalSwapchainProbe=true reason=${markerToken(reason)} " +
                        "externalHandle=$externalHandle error=${markerToken(throwable.javaClass.simpleName)}"
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
          "channel=external-xr-swapchain-wrap-probe status=destroyed externalSwapchainProbe=true " +
              "reason=${markerToken(reason)} layerDestroyed=$layerDestroyed " +
              "sceneObjectDestroyed=$sceneObjectDestroyed wrapperDestroyed=$wrapperDestroyed " +
              "wrapperDestroySkipped=$wrapperDestroySkipped " +
              "nativeDestroyResult=$nativeDestroyResult destroyOwnership=$destroyOwnership " +
              "deviceLost=false runtimeCrash=false"
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
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val center = viewerPose.t + forward * EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  private fun startNativeSurfaceParticleLayer(surface: AndroidSurface) {
    if (cameraStackSuppressesParticles) {
      marker(
          "channel=native-surface-particle-layer status=start-suppressed " +
              "renderPolicy=native-vulkan-wsi-surface-panel source=camera-stack " +
              "cameraStackSuppressesParticles=true particleLayerVisible=false " +
              "particleLayerStarted=$particleLayerStarted nativeSurfaceStartRequested=$nativeSurfaceStartRequested"
      )
      return
    }
    if (particleLayerStarted) {
      marker(
          "channel=native-surface-particle-layer status=start-skipped " +
              "renderPolicy=native-vulkan-wsi-surface-panel reason=already-started"
      )
      return
    }
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=native-surface-particle-layer status=library-unavailable " +
              "renderPolicy=native-vulkan-wsi-surface-panel error=${markerToken(nativeReceiptLibraryError)}"
      )
      return
    }
    val surfaceValid = surface.isValid
    val openXrProbe = SpatialNativeInteropProbe.capture(scene)
    if (!surfaceValid) {
      marker(
          "channel=native-surface-particle-layer status=surface-unavailable " +
              "renderPolicy=native-vulkan-wsi-surface-panel surfaceValid=false"
      )
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
              "channel=native-surface-particle-layer status=start-requested " +
                  "renderPolicy=native-vulkan-wsi-surface-panel " +
                  "surfaceValid=$surfaceValid startMask=$startMask " +
                  "liveHandJointInputExpected=true " +
                  "openXrInstanceHandleNonZero=${openXrProbe.openXrInstanceHandleNonZero} " +
                  "openXrSessionHandleNonZero=${openXrProbe.openXrSessionHandleNonZero} " +
                  "openXrGetInstanceProcAddrHandleNonZero=${openXrProbe.openXrGetInstanceProcAddrHandleNonZero} " +
                  "widthPx=$PARTICLE_LAYER_WIDTH_PX heightPx=$PARTICLE_LAYER_HEIGHT_PX " +
                  "particleCount=$PARTICLE_LAYER_PARTICLE_COUNT frameCount=$PARTICLE_LAYER_FRAME_COUNT " +
                  particleLayerPlacementMarkerFields() + " " +
                  particleLayerStereoMarkerFields()
          )
          submitNativeSurfaceParticleParameters(source = "start")
        }
        .getOrElse { throwable ->
          marker(
              "channel=native-surface-particle-layer status=start-failed " +
                  "renderPolicy=native-vulkan-wsi-surface-panel error=${markerToken(throwable.javaClass.simpleName)} " +
                  "message=${markerToken(throwable.message ?: "none")}"
          )
    }
  }

  private fun updateSurfaceParticleControls(
      driver0Value01: Float,
      driver1Value01: Float,
      pointScale: Float,
      source: String = "panel",
  ): SurfaceParticleControlState {
    particleControls =
        SurfaceParticleControlState(
            driver0Value01 = driver0Value01.coerceIn(0.0f, 1.0f),
            driver1Value01 = driver1Value01.coerceIn(0.0f, 1.0f),
            pointScale = pointScale.coerceIn(0.35f, 2.25f),
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
            "source=${markerToken(source)} driverProfileId=${markerToken(block.conditionId)} " +
            "profileId=${markerToken(block.profileId)} " +
            "workflowPanelVisibleAtHandoff=${panelPlacement.visible} " +
            "panelClosedBeforeHandoff=${!panelPlacement.visible} " +
            "profileDriver0Value01=${String.format(Locale.US, "%.3f", block.driver0Value01)} " +
            "profileDriver1Value01=${String.format(Locale.US, "%.3f", block.driver1Value01)} " +
            "driver0Value01=${markerFloat(updated.driver0Value01)} " +
            "driver1Value01=${markerFloat(updated.driver1Value01)} " +
            "pointScale=${markerFloat(updated.pointScale)}"
    )
    return updated
  }

  private fun submitNativeSurfaceParticleParameters(source: String) {
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=native-surface-particle-layer status=parameter-submit-skipped " +
              "renderPolicy=native-vulkan-wsi-surface-panel reason=library-unavailable source=${markerToken(source)}"
      )
      return
    }
    runCatching {
          val mask =
              nativeUpdateSurfaceParticleParameters(
                  particleControls.driver0Value01,
                  particleControls.driver1Value01,
                  particleControls.pointScale,
              )
          marker(
              "channel=native-surface-particle-layer status=parameters-submitted " +
                  "renderPolicy=native-vulkan-wsi-surface-panel transport=jni-live-queue " +
                  "computeParameterBridge=true source=${markerToken(source)} parameterMask=$mask " +
                  "driver0Value01=${"%.3f".format(particleControls.driver0Value01)} " +
                  "driver1Value01=${"%.3f".format(particleControls.driver1Value01)} " +
                  "pointScale=${"%.3f".format(particleControls.pointScale)}"
          )
        }
        .getOrElse { throwable ->
          marker(
              "channel=native-surface-particle-layer status=parameter-submit-failed " +
                  "renderPolicy=native-vulkan-wsi-surface-panel source=${markerToken(source)} " +
                  "error=${markerToken(throwable.javaClass.simpleName)}"
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
                "channel=camera-hwb-spatial-probe status=particle-layer-suppressed " +
                    "source=${markerToken(source)} cameraStackSuppressesParticles=true " +
                    "stopAttempted=true stopSucceeded=true particleLayerVisible=false " +
                    "launcherPanelVisible=${launcherPanelVisibleForPanelMode()} " +
                    "legacyLauncherPanelSuppressed=true launcherPanelSuppressedForCameraStack=true " +
                    "particleLayerStarted=$particleLayerStarted " +
                    "nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
                    "particleLayerRenderContinuity=stopped-for-camera-stack"
            )
          }
          .onFailure { throwable ->
            marker(
                "channel=camera-hwb-spatial-probe status=particle-layer-suppress-failed " +
                    "source=${markerToken(source)} cameraStackSuppressesParticles=true " +
                    "stopAttempted=true stopSucceeded=false particleLayerVisible=false " +
                    "particleLayerStarted=$particleLayerStarted " +
                    "nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
                    "error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")}"
            )
          }
      return
    }
    if (!wasStarted) {
      particleLayerStarted = false
      nativeSurfaceStartRequested = false
    }
    marker(
        "channel=camera-hwb-spatial-probe status=particle-layer-suppressed " +
            "source=${markerToken(source)} cameraStackSuppressesParticles=true " +
            "stopAttempted=$stopAttempted stopSucceeded=true particleLayerVisible=false " +
            "launcherPanelVisible=${launcherPanelVisibleForPanelMode()} " +
            "legacyLauncherPanelSuppressed=true launcherPanelSuppressedForCameraStack=true " +
            "particleLayerStarted=$particleLayerStarted " +
            "nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
            "particleLayerRenderContinuity=stopped-for-camera-stack"
    )
  }

  private fun suppressParticleLayerIfCameraProjectionRequested(source: String) {
    when {
      readOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true ->
          suppressParticleLayerForCameraStack("$source-camera-hwb-projection-property")
      readOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) == true ->
          suppressParticleLayerForCameraStack("$source-spatial-video-projection-property")
    }
  }

  private fun cameraStackOrRoomRequested(): Boolean =
      spatialVirtualRoomEnabled() ||
          readOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true ||
          readOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) == true ||
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
    marker(
        "channel=spatial-panel status=legacy-workflow-panels-deactivated " +
            "source=${markerToken(source)} roomCameraStackLaunch=true " +
            "workflowPanelVisible=false legacyWorkflowPanelVisible=false " +
            "launcherPanelVisible=false legacyLauncherPanelSuppressed=true " +
            "particleLayerVisible=false cameraStackSuppressesParticles=true " +
            "onlyRightPrimaryPrivateLayerPanel=true runtimeCrash=false"
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
                "channel=native-surface-particle-layer status=stopped " +
                    "renderPolicy=native-vulkan-wsi-surface-panel source=${markerToken(source)} " +
                    "particleLayerStarted=$particleLayerStarted " +
                    "nativeSurfaceStartRequested=$nativeSurfaceStartRequested"
            )
          }
          .onFailure { throwable ->
            marker(
                "channel=native-surface-particle-layer status=stop-failed " +
                    "renderPolicy=native-vulkan-wsi-surface-panel source=${markerToken(source)} " +
                    "error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")}"
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
    val yRange =
        if (panelPlacement.headlocked) {
          PANEL_HEADLOCK_OFFSET_Y_MIN_METERS..PANEL_HEADLOCK_OFFSET_Y_MAX_METERS
        } else {
          PANEL_WORLD_Y_MIN_METERS..PANEL_WORLD_Y_MAX_METERS
        }
    val zRange =
        if (panelPlacement.headlocked) {
          PANEL_HEADLOCK_DISTANCE_MIN_METERS..PANEL_HEADLOCK_DISTANCE_MAX_METERS
        } else {
          PANEL_WORLD_Z_MIN_METERS..PANEL_WORLD_Z_MAX_METERS
        }
    panelPlacement =
        panelPlacement.copy(
            xMeters =
                (panelPlacement.xMeters + deltaX)
                    .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
            yMeters = (panelPlacement.yMeters + deltaY).coerceIn(yRange.start, yRange.endInclusive),
            zMeters = (panelPlacement.zMeters + deltaZ).coerceIn(zRange.start, zRange.endInclusive),
            scale = (panelPlacement.scale + deltaScale).coerceIn(0.65f, 1.6f),
        )
    applyPanelPlacement()
    persistPanelHeadlockTuning("compose-placement-buttons")
    marker(
        "channel=spatial-panel status=placement-updated " +
            panelHeadlockMarkerFields() + " " +
            "panelMode=${panelStateToken()} particleLayerRenderContinuity=kept-running"
    )
    return panelPlacement
  }

  private fun resizeWorkflowPanel(deltaWidth: Float, deltaHeight: Float): PanelPlacement {
    panelPlacement =
        panelPlacement.copy(
            widthMeters =
                (panelPlacement.widthMeters + deltaWidth)
                    .coerceIn(PANEL_WIDTH_MIN_METERS, PANEL_WIDTH_MAX_METERS),
            heightMeters =
                (panelPlacement.heightMeters + deltaHeight)
                    .coerceIn(PANEL_HEIGHT_MIN_METERS, PANEL_HEIGHT_MAX_METERS),
    )
    applyPanelPlacement()
    persistPanelHeadlockTuning("compose-panel-resize")
    marker(
        "channel=spatial-panel status=size-updated panelWidth=${markerFloat(panelPlacement.widthMeters)} " +
            "panelHeight=${markerFloat(panelPlacement.heightMeters)} panelMode=${panelStateToken()} " +
            "particleLayerRenderContinuity=kept-running"
    )
    return panelPlacement
  }

  private fun resetWorkflowPanelPlacement(): PanelPlacement {
    privateLayerPanelVisible = false
    privateLayerPanelPlacement = privateLayerPanelPlacement.copy(visible = false)
    panelPlacement =
        panelPlacement.copy(
            visible = true,
            xMeters = PANEL_HEADLOCK_OFFSET_X_METERS,
            yMeters =
                if (panelPlacement.headlocked) PANEL_HEADLOCK_OFFSET_Y_METERS else PANEL_FOCUS_Y_METERS,
            zMeters =
                if (panelPlacement.headlocked) PANEL_FRONT_OF_CAMERA_VIDEO_DISTANCE_METERS
                else PANEL_FOCUS_Z_METERS,
            scale = if (panelPlacement.headlocked) PANEL_FRONT_OF_CAMERA_VIDEO_SCALE else 1.0f,
            widthMeters = PANEL_WIDTH_METERS,
            heightMeters = PANEL_HEIGHT_METERS,
        )
    applyPanelPlacement()
    persistPanelHeadlockTuning("compose-panel-reset")
    recordPanelState("compose-panel-reset")
    marker(
        "channel=spatial-panel status=placement-reset panelMode=${panelStateToken()} " +
            panelHeadlockMarkerFields() + " " +
            "particleLayerRenderContinuity=kept-running"
    )
    return panelPlacement
  }

  private fun setPanelHeadlocked(enabled: Boolean, source: String): PanelPlacement {
    panelPlacement =
        if (enabled) {
          panelPlacement.copy(
              headlocked = true,
              xMeters = panelPlacement.xMeters.coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
              yMeters = panelPlacement.yMeters.coerceIn(PANEL_HEADLOCK_OFFSET_Y_MIN_METERS, PANEL_HEADLOCK_OFFSET_Y_MAX_METERS),
              zMeters = panelPlacement.zMeters.coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS),
          )
        } else {
          panelPlacement.copy(
              headlocked = false,
              yMeters = PANEL_FOCUS_Y_METERS,
              zMeters = PANEL_FOCUS_Z_METERS,
          )
        }
    applyPanelPlacement()
    persistPanelHeadlockTuning(source)
    marker(
        "channel=spatial-panel status=headlock-mode-updated source=${markerToken(source)} " +
            panelHeadlockMarkerFields() + " " +
            "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"
    )
    return panelPlacement
  }

  private fun setWorkflowPanelVisible(
      visible: Boolean,
      focus: Boolean,
      source: String,
  ): PanelPlacement {
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
        "channel=spatial-panel status=mode-updated source=${markerToken(source)} " +
            "panelMode=${panelStateToken()} workflowPanelVisible=${panelPlacement.visible} " +
            "privateLayerPanelVisible=$privateLayerPanelVisible " +
            "launcherPanelVisible=${launcherPanelVisibleForPanelMode()} " +
            "legacyLauncherPanelSuppressed=${legacyLauncherPanelSuppressedForCameraStack()} " +
            "particleLayerVisible=${particleLayerVisibleForPanelMode()} " +
            "particleLayerRenderContinuity=kept-running rendererAuthority=native-vulkan-wsi-surface-panel " +
            "uiAuthority=spatial-sdk-compose-panel " +
            panelHeadlockMarkerFields()
    )
    return panelPlacement
  }

  private fun setPrivateLayerPanelVisible(
      visible: Boolean,
      focus: Boolean,
      source: String,
  ): PanelPlacement {
    if (!visible && !PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) {
      syncPrivateLayerPanelPlacementFromEntity("private-layer-panel-close")
    }
    privateLayerPanelVisible = visible
    privateLayerPanelPlacement =
        if (visible && focus) {
          coercePrivateLayerPanelPlacement(
              privateLayerPanelPlacement.copy(
                  visible = true,
                  headlocked = true,
                  scale = PRIVATE_LAYER_PANEL_SCALE,
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
    marker(
        "channel=private-layer-panel status=mode-updated source=${markerToken(source)} " +
            "panelMode=${panelStateToken()} workflowPanelVisible=${panelPlacement.visible} " +
            "privateLayerPanelVisible=$privateLayerPanelVisible " +
            "launcherPanelVisible=${launcherPanelVisibleForPanelMode()} " +
            "legacyLauncherPanelSuppressed=${legacyLauncherPanelSuppressedForCameraStack()} " +
            "particleLayerVisible=${particleLayerVisibleForPanelMode()} " +
            "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel " +
            "spatialPrivateLayerControlPanel=true " +
            "privateLayerPanelRenderMode=spatial-sdk-mesh " +
            "privateLayerPanelLayerConfig=disabled " +
            "privateLayerPanelWorldSpace=true " +
            "privateLayerPanelGrabbable=true " +
            "privateLayerPanelGrabType=PIVOT_Y " +
            "privateLayerPanelTransformAuthority=spatial-sdk-grabbable-free-transform " +
            "composeDragPanelMovement=false " +
            "privateLayerPanelPoseSource=initial-headset-facing-world-space-then-sdk-owned " +
            "privateLayerPanelDistanceMode=disabled-sdk-free-transform " +
            "privateLayerPanelForcedDistanceDisabled=true " +
            "privateLayerPanelDistanceControl=left-stick-y-free-transform-distance " +
            "leftStickYPanelDistanceEnabled=${currentLeftStickPanelDistanceEnabled()} " +
            "privateLayerPanelInputButtons=button-a+trigger-l+trigger-r " +
            "privateLayerPanelTriggerSelectEnabled=true " +
            "privateLayerPanelGrabButton=controller-squeeze " +
            "panelOpensInFrontOfCameraVideo=${privateLayerPanelPlacement.zMeters < CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS} " +
            "publicMultiStackOpaqueProjectionLayerOverride=${markerFloat(privateLayerOverride)} " +
            panelHeadlockMarkerFields()
    )
    return panelPlacement
  }

  private fun applyPanelPlacement(updatePrivateLayerPanelTransform: Boolean = false) {
    val pose = panelPose()
    panelEntity?.let { entity ->
      entity.setComponent(Transform(pose))
      entity.setComponent(Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale)))
      entity.setComponent(panelDimensions())
      entity.setComponent(Visible(panelPlacement.visible && !privateLayerPanelVisible))
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
      entity.setComponent(Visible(privateLayerPanelVisible && privateLayerPanelPlacement.visible))
    }
    panelLauncherEntity?.setComponent(Transform(panelLauncherPose()))
    panelLauncherEntity?.setComponent(panelLauncherDimensions())
    panelLauncherEntity?.setComponent(Visible(launcherPanelVisibleForPanelMode()))
    particleLayerEntity?.setComponent(Visible(particleLayerVisibleForPanelMode()))
  }

  private fun particleLayerVisibleForPanelMode(): Boolean =
      !panelPlacement.visible && !privateLayerPanelVisible && !cameraStackSuppressesParticles

  private fun launcherPanelVisibleForPanelMode(): Boolean =
      !panelPlacement.visible &&
          !privateLayerPanelVisible &&
          !cameraStackSuppressesParticles &&
          !spatialVirtualRoomEnabled()

  private fun legacyLauncherPanelSuppressedForCameraStack(): Boolean =
      cameraStackSuppressesParticles || spatialVirtualRoomEnabled()

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
      Pose(
          Vector3(panelPlacement.xMeters, panelPlacement.yMeters, panelPlacement.zMeters),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  private fun panelLauncherPose(): Pose =
      Pose(
          Vector3(PANEL_LAUNCHER_X_METERS, PANEL_LAUNCHER_Y_METERS, PANEL_LAUNCHER_Z_METERS),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  private fun privateLayerPanelWorldPose(): Pose =
      Pose(
          Vector3(
              privateLayerPanelPlacement.xMeters,
              privateLayerPanelPlacement.yMeters,
              privateLayerPanelPlacement.zMeters,
          ),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  private fun activeHeadlockedPanelPlacement(): PanelPlacement =
      if (privateLayerPanelVisible) privateLayerPanelPlacement else panelPlacement

  private fun privateLayerPanelGrabbable(enabled: Boolean): Grabbable =
      Grabbable(
          enabled = enabled,
          type = GrabbableType.PIVOT_Y,
          minHeight = PRIVATE_LAYER_PANEL_GRAB_MIN_HEIGHT_METERS,
          maxHeight = PRIVATE_LAYER_PANEL_GRAB_MAX_HEIGHT_METERS,
      )

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun syncPrivateLayerPanelPlacementFromEntity(reason: String): Boolean {
    val pose = privateLayerPanelEntity?.tryGetComponent<Transform>()?.transform ?: return false
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return false
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val viewerUp = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = cross(forward, viewerUp).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val up = cross(right, forward).normalizedOr(viewerUp)
    val offset = vectorSubtract(pose.t, viewerPose.t)
    val distance =
        vectorLength(offset)
            .coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val previous = privateLayerPanelPlacement
    privateLayerPanelPlacement =
        coercePrivateLayerPanelPlacement(
            privateLayerPanelPlacement.copy(
                xMeters = dot(offset, right),
                yMeters = dot(offset, up),
                zMeters = distance,
                visible = privateLayerPanelVisible,
            )
        )
    if (!previous.headlockEquivalent(privateLayerPanelPlacement)) {
      marker(
          "channel=private-layer-panel status=placement-synced-from-sdk-transform " +
              "reason=${markerToken(reason)} privateLayerPanelTransformAuthority=spatial-sdk-grabbable " +
              "composeDragPanelMovement=false previousDistanceMeters=${markerFloat(previous.zMeters)} " +
              panelHeadlockMarkerFields()
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
        "channel=private-layer-panel status=sdk-grabbable-state " +
            "reason=${markerToken(reason)} privateLayerPanelGrabbable=true " +
            "privateLayerPanelGrabType=PIVOT_Y privateLayerPanelIsGrabbed=$grabbed " +
            "privateLayerPanelGrabMinHeightMeters=${markerFloat(PRIVATE_LAYER_PANEL_GRAB_MIN_HEIGHT_METERS)} " +
            "privateLayerPanelGrabMaxHeightMeters=${markerFloat(PRIVATE_LAYER_PANEL_GRAB_MAX_HEIGHT_METERS)} " +
            "privateLayerPanelTransformAuthority=spatial-sdk-grabbable-free-transform " +
            "privateLayerPanelForcedDistanceDisabled=$PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM " +
            "privateLayerPanelDistanceControl=left-stick-y-free-transform-distance " +
            "composeDragPanelMovement=false panelHeaderGrabHandleVisualOnly=true " +
            panelHeadlockMarkerFields()
    )
  }

  private fun coercePrivateLayerPanelPlacement(placement: PanelPlacement): PanelPlacement {
    val distance =
        placement.zMeters.coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val maxLateral =
        (distance * PRIVATE_LAYER_PANEL_MAX_LATERAL_DISTANCE_FRACTION).coerceAtLeast(0.05f)
    val lateralLength =
        sqrt((placement.xMeters * placement.xMeters + placement.yMeters * placement.yMeters).toDouble())
            .toFloat()
    val lateralScale =
        if (lateralLength > maxLateral && lateralLength > 0.000001f) {
          maxLateral / lateralLength
        } else {
          1.0f
        }
    return placement.copy(
        xMeters =
            (placement.xMeters * lateralScale)
                .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
        yMeters =
            (placement.yMeters * lateralScale)
                .coerceIn(PANEL_HEADLOCK_OFFSET_Y_MIN_METERS, PANEL_HEADLOCK_OFFSET_Y_MAX_METERS),
        zMeters = distance,
        scale = placement.scale.coerceIn(PANEL_HEADLOCK_SCALE_MIN, PANEL_HEADLOCK_SCALE_MAX),
        widthMeters = placement.widthMeters.coerceIn(PANEL_WIDTH_MIN_METERS, PANEL_WIDTH_MAX_METERS),
        heightMeters = placement.heightMeters.coerceIn(PANEL_HEIGHT_MIN_METERS, PANEL_HEIGHT_MAX_METERS),
    )
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun headlockedPanelPoseFromViewer(): Pose? {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return null
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = cross(forward, up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
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
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val viewerUp = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = cross(forward, viewerUp).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val up = cross(right, forward).normalizedOr(viewerUp)
    val placement = coercePrivateLayerPanelPlacement(privateLayerPanelPlacement)
    if (placement != privateLayerPanelPlacement) {
      privateLayerPanelPlacement = placement
    }
    val distance = placement.zMeters.coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val lateralSquared = placement.xMeters * placement.xMeters + placement.yMeters * placement.yMeters
    val forwardMeters = sqrt((distance * distance - lateralSquared).coerceAtLeast(0.0f).toDouble()).toFloat()
    val offset = right * placement.xMeters + up * placement.yMeters + forward * forwardMeters
    val direction = offset.normalizedOr(forward)
    val panelUp = (up + direction * -dot(up, direction)).normalizedOr(up)
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
                  marker(
                      "channel=spatial-panel status=headlocked-pose-update-skipped " +
                          "reason=${markerToken(reason)} headlockedPanelEnabled=true " +
                          "viewerPoseSource=Scene.getViewerPose error=unavailable"
                  )
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
    val privatePose =
        if (privateLayerPanelVisible) {
          privateLayerPanelEntity?.tryGetComponent<Transform>()?.transform
        } else {
          null
        }
    if (privateLayerPanelVisible) {
      privateLayerPanelEntity?.let { privatePanel ->
        privatePanel.setComponent(Visible(privateLayerPanelPlacement.visible))
      }
      logPrivateLayerPanelGrabbableState(reason, forceLog)
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
        "channel=spatial-panel status=headlocked-pose-updated " +
            "reason=${markerToken(reason)} viewerPoseSource=Scene.getViewerPose " +
            "panelPoseSource=${if (privateLayerPanelVisible) "spatial-sdk-grabbable-current-transform" else "headlocked-viewer-relative"} " +
            panelHeadlockMarkerFields() + " " +
            "panelPositionM=${vectorMarker((privatePose ?: workflowPose)?.t ?: Vector3(0.0f))} " +
            "panelQuaternion=${quaternionMarker((privatePose ?: workflowPose)?.q ?: Quaternion(1.0f, 0.0f, 0.0f, 0.0f))}"
    )
  }

  private fun pollPanelHeadlockHotload(reason: String) {
    val requestedHeadlocked =
        readOptionalBooleanSystemProperty(PANEL_HEADLOCK_ENABLED_PROPERTY)
            ?: panelPlacement.headlocked
    val updated =
        panelPlacement.copy(
            headlocked = requestedHeadlocked,
            xMeters =
                (readOptionalFloatSystemProperty(
                        PANEL_HEADLOCK_OFFSET_X_PROPERTY,
                        PANEL_HEADLOCK_OFFSET_X_MIN_METERS,
                        PANEL_HEADLOCK_OFFSET_X_MAX_METERS,
                    )
                    ?: panelPlacement.xMeters)
                    .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
            yMeters =
                (readOptionalFloatSystemProperty(
                        PANEL_HEADLOCK_OFFSET_Y_PROPERTY,
                        PANEL_HEADLOCK_OFFSET_Y_MIN_METERS,
                        PANEL_HEADLOCK_OFFSET_Y_MAX_METERS,
                    )
                    ?: panelPlacement.yMeters)
                    .coerceIn(
                        if (requestedHeadlocked) PANEL_HEADLOCK_OFFSET_Y_MIN_METERS else PANEL_WORLD_Y_MIN_METERS,
                        if (requestedHeadlocked) PANEL_HEADLOCK_OFFSET_Y_MAX_METERS else PANEL_WORLD_Y_MAX_METERS,
                    ),
            zMeters =
                (readOptionalFloatSystemProperty(
                        PANEL_HEADLOCK_DISTANCE_PROPERTY,
                        PANEL_HEADLOCK_DISTANCE_MIN_METERS,
                        PANEL_HEADLOCK_DISTANCE_MAX_METERS,
                    )
                    ?: panelPlacement.zMeters)
                    .coerceIn(
                        if (requestedHeadlocked) PANEL_HEADLOCK_DISTANCE_MIN_METERS else PANEL_WORLD_Z_MIN_METERS,
                        if (requestedHeadlocked) PANEL_HEADLOCK_DISTANCE_MAX_METERS else PANEL_WORLD_Z_MAX_METERS,
                    ),
            widthMeters =
                (readOptionalFloatSystemProperty(
                        PANEL_HEADLOCK_WIDTH_PROPERTY,
                        PANEL_WIDTH_MIN_METERS,
                        PANEL_WIDTH_MAX_METERS,
                    )
                    ?: panelPlacement.widthMeters)
                    .coerceIn(PANEL_WIDTH_MIN_METERS, PANEL_WIDTH_MAX_METERS),
            heightMeters =
                (readOptionalFloatSystemProperty(
                        PANEL_HEADLOCK_HEIGHT_PROPERTY,
                        PANEL_HEIGHT_MIN_METERS,
                        PANEL_HEIGHT_MAX_METERS,
                    )
                    ?: panelPlacement.heightMeters)
                    .coerceIn(PANEL_HEIGHT_MIN_METERS, PANEL_HEIGHT_MAX_METERS),
            scale =
                (readOptionalFloatSystemProperty(
                        PANEL_HEADLOCK_SCALE_PROPERTY,
                        PANEL_HEADLOCK_SCALE_MIN,
                        PANEL_HEADLOCK_SCALE_MAX,
                    )
                    ?: panelPlacement.scale)
                    .coerceIn(PANEL_HEADLOCK_SCALE_MIN, PANEL_HEADLOCK_SCALE_MAX),
        )
    if (!panelPlacement.headlockEquivalent(updated)) {
      panelPlacement = updated
      applyPanelPlacement()
      persistPanelHeadlockTuning("runtime-hotload-android-property")
    }
    val token = panelHeadlockMarkerFields()
    if (token != lastPanelHeadlockHotloadToken) {
      lastPanelHeadlockHotloadToken = token
      marker(
          "channel=spatial-panel status=headlock-hotload-updated " +
              "reason=${markerToken(reason)} " +
              "headlockedPanelHotloadSource=runtime-hotload-android-property " +
              panelHeadlockPropertyMarkerFields() + " " +
              token
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
                      if (privateLayerPanelVisible) "disabled-sdk-free-transform"
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
                          .put("distance_mode", "disabled-sdk-free-transform")
                          .put("render_mode", "spatial-sdk-mesh")
                          .put("layer_config", "disabled")
                          .put("scale", privateLayerPanelPlacement.scale.toDouble())
                          .put("width_m", privateLayerPanelPlacement.widthMeters.toDouble())
                          .put("height_m", privateLayerPanelPlacement.heightMeters.toDouble()),
                  )
          File(filesDir, PANEL_HEADLOCK_TUNING_FILE).writeText(row.toString(2), Charsets.UTF_8)
        }
        .getOrElse { throwable ->
          marker(
              "channel=spatial-panel status=headlock-tuning-persist-failed " +
                  "source=${markerToken(source)} error=${markerToken(throwable.javaClass.simpleName)}"
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
            "channel=native-surface-particle-layer status=projection-plane-update-suppressed " +
                "reason=${markerToken(reason)} cameraStackSuppressesParticles=true " +
                "particleLayerVisible=false nativePanelPoseAuthority=camera-hwb-projection-plane"
        )
      }
      return
    }
    val viewerPose =
        runCatching { scene.getViewerPose() }
            .getOrElse { throwable ->
              if (forceLog) {
                marker(
                    "channel=native-surface-particle-layer status=projection-plane-update-skipped " +
                        "reason=${markerToken(reason)} error=${markerToken(throwable.javaClass.simpleName)}"
                )
              }
              return
            }
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = cross(forward, up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val targetDistanceMeters = currentParticleLayerTargetDistanceMeters()
    val projectionWidthMeters = particleLayerProjectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters = particleLayerProjectionHeightMeters(targetDistanceMeters)
    val surfaceOverscanScale = currentParticleLayerSurfaceOverscanScale()
    val surfaceWidthMeters =
        particleLayerSurfaceWidthMeters(targetDistanceMeters, surfaceOverscanScale)
    val surfaceHeightMeters =
        particleLayerSurfaceHeightMeters(targetDistanceMeters, surfaceOverscanScale)
    val previousTargetDistanceMeters = lastParticleLayerTargetDistanceMeters
    val previousSurfaceOverscanScale = lastParticleLayerSurfaceOverscanScale
    if (
        previousTargetDistanceMeters == null ||
            abs(previousTargetDistanceMeters - targetDistanceMeters) >= 0.001f ||
            previousSurfaceOverscanScale == null ||
            abs(previousSurfaceOverscanScale - surfaceOverscanScale) >= 0.001f
    ) {
      lastParticleLayerTargetDistanceMeters = targetDistanceMeters
      lastParticleLayerSurfaceOverscanScale = surfaceOverscanScale
      marker(
          "channel=native-surface-particle-layer status=surface-geometry-hotload-updated " +
              "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property " +
              "particleLayerTargetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
              "particleLayerSurfaceOverscanParameterSource=runtime-hotload-android-property " +
              "particleLayerSurfaceOverscanProperty=$PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY " +
              "targetDistanceMeters=${markerFloat(targetDistanceMeters)} " +
              "projectionPlanePoseInvariantWithOverscan=true " +
              "projectionWidthMeters=${markerFloat(projectionWidthMeters)} " +
              "projectionHeightMeters=${markerFloat(projectionHeightMeters)} " +
              "surfaceOverscanScale=${markerFloat(surfaceOverscanScale)} " +
              "surfaceWidthMeters=${markerFloat(surfaceWidthMeters)} " +
              "surfaceHeightMeters=${markerFloat(surfaceHeightMeters)}"
      )
    }
    val center = viewerPose.t + forward * targetDistanceMeters
    val planePose = Pose(center, Quaternion.fromDirection(forward, up))
    entity.setComponent(Transform(planePose))
    entity.setComponent(PanelDimensions(Vector2(surfaceWidthMeters, surfaceHeightMeters)))
    entity.setComponent(Visible(particleLayerVisibleForPanelMode()))
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
                )
              }
              .getOrElse { throwable ->
                if (forceLog) {
                  marker(
                      "channel=native-surface-particle-layer status=panel-pose-native-update-failed " +
                          "reason=${markerToken(reason)} error=${markerToken(throwable.javaClass.simpleName)}"
                  )
                }
                0L
              }
        } else {
          0L
        }

    val now = SystemClock.elapsedRealtime()
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
    val eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull()
    marker(
        "channel=native-surface-particle-layer status=projection-plane-updated " +
            "reason=${markerToken(reason)} " +
            particleLayerPlacementMarkerFields() + " " +
            "viewerPoseSource=Scene.getViewerPose eyeOffsetsSource=Scene.getEyeOffsets " +
            "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property " +
            "particleLayerTargetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
            "projectionPlaneFacingMode=viewer-forward-front-face " +
            "viewerPositionM=${vectorMarker(viewerPose.t)} " +
            "viewerForward=${vectorMarker(forward)} viewerUp=${vectorMarker(up)} " +
            "viewerRight=${vectorMarker(right)} panelPoseNativeUpdateMask=$nativePanelPoseUpdateMask " +
            "worldToPanelProjection=spatial-sdk-panel-plane-basis " +
            "particleLayerSurfaceOverscanProperty=$PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY " +
            "projectionWidthMeters=${markerFloat(projectionWidthMeters)} " +
            "projectionHeightMeters=${markerFloat(projectionHeightMeters)} " +
            "surfaceOverscanScale=${markerFloat(surfaceOverscanScale)} " +
            "surfaceWidthMeters=${markerFloat(surfaceWidthMeters)} " +
            "surfaceHeightMeters=${markerFloat(surfaceHeightMeters)} " +
            "projectionPlanePoseInvariantWithOverscan=true particleWorldScaleInvariantWithOverscan=true " +
            "planeCenterM=${vectorMarker(center)} planeQuaternion=${quaternionMarker(planePose.q)} " +
            "leftEyeOffsetM=${vectorMarker(eyeOffsets?.first ?: Vector3(0.0f))} " +
            "rightEyeOffsetM=${vectorMarker(eyeOffsets?.second ?: Vector3(0.0f))}"
    )
  }

  private fun particleLayerPlacementMarkerFields(): String =
      "cameraFacingParticleSurface=true projectionLockedParticleSurface=true " +
          "placementMode=$PARTICLE_LAYER_PLACEMENT_MODE " +
          "placementAuthority=$PARTICLE_LAYER_PLACEMENT_AUTHORITY " +
          "targetCoordinateSpace=$PARTICLE_LAYER_TARGET_COORDINATE_SPACE " +
          "targetProjectionSpace=$PARTICLE_LAYER_TARGET_PROJECTION_SPACE " +
          "projectionContentMappingMode=$PARTICLE_LAYER_PROJECTION_CONTENT_MAPPING_MODE " +
          "targetFovTangents=$PARTICLE_LAYER_TARGET_FOV_TANGENTS " +
          "targetDistanceMeters=${markerFloat(currentParticleLayerTargetDistanceMeters())} " +
          "targetDistanceDefaultMeters=$PARTICLE_LAYER_TARGET_DISTANCE_METERS " +
          "targetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
          "surfaceOverscanScale=${markerFloat(currentParticleLayerSurfaceOverscanScale())} " +
          "surfaceOverscanDefaultScale=$PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE " +
          "surfaceOverscanProperty=$PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY " +
          "leftTargetSurfaceUvRect=$PARTICLE_LAYER_TARGET_SURFACE_UV_RECT " +
          "rightTargetSurfaceUvRect=$PARTICLE_LAYER_TARGET_SURFACE_UV_RECT " +
          "viewOriginMeters=$PARTICLE_LAYER_VIEW_ORIGIN_METERS " +
          "viewOriginYawDegrees=$PARTICLE_LAYER_VIEW_ORIGIN_YAW_DEGREES " +
          "x=$PARTICLE_LAYER_X_METERS y=$PARTICLE_LAYER_Y_METERS z=$PARTICLE_LAYER_Z_METERS " +
          "projectionWidthMeters=${markerFloat(particleLayerProjectionWidthMeters(currentParticleLayerTargetDistanceMeters()))} " +
          "projectionHeightMeters=${markerFloat(particleLayerProjectionHeightMeters(currentParticleLayerTargetDistanceMeters()))} " +
          "surfaceWidthMeters=${markerFloat(particleLayerSurfaceWidthMeters(currentParticleLayerTargetDistanceMeters()))} " +
          "surfaceHeightMeters=${markerFloat(particleLayerSurfaceHeightMeters(currentParticleLayerTargetDistanceMeters()))}"

  private fun currentParticleLayerTargetDistanceMeters(): Float =
      readFloatSystemProperty(
          PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY,
          PARTICLE_LAYER_TARGET_DISTANCE_METERS,
          PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
          PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
      )

  private fun currentParticleLayerSurfaceOverscanScale(): Float =
      readFloatSystemProperty(
          PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY,
          PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE,
          PARTICLE_LAYER_SURFACE_OVERSCAN_MIN_SCALE,
          PARTICLE_LAYER_SURFACE_OVERSCAN_MAX_SCALE,
      )

  private fun particleLayerProjectionWidthMeters(targetDistanceMeters: Float): Float =
      (targetDistanceMeters * PARTICLE_LAYER_WIDTH_PER_DISTANCE)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_DIMENSION_MAX_METERS)

  private fun particleLayerProjectionHeightMeters(targetDistanceMeters: Float): Float =
      (targetDistanceMeters * PARTICLE_LAYER_HEIGHT_PER_DISTANCE)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_DIMENSION_MAX_METERS)

  private fun particleLayerSurfaceWidthMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = currentParticleLayerSurfaceOverscanScale(),
  ): Float =
      (particleLayerProjectionWidthMeters(targetDistanceMeters) * overscanScale)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_SURFACE_DIMENSION_MAX_METERS)

  private fun particleLayerSurfaceHeightMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = currentParticleLayerSurfaceOverscanScale(),
  ): Float =
      (particleLayerProjectionHeightMeters(targetDistanceMeters) * overscanScale)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_SURFACE_DIMENSION_MAX_METERS)

  private fun particleLayerSurfacePanelDimensions(
      targetDistanceMeters: Float = currentParticleLayerTargetDistanceMeters(),
      overscanScale: Float = currentParticleLayerSurfaceOverscanScale(),
  ): PanelDimensions =
      PanelDimensions(
          Vector2(
              particleLayerSurfaceWidthMeters(targetDistanceMeters, overscanScale),
              particleLayerSurfaceHeightMeters(targetDistanceMeters, overscanScale),
          )
      )

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun updateCameraHwbProjectionFromViewer(reason: String, forceLog: Boolean) {
    val entity = cameraHwbProjectionEntity ?: return
    val plane = cameraHwbProjectionPlaneForPlacement()
    entity.setComponent(Transform(plane.pose))
    entity.setComponent(Visible(true))
    val layerUpdateStatus = updateCameraHwbProjectionLayer(plane, reason)
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
        "channel=camera-hwb-spatial-probe status=raw-camera-projection-plane-updated " +
            "reason=${markerToken(reason)} rawCameraProjectionProbe=true " +
            "viewerPoseSource=${cameraHwbProjectionPoseSourceToken(plane)} eyeOffsetsSource=Scene.getEyeOffsets " +
            cameraHwbProjectionMarkerFields(plane) + " " +
            cameraHwbProjectionStereoMarkerFields() + " " +
            spatialVideoProjectionMarkerFields(spatialVideoProjectionSettings) + " " +
            SpatialPublicMultiStack.markerFields() + " " +
            "viewerPositionM=${vectorMarker(plane.viewerPosition)} " +
            "viewerForward=${vectorMarker(plane.forward)} viewerUp=${vectorMarker(plane.up)} " +
            "viewerRight=${vectorMarker(plane.right)} planeCenterM=${vectorMarker(plane.center)} " +
            "planeQuaternion=${quaternionMarker(plane.pose.q)} " +
            "sceneQuadLayerUpdateStatus=${markerToken(layerUpdateStatus)} " +
            "nativePanelPoseAuthority=camera-hwb-projection-plane " +
            "nativePanelPoseUpdateMask=$nativePanelPoseUpdateMask " +
            "leftEyeOffsetM=${vectorMarker(plane.leftEyeOffset)} " +
            "rightEyeOffsetM=${vectorMarker(plane.rightEyeOffset)} " +
            "outputMode=raw-color-target-rect sampledCameraTexture=see-native-logcat " +
            "sampledLeftCameraTexture=see-native-logcat sampledRightCameraTexture=see-native-logcat " +
            "monoDuplicated=false " +
            "privateShaderStack=false customProjectionStack=false runtimeCrash=false"
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
              "channel=camera-hwb-spatial-probe status=raw-camera-projection-layer-update-failed " +
                  "reason=${markerToken(reason)} rawCameraProjectionProbe=true " +
                  "targetDistanceMeters=${markerFloat(plane.targetDistanceMeters)} " +
                  "projectionWidthMeters=${markerFloat(plane.projectionWidthMeters)} " +
                  "projectionHeightMeters=${markerFloat(plane.projectionHeightMeters)} " +
                  "error=${markerToken(throwable.javaClass.simpleName)} " +
                  "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
            "channel=camera-hwb-spatial-probe status=native-panel-pose-update-skipped " +
                "reason=${markerToken(reason)} rawCameraProjectionProbe=true " +
                "nativePanelPoseAuthority=camera-hwb-projection-plane " +
                "error=${markerToken(nativeReceiptLibraryError)}"
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
          )
        }
        .getOrElse { throwable ->
          if (forceLog) {
            marker(
                "channel=camera-hwb-spatial-probe status=native-panel-pose-update-failed " +
                    "reason=${markerToken(reason)} rawCameraProjectionProbe=true " +
                    "nativePanelPoseAuthority=camera-hwb-projection-plane " +
                    "targetDistanceMeters=${markerFloat(plane.targetDistanceMeters)} " +
                    "projectionWidthMeters=${markerFloat(plane.projectionWidthMeters)} " +
                    "projectionHeightMeters=${markerFloat(plane.projectionHeightMeters)} " +
                    "error=${markerToken(throwable.javaClass.simpleName)} " +
                    "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
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
    val fallbackViewerPosition = Vector3(0.0f, 1.20f, -2.0f)
    val fallbackForward = Vector3(0.0f, 0.0f, -1.0f)
    val fallbackUp = Vector3(0.0f, 1.0f, 0.0f)
    val viewerPosition = viewerPose?.t ?: fallbackViewerPosition
    val forward = viewerPose?.forward()?.normalizedOr(fallbackForward) ?: fallbackForward
    val up = viewerPose?.up()?.normalizedOr(fallbackUp) ?: fallbackUp
    val right = cross(forward, up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val targetDistanceMeters = currentCameraHwbProjectionTargetDistanceMeters()
    val projectionWidthMeters = cameraHwbProjectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters = cameraHwbProjectionHeightMeters(targetDistanceMeters)
    val center = viewerPosition + forward * targetDistanceMeters
    val pose = Pose(center, Quaternion.fromDirection(forward, up))
    val eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull()
    return CameraHwbProjectionPlane(
        viewerPosition = viewerPosition,
        forward = forward,
        up = up,
        right = right,
        center = center,
        pose = pose,
        placementMode = CameraHwbProjectionPlacementMode.ViewerLocked,
        targetDistanceMeters = targetDistanceMeters,
        projectionWidthMeters = projectionWidthMeters,
        projectionHeightMeters = projectionHeightMeters,
        leftEyeOffset = eyeOffsets?.first ?: Vector3(0.0f),
        rightEyeOffset = eyeOffsets?.second ?: Vector3(0.0f),
    )
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun cameraHwbProjectionPlaneOnVirtualWall(): CameraHwbProjectionPlane {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    val viewerPosition = viewerPose?.t ?: Vector3(0.0f, 1.20f, 0.0f)
    val forward = Vector3(0.0f, 0.0f, 1.0f)
    val up = Vector3(0.0f, 1.0f, 0.0f)
    val right = Vector3(1.0f, 0.0f, 0.0f)
    val center =
        Vector3(
            CAMERA_HWB_PROJECTION_WALL_CENTER_X_METERS,
            CAMERA_HWB_PROJECTION_WALL_CENTER_Y_METERS,
            CAMERA_HWB_PROJECTION_WALL_CENTER_Z_METERS,
        )
    val eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull()
    return CameraHwbProjectionPlane(
        viewerPosition = viewerPosition,
        forward = forward,
        up = up,
        right = right,
        center = center,
        pose = Pose(center, Quaternion.fromDirection(forward, up)),
        placementMode = CameraHwbProjectionPlacementMode.VirtualRoomWall,
        targetDistanceMeters = vectorLength(vectorSubtract(center, viewerPosition)),
        projectionWidthMeters = CAMERA_HWB_PROJECTION_WALL_WIDTH_METERS,
        projectionHeightMeters = CAMERA_HWB_PROJECTION_WALL_HEIGHT_METERS,
        leftEyeOffset = eyeOffsets?.first ?: Vector3(0.0f),
        rightEyeOffset = eyeOffsets?.second ?: Vector3(0.0f),
    )
  }

  private fun cameraHwbProjectionPoseSourceToken(plane: CameraHwbProjectionPlane): String =
      when (plane.placementMode) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> "Scene.getViewerPose"
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> "virtual-room-wall-fixed-pose"
      }

  private fun cameraHwbProjectionZIndexForPlacement(
      placementMode: CameraHwbProjectionPlacementMode,
  ): Int =
      when (placementMode) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> CAMERA_HWB_PROJECTION_VIEWER_LOCKED_Z_INDEX
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> CAMERA_HWB_PROJECTION_WALL_Z_INDEX
      }

  private fun cameraHwbProjectionDisplayRoleForPlacement(
      placementMode: CameraHwbProjectionPlacementMode,
  ): String =
      when (placementMode) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> "full-fov-video-plus-custom-camera-stack"
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> "room-wall-video-plus-custom-camera-stack"
      }

  private data class SpatialVideoProjectionSettings(
      val enabled: Boolean,
      val path: String,
      val stereoLayout: String,
      val width: Int,
      val height: Int,
      val maxImages: Int,
      val fpsCap: Int,
      val looping: Boolean,
      val opacity: Float,
      val highRateJsonPayload: Boolean,
  ) {
    val active: Boolean
      get() = enabled && path.isNotBlank()

    companion object {
      fun disabled(): SpatialVideoProjectionSettings =
          SpatialVideoProjectionSettings(
              enabled = false,
              path = "",
              stereoLayout = "side-by-side-left-right",
              width = 3840,
              height = 1920,
              maxImages = 3,
              fpsCap = 30,
              looping = true,
              opacity = 1.0f,
              highRateJsonPayload = false,
          )
    }
  }

  private fun currentSpatialVideoProjectionSettings(intent: Intent?): SpatialVideoProjectionSettings {
    val enabled =
        readOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_ENABLED)
            ?: readOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY)
            ?: CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED
    val path =
        readOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_PATH)
            ?: readSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY)
    val stereoLayout =
        normalizeSpatialVideoProjectionStereoLayout(
            readOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_STEREO_LAYOUT)
                ?: readSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_STEREO_LAYOUT_PROPERTY)
        )
    val width =
        readOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_WIDTH,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_WIDTH_PX,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_WIDTH_PX,
        )
            ?: readIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_WIDTH_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_WIDTH_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_WIDTH_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_WIDTH_PX,
            )
    val height =
        readOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_HEIGHT,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_HEIGHT_PX,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_HEIGHT_PX,
        )
            ?: readIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_HEIGHT_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HEIGHT_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_HEIGHT_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_HEIGHT_PX,
            )
    val maxImages =
        readOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_MAX_IMAGES,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_IMAGES,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES,
        )
            ?: readIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_IMAGES,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_IMAGES,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES,
            )
    val fpsCap =
        readOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_FPS_CAP,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_FPS,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_FPS,
        )
            ?: readIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_FPS_CAP_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_FPS,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_FPS,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_FPS,
            )
    val looping =
        readOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_LOOPING)
            ?: readOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_LOOPING_PROPERTY)
            ?: true
    val opacity =
        readOptionalFloatIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_OPACITY,
            0.0f,
            1.0f,
        )
            ?: readOptionalFloatSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_OPACITY_PROPERTY,
                0.0f,
                1.0f,
            )
            ?: 1.0f
    val highRateJsonPayload =
        readOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD)
            ?: readOptionalBooleanSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_HIGH_RATE_JSON_PAYLOAD_PROPERTY
            )
            ?: false
    return SpatialVideoProjectionSettings(
        enabled = enabled,
        path = path.trim(),
        stereoLayout = stereoLayout,
        width = width,
        height = height,
        maxImages = maxImages,
        fpsCap = fpsCap,
        looping = looping,
        opacity = opacity,
        highRateJsonPayload = highRateJsonPayload,
    )
  }

  private fun normalizeSpatialVideoProjectionStereoLayout(value: String): String =
      when (value.trim().lowercase(Locale.US).replace("_", "-")) {
        "top-bottom", "top-bottom-left-right", "tb", "over-under" -> "top-bottom-left-right"
        "side-by-side", "sbs", "left-right", "side-by-side-left-right" ->
            "side-by-side-left-right"
        else -> CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_STEREO_LAYOUT
      }

  private fun spatialVideoProjectionMarkerFields(settings: SpatialVideoProjectionSettings): String =
      "videoProjectionEnabled=${settings.enabled} " +
          "spatialVideoProjectionEnabled=${settings.enabled} " +
          "spatialVideoProjectionActive=${settings.active} " +
          "videoProjectionPath=${markerToken(settings.path)} " +
          "videoProjectionPathProvided=${settings.path.isNotBlank()} " +
          "videoProjectionNoPackagedMedia=true " +
          "videoProjectionPathProperty=$CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY " +
          "videoProjectionEnabledProperty=$CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY " +
          "videoProjectionEnabledIntentExtra=$EXTRA_VIDEO_PROJECTION_ENABLED " +
          "videoProjectionPathIntentExtra=$EXTRA_VIDEO_PROJECTION_PATH " +
          "videoProjectionWidth=${settings.width} videoProjectionHeight=${settings.height} " +
          "videoProjectionMaxImages=${settings.maxImages} videoProjectionFpsCap=${settings.fpsCap} " +
          "videoProjectionLooping=${settings.looping} " +
          "videoProjectionStereoLayout=${settings.stereoLayout} " +
          "videoProjectionTarget=packed-sbs-full-eye " +
          "videoProjectionOpacity=${markerFloat(settings.opacity)} " +
          "videoProjectionHighRateJsonPayload=${settings.highRateJsonPayload} " +
          "videoProjectionStream=stereo_video " +
          "videoProjectionSource=app-private-or-device-local-file " +
          "videoProjectionSourceAuthority=android-mediacodec-surface-decoder " +
          "videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer " +
          "videoProjectionControlPlane=spatial-activity-runtime-property-or-intent-extra " +
          "videoProjectionDecodePath=MediaCodec-to-Surface " +
          "videoProjectionFormat=private " +
          "videoProjectionLeftSourceUvRect=0.000000,0.000000,0.500000,1.000000 " +
          "videoProjectionRightSourceUvRect=0.500000,0.000000,0.500000,1.000000 " +
          "videoProjectionLeftTargetPackedUvRect=0.000000,0.000000,0.500000,1.000000 " +
          "videoProjectionRightTargetPackedUvRect=0.500000,0.000000,0.500000,1.000000 " +
          "spatialVideoProjectionSameSurfaceComposition=true " +
          "videoProjectionComposedBeforeCamera=true " +
          "cameraProjectionAlignmentPreserved=true " +
          "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false " +
          "highRateJsonPayload=${settings.highRateJsonPayload} " +
          "rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false"

  private fun configureNativeSpatialVideoProjection(
      settings: SpatialVideoProjectionSettings,
      reason: String,
  ): Long {
    if (!nativeReceiptLibraryLoaded) {
      marker(
          "channel=spatial-video-projection status=native-configure-skipped " +
              "reason=${markerToken(reason)} nativeReceiptLibraryLoaded=false " +
              spatialVideoProjectionMarkerFields(settings) + " runtimeCrash=false"
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
                  "channel=spatial-video-projection status=native-configure-failed " +
                      "reason=${markerToken(reason)} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      spatialVideoProjectionMarkerFields(settings) + " runtimeCrash=false"
              )
              return 0L
            }
    marker(
        "channel=spatial-video-projection status=native-configured " +
            "reason=${markerToken(reason)} configureMask=$mask " +
            spatialVideoProjectionMarkerFields(settings) + " runtimeCrash=false"
    )
    return mask
  }

  private fun startSpatialVideoProjection(
      settings: SpatialVideoProjectionSettings,
      reason: String,
  ) {
    marker(
        "channel=spatial-video-projection status=start-requested " +
            "reason=${markerToken(reason)} " +
            spatialVideoProjectionMarkerFields(settings) + " runtimeCrash=false"
    )
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
    marker(
        "channel=spatial-video-projection status=stopped " +
            "reason=${markerToken(reason)} videoProjectionStopRequested=true " +
            spatialVideoProjectionMarkerFields(previousSettings) + " runtimeCrash=false"
    )
  }

  private fun cameraHwbProjectionMarkerFields(plane: CameraHwbProjectionPlane? = null): String {
    val targetScale = currentCameraHwbProjectionTargetScale()
    val stereoHorizontalOffsetUv = currentCameraHwbProjectionStereoHorizontalOffsetUv()
    val placementMode = plane?.placementMode ?: cameraHwbProjectionPlacementMode
    val targetDistanceMeters = plane?.targetDistanceMeters ?: currentCameraHwbProjectionTargetDistanceMeters()
    val projectionWidthMeters =
        plane?.projectionWidthMeters ?: cameraHwbProjectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters =
        plane?.projectionHeightMeters ?: cameraHwbProjectionHeightMeters(targetDistanceMeters)
    val placementAuthority =
        when (placementMode) {
          CameraHwbProjectionPlacementMode.ViewerLocked ->
              CAMERA_HWB_PROJECTION_PLACEMENT_AUTHORITY
          CameraHwbProjectionPlacementMode.VirtualRoomWall ->
              CAMERA_HWB_PROJECTION_WALL_PLACEMENT_AUTHORITY
        }
    val layerZIndex = cameraHwbProjectionZIndexForPlacement(placementMode)
    val displayRole = cameraHwbProjectionDisplayRoleForPlacement(placementMode)
    return "placementMode=${placementMode.markerToken} " +
        "placementAuthority=$placementAuthority " +
        "projectionDisplaySurface=$displayRole " +
        "projectionDisplaySurfaceContainsVideo=true " +
        "projectionDisplaySurfaceContainsCustomCameraProjection=true " +
        "projectionRoomRenderOrder=projection-layer-over-virtual-room " +
        "cameraVideoProjectionLayerZIndex=$layerZIndex " +
        "legacyLauncherPanelSuppressed=${legacyLauncherPanelSuppressedForCameraStack()} " +
        "viewerLockedPlacementMode=$CAMERA_HWB_PROJECTION_PLACEMENT_MODE " +
        "virtualRoomWallPlacementMode=$CAMERA_HWB_PROJECTION_WALL_PLACEMENT_MODE " +
        "virtualRoomWallPlacementActive=${placementMode == CameraHwbProjectionPlacementMode.VirtualRoomWall} " +
        "cameraProjectionWallToggleInput=right-controller-secondary-button " +
        "cameraProjectionWallToggleEnabled=true " +
        "virtualRoomWallCenterM=$CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER " +
        "virtualRoomWallSizeM=$CAMERA_HWB_PROJECTION_WALL_SIZE_MARKER " +
        "cameraFacingParticleSurface=true projectionLockedParticleSurface=true " +
        "targetDistanceMeters=${markerFloat(targetDistanceMeters)} " +
        "targetDistanceDefaultMeters=$CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER " +
        "targetDistanceProperty=none-fixed-camera-projection-default " +
        "targetDistanceParameterSource=${cameraHwbProjectionTargetDistanceSource()} " +
        "targetDistanceJoystickControlsEnabled=false " +
        "targetDistanceJoystickInput=none-fixed-distance " +
        "projectionTargetScaleJoystickControlsEnabled=true " +
        "projectionTargetScaleJoystickInput=android-right-stick-y;spatial-sdk-avatar-body-right-thumb-up-down;native-openxr-right-thumbstick-y;panel-control " +
        "projectionTargetScaleJoystickRateProperty=$CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY " +
        "projectionTargetScaleJoystickRatePerSecond=${markerFloat(currentCameraHwbProjectionTargetScaleJoystickRate())} " +
        "stereoHorizontalOffsetJoystickControlsEnabled=false " +
        "stereoHorizontalOffsetJoystickInput=disabled-default-locked-left-stick-y-controls-panel-distance-private-free-transform " +
        "stereoHorizontalOffsetJoystickRateProperty=none-disabled " +
        "stereoHorizontalOffsetJoystickRateUvPerSecond=0.000 " +
        "cameraHwbProjectionStereoHorizontalOffsetIgnoresPanelVisibility=true " +
        "targetFovTangents=$CAMERA_HWB_PROJECTION_TARGET_FOV_TANGENTS " +
        "projectionWidthMeters=${markerFloat(projectionWidthMeters)} " +
        "projectionHeightMeters=${markerFloat(projectionHeightMeters)} " +
        "surfaceOverscanScale=$CAMERA_HWB_PROJECTION_SURFACE_OVERSCAN_MARKER " +
        "surfaceWidthMeters=${markerFloat(projectionWidthMeters)} " +
        "surfaceHeightMeters=${markerFloat(projectionHeightMeters)} " +
        "projectionPlaneAngularCoveragePreserved=true " +
        "eyeSpaceTargetRectPreserved=true " +
        "projectionTarget=$CAMERA_HWB_PROJECTION_TARGET " +
        "borderOpacity=$CAMERA_HWB_PROJECTION_BORDER_OPACITY_MARKER " +
        "leftCameraId=50 rightCameraId=51 " +
        "leftTargetScreenUvRect=$CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_MARKER " +
        "rightTargetScreenUvRect=$CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_MARKER " +
        "leftEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftEffectiveTargetRectMarker()} " +
        "rightEffectiveTargetScreenUvRect=${cameraHwbProjectionRightEffectiveTargetRectMarker()} " +
        "leftPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftPackedEffectiveTargetRectMarker()} " +
        "rightPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionRightPackedEffectiveTargetRectMarker()} " +
        "projectionTargetControlsEnabled=true " +
        "projectionTargetLiveScale=${markerFloat(targetScale)} " +
        "projectionTargetTunedMaxScale=${markerFloat(targetScale)} " +
        "projectionTargetMinScale=${markerFloat(CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE)} " +
        "projectionTargetMaxScale=${markerFloat(CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE)} " +
        "projectionTargetOffsetUv=0.000000,0.000000 " +
        "projectionTargetStereoHorizontalOffsetUv=${markerFloat6(stereoHorizontalOffsetUv)} " +
        "projectionTargetStereoHorizontalOffsetDefaultUv=${markerFloat6(CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV)} " +
        "projectionTargetStereoHorizontalOffsetRangeUv=$CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_RANGE_MARKER " +
        "projectionTargetLeftOffsetUv=${markerFloat6(-stereoHorizontalOffsetUv)},0.000000 " +
        "projectionTargetRightOffsetUv=${markerFloat6(stereoHorizontalOffsetUv)},0.000000 " +
        "projectionTargetStereoHorizontalOffsetSign=positive-increases-separation " +
        "targetClipPolicy=clip-to-visible-eye " +
        "targetCoordinateSpace=$PARTICLE_LAYER_TARGET_COORDINATE_SPACE " +
        "targetProjectionSpace=$PARTICLE_LAYER_TARGET_PROJECTION_SPACE " +
        "projectionContentMappingMode=target-local-raster"
  }

  private fun cameraHwbProjectionStereoMarkerFields(): String =
      "stereoMode=LeftRight stereoSource=camera50-51 leftCameraId=50 rightCameraId=51 " +
          "monoDuplicated=false " +
          "perEyeExtent=${CAMERA_HWB_PROJECTION_PER_EYE_WIDTH_PX}x$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
          "packedExtent=${CAMERA_HWB_PROJECTION_WIDTH_PX}x$CAMERA_HWB_PROJECTION_HEIGHT_PX"

  private fun currentCameraHwbProjectionTargetDistanceMeters(): Float =
      CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS.coerceIn(
          PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
          PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
      )

  private fun cameraHwbProjectionTargetDistanceSource(): String =
      "fixed-camera-projection-default"

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
      readFloatSystemProperty(
          CAMERA_HWB_PROJECTION_TARGET_SCALE_PROPERTY,
          CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT,
          CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
          CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
      )

  private fun initialPrivateLayerDepthLayerPolicy(): Int =
      PrivateLayerControls.depthLayerPolicyForToken(
          readSystemProperty(CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY)
      ) ?: PrivateLayerControls.defaultDepthLayerPolicy

  private fun currentCameraHwbProjectionTargetScale(): Float =
      cameraHwbProjectionTargetScale.coerceIn(
          CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
          CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
      )

  private fun currentCameraHwbProjectionTargetScaleJoystickRate(): Float =
      readFloatSystemProperty(
          CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY,
          CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PER_SECOND,
          0.02f,
          1.25f,
      )

  private fun cameraHwbProjectionEffectiveTargetRect(
      baseX: Float,
      baseY: Float,
      baseWidth: Float,
      baseHeight: Float,
      offsetX: Float,
  ): FloatArray {
    val scale = currentCameraHwbProjectionTargetScale()
    val width = (baseWidth * scale).coerceIn(0.0001f, 1.0f)
    val height = (baseHeight * scale).coerceIn(0.0001f, 1.0f)
    val centerX = baseX + baseWidth * 0.5f + offsetX
    val centerY = baseY + baseHeight * 0.5f
    val x = (centerX - width * 0.5f).coerceIn(0.0f, 1.0f - width)
    val y = (centerY - height * 0.5f).coerceIn(0.0f, 1.0f - height)
    return floatArrayOf(x, y, width, height)
  }

  private fun cameraHwbProjectionLeftEffectiveTargetRect(): FloatArray =
      cameraHwbProjectionEffectiveTargetRect(
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_X,
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_Y,
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_WIDTH,
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_HEIGHT,
          -currentCameraHwbProjectionStereoHorizontalOffsetUv(),
      )

  private fun cameraHwbProjectionRightEffectiveTargetRect(): FloatArray =
      cameraHwbProjectionEffectiveTargetRect(
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_X,
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_Y,
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_WIDTH,
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_HEIGHT,
          currentCameraHwbProjectionStereoHorizontalOffsetUv(),
      )

  private fun cameraHwbProjectionRectMarker(rect: FloatArray): String =
      "${markerFloat6(rect[0])};${markerFloat6(rect[1])};${markerFloat6(rect[2])};${markerFloat6(rect[3])}"

  private fun cameraHwbProjectionLeftEffectiveTargetRectMarker(): String =
      cameraHwbProjectionRectMarker(cameraHwbProjectionLeftEffectiveTargetRect())

  private fun cameraHwbProjectionRightEffectiveTargetRectMarker(): String =
      cameraHwbProjectionRectMarker(cameraHwbProjectionRightEffectiveTargetRect())

  private fun cameraHwbProjectionLeftPackedEffectiveTargetRectMarker(): String {
    val rect = cameraHwbProjectionLeftEffectiveTargetRect()
    return cameraHwbProjectionRectMarker(
        floatArrayOf(0.5f * rect[0], rect[1], 0.5f * rect[2], rect[3])
    )
  }

  private fun cameraHwbProjectionRightPackedEffectiveTargetRectMarker(): String {
    val rect = cameraHwbProjectionRightEffectiveTargetRect()
    return cameraHwbProjectionRectMarker(
        floatArrayOf(0.5f + 0.5f * rect[0], rect[1], 0.5f * rect[2], rect[3])
    )
  }

  private fun currentSpatialVrInputSystemToken(): String =
      when (readSystemProperty(SPATIAL_VR_INPUT_SYSTEM_PROPERTY).trim().lowercase(Locale.US)) {
        "simple", "simple-controller", "simple_controller" -> "simple_controller"
        "interaction", "interaction-sdk", "interaction_sdk", "isdk" -> "interaction_sdk"
        "default", "" -> SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN
        else -> SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN
      }

  private fun currentSpatialVrInputSystemType(): VrInputSystemType =
      when (currentSpatialVrInputSystemToken()) {
        "simple_controller" -> VrInputSystemType.SIMPLE_CONTROLLER
        else -> VrInputSystemType.INTERACTION_SDK
      }

  private fun currentSpatialShouldConsumeLeftRightInput(): Boolean =
      readOptionalBooleanSystemProperty(SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY)
          ?: SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_DEFAULT

  private fun panelHeadlockMarkerFields(): String {
    val placement = activeHeadlockedPanelPlacement()
    val activePanelToken =
        if (privateLayerPanelVisible) "private-layer-panel" else "workflow-panel"
    val distanceMode =
        if (privateLayerPanelVisible) {
          "disabled-sdk-free-transform"
        } else {
          "viewer-forward-distance"
        }
    return "headlockedPanelEnabled=${placement.headlocked} " +
          "headlockedPanelDefaultEnabled=true " +
          "activeHeadlockedPanel=${markerToken(activePanelToken)} " +
          "headlockedPanelOffsetXMeters=${markerFloat(placement.xMeters)} " +
          "headlockedPanelOffsetYMeters=${markerFloat(placement.yMeters)} " +
          "headlockedPanelDistanceMeters=${markerFloat(placement.zMeters)} " +
          "headlockedPanelDistanceMode=${markerToken(distanceMode)} " +
          "privateLayerPanelWorldSpace=true " +
          "privateLayerPanelPoseSource=initial-headset-facing-world-space-then-sdk-owned " +
          "privateLayerPanelLayerConfig=disabled " +
          "privateLayerPanelGrabbable=true " +
          "privateLayerPanelGrabType=PIVOT_Y " +
          "privateLayerPanelTransformAuthority=spatial-sdk-grabbable-free-transform " +
          "privateLayerPanelForcedDistanceDisabled=$privateLayerPanelVisible " +
          "composeDragPanelMovement=false " +
          "panelRenderOrder=front-of-camera-video " +
          "panelOpensInFrontOfCameraVideo=${placement.zMeters < CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS} " +
          "panelDistanceLessThanCameraProjection=${placement.zMeters < currentCameraHwbProjectionTargetDistanceMeters()} " +
          "cameraVideoProjectionLayerZIndex=${cameraHwbProjectionZIndexForPlacement(cameraHwbProjectionPlacementMode)} " +
          "privateLayerPanelRenderMode=spatial-sdk-mesh " +
          "panelRenderOrderProof=private-layer-world-space-in-front-of-camera-video " +
          "panelScale=${markerFloat(placement.scale)} " +
          "panelWidth=${markerFloat(placement.widthMeters)} " +
          "panelHeight=${markerFloat(placement.heightMeters)}"
  }

  private fun panelHeadlockPropertyMarkerFields(): String =
      "headlockedPanelEnabledProperty=$PANEL_HEADLOCK_ENABLED_PROPERTY " +
          "headlockedPanelOffsetXProperty=$PANEL_HEADLOCK_OFFSET_X_PROPERTY " +
          "headlockedPanelOffsetYProperty=$PANEL_HEADLOCK_OFFSET_Y_PROPERTY " +
          "headlockedPanelDistanceProperty=$PANEL_HEADLOCK_DISTANCE_PROPERTY " +
          "headlockedPanelWidthProperty=$PANEL_HEADLOCK_WIDTH_PROPERTY " +
          "headlockedPanelHeightProperty=$PANEL_HEADLOCK_HEIGHT_PROPERTY " +
          "headlockedPanelScaleProperty=$PANEL_HEADLOCK_SCALE_PROPERTY " +
          "headlockedPanelJoystickEnabledProperty=$PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY " +
          "headlockedPanelJoystickTranslateRateProperty=$PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_PROPERTY " +
          "headlockedPanelJoystickDistanceRateProperty=$PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY " +
          "headlockedPanelJoystickScaleRateProperty=$PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PROPERTY"

  private fun applyCameraHwbProjectionScaleJoystickInput(event: MotionEvent): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE || !isJoystickEvent(event)) {
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
        detail = "rightStickY=${markerFloat(rightY)}",
    )
  }

  private fun applyCameraHwbProjectionScaleInput(
      rightY: Float,
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
  ): Boolean {
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
          "channel=camera-hwb-spatial-probe status=target-scale-joystick-adjusted " +
              "rawCameraProjectionProbe=true inputSource=${markerToken(inputSource)} " +
              "controllerJoystickMapping=${markerToken(controllerJoystickMapping)} " +
              "$detail dtSeconds=${markerFloat(dtSeconds)} " +
              "projectionTargetScaleRatePerSecond=${markerFloat(scaleRate)} " +
              "panelVisible=${panelPlacement.visible} " +
              "cameraHwbProjectionScaleIgnoresPanelVisibility=true " +
              "previousProjectionTargetLiveScale=${markerFloat(previousScale)} " +
              "projectionTargetLiveScale=${markerFloat(updatedScale)} " +
              "projectionTargetTunedMaxScale=${markerFloat(updatedScale)} " +
              "projectionTargetMinScale=${markerFloat(CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE)} " +
              "projectionTargetMaxScale=${markerFloat(CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE)} " +
              "targetDistanceMeters=${markerFloat(currentCameraHwbProjectionTargetDistanceMeters())} " +
              "projectionPlaneAngularCoveragePreserved=true " +
              "eyeSpaceTargetRectPreserved=true " +
              "leftEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftEffectiveTargetRectMarker()} " +
              "rightEffectiveTargetScreenUvRect=${cameraHwbProjectionRightEffectiveTargetRectMarker()} " +
              "leftPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftPackedEffectiveTargetRectMarker()} " +
              "rightPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionRightPackedEffectiveTargetRectMarker()}"
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
        "channel=camera-hwb-spatial-probe status=target-scale-panel-adjusted " +
            "rawCameraProjectionProbe=true inputSource=spatial-sdk-compose-panel " +
            "source=${markerToken(source)} previousProjectionTargetLiveScale=${markerFloat(previousScale)} " +
            "projectionTargetLiveScale=${markerFloat(updatedScale)} " +
            "projectionTargetMinScale=${markerFloat(CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE)} " +
            "projectionTargetMaxScale=${markerFloat(CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE)} " +
            "leftPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftPackedEffectiveTargetRectMarker()} " +
            "rightPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionRightPackedEffectiveTargetRectMarker()} " +
            "runtimeCrash=false"
    )
    return updatedScale
  }

  private fun updatePrivateLayerOverrideFromPanel(
      requestedLayerOverride: Float,
      source: String,
  ): Float {
    val previousOverride = privateLayerOverride
    val updatedOverride =
        if (requestedLayerOverride < 0.0f) {
          PrivateLayerControls.cycleOverride
        } else {
          requestedLayerOverride.coerceIn(0.0f, 6.0f).toInt().toFloat()
        }
    marker(
        "channel=private-layer-panel status=layer-button-selected " +
            "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
            "privateLayerPanelInputButtons=button-a+trigger-l+trigger-r " +
            "privateLayerPanelTriggerSelectEnabled=true " +
            "requestedPublicMultiStackOpaqueProjectionLayerOverride=${markerFloat(requestedLayerOverride)} " +
            "previousPublicMultiStackOpaqueProjectionLayerOverride=${markerFloat(previousOverride)} " +
            "publicMultiStackOpaqueProjectionLayerOverride=${markerFloat(updatedOverride)} " +
            "publicMultiStackOpaqueProjectionLayerLabel=${markerToken(PrivateLayerControls.labelForOverride(updatedOverride))} " +
            "projectionPlacementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
            "layerOverrideAppliesToWallAndFullFov=true " +
            "cameraProjectionPlacementIndependentLayerControl=true " +
            "runtimeCrash=false"
    )
    privateLayerOverride = updatedOverride
    val updateMask =
        runCatching { nativeUpdatePrivateLayerOverride(updatedOverride) }
            .getOrElse { throwable ->
              marker(
                  "channel=private-layer-panel status=layer-override-update-failed " +
                      "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
                      "requestedPublicMultiStackOpaqueProjectionLayerOverride=${markerFloat(requestedLayerOverride)} " +
                      "publicMultiStackOpaqueProjectionLayerOverride=${markerFloat(updatedOverride)} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              0L
            }
    marker(
        "channel=private-layer-panel status=layer-override-submitted " +
            "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
            "transport=jni-live-queue publicMultiStackLayerControl=true updateMask=$updateMask " +
            "previousPublicMultiStackOpaqueProjectionLayerOverride=${markerFloat(previousOverride)} " +
            "publicMultiStackOpaqueProjectionLayerOverride=${markerFloat(updatedOverride)} " +
            "publicMultiStackOpaqueProjectionLayerLabel=${markerToken(PrivateLayerControls.labelForOverride(updatedOverride))} " +
            "projectionPlacementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
            "layerOverrideAppliesToWallAndFullFov=true " +
            "cameraProjectionPlacementIndependentLayerControl=true " +
            "publicMultiStackLayerManifest=0:final,1:raw-brightness,2:preblur-brightness,3:raw-strength,4:blurred-strength,5:displacement,6:depth-gradient " +
            "projectionTargetLiveScale=${markerFloat(currentCameraHwbProjectionTargetScale())} " +
            "panelRenderOrder=front-of-camera-video runtimeCrash=false"
    )
    return updatedOverride
  }

  private fun updatePrivateLayerDepthLayerPolicyFromPanel(
      requestedPolicy: Int,
      source: String,
  ): Int {
    val previousPolicy = privateLayerDepthLayerPolicy
    val updatedPolicy = PrivateLayerControls.normalizeDepthLayerPolicy(requestedPolicy)
    privateLayerDepthLayerPolicy = updatedPolicy
    val policyToken = PrivateLayerControls.tokenForDepthLayerPolicy(updatedPolicy)
    val compareMode =
        if (updatedPolicy == PrivateLayerControls.depthPolicyCompare) {
          "visual-shader"
        } else {
          "off"
        }
    marker(
        "channel=private-layer-panel status=depth-layer-policy-selected " +
            "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
            "requestedPublicMultiStackDepthLayerPolicyCode=$requestedPolicy " +
            "previousPublicMultiStackDepthLayerPolicy=${markerToken(PrivateLayerControls.tokenForDepthLayerPolicy(previousPolicy))} " +
            "publicMultiStackDepthLayerPolicy=${markerToken(policyToken)} " +
            "publicMultiStackDepthLayerCompareMode=${markerToken(compareMode)} " +
            "publicMultiStackDepthLayerPolicyProperty=$CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY " +
            "runtimeCrash=false"
    )
    val updateMask =
        runCatching { nativeUpdatePrivateLayerDepthLayerPolicy(updatedPolicy) }
            .getOrElse { throwable ->
              marker(
                  "channel=private-layer-panel status=depth-layer-policy-update-failed " +
                      "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
                      "publicMultiStackDepthLayerPolicy=${markerToken(policyToken)} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              0L
            }
    marker(
        "channel=private-layer-panel status=depth-layer-policy-submitted " +
            "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
            "transport=jni-live-queue publicMultiStackDepthLayerPolicyControl=true updateMask=$updateMask " +
            "previousPublicMultiStackDepthLayerPolicy=${markerToken(PrivateLayerControls.tokenForDepthLayerPolicy(previousPolicy))} " +
            "publicMultiStackDepthLayerPolicy=${markerToken(policyToken)} " +
            "publicMultiStackDepthLayerCompareMode=${markerToken(compareMode)} " +
            "publicMultiStackDepthLayerCompareEvidence=${markerToken(if (compareMode == "visual-shader") "shader-samples-layer0-and-layer1-at-same-depth-uv" else "inactive")} " +
            "publicMultiStackDepthLayerPolicyManifest=0:mono-layer0,1:mono-layer1,2:eye-index,3:compare " +
            "panelRenderOrder=front-of-camera-video runtimeCrash=false"
    )
    return updatedPolicy
  }

  private fun updatePrivateLayerDepthAlignmentFromPanel(
      requestedAlignment: PrivateLayerDepthAlignment,
      source: String,
  ): PrivateLayerDepthAlignment {
    val previousAlignment = privateLayerDepthAlignment
    val updatedAlignment =
        PrivateLayerDepthAlignment(
            leftX = requestedAlignment.leftX.coerceIn(-0.25f, 0.25f),
            leftY = requestedAlignment.leftY.coerceIn(-0.25f, 0.25f),
            rightX = requestedAlignment.rightX.coerceIn(-0.25f, 0.25f),
            rightY = requestedAlignment.rightY.coerceIn(-0.25f, 0.25f),
            sampleScale = requestedAlignment.sampleScale.coerceIn(0.25f, 3.0f),
        )
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
                  "channel=private-layer-panel status=depth-alignment-update-failed " +
                      "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
                      "publicMultiStackDepthAlignmentLeftOffsetUv=${markerFloat6(updatedAlignment.leftX)},${markerFloat6(updatedAlignment.leftY)} " +
                      "publicMultiStackDepthAlignmentRightOffsetUv=${markerFloat6(updatedAlignment.rightX)},${markerFloat6(updatedAlignment.rightY)} " +
                      "publicMultiStackDepthAlignmentSampleScale=${markerFloat(updatedAlignment.sampleScale)} " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
              )
              0L
            }
    marker(
        "channel=private-layer-panel status=depth-alignment-submitted " +
            "source=${markerToken(source)} spatialPrivateLayerControlPanel=true " +
            "transport=jni-live-queue publicMultiStackDepthAlignmentControl=true updateMask=$updateMask " +
            "previousPublicMultiStackDepthAlignmentLeftOffsetUv=${markerFloat6(previousAlignment.leftX)},${markerFloat6(previousAlignment.leftY)} " +
            "previousPublicMultiStackDepthAlignmentRightOffsetUv=${markerFloat6(previousAlignment.rightX)},${markerFloat6(previousAlignment.rightY)} " +
            "previousPublicMultiStackDepthAlignmentSampleScale=${markerFloat(previousAlignment.sampleScale)} " +
            "publicMultiStackDepthAlignmentLeftOffsetUv=${markerFloat6(updatedAlignment.leftX)},${markerFloat6(updatedAlignment.leftY)} " +
            "publicMultiStackDepthAlignmentRightOffsetUv=${markerFloat6(updatedAlignment.rightX)},${markerFloat6(updatedAlignment.rightY)} " +
            "publicMultiStackDepthAlignmentSampleScale=${markerFloat(updatedAlignment.sampleScale)} " +
            "panelRenderOrder=front-of-camera-video runtimeCrash=false"
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
                    "channel=camera-hwb-spatial-probe status=target-stereo-horizontal-offset-update-failed " +
                        "reason=${markerToken(reason)} rawCameraProjectionProbe=true " +
                        "projectionTargetStereoHorizontalOffsetUv=${markerFloat6(stereoOffsetUv)} " +
                        "error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
                )
              }
              0L
            }
    if (forceLog) {
      marker(
          "channel=camera-hwb-spatial-probe status=target-stereo-horizontal-offset-native-updated " +
              "reason=${markerToken(reason)} rawCameraProjectionProbe=true updateMask=$updateMask " +
              "projectionTargetStereoHorizontalOffsetUv=${markerFloat6(stereoOffsetUv)} " +
              "projectionTargetLeftOffsetUv=${markerFloat6(-stereoOffsetUv)},0.000000 " +
              "projectionTargetRightOffsetUv=${markerFloat6(stereoOffsetUv)},0.000000 " +
              "leftPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftPackedEffectiveTargetRectMarker()} " +
              "rightPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionRightPackedEffectiveTargetRectMarker()} " +
              "runtimeCrash=false"
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
                    "channel=camera-hwb-spatial-probe status=target-scale-update-failed " +
                        "reason=${markerToken(reason)} rawCameraProjectionProbe=true " +
                        "projectionTargetLiveScale=${markerFloat(targetScale)} " +
                        "error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
                )
              }
              0L
            }
    if (forceLog) {
      marker(
          "channel=camera-hwb-spatial-probe status=target-scale-native-updated " +
              "reason=${markerToken(reason)} rawCameraProjectionProbe=true updateMask=$updateMask " +
              "projectionTargetLiveScale=${markerFloat(targetScale)} " +
              "projectionTargetTunedMaxScale=${markerFloat(targetScale)} " +
              "leftPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionLeftPackedEffectiveTargetRectMarker()} " +
              "rightPackedEffectiveTargetScreenUvRect=${cameraHwbProjectionRightPackedEffectiveTargetRectMarker()} " +
              "runtimeCrash=false"
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
          "channel=spatial-panel status=joystick-input-arbitrated " +
              "inputSource=${markerToken(inputSource)} " +
              "leftStick=${markerFloat(leftX)};${markerFloat(leftY)} " +
              "rightStick=${markerFloat(rightX)};${markerFloat(rightY)} " +
              "projectionScaleHandled=$projectionScaleHandled " +
              "panelPlacementHandled=$panelPlacementHandled " +
              "rightStickSwallowedAsIgnored=$rightStickSwallowedAsIgnored " +
              "leftStickYDeliveredToPanelScroll=$leftStickYDeliveredToPanelScroll " +
              "leftStickYPanelDistanceObserved=$leftDistanceObserved " +
              "consumedByActivity=$consumed " +
              "leftStickYPanelDistanceEnabled=$leftStickPanelDistanceEnabled " +
              "leftStickYPanelScrollReserved=false " +
              "leftStickYProjectionHorizontalOffsetDisabled=true " +
              "rightStickYProjectionScaleEnabled=true " +
              "rightStickYPanelDistanceDisabled=true " +
              "rightStickXIgnored=true rightStickXPanelScaleDisabled=true " +
              "panelMode=${panelStateToken()} " +
              "projectionTargetLiveScale=${markerFloat(currentCameraHwbProjectionTargetScale())} " +
              panelHeadlockMarkerFields()
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
    if (
        (!panelPlacement.visible && !privateLayerPanelVisible) ||
            !currentPanelHeadlockJoystickEnabled()
    ) {
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
            "leftStick=${markerFloat(leftX)};${markerFloat(leftY)} " +
                "rightStick=${markerFloat(rightX)};${markerFloat(rightY)} " +
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
        readFloatSystemProperty(
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
            .coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
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
          "channel=spatial-panel status=headlock-distance-joystick-adjusted " +
              "inputSource=${markerToken(inputSource)} " +
              "controllerJoystickMapping=${markerToken(controllerJoystickMapping)} " +
              "$detail " +
              "leftThumbY=${markerFloat(leftY)} " +
              "dtSeconds=${markerFloat(dtSeconds)} " +
              "distanceRateMps=${markerFloat(distanceRate)} " +
              "previousHeadlockedPanelDistanceMeters=${markerFloat(previousDistance)} " +
              "leftStickUpIncreasesPanelDistance=true leftStickDownDecreasesPanelDistance=true " +
              "leftStickYPanelDistanceEnabled=${currentLeftStickPanelDistanceEnabled()} leftStickYPanelScrollReserved=false " +
              "leftStickYProjectionHorizontalOffsetDisabled=true " +
              "panelDistanceControl=${markerToken(currentLeftStickPanelDistanceMapping())} " +
              panelHeadlockMarkerFields()
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
            "channel=spatial-panel status=private-layer-free-transform-distance-joystick-skipped " +
                "inputSource=${markerToken(inputSource)} " +
                "$detail " +
                "leftThumbY=${markerFloat(leftY)} " +
                "privateLayerPanelIsGrabbed=true " +
                "leftStickYPanelDistanceEnabled=false " +
                "panelDistanceControl=left-stick-y-free-transform-distance " +
                panelHeadlockMarkerFields()
        )
      }
      return true
    }

    val entity = privateLayerPanelEntity ?: return false
    val currentPose = entity.tryGetComponent<Transform>()?.transform ?: return false
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return false
    val offset = vectorSubtract(currentPose.t, viewerPose.t)
    val previousDistance =
        vectorLength(offset)
            .coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    val direction =
        offset.normalizedOr(viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f)))
    val now = SystemClock.elapsedRealtime()
    val dtSeconds =
        if (lastPanelHeadlockJoystickMs <= 0L) {
          1.0f / 60.0f
        } else {
          ((now - lastPanelHeadlockJoystickMs).toFloat() / 1000.0f).coerceIn(0.0f, 0.08f)
        }
    lastPanelHeadlockJoystickMs = now
    val distanceRate =
        readFloatSystemProperty(
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
            .coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    if (abs(updatedDistance - previousDistance) < 0.00001f) {
      return true
    }
    val updatedPose = Pose(viewerPose.t + direction * updatedDistance, currentPose.q)
    entity.setComponent(Transform(updatedPose))
    privateLayerPanelPlacement =
        privateLayerPanelPlacement.copy(
            visible = true,
            headlocked = false,
            zMeters = updatedDistance,
        )
    persistPanelHeadlockTuning("controller-joystick-private-free-transform-distance")
    if (now - lastPanelHeadlockJoystickMarkerMs >= PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS) {
      lastPanelHeadlockJoystickMarkerMs = now
      marker(
          "channel=spatial-panel status=private-layer-free-transform-distance-joystick-adjusted " +
              "inputSource=${markerToken(inputSource)} " +
              "$detail " +
              "leftThumbY=${markerFloat(leftY)} " +
              "dtSeconds=${markerFloat(dtSeconds)} " +
              "distanceRateMps=${markerFloat(distanceRate)} " +
              "previousHeadlockedPanelDistanceMeters=${markerFloat(previousDistance)} " +
              "headlockedPanelDistanceMeters=${markerFloat(updatedDistance)} " +
              "leftStickUpIncreasesPanelDistance=true leftStickDownDecreasesPanelDistance=true " +
              "leftStickYPanelDistanceEnabled=${currentLeftStickPanelDistanceEnabled()} " +
              "leftStickYPanelScrollReserved=false " +
              "leftStickYProjectionHorizontalOffsetDisabled=true " +
              "panelDistanceControl=left-stick-y-free-transform-distance " +
              panelHeadlockMarkerFields()
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
            if (!handleControllerSecondaryButton(it)) {
              handleControllerPrimaryButton(it)
            }
          }
          motionEvent?.let { event ->
            if (!handleControllerSecondaryButton(event) && !handleControllerPrimaryButton(event)) {
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
          "channel=spatial-panel status=spatial-input-enabled " +
              "reason=${markerToken(reason)} spatialInterfaceEnableInput=$enableResult " +
              "gameControllerDeviceCount=${gameControllerIds.size} " +
              "pinnedGameControllerCount=${pinnedSpatialGameControllerIds.size} " +
              "newlyPinnedGameControllerCount=$newlyPinned " +
              "controllerInputRoutes=spatial-sdk-controller-component+spatial-sdk-avatar-body-controller+interaction-sdk-pointer+pinned-android-game-controller-fallback+native-openxr-diagnostic-opt-in"
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
                  "channel=spatial-controller-actions status=poll-error " +
                      "nativeControllerActionBridge=true controllerInput=left-thumbstick-y " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      "actionSetAttached=false"
              )
              Float.NaN
            }
    if (leftY.isFinite() && abs(leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      applyPanelHeadlockDistanceInput(
          leftY = leftY,
          inputSource = "native-openxr-action",
          controllerJoystickMapping = currentLeftStickPanelDistanceMapping(),
          detail =
              "leftThumbstickY=${markerFloat(leftY)} " +
                  "nativeControllerActionStartMask=$nativeSpatialControllerActionsStartMask",
      )
    }

    val rightY =
        runCatching { nativePollSpatialControllerRightThumbstickY() }
            .getOrElse { throwable ->
              nativeSpatialControllerActionsStarted = false
              marker(
                  "channel=spatial-controller-actions status=poll-error " +
                      "nativeControllerActionBridge=true controllerInput=right-thumbstick-y " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      "actionSetAttached=false"
              )
              Float.NaN
            }
    if (rightY.isFinite() && abs(rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      applyCameraHwbProjectionScaleInput(
          rightY = rightY,
          inputSource = "native-openxr-action",
          controllerJoystickMapping = "right-thumbstick-y-projection-target-scale",
          detail =
              "rightThumbstickY=${markerFloat(rightY)} " +
                  "nativeControllerActionStartMask=$nativeSpatialControllerActionsStartMask",
      )
    }

    val rightButtonBDown =
        runCatching { nativePollSpatialControllerRightButtonB() }
            .getOrElse { throwable ->
              nativeSpatialControllerActionsStarted = false
              nativeControllerSecondaryDown = false
              marker(
                  "channel=spatial-controller-actions status=poll-error " +
                      "nativeControllerActionBridge=true controllerInput=right-button-b " +
                      "error=${markerToken(throwable.javaClass.simpleName)} " +
                      "message=${markerToken(throwable.message ?: "none")} " +
                      "actionSetAttached=false"
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
              val buttonABit = ButtonBits.ButtonA
              val buttonBBit = ButtonBits.ButtonB
              val leftThumbUpBit = ButtonBits.ButtonThumbLU
              val leftThumbDownBit = ButtonBits.ButtonThumbLD
              val rightThumbUpBit = ButtonBits.ButtonThumbRU
              val rightThumbDownBit = ButtonBits.ButtonThumbRD
              var componentCount = 0
              var controllerTypeCount = 0
              var allControllerChangedButtons = 0
              var allControllerButtonState = 0
              var localControllerCount = 0
              var localActiveControllerCount = 0
              var localRightControllerCount = 0
              var localRightControllerType = "none"
              var localRightControllerAttachmentType = "none"
              var localRightControllerActive = false
              var localRightControllerButtonState = 0
              var localRightControllerChangedButtons = 0
              var avatarBodyCount = 0
              var playerAvatarBodyCount = 0
              var playerAvatarBody: AvatarBody? = null
              val dataModel = scene.spatialInterface.dataModel
              Query.where { has(Controller.id) }
                  .eval(dataModel)
                  .forEach { entity ->
                    val controller = entity.getComponent<Controller>()
                    componentCount += 1
                    val controllerType = controller.type == ControllerType.CONTROLLER
                    if (controllerType) {
                      controllerTypeCount += 1
                      allControllerButtonState = allControllerButtonState or controller.buttonState
                      allControllerChangedButtons =
                          allControllerChangedButtons or controller.changedButtons
                      val localController = runCatching { entity.isLocal() }.getOrDefault(false)
                      if (localController) {
                        localControllerCount += 1
                        if (controller.isActive) {
                          localActiveControllerCount += 1
                        }
                        val attachmentType =
                            entity.tryGetComponent<AvatarAttachment>()?.type ?: "none"
                        if (attachmentType == "right_controller") {
                          localRightControllerCount += 1
                          localRightControllerType = controller.type.name
                          localRightControllerAttachmentType = attachmentType
                          localRightControllerActive =
                              localRightControllerActive || controller.isActive
                          localRightControllerButtonState =
                              localRightControllerButtonState or controller.buttonState
                          localRightControllerChangedButtons =
                              localRightControllerChangedButtons or controller.changedButtons
                        }
                      }
                    }
                  }
              Query.where { has(AvatarBody.id) }
                  .eval(dataModel)
                  .forEach { entity ->
                    val avatarBody = entity.tryGetComponent<AvatarBody>() ?: return@forEach
                    avatarBodyCount += 1
                    if (entity.isLocal() && avatarBody.isPlayerControlled) {
                      playerAvatarBodyCount += 1
                      if (playerAvatarBody == null) {
                        playerAvatarBody = avatarBody
                      }
                    }
                  }
              val leftAvatarController = playerAvatarBody?.leftHand?.tryGetComponent<Controller>()
              val rightAvatarController = playerAvatarBody?.rightHand?.tryGetComponent<Controller>()
              val leftAvatarButtonState = leftAvatarController?.buttonState ?: 0
              val leftAvatarChangedButtons = leftAvatarController?.changedButtons ?: 0
              val rightAvatarButtonState = rightAvatarController?.buttonState ?: 0
              val rightAvatarChangedButtons = rightAvatarController?.changedButtons ?: 0
              val leftAvatarControllerUsable = leftAvatarController?.type == ControllerType.CONTROLLER
              val rightAvatarControllerUsable = rightAvatarController?.type == ControllerType.CONTROLLER
              val leftAvatarActive =
                  leftAvatarController?.let {
                    it.isActive
                  } == true
              val rightAvatarActive =
                  rightAvatarController?.let {
                    it.isActive
                  } == true
              val activeCount = (if (leftAvatarActive) 1 else 0) + (if (rightAvatarActive) 1 else 0)
              val leftInputButtonState = if (leftAvatarControllerUsable) leftAvatarButtonState else 0
              val leftInputChangedButtons =
                  if (leftAvatarControllerUsable) leftAvatarChangedButtons else 0
              val rightInputButtonState =
                  when {
                    localRightControllerCount > 0 -> localRightControllerButtonState
                    rightAvatarControllerUsable -> rightAvatarButtonState
                    else -> allControllerButtonState
                  }
              val rightInputChangedButtons =
                  when {
                    localRightControllerCount > 0 -> localRightControllerChangedButtons
                    rightAvatarControllerUsable -> rightAvatarChangedButtons
                    else -> allControllerChangedButtons
                  }
              val rightInputSource =
                  when {
                    localRightControllerCount > 0 -> "spatial-sdk-controller-component"
                    rightAvatarControllerUsable -> "spatial-sdk-avatar-body-controller"
                    else -> "spatial-sdk-controller-component-fallback"
                  }
              val buttonState =
                  leftInputButtonState or rightInputButtonState
              val changedButtons = leftInputChangedButtons or rightInputChangedButtons
              val rightAvatarDown = (rightInputButtonState and buttonABit) != 0
              val rightAvatarPressed =
                  rightAvatarDown && (rightInputChangedButtons and buttonABit) != 0
              val rightAvatarSecondaryDown =
                  (rightInputButtonState and buttonBBit) != 0
              val rightAvatarSecondaryPressed =
                  rightAvatarSecondaryDown && (rightInputChangedButtons and buttonBBit) != 0
              val leftAvatarThumbUp = (leftInputButtonState and leftThumbUpBit) != 0
              val leftAvatarThumbDown =
                  (leftInputButtonState and leftThumbDownBit) != 0
              val leftAvatarThumbY =
                  when {
                    leftAvatarThumbUp && !leftAvatarThumbDown -> -1.0f
                    leftAvatarThumbDown && !leftAvatarThumbUp -> 1.0f
                    else -> 0.0f
                  }
              val rightAvatarThumbUp = (rightInputButtonState and rightThumbUpBit) != 0
              val rightAvatarThumbDown =
                  (rightInputButtonState and rightThumbDownBit) != 0
              val rightAvatarThumbY =
                  when {
                    rightAvatarThumbUp && !rightAvatarThumbDown -> -1.0f
                    rightAvatarThumbDown && !rightAvatarThumbUp -> 1.0f
                    else -> 0.0f
                  }
              SpatialControllerPrimarySnapshot(
                  componentCount = componentCount,
                  controllerTypeCount = controllerTypeCount,
                  activeCount = activeCount,
                  localControllerCount = localControllerCount,
                  localActiveControllerCount = localActiveControllerCount,
                  localRightControllerType = localRightControllerType,
                  localRightControllerAttachmentType = localRightControllerAttachmentType,
                  localRightControllerActive = localRightControllerActive,
                  localRightControllerButtonState = localRightControllerButtonState,
                  localRightControllerChangedButtons = localRightControllerChangedButtons,
                  rightInputSource = rightInputSource,
                  avatarBodyCount = avatarBodyCount,
                  playerAvatarBodyCount = playerAvatarBodyCount,
                  leftAvatarControllerType = leftAvatarController?.type?.name ?: "none",
                  rightAvatarControllerType = rightAvatarController?.type?.name ?: "none",
                  leftAvatarControllerActive = leftAvatarController?.isActive == true,
                  rightAvatarControllerActive = rightAvatarController?.isActive == true,
                  leftAvatarButtonState = leftAvatarButtonState,
                  leftAvatarChangedButtons = leftAvatarChangedButtons,
                  rightAvatarButtonState = rightAvatarButtonState,
                  rightAvatarChangedButtons = rightAvatarChangedButtons,
                  buttonState = buttonState,
                  changedButtons = changedButtons,
                  allControllerButtonState = allControllerButtonState,
                  allControllerChangedButtons = allControllerChangedButtons,
                  leftThumbUp = leftAvatarThumbUp,
                  leftThumbDown = leftAvatarThumbDown,
                  leftThumbY = leftAvatarThumbY,
                  rightThumbUp = rightAvatarThumbUp,
                  rightThumbDown = rightAvatarThumbDown,
                  rightThumbY = rightAvatarThumbY,
                  down = rightAvatarDown,
                  pressed = rightAvatarPressed,
                  secondaryDown = rightAvatarSecondaryDown,
                  secondaryPressed = rightAvatarSecondaryPressed,
              )
            }
            .getOrElse { throwable ->
              spatialControllerPrimaryDown = false
              spatialControllerSecondaryDown = false
              if (
                  !spatialControllerRouteLogged ||
                      now - lastSpatialControllerRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
              ) {
                spatialControllerRouteLogged = true
                lastSpatialControllerRouteMarkerMs = now
                marker(
                    "channel=spatial-panel status=controller-input-route-error " +
                        "inputSource=spatial-sdk-avatar-body-controller " +
                        "controllerInput=right-primary-button error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} debugOnly=true"
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
          "channel=spatial-panel status=controller-input-route-ready " +
              "inputSource=${markerToken(snapshot.rightInputSource)} " +
              "controllerInput=right-primary-button+right-secondary-button-wall-toggle+right-thumb-up-down-projection-scale+${currentLeftStickPanelDistanceMapping()} " +
              "spatialVrInputSystem=${currentSpatialVrInputSystemToken()} " +
              "controllerComponentCount=${snapshot.componentCount} " +
              "controllerTypeComponentCount=${snapshot.controllerTypeCount} " +
              "activeControllerComponentCount=${snapshot.activeCount} " +
              "localControllerComponentCount=${snapshot.localControllerCount} " +
              "localActiveControllerComponentCount=${snapshot.localActiveControllerCount} " +
              "localRightControllerType=${markerToken(snapshot.localRightControllerType)} " +
              "localRightControllerAttachmentType=${markerToken(snapshot.localRightControllerAttachmentType)} " +
              "localRightControllerActive=${snapshot.localRightControllerActive} " +
              "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
              "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
              "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
              "avatarBodyCount=${snapshot.avatarBodyCount} " +
              "playerAvatarBodyCount=${snapshot.playerAvatarBodyCount} " +
              "leftAvatarControllerType=${markerToken(snapshot.leftAvatarControllerType)} " +
              "leftAvatarControllerActive=${snapshot.leftAvatarControllerActive} " +
              "leftAvatarButtonState=${snapshot.leftAvatarButtonState} " +
              "leftAvatarChangedButtons=${snapshot.leftAvatarChangedButtons} " +
              "rightAvatarControllerType=${markerToken(snapshot.rightAvatarControllerType)} " +
              "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
              "rightControllerInactiveButtonStateAccepted=true " +
              "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
              "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
              "buttonABit=${ButtonBits.ButtonA} buttonADown=${snapshot.down} " +
              "buttonBBit=${ButtonBits.ButtonB} buttonBDown=${snapshot.secondaryDown} " +
              "leftThumbUpBit=${ButtonBits.ButtonThumbLU} leftThumbDownBit=${ButtonBits.ButtonThumbLD} " +
              "leftThumbUp=${snapshot.leftThumbUp} leftThumbDown=${snapshot.leftThumbDown} " +
              "leftThumbYPanelDistanceEnabled=${currentLeftStickPanelDistanceEnabled()} leftThumbYPanelScrollReserved=false " +
              "leftThumbYProjectionHorizontalOffsetDisabled=true " +
              "rightThumbUpBit=${ButtonBits.ButtonThumbRU} rightThumbDownBit=${ButtonBits.ButtonThumbRD} " +
              "rightThumbUp=${snapshot.rightThumbUp} rightThumbDown=${snapshot.rightThumbDown} " +
              "rightThumbY=${markerFloat(snapshot.rightThumbY)} " +
              "activeButtonState=${snapshot.buttonState} activeChangedButtons=${snapshot.changedButtons} " +
              "allControllerButtonState=${snapshot.allControllerButtonState} " +
              "allControllerChangedButtons=${snapshot.allControllerChangedButtons} " +
              "debugOnly=true"
      )
    }

    if (snapshot.rightThumbY != 0.0f) {
      applyCameraHwbProjectionScaleInput(
          rightY = snapshot.rightThumbY,
          inputSource = snapshot.rightInputSource,
          controllerJoystickMapping = "right-thumb-up-down-projection-target-scale",
          detail =
              "rightThumbY=${markerFloat(snapshot.rightThumbY)} " +
                  "rightThumbUp=${snapshot.rightThumbUp} rightThumbDown=${snapshot.rightThumbDown} " +
                  "rightThumbUpBit=${ButtonBits.ButtonThumbRU} rightThumbDownBit=${ButtonBits.ButtonThumbRD} " +
                  "rightAvatarControllerType=${markerToken(snapshot.rightAvatarControllerType)} " +
                  "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
                  "rightControllerInactiveButtonStateAccepted=true " +
                  "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
                  "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
                  "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
                  "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
                  "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
                  "allControllerButtonState=${snapshot.allControllerButtonState}",
      )
    }
    if (snapshot.leftThumbY != 0.0f) {
      applyPanelHeadlockDistanceInput(
          leftY = snapshot.leftThumbY,
          inputSource = "spatial-sdk-avatar-body-controller",
          controllerJoystickMapping = currentLeftStickPanelDistanceMapping(),
          detail =
              "leftThumbY=${markerFloat(snapshot.leftThumbY)} " +
                  "leftThumbUp=${snapshot.leftThumbUp} leftThumbDown=${snapshot.leftThumbDown} " +
                  "leftThumbUpBit=${ButtonBits.ButtonThumbLU} leftThumbDownBit=${ButtonBits.ButtonThumbLD} " +
                  "leftAvatarControllerType=${markerToken(snapshot.leftAvatarControllerType)} " +
                  "leftAvatarControllerActive=${snapshot.leftAvatarControllerActive} " +
                  "leftAvatarButtonState=${snapshot.leftAvatarButtonState} " +
                  "leftAvatarChangedButtons=${snapshot.leftAvatarChangedButtons} " +
                  "allControllerButtonState=${snapshot.allControllerButtonState}",
      )
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
          detail =
              "buttonBBit=${ButtonBits.ButtonB} buttonState=${snapshot.buttonState} " +
                  "changedButtons=${snapshot.changedButtons} " +
                  "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
                  "localRightControllerType=${markerToken(snapshot.localRightControllerType)} " +
                  "localRightControllerAttachmentType=${markerToken(snapshot.localRightControllerAttachmentType)} " +
                  "localRightControllerActive=${snapshot.localRightControllerActive} " +
                  "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
                  "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
                  "rightAvatarControllerType=${markerToken(snapshot.rightAvatarControllerType)} " +
                  "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
                  "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
                  "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
                  "controllerComponentCount=${snapshot.componentCount} " +
                  "activeControllerComponentCount=${snapshot.activeCount}",
      )
      return
    }

    val pressedEdge = snapshot.pressed || (snapshot.down && !spatialControllerPrimaryDown)
    spatialControllerPrimaryDown = snapshot.down
    if (!pressedEdge || panelPlacement.visible || privateLayerPanelVisible) {
      return
    }
    openWorkflowPanelFromController(
        inputSource = snapshot.rightInputSource,
        detail =
                "buttonABit=${ButtonBits.ButtonA} buttonState=${snapshot.buttonState} " +
                "changedButtons=${snapshot.changedButtons} " +
                "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
                "localRightControllerType=${markerToken(snapshot.localRightControllerType)} " +
                "localRightControllerAttachmentType=${markerToken(snapshot.localRightControllerAttachmentType)} " +
                "localRightControllerActive=${snapshot.localRightControllerActive} " +
                "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
                "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
                "rightAvatarControllerType=${markerToken(snapshot.rightAvatarControllerType)} " +
                "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
                "rightControllerInactiveButtonStateAccepted=true " +
                "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
                "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
                "controllerComponentCount=${snapshot.componentCount} " +
                "activeControllerComponentCount=${snapshot.activeCount}",
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

  private fun toggleCameraHwbProjectionPlacementMode(inputSource: String, detail: String): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (!cameraHwbProjectionSecondaryToggleArmed) {
      marker(
          "channel=camera-hwb-spatial-probe status=projection-placement-toggle-ignored " +
              "controllerInput=right-secondary-button inputSource=${markerToken(inputSource)} " +
              "${detail.trim()} placementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
              "toggleGuard=wait-for-secondary-release-after-projection-start " +
              "projectionStartsInFullFov=true runtimeCrash=false"
      )
      return true
    }
    if (
        lastCameraHwbProjectionPlacementToggleMs > 0L &&
            now - lastCameraHwbProjectionPlacementToggleMs <
                CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS
    ) {
      marker(
          "channel=camera-hwb-spatial-probe status=projection-placement-toggle-ignored " +
              "controllerInput=right-secondary-button inputSource=${markerToken(inputSource)} " +
              "${detail.trim()} placementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
              "toggleDebounceMs=$CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS " +
              "runtimeCrash=false"
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
                    "channel=private-layer-panel status=layer-override-reapply-failed " +
                        "source=projection-placement-toggle spatialPrivateLayerControlPanel=true " +
                        "projectionPlacementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
                        "publicMultiStackOpaqueProjectionLayerOverride=${markerFloat(privateLayerOverride)} " +
                        "error=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")} runtimeCrash=false"
                )
                0L
              }
        } else {
          0L
        }
    marker(
        "channel=camera-hwb-spatial-probe status=projection-placement-toggled " +
            "controllerInput=right-secondary-button inputSource=${markerToken(inputSource)} " +
            "${detail.trim()} " +
            "previousPlacementMode=${previous.markerToken} " +
            "placementMode=${cameraHwbProjectionPlacementMode.markerToken} " +
            "virtualRoomWallPlacementActive=${cameraHwbProjectionPlacementMode == CameraHwbProjectionPlacementMode.VirtualRoomWall} " +
            "projectionEntityPresent=${cameraHwbProjectionEntity != null} " +
            "sceneQuadLayerRebuildStatus=not-rebuilt-existing-scene-anchor-updated " +
            "projectionDisplaySurface=${cameraHwbProjectionDisplayRoleForPlacement(cameraHwbProjectionPlacementMode)} " +
            "projectionRoomRenderOrder=projection-layer-over-virtual-room " +
            "cameraVideoProjectionLayerZIndex=${cameraHwbProjectionZIndexForPlacement(cameraHwbProjectionPlacementMode)} " +
            "cameraProjectionWallToggleInput=right-controller-secondary-button " +
            "cameraProjectionWallToggleEnabled=true virtualRoomWallCenterM=$CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER " +
            "virtualRoomWallSizeM=$CAMERA_HWB_PROJECTION_WALL_SIZE_MARKER " +
            "layerOverrideReappliedOnPlacementToggle=${nativeReceiptLibraryLoaded && layerOverrideReapplyMask != 0L} " +
            "layerOverrideUpdateMask=$layerOverrideReapplyMask " +
            "publicMultiStackOpaqueProjectionLayerOverride=${markerFloat(privateLayerOverride)} " +
            "layerOverrideAppliesToWallAndFullFov=true " +
            "cameraProjectionPlacementIndependentLayerControl=true " +
            "mrukPlacement=false passthroughRoomPlacement=false runtimeCrash=false"
    )
    return true
  }

  private fun armCameraHwbProjectionSecondaryToggle(inputSource: String) {
    if (!cameraHwbProjectionProbeStarted || cameraHwbProjectionSecondaryToggleArmed) {
      return
    }
    cameraHwbProjectionSecondaryToggleArmed = true
    marker(
        "channel=camera-hwb-spatial-probe status=projection-placement-toggle-armed " +
            "controllerInput=right-secondary-button inputSource=${markerToken(inputSource)} " +
            "projectionStartsInFullFov=true runtimeCrash=false"
    )
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
    if (!pressedEdge || panelPlacement.visible || privateLayerPanelVisible) {
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
    if (!pressedEdge || panelPlacement.visible || privateLayerPanelVisible) {
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
    if (panelPlacement.visible || privateLayerPanelVisible) {
      return false
    }
    val rightPrimary =
        inputSource == "spatial-sdk-avatar-body-controller" ||
            inputSource == "spatial-sdk-controller-component" ||
            inputSource == "spatial-sdk-controller-component-fallback" ||
            inputSource == "android-key-event" ||
            inputSource == "android-generic-motion-button"
    if (!rightPrimary) return false
    val opensPrivateLayerPanel =
        cameraStackSuppressesParticles || cameraHwbProjectionProbeStarted || spatialVideoProjectionStarted
    if (opensPrivateLayerPanel) {
      setPrivateLayerPanelVisible(
          true,
          focus = true,
          source = "right-controller-primary-button",
      )
    } else {
      setWorkflowPanelVisible(true, focus = true, source = "right-controller-primary-button")
    }
    marker(
        "channel=spatial-panel status=controller-primary-opened-panel " +
            "controllerInput=right-primary-button inputSource=${markerToken(inputSource)} " +
            "${detail.trim()} " +
            "panelMode=${panelStateToken()} workflowPanelVisible=${panelPlacement.visible} " +
            "privateLayerPanelVisible=$privateLayerPanelVisible " +
            "opensPrivateLayerPanel=$opensPrivateLayerPanel " +
            "spatialPrivateLayerControlPanel=$opensPrivateLayerPanel " +
            "debugOnly=true"
    )
    return true
  }

  private fun isJoystickEvent(event: MotionEvent): Boolean =
      event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)

  private fun currentPanelHeadlockJoystickEnabled(): Boolean =
      readOptionalBooleanSystemProperty(PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY) ?: true

  private fun currentLeftStickPanelDistanceEnabled(): Boolean =
      currentPanelHeadlockJoystickEnabled() &&
          when {
            privateLayerPanelVisible ->
                if (PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) !privateLayerPanelIsGrabbed()
                else privateLayerPanelPlacement.headlocked
            panelPlacement.visible -> panelPlacement.headlocked
            else -> false
          }

  private fun currentLeftStickPanelDistanceMapping(): String =
      if (privateLayerPanelVisible && PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) {
        "left-stick-y-private-panel-free-transform-distance"
      } else {
        "left-stick-y-panel-distance"
      }

  private fun privateLayerPanelIsGrabbed(): Boolean =
      privateLayerPanelEntity?.tryGetComponent<Grabbable>()?.isGrabbed ?: false

  private fun joystickAxis(event: MotionEvent, primaryAxis: Int, fallbackAxis: Int? = null): Float {
    val primary = event.getAxisValue(primaryAxis)
    val value =
        if (abs(primary) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE || fallbackAxis == null) {
          primary
        } else {
          event.getAxisValue(fallbackAxis)
        }
    return if (abs(value) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) value.coerceIn(-1.0f, 1.0f) else 0.0f
  }

  private fun readFloatSystemProperty(
      propertyName: String,
      fallback: Float,
      min: Float,
      max: Float,
  ): Float {
    val text = readSystemProperty(propertyName)
    val parsed = text.toFloatOrNull()
    return if (parsed != null && parsed.isFinite()) parsed.coerceIn(min, max) else fallback
  }

  private fun readIntSystemProperty(
      propertyName: String,
      fallback: Int,
      min: Int,
      max: Int,
  ): Int {
    val parsed = readSystemProperty(propertyName).toIntOrNull()
    return parsed?.coerceIn(min, max) ?: fallback
  }

  private fun readLongSystemProperty(
      propertyName: String,
      fallback: Long,
      min: Long,
      max: Long,
  ): Long {
    val parsed = readSystemProperty(propertyName).toLongOrNull()
    return parsed?.coerceIn(min, max) ?: fallback
  }

  private fun readOptionalFloatSystemProperty(
      propertyName: String,
      min: Float,
      max: Float,
  ): Float? {
    val parsed = readSystemProperty(propertyName).toFloatOrNull()
    return if (parsed != null && parsed.isFinite()) parsed.coerceIn(min, max) else null
  }

  private fun readOptionalBooleanSystemProperty(propertyName: String): Boolean? {
    return when (readSystemProperty(propertyName).lowercase(Locale.US)) {
      "1", "true", "yes", "on", "enabled" -> true
      "0", "false", "no", "off", "disabled" -> false
      else -> null
    }
  }

  private fun readOptionalStringIntentExtra(intent: Intent?, extraName: String): String? =
      if (intent?.hasExtra(extraName) == true) {
        intent.getStringExtra(extraName)?.trim()
      } else {
        null
      }

  private fun readOptionalBooleanIntentExtra(intent: Intent?, extraName: String): Boolean? =
      if (intent?.hasExtra(extraName) == true) {
        intent.getBooleanExtra(extraName, false)
      } else {
        null
      }

  private fun readOptionalIntIntentExtra(
      intent: Intent?,
      extraName: String,
      min: Int,
      max: Int,
  ): Int? =
      if (intent?.hasExtra(extraName) == true) {
        intent.getIntExtra(extraName, min).coerceIn(min, max)
      } else {
        null
      }

  private fun readOptionalFloatIntentExtra(
      intent: Intent?,
      extraName: String,
      min: Float,
      max: Float,
  ): Float? =
      if (intent?.hasExtra(extraName) == true) {
        val value = intent.getFloatExtra(extraName, min)
        if (value.isFinite()) value.coerceIn(min, max) else null
      } else {
        null
      }

  private fun spatialMultimodalInputEnabled(): Boolean =
      readOptionalBooleanSystemProperty(SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY)
          ?: SPATIAL_MULTIMODAL_INPUT_DEFAULT_ENABLED

  private fun nativeSpatialControllerActionsEnabled(): Boolean =
      readOptionalBooleanSystemProperty(NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY)
          ?: NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED

  private fun spatialMultimodalRequiredOpenXrExtensions(): List<String> =
      if (spatialMultimodalInputEnabled()) {
        SPATIAL_MULTIMODAL_REQUIRED_OPENXR_EXTENSIONS
      } else {
        emptyList()
      }

  private fun spatialRequiredOpenXrExtensions(): List<String> =
      (SPATIAL_PASSTHROUGH_REQUIRED_OPENXR_EXTENSIONS + spatialMultimodalRequiredOpenXrExtensions())
          .distinct()

  private fun spatialRequiredOpenXrExtensionMarker(): String =
      spatialRequiredOpenXrExtensions().ifEmpty { listOf("none") }.joinToString(";")

  private fun readSystemProperty(propertyName: String): String =
      runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, propertyName, "") as String
          }
          .getOrDefault("")
          .trim()

  private fun particleLayerStereoMarkerFields(): String =
      "stereoMode=$PARTICLE_LAYER_STEREO_MODE " +
          "perEyeExtent=${PARTICLE_LAYER_PER_EYE_WIDTH_PX}x$PARTICLE_LAYER_HEIGHT_PX " +
          "packedExtent=${PARTICLE_LAYER_WIDTH_PX}x$PARTICLE_LAYER_HEIGHT_PX"

  private fun vectorMarker(vector: Vector3): String =
      "${markerFloat(vector.x)};${markerFloat(vector.y)};${markerFloat(vector.z)}"

  private fun quaternionMarker(quaternion: Quaternion): String =
      "${markerFloat(quaternion.w)};${markerFloat(quaternion.x)};" +
          "${markerFloat(quaternion.y)};${markerFloat(quaternion.z)}"

  private fun markerFloat(value: Float): String = String.format(Locale.US, "%.4f", value)

  private fun markerFloat6(value: Float): String = String.format(Locale.US, "%.6f", value)

  private fun cross(left: Vector3, right: Vector3): Vector3 =
      Vector3(
          left.y * right.z - left.z * right.y,
          left.z * right.x - left.x * right.z,
          left.x * right.y - left.y * right.x,
      )

  private fun dot(left: Vector3, right: Vector3): Float =
      left.x * right.x + left.y * right.y + left.z * right.z

  private fun vectorSubtract(left: Vector3, right: Vector3): Vector3 =
      Vector3(left.x - right.x, left.y - right.y, left.z - right.z)

  private fun vectorLength(vector: Vector3): Float =
      sqrt((vector.x * vector.x + vector.y * vector.y + vector.z * vector.z).toDouble()).toFloat()

  private fun Vector3.normalizedOr(fallback: Vector3): Vector3 {
    val length = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    return if (length > 0.000001f) {
      Vector3(x / length, y / length, z / length)
    } else {
      fallback
    }
  }

  private fun particleLayerMediaSettings(): MediaPanelSettings =
      MediaPanelSettings(
          shape =
              QuadShapeOptions(
                  particleLayerSurfaceWidthMeters(PARTICLE_LAYER_TARGET_DISTANCE_METERS),
                  particleLayerSurfaceHeightMeters(PARTICLE_LAYER_TARGET_DISTANCE_METERS),
              ),
          display =
              FixedMediaPanelDisplayOptions(
                  widthPx = PARTICLE_LAYER_WIDTH_PX,
                  heightPx = PARTICLE_LAYER_HEIGHT_PX,
              ),
          rendering =
              MediaPanelRenderOptions(
                  false,
                  StereoMode.LeftRight,
                  SamplerConfig(),
                  0,
                  PARTICLE_LAYER_Z_INDEX,
              ),
          style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
          input = PanelInputOptions(0),
      )

  private fun privateLayerPanelSettings(): PanelSettings {
    return UIPanelSettings(
        shape = QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
        style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
        display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
        rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
        input =
            PanelInputOptions(
                ButtonBits.ButtonA or ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
            ),
    )
  }

  private fun panelDimensions(): PanelDimensions =
      PanelDimensions(Vector2(panelPlacement.widthMeters, panelPlacement.heightMeters))

  private fun privateLayerPanelDimensions(): PanelDimensions =
      PanelDimensions(
          Vector2(privateLayerPanelPlacement.widthMeters, privateLayerPanelPlacement.heightMeters)
      )

  private fun panelLauncherDimensions(): PanelDimensions =
      PanelDimensions(Vector2(PANEL_LAUNCHER_WIDTH_METERS, PANEL_LAUNCHER_HEIGHT_METERS))

  private fun panelStateToken(): String =
      when {
        privateLayerPanelVisible -> "spatial-sdk-private-layer-panel-open"
        panelPlacement.visible -> "spatial-sdk-workflow-panel-open"
        else -> "spatial-sdk-particle-view-panel-closed"
      }

  private fun recordPanelState(source: String) {
    runCatching { store.recordPanelForegroundState(panelStateToken(), source) }
        .getOrElse { throwable ->
          marker(
              "channel=spatial-panel status=panel-state-record-failed source=${markerToken(source)} " +
                  "error=${markerToken(throwable.javaClass.simpleName)}"
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
    if (spatialVirtualRoomEnabled() && !spatialVirtualRoomLoaded) {
      marker(
          "channel=spatial-sdk-asset-model status=start-deferred " +
              "module=${SpatialStagedAssetModule.MODULE_ID} reason=${markerToken(reason)} " +
              "deferredUntil=virtual-room-loaded spatialVirtualRoomLoaded=false " +
              "privateSourceAssetPackaged=false highRateJsonPayload=false"
      )
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
            ?: "codex-spatial-sdk-validation"
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: "real-hands"

    marker(
        "channel=validation status=self-test-start participantId=${markerToken(participantId)} " +
            "surfaceTargetId=${markerToken(surfaceTargetId)}"
    )
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
          "channel=validation status=self-test-block-started participantId=${markerToken(participantId)} " +
              "surfaceTargetId=${markerToken(surfaceTargetId)} validationDriverProfileId=$VALIDATION_DRIVER_PROFILE_ID"
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
                      "channel=validation status=self-test-complete participantId=${markerToken(participantId)} " +
                          "surfaceTargetId=${markerToken(surfaceTargetId)} validationDriverProfileId=$VALIDATION_DRIVER_PROFILE_ID"
                  )
                } catch (throwable: Throwable) {
                  marker("channel=validation status=self-test-failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
                  Log.e(TAG, "Spatial Camera Panel validation workflow failed", throwable)
                }
              },
              SpatialCameraPanelStore.DEFAULT_BLOCK_DURATION_MS + 750L,
          )
    } catch (throwable: Throwable) {
      marker("channel=validation status=self-test-failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
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
    val source = "remote-ui-command-$uiAction"
    marker(
        "channel=validation status=ui-command-start uiAction=${markerToken(uiAction)} " +
            "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"
    )
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
                intent.getFloatExtra(EXTRA_DRIVER0, particleControls.driver0Value01),
                intent.getFloatExtra(EXTRA_DRIVER1, particleControls.driver1Value01),
                intent.getFloatExtra(EXTRA_POINT_SCALE, particleControls.pointScale),
            )
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
          "channel=validation status=ui-command-complete uiAction=${markerToken(uiAction)} " +
              "panelMode=${panelStateToken()} workflowPanelVisible=${panelPlacement.visible} " +
              "surfaceTargetId=${markerToken(store.snapshot().surfaceTargetId)}"
      )
    } catch (throwable: Throwable) {
      marker(
          "channel=validation status=ui-command-failed uiAction=${markerToken(uiAction)} " +
              "error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}"
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
            ?: "codex-spatial-surface-target"
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: "real-hands"

    try {
      startRemoteSurfaceBlock(intent, "surface-target-activation", resetSession = true)
      marker(
          "channel=validation status=surface-target-activated " +
              "participantId=${markerToken(participantId)} surfaceTargetId=${markerToken(surfaceTargetId)} " +
              "validationDriverProfileId=$VALIDATION_DRIVER_PROFILE_ID panelMode=${panelStateToken()} " +
              "leftInParticleView=true"
      )
    } catch (throwable: Throwable) {
      marker(
          "channel=validation status=surface-target-activation-failed " +
              "surfaceTargetId=${markerToken(surfaceTargetId)} error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}"
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
        "channel=validation status=surface-target-activation-start " +
            "participantId=${markerToken(remoteParticipantId(intent))} " +
            "surfaceTargetId=${markerToken(remoteSurfaceTargetId(intent))} source=${markerToken(source)} " +
            "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"
    )
    scheduleParticleLayerLifecycleDiagnostics(source)
    if (resetSession) {
      store.resetForNewParticipant()
    }
    ensureRemoteParticipantAndPolarSetup(intent, source)
    store.selectSurface(remoteSurfaceTargetId(intent))
    store.prioritizeConditionForValidation(VALIDATION_DRIVER_PROFILE_ID)
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
          "channel=validation status=remote-participant-created " +
              "source=${markerToken(source)} participantId=${markerToken(remoteParticipantId(intent))}"
      )
    }
  }

  private fun remoteParticipantId(intent: Intent): String =
      intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: "codex-spatial-ui-command"

  private fun remoteSurfaceTargetId(intent: Intent): String =
      intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: "real-hands"

  private fun runPolarLiveValidationIfRequested(intent: Intent?) {
    if (intent?.action != ACTION_RUN_POLAR_LIVE_VALIDATION) {
      return
    }
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: "codex-spatial-polar-live-validation"
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: "real-hands"
    val scanDelayMs =
        intent.getIntExtra(EXTRA_POLAR_SCAN_SECONDS, 16).coerceIn(3, 60) * 1000L
    val connectDelayMs =
        intent.getIntExtra(EXTRA_POLAR_CONNECT_DELAY_SECONDS, 10).coerceIn(3, 60) * 1000L
    val ecgRunMs =
        intent.getIntExtra(EXTRA_POLAR_ECG_SECONDS, 14).coerceIn(3, 180) * 1000L
    val mainHandler = Handler(Looper.getMainLooper())

    marker(
        "channel=polar-live-validation status=start participantId=${markerToken(participantId)} " +
            "surfaceTargetId=${markerToken(surfaceTargetId)} scanSeconds=${scanDelayMs / 1000L} " +
            "connectDelaySeconds=${connectDelayMs / 1000L} ecgSeconds=${ecgRunMs / 1000L} " +
            "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"
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
      marker(
          "channel=polar-live-validation status=polar-panel-automation-ready " +
              "participantId=${markerToken(participantId)}"
      )
      panel.handleCommand("select_ecg")
      panel.handleCommand("scan")
      marker(
          "channel=polar-live-validation status=scan-command-issued " +
              "participantId=${markerToken(participantId)}"
      )
      mainHandler.postDelayed(
          {
            marker(
                "channel=polar-live-validation status=connect-requested " +
                    "discoveredDeviceCount=${panel.discoveredDeviceCount()}"
            )
            panel.connectBestDiscovered("ecg")
          },
          scanDelayMs,
      )
      mainHandler.postDelayed(
          {
            marker(
                "channel=polar-live-validation status=start-ecg-requested " +
                    "discoveredDeviceCount=${panel.discoveredDeviceCount()}"
            )
            panel.handleCommand("start_ecg")
          },
          scanDelayMs + connectDelayMs,
      )
      mainHandler.postDelayed(
          {
            val ecgReceiving = panel.isEcgReceiving()
            marker(
                "channel=polar-live-validation status=complete ecgReceiving=$ecgReceiving " +
                    "discoveredDeviceCount=${panel.discoveredDeviceCount()} " +
                    "ecgStatus=${markerToken(panel.ecgExperimentStatusLine(true))}"
            )
          },
          scanDelayMs + connectDelayMs + ecgRunMs,
      )
    } catch (throwable: Throwable) {
      marker("channel=polar-live-validation status=failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
      Log.e(TAG, "Spatial Camera Panel Polar live validation failed", throwable)
    }
  }

  private fun markerToken(value: String): String =
      value
          .trim()
          .replace(Regex("[^A-Za-z0-9._-]+"), "_")
          .ifBlank { "none" }
          .take(96)

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
        "channel=native-surface-particle-layer status=lifecycle-check phase=${markerToken(phase)} " +
            "renderPolicy=native-vulkan-wsi-surface-panel " +
            "activityMarkerFile=$ACTIVITY_MARKERS_FILE panelRegistrationCount=$panelRegistrationCount " +
            "panelMode=${panelStateToken()} workflowPanelVisible=${panelPlacement.visible} " +
            "launcherPanelVisible=${launcherPanelVisibleForPanelMode()} " +
            "legacyLauncherPanelSuppressed=${legacyLauncherPanelSuppressedForCameraStack()} " +
            "particleLayerEntityCreated=${particleLayerEntity != null} particleSurfacePanelReady=$particleSurfacePanelReady " +
            "particleSurfaceConsumerCalled=$particleSurfaceConsumerCalled " +
            "particleSurfaceConsumerSurfaceValid=$particleSurfaceConsumerSurfaceValid " +
            "particleLayerStarted=$particleLayerStarted nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
            "lastNativeSurfaceStartMask=$lastNativeSurfaceStartMask " +
            "nativeReceiptLibraryLoaded=$nativeReceiptLibraryLoaded nativeReceiptLibraryError=${markerToken(nativeReceiptLibraryError)} " +
            "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
            "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
            "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
            "currentDriverProfileId=${markerToken(snapshot?.currentConditionId ?: "none")} " +
            "currentProfileId=${markerToken(snapshot?.currentProfileId ?: "none")} " +
            particleLayerPlacementMarkerFields() + " " +
            particleLayerStereoMarkerFields()
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
    private const val EXTRA_POINT_SCALE = "point_scale"
    private const val EXTRA_RUN_LABEL = "run_label"
    private const val EXTRA_OPERATOR_ID = "operator_id"
    private const val EXTRA_NOTES = "notes"
    private const val EXTRA_COMFORT_RATING = "comfort_rating"
    private const val EXTRA_INTENSITY_RATING = "intensity_rating"
    private const val EXTRA_ENGAGEMENT_RATING = "engagement_rating"
    private const val EXTRA_POLAR_SCAN_SECONDS = "polar_scan_seconds"
    private const val EXTRA_POLAR_CONNECT_DELAY_SECONDS = "polar_connect_delay_seconds"
    private const val EXTRA_POLAR_ECG_SECONDS = "polar_ecg_seconds"
    private const val PANEL_WIDTH_METERS = 1.20f
    private const val PANEL_HEIGHT_METERS = 1.254f
    private const val PANEL_DP_PER_METER = 720f
    private const val PANEL_FOCUS_Y_METERS = 1.1f
    private const val PANEL_FOCUS_Z_METERS = 0.475f
    private const val PANEL_WORLD_Y_MIN_METERS = 0.8f
    private const val PANEL_WORLD_Y_MAX_METERS = 2.2f
    private const val PANEL_WORLD_Z_MIN_METERS = -3.2f
    private const val PANEL_WORLD_Z_MAX_METERS = 0.8f
    private const val PANEL_HEADLOCK_TUNING_FILE = "spatial_camera_panel_headlock_tuning.json"
    private const val PANEL_HEADLOCK_OFFSET_X_METERS = 0.0f
    private const val PANEL_HEADLOCK_OFFSET_Y_METERS = 0.0f
    private const val PANEL_HEADLOCK_DISTANCE_METERS = 1.40f
    private const val PANEL_HEADLOCK_SCALE = 0.65f
    private const val PANEL_FRONT_OF_CAMERA_VIDEO_DISTANCE_METERS = 0.72f
    private const val PANEL_FRONT_OF_CAMERA_VIDEO_SCALE = 0.65f
    private const val PRIVATE_LAYER_PANEL_OFFSET_X_METERS = 0.0f
    private const val PRIVATE_LAYER_PANEL_OFFSET_Y_METERS = 0.0f
    private const val PRIVATE_LAYER_PANEL_DISTANCE_METERS = 0.72f
    private const val PRIVATE_LAYER_PANEL_SCALE = 0.65f
    private const val PRIVATE_LAYER_PANEL_MAX_LATERAL_DISTANCE_FRACTION = 0.80f
    private const val PRIVATE_LAYER_PANEL_GRAB_MIN_HEIGHT_METERS = 0.55f
    private const val PRIVATE_LAYER_PANEL_GRAB_MAX_HEIGHT_METERS = 2.50f
    private const val PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM = true
    private const val PANEL_HEADLOCK_OFFSET_X_MIN_METERS = -0.85f
    private const val PANEL_HEADLOCK_OFFSET_X_MAX_METERS = 0.85f
    private const val PANEL_HEADLOCK_OFFSET_Y_MIN_METERS = -0.65f
    private const val PANEL_HEADLOCK_OFFSET_Y_MAX_METERS = 0.65f
    private const val PANEL_HEADLOCK_DISTANCE_MIN_METERS = 0.35f
    private const val PANEL_HEADLOCK_DISTANCE_MAX_METERS = 1.50f
    private const val PANEL_HEADLOCK_SCALE_MIN = 0.65f
    private const val PANEL_HEADLOCK_SCALE_MAX = 1.60f
    private const val PANEL_HEADLOCK_ENABLED_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.enabled"
    private const val PANEL_HEADLOCK_OFFSET_X_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_x_m"
    private const val PANEL_HEADLOCK_OFFSET_Y_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_y_m"
    private const val PANEL_HEADLOCK_DISTANCE_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.distance_meters"
    private const val PANEL_HEADLOCK_WIDTH_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.width_meters"
    private const val PANEL_HEADLOCK_HEIGHT_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.height_meters"
    private const val PANEL_HEADLOCK_SCALE_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.scale"
    private const val PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.enabled"
    private const val PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.translate_rate_mps"
    private const val PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.distance_rate_mps"
    private const val PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.scale_rate_per_second"
    private const val PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_METERS_PER_SECOND = 0.18f
    private const val PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND = 0.16f
    private const val PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PER_SECOND = 0.30f
    private const val PANEL_HEADLOCK_JOYSTICK_DEADZONE = 0.14f
    private const val PRIVATE_LAYER_PANEL_GRABBABLE_MARKER_INTERVAL_MS = 450L
    private const val PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS = 450L
    private const val PANEL_HEADLOCK_MARKER_INTERVAL_MS = 900L
    private const val SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS = 2500L
    private const val SPATIAL_JOYSTICK_ARBITRATION_MARKER_INTERVAL_MS = 350L
    private const val SPATIAL_VR_INPUT_SYSTEM_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.vr_input_system"
    private const val SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN = "interaction_sdk"
    private const val SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.consume_left_right_input"
    private const val SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_DEFAULT = false
    private const val SPATIAL_VIRTUAL_ROOM_MODULE_ID = "spatial-sdk-packaged-virtual-room"
    private const val SPATIAL_VIRTUAL_ROOM_ENABLED_PROPERTY =
        "debug.rustyquest.spatial.virtual_room.enabled"
    private const val SPATIAL_SKYBOX_MODULE_ID = "spatial-sdk-skybox-only"
    private const val SPATIAL_SKYBOX_ENABLED_PROPERTY = "debug.rustyquest.spatial.skybox.enabled"
    private const val SPATIAL_VIRTUAL_ROOM_SCENE_URI = "apk:///scenes/Composition.glxf"
    private const val SPATIAL_VIRTUAL_ROOM_ENVIRONMENT_NODE = "Environment"
    private const val SPATIAL_VIRTUAL_ROOM_IBL_ASSET = "environment.env"
    private const val SPATIAL_VIRTUAL_ROOM_SKYDOME_RESOURCE = "skydome"
    private const val SPATIAL_VIRTUAL_ROOM_SKYBOX_MESH_URI = "mesh://skybox"
    private const val PANEL_WIDTH_MIN_METERS = 1.20f
    private const val PANEL_WIDTH_MAX_METERS = 2.60f
    private const val PANEL_HEIGHT_MIN_METERS = 0.75f
    private const val PANEL_HEIGHT_MAX_METERS = 1.65f
    private const val PANEL_LAUNCHER_WIDTH_METERS = 0.78f
    private const val PANEL_LAUNCHER_HEIGHT_METERS = 0.30f
    private const val PANEL_LAUNCHER_DP_PER_METER = 680f
    private const val PANEL_LAUNCHER_X_METERS = -0.62f
    private const val PANEL_LAUNCHER_Y_METERS = 0.92f
    private const val PANEL_LAUNCHER_Z_METERS = 0.525f
    private const val PARTICLE_LAYER_PER_EYE_WIDTH_PX = 1024
    private const val PARTICLE_LAYER_WIDTH_PX = PARTICLE_LAYER_PER_EYE_WIDTH_PX * 2
    private const val PARTICLE_LAYER_HEIGHT_PX = 1024
    private const val PARTICLE_LAYER_TARGET_DISTANCE_METERS = 0.72f
    private const val PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters"
    private const val PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS = 0.20f
    private const val PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS = 1.50f
    private const val PARTICLE_LAYER_WIDTH_METERS = 1.44f
    private const val PARTICLE_LAYER_HEIGHT_METERS = 1.44f
    private const val PARTICLE_LAYER_WIDTH_PER_DISTANCE =
        PARTICLE_LAYER_WIDTH_METERS / PARTICLE_LAYER_TARGET_DISTANCE_METERS
    private const val PARTICLE_LAYER_HEIGHT_PER_DISTANCE =
        PARTICLE_LAYER_HEIGHT_METERS / PARTICLE_LAYER_TARGET_DISTANCE_METERS
    private const val PARTICLE_LAYER_DIMENSION_MIN_METERS = 0.20f
    private const val PARTICLE_LAYER_DIMENSION_MAX_METERS = 3.00f
    private const val PARTICLE_LAYER_SURFACE_DIMENSION_MAX_METERS = 4.00f
    private const val PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE = 1.35f
    private const val PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY =
        "debug.rustyquest.spatial_camera_panel.particle_layer.surface_overscan_scale"
    private const val PARTICLE_LAYER_SURFACE_OVERSCAN_MIN_SCALE = 1.00f
    private const val PARTICLE_LAYER_SURFACE_OVERSCAN_MAX_SCALE = 2.25f
    private const val PARTICLE_LAYER_X_METERS = 0.0f
    private const val PARTICLE_LAYER_Y_METERS = 1.22f
    private const val PARTICLE_LAYER_Z_METERS = -0.72f
    private const val PARTICLE_LAYER_PARTICLE_COUNT = 2048
    private const val PARTICLE_LAYER_FRAME_COUNT = 0
    private const val PARTICLE_LAYER_Z_INDEX = 8
    private const val PARTICLE_LAYER_STEREO_MODE = "LeftRight"
    private const val PARTICLE_LAYER_PLACEMENT_MODE = "viewer-pose-projection-locked-quad"
    private const val PARTICLE_LAYER_PLACEMENT_AUTHORITY = "spatial-sdk-viewer-pose-scene-tick"
    private const val PARTICLE_LAYER_TARGET_COORDINATE_SPACE = "spatial-sdk-surface-panel-eye-uv"
    private const val PARTICLE_LAYER_TARGET_PROJECTION_SPACE =
        "spatial-sdk-panel-plane-perspective-projection"
    private const val PARTICLE_LAYER_PROJECTION_CONTENT_MAPPING_MODE =
        "world-to-spatial-sdk-panel-plane-left-right"
    private const val PARTICLE_LAYER_TARGET_FOV_TANGENTS = "panel-plane-derived"
    private const val PARTICLE_LAYER_TARGET_SURFACE_UV_RECT = "0.0;0.0;1.0;1.0"
    private const val PARTICLE_LAYER_VIEW_ORIGIN_METERS = "0.0;0.0;2.0"
    private const val PARTICLE_LAYER_VIEW_ORIGIN_YAW_DEGREES = "180.0"
    private const val PARTICLE_LAYER_PROJECTION_MARKER_INTERVAL_MS = 900L
    private const val EXTERNAL_SWAPCHAIN_PROBE_PROPERTY =
        "debug.rustyquest.spatial.external_swapchain_probe"
    private const val EXTERNAL_SWAPCHAIN_PROBE_CYCLES_PROPERTY =
        "debug.rustyquest.spatial.external_swapchain_probe.cycles"
    private const val EXTERNAL_SWAPCHAIN_PROBE_CYCLE_MS_PROPERTY =
        "debug.rustyquest.spatial.external_swapchain_probe.cycle_ms"
    private const val EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX = 256
    private const val EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX = 256
    private const val EXTERNAL_SWAPCHAIN_PROBE_WIDTH_METERS = 0.35f
    private const val EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_METERS = 0.35f
    private const val EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS = 0.85f
    private const val EXTERNAL_SWAPCHAIN_PROBE_Z_INDEX = 18
    private const val EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLES = 1
    private const val EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLES = 10
    private const val EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLE_MS = 60_000L
    private const val EXTERNAL_SWAPCHAIN_PROBE_MIN_CYCLE_MS = 1_000L
    private const val EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLE_MS = 60_000L
    private const val EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS = 750L
    private const val SDK_QUAD_SURFACE_PROBE_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_surface_probe"
    private const val SDK_QUAD_SURFACE_PROBE_HOLD_MS_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_surface_probe.hold_ms"
    private const val SDK_QUAD_SURFACE_PROBE_WIDTH_PX = 512
    private const val SDK_QUAD_SURFACE_PROBE_HEIGHT_PX = 512
    private const val SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS = 8
    private const val SDK_QUAD_SURFACE_PROBE_WIDTH_METERS = 0.55f
    private const val SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS = 0.55f
    private const val SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS = 0.85f
    private const val SDK_QUAD_SURFACE_PROBE_Z_INDEX = 22
    private const val SDK_QUAD_SURFACE_PROBE_DEFAULT_HOLD_MS = 30_000L
    private const val SDK_QUAD_SURFACE_PROBE_MIN_HOLD_MS = 1_000L
    private const val SDK_QUAD_SURFACE_PROBE_MAX_HOLD_MS = 120_000L
    private const val SDK_QUAD_VULKAN_PROBE_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_vulkan_probe"
    private const val SDK_QUAD_VULKAN_PROBE_HOLD_MS_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_vulkan_probe.hold_ms"
    private const val SDK_QUAD_VULKAN_PROBE_FRAME_COUNT_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_vulkan_probe.frame_count"
    private const val SDK_QUAD_VULKAN_PROBE_DEFAULT_HOLD_MS = 8_000L
    private const val SDK_QUAD_VULKAN_PROBE_MIN_HOLD_MS = 1_000L
    private const val SDK_QUAD_VULKAN_PROBE_MAX_HOLD_MS = 120_000L
    private const val SDK_QUAD_VULKAN_PROBE_DEFAULT_FRAME_COUNT = 240
    private const val SDK_QUAD_VULKAN_PROBE_MAX_FRAME_COUNT = 1_800
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe"
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_HOLD_MS_PROPERTY =
        "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe.hold_ms"
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX = 2048
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX = 1024
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX = 1024
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS = 1.15f
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS = 1.15f
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW = 24
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH = 34
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH = 0.88f
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW = 0.45f
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_CHANGE_MS = 1_500L
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_CHANGE_MS = 3_000L
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_RESTORE_MS = 5_500L
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_DEFAULT_HOLD_MS = 30_000L
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_MIN_HOLD_MS = 3_000L
    private const val SDK_QUAD_STEREO_ALPHA_PROBE_MAX_HOLD_MS = 120_000L
    private const val PANEL_SURFACE_MATRIX_PROBE_PROPERTY =
        "debug.rustyquest.spatial.panel_surface_matrix_probe"
    private const val PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX = 512
    private const val PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX = 512
    private const val PANEL_SURFACE_MATRIX_PROBE_FRAME_COUNT = 90
    private const val PANEL_SURFACE_MATRIX_PROBE_VARIANT_HOLD_MS = 2_500L
    private const val PANEL_SURFACE_MATRIX_PROBE_INTER_VARIANT_MS = 500L
    private const val CAMERA_HWB_PROBE_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_probe"
    private const val CAMERA_HWB_PROBE_HOLD_MS_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_probe.hold_ms"
    private const val CAMERA_HWB_PROBE_FRAME_COUNT_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_probe.frame_count"
    private const val CAMERA_HWB_PROBE_READER_MAX_IMAGES_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_probe.reader_max_images"
    private const val CAMERA_HWB_PROBE_WIDTH_PX = 1024
    private const val CAMERA_HWB_PROBE_HEIGHT_PX = 512
    private const val CAMERA_HWB_PROBE_WIDTH_METERS = 1.0f
    private const val CAMERA_HWB_PROBE_HEIGHT_METERS = 0.5f
    private const val CAMERA_HWB_PROBE_Z_INDEX = 36
    private const val CAMERA_HWB_PROBE_DEFAULT_HOLD_MS = 10_000L
    private const val CAMERA_HWB_PROBE_MIN_HOLD_MS = 2_000L
    private const val CAMERA_HWB_PROBE_MAX_HOLD_MS = 120_000L
    private const val CAMERA_HWB_PROBE_DEFAULT_FRAME_COUNT = 240
    private const val CAMERA_HWB_PROBE_MAX_FRAME_COUNT = 1_800
    private const val CAMERA_HWB_PROBE_DEFAULT_READER_MAX_IMAGES = 4
    private const val CAMERA_HWB_PROBE_MIN_READER_MAX_IMAGES = 3
    private const val CAMERA_HWB_PROBE_MAX_READER_MAX_IMAGES = 12
    private const val CAMERA_HWB_PROJECTION_PROBE_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe"
    private const val CAMERA_HWB_PROJECTION_READER_MAX_IMAGES_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.reader_max_images"
    private const val CAMERA_HWB_PROJECTION_WIDTH_PX = 2048
    private const val CAMERA_HWB_PROJECTION_HEIGHT_PX = 1024
    private const val CAMERA_HWB_PROJECTION_PER_EYE_WIDTH_PX = 1024
    private const val CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS = 1.0f
    private const val CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER = "1.00"
    private const val CAMERA_HWB_PROJECTION_TARGET_SCALE_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.projection.target.scale"
    private const val CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.projection.target.joystick.scale.rate_per_second"
    private const val CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.depth.layer_policy"
    private const val CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PER_SECOND = 0.30f
    private const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV = 0.046320f
    private const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV = -0.12f
    private const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV = 0.12f
    private const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_RANGE_MARKER =
        "-0.120000..0.120000"
    private const val CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_MARKER_INTERVAL_MS = 450L
    private const val CAMERA_HWB_PROJECTION_TARGET_FOV_TANGENTS = "-1.0;1.0;-1.0;1.0"
    private const val CAMERA_HWB_PROJECTION_SURFACE_OVERSCAN_MARKER = "1.0"
    private const val CAMERA_HWB_PROJECTION_BORDER_OPACITY_MARKER = "0.0"
    private const val CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT = 1.0f
    private const val CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE = 0.25f
    private const val CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE = 1.80f
    private const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_X = 0.171875f
    private const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_Y = 0.218750f
    private const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_WIDTH = 0.750000f
    private const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_HEIGHT = 0.656250f
    private const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_X = 0.078125f
    private const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_Y = 0.218750f
    private const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_WIDTH = 0.750000f
    private const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_HEIGHT = 0.671875f
    private const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_MARKER =
        "0.171875;0.218750;0.750000;0.656250"
    private const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_MARKER =
        "0.078125;0.218750;0.750000;0.671875"
    private const val CAMERA_HWB_PROJECTION_TARGET = "full-eye"
    private const val CAMERA_HWB_PROJECTION_PLACEMENT_MODE =
        "viewer-pose-projection-locked-quad"
    private const val CAMERA_HWB_PROJECTION_PLACEMENT_AUTHORITY =
        "spatial-sdk-viewer-pose-scene-tick"
    private const val CAMERA_HWB_PROJECTION_WALL_PLACEMENT_MODE =
        "virtual-room-wall-fixed-quad"
    private const val CAMERA_HWB_PROJECTION_WALL_PLACEMENT_AUTHORITY =
        "spatial-sdk-virtual-room-fixed-wall"
    private const val CAMERA_HWB_PROJECTION_WALL_CENTER_X_METERS = 0.0f
    private const val CAMERA_HWB_PROJECTION_WALL_CENTER_Y_METERS = 1.45f
    private const val CAMERA_HWB_PROJECTION_WALL_CENTER_Z_METERS = -2.40f
    private const val CAMERA_HWB_PROJECTION_WALL_WIDTH_METERS = 1.60f
    private const val CAMERA_HWB_PROJECTION_WALL_HEIGHT_METERS = 0.90f
    private const val CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER = "0.0;1.45;-2.40"
    private const val CAMERA_HWB_PROJECTION_WALL_SIZE_MARKER = "1.60;0.90"
    private const val CAMERA_HWB_PROJECTION_VIEWER_LOCKED_Z_INDEX = 40
    private const val CAMERA_HWB_PROJECTION_WALL_Z_INDEX = 44
    private const val CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS = 650L
    private const val CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED = 0
    private const val CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES = 4
    private const val CAMERA_HWB_PROJECTION_MIN_READER_MAX_IMAGES = 3
    private const val CAMERA_HWB_PROJECTION_MAX_READER_MAX_IMAGES = 12
    private const val CAMERA_HWB_PROJECTION_MARKER_INTERVAL_MS = 900L
    private const val SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY =
        "debug.rustyquest.spatial.video_projection_probe"
    private const val SPATIAL_VIDEO_PROJECTION_FRAME_COUNT_UNBOUNDED = 0
    private const val CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
    private const val CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.path"
    private const val CAMERA_HWB_PROJECTION_VIDEO_STEREO_LAYOUT_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.stereo_layout"
    private const val CAMERA_HWB_PROJECTION_VIDEO_WIDTH_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.width"
    private const val CAMERA_HWB_PROJECTION_VIDEO_HEIGHT_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.height"
    private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.max_images"
    private const val CAMERA_HWB_PROJECTION_VIDEO_FPS_CAP_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.fps_cap"
    private const val CAMERA_HWB_PROJECTION_VIDEO_LOOPING_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.looping"
    private const val CAMERA_HWB_PROJECTION_VIDEO_OPACITY_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.opacity"
    private const val CAMERA_HWB_PROJECTION_VIDEO_HIGH_RATE_JSON_PAYLOAD_PROPERTY =
        "debug.rustyquest.spatial.camera_hwb_projection_probe.video.high_rate_json_payload"
    private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED = false
    private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_STEREO_LAYOUT =
        "side-by-side-left-right"
    private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_WIDTH_PX = 3840
    private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HEIGHT_PX = 1920
    private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_WIDTH_PX = 320
    private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_HEIGHT_PX = 240
    private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_WIDTH_PX = 4096
    private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_HEIGHT_PX = 4096
    private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_IMAGES = 3
    private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_IMAGES = 2
    private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES = 6
    private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_FPS = 30
    private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_FPS = 1
    private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_FPS = 90
    private const val EXTRA_VIDEO_PROJECTION_ENABLED =
        "rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
    private const val EXTRA_VIDEO_PROJECTION_PATH =
        "rustyquest.spatial.camera_hwb_projection_probe.video.path"
    private const val EXTRA_VIDEO_PROJECTION_STEREO_LAYOUT =
        "rustyquest.spatial.camera_hwb_projection_probe.video.stereo_layout"
    private const val EXTRA_VIDEO_PROJECTION_WIDTH =
        "rustyquest.spatial.camera_hwb_projection_probe.video.width"
    private const val EXTRA_VIDEO_PROJECTION_HEIGHT =
        "rustyquest.spatial.camera_hwb_projection_probe.video.height"
    private const val EXTRA_VIDEO_PROJECTION_MAX_IMAGES =
        "rustyquest.spatial.camera_hwb_projection_probe.video.max_images"
    private const val EXTRA_VIDEO_PROJECTION_FPS_CAP =
        "rustyquest.spatial.camera_hwb_projection_probe.video.fps_cap"
    private const val EXTRA_VIDEO_PROJECTION_LOOPING =
        "rustyquest.spatial.camera_hwb_projection_probe.video.looping"
    private const val EXTRA_VIDEO_PROJECTION_OPACITY =
        "rustyquest.spatial.camera_hwb_projection_probe.video.opacity"
    private const val EXTRA_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD =
        "rustyquest.spatial.camera_hwb_projection_probe.video.high_rate_json_payload"
    private const val OPENXR_ERROR_HANDLE_INVALID = -12
    private const val NATIVE_RECEIPT_LIBRARY = "spatial_camera_panel_native_receipt"
    private const val SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY =
        "debug.rustyquest.spatial.multimodal_input.enabled"
    private const val SPATIAL_MULTIMODAL_INPUT_DEFAULT_ENABLED = false
    private const val NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY =
        "debug.rustyquest.spatial.native_controller_actions.enabled"
    private const val NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED = false
    private const val XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION =
        "XR_META_simultaneous_hands_and_controllers"
    private const val XR_META_DETACHED_CONTROLLERS_EXTENSION =
        "XR_META_detached_controllers"
    private const val XR_FB_PASSTHROUGH_EXTENSION = "XR_FB_passthrough"
    private const val XR_META_ENVIRONMENT_DEPTH_EXTENSION = "XR_META_environment_depth"
    private val SPATIAL_PASSTHROUGH_REQUIRED_OPENXR_EXTENSIONS =
        listOf(XR_FB_PASSTHROUGH_EXTENSION, XR_META_ENVIRONMENT_DEPTH_EXTENSION)
    private val SPATIAL_MULTIMODAL_REQUIRED_OPENXR_EXTENSIONS =
        listOf(
            XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION,
            XR_META_DETACHED_CONTROLLERS_EXTENSION,
        )
    private const val SPATIAL_MULTIMODAL_INPUT_SUPPORTED_BIT = 1L shl 8
    private const val SPATIAL_MULTIMODAL_INPUT_RESUME_RESOLVED_BIT = 1L shl 9
    private const val SPATIAL_MULTIMODAL_INPUT_RESUME_SUCCEEDED_BIT = 1L shl 10
    private const val NATIVE_SPATIAL_CONTROLLER_ACTION_SET_ATTACHED_BIT = 1L shl 8
    private const val SPATIAL_NATIVE_PASSTHROUGH_LAYER_ACTIVE_BIT = 1L shl 10
    private const val SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_STARTED_BIT = 1L shl 22
    private const val SPATIAL_ENVIRONMENT_DEPTH_ACQUIRE_THREAD_STARTED_BIT = 1L shl 23
    private const val NATIVE_RECEIPT_OPENXR_INSTANCE_BIT = 1L shl 1
    private const val NATIVE_RECEIPT_OPENXR_SESSION_BIT = 1L shl 2
    private const val NATIVE_RECEIPT_OPENXR_GET_PROC_BIT = 1L shl 3
    private const val NATIVE_RECEIPT_PANEL_SURFACE_BIT = 1L shl 4
    private const val NATIVE_RECEIPT_OPENXR_GET_PROC_CALLABLE_BIT = 1L shl 5
    private const val NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED_BIT = 1L shl 6
    private const val NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED_BIT = 1L shl 7
    private const val NATIVE_RECEIPT_XR_GET_SYSTEM_RESOLVED_BIT = 1L shl 8
    private const val NATIVE_RECEIPT_XR_GET_SYSTEM_SUCCEEDED_BIT = 1L shl 9
    private const val NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_RESOLVED_BIT = 1L shl 10
    private const val NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED_BIT = 1L shl 11
    private const val NATIVE_RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED_BIT = 1L shl 12
    private const val NATIVE_RECEIPT_XR_GET_VULKAN_GRAPHICS_DEVICE2_RESOLVED_BIT = 1L shl 13
    private const val NATIVE_RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED_BIT = 1L shl 14
    private const val NATIVE_RECEIPT_VK_INSTANCE_CREATED_BIT = 1L shl 15
    private const val NATIVE_RECEIPT_VK_GRAPHICS_DEVICE_OBTAINED_BIT = 1L shl 16
    private const val NATIVE_RECEIPT_VK_GRAPHICS_COMPUTE_QUEUE_FOUND_BIT = 1L shl 17
    private const val NATIVE_RECEIPT_VK_DEVICE_CREATED_BIT = 1L shl 18
    private const val NATIVE_RECEIPT_VK_QUEUE_OBTAINED_BIT = 1L shl 19
    private const val NATIVE_RECEIPT_VK_OBJECTS_DESTROYED_BIT = 1L shl 20
  }
}
