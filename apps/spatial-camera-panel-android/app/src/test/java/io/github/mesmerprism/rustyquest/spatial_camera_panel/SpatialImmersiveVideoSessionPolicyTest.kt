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
  fun rejectsAProjectionShapeOrPerEyeAspectChange() {
    val anchor = config(pack("anchor", 5760, 2880))
    val changedShape =
        config(
            pack("shape", 5760, 2880, shape = SpatialImmersiveVideoShape.Equirect360)
        )
    val changedAspect = config(pack("aspect", 3840, 2160))

    assertFalse(
        SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, changedShape)
    )
    assertFalse(
        SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, changedAspect)
    )
  }

  @Test
  fun scalesPackedStereoToTheCustomDecoderSurfaceLimit() {
    val dimensions =
        SpatialImmersiveVideoSessionPolicy.customProjectionDimensions(
            config(pack("large", 5760, 2880))
        )

    assertEquals(4096 to 2048, dimensions)
  }

  @Test
  fun previousAndNextSelectionWrapWithinTheBoundedCatalog() {
    assertEquals(2, SpatialImmersiveVideoSessionPolicy.wrappedIndex(-1, 3))
    assertEquals(0, SpatialImmersiveVideoSessionPolicy.wrappedIndex(3, 3))
    assertEquals(-1, SpatialImmersiveVideoSessionPolicy.wrappedIndex(0, 0))
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
  ): OfflineImmersiveMediaPack =
      OfflineImmersiveMediaPack(
          packId = id,
          manifestFile = File("$id-manifest.json"),
          sourceSizeBytes = 1L,
          sourceSha256 = "0".repeat(64),
          chunkSizeBytes = 1,
          shape = shape,
          stereoLayout = SpatialImmersiveVideoStereoLayout.SideBySideLeftRight,
          widthPx = widthPx,
          heightPx = heightPx,
          chunks = emptyList(),
          key = ByteArray(32),
          packagedInApk = true,
      )
}
