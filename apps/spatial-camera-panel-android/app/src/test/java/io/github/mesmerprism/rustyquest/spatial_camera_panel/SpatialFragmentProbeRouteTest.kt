package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialFragmentProbeRouteTest {
  @Test
  fun defaultsToInertWhenPropertiesAreAbsent() {
    val config = SpatialFragmentProbeRoute.resolve { "" }

    assertFalse(config.enabled)
    assertEquals("missing-or-invalid-enable", config.rejectionReason)
  }

  @Test
  fun resolvesFlat2dWithoutFragmentDepth() {
    val config =
        SpatialFragmentProbeRoute.resolve(
            propertyReader(
                SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY to "true",
                SPATIAL_FRAGMENT_PROBE_MODE_PROPERTY to "flat-2d",
                SPATIAL_FRAGMENT_PROBE_DEPTH_PROPERTY to "false",
                SPATIAL_FRAGMENT_PROBE_HOLD_MS_PROPERTY to "9000",
            )
        )

    assertTrue(config.enabled)
    assertEquals(SpatialFragmentProbeMode.FLAT_2D, config.mode)
    assertFalse(config.fragmentDepth)
    assertEquals("spatial_fragment_probe_nodepth", config.shaderName)
    assertEquals(9_000L, config.holdMs)
  }

  @Test
  fun resolvesRaymarchWithFragmentDepthAndClampsHold() {
    val config =
        SpatialFragmentProbeRoute.resolve(
            propertyReader(
                SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY to "1",
                SPATIAL_FRAGMENT_PROBE_MODE_PROPERTY to "raymarch",
                SPATIAL_FRAGMENT_PROBE_DEPTH_PROPERTY to "on",
                SPATIAL_FRAGMENT_PROBE_HOLD_MS_PROPERTY to "999999",
            )
        )

    assertTrue(config.enabled)
    assertEquals(SpatialFragmentProbeMode.RAYMARCH, config.mode)
    assertTrue(config.fragmentDepth)
    assertEquals("spatial_fragment_probe_depth", config.shaderName)
    assertEquals(SPATIAL_FRAGMENT_PROBE_MAX_HOLD_MS, config.holdMs)
  }

  @Test
  fun invalidModeFailsClosed() {
    val config =
        SpatialFragmentProbeRoute.resolve(
            propertyReader(
                SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY to "true",
                SPATIAL_FRAGMENT_PROBE_MODE_PROPERTY to "animated-strobe",
                SPATIAL_FRAGMENT_PROBE_DEPTH_PROPERTY to "false",
            )
        )

    assertFalse(config.enabled)
    assertEquals("missing-or-invalid-mode", config.rejectionReason)
  }

  @Test
  fun fragmentDepthMustBeExplicit() {
    val config =
        SpatialFragmentProbeRoute.resolve(
            propertyReader(
                SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY to "true",
                SPATIAL_FRAGMENT_PROBE_MODE_PROPERTY to "raymarch",
            )
        )

    assertFalse(config.enabled)
    assertEquals("missing-or-invalid-fragment-depth", config.rejectionReason)
  }

  private fun propertyReader(vararg values: Pair<String, String>): (String) -> String {
    val properties = values.toMap()
    return { name -> properties[name].orEmpty() }
  }
}
