package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock

internal const val CAMERA_LATENCY_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.enabled"
internal const val CAMERA_LATENCY_REVISION_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.revision"
internal const val CAMERA_LATENCY_POSE_MODE_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.pose_mode"
internal const val CAMERA_LATENCY_FRAME_WAIT_MS_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.frame_wait_ms"
internal const val CAMERA_LATENCY_SUMMARY_MS_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.summary_ms"
internal const val CAMERA_LATENCY_FRAME_LOG_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.frame_log"
internal const val CAMERA_LATENCY_PRESENT_MODE_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.present_mode"
internal const val CAMERA_LATENCY_IMAGE_COUNT_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.image_count"
internal const val CAMERA_LATENCY_CAPTURE_FPS_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.capture_fps"
internal const val CAMERA_LATENCY_CAMERA_SYNC_MODE_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.camera_sync_mode"
internal const val CAMERA_LATENCY_CAPTURE_PROCESSING_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.capture_processing"
internal const val CAMERA_LATENCY_ADOPTION_CADENCE_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.adoption_cadence"
internal const val CAMERA_LATENCY_STEREO_POLICY_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.stereo_policy"
internal const val CAMERA_LATENCY_ISOLATION_MODE_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.isolation_mode"
internal const val CAMERA_LATENCY_FREEZE_FRAME_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.freeze_frame"
internal const val CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.reprojection_mode"
internal const val CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.assumed_capture_age_ms"
internal const val CAMERA_LATENCY_REPROJECTION_FOV_DEGREES_PROPERTY =
    "debug.rustyquest.spatial.camera_latency.reprojection_fov_degrees"

internal val CAMERA_LATENCY_DIAGNOSTIC_PROPERTIES =
    listOf(
        CAMERA_LATENCY_ENABLED_PROPERTY,
        CAMERA_LATENCY_REVISION_PROPERTY,
        CAMERA_LATENCY_POSE_MODE_PROPERTY,
        CAMERA_LATENCY_FRAME_WAIT_MS_PROPERTY,
        CAMERA_LATENCY_SUMMARY_MS_PROPERTY,
        CAMERA_LATENCY_FRAME_LOG_PROPERTY,
        CAMERA_LATENCY_PRESENT_MODE_PROPERTY,
        CAMERA_LATENCY_IMAGE_COUNT_PROPERTY,
        CAMERA_LATENCY_CAPTURE_FPS_PROPERTY,
        CAMERA_LATENCY_CAMERA_SYNC_MODE_PROPERTY,
        CAMERA_LATENCY_CAPTURE_PROCESSING_PROPERTY,
        CAMERA_LATENCY_ADOPTION_CADENCE_PROPERTY,
        CAMERA_LATENCY_STEREO_POLICY_PROPERTY,
        CAMERA_LATENCY_ISOLATION_MODE_PROPERTY,
        CAMERA_LATENCY_FREEZE_FRAME_PROPERTY,
        CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY,
        CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS_PROPERTY,
        CAMERA_LATENCY_REPROJECTION_FOV_DEGREES_PROPERTY,
    )

internal enum class SpatialCameraLatencyPoseMode(val markerToken: String, val nativeCode: Int) {
  CurrentViewer("current-viewer", 0),
  FrozenWorld("frozen-world", 1),
}

internal enum class SpatialCameraLatencyPresentMode(val markerToken: String, val nativeCode: Int) {
  Fifo("fifo", 0),
  MailboxIfAvailable("mailbox-if-available", 1),
  ImmediateIfAvailable("immediate-if-available", 2),
  AutoLowLatency("auto-low-latency", 3),
}

internal enum class SpatialCameraLatencyImageCount(val markerToken: String, val nativeCode: Int) {
  MinPlusOne("min-plus-one", 0),
  MinSafe("min-safe", 1),
}

internal enum class SpatialCameraLatencyCaptureFps(val markerToken: String, val nativeCode: Int) {
  CameraDefault("camera-default", 0),
  Fps30("30", 30),
  Fps45("45", 45),
  Fps50("50", 50),
  Fps60("60", 60),
}

internal enum class SpatialCameraLatencyAdoptionCadence(
    val markerToken: String,
    val nativeCode: Int,
) {
  EveryAvailable("every-available", 0),
  DisplayAligned45("display-aligned-45", 45),
}

internal enum class SpatialCameraLatencyCameraSyncMode(
    val markerToken: String,
    val nativeCode: Int,
) {
  EarlyDeleteAhbRetained("early-delete-ahb-retained", 0),
  HoldImageUntilGpuFence("hold-image-until-gpu-fence", 1),
}

