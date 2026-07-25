package io.github.mesmerprism.rustyquest.spatial_sdk_shared

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpatialPanelFacingTest {
  @Test
  fun viewerRelativePoseKeepsRequestedDistanceAndUsesSharedConvention() {
    val viewer = Pose(Vector3(0.25f, 1.4f, 0.5f), Quaternion(0.0f, 0.0f, 0.0f, 1.0f))

    val panel = SpatialPanelFacing.poseFromViewer(viewerPose = viewer, distanceMeters = 0.82f)
    val offset = panel.t - viewer.t
    val length = sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z)

    assertEquals(0.82f, length, absoluteTolerance = 0.0001f)
    assertEquals("meta-panel-front-look-rotation-around-y", SpatialPanelFacing.CONVENTION_MARKER)
  }

  @Test
  fun missingViewerUsesKnownFrontFacingFallbackPosition() {
    val panel = SpatialPanelFacing.poseFromViewer(viewerPose = null, distanceMeters = 0.82f)

    assertEquals(0.0f, panel.t.x, absoluteTolerance = 0.0001f)
    assertEquals(1.20f, panel.t.y, absoluteTolerance = 0.0001f)
    assertEquals(-0.82f, panel.t.z, absoluteTolerance = 0.0001f)
    assertEquals("static-panel-front-yaw-180", SpatialPanelFacing.FALLBACK_MARKER)
  }

  @Test
  fun nonPositiveDistanceIsRejected() {
    val failure =
        assertFailsWith<IllegalArgumentException> {
          SpatialPanelFacing.poseFromViewer(viewerPose = null, distanceMeters = 0.0f)
        }

    assertTrue(failure.message.orEmpty().contains("positive"))
  }
}
