package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box as ComposeBox
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal object ExperimentPanelController {
  const val boundaryId: String = "spatial-sdk-experiment-panel-controller"
  const val authority: String = "low-rate-compose-ui-and-session-requester"
  const val highRatePayloadPolicy: String = "forbidden"
}

internal val PanelProbeBackground = Color(0xFFFFF3B0)
internal val PanelProbeHeader = Color(0xFF0F5F6F)
internal val PanelProbeButton = Color(0xFFFF5A1F)
internal val PanelProbeInk = Color(0xFF111827)
private val PanelProbeBorder = Color(0xFF0B1720)
private const val PANEL_RESIZE_STEP_METERS = 0.12f

@Composable
internal fun SpatialCameraPanel(
    store: SpatialCameraPanelStore,
    placement: PanelPlacement,
    particleControls: SurfaceParticleControlState,
    polarPanel: PolarSensorPanel,
    setWorkflowPanelVisible: (Boolean, Boolean, String) -> PanelPlacement,
    adjustPlacement: (Float, Float, Float, Float) -> PanelPlacement,
    setPanelHeadlocked: (Boolean, String) -> PanelPlacement,
    resizePanel: (Float, Float) -> PanelPlacement,
    resetPlacement: () -> PanelPlacement,
    updateParticleControls: (Float, Float, Float) -> SurfaceParticleControlState,
    applyDriverProfile: (ActiveBlockSnapshot, String) -> SurfaceParticleControlState,
) {
  var snapshot by remember { mutableStateOf(store.snapshot()) }
  var localPlacement by remember { mutableStateOf(placement) }
  var localParticleControls by remember { mutableStateOf(particleControls) }

  fun refreshSnapshot(source: String) {
    val updated = store.snapshot()
    if (updated.stage == "questionnaire") {
      localPlacement = setWorkflowPanelVisible(true, true, source)
    }
    snapshot = updated
  }

  fun startBlockFromPanel(surfaceId: String?, source: String) {
    if (surfaceId != null) {
      store.selectSurface(surfaceId)
    }
    localPlacement = setWorkflowPanelVisible(false, false, source)
    val block = store.startNextBlock()
    if (block == null) {
      localPlacement = setWorkflowPanelVisible(true, true, "$source-complete")
      refreshSnapshot("$source-complete")
      return
    }
    localParticleControls = applyDriverProfile(block, source)
    snapshot = store.snapshot()
  }

  LaunchedEffect(snapshot.stage, snapshot.activeBlock?.deadlineUnixMs) {
    while (snapshot.stage == "block_running") {
      delay(500L)
      store.syncElapsedBlock()
      refreshSnapshot("experiment-block-elapsed-questionnaire")
    }
  }

  Surface(
      modifier = Modifier.fillMaxSize(),
      color = PanelProbeBackground,
      contentColor = PanelProbeInk,
  ) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PanelProbeBackground)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      PanelColorProbe(snapshot)
      Header(snapshot)
      PanelModeControls(
          placement = localPlacement,
          setWorkflowPanelVisible = setWorkflowPanelVisible,
          setPanelHeadlocked = setPanelHeadlocked,
          resetPlacement = resetPlacement,
          onPlacementChanged = { localPlacement = it },
      )
      PanelPlacementControls(
          placement = localPlacement,
          onAdjust = { dx, dy, dz, ds -> localPlacement = adjustPlacement(dx, dy, dz, ds) },
          onResize = { dw, dh -> localPlacement = resizePanel(dw, dh) },
      )
      SurfaceParticleControls(localParticleControls) { driver0, driver1, pointScale ->
        localParticleControls = updateParticleControls(driver0, driver1, pointScale)
      }
      HorizontalDivider()
      when (snapshot.stage) {
        "participant" ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  refreshSnapshot("experiment-participant-created")
                },
                onReset = {
                  store.resetForNewParticipant()
                  refreshSnapshot("experiment-reset")
                },
            )
        "polar_setup" ->
            PolarSetupStep(
                snapshot = snapshot,
                polarPanel = polarPanel,
                onContinue = { runLabel, operatorId, notes ->
                  store.savePolarSetup(runLabel, operatorId, notes)
                  refreshSnapshot("experiment-polar-setup-recorded")
                },
            )
        "surface_setup" ->
            SurfaceStep(
                snapshot = snapshot,
                onStart = { surfaceId -> startBlockFromPanel(surfaceId, "experiment-block-start") },
            )
        "block_running" ->
            RunningStep(snapshot) {
              store.syncElapsedBlock()
              refreshSnapshot("experiment-block-running-refresh")
            }
        "questionnaire" ->
            QuestionnaireStep(snapshot) { comfort, intensity, engagement, notes, signature ->
              store.submitQuestionnaire(comfort, intensity, engagement, notes, signature)
              refreshSnapshot("experiment-questionnaire-submitted")
            }
        "ready_next_block" ->
            ReadyNextStep(snapshot) { startBlockFromPanel(null, "experiment-ready-next-block") }
        "complete" ->
            CompleteStep(snapshot) {
              store.resetForNewParticipant()
              refreshSnapshot("experiment-reset")
            }
        else ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  refreshSnapshot("experiment-participant-created")
                },
                onReset = {
                  store.resetForNewParticipant()
                  refreshSnapshot("experiment-reset")
                },
            )
      }
    }
  }
}

