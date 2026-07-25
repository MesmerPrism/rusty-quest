package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.File
import org.json.JSONObject

internal data class SpatialPanelHeadlockTuningSnapshot(
    val privateLayerPlacement: PanelPlacement,
)

internal data class SpatialPanelPersistenceBindings(
    val outputDirectory: () -> File,
    val headlockSnapshot: () -> SpatialPanelHeadlockTuningSnapshot,
    val panelMode: () -> String,
    val recordPanelForegroundState: (String, String) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialPanelPersistenceCoordinator(
    private val bindings: SpatialPanelPersistenceBindings,
) {
  fun persistHeadlockTuning(source: String) {
    runCatching {
          val snapshot = bindings.headlockSnapshot()
          val activePlacement = snapshot.privateLayerPlacement
          val row =
              JSONObject()
                  .put("schema_id", "rusty.quest.spatial_camera_panel.panel_headlock_tuning.v1")
                  .put("source", source)
                  .put("updated_at_unix_ms", System.currentTimeMillis())
                  .put(
                      "active_panel",
                      "private-layer-panel",
                  )
                  .put("headlocked", activePlacement.headlocked)
                  .put("offset_x_m", activePlacement.xMeters.toDouble())
                  .put("offset_y_m", activePlacement.yMeters.toDouble())
                  .put("distance_m", activePlacement.zMeters.toDouble())
                  .put(
                      "distance_mode",
                      "left-stick-stored-placement",
                  )
                  .put("scale", activePlacement.scale.toDouble())
                  .put("width_m", activePlacement.widthMeters.toDouble())
                  .put("height_m", activePlacement.heightMeters.toDouble())
                  .put(
                      "private_layer_panel",
                      privateLayerPlacementJson(snapshot.privateLayerPlacement),
                  )
          File(bindings.outputDirectory(), PANEL_HEADLOCK_TUNING_FILE)
              .writeText(row.toString(2), Charsets.UTF_8)
        }
        .getOrElse { throwable ->
          bindings.marker(
              SpatialPanelPlacementModule.headlockTuningPersistFailedMarker(
                  source = source,
                  error = throwable.javaClass.simpleName,
              )
          )
        }
  }

  fun recordPanelState(source: String) {
    runCatching { bindings.recordPanelForegroundState(bindings.panelMode(), source) }
        .getOrElse { throwable ->
          bindings.marker(
              SpatialPanelPlacementModule.panelStateRecordFailedMarker(
                  source = source,
                  error = throwable.javaClass.simpleName,
              )
          )
        }
  }

  private fun privateLayerPlacementJson(placement: PanelPlacement): JSONObject =
      JSONObject()
          .put("headlocked", placement.headlocked)
          .put("offset_x_m", placement.xMeters.toDouble())
          .put("offset_y_m", placement.yMeters.toDouble())
          .put("distance_m", placement.zMeters.toDouble())
          .put("distance_mode", "left-stick-stored-placement")
          .put("render_mode", "spatial-sdk-mesh")
          .put("layer_config", "disabled")
          .put("layer_z_index", "none")
          .put("scale", placement.scale.toDouble())
          .put("width_m", placement.widthMeters.toDouble())
          .put("height_m", placement.heightMeters.toDouble())

  companion object {
    const val MODULE_ID = "spatial-panel-persistence-coordinator"
  }
}
