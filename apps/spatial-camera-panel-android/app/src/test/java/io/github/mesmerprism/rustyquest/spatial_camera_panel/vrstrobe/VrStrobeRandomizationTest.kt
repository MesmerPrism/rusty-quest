package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VrStrobeRandomizationTest {
  @Test
  fun interferenceRandomizationPreservesSelectionIdentity() {
    val profile = VrStrobePresetCatalog.interference.first().profile

    val randomized = profile.randomized(Random(7))

    assertEquals(profile.id, randomized.id)
    assertEquals(profile.title, randomized.title)
    assertNotEquals(profile.scale, randomized.scale)
  }

  @Test
  fun temporalRandomizationPreservesSelectionIdentity() {
    val profile = VrStrobePresetCatalog.temporal.first().profile

    val randomized = profile.randomized(Random(11))

    assertEquals(profile.id, randomized.id)
    assertEquals(profile.title, randomized.title)
    assertNotEquals(profile.frequencyHz, randomized.frequencyHz)
  }

  @Test
  fun interferenceRandomizationStaysInsideTrevorBoundsAndQuestReliableEnvelope() {
    val profile =
        VrStrobeInterferenceProfile(
            patterns = VrStrobePatternKind.entries.map(::VrStrobePattern)
        )

    repeat(500) { seed ->
      val randomized = profile.randomized(Random(seed))
      listOf(randomized.color1, randomized.color2, randomized.color3).forEach { color ->
        assertTrue((color.rgb shr 16 and 0xff) in 100..254)
        assertTrue((color.rgb shr 8 and 0xff) in 100..254)
        assertTrue((color.rgb and 0xff) in 100..254)
      }
      randomized.patterns.filter { it.kind != VrStrobePatternKind.PERLIN }.forEach { pattern ->
        assertTrue(pattern.distortAmp in 0f..2f)
        assertTrue(pattern.waveAmp in 0f..2f)
      }
      assertTrue(VrStrobeQuestRandomizationEnvelope.accepts(randomized))
    }
  }

  @Test
  fun temporalRandomizationUsesQuestReliableSubsetOfOriginalControlBoundaries() {
    val values = List(500) { seed -> VrStrobeTemporalProfile().randomized(Random(seed)) }

    assertTrue(values.all(VrStrobeQuestRandomizationEnvelope::accepts))
    assertTrue(values.all { it.frequencyHz in 0.1f..120f })
    assertTrue(values.all { it.dutyPercent in 1f..99f })
    assertTrue(values.all { it.noiseResolution in 1..50 })
    assertTrue(values.all { it.fixationSize in 2..100 })
  }

  @Test
  fun randomizationNeverCreatesEqualEdgeVignetteOrMultipleCostlyBranches() {
    val profile =
        VrStrobeInterferenceProfile(
            patterns =
                VrStrobePatternKind.entries.flatMap { kind ->
                  List(4) { VrStrobePattern(kind) }
                }
        )

    repeat(1_000) { seed ->
      val randomized = profile.randomized(Random(seed))
      assertTrue(
          randomized.vignetteEdge == 0f ||
              randomized.vignetteEdge - randomized.vignetteCenter >= 0.2f
      )
      assertTrue(randomized.patterns.count { it.active && it.distortAmp > 0f } <= 1)
      assertTrue(randomized.patterns.count { it.active && it.waveAmp > 0f } <= 1)
    }
  }

  @Test
  fun interferenceRandomizationIncludesCoarseAndFineSpatialStructure() {
    val profile =
        VrStrobeInterferenceProfile(
            patterns =
                listOf(
                    VrStrobePattern(VrStrobePatternKind.STRIPE),
                    VrStrobePattern(VrStrobePatternKind.RIPPLE),
                    VrStrobePattern(VrStrobePatternKind.RAY),
                )
        )
    val values = List(1_000) { seed -> profile.randomized(Random(seed)) }

    assertTrue(values.any { it.scale <= 6f })
    assertTrue(values.any { it.scale >= 14f })
    assertTrue(
        values.count(VrStrobeQuestRandomizationEnvelope::fineSpatialDetail) >= 850
    )
    assertTrue(
        values.any { randomized ->
          randomized.patterns.any { pattern ->
            pattern.active &&
                ((pattern.kind == VrStrobePatternKind.RAY && pattern.period >= 45f) ||
                    (pattern.kind != VrStrobePatternKind.RAY && pattern.period >= 45f))
          }
        }
    )
    assertTrue(values.all(VrStrobeQuestRandomizationEnvelope::accepts))
  }

  @Test
  fun perlinRandomizationCanProduceFineNoiseWithoutLeavingEnvelope() {
    val profile =
        VrStrobeInterferenceProfile(
            patterns = listOf(VrStrobePattern(VrStrobePatternKind.PERLIN))
        )
    val values = List(500) { seed -> profile.randomized(Random(seed)) }

    assertTrue(values.any { it.patterns.single().perlinScale >= 34f })
    assertTrue(values.all(VrStrobeQuestRandomizationEnvelope::accepts))
  }
}
