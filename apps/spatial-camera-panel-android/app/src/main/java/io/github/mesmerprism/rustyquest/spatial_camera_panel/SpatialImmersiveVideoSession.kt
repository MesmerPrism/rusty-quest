package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.math.floor
import kotlin.math.min

internal data class SpatialImmersiveVideoSessionSnapshot(
    val requested: Boolean,
    val available: Boolean,
    val activeIndex: Int,
    val itemCount: Int,
    val activePackId: String?,
    val customProjectionCompatible: Boolean,
) {
  val activeOrdinal: Int
    get() = if (activeIndex >= 0) activeIndex + 1 else 0
}

internal data class SpatialImmersiveVideoSelection(
    val snapshot: SpatialImmersiveVideoSessionSnapshot,
    val config: SpatialImmersiveVideoConfig?,
    val changed: Boolean,
)

internal object SpatialImmersiveVideoSessionPolicy {
  private const val MAX_CUSTOM_PROJECTION_DIMENSION_PX = 4096
  private const val MIN_CUSTOM_PROJECTION_WIDTH_PX = 320
  private const val MIN_CUSTOM_PROJECTION_HEIGHT_PX = 240
  const val CUSTOM_PROJECTION_SOURCE = "encrypted-offline-pack"

  fun compatibleWithSession(
      anchor: SpatialImmersiveVideoConfig,
      candidate: SpatialImmersiveVideoConfig,
  ): Boolean =
      anchor.isEncryptedOfflinePack &&
          candidate.isEncryptedOfflinePack &&
          customProjectionDimensions(anchor) != null &&
          customProjectionDimensions(candidate) != null

  fun customProjectionSettings(
      base: SpatialVideoProjectionSettings,
      config: SpatialImmersiveVideoConfig?,
  ): SpatialVideoProjectionSettings? {
    val pack = config?.offlinePack ?: return null
    val dimensions = customProjectionDimensions(config) ?: return null
    val (scaledWidth, scaledHeight) = dimensions
    val packedLayout = customProjectionLayout(config) ?: return null
    return base.copy(
        enabled = true,
        source = CUSTOM_PROJECTION_SOURCE,
        path = pack.virtualUri.toString(),
        mediaLayout = packedLayout,
        stereoLayout = packedLayout,
        width = scaledWidth,
        height = scaledHeight,
        looping = config.loop,
    )
  }

  fun customProjectionLayout(config: SpatialImmersiveVideoConfig): String? =
      when (config.stereoLayout) {
        SpatialImmersiveVideoStereoLayout.SideBySideLeftRight ->
            "side-by-side-left-right"
        SpatialImmersiveVideoStereoLayout.TopBottom -> "top-bottom-left-right"
        SpatialImmersiveVideoStereoLayout.Mono -> null
      }

  fun customProjectionDimensions(
      config: SpatialImmersiveVideoConfig,
  ): Pair<Int, Int>? {
    if (config.stereoLayout == SpatialImmersiveVideoStereoLayout.Mono) {
      return null
    }
    val scale =
        min(
            1.0,
            min(
                MAX_CUSTOM_PROJECTION_DIMENSION_PX.toDouble() / config.widthPx,
                MAX_CUSTOM_PROJECTION_DIMENSION_PX.toDouble() / config.heightPx,
            ),
        )
    var scaledWidth = floor(config.widthPx * scale).toInt()
    var scaledHeight = floor(config.heightPx * scale).toInt()
    when (config.stereoLayout) {
      SpatialImmersiveVideoStereoLayout.SideBySideLeftRight ->
          scaledWidth -= scaledWidth % 2
      SpatialImmersiveVideoStereoLayout.TopBottom ->
          scaledHeight -= scaledHeight % 2
      SpatialImmersiveVideoStereoLayout.Mono -> return null
    }
    if (scaledWidth < MIN_CUSTOM_PROJECTION_WIDTH_PX ||
        scaledHeight < MIN_CUSTOM_PROJECTION_HEIGHT_PX) {
      return null
    }
    return scaledWidth to scaledHeight
  }

  fun wrappedIndex(requestedIndex: Int, itemCount: Int): Int =
      if (itemCount <= 0) -1 else Math.floorMod(requestedIndex, itemCount)
}
