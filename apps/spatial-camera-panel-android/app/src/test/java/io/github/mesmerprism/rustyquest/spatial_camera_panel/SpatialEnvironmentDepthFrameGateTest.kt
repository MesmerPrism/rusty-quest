package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialEnvironmentDepthFrameGateTest {
  @Test
  fun acquiresOnlyOnceForEachNewlyWaitedPositiveFrame() {
    val gate = SpatialEnvironmentDepthFrameGate()

    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(0L, 0L)))
    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(-1L, 1L)))
    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_000L, -1L)))
    assertTrue(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_000L, 100L)))
    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_000L, 100L)))
    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(2_000L, 100L)))
    assertTrue(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(2_000L, 200L)))
    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(3_000L, 200L)))
    assertTrue(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(3_000L, 300L)))
  }

  @Test
  fun aggressiveRecoveryRetriesEveryPositiveEcsTickIncludingDuplicateWaitFrameIdentity() {
    val gate = SpatialEnvironmentDepthFrameGate()
    val aggressive = SpatialEnvironmentDepthRecoveryPolicy.Aggressive

    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(0L, 100L), aggressive))
    assertFalse(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_000L, 0L), aggressive))
    assertTrue(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_000L, 100L), aggressive))
    assertTrue(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_100L, 100L), aggressive))
    assertTrue(gate.shouldAcquire(SpatialEnvironmentDepthFrameTiming(1_200L, 100L), aggressive))
  }
}
