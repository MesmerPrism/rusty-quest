package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.math.abs

internal data class SpatialSurfaceParticlePanelLayerBindings(
    val marker: (String) -> Unit,
)

internal data class SpatialSurfaceParticlePanelLayerUpdateRequest(
    val reason: String,
    val forceLog: Boolean,
    val opacity: Float,
    val applyLayerChanges: (Boolean, Boolean, Float) -> Boolean,
)

internal class SpatialSurfaceParticlePanelLayerCoordinator(
    private val bindings: SpatialSurfaceParticlePanelLayerBindings,
) {
  private var lastOpacity: Float? = null
  private var layerConfigured = false

  fun update(request: SpatialSurfaceParticlePanelLayerUpdateRequest): String =
      runCatching {
            val previousOpacity = lastOpacity
            val opacityChanged =
                previousOpacity == null || abs(previousOpacity - request.opacity) >= 0.001f
            val layerConfigChanged = request.forceLog || !layerConfigured
            if (!request.applyLayerChanges(layerConfigChanged, opacityChanged, request.opacity)) {
              return "panel-layer-missing"
            }
            if (layerConfigChanged) {
              layerConfigured = true
            }
            if (opacityChanged) {
              lastOpacity = request.opacity
            }
            if (request.forceLog || layerConfigChanged || opacityChanged) {
              bindings.marker(
                  SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelLayerUpdatedMarker(
                      request.reason,
                      request.opacity,
                  )
              )
            }
            if (layerConfigChanged || opacityChanged) {
              "updated-particle-layer-panel-alpha"
            } else {
              "unchanged-particle-layer-panel-alpha"
            }
          }
          .getOrElse { throwable ->
            if (request.forceLog) {
              bindings.marker(
                  SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelLayerUpdateFailedMarker(
                      request.reason,
                      request.opacity,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                  )
              )
            }
            "failed-${throwable.javaClass.simpleName}"
          }

  companion object {
    const val MODULE_ID = "spatial-surface-particle-panel-layer-coordinator"
  }
}
