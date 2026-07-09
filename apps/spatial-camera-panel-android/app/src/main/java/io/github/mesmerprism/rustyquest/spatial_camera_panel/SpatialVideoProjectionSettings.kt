package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import java.util.Locale

internal const val SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY =
    "debug.rustyquest.spatial.video_projection_probe"
internal const val SPATIAL_VIDEO_PROJECTION_FRAME_COUNT_UNBOUNDED = 0

private const val CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
private const val CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.path"
private const val CAMERA_HWB_PROJECTION_VIDEO_STEREO_LAYOUT_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.stereo_layout"
private const val CAMERA_HWB_PROJECTION_VIDEO_WIDTH_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.width"
private const val CAMERA_HWB_PROJECTION_VIDEO_HEIGHT_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.height"
private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.max_images"
private const val CAMERA_HWB_PROJECTION_VIDEO_FPS_CAP_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.fps_cap"
private const val CAMERA_HWB_PROJECTION_VIDEO_LOOPING_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.looping"
private const val CAMERA_HWB_PROJECTION_VIDEO_OPACITY_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.opacity"
private const val CAMERA_HWB_PROJECTION_VIDEO_HIGH_RATE_JSON_PAYLOAD_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.high_rate_json_payload"

private const val EXTRA_VIDEO_PROJECTION_ENABLED =
    "rustyquest.spatial.camera_hwb_projection_probe.video.enabled"
private const val EXTRA_VIDEO_PROJECTION_PATH =
    "rustyquest.spatial.camera_hwb_projection_probe.video.path"
private const val EXTRA_VIDEO_PROJECTION_STEREO_LAYOUT =
    "rustyquest.spatial.camera_hwb_projection_probe.video.stereo_layout"
private const val EXTRA_VIDEO_PROJECTION_WIDTH =
    "rustyquest.spatial.camera_hwb_projection_probe.video.width"
private const val EXTRA_VIDEO_PROJECTION_HEIGHT =
    "rustyquest.spatial.camera_hwb_projection_probe.video.height"
private const val EXTRA_VIDEO_PROJECTION_MAX_IMAGES =
    "rustyquest.spatial.camera_hwb_projection_probe.video.max_images"
private const val EXTRA_VIDEO_PROJECTION_FPS_CAP =
    "rustyquest.spatial.camera_hwb_projection_probe.video.fps_cap"
private const val EXTRA_VIDEO_PROJECTION_LOOPING =
    "rustyquest.spatial.camera_hwb_projection_probe.video.looping"
private const val EXTRA_VIDEO_PROJECTION_OPACITY =
    "rustyquest.spatial.camera_hwb_projection_probe.video.opacity"
private const val EXTRA_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD =
    "rustyquest.spatial.camera_hwb_projection_probe.video.high_rate_json_payload"

private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED = false
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_STEREO_LAYOUT =
    "side-by-side-left-right"
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_WIDTH_PX = 3840
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HEIGHT_PX = 1920
private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_WIDTH_PX = 320
private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_HEIGHT_PX = 240
private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_WIDTH_PX = 4096
private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_HEIGHT_PX = 4096
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_IMAGES = 3
private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_IMAGES = 2
private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES = 6
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_FPS = 30
private const val CAMERA_HWB_PROJECTION_VIDEO_MIN_FPS = 1
private const val CAMERA_HWB_PROJECTION_VIDEO_MAX_FPS = 90
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_LOOPING = true
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_OPACITY = 1.0f
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HIGH_RATE_JSON_PAYLOAD = false

internal data class SpatialVideoProjectionSettings(
    val enabled: Boolean,
    val path: String,
    val stereoLayout: String,
    val width: Int,
    val height: Int,
    val maxImages: Int,
    val fpsCap: Int,
    val looping: Boolean,
    val opacity: Float,
    val highRateJsonPayload: Boolean,
) {
  val active: Boolean
    get() = enabled && path.isNotBlank()

  companion object {
    fun disabled(): SpatialVideoProjectionSettings =
        SpatialVideoProjectionSettings(
            enabled = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED,
            path = "",
            stereoLayout = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_STEREO_LAYOUT,
            width = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_WIDTH_PX,
            height = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HEIGHT_PX,
            maxImages = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_IMAGES,
            fpsCap = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_FPS,
            looping = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_LOOPING,
            opacity = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_OPACITY,
            highRateJsonPayload = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HIGH_RATE_JSON_PAYLOAD,
        )
  }
}

internal object SpatialVideoProjectionRouteModule {
  const val MODULE_ID = "spatial-video-projection-route-policy"

