package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpatialCameraLatencyDiagnosticModuleTest {
  private class LifetimeFixture {
    val properties = mutableMapOf<String, String>()
    val configuredOwners = mutableListOf<Long>()
    val releases = mutableListOf<Pair<Long, Boolean>>()
    var sdkLive = true
    var applies = 0
    var configures = 0
    var nowMs = 0L
    var beforeConfigure: () -> Unit = {}
    var beforeRelease: () -> Unit = {}
    var configureResult = 0L
    var releaseResult = 1L
    val module = SpatialCameraLatencyDiagnosticModule(
        SpatialCameraLatencyDiagnosticBindings(
            readSystemProperty = { properties[it] ?: "" },
            applyNative = { applies++; 0L },
            recordViewerPose = { _, _ -> error("unexpected viewer pose") },
            marker = {},
            configureOpenXr = { owner ->
              check(sdkLive)
              beforeConfigure()
              configures++
              configuredOwners += owner
              configureResult
            },
            releaseOpenXr = { owner, retiring ->
              check(sdkLive)
              beforeRelease()
              releases += owner to retiring
              releaseResult
            },
            elapsedRealtimeMs = { nowMs },
        )
    )

    fun mode(enabled: Boolean, presentation: String, revision: Int) {
      properties[CAMERA_LATENCY_ENABLED_PROPERTY] = enabled.toString()
      properties[CAMERA_LATENCY_PRESENTATION_POSE_MODE_PROPERTY] = presentation
      properties[CAMERA_LATENCY_REVISION_PROPERTY] = revision.toString()
      module.poll("fixture", force = true)
    }
  }

  @Test
  fun fallbackModesNeverAcquireOptionalOpenXrLifetime() {
    val fallbackModes = listOf(
        false to "openxr-locate-views",
        true to "scene-tick-latest",
        true to "scene-extrapolated",
    )
    for ((enabled, mode) in fallbackModes) {
      val f = LifetimeFixture()
      f.module.onSpatialReady()
      f.mode(enabled, mode, 1)
      f.module.onSpatialShutdown()
      f.sdkLive = false
      f.module.poll("late", force = true)
      f.module.onSpatialReady()
      f.module.onSpatialShutdown()
      check(f.configures == 0 && f.releases.isEmpty() && f.applies == 1)
    }
  }

  @Test
  fun selectedModeWaitsForSdkAndCanClearAndReenableSameOwner() {
    val f = LifetimeFixture()
    f.mode(true, "openxr-locate-views", 1)
    check(f.configures == 0)
    f.module.onSpatialReady()
    check(f.configures == 1) // reprojection defaults to off; presentation still needs the locator.
    f.mode(true, "scene-tick-latest", 2)
    check(f.releases.single().second == false)
    f.mode(true, "openxr-locate-views", 3)
    check(f.configures == 2 && f.configuredOwners.distinct().size == 1)
    f.module.onSpatialShutdown()
    check(f.releases.last() == (f.configuredOwners.singleOwner() to true))
    f.sdkLive = false
    f.module.poll("late", force = true)
    f.module.onSpatialShutdown()
    check(f.configures == 2 && f.releases.size == 2)
    val next = LifetimeFixture()
    next.module.onSpatialReady()
    next.mode(true, "openxr-locate-views", 1)
    check(next.configuredOwners.single() > f.configuredOwners.first())
    f.module.onSpatialReady()
    f.module.onSpatialShutdown()
    f.module.poll("late-old-after-new-owner", force = true)
    check(f.configures == 2 && f.releases.size == 2)
    next.module.onSpatialShutdown()
    check(next.releases.single().first == next.configuredOwners.single())
  }

  private fun List<Long>.singleOwner(): Long = distinct().single()

  @Test
  fun unchangedRevisionRetriesBusyConfigurationAfterPollThrottle() {
    val f = LifetimeFixture()
    f.module.onSpatialReady()
    f.configureResult = 3L // native busy/no configured bit; no allocation has happened.
    f.mode(true, "openxr-locate-views", 1)
    f.module.poll("throttled", force = false)
    check(f.configures == 1)
    f.nowMs = 250L
    f.module.poll("retry", force = false)
    check(f.configures == 2 && f.applies == 1)
    f.module.onSpatialShutdown()
  }

  @Test
  fun nativeLifetimeFailuresAreVisibleAndRetirementPrecedesCallback() {
    val f = LifetimeFixture()
    f.module.onSpatialReady()
    f.configureResult = -1L
    check(runCatching { f.mode(true, "openxr-locate-views", 1) }.isFailure)
    f.beforeRelease = { f.module.poll("reentrant-late", force = true) }
    f.releaseResult = -1L
    check(runCatching { f.module.onSpatialShutdown() }.isFailure)
    check(f.configures == 1 && f.releases.size == 1 && f.applies == 1)
    f.sdkLive = false
    f.module.onSpatialShutdown()
    f.module.poll("late", force = true)
    check(f.configures == 1 && f.releases.size == 1)
  }

  @Test
  fun modeReleaseFailureRetriesAndFinalRetirementStillClosesTouchedOwner() {
    val f = LifetimeFixture()
    f.module.onSpatialReady()
    f.mode(true, "openxr-locate-views", 1)
    f.releaseResult = -1L
    check(runCatching { f.mode(false, "scene-tick-latest", 2) }.isFailure)
    check(f.applies == 2)
    f.releaseResult = 1L
    f.module.poll("mode-release-retry", force = true)
    check(f.releases.size == 2 && f.releases.none { it.second })
    f.module.poll("mode-clear-is-idempotent", force = true)
    check(f.releases.size == 2)
    f.module.onSpatialShutdown()
    check(f.releases.size == 3 && f.releases.last().second)
  }

  @Test
  fun shutdownDrainsInFlightConfigureBeforeSdkInvalidation() {
    val f = LifetimeFixture()
    f.module.onSpatialReady()
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val shutdownStarted = CountDownLatch(1)
    val shutdownDone = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    f.beforeConfigure = { entered.countDown(); check(proceed.await(2, TimeUnit.SECONDS)) }
    val poll = Thread { try { f.mode(true, "openxr-locate-views", 1) } catch (t: Throwable) { failure.set(t) } }
    val shutdown = Thread {
      try {
        shutdownStarted.countDown()
        f.module.onSpatialShutdown()
        f.sdkLive = false
      } catch (t: Throwable) { failure.set(t) } finally { shutdownDone.countDown() }
    }
    poll.start()
    check(entered.await(2, TimeUnit.SECONDS))
    shutdown.start()
    check(shutdownStarted.await(2, TimeUnit.SECONDS))
    check(!shutdownDone.await(50, TimeUnit.MILLISECONDS))
    proceed.countDown()
    poll.join(2000); shutdown.join(2000)
    check(!poll.isAlive && !shutdown.isAlive && failure.get() == null)
    check(f.releases.single().second && !f.sdkLive)
    f.module.poll("late", force = true)
    check(f.configures == 1)
  }

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
                CAMERA_LATENCY_REPROJECTION_SOURCE_OVERSCAN_PERCENT_PROPERTY to "10",
                CAMERA_LATENCY_REPROJECTION_GUARD_BAND_MODE_PROPERTY to "reduced-footprint",
                CAMERA_LATENCY_PRESENTATION_POSE_MODE_PROPERTY to "openxr-locate-views",
                CAMERA_LATENCY_PRESENTATION_LEAD_MS_PROPERTY to "11",
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
    assertEquals(10, parsed.settings?.reprojectionSourceOverscanPercent)
    assertEquals(
        SpatialCameraLatencyReprojectionGuardBandMode.ReducedFootprint,
        parsed.settings?.reprojectionGuardBandMode,
    )
    assertEquals(
        SpatialCameraLatencyPresentationPoseMode.OpenXrLocateViews,
        parsed.settings?.presentationPoseMode,
    )
    assertEquals(11, parsed.settings?.presentationLeadMs)
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
  fun outOfRangeReprojectionSourceOverscanIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "57",
                CAMERA_LATENCY_REPROJECTION_SOURCE_OVERSCAN_PERCENT_PROPERTY to "21",
            )
        )

    assertEquals("invalid-reprojection-source-overscan-percent", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun unknownReprojectionGuardBandModeIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "58",
                CAMERA_LATENCY_REPROJECTION_GUARD_BAND_MODE_PROPERTY to "shrink-somehow",
            )
        )

    assertEquals("invalid-reprojection-guard-band-mode", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun dynamicReprojectionGuardBandModeIsAccepted() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "59",
                CAMERA_LATENCY_REPROJECTION_SOURCE_OVERSCAN_PERCENT_PROPERTY to "10",
                CAMERA_LATENCY_REPROJECTION_GUARD_BAND_MODE_PROPERTY to
                    "dynamic-reduced-footprint",
            )
        )

    assertNull(parsed.error)
    assertEquals(
        SpatialCameraLatencyReprojectionGuardBandMode.DynamicReducedFootprint,
        parsed.settings?.reprojectionGuardBandMode,
    )
  }

  @Test
  fun unknownPresentationPoseModeIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "55",
                CAMERA_LATENCY_PRESENTATION_POSE_MODE_PROPERTY to "guess-at-photons",
            )
        )

    assertEquals("invalid-presentation-pose-mode", parsed.error)
    assertNull(parsed.settings)
  }

  @Test
  fun outOfRangePresentationLeadIsRejectedClosed() {
    val parsed =
        parseSpatialCameraLatencyDiagnosticSettings(
            mapOf(
                CAMERA_LATENCY_REVISION_PROPERTY to "56",
                CAMERA_LATENCY_PRESENTATION_LEAD_MS_PROPERTY to "31",
            )
        )

    assertEquals("invalid-presentation-lead-ms", parsed.error)
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
