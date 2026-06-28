package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LayerPanelBackground = Color(0xFF141820)
private val LayerPanelSurface = Color(0xFF202634)
private val LayerPanelSurfaceAlt = Color(0xFF293142)
private val LayerPanelInk = Color(0xFFF4F7FA)
private val LayerPanelMuted = Color(0xFFAAB3C2)
private val LayerPanelAccent = Color(0xFF63D2FF)
private val LayerPanelWarm = Color(0xFFFFC857)
private val LayerPanelBorder = Color(0xFF3B465A)

internal data class PrivateLayerChoice(
    val index: Int,
    val title: String,
    val token: String,
)

internal data class PrivateLayerDepthAlignment(
    val leftX: Float = 0.0f,
    val leftY: Float = 0.0f,
    val rightX: Float = 0.0f,
    val rightY: Float = 0.0f,
    val sampleScale: Float = 1.0f,
)

internal object PrivateLayerControls {
  const val cycleOverride: Float = -1.0f

  val layers =
      listOf(
          PrivateLayerChoice(0, "Final", "final"),
          PrivateLayerChoice(1, "Raw brightness", "raw-brightness"),
          PrivateLayerChoice(2, "Preblur brightness", "preblur-brightness"),
          PrivateLayerChoice(3, "Raw strength", "raw-strength"),
          PrivateLayerChoice(4, "Blurred strength", "blurred-strength"),
          PrivateLayerChoice(5, "Displacement", "displacement"),
          PrivateLayerChoice(6, "Depth gradient", "depth-gradient"),
      )

  fun labelForOverride(layerOverride: Float): String {
    val rounded = layerOverride.toInt()
    return if (layerOverride < 0.0f) {
      "Cycle"
    } else {
      layers.firstOrNull { it.index == rounded }?.title ?: "Final"
    }
  }
}

@Composable
internal fun PrivateLayerControlPanel(
    layerOverride: Float,
    projectionScale: Float,
    projectionScaleRange: ClosedFloatingPointRange<Float>,
    depthAlignment: PrivateLayerDepthAlignment,
    setLayerOverride: (Float, String) -> Float,
    updateProjectionScale: (Float, String) -> Float,
    updateDepthAlignment: (PrivateLayerDepthAlignment, String) -> PrivateLayerDepthAlignment,
    closePanel: () -> Unit,
) {
  var localLayerOverride by remember(layerOverride) { mutableStateOf(layerOverride) }
  var localProjectionScale by remember(projectionScale) { mutableStateOf(projectionScale) }
  var localDepthAlignment by remember(depthAlignment) { mutableStateOf(depthAlignment) }
  Surface(
      modifier = Modifier.fillMaxSize(),
      color = LayerPanelBackground,
      contentColor = LayerPanelInk,
  ) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LayerPanelBackground)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
              "Layer Selection Panel",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
          )
          Text(
              "Active: ${PrivateLayerControls.labelForOverride(localLayerOverride)}",
              style = MaterialTheme.typography.bodyMedium,
              color = LayerPanelMuted,
          )
        }
        Button(
            onClick = closePanel,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = LayerPanelSurfaceAlt,
                    contentColor = LayerPanelInk,
                ),
        ) {
          Text("Close")
        }
      }

      PreviewBand()
      Section("Active Rendering") {
        LayerButtonGrid(
            selectedLayerOverride = localLayerOverride,
            onSelect = { override ->
              localLayerOverride = setLayerOverride(override, "private-layer-control-panel")
            },
        )
      }

      Section("Projection Area") {
        Text(
            "Scale ${"%.2f".format(localProjectionScale)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Slider(
            value = localProjectionScale,
            onValueChange = { value ->
              localProjectionScale = updateProjectionScale(value, "private-layer-control-panel-scale")
            },
            valueRange = projectionScaleRange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OperatorButton("0.75x") {
            localProjectionScale =
                updateProjectionScale(0.75f, "private-layer-control-panel-scale-preset")
          }
          OperatorButton("1.00x") {
            localProjectionScale =
                updateProjectionScale(1.0f, "private-layer-control-panel-scale-preset")
          }
          OperatorButton("1.25x") {
            localProjectionScale =
                updateProjectionScale(1.25f, "private-layer-control-panel-scale-preset")
          }
        }
      }

      Section("Depth Alignment") {
        DepthSlider("Left depth X", localDepthAlignment.leftX, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(leftX = value),
                  "private-layer-control-panel-depth-left-x",
              )
        }
        DepthSlider("Left depth Y", localDepthAlignment.leftY, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(leftY = value),
                  "private-layer-control-panel-depth-left-y",
              )
        }
        DepthSlider("Right depth X", localDepthAlignment.rightX, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rightX = value),
                  "private-layer-control-panel-depth-right-x",
              )
        }
        DepthSlider("Right depth Y", localDepthAlignment.rightY, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rightY = value),
                  "private-layer-control-panel-depth-right-y",
              )
        }
        DepthSlider("Depth sample scale", localDepthAlignment.sampleScale, 0.25f..3.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(sampleScale = value),
                  "private-layer-control-panel-depth-sample-scale",
              )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OperatorButton("Reset Depth") {
            localDepthAlignment =
                updateDepthAlignment(
                    PrivateLayerDepthAlignment(),
                    "private-layer-control-panel-depth-reset",
                )
          }
        }
      }
    }
  }
}

@Composable
private fun PreviewBand() {
  Box(
      modifier =
          Modifier
              .fillMaxWidth()
              .height(62.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(
                  Brush.horizontalGradient(
                      listOf(
                          Color(0xFF111827),
                          Color(0xFF2B8FD8),
                          LayerPanelWarm,
                          Color(0xFFD84F9A),
                          Color(0xFF111827),
                      )
                  )
              )
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp)),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        "private layer selector",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(
      modifier =
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(LayerPanelSurface)
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp))
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider(color = LayerPanelBorder)
    content()
  }
}

@Composable
private fun LayerButtonGrid(
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    LayerButtonRow(
        choices =
            listOf(
                PrivateLayerChoice(-1, "Cycle", "cycle"),
                PrivateLayerControls.layers[0],
            ),
        selectedLayerOverride = selectedLayerOverride,
        onSelect = onSelect,
    )
    PrivateLayerControls.layers.drop(1).chunked(2).forEach { row ->
      LayerButtonRow(row, selectedLayerOverride, onSelect)
    }
  }
}

@Composable
private fun LayerButtonRow(
    choices: List<PrivateLayerChoice>,
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    choices.forEach { choice ->
      val override =
          if (choice.index < 0) PrivateLayerControls.cycleOverride else choice.index.toFloat()
      val selected =
          if (override < 0.0f) {
            selectedLayerOverride < 0.0f
          } else {
            selectedLayerOverride.toInt() == choice.index
          }
      Button(
          modifier = Modifier.weight(1.0f).height(52.dp),
          onClick = { onSelect(override) },
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
                  contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
              ),
      ) {
        Text(choice.title)
      }
    }
    if (choices.size == 1) {
      Spacer(Modifier.weight(1.0f))
    }
  }
}

@Composable
private fun DepthSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text("%.3f".format(value), style = MaterialTheme.typography.bodyMedium, color = LayerPanelMuted)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
  }
}

@Composable
private fun OperatorButton(label: String, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = LayerPanelSurfaceAlt,
              contentColor = LayerPanelInk,
          ),
  ) {
    Text(label)
  }
}