  fun currentSettings(intent: Intent?): SpatialVideoProjectionSettings {
    val enabled =
        activityReadOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_ENABLED)
            ?: activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY)
            ?: CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED
    val path =
        activityReadOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_PATH)
            ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY)
    val stereoLayout =
        normalizeStereoLayout(
            activityReadOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_STEREO_LAYOUT)
                ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_STEREO_LAYOUT_PROPERTY)
        )
    val width =
        activityReadOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_WIDTH,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_WIDTH_PX,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_WIDTH_PX,
        )
            ?: activityReadIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_WIDTH_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_WIDTH_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_WIDTH_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_WIDTH_PX,
            )
    val height =
        activityReadOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_HEIGHT,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_HEIGHT_PX,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_HEIGHT_PX,
        )
            ?: activityReadIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_HEIGHT_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HEIGHT_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_HEIGHT_PX,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_HEIGHT_PX,
            )
    val maxImages =
        activityReadOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_MAX_IMAGES,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_IMAGES,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES,
        )
            ?: activityReadIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_IMAGES,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_IMAGES,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_IMAGES,
            )
    val fpsCap =
        activityReadOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_FPS_CAP,
            CAMERA_HWB_PROJECTION_VIDEO_MIN_FPS,
            CAMERA_HWB_PROJECTION_VIDEO_MAX_FPS,
        )
            ?: activityReadIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_FPS_CAP_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_FPS,
                CAMERA_HWB_PROJECTION_VIDEO_MIN_FPS,
                CAMERA_HWB_PROJECTION_VIDEO_MAX_FPS,
            )
    val looping =
        activityReadOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_LOOPING)
            ?: activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_LOOPING_PROPERTY)
            ?: CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_LOOPING
    val opacity =
        activityReadOptionalFloatIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_OPACITY,
            0.0f,
            1.0f,
        )
            ?: activityReadOptionalFloatSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_OPACITY_PROPERTY,
                0.0f,
                1.0f,
            )
            ?: CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_OPACITY
    val highRateJsonPayload =
        activityReadOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD)
            ?: activityReadOptionalBooleanSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_HIGH_RATE_JSON_PAYLOAD_PROPERTY
            )
            ?: CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_HIGH_RATE_JSON_PAYLOAD
    return SpatialVideoProjectionSettings(
        enabled = enabled,
        path = path.trim(),
        stereoLayout = stereoLayout,
        width = width,
        height = height,
        maxImages = maxImages,
        fpsCap = fpsCap,
        looping = looping,
        opacity = opacity,
        highRateJsonPayload = highRateJsonPayload,
    )
  }

  fun normalizeStereoLayout(value: String): String =
      when (value.trim().lowercase(Locale.US).replace("_", "-")) {
        "top-bottom", "top-bottom-left-right", "tb", "over-under" -> "top-bottom-left-right"
        "side-by-side", "sbs", "left-right", "side-by-side-left-right" ->
            "side-by-side-left-right"
        else -> CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_STEREO_LAYOUT
      }

  fun markerFields(settings: SpatialVideoProjectionSettings): String =
      "videoProjectionEnabled=${settings.enabled} " +
          "spatialVideoProjectionEnabled=${settings.enabled} " +
          "spatialVideoProjectionActive=${settings.active} " +
          "spatialFeatureExplicitOptIn=${settings.enabled} " +
          "spatialFeatureOptInRoute=runtime-property-or-intent-extra " +
          "videoProjectionDefaultEnabled=$CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED " +
          "videoProjectionPath=${activityMarkerToken(settings.path)} " +
          "videoProjectionPathProvided=${settings.path.isNotBlank()} " +
          "videoProjectionNoPackagedMedia=true " +
          "videoProjectionPathProperty=$CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY " +
          "videoProjectionEnabledProperty=$CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY " +
          "videoProjectionEnabledIntentExtra=$EXTRA_VIDEO_PROJECTION_ENABLED " +
          "videoProjectionPathIntentExtra=$EXTRA_VIDEO_PROJECTION_PATH " +
          "videoProjectionWidth=${settings.width} videoProjectionHeight=${settings.height} " +
          "videoProjectionMaxImages=${settings.maxImages} videoProjectionFpsCap=${settings.fpsCap} " +
          "videoProjectionLooping=${settings.looping} " +
          "videoProjectionStereoLayout=${settings.stereoLayout} " +
          "videoProjectionTarget=packed-sbs-full-eye " +
          "videoProjectionOpacity=${activityMarkerFloat(settings.opacity)} " +
          "videoProjectionHighRateJsonPayload=${settings.highRateJsonPayload} " +
          "videoProjectionStream=stereo_video " +
          "videoProjectionSource=app-private-or-device-local-file " +
          "videoProjectionSourceAuthority=android-mediacodec-surface-decoder " +
          "videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer " +
          "videoProjectionControlPlane=spatial-activity-runtime-property-or-intent-extra " +
          "videoProjectionDecodePath=MediaCodec-to-Surface " +
          "videoProjectionFormat=private " +
          "videoProjectionLeftSourceUvRect=0.000000,0.000000,0.500000,1.000000 " +
          "videoProjectionRightSourceUvRect=0.500000,0.000000,0.500000,1.000000 " +
          "videoProjectionLeftTargetPackedUvRect=0.000000,0.000000,0.500000,1.000000 " +
          "videoProjectionRightTargetPackedUvRect=0.500000,0.000000,0.500000,1.000000 " +
          "spatialVideoProjectionSameSurfaceComposition=true " +
          "videoProjectionComposedBeforeCamera=true " +
          "cameraProjectionAlignmentPreserved=true " +
          "nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false " +
          "highRateJsonPayload=${settings.highRateJsonPayload} " +
          "rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false"
}
