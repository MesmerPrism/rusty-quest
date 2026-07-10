package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal class SpatialPanelPlacementStateCoordinator(
    initialWorkflowPlacement: PanelPlacement,
    initialPrivateLayerPlacement: PanelPlacement,
) {
  var workflowPlacement: PanelPlacement = initialWorkflowPlacement
    private set

  var privateLayerPlacement: PanelPlacement = initialPrivateLayerPlacement
    private set

  var privateLayerVisible: Boolean = false
    private set

  fun replaceWorkflowPlacement(placement: PanelPlacement): PanelPlacement {
    workflowPlacement = placement
    return workflowPlacement
  }

  fun replacePrivateLayerPlacement(placement: PanelPlacement): PanelPlacement {
    privateLayerPlacement = placement
    return privateLayerPlacement
  }

  fun setPrivateLayerVisibleFlag(visible: Boolean) {
    privateLayerVisible = visible
  }

  fun hideAllPanels() {
    workflowPlacement = workflowPlacement.copy(visible = false)
    privateLayerVisible = false
    privateLayerPlacement = privateLayerPlacement.copy(visible = false)
  }

  fun adjustWorkflowPlacement(
      deltaX: Float,
      deltaY: Float,
      deltaZ: Float,
      deltaScale: Float,
  ): PanelPlacement {
    workflowPlacement =
        SpatialPanelPlacementModule.adjustWorkflowPlacement(
            workflowPlacement,
            deltaX,
            deltaY,
            deltaZ,
            deltaScale,
        )
    return workflowPlacement
  }

  fun resizeWorkflowPanel(deltaWidth: Float, deltaHeight: Float): PanelPlacement {
    workflowPlacement =
        SpatialPanelPlacementModule.resizeWorkflowPanel(
            workflowPlacement,
            deltaWidth,
            deltaHeight,
        )
    return workflowPlacement
  }

  fun resetWorkflowPanelPlacement(): PanelPlacement {
    privateLayerVisible = false
    privateLayerPlacement = privateLayerPlacement.copy(visible = false)
    workflowPlacement =
        SpatialPanelPlacementModule.resetWorkflowPanelPlacement(workflowPlacement)
    return workflowPlacement
  }

  fun setWorkflowHeadlocked(enabled: Boolean): PanelPlacement {
    workflowPlacement =
        SpatialPanelPlacementModule.setWorkflowHeadlocked(workflowPlacement, enabled)
    return workflowPlacement
  }

  fun setWorkflowPanelVisible(visible: Boolean, focus: Boolean): PanelPlacement {
    if (visible) {
      privateLayerVisible = false
      privateLayerPlacement = privateLayerPlacement.copy(visible = false)
    }
    workflowPlacement =
        if (visible && focus) {
          if (workflowPlacement.headlocked) {
            workflowPlacement.copy(
                visible = true,
                xMeters = PANEL_HEADLOCK_OFFSET_X_METERS,
                yMeters = PANEL_HEADLOCK_OFFSET_Y_METERS,
                zMeters = PANEL_FRONT_OF_CAMERA_VIDEO_DISTANCE_METERS,
                scale = PANEL_FRONT_OF_CAMERA_VIDEO_SCALE,
            )
          } else {
            workflowPlacement.copy(
                visible = true,
                yMeters = PANEL_FOCUS_Y_METERS,
                zMeters = PANEL_FOCUS_Z_METERS,
                scale = 1.0f,
            )
          }
        } else {
          workflowPlacement.copy(visible = visible)
        }
    return workflowPlacement
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
    workflowPlacement = workflowPlacement.copy(visible = false)
    return workflowPlacement
  }

  companion object {
    const val MODULE_ID = "spatial-panel-placement-state-coordinator"
  }
}
