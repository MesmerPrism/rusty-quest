package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VrStrobeStoredProfileAuthorityTest {
  @Test
  fun storedInterferenceProfileRoundTripsExactlyAcrossAuthorityRestart() {
    var payload: String? = null
    val source =
        VrStrobeStimulusProfile.Interference(
            VrStrobePresetCatalog.interference.first().profile.randomized(kotlin.random.Random(19))
        )
    val authority = authority(read = { payload }, write = { payload = it; true })

    val stored =
        assertNotNull(
            authority
                .store(
                    source,
                    1.75f,
                    VrStrobeCarrierShapeState(curvedMode = true, concavity = 0.72f),
                )
                .storedProfile
        )
    val restored = authority(read = { payload }, write = { payload = it; true })

    assertEquals(listOf(stored), restored.snapshot())
    assertEquals(1.75f, restored.snapshot().single().distanceMeters)
    assertEquals(VrStrobeCarrierShapeState(true, 0.72f), restored.snapshot().single().carrierShape)
    assertEquals(-1, VrStrobePresetCatalog.all.indexOfFirst { it.id == stored.id })
  }

  @Test
  fun legacyVersionOnePayloadLoadsFlatScalesDistanceAndSurvivesVersionThreeRewrite() {
    val source = VrStrobeStimulusProfile.Temporal(VrStrobeTemporalProfile())
    val initial = authority(read = { null }, write = { true })
    val stored =
        assertNotNull(
            initial
                .store(
                    source,
                    0.9f,
                    VrStrobeCarrierShapeState(curvedMode = true, concavity = 0.8f),
                )
                .storedProfile
        )
    var payload = VrStrobeStoredProfileCodec.encodeForVersion(listOf(stored), 1)
    val migrated = authority(read = { payload }, write = { payload = it; true })

    assertEquals(1, migrated.snapshot().size)
    assertFalse(migrated.snapshot().single().carrierShape.curvedMode)
    assertEquals(VrStrobeConcavityPolicy.DEFAULT, migrated.snapshot().single().carrierShape.concavity)
    assertEquals(2.1f, migrated.snapshot().single().distanceMeters)

    assertNotNull(migrated.store(source).storedProfile)
    val rewritten = VrStrobeStoredProfileCodec.decode(payload)
    assertEquals(2, rewritten.size)
    assertEquals(stored.id, rewritten.last().id)
  }

  @Test
  fun versionTwoDistanceMigratesToTheSameAngularScaleOnlyOnce() {
    val source = VrStrobeStimulusProfile.Temporal(VrStrobeTemporalProfile())
    val stored =
        assertNotNull(
            authority(read = { null }, write = { true })
                .store(source, VrStrobeDistancePolicy.DEFAULT_METERS)
                .storedProfile
        )
    val legacyDistance = stored.copy(distanceMeters = 0.62f)
    val versionTwo = VrStrobeStoredProfileCodec.encodeForVersion(listOf(legacyDistance), 2)
    val migrated = VrStrobeStoredProfileCodec.decode(versionTwo).single()

    assertEquals(1.24f, migrated.distanceMeters)
    val rewritten = VrStrobeStoredProfileCodec.encode(listOf(migrated))
    assertEquals(1.24f, VrStrobeStoredProfileCodec.decode(rewritten).single().distanceMeters)
  }

  @Test
  fun sameClockTickProducesUniqueStableIds() {
    var payload: String? = null
    val authority = authority(read = { payload }, write = { payload = it; true })
    val source = VrStrobeStimulusProfile.Temporal(VrStrobeTemporalProfile())

    val first = assertNotNull(authority.store(source).storedProfile)
    val second = assertNotNull(authority.store(source).storedProfile)

    assertEquals("stored-123456", first.id)
    assertEquals("stored-123456-2", second.id)
  }

  @Test
  fun failedPersistenceDoesNotPublishAStoredProfile() {
    val authority = authority(read = { null }, write = { false })

    val result = authority.store(
        VrStrobeStimulusProfile.Temporal(VrStrobeTemporalProfile())
    )

    assertNull(result.storedProfile)
    assertEquals("stored-profile-persist-failed", result.rejectionReason)
    assertEquals(emptyList(), authority.snapshot())
  }

  private fun authority(
      read: () -> String?,
      write: (String) -> Boolean,
  ): VrStrobeStoredProfileAuthority =
      VrStrobeStoredProfileAuthority(
          VrStrobeStoredProfileBindings(
              readPayload = read,
              writePayload = write,
              wallClockNowMs = { 123_456L },
          )
      )
}
