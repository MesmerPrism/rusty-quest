package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpatialImmersiveVideoRouteModuleTest {
  private val packageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
  private val allowedMediaRoot =
      "/storage/emulated/0/Android/data/$packageName/files/immersive-video"
  private val path = "$allowedMediaRoot/v.mp4"

  @Test
  fun routeIsDisabledWithoutExplicitOptIn() {
    val resolution = resolve(values(enabled = null))
    assertIs<SpatialImmersiveVideoRouteResolution.Disabled>(resolution)
    assertFalse(resolution.requested)
  }

  @Test
  fun routesEquirect180SideBySideStereo() {
    val resolution =
        assertIs<SpatialImmersiveVideoRouteResolution.Ready>(
            resolve(
                values(
                    shape = "equirect-180",
                    stereo = "side-by-side-left-right",
                    widthPx = "5120",
                    heightPx = "2560",
                )
            )
        )
    assertEquals(SpatialImmersiveVideoShape.Equirect180, resolution.config.shape)
    assertEquals(
        SpatialImmersiveVideoStereoLayout.SideBySideLeftRight,
        resolution.config.stereoLayout,
    )
    assertEquals(-1, resolution.config.zIndex)
    assertEquals(1.0f, resolution.config.perEyeAspectRatio)
  }

  @Test
  fun routesEquirect360MonoEvenWhenSourceMetadataWouldClaimStereo() {
    val resolution =
        assertIs<SpatialImmersiveVideoRouteResolution.Ready>(
            resolve(
                values(
                    shape = "equirect-360",
                    stereo = "mono",
                    widthPx = "7680",
                    heightPx = "3840",
                )
            )
        )
    assertEquals(SpatialImmersiveVideoShape.Equirect360, resolution.config.shape)
    assertEquals(SpatialImmersiveVideoStereoLayout.Mono, resolution.config.stereoLayout)
    assertEquals(2.0f, resolution.config.perEyeAspectRatio)
  }

  @Test
  fun routesAReadableGrantedMediaContentUriWithoutBroadStorageAccess() {
    val contentUri = "content://media/external/video/media/1000000047"
    val resolution =
        assertIs<SpatialImmersiveVideoRouteResolution.Ready>(
            SpatialImmersiveVideoRouteModule.resolve(
                values(
                    path = contentUri,
                    shape = "equirect-360",
                    stereo = "mono",
                    widthPx = "7680",
                    heightPx = "3840",
                ),
                allowedMediaRoot,
            ) { candidate ->
              candidate == contentUri
            }
        )
    assertTrue(resolution.config.isGrantedContentUri)
    assertEquals(contentUri, resolution.config.path)
  }

  @Test
  fun rejectsContentUrisOutsideTheMediaVideoCollection() {
    val resolution =
        SpatialImmersiveVideoRouteModule.resolve(
            values(path = "content://untrusted.provider/video/7"),
            allowedMediaRoot,
        ) {
          true
        }
    assertEquals(
        "content-uri-not-media-video",
        assertIs<SpatialImmersiveVideoRouteResolution.Rejected>(resolution).reason,
    )
  }

  @Test
  fun supportsFlatAndTopBottomWithoutChangingTheRoutingContract() {
    val resolution =
        assertIs<SpatialImmersiveVideoRouteResolution.Ready>(
            resolve(
                values(
                    shape = "flat",
                    stereo = "top-bottom",
                    widthPx = "3840",
                    heightPx = "2160",
                )
            )
        )
    assertEquals(SpatialImmersiveVideoShape.Flat, resolution.config.shape)
    assertEquals(SpatialImmersiveVideoStereoLayout.TopBottom, resolution.config.stereoLayout)
    assertEquals(0, resolution.config.zIndex)
    assertTrue(resolution.config.flatPanelWidthMeters > resolution.config.flatPanelHeightMeters)
  }

  @Test
  fun rejectsUnknownProjectionInsteadOfGuessingFromFilenameOrMetadata() {
    val resolution = resolve(values(shape = "auto", stereo = "side-by-side-left-right"))
    assertEquals(
        "projection-shape-unknown",
        assertIs<SpatialImmersiveVideoRouteResolution.Rejected>(resolution).reason,
    )
    assertTrue(resolution.requested)
  }

  @Test
  fun rejectsMediaOutsideThePackageScopedImmersiveVideoDirectory() {
    val resolution =
        resolve(
            values(
                path = "/sdcard/Movies/private.mp4",
                shape = "equirect-360",
                stereo = "mono",
            )
        )
    assertEquals(
        "path-outside-app-scoped-media-root",
        assertIs<SpatialImmersiveVideoRouteResolution.Rejected>(resolution).reason,
    )
  }

  @Test
  fun rejectsInvalidPackedStereoGeometry() {
    val resolution =
        resolve(
            values(
                shape = "equirect-180",
                stereo = "side-by-side-left-right",
                widthPx = "5119",
            )
        )
    assertEquals(
        "side-by-side-width-not-even",
        assertIs<SpatialImmersiveVideoRouteResolution.Rejected>(resolution).reason,
    )
  }

  private fun resolve(
      values: SpatialImmersiveVideoLaunchValues
  ): SpatialImmersiveVideoRouteResolution =
      SpatialImmersiveVideoRouteModule.resolve(values, allowedMediaRoot) { candidate ->
        candidate == path
      }

  private fun values(
      enabled: String? = "true",
      path: String = this.path,
      shape: String = "equirect-180",
      stereo: String = "side-by-side-left-right",
      widthPx: String = "5120",
      heightPx: String = "2560",
  ): SpatialImmersiveVideoLaunchValues =
      SpatialImmersiveVideoLaunchValues(
          enabled = enabled,
          path = path,
          shape = shape,
          stereo = stereo,
          widthPx = widthPx,
          heightPx = heightPx,
          autoplay = null,
          loop = null,
          radiusMeters = null,
      )
}
