package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.StereoMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialImmersiveVideoSessionPolicyTest {
  @Test
  fun changedVideoRecentersExactlyOnceWhenFirstSuccessfulRouteBecomesReady() {
    val gate = SpatialImmersiveVideoAutoRecenterGate()
    gate.arm("content://videos/new", "right-stick-next")

    val request =
        requireNotNull(
            gate.consume(
                loadedPath = "content://videos/new",
                readyRoute = "custom-projection-decoder-ready",
            )
        )

    assertEquals("right-stick-next", request.selectionSource)
    assertEquals(
        "selection-right-stick-next-ready-custom-projection-decoder-ready",
        request.detail,
    )
    assertEquals(
        null,
        gate.consume(
            loadedPath = "content://videos/new",
            readyRoute = "direct-spatial-first-frame",
        ),
    )
  }

  @Test
  fun staleOrFailedVideoRouteCannotConsumePendingRecenter() {
    val gate = SpatialImmersiveVideoAutoRecenterGate()
    gate.arm("content://videos/new", "playlist-step")

    assertEquals(
        null,
        gate.consume(
            loadedPath = "content://videos/old",
            readyRoute = "direct-spatial-first-frame",
        ),
    )
    assertTrue(
        gate.consume(
            loadedPath = "content://videos/new",
            readyRoute = "direct-spatial-first-frame",
        ) != null
    )
  }

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
  fun selectedOfflineVideoBecomesTheActiveCustomProjectionSource() {
    val selected =
        config(
            pack(
                "selected-360",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )

    val settings =
        requireNotNull(
            SpatialImmersiveVideoSessionPolicy.customProjectionSettings(
                SpatialVideoProjectionSettings.disabled(),
                selected,
            )
        )

    assertTrue(settings.enabled)
    assertEquals(SpatialImmersiveVideoSessionPolicy.CUSTOM_PROJECTION_SOURCE, settings.source)
    assertEquals("rusty-offline-media://selected-360/video", settings.path)
    assertEquals("top-bottom-left-right", settings.mediaLayout)
    assertEquals("top-bottom-left-right", settings.stereoLayout)
    assertEquals(3640, settings.width)
    assertEquals(4096, settings.height)
    assertTrue(settings.looping)
  }

  @Test
  fun persistedImmersiveSelectionDoesNotReplaceExplicitPeerStream() {
    val selected =
        config(
            pack(
                "persisted-360",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )
    val peer =
        SpatialVideoProjectionSettings.disabled().copy(
            enabled = true,
            source = "peer-packed-stereo",
            brokerPort = 8879,
            peerSessionId = "session.current-run",
        )

    assertNull(SpatialImmersiveVideoSessionPolicy.customProjectionSettings(peer, selected))
  }

  @Test
  fun validatedSharedPlainVideoFeedsBothDirectAndCustomProjectionDecoders() {
    val item =
        SharedPlainImmersiveMediaItem(
            mediaId = "0123456789abcdef",
            contentUri = "content://documents/plain-360-tb",
            shape = SpatialImmersiveVideoShape.Equirect360,
            stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            widthPx = 3840,
            heightPx = 3840,
            containerMimeType = "video/mp4",
        )
    val resolution =
        SpatialImmersiveVideoRouteModule.resolvePlainMedia(item, null, null, null)
    val config =
        (resolution as SpatialImmersiveVideoRouteResolution.Ready).config

    assertTrue(config.isSharedPlainVideo)
    assertTrue(config.isGrantedContentUri)
    assertEquals("Plain · 360° · Top/bottom", config.catalogLabel)
    val settings =
        requireNotNull(
            SpatialImmersiveVideoSessionPolicy.customProjectionSettings(
                SpatialVideoProjectionSettings.disabled(),
                config,
            )
        )
    assertEquals(
        SpatialImmersiveVideoSessionPolicy.PLAIN_CUSTOM_PROJECTION_SOURCE,
        settings.source,
    )
    assertEquals(item.contentUri, settings.path)
    assertEquals("top-bottom-left-right", settings.stereoLayout)
  }

  @Test
  fun previousAndNextSelectionWrapWithinTheBoundedCatalog() {
    assertEquals(2, SpatialImmersiveVideoSessionPolicy.wrappedIndex(-1, 3))
    assertEquals(0, SpatialImmersiveVideoSessionPolicy.wrappedIndex(3, 3))
    assertEquals(-1, SpatialImmersiveVideoSessionPolicy.wrappedIndex(0, 0))
  }

  @Test
  fun directVideoFadeOpacityIsBoundedAndDeterministic() {
    assertEquals(1.0f, SpatialImmersiveVideoSessionPolicy.fadeOpacity(1.0f, 0.0f, -1L, 300L))
    assertEquals(0.5f, SpatialImmersiveVideoSessionPolicy.fadeOpacity(1.0f, 0.0f, 150L, 300L))
    assertEquals(0.0f, SpatialImmersiveVideoSessionPolicy.fadeOpacity(1.0f, 0.0f, 600L, 300L))
    assertEquals(1.0f, SpatialImmersiveVideoSessionPolicy.fadeOpacity(0.0f, 1.0f, 300L, 300L))
    assertEquals(0.25f, SpatialImmersiveVideoSessionPolicy.fadeOpacity(0.0f, 0.25f, 1L, 0L))
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
  fun headFixedTopBottomDirectPanelOverscansOuterVideoAndKeepsEyeCrops() {
    val topBottom =
        config(
            pack(
                "top-bottom-head-fixed",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )

    val presentation =
        SpatialImmersiveVideoSessionPolicy.directPanelPresentation(
            topBottom,
            SpatialImmersiveVideoPresentationMode.HeadFixedBorder,
        )

    assertEquals(SpatialImmersiveVideoCarrierShape.LegacyQuad, presentation.shape)
    assertEquals(StereoMode.UpDown, presentation.stereoMode)
    assertEquals(3840 to 4320, presentation.displayWidthPx to presentation.displayHeightPx)
    assertEquals(16.0f / 9.0f, presentation.perEyeAspectRatio, 0.0001f)
    val quadGeometry = requireNotNull(presentation.quadGeometry)
    assertEquals(
        SpatialImmersiveVideoQuadScaleMode.AspectPreservingCover,
        quadGeometry.scaleMode,
    )
    assertEquals(5.40f, PARTICLE_LAYER_WIDTH_METERS, 0.0001f)
    assertEquals(4.00f, PARTICLE_LAYER_HEIGHT_METERS, 0.0001f)
    assertEquals(1.10f, HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE, 0.0001f)
    assertEquals(6.0885f, requireNotNull(quadGeometry.coverageTargetWidthMeters), 0.0001f)
    assertEquals(4.51f, requireNotNull(quadGeometry.coverageTargetHeightMeters), 0.0001f)
    assertEquals(4.51f * (16.0f / 9.0f), quadGeometry.widthMeters, 0.0001f)
    assertEquals(4.51f, quadGeometry.heightMeters, 0.0001f)
    assertEquals(
        presentation.perEyeAspectRatio,
        quadGeometry.widthMeters / quadGeometry.heightMeters,
        0.0001f,
    )
    assertTrue(quadGeometry.widthMeters >= requireNotNull(quadGeometry.coverageTargetWidthMeters))
    assertTrue(quadGeometry.heightMeters >= requireNotNull(quadGeometry.coverageTargetHeightMeters))
    assertTrue(
        presentation.sourceUvRectForEye(0).contentEquals(
            floatArrayOf(0.0f, 0.0f, 1.0f, 0.5f)
        )
    )
    assertTrue(
        presentation.sourceUvRectForEye(1).contentEquals(
            floatArrayOf(0.0f, 0.5f, 1.0f, 0.5f)
        )
    )
    assertEquals(0.0f, StereoMode.UpDown.view2OffsetX, 0.0001f)
    assertEquals(0.5f, StereoMode.UpDown.view2OffsetY, 0.0001f)
    assertEquals(1.0f, StereoMode.UpDown.viewScaleX, 0.0001f)
    assertEquals(0.5f, StereoMode.UpDown.viewScaleY, 0.0001f)
    assertTrue(presentation.markerFields().contains("directVideoQuadScaleMode=aspect-preserving-cover"))
    assertTrue(presentation.markerFields().contains("directVideoCoverageTargetMeters=6.0885x4.5100"))
    assertTrue(presentation.markerFields().contains("directVideoCoverageOverscanScale=1.1000"))
    assertTrue(
        presentation.markerFields().contains(
            "directVideoCarrierGeometryOwner=head-fixed-outer-video-panel"
        )
    )
    assertTrue(presentation.markerFields().contains("customProjectionGeometryUnchanged=true"))
  }

  @Test
  fun worldAnchoredFlatVideoKeepsItsSourceAspectSizeInsteadOfUsingHeadFixedCover() {
    val flat =
        config(
            pack(
                "flat-world",
                3840,
                2160,
                shape = SpatialImmersiveVideoShape.Flat,
                stereoLayout = SpatialImmersiveVideoStereoLayout.Mono,
            )
        )

    val presentation =
        SpatialImmersiveVideoSessionPolicy.directPanelPresentation(
            flat,
            SpatialImmersiveVideoPresentationMode.WorldAnchored,
        )

    assertEquals(SpatialImmersiveVideoCarrierShape.WorldQuad, presentation.shape)
    val quadGeometry = requireNotNull(presentation.quadGeometry)
    assertEquals(SpatialImmersiveVideoQuadScaleMode.SourceAspect, quadGeometry.scaleMode)
    assertEquals(flat.flatPanelWidthMeters, quadGeometry.widthMeters, 0.0001f)
    assertEquals(flat.flatPanelHeightMeters, quadGeometry.heightMeters, 0.0001f)
    assertEquals(null, quadGeometry.coverageTargetWidthMeters)
    assertEquals(null, quadGeometry.coverageTargetHeightMeters)
    assertFalse(presentation.markerFields().contains("directVideoCoverageOverscanScale"))
    assertFalse(presentation.markerFields().contains("head-fixed-outer-video-panel"))
  }

  @Test
  fun directPanelStereoContractIsIndependentOfWorldOrHeadFixedRegistration() {
    val topBottom =
        config(
            pack(
                "top-bottom-world",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
                stereoLayout = SpatialImmersiveVideoStereoLayout.TopBottom,
            )
        )
    val sideBySide = config(pack("side-by-side", 5760, 2880))

    val world =
        SpatialImmersiveVideoSessionPolicy.directPanelPresentation(
            topBottom,
            SpatialImmersiveVideoPresentationMode.WorldAnchored,
        )
    val headFixedSideBySide =
        SpatialImmersiveVideoSessionPolicy.directPanelPresentation(
            sideBySide,
            SpatialImmersiveVideoPresentationMode.HeadFixedBorder,
        )

    assertEquals(SpatialImmersiveVideoCarrierShape.Equirect360, world.shape)
    assertEquals(StereoMode.UpDown, world.stereoMode)
    assertEquals(StereoMode.LeftRight, headFixedSideBySide.stereoMode)
    assertTrue(
        headFixedSideBySide.sourceUvRectForEye(0).contentEquals(
            floatArrayOf(0.0f, 0.0f, 0.5f, 1.0f)
        )
    )
    assertTrue(
        headFixedSideBySide.sourceUvRectForEye(1).contentEquals(
            floatArrayOf(0.5f, 0.0f, 0.5f, 1.0f)
        )
    )
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

  @Test
  fun blackBackingUsesFullSphereForWorldAnchored180And360() {
    val stereo180 = config(pack("stereo-180", 5760, 2880))
    val stereo360 =
        config(
            pack(
                "stereo-360",
                3840,
                4320,
                shape = SpatialImmersiveVideoShape.Equirect360,
            )
        )

    assertEquals(
        SpatialImmersiveVideoBlackBackingShape.Equirect360,
        SpatialImmersiveVideoBlackBackingPolicy.shape(
            stereo180,
            SpatialImmersiveVideoPresentationMode.WorldAnchored,
        ),
    )
    assertEquals(
        SpatialImmersiveVideoBlackBackingShape.Equirect360,
        SpatialImmersiveVideoBlackBackingPolicy.shape(
            stereo360,
            SpatialImmersiveVideoPresentationMode.WorldAnchored,
        ),
    )
    assertEquals(50.025f, SpatialImmersiveVideoBlackBackingPolicy.radiusMeters(stereo180), 0.0001f)
  }

  @Test
  fun blackBackingUsesMatchingQuadForFlatAndHeadFixedVideo() {
    val flat =
        config(
            pack(
                "flat",
                3840,
                2160,
                shape = SpatialImmersiveVideoShape.Flat,
            )
        )
    val immersive = config(pack("immersive", 5760, 2880))

    assertEquals(
        SpatialImmersiveVideoBlackBackingShape.Quad,
        SpatialImmersiveVideoBlackBackingPolicy.shape(
            flat,
            SpatialImmersiveVideoPresentationMode.WorldAnchored,
        ),
    )
    assertEquals(
        SpatialImmersiveVideoBlackBackingShape.Quad,
        SpatialImmersiveVideoBlackBackingPolicy.shape(
            immersive,
            SpatialImmersiveVideoPresentationMode.HeadFixedBorder,
        ),
    )
    assertEquals(
        100.0f,
        SpatialImmersiveVideoBlackBackingPolicy.radiusMeters(immersive.copy(radiusMeters = 100.0f)),
        0.0001f,
    )
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
