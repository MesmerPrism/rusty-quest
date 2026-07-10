package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.graphics.Color
import android.graphics.Paint
import android.view.Surface

internal data class SpatialCameraHwbProjectionSyntheticRendererBindings(
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionSyntheticRenderer(
    private val bindings: SpatialCameraHwbProjectionSyntheticRendererBindings,
) {
  fun draw(surface: Surface, carrierLabel: String): Boolean {
    if (!surface.isValid) {
      bindings.marker(CameraHwbProjectionModule.syntheticVisualDrawSkippedMarker(carrierLabel))
      return false
    }
    var canvas: android.graphics.Canvas? = null
    return runCatching {
          canvas = surface.lockCanvas(null)
          val lockedCanvas = canvas ?: return@runCatching false
          val paint = Paint()
          val cellsX = 8
          val cellsY = 8
          val cellWidth = lockedCanvas.width / cellsX.toFloat()
          val cellHeight = lockedCanvas.height / cellsY.toFloat()
          val colors =
              intArrayOf(
                  Color.rgb(226, 18, 18),
                  Color.rgb(18, 210, 58),
                  Color.rgb(18, 86, 232),
                  Color.rgb(242, 214, 20),
              )
          for (y in 0 until cellsY) {
            for (x in 0 until cellsX) {
              paint.color = colors[(x + y) % colors.size]
              lockedCanvas.drawRect(
                  x * cellWidth,
                  y * cellHeight,
                  (x + 1) * cellWidth,
                  (y + 1) * cellHeight,
                  paint,
              )
            }
          }
          paint.isAntiAlias = true
          paint.color = Color.BLACK
          lockedCanvas.drawRect(
              lockedCanvas.width * 0.18f,
              lockedCanvas.height * 0.40f,
              lockedCanvas.width * 0.82f,
              lockedCanvas.height * 0.60f,
              paint,
          )
          paint.color = Color.WHITE
          paint.textSize = lockedCanvas.height * 0.075f
          lockedCanvas.drawText(
              "SPATIAL SDK",
              lockedCanvas.width * 0.24f,
              lockedCanvas.height * 0.52f,
              paint,
          )
          true
        }
        .onSuccess { drawn ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          bindings.marker(
              CameraHwbProjectionModule.syntheticVisualDrawCompleteMarker(drawn, carrierLabel)
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          bindings.marker(
              CameraHwbProjectionModule.syntheticVisualDrawFailedMarker(
                  carrierLabel = carrierLabel,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
        .getOrDefault(false)
  }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-synthetic-renderer"
  }
}
