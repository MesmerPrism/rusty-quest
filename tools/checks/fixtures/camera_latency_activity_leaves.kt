package io.github.mesmerprism.rustyquest.spatial_camera_panel

// SDK/renderer/JNI leaves only. The runner inserts the actual Activity binding,
// projection launch method and spatial shutdown hook at the placeholders below.
class HostScene {
  var live = true
  var captures = 0
  fun requireLive() = check(live) { "SDK already invalidated" }
}
class SpatialNativeInteropProbe {
  val openXrInstanceHandle = 1L
  val openXrSessionHandle = 2L
  val openXrGetInstanceProcAddrHandle = 3L
  companion object {
    fun capture(scene: HostScene): SpatialNativeInteropProbe {
      scene.requireLive()
      scene.captures++
      return SpatialNativeInteropProbe()
    }
  }
}
class HostInterop {
  val receiptLibraryLoaded = true
  val receiptLibraryError = ""
}
class HostCarrier {
  var runs = 0
  fun cleanup(reason: String) {}
  fun run(readerMaxImages: Int, settings: SpatialVideoProjectionSettings) { runs++ }
  fun retireScene(reason: String) {}
}
class HostCarrierState(val panel: Boolean) {
  fun resetForLaunch() {}
  fun scenePanelCarrierEnabled() = panel
}
class HostControls {
  fun resetForLaunch() {}
  fun resetMarkerCadence() {}
  fun clearNativeLayerOverrideLifecycle() {}
  fun initializeDepthLayerPolicy(value: Int) {}
  fun initializeGuideProcessing(value: Int) {}
  fun setPrivateLayerVisibleFlag(value: Boolean) {}
  fun retireScene(reason: String) {}
}
class HostVideo {
  val activeOfflinePack: Any? = null
  fun adoptSettings(settings: SpatialVideoProjectionSettings, pack: Any?) {}
  fun retireScene(reason: String) {}
}
class SpatialVideoProjectionSettings(val source: String = "fixture")
object SpatialImmersiveVideoSessionPolicy { const val CUSTOM_PROJECTION_SOURCE = "custom" }
open class HostSdkActivity(val scene: HostScene = HostScene()) {
  open fun onSpatialShutdown() { scene.live = false }
}
class HostLocatorRegistry {
  var activeOwner = 0L
  var latestOwner = 0L
  fun configure(owner: Long): Long {
    if (owner < latestOwner) return 3L
    latestOwner = owner
    if (activeOwner != 0L && activeOwner != owner) return 3L
    activeOwner = owner
    return 16L
  }
  fun release(owner: Long): Long {
    if (owner < latestOwner) return 0L
    latestOwner = owner
    if (activeOwner != owner) return 0L
    activeOwner = 0L
    return 1L
  }
}
internal class HostActivity(panel: Boolean, val registry: HostLocatorRegistry = HostLocatorRegistry()) : HostSdkActivity() {
  val properties = mutableMapOf<String, String>()
  var configures = 0
  var releases = 0
  var configuredOwner = 0L
  var spatialSceneReady = false
  var embeddedDuplexActivityDestroying = false
  var cameraHwbProjectionEntity: Any? = null
  val nativeInteropCoordinator = HostInterop()
  val privateLayerControlCoordinator = HostControls()
  val cameraHwbProjectionPanelCarrierCoordinator = HostCarrier()
  val cameraHwbProjectionRawCarrierCoordinator = HostCarrier()
  val cameraHwbProjectionCarrierStateCoordinator = HostCarrierState(panel)
  val cameraHwbProjectionTuningCoordinator = HostControls()
  val cameraHwbProjectionPlacementUpdateCoordinator = HostControls()
  val panelPlacementStateCoordinator = HostControls()
  val spatialVideoProjectionRuntimeCoordinator = HostVideo()
  val immersiveVideoPanelCoordinator = HostVideo()
  val backgroundImmersiveVideoPanelCoordinator = HostVideo()
  val spatialVirtualRoomModule = HostControls()
  val stagedAssetModule = HostControls()
  fun activityReadSystemProperty(key: String) = properties[key] ?: ""
  fun nativeUpdateCameraLatencyDiagnostics(vararg values: Any): Long = 0L
  fun nativeUpdateCameraLatencyViewerPose(vararg values: Any): Long = 0L
  fun nativeConfigureCameraLatencyOpenXrHandles(owner: Long, instance: Long, session: Long, getProc: Long): Long {
    scene.requireLive()
    check(owner > 0L && instance == 1L && session == 2L && getProc == 3L)
    configures++
    configuredOwner = owner
    return registry.configure(owner)
  }
  fun nativeReleaseCameraLatencyOpenXrHandles(owner: Long, retiring: Boolean): Long {
    scene.requireLive()
    check(owner > 0L && retiring)
    releases++
    return registry.release(owner)
  }
  fun marker(value: String) {}
  fun cleanupSdkQuadSurfaceProbe(reason: String) = "destroyed"
  fun initialPrivateLayerDepthLayerPolicy() = 0
  fun initialPrivateLayerGuideProcessing() = 0
  fun suppressParticleLayerForCameraStack(reason: String) {}
  fun setPrivateLayerPanelVisible(visible: Boolean, focus: Boolean, source: String) {}
  fun pollPendingControlProfileAfterProjectionStart() {}
  fun ready() { spatialSceneReady = true; cameraLatencyDiagnosticModule.onSpatialReady() }
  fun launch() = runCameraHwbProjectionProbe(4, SpatialVideoProjectionSettings())
  // ACTUAL_BINDING
  // ACTUAL_PROJECTION_LAUNCH
  // ACTUAL_SPATIAL_SHUTDOWN
}

