package io.github.mesmerprism.rustyquest.kuramoto_spatial

import android.content.Intent
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
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.PanelSurface
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneQuadLayer
import com.meta.spatial.runtime.SceneSwapchain
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.MediaPanelDisplayOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class KuramotoSpatialActivity : AppSystemActivity() {
  private val store: KuramotoExperimentStore by lazy(LazyThreadSafetyMode.NONE) {
    KuramotoExperimentStore(this)
  }
  private var nativeReceiptLibraryLoaded = false
  private var nativeReceiptLibraryError = "not-loaded"
  private var panelEntity: Entity? = null
  private var panelLauncherEntity: Entity? = null
  private var panelPlacement = PanelPlacement()
  private var particleControls = SurfaceParticleControlState()
  private var particleLayerEntity: Entity? = null
  private var panelRegistrationCount = 0
  private var particleSurfacePanelReady = false
  private var particleSurfaceConsumerCalled = false
  private var particleSurfaceConsumerSurfaceValid = false
  private var particleLayerStarted = false
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
  private var spatialControllerPrimaryDown = false
  private var spatialControllerRouteLogged = false
  private var lastSpatialControllerRouteMarkerMs = 0L
  private var lastSpatialControllerComponentCount = -1
  private var lastSpatialControllerActiveCount = -1
  private var androidControllerPrimaryKeyDown = false
  private var androidControllerPrimaryMotionDown = false
  private var externalSwapchainProbeStarted = false
  private var externalSwapchainProbeLayer: SceneQuadLayer? = null
  private var externalSwapchainProbeSceneObject: SceneObject? = null
  private var externalSwapchainProbeWrappedSwapchain: SceneSwapchain? = null
  private var externalSwapchainProbeExternalHandle = 0L
  private val externalSwapchainProbeSdkWrapRetainers = mutableListOf<SceneSwapchain>()
  private val externalSwapchainProbeExternalWrapRetainers = mutableListOf<SceneSwapchain>()

  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(
        VRFeature(this),
        SpatialAvatarHandVisualFeature(::marker),
        ComposeFeature(),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    loadNativeReceiptLibrary()
    if (shouldResetExperimentForPanelFirstLaunch(intent)) {
      store.resetForNewParticipant()
      marker(
          "channel=experiment-panel status=panel-first-launch-reset " +
              "freshSpatialActivityLaunch=true initialStage=participant " +
              "validationIntent=false panelFirstExperimentFlow=true"
      )
    }
    marker(
        "channel=activity status=created package=io.github.mesmerprism.rustyquest.kuramoto_spatial " +
            "highRateJsonPayload=false hand_rendering_expected=true nativeSurfaceParticleLayerExpected=true"
    )
    scheduleParticleLayerLifecycleDiagnostics("activity-created")
    runValidationWorkflowIfRequested(intent)
    runPolarLiveValidationIfRequested(intent)
    runUiCommandIfRequested(intent)
    runSurfaceTargetActivationIfRequested(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    runValidationWorkflowIfRequested(intent)
    runPolarLiveValidationIfRequested(intent)
    runUiCommandIfRequested(intent)
    runSurfaceTargetActivationIfRequested(intent)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (handleControllerPrimaryButton(event)) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    panelEntity =
        Entity.createPanelEntity(
            R.id.kuramoto_experiment_panel,
            Transform(panelPose()),
            panelDimensions(),
            Visible(panelPlacement.visible),
        )
    panelLauncherEntity =
        Entity.createPanelEntity(
            R.id.kuramoto_panel_launcher,
            Transform(panelLauncherPose()),
            panelLauncherDimensions(),
            Visible(!panelPlacement.visible),
        )
    particleLayerEntity =
        runCatching {
              Entity.createPanelEntity(
                  R.id.kuramoto_particle_surface_panel,
                  Transform(particleLayerPose()),
                  particleLayerSurfacePanelDimensions(),
                  Visible(true),
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
        "channel=spatial-panel status=spawned panelRegistrationId=kuramoto_experiment_panel " +
            "launcherPanelRegistrationId=kuramoto_panel_launcher " +
            "panelY=${panelPlacement.yMeters} panelZ=${panelPlacement.zMeters} panelScale=${panelPlacement.scale} " +
            "panelWidth=${panelPlacement.widthMeters} panelHeight=${panelPlacement.heightMeters} " +
            "workflowPanelVisible=${panelPlacement.visible} launcherPanelVisible=${!panelPlacement.visible} " +
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
            "renderPolicy=native-vulkan-wsi-surface-panel panelRegistrationId=kuramoto_particle_surface_panel " +
            particleLayerPlacementMarkerFields() + " " +
            particleLayerStereoMarkerFields()
    )
    scheduleParticleLayerLifecycleDiagnostics("scene-ready")
  }

  override fun onVRReady() {
    super.onVRReady()
    updateWorkflowPanelHeadlockFromViewer(reason = "vr-ready", forceLog = true)
    updateParticleLayerProjectionFromViewer(reason = "vr-ready", forceLog = true)
    logNativeInteropProbe(phase = "vr-ready", probeSurface = true)
    runExternalSwapchainProbeIfRequested("vr-ready")
  }

  override fun onSceneTick() {
    super.onSceneTick()
    updateWorkflowPanelHeadlockFromViewer(reason = "scene-tick", forceLog = false)
    updateParticleLayerProjectionFromViewer(reason = "scene-tick", forceLog = false)
    pollSpatialControllerPrimaryButton()
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (handleControllerPrimaryButton(event)) {
      return true
    }
    if (applyPanelHeadlockJoystickInput(event)) {
      return true
    }
    return super.dispatchGenericMotionEvent(event)
  }

  override fun onDestroy() {
    cleanupExternalSwapchainProbe("activity-destroy")
    polarSensorPanel?.stop()
    polarSensorPanel = null
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
            R.id.kuramoto_experiment_panel,
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
                    KuramotoExperimentPanel(
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
                        applyExperimentBlock = { block, source ->
                          applyExperimentBlockToParticleControls(block, source)
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
            R.id.kuramoto_panel_launcher,
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
                    KuramotoPanelLauncher {
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
            R.id.kuramoto_particle_surface_panel,
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
            "workflowPanelRegistrationId=kuramoto_experiment_panel " +
            "launcherPanelRegistrationId=kuramoto_panel_launcher " +
            "particlePanelRegistrationId=kuramoto_particle_surface_panel"
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
            "streamMirror=kuramoto-experiment-store"
    )
    return created
  }

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
            "nativeFrameLoop=false morphovisionStack=false camera2Stack=false privateShaderStack=false"
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

  private fun applyExperimentBlockToParticleControls(
      block: ActiveBlockSnapshot,
      source: String,
  ): SurfaceParticleControlState {
    val energyDriver = if (block.movementBaseFrequencyHz > 0.5) 0.85f else 0.25f
    val coherenceDriver = if (block.movementCoupling > 0.5) 0.85f else 0.15f
    val updated =
        updateSurfaceParticleControls(
            energyDriver,
            coherenceDriver,
            particleControls.pointScale,
            source = source,
        )
    marker(
        "channel=experiment-panel status=experiment-condition-parameter-handoff " +
            "rendererAuthority=native-vulkan-wsi-surface-panel transport=jni-live-queue " +
            "panelMustNotBeAuthority=true highRatePayloadsAllowed=false " +
            "source=${markerToken(source)} conditionId=${markerToken(block.conditionId)} " +
            "profileId=${markerToken(block.profileId)} " +
            "workflowPanelVisibleAtHandoff=${panelPlacement.visible} " +
            "panelClosedBeforeHandoff=${!panelPlacement.visible} " +
            "movementBaseFrequencyHz=${String.format(Locale.US, "%.3f", block.movementBaseFrequencyHz)} " +
            "movementCoupling=${String.format(Locale.US, "%.3f", block.movementCoupling)} " +
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

  private fun stopNativeSurfaceParticleLayer() {
    if (nativeReceiptLibraryLoaded) {
      runCatching { nativeStopSurfaceParticleLayer() }
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
    panelPlacement =
        panelPlacement.copy(
            visible = true,
            xMeters = PANEL_HEADLOCK_OFFSET_X_METERS,
            yMeters =
                if (panelPlacement.headlocked) PANEL_HEADLOCK_OFFSET_Y_METERS else PANEL_FOCUS_Y_METERS,
            zMeters =
                if (panelPlacement.headlocked) PANEL_HEADLOCK_DISTANCE_METERS else PANEL_FOCUS_Z_METERS,
            scale = if (panelPlacement.headlocked) PANEL_HEADLOCK_SCALE else 1.0f,
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
    panelPlacement =
        if (visible && focus) {
          if (panelPlacement.headlocked) {
            panelPlacement.copy(
                visible = true,
                xMeters = PANEL_HEADLOCK_OFFSET_X_METERS,
                yMeters = PANEL_HEADLOCK_OFFSET_Y_METERS,
                zMeters = PANEL_HEADLOCK_DISTANCE_METERS,
                scale = PANEL_HEADLOCK_SCALE,
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
            "launcherPanelVisible=${!panelPlacement.visible} " +
            "particleLayerVisible=${particleLayerVisibleForPanelMode()} " +
            "particleLayerRenderContinuity=kept-running rendererAuthority=native-vulkan-wsi-surface-panel " +
            "uiAuthority=spatial-sdk-compose-panel " +
            panelHeadlockMarkerFields()
    )
    return panelPlacement
  }

  private fun applyPanelPlacement() {
    val entity = panelEntity ?: return
    entity.setComponent(Transform(panelPose()))
    entity.setComponent(Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale)))
    entity.setComponent(panelDimensions())
    entity.setComponent(Visible(panelPlacement.visible))
    panelLauncherEntity?.setComponent(Transform(panelLauncherPose()))
    panelLauncherEntity?.setComponent(panelLauncherDimensions())
    panelLauncherEntity?.setComponent(Visible(!panelPlacement.visible))
    particleLayerEntity?.setComponent(Visible(particleLayerVisibleForPanelMode()))
  }

  private fun particleLayerVisibleForPanelMode(): Boolean = !panelPlacement.visible

  private fun panelPose(): Pose =
      if (panelPlacement.headlocked) {
        headlockedPanelPoseFromViewer() ?: worldPanelPose()
      } else {
        worldPanelPose()
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

  private fun updateWorkflowPanelHeadlockFromViewer(reason: String, forceLog: Boolean) {
    pollPanelHeadlockHotload(reason)
    val entity = panelEntity ?: return
    if (!panelPlacement.headlocked) {
      return
    }
    val pose =
        headlockedPanelPoseFromViewer()
            ?: run {
              if (forceLog) {
                marker(
                    "channel=spatial-panel status=headlocked-pose-update-skipped " +
                        "reason=${markerToken(reason)} headlockedPanelEnabled=true " +
                        "viewerPoseSource=Scene.getViewerPose error=unavailable"
                )
              }
              return
            }
    entity.setComponent(Transform(pose))
    entity.setComponent(Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale)))
    entity.setComponent(panelDimensions())
    entity.setComponent(Visible(panelPlacement.visible))

    val now = SystemClock.elapsedRealtime()
    val shouldLog =
        forceLog ||
            (panelPlacement.visible &&
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
            "panelPoseSource=headlocked-viewer-relative " +
            panelHeadlockMarkerFields() + " " +
            "panelPositionM=${vectorMarker(pose.t)} panelQuaternion=${quaternionMarker(pose.q)}"
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
          val row =
              JSONObject()
                  .put("schema_id", "rusty.quest.kuramoto_spatial.panel_headlock_tuning.v1")
                  .put("source", source)
                  .put("updated_at_unix_ms", System.currentTimeMillis())
                  .put("headlocked", panelPlacement.headlocked)
                  .put("offset_x_m", panelPlacement.xMeters.toDouble())
                  .put("offset_y_m", panelPlacement.yMeters.toDouble())
                  .put("distance_m", panelPlacement.zMeters.toDouble())
                  .put("scale", panelPlacement.scale.toDouble())
                  .put("width_m", panelPlacement.widthMeters.toDouble())
                  .put("height_m", panelPlacement.heightMeters.toDouble())
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

  private fun panelHeadlockMarkerFields(): String =
      "headlockedPanelEnabled=${panelPlacement.headlocked} " +
          "headlockedPanelDefaultEnabled=true " +
          "headlockedPanelOffsetXMeters=${markerFloat(panelPlacement.xMeters)} " +
          "headlockedPanelOffsetYMeters=${markerFloat(panelPlacement.yMeters)} " +
          "headlockedPanelDistanceMeters=${markerFloat(panelPlacement.zMeters)} " +
          "panelScale=${markerFloat(panelPlacement.scale)} " +
          "panelWidth=${markerFloat(panelPlacement.widthMeters)} " +
          "panelHeight=${markerFloat(panelPlacement.heightMeters)}"

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

  private fun applyPanelHeadlockJoystickInput(event: MotionEvent): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE || !isJoystickEvent(event)) {
      return false
    }
    if (!panelPlacement.visible || !panelPlacement.headlocked || !currentPanelHeadlockJoystickEnabled()) {
      return false
    }

    val leftX = joystickAxis(event, MotionEvent.AXIS_X)
    val leftY = joystickAxis(event, MotionEvent.AXIS_Y)
    val rightX = joystickAxis(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z)
    val rightY = joystickAxis(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ)
    if (
        abs(leftX) < PANEL_HEADLOCK_JOYSTICK_DEADZONE &&
            abs(leftY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE &&
            abs(rightX) < PANEL_HEADLOCK_JOYSTICK_DEADZONE &&
            abs(rightY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE
    ) {
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
    val translateRate =
        readFloatSystemProperty(
            PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_PROPERTY,
            PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_METERS_PER_SECOND,
            0.02f,
            0.80f,
        )
    val distanceRate =
        readFloatSystemProperty(
            PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY,
            PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND,
            0.02f,
            0.80f,
        )
    val scaleRate =
        readFloatSystemProperty(
            PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PROPERTY,
            PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PER_SECOND,
            0.02f,
            1.25f,
        )
    panelPlacement =
        panelPlacement.copy(
            xMeters =
                (panelPlacement.xMeters + leftX * translateRate * dtSeconds)
                    .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
            yMeters =
                (panelPlacement.yMeters - leftY * translateRate * dtSeconds)
                    .coerceIn(PANEL_HEADLOCK_OFFSET_Y_MIN_METERS, PANEL_HEADLOCK_OFFSET_Y_MAX_METERS),
            zMeters =
                (panelPlacement.zMeters - rightY * distanceRate * dtSeconds)
                    .coerceIn(PANEL_HEADLOCK_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS),
            scale =
                (panelPlacement.scale + rightX * scaleRate * dtSeconds)
                    .coerceIn(PANEL_HEADLOCK_SCALE_MIN, PANEL_HEADLOCK_SCALE_MAX),
        )
    applyPanelPlacement()
    persistPanelHeadlockTuning("controller-joystick")
    if (now - lastPanelHeadlockJoystickMarkerMs >= PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS) {
      lastPanelHeadlockJoystickMarkerMs = now
      marker(
          "channel=spatial-panel status=headlock-joystick-adjusted " +
              "inputSource=android-generic-motion-joystick " +
              "controllerJoystickMapping=left-stick-x-y-offset-right-stick-y-distance-right-stick-x-scale " +
              "leftStick=${markerFloat(leftX)};${markerFloat(leftY)} " +
              "rightStick=${markerFloat(rightX)};${markerFloat(rightY)} " +
              "dtSeconds=${markerFloat(dtSeconds)} " +
              "translateRateMps=${markerFloat(translateRate)} " +
              "distanceRateMps=${markerFloat(distanceRate)} " +
              "scaleRatePerSecond=${markerFloat(scaleRate)} " +
              panelHeadlockMarkerFields()
      )
    }
    return true
  }

  private fun pollSpatialControllerPrimaryButton() {
    val now = SystemClock.elapsedRealtime()
    val snapshot =
        runCatching {
              val buttonABit = ButtonBits.ButtonA
              var componentCount = 0
              var activeCount = 0
              var down = false
              var pressed = false
              var changedButtons = 0
              var buttonState = 0
              Query.where { has(Controller.id) }
                  .eval(scene.spatialInterface.dataModel)
                  .forEach { entity ->
                    val controller = entity.getComponent<Controller>()
                    componentCount += 1
                    val activeController =
                        controller.isActive && controller.type == ControllerType.CONTROLLER
                    if (activeController) {
                      activeCount += 1
                      buttonState = buttonState or controller.buttonState
                      changedButtons = changedButtons or controller.changedButtons
                      down = down || controller.isDown(buttonABit)
                      pressed = pressed || controller.isPressed(buttonABit)
                    }
                  }
              SpatialControllerPrimarySnapshot(
                  componentCount = componentCount,
                  activeCount = activeCount,
                  buttonState = buttonState,
                  changedButtons = changedButtons,
                  down = down,
                  pressed = pressed,
              )
            }
            .getOrElse { throwable ->
              spatialControllerPrimaryDown = false
              if (
                  !spatialControllerRouteLogged ||
                      now - lastSpatialControllerRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
              ) {
                spatialControllerRouteLogged = true
                lastSpatialControllerRouteMarkerMs = now
                marker(
                    "channel=spatial-panel status=controller-input-route-error " +
                        "inputSource=spatial-sdk-controller-component " +
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
            now - lastSpatialControllerRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
    if (shouldLogRoute) {
      spatialControllerRouteLogged = true
      lastSpatialControllerRouteMarkerMs = now
      lastSpatialControllerComponentCount = snapshot.componentCount
      lastSpatialControllerActiveCount = snapshot.activeCount
      marker(
          "channel=spatial-panel status=controller-input-route-ready " +
              "inputSource=spatial-sdk-controller-component controllerInput=right-primary-button " +
              "controllerComponentCount=${snapshot.componentCount} " +
              "activeControllerComponentCount=${snapshot.activeCount} " +
              "buttonABit=${ButtonBits.ButtonA} buttonADown=${snapshot.down} " +
              "buttonState=${snapshot.buttonState} changedButtons=${snapshot.changedButtons} " +
              "debugOnly=true"
      )
    }

    val pressedEdge = snapshot.pressed || (snapshot.down && !spatialControllerPrimaryDown)
    spatialControllerPrimaryDown = snapshot.down
    if (!pressedEdge || panelPlacement.visible) {
      return
    }
    openWorkflowPanelFromController(
        inputSource = "spatial-sdk-controller-component",
        detail =
            "buttonABit=${ButtonBits.ButtonA} buttonState=${snapshot.buttonState} " +
                "changedButtons=${snapshot.changedButtons} " +
                "controllerComponentCount=${snapshot.componentCount} " +
                "activeControllerComponentCount=${snapshot.activeCount}",
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
    if (!pressedEdge || panelPlacement.visible) {
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
    if (!pressedEdge || panelPlacement.visible) {
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
    if (panelPlacement.visible) {
      return false
    }
    val rightPrimary =
        inputSource == "spatial-sdk-controller-component" ||
            inputSource == "android-key-event" ||
            inputSource == "android-generic-motion-button"
    if (!rightPrimary) return false
    setWorkflowPanelVisible(true, focus = true, source = "right-controller-primary-button")
    marker(
        "channel=spatial-panel status=controller-primary-opened-panel " +
            "controllerInput=right-primary-button inputSource=${markerToken(inputSource)} " +
            "${detail.trim()} " +
            "panelMode=${panelStateToken()} workflowPanelVisible=${panelPlacement.visible} " +
            "debugOnly=true"
    )
    return true
  }

  private fun isJoystickEvent(event: MotionEvent): Boolean =
      event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)

  private fun currentPanelHeadlockJoystickEnabled(): Boolean =
      readOptionalBooleanSystemProperty(PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY) ?: true

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

  private fun cross(left: Vector3, right: Vector3): Vector3 =
      Vector3(
          left.y * right.z - left.z * right.y,
          left.z * right.x - left.x * right.z,
          left.x * right.y - left.y * right.x,
      )

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

  private fun panelDimensions(): PanelDimensions =
      PanelDimensions(Vector2(panelPlacement.widthMeters, panelPlacement.heightMeters))

  private fun panelLauncherDimensions(): PanelDimensions =
      PanelDimensions(Vector2(PANEL_LAUNCHER_WIDTH_METERS, PANEL_LAUNCHER_HEIGHT_METERS))

  private fun panelStateToken(): String =
      if (panelPlacement.visible) "spatial-sdk-workflow-panel-open" else "spatial-sdk-particle-view-panel-closed"

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
      store.prioritizeConditionForValidation(VALIDATION_STUDY_CONDITION_ID)
      setWorkflowPanelVisible(false, focus = false, source = "self-test-particle-view")
      val block = store.startNextBlock()
      if (block != null) {
        applyExperimentBlockToParticleControls(block, "self-test-experiment-block-start")
      }
      Handler(Looper.getMainLooper())
          .postDelayed(
              { setWorkflowPanelVisible(true, focus = true, source = "self-test-workflow-panel") },
              1500L,
          )
      marker(
          "channel=validation status=self-test-block-started participantId=${markerToken(participantId)} " +
              "surfaceTargetId=${markerToken(surfaceTargetId)} validationStudyConditionId=$VALIDATION_STUDY_CONDITION_ID"
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
                          "surfaceTargetId=${markerToken(surfaceTargetId)} validationStudyConditionId=$VALIDATION_STUDY_CONDITION_ID"
                  )
                } catch (throwable: Throwable) {
                  marker("channel=validation status=self-test-failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
                  Log.e(TAG, "Kuramoto Spatial SDK validation workflow failed", throwable)
                }
              },
              KuramotoExperimentStore.DEFAULT_BLOCK_DURATION_MS + 750L,
          )
    } catch (throwable: Throwable) {
      marker("channel=validation status=self-test-failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
      Log.e(TAG, "Kuramoto Spatial SDK validation workflow failed", throwable)
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
      Log.e(TAG, "Kuramoto Spatial SDK UI command failed", throwable)
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
              "validationStudyConditionId=$VALIDATION_STUDY_CONDITION_ID panelMode=${panelStateToken()} " +
              "leftInParticleView=true"
      )
    } catch (throwable: Throwable) {
      marker(
          "channel=validation status=surface-target-activation-failed " +
              "surfaceTargetId=${markerToken(surfaceTargetId)} error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}"
      )
      Log.e(TAG, "Kuramoto Spatial SDK surface target activation failed", throwable)
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
    store.prioritizeConditionForValidation(VALIDATION_STUDY_CONDITION_ID)
    setWorkflowPanelVisible(false, focus = false, source = "$source-particle-view")
    val block = store.startNextBlock()
    if (block != null) {
      applyExperimentBlockToParticleControls(block, "$source-block-start")
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
      Log.e(TAG, "Kuramoto Spatial SDK Polar live validation failed", throwable)
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
            "launcherPanelVisible=${!panelPlacement.visible} " +
            "particleLayerEntityCreated=${particleLayerEntity != null} particleSurfacePanelReady=$particleSurfacePanelReady " +
            "particleSurfaceConsumerCalled=$particleSurfaceConsumerCalled " +
            "particleSurfaceConsumerSurfaceValid=$particleSurfaceConsumerSurfaceValid " +
            "particleLayerStarted=$particleLayerStarted nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
            "lastNativeSurfaceStartMask=$lastNativeSurfaceStartMask " +
            "nativeReceiptLibraryLoaded=$nativeReceiptLibraryLoaded nativeReceiptLibraryError=${markerToken(nativeReceiptLibraryError)} " +
            "openXrInstanceHandleNonZero=${probe.openXrInstanceHandleNonZero} " +
            "openXrSessionHandleNonZero=${probe.openXrSessionHandleNonZero} " +
            "openXrGetInstanceProcAddrHandleNonZero=${probe.openXrGetInstanceProcAddrHandleNonZero} " +
            "currentConditionId=${markerToken(snapshot?.currentConditionId ?: "none")} " +
            "currentProfileId=${markerToken(snapshot?.currentProfileId ?: "none")} " +
            particleLayerPlacementMarkerFields() + " " +
            particleLayerStereoMarkerFields()
    )
  }

  companion object {
    private const val TAG = "RQKuramotoSpatial"
    private const val MARKER_PREFIX = "RUSTY_QUEST_KURAMOTO_SPATIAL"
    private const val ACTIVITY_MARKERS_FILE = "kuramoto_spatial_activity_markers.log"
    private const val VALIDATION_STUDY_CONDITION_ID = "lche"
    private const val ACTION_RUN_WORKFLOW_SELF_TEST =
        "io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_WORKFLOW_SELF_TEST"
    private const val ACTION_RUN_POLAR_LIVE_VALIDATION =
        "io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_POLAR_LIVE_VALIDATION"
    private const val ACTION_RUN_UI_COMMAND =
        "io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_UI_COMMAND"
    private const val ACTION_RUN_SURFACE_TARGET =
        "io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_SURFACE_TARGET"
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
    private const val PANEL_HEADLOCK_TUNING_FILE = "kuramoto_spatial_panel_headlock_tuning.json"
    private const val PANEL_HEADLOCK_OFFSET_X_METERS = 0.0f
    private const val PANEL_HEADLOCK_OFFSET_Y_METERS = 0.0f
    private const val PANEL_HEADLOCK_DISTANCE_METERS = 1.40f
    private const val PANEL_HEADLOCK_SCALE = 0.65f
    private const val PANEL_HEADLOCK_OFFSET_X_MIN_METERS = -0.85f
    private const val PANEL_HEADLOCK_OFFSET_X_MAX_METERS = 0.85f
    private const val PANEL_HEADLOCK_OFFSET_Y_MIN_METERS = -0.65f
    private const val PANEL_HEADLOCK_OFFSET_Y_MAX_METERS = 0.65f
    private const val PANEL_HEADLOCK_DISTANCE_MIN_METERS = 0.35f
    private const val PANEL_HEADLOCK_DISTANCE_MAX_METERS = 1.50f
    private const val PANEL_HEADLOCK_SCALE_MIN = 0.65f
    private const val PANEL_HEADLOCK_SCALE_MAX = 1.60f
    private const val PANEL_HEADLOCK_ENABLED_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.enabled"
    private const val PANEL_HEADLOCK_OFFSET_X_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.offset_x_m"
    private const val PANEL_HEADLOCK_OFFSET_Y_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.offset_y_m"
    private const val PANEL_HEADLOCK_DISTANCE_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.distance_meters"
    private const val PANEL_HEADLOCK_WIDTH_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.width_meters"
    private const val PANEL_HEADLOCK_HEIGHT_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.height_meters"
    private const val PANEL_HEADLOCK_SCALE_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.scale"
    private const val PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.enabled"
    private const val PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.translate_rate_mps"
    private const val PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.distance_rate_mps"
    private const val PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.panel.headlocked.joystick.scale_rate_per_second"
    private const val PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_METERS_PER_SECOND = 0.18f
    private const val PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND = 0.16f
    private const val PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PER_SECOND = 0.30f
    private const val PANEL_HEADLOCK_JOYSTICK_DEADZONE = 0.14f
    private const val PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS = 450L
    private const val PANEL_HEADLOCK_MARKER_INTERVAL_MS = 900L
    private const val SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS = 2500L
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
        "debug.rustyquest.kuramoto_spatial.particle_layer.target_distance_meters"
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
        "debug.rustyquest.kuramoto_spatial.particle_layer.surface_overscan_scale"
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
    private const val OPENXR_ERROR_HANDLE_INVALID = -12
    private const val NATIVE_RECEIPT_LIBRARY = "kuramoto_spatial_native_receipt"
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

private class SpatialAvatarHandVisualFeature(
    private val marker: (String) -> Unit,
) : SpatialFeature {
  override fun earlySystemsToRegister(): List<SystemBase> =
      listOf(SpatialAvatarHandVisualSuppressionSystem(marker))
}

private class SpatialAvatarHandVisualSuppressionSystem(
    private val marker: (String) -> Unit,
) : SystemBase() {
  private var pendingLogged = false
  private var disabledLogged = false

  override fun execute() {
    val avatarSystem =
        runCatching { systemManager.tryFindSystem(AvatarSystem::class) }.getOrNull()
    if (avatarSystem == null) {
      if (!pendingLogged) {
        pendingLogged = true
        marker(
            "channel=spatial-sdk-avatar-visual status=disable-pending " +
                "system=AvatarSystem systemFound=false suppressionSystem=SpatialAvatarHandVisualSuppressionSystem " +
                "builtInMetaHandVisualPolicy=pending nativeBaseHandMeshPolicy=explicit-only"
        )
      }
      return
    }

    avatarSystem.setShowHands(false)
    if (!disabledLogged) {
      disabledLogged = true
      marker(
          "channel=spatial-sdk-avatar-visual status=disabled " +
              "system=AvatarSystem systemFound=true suppressionSystem=SpatialAvatarHandVisualSuppressionSystem " +
              "showHands=false builtInMetaHandVisualPolicy=disabled " +
              "nativeBaseHandMeshPolicy=explicit-only"
      )
    }
  }
}

private fun Long.hasReceiptBit(bit: Long): Boolean = (this and bit) != 0L

private fun PanelPlacement.headlockEquivalent(other: PanelPlacement): Boolean =
    visible == other.visible &&
        headlocked == other.headlocked &&
        abs(xMeters - other.xMeters) < 0.0005f &&
        abs(yMeters - other.yMeters) < 0.0005f &&
        abs(zMeters - other.zMeters) < 0.0005f &&
        abs(scale - other.scale) < 0.0005f &&
        abs(widthMeters - other.widthMeters) < 0.0005f &&
        abs(heightMeters - other.heightMeters) < 0.0005f

private class FixedMediaPanelDisplayOptions(
    private val widthPx: Int,
    private val heightPx: Int,
) : MediaPanelDisplayOptions {
  override fun applyTo(config: PanelConfigOptions) {
    config.layoutWidthInPx = widthPx
    config.layoutHeightInPx = heightPx
    config.layoutDpi = PanelConfigOptions.DEFAULT_DPI
  }
}

data class PanelPlacement(
    val visible: Boolean = true,
    val headlocked: Boolean = true,
    val xMeters: Float = 0.0f,
    val yMeters: Float = 0.0f,
    val zMeters: Float = 1.40f,
    val scale: Float = 0.65f,
    val widthMeters: Float = 1.20f,
    val heightMeters: Float = 1.254f,
)

private data class SpatialControllerPrimarySnapshot(
    val componentCount: Int,
    val activeCount: Int,
    val buttonState: Int,
    val changedButtons: Int,
    val down: Boolean,
    val pressed: Boolean,
)

data class SurfaceParticleControlState(
    val driver0Value01: Float = 1.0f,
    val driver1Value01: Float = 0.0f,
    val pointScale: Float = 1.0f,
)

data class SpatialNativeInteropProbe(
    val runtimeName: String,
    val openXrInstanceHandle: Long,
    val openXrSessionHandle: Long,
    val openXrGetInstanceProcAddrHandle: Long,
    val renderPolicy: String = "no-render",
) {
  val openXrInstanceHandleNonZero: Boolean
    get() = openXrInstanceHandle != 0L
  val openXrSessionHandleNonZero: Boolean
    get() = openXrSessionHandle != 0L
  val openXrGetInstanceProcAddrHandleNonZero: Boolean
    get() = openXrGetInstanceProcAddrHandle != 0L

  companion object {
    @OptIn(SpatialSDKExperimentalAPI::class)
    fun capture(scene: Scene): SpatialNativeInteropProbe =
        SpatialNativeInteropProbe(
            runtimeName = runCatching { scene.getRuntimeName().name }.getOrElse { "unavailable" },
            openXrInstanceHandle = runCatching { scene.getOpenXrInstanceHandle() }.getOrDefault(0L),
            openXrSessionHandle = runCatching { scene.getOpenXrSessionHandle() }.getOrDefault(0L),
            openXrGetInstanceProcAddrHandle =
                runCatching { scene.getOpenXrGetInstanceProcAddrHandle() }.getOrDefault(0L),
        )
  }
}

data class NativeInteropSurfaceProbeResult(
    val capability: String,
    val status: String,
    val surfaceValid: Boolean,
    val error: String,
)

data class NativeInteropReceiptResult(
    val status: String,
    val mask: Long,
    val openXrInstanceHandleNonZero: Boolean,
    val openXrSessionHandleNonZero: Boolean,
    val openXrGetInstanceProcAddrHandleNonZero: Boolean,
    val openXrGetInstanceProcAddrCallable: Boolean,
    val xrGetInstancePropertiesResolved: Boolean,
    val xrGetInstancePropertiesSucceeded: Boolean,
    val xrGetSystemResolved: Boolean,
    val xrGetSystemSucceeded: Boolean,
    val xrVulkanGraphicsRequirements2Resolved: Boolean,
    val xrVulkanGraphicsRequirements2Succeeded: Boolean,
    val xrCreateVulkanInstanceResolved: Boolean,
    val xrGetVulkanGraphicsDevice2Resolved: Boolean,
    val xrCreateVulkanDeviceResolved: Boolean,
    val vkInstanceCreated: Boolean,
    val vkGraphicsDeviceObtained: Boolean,
    val vkGraphicsComputeQueueFound: Boolean,
    val vkDeviceCreated: Boolean,
    val vkQueueObtained: Boolean,
    val vkObjectsDestroyed: Boolean,
    val surfaceValid: Boolean,
    val error: String,
)

private val PanelProbeBackground = Color(0xFFFFF3B0)
private val PanelProbeHeader = Color(0xFF0F5F6F)
private val PanelProbeButton = Color(0xFFFF5A1F)
private val PanelProbeInk = Color(0xFF111827)
private val PanelProbeBorder = Color(0xFF0B1720)
private const val PANEL_RESIZE_STEP_METERS = 0.12f

@Composable
private fun KuramotoExperimentPanel(
    store: KuramotoExperimentStore,
    placement: PanelPlacement,
    particleControls: SurfaceParticleControlState,
    polarPanel: PolarSensorPanel,
    setWorkflowPanelVisible: (Boolean, Boolean, String) -> PanelPlacement,
    adjustPlacement: (Float, Float, Float, Float) -> PanelPlacement,
    setPanelHeadlocked: (Boolean, String) -> PanelPlacement,
    resizePanel: (Float, Float) -> PanelPlacement,
    resetPlacement: () -> PanelPlacement,
    updateParticleControls: (Float, Float, Float) -> SurfaceParticleControlState,
    applyExperimentBlock: (ActiveBlockSnapshot, String) -> SurfaceParticleControlState,
) {
  var snapshot by remember { mutableStateOf(store.snapshot()) }
  var localPlacement by remember { mutableStateOf(placement) }
  var localParticleControls by remember { mutableStateOf(particleControls) }

  fun refreshSnapshot(source: String) {
    val updated = store.snapshot()
    if (updated.stage == "questionnaire") {
      localPlacement = setWorkflowPanelVisible(true, true, source)
    }
    snapshot = updated
  }

  fun startBlockFromPanel(surfaceId: String?, source: String) {
    if (surfaceId != null) {
      store.selectSurface(surfaceId)
    }
    localPlacement = setWorkflowPanelVisible(false, false, source)
    val block = store.startNextBlock()
    if (block == null) {
      localPlacement = setWorkflowPanelVisible(true, true, "$source-complete")
      refreshSnapshot("$source-complete")
      return
    }
    localParticleControls = applyExperimentBlock(block, source)
    snapshot = store.snapshot()
  }

  LaunchedEffect(snapshot.stage, snapshot.activeBlock?.deadlineUnixMs) {
    while (snapshot.stage == "block_running") {
      delay(500L)
      store.syncElapsedBlock()
      refreshSnapshot("experiment-block-elapsed-questionnaire")
    }
  }

  Surface(
      modifier = Modifier.fillMaxSize(),
      color = PanelProbeBackground,
      contentColor = PanelProbeInk,
  ) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PanelProbeBackground)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      PanelColorProbe(snapshot)
      Header(snapshot)
      PanelModeControls(
          placement = localPlacement,
          setWorkflowPanelVisible = setWorkflowPanelVisible,
          setPanelHeadlocked = setPanelHeadlocked,
          resetPlacement = resetPlacement,
          onPlacementChanged = { localPlacement = it },
      )
      PanelPlacementControls(
          placement = localPlacement,
          onAdjust = { dx, dy, dz, ds -> localPlacement = adjustPlacement(dx, dy, dz, ds) },
          onResize = { dw, dh -> localPlacement = resizePanel(dw, dh) },
      )
      SurfaceParticleControls(localParticleControls) { driver0, driver1, pointScale ->
        localParticleControls = updateParticleControls(driver0, driver1, pointScale)
      }
      HorizontalDivider()
      when (snapshot.stage) {
        "participant" ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  refreshSnapshot("experiment-participant-created")
                },
                onReset = {
                  store.resetForNewParticipant()
                  refreshSnapshot("experiment-reset")
                },
            )
        "polar_setup" ->
            PolarSetupStep(
                snapshot = snapshot,
                polarPanel = polarPanel,
                onContinue = { runLabel, operatorId, notes ->
                  store.savePolarSetup(runLabel, operatorId, notes)
                  refreshSnapshot("experiment-polar-setup-recorded")
                },
            )
        "surface_setup" ->
            SurfaceStep(
                snapshot = snapshot,
                onStart = { surfaceId -> startBlockFromPanel(surfaceId, "experiment-block-start") },
            )
        "block_running" ->
            RunningStep(snapshot) {
              store.syncElapsedBlock()
              refreshSnapshot("experiment-block-running-refresh")
            }
        "questionnaire" ->
            QuestionnaireStep(snapshot) { comfort, intensity, engagement, notes, signature ->
              store.submitQuestionnaire(comfort, intensity, engagement, notes, signature)
              refreshSnapshot("experiment-questionnaire-submitted")
            }
        "ready_next_block" ->
            ReadyNextStep(snapshot) { startBlockFromPanel(null, "experiment-ready-next-block") }
        "complete" ->
            CompleteStep(snapshot) {
              store.resetForNewParticipant()
              refreshSnapshot("experiment-reset")
            }
        else ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  refreshSnapshot("experiment-participant-created")
                },
                onReset = {
                  store.resetForNewParticipant()
                  refreshSnapshot("experiment-reset")
                },
            )
      }
    }
  }
}

@Composable
private fun KuramotoPanelLauncher(openPanel: () -> Unit) {
  Surface(
      modifier = Modifier.fillMaxSize(),
      color = PanelProbeHeader,
      contentColor = Color.White,
  ) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Particles running", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
      Button(
          onClick = openPanel,
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = PanelProbeButton,
                  contentColor = Color.White,
              ),
      ) {
        Text("Open Panel")
      }
    }
  }
}

@Composable
private fun SurfaceParticleControls(
    controls: SurfaceParticleControlState,
    onChange: (Float, Float, Float) -> Unit,
) {
  var driver0 by remember { mutableStateOf(controls.driver0Value01) }
  var driver1 by remember { mutableStateOf(controls.driver1Value01) }
  var pointScale by remember { mutableStateOf(controls.pointScale) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Native particle compute", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    NativeParticleSlider("Energy", driver0, 0.0f, 1.0f) {
      driver0 = it
      onChange(driver0, driver1, pointScale)
    }
    NativeParticleSlider("Coherence", driver1, 0.0f, 1.0f) {
      driver1 = it
      onChange(driver0, driver1, pointScale)
    }
    NativeParticleSlider("Point scale", pointScale, 0.35f, 2.25f) {
      pointScale = it
      onChange(driver0, driver1, pointScale)
    }
  }
}

@Composable
private fun NativeParticleSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = { onChange(it.coerceIn(min, max)) },
        valueRange = min..max,
    )
  }
}

