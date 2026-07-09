package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal const val EXTERNAL_SWAPCHAIN_PROBE_PROPERTY =
    "debug.rustyquest.spatial.external_swapchain_probe"
internal const val EXTERNAL_SWAPCHAIN_PROBE_CYCLES_PROPERTY =
    "debug.rustyquest.spatial.external_swapchain_probe.cycles"
internal const val EXTERNAL_SWAPCHAIN_PROBE_CYCLE_MS_PROPERTY =
    "debug.rustyquest.spatial.external_swapchain_probe.cycle_ms"
internal const val EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX = 256
internal const val EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX = 256
internal const val EXTERNAL_SWAPCHAIN_PROBE_WIDTH_METERS = 0.35f
internal const val EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_METERS = 0.35f
internal const val EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS = 0.85f
internal const val EXTERNAL_SWAPCHAIN_PROBE_Z_INDEX = 18
internal const val EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLES = 1
internal const val EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLES = 10
internal const val EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLE_MS = 60_000L
internal const val EXTERNAL_SWAPCHAIN_PROBE_MIN_CYCLE_MS = 1_000L
internal const val EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLE_MS = 60_000L
internal const val EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS = 750L

internal const val SDK_QUAD_SURFACE_PROBE_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_surface_probe"
internal const val SDK_QUAD_SURFACE_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_surface_probe.hold_ms"
internal const val SDK_QUAD_SURFACE_PROBE_WIDTH_PX = 512
internal const val SDK_QUAD_SURFACE_PROBE_HEIGHT_PX = 512
internal const val SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS = 8
internal const val SDK_QUAD_SURFACE_PROBE_WIDTH_METERS = 0.55f
internal const val SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS = 0.55f
internal const val SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS = 0.85f
internal const val SDK_QUAD_SURFACE_PROBE_Z_INDEX = 22
internal const val SDK_QUAD_SURFACE_PROBE_DEFAULT_HOLD_MS = 30_000L
internal const val SDK_QUAD_SURFACE_PROBE_MIN_HOLD_MS = 1_000L
internal const val SDK_QUAD_SURFACE_PROBE_MAX_HOLD_MS = 120_000L

internal const val SDK_QUAD_VULKAN_PROBE_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe"
internal const val SDK_QUAD_VULKAN_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe.hold_ms"
internal const val SDK_QUAD_VULKAN_PROBE_FRAME_COUNT_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe.frame_count"
internal const val SDK_QUAD_VULKAN_PROBE_DEFAULT_HOLD_MS = 8_000L
internal const val SDK_QUAD_VULKAN_PROBE_MIN_HOLD_MS = 1_000L
internal const val SDK_QUAD_VULKAN_PROBE_MAX_HOLD_MS = 120_000L
internal const val SDK_QUAD_VULKAN_PROBE_DEFAULT_FRAME_COUNT = 240
internal const val SDK_QUAD_VULKAN_PROBE_MAX_FRAME_COUNT = 1_800

internal const val SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe"
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe.hold_ms"
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX = 2048
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX = 1024
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX = 1024
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS = 1.15f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS = 1.15f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW = 24
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH = 34
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH = 0.88f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW = 0.45f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_CHANGE_MS = 1_500L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_CHANGE_MS = 3_000L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_RESTORE_MS = 5_500L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_DEFAULT_HOLD_MS = 30_000L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_MIN_HOLD_MS = 3_000L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_MAX_HOLD_MS = 120_000L

internal const val PANEL_SURFACE_MATRIX_PROBE_PROPERTY =
    "debug.rustyquest.spatial.panel_surface_matrix_probe"
internal const val PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX = 512
internal const val PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX = 512
internal const val PANEL_SURFACE_MATRIX_PROBE_FRAME_COUNT = 90
internal const val PANEL_SURFACE_MATRIX_PROBE_VARIANT_HOLD_MS = 2_500L
internal const val PANEL_SURFACE_MATRIX_PROBE_INTER_VARIANT_MS = 500L