internal enum class SpatialCameraLatencyCaptureProcessing(
    val markerToken: String,
    val nativeCode: Int,
) {
  TemplateDefault("template-default", 0),
  NoiseEdgeOff("noise-edge-off", 1),
}

internal enum class SpatialCameraLatencyStereoPolicy(val markerToken: String, val nativeCode: Int) {
  IndependentLatest("independent-latest", 0),
  StrictTimestampPair("strict-timestamp-pair", 1),
  MonoDuplicateLeft("mono-duplicate-left", 2),
}

internal enum class SpatialCameraLatencyIsolationMode(
    val markerToken: String,
    val nativeCode: Int,
) {
  NormalComposite("normal-composite", 0),
  OpaqueCameraOnly("opaque-camera-only", 1),
  FreshFrameOnlyPulse("fresh-frame-only-pulse", 2),
}

internal enum class SpatialCameraLatencyReprojectionMode(
    val markerToken: String,
    val nativeCode: Int,
) {
  Off("off", 0),
  RotationOnlyAssumedAge("rotation-only-raw-layer", 1),
  RotationOnlySensorTimestamp("rotation-only-sensor-timestamp", 2),
  RotationOnlySensorTimestampInverse("rotation-only-sensor-timestamp-inverse", 3),
  RotationOnlySensorTimestampInverseRollFree(
      "rotation-only-sensor-timestamp-inverse-roll-free",
      4,
  ),
  RotationOnlySensorTimestampInverseYawOnly(
      "rotation-only-sensor-timestamp-inverse-yaw-only",
      5,
  ),
  RotationOnlySensorTimestampCameraCalibrated(
      "rotation-only-sensor-timestamp-camera-calibrated",
      6,
  ),
}

internal data class SpatialCameraLatencyDiagnosticSettings(
    val enabled: Boolean = false,
    val revision: Long = 0L,
    val poseMode: SpatialCameraLatencyPoseMode = SpatialCameraLatencyPoseMode.CurrentViewer,
    val frameWaitMs: Int = 2,
    val summaryIntervalMs: Int = 1000,
    val frameLog: Boolean = false,
    val presentMode: SpatialCameraLatencyPresentMode = SpatialCameraLatencyPresentMode.Fifo,
    val imageCount: SpatialCameraLatencyImageCount = SpatialCameraLatencyImageCount.MinPlusOne,
    val captureFps: SpatialCameraLatencyCaptureFps = SpatialCameraLatencyCaptureFps.CameraDefault,
    val cameraSyncMode: SpatialCameraLatencyCameraSyncMode =
        SpatialCameraLatencyCameraSyncMode.EarlyDeleteAhbRetained,
    val captureProcessing: SpatialCameraLatencyCaptureProcessing =
        SpatialCameraLatencyCaptureProcessing.TemplateDefault,
    val adoptionCadence: SpatialCameraLatencyAdoptionCadence =
        SpatialCameraLatencyAdoptionCadence.EveryAvailable,
    val stereoPolicy: SpatialCameraLatencyStereoPolicy =
        SpatialCameraLatencyStereoPolicy.IndependentLatest,
    val isolationMode: SpatialCameraLatencyIsolationMode =
        SpatialCameraLatencyIsolationMode.NormalComposite,
    val freezeFrame: Boolean = false,
    val reprojectionMode: SpatialCameraLatencyReprojectionMode =
        SpatialCameraLatencyReprojectionMode.Off,
    val assumedCaptureAgeMs: Int = 40,
    val reprojectionFovDegrees: Int = 90,
) {
  fun markerFields(): String =
      "cameraLatencyDiagnosticEnabled=$enabled " +
          "cameraLatencyRevision=$revision " +
          "cameraLatencyPoseMode=${poseMode.markerToken} " +
          "cameraLatencyFrameWaitMs=$frameWaitMs " +
          "cameraLatencySummaryIntervalMs=$summaryIntervalMs " +
          "cameraLatencyFrameLog=$frameLog " +
          "cameraLatencyPresentModeRequested=${presentMode.markerToken} " +
          "cameraLatencyImageCountRequested=${imageCount.markerToken} " +
          "cameraLatencyCaptureFpsRequested=${captureFps.markerToken} " +
          "cameraLatencyCameraSyncRequested=${cameraSyncMode.markerToken} " +
          "cameraLatencyCaptureProcessingRequested=${captureProcessing.markerToken} " +
          "cameraLatencyAdoptionCadence=${adoptionCadence.markerToken} " +
          "cameraLatencyStereoPolicy=${stereoPolicy.markerToken} " +
          "cameraLatencyIsolationMode=${isolationMode.markerToken} " +
          "cameraLatencyFreezeFrame=$freezeFrame " +
          "cameraLatencyReprojectionMode=${reprojectionMode.markerToken} " +
          "cameraLatencyAssumedCaptureAgeMs=$assumedCaptureAgeMs " +
          "cameraLatencyReprojectionFovDegrees=$reprojectionFovDegrees"
}

