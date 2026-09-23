package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlainImmersiveMediaLibraryPolicyTest {
  @Test
  fun folderBootstrapPlanIsFixedCompleteAndContainsNoCallerPaths() {
    val chains = PlainImmersiveMediaPolicy.canonicalDirectoryChains()

    assertEquals(13, chains.size)
    assertEquals(listOf("plain-videos"), chains.first())
    assertTrue(chains.contains(listOf("plain-videos", "equirect-360", "top-bottom")))
    assertEquals(13, chains.distinct().size)
    assertTrue(chains.all { chain -> chain.all { '/' !in it && '\\' !in it } })
  }

  @Test
  fun canonicalFolderTaxonomyDeclaresShapeAndStereoWithoutFilenameRules() {
    val declaration =
        PlainImmersiveMediaPolicy.declaration("equirect-360", "top-bottom")

    assertEquals(SpatialImmersiveVideoShape.Equirect360, declaration?.shape)
    assertEquals(
        SpatialImmersiveVideoStereoLayout.TopBottom,
        declaration?.stereoLayout,
    )
    assertNull(PlainImmersiveMediaPolicy.declaration("360-movie", "tb"))
  }

  @Test
  fun topBottom360ContainerAndSampleGeometryAreAccepted() {
    val result =
        PlainImmersiveMediaPolicy.validate(
            requireNotNull(
                PlainImmersiveMediaPolicy.declaration("equirect-360", "top-bottom")
            ),
            probe(width = 3840, height = 3840, sampleWidth = 192, sampleHeight = 192),
        )

    assertTrue(result is PlainImmersiveMediaValidation.Accepted)
    assertEquals(2.0f, (result as PlainImmersiveMediaValidation.Accepted).perEyeAspectRatio)
  }

  @Test
  fun sideBySide180GeometryIsAccepted() {
    val result =
        PlainImmersiveMediaPolicy.validate(
            requireNotNull(
                PlainImmersiveMediaPolicy.declaration(
                    "equirect-180",
                    "side-by-side-left-right",
                )
            ),
            probe(width = 3840, height = 1920, sampleWidth = 192, sampleHeight = 96),
        )

    assertTrue(result is PlainImmersiveMediaValidation.Accepted)
  }

  @Test
  fun declarationContradictingPerEyeGeometryFailsClosed() {
    val result =
        PlainImmersiveMediaPolicy.validate(
            requireNotNull(
                PlainImmersiveMediaPolicy.declaration("equirect-360", "mono")
            ),
            probe(width = 1920, height = 1920, sampleWidth = 192, sampleHeight = 192),
        )

    assertEquals(
        "plain-video-declared-shape-geometry-mismatch",
        (result as PlainImmersiveMediaValidation.Rejected).reason,
    )
  }

  @Test
  fun metadataAndDecodedSampleMustAgree() {
    val result =
        PlainImmersiveMediaPolicy.validate(
            requireNotNull(PlainImmersiveMediaPolicy.declaration("flat", "mono")),
            probe(width = 1920, height = 1080, sampleWidth = 100, sampleHeight = 100),
        )

    assertEquals(
        "plain-video-container-sample-geometry-mismatch",
        (result as PlainImmersiveMediaValidation.Rejected).reason,
    )
  }

  @Test
  fun unsupportedRotationAndInvalidStereoPackingFailClosed() {
    val declaration =
        requireNotNull(PlainImmersiveMediaPolicy.declaration("flat", "top-bottom"))
    val rotated =
        PlainImmersiveMediaPolicy.validate(
            declaration,
            probe(1920, 1080, 192, 108).copy(rotationDegrees = 90),
        )
    val oddPacking =
        PlainImmersiveMediaPolicy.validate(
            declaration,
            probe(1920, 1079, 192, 108),
        )

    assertEquals(
        "plain-video-rotation-unsupported",
        (rotated as PlainImmersiveMediaValidation.Rejected).reason,
    )
    assertEquals(
        "plain-video-top-bottom-height-not-even",
        (oddPacking as PlainImmersiveMediaValidation.Rejected).reason,
    )
  }

  @Test
  fun catalogLabelMakesStorageShapeAndStereoVisible() {
    val label =
        PlainImmersiveMediaPolicy.mediaLabel(
            "shared-plain-video",
            SpatialImmersiveVideoShape.Equirect360,
            SpatialImmersiveVideoStereoLayout.TopBottom,
        )

    assertEquals("Plain · 360° · Top/bottom", label)
    assertFalse(label.contains(".mp4"))
  }

  private fun probe(
      width: Int,
      height: Int,
      sampleWidth: Int,
      sampleHeight: Int,
  ): PlainImmersiveMediaProbe =
      PlainImmersiveMediaProbe(
          widthPx = width,
          heightPx = height,
          rotationDegrees = 0,
          durationMs = 10_000,
          containerMimeType = "video/mp4",
          sampledFrameWidthPx = sampleWidth,
          sampledFrameHeightPx = sampleHeight,
      )
}
