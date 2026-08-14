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

  @Test
  fun colorStopsInterpolateContinuouslyAndUpdateAtVideoCadence() {
    val atGreen = SpatialPassthroughLutModule.interpolatedSaturatedColor(0.0f)
    val halfwayToYellow =
        SpatialPassthroughLutModule.interpolatedSaturatedColor(
            0.5f / SpatialPassthroughLutModule.SATURATED_COLOR_BAND_COUNT
        )

    assertEquals(0.0f, atGreen[0], 0.001f)
    assertEquals(255.0f, atGreen[1], 0.001f)
    assertTrue(halfwayToYellow[0] in 126.0f..129.0f)
    assertEquals(255.0f, halfwayToYellow[1], 0.001f)
    assertTrue(SpatialPassthroughLutModule.UPDATE_HZ >= 30.0f)
    assertTrue(SpatialPassthroughLutModule.UPDATE_PERIOD_MS <= 34L)
  }
}
