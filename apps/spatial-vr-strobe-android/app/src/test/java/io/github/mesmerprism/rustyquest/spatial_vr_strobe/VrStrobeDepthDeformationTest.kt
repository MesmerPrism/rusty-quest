package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VrStrobeDepthDeformationTest {
  @Test
  fun experimentalDepthStartsEnabledAtHalfTheCarrierRadius() {
    val state = VrStrobeDepthDeformationState()

    assertTrue(state.enabled)
    assertEquals(1.42f, state.maxDisplacementMeters, 0.0001f)
    assertEquals(
        VrStrobeCarrierGeometry.RADIUS_METERS * 0.5f,
        VrStrobeDepthDeformationPolicy.MAX_MAX_METERS,
    )
  }

  @Test
  fun maxDisplacementIsFiniteClampedAndQuantizedToOneMillimeter() {
    assertEquals(0f, VrStrobeDepthDeformationPolicy.sanitizeMaxMeters(-1f))
    assertEquals(1.42f, VrStrobeDepthDeformationPolicy.sanitizeMaxMeters(100f), 0.0001f)
    assertEquals(1.42f, VrStrobeDepthDeformationPolicy.sanitizeMaxMeters(Float.NaN), 0.0001f)
    assertEquals(0.013f, VrStrobeDepthDeformationPolicy.sanitizeMaxMeters(0.0126f))
  }

  @Test
  fun firstPaletteSlotMovesFarLastMovesNearAndMiddleStaysOnCarrier() {
    assertEquals(
        -1.42f,
        VrStrobeDepthDeformationPolicy.signedPaletteDisplacementMeters(0f, 1.42f),
        0.0001f,
    )
    assertEquals(
        0f,
        VrStrobeDepthDeformationPolicy.signedPaletteDisplacementMeters(0.5f, 1.42f),
    )
    assertEquals(
        1.42f,
        VrStrobeDepthDeformationPolicy.signedPaletteDisplacementMeters(1f, 1.42f),
        0.0001f,
    )
  }

  @Test
  fun depthBandwidthKeepsSmallReliefDetailedAndSmoothsOnlyTheStressRange() {
    assertEquals(12f, VrStrobeDepthDeformationPolicy.spatialFrequencyLimit(0.05f))
    assertTrue(VrStrobeDepthDeformationPolicy.spatialFrequencyLimit(0.5f) < 12f)
    assertTrue(VrStrobeDepthDeformationPolicy.spatialFrequencyLimit(0.5f) > 4f)
    assertEquals(4f, VrStrobeDepthDeformationPolicy.spatialFrequencyLimit(1.42f), 0.0001f)
  }

  @Test
  fun nonlinearSliderRetainsFineLowRangeControlAndReachesHalfRadius() {
    val lowPosition = VrStrobeDepthDeformationPolicy.sliderPosition(0.025f)

    assertEquals(0.025f, VrStrobeDepthDeformationPolicy.maxMetersFromSlider(lowPosition), 0.001f)
    assertEquals(0f, VrStrobeDepthDeformationPolicy.maxMetersFromSlider(0f))
    assertEquals(1.42f, VrStrobeDepthDeformationPolicy.maxMetersFromSlider(1f), 0.0001f)
  }
}
