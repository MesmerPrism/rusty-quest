package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VrStrobeProfileCatalogTest {
  @Test
  fun catalogContainsAllPinnedPortalEntriesAndBothDesigners() {
    assertEquals(5, VrStrobePresetCatalog.interference.size)
    assertEquals(4, VrStrobePresetCatalog.temporal.size)
    assertEquals(9, VrStrobePresetCatalog.all.size)
    assertEquals("52c71cc069f4102bc4148e05c5fd3fc4d5466479", VrStrobePresetCatalog.UPSTREAM_COMMIT)
  }

  @Test
  fun firstPinnedPayloadDecodesWithoutDroppingPatternFamilies() {
    val profile = VrStrobePresetCatalog.interference.first().profile

    assertEquals(2, profile.colorCount)
    assertEquals("#000000", profile.color1.hex())
    assertEquals("#ff0000", profile.color2.hex())
    assertEquals(33.75f, profile.oscillatorFrequencyHz)
    assertEquals(24.9f, profile.scale)
    assertEquals(1, profile.patterns(VrStrobePatternKind.STRIPE).size)
    assertEquals(1, profile.patterns(VrStrobePatternKind.RIPPLE).size)
    assertEquals(1, profile.patterns(VrStrobePatternKind.RAY).size)
    assertNotNull(profile.sourcePayload)
  }

  @Test
  fun sourcePayloadsRemainPresentAndSubstantial() {
    VrStrobePresetCatalog.interference.forEach { preset ->
      assertTrue((preset.profile.sourcePayload?.length ?: 0) > 1_000)
      assertTrue(preset.sourceLabel.startsWith("Simulated"))
    }
  }

  @Test
  fun sourcePayloadHashesMatchThePinnedIndexExactly() {
    val expected =
        listOf(
            "7ac9679bc3152f72d34c085d54ec295931071cc06cee2f1e0c5d75249510374d",
            "67bdf36a7a78fd2ed215a045c0c52b399279abd5bde2f496b3f1406c386c85c3",
            "ca1005e7cf105f6f1b6b1b21014bc65ae6cd5d610aaea8050863c6e4bed63e43",
            "ae063e1d505d5c00bc0db40f8897ee1a45ea4a0b14efe0b60928e313b83707ac",
            "edf9579792faed2b2517375c5b48ae31932a09ec1a74821ecc015ef4708d54a3",
        )
    val actual =
        VrStrobePresetCatalog.interference.map { preset ->
          MessageDigest.getInstance("SHA-256")
              .digest(requireNotNull(preset.profile.sourcePayload).toByteArray(Charsets.UTF_8))
              .joinToString("") { "%02x".format(it) }
        }

    assertEquals(expected, actual)
  }

  @Test
  fun realStrobePresetsPreserveFrequencyColorNoiseAndFixationInputs() {
    val presets = VrStrobePresetCatalog.temporal.associateBy { it.id }

    assertEquals(7f, presets.getValue("source-strobe-7hz").profile.frequencyHz)
    assertEquals(20f, presets.getValue("source-strobe-20hz").profile.frequencyHz)
    assertEquals("#ff0000", presets.getValue("source-strobe-12hz-red").profile.color2.hex())
    val noisy = presets.getValue("source-strobe-14hz-noise").profile
    assertTrue(noisy.noisePhase1)
    assertEquals(1f, noisy.noiseAmplitude1)
    assertTrue(noisy.fixationEnabled)
  }

  @Test
  fun profileSanitizersEnforceSourceAndSafetyBounds() {
    val temporal =
        VrStrobeTemporalProfile(
                durationSeconds = 900f,
                frequencyHz = 1_000f,
                dutyPercent = 0f,
                noiseResolution = 999,
            )
            .sanitized()
    assertEquals(VR_STROBE_MAX_DURATION_SECONDS, temporal.durationSeconds)
    assertEquals(120f, temporal.frequencyHz)
    assertEquals(1f, temporal.dutyPercent)
    assertEquals(50, temporal.noiseResolution)

    val profile =
        VrStrobeInterferenceProfile(
                patterns =
                    List(12) {
                      VrStrobePattern(VrStrobePatternKind.STRIPE, strength = 10f)
                    }
            )
            .sanitized()
    assertEquals(VR_STROBE_MAX_PATTERN_INSTANCES, profile.patterns(VrStrobePatternKind.STRIPE).size)
    assertTrue(profile.patterns.all { it.strength <= 2f })
  }
}