@Composable
internal fun SpatialCameraPanelLauncher(openPanel: () -> Unit) {
  Surface(
      modifier = Modifier.fillMaxSize(),
      color = PanelProbeHeader,
      contentColor = Color.White,
  ) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Particles running", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
      Button(
          onClick = openPanel,
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = PanelProbeButton,
                  contentColor = Color.White,
              ),
      ) {
        Text("Open Panel")
      }
    }
  }
}

@Composable
private fun SurfaceParticleControls(
    controls: SurfaceParticleControlState,
    onChange: (Float, Float, Float) -> Unit,
) {
  var driver0 by remember { mutableStateOf(controls.driver0Value01) }
  var driver1 by remember { mutableStateOf(controls.driver1Value01) }
  var pointScale by remember { mutableStateOf(controls.pointScale) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Native particle compute", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    NativeParticleSlider("Driver 0", driver0, 0.0f, 1.0f) {
      driver0 = it
      onChange(driver0, driver1, pointScale)
    }
    NativeParticleSlider("Driver 1", driver1, 0.0f, 1.0f) {
      driver1 = it
      onChange(driver0, driver1, pointScale)
    }
    NativeParticleSlider("Point scale", pointScale, 0.35f, 2.25f) {
      pointScale = it
      onChange(driver0, driver1, pointScale)
    }
  }
}

@Composable
private fun NativeParticleSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = { onChange(it.coerceIn(min, max)) },
        valueRange = min..max,
    )
  }
}

@Composable
private fun PanelColorProbe(snapshot: ExperimentSnapshot) {
  Surface(
      modifier =
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .border(3.dp, PanelProbeBorder, RoundedCornerShape(10.dp)),
      color = PanelProbeHeader,
      contentColor = Color.White,
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
          modifier = Modifier.weight(1.0f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
            "PANEL COLOR PROBE",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Non-black Compose content is active. Stage: ${snapshot.stage}",
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      Spacer(Modifier.width(16.dp))
      Button(
          onClick = {},
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = PanelProbeButton,
                  contentColor = Color.White,
              ),
      ) {
        Text("Visible Button")
      }
    }
  }
}

@Composable
private fun Header(snapshot: ExperimentSnapshot) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Spatial Camera Panel", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Stage: ${snapshot.stage}", style = MaterialTheme.typography.bodyMedium)
    if (snapshot.sessionId.isNotBlank()) {
      Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
      Text("Session: ${snapshot.sessionId}", style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun PanelModeControls(
    placement: PanelPlacement,
    setWorkflowPanelVisible: (Boolean, Boolean, String) -> PanelPlacement,
    setPanelHeadlocked: (Boolean, String) -> PanelPlacement,
    resetPlacement: () -> PanelPlacement,
    onPlacementChanged: (PanelPlacement) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        "Panel mode: ${if (placement.visible) "workflow panel" else "particle view"}; ${if (placement.headlocked) "headlocked" else "world-locked"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onPlacementChanged(setWorkflowPanelVisible(true, true, "panel-view-button")) }) {
        Text("Panel View")
      }
      Button(onClick = { onPlacementChanged(setWorkflowPanelVisible(false, false, "particle-view-button")) }) {
        Text("Particle View")
      }
      Button(onClick = { onPlacementChanged(resetPlacement()) }) {
        Text("Reset Panel")
      }
      Button(
          onClick = {
            onPlacementChanged(
                setPanelHeadlocked(!placement.headlocked, "panel-headlock-toggle")
            )
          }
      ) {
        Text(if (placement.headlocked) "World Lock" else "Head Lock")
      }
    }
  }
}

