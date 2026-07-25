package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.File

internal enum class SpatialImmersiveVideoShape(val token: String) {
  Flat("flat"),
  Equirect180("equirect-180"),
  Equirect360("equirect-360");

  companion object {
    fun fromToken(token: String): SpatialImmersiveVideoShape? =
        entries.firstOrNull { it.token == token.trim().lowercase() }
  }
}

internal enum class SpatialImmersiveVideoStereoLayout(val token: String) {
  Mono("mono"),
  SideBySideLeftRight("side-by-side-left-right"),
  TopBottom("top-bottom");

  companion object {
    fun fromToken(token: String): SpatialImmersiveVideoStereoLayout? =
        entries.firstOrNull { it.token == token.trim().lowercase() }
  }
}

internal data class SpatialImmersiveVideoLaunchValues(
    val enabled: String?,
    val path: String?,
    val shape: String?,
    val stereo: String?,
    val widthPx: String?,
    val heightPx: String?,
    val autoplay: String?,
    val loop: String?,
    val radiusMeters: String?,
)

internal data class SpatialImmersiveVideoConfig(
    val path: String,
    val shape: SpatialImmersiveVideoShape,
    val stereoLayout: SpatialImmersiveVideoStereoLayout,
    val widthPx: Int,
    val heightPx: Int,
    val autoplay: Boolean,
    val loop: Boolean,
    val radiusMeters: Float,
    val offlinePack: OfflineImmersiveMediaPack? = null,
) {
  val isGrantedContentUri: Boolean
    get() = path.startsWith("content://")

  val isEncryptedOfflinePack: Boolean
    get() = offlinePack != null

  val perEyeAspectRatio: Float
    get() =
        when (stereoLayout) {
          SpatialImmersiveVideoStereoLayout.Mono -> widthPx.toFloat() / heightPx.toFloat()
          SpatialImmersiveVideoStereoLayout.SideBySideLeftRight ->
              (widthPx / 2.0f) / heightPx.toFloat()
          SpatialImmersiveVideoStereoLayout.TopBottom ->
              widthPx.toFloat() / (heightPx / 2.0f)
        }

  val flatPanelHeightMeters: Float
    get() = 1.8f

  val flatPanelWidthMeters: Float
    get() = (flatPanelHeightMeters * perEyeAspectRatio).coerceIn(1.0f, 5.0f)

  val zIndex: Int
    get() = if (shape == SpatialImmersiveVideoShape.Flat) 0 else -1

  fun markerFields(): String =
      "immersiveVideo=true projectionShape=${shape.token} stereoLayout=${stereoLayout.token} " +
          "sourceWidthPx=$widthPx sourceHeightPx=$heightPx " +
          "directToSurface=true readableSurface=false zIndex=$zIndex " +
          "autoplay=$autoplay loop=$loop " +
          "offlineEncryptedPack=$isEncryptedOfflinePack embeddedKeyPrototype=$isEncryptedOfflinePack " +
          "encryptedMediaPackagedInApk=${offlinePack?.packagedInApk == true} " +
          "plaintextFileWritten=false"
}

internal sealed class SpatialImmersiveVideoRouteResolution {
  data object Disabled : SpatialImmersiveVideoRouteResolution()

  data class Ready(val config: SpatialImmersiveVideoConfig) :
      SpatialImmersiveVideoRouteResolution()

  data class Rejected(val reason: String) : SpatialImmersiveVideoRouteResolution()

  val requested: Boolean
    get() = this !is Disabled
}

internal object SpatialImmersiveVideoRouteModule {
  const val EXTRA_ENABLED =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_ENABLED"
  const val EXTRA_PATH =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_PATH"
  const val EXTRA_SHAPE =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_SHAPE"
  const val EXTRA_STEREO =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_STEREO"
  const val EXTRA_WIDTH_PX =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_WIDTH_PX"
  const val EXTRA_HEIGHT_PX =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_HEIGHT_PX"
  const val EXTRA_AUTOPLAY =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_AUTOPLAY"
  const val EXTRA_LOOP =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_LOOP"
  const val EXTRA_RADIUS_METERS =
      "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_RADIUS_METERS"

  private const val DEFAULT_RADIUS_METERS = 50.0f
  private const val MAX_SOURCE_DIMENSION_PX = 16_384

