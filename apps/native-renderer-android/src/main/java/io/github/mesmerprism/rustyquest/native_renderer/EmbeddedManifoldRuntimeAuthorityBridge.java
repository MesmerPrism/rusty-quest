package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

/** Trusted embedded JNI projection. It validates shape, never command policy. */
public final class EmbeddedManifoldRuntimeAuthorityBridge {
    private static final String INVOCATION_SCHEMA = "rusty.quest.broker.authority_invocation.v1";
    private static final String RESPONSE_SCHEMA = "rusty.quest.broker.authority_response.v1";
    private static final String DECISION_OWNER = "module.runtime.host";

    static {
        System.loadLibrary("rusty_quest_native_renderer");
    }

    private EmbeddedManifoldRuntimeAuthorityBridge() {
    }

    public static JSONObject evaluateTrustedInvocation(JSONObject invocation) throws Exception {
        if (!INVOCATION_SCHEMA.equals(invocation.optString("$schema", ""))) {
            throw new IllegalArgumentException("authority invocation schema mismatch");
        }
        String responseJson = nativeEvaluate(invocation.toString());
        if (responseJson == null || responseJson.isEmpty()) {
            throw new IllegalStateException("embedded Manifold Runtime Host JNI evaluation failed");
        }
        JSONObject response = new JSONObject(responseJson);
        if (!RESPONSE_SCHEMA.equals(response.optString("$schema", ""))
                || response.optBoolean("local_acceptance_rules", true)
                || !DECISION_OWNER.equals(response.optString("decision_owner_id", ""))) {
            throw new IllegalStateException("embedded Manifold authority response binding mismatch");
        }
        return response;
    }

    private static native String nativeEvaluate(String invocationJson);
}
