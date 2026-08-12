package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
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
import com.meta.spatial.vr.VrInputSystemType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

class SpatialCameraPanelActivity : AppSystemActivity() {
  private val productPolicy = SpatialProductBuildPolicy.current
  private val presentationPolicy = SpatialPresentationBuildPolicy.current
  private var connectionHubSurfaceClient: ConnectionHubSurfaceClient? = null
  private val immersiveVideoRouteResolution: SpatialImmersiveVideoRouteResolution by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialImmersiveVideoPanelCoordinator.resolveFromIntent(this, intent)
      }
  private val immersiveVideoPanelCoordinator: SpatialImmersiveVideoPanelCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialImmersiveVideoPanelCoordinator(
            context = this,
            resolution = immersiveVideoRouteResolution,
            emitMarker = ::marker,
        )
      }
  private val storedProfileAuthority: SpatialCameraPanelProfileLibraryAuthority by
      lazy(LazyThreadSafetyMode.NONE) {
        val internalFile = File(filesDir, SpatialCameraPanelProfileFiles.INTERNAL_FILE_NAME)
        val transferDirectory =
            getExternalFilesDir(null)?.resolve(SpatialCameraPanelProfileFiles.TRANSFER_DIRECTORY)
        val importFile = transferDirectory?.resolve(SpatialCameraPanelProfileFiles.IMPORT_FILE_NAME)
        val exportFile = transferDirectory?.resolve(SpatialCameraPanelProfileFiles.EXPORT_FILE_NAME)
        SpatialCameraPanelProfileLibraryAuthority(
            SpatialCameraPanelProfileLibraryBindings(
                readPayload = { SpatialCameraPanelProfileFiles.read(internalFile) },
                writePayload = { payload ->
                  SpatialCameraPanelProfileFiles.writeAtomically(internalFile, payload)
                },
                readImportBundlePayload = {
                  importFile?.let(SpatialCameraPanelProfileFiles::read)
                },
                clearImportBundlePayload = {
                  importFile == null || !importFile.exists() || importFile.delete()
                },
                writeExportBundlePayload = { payload ->
                  exportFile != null &&
                      SpatialCameraPanelProfileFiles.writeAtomically(exportFile, payload)
                },
            )
        )
      }
  private val privatePanelExtension: SpatialPrivatePanelExtension? by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPrivatePanelExtensionLoader.load(
            SpatialPrivatePanelExtensionHost(
                context = this,
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                wallClockNowMs = System::currentTimeMillis,
                profileLibrary = storedProfileAuthority::snapshot,
                applyProfile = { profile, source ->
                  applyStoredProfileControls(profile.controls, source)
                },
                captureControls = ::captureStoredProfileControls,
                applyControls = ::applyStoredProfileControls,
                recenterVideo = ::recenterImmersiveVideo,
                notifyLaunchOptionsChanged = {
                  contentResolver.notifyChange(
                      SpatialAppLaunchOptionsContract.contentUri(BuildConfig.APPLICATION_ID),
                      null,
                  )
                },
                marker = ::marker,
            )
        )
      }
  private var unavailableLaunchOptionInputLocked = false
  private var privatePanelLaunchStatus = "none"
  private var privatePanelInputPolicyApplied = false
  private var privateLayerPanelEntity: Entity? = null
  private var privateLayerPanelSceneObject: PanelSceneObject? = null
  private var surfaceTargetId: String = SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID
  private val panelPlacementStateCoordinator =
      SpatialPanelPlacementStateCoordinator(
          initialPrivateLayerPlacement = SpatialPanelPlacementModule.initialPrivateLayerPlacement(),
      )
  private val privateLayerPanelPlacement: PanelPlacement
    get() = panelPlacementStateCoordinator.privateLayerPlacement
  private val privateLayerPanelVisible: Boolean
    get() = panelPlacementStateCoordinator.privateLayerVisible
  private val panelPoseCoordinator = SpatialPanelPoseCoordinator()
  private var particleLayerEntity: Entity? = null
  private var particleLayerManualPanelSurface: AndroidSurface? = null
  private val panelInteractionStateCoordinator = SpatialPanelInteractionStateCoordinator()
  private val panelPersistenceCoordinator: SpatialPanelPersistenceCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPanelPersistenceCoordinator(
            SpatialPanelPersistenceBindings(
                outputDirectory = { filesDir },
                headlockSnapshot = {
                  SpatialPanelHeadlockTuningSnapshot(
                      privateLayerPlacement = privateLayerPanelPlacement,
                  )
                },
                panelMode = ::panelStateToken,
                recordPanelForegroundState = { panelMode, source ->
                  marker(
                      "channel=spatial-panel status=foreground-state-recorded " +
                          "panelMode=${activityMarkerToken(panelMode)} " +
                          "source=${activityMarkerToken(source)}"
                  )
                },
                marker = ::marker,
            )
        )
      }
  private val panelDistanceActuationCoordinator: SpatialPanelDistanceActuationCoordinator by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialPanelDistanceActuationCoordinator(
            SpatialPanelDistanceActuationBindings(
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
                      alignment.sampleScaleY,
                      alignment.rollDegrees,
                      if (alignment.metadataAutoAlign) 1 else 0,
                  )
                },
                updateGuideProcessingNative = { processing ->
                  nativeUpdatePrivateLayerGuideProcessing(
                      processing.preblurKernel,
                      processing.preblurInput,
                      processing.postblurKernel,
                      processing.cameraSampling,
                  )
                },
                updateZoneCompositorNative = { configuration ->
                  nativeUpdatePrivateLayerZoneCompositor(
                      configuration.coverageMode,
                      configuration.regionContractVersion,
                      configuration.bufferGeometryMode,
                      configuration.bufferStaticWidthUv,
                      configuration.bufferFillMode,
                      configuration.stretchExtentMode,
                      configuration.stretchSource,
                      configuration.debugMode,
                      configuration.outerTargetMode,
                      configuration.stretchMapping,
                      if (configuration.projectionEffectEdgeGuardEnabled) 1 else 0,
                      configuration.stretchOptionFlags,
                      configuration.edgeInsetUv,
                      configuration.maxInsetUv,
                      configuration.stretchCurve,
                      configuration.processedMix,
                      configuration.innerSignal,
                      configuration.innerWidthUv,
                      configuration.innerCurve,
                      configuration.innerThresholdR,
                      configuration.innerThresholdG,
                      configuration.innerThresholdB,
                      configuration.innerSoftness,
                      configuration.innerStrength,
                      configuration.innerCycleAmplitude,
                      configuration.innerCycleHz,
                      configuration.innerMotionGain,
                      configuration.outerSignal,
                      configuration.outerWidthUv,
                      configuration.outerCurve,
                      configuration.outerThresholdR,
                      configuration.outerThresholdG,
                      configuration.outerThresholdB,
                      configuration.outerSoftness,
                      configuration.outerStrength,
                      configuration.outerCycleAmplitude,
                      configuration.outerCycleHz,
                      configuration.outerMotionGain,
                  )
                  nativeUpdatePrivateLayerZoneChannelDynamics(
                      configuration.innerChannelDynamics.applicationMode,
                      configuration.innerChannelDynamics.sourceChoice,
                      configuration.innerChannelDynamics.regionDriver,
                      configuration.innerChannelDynamics.strengthR,
                      configuration.innerChannelDynamics.strengthG,
                      configuration.innerChannelDynamics.strengthB,
                      configuration.innerChannelDynamics.cycleAmplitudeR,
                      configuration.innerChannelDynamics.cycleAmplitudeG,
                      configuration.innerChannelDynamics.cycleAmplitudeB,
                      configuration.innerChannelDynamics.cycleHzR,
                      configuration.innerChannelDynamics.cycleHzG,
                      configuration.innerChannelDynamics.cycleHzB,
                      configuration.innerChannelDynamics.cyclePhaseR,
                      configuration.innerChannelDynamics.cyclePhaseG,
                      configuration.innerChannelDynamics.cyclePhaseB,
                      configuration.outerChannelDynamics.applicationMode,
                      configuration.outerChannelDynamics.sourceChoice,
                      configuration.outerChannelDynamics.regionDriver,
                      configuration.outerChannelDynamics.strengthR,
                      configuration.outerChannelDynamics.strengthG,
                      configuration.outerChannelDynamics.strengthB,
                      configuration.outerChannelDynamics.cycleAmplitudeR,
                      configuration.outerChannelDynamics.cycleAmplitudeG,
                      configuration.outerChannelDynamics.cycleAmplitudeB,
                      configuration.outerChannelDynamics.cycleHzR,
                      configuration.outerChannelDynamics.cycleHzG,
                      configuration.outerChannelDynamics.cycleHzB,
                      configuration.outerChannelDynamics.cyclePhaseR,
                      configuration.outerChannelDynamics.cyclePhaseG,
                      configuration.outerChannelDynamics.cyclePhaseB,
                  )
                },
                updateRgbChannelTransformNative = { configuration ->
                  nativeUpdateRgbChannelTransform(
                      configuration.mode,
                      configuration.edgeMode,
                      configuration.red.directionTurns,
                      configuration.green.directionTurns,
                      configuration.blue.directionTurns,
                      configuration.red.directionRateHz,
                      configuration.green.directionRateHz,
                      configuration.blue.directionRateHz,
                      configuration.red.displacementStrengthUv,
                      configuration.green.displacementStrengthUv,
                      configuration.blue.displacementStrengthUv,
                      configuration.red.imageScale,
                      configuration.green.imageScale,
                      configuration.blue.imageScale,
                      configuration.red.coverageScale,
                      configuration.green.coverageScale,
                      configuration.blue.coverageScale,
                  )
                },
                updateProjectionSurfaceDisplacementNative = { configuration ->
                  nativeUpdateProjectionSurfaceDisplacement(
                      if (configuration.enabled) 1 else 0,
                      configuration.maxDisplacementMeters,
                      configuration.referenceSurfaceDistanceMeters,
                      configuration.polarity,
                      configuration.edgeTaper,
                  )
                },
                updateProjectionSurfaceFeaturesNative = { tiling, innerAlpha ->
                  nativeUpdateProjectionSurfaceFeatures(
                      if (tiling.enabled) 1 else 0,
                      tiling.topology,
                      tiling.gapNormalized,
                      tiling.depthFlexibility,
                      tiling.scope,
                      if (innerAlpha.enabled) 1 else 0,
                      innerAlpha.driver,
                      innerAlpha.threshold,
                      innerAlpha.softness,
                      innerAlpha.amount,
                      if (innerAlpha.invert) 1 else 0,
                      innerAlpha.stretchPolicy,
                      if (innerAlpha.stretchObeysExactProjectionMask) 1 else 0,
                  )
                },
                marker = ::marker,
            ),
            fixedLayerOverride = presentationPolicy.fixedLayerOverride,
            initialZoneCompositor =
                PrivateLayerZoneCompositorControls.presetForToken(
                    BuildConfig.ZONE_COMPOSITOR_DEFAULT_PRESET
                ),
        )
      }
  private val controlProfileHotloader: SpatialCameraControlProfileHotloader by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialCameraControlProfileHotloader(
            context = this,
            routeActive = {
              cameraHwbProjectionLaunchCoordinator.started ||
                  spatialVideoProjectionRuntimeCoordinator.started
            },
            applyProfile = ::applyControlProfile,
            marker = ::marker,
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
                applyImmersiveVideoSelection = { axes, inputSource ->
                  immersiveVideoSelectionInputCoordinator.handleRightStick(
                      axes.rightX,
                      axes.rightY,
                      inputSource,
                  )
                },
                immersiveVideoSelectionEnabled = ::directImmersiveVideoSelectionEnabled,
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
  private val immersiveVideoSelectionInputCoordinator:
      SpatialImmersiveVideoSelectionInputCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        SpatialImmersiveVideoSelectionInputCoordinator(
            SpatialImmersiveVideoSelectionInputBindings(
                selectionEnabled = ::directImmersiveVideoSelectionEnabled,
                select = { direction, inputSource ->
                  changeImmersiveVideo(
                      direction.action,
                      null,
                      "$inputSource-right-stick-flick",
                  )
                },
                marker = ::marker,
            )
        )
      }
  private val controllerInputRouteSpec =
      SpatialControllerInputRouteSpec(
          enabled = presentationPolicy.appControlInputsEnabled,
          source =
              if (presentationPolicy.appControlInputsEnabled) {
                "spatial-camera-panel-app-spec"
              } else {
                "locked-final-presentation-build"
              },
      )
  private val androidControllerEventRouter by lazy(LazyThreadSafetyMode.NONE) {
    SpatialControllerAndroidEventRouter(
        recenterVideo = ::recenterImmersiveVideo,
        recenterTrigger = { inputSource, detail ->
          surfaceParticleRecenterCoordinator.recenter(
              SpatialSurfaceParticleRecenterRequest(
                  inputSource = inputSource,
                  detail = detail,
                  requireParticleView = true,
              )
          )
        },
        openPrimary = { inputSource, detail ->
          toggleLayerControlPanelFromController(inputSource, detail)
        },
        storeLeftPrimary = { _, _ -> false },
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
            surfaceTargetId = { surfaceTargetId },
            particleLayerVisible = ::particleLayerVisibleForPanelMode,
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
            pollNativeRightButtonA = ::nativePollSpatialControllerRightButtonA,
            pollNativeRightButtonB = ::nativePollSpatialControllerRightButtonB,
            captureSpatialSnapshot = { SpatialControllerSnapshotAdapter.capture(scene) },
            currentLeftStickPanelDistanceMapping = ::currentLeftStickPanelDistanceMapping,
            currentLeftStickPanelDistanceEnabled = ::currentLeftStickPanelDistanceEnabled,
            currentSpatialVrInputSystemToken = ::currentSpatialVrInputSystemToken,
            applyImmersiveVideoSelection = { rightX, rightY, inputSource ->
              immersiveVideoSelectionInputCoordinator.handleRightStick(
                  rightX,
                  rightY,
                  inputSource,
              )
            },
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
            recenterVideo = { inputSource, detail ->
              recenterImmersiveVideo(inputSource, detail)
              Unit
            },
            openPrimary = { inputSource, detail ->
              toggleLayerControlPanelFromController(inputSource, detail)
              Unit
            },
            marker = ::marker,
        )
    )
  }
  private val validationWorkflowCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialValidationWorkflowCoordinator(
        SpatialValidationWorkflowBindings(
            marker = ::marker,
            scheduleParticleLayerLifecycleDiagnostics = { reason ->
              surfaceParticleLifecycleDiagnosticsCoordinator.schedule(
                  reason,
                  explicitRequest = true,
              )
              Unit
            },
            setPrivateLayerPanelVisible = { visible, focus, source ->
              setPrivateLayerPanelVisible(visible, focus, source)
              Unit
            },
            privateLayerPanelVisible = { privateLayerPanelVisible },
            updatePrivateLayerOverride = { layerOverride, source ->
              privateLayerControlCoordinator.updateLayerOverride(layerOverride, source)
              Unit
            },
            currentPrivateLayerZoneCompositor = {
              privateLayerControlCoordinator.zoneCompositor
            },
            updatePrivateLayerZoneCompositor = { configuration, source ->
              privateLayerControlCoordinator.updateZoneCompositor(configuration, source)
              Unit
            },
            updateRgbChannelTransform = { configuration, source ->
              privateLayerControlCoordinator.updateRgbChannelTransform(configuration, source)
              Unit
            },
            updateProjectionSurfaceDisplacement = { configuration, source ->
              privateLayerControlCoordinator.updateProjectionSurfaceDisplacement(
                  configuration,
                  source,
              )
              Unit
            },
            setProjectionPanelEnabled = { enabled, source ->
              setProjectionPanelEnabled(enabled, source)
              Unit
            },
            changeImmersiveVideo = { action, packId, source ->
              changeImmersiveVideo(action, packId, source)
              Unit
            },
            recenterImmersiveVideo = { source, detail ->
              recenterImmersiveVideo(source, detail)
              Unit
            },
            setImmersiveVideoPresentationMode = { mode, source ->
              setImmersiveVideoPresentationMode(mode, source)
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
            selectSurfaceTarget = ::selectSurfaceTarget,
            currentSurfaceTarget = { surfaceTargetId },
            panelStateToken = ::panelStateToken,
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
            startPlayback = { settings, offlinePack ->
              SpatialStereoVideoPlayback.start(
                  this,
                  settings.source,
                  settings.path,
                  offlinePack?.let {
                    OfflineImmersiveMediaExtractorDataSource(it, ::marker)
                  },
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
  private val spatialFragmentProbeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialFragmentProbeCoordinator(
        SpatialFragmentProbeBindings(
            scene = scene,
            poseFromViewer = sdkQuadResourceCoordinator::poseFromViewer,
            marker = ::marker,
        )
    )
  }
  private val spatialStimulusVolumeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialStimulusVolumeCoordinator(
        SpatialStimulusVolumeBindings(
            scene = scene,
            poseFromViewer = sdkQuadResourceCoordinator::poseFromViewer,
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
  private val cameraLatencyDiagnosticModule by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraLatencyDiagnosticModule(
        SpatialCameraLatencyDiagnosticBindings(
            readSystemProperty = ::activityReadSystemProperty,
            applyNative = { settings ->
              if (!nativeInteropCoordinator.receiptLibraryLoaded) {
                marker(
                    "channel=camera-latency-diagnostic status=native-update-skipped " +
                        "reason=receipt-library-unavailable error=" +
                        activityMarkerToken(nativeInteropCoordinator.receiptLibraryError)
                )
                0L
              } else {
                nativeUpdateCameraLatencyDiagnostics(
                    settings.enabled,
                    settings.revision,
                    settings.poseMode.nativeCode,
                    settings.frameWaitMs,
                    settings.summaryIntervalMs,
                    settings.frameLog,
                    settings.presentMode.nativeCode,
                    settings.imageCount.nativeCode,
                    settings.captureFps.nativeCode,
                    settings.cameraSyncMode.nativeCode,
                    settings.captureProcessing.nativeCode,
                    settings.adoptionCadence.nativeCode,
                    settings.stereoPolicy.nativeCode,
                    settings.isolationMode.nativeCode,
                    settings.freezeFrame,
                    settings.reprojectionMode.nativeCode,
                    settings.assumedCaptureAgeMs,
                    settings.reprojectionFovDegrees,
                    settings.reprojectionSourceOverscanPercent,
                    settings.reprojectionGuardBandMode.nativeCode,
                    settings.presentationPoseMode.nativeCode,
                    settings.presentationLeadMs,
                )
              }
            },
            recordViewerPose = { plane, timestampNs ->
              if (!nativeInteropCoordinator.receiptLibraryLoaded) {
                0L
              } else {
                nativeUpdateCameraLatencyViewerPose(
                    timestampNs,
                    plane.viewerPosition.x,
                    plane.viewerPosition.y,
                    plane.viewerPosition.z,
                    plane.right.x,
                    plane.right.y,
                    plane.right.z,
                    plane.up.x,
                    plane.up.y,
                    plane.up.z,
                    plane.forward.x,
                    plane.forward.y,
                    plane.forward.z,
                )
              }
            },
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
              setPrivateLayerPanelVisible(
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

  private var projectionPanelRuntimeEnabled = true

  private val cameraHwbProjectionLaunchCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialCameraHwbProjectionLaunchCoordinator(
        SpatialCameraHwbProjectionLaunchBindings(
            state = {
              SpatialCameraHwbProjectionLaunchState(
                  enabled =
                      projectionPanelRuntimeEnabled &&
                          (presentationPolicy.lockedFinalPresentation ||
                              BuildConfig.CAMERA_PROJECTION_DEFAULT_ENABLED ||
                              activityReadOptionalBooleanSystemProperty(
                                  CAMERA_HWB_PROJECTION_PROBE_PROPERTY
                              ) == true),
                  sceneReady = spatialSceneReady,
                  virtualRoomEnabled = spatialVirtualRoomEnabled(),
                  virtualRoomLoaded = spatialVirtualRoomLoaded(),
              )
            },
            prepareRequest = {
              cameraHwbProjectionCarrierStateCoordinator.refreshCarrierMode()
              currentCameraHwbProjectionLaunchRequest(currentProjectionVideoSettings())
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
            directImmersiveVideoActive = {
              immersiveVideoPanelCoordinator.sessionSnapshot().playbackEnabled
            },
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
            projectionPlane = {
              cameraLatencyDiagnosticModule.projectionPlane(
                  cameraHwbProjectionGeometryCoordinator::planeForPlacement
              )
            },
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
            immersiveVideoCarrierPresentation = { null },
            outputDimensions = cameraHwbProjectionGeometryCoordinator::outputDimensions,
            projectionPlane = {
              cameraLatencyDiagnosticModule.projectionPlane(
                  cameraHwbProjectionGeometryCoordinator::planeForPlacement
              )
            },
            projectionEntity = { cameraHwbProjectionEntity },
            setProjectionEntity = { entity -> cameraHwbProjectionEntity = entity },
            layerZIndex = cameraHwbProjectionCarrierStateCoordinator::zIndexForPlacement,
            carrierToken = cameraHwbProjectionCarrierStateCoordinator::carrierToken,
            panelRegistrationId = cameraHwbProjectionCarrierStateCoordinator::panelRegistrationId,
            projectionMarkerFields = cameraHwbProjectionGeometryCoordinator::markerFields,
            stereoMarkerFields = cameraHwbProjectionGeometryCoordinator::stereoMarkerFields,
            videoSettings = { spatialVideoProjectionRuntimeCoordinator.settings },
            videoProjectionMarkerFields = { settings ->
              spatialVideoProjectionRuntimeCoordinator.markerFields(settings) + " " +
                  "customProjectionCarrierShape=planar-quad " +
                  "immersiveVideoCarrier=separate-spatial-layer"
            },
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
            pollLatencyDiagnostics = cameraLatencyDiagnosticModule::poll,
            routeActive = {
              cameraHwbProjectionLaunchCoordinator.started ||
                  spatialVideoProjectionRuntimeCoordinator.started
            },
            projectionEntity = { cameraHwbProjectionEntity },
            scenePanelCarrierEnabled =
                cameraHwbProjectionCarrierStateCoordinator::scenePanelCarrierEnabled,
            projectionPlane = {
              cameraLatencyDiagnosticModule.projectionPlane(
                  cameraHwbProjectionGeometryCoordinator::planeForPlacement
              )
            },
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
        ),
        fixedTargetScale = presentationPolicy.fixedProjectionScale,
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
            privateLayerPanelZ = { privateLayerPanelPlacement.zMeters },
            immersiveVideoCarrierPresentation = { null },
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
    if (!productPolicy.cameraPanelRoutesEnabled) {
      return super.registerRequiredOpenXRExtensions()
    }
    return (super.registerRequiredOpenXRExtensions() + spatialRequiredOpenXrExtensions())
        .distinct()
  }

  override fun registerFeatures(): List<SpatialFeature> {
    val sharedFeatures =
        listOf<SpatialFeature>(
            SpatialInteractionInputOnlyFeature(this, ::marker),
            SpatialControllerInputLateFeature {
              if (appControllerInputsEnabled()) {
                controllerPollingCoordinator.pollSpatialInput()
              }
            },
            ComposeFeature(),
        )
    if (!productPolicy.cameraPanelRoutesEnabled) {
      return sharedFeatures
    }
    return listOf(
        SpatialInteractionInputOnlyFeature(this, ::marker),
        SpatialAvatarHandVisualFeature(::marker),
        SpatialAvatarHandInvestigationFeature(::marker),
        SpatialHandBillboardFlockFeature(
            this,
            ::marker,
            { surfaceTargetId },
            { SpatialNativeInteropProbe.capture(scene) },
        ),
        SpatialOpenXrHandAlignmentFeature(::marker) {
          SpatialNativeInteropProbe.capture(scene)
        },
        SpatialHandCaptureRecorderFeature(this, ::marker) {
          SpatialNativeInteropProbe.capture(scene)
        },
    ) + SpatialPrivateFeatureLoader.load(::marker, this) + sharedFeatures.drop(1)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handlePrivatePanelLaunchIntent(intent, "activity-create")
    PrivateLayerZoneCompositorPanelBridge.bind(
        initial = privateLayerControlCoordinator.zoneCompositor,
        submit = privateLayerControlCoordinator::updateZoneCompositor,
    )
    controlProfileHotloader.arm()
    if (productPolicy.cameraPanelRoutesEnabled) {
      nativeInteropCoordinator.loadReceiptLibrary()
      if (nativeInteropCoordinator.receiptLibraryLoaded) {
        val cameraReplayCapture = SpatialCameraReplayCaptureModule.resolve(this)
        val cameraReplayCaptureReceipt =
            nativeConfigureCameraReplayCapture(
                cameraReplayCapture.outputDirectory,
                cameraReplayCapture.requestedFrameCount,
                cameraReplayCapture.intervalMs,
            )
        marker(
            "channel=camera-replay-capture status=activity-configured " +
                cameraReplayCapture.markerFields(cameraReplayCaptureReceipt)
        )
      }
      suppressParticleLayerIfCameraProjectionRequested("activity-created")
      deactivateControlPanelForCameraStack("activity-created")
      deactivatePanelShellIfRequested("activity-created")
    }
    marker(
        "channel=activity status=created package=${BuildConfig.APPLICATION_ID} " +
            "sourceNamespace=io.github.mesmerprism.rustyquest.spatial_camera_panel " +
            "highRateJsonPayload=false hand_rendering_expected=false " +
            "controller_rendering_expected=false controllerVisualModelsEnabled=false " +
            "controllerRenderModelManifest=false " +
            "spatialPointerInputExpected=${appControllerInputsEnabled()} " +
            "locomotionSystemRegistered=false teleportLocomotionEnabled=false " +
            "joystickLocomotionEnabled=false gripPanelGrabEnabled=true " +
            "panelRightPrimaryClickEnabled=false " +
            "nativeSurfaceParticleLayerExpected=true " +
            "spatialVrInputSystem=${currentSpatialVrInputSystemToken()} " +
            "spatialVrInputSystemProperty=$SPATIAL_VR_INPUT_SYSTEM_PROPERTY " +
            "spatialShouldConsumeLeftRightInput=${currentSpatialShouldConsumeLeftRightInput()} " +
            "spatialShouldConsumeLeftRightInputProperty=$SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY " +
            "spatialMultimodalInputProperty=$SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY " +
            "nativeSpatialControllerActionsProperty=$NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY " +
            "nativeSpatialControllerActionsDefaultEnabled=$NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED " +
            "spatialControllerOnlyMode=false spatialControllerInputManifest=true " +
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
            "cameraProjectionDefaultEnabled=${BuildConfig.CAMERA_PROJECTION_DEFAULT_ENABLED} " +
            "immersiveVideoDefaultEnabled=${BuildConfig.IMMERSIVE_VIDEO_DEFAULT_ENABLED} " +
            "immersiveVideoDefaultOfflinePackConfigured=${BuildConfig.IMMERSIVE_VIDEO_DEFAULT_OFFLINE_PACK_ID.isNotBlank()} " +
            "zoneCompositorDefaultPreset=${activityMarkerToken(BuildConfig.ZONE_COMPOSITOR_DEFAULT_PRESET)} " +
            "spatialVirtualRoomModule=${SpatialVirtualRoomModule.MODULE_ID} " +
            "spatialVirtualRoomEnabledProperty=${SpatialVirtualRoomModule.ENABLED_PROPERTY} " +
            "spatialVirtualRoomDefaultEnabled=false " +
            "spatialSkyboxModule=${SpatialVirtualRoomModule.SKYBOX_MODULE_ID} " +
            "spatialSkyboxEnabledProperty=${SpatialVirtualRoomModule.SKYBOX_ENABLED_PROPERTY} " +
            "spatialSkyboxModeProperty=${SpatialVirtualRoomModule.SKYBOX_MODE_PROPERTY} " +
            "spatialSkyboxDefaultEnabled=false " +
            "spatialSkyboxDefaultMode=none " +
            "spatialSdk3dAssetHighRateJsonPayload=false " +
            "${productPolicy.markerFields()} " +
            "layerControlPanel=spatial-private-layer-panel " +
            "privatePanelExtensionLoaded=${privatePanelExtension != null} " +
            "appLaunchOptionSchema=${SpatialAppLaunchOptionsContract.SCHEMA} " +
            "appLaunchOptionStatus=${activityMarkerToken(privatePanelLaunchStatus)} " +
            "privatePanelInputLocked=${privatePanelInputLocked()} " +
            "metaHomeSystemEscapeRetained=true " +
            "${presentationPolicy.markerFields()} " +
            "spatialSdkLaneBoundaries=${SpatialSdkLaneBoundaries.summaryToken()}"
    )
    if (productPolicy.cameraPanelRoutesEnabled) {
      runSpatialVirtualRoomIfRequested("activity-created")
      surfaceParticleLifecycleDiagnosticsCoordinator.schedule("activity-created")
      if (!privatePanelInputLocked()) {
        validationWorkflowCoordinator.dispatchIfRequested(intent)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handlePrivatePanelLaunchIntent(intent, "new-intent")
    if (productPolicy.cameraPanelRoutesEnabled) {
      suppressParticleLayerIfCameraProjectionRequested("new-intent")
      deactivateControlPanelForCameraStack("new-intent")
      deactivatePanelShellIfRequested("new-intent")
      if (!privatePanelInputLocked()) {
        validationWorkflowCoordinator.dispatchIfRequested(intent)
      }
      runSpatialStagedAssetIfRequested(intent, "new-intent")
      runSpatialVirtualRoomIfRequested("new-intent")
      if (!privatePanelInputLocked()) {
        controlProfileHotloader.poll(force = true)
      }
    }
  }

  @Deprecated("Android Activity result compatibility route for Spatial SDK AppSystemActivity")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != SHARED_MEDIA_FOLDER_REQUEST_CODE) return
    val treeUri = data?.data
    if (resultCode != Activity.RESULT_OK || treeUri == null) {
      marker(
          "channel=spatial-immersive-video status=shared-media-folder-selection-cancelled"
      )
      return
    }
    runCatching { SharedOfflineImmersiveMediaLibrary.adoptTreeUri(this, treeUri) }
        .onSuccess { snapshot ->
          marker(
              "channel=spatial-immersive-video status=shared-media-folder-adopted " +
                  "persistedReadGrant=true accessible=${snapshot.accessible} " +
                  "packCount=${snapshot.packCount} rawFolderUriExposed=false " +
                  "plaintextFileWritten=false"
          )
          recreate()
        }
        .onFailure { error ->
          marker(
              "channel=spatial-immersive-video status=shared-media-folder-rejected " +
                  "reason=${activityMarkerToken(error.javaClass.simpleName)} " +
                  "rawFolderUriExposed=false failClosed=true"
          )
        }
  }

  @Suppress("DEPRECATION")
  private fun chooseSharedMediaFolder() {
    val chooser =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
          addFlags(
              Intent.FLAG_GRANT_READ_URI_PERMISSION or
                  Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                  Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
          )
          SharedOfflineImmersiveMediaLibrary.persistedTreeUri(this@SpatialCameraPanelActivity)
              ?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
    startActivityForResult(chooser, SHARED_MEDIA_FOLDER_REQUEST_CODE)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (!appControllerInputsEnabled()) {
      return super.dispatchKeyEvent(event)
    }
    if (androidControllerEventRouter.dispatchKeyEvent(event)) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun customStereoProjectionRequested(): Boolean =
      productPolicy.cameraPanelRoutesEnabled &&
          projectionPanelRuntimeEnabled &&
          (presentationPolicy.lockedFinalPresentation ||
              BuildConfig.CAMERA_PROJECTION_DEFAULT_ENABLED ||
              activityReadOptionalBooleanSystemProperty(
                  CAMERA_HWB_PROJECTION_PROBE_PROPERTY
              ) == true)

  private fun directImmersiveVideoPanelRequested(): Boolean =
      immersiveVideoPanelCoordinator.requested

  private fun directImmersiveVideoSelectionEnabled(): Boolean {
    val session = immersiveVideoPanelCoordinator.sessionSnapshot()
    return directImmersiveVideoPanelRequested() && session.available && session.itemCount > 1
  }

  private fun currentProjectionVideoSettings(): SpatialVideoProjectionSettings {
    val base =
        presentationPolicy.videoSettings(
            spatialVideoProjectionRuntimeCoordinator.resolveSettings(intent)
        )
    return immersiveVideoPanelCoordinator.customProjectionSettings(base) ?: base
  }

  private fun changeImmersiveVideo(
      action: String,
      requestedPackId: String?,
      source: String,
  ): SpatialImmersiveVideoSessionSnapshot {
    val selection =
        when (action) {
          "previous" -> immersiveVideoPanelCoordinator.selectPrevious(source)
          "next" -> immersiveVideoPanelCoordinator.selectNext(source)
          "select" ->
              immersiveVideoPanelCoordinator.selectPack(
                  requestedPackId.orEmpty(),
                  source,
              )
          else -> error("unknown_immersive_video_action_$action")
        }
    if (selection.changed &&
        projectionPanelVisibilityCoordinator.enabled &&
        cameraHwbProjectionLaunchCoordinator.started) {
      val replacementSettings =
          SpatialImmersiveVideoSessionPolicy.customProjectionSettings(
              presentationPolicy.videoSettings(
                  spatialVideoProjectionRuntimeCoordinator.resolveSettings(intent)
              ),
              selection.config,
          )
      val replacementPack = selection.config?.offlinePack
      if (replacementSettings != null && replacementPack != null) {
        spatialVideoProjectionRuntimeCoordinator.replaceMediaSource(
            replacementSettings,
            replacementPack,
            "$source-video-selection",
        )
      }
    }
    return selection.snapshot
  }

  private fun recenterImmersiveVideo(inputSource: String, detail: String): Boolean {
    val viewerPose =
        runCatching { scene.getViewerPose() }
            .getOrElse { throwable ->
              marker(
                  "channel=spatial-immersive-video status=recenter-rejected " +
                      "reason=viewer-pose-unavailable source=${activityMarkerToken(inputSource)} " +
                      "detail=${activityMarkerToken(detail)} " +
                      "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                      "activityRestarted=false"
              )
              return false
            }
    return immersiveVideoPanelCoordinator.recenterAtViewer(
        viewerPose,
        inputSource,
        detail,
    )
  }

  private fun setImmersiveVideoPlaybackEnabled(
      enabled: Boolean,
      source: String,
  ): SpatialImmersiveVideoSessionSnapshot =
      immersiveVideoPanelCoordinator.setPlaybackEnabled(enabled, source)

  private fun setImmersiveVideoPresentationMode(
      mode: SpatialImmersiveVideoPresentationMode,
      source: String,
  ): SpatialImmersiveVideoSessionSnapshot {
    return immersiveVideoPanelCoordinator.setPresentationMode(mode, source)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    spatialSceneReady = true
    if (directImmersiveVideoPanelRequested()) {
      marker(immersiveVideoPanelCoordinator.routePolicyMarker())
      val viewerPose = runCatching { scene.getViewerPose() }.getOrElse { Pose() }
      immersiveVideoPanelCoordinator.spawnAtViewer(viewerPose)
      if (customStereoProjectionRequested()) {
        marker(
            "channel=spatial-immersive-video status=layered-carriers-adopted " +
                "directSpatialPanelActive=true customProjectionStackActive=true " +
                "customProjectionCarrierShape=planar-quad cameraMappingPreserved=true " +
                "activityLifecycleExclusive=false"
        )
      }
    } else if (immersiveVideoPanelCoordinator.requested) {
      marker(
          "channel=spatial-immersive-video status=direct-panel-unavailable " +
              "customProjectionCarrierShape=planar-quad cameraMappingPreserved=true"
      )
    }
    if (productPolicy.cameraPanelRoutesEnabled) {
      deactivateControlPanelForCameraStack("scene-ready")
      deactivatePanelShellIfRequested("scene-ready")
      configureSpatialVirtualRoomScene("scene-ready")
      runSpatialStagedAssetIfRequested(intent, "scene-ready")
    }
    if (appControllerInputsEnabled()) {
      controllerInputRouteCoordinator.ensureEnabled("scene-ready", forceLog = true)
    }
    privateLayerPanelEntity =
        if (!productPolicy.cameraPanelRoutesEnabled) {
          null
        } else {
          Entity.createPanelEntity(
              R.id.spatial_private_layer_panel,
              Transform(privateLayerPanelPose()),
              privateLayerPanelDimensions(),
              privateLayerPanelGrabbable(enabled = privateLayerPanelVisible),
              Visible(privateLayerPanelVisible),
          )
        }
    particleLayerEntity =
        if (!productPolicy.cameraPanelRoutesEnabled) {
          null
        } else if (nativeSurfaceParticleLayerEnabled()) {
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
    if (productPolicy.cameraPanelRoutesEnabled) {
      updateLayerControlPanelPoseFromViewer(reason = "scene-ready", forceLog = true)
      updateParticleLayerProjectionFromViewer(reason = "scene-ready", forceLog = true)
      nativeInteropCoordinator.logProbe(phase = "scene-ready", probeSurface = false)
    }
    if (productPolicy.cameraPanelRoutesEnabled) {
      marker(
          "channel=spatial-panel status=layer-control-panel-spawned " +
              "panelRegistrationId=spatial_private_layer_panel " +
              "layerControlPanel=spatial-private-layer-panel " +
              "privateLayerPanelVisible=$privateLayerPanelVisible " +
              "particleLayerVisible=${particleLayerVisibleForPanelMode()} " +
              "visibleComponent=true panelDimensionsComponent=true diagnosticBackdrop=false contrastEnvironment=false " +
              "panelMode=${panelStateToken()} rendererAuthority=native-vulkan-wsi-surface-panel"
      )
    }
    if (productPolicy.cameraPanelRoutesEnabled) {
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
    enforcePrivatePanelInputPolicy()
  }

  override fun onVRReady() {
    super.onVRReady()
    if (directImmersiveVideoPanelRequested()) {
      immersiveVideoPanelCoordinator.resume("vr-ready")
    }
    if (appControllerInputsEnabled()) {
      controllerInputRouteCoordinator.ensureEnabled("vr-ready", forceLog = true)
    }
    if (productPolicy.cameraPanelRoutesEnabled) {
      updateLayerControlPanelPoseFromViewer(reason = "vr-ready", forceLog = true)
      updateParticleLayerProjectionFromViewer(reason = "vr-ready", forceLog = true)
      nativeInteropCoordinator.logProbe(phase = "vr-ready", probeSurface = true)
      externalSwapchainProbeCoordinator.runIfRequested("vr-ready")
      sdkQuadSurfaceProbeCoordinator.runIfRequested("vr-ready")
      sdkQuadVulkanProbeCoordinator.runIfRequested("vr-ready")
      sdkQuadStereoAlphaProbeCoordinator.runIfRequested("vr-ready")
      spatialFragmentProbeCoordinator.runIfRequested("vr-ready")
      spatialStimulusVolumeCoordinator.runIfRequested("vr-ready")
      panelSurfaceMatrixProbeCoordinator.runIfRequested("vr-ready")
      spatialVideoProjectionProbeCoordinator.runIfRequested("vr-ready")
      cameraHwbProjectionLaunchCoordinator.runIfRequested("vr-ready")
      cameraHwbProbeCoordinator.runIfRequested("vr-ready")
    }
  }

  override fun onSceneTick() {
    super.onSceneTick()
    privatePanelExtension?.tick(spatialSceneReady)
    connectionHubSurfaceClient?.refresh()
    enforcePrivatePanelInputPolicy()
    if (directImmersiveVideoPanelRequested()) {
      runCatching { scene.getViewerPose() }
          .onSuccess(immersiveVideoPanelCoordinator::updateFromViewer)
    }
    if (productPolicy.cameraPanelRoutesEnabled) {
      if (!privatePanelInputLocked()) {
        controlProfileHotloader.poll()
      }
      updateLayerControlPanelPoseFromViewer(reason = "scene-tick", forceLog = false)
      updateParticleLayerProjectionFromViewer(reason = "scene-tick", forceLog = false)
      cameraHwbProjectionPlacementUpdateCoordinator.update("scene-tick", false)
      spatialFragmentProbeCoordinator.onSceneTick()
      spatialStimulusVolumeCoordinator.onSceneTick()
    }
    if (appControllerInputsEnabled()) {
      controllerInputRouteCoordinator.ensureEnabled("scene-tick", forceLog = false)
      controllerPollingCoordinator.pollNativeInput()
    }
  }

  private fun captureStoredProfileControls(): SpatialCameraPanelControlSnapshot {
    val video = immersiveVideoPanelCoordinator.sessionSnapshot()
    return SpatialCameraPanelControlSnapshot(
            projectionPanelEnabled = projectionPanelVisibilityCoordinator.enabled,
            layerOverride = privateLayerControlCoordinator.layerOverride,
            projectionScale = cameraHwbProjectionTuningCoordinator.targetScale(),
            depthLayerPolicy = privateLayerControlCoordinator.depthLayerPolicy,
            depthAlignment = privateLayerControlCoordinator.depthAlignment,
            guideProcessing = privateLayerControlCoordinator.guideProcessing,
            zoneCompositor = privateLayerControlCoordinator.zoneCompositor,
            rgbChannelTransform = privateLayerControlCoordinator.rgbChannelTransform,
            projectionSurfaceDisplacement =
                privateLayerControlCoordinator.projectionSurfaceDisplacement,
            projectionSurfaceTiling = privateLayerControlCoordinator.projectionSurfaceTiling,
            projectionInnerAlpha = privateLayerControlCoordinator.projectionInnerAlpha,
            videoPlaybackEnabled = video.playbackEnabled,
            videoPresentationMode = video.presentationMode.token,
        )
        .normalized()
  }

  private fun applyStoredProfileControls(
      requested: SpatialCameraPanelControlSnapshot,
      source: String,
  ): SpatialCameraPanelControlSnapshot {
    val controls = requested.normalized()
    setImmersiveVideoPresentationMode(controls.presentationMode(), "$source-video-presentation")
    setImmersiveVideoPlaybackEnabled(controls.videoPlaybackEnabled, "$source-video-playback")
    setProjectionPanelEnabled(controls.projectionPanelEnabled, "$source-projection-visibility")
    privateLayerControlCoordinator.updateLayerOverride(controls.layerOverride, source)
    cameraHwbProjectionTuningCoordinator.updateTargetScaleFromPanel(
        controls.projectionScale,
        source,
    )
    privateLayerControlCoordinator.updateDepthLayerPolicy(controls.depthLayerPolicy, source)
    privateLayerControlCoordinator.updateDepthAlignment(controls.depthAlignment, source)
    privateLayerControlCoordinator.updateGuideProcessing(controls.guideProcessing, source)
    PrivateLayerZoneCompositorPanelBridge.submit(controls.zoneCompositor, source)
    privateLayerControlCoordinator.updateRgbChannelTransform(controls.rgbChannelTransform, source)
    privateLayerControlCoordinator.updateProjectionSurfaceDisplacement(
        controls.projectionSurfaceDisplacement,
        source,
    )
    privateLayerControlCoordinator.updateProjectionSurfaceFeatures(
        controls.projectionSurfaceTiling,
        controls.projectionInnerAlpha,
        source,
    )
    return captureStoredProfileControls()
  }

  private fun appControllerInputsEnabled(): Boolean =
      presentationPolicy.appControlInputsEnabled && !privatePanelInputLocked()

  private fun privatePanelInputLocked(): Boolean =
      unavailableLaunchOptionInputLocked || (privatePanelExtension?.inputLocked() == true)

  private fun handlePrivatePanelLaunchIntent(request: Intent, source: String) {
    val wasLocked = privatePanelInputLocked()
    val optionPresent = SpatialAppLaunchOptionsContract.hasLaunchOption(request)
    val optionId = SpatialAppLaunchOptionsContract.requestedOptionId(request)
    val result =
        privatePanelExtension?.handleLaunchOption(optionPresent, optionId, source)
            ?: SpatialPrivatePanelLaunchResult(
                status = if (optionPresent) "private-extension-unavailable" else "normal-launch",
                inputLocked = optionPresent,
            )
    unavailableLaunchOptionInputLocked = privatePanelExtension == null && result.inputLocked
    privatePanelLaunchStatus = result.status
    if (privatePanelInputLocked()) {
      nativeInputBootstrapCoordinator.disableControllerActions()
    } else {
      privatePanelInputPolicyApplied = false
      if (wasLocked) {
        controlProfileHotloader.arm()
        if (spatialSceneReady) {
          runCatching {
            scene.spatialInterface.enableInput(presentationPolicy.appControlInputsEnabled)
          }
        }
      }
    }
    marker(
        "channel=spatial-app-launch-option status=${activityMarkerToken(result.status)} " +
            "source=${activityMarkerToken(source)} optionPresent=$optionPresent " +
            "opaqueOptionIdAccepted=${result.optionAccepted} " +
            "appControllerInputsEnabled=${appControllerInputsEnabled()} " +
            "metaHomeSystemEscapeRetained=true deviceOwner=false lockTask=false"
    )
  }

  private fun enforcePrivatePanelInputPolicy() {
    if (!privatePanelInputLocked() || !spatialSceneReady || privatePanelInputPolicyApplied) return
    setPrivateLayerPanelVisible(false, focus = false, source = "locked-app-launch-option")
    runCatching { scene.spatialInterface.enableInput(false) }
    nativeInputBootstrapCoordinator.disableControllerActions()
    privatePanelInputPolicyApplied = true
    marker(
        "channel=spatial-app-launch-option status=input-policy-applied " +
            "appControllerInputsEnabled=false lockedExtensionInputsEnabled=false " +
            "metaHomeSystemEscapeRetained=true deviceOwner=false lockTask=false"
    )
  }

  private fun saveStoredProfile(title: String): SpatialCameraPanelProfileOperationResult {
    val result = storedProfileAuthority.store(title, captureStoredProfileControls())
    marker(
        "channel=spatial-camera-panel status=${activityMarkerToken(result.status)} " +
            "profileCount=${result.library.profiles.size} mediaSelectionRetained=true"
    )
    return result
  }

  private fun loadStoredProfile(id: String): SpatialCameraPanelProfileOperationResult {
    val stored = storedProfileAuthority.find(id)
    if (stored == null) {
      return SpatialCameraPanelProfileOperationResult(
          status = "profile-not-found",
          library = storedProfileAuthority.snapshot(),
      )
    }
    val effective = applyStoredProfileControls(stored.controls, "stored-profile-load")
    marker(
        "channel=spatial-camera-panel status=profile-loaded " +
            "profileId=${activityMarkerToken(stored.id)} mediaSelectionRetained=true"
    )
    return SpatialCameraPanelProfileOperationResult(
        status = "profile-loaded",
        library = storedProfileAuthority.snapshot(),
        effectiveControls = effective,
    )
  }

  private fun deleteStoredProfile(id: String): SpatialCameraPanelProfileOperationResult {
    val result = storedProfileAuthority.delete(id)
    marker(
        "channel=spatial-camera-panel status=${activityMarkerToken(result.status)} " +
            "profileId=${activityMarkerToken(id)} profileCount=${result.library.profiles.size}"
    )
    return result
  }

  private fun importStagedProfiles(): SpatialCameraPanelProfileOperationResult {
    val result = storedProfileAuthority.importStaged()
    marker(
        "channel=spatial-camera-panel status=${activityMarkerToken(result.status)} " +
            "profileCount=${result.library.profiles.size} mediaSelectionRetained=true"
    )
    return result
  }

  private fun applyControlProfile(
      profile: SpatialCameraControlProfile,
      source: String,
  ): SpatialCameraControlProfileEffective {
    val effectiveLayer =
        privateLayerControlCoordinator.updateLayerOverride(profile.layerOverride, source)
    val effectiveScale =
        cameraHwbProjectionTuningCoordinator.updateTargetScaleFromPanel(
            profile.projectionScale,
            source,
        )
    val effectiveZone =
        privateLayerControlCoordinator.updateZoneCompositor(profile.zoneCompositor, source)
    val effectiveRgb =
        privateLayerControlCoordinator.updateRgbChannelTransform(
            profile.rgbChannelTransform,
            source,
        )
    val effectiveDisplacement =
        privateLayerControlCoordinator.updateProjectionSurfaceDisplacement(
            profile.projectionSurfaceDisplacement,
            source,
        )
    val (effectiveTiling, effectiveInnerAlpha) =
        privateLayerControlCoordinator.updateProjectionSurfaceFeatures(
            profile.projectionSurfaceTiling,
            profile.projectionInnerAlpha,
            source,
        )
    return SpatialCameraControlProfileEffective(
        layerOverride = effectiveLayer,
        projectionScale = effectiveScale,
        zoneCompositor = effectiveZone,
        rgbChannelTransform = effectiveRgb,
        projectionSurfaceDisplacement = effectiveDisplacement,
        projectionSurfaceTiling = effectiveTiling,
        projectionInnerAlpha = effectiveInnerAlpha,
    )
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (!appControllerInputsEnabled()) {
      return super.dispatchGenericMotionEvent(event)
    }
    if (androidControllerEventRouter.dispatchMotionButtonEvent(event)) {
      return true
    }
    if (handleSpatialJoystickMotion(event, "android-dispatch-generic-motion")) {
      return true
    }
    return super.dispatchGenericMotionEvent(event)
  }

  override fun onResume() {
    super.onResume()
    immersiveVideoPanelCoordinator.resume("activity-resume")
  }

  override fun onStart() {
    super.onStart()
    val target = SpatialConnectionHubSurfaceTargetLoader.load(::marker, privatePanelExtension)
    connectionHubSurfaceClient =
        target?.let { ConnectionHubSurfaceClient(this, it).also { client -> client.start() } }
  }

  override fun onStop() {
    connectionHubSurfaceClient?.close()
    connectionHubSurfaceClient = null
    super.onStop()
  }

  override fun onPause() {
    immersiveVideoPanelCoordinator.pause("activity-pause")
    super.onPause()
  }

  override fun onDestroy() {
    connectionHubSurfaceClient?.close()
    connectionHubSurfaceClient = null
    privatePanelExtension?.shutdown()
    immersiveVideoPanelCoordinator.destroy("activity-destroy")
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
    spatialFragmentProbeCoordinator.destroy("activity-destroy")
    spatialStimulusVolumeCoordinator.destroy("activity-destroy")
    cleanupSdkQuadSurfaceProbe("activity-destroy")
    externalSwapchainProbeCoordinator.destroy("activity-destroy")
    stagedAssetModule.destroy("activity-destroy")
    destroySpatialVirtualRoom("activity-destroy")
    surfaceParticleRuntimeCoordinator.stop()
    super.onDestroy()
  }

  override fun registerPanels(): List<PanelRegistration> {
    if (immersiveVideoPanelCoordinator.requested) {
      marker(immersiveVideoPanelCoordinator.routePolicyMarker())
    }
    val composePanels =
        SpatialComposePanelRegistrationModule.registrations(
            privateLayer =
                SpatialPrivateLayerPanelRegistrationBindings(
                    layerOverride = { privateLayerControlCoordinator.layerOverride },
                    projectionPanelEnabled = { projectionPanelVisibilityCoordinator.enabled },
                    projectionScale = cameraHwbProjectionTuningCoordinator.targetScale(),
                    projectionScaleRange =
                        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE..CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
                    depthLayerPolicy = privateLayerControlCoordinator.depthLayerPolicy,
                    depthAlignment = privateLayerControlCoordinator.depthAlignment,
                    guideProcessing = privateLayerControlCoordinator.guideProcessing,
                    rgbChannelTransform = privateLayerControlCoordinator.rgbChannelTransform,
                    projectionSurfaceDisplacement =
                        privateLayerControlCoordinator.projectionSurfaceDisplacement,
                    projectionSurfaceTiling =
                        privateLayerControlCoordinator.projectionSurfaceTiling,
                    projectionInnerAlpha =
                        privateLayerControlCoordinator.projectionInnerAlpha,
                    videoSession = immersiveVideoPanelCoordinator::sessionSnapshot,
                    sharedMediaLibrary = {
                      SharedOfflineImmersiveMediaLibrary.snapshot(this)
                    },
                    setLayerOverride = privateLayerControlCoordinator::updateLayerOverride,
                    setProjectionPanelEnabled = ::setProjectionPanelEnabled,
                    setVideoPlaybackEnabled = { enabled ->
                      setImmersiveVideoPlaybackEnabled(
                          enabled,
                          "private-layer-control-panel-video-toggle",
                      )
                    },
                    updateProjectionScale = { scale, source ->
                      cameraHwbProjectionTuningCoordinator.updateTargetScaleFromPanel(
                          scale,
                          source,
                      )
                    },
                    updateDepthLayerPolicy =
                        privateLayerControlCoordinator::updateDepthLayerPolicy,
                    updateDepthAlignment = privateLayerControlCoordinator::updateDepthAlignment,
                    updateGuideProcessing =
                        privateLayerControlCoordinator::updateGuideProcessing,
                    updateRgbChannelTransform =
                        privateLayerControlCoordinator::updateRgbChannelTransform,
                    updateProjectionSurfaceDisplacement =
                        privateLayerControlCoordinator::updateProjectionSurfaceDisplacement,
                    updateProjectionSurfaceTiling =
                        privateLayerControlCoordinator::updateProjectionSurfaceTiling,
                    updateProjectionInnerAlpha =
                        privateLayerControlCoordinator::updateProjectionInnerAlpha,
                    selectPreviousVideo = {
                      changeImmersiveVideo(
                          "previous",
                          null,
                          "private-layer-control-panel-video-previous",
                      )
                    },
                    selectNextVideo = {
                      changeImmersiveVideo(
                          "next",
                          null,
                          "private-layer-control-panel-video-next",
                      )
                    },
                    setVideoPresentationMode = { mode ->
                      setImmersiveVideoPresentationMode(
                          mode,
                          "private-layer-control-panel-video-presentation",
                      )
                    },
                    chooseSharedMediaFolder = {
                      chooseSharedMediaFolder()
                    },
                    profileLibrary = storedProfileAuthority::snapshot,
                    panelExtension = privatePanelExtension,
                    saveStoredProfile = ::saveStoredProfile,
                    loadStoredProfile = ::loadStoredProfile,
                    deleteStoredProfile = ::deleteStoredProfile,
                    importStagedProfiles = ::importStagedProfiles,
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
        )
    val immersiveVideoPanels =
        if (directImmersiveVideoPanelRequested()) {
          immersiveVideoPanelCoordinator.panelRegistrations()
        } else {
          emptyList()
        }
    val panels =
        composePanels +
            immersiveVideoPanels +
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
    cameraLatencyDiagnosticModule.poll("camera-hwb-projection-pre-run", force = true)
    if (nativeInteropCoordinator.receiptLibraryLoaded) {
      val openXrProbe = SpatialNativeInteropProbe.capture(scene)
      val timingMask =
          nativeConfigureCameraLatencyOpenXrHandles(
              openXrProbe.openXrInstanceHandle,
              openXrProbe.openXrSessionHandle,
              openXrProbe.openXrGetInstanceProcAddrHandle,
          )
      marker(
          "channel=camera-latency-diagnostic status=openxr-handles-configured " +
              "nativeUpdateMask=$timingMask openXrInstanceHandleNonZero=" +
              "${openXrProbe.openXrInstanceHandle != 0L} openXrSessionHandleNonZero=" +
              "${openXrProbe.openXrSessionHandle != 0L} " +
              "openXrGetInstanceProcAddrHandleNonZero=" +
              "${openXrProbe.openXrGetInstanceProcAddrHandle != 0L} " +
              "frameLoopAuthority=spatial-sdk sidecarWaitFrame=false"
      )
    }
    cameraLatencyDiagnosticModule.resetPoseCapture("camera-hwb-projection-pre-run")
    cleanupSdkQuadSurfaceProbe("camera-hwb-projection-pre-run")
    cameraHwbProjectionPanelCarrierCoordinator.cleanup("camera-hwb-projection-pre-run")
    spatialVideoProjectionRuntimeCoordinator.adoptSettings(
        videoSettings,
        immersiveVideoPanelCoordinator.activeOfflinePack.takeIf {
          videoSettings.source == SpatialImmersiveVideoSessionPolicy.CUSTOM_PROJECTION_SOURCE
        },
    )
    cameraHwbProjectionEntity = null
    cameraHwbProjectionCarrierStateCoordinator.resetForLaunch()
    cameraHwbProjectionTuningCoordinator.resetForLaunch()
    privateLayerControlCoordinator.initializeDepthLayerPolicy(
        initialPrivateLayerDepthLayerPolicy()
    )
    privateLayerControlCoordinator.initializeGuideProcessing(
        initialPrivateLayerGuideProcessing()
    )
    cameraHwbProjectionPlacementUpdateCoordinator.resetMarkerCadence()
    suppressParticleLayerForCameraStack("camera-hwb-projection-probe")
    panelPlacementStateCoordinator.setPrivateLayerVisibleFlag(false)
    setPrivateLayerPanelVisible(false, focus = false, source = "camera-hwb-projection-probe")
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
    surfaceParticleRuntimeCoordinator.suppressForCameraStack(source)
  }

  private fun suppressParticleLayerIfCameraProjectionRequested(source: String) {
    when {
      presentationPolicy.lockedFinalPresentation ->
          suppressParticleLayerForCameraStack("$source-locked-final-presentation")
      activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true ->
          suppressParticleLayerForCameraStack("$source-camera-hwb-projection-property")
      activityReadOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) == true ->
          suppressParticleLayerForCameraStack("$source-spatial-video-projection-property")
    }
  }

  private fun cameraStackOrRoomRequested(): Boolean =
      presentationPolicy.lockedFinalPresentation ||
          spatialVirtualRoomEnabled() ||
          activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_PROBE_PROPERTY) == true ||
          activityReadOptionalBooleanSystemProperty(SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY) == true ||
          spatialVideoProjectionRuntimeCoordinator.resolveSettings(intent).active

  private fun deactivateControlPanelForCameraStack(source: String) {
    if (!cameraStackOrRoomRequested()) {
      return
    }
    surfaceParticleRuntimeCoordinator.suppressStartsForCameraStack()
    panelPlacementStateCoordinator.hideAllPanels()
    privateLayerPanelEntity?.setComponent(Visible(false))
    particleLayerEntity?.setComponent(Visible(false))
    marker(
        "channel=spatial-panel status=camera-stack-initial-ui-hidden " +
                          "source=${activityMarkerToken(source)}"
    )
  }

  private fun deactivatePanelShellIfRequested(source: String) {
    if (panelShellVisible()) {
      return
    }
    panelPlacementStateCoordinator.hideAllPanels()
    privateLayerPanelEntity?.setComponent(Visible(false))
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

  private fun setProjectionPanelEnabled(enabled: Boolean, source: String): Boolean {
    val effectiveEnabled = enabled || presentationPolicy.lockedFinalPresentation
    projectionPanelRuntimeEnabled = effectiveEnabled
    return projectionPanelVisibilityCoordinator.setEnabled(
        requestedEnabled = effectiveEnabled,
        source = source,
    )
  }

  private fun selectSurfaceTarget(requestedTargetId: String, source: String): String {
    val targetId = requestedTargetId.trim()
    require(targetId in SUPPORTED_SURFACE_TARGET_IDS) {
      "unsupported_surface_target:${activityMarkerToken(targetId)}"
    }
    surfaceTargetId = targetId
    marker(
        "channel=spatial-surface status=target-selected " +
            "source=${activityMarkerToken(source)} surfaceTargetId=${activityMarkerToken(targetId)} " +
            "surfaceTargetAuthority=runtime-control"
    )
    return targetId
  }

  private fun setPrivateLayerPanelVisible(
      visible: Boolean,
      focus: Boolean,
      source: String,
  ): PanelPlacement {
    val requestedVisible = visible && appControllerInputsEnabled()
    if (requestedVisible && !panelShellVisible()) {
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
      return privateLayerPanelPlacement
    }
    if (!requestedVisible && !PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM) {
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
        visible = requestedVisible,
        focus = focus && requestedVisible,
        inputForegroundDistanceMeters = inputForegroundDistanceMeters,
        inputForegroundScale = inputForegroundScale,
        freeTransform = PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM,
    )
    val privateLayerPanelSeedPose =
        if (requestedVisible && focus) {
          privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
        } else {
          null
        }
    applyPanelPlacement(
        updatePrivateLayerPanelTransform =
            requestedVisible && focus && !PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM
    )
    privateLayerPanelSeedPose?.let { pose ->
      privateLayerPanelEntity?.setComponent(Transform(pose))
    }
    privateLayerPanelEntity?.setComponent(privateLayerPanelGrabbable(enabled = requestedVisible))
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
                privateLayerPanelVisible = privateLayerPanelVisible,
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
    return privateLayerPanelPlacement
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
      entity.setComponent(
          Visible(
              shellVisible &&
                  privateLayerPanelVisible &&
                  privateLayerPanelPlacement.visible
          )
      )
    }
    particleLayerEntity?.setComponent(Visible(particleLayerVisibleForPanelMode()))
    updateParticleLayerPanelLayer("apply-panel-placement", forceLog = false)
  }

  private fun particleLayerVisibleForPanelMode(): Boolean =
      SpatialPanelPlacementModule.particleLayerVisibleForPanelMode(
          privateLayerPanelVisible = privateLayerPanelVisible,
          cameraStackSuppressesParticles =
              surfaceParticleRuntimeCoordinator.cameraStackSuppressesParticles,
          nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled(),
      )

  private fun privateLayerPanelPose(): Pose =
      if (privateLayerPanelPlacement.headlocked) {
        privateLayerPanelPoseFromViewer() ?: privateLayerPanelWorldPose()
      } else {
        privateLayerPanelWorldPose()
      }

  private fun privateLayerPanelWorldPose(): Pose =
      SpatialPanelPlacementModule.privateLayerPanelWorldPose(privateLayerPanelPlacement)

  private fun activeHeadlockedPanelPlacement(): PanelPlacement =
      privateLayerPanelPlacement

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

  private fun updateLayerControlPanelPoseFromViewer(reason: String, forceLog: Boolean) {
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
            anyPanelVisible = privateLayerPanelVisible,
        )
    ) {
      return
    }
    marker(
        SpatialPanelPlacementModule.headlockedPoseUpdatedMarker(
            reason = reason,
            privateLayerPanelVisible = privateLayerPanelVisible,
            headlockMarkerFields = panelHeadlockMarkerFields(),
            panelPositionM = activityVectorMarker(privatePose?.t ?: Vector3(0.0f)),
            panelQuaternion =
                activityQuaternionMarker(
                    privatePose?.q ?: Quaternion(1.0f, 0.0f, 0.0f, 0.0f)
                ),
        )
    )
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

  private fun initialPrivateLayerGuideProcessing(): PrivateLayerGuideProcessing =
      PrivateLayerGuideProcessing(
          preblurKernel =
              PrivateLayerControls.guideKernelForToken(
                  activityReadSystemProperty(CAMERA_HWB_PROJECTION_GUIDE_PREBLUR_KERNEL_PROPERTY)
              ) ?: PrivateLayerControls.guideKernelNativeBox5,
          preblurInput =
              PrivateLayerControls.guideInputForToken(
                  activityReadSystemProperty(CAMERA_HWB_PROJECTION_GUIDE_PREBLUR_INPUT_PROPERTY)
              ) ?: PrivateLayerControls.guideInputLuma,
          postblurKernel =
              PrivateLayerControls.guideKernelForToken(
                  activityReadSystemProperty(CAMERA_HWB_PROJECTION_GUIDE_POSTBLUR_KERNEL_PROPERTY)
              ) ?: PrivateLayerControls.guideKernelNativeBox5,
          cameraSampling =
              PrivateLayerControls.cameraSamplingForToken(
                  activityReadSystemProperty(CAMERA_HWB_PROJECTION_CAMERA_SAMPLING_PROPERTY)
              ) ?: PrivateLayerControls.cameraSamplingThinLineTent5,
      )

  private fun currentSpatialVrInputSystemToken(): String =
      SpatialControllerRoutingModule.spatialVrInputSystemToken(
          activityReadSystemProperty(SPATIAL_VR_INPUT_SYSTEM_PROPERTY)
      )

  private fun currentSpatialVrInputSystemType(): VrInputSystemType =
      SpatialControllerRoutingModule.spatialVrInputSystemType(currentSpatialVrInputSystemToken())

  private fun currentSpatialShouldConsumeLeftRightInput(): Boolean =
      presentationPolicy.appControlInputsEnabled &&
          SpatialControllerRoutingModule.shouldConsumeLeftRightInput(
              activityReadOptionalBooleanSystemProperty(
                  SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY
              )
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

    val axes =
        SpatialPanelJoystickAxes(
            leftX = joystickAxis(event, MotionEvent.AXIS_X),
            leftY = joystickAxis(event, MotionEvent.AXIS_Y),
            rightX = joystickAxis(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z),
            rightY = joystickAxis(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ),
        )
    return panelJoystickArbitrationCoordinator.handle(axes = axes, inputSource = inputSource)
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
                "rightStickXVideoSelectionReserved=true rightStickYPanelDistanceDisabled=true " +
                "rightStickXPanelScaleDisabled=true",
    )
  }

  private fun toggleLayerControlPanelFromController(inputSource: String, detail: String): Boolean {
    if (!SpatialControllerRoutingModule.isRightPrimaryPanelToggleSource(inputSource)) return false
    val panelToggleAction =
        SpatialControllerRoutingModule.panelToggleAction(
            privateLayerPanelVisible = privateLayerPanelVisible,
        )
    when (panelToggleAction) {
      SpatialControllerPanelToggleAction.ClosePrivateLayerPanel -> {
        setPrivateLayerPanelVisible(
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
    }
    marker(
        SpatialControllerRoutingModule.controllerPrimaryToggledPanelMarker(
            inputSource = inputSource,
            detail = detail,
            panelToggleAction = panelToggleAction,
            panelMode = panelStateToken(),
            privateLayerPanelVisible = privateLayerPanelVisible,
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
      presentationPolicy.appControlInputsEnabled &&
          SpatialOpenXrRouteModule.spatialMultimodalInputEnabled(
              activityReadOptionalBooleanSystemProperty(SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY)
          )

  private fun nativeSpatialControllerActionsEnabled(): Boolean =
      presentationPolicy.appControlInputsEnabled &&
          SpatialControllerRoutingModule.nativeSpatialControllerActionsEnabled(
              activityReadOptionalBooleanSystemProperty(
                  NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY
              )
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
      presentationPolicy.appControlInputsEnabled &&
          (activityReadOptionalBooleanSystemProperty(PANEL_SHELL_VISIBLE_PROPERTY) ?: true)

  private fun startInParticleView(): Boolean =
      SpatialSurfaceParticleRouteModule.startInParticleView(
          activityReadOptionalBooleanSystemProperty(PANEL_START_IN_PARTICLE_VIEW_PROPERTY),
          activityParseBuildConfigBoolean(BuildConfig.START_IN_PARTICLE_VIEW_DEFAULT, false),
      )

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

  private fun privateLayerPanelDimensions(): PanelDimensions =
      SpatialPanelPlacementModule.privateLayerPanelDimensions(privateLayerPanelPlacement)

  private fun panelStateToken(): String =
      SpatialPanelPlacementModule.panelStateToken(
          panelShellVisible = panelShellVisible(),
          privateLayerPanelVisible = privateLayerPanelVisible,
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

  private external fun nativePollSpatialControllerRightButtonA(): Boolean

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

  private external fun nativeConfigureCameraReplayCapture(
      outputDirectory: String,
      requestedFrameCount: Int,
      intervalMs: Int,
  ): Long

  private external fun nativeUpdateCameraHwbProjectionStereoOffsetUv(stereoOffsetUv: Float): Long

  private external fun nativeUpdateCameraHwbProjectionTargetScale(targetScale: Float): Long

  private external fun nativeUpdateCameraLatencyDiagnostics(
      enabled: Boolean,
      revision: Long,
      poseMode: Int,
      frameWaitMs: Int,
      summaryIntervalMs: Int,
      frameLog: Boolean,
      presentMode: Int,
      imageCount: Int,
      captureFps: Int,
      cameraSyncMode: Int,
      captureProcessing: Int,
      adoptionCadence: Int,
      stereoPolicy: Int,
      isolationMode: Int,
      freezeFrame: Boolean,
      reprojectionMode: Int,
      assumedCaptureAgeMs: Int,
      reprojectionFovDegrees: Int,
      reprojectionSourceOverscanPercent: Int,
      reprojectionGuardBandMode: Int,
      presentationPoseMode: Int,
      presentationLeadMs: Int,
  ): Long

  private external fun nativeConfigureCameraLatencyOpenXrHandles(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  private external fun nativeUpdateCameraLatencyViewerPose(
      timestampNs: Long,
      positionX: Float,
      positionY: Float,
      positionZ: Float,
      rightX: Float,
      rightY: Float,
      rightZ: Float,
      upX: Float,
      upY: Float,
      upZ: Float,
      forwardX: Float,
      forwardY: Float,
      forwardZ: Float,
  ): Long

  private external fun nativeUpdatePrivateLayerOverride(layerOverride: Float): Long

  private external fun nativeUpdatePrivateLayerDepthLayerPolicy(depthLayerPolicy: Int): Long

  private external fun nativeUpdatePrivateLayerDepthAlignment(
      leftOffsetX: Float,
      leftOffsetY: Float,
      rightOffsetX: Float,
      rightOffsetY: Float,
      sampleScale: Float,
      sampleScaleY: Float,
      rollDegrees: Float,
      metadataAutoAlign: Int,
  ): Long

  private external fun nativeUpdatePrivateLayerGuideProcessing(
      preblurKernel: Int,
      preblurInput: Int,
      postblurKernel: Int,
      cameraSampling: Int,
  ): Long

  private external fun nativeUpdatePrivateLayerZoneCompositor(
      coverageMode: Int,
      regionContractVersion: Int,
      bufferGeometryMode: Int,
      bufferStaticWidthUv: Float,
      bufferFillMode: Int,
      stretchExtentMode: Int,
      stretchSource: Int,
      debugMode: Int,
      outerTargetMode: Int,
      stretchMapping: Int,
      projectionEffectEdgeGuardEnabled: Int,
      stretchOptionFlags: Int,
      edgeInsetUv: Float,
      maxInsetUv: Float,
      stretchCurve: Float,
      processedMix: Float,
      innerSignal: Int,
      innerWidthUv: Float,
      innerCurve: Float,
      innerThresholdR: Float,
      innerThresholdG: Float,
      innerThresholdB: Float,
      innerSoftness: Float,
      innerStrength: Float,
      innerCycleAmplitude: Float,
      innerCycleHz: Float,
      innerMotionGain: Float,
      outerSignal: Int,
      outerWidthUv: Float,
      outerCurve: Float,
      outerThresholdR: Float,
      outerThresholdG: Float,
      outerThresholdB: Float,
      outerSoftness: Float,
      outerStrength: Float,
      outerCycleAmplitude: Float,
      outerCycleHz: Float,
      outerMotionGain: Float,
  ): Long

  private external fun nativeUpdatePrivateLayerZoneChannelDynamics(
      innerApplicationMode: Int,
      innerSourceChoice: Int,
      innerRegionDriver: Int,
      innerStrengthR: Float,
      innerStrengthG: Float,
      innerStrengthB: Float,
      innerCycleAmplitudeR: Float,
      innerCycleAmplitudeG: Float,
      innerCycleAmplitudeB: Float,
      innerCycleHzR: Float,
      innerCycleHzG: Float,
      innerCycleHzB: Float,
      innerCyclePhaseR: Float,
      innerCyclePhaseG: Float,
      innerCyclePhaseB: Float,
      outerApplicationMode: Int,
      outerSourceChoice: Int,
      outerRegionDriver: Int,
      outerStrengthR: Float,
      outerStrengthG: Float,
      outerStrengthB: Float,
      outerCycleAmplitudeR: Float,
      outerCycleAmplitudeG: Float,
      outerCycleAmplitudeB: Float,
      outerCycleHzR: Float,
      outerCycleHzG: Float,
      outerCycleHzB: Float,
      outerCyclePhaseR: Float,
      outerCyclePhaseG: Float,
      outerCyclePhaseB: Float,
  ): Long

  private external fun nativeUpdateRgbChannelTransform(
      mode: Int,
      edgeMode: Int,
      redDirectionTurns: Float,
      greenDirectionTurns: Float,
      blueDirectionTurns: Float,
      redDirectionRateHz: Float,
      greenDirectionRateHz: Float,
      blueDirectionRateHz: Float,
      redDisplacementStrengthUv: Float,
      greenDisplacementStrengthUv: Float,
      blueDisplacementStrengthUv: Float,
      redImageScale: Float,
      greenImageScale: Float,
      blueImageScale: Float,
      redCoverageScale: Float,
      greenCoverageScale: Float,
      blueCoverageScale: Float,
  ): Long

  private external fun nativeUpdateProjectionSurfaceDisplacement(
      enabled: Int,
      maxDisplacementMeters: Float,
      referenceSurfaceDistanceMeters: Float,
      polarity: Float,
      edgeTaper: Float,
  ): Long

  private external fun nativeUpdateProjectionSurfaceFeatures(
      tilingEnabled: Int,
      topology: Int,
      gapNormalized: Float,
      depthFlexibility: Float,
      scope: Int,
      innerAlphaEnabled: Int,
      innerAlphaDriver: Int,
      threshold: Float,
      softness: Float,
      amount: Float,
      invert: Int,
      stretchPolicy: Int,
      stretchObeysExactProjectionMask: Int,
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
    val presentationSnapshot = surfaceParticlePresentationStateCoordinator.snapshot()
    return SpatialSurfaceParticleLifecycleDiagnosticSnapshot(
        panelRegistrationCount = presentationSnapshot.panelRegistrationCount,
        panelMode = panelStateToken(),
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
        placementMarkerFields = surfaceParticleProjectionGeometryCoordinator.placementMarkerFields(),
        stereoMarkerFields = particleLayerStereoMarkerFields(),
    )
  }

  companion object {
    private const val TAG = "RQSpatialCameraPanel"
    private const val SHARED_MEDIA_FOLDER_REQUEST_CODE = 0x534D
    private const val MARKER_PREFIX = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL"
    private const val ACTIVITY_MARKERS_FILE = "spatial_camera_panel_activity_markers.log"
    private const val PANEL_SHELL_VISIBLE_PROPERTY =
        "debug.rustyquest.spatial.panel_shell.visible"
    private val SUPPORTED_SURFACE_TARGET_IDS =
        setOf("real-hands", "gpu-replay-hands", "icosphere")
  }
}
