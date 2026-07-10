package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Vector2
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import java.util.Locale
import kotlin.math.abs

internal const val NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.native_surface_particle_layer.enabled"
internal const val PRIVATE_SPATIAL_ECS_PARTICLE_RENDERER_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.viscereality_ecs.enabled"
internal const val PANEL_START_IN_PARTICLE_VIEW_PROPERTY =
    "debug.rustyquest.spatial.panel.start_in_particle_view"
internal const val PARTICLE_LAYER_PER_EYE_WIDTH_PX = 1024
internal const val PARTICLE_LAYER_WIDTH_PX = PARTICLE_LAYER_PER_EYE_WIDTH_PX * 2
internal const val PARTICLE_LAYER_HEIGHT_PX = 1024
internal const val PARTICLE_LAYER_TARGET_DISTANCE_METERS = 2.0f
internal const val PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.particle_layer.target_distance_meters"
internal const val PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS = 0.20f
internal const val PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS = 2.00f
internal const val PARTICLE_LAYER_VIEW_YAW_DEGREES = 0.0f
internal const val PARTICLE_LAYER_VIEW_YAW_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.particle_layer.view_yaw_degrees"
internal const val PARTICLE_LAYER_VIEW_YAW_MIN_DEGREES = -45.0f
internal const val PARTICLE_LAYER_VIEW_YAW_MAX_DEGREES = 45.0f
internal const val PARTICLE_LAYER_PANEL_OPACITY = 1.0f
internal const val PARTICLE_LAYER_PANEL_OPACITY_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.particle_layer.panel_opacity"
internal const val PARTICLE_LAYER_PANEL_OPACITY_MIN = 0.0f
internal const val PARTICLE_LAYER_PANEL_OPACITY_MAX = 1.0f
internal const val PARTICLE_LAYER_PANEL_LAYER_CHECK_INTERVAL_MS = 500L
internal const val PARTICLE_LAYER_CARRIER_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.particle_layer.carrier"
internal const val PARTICLE_LAYER_CARRIER_DEFAULT = "manual-panel-scene-object-custom-mesh"
internal const val PARTICLE_LAYER_WIDTH_METERS = 5.40f
internal const val PARTICLE_LAYER_HEIGHT_METERS = 4.00f
internal const val PARTICLE_LAYER_HORIZONTAL_FOV_SCALE =
    PARTICLE_LAYER_WIDTH_METERS / PARTICLE_LAYER_HEIGHT_METERS
private const val PARTICLE_LAYER_WIDTH_PER_DISTANCE =
    PARTICLE_LAYER_WIDTH_METERS / PARTICLE_LAYER_TARGET_DISTANCE_METERS
private const val PARTICLE_LAYER_HEIGHT_PER_DISTANCE =
    PARTICLE_LAYER_HEIGHT_METERS / PARTICLE_LAYER_TARGET_DISTANCE_METERS
private const val PARTICLE_LAYER_DIMENSION_MIN_METERS = 0.20f
internal const val PARTICLE_LAYER_WIDTH_MAX_METERS = 5.40f
internal const val PARTICLE_LAYER_HEIGHT_MAX_METERS = 4.00f
internal const val PARTICLE_LAYER_SURFACE_WIDTH_MAX_METERS = 5.40f
internal const val PARTICLE_LAYER_SURFACE_HEIGHT_MAX_METERS = 4.00f
internal const val PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE = 1.00f
internal const val PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.particle_layer.surface_overscan_scale"
internal const val PARTICLE_LAYER_SURFACE_OVERSCAN_MIN_SCALE = 1.00f
internal const val PARTICLE_LAYER_SURFACE_OVERSCAN_MAX_SCALE = 1.00f
internal const val PARTICLE_LAYER_X_METERS = 0.0f
internal const val PARTICLE_LAYER_Y_METERS = 1.22f
internal const val PARTICLE_LAYER_Z_METERS = -2.0f
internal const val PARTICLE_LAYER_PARTICLE_COUNT = 2048
internal const val PARTICLE_LAYER_FRAME_COUNT = 0
internal const val PARTICLE_LAYER_Z_INDEX = 8
private const val PARTICLE_LAYER_STEREO_MODE = "LeftRight"
private const val PARTICLE_LAYER_PLACEMENT_MODE = "viewer-pose-projection-locked-quad"
private const val PARTICLE_LAYER_PLACEMENT_AUTHORITY = "spatial-sdk-viewer-pose-scene-tick"
internal const val PARTICLE_LAYER_TARGET_COORDINATE_SPACE = "spatial-sdk-surface-panel-eye-uv"
internal const val PARTICLE_LAYER_TARGET_PROJECTION_SPACE =
    "spatial-sdk-panel-plane-perspective-projection"
private const val PARTICLE_LAYER_PROJECTION_CONTENT_MAPPING_MODE =
    "world-to-spatial-sdk-panel-plane-left-right"
private const val PARTICLE_LAYER_TARGET_FOV_TANGENTS = "panel-plane-derived"
private const val PARTICLE_LAYER_TARGET_SURFACE_UV_RECT = "0.0;0.0;1.0;1.0"
private const val PARTICLE_LAYER_VIEW_ORIGIN_METERS = "0.0;0.0;2.0"
private const val PARTICLE_LAYER_VIEW_ORIGIN_YAW_DEGREES = "180.0"
internal const val PARTICLE_LAYER_PROJECTION_MARKER_INTERVAL_MS = 900L
internal const val SURFACE_PARTICLE_RECENTER_ACCEPTED_BIT = 1L shl 5

internal enum class SpatialSurfaceParticleCarrierMode(val markerToken: String) {
  VideoSurfacePanelSceneObject("video-surface-panel-scene-object"),
  ManualPanelSceneObjectCustomMesh("manual-panel-scene-object-custom-mesh"),
}

internal object SpatialSurfaceParticleRouteModule {
  const val MODULE_ID = "spatial-surface-particle-route-policy"

