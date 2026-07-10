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
private const val CAMERA_HWB_PROJECTION_VIDEO_SOURCE_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.source"
private const val CAMERA_HWB_PROJECTION_VIDEO_BROKER_HOST_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.broker.host"
private const val CAMERA_HWB_PROJECTION_VIDEO_BROKER_PORT_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.broker.port"
private const val CAMERA_HWB_PROJECTION_VIDEO_BROKER_CONNECT_TIMEOUT_MS_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.broker.connect_timeout_ms"
private const val CAMERA_HWB_PROJECTION_VIDEO_MEDIA_LAYOUT_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.video.media_layout"
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
private const val EXTRA_VIDEO_PROJECTION_SOURCE =
    "rustyquest.spatial.camera_hwb_projection_probe.video.source"
private const val EXTRA_VIDEO_PROJECTION_BROKER_HOST =
    "rustyquest.spatial.camera_hwb_projection_probe.video.broker.host"
private const val EXTRA_VIDEO_PROJECTION_BROKER_PORT =
    "rustyquest.spatial.camera_hwb_projection_probe.video.broker.port"
private const val EXTRA_VIDEO_PROJECTION_BROKER_CONNECT_TIMEOUT_MS =
    "rustyquest.spatial.camera_hwb_projection_probe.video.broker.connect_timeout_ms"
