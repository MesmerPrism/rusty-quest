package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCameraPanelStoredProfilesTest {
  @Test
  fun completeControlSnapshotRoundTripsThroughHumanBundle() {
    val profile =
        SpatialCameraPanelProfileEntry(
            id = "profile-42",
            title = "World underlay tuning",
            createdAtEpochMs = 42L,
            controls = sampleControls(),
        )

    val encoded = SpatialCameraPanelProfileBundleCodec.encode(listOf(profile))
    val decoded = SpatialCameraPanelProfileBundleCodec.decode(encoded)

    assertTrue(encoded.contains(SpatialCameraPanelProfileBundleCodec.SCHEMA))
    assertTrue(encoded.contains("World underlay tuning"))
    assertEquals(listOf(profile), decoded)
    assertEquals("world-anchored", decoded.single().controls.videoPresentationMode)
    assertEquals("lut-passthrough", decoded.single().controls.backgroundMode)
  }

  @Test
  fun legacyProfileWithoutBackgroundModeMigratesToBlackWithoutChangingSchema() {
    val encoded =
        SpatialCameraPanelProfileBundleCodec.encode(
            listOf(
                SpatialCameraPanelProfileEntry(
                    id = "profile-legacy",
                    title = "Legacy profile",
                    createdAtEpochMs = 5L,
                    controls = sampleControls().copy(backgroundMode = SpatialBackgroundMode.Black.token),
                )
            )
        )
    val legacyPayload = encoded.replace(Regex(",\\s*\\\"backgroundMode\\\": \\\"black\\\""), "")

    val decoded = SpatialCameraPanelProfileBundleCodec.decode(legacyPayload)

    assertEquals(SpatialCameraPanelProfileBundleCodec.FORMAT_VERSION, 1)
    assertEquals(null, decoded.single().controls.backgroundMode)
    assertEquals(SpatialBackgroundMode.Black, decoded.single().controls.resolvedBackgroundMode())
    assertEquals(legacyPayload, SpatialCameraPanelProfileBundleCodec.encode(decoded))
  }

  @Test
  fun authorityPersistsListsDeletesAndPublishesExportMirror() {
    var persisted: String? = null
    var exported: String? = null
    val authority =
        SpatialCameraPanelProfileLibraryAuthority(
            SpatialCameraPanelProfileLibraryBindings(
                readPayload = { persisted },
                writePayload = { persisted = it; true },
                writeExportBundlePayload = { exported = it; true },
                wallClockNowMs = { 42L },
            )
        )

    val stored = authority.store("Tuned setup", sampleControls())
    assertEquals("profile-saved", stored.status)
    assertEquals(1, stored.library.profiles.size)
    assertNotNull(exported)
    val id = stored.library.profiles.single().id
    assertNotNull(authority.find(id))

    val restored =
        SpatialCameraPanelProfileLibraryAuthority(
            SpatialCameraPanelProfileLibraryBindings(
                readPayload = { persisted },
                writePayload = { persisted = it; true },
            )
        )
    assertEquals(sampleControls(), restored.find(id)?.controls)
    assertEquals("profile-deleted", restored.delete(id).status)
    assertNull(restored.find(id))
  }

  @Test
  fun stagedImportReplacesTheCompleteListAndClearsStaging() {
    val imported =
        SpatialCameraPanelProfileBundleCodec.encode(
            listOf(
                SpatialCameraPanelProfileEntry(
                    id = "profile-imported",
                    title = "Imported",
                    createdAtEpochMs = 7L,
                    controls = sampleControls(),
                )
            )
        )
    var persisted: String? = null
    var staged: String? = imported
    val authority =
        SpatialCameraPanelProfileLibraryAuthority(
            SpatialCameraPanelProfileLibraryBindings(
                readPayload = { null },
                writePayload = { persisted = it; true },
                readImportBundlePayload = { staged },
                clearImportBundlePayload = { staged = null; true },
            )
        )

    assertEquals("imported", authority.snapshot().loadStatus)
    assertEquals("profile-imported", authority.snapshot().profiles.single().id)
    assertNotNull(persisted)
    assertNull(staged)
  }

  @Test
  fun malformedEnvelopeAndOutOfBoundsControlsFailClosed() {
    val valid =
        SpatialCameraPanelProfileBundleCodec.encode(
            listOf(
                SpatialCameraPanelProfileEntry(
                    id = "profile-1",
                    title = "Valid",
                    createdAtEpochMs = 1L,
                    controls = sampleControls(),
                )
            )
        )
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraPanelProfileBundleCodec.decode(
          valid.replace("\"profile_count\": 1", "\"profile_count\": 2")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraPanelProfileBundleCodec.decode(
          valid.replaceFirst("\"projectionScale\": 0.85", "\"projectionScale\": 99.0")
      )
    }
    val duplicate =
        valid.replace(
            Regex("\"profiles\": \\[.*]", setOf(RegexOption.DOT_MATCHES_ALL)),
            "\"profiles\": [${profileObject(valid)}, ${profileObject(valid)}]",
        ).replace("\"profile_count\": 1", "\"profile_count\": 2")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraPanelProfileBundleCodec.decode(duplicate)
    }
  }

  private fun profileObject(bundle: String): String =
      bundle.substringAfter("\"profiles\": [").substringBeforeLast("]").trim()

  private fun sampleControls(): SpatialCameraPanelControlSnapshot =
      SpatialCameraPanelControlSnapshot(
              projectionPanelEnabled = true,
              layerOverride = 8.0f,
              projectionScale = 0.85f,
              depthLayerPolicy = PrivateLayerControls.depthPolicyEyeIndex,
              depthAlignment = PrivateLayerDepthAlignment(leftX = 0.01f, rightX = -0.01f),
              guideProcessing = PrivateLayerControls.gaussianRgbGuideProcessing,
              zoneCompositor = PrivateLayerZoneCompositorControls.organicBuffer,
              rgbChannelTransform = RgbChannelTransformControls.independent,
              projectionSurfaceDisplacement = ProjectionSurfaceDisplacementControls.gentle,
              projectionSurfaceTiling =
                  ProjectionSurfaceTiling(
                      enabled = true,
                      topology = ProjectionSurfaceTilingControls.topologyTriangleTiles,
                      gapNormalized = 0.08f,
                      depthFlexibility = 0.7f,
                  ),
              projectionInnerAlpha =
                  ProjectionInnerAlpha(
                      enabled = true,
                      driver = ProjectionInnerAlphaControls.driverLuma,
                      threshold = 0.4f,
                      softness = 0.08f,
                      amount = 0.5f,
                  ),
              videoPlaybackEnabled = true,
              videoPresentationMode = SpatialImmersiveVideoPresentationMode.WorldAnchored.token,
              backgroundMode = SpatialBackgroundMode.LutPassthrough.token,
          )
          .normalized()
}