internal const val CAMERA_HWB_PROBE_PROPERTY = "debug.rustyquest.spatial.camera_hwb_probe"
internal const val CAMERA_HWB_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_probe.hold_ms"
internal const val CAMERA_HWB_PROBE_FRAME_COUNT_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_probe.frame_count"
internal const val CAMERA_HWB_PROBE_READER_MAX_IMAGES_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_probe.reader_max_images"
internal const val CAMERA_HWB_PROBE_WIDTH_PX = 1024
internal const val CAMERA_HWB_PROBE_HEIGHT_PX = 512
internal const val CAMERA_HWB_PROBE_WIDTH_METERS = 1.0f
internal const val CAMERA_HWB_PROBE_HEIGHT_METERS = 0.5f
internal const val CAMERA_HWB_PROBE_Z_INDEX = 36
internal const val CAMERA_HWB_PROBE_DEFAULT_HOLD_MS = 10_000L
internal const val CAMERA_HWB_PROBE_MIN_HOLD_MS = 2_000L
internal const val CAMERA_HWB_PROBE_MAX_HOLD_MS = 120_000L
internal const val CAMERA_HWB_PROBE_DEFAULT_FRAME_COUNT = 240
internal const val CAMERA_HWB_PROBE_MAX_FRAME_COUNT = 1_800
internal const val CAMERA_HWB_PROBE_DEFAULT_READER_MAX_IMAGES = 4
internal const val CAMERA_HWB_PROBE_MIN_READER_MAX_IMAGES = 3
internal const val CAMERA_HWB_PROBE_MAX_READER_MAX_IMAGES = 12

internal object SpatialDiagnosticProbeRouteModule {
  const val MODULE_ID = "spatial-diagnostic-probe-route-policy"

  fun externalSwapchainProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(EXTERNAL_SWAPCHAIN_PROBE_PROPERTY) == true

  fun externalSwapchainProbeCycles(): Int =
      activityReadIntSystemProperty(
          EXTERNAL_SWAPCHAIN_PROBE_CYCLES_PROPERTY,
          EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLES,
          1,
          EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLES,
      )

  fun externalSwapchainProbeCycleMs(): Long =
      activityReadLongSystemProperty(
          EXTERNAL_SWAPCHAIN_PROBE_CYCLE_MS_PROPERTY,
          EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLE_MS,
          EXTERNAL_SWAPCHAIN_PROBE_MIN_CYCLE_MS,
          EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLE_MS,
      )

  fun sdkQuadSurfaceProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(SDK_QUAD_SURFACE_PROBE_PROPERTY) == true

