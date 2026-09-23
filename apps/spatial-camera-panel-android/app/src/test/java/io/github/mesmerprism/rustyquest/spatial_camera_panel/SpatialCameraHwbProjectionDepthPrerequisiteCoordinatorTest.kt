package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCameraHwbProjectionDepthPrerequisiteCoordinatorTest {
  @Test
  fun unusedDepthProviderNeverStartsAndActiveProviderStopsAtConsumerBoundary() {
    var starts = 0
    var stops = 0
    var passthroughStarts = 0
    var passthroughStops = 0
    var frameAcquires = 0
    val markers = ArrayList<String>()
    val coordinator =
        SpatialCameraHwbProjectionDepthPrerequisiteCoordinator(
            SpatialCameraHwbProjectionDepthPrerequisiteBindings(
                routeActive = { true },
                environmentDepthOwner = {
                  SpatialEnvironmentDepthOwner.LegacyNativeSidecar
                },
                nativeState = {
                  SpatialCameraHwbProjectionDepthPrerequisiteNativeState(
                      receiptLibraryLoaded = true,
                      receiptLibraryError = "",
                  )
                },
                captureInteropProbe = {
                  SpatialNativeInteropProbe("test", 1L, 2L, 3L)
                },
                requiredOpenXrExtensions = { "XR_META_environment_depth" },
                projectionEntityPresent = { true },
                startNativePassthrough = { _, _, _ ->
                  passthroughStarts += 1
                  1L shl 10
                },
                stopNativePassthrough = {
                  passthroughStops += 1
                  1L
                },
                startNativeEnvironmentDepth = { _, _, _ ->
                  starts += 1
                  (1L shl 22) or (1L shl 23)
                },
                stopNativeEnvironmentDepth = {
                  stops += 1
                  1L
                },
                marker = markers::add,
            )
        )

    assertEquals(0L, coordinator.startEnvironmentDepth("unused"))
    assertEquals(0L, coordinator.startPassthrough("unused"))
    assertEquals(0, starts)
    assertEquals(0, passthroughStarts)
    coordinator.acquireEnvironmentDepthFrameIfRequired(1_000L) { _, _ ->
      frameAcquires += 1
      1L
    }
    assertEquals(0, frameAcquires)

    coordinator.updateEnvironmentDepthConsumer(true, "depth-layer")
    coordinator.startEnvironmentDepth("depth-layer-retained")
    assertEquals(1, starts)
    assertEquals(1, passthroughStarts)
    coordinator.acquireEnvironmentDepthFrameIfRequired(2_000L) { _, _ ->
      frameAcquires += 1
      1L
    }
    assertEquals(1, frameAcquires)

    coordinator.updateEnvironmentDepthConsumer(false, "analysis-layer")
    assertEquals(1, stops)
    assertEquals(1, passthroughStops)
    coordinator.acquireEnvironmentDepthFrameIfRequired(3_000L) { _, _ ->
      frameAcquires += 1
      1L
    }
    assertEquals(1, frameAcquires)
    assertTrue(markers.any { it.contains("environmentDepthZeroContributionWorkSkipped=true") })
    assertTrue(markers.any { it.contains("systemPassthroughConflictAvoided=true") })
    assertFalse(markers.any { it.contains("runtimeCrash=true") })
  }

  @Test
  fun callOrderFailureRetainsLastValidDepthAndBothRecoveryPoliciesKeepAcquiring() {
    var starts = 0
    var stops = 0
    var frameAcquires = 0
    val markers = ArrayList<String>()
    val coordinator =
        SpatialCameraHwbProjectionDepthPrerequisiteCoordinator(
            SpatialCameraHwbProjectionDepthPrerequisiteBindings(
                routeActive = { true },
                environmentDepthOwner = {
                  SpatialEnvironmentDepthOwner.LegacyNativeSidecar
                },
                nativeState = {
                  SpatialCameraHwbProjectionDepthPrerequisiteNativeState(
                      receiptLibraryLoaded = true,
                      receiptLibraryError = "",
                  )
                },
                captureInteropProbe = {
                  SpatialNativeInteropProbe("test", 10L, 20L, 30L)
                },
                requiredOpenXrExtensions = { "XR_META_environment_depth" },
                projectionEntityPresent = { true },
                startNativePassthrough = { _, _, _ -> 0L },
                stopNativePassthrough = { 0L },
                startNativeEnvironmentDepth = { _, _, _ ->
                  starts += 1
                  (1L shl 22) or (1L shl 23)
                },
                stopNativeEnvironmentDepth = {
                  stops += 1
                  1L
                },
                marker = markers::add,
            )
        )

    coordinator.updateEnvironmentDepthConsumer(true, "final-layer")
    assertEquals(1, starts)

    coordinator.acquireEnvironmentDepthFrameIfRequired(900L) { _, aggressive ->
      assertFalse(aggressive)
      frameAcquires += 1
      1L or (1L shl 1)
    }
    coordinator.acquireEnvironmentDepthFrameIfRequired(1_000L) { _, aggressive ->
      assertFalse(aggressive)
      frameAcquires += 1
      1L or (1L shl 3) or (1L shl 4)
    }
    assertEquals(2, frameAcquires)
    assertEquals(0, stops)
    assertTrue(
        coordinator.environmentDepthUnavailableWarning()?.contains("last valid world-depth frame") ==
            true
    )

    coordinator.acquireEnvironmentDepthFrameIfRequired(2_000L) { _, aggressive ->
      assertFalse(aggressive)
      frameAcquires += 1
      1L
    }
    assertEquals(3, frameAcquires)
    assertEquals(1, starts)
    assertTrue(
        markers.any {
          it.contains("status=acquire-recoverable-error") &&
              it.contains("environmentDepthAcquisitionQuarantined=false") &&
              it.contains("environmentDepthFallbackBinding=last-valid-depth")
        }
    )

    assertEquals(
        SpatialEnvironmentDepthRecoveryPolicy.Aggressive,
        coordinator.updateEnvironmentDepthRecoveryPolicy(
            SpatialEnvironmentDepthRecoveryPolicy.Aggressive,
            "test-aggressive",
        ),
    )
    coordinator.acquireEnvironmentDepthFrameIfRequired(
        3_000L,
        SpatialEnvironmentDepthRecoveryPolicy.Aggressive,
    ) { _, aggressive ->
      assertTrue(aggressive)
      frameAcquires += 1
      1L or (1L shl 1)
    }
    assertEquals(4, frameAcquires)
    assertEquals(null, coordinator.environmentDepthUnavailableWarning())
    assertTrue(
        markers.any {
          it.contains("environmentDepthRecoveryPolicy=aggressive") &&
              it.contains("environmentDepthLastValidRetention=true")
        }
    )
    assertFalse(markers.any { it.contains("runtimeCrash=true") })
  }

  @Test
  fun sdkOwnerCannotStartOrAcquireTheLegacyProvider() {
    var starts = 0
    var acquires = 0
    val markers = ArrayList<String>()
    val coordinator =
        SpatialCameraHwbProjectionDepthPrerequisiteCoordinator(
            SpatialCameraHwbProjectionDepthPrerequisiteBindings(
                routeActive = { true },
                environmentDepthOwner = {
                  SpatialEnvironmentDepthOwner.SpatialSdkApiLayer
                },
                nativeState = {
                  SpatialCameraHwbProjectionDepthPrerequisiteNativeState(true, "")
                },
                captureInteropProbe = {
                  SpatialNativeInteropProbe("test", 10L, 20L, 30L)
                },
                requiredOpenXrExtensions = { "XR_META_environment_depth" },
                projectionEntityPresent = { true },
                startNativePassthrough = { _, _, _ -> 0L },
                stopNativePassthrough = { 0L },
                startNativeEnvironmentDepth = { _, _, _ ->
                  starts += 1
                  (1L shl 22) or (1L shl 23)
                },
                stopNativeEnvironmentDepth = { 1L },
                marker = markers::add,
            )
        )

    coordinator.updateEnvironmentDepthConsumer(true, "sdk-owner")
    coordinator.acquireEnvironmentDepthFrameIfRequired(1_000L) { _, _ ->
      acquires += 1
      1L
    }

    assertEquals(0, starts)
    assertEquals(0, acquires)
    assertTrue(
        markers.any {
          it.contains("environmentDepthOwner=spatial-sdk-api-layer") &&
              it.contains("legacyEnvironmentDepthProviderRequested=false") &&
              it.contains("exclusiveDepthOwner=true")
        }
    )
  }
}
