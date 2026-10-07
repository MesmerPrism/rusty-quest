package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialVideoSourceRouteTransitionMarkerTest {
  @Test fun timestampOnlyReadbackRefreshIsNotLoggedButPendingToEffectiveIs() {
    val marker = SpatialVideoSourceRouteTransitionMarker()
    val pending = state(SpatialVideoSourceResult.Pending, acquisitionTimeNs = 0L)
    assertNotNull(marker.markerFor(pending, "raw-carrier-resume"))
    val firstEffective = state(SpatialVideoSourceResult.Effective, acquisitionTimeNs = 100L)
    val transition = marker.markerFor(firstEffective, "raw-carrier-readback")
    assertNotNull(transition)
    assertTrue(transition.contains("pending=none"))
    assertTrue(transition.contains("effective=local"))
    assertTrue(transition.contains("receiptResult=effective"))
    assertNull(marker.markerFor(firstEffective.copy(readback = firstEffective.readback!!.copy(
        acquisitionTimeNs = 200L)), "timestamp-refresh"))
  }

  @Test fun importAndPairProgressionAreSuppressedButReaderIdentityChangeIsVisible() {
    val marker = SpatialVideoSourceRouteTransitionMarker()
    val state = state(SpatialVideoSourceResult.Effective, acquisitionTimeNs = 100L)
    marker.markerFor(state, "first")
    val importOnly = state.copy(readback = state.readback!!.copy(importGeneration = 14L, pairGeneration = 15L))
    assertNull(marker.markerFor(importOnly, "import-pair-refresh"))
    val changed = importOnly.copy(readback = importOnly.readback!!.copy(readerGeneration = 12L))
    val emitted = marker.markerFor(changed, "reader-change")
    assertNotNull(emitted)
    assertTrue(emitted.contains("readerGeneration=12"))
    assertTrue(emitted.contains("importGeneration=14"))
  }

  private fun state(result: SpatialVideoSourceResult, acquisitionTimeNs: Long): SpatialVideoSourceRoutingState {
    val pending = if (result == SpatialVideoSourceResult.Pending) SpatialVideoSource.Local else null
    val effective = if (result == SpatialVideoSourceResult.Effective) SpatialVideoSource.Local else SpatialVideoSource.Disabled
    return SpatialVideoSourceRoutingState(
        requested = SpatialVideoSource.Local,
        pending = pending,
        effective = effective,
        generation = 7L,
        readback = SpatialVideoSourceNativeReadback(
            routeGeneration = 7L, source = SpatialVideoSource.Local, decoderToken = 0L,
            readerGeneration = 11L, launchChallenge = 31L, surfaceGeneration = 9L,
            pairGeneration = 0L, importGeneration = 13L, acquisitionTimeNs = acquisitionTimeNs,
            stages = SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Local), result = result,
            reason = SpatialVideoSourceReason.None, cameraStartRequested = true,
            producerSession = 0L, producerEpoch = 0L,
        ),
    )
  }
}
