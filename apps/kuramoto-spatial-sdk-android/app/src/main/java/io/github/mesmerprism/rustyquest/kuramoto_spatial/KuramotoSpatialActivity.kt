package io.github.mesmerprism.rustyquest.kuramoto_spatial

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.delay

class KuramotoSpatialActivity : AppSystemActivity() {
  private val store: KuramotoExperimentStore by lazy(LazyThreadSafetyMode.NONE) {
    KuramotoExperimentStore(this)
  }
  private var panelEntity: Entity? = null
  private var panelPlacement = PanelPlacement()

  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(VRFeature(this), ComposeFeature())
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    marker(
        "channel=activity status=created package=io.github.mesmerprism.rustyquest.kuramoto_spatial " +
            "highRateJsonPayload=false hand_rendering_expected=false"
    )
    runValidationWorkflowIfRequested(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    runValidationWorkflowIfRequested(intent)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.18f, 0.18f, 0.18f),
        sunColor = Vector3(2.0f, 2.0f, 2.0f),
        sunDirection = -Vector3(0.5f, 2.0f, -1.0f),
        environmentIntensity = 0.2f,
    )
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    panelEntity =
        Entity.createPanelEntity(
            R.id.kuramoto_experiment_panel,
            Transform(Pose(Vector3(0.0f, panelPlacement.yMeters, panelPlacement.zMeters))),
        )
    applyPanelPlacement()
    marker(
    "channel=spatial-panel status=spawned panelRegistrationId=kuramoto_experiment_panel " +
            "panelY=${panelPlacement.yMeters} panelZ=${panelPlacement.zMeters} panelScale=${panelPlacement.scale}"
    )
  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        ComposeViewPanelRegistration(
            R.id.kuramoto_experiment_panel,
            composeViewCreator = { _, context ->
              ComposeView(context).apply {
                setContent {
                  MaterialTheme {
                    KuramotoExperimentPanel(
                        store = store,
                        placement = panelPlacement,
                        adjustPlacement = { dy, dz, scaleDelta ->
                          adjustPanelPlacement(dy, dz, scaleDelta)
                        },
                    )
                  }
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
              )
            },
        )
    )
  }

  private fun adjustPanelPlacement(deltaY: Float, deltaZ: Float, deltaScale: Float): PanelPlacement {
    panelPlacement =
        panelPlacement.copy(
            yMeters = (panelPlacement.yMeters + deltaY).coerceIn(0.8f, 2.2f),
            zMeters = (panelPlacement.zMeters + deltaZ).coerceIn(-3.2f, -0.8f),
            scale = (panelPlacement.scale + deltaScale).coerceIn(0.65f, 1.6f),
        )
    applyPanelPlacement()
    marker(
        "channel=spatial-panel status=placement-updated panelY=${panelPlacement.yMeters} " +
            "panelZ=${panelPlacement.zMeters} panelScale=${panelPlacement.scale}"
    )
    return panelPlacement
  }

  private fun applyPanelPlacement() {
    val entity = panelEntity ?: return
    entity.setComponent(Transform(Pose(Vector3(0.0f, panelPlacement.yMeters, panelPlacement.zMeters))))
    entity.setComponent(Scale(Vector3(panelPlacement.scale, panelPlacement.scale, panelPlacement.scale)))
  }

  private fun marker(detail: String) {
    Log.i(TAG, "$MARKER_PREFIX $detail")
  }

  private fun runValidationWorkflowIfRequested(intent: Intent?) {
    if (intent?.action != ACTION_RUN_WORKFLOW_SELF_TEST) {
      return
    }
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: "codex-spatial-sdk-validation"
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: "real-hands"

    marker(
        "channel=validation status=self-test-start participantId=${markerToken(participantId)} " +
            "surfaceTargetId=${markerToken(surfaceTargetId)}"
    )
    try {
      store.resetForNewParticipant()
      store.beginParticipant(participantId)
      store.savePolarSetup(
          runLabel = "headset-self-test",
          operatorId = "codex",
          notes = "Meta Spatial SDK validation intent",
      )
      store.selectSurface(surfaceTargetId)
      store.startNextBlock()
      marker(
          "channel=validation status=self-test-block-started participantId=${markerToken(participantId)} " +
              "surfaceTargetId=${markerToken(surfaceTargetId)}"
      )
      Handler(Looper.getMainLooper())
          .postDelayed(
              {
                try {
                  store.syncElapsedBlock()
                  store.submitQuestionnaire(
                      comfortRating = 4,
                      intensityRating = 4,
                      engagementRating = 4,
                      notes = "Codex headset validation self-test",
                  )
                  marker(
                      "channel=validation status=self-test-complete participantId=${markerToken(participantId)} " +
                          "surfaceTargetId=${markerToken(surfaceTargetId)}"
                  )
                } catch (throwable: Throwable) {
                  marker("channel=validation status=self-test-failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
                  Log.e(TAG, "Kuramoto Spatial SDK validation workflow failed", throwable)
                }
              },
              KuramotoExperimentStore.DEFAULT_BLOCK_DURATION_MS + 750L,
          )
    } catch (throwable: Throwable) {
      marker("channel=validation status=self-test-failed error=${markerToken(throwable.message ?: throwable.javaClass.simpleName)}")
      Log.e(TAG, "Kuramoto Spatial SDK validation workflow failed", throwable)
    }
  }

  private fun markerToken(value: String): String =
      value
          .trim()
          .replace(Regex("[^A-Za-z0-9._-]+"), "_")
          .ifBlank { "none" }
          .take(96)

  companion object {
    private const val TAG = "RQKuramotoSpatial"
    private const val MARKER_PREFIX = "RUSTY_QUEST_KURAMOTO_SPATIAL"
    private const val ACTION_RUN_WORKFLOW_SELF_TEST =
        "io.github.mesmerprism.rustyquest.kuramoto_spatial.action.RUN_WORKFLOW_SELF_TEST"
    private const val EXTRA_PARTICIPANT_ID = "participant_id"
    private const val EXTRA_SURFACE_TARGET_ID = "surface_target_id"
    private const val PANEL_WIDTH_METERS = 1.24f
    private const val PANEL_HEIGHT_METERS = 0.86f
    private const val PANEL_DP_PER_METER = 720f
  }
}

