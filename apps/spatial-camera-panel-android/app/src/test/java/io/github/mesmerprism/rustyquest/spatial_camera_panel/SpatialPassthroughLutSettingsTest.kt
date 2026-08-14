package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialPassthroughLutSettingsTest {
  @Test
  fun settingsNormalizeToTheUiAndRuntimeBounds() {
    val normalized =
        SpatialPassthroughLutSettings(
                colorIntensity = 2.0f,
                colorPhaseHz = -1.0f,
                blackLevelCutoff = 0.9f,
            )
            .normalized()

    assertEquals(1.0f, normalized.colorIntensity)
    assertEquals(0.0f, normalized.colorPhaseHz)
    assertEquals(0.20f, normalized.blackLevelCutoff)
  }

  @Test
  fun staticModeProducesOneStableFullAmplitudeSnapshot() {
    val settings = SpatialPassthroughLutSettings(animationEnabled = false)

    val first = SpatialPassthroughLutModule.snapshot(0L, settings)
    val later = SpatialPassthroughLutModule.snapshot(90_000L, settings)

    assertEquals(first, later)
    assertEquals(0.0f, later.phase)
    assertEquals(1.0f, later.amplitude)
  }

  @Test
  fun cycleSpeedControlsPhaseWithoutChangingItsBounds() {
    val slow =
        SpatialPassthroughLutModule.snapshot(
            1_000L,
            SpatialPassthroughLutSettings(colorPhaseHz = 0.10f),
        )
    val fast =
        SpatialPassthroughLutModule.snapshot(
            1_000L,
            SpatialPassthroughLutSettings(colorPhaseHz = 0.25f),
        )

    assertTrue(fast.phase > slow.phase)
    assertTrue(slow.phase in 0.0f..1.0f)
    assertTrue(fast.phase in 0.0f..1.0f)
  }
}