@Composable
private fun PanelColorProbe(snapshot: ExperimentSnapshot) {
  Surface(
      modifier =
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .border(3.dp, PanelProbeBorder, RoundedCornerShape(10.dp)),
      color = PanelProbeHeader,
      contentColor = Color.White,
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
          modifier = Modifier.weight(1.0f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
            "PANEL COLOR PROBE",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Non-black Compose content is active. Stage: ${snapshot.stage}",
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      Spacer(Modifier.width(16.dp))
      Button(
          onClick = {},
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = PanelProbeButton,
                  contentColor = Color.White,
              ),
      ) {
        Text("Visible Button")
      }
    }
  }
}

@Composable
private fun Header(snapshot: ExperimentSnapshot) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Kuramoto Experiment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Stage: ${snapshot.stage}", style = MaterialTheme.typography.bodyMedium)
    if (snapshot.sessionId.isNotBlank()) {
      Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
      Text("Session: ${snapshot.sessionId}", style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun PanelModeControls(
    placement: PanelPlacement,
    setWorkflowPanelVisible: (Boolean, Boolean, String) -> PanelPlacement,
    setPanelHeadlocked: (Boolean, String) -> PanelPlacement,
    resetPlacement: () -> PanelPlacement,
    onPlacementChanged: (PanelPlacement) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        "Panel mode: ${if (placement.visible) "workflow panel" else "particle view"}; ${if (placement.headlocked) "headlocked" else "world-locked"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onPlacementChanged(setWorkflowPanelVisible(true, true, "panel-view-button")) }) {
        Text("Panel View")
      }
      Button(onClick = { onPlacementChanged(setWorkflowPanelVisible(false, false, "particle-view-button")) }) {
        Text("Particle View")
      }
      Button(onClick = { onPlacementChanged(resetPlacement()) }) {
        Text("Reset Panel")
      }
      Button(
          onClick = {
            onPlacementChanged(
                setPanelHeadlocked(!placement.headlocked, "panel-headlock-toggle")
            )
          }
      ) {
        Text(if (placement.headlocked) "World Lock" else "Head Lock")
      }
    }
  }
}

