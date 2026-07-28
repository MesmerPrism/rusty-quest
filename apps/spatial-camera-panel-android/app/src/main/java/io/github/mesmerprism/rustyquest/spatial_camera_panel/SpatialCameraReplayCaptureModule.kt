package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.os.Process
import java.io.File

internal data class SpatialCameraReplayCaptureRequest(
    val enabled: Boolean,
    val outputDirectory: String,
    val requestedFrameCount: Int,
    val intervalMs: Int,
) {
  fun markerFields(nativeReceipt: Long): String =
      "cameraReplayCaptureEnabled=$enabled " +
          "cameraReplayCaptureSchema=$CAMERA_REPLAY_CAPTURE_SCHEMA " +
          "cameraReplayCaptureOutputDirectory=${activityMarkerToken(outputDirectory)} " +
          "cameraReplayCaptureRequestedFrameCount=$requestedFrameCount " +
          "cameraReplayCaptureIntervalMs=$intervalMs " +
          "cameraReplayCaptureNativeReceipt=$nativeReceipt " +
          "cameraReplayCapturePackedStereo=true cameraReplayCaptureEyeOrder=left-right " +
          "cameraReplayCapturePixelFormat=rgba8-unorm " +
          "cameraReplayCaptureMediaPlane=app-private-raw-frame-files " +
          "cameraReplayCaptureHighRateJsonPayload=false"
}

internal object SpatialCameraReplayCaptureModule {
  fun resolve(context: Context): SpatialCameraReplayCaptureRequest {
    val enabled =
        activityReadOptionalBooleanSystemProperty(CAMERA_REPLAY_CAPTURE_ENABLED_PROPERTY) == true
    if (!enabled) {
      return SpatialCameraReplayCaptureRequest(
          enabled = false,
          outputDirectory = "",
          requestedFrameCount = 0,
          intervalMs = DEFAULT_CAMERA_REPLAY_CAPTURE_INTERVAL_MS,
      )
    }
    val root = context.getExternalFilesDir(null)
    if (root == null) {
      return SpatialCameraReplayCaptureRequest(
          enabled = false,
          outputDirectory = "",
          requestedFrameCount = 0,
          intervalMs = DEFAULT_CAMERA_REPLAY_CAPTURE_INTERVAL_MS,
      )
    }
    val requestedFrameCount =
        activityReadIntSystemProperty(
            CAMERA_REPLAY_CAPTURE_FRAME_COUNT_PROPERTY,
            DEFAULT_CAMERA_REPLAY_CAPTURE_FRAME_COUNT,
            1,
            MAX_CAMERA_REPLAY_CAPTURE_FRAME_COUNT,
        )
    val intervalMs =
        activityReadIntSystemProperty(
            CAMERA_REPLAY_CAPTURE_INTERVAL_MS_PROPERTY,
            DEFAULT_CAMERA_REPLAY_CAPTURE_INTERVAL_MS,
            33,
            2_000,
        )
    val captureId = "camera-replay-${System.currentTimeMillis()}-${Process.myPid()}"
    val outputDirectory = File(File(root, CAMERA_REPLAY_CAPTURE_ROOT), captureId)
    outputDirectory.mkdirs()
    return SpatialCameraReplayCaptureRequest(
        enabled = outputDirectory.isDirectory,
        outputDirectory = outputDirectory.canonicalPath,
        requestedFrameCount = if (outputDirectory.isDirectory) requestedFrameCount else 0,
        intervalMs = intervalMs,
    )
  }
}

internal const val CAMERA_REPLAY_CAPTURE_SCHEMA = "rusty.quest.camera_replay_capture.v1"
internal const val CAMERA_REPLAY_CAPTURE_ROOT = "camera-replay"
internal const val CAMERA_REPLAY_CAPTURE_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.replay_capture.enabled"
internal const val CAMERA_REPLAY_CAPTURE_FRAME_COUNT_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.replay_capture.frame_count"
internal const val CAMERA_REPLAY_CAPTURE_INTERVAL_MS_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.replay_capture.interval_ms"
internal const val DEFAULT_CAMERA_REPLAY_CAPTURE_FRAME_COUNT = 12
internal const val DEFAULT_CAMERA_REPLAY_CAPTURE_INTERVAL_MS = 150
internal const val MAX_CAMERA_REPLAY_CAPTURE_FRAME_COUNT = 120