data class PanelPlacement(
    val yMeters: Float = 1.1f,
    val zMeters: Float = -1.7f,
    val scale: Float = 1.0f,
)

@Composable
private fun KuramotoExperimentPanel(
    store: KuramotoExperimentStore,
    placement: PanelPlacement,
    adjustPlacement: (Float, Float, Float) -> PanelPlacement,
) {
  var snapshot by remember { mutableStateOf(store.snapshot()) }
  var localPlacement by remember { mutableStateOf(placement) }

  LaunchedEffect(snapshot.stage, snapshot.activeBlock?.deadlineUnixMs) {
    while (snapshot.stage == "block_running") {
      delay(500L)
      store.syncElapsedBlock()
      snapshot = store.snapshot()
    }
  }

  Surface(
      modifier = Modifier.fillMaxSize(),
      color = Color(0xFFF8FAFC),
      contentColor = Color(0xFF111827),
  ) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Header(snapshot)
      PanelPlacementControls(localPlacement) { dy, dz, ds ->
        localPlacement = adjustPlacement(dy, dz, ds)
      }
      HorizontalDivider()
      when (snapshot.stage) {
        "participant" ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  snapshot = store.snapshot()
                },
                onReset = {
                  store.resetForNewParticipant()
                  snapshot = store.snapshot()
                },
            )
        "polar_setup" ->
            PolarSetupStep(
                snapshot = snapshot,
                onContinue = { runLabel, operatorId, notes ->
                  store.savePolarSetup(runLabel, operatorId, notes)
                  snapshot = store.snapshot()
                },
            )
        "surface_setup" ->
            SurfaceStep(
                snapshot = snapshot,
                onStart = { surfaceId ->
                  store.selectSurface(surfaceId)
                  store.startNextBlock()
                  snapshot = store.snapshot()
                },
            )
        "block_running" ->
            RunningStep(snapshot) {
              store.syncElapsedBlock()
              snapshot = store.snapshot()
            }
        "questionnaire" ->
            QuestionnaireStep(snapshot) { comfort, intensity, engagement, notes ->
              store.submitQuestionnaire(comfort, intensity, engagement, notes)
              snapshot = store.snapshot()
            }
        "ready_next_block" ->
            ReadyNextStep(snapshot) {
              store.startNextBlock()
              snapshot = store.snapshot()
            }
        "complete" ->
            CompleteStep(snapshot) {
              store.resetForNewParticipant()
              snapshot = store.snapshot()
            }
        else ->
            ParticipantStep(
                snapshot = snapshot,
                onBegin = { participantId ->
                  store.beginParticipant(participantId)
                  snapshot = store.snapshot()
                },
                onReset = {
                  store.resetForNewParticipant()
                  snapshot = store.snapshot()
                },
            )
      }
    }
  }
}