@Composable
private fun PanelPlacementControls(
    placement: PanelPlacement,
    onAdjust: (Float, Float, Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
) {
  val nearDelta = if (placement.headlocked) -0.08f else 0.12f
  val farDelta = if (placement.headlocked) 0.08f else -0.12f
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (placement.headlocked) {
      Text(
          "Headlocked pose x=${"%.2f".format(placement.xMeters)}m y=${"%.2f".format(placement.yMeters)}m distance=${"%.2f".format(placement.zMeters)}m scale=${"%.2f".format(placement.scale)} size=${"%.2f".format(placement.widthMeters)}m x ${"%.2f".format(placement.heightMeters)}m",
          style = MaterialTheme.typography.bodySmall,
      )
    } else {
      Text(
          "World pose x=${"%.2f".format(placement.xMeters)}m y=${"%.2f".format(placement.yMeters)}m z=${"%.2f".format(placement.zMeters)}m scale=${"%.2f".format(placement.scale)} size=${"%.2f".format(placement.widthMeters)}m x ${"%.2f".format(placement.heightMeters)}m",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onAdjust(0.0f, 0.0f, nearDelta, 0.0f) }) { Text("Near") }
      Button(onClick = { onAdjust(0.0f, 0.0f, farDelta, 0.0f) }) { Text("Far") }
      Button(onClick = { onAdjust(0.0f, 0.08f, 0.0f, 0.0f) }) { Text("Up") }
      Button(onClick = { onAdjust(0.0f, -0.08f, 0.0f, 0.0f) }) { Text("Down") }
      Button(onClick = { onAdjust(-0.08f, 0.0f, 0.0f, 0.0f) }) { Text("Left") }
      Button(onClick = { onAdjust(0.08f, 0.0f, 0.0f, 0.0f) }) { Text("Right") }
      Button(onClick = { onAdjust(0.0f, 0.0f, 0.0f, -0.08f) }) { Text("Scale -") }
      Button(onClick = { onAdjust(0.0f, 0.0f, 0.0f, 0.08f) }) { Text("Scale +") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onResize(-PANEL_RESIZE_STEP_METERS, 0.0f) }) { Text("Narrower") }
      Button(onClick = { onResize(PANEL_RESIZE_STEP_METERS, 0.0f) }) { Text("Wider") }
      Button(onClick = { onResize(0.0f, -PANEL_RESIZE_STEP_METERS) }) { Text("Shorter") }
      Button(onClick = { onResize(0.0f, PANEL_RESIZE_STEP_METERS) }) { Text("Taller") }
    }
  }
}