  fun sdkQuadSurfaceProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          SDK_QUAD_SURFACE_PROBE_HOLD_MS_PROPERTY,
          SDK_QUAD_SURFACE_PROBE_DEFAULT_HOLD_MS,
          SDK_QUAD_SURFACE_PROBE_MIN_HOLD_MS,
          SDK_QUAD_SURFACE_PROBE_MAX_HOLD_MS,
      )

  fun sdkQuadVulkanProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(SDK_QUAD_VULKAN_PROBE_PROPERTY) == true

  fun sdkQuadVulkanProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          SDK_QUAD_VULKAN_PROBE_HOLD_MS_PROPERTY,
          SDK_QUAD_VULKAN_PROBE_DEFAULT_HOLD_MS,
          SDK_QUAD_VULKAN_PROBE_MIN_HOLD_MS,
          SDK_QUAD_VULKAN_PROBE_MAX_HOLD_MS,
      )

  fun sdkQuadVulkanProbeFrameCount(): Int =
      activityReadIntSystemProperty(
          SDK_QUAD_VULKAN_PROBE_FRAME_COUNT_PROPERTY,
          SDK_QUAD_VULKAN_PROBE_DEFAULT_FRAME_COUNT,
          1,
          SDK_QUAD_VULKAN_PROBE_MAX_FRAME_COUNT,
      )

  fun sdkQuadStereoAlphaProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY) == true

  fun sdkQuadStereoAlphaProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          SDK_QUAD_STEREO_ALPHA_PROBE_HOLD_MS_PROPERTY,
          SDK_QUAD_STEREO_ALPHA_PROBE_DEFAULT_HOLD_MS,
          SDK_QUAD_STEREO_ALPHA_PROBE_MIN_HOLD_MS,
          SDK_QUAD_STEREO_ALPHA_PROBE_MAX_HOLD_MS,
      )

  fun panelSurfaceMatrixProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(PANEL_SURFACE_MATRIX_PROBE_PROPERTY) == true

  fun cameraHwbProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(CAMERA_HWB_PROBE_PROPERTY) == true

  fun cameraHwbProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          CAMERA_HWB_PROBE_HOLD_MS_PROPERTY,
          CAMERA_HWB_PROBE_DEFAULT_HOLD_MS,
          CAMERA_HWB_PROBE_MIN_HOLD_MS,
          CAMERA_HWB_PROBE_MAX_HOLD_MS,
      )

  fun cameraHwbProbeFrameCount(): Int =
      activityReadIntSystemProperty(
          CAMERA_HWB_PROBE_FRAME_COUNT_PROPERTY,
          CAMERA_HWB_PROBE_DEFAULT_FRAME_COUNT,
          1,
          CAMERA_HWB_PROBE_MAX_FRAME_COUNT,
      )

  fun cameraHwbProbeReaderMaxImages(): Int =
      activityReadIntSystemProperty(
          CAMERA_HWB_PROBE_READER_MAX_IMAGES_PROPERTY,
          CAMERA_HWB_PROBE_DEFAULT_READER_MAX_IMAGES,
          CAMERA_HWB_PROBE_MIN_READER_MAX_IMAGES,
          CAMERA_HWB_PROBE_MAX_READER_MAX_IMAGES,
      )

  fun explicitOptInMarkerFields(propertyName: String): String =
      "spatialFeatureExplicitOptIn=true " +
          "spatialFeatureOptInRoute=android-system-property " +
          "featureOptInProperty=$propertyName"

  fun sdkQuadSurfaceProbeStartMarker(reason: String, holdMs: Long): String =
      "channel=sdk-owned-quad-surface-probe status=start sdkQuadSurfaceProbe=true " +
          "reason=${activityMarkerToken(reason)} debugProperty=$SDK_QUAD_SURFACE_PROBE_PROPERTY " +
          "widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX " +
          "holdMs=$holdMs producer=android-canvas nativeVulkanProducer=false " +
          "videoSurfacePanelRegistration=false externalSwapchain=false privateShaderStack=false " +
          explicitOptInMarkerFields(SDK_QUAD_SURFACE_PROBE_PROPERTY)

  fun sdkQuadSurfaceProbeCompleteMarker(
      sdkSwapchainCreated: Boolean,
      surfaceValid: Boolean,
      canvasDrawn: Boolean,
      sceneQuadLayerCreated: Boolean,
      manualSceneQuadLayerViable: Boolean,
      cleanupStatus: String? = null,
      plainEntitySceneObjectLayerCreated: Boolean? = null,
      anchorMode: String? = null,
      nativeVulkanProducer: Boolean? = null,
      visiblePatternConfirmed: Boolean? = null,
      humanVisiblePatternCheckRequired: Boolean? = null,
      error: String? = null,
      message: String? = null,
  ): String =
      buildString {
        append("channel=sdk-owned-quad-surface-probe status=complete sdkQuadSurfaceProbe=true ")
        append("sdkSwapchainCreated=$sdkSwapchainCreated surfaceValid=$surfaceValid ")
        append("canvasDrawn=$canvasDrawn ")
        append("sceneQuadLayerCreated=$sceneQuadLayerCreated ")
        append("manualSceneQuadLayerViable=$manualSceneQuadLayerViable ")
        if (cleanupStatus != null) {
          append("cleanupStatus=$cleanupStatus ")
        }
        if (plainEntitySceneObjectLayerCreated != null) {
          append("plainEntitySceneObjectLayerCreated=$plainEntitySceneObjectLayerCreated ")
        }
        if (anchorMode != null) {
          append("anchorMode=$anchorMode ")
        }
        if (nativeVulkanProducer != null) {
          append("nativeVulkanProducer=$nativeVulkanProducer ")
        }
        if (visiblePatternConfirmed != null) {
          append("visiblePatternConfirmed=$visiblePatternConfirmed ")
        }
        if (humanVisiblePatternCheckRequired != null) {
          append("humanVisiblePatternCheckRequired=$humanVisiblePatternCheckRequired ")
        }
        if (error != null) {
          append("error=${activityMarkerToken(error)} ")
        }
        if (message != null) {
          append("message=${activityMarkerToken(message)} ")
        }
        append("runtimeCrash=false")
      }

  fun sdkQuadSurfaceProbeGetSurfaceFailedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      error: String,
      message: String,
  ): String =
      "channel=sdk-owned-quad-surface-probe status=get-surface-failed " +
          "sdkQuadSurfaceProbe=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun sdkQuadSurfaceProbeSdkSwapchainCreatedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      surfaceValid: Boolean,
  ): String =
      "channel=sdk-owned-quad-surface-probe status=sdk-swapchain-created " +
          "sdkQuadSurfaceProbe=true sdkSwapchainCreated=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "surfaceValid=$surfaceValid widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX " +
          "heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX"

  fun sdkQuadSurfaceProbeVisibleWindowMarker(
      surfaceValid: Boolean,
      canvasDrawn: Boolean,
      sceneQuadLayerCreated: Boolean,
      manualSceneQuadLayerViable: Boolean,
      plainEntitySceneObjectLayerCreated: Boolean,
      anchorMode: String,
      holdMs: Long,
  ): String =
      "channel=sdk-owned-quad-surface-probe status=visible-window sdkQuadSurfaceProbe=true " +
          "sdkSwapchainCreated=true surfaceValid=$surfaceValid canvasDrawn=$canvasDrawn " +
          "sceneQuadLayerCreated=$sceneQuadLayerCreated manualSceneQuadLayerViable=$manualSceneQuadLayerViable " +
          "plainEntitySceneObjectLayerCreated=$plainEntitySceneObjectLayerCreated anchorMode=$anchorMode " +
          "nativeVulkanProducer=false visiblePatternConfirmed=false " +
          "humanVisiblePatternCheckRequired=true holdMs=$holdMs runtimeCrash=false"

  fun sdkQuadVulkanProbeStartMarker(reason: String, holdMs: Long, frameCount: Int): String =
      "channel=sdk-owned-quad-vulkan-probe status=start sdkQuadVulkanProbe=true " +
          "reason=${activityMarkerToken(reason)} debugProperty=$SDK_QUAD_VULKAN_PROBE_PROPERTY " +
          "widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX " +
          "holdMs=$holdMs requestedFrames=$frameCount producer=native-vulkan-wsi " +
          "renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi " +
          "videoSurfacePanelRegistration=false externalSwapchain=false privateShaderStack=false " +
          explicitOptInMarkerFields(SDK_QUAD_VULKAN_PROBE_PROPERTY)

  fun sdkQuadVulkanProbeCompleteMarker(
      sdkSwapchainCreated: Boolean,
      surfaceValid: Boolean,
      sceneQuadLayerCreated: Boolean,
      nativeStartRequested: Boolean,
      nativeVulkanProducer: Boolean,
      firstFramePresented: String,
      manualSceneQuadLayerViable: Boolean,
      cleanupStatus: String? = null,
      error: String? = null,
      message: String? = null,
  ): String =
      buildString {
        append("channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true ")
        append("sdkSwapchainCreated=$sdkSwapchainCreated surfaceValid=$surfaceValid ")
        append("sceneQuadLayerCreated=$sceneQuadLayerCreated ")
        append("nativeStartRequested=$nativeStartRequested ")
        append("nativeVulkanProducer=$nativeVulkanProducer ")
        append("firstFramePresented=$firstFramePresented ")
        append("manualSceneQuadLayerViable=$manualSceneQuadLayerViable ")
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

  fun sdkQuadVulkanProbeHoldCompleteMarker(
      surfaceValid: Boolean,
      frameCount: Int,
      cleanupStatus: String,
  ): String =
      "channel=sdk-owned-quad-vulkan-probe status=complete sdkQuadVulkanProbe=true " +
          "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
          "manualSceneQuadLayerViable=true nativeStartRequested=true nativeVulkanProducer=true " +
          "firstFramePresented=see-native-logcat requestedFrames=$frameCount " +
          "cleanupStatus=$cleanupStatus runtimeCrash=false"

  fun sdkQuadVulkanProbeGetSurfaceFailedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      error: String,
      message: String,
  ): String =
      "channel=sdk-owned-quad-vulkan-probe status=get-surface-failed " +
          "sdkQuadVulkanProbe=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun sdkQuadVulkanProbeSdkSwapchainCreatedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      surfaceValid: Boolean,
  ): String =
      "channel=sdk-owned-quad-vulkan-probe status=sdk-swapchain-created " +
          "sdkQuadVulkanProbe=true sdkSwapchainCreated=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "surfaceValid=$surfaceValid widthPx=$SDK_QUAD_SURFACE_PROBE_WIDTH_PX " +
          "heightPx=$SDK_QUAD_SURFACE_PROBE_HEIGHT_PX"

  fun sdkQuadVulkanProbeLayerCreatedMarker(layerCreated: Boolean): String =
      "channel=sdk-owned-quad-vulkan-probe status=layer-created " +
          "sdkQuadVulkanProbe=true sceneQuadLayerCreated=$layerCreated " +
          "manualSceneQuadLayerViable=$layerCreated anchorMode=generated-single-sided-quad " +
          "stereoMode=None producer=native-vulkan-wsi"

  fun sdkQuadVulkanProbeNativeStartRequestedMarker(
      surfaceValid: Boolean,
      startMask: Long,
      frameCount: Int,
      holdMs: Long,
  ): String =
      "channel=sdk-owned-quad-vulkan-probe status=native-start-requested " +
          "sdkQuadVulkanProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
          "sceneQuadLayerCreated=true manualSceneQuadLayerViable=true nativeStartRequested=true " +
          "nativeVulkanProducer=true startMask=$startMask requestedFrames=$frameCount " +
          "holdMs=$holdMs renderPolicy=sdk-owned-scenequadlayer-android-surface-wsi"

  fun sdkQuadStereoAlphaProbeStartMarker(reason: String, holdMs: Long): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=start " +
          "sdkQuadStereoAlphaProbe=true reason=${activityMarkerToken(reason)} " +
          "debugProperty=$SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY " +
          "widthPx=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX " +
          "heightPx=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX " +
          "perEyeExtentPx=${SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX}x$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX " +
          "stereoMode=LeftRight producer=android-canvas nativeVulkanProducer=false " +
          "setClipPlanned=true alphaBlendPlanned=true colorScaleAlphaPlanned=true " +
          "zIndexChangePlanned=true holdMs=$holdMs " +
          explicitOptInMarkerFields(SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY)

  fun sdkQuadStereoAlphaProbeCompleteMarker(
      sdkSwapchainCreated: Boolean,
      surfaceValid: Boolean,
      canvasDrawn: Boolean,
      sceneQuadLayerCreated: Boolean,
      setClipApplied: Boolean,
      alphaBlendApplied: Boolean,
      zIndexChanged: Boolean,
      manualSceneQuadLayerViable: Boolean? = null,
      colorScaleAlphaApplied: Boolean? = null,
      cleanupStatus: String? = null,
      error: String? = null,
      message: String? = null,
      includeOperatorChecks: Boolean = false,
  ): String =
      buildString {
        append("channel=sdk-owned-quad-stereo-alpha-probe status=complete ")
        append("sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=$sdkSwapchainCreated ")
        append("surfaceValid=$surfaceValid canvasDrawn=$canvasDrawn ")
        append("sceneQuadLayerCreated=$sceneQuadLayerCreated ")
        if (manualSceneQuadLayerViable != null) {
          append("manualSceneQuadLayerViable=$manualSceneQuadLayerViable ")
        }
        append("stereoMode=LeftRight ")
        append("setClipApplied=$setClipApplied alphaBlendApplied=$alphaBlendApplied ")
        if (colorScaleAlphaApplied != null) {
          append("colorScaleAlphaApplied=$colorScaleAlphaApplied ")
        }
        append("zIndexChanged=$zIndexChanged ")
        if (cleanupStatus != null) {
          append("cleanupStatus=$cleanupStatus ")
        }
        if (error != null) {
          append("error=${activityMarkerToken(error)} ")
        }
        if (message != null) {
          append("message=${activityMarkerToken(message)} ")
        }
        if (includeOperatorChecks) {
          append("eyeLeakageCheck=operator-visible-required ")
          append("uvOrientationCheck=operator-visible-required ")
          append("alphaConventionCheck=operator-visible-required ")
        }
        append("runtimeCrash=false")
      }

  fun sdkQuadStereoAlphaProbeGetSurfaceFailedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      error: String,
      message: String,
  ): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=get-surface-failed " +
          "sdkQuadStereoAlphaProbe=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeSdkSwapchainCreatedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      surfaceValid: Boolean,
  ): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=sdk-swapchain-created " +
          "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "surfaceValid=$surfaceValid widthPx=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX " +
          "heightPx=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX"

  fun sdkQuadStereoAlphaProbeVisibleWindowMarker(
      surfaceValid: Boolean,
      canvasDrawn: Boolean,
      sceneQuadLayerCreated: Boolean,
      manualSceneQuadLayerViable: Boolean,
      holdMs: Long,
  ): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=visible-window " +
          "sdkQuadStereoAlphaProbe=true sdkSwapchainCreated=true surfaceValid=$surfaceValid " +
          "canvasDrawn=$canvasDrawn sceneQuadLayerCreated=$sceneQuadLayerCreated " +
          "manualSceneQuadLayerViable=$manualSceneQuadLayerViable stereoMode=LeftRight " +
          "leftEyePattern=red-grid rightEyePattern=blue-grid " +
          "expectedUvOrientation=left-half-to-left-eye-right-half-to-right-eye " +
          "eyeLeakageCheck=operator-visible-required croppingCheck=operator-visible-required " +
          "alphaConventionCheck=operator-visible-required holdMs=$holdMs runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeZIndexUpdatedMarker(): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=z-index-updated " +
          "sdkQuadStereoAlphaProbe=true zIndexChanged=true " +
          "zIndex=$SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeZIndexUpdateFailedMarker(error: String, message: String): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=z-index-update-failed " +
          "sdkQuadStereoAlphaProbe=true zIndexChanged=false " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeAlphaUpdatedMarker(): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-updated " +
          "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=true " +
          "alpha=${activityMarkerFloat(SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW)} " +
          "alphaConvention=premultiplied-unknown-source-alpha-blend-factors runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeAlphaUpdateFailedMarker(error: String): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-update-failed " +
          "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=false " +
          "error=${activityMarkerToken(error)} runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeAlphaRestoredMarker(): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-restored " +
          "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=true " +
          "alpha=1.0000 runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeAlphaRestoreFailedMarker(error: String): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=alpha-restore-failed " +
          "sdkQuadStereoAlphaProbe=true colorScaleAlphaApplied=false " +
          "error=${activityMarkerToken(error)} runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeLayerCreatedMarker(
      canvasDrawn: Boolean,
      sceneObjectHandle: Long,
      layerPositionM: String,
      layerQuaternion: String,
  ): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=layer-created " +
          "sdkQuadStereoAlphaProbe=true sceneQuadLayerCreated=true canvasDrawn=$canvasDrawn " +
          "anchorMode=generated-single-sided-quad sceneObjectHandle=$sceneObjectHandle " +
          "widthMeters=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS " +
          "heightMeters=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS " +
          "zIndex=$SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW stereoMode=LeftRight " +
          "setClipApplied=true clipUv=0.04;0.04;0.96;0.96 " +
          "alphaBlendApplied=true sourceFactorColor=SOURCE_ALPHA " +
          "destinationFactorColor=ONE_MINUS_SOURCE_ALPHA sourceFactorAlpha=ONE " +
          "destinationFactorAlpha=ONE_MINUS_SOURCE_ALPHA " +
          "colorScaleAlphaApplied=true alpha=${activityMarkerFloat(SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH)} " +
          "poseSource=Scene.getViewerPose layerPositionM=$layerPositionM " +
          "layerQuaternion=$layerQuaternion"

  fun sdkQuadStereoAlphaProbeLayerCreateFailedMarker(
      canvasDrawn: Boolean,
      error: String,
      message: String,
  ): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=layer-create-failed " +
          "sdkQuadStereoAlphaProbe=true sceneQuadLayerCreated=false canvasDrawn=$canvasDrawn " +
          "stereoMode=LeftRight error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun sdkQuadStereoAlphaProbeCanvasDrawSkippedMarker(): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=canvas-draw-skipped " +
          "sdkQuadStereoAlphaProbe=true reason=surface-invalid canvasDrawn=false"

  fun sdkQuadStereoAlphaProbeCanvasDrawCompleteMarker(drawn: Boolean): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=canvas-draw-complete " +
          "sdkQuadStereoAlphaProbe=true canvasDrawn=$drawn widthPx=$SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX " +
          "heightPx=$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX " +
          "leftEyePattern=red-grid rightEyePattern=blue-grid " +
          "perEyeExtentPx=${SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX}x$SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX"

  fun sdkQuadStereoAlphaProbeCanvasDrawFailedMarker(error: String, message: String): String =
      "channel=sdk-owned-quad-stereo-alpha-probe status=canvas-draw-failed " +
          "sdkQuadStereoAlphaProbe=true canvasDrawn=false " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun cameraHwbProbeStartMarker(
      reason: String,
      frameCount: Int,
      holdMs: Long,
      readerMaxImages: Int,
      publicMultiStackMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=start cameraHwbProbe=true " +
          "reason=${activityMarkerToken(reason)} debugProperty=$CAMERA_HWB_PROBE_PROPERTY " +
          "widthPx=$CAMERA_HWB_PROBE_WIDTH_PX heightPx=$CAMERA_HWB_PROBE_HEIGHT_PX " +
          "requestedFrames=$frameCount holdMs=$holdMs readerMaxImages=$readerMaxImages " +
          "cameraPreference=50-then-51 carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
          "outputMode=luma-checker ${publicMultiStackMarkerFields.trim()} " +
          "privateShaderStack=false customProjectionStack=false " +
          explicitOptInMarkerFields(CAMERA_HWB_PROBE_PROPERTY)

  fun cameraHwbProbeCompleteMarker(
      sdkSwapchainCreated: Boolean,
      surfaceValid: Boolean,
      sceneQuadLayerCreated: Boolean,
      nativeStartRequested: Boolean,
      sampledCameraTexture: String,
      cleanupStatus: String? = null,
      error: String? = null,
      message: String? = null,
      firstCameraFramePresented: String? = null,
  ): String =
      buildString {
        append("channel=camera-hwb-spatial-probe status=complete cameraHwbProbe=true ")
        append("sdkSwapchainCreated=$sdkSwapchainCreated surfaceValid=$surfaceValid ")
        append("sceneQuadLayerCreated=$sceneQuadLayerCreated ")
        append("nativeStartRequested=$nativeStartRequested ")
        if (firstCameraFramePresented != null) {
          append("firstCameraFramePresented=$firstCameraFramePresented ")
        }
        append("sampledCameraTexture=$sampledCameraTexture ")
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

  fun cameraHwbProbeGetSurfaceFailedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=get-surface-failed " +
          "cameraHwbProbe=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun cameraHwbProbeSdkSwapchainCreatedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      surfaceValid: Boolean,
  ): String =
      "channel=camera-hwb-spatial-probe status=sdk-swapchain-created cameraHwbProbe=true " +
          "sdkSwapchainCreated=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "surfaceValid=$surfaceValid widthPx=$CAMERA_HWB_PROBE_WIDTH_PX " +
          "heightPx=$CAMERA_HWB_PROBE_HEIGHT_PX"

  fun cameraHwbProbeNativeStartRequestedMarker(
      surfaceValid: Boolean,
      startMask: Long,
      frameCount: Int,
      readerMaxImages: Int,
      holdMs: Long,
      publicMultiStackMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=native-start-requested cameraHwbProbe=true " +
          "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
          "nativeStartRequested=true startMask=$startMask requestedFrames=$frameCount " +
          "readerMaxImages=$readerMaxImages holdMs=$holdMs " +
          "carrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
          "${publicMultiStackMarkerFields.trim()} " +
          "privateShaderStack=false customProjectionStack=false"
}