@Composable
private fun Header(snapshot: ExperimentSnapshot) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Kuramoto Experiment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Stage: ${snapshot.stage}", style = MaterialTheme.typography.bodyMedium)
    if (snapshot.sessionId.isNotBlank()) {
      Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
      Text("Session: ${snapshot.sessionId}", style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun PanelPlacementControls(
    placement: PanelPlacement,
    onAdjust: (Float, Float, Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        "Panel pose y=${"%.2f".format(placement.yMeters)}m z=${"%.2f".format(placement.zMeters)}m scale=${"%.2f".format(placement.scale)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onAdjust(0.0f, 0.12f, 0.0f) }) { Text("Near") }
      Button(onClick = { onAdjust(0.0f, -0.12f, 0.0f) }) { Text("Far") }
      Button(onClick = { onAdjust(0.08f, 0.0f, 0.0f) }) { Text("Up") }
      Button(onClick = { onAdjust(-0.08f, 0.0f, 0.0f) }) { Text("Down") }
      Button(onClick = { onAdjust(0.0f, 0.0f, -0.08f) }) { Text("Scale -") }
      Button(onClick = { onAdjust(0.0f, 0.0f, 0.08f) }) { Text("Scale +") }
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
    Text("Condition order: ${snapshot.orderSummary}", style = MaterialTheme.typography.bodySmall)
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
    onContinue: (String, String, String) -> Unit,
) {
  var runLabel by remember { mutableStateOf("") }
  var operatorId by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Polar Setup", style = MaterialTheme.typography.titleLarge)
    Text("Participant: ${snapshot.participantId}", style = MaterialTheme.typography.bodyMedium)
    Text("Live Polar intake remains in the native APK for this first Spatial SDK lane.", style = MaterialTheme.typography.bodySmall)
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
    Text("Next condition: ${snapshot.currentConditionLabel}", style = MaterialTheme.typography.bodyMedium)
    KuramotoExperimentStore.SURFACES.forEach { surface ->
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
    Text("Next: ${snapshot.currentConditionLabel}", style = MaterialTheme.typography.bodyMedium)
    Text("Surface: ${snapshot.surfaceLabel}", style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onStart) { Text("Start Block") }
  }
}

@Composable
private fun QuestionnaireStep(
    snapshot: ExperimentSnapshot,
    onSubmit: (Int, Int, Int, String) -> Unit,
) {
  var comfort by remember { mutableStateOf(4) }
  var intensity by remember { mutableStateOf(4) }
  var engagement by remember { mutableStateOf(4) }
  var notes by remember { mutableStateOf("") }
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
    Button(onClick = { onSubmit(comfort, intensity, engagement, notes) }) { Text("Submit") }
  }
}

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
