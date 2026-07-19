package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrStrobeGpuPlanTest {
  @Test
  fun inactivePatternSlotsAreNotSentToTheFragmentHotPath() {
    val profile =
        VrStrobeInterferenceProfile(
            patterns =
                listOf(
                    VrStrobePattern(VrStrobePatternKind.STRIPE, active = true),
                    VrStrobePattern(VrStrobePatternKind.STRIPE, active = false),
                    VrStrobePattern(VrStrobePatternKind.RIPPLE, active = true),
                    VrStrobePattern(VrStrobePatternKind.RAY, active = false),
                    VrStrobePattern(VrStrobePatternKind.PERLIN, active = true),
                )
        )

    val plan = profile.gpuPlan()

    assertEquals(1, plan.stripeCount)
    assertEquals(1, plan.rippleCount)
    assertEquals(0, plan.rayCount)
    assertEquals(1, plan.perlinCount)
    assertEquals(3, plan.activePatternCount)
    assertTrue(profile.activeGpuPatterns(VrStrobePatternKind.STRIPE).all { it.active })
  }

  @Test
  fun postProcessingHasAThreeSignalEvaluationCeiling() {
    assertEquals(1, VR_STROBE_MAX_SIGNAL_EVALUATIONS_PER_FRAGMENT)
  }

  @Test
  fun randomizeUsesBoundedActiveSlotsInsteadOfClearingEveryDeclaredSlot() {
    val profile =
        VrStrobeInterferenceProfile(
            patterns =
                listOf(
                    VrStrobePattern(VrStrobePatternKind.STRIPE),
                    VrStrobePattern(VrStrobePatternKind.RIPPLE),
                    VrStrobePattern(VrStrobePatternKind.RAY),
                )
        )

    val update = profile.materialUpdatePlan()

    assertEquals(3, update.activePatternCount)
    assertEquals(27, update.uniformWriteCount)
    assertEquals(0, update.inactivePatternSlotWrites)
    assertFalse(update.fullProfileClear)
    assertTrue(update.uniformWriteCount * 6 < VR_STROBE_INITIAL_PROFILE_CLEAR_WRITES)
  }

  @Test
  fun temporalRandomizeCommitsZeroPatternCountsWithoutInactiveSlotWrites() {
    val update = VrStrobeTemporalProfile().materialUpdatePlan()

    assertEquals(0, update.activePatternCount)
    assertEquals(9, update.uniformWriteCount)
    assertEquals(0, update.inactivePatternSlotWrites)
    assertFalse(update.fullProfileClear)
  }
}