@Composable
private fun ParticipantStep(
    snapshot: ExperimentSnapshot,
    onBegin: (String) -> Unit,
    onReset: () -> Unit,
) {
  var participantId by remember { mutableStateOf(snapshot.participantId) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Participant Setup", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = participantId,
        onValueChange = { participantId = it },
        label = { Text("participant_id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Condition order: ${snapshot.orderSummary}", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(enabled = participantId.isNotBlank(), onClick = { onBegin(participantId) }) {
        Text("Start Setup")
      }
      Button(onClick = onReset) { Text("Reset Order") }
    }
  }
}

@Composable
private fun PolarSetupStep(
    snapshot: ExperimentSnapshot,
    polarPanel: PolarSensorPanel,
    onContinue: (String, String, String) -> Unit,
) {
  var runLabel by remember { mutableStateOf("") }
  var operatorId by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("") }
  var ecgStatus by remember { mutableStateOf(polarPanel.ecgExperimentStatusLine(snapshot.sessionId.isNotBlank())) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Polar Setup", style = MaterialTheme.typography.titleLarge)
    Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
    Text("ECG: $ecgStatus", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
          onClick = {
            polarPanel.handleCommand("start_ecg")
            ecgStatus = polarPanel.ecgExperimentStatusLine(snapshot.sessionId.isNotBlank())
          }
      ) {
        Text("Start ECG")
      }
      Button(onClick = { ecgStatus = polarPanel.ecgExperimentStatusLine(snapshot.sessionId.isNotBlank()) }) {
        Text("Refresh")
      }
    }
    AndroidView(
        factory = { polarPanel.buildView() },
        modifier = Modifier.fillMaxWidth().height(420.dp),
    )
    OutlinedTextField(
        value = runLabel,
        onValueChange = { runLabel = it },
        label = { Text("run_label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = operatorId,
        onValueChange = { operatorId = it },
        label = { Text("operator_id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("notes") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onContinue(runLabel, operatorId, notes) }) { Text("Continue") }
  }
}

@Composable
private fun SurfaceStep(
    snapshot: ExperimentSnapshot,
    onStart: (String) -> Unit,
) {
  var selectedSurface by remember { mutableStateOf(snapshot.surfaceTargetId.ifBlank { "real-hands" }) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Surface", style = MaterialTheme.typography.titleLarge)
    Text("Next condition: ${snapshot.currentConditionLabel}", style = MaterialTheme.typography.bodyMedium)
    KuramotoExperimentStore.SURFACES.forEach { surface ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = selectedSurface == surface.id,
            onClick = { selectedSurface = surface.id },
        )
        Spacer(Modifier.width(8.dp))
        Column {
          Text(surface.label)
          Text(surface.surfaceTarget, style = MaterialTheme.typography.bodySmall)
        }
      }
    }
    Button(onClick = { onStart(selectedSurface) }) { Text("Start Block") }
  }
}

@Composable
private fun RunningStep(snapshot: ExperimentSnapshot, refresh: () -> Unit) {
  val block = snapshot.activeBlock
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Block Running", style = MaterialTheme.typography.titleLarge)
    if (block != null) {
      Text("Block ${block.blockNumber}/${snapshot.conditionCount}: ${block.conditionLabel}")
      Text("Surface: ${block.surfaceLabel}")
      Text("Remaining: ${(block.remainingMs + 999L) / 1000L}s")
    }
    Button(onClick = refresh) { Text("Refresh") }
  }
}

@Composable
private fun ReadyNextStep(snapshot: ExperimentSnapshot, onStart: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Next Block", style = MaterialTheme.typography.titleLarge)
    Text("Next: ${snapshot.currentConditionLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Surface: ${snapshot.surfaceLabel}", style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onStart) { Text("Start Block") }
  }
}