@Composable
private fun PanelPlacementControls(
    placement: PanelPlacement,
    onAdjust: (Float, Float, Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
) {
  val nearDelta = if (placement.headlocked) -0.08f else 0.12f
  val farDelta = if (placement.headlocked) 0.08f else -0.12f
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (placement.headlocked) {
      Text(
          "Headlocked pose x=${"%.2f".format(placement.xMeters)}m y=${"%.2f".format(placement.yMeters)}m distance=${"%.2f".format(placement.zMeters)}m scale=${"%.2f".format(placement.scale)} size=${"%.2f".format(placement.widthMeters)}m x ${"%.2f".format(placement.heightMeters)}m",
          style = MaterialTheme.typography.bodySmall,
      )
    } else {
      Text(
          "World pose x=${"%.2f".format(placement.xMeters)}m y=${"%.2f".format(placement.yMeters)}m z=${"%.2f".format(placement.zMeters)}m scale=${"%.2f".format(placement.scale)} size=${"%.2f".format(placement.widthMeters)}m x ${"%.2f".format(placement.heightMeters)}m",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onAdjust(0.0f, 0.0f, nearDelta, 0.0f) }) { Text("Near") }
      Button(onClick = { onAdjust(0.0f, 0.0f, farDelta, 0.0f) }) { Text("Far") }
      Button(onClick = { onAdjust(0.0f, 0.08f, 0.0f, 0.0f) }) { Text("Up") }
      Button(onClick = { onAdjust(0.0f, -0.08f, 0.0f, 0.0f) }) { Text("Down") }
      Button(onClick = { onAdjust(-0.08f, 0.0f, 0.0f, 0.0f) }) { Text("Left") }
      Button(onClick = { onAdjust(0.08f, 0.0f, 0.0f, 0.0f) }) { Text("Right") }
      Button(onClick = { onAdjust(0.0f, 0.0f, 0.0f, -0.08f) }) { Text("Scale -") }
      Button(onClick = { onAdjust(0.0f, 0.0f, 0.0f, 0.08f) }) { Text("Scale +") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onResize(-PANEL_RESIZE_STEP_METERS, 0.0f) }) { Text("Narrower") }
      Button(onClick = { onResize(PANEL_RESIZE_STEP_METERS, 0.0f) }) { Text("Wider") }
      Button(onClick = { onResize(0.0f, -PANEL_RESIZE_STEP_METERS) }) { Text("Shorter") }
      Button(onClick = { onResize(0.0f, PANEL_RESIZE_STEP_METERS) }) { Text("Taller") }
    }
  }
}

