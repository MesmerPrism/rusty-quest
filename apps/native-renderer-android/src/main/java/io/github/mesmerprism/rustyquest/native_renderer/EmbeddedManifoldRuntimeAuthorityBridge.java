package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;

/** Embedded JNI transport; all admission and acceptance decisions remain in Rust. */
public final class EmbeddedManifoldRuntimeAuthorityBridge {
    private static final String INITIALIZE_SCHEMA =
            "rusty.quest.broker.runtime_initialize_status.v1";
    private static final String ADMISSION_SCHEMA =
            "rusty.quest.broker.admission_response.v1";
    private static final String MUTATION_SCHEMA =
            "rusty.quest.broker.server_mutation_response.v1";
    private static final String EVIDENCE_SCHEMA =
            "rusty.quest.broker.runtime_evidence.v1";
    private static final String DECISION_OWNER = "module.runtime.host";
    private static final String EPOCH_ENTROPY_HEX = epochEntropy();

    static {
        System.loadLibrary("rusty_quest_native_renderer");
    }

    private EmbeddedManifoldRuntimeAuthorityBridge() {
    }

    public static JSONObject initialize() throws Exception {
        return requireRuntimeResponse(
                nativeInitialize(
                        GeneratedEmbeddedManifoldRuntimeConfig.JSON,
                        GeneratedEmbeddedManifoldRuntimeConfig.SHA256,
                        EPOCH_ENTROPY_HEX),
                INITIALIZE_SCHEMA,
                "initialization");
    }

    public static JSONObject admit(JSONObject operation) throws Exception {
        String responseJson = nativeAdmit(operation.toString());
        if (responseJson == null || responseJson.isEmpty()) {
            throw new IllegalStateException("embedded Manifold admission JNI failed");
        }
        JSONObject response = new JSONObject(responseJson);
        if (!ADMISSION_SCHEMA.equals(response.optString("$schema", ""))
                || response.optBoolean("local_token_or_grant_policy", true)
                || !"rusty.manifold.admission".equals(
                        response.optString("decision_owner", ""))) {
            throw new IllegalStateException("embedded Manifold admission binding mismatch");
        }
        return response;
    }

    public static JSONObject mutate(JSONObject request) throws Exception {
        return requireRuntimeResponse(
                nativeMutate(request.toString(), System.currentTimeMillis()),
                MUTATION_SCHEMA,
                "mutation");
    }

    public static JSONObject completeMediaAction(JSONObject request) throws Exception {
        return requireRuntimeResponse(
                nativeCompleteMediaAction(request.toString(), System.currentTimeMillis()),
                "rusty.quest.broker.media_completion_response.v1",
                "media completion");
    }

    public static JSONObject evidence() throws Exception {
        return requireRuntimeResponse(nativeEvidence(), EVIDENCE_SCHEMA, "evidence");
    }

    private static JSONObject requireRuntimeResponse(
            String responseJson,
            String schema,
            String stage) throws Exception {
        if (responseJson == null || responseJson.isEmpty()) {
            throw new IllegalStateException("embedded Manifold Runtime Host " + stage + " failed");
        }
        JSONObject response = new JSONObject(responseJson);
        if (!schema.equals(response.optString("$schema", ""))
                || response.optBoolean("local_acceptance_rules", true)
                || !DECISION_OWNER.equals(response.optString("decision_owner_id", ""))) {
            throw new IllegalStateException("embedded Manifold Runtime Host " + stage + " mismatch");
        }
        return response;
    }

    private static String epochEntropy() {
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        StringBuilder builder = new StringBuilder(64);
        for (byte value : entropy) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static native String nativeInitialize(
            String configJson,
            String expectedConfigSha256,
            String epochEntropyHex);
    private static native String nativeAdmit(String operationJson);
    private static native String nativeMutate(String mutationJson, long nowMs);
    private static native String nativeCompleteMediaAction(String completionJson, long nowMs);
    private static native String nativeEvidence();
}
