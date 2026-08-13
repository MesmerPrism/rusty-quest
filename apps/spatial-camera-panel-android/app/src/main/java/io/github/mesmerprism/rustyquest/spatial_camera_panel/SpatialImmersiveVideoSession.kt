package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.StereoMode
import kotlin.math.floor
import kotlin.math.min

internal data class SpatialImmersiveVideoSessionSnapshot(
    val requested: Boolean,
    val available: Boolean,
    val playbackEnabled: Boolean,
    val activeIndex: Int,
    val itemCount: Int,
    val activePackId: String?,
    val activeMediaLabel: String?,
    val customProjectionCompatible: Boolean,
    val presentationMode: SpatialImmersiveVideoPresentationMode,
    val backgroundMode: SpatialBackgroundMode,
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

internal const val HEAD_FIXED_VIDEO_DISTANCE_METERS = 2.05f
internal const val HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE = 1.20f

internal enum class SpatialImmersiveVideoQuadScaleMode(val token: String) {
  SourceAspect("source-aspect"),
  AspectPreservingCover("aspect-preserving-cover"),
}

internal data class SpatialImmersiveVideoQuadGeometry(
    val widthMeters: Float,
    val heightMeters: Float,
    val scaleMode: SpatialImmersiveVideoQuadScaleMode,
    val coverageTargetWidthMeters: Float? = null,
    val coverageTargetHeightMeters: Float? = null,
) {
  fun markerFields(): String {
    val targetFields =
        if (coverageTargetWidthMeters != null && coverageTargetHeightMeters != null) {
          " directVideoCoverageTargetMeters=" +
              "${formatMarkerFloat(coverageTargetWidthMeters)}x" +
              formatMarkerFloat(coverageTargetHeightMeters) +
              " directVideoCoverageOverscanScale=" +
              formatMarkerFloat(HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE) +
              " directVideoCarrierGeometryOwner=head-fixed-outer-video-panel" +
              " customProjectionGeometryUnchanged=true"
        } else {
          ""
        }
    return "directVideoQuadScaleMode=${scaleMode.token} " +
        "directVideoQuadMeters=${formatMarkerFloat(widthMeters)}x" +
        formatMarkerFloat(heightMeters) + targetFields
  }
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

internal data class SpatialImmersiveVideoDirectPanelPresentation(
    val mode: SpatialImmersiveVideoPresentationMode,
    val shape: SpatialImmersiveVideoCarrierShape,
    val stereoMode: StereoMode,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val perEyeAspectRatio: Float,
    val quadGeometry: SpatialImmersiveVideoQuadGeometry?,
) {
  fun sourceUvRectForEye(eyeIndex: Int): FloatArray =
      when (stereoMode) {
        StereoMode.LeftRight ->
            if (eyeIndex == 0) floatArrayOf(0.0f, 0.0f, 0.5f, 1.0f)
            else floatArrayOf(0.5f, 0.0f, 0.5f, 1.0f)
        StereoMode.UpDown ->
            if (eyeIndex == 0) floatArrayOf(0.0f, 0.0f, 1.0f, 0.5f)
            else floatArrayOf(0.0f, 0.5f, 1.0f, 0.5f)
        else -> floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
      }

  fun markerFields(): String {
    val left = sourceUvRectForEye(0).joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
    val right = sourceUvRectForEye(1).joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
    return "directVideoPresentation=${mode.token} " +
        "directVideoShape=${shape.name.lowercase()} " +
        "directVideoStereoMode=${stereoMode.name.lowercase()} " +
        "directVideoLeftSourceUvRect=$left directVideoRightSourceUvRect=$right " +
        "directVideoEyeOrder=${if (stereoMode == StereoMode.UpDown) "left-eye-top,right-eye-bottom" else if (stereoMode == StereoMode.LeftRight) "left-eye-left,right-eye-right" else "mono-full-frame"} " +
        "${quadGeometry?.markerFields() ?: "directVideoQuadScaleMode=not-applicable"} " +
        "directVideoRegistrationImmutable=true"
  }
}

private fun formatMarkerFloat(value: Float): String =
    "%.4f".format(java.util.Locale.US, value)

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
  const val PLAIN_CUSTOM_PROJECTION_SOURCE = "shared-plain-video"

  fun compatibleWithSession(
      anchor: SpatialImmersiveVideoConfig,
      candidate: SpatialImmersiveVideoConfig,
  ): Boolean =
      (anchor.isEncryptedOfflinePack || anchor.isSharedPlainVideo) &&
          (candidate.isEncryptedOfflinePack || candidate.isSharedPlainVideo) &&
          customProjectionDimensions(anchor) != null &&
          customProjectionDimensions(candidate) != null

  fun customProjectionSettings(
      base: SpatialVideoProjectionSettings,
      config: SpatialImmersiveVideoConfig?,
  ): SpatialVideoProjectionSettings? {
    if (base.source == "broker-rmanvid1" || base.source == "peer-packed-stereo") {
      return null
    }
    config ?: return null
    if (!config.isEncryptedOfflinePack && !config.isSharedPlainVideo) return null
    val dimensions = customProjectionDimensions(config) ?: return null
    val (scaledWidth, scaledHeight) = dimensions
    val packedLayout = customProjectionLayout(config) ?: return null
    return base.copy(
        enabled = true,
        source =
            if (config.isEncryptedOfflinePack) {
              CUSTOM_PROJECTION_SOURCE
            } else {
              PLAIN_CUSTOM_PROJECTION_SOURCE
            },
        path = config.offlinePack?.virtualUriString ?: config.path,
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

  fun directPanelPresentation(
      config: SpatialImmersiveVideoConfig,
      mode: SpatialImmersiveVideoPresentationMode,
  ): SpatialImmersiveVideoDirectPanelPresentation {
    val shape =
        if (mode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder) {
          SpatialImmersiveVideoCarrierShape.LegacyQuad
        } else {
          when (config.shape) {
            SpatialImmersiveVideoShape.Flat -> SpatialImmersiveVideoCarrierShape.WorldQuad
            SpatialImmersiveVideoShape.Equirect180 ->
                SpatialImmersiveVideoCarrierShape.Equirect180
            SpatialImmersiveVideoShape.Equirect360 ->
                SpatialImmersiveVideoCarrierShape.Equirect360
          }
        }
    val stereoMode =
        when (config.stereoLayout) {
          SpatialImmersiveVideoStereoLayout.Mono -> StereoMode.None
          SpatialImmersiveVideoStereoLayout.SideBySideLeftRight -> StereoMode.LeftRight
          SpatialImmersiveVideoStereoLayout.TopBottom -> StereoMode.UpDown
        }
    val quadGeometry =
        when (shape) {
          SpatialImmersiveVideoCarrierShape.LegacyQuad ->
              headFixedCoverGeometry(config.perEyeAspectRatio)
          SpatialImmersiveVideoCarrierShape.WorldQuad ->
              SpatialImmersiveVideoQuadGeometry(
                  widthMeters = config.flatPanelWidthMeters,
                  heightMeters = config.flatPanelHeightMeters,
                  scaleMode = SpatialImmersiveVideoQuadScaleMode.SourceAspect,
              )
          SpatialImmersiveVideoCarrierShape.Equirect180,
          SpatialImmersiveVideoCarrierShape.Equirect360 -> null
        }
    return SpatialImmersiveVideoDirectPanelPresentation(
        mode = mode,
        shape = shape,
        stereoMode = stereoMode,
        displayWidthPx = config.widthPx,
        displayHeightPx = config.heightPx,
        perEyeAspectRatio = config.perEyeAspectRatio,
        quadGeometry = quadGeometry,
    )
  }

  private fun headFixedCoverGeometry(
      perEyeAspectRatio: Float,
  ): SpatialImmersiveVideoQuadGeometry {
    val distanceScale =
        HEAD_FIXED_VIDEO_DISTANCE_METERS / PARTICLE_LAYER_TARGET_DISTANCE_METERS
    val targetWidthMeters =
        PARTICLE_LAYER_WIDTH_METERS * distanceScale * HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE
    val targetHeightMeters =
        PARTICLE_LAYER_HEIGHT_METERS * distanceScale * HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE
    val targetAspectRatio = targetWidthMeters / targetHeightMeters
    val (widthMeters, heightMeters) =
        if (perEyeAspectRatio >= targetAspectRatio) {
          (targetHeightMeters * perEyeAspectRatio) to targetHeightMeters
        } else {
          targetWidthMeters to (targetWidthMeters / perEyeAspectRatio)
        }
    return SpatialImmersiveVideoQuadGeometry(
        widthMeters = widthMeters,
        heightMeters = heightMeters,
        scaleMode = SpatialImmersiveVideoQuadScaleMode.AspectPreservingCover,
        coverageTargetWidthMeters = targetWidthMeters,
        coverageTargetHeightMeters = targetHeightMeters,
    )
  }

  fun wrappedIndex(requestedIndex: Int, itemCount: Int): Int =
      if (itemCount <= 0) -1 else Math.floorMod(requestedIndex, itemCount)

  fun fadeOpacity(
      from: Float,
      to: Float,
      elapsedMs: Long,
      durationMs: Long,
  ): Float {
    if (durationMs <= 0L) {
      return to.coerceIn(0.0f, 1.0f)
    }
    val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
    return (from + ((to - from) * progress)).coerceIn(0.0f, 1.0f)
  }
}
