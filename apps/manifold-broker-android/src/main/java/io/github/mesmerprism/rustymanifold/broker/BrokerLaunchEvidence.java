package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

final class BrokerLaunchEvidence {
    static final String PACKAGE_NAME = "io.github.mesmerprism.rustymanifold.broker";
    static final String ACTIVITY_NAME =
            "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity";
    static final String SERVICE_NAME =
            "io.github.mesmerprism.rustymanifold.broker/.BrokerStartService";

    private BrokerLaunchEvidence() {
    }

    static void write(Context context, String componentName, String launchSurface) {
        try {
            File root = new File(context.getExternalFilesDir(null), "manifold-broker");
            if (!root.exists() && !root.mkdirs()) {
                return;
            }
            JSONObject evidence = new JSONObject();
            evidence.put("$schema", "rusty.quest.manifold_broker_android.launch_evidence.v1");
            evidence.put("status", LocalManifoldBrokerServer.get().isRunning() ? "running" : "starting");
            evidence.put("package_name", PACKAGE_NAME);
            evidence.put("activity", ACTIVITY_NAME);
            evidence.put("service", SERVICE_NAME);
            evidence.put("component", componentName);
            evidence.put("launch_surface", launchSurface);
            evidence.put("runtime_authority", ManifoldRuntimeAuthorityBridge.evidence());
            evidence.put("endpoint_path", LocalManifoldBrokerServer.EVENTS_PATH);
            evidence.put("port", LocalManifoldBrokerServer.PORT);
            evidence.put("started_at_utc", Instant.now().toString());
            evidence.put("legacy_reference_package", false);
            File out = new File(root, "latest.json");
            try (FileOutputStream stream = new FileOutputStream(out, false)) {
                stream.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Evidence write failure must not prevent the broker from starting.
        }
    }
}
