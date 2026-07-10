package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialPrivateLayerPanelLayerBindings(
    val layerConfigEnabled: () -> Boolean,
    val panelAvailable: () -> Boolean,
    val applyLayerZIndex: () -> Boolean,
    val marker: (String) -> Unit,
)

internal class SpatialPrivateLayerPanelLayerCoordinator(
    private val bindings: SpatialPrivateLayerPanelLayerBindings,
) {
  fun update(reason: String, forceLog: Boolean = true): String {
    if (!bindings.layerConfigEnabled()) {
      return "disabled-mesh-render-mode"
    }
    if (!bindings.panelAvailable()) {
      return "panel-scene-object-missing"
    }
    return runCatching {
          if (!bindings.applyLayerZIndex()) {
            return "panel-layer-missing"
          }
          "updated-private-layer-panel-z-index"
        }
        .getOrElse { throwable ->
          if (forceLog) {
            bindings.marker(
                SpatialPanelPlacementModule.privateLayerPanelLayerUpdateFailedMarker(
                    reason = reason,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
          "failed-${throwable.javaClass.simpleName}"
        }
  }

  companion object {
    const val MODULE_ID = "spatial-private-layer-panel-layer-coordinator"
  }
}
