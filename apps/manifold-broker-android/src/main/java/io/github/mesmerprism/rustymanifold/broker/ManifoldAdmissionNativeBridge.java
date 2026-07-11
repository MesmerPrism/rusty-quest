package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

/** JNI transport to Manifold admission. No grant or token policy is owned here. */
public final class ManifoldAdmissionNativeBridge {
    private static final String RESPONSE_SCHEMA = "rusty.quest.broker.admission_response.v1";

    static {
        System.loadLibrary("rusty_quest_manifold_broker_authority");
    }

    private ManifoldAdmissionNativeBridge() {
    }

    public static JSONObject initialize(String configJson) throws Exception {
        String result = nativeInitialize(configJson);
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Manifold admission initialization failed");
        }
        JSONObject status = new JSONObject(result);
        if (!status.optBoolean("initialized", false)
                || status.optBoolean("local_token_or_grant_policy", true)
                || !"rusty.manifold.admission".equals(status.optString("decision_owner", ""))) {
            throw new IllegalStateException("Manifold admission initialization binding mismatch");
        }
        return status;
    }

    public static JSONObject execute(JSONObject operation) throws Exception {
        String result = nativeExecute(operation.toString());
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Manifold admission JNI execution failed");
        }
        JSONObject response = new JSONObject(result);
        if (!RESPONSE_SCHEMA.equals(response.optString("$schema", ""))
                || response.optBoolean("local_token_or_grant_policy", true)
                || !"rusty.manifold.admission".equals(response.optString("decision_owner", ""))) {
            throw new IllegalStateException("Manifold admission response binding mismatch");
        }
        return response;
    }

    public static JSONObject snapshot() throws Exception {
        String result = nativeSnapshot();
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Manifold admission snapshot unavailable");
        }
        return new JSONObject(result);
    }

    private static native String nativeInitialize(String configJson);
    private static native String nativeExecute(String operationJson);
    private static native String nativeSnapshot();
}
