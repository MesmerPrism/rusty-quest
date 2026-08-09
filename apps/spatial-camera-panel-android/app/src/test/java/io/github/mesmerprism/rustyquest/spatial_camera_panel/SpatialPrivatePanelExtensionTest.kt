package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPrivatePanelExtensionTest {
  @Test
  fun publicBuildTreatsAbsentPrivateConnectionHubTargetAsUnavailable() {
    val markers = mutableListOf<String>()

    val target = SpatialConnectionHubSurfaceTargetLoader.load(markers::add)

    assertNull(target)
    assertTrue(markers.single().contains("status=not-present"))
  }
}

