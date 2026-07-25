package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpatialProductBuildPolicyTest {
  @Test
  fun cameraModuleCanOnlyResolveCameraPanelProduct() {
    val cameraPanel =
        SpatialProductBuildPolicy.resolve(
            SpatialProductBuildPolicy.CAMERA_PANEL_PRODUCT_ID,
            "debug.rustyquest.spatial_camera_panel",
        )
    assertTrue(cameraPanel.cameraPanelRoutesEnabled)
    assertTrue(cameraPanel.markerFields().contains("applicationModule=:app"))
  }

  @Test
  fun unknownProductFailsClosed() {
    assertFailsWith<IllegalStateException> {
      SpatialProductBuildPolicy.resolve("ambient-or-unknown", "debug.rustyquest.unknown")
    }
  }
}
