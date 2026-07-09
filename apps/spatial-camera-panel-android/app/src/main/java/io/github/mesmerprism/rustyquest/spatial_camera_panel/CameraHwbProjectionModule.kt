package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import java.util.Locale

internal const val CAMERA_HWB_PROJECTION_PROBE_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe"
internal const val CAMERA_HWB_PROJECTION_SYNTHETIC_VISUAL_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.synthetic_visual"
internal const val CAMERA_HWB_PROJECTION_READER_MAX_IMAGES_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.reader_max_images"
internal const val CAMERA_HWB_PROJECTION_WIDTH_PX = 2048
internal const val CAMERA_HWB_PROJECTION_HEIGHT_PX = 1024
internal const val CAMERA_HWB_PROJECTION_PER_EYE_WIDTH_PX = 1024
internal const val CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS = 2.0f
internal const val CAMERA_HWB_PROJECTION_ROOM_FOREGROUND_TARGET_DISTANCE_METERS = 0.25f
internal const val CAMERA_HWB_PROJECTION_PRIVATE_PANEL_INPUT_CLEARANCE_METERS = 0.03f
internal const val CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER = "2.00"
internal const val CAMERA_HWB_PROJECTION_TARGET_SCALE_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.projection.target.scale"
internal const val CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.projection.target.joystick.scale.rate_per_second"
internal const val CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.depth.layer_policy"
internal const val CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PER_SECOND = 0.30f
internal const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV = 0.046320f
internal const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV = -0.12f
internal const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV = 0.12f
internal const val CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_RANGE_MARKER =
    "-0.120000..0.120000"
internal const val CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_MARKER_INTERVAL_MS = 450L
internal const val CAMERA_HWB_PROJECTION_TARGET_FOV_TANGENTS = "-1.0;1.0;-1.0;1.0"
internal const val CAMERA_HWB_PROJECTION_SURFACE_OVERSCAN_MARKER = "1.0"
internal const val CAMERA_HWB_PROJECTION_BORDER_OPACITY_MARKER = "0.0"
internal const val CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT = 1.0f
internal const val CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE = 0.25f
internal const val CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE = 1.80f
internal const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_X = 0.171875f
internal const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_Y = 0.218750f
internal const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_WIDTH = 0.750000f
internal const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_HEIGHT = 0.656250f
internal const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_X = 0.078125f
internal const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_Y = 0.218750f
internal const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_WIDTH = 0.750000f
internal const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_HEIGHT = 0.671875f
internal const val CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_MARKER =
    "0.171875;0.218750;0.750000;0.656250"
internal const val CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_MARKER =
    "0.078125;0.218750;0.750000;0.671875"
internal const val CAMERA_HWB_PROJECTION_TARGET = "full-eye"
internal const val CAMERA_HWB_PROJECTION_PLACEMENT_MODE =
    "viewer-pose-projection-locked-quad"
internal const val CAMERA_HWB_PROJECTION_PLACEMENT_AUTHORITY =
    "spatial-sdk-viewer-pose-scene-tick"
internal const val CAMERA_HWB_PROJECTION_WALL_PLACEMENT_MODE =
    "virtual-room-wall-fixed-quad"
internal const val CAMERA_HWB_PROJECTION_WALL_PLACEMENT_AUTHORITY =
    "spatial-sdk-virtual-room-fixed-wall"
internal const val CAMERA_HWB_PROJECTION_WALL_CENTER_X_METERS = 0.0f
internal const val CAMERA_HWB_PROJECTION_WALL_CENTER_Y_METERS = 1.45f
internal const val CAMERA_HWB_PROJECTION_WALL_CENTER_Z_METERS = -2.40f
internal const val CAMERA_HWB_PROJECTION_WALL_WIDTH_METERS = 1.60f
internal const val CAMERA_HWB_PROJECTION_WALL_HEIGHT_METERS = 0.90f
internal const val CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER = "0.0;1.45;-2.40"
internal const val CAMERA_HWB_PROJECTION_WALL_SIZE_MARKER = "1.60;0.90"
internal const val CAMERA_HWB_PROJECTION_CARRIER_PROPERTY =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.carrier"
internal const val CAMERA_HWB_PROJECTION_CARRIER_EXTRA =
    "rusty.quest.spatial.camera_hwb_projection_probe.carrier"
internal const val CAMERA_HWB_PROJECTION_SCENE_QUAD_LAYER_Z_INDEX = 40
internal const val CAMERA_HWB_PROJECTION_PANEL_VIEWER_LOCKED_Z_INDEX = -20
internal const val CAMERA_HWB_PROJECTION_PANEL_WALL_Z_INDEX = -16
internal const val CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS = 650L
internal const val CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED = 0
internal const val CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES = 4
internal const val CAMERA_HWB_PROJECTION_MIN_READER_MAX_IMAGES = 3
internal const val CAMERA_HWB_PROJECTION_MAX_READER_MAX_IMAGES = 12
internal const val CAMERA_HWB_PROJECTION_MARKER_INTERVAL_MS = 900L