@Composable
private fun QuestionnaireStep(
    snapshot: ExperimentSnapshot,
    onSubmit: (Int, Int, Int, String, JSONObject) -> Unit,
) {
  var comfort by remember { mutableStateOf(4) }
  var intensity by remember { mutableStateOf(4) }
  var engagement by remember { mutableStateOf(4) }
  var notes by remember { mutableStateOf("") }
  var signatureStrokes by remember { mutableStateOf<List<List<SignaturePoint>>>(emptyList()) }
  var signatureSize by remember { mutableStateOf(IntSize.Zero) }
  val block = snapshot.activeBlock
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Questionnaire", style = MaterialTheme.typography.titleLarge)
    if (block != null) {
      Text("Block ${block.blockNumber}: ${block.conditionLabel}", style = MaterialTheme.typography.bodyMedium)
      Text("Surface: ${block.surfaceLabel}", style = MaterialTheme.typography.bodySmall)
    }
    RatingSlider("Comfort", comfort) { comfort = it }
    RatingSlider("Intensity", intensity) { intensity = it }
    RatingSlider("Engagement", engagement) { engagement = it }
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("notes") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Signature", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    SignaturePad(
        strokes = signatureStrokes,
        onStrokesChange = { signatureStrokes = it },
        onSizeChange = { signatureSize = it },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(onClick = { signatureStrokes = emptyList() }) { Text("Clear signature") }
      Button(
          onClick = {
            onSubmit(
                comfort,
                intensity,
                engagement,
                notes,
                signatureJson(signatureStrokes, signatureSize.width, signatureSize.height),
            )
          }
      ) {
        Text("Submit")
      }
    }
  }
}

