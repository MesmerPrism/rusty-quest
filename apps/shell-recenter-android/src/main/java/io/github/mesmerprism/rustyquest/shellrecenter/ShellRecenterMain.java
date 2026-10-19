// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.shellrecenter;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finite observer, owned original lifetime. app_process only; does not stage or clean host resources. */
@SuppressWarnings("deprecation") // app_process owns its main looper; no Android Activity exists.
public final class ShellRecenterMain {
    private final String session, nonce;
    private final File directory;
    private final AndroidShellRecenter backend;
    private final ShellRecenterOperation operation;
    private JSONObject finalPin, intentPin;
    private ShellRecenterMain(Map<String, String> args) throws Exception {
        if (args.size() != 7) throw new IllegalArgumentException("exact-closed-arguments-required");
        session = required(args, "session"); nonce = required(args, "nonce");
        if (!session.matches("[0-9a-f]{32}") || !nonce.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("opaque-original-identity-required");
        String modeText = required(args, "mode");
        ShellRecenterOperation.Mode mode;
        if ("readiness".equals(modeText)) mode = ShellRecenterOperation.Mode.READINESS;
        else if ("recenter".equals(modeText)) mode = ShellRecenterOperation.Mode.RECENTER;
        else throw new IllegalArgumentException("mode-readiness-or-recenter-required");
        String boot = required(args, "expected-boot-id"), source = required(args, "source-id");
        if (!boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !source.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("exact-boot-and-source-pin-required");
        String fingerprint = required(args, "expected-fingerprint");
        if (fingerprint.isEmpty() || fingerprint.length() > 4096) throw new IllegalArgumentException("bounded-fingerprint-required");
        directory = new File(required(args, "output-dir"));
        if (!directory.isAbsolute() || directory.exists() || !directory.getCanonicalFile().equals(directory.getAbsoluteFile())) throw new IllegalArgumentException("fresh-canonical-original-directory-required");
        if (!directory.mkdir()) throw new IllegalStateException("create-new-owned-directory-failed");
        Handler handler = new Handler(Looper.getMainLooper());
        backend = new AndroidShellRecenter(handler, boot, fingerprint, source);
        operation = new ShellRecenterOperation(mode, backend, new ShellRecenterOperation.Journal() {
            public void intent(ShellRecenterOperation.Snapshot snapshot) throws Exception {
                intentPin = publish(new File(directory, "EXECUTE_INTENT.json"), base("EXECUTE_INTENT", snapshot));
            }
            public void terminal(ShellRecenterOperation.Snapshot snapshot) throws Exception {
                finalPin = publish(new File(directory, "FINAL.json"), base("TERMINAL", snapshot));
            }
        }, (event, snapshot) -> {
            try {
                JSONObject record = base(event, snapshot);
                if (intentPin != null) record.put("intent", intentPin);
                if (finalPin != null) record.put("final", finalPin);
                System.out.println(record); System.out.flush();
                if (System.out.checkError()) throw new IllegalStateException("original-output-write-failed");
                if ("FINAL_PUBLISHED".equals(event)) System.exit("ORIGINAL_RESULT".equals(snapshot.outcome) || "READINESS_READY".equals(snapshot.outcome) ? 0 : 1);
                if ("TERMINAL_NO_EXECUTE_OUTPUT_FAILURE".equals(event)) System.exit(1);
            } catch (Exception error) { throw new IllegalStateException("original-event-publication-failed", error); }
        });
        handler.postDelayed(operation::deadline, 30000);
    }
    private static String required(Map<String, String> args, String key) {
        String value = args.get(key); if (value == null) throw new IllegalArgumentException("missing-" + key); return value;
    }
    public static void main(String[] argv) {
        ShellRecenterMain owner = null;
        try {
            Map<String, String> args = new HashMap<>();
            for (int i = 0; i < argv.length; i += 2) {
                if (i + 1 == argv.length || !argv[i].startsWith("--") || args.put(argv[i].substring(2), argv[i + 1]) != null) throw new IllegalArgumentException("closed-key-value-arguments-required");
            }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            owner = new ShellRecenterMain(args);
            owner.operation.start();
            Looper.loop();
        } catch (Throwable error) {
            if (owner != null && owner.operation.snapshot().executeAttempted) {
                // Never exit an uncertain dispatched original. Preserve the event and live owner.
                try { System.out.println(owner.base("ORIGINAL_UNKNOWN_HELD", owner.operation.snapshot()).put("error", error(error))); System.out.flush(); } catch (Exception ignored) { }
                Looper.loop();
            } else {
                try { System.out.println(new JSONObject().put("schema", "rusty.quest.shell_recenter.event.v1").put("event", "START_FAILED_BEFORE_EXECUTE").put("execute_attempted", false).put("error", error(error))); System.out.flush(); } catch (Exception ignored) { }
                System.exit(1);
            }
        }
    }
    private JSONObject base(String event, ShellRecenterOperation.Snapshot value) throws Exception {
        return record(event, value, session, nonce, backend.evidence(), intentPin != null);
    }
    static JSONObject record(String event, ShellRecenterOperation.Snapshot value, String session,
            String nonce, JSONObject evidence, boolean intentPublished) throws Exception {
        JSONObject result = new JSONObject().put("schema", "rusty.quest.shell_recenter.event.v1")
                .put("event", event).put("session", session).put("original_nonce", nonce)
                .put("pid", Process.myPid()).put("uid", Process.myUid()).put("host_elapsed_ms", SystemClock.elapsedRealtime())
                .put("target_package", ShellRecenterOperation.TARGET_PACKAGE).put("function_id", ShellRecenterOperation.FUNCTION_ID)
                .put("source_sha256", SourceIdentity.SHA256).put("context_identity", evidence)
                .put("source_id", SourceIdentity.SHA256).put("boot_id", evidence.opt("boot_id"))
                .put("intent_published", intentPublished)
                .put("execute_attempted", value.executeAttempted).put("original_callback_received", value.callbackReceived)
                .put("deadline_reached", value.deadlineReached).put("cleanup_held", value.cleanupHeld)
                .put("cancellation_attempted", value.cancellationAttempted).put("cancellation_returned", value.cancellationReturned)
                .put("cancellation_is_not_reversal", true).put("logical_callback_is_not_pose_ack", true)
                .put("pose_or_visibility_acknowledged", false).put("callback_after_deadline", value.deadlineReached && value.callbackReceived)
                .put("parameters", "BUILDER_DEFAULT_EMPTY_DOCUMENT").put("extras", "BUILDER_DEFAULT_EMPTY_BUNDLE");
        if (value.outcome != null) result.put("outcome", value.outcome);
        if (value.resultDocument != null) result.put("result_document", value.resultDocument);
        if (value.originalError != null) result.put("error", error(value.originalError));
        if (value.readinessReason != null) result.put("readiness_reason", value.readinessReason);
        if (value.callbackErrorOrigin != null) result.put("callback_error_origin", value.callbackErrorOrigin);
        return result;
    }
    public static JSONArray error(Throwable error) {
        JSONArray chain = new JSONArray();
        try {
            for (int n = 0; error != null && n < 8; n++, error = error.getCause()) {
                String message = String.valueOf(error.getMessage());
                JSONObject item = new JSONObject().put("class", error.getClass().getName()).put("message", message.substring(0, Math.min(4096, message.length())))
                        .put("message_truncated", message.length() > 4096).put("message_length", message.length());
                if ("android.app.appfunctions.AppFunctionException".equals(error.getClass().getName())) item.put("error_code", error.getClass().getMethod("getErrorCode").invoke(error));
                chain.put(item);
            }
        } catch (Exception ignored) { }
        return chain;
    }
    static JSONObject publish(File file, JSONObject record) throws Exception {
        byte[] bytes = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 131072 || !file.createNewFile()) throw new IllegalStateException("bounded-create-new-original-record-required");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); output.getFD().sync(); }
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", b & 255));
        return new JSONObject().put("path", file.getAbsolutePath()).put("bytes", bytes.length).put("sha256", sha.toString());
    }
}
