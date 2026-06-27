package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.app.Activity
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.Random
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal class SpatialCameraPanelStore(private val activity: Activity) {
  private var state: JSONObject = loadState()

  fun snapshot(): ExperimentSnapshot {
    syncElapsedBlock()
    val active = state.optJSONObject("active_block")
    val condition = currentCondition()
    return ExperimentSnapshot(
        stage = state.optString("stage", "participant"),
        participantId = state.optString("participant_id", ""),
        sessionId = state.optString("session_id", ""),
        sessionDir = state.optString("session_dir", ""),
        blockDurationMs = state.optLong("block_duration_ms", DEFAULT_BLOCK_DURATION_MS),
        nextBlockIndex = state.optInt("next_block_index", 0),
        conditionCount = conditionOrder().length(),
        orderSummary = orderSummary(),
        filesSummary = filesSummary(),
        surfaceTargetId = surfaceSnapshot().optString("surface_target_id", SURFACES[0].id),
        surfaceLabel = surfaceSnapshot().optString("surface_label", SURFACES[0].label),
        currentConditionId = condition?.optString("condition_id", "none") ?: "none",
        currentConditionLabel = condition?.optString("condition_label", "none") ?: "none",
        currentProfileId = condition?.optString("profile_id", "none") ?: "none",
        activeBlock = active?.toActiveBlock(),
    )
  }

  fun resetForNewParticipant() {
    state = newState()
    save()
    marker("status=session-reset")
  }

  fun recordPanelForegroundState(panelState: String, source: String) {
    val cleanState = safeToken(panelState)
    val cleanSource = safeToken(source)
    state
        .put("panel_state", cleanState)
        .put("panel_state_source", cleanSource)
        .put("panel_state_updated_at_unix_ms", System.currentTimeMillis())
        .put("panel_state_updated_time_utc", Instant.now().toString())
    save()
    appendForegroundEvent("spatial_panel_state_changed", cleanSource)
    marker("status=panel-state-recorded panelState=$cleanState source=$cleanSource")
  }

  fun beginParticipant(participantId: String) {
    val clean = participantId.trim()
    require(clean.isNotEmpty()) { "participant_id_required" }
    val participantKey = safeToken(clean)
    val nowMs = System.currentTimeMillis()
    val sessionId = "${participantKey}_$nowMs"
    val sessionDir = File(rootDir(), sessionId)
    if (!sessionDir.exists() && !sessionDir.mkdirs()) {
      error("create_session_dir_failed")
    }
    state
        .put("participant_id", clean)
        .put("participant_key", participantKey)
        .put("session_id", sessionId)
        .put("session_dir", sessionDir.absolutePath)
        .put("stage", "polar_setup")
        .put("created_at_unix_ms", nowMs)
        .put("created_time_utc", Instant.now().toString())
        .put("next_block_index", 0)
        .put("block_duration_ms", DEFAULT_BLOCK_DURATION_MS)
        .put("metadata", JSONObject())
        .put(
            "files",
            JSONObject()
                .put("manifest", MANIFEST_FILE)
                .put("polar_events", POLAR_EVENTS_FILE)
                .put("ecg_events", ECG_EVENTS_FILE)
                .put("block_events", BLOCK_EVENTS_FILE)
                .put("foreground_events", FOREGROUND_EVENTS_FILE)
                .put("questionnaire", QUESTIONNAIRE_FILE),
        )
        .put("completed_blocks", JSONArray())
    state.remove("active_block")
    ensureSessionFiles()
    save()
    appendBlockEvent("participant_created", null)
    appendForegroundEvent("spatial_panel_participant_setup", "spatial-sdk-panel")
    marker("status=participant-created participantId=${markerToken(clean)} sessionId=$sessionId")
  }

  fun savePolarSetup(runLabel: String, operatorId: String, notes: String) {
    requireParticipant()
    val metadata = state.optJSONObject("metadata") ?: JSONObject().also { state.put("metadata", it) }
    metadata
        .put("run_label", runLabel.trim())
        .put("operator_id", operatorId.trim())
        .put("notes", notes.trim())
        .put("polar_intake_lane", "spatial-sdk-direct-ble-panel")
        .put("live_polar_intake", true)
        .put("updated_at_unix_ms", System.currentTimeMillis())
        .put("updated_time_utc", Instant.now().toString())
    state.put("stage", "surface_setup")
    save()
    appendPolarSetupEvent("polar_setup_recorded")
    appendForegroundEvent("spatial_panel_surface_setup", "polar_setup_continue")
    marker("status=polar-setup-recorded livePolarIntake=true polarIntakeLane=spatial-sdk-direct-ble-panel")
  }

  fun appendPolarEvent(event: JSONObject) {
    if (state.optString("session_id", "").isBlank()) {
      return
    }
    syncElapsedBlock()
    val row = JSONObject(event.toString()).put("spatial_camera_panel_session", experimentEnvelope())
    appendLine(sessionFile(POLAR_EVENTS_FILE), row.toString())
    val stream = row.optString("stream_id", row.optString("stream", ""))
    if (stream == "stream.polar_h10.ecg") {
      appendLine(sessionFile(ECG_EVENTS_FILE), row.toString())
    }
    marker(
        "status=polar-stream-event-recorded streamId=${markerToken(stream)} " +
            "ecgMirrored=${stream == "stream.polar_h10.ecg"}"
    )
  }

  fun selectSurface(surfaceId: String) {
    requireParticipant()
    val surface = SURFACES.firstOrNull { it.id == surfaceId } ?: SURFACES[0]
    state
        .put(
            "surface",
            JSONObject()
                .put("surface_target_id", surface.id)
                .put("surface_label", surface.label)
                .put("surface_target", surface.surfaceTarget)
                .put("resource_plan_id", surface.resourcePlanId)
                .put("runtime_profile_path", surface.runtimeProfilePath)
                .put("recenter_icosphere_on_block_start", surface.id == "icosphere"),
        )
        .put("surface_target_id", surface.id)
        .put("surface_label", surface.label)
    save()
    appendForegroundEvent("experiment_surface_selected", "spatial-sdk-panel")
    marker("status=surface-selected surfaceTargetId=${surface.id}")
  }

  fun prioritizeConditionForValidation(conditionId: String) {
    requireParticipant()
    if (state.optString("stage", "") == "block_running") {
      marker("status=validation-condition-prioritize-skipped reason=block-running")
      return
    }
    val target = conditionId.trim()
    val order = conditionOrder()
    var selected: JSONObject? = null
    for (index in 0 until order.length()) {
      val condition = order.optJSONObject(index) ?: continue
      if (condition.optString("condition_id") == target) {
        selected = JSONObject(condition.toString())
        break
      }
    }
    if (selected == null) {
      marker("status=validation-condition-prioritize-skipped reason=missing-condition conditionId=${markerToken(target)}")
      return
    }

    val reordered = JSONArray()
    selected.put("order_index", 0)
    reordered.put(selected)
    var nextIndex = 1
    for (index in 0 until order.length()) {
      val condition = order.optJSONObject(index) ?: continue
      if (condition.optString("condition_id") == target) {
        continue
      }
      reordered.put(JSONObject(condition.toString()).put("order_index", nextIndex))
      nextIndex += 1
    }
    state.put("condition_order", reordered)
    save()
    appendForegroundEvent("validation_condition_prioritized", "spatial-sdk-self-test")
    marker(
        "status=validation-condition-prioritized conditionId=${markerToken(target)} " +
            "profileId=${markerToken(selected.optString("profile_id"))}"
    )
  }

  fun startNextBlock(): ActiveBlockSnapshot? {
    requireParticipant()
    syncElapsedBlock()
    if (state.optString("stage", "") == "block_running") {
      return state.optJSONObject("active_block")?.toActiveBlock()
    }
    val blockIndex = state.optInt("next_block_index", 0)
    val order = conditionOrder()
    if (blockIndex >= order.length()) {
      state.put("stage", "complete")
      save()
      appendBlockEvent("experiment_complete", null)
      return null
    }
    val condition = order.getJSONObject(blockIndex)
    val surface = surfaceSnapshot()
    val nowMs = System.currentTimeMillis()
    val durationMs = state.optLong("block_duration_ms", DEFAULT_BLOCK_DURATION_MS)
    val block =
        JSONObject()
            .put("block_index", blockIndex)
            .put("block_number", blockIndex + 1)
            .put("condition", condition)
            .put("condition_id", condition.optString("condition_id"))
            .put("condition_label", condition.optString("condition_label"))
            .put("profile_id", condition.optString("profile_id"))
            .put("surface", surface)
            .put("surface_target_id", surface.optString("surface_target_id"))
            .put("surface_label", surface.optString("surface_label"))
            .put("surface_target", surface.optString("surface_target"))
            .put("resource_plan_id", surface.optString("resource_plan_id"))
            .put("runtime_profile_path", surface.optString("runtime_profile_path"))
            .put(
                "recenter_icosphere_on_block_start",
                surface.optBoolean("recenter_icosphere_on_block_start", false),
            )
            .put("duration_ms", durationMs)
            .put("started_at_unix_ms", nowMs)
            .put("deadline_unix_ms", nowMs + durationMs)
            .put("started_time_utc", Instant.now().toString())
            .put("status", "running")
    state.put("active_block", block)
    state.put("stage", "block_running")
    save()
    appendBlockEvent("block_started", block)
    appendForegroundEvent("condition_started", "spatial-sdk-panel")
    marker(
        "status=block-started blockIndex=$blockIndex blockNumber=${blockIndex + 1} " +
            "conditionId=${condition.optString("condition_id")} surfaceTargetId=${surface.optString("surface_target_id")}"
    )
    return block.toActiveBlock()
  }

  fun syncElapsedBlock() {
    if (state.optString("stage", "") != "block_running") {
      return
    }
    val block = state.optJSONObject("active_block") ?: return
    val deadline = block.optLong("deadline_unix_ms", 0L)
    if (deadline <= 0L || System.currentTimeMillis() < deadline) {
      return
    }
    block
        .put("status", "awaiting_questionnaire")
        .put("ended_at_unix_ms", System.currentTimeMillis())
        .put("ended_time_utc", Instant.now().toString())
        .put("end_reason", "duration_elapsed")
    state.put("stage", "questionnaire")
    save()
    appendBlockEvent("block_elapsed", block)
    appendForegroundEvent("questionnaire_due", "duration_elapsed")
    marker(
        "status=block-elapsed blockIndex=${block.optInt("block_index")} " +
            "conditionId=${block.optString("condition_id")} surfaceTargetId=${block.optString("surface_target_id")}"
    )
  }

  fun submitQuestionnaire(
      comfortRating: Int,
      intensityRating: Int,
      engagementRating: Int,
      notes: String,
      signature: JSONObject,
  ) {
    syncElapsedBlock()
    if (state.optString("stage", "") != "questionnaire") {
      error("questionnaire_not_due")
    }
    val block = state.optJSONObject("active_block") ?: error("missing_active_block")
    val nowMs = System.currentTimeMillis()
    val surface = surfaceSnapshot()
    val row =
        JSONObject()
            .put("schema_id", QUESTIONNAIRE_SCHEMA)
            .put("participant_id", state.optString("participant_id"))
            .put("session_id", state.optString("session_id"))
            .put("block_index", block.optInt("block_index", 0))
            .put("block_number", block.optInt("block_number", block.optInt("block_index", 0) + 1))
            .put("condition_id", block.optString("condition_id"))
            .put("condition_label", block.optString("condition_label"))
            .put("profile_id", block.optString("profile_id"))
            .put("surface", surface)
            .put("surface_target_id", surface.optString("surface_target_id"))
            .put("surface_label", surface.optString("surface_label"))
            .put("submitted_at_unix_ms", nowMs)
            .put("submitted_time_utc", Instant.now().toString())
            .put(
                "response",
                JSONObject()
                    .put("comfort_rating_1_to_7", comfortRating.coerceIn(1, 7))
                    .put("intensity_rating_1_to_7", intensityRating.coerceIn(1, 7))
                    .put("engagement_rating_1_to_7", engagementRating.coerceIn(1, 7))
                    .put("notes", notes.trim())
                    .put("signature", JSONObject(signature.toString())),
            )
    appendLine(sessionFile(QUESTIONNAIRE_FILE), row.toString())
    val completed = state.optJSONArray("completed_blocks") ?: JSONArray().also { state.put("completed_blocks", it) }
    block.put("status", "questionnaire_submitted").put("questionnaire_submitted_at_unix_ms", nowMs)
    completed.put(block)
    state.remove("active_block")
    val next = block.optInt("block_index", 0) + 1
    state.put("next_block_index", next)
    state.put("stage", if (next >= conditionOrder().length()) "complete" else "ready_next_block")
    save()
    appendBlockEvent("questionnaire_submitted", block)
    appendForegroundEvent(
        if (next >= conditionOrder().length()) "experiment_complete_foreground" else "next_block_ready",
        "questionnaire_submitted",
    )
    if (next >= conditionOrder().length()) {
      appendBlockEvent("experiment_complete", null)
    }
    marker(
        "status=questionnaire-submitted blockIndex=${block.optInt("block_index")} " +
            "conditionId=${block.optString("condition_id")} surfaceTargetId=${surface.optString("surface_target_id")}"
    )
  }

  private fun loadState(): JSONObject {
    val file = File(activity.filesDir, SESSION_FILE)
    val loaded =
        runCatching {
              if (file.exists()) {
                JSONObject(file.readText(Charsets.UTF_8))
              } else {
                null
              }
            }
            .getOrNull()
    return if (loaded != null && loaded.optString("schema_id") == SESSION_SCHEMA) {
      loaded
    } else {
      newState()
    }
  }

  private fun newState(): JSONObject {
    val seed = System.currentTimeMillis()
    val shuffled = CONDITIONS.indices.toMutableList()
    Collections.shuffle(shuffled, Random(seed))
    val order = JSONArray()
    shuffled.forEachIndexed { orderIndex, conditionIndex ->
      val condition = CONDITIONS[conditionIndex]
      order.put(
          JSONObject()
              .put("order_index", orderIndex)
              .put("condition_id", condition.id)
              .put("condition_label", condition.label)
              .put("profile_id", condition.profileId)
              .put("driver0_value01", condition.driver0Value01)
              .put("driver1_value01", condition.driver1Value01)
      )
    }
    return JSONObject()
        .put("schema_id", SESSION_SCHEMA)
        .put("stage", "participant")
        .put("participant_id", "")
        .put("session_id", "")
        .put("panel_state", "spatial-sdk-workflow-panel-open")
        .put("panel_state_source", "spatial-sdk-default")
        .put("randomization_seed", seed)
        .put("randomized_at_unix_ms", seed)
        .put("randomized_time_utc", Instant.now().toString())
        .put("block_duration_ms", DEFAULT_BLOCK_DURATION_MS)
        .put("condition_order", order)
        .put("next_block_index", 0)
        .put("completed_blocks", JSONArray())
  }

  private fun requireParticipant() {
    require(state.optString("participant_id", "").isNotBlank() && state.optString("session_id", "").isNotBlank()) {
      "participant_required"
    }
  }

  private fun save() {
    ensureRootDir()
    File(activity.filesDir, SESSION_FILE).writeText(state.toString(2), Charsets.UTF_8)
    if (state.optString("session_id", "").isNotBlank()) {
      ensureSessionFiles()
      sessionFile(MANIFEST_FILE).writeText(state.toString(2), Charsets.UTF_8)
    }
  }

  private fun appendBlockEvent(eventType: String, block: JSONObject?) {
    if (state.optString("session_id", "").isBlank()) {
      return
    }
    val row =
        JSONObject()
            .put("schema_id", EVENT_SCHEMA)
            .put("event_type", eventType)
            .put("participant_id", state.optString("participant_id"))
            .put("session_id", state.optString("session_id"))
            .put("stage", state.optString("stage"))
            .put("time_unix_ms", System.currentTimeMillis())
            .put("time_utc", Instant.now().toString())
    if (block != null) {
      row
          .put("block", JSONObject(block.toString()))
          .put("condition_id", block.optString("condition_id"))
          .put("condition_label", block.optString("condition_label"))
          .put("profile_id", block.optString("profile_id"))
    }
    appendLine(sessionFile(BLOCK_EVENTS_FILE), row.toString())
  }

  private fun appendForegroundEvent(eventType: String, source: String) {
    if (state.optString("session_id", "").isBlank()) {
      return
    }
    val block = state.optJSONObject("active_block")
    val row =
        JSONObject()
            .put("schema_id", EVENT_SCHEMA)
            .put("event_type", eventType)
            .put("participant_id", state.optString("participant_id"))
            .put("session_id", state.optString("session_id"))
            .put("stage", state.optString("stage"))
            .put("source", source)
            .put("time_unix_ms", System.currentTimeMillis())
            .put("time_utc", Instant.now().toString())
            .put("foreground", foregroundEnvelope())
    if (block != null) {
      row
          .put("block", JSONObject(block.toString()))
          .put("condition_id", block.optString("condition_id"))
          .put("condition_label", block.optString("condition_label"))
          .put("profile_id", block.optString("profile_id"))
    }
    appendLine(sessionFile(FOREGROUND_EVENTS_FILE), row.toString())
  }

  private fun appendPolarSetupEvent(eventType: String) {
    val row =
        JSONObject()
            .put("schema_id", EVENT_SCHEMA)
            .put("event_type", eventType)
            .put("participant_id", state.optString("participant_id"))
            .put("session_id", state.optString("session_id"))
            .put("stage", state.optString("stage"))
            .put("time_unix_ms", System.currentTimeMillis())
            .put("time_utc", Instant.now().toString())
            .put("stream_id", "stream.polar_h10.device_status")
            .put("event_kind", "spatial_polar_setup")
            .put("live_polar_intake", true)
            .put("lane", "spatial-sdk-direct-ble-panel")
    appendLine(sessionFile(POLAR_EVENTS_FILE), row.toString())
  }

  private fun experimentEnvelope(): JSONObject {
    val envelope =
        JSONObject()
            .put("schema_id", SESSION_SCHEMA)
            .put("participant_id", state.optString("participant_id"))
            .put("session_id", state.optString("session_id"))
            .put("stage", state.optString("stage"))
            .put("next_block_index", state.optInt("next_block_index", 0))
            .put("surface", surfaceSnapshot())
            .put("surface_target_id", surfaceSnapshot().optString("surface_target_id"))
            .put("surface_label", surfaceSnapshot().optString("surface_label"))
            .put("foreground", foregroundEnvelope())
    val block = state.optJSONObject("active_block")
    if (block != null) {
      envelope
          .put("block_index", block.optInt("block_index", -1))
          .put("block_number", block.optInt("block_number", 0))
          .put("condition_id", block.optString("condition_id"))
          .put("condition_label", block.optString("condition_label"))
          .put("profile_id", block.optString("profile_id"))
    } else {
      envelope.put("condition_id", "none").put("condition_label", "none").put("profile_id", "none")
    }
    return envelope
  }

  private fun foregroundEnvelope(): JSONObject {
    val block = state.optJSONObject("active_block")
    val foreground =
        JSONObject()
            .put("stage", state.optString("stage"))
            .put("surface_target_id", surfaceSnapshot().optString("surface_target_id"))
            .put("surface_label", surfaceSnapshot().optString("surface_label"))
            .put("panel_state", state.optString("panel_state", "spatial-sdk-workflow-panel-open"))
            .put("panel_state_source", state.optString("panel_state_source", "spatial-sdk"))
    if (block != null) {
      foreground
          .put("block_index", block.optInt("block_index", -1))
          .put("block_number", block.optInt("block_number", 0))
          .put("condition_id", block.optString("condition_id"))
          .put("condition_label", block.optString("condition_label"))
          .put("profile_id", block.optString("profile_id"))
    }
    return foreground
  }

  private fun surfaceSnapshot(): JSONObject {
    val existing = state.optJSONObject("surface")
    if (existing != null) {
      return JSONObject(existing.toString())
    }
    val surface = SURFACES[0]
    return JSONObject()
        .put("surface_target_id", surface.id)
        .put("surface_label", surface.label)
        .put("surface_target", surface.surfaceTarget)
        .put("resource_plan_id", surface.resourcePlanId)
        .put("runtime_profile_path", surface.runtimeProfilePath)
        .put("recenter_icosphere_on_block_start", false)
  }

  private fun currentCondition(): JSONObject? {
    state.optJSONObject("active_block")?.optJSONObject("condition")?.let { return it }
    val order = conditionOrder()
    val index = state.optInt("next_block_index", 0)
    return if (index >= 0 && index < order.length()) order.optJSONObject(index) else null
  }

  private fun conditionOrder(): JSONArray = state.optJSONArray("condition_order") ?: JSONArray()

  private fun orderSummary(): String {
    val order = conditionOrder()
    return (0 until order.length()).joinToString(" -> ") { index ->
      val condition = order.optJSONObject(index)
      "${index + 1}:${condition?.optString("condition_id", "none") ?: "none"}"
    }
  }

  private fun filesSummary(): String {
    val sessionDir = state.optString("session_dir", "")
    return if (sessionDir.isBlank()) {
      "No participant files yet."
    } else {
      "Session files: $sessionDir"
    }
  }

  private fun ensureRootDir() {
    val root = rootDir()
    if (!root.exists() && !root.mkdirs()) {
      error("create_experiment_root_failed")
    }
  }

  private fun ensureSessionFiles() {
    ensureRootDir()
    val sessionDir = File(state.optString("session_dir", ""))
    if (!sessionDir.exists() && !sessionDir.mkdirs()) {
      error("create_session_dir_failed")
    }
    listOf(POLAR_EVENTS_FILE, ECG_EVENTS_FILE, BLOCK_EVENTS_FILE, FOREGROUND_EVENTS_FILE, QUESTIONNAIRE_FILE)
        .forEach { fileName ->
          val file = File(sessionDir, fileName)
          if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("", Charsets.UTF_8)
          }
        }
  }

  private fun rootDir(): File = File(activity.filesDir, ROOT_DIR)

  private fun sessionFile(name: String): File = File(state.optString("session_dir", ""), name)

  private fun appendLine(file: File, line: String) {
    file.parentFile?.mkdirs()
    file.appendText(line + "\n", Charsets.UTF_8)
  }

  private fun JSONObject.toActiveBlock(): ActiveBlockSnapshot {
    val now = System.currentTimeMillis()
    val remaining = max(0L, optLong("deadline_unix_ms", now) - now)
    val condition = optJSONObject("condition")
    return ActiveBlockSnapshot(
        blockIndex = optInt("block_index", 0),
        blockNumber = optInt("block_number", optInt("block_index", 0) + 1),
        conditionId = optString("condition_id", "none"),
        conditionLabel = optString("condition_label", "none"),
        profileId = optString("profile_id", "none"),
        driver0Value01 = condition?.optDouble("driver0_value01", 0.0) ?: 0.0,
        driver1Value01 = condition?.optDouble("driver1_value01", 0.0) ?: 0.0,
        surfaceTargetId = optString("surface_target_id", SURFACES[0].id),
        surfaceLabel = optString("surface_label", SURFACES[0].label),
        deadlineUnixMs = optLong("deadline_unix_ms", 0L),
        remainingMs = remaining,
    )
  }

  companion object {
    const val SESSION_FILE = "spatial_camera_panel_session.json"
    const val SESSION_SCHEMA = "rusty.quest.spatial_camera_panel.session.v1"
    const val EVENT_SCHEMA = "rusty.quest.spatial_camera_panel.event.v1"
    const val QUESTIONNAIRE_SCHEMA = "rusty.quest.spatial_camera_panel.questionnaire.v1"
    const val DEFAULT_BLOCK_DURATION_MS = 10_000L
    private const val ROOT_DIR = "spatial_camera_panel_session"
    private const val MANIFEST_FILE = "session_manifest.json"
    private const val POLAR_EVENTS_FILE = "polar_events.jsonl"
    private const val ECG_EVENTS_FILE = "ecg_events.jsonl"
    private const val BLOCK_EVENTS_FILE = "block_events.jsonl"
    private const val FOREGROUND_EVENTS_FILE = "foreground_events.jsonl"
    private const val QUESTIONNAIRE_FILE = "questionnaire_results.jsonl"
    private const val TAG = "RQSpatialCameraPanel"
    private const val MARKER_PREFIX = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL"

    val SURFACES =
        listOf(
            SurfaceTarget(
                id = "real-hands",
                label = "Real hands",
                surfaceTarget = "quest-live-hand-mesh",
                resourcePlanId = "rusty.quest.spatial_camera_panel.live-hands.1024.solid-black.resource-plan.v1",
                runtimeProfilePath = "",
            ),
            SurfaceTarget(
                id = "gpu-replay-hands",
                label = "GPU replay hands",
                surfaceTarget = "quest-recorded-gpu-hand-mesh",
                resourcePlanId = "rusty.quest.spatial_camera_panel.left.1024.solid-black.resource-plan.v1",
                runtimeProfilePath = "",
            ),
            SurfaceTarget(
                id = "icosphere",
                label = "Icosphere",
                surfaceTarget = "static-icosphere-l4",
                resourcePlanId = "rusty.quest.spatial_camera_panel.icosphere-l4.solid-black.resource-plan.v1",
                runtimeProfilePath = "fixtures/native-gpu/quest-native-renderer-spatial-camera-panel-icosphere-l4-solid-black.profile.json",
            ),
        )

    private val CONDITIONS =
        listOf(
            Condition(
                id = "profile-a",
                label = "Driver profile A",
                profileId = "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1",
                driver0Value01 = 0.25,
                driver1Value01 = 0.15,
            ),
            Condition(
                id = "profile-b",
                label = "Driver profile B",
                profileId = "rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1",
                driver0Value01 = 0.85,
                driver1Value01 = 0.15,
            ),
            Condition(
                id = "profile-c",
                label = "Driver profile C",
                profileId = "rusty.quest.spatial_camera_panel.driver_profile.profile-c.v1",
                driver0Value01 = 0.25,
                driver1Value01 = 0.85,
            ),
            Condition(
                id = "profile-d",
                label = "Driver profile D",
                profileId = "rusty.quest.spatial_camera_panel.driver_profile.profile-d.v1",
                driver0Value01 = 0.85,
                driver1Value01 = 0.85,
            ),
        )

    private fun marker(detail: String) {
      Log.i(TAG, "$MARKER_PREFIX channel=experiment $detail")
    }

    private fun markerToken(value: String): String =
        value
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "none" }

    private fun safeToken(value: String): String {
      val token =
          value
              .trim()
              .replace(Regex("[^A-Za-z0-9._-]+"), "_")
              .replace(Regex("_+"), "_")
      return token.ifBlank { "participant" }.take(48)
    }
  }
}

internal data class ExperimentSnapshot(
    val stage: String,
    val participantId: String,
    val sessionId: String,
    val sessionDir: String,
    val blockDurationMs: Long,
    val nextBlockIndex: Int,
    val conditionCount: Int,
    val orderSummary: String,
    val filesSummary: String,
    val surfaceTargetId: String,
    val surfaceLabel: String,
    val currentConditionId: String,
    val currentConditionLabel: String,
    val currentProfileId: String,
    val activeBlock: ActiveBlockSnapshot?,
)

internal data class ActiveBlockSnapshot(
    val blockIndex: Int,
    val blockNumber: Int,
    val conditionId: String,
    val conditionLabel: String,
    val profileId: String,
    val driver0Value01: Double,
    val driver1Value01: Double,
    val surfaceTargetId: String,
    val surfaceLabel: String,
    val deadlineUnixMs: Long,
    val remainingMs: Long,
)

internal data class SurfaceTarget(
    val id: String,
    val label: String,
    val surfaceTarget: String,
    val resourcePlanId: String,
    val runtimeProfilePath: String,
)

private data class Condition(
    val id: String,
    val label: String,
    val profileId: String,
    val driver0Value01: Double,
    val driver1Value01: Double,
)
