package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialImmersiveVideoSessionPolicyTest {
  @Test
  fun acceptsDifferentSourceDimensionsWithinOneProjectionClass() {
    val anchor = config(pack("anchor", 5760, 2880))
    val candidate = config(pack("candidate", 4096, 2048))

    assertTrue(
        SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, candidate)
    )
  }

  @Test
  fun acceptsMixedShapeAspectAndStereoPackingForEncryptedStereoSources() {
    val anchor = config(pack("anchor", 5760, 2880))
    val topBottom360 =
        config(
            pack(
                "top-bottom-360",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )

    assertTrue(
        SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, topBottom360)
    )
  }

  @Test
  fun rejectsMonoOrUnencryptedSourcesFromTheCustomStereoSession() {
    val anchor = config(pack("anchor", 5760, 2880))
    val mono =
        config(
            pack(
                "mono",
                7680,
                3840,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.Mono,
            )
        )
    val unencrypted = config(pack("plain", 4096, 2048)).copy(offlinePack = null)

    assertFalse(SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, mono))
    assertFalse(
        SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, unencrypted)
    )
  }

  @Test
  fun scalesSideBySideStereoToTheCustomDecoderSurfaceLimit() {
    val dimensions =
        SpatialImmersiveVideoSessionPolicy.customProjectionDimensions(
            config(pack("large", 5760, 2880))
        )

    assertEquals(4096 to 2048, dimensions)
  }

  @Test
  fun scalesTopBottomStereoAndPreservesItsEyePacking() {
    val topBottom =
        config(
            pack(
                "top-bottom",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )

    assertEquals(
        3640 to 4096,
        SpatialImmersiveVideoSessionPolicy.customProjectionDimensions(topBottom),
    )
    val layout =
        requireNotNull(SpatialImmersiveVideoSessionPolicy.customProjectionLayout(topBottom))
    assertEquals("top-bottom-left-right", layout)
    val settings =
        SpatialVideoProjectionSettings.disabled().copy(
            mediaLayout = layout,
            stereoLayout = layout,
        )
    val marker = SpatialVideoProjectionRouteModule.markerFields(settings)
    assertTrue(
        marker.contains(
            "videoProjectionLeftSourceUvRect=0.000000,0.000000,1.000000,0.500000"
        )
    )
    assertTrue(
        marker.contains(
            "videoProjectionRightSourceUvRect=0.000000,0.500000,1.000000,0.500000"
        )
    )
  }

  @Test
  fun previousAndNextSelectionWrapWithinTheBoundedCatalog() {
    assertEquals(2, SpatialImmersiveVideoSessionPolicy.wrappedIndex(-1, 3))
    assertEquals(0, SpatialImmersiveVideoSessionPolicy.wrappedIndex(3, 3))
    assertEquals(-1, SpatialImmersiveVideoSessionPolicy.wrappedIndex(0, 0))
  }

  @Test
  fun worldAnchored360UsesEquirectCarrierWithTwoToOnePerEyeOutput() {
    val stereo360 =
        config(
            pack(
                "stereo-360",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )

    val presentation =
        requireNotNull(
            SpatialImmersiveVideoSessionPolicy.customCarrierPresentation(
                stereo360,
                SpatialImmersiveVideoPresentationMode.WorldAnchored,
            )
        )

    assertTrue(presentation.worldAnchored)
    assertEquals(SpatialImmersiveVideoCarrierShape.Equirect360, presentation.shape)
    assertEquals(4096, presentation.outputWidthPx)
    assertEquals(1024, presentation.outputHeightPx)
    assertTrue(presentation.markerFields().contains("headOrientationLocked=false"))
  }

  @Test
  fun headFixedBorderPreservesLegacyStereoQuadFor360Video() {
    val stereo360 =
        config(
            pack(
                "stereo-360",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )

    val presentation =
        requireNotNull(
            SpatialImmersiveVideoSessionPolicy.customCarrierPresentation(
                stereo360,
                SpatialImmersiveVideoPresentationMode.HeadFixedBorder,
            )
        )

    assertFalse(presentation.worldAnchored)
    assertEquals(SpatialImmersiveVideoCarrierShape.LegacyQuad, presentation.shape)
    assertEquals(2048 to 1024, presentation.outputWidthPx to presentation.outputHeightPx)
    assertTrue(presentation.markerFields().contains("headOrientationLocked=true"))
  }

  @Test
  fun worldAnchored180KeepsSquarePerEyePackedStereoOutput() {
    val presentation =
        requireNotNull(
            SpatialImmersiveVideoSessionPolicy.customCarrierPresentation(
                config(pack("stereo-180", 5760, 2880)),
                SpatialImmersiveVideoPresentationMode.WorldAnchored,
            )
        )

    assertEquals(SpatialImmersiveVideoCarrierShape.Equirect180, presentation.shape)
    assertEquals(2048 to 1024, presentation.outputWidthPx to presentation.outputHeightPx)
  }

  private fun config(pack: OfflineImmersiveMediaPack): SpatialImmersiveVideoConfig =
      SpatialImmersiveVideoConfig(
          path = "rusty-offline-media://${pack.packId}/video",
          shape = pack.shape,
          stereoLayout = pack.stereoLayout,
          widthPx = pack.widthPx,
          heightPx = pack.heightPx,
          autoplay = true,
          loop = true,
          radiusMeters = 50.0f,
          offlinePack = pack,
      )

  private fun pack(
      id: String,
      widthPx: Int,
      heightPx: Int,
      shape: SpatialImmersiveVideoShape = SpatialImmersiveVideoShape.Equirect180,
      stereoLayout: SpatialImmersiveVideoStereoLayout =
          SpatialImmersiveVideoStereoLayout.SideBySideLeftRight,
  ): OfflineImmersiveMediaPack =
      OfflineImmersiveMediaPack(
          packId = id,
          manifestFile = File("$id-manifest.json"),
          sourceSizeBytes = 1L,
          sourceSha256 = "0".repeat(64),
          chunkSizeBytes = 1,
          shape = shape,
          stereoLayout = stereoLayout,
          widthPx = widthPx,
          heightPx = heightPx,
          chunks = emptyList(),
          key = ByteArray(32),
          packagedInApk = true,
      )
}