@Composable
private fun ParticipantStep(
    snapshot: ExperimentSnapshot,
    onBegin: (String) -> Unit,
    onReset: () -> Unit,
) {
  var participantId by remember { mutableStateOf(snapshot.participantId) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Participant Setup", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = participantId,
        onValueChange = { participantId = it },
        label = { Text("participant_id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Driver profile order: ${snapshot.orderSummary}", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(enabled = participantId.isNotBlank(), onClick = { onBegin(participantId) }) {
        Text("Start Setup")
      }
      Button(onClick = onReset) { Text("Reset Order") }
    }
  }
}

@Composable
private fun PolarSetupStep(
    snapshot: ExperimentSnapshot,
    polarPanel: PolarSensorPanel,
    onContinue: (String, String, String) -> Unit,
) {
  var runLabel by remember { mutableStateOf("") }
  var operatorId by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("") }
  var ecgStatus by remember { mutableStateOf(polarPanel.ecgExperimentStatusLine(snapshot.sessionId.isNotBlank())) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Polar Setup", style = MaterialTheme.typography.titleLarge)
    Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
    Text("ECG: $ecgStatus", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
          onClick = {
            polarPanel.handleCommand("start_ecg")
            ecgStatus = polarPanel.ecgExperimentStatusLine(snapshot.sessionId.isNotBlank())
          }
      ) {
        Text("Start ECG")
      }
      Button(onClick = { ecgStatus = polarPanel.ecgExperimentStatusLine(snapshot.sessionId.isNotBlank()) }) {
        Text("Refresh")
      }
    }
    AndroidView(
        factory = { polarPanel.buildView() },
        modifier = Modifier.fillMaxWidth().height(420.dp),
    )
    OutlinedTextField(
        value = runLabel,
        onValueChange = { runLabel = it },
        label = { Text("run_label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = operatorId,
        onValueChange = { operatorId = it },
        label = { Text("operator_id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("notes") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onContinue(runLabel, operatorId, notes) }) { Text("Continue") }
  }
}

@Composable
private fun SurfaceStep(
    snapshot: ExperimentSnapshot,
    onStart: (String) -> Unit,
) {
  var selectedSurface by remember { mutableStateOf(snapshot.surfaceTargetId.ifBlank { "real-hands" }) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Surface", style = MaterialTheme.typography.titleLarge)
    Text("Next driver profile: ${snapshot.currentConditionLabel}", style = MaterialTheme.typography.bodyMedium)
    SpatialCameraPanelStore.SURFACES.forEach { surface ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = selectedSurface == surface.id,
            onClick = { selectedSurface = surface.id },
        )
        Spacer(Modifier.width(8.dp))
        Column {
          Text(surface.label)
          Text(surface.surfaceTarget, style = MaterialTheme.typography.bodySmall)
        }
      }
    }
    Button(onClick = { onStart(selectedSurface) }) { Text("Start Block") }
  }
}

@Composable
private fun RunningStep(snapshot: ExperimentSnapshot, refresh: () -> Unit) {
  val block = snapshot.activeBlock
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Block Running", style = MaterialTheme.typography.titleLarge)
    if (block != null) {
      Text("Block ${block.blockNumber}/${snapshot.conditionCount}: ${block.conditionLabel}")
      Text("Surface: ${block.surfaceLabel}")
      Text("Remaining: ${(block.remainingMs + 999L) / 1000L}s")
    }
    Button(onClick = refresh) { Text("Refresh") }
  }
}

@Composable
private fun ReadyNextStep(snapshot: ExperimentSnapshot, onStart: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Next Block", style = MaterialTheme.typography.titleLarge)
    Text("Next driver profile: ${snapshot.currentConditionLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Surface: ${snapshot.surfaceLabel}", style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onStart) { Text("Start Block") }
  }
}

@Composable
private fun QuestionnaireStep(
    snapshot: ExperimentSnapshot,
    onSubmit: (Int, Int, Int, String, JSONObject) -> Unit,
) {
  var comfort by remember { mutableStateOf(4) }
  var intensity by remember { mutableStateOf(4) }
  var engagement by remember { mutableStateOf(4) }
  var notes by remember { mutableStateOf("") }
  var signatureStrokes by remember { mutableStateOf<List<List<SignaturePoint>>>(emptyList()) }
  var signatureSize by remember { mutableStateOf(IntSize.Zero) }
  val block = snapshot.activeBlock
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Questionnaire", style = MaterialTheme.typography.titleLarge)
    if (block != null) {
      Text("Block ${block.blockNumber}: ${block.conditionLabel}", style = MaterialTheme.typography.bodyMedium)
      Text("Surface: ${block.surfaceLabel}", style = MaterialTheme.typography.bodySmall)
    }
    RatingSlider("Comfort", comfort) { comfort = it }
    RatingSlider("Intensity", intensity) { intensity = it }
    RatingSlider("Engagement", engagement) { engagement = it }
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("notes") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Signature", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    SignaturePad(
        strokes = signatureStrokes,
        onStrokesChange = { signatureStrokes = it },
        onSizeChange = { signatureSize = it },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(onClick = { signatureStrokes = emptyList() }) { Text("Clear signature") }
      Button(
          onClick = {
            onSubmit(
                comfort,
                intensity,
                engagement,
                notes,
                signatureJson(signatureStrokes, signatureSize.width, signatureSize.height),
            )
          }
      ) {
        Text("Submit")
      }
    }
  }
}