internal data class CameraHwbProjectionMarkerInput(
    val carrierMode: CameraHwbProjectionCarrierMode,
    val placementMode: CameraHwbProjectionPlacementMode,
    val carrierTransportToken: String,
    val startGateToken: String,
    val roomRenderOrderToken: String,
    val targetDistanceMeters: Float,
    val projectionWidthMeters: Float,
    val projectionHeightMeters: Float,
    val targetScale: Float,
    val stereoHorizontalOffsetUv: Float,
    val targetScaleJoystickRatePerSecond: Float,
    val legacyLauncherPanelSuppressed: Boolean,
    val targetDistanceSource: String,
    val virtualRoomForegroundDistanceActive: Boolean,
    val privatePanelInputClearanceActive: Boolean,
    val inputCarrierBehindPrivatePanel: Boolean,
    val privatePanelInputClearanceTargetDistanceMeters: Float,
    val targetCoordinateSpace: String,
    val targetProjectionSpace: String,
)

internal object CameraHwbProjectionModule {
  fun zIndexForPlacement(
      carrierMode: CameraHwbProjectionCarrierMode,
      placementMode: CameraHwbProjectionPlacementMode,
  ): Int =
      when (carrierMode) {
        CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject ->
            CAMERA_HWB_PROJECTION_SCENE_QUAD_LAYER_Z_INDEX
        CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject ->
            when (placementMode) {
              CameraHwbProjectionPlacementMode.ViewerLocked ->
                  CAMERA_HWB_PROJECTION_PANEL_VIEWER_LOCKED_Z_INDEX
              CameraHwbProjectionPlacementMode.VirtualRoomWall ->
                  CAMERA_HWB_PROJECTION_PANEL_WALL_Z_INDEX
            }
        CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh ->
            when (placementMode) {
              CameraHwbProjectionPlacementMode.ViewerLocked ->
                  CAMERA_HWB_PROJECTION_PANEL_VIEWER_LOCKED_Z_INDEX
              CameraHwbProjectionPlacementMode.VirtualRoomWall ->
                  CAMERA_HWB_PROJECTION_PANEL_WALL_Z_INDEX
            }
      }