  private const val RENDER_POLICY = "native-vulkan-wsi-surface-panel"

  fun nativeSurfaceParticleLayerEnabled(
      rawValue: Boolean?,
      privateRendererEnabled: Boolean,
  ): Boolean = (rawValue ?: true) && !privateRendererEnabled

  fun privateSpatialEcsParticleRendererEnabled(rawValue: Boolean?): Boolean = rawValue ?: false

  fun nativeSurfaceParticleLayerSuppressedByPrivateRenderer(
      privateRendererEnabled: Boolean
  ): Boolean = privateRendererEnabled

  fun nativeSurfaceParticleLayerSuppressionSource(suppressedByPrivateRenderer: Boolean): String =
      if (suppressedByPrivateRenderer) {
        "private-spatial-ecs-particle-renderer"
      } else {
        "property"
      }

  fun startInParticleView(rawValue: Boolean?, buildConfigDefault: Boolean): Boolean =
      rawValue ?: buildConfigDefault

  fun carrierMode(
      rawValue: String,
      buildConfigDefault: String,
  ): SpatialSurfaceParticleCarrierMode =
      parseCarrierMode(rawValue) ?: defaultCarrierMode(buildConfigDefault)

  fun defaultCarrierMode(buildConfigDefault: String): SpatialSurfaceParticleCarrierMode =
      parseCarrierMode(buildConfigDefault) ?: parseCarrierMode(PARTICLE_LAYER_CARRIER_DEFAULT)!!

  fun manualCustomMeshCarrierEnabled(mode: SpatialSurfaceParticleCarrierMode): Boolean =
      mode == SpatialSurfaceParticleCarrierMode.ManualPanelSceneObjectCustomMesh

  fun carrierToken(mode: SpatialSurfaceParticleCarrierMode): String =
      if (manualCustomMeshCarrierEnabled(mode)) {
        "spatial-sdk-manual-panel-scene-object-android-surface"
      } else {
        "spatial-sdk-video-surface-panel-android-surface"
      }

  fun stereoMarkerFields(): String =
      "stereoMode=$PARTICLE_LAYER_STEREO_MODE " +
          "perEyeExtent=${PARTICLE_LAYER_PER_EYE_WIDTH_PX}x$PARTICLE_LAYER_HEIGHT_PX " +
          "packedExtent=${PARTICLE_LAYER_WIDTH_PX}x$PARTICLE_LAYER_HEIGHT_PX"

  fun mediaSettings(): MediaPanelSettings =
      MediaPanelSettings(
          shape =
              QuadShapeOptions(
                  surfaceWidthMeters(PARTICLE_LAYER_TARGET_DISTANCE_METERS),
                  surfaceHeightMeters(PARTICLE_LAYER_TARGET_DISTANCE_METERS),
              ),
          display =
              FixedMediaPanelDisplayOptions(
                  widthPx = PARTICLE_LAYER_WIDTH_PX,
                  heightPx = PARTICLE_LAYER_HEIGHT_PX,
              ),
          rendering =
              MediaPanelRenderOptions(
                  false,
                  StereoMode.LeftRight,
                  SamplerConfig(),
                  0,
                  PARTICLE_LAYER_Z_INDEX,
              ),
          style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
          input = PanelInputOptions(0),
      )

