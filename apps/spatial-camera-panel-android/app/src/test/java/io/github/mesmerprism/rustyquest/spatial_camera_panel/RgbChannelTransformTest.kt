package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbChannelTransformTest {
  @Test
  fun defaultIsBypassIdentity() {
    val value = RgbChannelTransformModule.normalize(RgbChannelTransform())
    assertEquals(RgbChannelTransformControls.modeBypass, value.mode)
    assertEquals(RgbChannelParameters(), value.red)
    assertEquals(RgbChannelParameters(), value.green)
    assertEquals(RgbChannelParameters(), value.blue)
  }

  @Test
  fun linkedModeUsesRedAsSingleChannelAuthority() {
    val value =
        RgbChannelTransformModule.normalize(
            RgbChannelTransform(
                mode = RgbChannelTransformControls.modeLinked,
                red =
                    RgbChannelParameters(
                        directionTurns = 0.25f,
                        directionRateHz = 0.5f,
                        displacementStrengthUv = 0.02f,
                        imageScale = 1.25f,
                        coverageScale = 0.75f,
                    ),
                directionNoiseAmountTurns = 0.08f,
                directionNoiseRateHz = 0.5f,
                green = RgbChannelParameters(directionTurns = 0.6f),
                blue = RgbChannelParameters(directionTurns = 0.8f),
            )
        )
    assertEquals(value.red, value.green)
    assertEquals(value.red, value.blue)
    assertEquals(0.08f, value.directionNoiseAmountTurns)
    assertEquals(0.5f, value.directionNoiseRateHz)
  }

  @Test
  fun independentModePreservesBoundedChannelDifferences() {
    val value = RgbChannelTransformModule.normalize(RgbChannelTransformControls.independent)
    assertEquals(RgbChannelTransformControls.modeIndependent, value.mode)
    assertTrue(value.red.directionRateHz != value.green.directionRateHz)
    assertTrue(value.green.displacementStrengthUv != value.blue.displacementStrengthUv)
    assertTrue(value.red.coverageScale != value.blue.coverageScale)
    assertEquals(1.0f, value.red.imageScale)
    assertEquals(1.0f, value.green.imageScale)
    assertEquals(1.0f, value.blue.imageScale)
    assertEquals(0.055f, value.red.directionRateHz)
    assertEquals(0.085f, value.green.directionRateHz)
    assertEquals(-0.065f, value.blue.directionRateHz)
    assertEquals(0.0f, value.directionNoiseAmountTurns)
    assertEquals(0.1f, value.directionNoiseRateHz)
  }

  @Test
  fun damagedInputsFailClosedToFiniteBounds() {
    val value =
        RgbChannelTransformModule.normalize(
            RgbChannelTransform(
                mode = 99,
                edgeMode = -4,
                red =
                    RgbChannelParameters(
                        directionTurns = Float.NaN,
                        directionRateHz = Float.POSITIVE_INFINITY,
                        displacementStrengthUv = 10.0f,
                        imageScale = 0.0f,
                        coverageScale = -1.0f,
                    ),
            )
        )
    assertEquals(RgbChannelTransformControls.modeBypass, value.mode)
    assertEquals(RgbChannelTransformControls.edgeClamp, value.edgeMode)
    assertEquals(0.0f, value.red.directionTurns)
    assertEquals(0.0f, value.red.directionRateHz)
    assertEquals(0.08f, value.red.displacementStrengthUv)
    assertEquals(0.5f, value.red.imageScale)
    assertEquals(0.5f, value.red.coverageScale)
    assertEquals(RgbChannelParameters(), value.green)
    assertEquals(RgbChannelParameters(), value.blue)
  }

  @Test
  fun markerPublishesContractAndEffectiveValues() {
    val marker = RgbChannelTransformModule.markerFields(RgbChannelTransformControls.independent)
    assertTrue(marker.contains("rgbChannelTransformContract=rusty.quest.rgb-channel-transform.v1"))
    assertTrue(marker.contains("rgbChannelTransformMode=independent-rgb"))
    assertTrue(marker.contains("rgbDirectionRateHz="))
    assertTrue(marker.contains("rgbDirectionNoiseAmountTurns="))
    assertTrue(marker.contains("rgbDirectionNoiseRateHz="))
    assertTrue(marker.contains("rgbCoverageScale="))
  }

  @Test
  fun noiseControlsNormalizeToSafeFiniteBounds() {
    val value =
        RgbChannelTransformModule.normalize(
            RgbChannelTransform(
                directionNoiseAmountTurns = Float.POSITIVE_INFINITY,
                directionNoiseRateHz = -1.0f,
            )
        )
    assertEquals(0.0f, value.directionNoiseAmountTurns)
    assertEquals(0.0f, value.directionNoiseRateHz)
  }
}