internal data class SpatialCameraLatencyDiagnosticParseResult(
    val settings: SpatialCameraLatencyDiagnosticSettings? = null,
    val error: String? = null,
)

internal fun parseSpatialCameraLatencyDiagnosticSettings(
    values: Map<String, String>
): SpatialCameraLatencyDiagnosticParseResult {
  fun value(propertyName: String): String = values[propertyName].orEmpty().trim().lowercase()

  fun parseBoolean(propertyName: String, fallback: Boolean): Boolean? =
      when (val raw = value(propertyName)) {
        "" -> fallback
        "1", "true", "yes", "on", "enabled" -> true
        "0", "false", "no", "off", "disabled" -> false
        else -> null
      }

  fun parseInt(propertyName: String, fallback: Int, range: IntRange): Int? {
    val raw = value(propertyName)
    if (raw.isEmpty()) return fallback
    val parsed = raw.toIntOrNull() ?: return null
    return parsed.takeIf { it in range }
  }

  val revisionRaw = value(CAMERA_LATENCY_REVISION_PROPERTY)
  val revision = if (revisionRaw.isEmpty()) 0L else revisionRaw.toLongOrNull()
  if (revision == null || revision < 0L) {
    return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-revision")
  }
  val enabled = parseBoolean(CAMERA_LATENCY_ENABLED_PROPERTY, false)
      ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-enabled")
  val frameLog = parseBoolean(CAMERA_LATENCY_FRAME_LOG_PROPERTY, false)
      ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-frame-log")
  val frameWaitMs = parseInt(CAMERA_LATENCY_FRAME_WAIT_MS_PROPERTY, 2, 0..10)
      ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-frame-wait-ms")
  val summaryIntervalMs = parseInt(CAMERA_LATENCY_SUMMARY_MS_PROPERTY, 1000, 250..5000)
      ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-summary-ms")
  val poseMode =
      when (value(CAMERA_LATENCY_POSE_MODE_PROPERTY)) {
        "", "current-viewer" -> SpatialCameraLatencyPoseMode.CurrentViewer
        "frozen-world" -> SpatialCameraLatencyPoseMode.FrozenWorld
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-pose-mode")
      }
  val presentMode =
      when (value(CAMERA_LATENCY_PRESENT_MODE_PROPERTY)) {
        "", "fifo" -> SpatialCameraLatencyPresentMode.Fifo
        "mailbox-if-available" -> SpatialCameraLatencyPresentMode.MailboxIfAvailable
        "immediate-if-available" -> SpatialCameraLatencyPresentMode.ImmediateIfAvailable
        "auto-low-latency" -> SpatialCameraLatencyPresentMode.AutoLowLatency
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-present-mode")
      }
  val imageCount =
      when (value(CAMERA_LATENCY_IMAGE_COUNT_PROPERTY)) {
        "", "min-plus-one" -> SpatialCameraLatencyImageCount.MinPlusOne
        "min-safe" -> SpatialCameraLatencyImageCount.MinSafe
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-image-count")
      }
  val captureFps =
      when (value(CAMERA_LATENCY_CAPTURE_FPS_PROPERTY)) {
        "", "camera-default", "default" -> SpatialCameraLatencyCaptureFps.CameraDefault
        "30" -> SpatialCameraLatencyCaptureFps.Fps30
        "45" -> SpatialCameraLatencyCaptureFps.Fps45
        "50" -> SpatialCameraLatencyCaptureFps.Fps50
        "60" -> SpatialCameraLatencyCaptureFps.Fps60
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-capture-fps")
      }
  val cameraSyncMode =
      when (value(CAMERA_LATENCY_CAMERA_SYNC_MODE_PROPERTY)) {
        "", "early-delete-ahb-retained" ->
            SpatialCameraLatencyCameraSyncMode.EarlyDeleteAhbRetained
        "hold-image-until-gpu-fence" ->
            SpatialCameraLatencyCameraSyncMode.HoldImageUntilGpuFence
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-camera-sync-mode")
      }
  val captureProcessing =
      when (value(CAMERA_LATENCY_CAPTURE_PROCESSING_PROPERTY)) {
        "", "template-default" -> SpatialCameraLatencyCaptureProcessing.TemplateDefault
        "noise-edge-off" -> SpatialCameraLatencyCaptureProcessing.NoiseEdgeOff
        else ->
            return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-capture-processing")
      }
  val adoptionCadence =
      when (value(CAMERA_LATENCY_ADOPTION_CADENCE_PROPERTY)) {
        "", "every-available" -> SpatialCameraLatencyAdoptionCadence.EveryAvailable
        "display-aligned-45" -> SpatialCameraLatencyAdoptionCadence.DisplayAligned45
        else ->
            return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-adoption-cadence")
      }
  val stereoPolicy =
      when (value(CAMERA_LATENCY_STEREO_POLICY_PROPERTY)) {
        "", "independent-latest" -> SpatialCameraLatencyStereoPolicy.IndependentLatest
        "strict-timestamp-pair" -> SpatialCameraLatencyStereoPolicy.StrictTimestampPair
        "mono-duplicate-left" -> SpatialCameraLatencyStereoPolicy.MonoDuplicateLeft
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-stereo-policy")
      }
  val isolationMode =
      when (value(CAMERA_LATENCY_ISOLATION_MODE_PROPERTY)) {
        "", "normal-composite" -> SpatialCameraLatencyIsolationMode.NormalComposite
        "opaque-camera-only" -> SpatialCameraLatencyIsolationMode.OpaqueCameraOnly
        "fresh-frame-only-pulse" -> SpatialCameraLatencyIsolationMode.FreshFrameOnlyPulse
        else -> return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-isolation-mode")
      }
  val freezeFrame = parseBoolean(CAMERA_LATENCY_FREEZE_FRAME_PROPERTY, false)
      ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-freeze-frame")
  val reprojectionMode =
      when (value(CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY)) {
        "", "off" -> SpatialCameraLatencyReprojectionMode.Off
        "rotation-only-raw-layer" ->
            SpatialCameraLatencyReprojectionMode.RotationOnlyAssumedAge
        "rotation-only-sensor-timestamp" ->
            SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestamp
        "rotation-only-sensor-timestamp-inverse" ->
            SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampInverse
        "rotation-only-sensor-timestamp-inverse-roll-free" ->
            SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampInverseRollFree
        "rotation-only-sensor-timestamp-inverse-yaw-only" ->
            SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampInverseYawOnly
        "rotation-only-sensor-timestamp-camera-calibrated" ->
            SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampCameraCalibrated
        else ->
            return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-reprojection-mode")
      }
  val assumedCaptureAgeMs =
      parseInt(CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS_PROPERTY, 40, 0..120)
          ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-capture-age-ms")
  val reprojectionFovDegrees =
      parseInt(CAMERA_LATENCY_REPROJECTION_FOV_DEGREES_PROPERTY, 90, 60..130)
          ?: return SpatialCameraLatencyDiagnosticParseResult(error = "invalid-reprojection-fov")
  return SpatialCameraLatencyDiagnosticParseResult(
      settings =
          SpatialCameraLatencyDiagnosticSettings(
              enabled = enabled,
              revision = revision,
              poseMode = poseMode,
              frameWaitMs = frameWaitMs,
              summaryIntervalMs = summaryIntervalMs,
              frameLog = frameLog,
              presentMode = presentMode,
              imageCount = imageCount,
              captureFps = captureFps,
              cameraSyncMode = cameraSyncMode,
              captureProcessing = captureProcessing,
              adoptionCadence = adoptionCadence,
              stereoPolicy = stereoPolicy,
              isolationMode = isolationMode,
              freezeFrame = freezeFrame,
              reprojectionMode = reprojectionMode,
              assumedCaptureAgeMs = assumedCaptureAgeMs,
              reprojectionFovDegrees = reprojectionFovDegrees,
          )
  )
}