  fun resolve(
      values: SpatialImmersiveVideoLaunchValues,
      allowedMediaRoot: String,
      sourceReadable: (String) -> Boolean = { File(it).isFile },
  ): SpatialImmersiveVideoRouteResolution {
    val enabled = parseBoolean(values.enabled)
    if (values.enabled == null || enabled == false) {
      return SpatialImmersiveVideoRouteResolution.Disabled
    }
    if (enabled == null) {
      return SpatialImmersiveVideoRouteResolution.Rejected("enabled-not-boolean")
    }

    val path = values.path?.trim().orEmpty()
    if (path.isEmpty()) {
      return SpatialImmersiveVideoRouteResolution.Rejected("path-missing")
    }
    val isGrantedContentUri = path.startsWith("content://")
    if (isGrantedContentUri) {
      if (!path.matches(Regex("^content://media/(external|external_primary)/video/media/[0-9]+$"))) {
        return SpatialImmersiveVideoRouteResolution.Rejected("content-uri-not-media-video")
      }
      if (!sourceReadable(path)) {
        return SpatialImmersiveVideoRouteResolution.Rejected("content-uri-not-readable")
      }
    } else {
      val allowedRoot = allowedMediaRoot.trimEnd('/', '\\') + "/"
      if (!path.startsWith(allowedRoot) ||
          path.contains("/../") ||
          path.endsWith("/..") ||
          path.contains('\\')) {
        return SpatialImmersiveVideoRouteResolution.Rejected("path-outside-app-scoped-media-root")
      }
      if (!sourceReadable(path)) {
        return SpatialImmersiveVideoRouteResolution.Rejected("path-not-found")
      }
    }

    val shape =
        values.shape?.let(SpatialImmersiveVideoShape::fromToken)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("projection-shape-unknown")
    val stereo =
        values.stereo?.let(SpatialImmersiveVideoStereoLayout::fromToken)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("stereo-layout-unknown")
    val widthPx =
        parseDimension(values.widthPx)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("source-width-invalid")
    val heightPx =
        parseDimension(values.heightPx)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("source-height-invalid")
    if (stereo == SpatialImmersiveVideoStereoLayout.SideBySideLeftRight && widthPx % 2 != 0) {
      return SpatialImmersiveVideoRouteResolution.Rejected("side-by-side-width-not-even")
    }
    if (stereo == SpatialImmersiveVideoStereoLayout.TopBottom && heightPx % 2 != 0) {
      return SpatialImmersiveVideoRouteResolution.Rejected("top-bottom-height-not-even")
    }

    val autoplay =
        parseBooleanOrDefault(values.autoplay, true)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("autoplay-not-boolean")
    val loop =
        parseBooleanOrDefault(values.loop, true)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("loop-not-boolean")
    val radiusMeters =
        if (values.radiusMeters.isNullOrBlank()) {
          DEFAULT_RADIUS_METERS
        } else {
          values.radiusMeters.toFloatOrNull()
              ?: return SpatialImmersiveVideoRouteResolution.Rejected("radius-invalid")
        }
    if (!radiusMeters.isFinite() || radiusMeters !in 1.0f..100.0f) {
      return SpatialImmersiveVideoRouteResolution.Rejected("radius-out-of-range")
    }

    return SpatialImmersiveVideoRouteResolution.Ready(
        SpatialImmersiveVideoConfig(
            path = path,
            shape = shape,
            stereoLayout = stereo,
            widthPx = widthPx,
            heightPx = heightPx,
            autoplay = autoplay,
            loop = loop,
            radiusMeters = radiusMeters,
        )
    )
  }

  fun resolveOfflinePack(
      pack: OfflineImmersiveMediaPack,
      autoplay: String?,
      loop: String?,
      radiusMeters: String?,
  ): SpatialImmersiveVideoRouteResolution {
    val autoplayValue =
        parseBooleanOrDefault(autoplay, true)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("autoplay-not-boolean")
    val loopValue =
        parseBooleanOrDefault(loop, true)
            ?: return SpatialImmersiveVideoRouteResolution.Rejected("loop-not-boolean")
    val radiusValue =
        if (radiusMeters.isNullOrBlank()) {
          DEFAULT_RADIUS_METERS
        } else {
          radiusMeters.toFloatOrNull()
              ?: return SpatialImmersiveVideoRouteResolution.Rejected("radius-invalid")
        }
    if (!radiusValue.isFinite() || radiusValue !in 1.0f..100.0f) {
      return SpatialImmersiveVideoRouteResolution.Rejected("radius-out-of-range")
    }
    return SpatialImmersiveVideoRouteResolution.Ready(
        SpatialImmersiveVideoConfig(
            path = pack.virtualUri.toString(),
            shape = pack.shape,
            stereoLayout = pack.stereoLayout,
            widthPx = pack.widthPx,
            heightPx = pack.heightPx,
            autoplay = autoplayValue,
            loop = loopValue,
            radiusMeters = radiusValue,
            offlinePack = pack,
        )
    )
  }

  fun routePolicyMarker(resolution: SpatialImmersiveVideoRouteResolution): String =
      when (resolution) {
        SpatialImmersiveVideoRouteResolution.Disabled ->
            "channel=spatial-immersive-video status=route-disabled explicitOptIn=false"
        is SpatialImmersiveVideoRouteResolution.Rejected ->
            "channel=spatial-immersive-video status=route-rejected explicitOptIn=true " +
                "reason=${activityMarkerToken(resolution.reason)} failClosed=true"
        is SpatialImmersiveVideoRouteResolution.Ready ->
            "channel=spatial-immersive-video status=route-ready explicitOptIn=true " +
                resolution.config.markerFields()
      }

  private fun parseDimension(raw: String?): Int? =
      raw?.toIntOrNull()?.takeIf { it in 1..MAX_SOURCE_DIMENSION_PX }

  private fun parseBooleanOrDefault(raw: String?, defaultValue: Boolean): Boolean? =
      if (raw == null) defaultValue else parseBoolean(raw)

  private fun parseBoolean(raw: String?): Boolean? =
      when (raw?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
      }
}
