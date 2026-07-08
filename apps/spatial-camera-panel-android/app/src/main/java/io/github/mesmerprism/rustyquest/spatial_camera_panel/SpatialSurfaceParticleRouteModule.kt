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
}
