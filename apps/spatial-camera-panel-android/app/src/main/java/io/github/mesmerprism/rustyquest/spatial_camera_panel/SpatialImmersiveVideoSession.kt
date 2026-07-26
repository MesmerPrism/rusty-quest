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
    val presentationMode: SpatialImmersiveVideoPresentationMode,
) {
  val activeOrdinal: Int
    get() = if (activeIndex >= 0) activeIndex + 1 else 0
}

internal enum class SpatialImmersiveVideoPresentationMode(val token: String) {
  WorldAnchored("world-anchored"),
  HeadFixedBorder("head-fixed-border"),
}

internal enum class SpatialImmersiveVideoCarrierShape {
  LegacyQuad,
  WorldQuad,
  Equirect180,
  Equirect360,
}

internal data class SpatialImmersiveVideoCustomCarrierPresentation(
    val mode: SpatialImmersiveVideoPresentationMode,
    val shape: SpatialImmersiveVideoCarrierShape,
    val radiusMeters: Float,
    val flatPanelWidthMeters: Float,
    val flatPanelHeightMeters: Float,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
) {
  val worldAnchored: Boolean
    get() = mode == SpatialImmersiveVideoPresentationMode.WorldAnchored

  fun markerFields(): String =
      "videoCarrierPresentation=${mode.token} " +
          "videoCarrierShape=${shape.name.lowercase()} " +
          "headOrientationLocked=${!worldAnchored} " +
          "videoCarrierOutputExtentPx=${outputWidthPx}x$outputHeightPx"
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
  private const val LEGACY_OUTPUT_WIDTH_PX = 2048
  private const val LEGACY_OUTPUT_HEIGHT_PX = 1024
  private const val EQUIRECT_360_OUTPUT_WIDTH_PX = 4096
  private const val EQUIRECT_360_OUTPUT_HEIGHT_PX = 1024
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

  fun customCarrierPresentation(
      config: SpatialImmersiveVideoConfig?,
      mode: SpatialImmersiveVideoPresentationMode,
  ): SpatialImmersiveVideoCustomCarrierPresentation? {
    if (config == null || customProjectionDimensions(config) == null) {
      return null
    }
    if (mode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder) {
      return SpatialImmersiveVideoCustomCarrierPresentation(
          mode = mode,
          shape = SpatialImmersiveVideoCarrierShape.LegacyQuad,
          radiusMeters = config.radiusMeters,
          flatPanelWidthMeters = config.flatPanelWidthMeters,
          flatPanelHeightMeters = config.flatPanelHeightMeters,
          outputWidthPx = LEGACY_OUTPUT_WIDTH_PX,
          outputHeightPx = LEGACY_OUTPUT_HEIGHT_PX,
      )
    }
    val shape =
        when (config.shape) {
          SpatialImmersiveVideoShape.Flat -> SpatialImmersiveVideoCarrierShape.WorldQuad
          SpatialImmersiveVideoShape.Equirect180 ->
              SpatialImmersiveVideoCarrierShape.Equirect180
          SpatialImmersiveVideoShape.Equirect360 ->
              SpatialImmersiveVideoCarrierShape.Equirect360
        }
    val outputDimensions =
        if (shape == SpatialImmersiveVideoCarrierShape.Equirect360) {
          EQUIRECT_360_OUTPUT_WIDTH_PX to EQUIRECT_360_OUTPUT_HEIGHT_PX
        } else {
          LEGACY_OUTPUT_WIDTH_PX to LEGACY_OUTPUT_HEIGHT_PX
        }
    return SpatialImmersiveVideoCustomCarrierPresentation(
        mode = mode,
        shape = shape,
        radiusMeters = config.radiusMeters,
        flatPanelWidthMeters = config.flatPanelWidthMeters,
        flatPanelHeightMeters = config.flatPanelHeightMeters,
        outputWidthPx = outputDimensions.first,
        outputHeightPx = outputDimensions.second,
    )
  }

  fun wrappedIndex(requestedIndex: Int, itemCount: Int): Int =
      if (itemCount <= 0) -1 else Math.floorMod(requestedIndex, itemCount)
}