@Composable
private fun SignaturePad(
    strokes: List<List<SignaturePoint>>,
    onStrokesChange: (List<List<SignaturePoint>>) -> Unit,
    onSizeChange: (IntSize) -> Unit,
) {
  var activeStroke by remember { mutableStateOf<List<SignaturePoint>>(emptyList()) }
  var padSize by remember { mutableStateOf(IntSize.Zero) }
  var strokeStartMs by remember { mutableStateOf(0L) }

  fun pointAt(offset: Offset): SignaturePoint {
    val width = padSize.width.coerceAtLeast(1).toFloat()
    val height = padSize.height.coerceAtLeast(1).toFloat()
    return SignaturePoint(
        x = (offset.x / width).coerceIn(0.0f, 1.0f),
        y = (offset.y / height).coerceIn(0.0f, 1.0f),
        tMs = (SystemClock.uptimeMillis() - strokeStartMs).coerceAtLeast(0L),
    )
  }

  ComposeBox(
      modifier =
          Modifier.fillMaxWidth()
              .height(180.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFFF6F7FA))
              .border(1.dp, Color(0xFF6F7785), RoundedCornerShape(6.dp))
              .onSizeChanged {
                padSize = it
                onSizeChange(it)
              }
              .pointerInput(padSize) {
                detectDragGestures(
                    onDragStart = { offset ->
                      strokeStartMs = SystemClock.uptimeMillis()
                      activeStroke = listOf(pointAt(offset))
                    },
                    onDrag = { change, _ ->
                      activeStroke = activeStroke + pointAt(change.position)
                    },
                    onDragEnd = {
                      if (activeStroke.isNotEmpty()) {
                        onStrokesChange(strokes + listOf(activeStroke))
                      }
                      activeStroke = emptyList()
                    },
                    onDragCancel = { activeStroke = emptyList() },
                )
              }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      fun drawStroke(stroke: List<SignaturePoint>) {
        if (stroke.isEmpty()) {
          return
        }
        if (stroke.size == 1) {
          val point = stroke.first()
          drawCircle(
              color = Color(0xFF1A1C22),
              radius = 2.0f,
              center = Offset(point.x * size.width, point.y * size.height),
          )
          return
        }
        stroke.zipWithNext().forEach { (from, to) ->
          drawLine(
              color = Color(0xFF1A1C22),
              start = Offset(from.x * size.width, from.y * size.height),
              end = Offset(to.x * size.width, to.y * size.height),
              strokeWidth = 4.0f,
              cap = StrokeCap.Round,
          )
        }
      }
      strokes.forEach { drawStroke(it) }
      drawStroke(activeStroke)
    }
  }
}

