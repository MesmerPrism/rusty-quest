package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Observable accepted controls for an already-open Compose panel after an external profile apply. */
internal object SpatialPrivateLayerControlPanelStateBridge {
  var controls: SpatialCameraPanelControlSnapshot? by mutableStateOf(null)
    private set

  fun publish(accepted: SpatialCameraPanelControlSnapshot) {
    controls = accepted.normalized()
  }
}
