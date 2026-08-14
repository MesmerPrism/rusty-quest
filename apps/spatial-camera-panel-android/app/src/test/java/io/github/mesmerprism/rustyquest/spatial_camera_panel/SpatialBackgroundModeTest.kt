package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals

class SpatialBackgroundModeTest {
  @Test
  fun tokensNormalizeAndLegacyMissingValueDefaultsToBlack() {
    assertEquals(SpatialBackgroundMode.Black, SpatialBackgroundMode.fromToken(null))
    assertEquals(SpatialBackgroundMode.Black, SpatialBackgroundMode.fromToken("unknown"))
    assertEquals(SpatialBackgroundMode.Passthrough, SpatialBackgroundMode.fromToken("passthrough"))
    assertEquals(
        SpatialBackgroundMode.LutPassthrough,
        SpatialBackgroundMode.fromToken("lut_passthrough"),
    )
  }

  @Test
  fun eachBackgroundModeResolvesOneExplicitCompositionPolicy() {
    assertEquals(
        SpatialBackgroundEffects(true, true, false),
        SpatialBackgroundModePolicy.resolve(SpatialBackgroundMode.Black, false),
    )
    assertEquals(
        SpatialBackgroundEffects(false, true, false),
        SpatialBackgroundModePolicy.resolve(SpatialBackgroundMode.Passthrough, false),
    )
    assertEquals(
        SpatialBackgroundEffects(false, true, true),
        SpatialBackgroundModePolicy.resolve(SpatialBackgroundMode.LutPassthrough, false),
    )
  }

  @Test
  fun retainedDiagnosticLutComposesWithTheSelectedBackground() {
    assertEquals(
        SpatialBackgroundEffects(true, true, true),
        SpatialBackgroundModePolicy.resolve(SpatialBackgroundMode.Black, true),
    )
    assertEquals(
        SpatialBackgroundEffects(false, true, true),
        SpatialBackgroundModePolicy.resolve(SpatialBackgroundMode.Passthrough, true),
    )
  }
}
