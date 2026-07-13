package io.github.mesmerprism.rustymanifold.broker;

import android.util.Log;

import org.json.JSONObject;

/** JNI transport to one stateful Rust Runtime Host plus Manifold admission. */
public final class ManifoldRuntimeAuthorityBridge {
    private static final String INITIALIZE_SCHEMA =
            "rusty.quest.broker.runtime_initialize_status.v1";
    private static final String RESPONSE_SCHEMA =
            "rusty.quest.broker.server_mutation_response.v1";
    private static final String EVIDENCE_SCHEMA =
            "rusty.quest.broker.runtime_evidence.v1";
    private static final String DECISION_OWNER = "module.runtime.host";
    private static final String LOG_TAG = "RustyManifoldRuntime";

    static {
        System.loadLibrary("rusty_quest_manifold_broker_authority");
    }

    private ManifoldRuntimeAuthorityBridge() {
    }

    public static JSONObject initialize() throws Exception {
        String responseJson = nativeInitialize(
                GeneratedBrokerRuntimeConfig.JSON,
                GeneratedBrokerRuntimeConfig.SHA256,
                ProviderEpochEntropy.hex());
        JSONObject status = requireAuthorityResponse(
                responseJson,
                INITIALIZE_SCHEMA,
                "initialization");
        Log.i(LOG_TAG,
                "status=initialized providerEpoch="
                        + status.optString("provider_epoch_id", "missing")
                        + " existingAuthorityPreserved="
                        + status.optBoolean("existing_authority_preserved", false));
        return status;
    }

    public static JSONObject evaluateMutation(JSONObject request) throws Exception {
        String responseJson = nativeMutate(request.toString(), System.currentTimeMillis());
        return requireAuthorityResponse(responseJson, RESPONSE_SCHEMA, "mutation");
    }

    public static JSONObject completeMediaAction(JSONObject request) throws Exception {
        String responseJson = nativeCompleteMediaAction(request.toString(), System.currentTimeMillis());
        return requireAuthorityResponse(
                responseJson,
                "rusty.quest.broker.media_completion_response.v1",
                "media completion");
    }

    public static JSONObject evidence() throws Exception {
        String responseJson = nativeEvidence();
        return requireAuthorityResponse(responseJson, EVIDENCE_SCHEMA, "evidence");
    }

    private static JSONObject requireAuthorityResponse(
            String responseJson,
            String schema,
            String stage) throws Exception {
        if (responseJson == null || responseJson.isEmpty()) {
            throw new IllegalStateException("Manifold Runtime Host JNI " + stage + " failed");
        }
        JSONObject response = new JSONObject(responseJson);
        if (!schema.equals(response.optString("$schema", ""))
                || response.optBoolean("local_acceptance_rules", true)
                || !DECISION_OWNER.equals(response.optString("decision_owner_id", ""))) {
            throw new IllegalStateException("Manifold Runtime Host " + stage + " binding mismatch");
        }
        return response;
    }

    private static native String nativeInitialize(
            String configJson,
            String expectedConfigSha256,
            String epochEntropyHex);
    private static native String nativeMutate(String mutationJson, long nowMs);
    private static native String nativeCompleteMediaAction(String completionJson, long nowMs);
    private static native String nativeEvidence();
}
