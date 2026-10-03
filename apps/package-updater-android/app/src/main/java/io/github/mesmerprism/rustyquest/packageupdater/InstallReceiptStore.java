package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class InstallReceiptStore {
    static final String SCHEMA = "rusty.quest.package_update.install_receipt.v1";
    static final String CALLBACK_ACTION =
            "io.github.mesmerprism.rustyquest.packageupdater.labs.INSTALL_STATUS";
    static final String CALLBACK_SCHEME = "rusty-package-updater";
    static final String CALLBACK_AUTHORITY = "install";
    private static final int MAX_RECEIPT_BYTES = 32 * 1024;
    private static final Object RECEIPT_LOCK = new Object();
    private static final java.util.Set<Object> CALLBACK_OWNERS =
            new java.util.HashSet<>();

    private final AtomicFile receiptFile;

    InstallReceiptStore(Context context) {
        File directory = new File(
                context.getNoBackupFilesDir(), "package-updater/labs/receipts");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("could_not_create_private_receipt_directory");
        }
        receiptFile = new AtomicFile(new File(directory, "install-receipt.json"));
    }

    void begin(int sessionId, String token, VerifiedUpdatePlan plan, File staged)
            throws Exception {
        synchronized (RECEIPT_LOCK) {
            if (!CALLBACK_OWNERS.isEmpty()) {
                throw new IllegalStateException("install_receipt_callback_active");
            }
            beginUnlocked(sessionId, token, plan, staged);
        }
    }

    JSONObject read() throws Exception {
        synchronized (RECEIPT_LOCK) { return readUnlocked(); }
    }

    boolean matchesCallback(Intent intent) throws Exception {
        synchronized (RECEIPT_LOCK) { return matchesCallbackUnlocked(intent); }
    }

    void updateState(int sessionId, String state, Integer code, String message)
            throws Exception {
        synchronized (RECEIPT_LOCK) { updateStateUnlocked(sessionId, state, code, message); }
    }

    boolean compareAndSetState(int sessionId, String expected, String state,
            Integer code, String message) throws Exception {
        synchronized (RECEIPT_LOCK) {
            return compareAndSetStateUnlocked(sessionId, expected, state, code, message);
        }
    }

    static final class CallbackReceipt implements AutoCloseable {
        final JSONObject receipt;
        private final Object owner = new Object();
        private CallbackReceipt(JSONObject receipt) { this.receipt = receipt; }
        @Override public void close() {
            synchronized (RECEIPT_LOCK) { CALLBACK_OWNERS.remove(owner); }
        }
    }

    CallbackReceipt captureCallback(Intent intent) throws Exception {
        synchronized (RECEIPT_LOCK) {
            if (!matchesCallbackUnlocked(intent)) { return null; }
            JSONObject receipt = readUnlocked();
            if (receipt == null || isTerminal(receipt.optString("state"))) { return null; }
            CallbackReceipt captured = new CallbackReceipt(receipt);
            CALLBACK_OWNERS.add(captured.owner);
            return captured;
        }
    }

    void updateCallbackState(CallbackReceipt captured, int sessionId,
            String state, Integer code, String message) throws Exception {
        synchronized (RECEIPT_LOCK) {
            JSONObject current = readUnlocked();
            if (!CALLBACK_OWNERS.contains(captured.owner) || current == null
                    || sessionId != captured.receipt.getInt("session_id")
                    || sessionId != current.getInt("session_id")
                    || !captured.receipt.getString("callback_token").equals(
                            current.getString("callback_token"))) {
                throw new IllegalStateException("install_receipt_callback_mismatch");
            }
            updateStateUnlocked(sessionId, state, code, message);
        }
    }

    void dispatchPendingConfirmation(CallbackReceipt captured, int sessionId,
            int status, String message, Runnable dispatch) throws Exception {
        // Only this one synchronous confirmation dispatch is serialized with
        // receipt cancellation. Download/readback/checkpoint work stays outside.
        synchronized (RECEIPT_LOCK) {
            updateCallbackState(captured, sessionId,
                    "pending_user_confirmation", status, message);
            JSONObject current = readUnlocked();
            if (!"pending_user_confirmation".equals(current.optString("state"))) {
                return;
            }
            dispatch.run();
        }
    }

    private void beginUnlocked(
            int sessionId,
            String callbackToken,
            VerifiedUpdatePlan plan,
            File stagedApk) throws Exception {
        UpdateArtifact artifact = plan.artifact;
        JSONObject receipt = new JSONObject();
        receipt.put("schema", SCHEMA);
        receipt.put("session_id", sessionId);
        receipt.put("callback_token", callbackToken);
        receipt.put("package_name", artifact.packageName);
        receipt.put("version_code", artifact.versionCode);
        receipt.put("version_name", artifact.versionName);
        receipt.put("apk_url", artifact.apkUri.toASCIIString());
        receipt.put("apk_size_bytes", artifact.apkSizeBytes);
        receipt.put("apk_sha256", artifact.apkSha256);
        receipt.put("signer_sha256", artifact.signerSha256);
        receipt.put("staged_apk_path", stagedApk.getAbsolutePath());
        receipt.put("manifest_id", plan.manifestId);
        receipt.put("channel", plan.channel);
        receipt.put("key_id", plan.keyId);
        receipt.put("public_key", plan.publicKey);
        receipt.put("https_origin", plan.httpsOrigin);
        receipt.put("manifest_sequence", plan.sequence);
        receipt.put("manifest_expires_at_ms", plan.expiresAtMs);
        receipt.put("rollout_ring", plan.rolloutRing);
        receipt.put("signed_manifest_sha256", plan.signedManifestSha256);
        receipt.put("state", "session_written");
        receipt.put("status_code", JSONObject.NULL);
        receipt.put("status_message", JSONObject.NULL);
        receipt.put("updated_at_ms", System.currentTimeMillis());
        write(receipt);
    }

    private JSONObject readUnlocked() throws Exception {
        if (!receiptFile.getBaseFile().isFile()) {
            return null;
        }
        try (FileInputStream input = receiptFile.openRead()) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0 || bytes.length > MAX_RECEIPT_BYTES) {
                throw new IllegalStateException("install_receipt_size_invalid");
            }
            JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(value.optString("schema"))) {
                throw new IllegalStateException("install_receipt_schema_invalid");
            }
            return value;
        }
    }

    private boolean matchesCallbackUnlocked(Intent intent) throws Exception {
        if (intent == null || !CALLBACK_ACTION.equals(intent.getAction())) {
            return false;
        }
        JSONObject receipt = read();
        if (receipt == null) {
            return false;
        }
        Uri data = intent.getData();
        if (data == null
                || !CALLBACK_SCHEME.equals(data.getScheme())
                || !CALLBACK_AUTHORITY.equals(data.getAuthority())
                || data.getPathSegments().size() != 2) {
            return false;
        }
        int sessionId;
        try {
            sessionId = Integer.parseInt(data.getPathSegments().get(0));
        } catch (NumberFormatException exception) {
            return false;
        }
        String callbackToken = data.getPathSegments().get(1);
        return sessionId == receipt.getInt("session_id")
                && sessionId == intent.getIntExtra(
                        android.content.pm.PackageInstaller.EXTRA_SESSION_ID, -1)
                && callbackToken.equals(receipt.getString("callback_token"));
    }

    private void updateStateUnlocked(
            int sessionId, String state, Integer statusCode, String statusMessage)
            throws Exception {
        JSONObject receipt = read();
        if (receipt == null || receipt.getInt("session_id") != sessionId) {
            throw new IllegalStateException("install_receipt_session_mismatch");
        }
        String currentState = receipt.optString("state");
        if (isTerminal(currentState) && !currentState.equals(state)) {
            return;
        }
        if (isCancellationPending(currentState)
                && !isTerminal(state)
                && !isInstalledCheckpointPending(state)) {
            return;
        }
        if (isInstalledCheckpointPending(currentState)
                && !isTerminal(state)) {
            return;
        }
        writeState(receipt, state, statusCode, statusMessage);
    }

    private boolean compareAndSetStateUnlocked(
            int sessionId,
            String expectedState,
            String state,
            Integer statusCode,
            String statusMessage) throws Exception {
        JSONObject receipt = read();
        if (receipt == null || receipt.getInt("session_id") != sessionId) {
            throw new IllegalStateException("install_receipt_session_mismatch");
        }
        String currentState = receipt.optString("state");
        if (isTerminal(currentState) || !expectedState.equals(currentState)) {
            return false;
        }
        writeState(receipt, state, statusCode, statusMessage);
        return true;
    }

    private void writeState(
            JSONObject receipt,
            String state,
            Integer statusCode,
            String statusMessage) throws Exception {
        receipt.put("state", state);
        receipt.put("status_code", statusCode == null ? JSONObject.NULL : statusCode);
        String boundedMessage = statusMessage;
        if (boundedMessage != null && boundedMessage.length() > 512) {
            boundedMessage = boundedMessage.substring(0, 512);
        }
        receipt.put(
                "status_message",
                boundedMessage == null ? JSONObject.NULL : boundedMessage);
        receipt.put("updated_at_ms", System.currentTimeMillis());
        write(receipt);
    }

    static boolean isTerminal(String state) {
        return state != null
                && (state.startsWith("installed_readback_ok")
                        || state.startsWith("installed_but_checkpoint_rejected")
                        || state.startsWith("install_failed")
                        || state.startsWith("install_staging_failed")
                        || state.startsWith("install_cancelled")
                        || state.startsWith("readback_failed")
                        || state.startsWith("session_missing"));
    }

    static boolean isCancellationPending(String state) {
        return state != null && state.startsWith("cancel_requested");
    }

    static boolean isInstalledCheckpointPending(String state) {
        return state != null
                && state.startsWith("installed_readback_checkpoint_pending");
    }

    static UpdateArtifact artifact(JSONObject receipt) throws Exception {
        return new UpdateArtifact(
                receipt.getString("package_name"),
                receipt.getLong("version_code"),
                receipt.getString("version_name"),
                URI.create(receipt.getString("apk_url")),
                receipt.getLong("apk_size_bytes"),
                receipt.getString("apk_sha256"),
                receipt.getString("signer_sha256"));
    }

    private void write(JSONObject receipt) throws Exception {
        FileOutputStream output = null;
        try {
            output = receiptFile.startWrite();
            output.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            receiptFile.finishWrite(output);
        } catch (Exception exception) {
            if (output != null) {
                receiptFile.failWrite(output);
            }
            throw exception;
        }
    }
}
