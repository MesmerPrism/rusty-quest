package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpatialPackedStereoFrameIdentityTest {
  @Test
  fun outputMetadataRequiresAnExactPresentationTimestamp() {
    val pending = SpatialPackedStereoBrokerPlayback.PendingPairIdentities()
    pending.register(100L, pair(1L))
    pending.register(300L, pair(3L))

    expectIOException { pending.takeExact(200L) }
    assertEquals(2, pending.size())
    assertEquals(3L, pending.takeExact(300L).pairId)
    assertEquals(1L, pending.takeExact(100L).pairId)
  }

  @Test
  fun duplicateAndOverflowAreRejectedWithoutDiscardingAnExistingIdentity() {
    val duplicate = SpatialPackedStereoBrokerPlayback.PendingPairIdentities()
    duplicate.register(10L, pair(1L))
    expectIOException { duplicate.register(10L, pair(2L)) }
    assertEquals(1L, duplicate.takeExact(10L).pairId)

    val bounded = SpatialPackedStereoBrokerPlayback.PendingPairIdentities()
    repeat(128) { index -> bounded.register(index.toLong(), pair(index.toLong() + 1L)) }
    expectIOException { bounded.register(128L, pair(129L)) }
    assertEquals(128, bounded.size())
  }

  @Test
  fun timestampConversionIsCheckedAndDecoderTokensAreUniquePerStart() {
    assertEquals(123_000L, SpatialPackedStereoBrokerPlayback.outputTimestampNs(123L))
    expectIOException {
      SpatialPackedStereoBrokerPlayback.outputTimestampNs(Long.MAX_VALUE)
    }

    val first = SpatialStereoVideoPlayback.nextDecoderToken()
    val second = SpatialStereoVideoPlayback.nextDecoderToken()
    assertTrue(first > 0L)
    assertTrue(second > 0L)
    assertNotEquals(first, second)
  }

  @Test
  fun nativeReaderCapacityAlwaysLeavesAcquireLatestDiscardMargin() {
    assertEquals(4, SpatialStereoVideoPlayback.normalizeMaxImages(Int.MIN_VALUE))
    assertEquals(4, SpatialStereoVideoPlayback.normalizeMaxImages(3))
    assertEquals(4, SpatialStereoVideoPlayback.normalizeMaxImages(4))
    assertEquals(6, SpatialStereoVideoPlayback.normalizeMaxImages(Int.MAX_VALUE))
  }

  private fun pair(pairId: Long) =
      SpatialPackedStereoBrokerPlayback.PairRecord(
          pairId,
          pairId * 2L,
          pairId * 2L + 1L,
          10_000L + pairId,
          10_002L + pairId,
          2L,
      )

  private fun expectIOException(block: () -> Unit) {
    try {
      block()
      fail("expected IOException")
    } catch (_: IOException) {
      // Expected fail-closed result.
    }
  }
}
