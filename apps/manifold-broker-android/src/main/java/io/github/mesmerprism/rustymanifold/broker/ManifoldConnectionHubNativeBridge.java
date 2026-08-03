package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

/** JNI transport to the retained Manifold Connection Hub authority. */
public final class ManifoldConnectionHubNativeBridge {
    private static final String RECEIPT_SCHEMA = "rusty.quest.connection_hub.native_receipt.v1";

    static {
        System.loadLibrary("rusty_quest_manifold_broker_authority");
    }

    private ManifoldConnectionHubNativeBridge() {}

    public static JSONObject initialize() throws Exception {
        return requireJson(nativeInitialize(GeneratedConnectionHubConfig.JSON),
                "Manifold Connection Hub initialization failed");
    }

    public static JSONObject execute(JSONObject proposal, long nowMs) throws Exception {
        JSONObject response = requireJson(nativeExecute(proposal.toString(), nowMs),
                "Manifold Connection Hub execution failed");
        if (!RECEIPT_SCHEMA.equals(response.optString("$schema", ""))) {
            throw new SecurityException("Manifold Connection Hub receipt schema mismatch");
        }
        return response;
    }

    public static String exportState() {
        String state = nativeExport();
        if (state == null || state.isEmpty()) {
            throw new IllegalStateException("Manifold Connection Hub state export failed");
        }
        return state;
    }

    public static JSONObject restore(String state) throws Exception {
        JSONObject response = requireJson(nativeRestore(state),
                "Manifold Connection Hub state restore failed");
        if (!RECEIPT_SCHEMA.equals(response.optString("$schema", ""))) {
            throw new SecurityException("Manifold Connection Hub restore receipt mismatch");
        }
        return response;
    }

    private static JSONObject requireJson(String value, String error) throws Exception {
        if (value == null || value.isEmpty()) { throw new IllegalStateException(error); }
        return new JSONObject(value);
    }

    private static native String nativeInitialize(String configJson);
    private static native String nativeExecute(String proposalJson, long nowMs);
    private static native String nativeExport();
    private static native String nativeRestore(String stateJson);
}