internal data class SpatialCameraLatencyDiagnosticBindings(
    val readSystemProperty: (String) -> String,
    val applyNative: (SpatialCameraLatencyDiagnosticSettings) -> Long,
    val recordViewerPose: (CameraHwbProjectionPlane, Long) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialCameraLatencyDiagnosticModule(
    private val bindings: SpatialCameraLatencyDiagnosticBindings
) {
  private var settings = SpatialCameraLatencyDiagnosticSettings()
  private var lastRevisionToken: String? = null
  private var lastPollMs = Long.MIN_VALUE
  private var frozenPlane: CameraHwbProjectionPlane? = null

  fun poll(reason: String, force: Boolean) {
    val nowMs = SystemClock.elapsedRealtime()
    if (!force && lastPollMs != Long.MIN_VALUE && nowMs - lastPollMs < POLL_INTERVAL_MS) {
      return
    }
    lastPollMs = nowMs
    val revisionToken = bindings.readSystemProperty(CAMERA_LATENCY_REVISION_PROPERTY).trim()
    if (revisionToken == lastRevisionToken) {
      return
    }
    val values = CAMERA_LATENCY_DIAGNOSTIC_PROPERTIES.associateWith(bindings.readSystemProperty)
    val parsed = parseSpatialCameraLatencyDiagnosticSettings(values)
    lastRevisionToken = revisionToken
    val requested = parsed.settings
    if (requested == null) {
      bindings.marker(
          "channel=camera-latency-diagnostic status=hotload-rejected " +
              "reason=${activityMarkerToken(parsed.error ?: "invalid-settings")} " +
              "revisionToken=${activityMarkerToken(revisionToken)} " +
              "transport=android-system-property-revision-last currentRevision=${settings.revision}"
      )
      return
    }
    if (requested.poseMode != settings.poseMode || !requested.enabled) {
      frozenPlane = null
    }
    val nativeMask = runCatching { bindings.applyNative(requested) }.getOrDefault(0L)
    settings = requested
    bindings.marker(
        "channel=camera-latency-diagnostic status=hotload-applied " +
            "reason=${activityMarkerToken(reason)} transport=android-system-property-revision-last " +
            "nativeUpdateMask=$nativeMask liveSafeFields=pose-mode,frame-wait-ms,summary-ms,frame-log,camera-sync-mode,adoption-cadence,stereo-policy,isolation-mode,freeze-frame,reprojection-mode,assumed-capture-age-ms,reprojection-fov-degrees " +
            "restartRequiredFields=present-mode,image-count,capture-fps,capture-processing highRatePayloadAllowed=false " +
            requested.markerFields()
    )
  }

  fun resetPoseCapture(reason: String) {
    if (frozenPlane != null) {
      frozenPlane = null
      bindings.marker(
          "channel=camera-latency-diagnostic status=frozen-pose-reset " +
              "reason=${activityMarkerToken(reason)} cameraLatencyRevision=${settings.revision}"
      )
    }
  }

  fun projectionPlane(currentPlane: () -> CameraHwbProjectionPlane): CameraHwbProjectionPlane {
    val livePlane = currentPlane()
    if (settings.enabled &&
        settings.reprojectionMode != SpatialCameraLatencyReprojectionMode.Off) {
      runCatching {
        bindings.recordViewerPose(livePlane, SystemClock.elapsedRealtimeNanos())
      }
    }
    if (!settings.enabled || settings.poseMode == SpatialCameraLatencyPoseMode.CurrentViewer) {
      frozenPlane = null
      return livePlane
    }
    val captured =
        frozenPlane
            ?: livePlane.also { plane ->
              frozenPlane = plane
              bindings.marker(
                  "channel=camera-latency-diagnostic status=frozen-pose-captured " +
                      "cameraLatencyRevision=${settings.revision} poseMode=frozen-world " +
                      "centerM=${activityVectorMarker(plane.center)} " +
                      "poseAuthority=captured-current-viewer-pose"
              )
            }
    return captured.copy(
        targetDistanceMeters = livePlane.targetDistanceMeters,
        projectionWidthMeters = livePlane.projectionWidthMeters,
        projectionHeightMeters = livePlane.projectionHeightMeters,
    )
  }

  fun markerFields(): String =
      settings.markerFields() +
          " cameraLatencyPoseFrozen=${frozenPlane != null} " +
          "cameraLatencyPoseAuthority=" +
          if (settings.enabled && settings.poseMode == SpatialCameraLatencyPoseMode.FrozenWorld) {
            "captured-world-pose"
          } else {
            "current-Scene.getViewerPose"
          }

  companion object {
    const val MODULE_ID = "spatial-camera-latency-diagnostic-module"
    private const val POLL_INTERVAL_MS = 250L
  }
}
