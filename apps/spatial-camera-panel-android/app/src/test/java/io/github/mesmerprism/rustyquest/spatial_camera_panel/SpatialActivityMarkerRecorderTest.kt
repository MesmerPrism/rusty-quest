package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialActivityMarkerRecorderTest {
  @Test
  fun exactRecurringRowsAreDeduplicatedWithinTheBoundedWindow() {
    val policy = SpatialActivityMarkerDeduplicator(minimumRepeatMs = 10_000L)

    assertTrue(policy.shouldRecord("stable", nowMs = 1_000L))
    assertFalse(policy.shouldRecord("stable", nowMs = 10_999L))
    assertTrue(policy.shouldRecord("stable", nowMs = 11_000L))
    assertTrue(policy.shouldRecord("changed", nowMs = 11_001L))
    assertTrue(activityMarkerIsPeriodic("channel=panel status=controller-input-route-ready"))
    assertTrue(activityMarkerIsPeriodic("channel=panel status=spatial-input-enabled"))
    assertFalse(activityMarkerIsPeriodic("channel=playlist status=item-advanced"))
  }

  @Test
  fun oversizedEvidenceIsPreservedByRefusingFurtherRows() {
    val cap = SpatialActivityMarkerRecorder.MAXIMUM_FILE_BYTES

    assertTrue(activityMarkerFileAccepts(cap - 4L, appendedBytes = 4))
    assertFalse(activityMarkerFileAccepts(cap - 4L, appendedBytes = 5))
    assertFalse(activityMarkerFileAccepts(cap + 1L, appendedBytes = 1))
  }
}
