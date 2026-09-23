package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProjectionProfileApplyOrderTest {
  @Test
  fun preloadsOnlyWhenProjectionChangesFromDisabledToEnabled() {
    assertTrue(
        SpatialProjectionProfileApplyOrder.preloadBeforeEnable(
            currentlyEnabled = false,
            requestedEnabled = true,
        )
    )
    assertFalse(
        SpatialProjectionProfileApplyOrder.preloadBeforeEnable(
            currentlyEnabled = true,
            requestedEnabled = false,
        )
    )
    assertFalse(
        SpatialProjectionProfileApplyOrder.preloadBeforeEnable(
            currentlyEnabled = true,
            requestedEnabled = true,
        )
    )
    assertFalse(
        SpatialProjectionProfileApplyOrder.preloadBeforeEnable(
            currentlyEnabled = false,
            requestedEnabled = false,
        )
    )
  }
}
