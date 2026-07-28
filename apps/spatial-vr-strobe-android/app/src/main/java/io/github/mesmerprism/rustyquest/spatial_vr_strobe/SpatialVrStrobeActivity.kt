package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.PerformanceLevel
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionControls
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import io.github.mesmerprism.rustyquest.spatial_sdk_shared.SpatialPanelFacing
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class SpatialVrStrobeActivity : AppSystemActivity() {
  private val featureDecision = VrStrobeFeatureRoute.resolve()
  private val storedProfilePreferences by lazy(LazyThreadSafetyMode.NONE) {
    getSharedPreferences("spatial_vr_strobe_stored_profiles", MODE_PRIVATE)
  }
  private var panelEntity: Entity? = null
  private var panelSceneObject: PanelSceneObject? = null
  private var panelVisible = true

  private val coordinator by lazy(LazyThreadSafetyMode.NONE) {
    SpatialVrStrobeCoordinator(
        SpatialVrStrobeBindings(
            scene = scene,
            poseFromViewer = ::carrierPoseFromViewer,
            marker = ::marker,
            setPerformanceBoost = ::setPerformanceBoost,
            readStoredProfilesPayload = { storedProfilePreferences.getString("profiles_v1", null) },
            writeStoredProfilesPayload = { payload ->
              storedProfilePreferences.edit().putString("profiles_v1", payload).commit()
            },
            readImportBundlePayload = {
              File(filesDir, "vr_strobe_profile_import.json").takeIf(File::isFile)
                  ?.readText(Charsets.UTF_8)
            },
            clearImportBundlePayload = {
              File(filesDir, "vr_strobe_profile_import.json").let { !it.exists() || it.delete() }
            },
            writeExportBundlePayload = { payload ->
              runCatching {
                    File(filesDir, "vr_strobe_profile_bundle.json").writeText(payload, Charsets.UTF_8)
                    true
                  }
                  .getOrDefault(false)
            },
        ),
        featureDecision,
    )
  }

  private val controllerInput by lazy(LazyThreadSafetyMode.NONE) {
    VrStrobeControllerInputCoordinator(
        VrStrobeControllerInputBindings(
            featureEnabled = { true },
            stimulusSelected = coordinator::stimulusSelected,
            randomizeActive = coordinator::randomizeActive,
            storeActive = { source -> coordinator.storeActiveProfile(source); Unit },
            togglePanel = { source -> setPanelVisible(!panelVisible, source); Unit },
            cyclePreset = { direction, source -> coordinator.cyclePreset(direction, source); Unit },
            curvedMode = coordinator::curvedMode,
            adjustDistance = { value, seconds, source ->
              coordinator.adjustDistance(value, seconds, source); Unit
            },
            adjustConcavity = { value, seconds, source ->
              coordinator.adjustConcavity(value, seconds, source); Unit
            },
            monotonicNowMs = SystemClock::elapsedRealtime,
            marker = ::marker,
        )
    )
  }

  override fun registerFeatures(): List<SpatialFeature> =
      listOf(
          VRFeature(this, LocomotionControls.Right, false, VrInputSystemType.INTERACTION_SDK),
          SpatialVrStrobeControllerPollingFeature(::pollControllers),
          ComposeFeature(),
      )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    marker(
        "channel=activity status=created product=spatial-vr-strobe " +
            "package=${BuildConfig.APPLICATION_ID} sourceNamespace=io.github.mesmerprism.rustyquest.spatial_vr_strobe " +
            "module=:strobe-app cameraRoutesCompiled=false cameraPermissionsDeclared=false"
    )
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    val pose = panelPoseSnapshot()
    panelEntity =
        Entity.createPanelEntity(
            R.id.spatial_vr_strobe_panel,
            Transform(pose.first),
            SpatialVrStrobePanelModule.dimensions(),
            panelGrabbable(true),
            Visible(true),
        )
    marker(
        "channel=spatial-vr-strobe status=panel-spawned panelRegistrationId=spatial_vr_strobe_panel " +
            "visible=true outputActive=false poseSource=${pose.second} " +
            "panelFacingConvention=${SpatialPanelFacing.CONVENTION_MARKER} " +
            "panelFacingFallback=${SpatialPanelFacing.FALLBACK_MARKER}"
    )
  }

  override fun onVRReady() {
    super.onVRReady()
    updatePanelPose("vr-ready")
  }

  override fun onSceneTick() {
    super.onSceneTick()
    coordinator.onSceneTick()
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          SpatialVrStrobePanelModule.registration(
              SpatialVrStrobePanelRegistrationBindings(
                  actions =
                      VrStrobePanelActions(
                          snapshot = coordinator::snapshot,
                          acknowledgeWarning = coordinator::acknowledgeWarning,
                          beginInterference = { closePanelAfterAcceptedBegin(coordinator.begin(it), "interference") },
                          beginTemporal = { closePanelAfterAcceptedBegin(coordinator.begin(it), "temporal") },
                          randomizeActive = coordinator::randomizeActive,
                          toggleCurvedMode = coordinator::toggleCurvedMode,
                          setDepthDeformationEnabled = coordinator::setDepthDeformationEnabled,
                          setDepthMaxDisplacementMeters = coordinator::setDepthMaxDisplacementMeters,
                          storedProfiles = coordinator::storedProfiles,
                          storeActive = coordinator::storeActiveProfile,
                          loadStoredProfile = { id, source ->
                            closePanelAfterAcceptedBegin(coordinator.loadStoredProfile(id, source), "stored")
                          },
                          stop = coordinator::stop,
                      ),
                  onPanelSetup = { panel ->
                    panelSceneObject = panel
                    val layerReady = updatePanelLayer("panel-setup")
                    marker(
                        "channel=spatial-vr-strobe status=panel-setup panelLayerReady=$layerReady " +
                            "panelLayerZIndex=$VR_STROBE_PANEL_LAYER_Z_INDEX " +
                            "panelComfortDistanceMeters=$VR_STROBE_PANEL_COMFORT_DISTANCE_METERS " +
                            "cameraPanelRegistered=false composePrimaryInputPolicy=${SpatialVrStrobePanelInputPolicy.MARKER}"
                    )
                  },
              )
          )
      )

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      when (event.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A ->
            if (controllerInput.handlePrimary("android-key-event", "button-a")) return true
        KeyEvent.KEYCODE_BUTTON_B ->
            if (controllerInput.handleSecondary("android-key-event")) return true
        KeyEvent.KEYCODE_BUTTON_X ->
            if (controllerInput.handleStore("android-key-event", "button-x")) return true
      }
    }
    return super.dispatchKeyEvent(event)
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    val axes =
        VrStrobeControllerAxes(
            leftX = deadzone(event.getAxisValue(MotionEvent.AXIS_X)),
            leftY = deadzone(event.getAxisValue(MotionEvent.AXIS_Y)),
            rightX = deadzone(event.getAxisValue(MotionEvent.AXIS_Z)),
            rightY = deadzone(event.getAxisValue(MotionEvent.AXIS_RZ)),
        )
    if (axes != VrStrobeControllerAxes() && controllerInput.handleAxes(axes, "android-generic-motion")) {
      return true
    }
    return super.dispatchGenericMotionEvent(event)
  }

  override fun onPause() {
    coordinator.onFocusLost()
    super.onPause()
  }

  override fun onDestroy() {
    coordinator.destroy("activity-destroy")
    panelSceneObject = null
    super.onDestroy()
  }

  private fun pollControllers() {
    val sample = SpatialVrStrobeControllerAdapter.capture(scene)
    controllerInput.handleSnapshot(
        axes = sample.axes,
        primaryDown = sample.primaryDown,
        secondaryDown = sample.secondaryDown,
        storeDown = sample.storeDown,
        rightControllerSampleValid =
            VrStrobeRightControllerSamplePolicy.isValid(
                sample.localRightControllerType,
                sample.localRightAttachmentType,
                sample.rightAvatarControllerType,
            ),
        storeControllerSampleValid =
            VrStrobeLeftControllerSamplePolicy.isValid(
                sample.localLeftControllerType,
                sample.localLeftAttachmentType,
                sample.leftAvatarControllerType,
            ),
        inputSource = sample.rightInputSource,
        storeInputSource = sample.leftInputSource,
    )
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun panelPoseSnapshot(): Pair<Pose, String> {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    return SpatialPanelFacing.poseFromViewer(viewerPose, VR_STROBE_PANEL_COMFORT_DISTANCE_METERS) to
        if (viewerPose == null) "static-fallback" else "scene-viewer-pose"
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun carrierPoseFromViewer(distanceMeters: Float): Pose {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
        ?: return Pose(
            Vector3(0.0f, 1.20f, -distanceMeters),
            Quaternion.fromDirection(Vector3(0.0f, 0.0f, -1.0f), Vector3(0.0f, 1.0f, 0.0f)),
        )
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    return Pose(viewerPose.t + forward * distanceMeters, Quaternion.fromDirection(forward, up))
  }

  private fun updatePanelPose(reason: String) {
    val entity = panelEntity ?: return
    val pose = panelPoseSnapshot()
    entity.setComponent(Transform(pose.first))
    marker("channel=spatial-vr-strobe status=panel-pose-updated reason=${activityMarkerToken(reason)} poseSource=${pose.second}")
  }

  private fun closePanelAfterAcceptedBegin(
      snapshot: VrStrobeSafetySnapshot,
      source: String,
  ): VrStrobeSafetySnapshot {
    if (VrStrobePanelFlow.beginAccepted(snapshot)) setPanelVisible(false, "accepted-begin-$source")
    return snapshot
  }

  private fun setPanelVisible(visible: Boolean, source: String): Boolean {
    panelVisible = visible
    if (visible) updatePanelPose("panel-open-$source")
    panelSceneObject?.rootView?.let { it.clearFocus(); it.isEnabled = visible }
    panelSceneObject?.setIsVisible(visible)
    panelEntity?.setComponent(Visible(visible))
    panelEntity?.setComponent(panelGrabbable(visible))
    val carrierVisible = coordinator.setPanelVisible(visible, source)
    marker(
        "channel=spatial-vr-strobe status=panel-visibility source=${activityMarkerToken(source)} " +
            "visible=$visible carrierVisible=$carrierVisible outputLifecycleChanged=false"
    )
    return panelEntity != null
  }

  private fun updatePanelLayer(reason: String): Boolean =
      runCatching {
            requireNotNull(panelSceneObject?.layer) { "panel-layer-missing" }
                .setZIndex(VR_STROBE_PANEL_LAYER_Z_INDEX)
            marker("channel=spatial-vr-strobe status=panel-layer-ready reason=${activityMarkerToken(reason)}")
            true
          }
          .getOrElse {
            marker("channel=spatial-vr-strobe status=panel-layer-update-failed reason=${activityMarkerToken(reason)} error=${it.javaClass.simpleName}")
            false
          }

  private fun panelGrabbable(enabled: Boolean): Grabbable =
      Grabbable(enabled = enabled, type = GrabbableType.PIVOT_Y, minHeight = 0.55f, maxHeight = 2.50f)

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun setPerformanceBoost(active: Boolean, source: String): Boolean {
    val level = if (active) PerformanceLevel.BOOST_HINT else PerformanceLevel.SUSTAINED_HIGH
    val applied = runCatching { scene.spatialInterface.setPerformanceLevel(level) }.getOrDefault(false)
    marker("channel=spatial-vr-strobe status=performance-level-requested source=${activityMarkerToken(source)} active=$active applied=$applied")
    return applied
  }

  private fun marker(detail: String) {
    val line = "$MARKER_PREFIX $detail"
    Log.i(TAG, line)
    runCatching {
      File(filesDir, ACTIVITY_MARKERS_FILE).appendText("${System.currentTimeMillis()} $line\n", Charsets.UTF_8)
    }
  }

  private fun deadzone(value: Float): Float = if (abs(value) >= 0.14f) value.coerceIn(-1f, 1f) else 0f

  private fun Vector3.normalizedOr(fallback: Vector3): Vector3 {
    val magnitude = sqrt(x * x + y * y + z * z)
    return if (magnitude > 0.000001f) this * (1.0f / magnitude) else fallback
  }

  companion object {
    private const val TAG = "RustyQuestSpatialVrStrobe"
    private const val MARKER_PREFIX = "RUSTY_QUEST_SPATIAL_VR_STROBE"
    private const val ACTIVITY_MARKERS_FILE = "spatial_vr_strobe_activity_markers.log"
  }
}
