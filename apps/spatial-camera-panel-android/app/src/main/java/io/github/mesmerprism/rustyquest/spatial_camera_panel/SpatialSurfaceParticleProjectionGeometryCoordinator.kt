package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.toolkit.PanelDimensions

internal data class SpatialSurfaceParticleProjectionGeometryBindings(
    val configuredTargetDistanceMeters: () -> Float,
    val configuredViewYawDegrees: () -> Float,
    val panelOpacity: () -> Float,
    val surfaceOverscanScale: () -> Float,
    val carrierMode: () -> SpatialSurfaceParticleCarrierMode,
    val updateProjection: (String, Boolean) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialSurfaceParticleProjectionGeometryCoordinator(
    private val bindings: SpatialSurfaceParticleProjectionGeometryBindings,
) {
  var remoteTargetDistanceMeters: Float? = null
    private set

  var remoteViewYawDegrees: Float? = null
    private set

  fun currentTargetDistanceMeters(): Float =
      remoteTargetDistanceMeters ?: bindings.configuredTargetDistanceMeters()

  fun currentViewYawDegrees(): Float =
      remoteViewYawDegrees ?: bindings.configuredViewYawDegrees()

  fun currentPanelOpacity(): Float = bindings.panelOpacity()

  fun currentSurfaceOverscanScale(): Float = bindings.surfaceOverscanScale()

  fun applyTargetDistance(
      requested: Float,
      source: String,
  ): Float {
    val clamped =
        requested.coerceIn(
            PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
            PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
        )
    remoteTargetDistanceMeters = clamped
    bindings.updateProjection("$source-particle-panel-distance", true)
    bindings.marker(
        SpatialSurfaceParticleRouteModule.particleLayerTargetDistanceCommandAppliedMarker(
            source,
            requested,
            clamped,
        )
    )
    return clamped
  }

  fun applyViewYaw(
      requested: Float,
      source: String,
  ): Float {
    val clamped =
        requested.coerceIn(
            PARTICLE_LAYER_VIEW_YAW_MIN_DEGREES,
            PARTICLE_LAYER_VIEW_YAW_MAX_DEGREES,
        )
    remoteViewYawDegrees = clamped
    bindings.updateProjection("$source-particle-panel-view-yaw", true)
    bindings.marker(
        SpatialSurfaceParticleRouteModule.particleLayerViewYawCommandAppliedMarker(
            source,
            requested,
            clamped,
        )
    )
    return clamped
  }

  fun projectionWidthMeters(targetDistanceMeters: Float): Float =
      SpatialSurfaceParticleRouteModule.projectionWidthMeters(targetDistanceMeters)

  fun projectionHeightMeters(targetDistanceMeters: Float): Float =
      SpatialSurfaceParticleRouteModule.projectionHeightMeters(targetDistanceMeters)

  fun surfaceWidthMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = currentSurfaceOverscanScale(),
  ): Float =
      SpatialSurfaceParticleRouteModule.surfaceWidthMeters(targetDistanceMeters, overscanScale)

  fun surfaceHeightMeters(
      targetDistanceMeters: Float,
      overscanScale: Float = currentSurfaceOverscanScale(),
  ): Float =
      SpatialSurfaceParticleRouteModule.surfaceHeightMeters(targetDistanceMeters, overscanScale)

  fun surfacePanelDimensions(
      targetDistanceMeters: Float = currentTargetDistanceMeters(),
      overscanScale: Float = currentSurfaceOverscanScale(),
  ): PanelDimensions =
      SpatialSurfaceParticleRouteModule.surfacePanelDimensions(targetDistanceMeters, overscanScale)

  fun placementMarkerFields(): String {
    val targetDistanceMeters = currentTargetDistanceMeters()
    val surfaceOverscanScale = currentSurfaceOverscanScale()
    return SpatialSurfaceParticleRouteModule.placementMarkerFields(
        carrierMode = bindings.carrierMode(),
        targetDistanceMeters = targetDistanceMeters,
        viewYawDegrees = currentViewYawDegrees(),
        surfaceOverscanScale = surfaceOverscanScale,
        panelOpacity = currentPanelOpacity(),
    )
  }

  companion object {
    const val MODULE_ID = "spatial-surface-particle-projection-geometry-coordinator"
  }
}
