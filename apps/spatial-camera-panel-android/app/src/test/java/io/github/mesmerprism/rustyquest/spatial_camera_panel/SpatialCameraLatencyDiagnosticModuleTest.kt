package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpatialCameraLatencyDiagnosticModuleTest {
  @Test
  fun validFrozenNonBlockingProfileIsAccepted() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_ENABLED_PROPERTY to "true",
                CAMERA_LATENCY_REVISION_PROPERTY to "42",
                CAMERA_LATENCY_POSE_MODE_PROPERTY to "frozen-world",
                CAMERA_LATENCY_FRAME_WAIT_MS_PROPERTY to "0",
                CAMERA_LATENCY_SUMMARY_MS_PROPERTY to "500",
                CAMERA_LATENCY_FRAME_LOG_PROPERTY to "false",
                CAMERA_LATENCY_PRESENT_MODE_PROPERTY to "auto-low-latency",
                CAMERA_LATENCY_IMAGE_COUNT_PROPERTY to "min-safe",
                CAMERA_LATENCY_CAPTURE_FPS_PROPERTY to "45",
                CAMERA_LATENCY_CAMERA_SYNC_MODE_PROPERTY to "hold-image-until-gpu-fence",
                CAMERA_LATENCY_CAPTURE_PROCESSING_PROPERTY to "noise-edge-off",
                CAMERA_LATENCY_ADOPTION_CADENCE_PROPERTY to "display-aligned-45",
                CAMERA_LATENCY_STEREO_POLICY_PROPERTY to "strict-timestamp-pair",
                CAMERA_LATENCY_ISOLATION_MODE_PROPERTY to "opaque-camera-only",
                CAMERA_LATENCY_FREEZE_FRAME_PROPERTY to "true",
                CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY to "rotation-only-raw-layer",
                CAMERA_LATENCY_ASSUMED_CAPTURE_AGE_MS_PROPERTY to "60",
                CAMERA_LATENCY_REPROJECTION_FOV_DEGREES_PROPERTY to "95",
            )
        )

    assertNull(parsed.error)
    assertEquals(42L, parsed.settings?.revision)
    assertEquals(SpatialCameraLatencyPoseMode.FrozenWorld, parsed.settings?.poseMode)
    assertEquals(0, parsed.settings?.frameWaitMs)
    assertEquals(SpatialCameraLatencyPresentMode.AutoLowLatency, parsed.settings?.presentMode)
    assertEquals(SpatialCameraLatencyImageCount.MinSafe, parsed.settings?.imageCount)
    assertEquals(SpatialCameraLatencyCaptureFps.Fps45, parsed.settings?.captureFps)
    assertEquals(
        SpatialCameraLatencyCameraSyncMode.HoldImageUntilGpuFence,
        parsed.settings?.cameraSyncMode,
    )
    assertEquals(
        SpatialCameraLatencyCaptureProcessing.NoiseEdgeOff,
        parsed.settings?.captureProcessing,
    )
    assertEquals(
        SpatialCameraLatencyAdoptionCadence.DisplayAligned45,
        parsed.settings?.adoptionCadence,
    )
    assertEquals(
        SpatialCameraLatencyStereoPolicy.StrictTimestampPair,
        parsed.settings?.stereoPolicy,
    )
    assertEquals(
        SpatialCameraLatencyIsolationMode.OpaqueCameraOnly,
        parsed.settings?.isolationMode,
    )
    assertEquals(true, parsed.settings?.freezeFrame)
    assertEquals(
        SpatialCameraLatencyReprojectionMode.RotationOnlyAssumedAge,
        parsed.settings?.reprojectionMode,
    )
    assertEquals(60, parsed.settings?.assumedCaptureAgeMs)
    assertEquals(95, parsed.settings?.reprojectionFovDegrees)
  }

  @Test
  fun unknownPresentModeIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "43",
                CAMERA_LATENCY_PRESENT_MODE_PROPERTY to "fastest-maybe",
            )
        )

    assertEquals("invalid-present-mode", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun outOfRangeFrameWaitIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "44",
                CAMERA_LATENCY_FRAME_WAIT_MS_PROPERTY to "11",
            )
        )

    assertEquals("invalid-frame-wait-ms", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun invalidReprojectionFovIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "45",
                CAMERA_LATENCY_REPROJECTION_FOV_DEGREES_PROPERTY to "131",
            )
        )

    assertEquals("invalid-reprojection-fov", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun unknownAdoptionCadenceIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "46",
                CAMERA_LATENCY_ADOPTION_CADENCE_PROPERTY to "drop-whenever",
            )
        )

    assertEquals("invalid-adoption-cadence", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun unknownCameraSyncModeIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "47",
                CAMERA_LATENCY_CAMERA_SYNC_MODE_PROPERTY to "trust-the-buffer",
            )
        )

    assertEquals("invalid-camera-sync-mode", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun unknownCaptureProcessingIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "48",
                CAMERA_LATENCY_CAPTURE_PROCESSING_PROPERTY to "maximum-magic",
            )
        )

    assertEquals("invalid-capture-processing", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun freshFrameOnlyPulseIsAcceptedAsLiveIsolationMode() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_ENABLED_PROPERTY to "true",
                CAMERA_LATENCY_REVISION_PROPERTY to "49",
                CAMERA_LATENCY_ISOLATION_MODE_PROPERTY to "fresh-frame-only-pulse",
            )
        )

    assertNull(parsed.error)
    assertEquals(
        SpatialCameraLatencyIsolationMode.FreshFrameOnlyPulse,
        parsed.settings?.isolationMode,
    )
  }

  @Test
  fun sensorTimestampReprojectionDirectionsAreAccepted() {
    val forward =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "50",
                CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY to
                    "rotation-only-sensor-timestamp",
            )
        )
    val inverse =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "51",
                CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY to
                    "rotation-only-sensor-timestamp-inverse",
            )
        )
    val rollFree =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "52",
                CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY to
                    "rotation-only-sensor-timestamp-inverse-roll-free",
            )
        )
    val yawOnly =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "53",
                CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY to
                    "rotation-only-sensor-timestamp-inverse-yaw-only",
            )
        )
    val cameraCalibrated =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "54",
                CAMERA_LATENCY_REPROJECTION_MODE_PROPERTY to
                    "rotation-only-sensor-timestamp-camera-calibrated",
            )
        )

    assertNull(forward.error)
    assertEquals(
        SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestamp,
        forward.settings?.reprojectionMode,
    )
    assertNull(inverse.error)
    assertEquals(
        SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampInverse,
        inverse.settings?.reprojectionMode,
    )
    assertNull(rollFree.error)
    assertEquals(
        SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampInverseRollFree,
        rollFree.settings?.reprojectionMode,
    )
    assertNull(yawOnly.error)
    assertEquals(
        SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampInverseYawOnly,
        yawOnly.settings?.reprojectionMode,
    )
    assertNull(cameraCalibrated.error)
    assertEquals(
        SpatialCameraLatencyReprojectionMode.RotationOnlySensorTimestampCameraCalibrated,
        cameraCalibrated.settings?.reprojectionMode,
    )
  }
}