fun activityLifetimeControls() {
  val modes = listOf(false to "openxr-locate-views", true to "scene-tick-latest", true to "scene-extrapolated", true to "openxr-locate-views")
  for (panel in listOf(false, true)) {
    for ((enabled, mode) in modes) {
      val activity = HostActivity(panel)
      activity.properties[CAMERA_LATENCY_ENABLED_PROPERTY] = enabled.toString()
      activity.properties[CAMERA_LATENCY_PRESENTATION_POSE_MODE_PROPERTY] = mode
      activity.ready()
      activity.launch()
      val selected = enabled && mode == "openxr-locate-views"
      check(activity.scene.captures == if (selected) 1 else 0)
      check(activity.configures == if (selected) 1 else 0)
      check(activity.cameraHwbProjectionPanelCarrierCoordinator.runs == if (panel) 1 else 0)
      check(activity.cameraHwbProjectionRawCarrierCoordinator.runs == if (panel) 0 else 1)
      activity.onSpatialShutdown()
      check(!activity.scene.live && activity.releases == if (selected) 1 else 0)
      activity.onSpatialShutdown()
      check(activity.releases == if (selected) 1 else 0)
    }
  }
  val registry = HostLocatorRegistry()
  val old = HostActivity(true, registry)
  val newer = HostActivity(true, registry)
  check(old.configures == 0 && newer.configures == 0 && old.scene.captures == 0 && newer.scene.captures == 0)
  for (activity in listOf(old, newer)) {
    activity.properties[CAMERA_LATENCY_ENABLED_PROPERTY] = "true"
    activity.properties[CAMERA_LATENCY_PRESENTATION_POSE_MODE_PROPERTY] = "openxr-locate-views"
  }
  newer.ready()
  newer.launch()
  val newerOwner = newer.configuredOwner
  check(registry.activeOwner == newerOwner)
  old.ready()
  old.launch()
  check(old.configuredOwner < newerOwner) { "Owner IDs must follow Activity construction, not first readiness" }
  old.onSpatialShutdown()
  check(registry.activeOwner == newerOwner && registry.latestOwner == newerOwner && newer.releases == 0)
  newer.launch()
  check(registry.activeOwner == newerOwner)
  newer.onSpatialShutdown()
  check(registry.activeOwner == 0L && newer.releases == 1)
}
