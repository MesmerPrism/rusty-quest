package io.github.mesmerprism.rustyquest.kuramoto_spatial

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import androidx.core.net.toUri
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelSurface
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MediaPanelDisplayOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
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
  private var lastParticleLayerPanelFlipBackFacing: Boolean? = null
  private var lastParticleLayerPanelFlipMarkerMs = 0L

  override fun registerRequiredOpenXRExtensions(): List<String> =
      super.registerRequiredOpenXRExtensions() +
          listOf(
              "XR_META_body_tracking_full_body",
              "XR_META_body_tracking_fidelity",
          )

  override fun registerFeatures(): List<SpatialFeature> {
    val features =
        mutableListOf<SpatialFeature>(
        VRFeature(this),
        SpatialAvatarHandVisualFeature(::marker),
    )
    if (currentWorkflowPanelEnabled()) {
      features += ComposeFeature()
    } else {
      marker(
          "channel=spatial-panel status=compose-feature-suppressed " +
              "panelRegistrationId=kuramoto_experiment_panel workflowPanelEnabled=false " +
              "workflowPanelProperty=$WORKFLOW_PANEL_ENABLED_PROPERTY"
      )
    }
    return features + SpatialPrivateFeatureLoader.load(::marker, this)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    loadNativeReceiptLibrary()
    marker(
        "channel=activity status=created package=io.github.mesmerprism.rustyquest.kuramoto_spatial " +
            "highRateJsonPayload=false hand_rendering_expected=true nativeSurfaceParticleLayerExpected=true"
    )
    scheduleParticleLayerLifecycleDiagnostics("activity-created")
    runValidationWorkflowIfRequested(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    runValidationWorkflowIfRequested(intent)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.18f, 0.18f, 0.18f),
        sunColor = Vector3(2.0f, 2.0f, 2.0f),
        sunDirection = -Vector3(0.5f, 2.0f, -1.0f),
        environmentIntensity = 0.2f,
    )
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    val workflowPanelEnabled = currentWorkflowPanelEnabled()
    val diagnosticBackdropEnabled = currentDiagnosticBackdropEnabled(workflowPanelEnabled)
    if (diagnosticBackdropEnabled) {
      createDiagnosticBackdrop()
    } else {
      marker(
          "channel=diagnostic-backdrop status=suppressed diagnosticBackdrop=false " +
              "workflowPanelEnabled=$workflowPanelEnabled " +
              "diagnosticBackdropProperty=$DIAGNOSTIC_BACKDROP_ENABLED_PROPERTY"
      )
    }
    panelEntity =
        if (workflowPanelEnabled) {
          Entity.createPanelEntity(
              R.id.kuramoto_experiment_panel,
              Transform(panelPose()),
              panelDimensions(),
              Visible(true),
          )
        } else {
          marker(
              "channel=spatial-panel status=suppressed panelRegistrationId=kuramoto_experiment_panel " +
                  "workflowPanelEnabled=false workflowPanelProperty=$WORKFLOW_PANEL_ENABLED_PROPERTY"
          )
          null
        }
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
    updateParticleLayerProjectionFromViewer(reason = "scene-ready", forceLog = true)
    logNativeInteropProbe(phase = "scene-ready", probeSurface = false)
    if (workflowPanelEnabled) {
      marker(
          "channel=spatial-panel status=spawned panelRegistrationId=kuramoto_experiment_panel " +
              "panelY=${panelPlacement.yMeters} panelZ=${panelPlacement.zMeters} panelScale=${panelPlacement.scale} " +
              "panelWidth=$PANEL_WIDTH_METERS panelHeight=$PANEL_HEIGHT_METERS " +
              "workflowPanelEnabled=true workflowPanelProperty=$WORKFLOW_PANEL_ENABLED_PROPERTY " +
              "visibleComponent=true panelDimensionsComponent=true diagnosticBackdrop=$diagnosticBackdropEnabled"
      )
    }
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
    updateParticleLayerProjectionFromViewer(reason = "vr-ready", forceLog = true)
    logNativeInteropProbe(phase = "vr-ready", probeSurface = true)
  }

  override fun onSceneTick() {
    super.onSceneTick()
    updateParticleLayerProjectionFromViewer(reason = "scene-tick", forceLog = false)
  }

  override fun onDestroy() {
    stopNativeSurfaceParticleLayer()
    super.onDestroy()
  }

  override fun registerPanels(): List<PanelRegistration> {
    val panels = mutableListOf<PanelRegistration>()
    if (currentWorkflowPanelEnabled()) {
      panels +=
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
                          adjustPlacement = { dy, dz, scaleDelta ->
                            adjustPanelPlacement(dy, dz, scaleDelta)
                          },
                          updateParticleControls = { driver0, driver1, pointScale ->
                            updateSurfaceParticleControls(driver0, driver1, pointScale)
                          },
                      )
                    }
                  }
                }
              },
              settingsCreator = {
                UIPanelSettings(
                    shape =
                        QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
                    display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
                )
              },
          )
    } else {
      marker(
          "channel=spatial-panel status=registration-suppressed " +
              "panelRegistrationId=kuramoto_experiment_panel workflowPanelEnabled=false " +
              "workflowPanelProperty=$WORKFLOW_PANEL_ENABLED_PROPERTY"
      )
    }
    panels +=
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
        )
    panelRegistrationCount = panels.size
    marker(
        "channel=native-surface-particle-layer status=panel-registrations-created " +
            "renderPolicy=native-vulkan-wsi-surface-panel panelRegistrationCount=$panelRegistrationCount " +
            "workflowPanelEnabled=${currentWorkflowPanelEnabled()} " +
            "particlePanelRegistrationId=kuramoto_particle_surface_panel"
    )
    scheduleParticleLayerLifecycleDiagnostics("register-panels")
    return panels
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
  ): SurfaceParticleControlState {
    particleControls =
        SurfaceParticleControlState(
            driver0Value01 = driver0Value01.coerceIn(0.0f, 1.0f),
            driver1Value01 = driver1Value01.coerceIn(0.0f, 1.0f),
            pointScale = pointScale.coerceIn(0.35f, 2.25f),
        )
    submitNativeSurfaceParticleParameters(source = "panel")
    return particleControls
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

  private fun adjustPanelPlacement(deltaY: Float, deltaZ: Float, deltaScale: Float): PanelPlacement {
    panelPlacement =
        panelPlacement.copy(
            yMeters = (panelPlacement.yMeters + deltaY).coerceIn(0.8f, 2.2f),
            zMeters = (panelPlacement.zMeters + deltaZ).coerceIn(-3.2f, -0.8f),
            scale = (panelPlacement.scale + deltaScale).coerceIn(0.65f, 1.6f),
        )
    applyPanelPlacement()
    marker(
        "channel=spatial-panel status=placement-updated panelY=${panelPlacement.yMeters} " +
            "panelZ=${panelPlacement.zMeters} panelScale=${panelPlacement.scale}"
    )
    return panelPlacement
  }

  private fun applyPanelPlacement() {
    val entity = panelEntity ?: return
    if (!currentWorkflowPanelEnabled()) {
      entity.setComponent(Visible(false))
      return
    }
    entity.setComponent(Transform(panelPose()))
    entity.setComponent(Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale)))
    entity.setComponent(panelDimensions())
    entity.setComponent(Visible(true))
  }

  private fun panelPose(): Pose =
      Pose(
          Vector3(0.0f, panelPlacement.yMeters, panelPlacement.zMeters),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

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
    val now = SystemClock.elapsedRealtime()
    val panelFlipEnabled = currentParticleLayerPanelFlipEnabled()
    val panelFlipIntervalMs = currentParticleLayerPanelFlipIntervalMs()
    val panelBackFacing = panelFlipEnabled && ((now / panelFlipIntervalMs) % 2L == 1L)
    val panelForward = if (panelBackFacing) forward * -1.0f else forward
    val planePose = Pose(center, Quaternion.fromDirection(panelForward, up))
    entity.setComponent(Transform(planePose))
    entity.setComponent(PanelDimensions(Vector2(surfaceWidthMeters, surfaceHeightMeters)))
    entity.setComponent(Visible(true))
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

    val flipStateChanged = lastParticleLayerPanelFlipBackFacing != panelBackFacing
    if (
        forceLog ||
            flipStateChanged ||
            (panelFlipEnabled && now - lastParticleLayerPanelFlipMarkerMs >= panelFlipIntervalMs)
    ) {
      lastParticleLayerPanelFlipBackFacing = panelBackFacing
      lastParticleLayerPanelFlipMarkerMs = now
      marker(
          "channel=native-surface-particle-layer status=panel-flip-state " +
              "reason=${markerToken(reason)} panelFlipEnabled=$panelFlipEnabled " +
              "panelFlipProperty=$PARTICLE_LAYER_PANEL_FLIP_ENABLED_PROPERTY " +
              "panelFlipIntervalMs=$panelFlipIntervalMs " +
              "panelFlipIntervalProperty=$PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS_PROPERTY " +
              "panelBackFacing=$panelBackFacing panelFrontFacing=${!panelBackFacing} " +
              "visualAlternationMode=panel-gpu-vs-spatial-ecs " +
              "nativeProjectionBasisUnflipped=true projectionPlaneFacingMode=viewer-forward-front-face " +
              "viewerForward=${vectorMarker(forward)} panelForward=${vectorMarker(panelForward)} " +
              "planeCenterM=${vectorMarker(center)} panelQuaternion=${quaternionMarker(planePose.q)}"
      )
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
    val eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull()
    marker(
        "channel=native-surface-particle-layer status=projection-plane-updated " +
            "reason=${markerToken(reason)} " +
            particleLayerPlacementMarkerFields() + " " +
            "viewerPoseSource=Scene.getViewerPose eyeOffsetsSource=Scene.getEyeOffsets " +
            "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property " +
            "particleLayerTargetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
            "projectionPlaneFacingMode=viewer-forward-front-face " +
            "panelFlipEnabled=$panelFlipEnabled panelBackFacing=$panelBackFacing " +
            "nativeProjectionBasisUnflipped=true " +
            "viewerPositionM=${vectorMarker(viewerPose.t)} " +
            "viewerForward=${vectorMarker(forward)} panelForward=${vectorMarker(panelForward)} " +
            "viewerUp=${vectorMarker(up)} " +
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
          "panelFlipEnabled=${currentParticleLayerPanelFlipEnabled()} " +
          "panelFlipDefaultEnabled=$PARTICLE_LAYER_PANEL_FLIP_ENABLED " +
          "panelFlipProperty=$PARTICLE_LAYER_PANEL_FLIP_ENABLED_PROPERTY " +
          "panelFlipIntervalMs=${currentParticleLayerPanelFlipIntervalMs()} " +
          "panelFlipDefaultIntervalMs=$PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS " +
          "panelFlipIntervalProperty=$PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS_PROPERTY " +
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

  private fun currentParticleLayerPanelFlipEnabled(): Boolean =
      readBooleanSystemProperty(
          PARTICLE_LAYER_PANEL_FLIP_ENABLED_PROPERTY,
          PARTICLE_LAYER_PANEL_FLIP_ENABLED,
      )

  private fun currentParticleLayerPanelFlipIntervalMs(): Long =
      readLongSystemProperty(
          PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS_PROPERTY,
          PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS,
          PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MIN_MS,
          PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MAX_MS,
      )

  private fun currentWorkflowPanelEnabled(): Boolean =
      readBooleanSystemProperty(WORKFLOW_PANEL_ENABLED_PROPERTY, WORKFLOW_PANEL_ENABLED)

  private fun currentDiagnosticBackdropEnabled(workflowPanelEnabled: Boolean): Boolean =
      readBooleanSystemProperty(DIAGNOSTIC_BACKDROP_ENABLED_PROPERTY, workflowPanelEnabled)

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

  private fun readFloatSystemProperty(
      propertyName: String,
      fallback: Float,
      min: Float,
      max: Float,
  ): Float {
    val text = readSystemProperty(propertyName).trim()
    val parsed = text.toFloatOrNull()
    return if (parsed != null && parsed.isFinite()) parsed.coerceIn(min, max) else fallback
  }

  private fun readBooleanSystemProperty(propertyName: String, fallback: Boolean): Boolean =
      when (readSystemProperty(propertyName).trim().lowercase(Locale.US)) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> fallback
      }

  private fun readLongSystemProperty(
      propertyName: String,
      fallback: Long,
      min: Long,
      max: Long,
  ): Long =
      readSystemProperty(propertyName).trim().toLongOrNull()?.coerceIn(min, max) ?: fallback

  private fun readSystemProperty(propertyName: String): String =
      runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, propertyName, "") as String
          }
          .getOrDefault("")

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
      PanelDimensions(Vector2(PANEL_WIDTH_METERS, PANEL_HEIGHT_METERS))

  private fun createDiagnosticBackdrop() {
    Entity.create(
        listOf(
            Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
            Material().apply {
              baseColor = Color4(0.10f, 0.13f, 0.18f, 1.0f)
              unlit = true
            },
            Transform(Pose(Vector3(0.0f, 0.0f, 0.0f))),
            Visible(true),
        )
    )
    Entity.create(
        listOf(
            Box(),
            Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
            Material().apply {
              baseColor = Color4(0.19f, 0.42f, 0.57f, 1.0f)
              unlit = true
            },
            Transform(Pose(Vector3(0.0f, panelPlacement.yMeters, panelPlacement.zMeters - 0.10f))),
            Scale(Vector3(2.35f, 1.45f, 0.04f)),
            Visible(true),
        )
    )
    Entity.create(
        listOf(
            Box(),
            Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
            Material().apply {
              baseColor = Color4(0.72f, 0.78f, 0.84f, 1.0f)
              unlit = true
            },
            Transform(Pose(Vector3(0.0f, 0.02f, panelPlacement.zMeters))),
            Scale(Vector3(3.2f, 0.04f, 2.4f)),
            Visible(true),
        )
    )
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
      store.startNextBlock()
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
    private const val EXTRA_PARTICIPANT_ID = "participant_id"
    private const val EXTRA_SURFACE_TARGET_ID = "surface_target_id"
    private const val PANEL_WIDTH_METERS = 2.048f
    private const val PANEL_HEIGHT_METERS = 1.254f
    private const val PANEL_DP_PER_METER = 720f
    private const val WORKFLOW_PANEL_ENABLED = true
    private const val WORKFLOW_PANEL_ENABLED_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.workflow_panel.enabled"
    private const val DIAGNOSTIC_BACKDROP_ENABLED_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.diagnostic_backdrop.enabled"
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
    private const val PARTICLE_LAYER_PANEL_FLIP_ENABLED = false
    private const val PARTICLE_LAYER_PANEL_FLIP_ENABLED_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.particle_layer.panel_flip.enabled"
    private const val PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS = 1000L
    private const val PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MS_PROPERTY =
        "debug.rustyquest.kuramoto_spatial.particle_layer.panel_flip.interval_ms"
    private const val PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MIN_MS = 250L
    private const val PARTICLE_LAYER_PANEL_FLIP_INTERVAL_MAX_MS = 10000L
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
    val yMeters: Float = 1.1f,
    val zMeters: Float = -1.7f,
    val scale: Float = 1.0f,
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

@Composable
private fun KuramotoExperimentPanel(
    store: KuramotoExperimentStore,
    placement: PanelPlacement,
    particleControls: SurfaceParticleControlState,
    adjustPlacement: (Float, Float, Float) -> PanelPlacement,
    updateParticleControls: (Float, Float, Float) -> SurfaceParticleControlState,
) {
  var snapshot by remember { mutableStateOf(store.snapshot()) }
  var localPlacement by remember { mutableStateOf(placement) }
  var localParticleControls by remember { mutableStateOf(particleControls) }

  LaunchedEffect(snapshot.stage, snapshot.activeBlock?.deadlineUnixMs) {
    while (snapshot.stage == "block_running") {
      delay(500L)
      store.syncElapsedBlock()
      snapshot = store.snapshot()
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
      PanelPlacementControls(localPlacement) { dy, dz, ds ->
        localPlacement = adjustPlacement(dy, dz, ds)
      }
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
                  snapshot = store.snapshot()
                },
                onReset = {
                  store.resetForNewParticipant()
                  snapshot = store.snapshot()
                },
            )
        "polar_setup" ->
            PolarSetupStep(
                snapshot = snapshot,
                onContinue = { runLabel, operatorId, notes ->
                  store.savePolarSetup(runLabel, operatorId, notes)
                  snapshot = store.snapshot()
                },
            )
        "surface_setup" ->
            SurfaceStep(
                snapshot = snapshot,
                onStart = { surfaceId ->
                  store.selectSurface(surfaceId)
                  store.startNextBlock()
                  snapshot = store.snapshot()
                },
            )
        "block_running" ->
            RunningStep(snapshot) {
              store.syncElapsedBlock()
              snapshot = store.snapshot()
            }
        "questionnaire" ->
            QuestionnaireStep(snapshot) { comfort, intensity, engagement, notes, signature ->
              store.submitQuestionnaire(comfort, intensity, engagement, notes, signature)
              snapshot = store.snapshot()
            }
        "ready_next_block" ->
            ReadyNextStep(snapshot) {
              store.startNextBlock()
              snapshot = store.snapshot()
            }
        "complete" ->
            CompleteStep(snapshot) {
              store.resetForNewParticipant()
              snapshot = store.snapshot()
            }
        else ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  snapshot = store.snapshot()
                },
                onReset = {
                  store.resetForNewParticipant()
                  snapshot = store.snapshot()
                },
            )
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
private fun PanelPlacementControls(
    placement: PanelPlacement,
    onAdjust: (Float, Float, Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        "Panel pose y=${"%.2f".format(placement.yMeters)}m z=${"%.2f".format(placement.zMeters)}m scale=${"%.2f".format(placement.scale)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onAdjust(0.0f, 0.12f, 0.0f) }) { Text("Near") }
      Button(onClick = { onAdjust(0.0f, -0.12f, 0.0f) }) { Text("Far") }
      Button(onClick = { onAdjust(0.08f, 0.0f, 0.0f) }) { Text("Up") }
      Button(onClick = { onAdjust(-0.08f, 0.0f, 0.0f) }) { Text("Down") }
      Button(onClick = { onAdjust(0.0f, 0.0f, -0.08f) }) { Text("Scale -") }
      Button(onClick = { onAdjust(0.0f, 0.0f, 0.08f) }) { Text("Scale +") }
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
    onContinue: (String, String, String) -> Unit,
) {
  var runLabel by remember { mutableStateOf("") }
  var operatorId by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Polar Setup", style = MaterialTheme.typography.titleLarge)
    Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
    Text("Live Polar intake remains in the native APK for this first Spatial SDK lane.", style = MaterialTheme.typography.bodySmall)
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