  fun displayRoleForPlacement(placementMode: CameraHwbProjectionPlacementMode): String =
      when (placementMode) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> "full-fov-video-plus-custom-camera-stack"
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> "room-wall-video-plus-custom-camera-stack"
      }

  fun scenePanelCarrierEnabled(carrierMode: CameraHwbProjectionCarrierMode): Boolean =
      carrierMode == CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject ||
          carrierMode == CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh

  fun manualCustomMeshCarrierEnabled(carrierMode: CameraHwbProjectionCarrierMode): Boolean =
      carrierMode == CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh

  fun panelRegistrationId(carrierMode: CameraHwbProjectionCarrierMode): String =
      if (manualCustomMeshCarrierEnabled(carrierMode)) {
        "spatial_camera_projection_manual_custom_mesh_panel"
      } else {
        "spatial_camera_projection_surface_panel"
      }

  fun carrierToken(carrierMode: CameraHwbProjectionCarrierMode): String = carrierMode.markerToken

  fun carrierModeForToken(
      rawToken: String,
      virtualRoomEnabled: Boolean,
  ): CameraHwbProjectionCarrierMode =
      when (rawToken.trim().lowercase(Locale.US).replace("_", "-")) {
        "video-surface-panel-scene-object", "video-surface-panel", "panel-scene-object" ->
            CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject
        "manual-panel-scene-object-custom-mesh",
        "manual-panel-scene-object",
        "custom-mesh-panel",
        "manual-custom-mesh" -> CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh
        "scenequadlayer-createasandroid-vulkan-wsi",
        "scenequadlayer-room-object",
        "scenequadlayer",
        "scene-quad-layer",
        "room-object" -> CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject
        "" ->
            if (virtualRoomEnabled) {
              CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject
            } else {
              CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject
            }
        else -> CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject
      }

  fun roomRenderOrderToken(
      virtualRoomEnabled: Boolean,
      carrierMode: CameraHwbProjectionCarrierMode,
  ): String =
      when {
        !virtualRoomEnabled -> "no-room-scenequadlayer-baseline"
        carrierMode == CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject ->
            "projection-layer-over-virtual-room"
        carrierMode == CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh ->
            "manual-custom-mesh-panel-over-virtual-room"
        else -> "video-surface-panel-over-virtual-room"
      }

  fun startGateToken(virtualRoomEnabled: Boolean): String =
      if (virtualRoomEnabled) "virtual-room-loaded" else "scene-ready"

  fun carrierTransportToken(hasIntentExtra: Boolean): String =
      if (hasIntentExtra) "intent-extra" else "android-property-or-default"

  fun panelHittableToken(carrierMode: CameraHwbProjectionCarrierMode): String =
      when (carrierMode) {
        CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject -> "NoCollision"
        CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh ->
            "none-manual-custom-mesh-noninteractive"
        CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject -> "not-applicable-scenequadlayer"
      }

  fun virtualRoomForegroundDistanceActive(
      placementMode: CameraHwbProjectionPlacementMode,
      virtualRoomEnabled: Boolean,
      scenePanelCarrierEnabled: Boolean,
  ): Boolean =
      placementMode == CameraHwbProjectionPlacementMode.ViewerLocked &&
          virtualRoomEnabled &&
          scenePanelCarrierEnabled

  fun viewerLockedProjectionPlane(
      viewerPosition: Vector3?,
      viewerForward: Vector3?,
      viewerUp: Vector3?,
      eyeOffsets: Pair<Vector3, Vector3>?,
      targetDistanceMeters: Float,
      projectionWidthMeters: Float,
      projectionHeightMeters: Float,
  ): CameraHwbProjectionPlane {
    val fallbackViewerPosition = Vector3(0.0f, 1.20f, -2.0f)
    val fallbackForward = Vector3(0.0f, 0.0f, -1.0f)
    val fallbackUp = Vector3(0.0f, 1.0f, 0.0f)
    val fallbackRight = Vector3(1.0f, 0.0f, 0.0f)
    val resolvedViewerPosition = viewerPosition ?: fallbackViewerPosition
    val forward = viewerForward?.activityNormalizedOr(fallbackForward) ?: fallbackForward
    val up = viewerUp?.activityNormalizedOr(fallbackUp) ?: fallbackUp
    val right = activityCross(forward, up).activityNormalizedOr(fallbackRight)
    val center = resolvedViewerPosition + forward * targetDistanceMeters
    return CameraHwbProjectionPlane(
        viewerPosition = resolvedViewerPosition,
        forward = forward,
        up = up,
        right = right,
        center = center,
        pose = Pose(center, Quaternion.fromDirection(forward, up)),
        placementMode = CameraHwbProjectionPlacementMode.ViewerLocked,
        targetDistanceMeters = targetDistanceMeters,
        projectionWidthMeters = projectionWidthMeters,
        projectionHeightMeters = projectionHeightMeters,
        leftEyeOffset = eyeOffsets?.first ?: Vector3(0.0f),
        rightEyeOffset = eyeOffsets?.second ?: Vector3(0.0f),
    )
  }

  fun virtualWallProjectionPlane(
      viewerPosition: Vector3?,
      eyeOffsets: Pair<Vector3, Vector3>?,
  ): CameraHwbProjectionPlane {
    val resolvedViewerPosition = viewerPosition ?: Vector3(0.0f, 1.20f, 0.0f)
    val forward = Vector3(0.0f, 0.0f, 1.0f)
    val up = Vector3(0.0f, 1.0f, 0.0f)
    val right = Vector3(1.0f, 0.0f, 0.0f)
    val center =
        Vector3(
            CAMERA_HWB_PROJECTION_WALL_CENTER_X_METERS,
            CAMERA_HWB_PROJECTION_WALL_CENTER_Y_METERS,
            CAMERA_HWB_PROJECTION_WALL_CENTER_Z_METERS,
        )
    return CameraHwbProjectionPlane(
        viewerPosition = resolvedViewerPosition,
        forward = forward,
        up = up,
        right = right,
        center = center,
        pose = Pose(center, Quaternion.fromDirection(forward, up)),
        placementMode = CameraHwbProjectionPlacementMode.VirtualRoomWall,
        targetDistanceMeters =
            activityVectorLength(activityVectorSubtract(center, resolvedViewerPosition)),
        projectionWidthMeters = CAMERA_HWB_PROJECTION_WALL_WIDTH_METERS,
        projectionHeightMeters = CAMERA_HWB_PROJECTION_WALL_HEIGHT_METERS,
        leftEyeOffset = eyeOffsets?.first ?: Vector3(0.0f),
        rightEyeOffset = eyeOffsets?.second ?: Vector3(0.0f),
    )
  }

  fun poseSourceToken(plane: CameraHwbProjectionPlane): String =
      when (plane.placementMode) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> "Scene.getViewerPose"
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> "virtual-room-wall-fixed-pose"
      }

  fun effectiveTargetRect(
      baseX: Float,
      baseY: Float,
      baseWidth: Float,
      baseHeight: Float,
      offsetX: Float,
      targetScale: Float,
  ): FloatArray {
    val scale =
        targetScale.coerceIn(
            CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
            CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
        )
    val width = (baseWidth * scale).coerceIn(0.0001f, 1.0f)
    val height = (baseHeight * scale).coerceIn(0.0001f, 1.0f)
    val centerX = baseX + baseWidth * 0.5f + offsetX
    val centerY = baseY + baseHeight * 0.5f
    val x = (centerX - width * 0.5f).coerceIn(0.0f, 1.0f - width)
    val y = (centerY - height * 0.5f).coerceIn(0.0f, 1.0f - height)
    return floatArrayOf(x, y, width, height)
  }

  fun leftEffectiveTargetRect(
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): FloatArray =
      effectiveTargetRect(
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_X,
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_Y,
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_WIDTH,
          CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_HEIGHT,
          -stereoHorizontalOffsetUv,
          targetScale,
      )

  fun rightEffectiveTargetRect(
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): FloatArray =
      effectiveTargetRect(
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_X,
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_Y,
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_WIDTH,
          CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_HEIGHT,
          stereoHorizontalOffsetUv,
          targetScale,
      )

  fun rectMarker(rect: FloatArray): String =
      "${activityMarkerFloat6(rect[0])};${activityMarkerFloat6(rect[1])};" +
          "${activityMarkerFloat6(rect[2])};${activityMarkerFloat6(rect[3])}"

  fun leftEffectiveTargetRectMarker(
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): String = rectMarker(leftEffectiveTargetRect(targetScale, stereoHorizontalOffsetUv))

  fun rightEffectiveTargetRectMarker(
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): String = rectMarker(rightEffectiveTargetRect(targetScale, stereoHorizontalOffsetUv))

  fun leftPackedEffectiveTargetRectMarker(
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): String {
    val rect = leftEffectiveTargetRect(targetScale, stereoHorizontalOffsetUv)
    return rectMarker(floatArrayOf(0.5f * rect[0], rect[1], 0.5f * rect[2], rect[3]))
  }

  fun rightPackedEffectiveTargetRectMarker(
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): String {
    val rect = rightEffectiveTargetRect(targetScale, stereoHorizontalOffsetUv)
    return rectMarker(floatArrayOf(0.5f + 0.5f * rect[0], rect[1], 0.5f * rect[2], rect[3]))
  }

  fun stereoMarkerFields(): String =
      "stereoMode=LeftRight stereoSource=camera50-51 leftCameraId=50 rightCameraId=51 " +
          "monoDuplicated=false " +
          "perEyeExtent=${CAMERA_HWB_PROJECTION_PER_EYE_WIDTH_PX}x$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
          "packedExtent=${CAMERA_HWB_PROJECTION_WIDTH_PX}x$CAMERA_HWB_PROJECTION_HEIGHT_PX"

  fun rawProjectionStartDeferredForSceneMarker(reason: String): String =
      "channel=camera-hwb-spatial-probe status=start-deferred " +
          "reason=${activityMarkerToken(reason)} deferredUntil=scene-ready " +
          "sceneReady=false runtimeCrash=false"

  fun rawProjectionStartDeferredForVirtualRoomMarker(reason: String, sceneReady: Boolean): String =
      "channel=camera-hwb-spatial-probe status=start-deferred " +
          "reason=${activityMarkerToken(reason)} deferredUntil=virtual-room-loaded " +
          "sceneReady=$sceneReady spatialVirtualRoomLoaded=false runtimeCrash=false"

  fun rawProjectionStartMarker(
      reason: String,
      startGateToken: String,
      readerMaxImages: Int,
      carrier: String,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      videoProjectionMarkerFields: String,
      publicMultiStackMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=start rawCameraProjectionProbe=true " +
          "reason=${activityMarkerToken(reason)} debugProperty=$CAMERA_HWB_PROJECTION_PROBE_PROPERTY " +
          "projectionStartGate=$startGateToken " +
          "widthPx=$CAMERA_HWB_PROJECTION_WIDTH_PX heightPx=$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
          "requestedFrames=0 frameLimit=none holdMs=none readerMaxImages=$readerMaxImages " +
          "cameraPreference=50-then-51 carrier=$carrier " +
          "${projectionMarkerFields.trim()} " +
          "${stereoMarkerFields.trim()} " +
          "${videoProjectionMarkerFields.trim()} " +
          "${publicMultiStackMarkerFields.trim()} " +
          "outputMode=raw-color-target-rect sampledCameraTexture=true " +
          "sampledLeftCameraTexture=true sampledRightCameraTexture=true monoDuplicated=false " +
          "sampledCameraTextureSource=native-camera-hwb-pending-first-frame " +
          "privateShaderStack=false " +
          "customProjectionStack=false"

  fun rawProjectionGetSurfaceFailedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=get-surface-failed " +
          "rawCameraProjectionProbe=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun rawProjectionSdkSwapchainCreatedMarker(
      handle: Long,
      nativeHandle: Long,
      platformHandle: Long,
      surfaceValid: Boolean,
      carrier: String,
      stereoMarkerFields: String,
      publicMultiStackMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=sdk-swapchain-created rawCameraProjectionProbe=true " +
          "sdkSwapchainCreated=true handle=$handle " +
          "nativeHandle=$nativeHandle platformHandle=$platformHandle " +
          "surfaceValid=$surfaceValid widthPx=$CAMERA_HWB_PROJECTION_WIDTH_PX " +
          "heightPx=$CAMERA_HWB_PROJECTION_HEIGHT_PX " +
          "carrier=$carrier " +
          "renderSurfaceCarrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
          "${stereoMarkerFields.trim()} " +
          publicMultiStackMarkerFields.trim()

  fun rawProjectionSyntheticVisualPresentedMarker(
      surfaceValid: Boolean,
      canvasDrawn: Boolean,
      carrier: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=synthetic-visual-presented " +
          "rawCameraProjectionProbe=true syntheticCarrierVisualProbe=true " +
          "surfaceValid=$surfaceValid canvasDrawn=$canvasDrawn " +
          "sceneQuadLayerCreated=true scenePanelCarrier=false nativeStartRequested=false " +
          "cameraRuntimeStarted=false carrier=$carrier " +
          "renderSurfaceCarrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
          "syntheticVisualPattern=high-contrast-red-green-blue-yellow-checkerboard " +
          "sampledCameraTexture=false privateShaderStack=false customProjectionStack=false " +
          "runtimeCrash=false"

  fun rawProjectionNativeStartRequestedMarker(
      surfaceValid: Boolean,
      startMask: Long,
      readerMaxImages: Int,
      carrier: String,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      videoProjectionMarkerFields: String,
      publicMultiStackMarkerFields: String,
      nativePassthroughStartMask: Long,
      nativeEnvironmentDepthStartMask: Long,
  ): String =
      "channel=camera-hwb-spatial-probe status=native-start-requested rawCameraProjectionProbe=true " +
          "sdkSwapchainCreated=true surfaceValid=$surfaceValid sceneQuadLayerCreated=true " +
          "nativeStartRequested=true startMask=$startMask requestedFrames=0 frameLimit=none " +
          "readerMaxImages=$readerMaxImages carrier=$carrier " +
          "renderSurfaceCarrier=scenequadlayer-createAsAndroid-vulkan-wsi " +
          "${projectionMarkerFields.trim()} " +
          "${stereoMarkerFields.trim()} " +
          "${videoProjectionMarkerFields.trim()} " +
          "${publicMultiStackMarkerFields.trim()} " +
          "nativePassthroughStartMask=$nativePassthroughStartMask " +
          "nativeEnvironmentDepthStartMask=$nativeEnvironmentDepthStartMask " +
          "outputMode=raw-color-target-rect sampledCameraTexture=see-native-logcat " +
          "sampledLeftCameraTexture=see-native-logcat sampledRightCameraTexture=see-native-logcat " +
          "monoDuplicated=false " +
          "privateShaderStack=false customProjectionStack=false runtimeCrash=false"

  fun targetScaleJoystickAdjustedMarker(
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
      dtSeconds: Float,
      scaleRate: Float,
      panelVisible: Boolean,
      previousScale: Float,
      updatedScale: Float,
      targetDistanceMeters: Float,
      stereoHorizontalOffsetUv: Float,
  ): String =
      "channel=camera-hwb-spatial-probe status=target-scale-joystick-adjusted " +
          "rawCameraProjectionProbe=true inputSource=${activityMarkerToken(inputSource)} " +
          "controllerJoystickMapping=${activityMarkerToken(controllerJoystickMapping)} " +
          "${detail.trim()} dtSeconds=${activityMarkerFloat(dtSeconds)} " +
          "projectionTargetScaleRatePerSecond=${activityMarkerFloat(scaleRate)} " +
          "panelVisible=$panelVisible " +
          "cameraHwbProjectionScaleIgnoresPanelVisibility=true " +
          "previousProjectionTargetLiveScale=${activityMarkerFloat(previousScale)} " +
          "projectionTargetLiveScale=${activityMarkerFloat(updatedScale)} " +
          "projectionTargetTunedMaxScale=${activityMarkerFloat(updatedScale)} " +
          "projectionTargetMinScale=${activityMarkerFloat(CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE)} " +
          "projectionTargetMaxScale=${activityMarkerFloat(CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE)} " +
          "targetDistanceMeters=${activityMarkerFloat(targetDistanceMeters)} " +
          "projectionPlaneAngularCoveragePreserved=true " +
          "eyeSpaceTargetRectPreserved=true " +
          "leftEffectiveTargetScreenUvRect=${leftEffectiveTargetRectMarker(updatedScale, stereoHorizontalOffsetUv)} " +
          "rightEffectiveTargetScreenUvRect=${rightEffectiveTargetRectMarker(updatedScale, stereoHorizontalOffsetUv)} " +
          "leftPackedEffectiveTargetScreenUvRect=${leftPackedEffectiveTargetRectMarker(updatedScale, stereoHorizontalOffsetUv)} " +
          "rightPackedEffectiveTargetScreenUvRect=${rightPackedEffectiveTargetRectMarker(updatedScale, stereoHorizontalOffsetUv)}"

  fun targetScalePanelAdjustedMarker(
      source: String,
      previousScale: Float,
      updatedScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): String =
      "channel=camera-hwb-spatial-probe status=target-scale-panel-adjusted " +
          "rawCameraProjectionProbe=true inputSource=spatial-sdk-compose-panel " +
          "source=${activityMarkerToken(source)} previousProjectionTargetLiveScale=${activityMarkerFloat(previousScale)} " +
          "projectionTargetLiveScale=${activityMarkerFloat(updatedScale)} " +
          "projectionTargetMinScale=${activityMarkerFloat(CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE)} " +
          "projectionTargetMaxScale=${activityMarkerFloat(CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE)} " +
          "leftPackedEffectiveTargetScreenUvRect=${leftPackedEffectiveTargetRectMarker(updatedScale, stereoHorizontalOffsetUv)} " +
          "rightPackedEffectiveTargetScreenUvRect=${rightPackedEffectiveTargetRectMarker(updatedScale, stereoHorizontalOffsetUv)} " +
          "runtimeCrash=false"

  fun targetStereoHorizontalOffsetUpdateFailedMarker(
      reason: String,
      stereoOffsetUv: Float,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=target-stereo-horizontal-offset-update-failed " +
          "reason=${activityMarkerToken(reason)} rawCameraProjectionProbe=true " +
          "projectionTargetStereoHorizontalOffsetUv=${activityMarkerFloat6(stereoOffsetUv)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun targetStereoHorizontalOffsetNativeUpdatedMarker(
      reason: String,
      updateMask: Long,
      targetScale: Float,
      stereoOffsetUv: Float,
  ): String =
      "channel=camera-hwb-spatial-probe status=target-stereo-horizontal-offset-native-updated " +
          "reason=${activityMarkerToken(reason)} rawCameraProjectionProbe=true updateMask=$updateMask " +
          "projectionTargetStereoHorizontalOffsetUv=${activityMarkerFloat6(stereoOffsetUv)} " +
          "projectionTargetLeftOffsetUv=${activityMarkerFloat6(-stereoOffsetUv)},0.000000 " +
          "projectionTargetRightOffsetUv=${activityMarkerFloat6(stereoOffsetUv)},0.000000 " +
          "leftPackedEffectiveTargetScreenUvRect=${leftPackedEffectiveTargetRectMarker(targetScale, stereoOffsetUv)} " +
          "rightPackedEffectiveTargetScreenUvRect=${rightPackedEffectiveTargetRectMarker(targetScale, stereoOffsetUv)} " +
          "runtimeCrash=false"

  fun targetScaleUpdateFailedMarker(
      reason: String,
      targetScale: Float,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=target-scale-update-failed " +
          "reason=${activityMarkerToken(reason)} rawCameraProjectionProbe=true " +
          "projectionTargetLiveScale=${activityMarkerFloat(targetScale)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun targetScaleNativeUpdatedMarker(
      reason: String,
      updateMask: Long,
      targetScale: Float,
      stereoHorizontalOffsetUv: Float,
  ): String =
      "channel=camera-hwb-spatial-probe status=target-scale-native-updated " +
          "reason=${activityMarkerToken(reason)} rawCameraProjectionProbe=true updateMask=$updateMask " +
          "projectionTargetLiveScale=${activityMarkerFloat(targetScale)} " +
          "projectionTargetTunedMaxScale=${activityMarkerFloat(targetScale)} " +
          "leftPackedEffectiveTargetScreenUvRect=${leftPackedEffectiveTargetRectMarker(targetScale, stereoHorizontalOffsetUv)} " +
          "rightPackedEffectiveTargetScreenUvRect=${rightPackedEffectiveTargetRectMarker(targetScale, stereoHorizontalOffsetUv)} " +
          "runtimeCrash=false"

  fun projectionPlacementToggleDisabledMarker(
      inputSource: String,
      detail: String,
      placementMode: CameraHwbProjectionPlacementMode,
  ): String =
      "channel=camera-hwb-spatial-probe status=projection-placement-toggle-ignored " +
          "controllerInput=right-secondary-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} placementMode=${placementMode.markerToken} " +
          "cameraProjectionWallToggleInput=disabled-right-secondary-noop " +
          "cameraProjectionWallToggleEnabled=false " +
          "toggleGuard=disabled-no-room-distance-diagnostic " +
          "projectionStartsInFullFov=true runtimeCrash=false"

  fun projectionPlacementToggleNotArmedMarker(
      inputSource: String,
      detail: String,
      placementMode: CameraHwbProjectionPlacementMode,
  ): String =
      "channel=camera-hwb-spatial-probe status=projection-placement-toggle-ignored " +
          "controllerInput=right-secondary-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} placementMode=${placementMode.markerToken} " +
          "toggleGuard=wait-for-secondary-release-after-projection-start " +
          "projectionStartsInFullFov=true runtimeCrash=false"

  fun projectionPlacementToggleDebouncedMarker(
      inputSource: String,
      detail: String,
      placementMode: CameraHwbProjectionPlacementMode,
  ): String =
      "channel=camera-hwb-spatial-probe status=projection-placement-toggle-ignored " +
          "controllerInput=right-secondary-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} placementMode=${placementMode.markerToken} " +
          "toggleDebounceMs=$CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS " +
          "runtimeCrash=false"

  fun projectionPlacementToggledMarker(
      inputSource: String,
      detail: String,
      previousPlacementMode: CameraHwbProjectionPlacementMode,
      placementMode: CameraHwbProjectionPlacementMode,
      projectionEntityPresent: Boolean,
      carrierMode: CameraHwbProjectionCarrierMode,
      roomRenderOrderToken: String,
      layerOverrideReapplied: Boolean,
      layerOverrideUpdateMask: Long,
      layerOverride: Float,
  ): String =
      "channel=camera-hwb-spatial-probe status=projection-placement-toggled " +
          "controllerInput=right-secondary-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} " +
          "previousPlacementMode=${previousPlacementMode.markerToken} " +
          "placementMode=${placementMode.markerToken} " +
          "virtualRoomWallPlacementActive=${placementMode == CameraHwbProjectionPlacementMode.VirtualRoomWall} " +
          "projectionEntityPresent=$projectionEntityPresent " +
          "sceneQuadLayerRebuildStatus=not-rebuilt-existing-scene-anchor-updated " +
          "projectionCarrier=${carrierToken(carrierMode)} " +
          "projectionCarrierProperty=$CAMERA_HWB_PROJECTION_CARRIER_PROPERTY " +
          "projectionDisplaySurface=${displayRoleForPlacement(placementMode)} " +
          "projectionRoomRenderOrder=$roomRenderOrderToken " +
          "cameraVideoProjectionLayerZIndex=${zIndexForPlacement(carrierMode, placementMode)} " +
          "cameraProjectionWallToggleInput=disabled-right-secondary-noop " +
          "cameraProjectionWallToggleEnabled=false " +
          "virtualRoomWallCenterM=$CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER " +
          "virtualRoomWallSizeM=$CAMERA_HWB_PROJECTION_WALL_SIZE_MARKER " +
          "layerOverrideReappliedOnPlacementToggle=$layerOverrideReapplied " +
          "layerOverrideUpdateMask=$layerOverrideUpdateMask " +
          "publicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(layerOverride)} " +
          "layerOverrideAppliesToWallAndFullFov=true " +
          "cameraProjectionPlacementIndependentLayerControl=true " +
          "mrukPlacement=false passthroughRoomPlacement=false runtimeCrash=false"

  fun projectionPlacementToggleArmedMarker(inputSource: String): String =
      "channel=camera-hwb-spatial-probe status=projection-placement-toggle-armed " +
          "controllerInput=right-secondary-button inputSource=${activityMarkerToken(inputSource)} " +
          "projectionStartsInFullFov=true runtimeCrash=false"

  fun markerFields(input: CameraHwbProjectionMarkerInput): String {
    val placementAuthority =
        when (input.placementMode) {
          CameraHwbProjectionPlacementMode.ViewerLocked ->
              CAMERA_HWB_PROJECTION_PLACEMENT_AUTHORITY
          CameraHwbProjectionPlacementMode.VirtualRoomWall ->
              CAMERA_HWB_PROJECTION_WALL_PLACEMENT_AUTHORITY
        }
    val layerZIndex = zIndexForPlacement(input.carrierMode, input.placementMode)
    val displayRole = displayRoleForPlacement(input.placementMode)
    val projectionPanelHittable = panelHittableToken(input.carrierMode)
    val projectionAnchorHittable =
        when (input.carrierMode) {
          CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject -> "none-first-room-diagnostic"
          CameraHwbProjectionCarrierMode.VideoSurfacePanelSceneObject ->
              "not-applicable-panel-carrier"
          CameraHwbProjectionCarrierMode.ManualPanelSceneObjectCustomMesh ->
              "not-applicable-manual-custom-mesh-panel"
        }
    return "placementMode=${input.placementMode.markerToken} " +
        "placementAuthority=$placementAuthority " +
        "projectionCarrier=${carrierToken(input.carrierMode)} " +
        "projectionCarrierProperty=$CAMERA_HWB_PROJECTION_CARRIER_PROPERTY " +
        "projectionCarrierIntentExtra=$CAMERA_HWB_PROJECTION_CARRIER_EXTRA " +
        "projectionCarrierTransport=${input.carrierTransportToken} " +
        "projectionCarrierRoomObject=${input.carrierMode == CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject} " +
        "projectionStartGate=${input.startGateToken} " +
        "projectionDisplaySurface=$displayRole " +
        "projectionDisplaySurfaceContainsVideo=true " +
        "projectionDisplaySurfaceContainsCustomCameraProjection=true " +
        "projectionContentMappingMode=target-local-raster " +
        "targetClipPolicy=clip-to-visible-eye " +
        "projectionRoomRenderOrder=${input.roomRenderOrderToken} " +
        "projectionPanelInputPassThrough=true " +
        "projectionPanelHittable=$projectionPanelHittable " +
        "projectionAnchorHittable=$projectionAnchorHittable " +
        "cameraVideoProjectionLayerZIndex=$layerZIndex " +
        "legacyLauncherPanelSuppressed=${input.legacyLauncherPanelSuppressed} " +
        "viewerLockedPlacementMode=$CAMERA_HWB_PROJECTION_PLACEMENT_MODE " +
        "virtualRoomWallPlacementMode=$CAMERA_HWB_PROJECTION_WALL_PLACEMENT_MODE " +
        "virtualRoomWallPlacementActive=${input.placementMode == CameraHwbProjectionPlacementMode.VirtualRoomWall} " +
        "cameraProjectionWallToggleInput=disabled-right-secondary-noop " +
        "cameraProjectionWallToggleEnabled=false " +
        "virtualRoomWallCenterM=$CAMERA_HWB_PROJECTION_WALL_CENTER_MARKER " +
        "virtualRoomWallSizeM=$CAMERA_HWB_PROJECTION_WALL_SIZE_MARKER " +
        "cameraFacingParticleSurface=true projectionLockedParticleSurface=true " +
        "targetDistanceMeters=${activityMarkerFloat(input.targetDistanceMeters)} " +
        "targetDistanceDefaultMeters=$CAMERA_HWB_PROJECTION_TARGET_DISTANCE_MARKER " +
        "targetDistanceProperty=none-fixed-camera-projection-default " +
        "targetDistanceParameterSource=${input.targetDistanceSource} " +
        "virtualRoomForegroundDistanceActive=${input.virtualRoomForegroundDistanceActive} " +
        "virtualRoomForegroundDistanceMeters=${activityMarkerFloat(CAMERA_HWB_PROJECTION_ROOM_FOREGROUND_TARGET_DISTANCE_METERS)} " +
        "projectionPanelInputClearanceActive=${input.privatePanelInputClearanceActive} " +
        "projectionPanelInputBehindPrivateLayerPanel=${input.inputCarrierBehindPrivatePanel} " +
        "projectionPanelInputClearanceMeters=${activityMarkerFloat(CAMERA_HWB_PROJECTION_PRIVATE_PANEL_INPUT_CLEARANCE_METERS)} " +
        "projectionPanelInputClearanceTargetDistanceMeters=${activityMarkerFloat(input.privatePanelInputClearanceTargetDistanceMeters)} " +
        "targetDistanceJoystickControlsEnabled=false " +
        "targetDistanceJoystickInput=none-fixed-distance " +
        "projectionTargetScaleJoystickControlsEnabled=true " +
        "projectionTargetScaleJoystickInput=android-right-stick-y;spatial-sdk-avatar-body-right-thumb-up-down;native-openxr-right-thumbstick-y;panel-control " +
        "projectionTargetScaleJoystickRateProperty=$CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_RATE_PROPERTY " +
        "projectionTargetScaleJoystickRatePerSecond=${activityMarkerFloat(input.targetScaleJoystickRatePerSecond)} " +
        "stereoHorizontalOffsetJoystickControlsEnabled=false " +
        "stereoHorizontalOffsetJoystickInput=disabled-default-locked-left-stick-y-controls-workflow-or-private-panel-distance-only " +
        "stereoHorizontalOffsetJoystickRateProperty=none-disabled " +
        "stereoHorizontalOffsetJoystickRateUvPerSecond=0.000 " +
        "cameraHwbProjectionStereoHorizontalOffsetIgnoresPanelVisibility=true " +
        "targetFovTangents=$CAMERA_HWB_PROJECTION_TARGET_FOV_TANGENTS " +
        "projectionWidthMeters=${activityMarkerFloat(input.projectionWidthMeters)} " +
        "projectionHeightMeters=${activityMarkerFloat(input.projectionHeightMeters)} " +
        "surfaceOverscanScale=$CAMERA_HWB_PROJECTION_SURFACE_OVERSCAN_MARKER " +
        "surfaceWidthMeters=${activityMarkerFloat(input.projectionWidthMeters)} " +
        "surfaceHeightMeters=${activityMarkerFloat(input.projectionHeightMeters)} " +
        "projectionPlaneAngularCoveragePreserved=true " +
        "eyeSpaceTargetRectPreserved=true " +
        "projectionTarget=$CAMERA_HWB_PROJECTION_TARGET " +
        "borderOpacity=$CAMERA_HWB_PROJECTION_BORDER_OPACITY_MARKER " +
        "leftCameraId=50 rightCameraId=51 " +
        "leftTargetScreenUvRect=$CAMERA_HWB_PROJECTION_LEFT_TARGET_RECT_MARKER " +
        "rightTargetScreenUvRect=$CAMERA_HWB_PROJECTION_RIGHT_TARGET_RECT_MARKER " +
        "leftEffectiveTargetScreenUvRect=${leftEffectiveTargetRectMarker(input.targetScale, input.stereoHorizontalOffsetUv)} " +
        "rightEffectiveTargetScreenUvRect=${rightEffectiveTargetRectMarker(input.targetScale, input.stereoHorizontalOffsetUv)} " +
        "leftPackedEffectiveTargetScreenUvRect=${leftPackedEffectiveTargetRectMarker(input.targetScale, input.stereoHorizontalOffsetUv)} " +
        "rightPackedEffectiveTargetScreenUvRect=${rightPackedEffectiveTargetRectMarker(input.targetScale, input.stereoHorizontalOffsetUv)} " +
        "projectionTargetControlsEnabled=true " +
        "projectionTargetLiveScale=${activityMarkerFloat(input.targetScale)} " +
        "projectionTargetTunedMaxScale=${activityMarkerFloat(input.targetScale)} " +
        "projectionTargetMinScale=${activityMarkerFloat(CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE)} " +
        "projectionTargetMaxScale=${activityMarkerFloat(CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE)} " +
        "projectionTargetOffsetUv=0.000000,0.000000 " +
        "projectionTargetStereoHorizontalOffsetUv=${activityMarkerFloat6(input.stereoHorizontalOffsetUv)} " +
        "projectionTargetStereoHorizontalOffsetDefaultUv=${activityMarkerFloat6(CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV)} " +
        "projectionTargetStereoHorizontalOffsetRangeUv=$CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_RANGE_MARKER " +
        "projectionTargetLeftOffsetUv=${activityMarkerFloat6(-input.stereoHorizontalOffsetUv)},0.000000 " +
        "projectionTargetRightOffsetUv=${activityMarkerFloat6(input.stereoHorizontalOffsetUv)},0.000000 " +
        "projectionTargetStereoHorizontalOffsetSign=positive-increases-separation " +
        "targetClipPolicy=clip-to-visible-eye " +
        "targetCoordinateSpace=${input.targetCoordinateSpace} " +
        "targetProjectionSpace=${input.targetProjectionSpace} " +
        "projectionContentMappingMode=target-local-raster"
  }
}
