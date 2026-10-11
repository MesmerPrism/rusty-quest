package io.github.mesmerprism.rustyquest.native_renderer;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
final class ExperimentSessionDebugOperator {
    /** Production shell control of the real selected session owner; no clock/phase fabrication. */
    static String execute(Context context, JSONObject request) throws Exception {
        validateShape(request);
        String operation = request.getString("operation");
        boolean read = "status".equals(operation);
        JSONObject breath = new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus());
        JSONObject snapshot = breath.getJSONObject("snapshot");
        if (!Boolean.TRUE.equals(snapshot.opt("feature_lock_active"))
                || !Boolean.TRUE.equals(snapshot.opt("activation_binding_matches")))
            throw new SecurityException("operator-feature-inactive");
        if (!read) {
            if (request.getLong("expected_pid") != android.os.Process.myPid()
                    || request.getLong("expected_uid") != android.os.Process.myUid()
                    || request.getLong("expected_process_start_ticks") != processStartTicks())
                throw new SecurityException("operator-actor-mismatch");
            if (!"shell-experiment-operator-v1".equals(request.getString("operator_origin")))
                throw new SecurityException("operator-origin-invalid");
            String binding = request.getString("expected_binding_sha256");
            if (!binding.matches("[0-9a-f]{64}")
                    || !binding.equals(snapshot.getString("activation_binding_sha256")))
                throw new SecurityException("operator-binding-mismatch");
            String apk = request.getString("expected_apk_sha256");
            if (!apk.matches("[0-9a-f]{64}") || !apk.equals(installedApkSha256(context)))
                throw new SecurityException("operator-apk-mismatch");
        }
        boolean legacy = !read && !"official-start".equals(operation) && !"pause".equals(operation) && !"resume".equals(operation);
        if (legacy) return new JSONObject().put("schema","rusty.quest.experiment_session.operator_receipt.v1")
            .put("native_response",BreathCompositionPanelModule.queueExperimentOperator(request)).toString();
        JSONObject command = new JSONObject()
            .put("schema", "rusty.quest.experiment_session.command.v1")
            .put("operation", read ? "status" : "operator-" + operation);
        if (!read) {
            command.put("operation_id", request.getString("operation_id"))
                .put("expected_generation", request.getLong("expected_generation"))
                .put("elapsed_realtime_ns", android.os.SystemClock.elapsedRealtimeNanos())
                .put("operator_origin", "shell-experiment-operator-v1")
                .put("expected_runtime_epoch", request.getLong("expected_runtime_epoch"))
                .put("expected_session_key", request.getString("expected_session_key"))
                .put("expected_condition", request.getString("expected_condition"))
                .put("expected_profile_sha256", request.getString("expected_profile_sha256"))
                .put("expected_binding_sha256", request.getString("expected_binding_sha256"));
        }
        JSONObject nativeResponse = new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(command.toString()));
        return new JSONObject().put("schema", "rusty.quest.experiment_session.operator_receipt.v1")
            .put("operation", operation).put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid()).put("process_start_ticks", processStartTicks())
            .put("read_completed_elapsed_ns", android.os.SystemClock.elapsedRealtimeNanos())
            .put("native_response", nativeResponse).put("owner_receipt",BreathCompositionPanelModule.experimentOperatorOwnerReceipt())
            .put("audio_readiness", new org.json.JSONArray()
                .put(ControlPanelActivity.conditionAudioReadiness("condition-a"))
                .put(ControlPanelActivity.conditionAudioReadiness("condition-b")))
            .toString();
    }

    static void validateShape(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        boolean read = "status".equals(operation);
        if (!read && !"official-start".equals(operation) && !"pause".equals(operation)
                && !"resume".equals(operation) && !ExperimentSessionBleProtocol.supportedOperation(operation)) throw new SecurityException("operator-operation-invalid");
        String[] fields = read ? new String[] {"schema", "operation"} : new String[] {
            "schema", "operation", "operation_id", "expected_pid", "expected_uid",
            "expected_process_start_ticks", "expected_apk_sha256", "expected_binding_sha256",
            "expected_runtime_epoch", "expected_generation", "expected_session_key",
            "expected_condition", "expected_profile_sha256", "operator_origin"};
        if ("arm".equals(operation)) { String[] extended=java.util.Arrays.copyOf(fields,fields.length+2); extended[fields.length]="requested_condition";extended[fields.length+1]="requested_bias";fields=extended; }
        if (request.length() != fields.length) throw new SecurityException("operator-unexpected-field");
        for (String field : fields) if (!request.has(field)) throw new SecurityException("operator-missing-field");
        for (String field : fields) {
            Object raw = request.opt(field);
            if (field.equals("expected_pid") || field.equals("expected_uid")
                    || field.equals("expected_process_start_ticks") || field.equals("expected_runtime_epoch")
                    || field.equals("expected_generation") || field.equals("requested_bias")) {
                if (!(raw instanceof Integer) && !(raw instanceof Long))
                    throw new SecurityException("operator-integer-field-required");
                if (((Number) raw).longValue() < 0L || (((Number) raw).longValue()==0L && !field.equals("expected_generation") && !field.equals("requested_bias")))
                    throw new SecurityException("operator-positive-field-required");
            } else if (!(raw instanceof String)) throw new SecurityException("operator-string-field-required");
        }
        if ("arm".equals(operation) && (!request.getString("requested_condition").matches("condition-[ab]") || request.getLong("requested_bias")>100)) throw new SecurityException("operator-arm-selection-invalid");
        if (!"rusty.quest.experiment_session.operator_command.v1".equals(request.getString("schema"))) throw new SecurityException("operator-schema-invalid");
    }

    private static long processStartTicks() throws Exception {
        byte[] raw = new byte[4096];
        int size;
        try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/stat")) {
            size = input.read(raw);
            if (size <= 0 || size == raw.length) throw new SecurityException("operator-stat-invalid");
        }
        String stat = new String(raw, 0, size, StandardCharsets.UTF_8);
        int close = stat.lastIndexOf(')');
        if (close < 0) throw new SecurityException("operator-stat-invalid");
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 20) throw new SecurityException("operator-stat-invalid");
        long ticks = Long.parseLong(fields[19]);
        if (ticks <= 0) throw new SecurityException("operator-stat-invalid");
        return ticks;
    }

    private static String installedApkSha256(Context context) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream input = new java.io.FileInputStream(context.getApplicationInfo().sourceDir)) {
            byte[] block = new byte[65536]; long total = 0; int read;
            while ((read = input.read(block)) != -1) {
                total += read;
                if (total > 64L * 1024L * 1024L) throw new SecurityException("operator-apk-too-large");
                digest.update(block, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

}
