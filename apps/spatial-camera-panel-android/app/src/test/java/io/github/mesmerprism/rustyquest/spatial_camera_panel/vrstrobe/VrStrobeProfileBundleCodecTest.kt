package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VrStrobeProfileBundleCodecTest {
  @Test
  fun interferenceAndTemporalProfilesRoundTripThroughBrowserJsonExactly() {
    val profiles =
        listOf(
            VrStrobeStoredProfile(
                id = "browser-interference-1",
                title = "Browser interference",
                createdAtEpochMs = 123L,
                distanceMeters = 4f,
                carrierShape = VrStrobeCarrierShapeState(true, 1f),
                profile =
                    VrStrobeStimulusProfile.Interference(
                        VrStrobePresetCatalog.interference.first().profile.copy(
                            id = "browser-interference-1",
                            title = "Browser interference",
                            sourcePayload = null,
                        )
                    ),
            ),
            VrStrobeStoredProfile(
                id = "browser-temporal-1",
                title = "Browser temporal",
                createdAtEpochMs = 456L,
                distanceMeters = 2.5f,
                carrierShape = VrStrobeCarrierShapeState(false, 0.25f),
                profile =
                    VrStrobeStimulusProfile.Temporal(
                        VrStrobeTemporalProfile(
                            id = "browser-temporal-1",
                            title = "Browser temporal",
                            frequencyHz = 19f,
                        )
                    ),
            ),
        )

    val payload = VrStrobeProfileBundleCodec.encode(profiles)

    assertEquals(profiles, VrStrobeProfileBundleCodec.decode(payload))
    assertTrue(payload.contains(VrStrobeProfileBundleCodec.SCHEMA))
    assertTrue(payload.contains("\"distance_meters\": 4.0"))
  }

  @Test
  fun bundleRejectsSilentQuestClamping() {
    val valid =
        VrStrobeProfileBundleCodec.encode(
            listOf(
                VrStrobeStoredProfile(
                    id = "invalid-scale",
                    title = "Invalid scale",
                    createdAtEpochMs = 1L,
                    distanceMeters = 4f,
                    profile =
                        VrStrobeStimulusProfile.Interference(
                            VrStrobeInterferenceProfile(
                                id = "invalid-scale",
                                title = "Invalid scale",
                            )
                        ),
                )
            )
        )
    val invalid = valid.replace("\"scale\": 2.0", "\"scale\": 1000.0")

    assertFailsWith<IllegalArgumentException> { VrStrobeProfileBundleCodec.decode(invalid) }
  }

  @Test
  fun stagedBundleReplacesStoredListAndPublishesEffectiveExport() {
    var binaryPayload: String? = null
    var exportPayload: String? = null
    var cleared = false
    val staged = VrStrobeProfileBundleCodec.encode(emptyList())

    val authority =
        VrStrobeStoredProfileAuthority(
            VrStrobeStoredProfileBindings(
                readPayload = { binaryPayload },
                writePayload = { binaryPayload = it; true },
                readImportBundlePayload = { staged },
                clearImportBundlePayload = { cleared = true; true },
                writeExportBundlePayload = { exportPayload = it; true },
            )
        )

    assertEquals("imported", authority.loadStatus)
    assertEquals(emptyList(), authority.snapshot())
    assertTrue(cleared)
    assertEquals(emptyList(), VrStrobeProfileBundleCodec.decode(requireNotNull(exportPayload)))
    assertEquals(emptyList(), VrStrobeStoredProfileCodec.decode(requireNotNull(binaryPayload)))
  }
}
