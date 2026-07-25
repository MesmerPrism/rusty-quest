package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialPresentationPolicyTest {
  @Test
  fun interactivePolicyLeavesPresentationAuthoritiesUnforced() {
    val policy = SpatialPresentationPolicy(false, 1.0f)

    assertNull(policy.fixedLayerOverride)
    assertNull(policy.fixedProjectionScale)
    assertTrue(policy.appControlInputsEnabled)
    assertFalse(policy.videoSettings(SpatialVideoProjectionSettings.disabled()).enabled)
  }

  @Test
  fun lockedPolicyForcesFinalScaleOneVideoAndNoAppInputs() {
    val policy = SpatialPresentationPolicy(true, 0.5f)

    assertEquals(0.0f, policy.fixedLayerOverride)
    assertEquals(1.0f, policy.fixedProjectionScale)
    assertFalse(policy.appControlInputsEnabled)
    assertTrue(policy.videoSettings(SpatialVideoProjectionSettings.disabled()).enabled)
    assertTrue(policy.markerFields().contains("videoBorderForcedEnabled=true"))
    assertTrue(policy.markerFields().contains("distortionSpeedScale=0.5000"))
  }
}
