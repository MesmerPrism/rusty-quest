package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

final class DriverProfileSession {
    static final String SESSION_FILE = "spatial_camera_panel_session.json";
    static final String SESSION_SCHEMA = "rusty.quest.spatial_camera_panel.session.v1";
    static final String EVENT_SCHEMA = "rusty.quest.spatial_camera_panel.event.v1";
    static final String QUESTIONNAIRE_SCHEMA = "rusty.quest.spatial_camera_panel.questionnaire.v1";
    static final long DEFAULT_BLOCK_DURATION_MS = 10000L;

    private static final String ROOT_DIR = "spatial_camera_panel_session";
    private static final String MANIFEST_FILE = "session_manifest.json";
    private static final String POLAR_EVENTS_FILE = "polar_events.jsonl";
    private static final String ECG_EVENTS_FILE = "ecg_events.jsonl";
    private static final String BLOCK_EVENTS_FILE = "block_events.jsonl";
    private static final String FOREGROUND_EVENTS_FILE = "foreground_events.jsonl";
    private static final String QUESTIONNAIRE_FILE = "questionnaire_results.jsonl";

    private final Activity activity;
    private final Condition[] conditions;
    private JSONObject state;

    static final class Condition {
        final String id;
        final String label;
        final String profileId;
        final double driver0Value01;
        final double driver1Value01;

        Condition(String id, String label, String profileId, double driver0Value01, double driver1Value01) {
            this.id = id;
            this.label = label;
            this.profileId = profileId;
            this.driver0Value01 = driver0Value01;
            this.driver1Value01 = driver1Value01;
        }

        JSONObject toJson(int orderIndex) throws Exception {
            return new JSONObject()
                .put("order_index", orderIndex)
                .put("condition_id", id)
                .put("condition_label", label)
                .put("profile_id", profileId)
                .put("driver0_value01", driver0Value01)
                .put("driver1_value01", driver1Value01);
        }
    }

    private DriverProfileSession(Activity activity, Condition[] conditions, JSONObject state) {
        this.activity = activity;
        this.conditions = conditions;
        this.state = state;
    }

    static DriverProfileSession load(Activity activity, Condition[] conditions) {
        JSONObject state = null;
        try {
            state = new JSONObject(readActivityFile(activity, SESSION_FILE));
        } catch (Exception ignored) {
        }
        if (state == null || !SESSION_SCHEMA.equals(state.optString("schema_id", ""))) {
            state = newState(conditions);
        }
        return new DriverProfileSession(activity, conditions, state);
    }

    void resetForNewParticipant() throws Exception {
        state = newState(conditions);
        save();
    }

    boolean hasParticipant() {
        return state.optString("participant_id", "").trim().length() > 0
            && state.optString("session_id", "").trim().length() > 0;
    }

    boolean isComplete() {
        return "complete".equals(stage());
    }

    boolean isAwaitingQuestionnaire() {
        syncElapsedBlock();
        return "questionnaire".equals(stage());
    }

    boolean isBlockRunning() {
        syncElapsedBlock();
        return "block_running".equals(stage());
    }

    String stage() {
        return state.optString("stage", "participant");
    }

    String participantId() {
        return state.optString("participant_id", "");
    }

    String sessionId() {
        return state.optString("session_id", "");
    }

    long blockDurationMs() {
        return state.optLong("block_duration_ms", DEFAULT_BLOCK_DURATION_MS);
    }

    int nextBlockIndex() {
        return state.optInt("next_block_index", 0);
    }

    int conditionCount() {
        return state.optJSONArray("condition_order") == null ? conditions.length : state.optJSONArray("condition_order").length();
    }

    JSONObject activeBlock() {
        return state.optJSONObject("active_block");
    }

    JSONObject currentConditionOrNull() {
        JSONObject active = activeBlock();
        if (active != null) {
            JSONObject condition = active.optJSONObject("condition");
            if (condition != null) {
                return condition;
            }
        }
        JSONArray order = state.optJSONArray("condition_order");
        int index = nextBlockIndex();
        if (order != null && index >= 0 && index < order.length()) {
            return order.optJSONObject(index);
        }
        return null;
    }