private data class SignaturePoint(val x: Float, val y: Float, val tMs: Long)

private fun signatureJson(
    strokes: List<List<SignaturePoint>>,
    widthPx: Int,
    heightPx: Int,
): JSONObject {
  val strokeRows = JSONArray()
  var pointCount = 0
  strokes.forEach { stroke ->
    val points = JSONArray()
    stroke.forEach { point ->
      points.put(
          JSONObject()
              .put("x", point.x.coerceIn(0.0f, 1.0f).toDouble())
              .put("y", point.y.coerceIn(0.0f, 1.0f).toDouble())
              .put("t_ms", point.tMs.coerceAtLeast(0L))
      )
      pointCount += 1
    }
    if (points.length() > 0) {
      strokeRows.put(points)
    }
  }
  return JSONObject()
      .put("format", "stroke-json-v1")
      .put("width_px", widthPx.coerceAtLeast(1))
      .put("height_px", heightPx.coerceAtLeast(1))
      .put("stroke_count", strokeRows.length())
      .put("point_count", pointCount)
      .put("is_empty", pointCount == 0)
      .put("strokes", strokeRows)
}

private fun emptySignatureJson(): JSONObject =
    JSONObject()
        .put("format", "stroke-json-v1")
        .put("width_px", 0)
        .put("height_px", 0)
        .put("stroke_count", 0)
        .put("point_count", 0)
        .put("is_empty", true)
        .put("strokes", JSONArray())

@Composable
private fun RatingSlider(label: String, value: Int, onChange: (Int) -> Unit) {
  Column {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt().coerceIn(1, 7)) },
        valueRange = 1f..7f,
        steps = 5,
    )
  }
}

@Composable
private fun CompleteStep(snapshot: ExperimentSnapshot, onReset: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Complete", style = MaterialTheme.typography.titleLarge)
    Text(snapshot.filesSummary, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onReset) { Text("New Participant") }
  }
}
