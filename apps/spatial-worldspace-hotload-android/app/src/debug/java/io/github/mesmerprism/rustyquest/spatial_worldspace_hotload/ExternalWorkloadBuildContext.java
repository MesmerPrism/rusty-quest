package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** One immutable APK context per attached debug store. UI reads never wait for hashing. */
final class ExternalWorkloadBuildContext {
    private static final class State {
        final String digest, phase, reason;
        State(String digest, String phase, String reason) {
            this.digest = digest; this.phase = phase; this.reason = reason;
        }
    }
    private volatile State state = new State(null, "context_preparing", "");
    ExternalWorkloadBuildContext(Callable<String> prepare) {
        Thread worker = new Thread(() -> {
            try {
                String digest = prepare.call();
                if (digest == null || !digest.matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("build_digest");
                state = new State(digest, "context_prepared", "");
            } catch (Exception failure) {
                state = new State(null, "context_rejected", "build_context_preparation_failed");
            }
        }, "external-workload-context");
        worker.setDaemon(true);
        worker.start();
    }
    String digest() { return state.digest; }
    String phase() { return state.phase; }
    String reason() { return state.reason; }
    Map<String, Object> info(Map<String, Object> live, String session, long nextRequestId) {
        State current = state;
        Map<String, Object> result = new LinkedHashMap<>(live);
        result.put("schema", "rusty.quest.hotload.external_workload_context.v1");
        result.put("slot", "external-workload-v1");
        result.put("maximumCodeBytes", 262144);
        result.put("phase", current.phase);
        if (!current.reason.isEmpty()) result.put("reason", current.reason);
        result.put("appBuild", current.digest);
        result.put("session", session);
        result.put("nextRequestId", Long.toString(nextRequestId));
        return result;
    }
}