    String sessionDirectoryPath() {
        return state.optString("session_dir", "");
    }

    String surfaceTargetId() {
        JSONObject surface = state.optJSONObject("surface");
        if (surface != null) {
            return surface.optString("surface_target_id", "real-hands");
        }
        return state.optString("surface_target_id", "real-hands");
    }

    String surfaceLabel() {
        JSONObject surface = state.optJSONObject("surface");
        if (surface != null) {
            return surface.optString("surface_label", surfaceTargetId());
        }
        return state.optString("surface_label", surfaceTargetId());
    }

    String filesSummary() {
        JSONObject files = state.optJSONObject("files");
        if (files == null) {
            return "No participant files yet.";
        }
        return "Manifest: " + files.optString("manifest", MANIFEST_FILE)
            + " | ECG: " + files.optString("ecg_events", ECG_EVENTS_FILE)
            + " | Foreground: " + files.optString("foreground_events", FOREGROUND_EVENTS_FILE)
            + " | Questionnaire: " + files.optString("questionnaire", QUESTIONNAIRE_FILE);
    }

    String orderSummary() {
        JSONArray order = state.optJSONArray("condition_order");
        if (order == null || order.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < order.length(); i++) {
            JSONObject condition = order.optJSONObject(i);
            if (condition == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" -> ");
            }
            builder.append(i + 1).append(": ").append(condition.optString("condition_id"));
        }
        return builder.toString();
    }

    JSONObject beginParticipant(String requestedParticipantId) throws Exception {
        String participantId = requestedParticipantId == null ? "" : requestedParticipantId.trim();
        if (participantId.length() == 0) {
            throw new IllegalArgumentException("participant_id_required");
        }
        String participantKey = safeToken(participantId);
        long nowMs = System.currentTimeMillis();
        String sessionId = participantKey + "_" + nowMs;
        File sessionDir = new File(new File(activity.getFilesDir(), ROOT_DIR), sessionId);
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IllegalStateException("create_session_dir_failed");
        }
        state.put("participant_id", participantId)
            .put("participant_key", participantKey)
            .put("session_id", sessionId)
            .put("session_dir", sessionDir.getAbsolutePath())
            .put("stage", "polar_setup")
            .put("created_at_unix_ms", nowMs)
            .put("created_time_utc", Instant.now().toString())
            .put("next_block_index", 0)
            .put("block_duration_ms", DEFAULT_BLOCK_DURATION_MS)
            .put("metadata", new JSONObject())
            .put("files", new JSONObject()
                .put("manifest", MANIFEST_FILE)
                .put("polar_events", POLAR_EVENTS_FILE)
                .put("ecg_events", ECG_EVENTS_FILE)
                .put("block_events", BLOCK_EVENTS_FILE)
                .put("foreground_events", FOREGROUND_EVENTS_FILE)
                .put("questionnaire", QUESTIONNAIRE_FILE));
        state.remove("active_block");
        state.put("completed_blocks", new JSONArray());
        ensureSessionFiles();
        save();
        appendBlockEvent("participant_created", null);
        recordPanelEvent("participant_panel_active", "open", "begin_participant");
        return state;
    }

    void saveRunMetadata(String runLabel, String operatorId, String notes) throws Exception {
        JSONObject metadata = state.optJSONObject("metadata");
        if (metadata == null) {
            metadata = new JSONObject();
            state.put("metadata", metadata);
        }
        metadata.put("run_label", cleanString(runLabel))
            .put("operator_id", cleanString(operatorId))
            .put("notes", cleanString(notes))
            .put("updated_at_unix_ms", System.currentTimeMillis())
            .put("updated_time_utc", Instant.now().toString());
        save();
    }

    void setSurfaceTarget(
        String surfaceTargetId,
        String surfaceLabel,
        String surfaceTarget,
        String resourcePlanId,
        String runtimeProfilePath
    ) throws Exception {
        if (!hasParticipant()) {
            throw new IllegalStateException("participant_required");
        }
        JSONObject surface = new JSONObject()
            .put("surface_target_id", cleanString(surfaceTargetId).length() == 0 ? "real-hands" : cleanString(surfaceTargetId))
            .put("surface_label", cleanString(surfaceLabel))
            .put("surface_target", cleanString(surfaceTarget))
            .put("resource_plan_id", cleanString(resourcePlanId))
            .put("runtime_profile_path", cleanString(runtimeProfilePath))
            .put("recenter_icosphere_on_block_start", "icosphere".equals(cleanString(surfaceTargetId)));
        state.put("surface", surface)
            .put("surface_target_id", surface.optString("surface_target_id"))
            .put("surface_label", surface.optString("surface_label"));
        save();
        appendForegroundEvent("experiment_surface_selected", "participant_setup");
    }

    JSONObject startNextBlock() throws Exception {
        if (!hasParticipant()) {
            throw new IllegalStateException("participant_required");
        }
        syncElapsedBlock();
        if ("block_running".equals(stage())) {
            return activeBlock();
        }
        int blockIndex = nextBlockIndex();
        JSONArray order = state.optJSONArray("condition_order");
        if (order == null || blockIndex >= order.length()) {
            state.put("stage", "complete");
            save();
            appendBlockEvent("experiment_complete", null);
            return null;
        }
        JSONObject condition = order.getJSONObject(blockIndex);
        JSONObject surface = selectedSurfaceSnapshot();
        long nowMs = System.currentTimeMillis();
        long durationMs = blockDurationMs();
        JSONObject block = new JSONObject()
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
            .put("recenter_icosphere_on_block_start", surface.optBoolean("recenter_icosphere_on_block_start", false))
            .put("duration_ms", durationMs)
            .put("started_at_unix_ms", nowMs)
            .put("deadline_unix_ms", nowMs + durationMs)
            .put("started_time_utc", Instant.now().toString())
            .put("status", "running");
        state.put("active_block", block);
        state.put("stage", "block_running");
        save();
        appendBlockEvent("block_started", block);
        appendForegroundEvent("condition_started", "start_next_block");
        return block;
    }

    void syncElapsedBlock() {
        if (!"block_running".equals(stage())) {
            return;
        }
        JSONObject block = state.optJSONObject("active_block");
        if (block == null) {
            return;
        }
        long deadline = block.optLong("deadline_unix_ms", 0L);
        if (deadline <= 0L || System.currentTimeMillis() < deadline) {
            return;
        }
        try {
            block.put("status", "awaiting_questionnaire")
                .put("ended_at_unix_ms", System.currentTimeMillis())
                .put("ended_time_utc", Instant.now().toString())
                .put("end_reason", "duration_elapsed");
            state.put("stage", "questionnaire");
            save();
            appendBlockEvent("block_elapsed", block);
            appendForegroundEvent("questionnaire_due", "duration_elapsed");
        } catch (Exception ignored) {
        }
    }

    JSONObject submitQuestionnaire(
        int comfortRating,
        int intensityRating,
        int engagementRating,
        String notes,
        JSONObject signature
    )
        throws Exception {
        syncElapsedBlock();
        if (!"questionnaire".equals(stage())) {
            throw new IllegalStateException("questionnaire_not_due");
        }
        JSONObject block = activeBlock();
        if (block == null) {
            throw new IllegalStateException("missing_active_block");
        }
        long nowMs = System.currentTimeMillis();
        JSONObject row = new JSONObject()
            .put("schema_id", QUESTIONNAIRE_SCHEMA)
            .put("participant_id", participantId())
            .put("session_id", sessionId())
            .put("block_index", block.optInt("block_index", 0))
            .put("block_number", block.optInt("block_number", block.optInt("block_index", 0) + 1))
            .put("condition_id", block.optString("condition_id"))
            .put("condition_label", block.optString("condition_label"))
            .put("profile_id", block.optString("profile_id"))
            .put("surface", selectedSurfaceSnapshot())
            .put("surface_target_id", surfaceTargetId())
            .put("surface_label", surfaceLabel())
            .put("submitted_at_unix_ms", nowMs)
            .put("submitted_time_utc", Instant.now().toString())
            .put("response", new JSONObject()
                .put("comfort_rating_1_to_7", comfortRating)
                .put("intensity_rating_1_to_7", intensityRating)
                .put("engagement_rating_1_to_7", engagementRating)
                .put("notes", cleanString(notes))
                .put("signature", signature == null ? emptySignature() : new JSONObject(signature.toString())));
        appendLine(sessionFile(QUESTIONNAIRE_FILE), row.toString());
        JSONArray completed = state.optJSONArray("completed_blocks");
        if (completed == null) {
            completed = new JSONArray();
            state.put("completed_blocks", completed);
        }
        block.put("status", "questionnaire_submitted")
            .put("questionnaire_submitted_at_unix_ms", nowMs);
        completed.put(block);
        state.remove("active_block");
        int next = block.optInt("block_index", 0) + 1;
        state.put("next_block_index", next);
        state.put("stage", next >= conditionCount() ? "complete" : "ready_next_block");
        save();
        appendBlockEvent("questionnaire_submitted", block);
        appendForegroundEvent(next >= conditionCount() ? "experiment_complete_foreground" : "next_block_ready", "questionnaire_submitted");
        if (next >= conditionCount()) {
            appendBlockEvent("experiment_complete", null);
        }
        return row;
    }

    private static JSONObject emptySignature() throws Exception {
        return new JSONObject()
            .put("format", "stroke-json-v1")
            .put("width_px", 0)
            .put("height_px", 0)
            .put("stroke_count", 0)
            .put("point_count", 0)
            .put("is_empty", true)
            .put("strokes", new JSONArray());
    }

    void appendPolarEvent(JSONObject event) {
        if (!hasParticipant() || event == null) {
            return;
        }
        try {
            syncElapsedBlock();
            JSONObject row = new JSONObject(event.toString());
            row.put("spatial_camera_panel_session", experimentEnvelope());
            appendLine(sessionFile(POLAR_EVENTS_FILE), row.toString());
            String stream = row.optString("stream_id", row.optString("stream", ""));
            if ("stream.polar_h10.ecg".equals(stream)) {
                appendLine(sessionFile(ECG_EVENTS_FILE), row.toString());
            }
        } catch (Exception ignored) {
        }
    }

    JSONObject experimentEnvelope() throws Exception {
        JSONObject envelope = new JSONObject()
            .put("schema_id", SESSION_SCHEMA)
            .put("participant_id", participantId())
            .put("session_id", sessionId())
            .put("stage", stage())
            .put("next_block_index", nextBlockIndex())
            .put("surface", selectedSurfaceSnapshot())
            .put("surface_target_id", surfaceTargetId())
            .put("surface_label", surfaceLabel())
            .put("foreground", foregroundEnvelope());
        JSONObject block = activeBlock();
        if (block != null) {
            envelope.put("block_index", block.optInt("block_index", -1))
                .put("block_number", block.optInt("block_number", 0))
                .put("condition_id", block.optString("condition_id"))
                .put("condition_label", block.optString("condition_label"))
                .put("profile_id", block.optString("profile_id"));
        } else {
            envelope.put("condition_id", "none")
                .put("condition_label", "none")
                .put("profile_id", "none");
        }
        return envelope;
    }

    void recordPanelEvent(String eventType, String panelState, String source) {
        if (!hasParticipant()) {
            return;
        }
        try {
            syncElapsedBlock();
            long nowMs = System.currentTimeMillis();
            state.put("panel_state", cleanString(panelState))
                .put("panel_state_source", cleanString(source))
                .put("panel_state_updated_at_unix_ms", nowMs)
                .put("panel_state_updated_time_utc", Instant.now().toString());
            save();
            appendForegroundEvent(eventType, source);
        } catch (Exception ignored) {
        }
    }

    JSONObject stateSnapshot() {
        try {
            return new JSONObject(state.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void save() throws Exception {
        ensureRootDir();
        writeActivityFile(activity, SESSION_FILE, state.toString(2));
        if (hasParticipant()) {
            ensureSessionFiles();
            writeFile(sessionFile(MANIFEST_FILE), state.toString(2));
        }
    }

    private void appendBlockEvent(String eventType, JSONObject block) throws Exception {
        if (!hasParticipant()) {
            return;
        }
        JSONObject row = new JSONObject()
            .put("schema_id", EVENT_SCHEMA)
            .put("event_type", eventType)
            .put("participant_id", participantId())
            .put("session_id", sessionId())
            .put("stage", stage())
            .put("time_unix_ms", System.currentTimeMillis())
            .put("time_utc", Instant.now().toString());
        if (block != null) {
            row.put("block", new JSONObject(block.toString()))
                .put("condition_id", block.optString("condition_id"))
                .put("condition_label", block.optString("condition_label"))
                .put("profile_id", block.optString("profile_id"));
        }
        appendLine(sessionFile(BLOCK_EVENTS_FILE), row.toString());
    }

    private void appendForegroundEvent(String eventType, String source) throws Exception {
        if (!hasParticipant()) {
            return;
        }
        JSONObject block = activeBlock();
        JSONObject row = new JSONObject()
            .put("schema_id", EVENT_SCHEMA)
            .put("event_type", eventType)
            .put("participant_id", participantId())
            .put("session_id", sessionId())
            .put("stage", stage())
            .put("source", cleanString(source))
            .put("time_unix_ms", System.currentTimeMillis())
            .put("time_utc", Instant.now().toString())
            .put("foreground", foregroundEnvelope());
        if (block != null) {
            row.put("block", new JSONObject(block.toString()))
                .put("condition_id", block.optString("condition_id"))
                .put("condition_label", block.optString("condition_label"))
                .put("profile_id", block.optString("profile_id"));
        }
        appendLine(sessionFile(FOREGROUND_EVENTS_FILE), row.toString());
    }

    private JSONObject foregroundEnvelope() throws Exception {
        String currentStage = stage();
        JSONObject block = activeBlock();
        JSONObject foreground = new JSONObject()
            .put("stage", currentStage)
            .put("surface_target_id", surfaceTargetId())
            .put("surface_label", surfaceLabel())
            .put("panel_state", state.optString("panel_state", "unknown"))
            .put("panel_state_source", state.optString("panel_state_source", "unknown"))
            .put("panel_state_updated_at_unix_ms", state.optLong("panel_state_updated_at_unix_ms", 0L))
            .put("panel_state_updated_time_utc", state.optString("panel_state_updated_time_utc", ""));
        if ("block_running".equals(currentStage) && block != null) {
            foreground.put("kind", "condition")
                .put("surface", "immersive_renderer")
                .put("label", block.optString("condition_label"))
                .put("block_index", block.optInt("block_index", -1))
                .put("block_number", block.optInt("block_number", 0))
                .put("condition_id", block.optString("condition_id"))
                .put("condition_label", block.optString("condition_label"))
                .put("profile_id", block.optString("profile_id"));
        } else if ("questionnaire".equals(currentStage) && block != null) {
            foreground.put("kind", "questionnaire")
                .put("surface", "same_apk_control_panel")
                .put("label", "Block questionnaire")
                .put("block_index", block.optInt("block_index", -1))
                .put("block_number", block.optInt("block_number", 0))
                .put("condition_id", block.optString("condition_id"))
                .put("condition_label", block.optString("condition_label"))
                .put("profile_id", block.optString("profile_id"));
        } else if ("polar_setup".equals(currentStage)) {
            foreground.put("kind", "polar_setup")
                .put("surface", "same_apk_control_panel")
                .put("label", "Polar setup");
        } else if ("ready_next_block".equals(currentStage)) {
            foreground.put("kind", "next_block_setup")
                .put("surface", "same_apk_control_panel")
                .put("label", "Next block ready");
        } else if ("complete".equals(currentStage)) {
            foreground.put("kind", "complete")
                .put("surface", "same_apk_control_panel")
                .put("label", "Experiment complete");
        } else {
            foreground.put("kind", "participant_setup")
                .put("surface", "same_apk_control_panel")
                .put("label", "Participant setup");
        }
        return foreground;
    }

    private JSONObject selectedSurfaceSnapshot() throws Exception {
        JSONObject surface = state.optJSONObject("surface");
        if (surface != null) {
            return new JSONObject(surface.toString());
        }
        String surfaceTargetId = state.optString("surface_target_id", "real-hands");
        String surfaceLabel = state.optString("surface_label", surfaceTargetId);
        String surfaceTarget = "quest-live-hand-mesh";
        if ("gpu-replay-hands".equals(surfaceTargetId)) {
            surfaceTarget = "quest-recorded-gpu-hand-mesh";
        } else if ("icosphere".equals(surfaceTargetId)) {
            surfaceTarget = "static-icosphere-l4";
        }
        return new JSONObject()
            .put("surface_target_id", surfaceTargetId)
            .put("surface_label", surfaceLabel)
            .put("surface_target", surfaceTarget)
            .put("resource_plan_id", "")
            .put("runtime_profile_path", "")
            .put("recenter_icosphere_on_block_start", "icosphere".equals(surfaceTargetId));
    }

    private void ensureSessionFiles() throws Exception {
        ensureRootDir();
        if (!hasParticipant()) {
            return;
        }
        File dir = new File(sessionDirectoryPath());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("create_session_dir_failed");
        }
        ensureFileExists(sessionFile(POLAR_EVENTS_FILE));
        ensureFileExists(sessionFile(ECG_EVENTS_FILE));
        ensureFileExists(sessionFile(BLOCK_EVENTS_FILE));
        ensureFileExists(sessionFile(FOREGROUND_EVENTS_FILE));
        ensureFileExists(sessionFile(QUESTIONNAIRE_FILE));
    }

    private File sessionFile(String name) {
        return new File(new File(sessionDirectoryPath()), name);
    }

    private void ensureRootDir() throws Exception {
        File root = new File(activity.getFilesDir(), ROOT_DIR);
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("create_experiment_root_failed");
        }
    }

    private static JSONObject newState(Condition[] conditions) {
        long seed = System.currentTimeMillis();
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < conditions.length; i++) {
            order.add(Integer.valueOf(i));
        }
        Collections.shuffle(order, new Random(seed));
        JSONArray conditionOrder = new JSONArray();
        try {
            for (int i = 0; i < order.size(); i++) {
                conditionOrder.put(conditions[order.get(i).intValue()].toJson(i));
            }
            return new JSONObject()
                .put("schema_id", SESSION_SCHEMA)
                .put("stage", "participant")
                .put("participant_id", "")
                .put("session_id", "")
                .put("randomization_seed", seed)
                .put("randomized_at_unix_ms", seed)
                .put("randomized_time_utc", Instant.now().toString())
                .put("block_duration_ms", DEFAULT_BLOCK_DURATION_MS)
                .put("condition_order", conditionOrder)
                .put("next_block_index", 0)
                .put("completed_blocks", new JSONArray());
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private static String cleanString(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeToken(String value) {
        String token = cleanString(value).replaceAll("[^A-Za-z0-9._-]+", "_");
        token = token.replaceAll("_+", "_");
        if (token.length() == 0) {
            return "participant";
        }
        if (token.length() > 48) {
            return token.substring(0, 48);
        }
        return token;
    }

    private static void ensureFileExists(File file) throws Exception {
        if (file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("create_file_parent_failed");
        }
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void appendLine(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("create_file_parent_failed");
        }
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void writeFile(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("create_file_parent_failed");
        }
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void writeActivityFile(Activity activity, String name, String content) throws Exception {
        FileOutputStream out = activity.openFileOutput(name, Context.MODE_PRIVATE);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String readActivityFile(Activity activity, String name) throws Exception {
        FileInputStream in = activity.openFileInput(name);
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
            );
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            in.close();
        }
    }
}
