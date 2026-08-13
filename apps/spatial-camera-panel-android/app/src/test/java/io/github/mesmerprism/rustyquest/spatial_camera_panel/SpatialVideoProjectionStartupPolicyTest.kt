package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVideoProjectionStartupPolicyTest {
  @Test
  fun streamBackedPeerUsesOnlyRawProjectionDecoderWhenCameraProjectionOwnsOutput() {
    assertTrue(
        SpatialVideoProjectionStartupPolicy.delegateStreamToCameraProjection(
            cameraProjectionEnabled = true,
            source = "peer-packed-stereo",
        )
    )
    assertTrue(
        SpatialVideoProjectionStartupPolicy.delegateStreamToCameraProjection(
            cameraProjectionEnabled = true,
            source = "broker-rmanvid1",
        )
    )
  }

  @Test
  fun fileAndStandaloneStreamRoutesRetainTheirVideoOnlyOwner() {
    assertFalse(
        SpatialVideoProjectionStartupPolicy.delegateStreamToCameraProjection(
            cameraProjectionEnabled = true,
            source = "shared-plain-video",
        )
    )
    assertFalse(
        SpatialVideoProjectionStartupPolicy.delegateStreamToCameraProjection(
            cameraProjectionEnabled = false,
            source = "peer-packed-stereo",
        )
    )
  }
}
