package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.util.AtomicFile;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
        return value;
    }

    static String finalizeReceipt(Context context, String challenge, String status,
            String enrollmentRecordSha256, String runtimeConfigSha256) throws Exception {
        if (context == null || challenge == null || !challenge.matches("[0-9a-f]{32}")
                || status == null || !(status.equals("bootstrap_closed")
                    || status.equals("bootstrap_failed_closed")
                    || status.equals("bootstrap_unavailable_closed"))) {
            throw new IllegalArgumentException("diagnostic finalization");
        }
        JSONObject receipt = new JSONObject()
                .put("$schema", SCHEMA)
                .put("challenge", challenge)
                .put("status", status)
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
        } catch (Exception failure) {
            atomic.failWrite(stream);
            throw failure;
        }
        return value;
    }

    private static File receiptFile(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) throw new IllegalStateException("application context unavailable");
        return new File(app.getNoBackupFilesDir(), RELATIVE);
    }
}
