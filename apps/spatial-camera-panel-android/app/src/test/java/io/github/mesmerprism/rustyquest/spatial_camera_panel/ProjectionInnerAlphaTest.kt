package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionInnerAlphaTest {
  @Test
  fun defaultIsDisabledProcessedCoreCompatibilityIdentity() {
    val value = ProjectionInnerAlphaModule.normalize(ProjectionInnerAlpha())
    assertEquals(ProjectionInnerAlphaControls.off, value)
    assertEquals(ProjectionInnerAlphaControls.driverLuma, value.driver)
    assertEquals(0.5f, value.threshold)
    assertEquals(0.1f, value.softness)
    assertEquals(0.0f, value.amount)
    assertFalse(value.invert)
    assertEquals(ProjectionInnerAlphaControls.stretchFollowProjection, value.stretchPolicy)
    assertFalse(value.stretchObeysExactProjectionMask)
    assertFalse(ProjectionInnerAlphaModule.requested(value))
  }

  @Test
  fun normalizeClampsValuesAndFallsBackToPublicTokens() {
    val value =
        ProjectionInnerAlphaModule.normalize(
            ProjectionInnerAlpha(
                enabled = true,
                driver = 99,
                threshold = -1.0f,
                softness = 4.0f,
                amount = 2.0f,
                invert = true,
                stretchPolicy = -7,
                stretchObeysExactProjectionMask = true,
            )
        )
    assertEquals(ProjectionInnerAlphaControls.driverLuma, value.driver)
    assertEquals("luma", ProjectionInnerAlphaControls.driverToken(value.driver))
    assertEquals(0.0f, value.threshold)
    assertEquals(0.5f, value.softness)
    assertEquals(1.0f, value.amount)
    assertTrue(value.invert)
    assertEquals(
        "follow-projection",
        ProjectionInnerAlphaControls.stretchPolicyToken(value.stretchPolicy),
    )
    assertTrue(value.stretchObeysExactProjectionMask)
  }

  @Test
  fun driverAndStretchPolicyTokensCoverEveryPublicChoice() {
    assertEquals("red", ProjectionInnerAlphaControls.driverToken(0))
    assertEquals("green", ProjectionInnerAlphaControls.driverToken(1))
    assertEquals("blue", ProjectionInnerAlphaControls.driverToken(2))
    assertEquals("luma", ProjectionInnerAlphaControls.driverToken(3))
    assertEquals("max", ProjectionInnerAlphaControls.driverToken(4))
    assertEquals(
        "opaque-independent",
        ProjectionInnerAlphaControls.stretchPolicyToken(
            ProjectionInnerAlphaControls.stretchOpaqueIndependent
        ),
    )
  }

  @Test
  fun requestRequiresIndependentEnableAndNonzeroAmount() {
    assertFalse(
        ProjectionInnerAlphaModule.requested(
            ProjectionInnerAlpha(enabled = true, amount = 0.0f)
        )
    )
    assertFalse(
        ProjectionInnerAlphaModule.requested(
            ProjectionInnerAlpha(enabled = false, amount = 1.0f)
        )
    )
    assertTrue(
        ProjectionInnerAlphaModule.requested(
            ProjectionInnerAlpha(enabled = true, amount = 0.5f)
        )
    )
  }

  @Test
  fun requestedSupportedAndEffectiveRemainSeparate() {
    val active = ProjectionInnerAlpha(enabled = true, amount = 0.7f)
    val unsupported =
        ProjectionInnerAlphaModule.markerFields(
            configuration = active,
            supported = false,
            effective = true,
        )
    assertTrue(unsupported.contains("projectionInnerAlphaInput=processed-core"))
    assertTrue(unsupported.contains("projectionInnerAlphaRequested=true"))
    assertTrue(unsupported.contains("projectionInnerAlphaSupported=false"))
    assertTrue(unsupported.contains("projectionInnerAlphaEffective=false"))

    val notAdopted =
        ProjectionInnerAlphaModule.markerFields(
            configuration = active,
            supported = true,
            effective = false,
        )
    assertTrue(notAdopted.contains("projectionInnerAlphaSupported=true"))
    assertTrue(notAdopted.contains("projectionInnerAlphaEffective=false"))

    val adopted =
        ProjectionInnerAlphaModule.markerFields(
            configuration = active,
            supported = true,
            effective = true,
        )
    assertTrue(adopted.contains("projectionInnerAlphaEffective=true"))
  }
}
