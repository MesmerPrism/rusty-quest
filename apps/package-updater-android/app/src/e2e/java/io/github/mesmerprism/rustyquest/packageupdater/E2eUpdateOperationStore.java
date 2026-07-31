package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable, machine-readable state for the test-only adb CLI. */
final class E2eUpdateOperationStore {
    static final String SCHEMA =
            "rusty.quest.package_update.e2e_operation_status.v1";
    private static final Object LOCK = new Object();

    private final AtomicFile file;
    private final InstallReceiptStore receiptStore;

    E2eUpdateOperationStore(Context context) {
        Context appContext = context.getApplicationContext();
        File directory = new File(
                appContext.getNoBackupFilesDir(), "package-updater");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException(
                    "could_not_create_e2e_operation_directory");
        }
        file = new AtomicFile(new File(directory, "e2e-operation.json"));
        receiptStore = new InstallReceiptStore(appContext);
    }

    JSONObject beginOrReuse() throws Exception {
        synchronized (LOCK) {
            JSONObject current = readLocked();
            if (current != null && !current.optBoolean("terminal", false)) {
                return copy(current);
            }
            long nowMs = System.currentTimeMillis();
            JSONObject created = new JSONObject()
                    .put("schema", SCHEMA)
                    .put("operation_id", UUID.randomUUID().toString())
                    .put("state", "queued")
                    .put("terminal", false)
                    .put("cancel_requested", false)
                    .put("bytes_received", 0L)
                    .put("bytes_expected", -1L)
                    .put("package_name", JSONObject.NULL)
                    .put("version_code", JSONObject.NULL)
                    .put("session_id", JSONObject.NULL)
                    .put("receipt_state", JSONObject.NULL)
                    .put("error_code", JSONObject.NULL)
                    .put("started_at_ms", nowMs)
                    .put("updated_at_ms", nowMs);
            writeLocked(created);
            return copy(created);
        }
    }

    JSONObject read() throws Exception {
        synchronized (LOCK) {
            JSONObject value = readLocked();
            return value == null ? null : copy(value);
        }
    }

    void updateProgress(
            String operationId,
            String state,
            UpdateArtifact artifact,
            long bytesReceived,
            long bytesExpected) throws Exception {
        synchronized (LOCK) {
            JSONObject current = requireOperationLocked(operationId);
            if (current.optBoolean("terminal", false)
                    || current.optBoolean("cancel_requested", false)) {
                return;
            }
            current.put("state", state)
                    .put("bytes_received", Math.max(0L, bytesReceived))
                    .put("bytes_expected", bytesExpected)
                    .put("updated_at_ms", System.currentTimeMillis());
            if (artifact != null) {
                current.put("package_name", artifact.packageName)
                        .put("version_code", artifact.versionCode);
            }
            writeLocked(current);
        }
    }

    void awaitingWearer(
            String operationId,
            UpdateArtifact artifact,
            int sessionId) throws Exception {
        synchronized (LOCK) {
            JSONObject current = requireOperationLocked(operationId);
            if (current.optBoolean("terminal", false)) {
                return;
            }
            boolean cancellationPending =
                    current.optBoolean("cancel_requested", false);
            current.put(
                            "state",
                            cancellationPending
                                    ? "cancel_requested"
                                    : "awaiting_wearer_confirmation")
                    .put("terminal", false)
                    .put("package_name", artifact.packageName)
                    .put("version_code", artifact.versionCode)
                    .put("session_id", sessionId)
                    .put("bytes_received", artifact.apkSizeBytes)
                    .put("bytes_expected", artifact.apkSizeBytes)
                    .put("updated_at_ms", System.currentTimeMillis());
            writeLocked(current);
        }
    }

    void requestCancel() throws Exception {
        synchronized (LOCK) {
            JSONObject current = readLocked();
            if (current == null || current.optBoolean("terminal", false)) {
                return;
            }
            current.put("cancel_requested", true)
                    .put("state", "cancel_requested")
                    .put("updated_at_ms", System.currentTimeMillis());
            writeLocked(current);
        }
    }

    boolean isCancelRequested(String operationId) {
        synchronized (LOCK) {
            try {
                JSONObject current = readLocked();
                return current != null
                        && operationId.equals(
                                current.optString("operation_id", ""))
                        && current.optBoolean("cancel_requested", false);
            } catch (Exception exception) {
                return true;
            }
        }
    }

    void terminal(String operationId, String state, String errorCode)
            throws Exception {
        synchronized (LOCK) {
            JSONObject current = requireOperationLocked(operationId);
            if (current.optBoolean("terminal", false)) {
                return;
            }
            current.put("state", state)
                    .put("terminal", true)
                    .put("error_code",
                            errorCode == null ? JSONObject.NULL : errorCode)
                    .put("updated_at_ms", System.currentTimeMillis());
            writeLocked(current);
        }
    }

    boolean correlateInstallReceipt(
            String operationId, JSONObject receipt) throws Exception {
        synchronized (LOCK) {
            JSONObject current = requireOperationLocked(operationId);
            int receiptSessionId = receipt.optInt("session_id", -1);
            if (receiptSessionId < 0
                    || receipt.optLong("updated_at_ms", 0L)
                            < current.optLong("started_at_ms", Long.MAX_VALUE)) {
                return false;
            }
            int operationSessionId = current.isNull("session_id")
                    ? -1
                    : current.optInt("session_id", -1);
            if (operationSessionId >= 0
                    && operationSessionId != receiptSessionId) {
                return false;
            }
            String operationPackage =
                    current.optString("package_name", "");
            String receiptPackage = receipt.optString("package_name", "");
            if (!operationPackage.isBlank()
                    && !operationPackage.equals(receiptPackage)) {
                return false;
            }
            current.put("session_id", receiptSessionId)
                    .put("package_name", receiptPackage)
                    .put("version_code", receipt.optLong("version_code"))
                    .put(
                            "state",
                            current.optBoolean("cancel_requested", false)
                                    ? "cancel_requested"
                                    : "awaiting_wearer_confirmation")
                    .put("updated_at_ms", System.currentTimeMillis());
            writeLocked(current);
            return true;
        }
    }

    JSONObject projectInstallReceipt() throws Exception {
        synchronized (LOCK) {
            JSONObject operation = readLocked();
            JSONObject receipt = receiptStore.read();
            if (operation == null || receipt == null) {
                return operation == null ? null : copy(operation);
            }
            if (operation.optBoolean("terminal", false)) {
                return copy(operation);
            }
            int operationSessionId = operation.isNull("session_id")
                    ? -1
                    : operation.optInt("session_id", -1);
            int receiptSessionId = receipt.optInt("session_id", -1);
            if (operationSessionId < 0
                    || operationSessionId != receiptSessionId) {
                return copy(operation);
            }
            String receiptState = receipt.optString("state", "unknown");
            operation.put("receipt_state", receiptState)
                    .put("session_id", receiptSessionId)
                    .put("updated_at_ms", System.currentTimeMillis());
            if (InstallReceiptStore.isInstalledCheckpointPending(
                    receiptState)) {
                operation.put(
                                "state",
                                "installed_readback_checkpoint_pending")
                        .put(
                                "error_code",
                                "checkpoint_persistence_pending");
            } else if (InstallReceiptStore.isTerminal(receiptState)) {
                if (receiptState.contains(
                        "installed_but_checkpoint_rejected_expired")) {
                    operation.put(
                                    "state",
                                    "installed_but_checkpoint_rejected_expired")
                            .put(
                                    "error_code",
                                    "fresh_signed_manifest_required");
                } else if (receiptState.contains("installed_readback_ok")) {
                    operation.put("state", "installed_readback_ok")
                            .put("error_code", JSONObject.NULL);
                } else if (receiptState.contains("cancel")) {
                    operation.put("state", "cancelled")
                            .put("error_code", "wearer_or_cli_cancelled");
                } else {
                    operation.put("state", "failed")
                            .put("error_code", stableErrorCode(
                                    receipt.optString(
                                            "status_message",
                                            "installer_terminal_failure")));
                }
                operation.put("terminal", true);
            } else if (!operation.optBoolean("cancel_requested", false)) {
                operation.put("state", "awaiting_wearer_confirmation");
            }
            writeLocked(operation);
            return copy(operation);
        }
    }

    static String stableErrorCode(String message) {
        if (message != null && message.matches("[a-z0-9_]{1,96}")) {
            return message;
        }
        return "update_pipeline_failed";
    }

    private JSONObject requireOperationLocked(String operationId)
            throws Exception {
        JSONObject current = readLocked();
        if (current == null
                || !operationId.equals(
                        current.optString("operation_id", ""))) {
            throw new IllegalStateException("e2e_operation_identity_mismatch");
        }
        return current;
    }

    private JSONObject readLocked() throws Exception {
        if (!file.getBaseFile().isFile()) {
            return null;
        }
        byte[] bytes;
        try (var input = file.openRead()) {
            bytes = input.readAllBytes();
        }
        JSONObject value =
                new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!SCHEMA.equals(value.optString("schema"))
                || !value.optString("operation_id", "")
                        .matches("[0-9a-f-]{36}")) {
            throw new IllegalStateException("e2e_operation_store_invalid");
        }
        return value;
    }

    private void writeLocked(JSONObject value) throws Exception {
        FileOutputStream output = file.startWrite();
        try {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
        } catch (Exception exception) {
            file.failWrite(output);
            throw exception;
        }
    }

    private static JSONObject copy(JSONObject value) throws Exception {
        return new JSONObject(value.toString());
    }
}
