package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals

class SpatialComposePanelRegistrationModuleTest {
  @Test
  fun cameraModuleOwnsThePrivateComposeRegistration() {
    assertEquals(
        "spatial-compose-panel-registration",
        SpatialComposePanelRegistrationModule.MODULE_ID,
    )
  }
}