@Composable
private fun SignaturePad(
    strokes: List<List<SignaturePoint>>,
    onStrokesChange: (List<List<SignaturePoint>>) -> Unit,
    onSizeChange: (IntSize) -> Unit,
) {
  var activeStroke by remember { mutableStateOf<List<SignaturePoint>>(emptyList()) }
  var padSize by remember { mutableStateOf(IntSize.Zero) }
  var strokeStartMs by remember { mutableStateOf(0L) }

  fun pointAt(offset: Offset): SignaturePoint {
    val width = padSize.width.coerceAtLeast(1).toFloat()
    val height = padSize.height.coerceAtLeast(1).toFloat()
    return SignaturePoint(
        x = (offset.x / width).coerceIn(0.0f, 1.0f),
        y = (offset.y / height).coerceIn(0.0f, 1.0f),
        tMs = (SystemClock.uptimeMillis() - strokeStartMs).coerceAtLeast(0L),
    )
  }

  ComposeBox(
      modifier =
          Modifier.fillMaxWidth()
              .height(180.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFFF6F7FA))
              .border(1.dp, Color(0xFF6F7785), RoundedCornerShape(6.dp))
              .onSizeChanged {
                padSize = it
                onSizeChange(it)
              }
              .pointerInput(padSize) {
                detectDragGestures(
                    onDragStart = { offset ->
                      strokeStartMs = SystemClock.uptimeMillis()
                      activeStroke = listOf(pointAt(offset))
                    },
                    onDrag = { change, _ ->
                      activeStroke = activeStroke + pointAt(change.position)
                    },
                    onDragEnd = {
                      if (activeStroke.isNotEmpty()) {
                        onStrokesChange(strokes + listOf(activeStroke))
                      }
                      activeStroke = emptyList()
                    },
                    onDragCancel = { activeStroke = emptyList() },
                )
              }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      fun drawStroke(stroke: List<SignaturePoint>) {
        if (stroke.isEmpty()) {
          return
        }
        if (stroke.size == 1) {
          val point = stroke.first()
          drawCircle(
              color = Color(0xFF1A1C22),
              radius = 2.0f,
              center = Offset(point.x * size.width, point.y * size.height),
          )
          return
        }
        stroke.zipWithNext().forEach { (from, to) ->
          drawLine(
              color = Color(0xFF1A1C22),
              start = Offset(from.x * size.width, from.y * size.height),
              end = Offset(to.x * size.width, to.y * size.height),
              strokeWidth = 4.0f,
              cap = StrokeCap.Round,
          )
        }
      }
      strokes.forEach { drawStroke(it) }
      drawStroke(activeStroke)
    }
  }
}

private data class SignaturePoint(val x: Float, val y: Float, val tMs: Long)

private fun signatureJson(
    strokes: List<List<SignaturePoint>>,
    widthPx: Int,
    heightPx: Int,
): JSONObject {
  val strokeRows = JSONArray()
  var pointCount = 0
  strokes.forEach { stroke ->
    val points = JSONArray()
    stroke.forEach { point ->
      points.put(
          JSONObject()
              .put("x", point.x.coerceIn(0.0f, 1.0f).toDouble())
              .put("y", point.y.coerceIn(0.0f, 1.0f).toDouble())
              .put("t_ms", point.tMs.coerceAtLeast(0L))
      )
      pointCount += 1
    }
    if (points.length() > 0) {
      strokeRows.put(points)
    }
  }
  return JSONObject()
      .put("format", "stroke-json-v1")
      .put("width_px", widthPx.coerceAtLeast(1))
      .put("height_px", heightPx.coerceAtLeast(1))
      .put("stroke_count", strokeRows.length())
      .put("point_count", pointCount)
      .put("is_empty", pointCount == 0)
      .put("strokes", strokeRows)
}

internal fun emptySignatureJson(): JSONObject =
    JSONObject()
        .put("format", "stroke-json-v1")
        .put("width_px", 0)
        .put("height_px", 0)
        .put("stroke_count", 0)
        .put("point_count", 0)
        .put("is_empty", true)
        .put("strokes", JSONArray())

@Composable
private fun RatingSlider(label: String, value: Int, onChange: (Int) -> Unit) {
  Column {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt().coerceIn(1, 7)) },
        valueRange = 1f..7f,
        steps = 5,
    )
  }
}

@Composable
private fun CompleteStep(snapshot: ExperimentSnapshot, onReset: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Complete", style = MaterialTheme.typography.titleLarge)
    Text(snapshot.filesSummary, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onReset) { Text("New Participant") }
  }
}
