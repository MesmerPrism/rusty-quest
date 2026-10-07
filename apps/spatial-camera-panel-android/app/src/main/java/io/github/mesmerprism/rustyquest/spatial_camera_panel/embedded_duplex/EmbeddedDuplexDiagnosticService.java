package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.util.AtomicFile;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Debug-provider bridge. Only the process host may finalize a diagnostic receipt. */
public final class EmbeddedDuplexDiagnosticService {
    private static final String SCHEMA = "rusty.quest.embedded_duplex.local_diagnostic.v1";
    private static final String RELATIVE = "embedded-duplex/local-diagnostic.json";

    private EmbeddedDuplexDiagnosticService() {}

    public static void arm(Context context, String challenge) {
        if (!BuildConfig.DEBUG || context == null || challenge == null
                || !challenge.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("diagnostic challenge");
        }
        EmbeddedDuplexProcessHost.forApplication(context).armDiagnosticChallenge(challenge);
    }

    public static String provisionLocalFixture(Context context, String challenge) throws Exception {
        if (!BuildConfig.DEBUG || context == null || challenge == null
                || !challenge.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("diagnostic fixture challenge");
        }
        EmbeddedDuplexEnrollment installed = EmbeddedDuplexProcessHost.forApplication(context)
                .provisionLocalDiagnosticFixture(challenge).get(30, TimeUnit.SECONDS);
        if (!installed.localFixture || !"peer_a".equals(installed.installed.roleId)) {
            throw new IllegalStateException("diagnostic fixture enrollment invalid");
        }
        return installed.recordSha256;
    }

    public static boolean requestRun(Context context, String challenge) throws Exception {
        if (!BuildConfig.DEBUG || context == null || challenge == null
                || !challenge.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("diagnostic run challenge");
        }
        if (!EmbeddedDuplexProcessHost.forApplication(context).hasDiagnosticChallenge(challenge)) {
            throw new IllegalStateException("diagnostic run was not armed");
        }
        EmbeddedDuplexEnrollment installed = EmbeddedDuplexEnrollmentResolver.resolve(context);
        if (!installed.localFixture || !"peer_a".equals(installed.installed.roleId)) {
            throw new IllegalStateException("diagnostic fixture is not installed");
        }
        return EmbeddedDuplexDiagnosticActivityGate.requestRun().get(5, TimeUnit.SECONDS);
    }

    public static String read(Context context, String challenge) throws Exception {
        if (!BuildConfig.DEBUG || context == null || challenge == null
                || !challenge.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("diagnostic read challenge");
        }
        byte[] bytes = new AtomicFile(receiptFile(context)).readFully();
        if (bytes.length == 0 || bytes.length > 4096) {
            throw new IllegalStateException("diagnostic receipt bounds");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        JSONObject receipt = new JSONObject(value);
        if (!SCHEMA.equals(receipt.getString("$schema"))
                || !challenge.equals(receipt.getString("challenge"))) {
            throw new IllegalStateException("diagnostic receipt challenge differs");
        }
        validateFailureProjection(receipt);
        return value;
    }

    static void validateFailureProjection(JSONObject receipt) throws Exception {
        if (!receipt.has("failure_stage") || !receipt.has("failure_code")) {
            throw new IllegalStateException("diagnostic failure projection absent");
        }
        String status = receipt.getString("status");
        if (status.equals("bootstrap_closed")) {
            if (!receipt.isNull("failure_stage") || !receipt.isNull("failure_code")) {
                throw new IllegalStateException("successful diagnostic carries a failure assertion");
            }
            return;
        }
        if (!status.equals("bootstrap_failed_closed")
                && !status.equals("bootstrap_unavailable_closed")) {
            throw new IllegalStateException("diagnostic status is invalid");
        }
        for (EmbeddedDuplexBootstrap.Failure allowed : EmbeddedDuplexBootstrap.Failure.values()) {
            if (allowed.stage.equals(receipt.optString("failure_stage"))
                    && allowed.code.equals(receipt.optString("failure_code"))) {
                return;
            }
        }
        throw new IllegalStateException("diagnostic failure projection is invalid");
    }

    static String finalizeReceipt(Context context, String challenge, String status,
            String enrollmentRecordSha256, String runtimeConfigSha256,
            EmbeddedDuplexBootstrap.Failure failure) throws Exception {
        if (context == null) throw new IllegalArgumentException("diagnostic finalization context");
        JSONObject receipt = receiptDocument(challenge, status, enrollmentRecordSha256,
                runtimeConfigSha256, failure);
        String value = receipt.toString();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IllegalStateException("diagnostic receipt bounds");
        File file = receiptFile(context);
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("diagnostic receipt directory unavailable");
        }
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(bytes);
            atomic.finishWrite(stream);
        } catch (Exception writeFailure) {
            atomic.failWrite(stream);
            throw writeFailure;
        }
        return value;
    }

    static JSONObject receiptDocument(String challenge, String status,
            String enrollmentRecordSha256, String runtimeConfigSha256,
            EmbeddedDuplexBootstrap.Failure failure) throws Exception {
        if (challenge == null || !challenge.matches("[0-9a-f]{32}")
                || status == null || !(status.equals("bootstrap_closed")
                    || status.equals("bootstrap_failed_closed")
                    || status.equals("bootstrap_unavailable_closed"))
                || (status.equals("bootstrap_closed") ? failure != null : failure == null)) {
            throw new IllegalArgumentException("diagnostic finalization");
        }
        JSONObject receipt = new JSONObject()
                .put("$schema", SCHEMA)
                .put("challenge", challenge)
                .put("status", status)
                .put("failure_stage", failure == null ? JSONObject.NULL : failure.stage)
                .put("failure_code", failure == null ? JSONObject.NULL : failure.code)
                .put("package_id", BuildConfig.APPLICATION_ID)
                .put("product_manifest_sha256", BuildConfig.EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256)
                .put("enrollment_record_sha256", enrollmentRecordSha256 == null
                        ? JSONObject.NULL : enrollmentRecordSha256)
                .put("runtime_config_sha256", runtimeConfigSha256 == null
                        ? JSONObject.NULL : runtimeConfigSha256)
                .put("local_fixture", true)
                .put("owner_effects_attempted", false)
                .put("display_detached", true)
                .put("finalized_wall_unix_ms", System.currentTimeMillis());
        validateFailureProjection(receipt);
        return receipt;
    }

    private static File receiptFile(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) throw new IllegalStateException("application context unavailable");
        return new File(app.getNoBackupFilesDir(), RELATIVE);
    }
}