  fun manualCarrierMediaSettings(
      surfaceWidthMeters: Float,
      surfaceHeightMeters: Float,
  ): MediaPanelSettings =
      MediaPanelSettings(
          shape = QuadShapeOptions(surfaceWidthMeters, surfaceHeightMeters),
          display =
              FixedMediaPanelDisplayOptions(
                  widthPx = PARTICLE_LAYER_WIDTH_PX,
                  heightPx = PARTICLE_LAYER_HEIGHT_PX,
              ),
          rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight),
          input = PanelInputOptions(0),
      )

  fun placementMarkerFields(
      carrierMode: SpatialSurfaceParticleCarrierMode,
      targetDistanceMeters: Float,
      viewYawDegrees: Float,
      surfaceOverscanScale: Float,
      panelOpacity: Float,
  ): String {
    val projectionWidth = projectionWidthMeters(targetDistanceMeters)
    val projectionHeight = projectionHeightMeters(targetDistanceMeters)
    val surfaceWidth = surfaceWidthMeters(targetDistanceMeters, surfaceOverscanScale)
    val surfaceHeight = surfaceHeightMeters(targetDistanceMeters, surfaceOverscanScale)
    val manualCarrier = manualCustomMeshCarrierEnabled(carrierMode)
    return "cameraFacingParticleSurface=true projectionLockedParticleSurface=true " +
        "surfaceParticleProjectionCarrier=${activityMarkerToken(carrierToken(carrierMode))} " +
        "surfaceParticleProjectionCarrierProperty=$PARTICLE_LAYER_CARRIER_PROPERTY " +
        "manualPanelSceneObjectCustomMesh=$manualCarrier " +
        "manualPanelForceSceneTexture=$manualCarrier " +
        "placementMode=$PARTICLE_LAYER_PLACEMENT_MODE " +
        "placementAuthority=$PARTICLE_LAYER_PLACEMENT_AUTHORITY " +
        "targetCoordinateSpace=$PARTICLE_LAYER_TARGET_COORDINATE_SPACE " +
        "targetProjectionSpace=$PARTICLE_LAYER_TARGET_PROJECTION_SPACE " +
        "projectionContentMappingMode=$PARTICLE_LAYER_PROJECTION_CONTENT_MAPPING_MODE " +
        "targetFovTangents=$PARTICLE_LAYER_TARGET_FOV_TANGENTS " +
        "targetDistanceMeters=${activityMarkerFloat(targetDistanceMeters)} " +
        "targetDistanceDefaultMeters=$PARTICLE_LAYER_TARGET_DISTANCE_METERS " +
        "targetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
        "viewYawDegrees=${activityMarkerFloat(viewYawDegrees)} " +
        "viewYawProperty=$PARTICLE_LAYER_VIEW_YAW_PROPERTY " +
        "surfaceOverscanScale=${activityMarkerFloat(surfaceOverscanScale)} " +
        "surfaceOverscanDefaultScale=$PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE " +
        "surfaceOverscanProperty=$PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY " +
        projectionSurfaceMarkerFields(
            projectionWidth,
            projectionHeight,
            surfaceWidth,
            surfaceHeight,
        ) +
        " leftTargetSurfaceUvRect=$PARTICLE_LAYER_TARGET_SURFACE_UV_RECT " +
        "rightTargetSurfaceUvRect=$PARTICLE_LAYER_TARGET_SURFACE_UV_RECT " +
        "viewOriginMeters=$PARTICLE_LAYER_VIEW_ORIGIN_METERS " +
        "viewOriginYawDegrees=$PARTICLE_LAYER_VIEW_ORIGIN_YAW_DEGREES " +
        "x=$PARTICLE_LAYER_X_METERS y=$PARTICLE_LAYER_Y_METERS z=$PARTICLE_LAYER_Z_METERS " +
        "projectionWidthMeters=${activityMarkerFloat(projectionWidth)} " +
        "projectionHeightMeters=${activityMarkerFloat(projectionHeight)} " +
        "surfaceWidthMeters=${activityMarkerFloat(surfaceWidth)} " +
        "surfaceHeightMeters=${activityMarkerFloat(surfaceHeight)} " +
        "particleLayerPanelOpacity=${activityMarkerFloat(panelOpacity)} " +
        "particleLayerPanelOpacityProperty=$PARTICLE_LAYER_PANEL_OPACITY_PROPERTY"
  }

  fun projectionSurfaceMarkerFields(
      projectionWidthMeters: Float,
      projectionHeightMeters: Float,
      surfaceWidthMeters: Float,
      surfaceHeightMeters: Float,
  ): String {
    val scaleX = projectionWidthMeters / surfaceWidthMeters.coerceAtLeast(0.001f)
    val scaleY = projectionHeightMeters / surfaceHeightMeters.coerceAtLeast(0.001f)
    val dimensionsMatch =
        abs(projectionWidthMeters - surfaceWidthMeters) < 0.001f &&
            abs(projectionHeightMeters - surfaceHeightMeters) < 0.001f
    return "overscanMode=none projectionSurfaceScaleX=${activityMarkerFloat(scaleX)} " +
        "projectionSurfaceScaleY=${activityMarkerFloat(scaleY)} " +
        "panelDimensionsMatchProjection=$dimensionsMatch overscanCompensated=not-required " +
        "horizontalProjectionMode=wide-fov " +
        "projectionHorizontalScale=${activityMarkerFloat(PARTICLE_LAYER_HORIZONTAL_FOV_SCALE)}"
  }

  fun nativeSurfaceParticleProjectionPlaneUpdateSuppressedMarker(reason: String): String =
      "channel=native-surface-particle-layer status=projection-plane-update-suppressed " +
          "reason=${activityMarkerToken(reason)} cameraStackSuppressesParticles=true " +
          "particleLayerVisible=false nativePanelPoseAuthority=camera-hwb-projection-plane"

  fun nativeSurfaceParticlePanelLayerUpdatedMarker(reason: String, opacity: Float): String =
      "channel=native-surface-particle-layer status=particle-panel-layer-updated " +
          "renderPolicy=$RENDER_POLICY reason=${activityMarkerToken(reason)} " +
          "particleLayerPanelAlphaBlendApplied=true " +
          "particleLayerPanelColorScaleAlphaApplied=true " +
          "particleLayerPanelLayerConfigCached=true " +
          "particleLayerPanelOpacity=${activityMarkerFloat(opacity)} " +
          "particleLayerPanelOpacityProperty=$PARTICLE_LAYER_PANEL_OPACITY_PROPERTY " +
          "particleLayerZIndex=$PARTICLE_LAYER_Z_INDEX runtimeCrash=false"

  fun nativeSurfaceParticlePanelLayerUpdateFailedMarker(
      reason: String,
      opacity: Float,
      error: String,
      message: String,
  ): String =
      "channel=native-surface-particle-layer status=particle-panel-layer-update-failed " +
          "renderPolicy=$RENDER_POLICY reason=${activityMarkerToken(reason)} " +
          "particleLayerPanelOpacity=${activityMarkerFloat(opacity)} " +
          "particleLayerPanelOpacityProperty=$PARTICLE_LAYER_PANEL_OPACITY_PROPERTY " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun nativeSurfaceParticleProjectionPlaneUpdateSkippedMarker(
      reason: String,
      error: String,
  ): String =
      "channel=native-surface-particle-layer status=projection-plane-update-skipped " +
          "reason=${activityMarkerToken(reason)} error=${activityMarkerToken(error)}"

  fun nativeSurfaceParticleSurfaceGeometryHotloadUpdatedMarker(
      targetDistanceMeters: Float,
      projectionWidthMeters: Float,
      projectionHeightMeters: Float,
      surfaceOverscanScale: Float,
      surfaceWidthMeters: Float,
      surfaceHeightMeters: Float,
      projectionSurfaceMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=surface-geometry-hotload-updated " +
          "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property " +
          "particleLayerTargetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
          "particleLayerSurfaceOverscanParameterSource=runtime-hotload-android-property " +
          "particleLayerSurfaceOverscanProperty=$PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY " +
          "targetDistanceMeters=${activityMarkerFloat(targetDistanceMeters)} " +
          "projectionPlanePoseInvariantWithOverscan=true " +
          "projectionWidthMeters=${activityMarkerFloat(projectionWidthMeters)} " +
          "projectionHeightMeters=${activityMarkerFloat(projectionHeightMeters)} " +
          "surfaceOverscanScale=${activityMarkerFloat(surfaceOverscanScale)} " +
          "surfaceWidthMeters=${activityMarkerFloat(surfaceWidthMeters)} " +
          "surfaceHeightMeters=${activityMarkerFloat(surfaceHeightMeters)} " +
          projectionSurfaceMarkerFields

  fun nativeSurfaceParticlePanelPoseNativeUpdateFailedMarker(
      reason: String,
      error: String,
  ): String =
      "channel=native-surface-particle-layer status=panel-pose-native-update-failed " +
          "reason=${activityMarkerToken(reason)} error=${activityMarkerToken(error)}"

  fun nativeSurfaceParticleViewerEyePoseNativeUpdateFailedMarker(
      reason: String,
      error: String,
  ): String =
      "channel=native-surface-particle-layer status=viewer-eye-pose-native-update-failed " +
          "reason=${activityMarkerToken(reason)} error=${activityMarkerToken(error)}"

  fun nativeSurfaceParticleProjectionPlaneUpdatedMarker(
      reason: String,
      placementMarkerFields: String,
      viewYawDegrees: Float,
      viewerPositionM: String,
      viewerForward: String,
      viewerUp: String,
      viewerRight: String,
      panelForward: String,
      panelRight: String,
      panelUp: String,
      nativePanelPoseUpdateMask: Long,
      nativeViewerEyePoseUpdateMask: Long,
      projectionSurfaceMarkerFields: String,
      projectionWidthMeters: Float,
      projectionHeightMeters: Float,
      surfaceOverscanScale: Float,
      surfaceWidthMeters: Float,
      surfaceHeightMeters: Float,
      planeCenterM: String,
      planeQuaternion: String,
      leftEyeOffsetM: String,
      rightEyeOffsetM: String,
      leftEyeWorldM: String,
      rightEyeWorldM: String,
      leftEyeOffsetRightMeters: Float,
      rightEyeOffsetRightMeters: Float,
  ): String =
      "channel=native-surface-particle-layer status=projection-plane-updated " +
          "reason=${activityMarkerToken(reason)} " +
          placementMarkerFields + " " +
          "viewerPoseSource=Scene.getViewerPose eyeOffsetsSource=Scene.getEyeOffsets " +
          "particleLayerTargetDistanceParameterSource=runtime-hotload-android-property " +
          "particleLayerTargetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
          "particleLayerViewYawParameterSource=runtime-hotload-android-property-or-remote-ui-command " +
          "particleLayerViewYawProperty=$PARTICLE_LAYER_VIEW_YAW_PROPERTY " +
          "particleLayerViewYawDegrees=${activityMarkerFloat(viewYawDegrees)} " +
          "projectionPlaneFacingMode=viewer-forward-front-face-roll-stable " +
          "projectionPlaneRollAuthority=spatial-world-up " +
          "projectionPlaneRollFollowsHeadset=false " +
          "viewerPositionM=$viewerPositionM " +
          "viewerForward=$viewerForward viewerUp=$viewerUp " +
          "viewerRight=$viewerRight panelForward=$panelForward " +
          "panelRight=$panelRight panelUp=$panelUp " +
          "panelPoseNativeUpdateMask=$nativePanelPoseUpdateMask " +
          "viewerEyePoseNativeUpdateMask=$nativeViewerEyePoseUpdateMask " +
          "drawCameraPoseSource=Scene.getViewerPose-position+forward-x-mirror-corrected-roll-stable " +
          "panelDefinesEye=false " +
          "worldToPanelProjection=spatial-sdk-panel-plane-basis " +
          "carrierSurfaceProjection=spatial-sdk-panel-plane-basis " +
          "particleLayerSurfaceOverscanProperty=$PARTICLE_LAYER_SURFACE_OVERSCAN_PROPERTY " +
          "$projectionSurfaceMarkerFields " +
          "projectionWidthMeters=${activityMarkerFloat(projectionWidthMeters)} " +
          "projectionHeightMeters=${activityMarkerFloat(projectionHeightMeters)} " +
          "surfaceOverscanScale=${activityMarkerFloat(surfaceOverscanScale)} " +
          "surfaceWidthMeters=${activityMarkerFloat(surfaceWidthMeters)} " +
          "surfaceHeightMeters=${activityMarkerFloat(surfaceHeightMeters)} " +
          "projectionPlanePoseInvariantWithOverscan=true particleWorldScaleInvariantWithOverscan=true " +
          "planeCenterM=$planeCenterM planeQuaternion=$planeQuaternion " +
          "leftEyeOffsetM=$leftEyeOffsetM " +
          "rightEyeOffsetM=$rightEyeOffsetM " +
          "leftEyeWorldM=$leftEyeWorldM " +
          "rightEyeWorldM=$rightEyeWorldM " +
          "leftEyeOffsetRightMeters=${activityMarkerFloat(leftEyeOffsetRightMeters)} " +
          "rightEyeOffsetRightMeters=${activityMarkerFloat(rightEyeOffsetRightMeters)} " +
          "particleLayerEyeOffsetSource=Scene.getEyeOffsets.viewerLocalX"

  fun particleLayerTargetDistanceCommandAppliedMarker(
      source: String,
      requestedMeters: Float,
      targetDistanceMeters: Float,
  ): String =
      "channel=native-surface-particle-layer status=particle-layer-target-distance-command-applied " +
          "source=${activityMarkerToken(source)} " +
          "particleLayerTargetDistanceCommand=true " +
          "particleLayerTargetDistanceCommandTransport=remote-ui-command " +
          "particleLayerTargetDistanceRequestedMeters=${activityMarkerFloat(requestedMeters)} " +
          "particleLayerTargetDistanceMeters=${activityMarkerFloat(targetDistanceMeters)} " +
          "particleLayerTargetDistanceProperty=$PARTICLE_LAYER_TARGET_DISTANCE_PROPERTY " +
          "noPhysicalControllerInput=true"

  fun particleLayerViewYawCommandAppliedMarker(
      source: String,
      requestedDegrees: Float,
      viewYawDegrees: Float,
  ): String =
      "channel=native-surface-particle-layer status=particle-layer-view-yaw-command-applied " +
          "source=${activityMarkerToken(source)} " +
          "particleLayerViewYawCommand=true " +
          "particleLayerViewYawCommandTransport=remote-ui-command " +
          "particleLayerViewYawRequestedDegrees=${activityMarkerFloat(requestedDegrees)} " +
          "particleLayerViewYawDegrees=${activityMarkerFloat(viewYawDegrees)} " +
          "particleLayerViewYawProperty=$PARTICLE_LAYER_VIEW_YAW_PROPERTY " +
          "noPhysicalControllerInput=true"

  fun nativeSurfaceParticleStartSuppressedDisabledMarker(
      suppressionSource: String,
      privateRendererEnabled: Boolean,
      particleLayerStarted: Boolean,
      nativeSurfaceStartRequested: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=start-suppressed " +
          "renderPolicy=$RENDER_POLICY source=$suppressionSource " +
          "nativeSurfaceParticleLayerEnabled=false " +
          "nativeSurfaceParticleLayerEnabledProperty=$NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY " +
          "privateSpatialEcsParticleRendererEnabled=$privateRendererEnabled " +
          "privateSpatialEcsParticleRendererEnabledProperty=$PRIVATE_SPATIAL_ECS_PARTICLE_RENDERER_ENABLED_PROPERTY " +
          "particleLayerVisible=false particleLayerStarted=$particleLayerStarted " +
          "nativeSurfaceStartRequested=$nativeSurfaceStartRequested"

  fun nativeSurfaceParticleStartSuppressedCameraStackMarker(
      particleLayerStarted: Boolean,
      nativeSurfaceStartRequested: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=start-suppressed " +
          "renderPolicy=$RENDER_POLICY source=camera-stack " +
          "cameraStackSuppressesParticles=true particleLayerVisible=false " +
          "particleLayerStarted=$particleLayerStarted nativeSurfaceStartRequested=$nativeSurfaceStartRequested"

  fun nativeSurfaceParticleStartSkippedAlreadyStartedMarker(): String =
      "channel=native-surface-particle-layer status=start-skipped " +
          "renderPolicy=$RENDER_POLICY reason=already-started"

  fun nativeSurfaceParticleLibraryUnavailableMarker(error: String): String =
      "channel=native-surface-particle-layer status=library-unavailable " +
          "renderPolicy=$RENDER_POLICY error=${activityMarkerToken(error)}"

  fun nativeSurfaceParticleSurfaceUnavailableMarker(): String =
      "channel=native-surface-particle-layer status=surface-unavailable " +
          "renderPolicy=$RENDER_POLICY surfaceValid=false"

  fun nativeSurfaceParticleStartRequestedMarker(
      surfaceValid: Boolean,
      startMask: Long,
      carrier: String,
      openXrInstanceHandleNonZero: Boolean,
      openXrSessionHandleNonZero: Boolean,
      openXrGetInstanceProcAddrHandleNonZero: Boolean,
      placementMarkerFields: String,
      stereoMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=start-requested " +
          "renderPolicy=$RENDER_POLICY " +
          "surfaceValid=$surfaceValid startMask=$startMask " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          "liveHandJointInputExpected=true " +
          "openXrInstanceHandleNonZero=$openXrInstanceHandleNonZero " +
          "openXrSessionHandleNonZero=$openXrSessionHandleNonZero " +
          "openXrGetInstanceProcAddrHandleNonZero=$openXrGetInstanceProcAddrHandleNonZero " +
          "widthPx=$PARTICLE_LAYER_WIDTH_PX heightPx=$PARTICLE_LAYER_HEIGHT_PX " +
          "particleCount=$PARTICLE_LAYER_PARTICLE_COUNT frameCount=$PARTICLE_LAYER_FRAME_COUNT " +
          placementMarkerFields + " " +
          stereoMarkerFields

  fun nativeSurfaceParticleStartFailedMarker(error: String, message: String): String =
      "channel=native-surface-particle-layer status=start-failed " +
          "renderPolicy=$RENDER_POLICY error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)}"

  fun nativeSurfaceParticleParameterSubmitSkippedMarker(source: String): String =
      "channel=native-surface-particle-layer status=parameter-submit-skipped " +
          "renderPolicy=$RENDER_POLICY reason=library-unavailable " +
          "source=${activityMarkerToken(source)}"

  fun nativeSurfaceParticleParametersSubmittedMarker(
      source: String,
      parameterMask: Long,
      controls: SurfaceParticleControlState,
  ): String =
      "channel=native-surface-particle-layer status=parameters-submitted " +
          "renderPolicy=$RENDER_POLICY transport=jni-live-queue " +
          "computeParameterBridge=true privateSurfaceParticleUiParameterPacketReady=true " +
          "privateSurfaceParticleUiParameterTransport=jni-live-queue " +
          "privateSurfaceParticleUiParameterHighRatePayloadAllowed=false " +
          "privateSurfaceParticleUiParameterRejected=false " +
          "privateSurfaceParticleUiParameterRejectReason=none " +
          "source=${activityMarkerToken(source)} parameterMask=$parameterMask " +
          "driver0Value01=${surfaceParticleParameterFloat(controls.driver0Value01)} " +
          "driver1Value01=${surfaceParticleParameterFloat(controls.driver1Value01)} " +
          "driver2Value01=${surfaceParticleParameterFloat(controls.driver2Value01)} " +
          "driver3Value01=${surfaceParticleParameterFloat(controls.driver3Value01)} " +
          "driver4Value01=${surfaceParticleParameterFloat(controls.driver4Value01)} " +
          "driver5Value01=${surfaceParticleParameterFloat(controls.driver5Value01)} " +
          "driver6Value01=${surfaceParticleParameterFloat(controls.driver6Value01)} " +
          "driver7Value01=${surfaceParticleParameterFloat(controls.driver7Value01)} " +
          "pointScale=${surfaceParticleParameterFloat(controls.pointScale)} " +
          "tracerDrawSlotsPerOscillator=${surfaceParticleParameterFloat(controls.tracerDrawSlotsPerOscillator)} " +
          "tracerLifetimeSeconds=${surfaceParticleParameterFloat(controls.tracerLifetimeSeconds)} " +
          "tracerCopiesPerSecond=${surfaceParticleParameterFloat(controls.tracerCopiesPerSecond)} " +
          "transparencyOpacity=${surfaceParticleParameterFloat(controls.transparencyOpacity)} " +
          "projectionWorldScale=${surfaceParticleParameterFloat(controls.projectionWorldScale)}"

  fun nativeSurfaceParticleParameterSubmitFailedMarker(source: String, error: String): String =
      "channel=native-surface-particle-layer status=parameter-submit-failed " +
          "renderPolicy=$RENDER_POLICY source=${activityMarkerToken(source)} " +
          "error=${activityMarkerToken(error)}"

  fun nativeSurfaceParticleAliasSubmitSkippedMarker(
      source: String,
      parameterId: String,
      visualDriverActivationProfile: String,
  ): String =
      "channel=native-surface-particle-layer status=alias-parameter-submit-skipped " +
          "renderPolicy=$RENDER_POLICY reason=library-unavailable " +
          "source=${activityMarkerToken(source)} parameterId=${activityMarkerToken(parameterId)} " +
          "visualDriverActivationProfile=${activityMarkerToken(visualDriverActivationProfile)}"

  fun nativeSurfaceParticleAliasSubmittedMarker(
      source: String,
      parameterId: String,
      visualDriverActivationProfile: String,
      requestedValue: Float,
      parameterMask: Long,
  ): String =
      "channel=native-surface-particle-layer status=alias-parameter-submitted " +
          "renderPolicy=$RENDER_POLICY transport=jni-live-queue " +
          "computeParameterBridge=true source=${activityMarkerToken(source)} " +
          "parameterId=${activityMarkerToken(parameterId)} " +
          "visualDriverActivationProfile=${activityMarkerToken(visualDriverActivationProfile)} " +
          "requestedValue=${activityMarkerFloat(requestedValue)} parameterMask=$parameterMask " +
          "privateSurfaceParticleUiParameterPacketReady=true " +
          "privateSurfaceParticleUiParameterTransport=jni-live-queue " +
          "privateSurfaceParticleUiParameterHighRatePayloadAllowed=false"

  fun nativeSurfaceParticleAliasSubmitFailedMarker(
      source: String,
      parameterId: String,
      error: String,
  ): String =
      "channel=native-surface-particle-layer status=alias-parameter-submit-failed " +
          "renderPolicy=$RENDER_POLICY source=${activityMarkerToken(source)} " +
          "parameterId=${activityMarkerToken(parameterId)} " +
          "error=${activityMarkerToken(error)}"

  fun nativeSurfaceParticleSurfaceConsumerCalledMarker(
      surfaceValid: Boolean,
      carrier: String,
      placementMarkerFields: String,
      stereoMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=surface-consumer-called " +
          "renderPolicy=$RENDER_POLICY surfaceValid=$surfaceValid " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          placementMarkerFields + " " +
          stereoMarkerFields

  fun nativeSurfaceParticleSurfacePanelReadyMarker(
      panelHandle: Long,
      layerUpdateStatus: String,
      surfaceValid: Boolean,
      carrier: String,
      placementMarkerFields: String,
      stereoMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=surface-panel-ready " +
          "renderPolicy=$RENDER_POLICY panelHandle=$panelHandle " +
          "particleLayerPanelLayerUpdateStatus=${activityMarkerToken(layerUpdateStatus)} " +
          "surfaceValid=$surfaceValid " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          placementMarkerFields + " " +
          stereoMarkerFields

  fun nativeSurfaceParticlePanelRegistrationSuppressedMarker(
      source: String,
      nativeSurfaceParticleLayerEnabled: Boolean,
      privateSpatialEcsParticleRendererEnabled: Boolean,
      carrier: String,
      manualPanelSceneObjectCustomMesh: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=panel-registration-suppressed " +
          "renderPolicy=$RENDER_POLICY source=${activityMarkerToken(source)} " +
          "nativeSurfaceParticleLayerEnabled=$nativeSurfaceParticleLayerEnabled " +
          "nativeSurfaceParticleLayerEnabledProperty=$NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY " +
          "privateSpatialEcsParticleRendererEnabled=$privateSpatialEcsParticleRendererEnabled " +
          "privateSpatialEcsParticleRendererEnabledProperty=$PRIVATE_SPATIAL_ECS_PARTICLE_RENDERER_ENABLED_PROPERTY " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          "manualPanelSceneObjectCustomMesh=$manualPanelSceneObjectCustomMesh"

  fun nativeSurfaceParticlePanelRegistrationsCreatedMarker(
      panelRegistrationCount: Int,
      particlePanelRegistrationId: String,
      carrier: String,
      nativeSurfaceParticleLayerEnabled: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=panel-registrations-created " +
          "renderPolicy=$RENDER_POLICY panelRegistrationCount=$panelRegistrationCount " +
          "workflowPanelRegistrationId=spatial_camera_panel " +
          "launcherPanelRegistrationId=spatial_camera_panel_launcher " +
          "projectionPanelRegistrationId=spatial_camera_projection_surface_panel " +
          "particlePanelRegistrationId=${activityMarkerToken(particlePanelRegistrationId)} " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          "nativeSurfaceParticleLayerEnabled=$nativeSurfaceParticleLayerEnabled"

  fun nativeSurfaceParticlePanelEntityCreateFailedMarker(error: String, message: String): String =
      "channel=native-surface-particle-layer status=panel-entity-create-failed " +
          "renderPolicy=$RENDER_POLICY error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)}"

  fun nativeSurfaceParticlePanelEntitySuppressedMarker(
      source: String,
      privateSpatialEcsParticleRendererEnabled: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=panel-entity-suppressed " +
          "renderPolicy=$RENDER_POLICY source=${activityMarkerToken(source)} " +
          "nativeSurfaceParticleLayerEnabled=false " +
          "nativeSurfaceParticleLayerEnabledProperty=$NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY " +
          "privateSpatialEcsParticleRendererEnabled=$privateSpatialEcsParticleRendererEnabled " +
          "privateSpatialEcsParticleRendererEnabledProperty=$PRIVATE_SPATIAL_ECS_PARTICLE_RENDERER_ENABLED_PROPERTY"

  fun nativeSurfaceParticlePanelEntitySpawnedMarker(
      placementMarkerFields: String,
      stereoMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=panel-entity-spawned " +
          "renderPolicy=$RENDER_POLICY panelRegistrationId=spatial_camera_surface_panel " +
          placementMarkerFields + " " +
          stereoMarkerFields

  fun nativeSurfaceParticleLifecycleCheckMarker(
      phase: String,
      activityMarkersFile: String,
      panelRegistrationCount: Int,
      panelMode: String,
      workflowPanelVisible: Boolean,
      launcherPanelVisible: Boolean,
      legacyLauncherPanelSuppressed: Boolean,
      particleLayerEntityCreated: Boolean,
      particleSurfacePanelReady: Boolean,
      particleSurfaceConsumerCalled: Boolean,
      particleSurfaceConsumerSurfaceValid: Boolean,
      nativeSurfaceParticleLayerEnabled: Boolean,
      particleLayerStarted: Boolean,
      nativeSurfaceStartRequested: Boolean,
      lastNativeSurfaceStartMask: Long,
      nativeReceiptLibraryLoaded: Boolean,
      nativeReceiptLibraryError: String,
      openXrInstanceHandleNonZero: Boolean,
      openXrSessionHandleNonZero: Boolean,
      openXrGetInstanceProcAddrHandleNonZero: Boolean,
      currentDriverProfileId: String,
      currentProfileId: String,
      placementMarkerFields: String,
      stereoMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=lifecycle-check " +
          "phase=${activityMarkerToken(phase)} " +
          "renderPolicy=$RENDER_POLICY " +
          "activityMarkerFile=$activityMarkersFile panelRegistrationCount=$panelRegistrationCount " +
          "panelMode=$panelMode workflowPanelVisible=$workflowPanelVisible " +
          "launcherPanelVisible=$launcherPanelVisible " +
          "legacyLauncherPanelSuppressed=$legacyLauncherPanelSuppressed " +
          "particleLayerEntityCreated=$particleLayerEntityCreated " +
          "particleSurfacePanelReady=$particleSurfacePanelReady " +
          "particleSurfaceConsumerCalled=$particleSurfaceConsumerCalled " +
          "particleSurfaceConsumerSurfaceValid=$particleSurfaceConsumerSurfaceValid " +
          "nativeSurfaceParticleLayerEnabled=$nativeSurfaceParticleLayerEnabled " +
          "nativeSurfaceParticleLayerEnabledProperty=$NATIVE_SURFACE_PARTICLE_LAYER_ENABLED_PROPERTY " +
          "particleLayerStarted=$particleLayerStarted " +
          "nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
          "lastNativeSurfaceStartMask=$lastNativeSurfaceStartMask " +
          "nativeReceiptLibraryLoaded=$nativeReceiptLibraryLoaded " +
          "nativeReceiptLibraryError=${activityMarkerToken(nativeReceiptLibraryError)} " +
          "openXrInstanceHandleNonZero=$openXrInstanceHandleNonZero " +
          "openXrSessionHandleNonZero=$openXrSessionHandleNonZero " +
          "openXrGetInstanceProcAddrHandleNonZero=$openXrGetInstanceProcAddrHandleNonZero " +
          "currentDriverProfileId=${activityMarkerToken(currentDriverProfileId)} " +
          "currentProfileId=${activityMarkerToken(currentProfileId)} " +
          placementMarkerFields + " " +
          stereoMarkerFields

  fun nativeSurfaceParticleRecenterIgnoredMarker(
      inputSource: String,
      detail: String,
      surfaceTargetId: String,
      particleLayerVisible: Boolean,
      requireParticleView: Boolean,
      workflowPanelVisible: Boolean,
      privateLayerPanelVisible: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=particle-recenter-ignored " +
          "controllerInput=right-trigger-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "requiredSurfaceTargetId=icosphere particleLayerVisible=$particleLayerVisible " +
          "requireParticleView=$requireParticleView workflowPanelVisible=$workflowPanelVisible " +
          "privateLayerPanelVisible=$privateLayerPanelVisible " +
          "privateSurfaceParticleWorldAnchorRecenterAccepted=false " +
          "privateSurfaceParticleWorldAnchorRecenterRejectReason=not-icosphere-particle-view " +
          "privateSurfaceParticleRecenterChangesCoordinateMapping=false"

  fun nativeSurfaceParticleRecenterNativeUnavailableMarker(
      inputSource: String,
      detail: String,
      surfaceTargetId: String,
  ): String =
      "channel=native-surface-particle-layer status=particle-recenter-failed " +
          "controllerInput=right-trigger-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "reason=native-library-unavailable privateSurfaceParticleWorldAnchorRecenterAccepted=false " +
          "privateSurfaceParticleRecenterChangesCoordinateMapping=false"

  fun nativeSurfaceParticleRecenterRequestedMarker(
      inputSource: String,
      detail: String,
      surfaceTargetId: String,
      particleLayerVisible: Boolean,
      requireParticleView: Boolean,
      nativeRecenterMask: Long,
      nativeRecenterAccepted: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=particle-recenter-requested " +
          "controllerInput=right-trigger-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "particleLayerVisible=$particleLayerVisible requireParticleView=$requireParticleView " +
          "nativeRecenterMask=$nativeRecenterMask nativeRecenterAccepted=$nativeRecenterAccepted " +
          "privateSurfaceParticleWorldAnchorRecenterSource=spatial-sdk-viewer-trigger " +
          "privateSurfaceParticleWorldAnchorCenterSource=current-spatial-sdk-viewer-world-coordinate " +
          "privateSurfaceParticleWorldAnchorBasis=spatial-world-canonical-axes " +
          "privateSurfaceParticleWorldAnchorScaleSource=fixed-sim-meter-radius " +
          "privateSurfaceParticleSimRegistration=sim-space-fixed-in-spatial-sdk-world-space " +
          "privateSurfaceParticleSimTransform=spatial-world-from-sim-fixed-configured-origin-basis-meter-scale " +
          "privateSurfaceParticleSimWorldAxesStable=true " +
          "privateSurfaceParticleRecenterChangesCoordinateMapping=false " +
          "privateSurfaceParticleRecenterChangesOnlySphereCenter=true"

  fun nativeSurfaceParticleRecenterFailedMarker(
      inputSource: String,
      detail: String,
      surfaceTargetId: String,
      error: String,
      message: String,
  ): String =
      "channel=native-surface-particle-layer status=particle-recenter-failed " +
          "controllerInput=right-trigger-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} " +
          "privateSurfaceParticleWorldAnchorRecenterAccepted=false " +
          "privateSurfaceParticleRecenterChangesCoordinateMapping=false"

  fun cameraStackParticleLayerSuppressedMarker(
      source: String,
      stopAttempted: Boolean,
      stopSucceeded: Boolean,
      launcherPanelVisible: Boolean,
      particleLayerStarted: Boolean,
      nativeSurfaceStartRequested: Boolean,
  ): String =
      "channel=camera-hwb-spatial-probe status=particle-layer-suppressed " +
          "source=${activityMarkerToken(source)} cameraStackSuppressesParticles=true " +
          "stopAttempted=$stopAttempted stopSucceeded=$stopSucceeded particleLayerVisible=false " +
          "launcherPanelVisible=$launcherPanelVisible " +
          "legacyLauncherPanelSuppressed=true launcherPanelSuppressedForCameraStack=true " +
          "particleLayerStarted=$particleLayerStarted " +
          "nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
          "particleLayerRenderContinuity=stopped-for-camera-stack"

  fun cameraStackParticleLayerSuppressFailedMarker(
      source: String,
      particleLayerStarted: Boolean,
      nativeSurfaceStartRequested: Boolean,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=particle-layer-suppress-failed " +
          "source=${activityMarkerToken(source)} cameraStackSuppressesParticles=true " +
          "stopAttempted=true stopSucceeded=false particleLayerVisible=false " +
          "particleLayerStarted=$particleLayerStarted " +
          "nativeSurfaceStartRequested=$nativeSurfaceStartRequested " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)}"

  fun nativeSurfaceParticleStoppedMarker(
      source: String,
      particleLayerStarted: Boolean,
      nativeSurfaceStartRequested: Boolean,
  ): String =
      "channel=native-surface-particle-layer status=stopped " +
          "renderPolicy=$RENDER_POLICY source=${activityMarkerToken(source)} " +
          "particleLayerStarted=$particleLayerStarted " +
          "nativeSurfaceStartRequested=$nativeSurfaceStartRequested"

  fun nativeSurfaceParticleStopFailedMarker(source: String, error: String, message: String): String =
      "channel=native-surface-particle-layer status=stop-failed " +
          "renderPolicy=$RENDER_POLICY source=${activityMarkerToken(source)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)}"

  fun projectionWidthMeters(targetDistanceMeters: Float): Float =
      (targetDistanceMeters * PARTICLE_LAYER_WIDTH_PER_DISTANCE)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_WIDTH_MAX_METERS)

  fun projectionHeightMeters(targetDistanceMeters: Float): Float =
      (targetDistanceMeters * PARTICLE_LAYER_HEIGHT_PER_DISTANCE)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_HEIGHT_MAX_METERS)

  fun surfaceWidthMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE,
  ): Float =
      (projectionWidthMeters(targetDistanceMeters) * overscanScale)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_SURFACE_WIDTH_MAX_METERS)

  fun surfaceHeightMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = PARTICLE_LAYER_SURFACE_OVERSCAN_SCALE,
  ): Float =
      (projectionHeightMeters(targetDistanceMeters) * overscanScale)
          .coerceIn(PARTICLE_LAYER_DIMENSION_MIN_METERS, PARTICLE_LAYER_SURFACE_HEIGHT_MAX_METERS)

  fun surfacePanelDimensions(
      targetDistanceMeters: Float,
      overscanScale: Float,
  ): PanelDimensions =
      PanelDimensions(
          Vector2(
              surfaceWidthMeters(targetDistanceMeters, overscanScale),
              surfaceHeightMeters(targetDistanceMeters, overscanScale),
          )
      )

  private fun parseCarrierMode(value: String): SpatialSurfaceParticleCarrierMode? =
      when (value.trim().lowercase(Locale.US)) {
        "video", "video-panel", "video-surface-panel", "video-surface-panel-scene-object" ->
            SpatialSurfaceParticleCarrierMode.VideoSurfacePanelSceneObject
        "manual", "manual-panel", "manual-custom-mesh", "manual-panel-scene-object",
        "manual-panel-scene-object-custom-mesh" ->
            SpatialSurfaceParticleCarrierMode.ManualPanelSceneObjectCustomMesh
        else -> null
      }

  private fun surfaceParticleParameterFloat(value: Float): String =
      String.format(Locale.US, "%.3f", value)
}
