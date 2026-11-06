package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.toolkit.MediaPanelShapeOptions

internal const val IMMERSIVE_VIDEO_TEXTURE_ROUTE_PROPERTY =
    "debug.rustyquest.spatial.immersive_video.texture_route"

/** Construction-time diagnostic; both supported routes retain the declared compositor layer. */
internal enum class SpatialImmersiveVideoPanelTextureRoute(val code: Int, val token: String) {
  AndroidSwapchainLayer(0, "android-swapchain-layer"),
  SceneTextureLayer(1, "scene-texture-layer");

  fun wrapShape(
      declaredShape: MediaPanelShapeOptions,
      onApplied: (PanelConfigOptions) -> Unit = {},
  ): MediaPanelShapeOptions =
      object : MediaPanelShapeOptions {
        override fun applyTo(options: PanelConfigOptions) {
          declaredShape.applyTo(options)
          if (this@SpatialImmersiveVideoPanelTextureRoute == SceneTextureLayer) {
            options.forceSceneTexture = true
          }
          onApplied(options)
        }
      }

  companion object {
    fun fromCode(code: Int): SpatialImmersiveVideoPanelTextureRoute =
        if (code == 1) SceneTextureLayer else AndroidSwapchainLayer

    fun parseCode(value: String?): Int = if (value?.trim() == "1") 1 else 0
  }
}
