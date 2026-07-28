package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal class SpatialPanelPlacementStateCoordinator(
    initialPrivateLayerPlacement: PanelPlacement,
) {
  var privateLayerPlacement: PanelPlacement = initialPrivateLayerPlacement
    private set

  var privateLayerVisible: Boolean = false
    private set

  fun replacePrivateLayerPlacement(placement: PanelPlacement): PanelPlacement {
    privateLayerPlacement = placement
    return privateLayerPlacement
  }

  fun setPrivateLayerVisibleFlag(visible: Boolean) {
    privateLayerVisible = visible
  }

  fun hideAllPanels() {
    privateLayerVisible = false
    privateLayerPlacement = privateLayerPlacement.copy(visible = false)
  }

  fun setPrivateLayerPanelVisible(
      visible: Boolean,
      focus: Boolean,
      inputForegroundDistanceMeters: Float,
      inputForegroundScale: Float,
      freeTransform: Boolean,
  ): PanelPlacement {
    privateLayerVisible = visible
    privateLayerPlacement =
        if (visible && focus) {
          SpatialPanelPlacementModule.coercePrivateLayerPanelPlacement(
              privateLayerPlacement.copy(
                  visible = true,
                  headlocked = true,
                  zMeters = inputForegroundDistanceMeters,
                  scale = inputForegroundScale,
                  widthMeters = PANEL_WIDTH_METERS,
                  heightMeters = PANEL_HEIGHT_METERS,
              )
          )
        } else {
          privateLayerPlacement.copy(visible = false)
        }
    if (visible && focus && freeTransform) {
      privateLayerPlacement = privateLayerPlacement.copy(headlocked = false)
    }
    return privateLayerPlacement
  }

  companion object {
    const val MODULE_ID = "spatial-panel-placement-state-coordinator"
  }
}