private const val EXTRA_VIDEO_PROJECTION_MEDIA_LAYOUT =
    "rustyquest.spatial.camera_hwb_projection_probe.video.media_layout"
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
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_SOURCE = "app-private-file"
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_HOST = "127.0.0.1"
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_PORT = 0
private const val CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_CONNECT_TIMEOUT_MS = 60000
private const val CAMERA_HWB_PROJECTION_VIDEO_PACKED_MEDIA_LAYOUT = "side-by-side-left-right"
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
    val source: String,
    val path: String,
    val brokerHost: String,
    val brokerPort: Int,
    val brokerConnectTimeoutMs: Int,
    val mediaLayout: String,
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
    get() = enabled && (if (source == "broker-rmanvid1") brokerPort > 0 else path.isNotBlank())

  companion object {
    fun disabled(): SpatialVideoProjectionSettings =
        SpatialVideoProjectionSettings(
            enabled = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED,
            source = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_SOURCE,
            path = "",
            brokerHost = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_HOST,
            brokerPort = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_PORT,
            brokerConnectTimeoutMs = CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_CONNECT_TIMEOUT_MS,
            mediaLayout = CAMERA_HWB_PROJECTION_VIDEO_PACKED_MEDIA_LAYOUT,
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

  private const val CHANNEL = "spatial-video-projection"
  private const val CARRIER = "scenequadlayer-createAsAndroid-vulkan-wsi"
  private const val OUTPUT_MODE = "video-only-full-sbs"

  fun currentSettings(intent: Intent?): SpatialVideoProjectionSettings {
    val enabled =
        activityReadOptionalBooleanIntentExtra(intent, EXTRA_VIDEO_PROJECTION_ENABLED)
            ?: activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_ENABLED_PROPERTY)
            ?: CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_ENABLED
    val path =
        activityReadOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_PATH)
            ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_PATH_PROPERTY)
    val source =
        normalizeSource(
            activityReadOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_SOURCE)
                ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_SOURCE_PROPERTY)
        )
    val brokerHost =
        (activityReadOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_BROKER_HOST)
                ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_BROKER_HOST_PROPERTY))
            .trim()
            .ifBlank { CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_HOST }
    val brokerPort =
        activityReadOptionalIntIntentExtra(intent, EXTRA_VIDEO_PROJECTION_BROKER_PORT, 0, 65535)
            ?: activityReadIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_BROKER_PORT_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_PORT,
                0,
                65535,
            )
    val brokerConnectTimeoutMs =
        activityReadOptionalIntIntentExtra(
            intent,
            EXTRA_VIDEO_PROJECTION_BROKER_CONNECT_TIMEOUT_MS,
            100,
            60000,
        )
            ?: activityReadIntSystemProperty(
                CAMERA_HWB_PROJECTION_VIDEO_BROKER_CONNECT_TIMEOUT_MS_PROPERTY,
                CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_BROKER_CONNECT_TIMEOUT_MS,
                100,
                60000,
            )
    val mediaLayout =
        normalizeMediaLayout(
            activityReadOptionalStringIntentExtra(intent, EXTRA_VIDEO_PROJECTION_MEDIA_LAYOUT)
                ?: activityReadSystemProperty(CAMERA_HWB_PROJECTION_VIDEO_MEDIA_LAYOUT_PROPERTY)
        )
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
        source = source,
        path = path.trim(),
        brokerHost = brokerHost,
        brokerPort = brokerPort,
        brokerConnectTimeoutMs = brokerConnectTimeoutMs,
        mediaLayout = mediaLayout,
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

  fun normalizeSource(value: String): String =
      when (value.trim().lowercase(Locale.US).replace("_", "-")) {
        "broker-rmanvid1", "rmanvid1" -> "broker-rmanvid1"
        else -> CAMERA_HWB_PROJECTION_VIDEO_DEFAULT_SOURCE
      }

  fun normalizeMediaLayout(value: String): String =
      when (value.trim().lowercase(Locale.US).replace("_", "-")) {
        "side-by-side", "sbs", "left-right", "side-by-side-left-right" ->
            CAMERA_HWB_PROJECTION_VIDEO_PACKED_MEDIA_LAYOUT
        else -> CAMERA_HWB_PROJECTION_VIDEO_PACKED_MEDIA_LAYOUT
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
          "videoProjectionSource=${settings.source} " +
          "videoProjectionMediaLayout=${settings.mediaLayout} " +
          "videoProjectionBrokerHost=${activityMarkerToken(settings.brokerHost)} " +
          "videoProjectionBrokerPort=${settings.brokerPort} " +
          "videoProjectionBrokerConnectTimeoutMs=${settings.brokerConnectTimeoutMs} " +
          "videoProjectionWidth=${settings.width} videoProjectionHeight=${settings.height} " +
          "videoProjectionMaxImages=${settings.maxImages} videoProjectionFpsCap=${settings.fpsCap} " +
          "videoProjectionLooping=${settings.looping} " +
          "videoProjectionStereoLayout=${settings.stereoLayout} " +
          "videoProjectionTarget=packed-sbs-full-eye " +
          "videoProjectionOpacity=${activityMarkerFloat(settings.opacity)} " +
          "videoProjectionHighRateJsonPayload=${settings.highRateJsonPayload} " +
          "videoProjectionStream=stereo_video " +
          "videoProjectionSourceAuthority=${if (settings.source == "broker-rmanvid1") "manifold-broker-rmanvid1-packed-camera2-h264" else "android-mediacodec-surface-decoder"} " +
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

  fun startDeferredForSceneMarker(reason: String): String =
      "channel=$CHANNEL status=start-deferred " +
          "reason=${activityMarkerToken(reason)} deferredUntil=scene-ready " +
          "sceneReady=false runtimeCrash=false"

  fun startDeferredForVirtualRoomMarker(reason: String, sceneReady: Boolean): String =
      "channel=$CHANNEL status=start-deferred " +
          "reason=${activityMarkerToken(reason)} deferredUntil=virtual-room-loaded " +
          "sceneReady=$sceneReady spatialVirtualRoomLoaded=false runtimeCrash=false"

  fun startMarker(
      reason: String,
      widthPx: Int,
      heightPx: Int,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      settings: SpatialVideoProjectionSettings,
  ): String =
      "channel=$CHANNEL status=start videoOnlySpatialProjection=true " +
          "reason=${activityMarkerToken(reason)} debugProperty=$SPATIAL_VIDEO_PROJECTION_PROBE_PROPERTY " +
          "widthPx=$widthPx heightPx=$heightPx " +
          "requestedFrames=0 frameLimit=none carrier=$CARRIER " +
          "cameraRuntimeStarted=false rawCameraProjectionProbe=false " +
          "${projectionMarkerFields.trim()} " +
          "${stereoMarkerFields.trim()} " +
          "${markerFields(settings)} " +
          "outputMode=$OUTPUT_MODE sampledCameraTexture=false " +
          "privateShaderStack=false customProjectionStack=false"

  fun inactiveCompleteMarker(settings: SpatialVideoProjectionSettings): String =
      "channel=$CHANNEL status=complete videoOnlySpatialProjection=true " +
          "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
          "nativeStartRequested=false cameraRuntimeStarted=false " +
          "error=video-path-missing " +
          markerFields(settings) + " runtimeCrash=false"

  fun nativeReceiptUnavailableCompleteMarker(error: String): String =
      "channel=$CHANNEL status=complete videoOnlySpatialProjection=true " +
          "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
          "nativeStartRequested=false cameraRuntimeStarted=false " +
          "error=${activityMarkerToken(error)} runtimeCrash=false"

  fun sdkSwapchainCreateFailedCompleteMarker(error: String, message: String): String =
      "channel=$CHANNEL status=complete videoOnlySpatialProjection=true " +
          "sdkSwapchainCreated=false surfaceValid=false sceneQuadLayerCreated=false " +
          "nativeStartRequested=false cameraRuntimeStarted=false " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun getSurfaceFailedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      error: String,
      message: String,
  ): String =
      "channel=$CHANNEL status=get-surface-failed " +
          "videoOnlySpatialProjection=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun sdkSwapchainCreatedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      surfaceValid: Boolean,
      widthPx: Int,
      heightPx: Int,
      stereoMarkerFields: String,
      settings: SpatialVideoProjectionSettings,
  ): String =
      "channel=$CHANNEL status=sdk-swapchain-created videoOnlySpatialProjection=true " +
          "sdkSwapchainCreated=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "surfaceValid=$surfaceValid widthPx=$widthPx " +
          "heightPx=$heightPx " +
          "carrier=$CARRIER cameraRuntimeStarted=false " +
          "${stereoMarkerFields.trim()} " +
          markerFields(settings)

  fun completeMarker(
      sdkSwapchainCreated: Boolean,
      surfaceValid: Boolean,
      sceneQuadLayerCreated: Boolean,
      nativeStartRequested: Boolean,
      cleanupStatus: String? = null,
      error: String? = null,
      message: String? = null,
  ): String =
      buildString {
        append("channel=$CHANNEL status=complete videoOnlySpatialProjection=true ")
        append("sdkSwapchainCreated=$sdkSwapchainCreated surfaceValid=$surfaceValid ")
        append("sceneQuadLayerCreated=$sceneQuadLayerCreated ")
        append("nativeStartRequested=$nativeStartRequested ")
        append("cameraRuntimeStarted=false ")
        if (cleanupStatus != null) {
          append("cleanupStatus=$cleanupStatus ")
        }
        if (error != null) {
          append("error=${activityMarkerToken(error)} ")
        }
        if (message != null) {
          append("message=${activityMarkerToken(message)} ")
        }
        append("runtimeCrash=false")
      }

  fun nativeStartRequestedMarker(
      surfaceValid: Boolean,
      startMask: Long,
      settings: SpatialVideoProjectionSettings,
  ): String =
      "channel=$CHANNEL status=native-start-requested videoOnlySpatialProjection=true " +
          "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
          "nativeStartRequested=true startMask=$startMask requestedFrames=0 frameLimit=none " +
          "carrier=$CARRIER cameraRuntimeStarted=false " +
          "sampledCameraTexture=false outputMode=$OUTPUT_MODE " +
          markerFields(settings) + " runtimeCrash=false"

  fun nativeConfigureSkippedMarker(
      reason: String,
      settings: SpatialVideoProjectionSettings,
  ): String =
      "channel=$CHANNEL status=native-configure-skipped " +
          "reason=${activityMarkerToken(reason)} nativeReceiptLibraryLoaded=false " +
          markerFields(settings) + " runtimeCrash=false"

  fun nativeConfigureFailedMarker(
      reason: String,
      error: String,
      message: String,
      settings: SpatialVideoProjectionSettings,
  ): String =
      "channel=$CHANNEL status=native-configure-failed " +
          "reason=${activityMarkerToken(reason)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} " +
          markerFields(settings) + " runtimeCrash=false"

  fun nativeConfiguredMarker(
      reason: String,
      configureMask: Long,
      settings: SpatialVideoProjectionSettings,
  ): String =
      "channel=$CHANNEL status=native-configured " +
          "reason=${activityMarkerToken(reason)} configureMask=$configureMask " +
          markerFields(settings) + " runtimeCrash=false"

  fun startRequestedMarker(reason: String, settings: SpatialVideoProjectionSettings): String =
      "channel=$CHANNEL status=start-requested " +
          "reason=${activityMarkerToken(reason)} " +
          markerFields(settings) + " runtimeCrash=false"

  fun stoppedMarker(reason: String, settings: SpatialVideoProjectionSettings): String =
      "channel=$CHANNEL status=stopped " +
          "reason=${activityMarkerToken(reason)} videoProjectionStopRequested=true " +
          markerFields(settings) + " runtimeCrash=false"
}
