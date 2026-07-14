package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
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
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

class SpatialCameraPanelActivity : AppSystemActivity() {
  private val store: SpatialCameraPanelStore by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraPanelStore(this)
  }
  private var panelEntity: Entity? = null
  private var privateLayerPanelEntity: Entity? = null
  private var privateLayerPanelSceneObject: PanelSceneObject? = null
  private var panelLauncherEntity: Entity? = null
  private val panelPlacementStateCoordinator =
      SpatialPanelPlacementStateCoordinator(
          initialWorkflowPlacement = PanelPlacement(visible = !startInParticleView()),
          initialPrivateLayerPlacement = SpatialPanelPlacementModule.initialPrivateLayerPlacement(),
      )
  private val panelPlacement: PanelPlacement
    get() = panelPlacementStateCoordinator.workflowPlacement
  private val privateLayerPanelPlacement: PanelPlacement
    get() = panelPlacementStateCoordinator.privateLayerPlacement
  private val privateLayerPanelVisible: Boolean
    get() = panelPlacementStateCoordinator.privateLayerVisible
  private val panelPoseCoordinator = SpatialPanelPoseCoordinator()
  private var questionnaireDueReopensPanel by mutableStateOf(true)
  private var particleLayerEntity: Entity? = null
  private var particleLayerManualPanelSurface: AndroidSurface? = null
  private var polarSensorPanel: PolarSensorPanel? = null
  private val panelInteractionStateCoordinator = SpatialPanelInteractionStateCoordinator()
  private val panelPersistenceCoordinator: SpatialPanelPersistenceCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPanelPersistenceCoordinator(
            SpatialPanelPersistenceBindings(
                outputDirectory = { filesDir },
                headlockSnapshot = {
                  SpatialPanelHeadlockTuningSnapshot(
                      privateLayerPanelVisible = privateLayerPanelVisible,
                      workflowPlacement = panelPlacement,
                      privateLayerPlacement = privateLayerPanelPlacement,
                  )
                },
                panelMode = ::panelStateToken,
                recordPanelForegroundState = { panelMode, source ->
                  store.recordPanelForegroundState(panelMode, source)
                },
                marker = ::marker,
            )
        )
      }
  private val panelDistanceActuationCoordinator: SpatialPanelDistanceActuationCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPanelDistanceActuationCoordinator(
            SpatialPanelDistanceActuationBindings(
                workflowPlacement = { panelPlacement },
                privateLayerPlacement = { privateLayerPanelPlacement },
                privateLayerPanelVisible = { privateLayerPanelVisible },
                panelHeadlockJoystickEnabled = ::currentPanelHeadlockJoystickEnabled,
                privateLayerFreeTransform = { PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM },
                privateLayerPanelGrabbed = ::privateLayerPanelIsGrabbed,
                privateLayerPanelResourceAvailable = { privateLayerPanelEntity != null },
                syncPrivateLayerPlacement = { reason ->
                  syncPrivateLayerPanelPlacementFromEntity(reason)
                  Unit
                },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                joystickDeltaSeconds = panelInteractionStateCoordinator::joystickDeltaSeconds,
                shouldEmitJoystickMarker =
                    panelInteractionStateCoordinator::shouldEmitJoystickMarker,
                distanceRateMetersPerSecond = {
                  activityReadFloatSystemProperty(
                      PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY,
                      PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND,
                      0.02f,
                      0.80f,
                  )
                },
                replaceWorkflowPlacement = { placement ->
                  panelPlacementStateCoordinator.replaceWorkflowPlacement(placement)
                  Unit
                },
                replacePrivateLayerPlacement = { placement ->
                  panelPlacementStateCoordinator.replacePrivateLayerPlacement(placement)
                  Unit
                },
                applyPanelPlacement = { updatePrivateLayerPanelTransform ->
                  applyPanelPlacement(updatePrivateLayerPanelTransform)
                },
                applyPrivateLayerPanelPose = {
                  val entity = privateLayerPanelEntity
                  if (entity != null) {
                    val updatedPose =
                        privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
                    entity.setComponent(Transform(updatedPose))
                  }
                },
                persistHeadlockTuning = panelPersistenceCoordinator::persistHeadlockTuning,
                leftStickPanelDistanceEnabled = ::currentLeftStickPanelDistanceEnabled,
                leftStickPanelDistanceMapping = ::currentLeftStickPanelDistanceMapping,
                headlockMarkerFields = ::panelHeadlockMarkerFields,
                marker = ::marker,
            )
        )
      }
  private val privateLayerControlCoordinator: SpatialPrivateLayerControlCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPrivateLayerControlCoordinator(
            SpatialPrivateLayerControlBindings(
                routeActive = {
                  cameraHwbProjectionLaunchCoordinator.started ||
                      spatialVideoProjectionRuntimeCoordinator.started
                },
                placementMode = cameraHwbProjectionCarrierStateCoordinator::placementMode,
                projectionTargetScale = cameraHwbProjectionTuningCoordinator::targetScale,
                updatePlacement = cameraHwbProjectionPlacementUpdateCoordinator::update,
                updateLayerOverrideNative = ::nativeUpdatePrivateLayerOverride,
                updateMetaPassthroughStyle = spatialPassthroughLutCoordinator::update,
                projectionPanelEnabled = { projectionPanelVisibilityCoordinator.enabled },
                refreshProjectionAfterPassthroughActivation = { source ->
                  projectionPanelVisibilityCoordinator.setEnabled(false, "$source-refresh-off")
                  projectionPanelVisibilityCoordinator.setEnabled(true, "$source-refresh-on")
                  Unit
                },
                updateDepthLayerPolicyNative = ::nativeUpdatePrivateLayerDepthLayerPolicy,
                updateDepthAlignmentNative = { alignment ->
                  nativeUpdatePrivateLayerDepthAlignment(
                      alignment.leftX,
                      alignment.leftY,
                      alignment.rightX,
                      alignment.rightY,
                      alignment.sampleScale,
                  )
                },
                marker = ::marker,
            )
        )
      }
  private val spatialPassthroughLutCoordinator: SpatialPassthroughLutCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPassthroughLutCoordinator(
            scene = scene,
            scope = activityScope,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            marker = ::marker,
        )
      }
  private val privateLayerPanelLayerCoordinator: SpatialPrivateLayerPanelLayerCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPrivateLayerPanelLayerCoordinator(
            SpatialPrivateLayerPanelLayerBindings(
                layerConfigEnabled = { true },
                panelAvailable = { privateLayerPanelSceneObject != null },
                applyLayerZIndex = apply@{
                  val layer = privateLayerPanelSceneObject?.layer ?: return@apply false
                  layer.setZIndex(PRIVATE_LAYER_PANEL_LAYER_Z_INDEX)
                  true
                },
                marker = ::marker,
            )
        )
      }
  private val panelJoystickArbitrationCoordinator:
      SpatialPanelJoystickArbitrationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        SpatialPanelJoystickArbitrationCoordinator(
            SpatialPanelJoystickArbitrationBindings(
                applyProjectionScale = { rightY ->
                  cameraHwbProjectionTuningCoordinator.applyScaleInput(
                      rightY = rightY,
                      inputSource = "android-generic-motion-joystick",
                      controllerJoystickMapping = "right-stick-y-projection-target-scale",
                      detail = "rightStickY=${activityMarkerFloat(rightY)}",
                  )
                },
                applyPanelPlacement = ::applyPanelHeadlockJoystickAxes,
                leftStickPanelDistanceEnabled = ::currentLeftStickPanelDistanceEnabled,
                privateLayerPanelVisible = { privateLayerPanelVisible },
                panelMode = ::panelStateToken,
                projectionTargetScale = cameraHwbProjectionTuningCoordinator::targetScale,
                headlockMarkerFields = ::panelHeadlockMarkerFields,
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                marker = ::marker,
            )
        )
      }
  private val controllerInputRouteSpec =
      SpatialControllerInputRouteSpec(
          enabled = true,
          source = "spatial-camera-panel-app-spec",
      )
  private val androidControllerEventRouter by lazy(LazyThreadSafetyMode.NONE) {
    SpatialControllerAndroidEventRouter(
        armSecondaryToggle = { inputSource ->
          cameraHwbProjectionCarrierStateCoordinator.armSecondaryToggle(inputSource)
        },
        toggleSecondary = { inputSource, detail ->
          cameraHwbProjectionCarrierStateCoordinator.togglePlacementMode(inputSource, detail)
        },
        recenterTrigger = { inputSource, detail ->
          surfaceParticleRecenterCoordinator.recenter(
              SpatialSurfaceParticleRecenterRequest(
                  inputSource = inputSource,
                  detail = detail,
                  requireParticleView = true,
              )
          )
        },
        openPrimary = ::openWorkflowPanelFromController,
    )
  }
  private val controllerInputRouteCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialControllerInputRouteCoordinator(
        SpatialControllerInputRouteBindings(
            routeSpec = { controllerInputRouteSpec },
            enableSpatialInput = {
              scene.spatialInterface.enableInput(true)
              true
            },
            gameControllerDeviceIds = { getGameControllerDeviceIds().toList() },
            pinGameController = { deviceId, listener ->
              pinGameController(deviceId) { motionEvent, keyEvent ->
                listener(motionEvent, keyEvent)
              }
            },
            dispatchKeyEvent = androidControllerEventRouter::dispatchKeyEvent,
            dispatchMotionButtonEvent =
                androidControllerEventRouter::dispatchMotionButtonEvent,
            dispatchJoystickMotion = ::handleSpatialJoystickMotion,
            marker = ::marker,
        )
    )
  }
  private val nativeInputBootstrapCoordinator:
      SpatialNativeInputBootstrapCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialNativeInputBootstrapCoordinator(
        SpatialNativeInputBootstrapBindings(
            receiptLibraryLoaded = { nativeInteropCoordinator.receiptLibraryLoaded },
            multimodalInputEnabled = ::spatialMultimodalInputEnabled,
            controllerActionsEnabled = ::nativeSpatialControllerActionsEnabled,
            requestMultimodalInput = ::nativeRequestSpatialMultimodalInput,
            startControllerActions = ::nativeStartSpatialControllerActions,
            marker = ::marker,
        )
    )
  }
  private val nativeInteropCoordinator:
      SpatialNativeInteropCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialNativeInteropCoordinator(
        SpatialNativeInteropBindings(
            scene = scene,
            recordNoRenderReceipt = ::nativeRecordNoRenderInteropReceipt,
            requestMultimodalInput = { probe, phase ->
              nativeInputBootstrapCoordinator.requestMultimodalInputIfReady(probe, phase)
            },
            startControllerActions = { probe, phase ->
              nativeInputBootstrapCoordinator.startControllerActionsIfReady(probe, phase)
            },
            marker = ::marker,
        )
    )
  }
  private val surfaceParticleParameterCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticleParameterCoordinator(
        SpatialSurfaceParticleParameterBindings(
            featureEnabled = {
              surfaceParticleRuntimeCoordinator.reconcileAdapterAdmission("parameter-effect")
            },
            receiptLibraryLoaded = { nativeInteropCoordinator.receiptLibraryLoaded },
            workflowPanelVisible = { panelPlacement.visible },
            submitNativeParameters = { controls ->
              nativeUpdateSurfaceParticleParameters(
                  controls.driver0Value01,
                  controls.driver1Value01,
                  controls.pointScale,
                  controls.driver2Value01,
                  controls.driver3Value01,
                  controls.driver4Value01,
                  controls.driver5Value01,
                  controls.driver6Value01,
                  controls.driver7Value01,
                  controls.tracerDrawSlotsPerOscillator,
                  controls.tracerLifetimeSeconds,
                  controls.tracerCopiesPerSecond,
                  controls.transparencyOpacity,
                  controls.projectionWorldScale,
              )
            },
            resolveNativeAlias = ::nativeResolveSurfaceParticleAliasParameter,
            marker = ::marker,
        )
    )
  }
  private val surfaceParticleRuntimeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticleRuntimeCoordinator(
        SpatialSurfaceParticleRuntimeBindings(
            nativeSurfaceParticleLayerEnabled = ::nativeSurfaceParticleLayerEnabled,
            suppressionSource = ::nativeSurfaceParticleLayerSuppressionSource,
            privateRendererEnabled = ::privateSpatialEcsParticleRendererEnabled,
            receiptLibraryLoaded = { nativeInteropCoordinator.receiptLibraryLoaded },
            receiptLibraryError = { nativeInteropCoordinator.receiptLibraryError },
            launcherPanelVisible = ::launcherPanelVisibleForPanelMode,
            stopNative = ::nativeStopSurfaceParticleLayer,
            marker = ::marker,
        )
    )
  }
  private val surfaceParticleProjectionGeometryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticleProjectionGeometryCoordinator(
        SpatialSurfaceParticleProjectionGeometryBindings(
            configuredTargetDistanceMeters = {
              activityReadFloatSystemProperty(
                  PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY,
                  PARTICLE_LAYER_TARGET_DISTANCE_METERS,
                  PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
                  PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
              )
            },
            configuredViewYawDegrees = {
              activityReadFloatSystemProperty(
                  PARTICLE_LAYER_VIEW_YAW_PROPERTY,
                  PARTICLE_LAYER_VIEW_YAW_DEGREES,
                  PARTICLE_LAYER_VIEW_YAW_MIN_DEGREES,
                  PARTICLE_LAYER_VIEW_YAW_MAX_DEGREES,
              )
            },
            panelOpacity = {
              activityReadFloatSystemProperty(
                  PARTICLE_LAYER_PANEL_OPACITY_PROPERTY,
                  PARTICLE_LAYER_PANEL_OPACITY,
                  PARTICLE_LAYER_PANEL_OPACITY_MIN,
                  PARTICLE_LAYER_PANEL_OPACITY_MAX,
              )
            },
            surfaceOverscanScale = {
              activityReadFloatSystemProperty(
                  PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY,
                  PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE,
                  PARTICLE_LAYER_SURFACE_OVERSCAN_MIN_SCALE,
                  PARTICLE_LAYER_SURFACE_OVERSCAN_MAX_SCALE,
              )
            },
            carrierMode = ::particleLayerCarrierMode,
            updateProjection = ::updateParticleLayerProjectionFromViewer,
            marker = ::marker,
        )
    )
  }
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val surfaceParticleProjectionUpdateCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticleProjectionUpdateCoordinator(
        SpatialSurfaceParticleProjectionUpdateBindings(
            featureEnabled = {
              surfaceParticleRuntimeCoordinator.reconcileAdapterAdmission("projection-effect")
            },
            cameraStackSuppressesParticles = {
              surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles
            },
            captureViewerState = {
              val viewerPose = scene.getViewerPose()
              val eyeOffsets = runCatching { scene.getEyeOffsets() }.getOrNull()
              SpatialSurfaceParticleViewerProjectionState(
                  viewerPose = viewerPose,
                  leftEyeOffset = eyeOffsets?.first,
                  rightEyeOffset = eyeOffsets?.second,
              )
            },
            currentViewYawDegrees =
                surfaceParticleProjectionGeometryCoordinator::currentViewYawDegrees,
            currentTargetDistanceMeters =
                surfaceParticleProjectionGeometryCoordinator::currentTargetDistanceMeters,
            projectionWidthMeters =
                surfaceParticleProjectionGeometryCoordinator::projectionWidthMeters,
            projectionHeightMeters =
                surfaceParticleProjectionGeometryCoordinator::projectionHeightMeters,
            currentSurfaceOverscanScale =
                surfaceParticleProjectionGeometryCoordinator::currentSurfaceOverscanScale,
            surfaceWidthMeters = surfaceParticleProjectionGeometryCoordinator::surfaceWidthMeters,
            surfaceHeightMeters =
                surfaceParticleProjectionGeometryCoordinator::surfaceHeightMeters,
            particleLayerVisible = ::particleLayerVisibleForPanelMode,
            updatePanelLayer = ::updateParticleLayerPanelLayer,
            receiptLibraryLoaded = { nativeInteropCoordinator.receiptLibraryLoaded },
            updateNativePanelPose = { update ->
              nativeUpdateSurfaceParticlePanelPose(
                  update.center.x,
                  update.center.y,
                  update.center.z,
                  update.right.x,
                  update.right.y,
                  update.right.z,
                  update.up.x,
                  update.up.y,
                  update.up.z,
                  update.surfaceWidthMeters,
                  update.surfaceHeightMeters,
                  update.targetDistanceMeters,
                  update.leftEyeOffsetRightMeters,
                  update.rightEyeOffsetRightMeters,
              )
            },
            updateNativeViewerEyePose = { update ->
              nativeUpdateSurfaceParticleViewerEyePose(
                  update.viewerPosition.x,
                  update.viewerPosition.y,
                  update.viewerPosition.z,
                  update.rawRight.x,
                  update.rawRight.y,
                  update.rawRight.z,
                  update.rawUp.x,
                  update.rawUp.y,
                  update.rawUp.z,
                  update.rawForward.x,
                  update.rawForward.y,
                  update.rawForward.z,
                  update.leftEyeWorld.x,
                  update.leftEyeWorld.y,
                  update.leftEyeWorld.z,
                  update.rightEyeWorld.x,
                  update.rightEyeWorld.y,
                  update.rightEyeWorld.z,
              )
            },
            elapsedRealtime = { SystemClock.elapsedRealtime() },
            placementMarkerFields =
                surfaceParticleProjectionGeometryCoordinator::placementMarkerFields,
            marker = ::marker,
        )
    )
  }
  private val surfaceParticlePanelLayerCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticlePanelLayerCoordinator(
        SpatialSurfaceParticlePanelLayerBindings(marker = ::marker)
    )
  }
  private val surfaceParticlePresentationStateCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticlePresentationStateCoordinator(
        SpatialSurfaceParticlePresentationStateBindings(marker = ::marker)
    )
  }
  private val surfaceParticleRecenterCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSurfaceParticleRecenterCoordinator(
        SpatialSurfaceParticleRecenterBindings(
            featureEnabled = {
              surfaceParticleRuntimeCoordinator.reconcileAdapterAdmission("recenter-effect")
            },
            surfaceTargetId = { store.snapshot().surfaceTargetId },
            particleLayerVisible = ::particleLayerVisibleForPanelMode,
            workflowPanelVisible = { panelPlacement.visible },
            privateLayerPanelVisible = { privateLayerPanelVisible },
            receiptLibraryLoaded = { nativeInteropCoordinator.receiptLibraryLoaded },
            recenterNative = ::nativeRecenterSurfaceParticleSphereOnViewer,
            marker = ::marker,
        )
    )
  }
  private val surfaceParticleLifecycleDiagnosticsCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialSurfaceParticleLifecycleDiagnosticsCoordinator(
            SpatialSurfaceParticleLifecycleDiagnosticsBindings(
                featureEnabled = {
                  surfaceParticleRuntimeCoordinator.reconcileAdapterAdmission(
                      "lifecycle-diagnostic"
                  )
                },
                activityMarkersFile = ACTIVITY_MARKERS_FILE,
                snapshot = ::surfaceParticleLifecycleDiagnosticSnapshot,
                marker = ::marker,
            )
        )
      }
  private val controllerPollingCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialControllerPollingCoordinator(
        SpatialControllerPollingBindings(
            nativeState = {
              SpatialNativeControllerPollingState(
                  featureEnabled = nativeSpatialControllerActionsEnabled(),
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  actionsStarted = nativeInputBootstrapCoordinator.controllerActionsStarted,
                  actionStartMask = nativeInputBootstrapCoordinator.controllerActionsStartMask,
              )
            },
            disableNativeActions = nativeInputBootstrapCoordinator::disableControllerActions,
            pollNativeLeftThumbstickY = ::nativePollSpatialControllerLeftThumbstickY,
            pollNativeRightThumbstickY = ::nativePollSpatialControllerRightThumbstickY,
            pollNativeRightButtonB = ::nativePollSpatialControllerRightButtonB,
            captureSpatialSnapshot = { SpatialControllerSnapshotAdapter.capture(scene) },
            currentLeftStickPanelDistanceMapping = ::currentLeftStickPanelDistanceMapping,
            currentLeftStickPanelDistanceEnabled = ::currentLeftStickPanelDistanceEnabled,
            currentSpatialVrInputSystemToken = ::currentSpatialVrInputSystemToken,
            applyProjectionScale = { value, inputSource, mapping, detail ->
              cameraHwbProjectionTuningCoordinator.applyScaleInput(
                  value,
                  inputSource,
                  mapping,
                  detail,
              )
              Unit
            },
            applyPanelDistance = { value, inputSource, mapping, detail ->
              panelDistanceActuationCoordinator.apply(value, inputSource, mapping, detail)
              Unit
            },
            recenterParticleSphere = { inputSource, detail ->
              surfaceParticleRecenterCoordinator.recenter(
                  SpatialSurfaceParticleRecenterRequest(
                      inputSource = inputSource,
                      detail = detail,
                      requireParticleView = true,
                  )
              )
            },
            armSecondaryToggle = { inputSource ->
              cameraHwbProjectionCarrierStateCoordinator.armSecondaryToggle(inputSource)
            },
            toggleSecondary = { inputSource, detail ->
              cameraHwbProjectionCarrierStateCoordinator.togglePlacementMode(
                  inputSource,
                  detail,
              )
              Unit
            },
            openPrimary = { inputSource, detail ->
              openWorkflowPanelFromController(inputSource, detail)
              Unit
            },
            marker = ::marker,
        )
    )
  }
  private val validationWorkflowCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialValidationWorkflowCoordinator(
        SpatialValidationWorkflowBindings(
            store = { store },
            marker = ::marker,
            scheduleParticleLayerLifecycleDiagnostics = { reason ->
              surfaceParticleLifecycleDiagnosticsCoordinator.schedule(
                  reason,
                  explicitRequest = true,
              )
              Unit
            },
            logParticleLayerLifecycleStatus = { phase ->
              surfaceParticleLifecycleDiagnosticsCoordinator.log(
                  phase,
                  explicitRequest = true,
              )
              Unit
            },
            setWorkflowPanelVisible = { visible, focus, source ->
              setWorkflowPanelVisible(visible, focus, source)
              Unit
            },
            setPrivateLayerPanelVisible = { visible, focus, source ->
              setPrivateLayerPanelVisible(visible, focus, source)
              Unit
            },
            updatePrivateLayerOverride = { layerOverride, source ->
              privateLayerControlCoordinator.updateLayerOverride(layerOverride, source)
              Unit
            },
            setProjectionPanelEnabled = { enabled, source ->
              projectionPanelVisibilityCoordinator.setEnabled(enabled, source)
              Unit
            },
            resetWorkflowPanelPlacement = {
              resetWorkflowPanelPlacement()
              Unit
            },
            setPanelHeadlocked = { enabled, source ->
              setPanelHeadlocked(enabled, source)
              Unit
            },
            panelHeadlocked = { panelPlacement.headlocked },
            adjustPanelPlacement = { deltaX, deltaY, deltaZ, deltaScale ->
              adjustPanelPlacement(deltaX, deltaY, deltaZ, deltaScale)
              Unit
            },
            resizeWorkflowPanel = { deltaWidth, deltaHeight ->
              resizeWorkflowPanel(deltaWidth, deltaHeight)
              Unit
            },
            currentParticleControls = { surfaceParticleParameterCoordinator.controls },
            updateSurfaceParticleControls = { controls, source ->
              surfaceParticleParameterCoordinator.updateControls(controls, source)
              Unit
            },
            applyRemoteParticleLayerTargetDistance =
                ::applyRemoteParticleLayerTargetDistance,
            applyRemoteParticleLayerViewYaw = ::applyRemoteParticleLayerViewYaw,
            recenterSurfaceParticleSphere = { inputSource, detail ->
              surfaceParticleRecenterCoordinator.recenter(
                  SpatialSurfaceParticleRecenterRequest(
                      inputSource = inputSource,
                      detail = detail,
                      requireParticleView = false,
                  )
              )
              Unit
            },
            resolveSurfaceParticleAliasControl = ::resolveSurfaceParticleAliasControl,
            applyDriverProfileToParticleControls = { block, source ->
              surfaceParticleParameterCoordinator.applyDriverProfile(block, source)
              Unit
            },
            setQuestionnaireDueReopensPanel = ::setQuestionnaireDueReopensPanel,
            panelStateToken = ::panelStateToken,
            workflowPanelVisible = { panelPlacement.visible },
            ensurePolarSensorPanel = ::ensurePolarSensorPanel,
            logError = { message, throwable -> Log.e(TAG, message, throwable) },
        )
    )
  }
  private val externalSwapchainProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialExternalSwapchainProbeCoordinator(
        SpatialExternalSwapchainProbeBindings(
            scene = scene,
            nativeState = {
              SpatialExternalSwapchainProbeNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            createExternalSwapchain = ::nativeCreateExternalOpenXrSwapchain,
            destroyExternalSwapchain = ::nativeDestroyExternalOpenXrSwapchain,
            marker = ::marker,
        )
    )
  }
  private val spatialVideoProjectionRuntimeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialVideoProjectionRuntimeCoordinator(
        SpatialVideoProjectionRuntimeBindings(
            nativeState = {
              SpatialVideoProjectionRuntimeNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded
              )
            },
            configureNative = { settings ->
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
            },
            startPlayback = { settings ->
              SpatialStereoVideoPlayback.start(
                  this,
                  settings.source,
                  settings.path,
                  settings.width,
                  settings.height,
                  settings.maxImages,
                  settings.fpsCap,
                  settings.looping,
                  settings.brokerHost,
                  settings.brokerPort,
                  settings.brokerConnectTimeoutMs,
                  settings.mediaLayout,
              )
            },
            stopPlayback = { SpatialStereoVideoPlayback.stop() },
            stopNativeProbe = ::nativeStopSpatialVideoProjectionProbe,
            marker = ::marker,
        )
    )
  }
  private var cameraHwbProjectionEntity: Entity? = null
  private val sdkQuadResourceCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSdkQuadResourceCoordinator(
        SpatialSdkQuadResourceBindings(
            scene = scene,
            marker = ::marker,
            onSceneResourcesCleared = { cameraHwbProjectionEntity = null },
        )
    )
  }
  private val sdkQuadSurfaceProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSdkQuadSurfaceProbeCoordinator(
        SpatialSdkQuadSurfaceProbeBindings(
            scene = scene,
            resources = sdkQuadResourceCoordinator,
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            marker = ::marker,
        )
    )
  }
  private val sdkQuadVulkanProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSdkQuadVulkanProbeCoordinator(
        SpatialSdkQuadVulkanProbeBindings(
            resources = sdkQuadResourceCoordinator,
            surfaceProbe = sdkQuadSurfaceProbeCoordinator,
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            nativeState = {
              SpatialSdkQuadVulkanNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            startNative = ::nativeStartSdkQuadVulkanProbe,
            stopNative = ::nativeStopSdkQuadVulkanProbe,
            marker = ::marker,
        )
    )
  }
  private val sdkQuadStereoAlphaProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialSdkQuadStereoAlphaProbeCoordinator(
        SpatialSdkQuadStereoAlphaProbeBindings(
            scene = scene,
            resources = sdkQuadResourceCoordinator,
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            marker = ::marker,
        )
    )
  }
  private val panelSurfaceMatrixProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialPanelSurfaceMatrixProbeCoordinator(
        SpatialPanelSurfaceMatrixProbeBindings(
            scene = scene,
            surfaceProbe = sdkQuadSurfaceProbeCoordinator,
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            nativeState = {
              SpatialPanelSurfaceMatrixNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded
              )
            },
            startNative = ::nativeStartSdkQuadVulkanProbe,
            stopNative = ::nativeStopSdkQuadVulkanProbe,
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProbeCoordinator(
        SpatialCameraHwbProbeBindings(
            scene = scene,
            resources = sdkQuadResourceCoordinator,
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            projectionProbeEnabled = {
              activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) ==
                  true
            },
            nativeState = {
              SpatialCameraHwbNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            startNative = ::nativeStartCameraHwbProbe,
            stopNative = ::nativeStopCameraHwbProbe,
            marker = ::marker,
        )
    )
  }
  private val spatialVideoProjectionProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialVideoProjectionProbeCoordinator(
        SpatialVideoProjectionProbeBindings(
            resources = sdkQuadResourceCoordinator,
            state = {
              SpatialVideoProjectionProbeState(
                  enabled =
                      activityReadOptionalBooleanSystemProperty(
                          SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY
                      ) == true,
                  sceneReady = spatialSceneReady,
                  virtualRoomEnabled = spatialVirtualRoomEnabled(),
                  virtualRoomLoaded = spatialVirtualRoomLoaded(),
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            resolveSettings = { spatialVideoProjectionRuntimeCoordinator.resolveSettings(intent) },
            projectionMarkerFields = cameraHwbProjectionGeometryCoordinator::markerFields,
            stereoMarkerFields = cameraHwbProjectionGeometryCoordinator::stereoMarkerFields,
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            prepare = { videoSettings ->
              spatialVideoProjectionRuntimeCoordinator.adoptSettings(videoSettings)
              cameraHwbProjectionEntity = null
              cameraHwbProjectionTuningCoordinator.resetStereoOffset()
              cameraHwbProjectionPlacementUpdateCoordinator.resetMarkerCadence()
              suppressParticleLayerForCameraStack("spatial-video-projection-probe")
              setWorkflowPanelVisible(
                  false,
                  focus = false,
                  source = "spatial-video-projection-probe",
              )
            },
            configureNative = spatialVideoProjectionRuntimeCoordinator::configure,
            startProjection = spatialVideoProjectionRuntimeCoordinator::start,
            createLayer = { swapchain ->
              cameraHwbProjectionRawCarrierCoordinator.createLayer(
                  swapchain,
                  spatialVideoProjectionRuntimeCoordinator.settings,
              )
            },
            startNative = ::nativeStartSpatialVideoProjectionProbe,
            updateFromViewer = { reason, forceLog ->
              cameraHwbProjectionPlacementUpdateCoordinator.update(reason, forceLog)
            },
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionLaunchCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionLaunchCoordinator(
        SpatialCameraHwbProjectionLaunchBindings(
            state = {
              SpatialCameraHwbProjectionLaunchState(
                  enabled =
                      activityReadOptionalBooleanSystemProperty(
                          CAMERA_HWB_PROJECTION_PROBE_PROPERTY
                      ) == true,
                  sceneReady = spatialSceneReady,
                  virtualRoomEnabled = spatialVirtualRoomEnabled(),
                  virtualRoomLoaded = spatialVirtualRoomLoaded(),
              )
            },
            prepareRequest = {
              cameraHwbProjectionCarrierStateCoordinator.refreshCarrierMode()
              currentCameraHwbProjectionLaunchRequest(
                  spatialVideoProjectionRuntimeCoordinator.resolveSettings(intent)
              )
            },
            startGateToken = cameraHwbProjectionCarrierStateCoordinator::startGateToken,
            carrierToken = cameraHwbProjectionCarrierStateCoordinator::carrierToken,
            projectionMarkerFields = cameraHwbProjectionGeometryCoordinator::markerFields,
            stereoMarkerFields = cameraHwbProjectionGeometryCoordinator::stereoMarkerFields,
            videoProjectionMarkerFields = spatialVideoProjectionRuntimeCoordinator::markerFields,
            launch = { request ->
              runCameraHwbProjectionProbe(request.readerMaxImages, request.videoSettings)
            },
            marker = ::marker,
        )
    )
  }
  private val projectionPanelVisibilityCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialProjectionPanelVisibilityCoordinator(
        SpatialProjectionPanelVisibilityBindings(
            projectionLaunchStarted = { cameraHwbProjectionLaunchCoordinator.started },
            currentVideoSettings = { spatialVideoProjectionRuntimeCoordinator.settings },
            markProjectionLaunchStopped = cameraHwbProjectionLaunchCoordinator::markStopped,
            stopProjectionPanel = ::stopCameraHwbProjectionPanel,
            enableSystemPassthrough = {
              scene.enablePassthrough(true)
              scene.isSystemPassthroughEnabled()
            },
            restartProjectionPanel = { videoSettings, reason ->
              cameraHwbProjectionCarrierStateCoordinator.refreshCarrierMode()
              cameraHwbProjectionLaunchCoordinator.restart(
                  reason,
                  currentCameraHwbProjectionLaunchRequest(videoSettings),
              )
            },
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionSyntheticRenderer by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionSyntheticRenderer(
        SpatialCameraHwbProjectionSyntheticRendererBindings(marker = ::marker)
    )
  }
  private val cameraHwbProjectionDepthPrerequisiteCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionDepthPrerequisiteCoordinator(
        SpatialCameraHwbProjectionDepthPrerequisiteBindings(
            routeActive = { cameraHwbProjectionLaunchCoordinator.started },
            nativeState = {
              SpatialCameraHwbProjectionDepthPrerequisiteNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            captureInteropProbe = { SpatialNativeInteropProbe.capture(scene) },
            requiredOpenXrExtensions = ::spatialRequiredOpenXrExtensionMarker,
            projectionEntityPresent = { cameraHwbProjectionEntity != null },
            startNativePassthrough = ::nativeStartSpatialNativePassthrough,
            stopNativePassthrough = ::nativeStopSpatialNativePassthrough,
            startNativeEnvironmentDepth = ::nativeStartSpatialEnvironmentDepthProbe,
            stopNativeEnvironmentDepth = ::nativeStopSpatialEnvironmentDepthProbe,
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionRawCarrierCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionRawCarrierCoordinator(
        SpatialCameraHwbProjectionRawCarrierBindings(
            scene = scene,
            resources = sdkQuadResourceCoordinator,
            routeEnabled = {
              cameraHwbProjectionLaunchCoordinator.started &&
                  !cameraHwbProjectionCarrierStateCoordinator.scenePanelCarrierEnabled()
            },
            nativeState = {
              SpatialCameraHwbProjectionRawNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            cleanup = ::cleanupSdkQuadSurfaceProbe,
            projectionPlane = cameraHwbProjectionGeometryCoordinator::planeForPlacement,
            setProjectionEntity = { entity -> cameraHwbProjectionEntity = entity },
            layerZIndex = cameraHwbProjectionCarrierStateCoordinator::zIndexForPlacement,
            carrierMode = cameraHwbProjectionCarrierStateCoordinator::carrierMode,
            carrierToken = cameraHwbProjectionCarrierStateCoordinator::carrierToken,
            projectionMarkerFields = cameraHwbProjectionGeometryCoordinator::markerFields,
            stereoMarkerFields = cameraHwbProjectionGeometryCoordinator::stereoMarkerFields,
            videoProjectionMarkerFields = spatialVideoProjectionRuntimeCoordinator::markerFields,
            syntheticVisualEnabled = ::cameraHwbProjectionSyntheticVisualProbeEnabled,
            drawSyntheticVisual = cameraHwbProjectionSyntheticRenderer::draw,
            startNativePassthrough =
                cameraHwbProjectionDepthPrerequisiteCoordinator::startPassthrough,
            startEnvironmentDepth =
                cameraHwbProjectionDepthPrerequisiteCoordinator::startEnvironmentDepth,
            updateNativeStereoOffset = { reason, forceLog ->
              cameraHwbProjectionTuningCoordinator.updateNativeStereoOffset(reason, forceLog)
            },
            updateNativeTargetScale = { reason, forceLog ->
              cameraHwbProjectionTuningCoordinator.updateNativeTargetScale(reason, forceLog)
            },
            applyPrivateLayerConfiguration =
                privateLayerControlCoordinator::applyCurrentConfiguration,
            configureVideoProjection = spatialVideoProjectionRuntimeCoordinator::configure,
            startVideoProjection = spatialVideoProjectionRuntimeCoordinator::start,
            startNative = ::nativeStartCameraHwbProjectionProbe,
            updateFromViewer = { reason, forceLog ->
              cameraHwbProjectionPlacementUpdateCoordinator.update(reason, forceLog)
            },
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionPanelCarrierCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionPanelCarrierCoordinator(
        SpatialCameraHwbProjectionPanelCarrierBindings(
            scene = scene,
            sceneObjectSystem = { systemManager.findSystem<SceneObjectSystem>() },
            routeEnabled = {
              cameraHwbProjectionLaunchCoordinator.started &&
                  cameraHwbProjectionCarrierStateCoordinator.scenePanelCarrierEnabled()
            },
            manualCustomMeshEnabled =
                cameraHwbProjectionCarrierStateCoordinator::manualCustomMeshCarrierEnabled,
            nativeState = {
              SpatialCameraHwbProjectionPanelNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            panelMediaSettings = cameraHwbProjectionGeometryCoordinator::panelMediaSettings,
            projectionPlane = cameraHwbProjectionGeometryCoordinator::planeForPlacement,
            projectionEntity = { cameraHwbProjectionEntity },
            setProjectionEntity = { entity -> cameraHwbProjectionEntity = entity },
            layerZIndex = cameraHwbProjectionCarrierStateCoordinator::zIndexForPlacement,
            carrierToken = cameraHwbProjectionCarrierStateCoordinator::carrierToken,
            panelRegistrationId = cameraHwbProjectionCarrierStateCoordinator::panelRegistrationId,
            projectionMarkerFields = cameraHwbProjectionGeometryCoordinator::markerFields,
            stereoMarkerFields = cameraHwbProjectionGeometryCoordinator::stereoMarkerFields,
            videoSettings = { spatialVideoProjectionRuntimeCoordinator.settings },
            videoProjectionMarkerFields = spatialVideoProjectionRuntimeCoordinator::markerFields,
            syntheticVisualEnabled = ::cameraHwbProjectionSyntheticVisualProbeEnabled,
            drawSyntheticVisual = cameraHwbProjectionSyntheticRenderer::draw,
            startNativePassthrough =
                cameraHwbProjectionDepthPrerequisiteCoordinator::startPassthrough,
            startEnvironmentDepth =
                cameraHwbProjectionDepthPrerequisiteCoordinator::startEnvironmentDepth,
            updateNativeStereoOffset = { reason, forceLog ->
              cameraHwbProjectionTuningCoordinator.updateNativeStereoOffset(reason, forceLog)
            },
            updateNativeTargetScale = { reason, forceLog ->
              cameraHwbProjectionTuningCoordinator.updateNativeTargetScale(reason, forceLog)
            },
            applyPrivateLayerConfiguration =
                privateLayerControlCoordinator::applyCurrentConfiguration,
            configureVideoProjection = spatialVideoProjectionRuntimeCoordinator::configure,
            startVideoProjection = spatialVideoProjectionRuntimeCoordinator::start,
            startNative = ::nativeStartCameraHwbProjectionProbe,
            stopNative = ::nativeStopCameraHwbProbe,
            updateFromViewer = { reason, forceLog ->
              cameraHwbProjectionPlacementUpdateCoordinator.update(reason, forceLog)
            },
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionPlacementUpdateCoordinator:
      SpatialCameraHwbProjectionPlacementUpdateCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionPlacementUpdateCoordinator(
        SpatialCameraHwbProjectionPlacementUpdateBindings(
            resources = sdkQuadResourceCoordinator,
            routeActive = {
              cameraHwbProjectionLaunchCoordinator.started ||
                  spatialVideoProjectionRuntimeCoordinator.started
            },
            projectionEntity = { cameraHwbProjectionEntity },
            scenePanelCarrierEnabled =
                cameraHwbProjectionCarrierStateCoordinator::scenePanelCarrierEnabled,
            projectionPlane = cameraHwbProjectionGeometryCoordinator::planeForPlacement,
            updatePanelCarrierLayer = { plane, reason ->
              cameraHwbProjectionPanelCarrierCoordinator.updateLayer(plane, reason)
            },
            layerZIndex = cameraHwbProjectionCarrierStateCoordinator::zIndexForPlacement,
            nativeState = {
              SpatialCameraHwbProjectionPlacementNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
                  receiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
              )
            },
            updateNativePanelPose = { plane ->
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
            },
            projectionMarkerFields = cameraHwbProjectionGeometryCoordinator::markerFields,
            stereoMarkerFields = cameraHwbProjectionGeometryCoordinator::stereoMarkerFields,
            videoProjectionMarkerFields = {
              spatialVideoProjectionRuntimeCoordinator.markerFields(
                  spatialVideoProjectionRuntimeCoordinator.settings
              )
            },
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionTuningCoordinator:
      SpatialCameraHwbProjectionTuningCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionTuningCoordinator(
        SpatialCameraHwbProjectionTuningBindings(
            routeActive = { cameraHwbProjectionLaunchCoordinator.started },
            projectionEntityPresent = { cameraHwbProjectionEntity != null },
            privateLayerPanelVisible = { privateLayerPanelVisible },
            workflowPanelVisible = { panelPlacement.visible },
            initialTargetScale = {
              activityReadFloatSystemProperty(
                  CAMERA_HWB_PROJECTION_TARGET_SCALE_PROPERTY,
                  CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT,
                  CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
                  CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
              )
            },
            targetScaleJoystickRate = {
              activityReadFloatSystemProperty(
                  CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY,
                  CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PER_SECOND,
                  0.02f,
                  1.25f,
              )
            },
            targetDistanceMeters = {
              cameraHwbProjectionGeometryCoordinator.targetDistanceMeters()
            },
            updatePlacement = { reason, forceLog ->
              cameraHwbProjectionPlacementUpdateCoordinator.update(reason, forceLog)
            },
            submitNativeStereoOffset = ::nativeUpdateCameraHwbProjectionStereoOffsetUv,
            submitNativeTargetScale = ::nativeUpdateCameraHwbProjectionTargetScale,
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionCarrierStateCoordinator:
      SpatialCameraHwbProjectionCarrierStateCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionCarrierStateCoordinator(
        SpatialCameraHwbProjectionCarrierStateBindings(
            resolveCarrierMode = {
              val rawToken =
                  activityReadOptionalStringIntentExtra(
                      intent,
                      CAMERA_HWB_PROJECTION_CARRIER_EXTRA,
                  ) ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_CARRIER_PROPERTY)
              CameraHwbProjectionModule.carrierModeForToken(rawToken, spatialVirtualRoomEnabled())
            },
            virtualRoomEnabled = ::spatialVirtualRoomEnabled,
            carrierTransportFromIntent = {
              intent?.hasExtra(CAMERA_HWB_PROJECTION_CARRIER_EXTRA) == true
            },
            routeActive = { cameraHwbProjectionLaunchCoordinator.started },
            secondaryToggleEnabled = { false },
            projectionEntityPresent = { cameraHwbProjectionEntity != null },
            resetPlacementMarkerCadence = {
              cameraHwbProjectionPlacementUpdateCoordinator.resetMarkerCadence()
            },
            updatePlacement = { reason, forceLog ->
              cameraHwbProjectionPlacementUpdateCoordinator.update(reason, forceLog)
            },
            nativeState = {
              SpatialCameraHwbProjectionCarrierNativeState(
                  receiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded
              )
            },
            privateLayerOverride = { privateLayerControlCoordinator.layerOverride },
            reapplyPrivateLayerOverride = ::nativeUpdatePrivateLayerOverride,
            marker = ::marker,
        )
    )
  }
  private val cameraHwbProjectionGeometryCoordinator:
      SpatialCameraHwbProjectionGeometryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionGeometryCoordinator(
        SpatialCameraHwbProjectionGeometryBindings(
            scene = scene,
            carrierState = cameraHwbProjectionCarrierStateCoordinator,
            tuning = cameraHwbProjectionTuningCoordinator,
            virtualRoomEnabled = ::spatialVirtualRoomEnabled,
            projectionWidthMeters =
                surfaceParticleProjectionGeometryCoordinator::projectionWidthMeters,
            projectionHeightMeters =
                surfaceParticleProjectionGeometryCoordinator::projectionHeightMeters,
            legacyLauncherPanelSuppressed = ::legacyLauncherPanelSuppressedForCameraStack,
            privateLayerPanelZ = { privateLayerPanelPlacement.zMeters },
        )
    )
  }
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
        SpatialHandBillboardFlockFeature(
            this,
            ::marker,
            { store.snapshot().surfaceTargetId },
            { SpatialNativeInteropProbe.capture(scene) },
        ),
        SpatialOpenXrHandAlignmentFeature(::marker) {
          SpatialNativeInteropProbe.capture(scene)
        },
        SpatialHandCaptureRecorderFeature(this, ::marker) {
          SpatialNativeInteropProbe.capture(scene)
        },
        SpatialControllerInputLateFeature(controllerPollingCoordinator::pollSpatialInput),
    ) + SpatialPrivateFeatureLoader.load(::marker, this) + listOf(
        ComposeFeature(),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    nativeInteropCoordinator.loadReceiptLibrary()
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
            "spatialOpenXrHandAlignment=spatial-openxr-hand-alignment " +
            "spatialOpenXrHandAlignmentEnabledProperty=debug.rustyquest.spatial.hand_alignment.enabled " +
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
    surfaceParticleLifecycleDiagnosticsCoordinator.schedule("activity-created")
    validationWorkflowCoordinator.dispatchIfRequested(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    suppressParticleLayerIfCameraProjectionRequested("new-intent")
    deactivateLegacyWorkflowPanelsForCameraStack("new-intent")
    deactivatePanelShellIfRequested("new-intent")
    validationWorkflowCoordinator.dispatchIfRequested(intent)
    runSpatialStagedAssetIfRequested(intent, "new-intent")
    runSpatialVirtualRoomIfRequested("new-intent")
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (androidControllerEventRouter.dispatchKeyEvent(event)) {
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
    controllerInputRouteCoordinator.ensureEnabled("scene-ready", forceLog = true)
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
                      surfaceParticleProjectionGeometryCoordinator.surfacePanelDimensions(),
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
          val particleAdapterDecision = particleAdapterActivationDecision()
          if (!particleAdapterDecision.applied) {
            marker(
                SpatialSurfaceParticleRouteModule.particleAdapterActivationMarker(
                    particleAdapterDecision
                )
            )
          }
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
    nativeInteropCoordinator.logProbe(phase = "scene-ready", probeSurface = false)
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
            placementMarkerFields =
                surfaceParticleProjectionGeometryCoordinator.placementMarkerFields(),
            stereoMarkerFields = particleLayerStereoMarkerFields(),
        )
    )
    surfaceParticleLifecycleDiagnosticsCoordinator.schedule("scene-ready")
    spatialVideoProjectionProbeCoordinator.runIfRequested("scene-ready")
    cameraHwbProjectionLaunchCoordinator.runIfRequested("scene-ready")
  }

  override fun onVRReady() {
    super.onVRReady()
    controllerInputRouteCoordinator.ensureEnabled("vr-ready", forceLog = true)
    updateWorkflowPanelHeadlockFromViewer(reason = "vr-ready", forceLog = true)
    updateParticleLayerProjectionFromViewer(reason = "vr-ready", forceLog = true)
    nativeInteropCoordinator.logProbe(phase = "vr-ready", probeSurface = true)
    externalSwapchainProbeCoordinator.runIfRequested("vr-ready")
    sdkQuadSurfaceProbeCoordinator.runIfRequested("vr-ready")
    sdkQuadVulkanProbeCoordinator.runIfRequested("vr-ready")
    sdkQuadStereoAlphaProbeCoordinator.runIfRequested("vr-ready")
    panelSurfaceMatrixProbeCoordinator.runIfRequested("vr-ready")
    spatialVideoProjectionProbeCoordinator.runIfRequested("vr-ready")
    cameraHwbProjectionLaunchCoordinator.runIfRequested("vr-ready")
    cameraHwbProbeCoordinator.runIfRequested("vr-ready")
  }

  override fun onSceneTick() {
    super.onSceneTick()
    updateWorkflowPanelHeadlockFromViewer(reason = "scene-tick", forceLog = false)
    updateParticleLayerProjectionFromViewer(reason = "scene-tick", forceLog = false)
    cameraHwbProjectionPlacementUpdateCoordinator.update("scene-tick", false)
    controllerInputRouteCoordinator.ensureEnabled("scene-tick", forceLog = false)
    controllerPollingCoordinator.pollNativeInput()
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (androidControllerEventRouter.dispatchMotionButtonEvent(event)) {
      return true
    }
    if (handleSpatialJoystickMotion(event, "android-dispatch-generic-motion")) {
      return true
    }
    return super.dispatchGenericMotionEvent(event)
  }

  override fun onDestroy() {
    spatialPassthroughLutCoordinator.stop("activity-destroy")
    if (nativeInteropCoordinator.receiptLibraryLoaded) {
      runCatching { nativeStopSpatialControllerActions() }
      cameraHwbProjectionDepthPrerequisiteCoordinator.stop()
      runCatching { nativeStopSdkQuadVulkanProbe() }
      runCatching { nativeStopCameraHwbProbe() }
      runCatching { nativeStopSpatialVideoProjectionProbe() }
    }
    spatialVideoProjectionRuntimeCoordinator.stop("activity-destroy")
    cameraHwbProjectionPanelCarrierCoordinator.cleanup("activity-destroy")
    cleanupSdkQuadSurfaceProbe("activity-destroy")
    externalSwapchainProbeCoordinator.destroy("activity-destroy")
    polarSensorPanel?.stop()
    polarSensorPanel = null
    stagedAssetModule.destroy("activity-destroy")
    destroySpatialVirtualRoom("activity-destroy")
    surfaceParticleRuntimeCoordinator.stop()
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
                    particleControls = surfaceParticleParameterCoordinator.controls,
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
                      surfaceParticleParameterCoordinator.updateControls(controls)
                    },
                    applyDriverProfile = { block, source ->
                      surfaceParticleParameterCoordinator.applyDriverProfile(block, source)
                    },
                    setQuestionnaireDueReopensPanel = { enabled, source ->
                      setQuestionnaireDueReopensPanel(enabled, source)
                    },
                ),
            privateLayer =
                SpatialPrivateLayerPanelRegistrationBindings(
                    layerOverride = { privateLayerControlCoordinator.layerOverride },
                    projectionPanelEnabled = { projectionPanelVisibilityCoordinator.enabled },
                    projectionScale = cameraHwbProjectionTuningCoordinator.targetScale(),
                    projectionScaleRange =
                        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE..CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
                    depthLayerPolicy = privateLayerControlCoordinator.depthLayerPolicy,
                    depthAlignment = privateLayerControlCoordinator.depthAlignment,
                    setLayerOverride = privateLayerControlCoordinator::updateLayerOverride,
                    setProjectionPanelEnabled =
                        projectionPanelVisibilityCoordinator::setEnabled,
                    updateProjectionScale = { scale, source ->
                      cameraHwbProjectionTuningCoordinator.updateTargetScaleFromPanel(
                          scale,
                          source,
                      )
                    },
                    updateDepthLayerPolicy =
                        privateLayerControlCoordinator::updateDepthLayerPolicy,
                    updateDepthAlignment = privateLayerControlCoordinator::updateDepthAlignment,
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
                          privateLayerPanelLayerCoordinator.update(
                              "panel-setup",
                              forceLog = false,
                          )
                      marker(
                          SpatialPanelPlacementModule.privateLayerPanelLayerReadyMarker(
                              layerUpdateStatus = layerUpdateStatus,
                              cameraVideoProjectionLayerZIndex =
                                  cameraHwbProjectionCarrierStateCoordinator.zIndexForPlacement(
                                      cameraHwbProjectionCarrierStateCoordinator.placementMode()
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
            cameraHwbProjectionPanelCarrierCoordinator.videoPanelBindings()
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
    surfaceParticlePresentationStateCoordinator.recordPanelRegistrations(
        registrationCount = panels.size,
        particlePanelRegistrationId =
            if (nativeSurfaceParticleLayerEnabled() &&
                !particleLayerManualCustomMeshCarrierEnabled()) {
              "spatial_camera_surface_panel"
            } else {
              "manual-scene-object"
            },
        carrier = particleLayerCarrierToken(),
        nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
    )
    surfaceParticleLifecycleDiagnosticsCoordinator.schedule("register-panels")
    return panels
  }

  private fun particleLayerVideoPanelBindings(): SpatialSurfaceParticleVideoPanelBindings =
      SpatialSurfaceParticleVideoPanelBindings(
          adoptSurface = { surface ->
            surfaceParticlePresentationStateCoordinator.recordSurfaceConsumer(surface.isValid)
          },
          settings = { _ -> particleLayerMediaSettings() },
          carrier = ::particleLayerCarrierToken,
          placementMarkerFields =
              surfaceParticleProjectionGeometryCoordinator::placementMarkerFields,
          stereoMarkerFields = ::particleLayerStereoMarkerFields,
          startLayer = ::startNativeSurfaceParticleLayer,
          adoptPanel = surfaceParticlePresentationStateCoordinator::adoptPanel,
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
    cameraHwbProjectionCarrierStateCoordinator.refreshCarrierMode()
    applyPanelPlacement()
    spatialVirtualRoomModule.runIfRequested(
        reason = reason,
        projectionState = spatialVirtualRoomProjectionState(),
        onLoaded = {
          runSpatialStagedAssetIfRequested(intent, "virtual-room-loaded")
          spatialVideoProjectionProbeCoordinator.runIfRequested("virtual-room-loaded")
          cameraHwbProjectionLaunchCoordinator.runIfRequested("virtual-room-loaded")
        },
    )
  }

  private fun configureSpatialVirtualRoomScene(reason: String) {
    if (!spatialVirtualRoomModule.shouldConfigureScene()) {
      return
    }
    cameraHwbProjectionCarrierStateCoordinator.refreshCarrierMode()
    spatialVirtualRoomModule.configureScene(reason, spatialVirtualRoomProjectionState())
  }

  private fun spatialVirtualRoomProjectionState(): SpatialVirtualRoomProjectionState =
      SpatialVirtualRoomProjectionState(
          placementModeToken =
              cameraHwbProjectionCarrierStateCoordinator.placementMode().markerToken,
          carrierToken = cameraHwbProjectionCarrierStateCoordinator.carrierToken(),
          carrierProperty = CAMERA_HWB_PROJECTION_CARRIER_PROPERTY,
          roomRenderOrderToken =
              cameraHwbProjectionCarrierStateCoordinator.roomRenderOrderToken(),
      )

  private fun destroySpatialVirtualRoom(reason: String) = spatialVirtualRoomModule.destroy(reason)

  private fun spatialVirtualRoomEnabled(): Boolean = spatialVirtualRoomModule.enabled()

  private fun spatialVirtualRoomLoaded(): Boolean = spatialVirtualRoomModule.loaded

  private fun spatialSkyboxEnabled(): Boolean = spatialVirtualRoomModule.skyboxEnabled()

  private fun runCameraHwbProjectionProbe(
      readerMaxImages: Int,
      videoSettings: SpatialVideoProjectionSettings,
  ) {
    cleanupSdkQuadSurfaceProbe("camera-hwb-projection-pre-run")
    cameraHwbProjectionPanelCarrierCoordinator.cleanup("camera-hwb-projection-pre-run")
    spatialVideoProjectionRuntimeCoordinator.adoptSettings(videoSettings)
    cameraHwbProjectionEntity = null
    cameraHwbProjectionCarrierStateCoordinator.resetForLaunch()
    cameraHwbProjectionTuningCoordinator.resetForLaunch()
    privateLayerControlCoordinator.initializeDepthLayerPolicy(
        initialPrivateLayerDepthLayerPolicy()
    )
    cameraHwbProjectionPlacementUpdateCoordinator.resetMarkerCadence()
    suppressParticleLayerForCameraStack("camera-hwb-projection-probe")
    panelPlacementStateCoordinator.setPrivateLayerVisibleFlag(false)
    setWorkflowPanelVisible(false, focus = false, source = "camera-hwb-projection-probe")
    if (cameraHwbProjectionCarrierStateCoordinator.scenePanelCarrierEnabled()) {
      cameraHwbProjectionPanelCarrierCoordinator.run(readerMaxImages, videoSettings)
      return
    }
    cameraHwbProjectionRawCarrierCoordinator.run(readerMaxImages, videoSettings)
  }

  private fun currentCameraHwbProjectionLaunchRequest(
      videoSettings: SpatialVideoProjectionSettings
  ): SpatialCameraHwbProjectionLaunchRequest =
      SpatialCameraHwbProjectionLaunchRequest(
          readerMaxImages =
              activityReadIntSystemProperty(
                  CAMERA_HWB_PROJECTION_READER_MAX_IMAGES_PROPERTY,
                  CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES,
                  CAMERA_HWB_PROJECTION_MIN_READER_MAX_IMAGES,
                  CAMERA_HWB_PROJECTION_MAX_READER_MAX_IMAGES,
              ),
          videoSettings = videoSettings,
      )

  private fun stopCameraHwbProjectionPanel(reason: String): SpatialProjectionPanelStopReceipt {
    val scenePanelCarrier = cameraHwbProjectionCarrierStateCoordinator.scenePanelCarrierEnabled()
    val panelCleanupStatus =
        if (scenePanelCarrier) {
          cameraHwbProjectionPanelCarrierCoordinator.cleanup(reason)
        } else {
          "not-active"
        }
    val nativeProjectionStopped =
        if (scenePanelCarrier) {
          panelCleanupStatus == "destroyed"
        } else {
          runCatching {
                if (nativeInteropCoordinator.receiptLibraryLoaded) {
                  nativeStopCameraHwbProbe()
                }
                true
              }
              .getOrDefault(false)
        }
    spatialVideoProjectionRuntimeCoordinator.stop(reason)
    cameraHwbProjectionDepthPrerequisiteCoordinator.stop()
    val rawCleanupStatus = sdkQuadResourceCoordinator.cleanup(reason)
    cameraHwbProjectionEntity = null
    val carrierCleanupStatus =
        "panel-$panelCleanupStatus-raw-$rawCleanupStatus"
    return SpatialProjectionPanelStopReceipt(
        nativeProjectionStopped = nativeProjectionStopped,
        videoProjectionStopped =
            !spatialVideoProjectionRuntimeCoordinator.started &&
                !spatialVideoProjectionRuntimeCoordinator.settings.enabled,
        carrierCleanupStatus = carrierCleanupStatus,
    )
  }

  private fun cleanupSdkQuadSurfaceProbe(reason: String): String {
    spatialVideoProjectionRuntimeCoordinator.stop("sdk-quad-surface-$reason")
    cameraHwbProjectionDepthPrerequisiteCoordinator.stop()
    return sdkQuadResourceCoordinator.cleanup(reason)
  }


  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun createManualSurfaceParticleLayerPanel(reason: String): Entity? {
    val targetDistanceMeters =
        surfaceParticleProjectionGeometryCoordinator.currentTargetDistanceMeters()
    val surfaceOverscanScale =
        surfaceParticleProjectionGeometryCoordinator.currentSurfaceOverscanScale()
    val surfaceWidthMeters =
        surfaceParticleProjectionGeometryCoordinator.surfaceWidthMeters(
            targetDistanceMeters,
            surfaceOverscanScale,
        )
    val surfaceHeightMeters =
        surfaceParticleProjectionGeometryCoordinator.surfaceHeightMeters(
            targetDistanceMeters,
            surfaceOverscanScale,
        )
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
    particleLayerManualPanelSurface = readyCarrier.surface
    surfaceParticlePresentationStateCoordinator.adoptManualCarrier(
        panel = readyCarrier.panelSceneObject,
        surfaceValid = readyCarrier.surface.isValid,
    )
    val layerUpdateStatus = updateParticleLayerPanelLayer("manual-custom-mesh-created", false)
    marker(
        SpatialSurfaceParticlePanelCarrierModule.manualPanelCarrierReadyMarker(
            reason = reason,
            carrier = particleLayerCarrierToken(),
            surfaceValid = readyCarrier.surface.isValid,
            layerUpdateStatus = layerUpdateStatus,
            placementMarkerFields =
                surfaceParticleProjectionGeometryCoordinator.placementMarkerFields(),
            stereoMarkerFields = particleLayerStereoMarkerFields(),
        )
    )
    startNativeSurfaceParticleLayer(readyCarrier.surface)
    return readyCarrier.entity
  }

  private fun startNativeSurfaceParticleLayer(surface: AndroidSurface) {
    val activationInput = particleAdapterRuntimeInput()
    surfaceParticleRuntimeCoordinator.start(
        SpatialSurfaceParticleStartRequest(
            surfaceValid = { surface.isValid },
            captureOpenXrProbe = { SpatialNativeInteropProbe.capture(scene) },
            startNative = { openXrProbe ->
              nativeStartSurfaceParticleLayer(
                  surface,
                  PARTICLE_LAYER_WIDTH_PX,
                  PARTICLE_LAYER_HEIGHT_PX,
                  PARTICLE_LAYER_PARTICLE_COUNT,
                  PARTICLE_LAYER_FRAME_COUNT,
                  openXrProbe.openXrInstanceHandle,
                  openXrProbe.openXrSessionHandle,
                  openXrProbe.openXrGetInstanceProcAddrHandle,
                  activationInput.enabled,
                  activationInput.profileId,
                  activationInput.projectId,
                  activationInput.featureId,
                  activationInput.lockRevision,
                  activationInput.lockSha256,
              )
            },
            carrier = ::particleLayerCarrierToken,
            placementMarkerFields =
                surfaceParticleProjectionGeometryCoordinator::placementMarkerFields,
            stereoMarkerFields = ::particleLayerStereoMarkerFields,
            submitParameters = { surfaceParticleParameterCoordinator.submit(source = "start") },
        )
    )
  }

  private fun resolveSurfaceParticleAliasControl(intent: Intent, source: String) {
    val parameterId =
        intent
            .getStringExtra(SpatialValidationWorkflowCoordinator.EXTRA_PARTICLE_ALIAS_PARAMETER_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: ""
    val requestedValue =
        intent.getFloatExtra(SpatialValidationWorkflowCoordinator.EXTRA_PARTICLE_ALIAS_VALUE, 0.0f)
    val activationProfile =
        intent
            .getStringExtra(
                SpatialValidationWorkflowCoordinator
                    .EXTRA_PARTICLE_ALIAS_VISUAL_DRIVER_ACTIVATION_PROFILE
            )
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "default"
    surfaceParticleParameterCoordinator.resolveAlias(
        source,
        parameterId,
        requestedValue,
        activationProfile,
    )
  }

  private fun suppressParticleLayerForCameraStack(source: String) {
    particleLayerEntity?.setComponent(Visible(false))
    panelLauncherEntity?.setComponent(Visible(false))
    surfaceParticleRuntimeCoordinator.suppressForCameraStack(source)
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
          spatialVideoProjectionRuntimeCoordinator.resolveSettings(intent).active

  private fun deactivateLegacyWorkflowPanelsForCameraStack(source: String) {
    if (!cameraStackOrRoomRequested()) {
      return
    }
    surfaceParticleRuntimeCoordinator.suppressStartsForCameraStack()
    panelPlacementStateCoordinator.hideAllPanels()
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
    panelPlacementStateCoordinator.hideAllPanels()
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
                cameraStackSuppressesParticles =
                    surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles,
                nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
                privateSpatialEcsParticleRendererEnabled = privateSpatialEcsParticleRendererEnabled(),
                nativeSurfaceParticleLayerSuppressedByPrivateRenderer =
                    nativeSurfaceParticleLayerSuppressedByPrivateRenderer(),
            )
        )
    )
  }

  private fun adjustPanelPlacement(
      deltaX: Float,
      deltaY: Float,
      deltaZ: Float,
      deltaScale: Float,
  ): PanelPlacement {
    panelPlacementStateCoordinator.adjustWorkflowPlacement(deltaX, deltaY, deltaZ, deltaScale)
    applyPanelPlacement()
    panelPersistenceCoordinator.persistHeadlockTuning("compose-placement-buttons")
    marker(
        SpatialPanelPlacementModule.workflowPlacementUpdatedMarker(
            panelMode = panelStateToken(),
            headlockMarkerFields = panelHeadlockMarkerFields(),
        )
    )
    return panelPlacement
  }

  private fun resizeWorkflowPanel(deltaWidth: Float, deltaHeight: Float): PanelPlacement {
    panelPlacementStateCoordinator.resizeWorkflowPanel(deltaWidth, deltaHeight)
    applyPanelPlacement()
    panelPersistenceCoordinator.persistHeadlockTuning("compose-panel-resize")
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
    panelPlacementStateCoordinator.resetWorkflowPanelPlacement()
    applyPanelPlacement()
    panelPersistenceCoordinator.persistHeadlockTuning("compose-panel-reset")
    panelPersistenceCoordinator.recordPanelState("compose-panel-reset")
    marker(
        SpatialPanelPlacementModule.workflowPlacementResetMarker(
            panelMode = panelStateToken(),
            headlockMarkerFields = panelHeadlockMarkerFields(),
        )
    )
    return panelPlacement
  }

  private fun setPanelHeadlocked(enabled: Boolean, source: String): PanelPlacement {
    panelPlacementStateCoordinator.setWorkflowHeadlocked(enabled)
    applyPanelPlacement()
    panelPersistenceCoordinator.persistHeadlockTuning(source)
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
    panelPlacementStateCoordinator.setWorkflowPanelVisible(visible, focus)
    applyPanelPlacement()
    panelPersistenceCoordinator.recordPanelState(source)
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
    val inputForegroundActive = false
    val inputForegroundDistanceMeters =
        privateLayerPanelPlacement.zMeters.coerceIn(
            PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
            PANEL_HEADLOCK_DISTANCE_MAX_METERS,
        )
    val inputForegroundScale = PRIVATE_LAYER_PANEL_SCALE
    panelPlacementStateCoordinator.setPrivateLayerPanelVisible(
        visible = visible,
        focus = focus,
        inputForegroundDistanceMeters = inputForegroundDistanceMeters,
        inputForegroundScale = inputForegroundScale,
        freeTransform = PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM,
    )
    val privateLayerPanelSeedPose =
        if (visible && focus) {
          privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
        } else {
          null
        }
    applyPanelPlacement(
        updatePrivateLayerPanelTransform =
            visible && focus && !PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM
    )
    privateLayerPanelSeedPose?.let { pose ->
      privateLayerPanelEntity?.setComponent(Transform(pose))
    }
    privateLayerPanelEntity?.setComponent(privateLayerPanelGrabbable(enabled = visible))
    val privateLayerPanelLayerUpdateStatus =
        privateLayerPanelLayerCoordinator.update("private-layer-panel-visibility")
    cameraHwbProjectionPlacementUpdateCoordinator.update(
        "private-layer-panel-visibility",
        true,
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
                    cameraHwbProjectionCarrierStateCoordinator.zIndexForPlacement(
                        cameraHwbProjectionCarrierStateCoordinator.placementMode()
                    ),
                leftStickYPanelDistanceEnabled = currentLeftStickPanelDistanceEnabled(),
                panelOpensInFrontOfCameraVideo =
                    privateLayerPanelPlacement.zMeters < CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS,
                inputForegroundActive = inputForegroundActive,
                inputForegroundDistanceMeters = inputForegroundDistanceMeters,
                inputForegroundScale = inputForegroundScale,
                projectionPanelHittable =
                    cameraHwbProjectionCarrierStateCoordinator.panelHittableToken(),
                projectionPanelInputClearanceActive =
                    cameraHwbProjectionGeometryCoordinator.privatePanelInputClearanceActive(),
                projectionPanelInputBehindPrivateLayerPanel =
                    cameraHwbProjectionGeometryCoordinator.inputCarrierBehindPrivatePanel(),
                projectionPanelInputTargetDistanceMeters =
                    cameraHwbProjectionGeometryCoordinator.targetDistanceMeters(),
                privateLayerOverride = privateLayerControlCoordinator.layerOverride,
                headlockMarkerFields = panelHeadlockMarkerFields(),
            )
        )
    )
    return panelPlacement
  }

  private fun updateParticleLayerPanelLayer(
      reason: String,
      forceLog: Boolean = true,
  ): String {
    val panel =
        surfaceParticlePresentationStateCoordinator.panelSceneObject
            ?: return "panel-scene-object-missing"
    val opacity = surfaceParticleProjectionGeometryCoordinator.currentPanelOpacity()
    return surfaceParticlePanelLayerCoordinator.update(
        SpatialSurfaceParticlePanelLayerUpdateRequest(
            reason = reason,
            forceLog = forceLog,
            opacity = opacity,
            applyLayerChanges = apply@{ configureLayer, updateOpacity, requestedOpacity ->
              val layer = panel.layer ?: return@apply false
              if (configureLayer) {
                layer.setZIndex(PARTICLE_LAYER_Z_INDEX)
                layer.setAlphaBlend(
                    LayerAlphaBlend(
                        BlendFactor.SOURCE_ALPHA,
                        BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                        BlendFactor.ONE,
                        BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                    )
                )
              }
              if (updateOpacity) {
                layer.setColorScaleBias(
                    Vector4(1.0f, 1.0f, 1.0f, requestedOpacity),
                    Vector4(0.0f),
                )
              }
              true
            },
        )
    )
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
          cameraStackSuppressesParticles =
              surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles,
          nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
      )

  private fun launcherPanelVisibleForPanelMode(): Boolean =
      SpatialPanelPlacementModule.launcherPanelVisibleForPanelMode(
          panelShellVisible = panelShellVisible(),
          panelLauncherVisible = panelLauncherVisible(),
          workflowPanelVisible = panelPlacement.visible,
          privateLayerPanelVisible = privateLayerPanelVisible,
          cameraStackSuppressesParticles =
              surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles,
          spatialVirtualRoomEnabled = spatialVirtualRoomEnabled(),
      )

  private fun legacyLauncherPanelSuppressedForCameraStack(): Boolean =
      SpatialPanelPlacementModule.legacyLauncherPanelSuppressedForCameraStack(
          surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles,
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
    val previous = privateLayerPanelPlacement
    panelPlacementStateCoordinator.replacePrivateLayerPlacement(
        panelPoseCoordinator.privateLayerPlacementFromEntity(
            panelPose = pose,
            viewerPose = viewerPose,
            currentPlacement = privateLayerPanelPlacement,
            privateLayerVisible = privateLayerPanelVisible,
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
    if (
        !panelInteractionStateCoordinator.shouldEmitPrivateLayerGrabbableMarker(
            grabbed = grabbed,
            nowMs = now,
            forceLog = forceLog,
        )
    ) {
      return
    }
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
    return panelPoseCoordinator.headlockedWorkflowPose(
        viewerPose = viewerPose,
        placement = panelPlacement,
        yawDegrees = surfaceParticleProjectionGeometryCoordinator.currentViewYawDegrees(),
    )
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun privateLayerPanelPoseFromViewer(): Pose? {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: return null
    val result =
        panelPoseCoordinator.privateLayerPoseFromViewer(
            viewerPose = viewerPose,
            currentPlacement = privateLayerPanelPlacement,
        )
    if (result.placement != privateLayerPanelPlacement) {
      panelPlacementStateCoordinator.replacePrivateLayerPlacement(result.placement)
    }
    return result.pose
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
    if (
        !panelInteractionStateCoordinator.shouldEmitHeadlockPoseMarker(
            nowMs = now,
            forceLog = forceLog,
            anyPanelVisible = panelPlacement.visible || privateLayerPanelVisible,
        )
    ) {
      return
    }
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
      panelPlacementStateCoordinator.replaceWorkflowPlacement(updated)
      applyPanelPlacement()
      panelPersistenceCoordinator.persistHeadlockTuning("runtime-hotload-android-property")
    }
    val token = panelHeadlockMarkerFields()
    if (panelInteractionStateCoordinator.consumeHeadlockHotloadToken(token)) {
      marker(
          SpatialPanelPlacementModule.headlockHotloadUpdatedMarker(
              reason = reason,
              headlockMarkerFields = token,
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
    surfaceParticleProjectionUpdateCoordinator.update(
        SpatialSurfaceParticleProjectionUpdateRequest(
            reason = reason,
            forceLog = forceLog,
            hideProjectionEntity = { entity.setComponent(Visible(false)) },
            applyProjectionEntity = { update ->
              entity.setComponent(Transform(update.pose))
              if (update.applySurfaceGeometry) {
                entity.setComponent(
                    PanelDimensions(
                        Vector2(update.surfaceWidthMeters, update.surfaceHeightMeters)
                    )
                )
              }
              entity.setComponent(Visible(update.visible))
            },
        )
    )
  }

  private fun applyRemoteParticleLayerTargetDistance(intent: Intent, source: String) {
    val requested =
        intent.getFloatExtra(
            SpatialValidationWorkflowCoordinator.EXTRA_PARTICLE_LAYER_TARGET_DISTANCE_METERS,
            surfaceParticleProjectionGeometryCoordinator.currentTargetDistanceMeters(),
        )
    surfaceParticleProjectionGeometryCoordinator.applyTargetDistance(requested, source)
  }

  private fun applyRemoteParticleLayerViewYaw(intent: Intent, source: String) {
    val requested =
        intent.getFloatExtra(
            SpatialValidationWorkflowCoordinator.EXTRA_PARTICLE_LAYER_VIEW_YAW_DEGREES,
            surfaceParticleProjectionGeometryCoordinator.currentViewYawDegrees(),
        )
    surfaceParticleProjectionGeometryCoordinator.applyViewYaw(requested, source)
  }

  private fun cameraHwbProjectionSyntheticVisualProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_SYNTHETIC_VISUAL_PROPERTY) == true

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

  private fun initialPrivateLayerDepthLayerPolicy(): Int =
      PrivateLayerControls.depthLayerPolicyForToken(
          activityReadSystemProperty(CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY)
      ) ?: PrivateLayerControls.defaultDepthLayerPolicy

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
            cameraTargetDistanceMeters = cameraHwbProjectionGeometryCoordinator.targetDistanceMeters(),
            projectionInputClearanceActive =
                cameraHwbProjectionGeometryCoordinator.privatePanelInputClearanceActive(),
            projectionInputCarrierBehindPrivatePanel =
                cameraHwbProjectionGeometryCoordinator.inputCarrierBehindPrivatePanel(),
            cameraProjectionLayerZIndex =
                cameraHwbProjectionCarrierStateCoordinator.zIndexForPlacement(
                    cameraHwbProjectionCarrierStateCoordinator.placementMode()
                ),
        )
    )
  }

  private fun panelHeadlockPropertyMarkerFields(): String =
      SpatialPanelPlacementModule.headlockPropertyMarkerFields()

  private fun handleSpatialJoystickMotion(event: MotionEvent, inputSource: String): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE || !isJoystickEvent(event)) {
      return false
    }

    return panelJoystickArbitrationCoordinator.handle(
        axes =
            SpatialPanelJoystickAxes(
                leftX = joystickAxis(event, MotionEvent.AXIS_X),
                leftY = joystickAxis(event, MotionEvent.AXIS_Y),
                rightX = joystickAxis(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z),
                rightY = joystickAxis(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ),
            ),
        inputSource = inputSource,
    )
  }

  private fun applyPanelHeadlockJoystickAxes(
      axes: SpatialPanelJoystickAxes,
      inputSource: String,
  ): Boolean {
    return panelDistanceActuationCoordinator.apply(
        leftY = axes.leftY,
        inputSource = inputSource,
        controllerJoystickMapping = currentLeftStickPanelDistanceMapping(),
        detail =
            "leftStick=${activityMarkerFloat(axes.leftX)};${activityMarkerFloat(axes.leftY)} " +
                "rightStick=${activityMarkerFloat(axes.rightX)};${activityMarkerFloat(axes.rightY)} " +
                "rightStickXIgnored=true rightStickYPanelDistanceDisabled=true " +
                "rightStickXPanelScaleDisabled=true",
    )
  }

  private fun openWorkflowPanelFromController(inputSource: String, detail: String): Boolean {
    if (!SpatialControllerRoutingModule.isRightPrimaryPanelToggleSource(inputSource)) return false
    val opensPrivateLayerPanel =
        surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles ||
            cameraHwbProjectionLaunchCoordinator.started ||
            spatialVideoProjectionRuntimeCoordinator.started
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
          particleAdapterActivationDecision(),
          privateSpatialEcsParticleRendererEnabled(),
      )

  private fun particleAdapterRuntimeInput(): SpatialAdapterRuntimeInput =
      SpatialAdapterRuntimeInput(
          enabled =
              activityReadOptionalBooleanSystemProperty(
                  NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY
              ) == true,
          profileId = activityReadSystemProperty(PARTICLE_ADAPTER_PROFILE_ID_PROPERTY),
          projectId = activityReadSystemProperty(PARTICLE_ADAPTER_PROJECT_ID_PROPERTY),
          featureId = activityReadSystemProperty(PARTICLE_ADAPTER_FEATURE_ID_PROPERTY),
          lockRevision =
              activityReadSystemProperty(PARTICLE_ADAPTER_LOCK_REVISION_PROPERTY).toLongOrNull()
                  ?: 0L,
          lockSha256 = activityReadSystemProperty(PARTICLE_ADAPTER_LOCK_SHA256_PROPERTY),
      )

  private fun particleAdapterActivationDecision(): SpatialAdapterLockDecision =
      SpatialSurfaceParticleRouteModule.particleAdapterActivationDecision(
          particleAdapterRuntimeInput()
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

  private external fun nativeUpdateSpatialNativePassthroughEdgeStyle(enabled: Boolean): Long

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
      runtimeEnabled: Boolean,
      runtimeProfileId: String,
      runtimeProjectId: String,
      runtimeFeatureId: String,
      runtimeLockRevision: Long,
      runtimeLockSha256: String,
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

  private fun surfaceParticleLifecycleDiagnosticSnapshot():
      SpatialSurfaceParticleLifecycleDiagnosticSnapshot {
    val probe =
        runCatching { SpatialNativeInteropProbe.capture(scene) }
            .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }
    val snapshot = runCatching { store.snapshot() }.getOrNull()
    val presentationSnapshot = surfaceParticlePresentationStateCoordinator.snapshot()
    return SpatialSurfaceParticleLifecycleDiagnosticSnapshot(
        panelRegistrationCount = presentationSnapshot.panelRegistrationCount,
        panelMode = panelStateToken(),
        workflowPanelVisible = panelPlacement.visible,
        launcherPanelVisible = launcherPanelVisibleForPanelMode(),
        legacyLauncherPanelSuppressed = legacyLauncherPanelSuppressedForCameraStack(),
        particleLayerEntityCreated = particleLayerEntity != null,
        particleSurfacePanelReady = presentationSnapshot.panelReady,
        particleSurfaceConsumerCalled = presentationSnapshot.surfaceConsumerCalled,
        particleSurfaceConsumerSurfaceValid = presentationSnapshot.surfaceConsumerSurfaceValid,
        nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
        particleLayerStarted = surfaceParticleRuntimeCoordinator.particleLayerStarted,
        nativeSurfaceStartRequested =
            surfaceParticleRuntimeCoordinator.nativeSurfaceStartRequested,
        lastNativeSurfaceStartMask = surfaceParticleRuntimeCoordinator.lastNativeSurfaceStartMask,
        nativeReceiptLibraryLoaded = nativeInteropCoordinator.receiptLibraryLoaded,
        nativeReceiptLibraryError = nativeInteropCoordinator.receiptLibraryError,
        openXrInstanceHandleNonZero = probe.openXrInstanceHandleNonZero,
        openXrSessionHandleNonZero = probe.openXrSessionHandleNonZero,
        openXrGetInstanceProcAddrHandleNonZero = probe.openXrGetInstanceProcAddrHandleNonZero,
        currentDriverProfileId = snapshot?.currentConditionId ?: "none",
        currentProfileId = snapshot?.currentProfileId ?: "none",
        placementMarkerFields = surfaceParticleProjectionGeometryCoordinator.placementMarkerFields(),
        stereoMarkerFields = particleLayerStereoMarkerFields(),
    )
  }

  companion object {
    private const val TAG = "RQSpatialCameraPanel"
    private const val MARKER_PREFIX = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL"
    private const val ACTIVITY_MARKERS_FILE = "spatial_camera_panel_activity_markers.log"
    private const val PANEL_SHELL_VISIBLE_PROPERTY =
        "debug.rustyquest.spatial.panel_shell.visible"
    private const val PANEL_LAUNCHER_VISIBLE_PROPERTY =
        "debug.rustyquest.spatial.panel_launcher.visible"
  }
}
