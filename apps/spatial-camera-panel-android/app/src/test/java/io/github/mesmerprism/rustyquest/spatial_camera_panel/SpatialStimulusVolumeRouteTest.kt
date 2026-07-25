package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialStimulusVolumeRouteTest {
  @Test
  fun defaultsToInertWhenPropertiesAreAbsent() {
    val config = SpatialStimulusVolumeRoute.resolve { "" }

    assertFalse(config.enabled)
    assertEquals("missing-or-invalid-enable", config.rejectionReason)
  }

  @Test
  fun resolvesCanonicalFixedPhaseProfileOnlyWithExplicitSafetyInputs() {
    val config = SpatialStimulusVolumeRoute.resolve(validProperties())

    assertTrue(config.enabled)
    assertEquals(SPATIAL_STIMULUS_VOLUME_PROFILE_ID, config.profileId)
    assertEquals(SpatialStimulusVolumePhaseMode.FIXED_PHASE, config.phaseMode)
    assertFalse(config.temporalEnabled)
    assertFalse(config.autostart)
    assertTrue(config.safetyAcknowledged)
    assertEquals(SPATIAL_STIMULUS_VOLUME_DEFAULT_HOLD_MS, config.holdMs)
  }

  @Test
  fun temporalPhaseModeFailsClosed() {
    val config =
        SpatialStimulusVolumeRoute.resolve(
            validProperties(SPATIAL_STIMULUS_VOLUME_PHASE_MODE_PROPERTY to "temporal-strobe")
        )

    assertFalse(config.enabled)
    assertEquals("temporal-phase-mode-forbidden", config.rejectionReason)
  }

  @Test
  fun temporalModulationFailsClosed() {
    val config =
        SpatialStimulusVolumeRoute.resolve(
            validProperties(SPATIAL_STIMULUS_VOLUME_TEMPORAL_ENABLED_PROPERTY to "true")
        )

    assertFalse(config.enabled)
    assertEquals("temporal-modulation-forbidden", config.rejectionReason)
  }

  @Test
  fun autostartFailsClosed() {
    val config =
        SpatialStimulusVolumeRoute.resolve(
            validProperties(SPATIAL_STIMULUS_VOLUME_AUTOSTART_PROPERTY to "true")
        )

    assertFalse(config.enabled)
    assertEquals("autostart-forbidden", config.rejectionReason)
  }

  @Test
  fun safetyAcknowledgementIsRequiredEvenForFixedPhase() {
    val config =
        SpatialStimulusVolumeRoute.resolve(
            validProperties(SPATIAL_STIMULUS_VOLUME_SAFETY_ACK_PROPERTY to "false")
        )

    assertFalse(config.enabled)
    assertEquals("safety-acknowledgement-required", config.rejectionReason)
  }

  @Test
  fun unsupportedProfileFailsClosed() {
    val config =
        SpatialStimulusVolumeRoute.resolve(
            validProperties(SPATIAL_STIMULUS_VOLUME_PROFILE_PROPERTY to "custom-unreviewed")
        )

    assertFalse(config.enabled)
    assertEquals("unsupported-profile", config.rejectionReason)
  }

  @Test
  fun holdIsBoundedBySourceSafetyMaximum() {
    val config =
        SpatialStimulusVolumeRoute.resolve(
            validProperties(SPATIAL_STIMULUS_VOLUME_HOLD_MS_PROPERTY to "900000")
        )

    assertTrue(config.enabled)
    assertEquals(SPATIAL_STIMULUS_VOLUME_MAX_HOLD_MS, config.holdMs)
  }

  @Test
  fun effectiveMarkerNamesAuthorityAndEvidenceBoundary() {
    val marker = SpatialStimulusVolumeRoute.effectiveMarker(
        SpatialStimulusVolumeRoute.resolve(validProperties())
    )

    assertTrue(marker.contains("profileAuthority=rusty-optics"))
    assertTrue(marker.contains("phaseSeconds=0"))
    assertTrue(marker.contains("temporalModulation=false"))
    assertTrue(marker.contains("deviceVisualProof=false"))
  }

  private fun validProperties(
      vararg overrides: Pair<String, String>,
  ): (String) -> String {
    val properties =
        mutableMapOf(
            SPATIAL_STIMULUS_VOLUME_ENABLED_PROPERTY to "true",
            SPATIAL_STIMULUS_VOLUME_PROFILE_PROPERTY to SPATIAL_STIMULUS_VOLUME_PROFILE_ID,
            SPATIAL_STIMULUS_VOLUME_PHASE_MODE_PROPERTY to "fixed-phase",
            SPATIAL_STIMULUS_VOLUME_TEMPORAL_ENABLED_PROPERTY to "false",
            SPATIAL_STIMULUS_VOLUME_AUTOSTART_PROPERTY to "false",
            SPATIAL_STIMULUS_VOLUME_SAFETY_ACK_PROPERTY to "true",
        )
    overrides.forEach { (name, value) -> properties[name] = value }
    return { name -> properties[name].orEmpty() }
  }
}
