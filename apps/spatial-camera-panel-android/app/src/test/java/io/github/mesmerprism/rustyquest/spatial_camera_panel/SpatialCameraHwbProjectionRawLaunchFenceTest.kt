package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpatialCameraHwbProjectionRawLaunchFenceTest {
  @Test
  fun firstLayerBindsChallengeGenerationAndActiveStateWithoutInventingASwitch() {
    val continuity = SpatialCameraHwbProjectionRawLayerContinuity()
    continuity.beginLaunch(41L)

    val fence = continuity.recordLayerCreated(41L)

    assertEquals(41L, fence.launchChallenge)
    assertEquals(1L, fence.layerGeneration)
    assertEquals(0L, fence.layerSwitchCount)
    assertEquals(
        SpatialCameraHwbProjectionRawLayerState.RAW_SCENE_QUAD_ACTIVE,
        fence.layerState,
    )
  }

  @Test
  fun secondLayerInTheSameLaunchRecordsAnActualSwitch() {
    val continuity = SpatialCameraHwbProjectionRawLayerContinuity()
    continuity.beginLaunch(43L)
    continuity.recordLayerCreated(43L)

    val switched = continuity.recordLayerCreated(43L)

    assertEquals(2L, switched.layerGeneration)
    assertEquals(1L, switched.layerSwitchCount)
  }

  @Test
  fun removalAndReplacementRemainInOneMonotonicLifecycle() {
    val continuity = SpatialCameraHwbProjectionRawLayerContinuity()
    continuity.beginLaunch(44L)
    continuity.recordLayerCreated(44L)

    val removed = requireNotNull(continuity.recordLayerRemoved())
    assertEquals(1L, removed.layerGeneration)
    assertEquals(1L, removed.layerSwitchCount)
    assertEquals(SpatialCameraHwbProjectionRawLayerState.ABSENT, removed.layerState)
    assertEquals(null, continuity.recordLayerRemoved())

    val replacement = continuity.recordLayerCreated(44L)
    assertEquals(2L, replacement.layerGeneration)
    assertEquals(1L, replacement.layerSwitchCount)
    assertEquals(
        SpatialCameraHwbProjectionRawLayerState.RAW_SCENE_QUAD_ACTIVE,
        replacement.layerState,
    )
  }

  @Test
  fun newLaunchResetsPerLaunchCountersButUsesANewChallenge() {
    val continuity = SpatialCameraHwbProjectionRawLayerContinuity()
    continuity.beginLaunch(44L)
    continuity.recordLayerCreated(44L)
    val removed = requireNotNull(continuity.recordLayerRemoved())

    continuity.beginLaunch(45L)
    val nextLaunch = continuity.recordLayerCreated(45L)

    assertEquals(44L, removed.launchChallenge)
    assertEquals(1L, removed.layerGeneration)
    assertEquals(1L, removed.layerSwitchCount)
    assertEquals(45L, nextLaunch.launchChallenge)
    assertEquals(1L, nextLaunch.layerGeneration)
    assertEquals(0L, nextLaunch.layerSwitchCount)
    assertEquals(
        SpatialCameraHwbProjectionRawLayerState.RAW_SCENE_QUAD_ACTIVE,
        nextLaunch.layerState,
    )
  }

  @Test
  fun staleOrMissingLaunchChallengeIsRejected() {
    val continuity = SpatialCameraHwbProjectionRawLayerContinuity()
    assertThrows(IllegalArgumentException::class.java) { continuity.beginLaunch(0L) }
    continuity.beginLaunch(47L)
    assertThrows(IllegalArgumentException::class.java) {
      continuity.recordLayerCreated(46L)
    }
  }
}
