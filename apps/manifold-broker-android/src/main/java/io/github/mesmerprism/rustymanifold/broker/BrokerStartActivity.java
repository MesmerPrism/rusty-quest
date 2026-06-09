package io.github.mesmerprism.rustymanifold.broker;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class BrokerStartActivity extends Activity {
    private static final String PACKAGE_NAME = "io.github.mesmerprism.rustymanifold.broker";
    private static final String ACTIVITY_NAME =
            "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        LocalManifoldBrokerServer.get().start();
        writeLaunchEvidence();

        TextView status = new TextView(this);
        status.setText(
                "Rusty Manifold Broker\n"
                        + ACTIVITY_NAME
                        + "\nws://127.0.0.1:8765/manifold/v1/events");
        status.setTextSize(18.0f);
        int padding = 32;
        status.setPadding(padding, padding, padding, padding);
        setContentView(status);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        LocalManifoldBrokerServer.get().start();
        writeLaunchEvidence();
    }

    private void writeLaunchEvidence() {
        try {
            File root = new File(getExternalFilesDir(null), "manifold-broker");
            if (!root.exists() && !root.mkdirs()) {
                return;
            }
            JSONObject evidence = new JSONObject();
            evidence.put("$schema", "rusty.quest.manifold_broker_android.launch_evidence.v1");
            evidence.put("status", LocalManifoldBrokerServer.get().isRunning() ? "running" : "starting");
            evidence.put("package_name", PACKAGE_NAME);
            evidence.put("activity", ACTIVITY_NAME);
            evidence.put("authority", "rusty.manifold");
            evidence.put("endpoint_path", LocalManifoldBrokerServer.EVENTS_PATH);
            evidence.put("port", LocalManifoldBrokerServer.PORT);
            evidence.put("started_at_utc", Instant.now().toString());
            evidence.put("legacy_reference_package", false);
            File out = new File(root, "latest.json");
            try (FileOutputStream stream = new FileOutputStream(out, false)) {
                stream.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Evidence write failure must not prevent the operator surface from opening.
        }
    }
}
