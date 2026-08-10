package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Host fixture for the bounded Binder runtime-evidence compatibility projection. */
public final class ConnectionHubRuntimeEvidenceProjectionTest {
    public static void main(String[] args) throws Exception {
        JSONObject full = largeAuthorityEvidence();
        byte[] fullBytes = full.toString().getBytes(StandardCharsets.UTF_8);
        require(fullBytes.length > 522_144, "large retained-history fixture is too small");

        JSONObject projected = ConnectionHubRuntimeEvidenceProjection.project(full);
        byte[] projectedBytes = projected.toString().getBytes(StandardCharsets.UTF_8);
        require(projectedBytes.length <= ConnectionHubRuntimeEvidenceProjection.MAX_UTF8_BYTES,
                "projection exceeds its fixed Binder transport bound");
        require(ConnectionHubRuntimeEvidenceProjection.PROJECTION_SCHEMA.equals(
                        projected.getString("$schema")),
                "projection schema mismatch");
        require("binder_compatibility".equals(projected.getString("projection_kind")),
                "projection kind mismatch");
        require(!projected.getBoolean("local_acceptance_rules"),
                "projection introduced local acceptance");
        require(ConnectionHubRuntimeEvidenceProjection.DECISION_OWNER.equals(
                        projected.getString("decision_owner_id")),
                "projection changed the authority owner");

        JSONObject source = projected.getJSONObject("source_evidence");
        require(fullBytes.length == source.getInt("utf8_bytes"),
                "source byte binding mismatch");
        require(sha256Hex(fullBytes).equals(source.getString("sha256")),
                "source digest binding mismatch");
        JSONObject runtime = projected.getJSONObject("runtime");
        require(full.getJSONObject("runtime").getString("provider_epoch_id")
                        .equals(runtime.getString("provider_epoch_id")),
                "provider epoch was not preserved");
        require(runtime.getJSONObject("host_snapshot").getLong("authority_revision") == 17,
                "Runtime Host revision was not preserved");
        require(runtime.getJSONObject("admission_snapshot").getLong("authority_revision") == 23,
                "admission revision was not preserved");
        require(!projected.toString().contains("retained-history-entry-"),
                "authority history escaped into the Binder projection");
        require(!projected.getJSONObject("projection").getBoolean("authority_history_included"),
                "projection mislabeled omitted authority history");

        expectRejected(copy(full).put("$schema", "rusty.quest.broker.runtime_evidence.v0"));
        expectRejected(copy(full).put("local_acceptance_rules", true));
        expectRejected(copy(full).put("local_acceptance_rules", "false"));
        expectRejected(copy(full).put("decision_owner_id", "java.local.policy"));
        JSONObject missingRuntime = copy(full);
        missingRuntime.remove("runtime");
        expectRejected(missingRuntime);
        expectRejected(withRuntime(full, copy(full.getJSONObject("runtime"))
                .put("$schema", "rusty.manifold.broker.runtime_evidence.v4")));
        expectRejected(withRuntime(full, copy(full.getJSONObject("runtime"))
                .put("provider_epoch_id", "epoch.provider.bad\nmarker")));
        JSONObject missingHostSnapshot = copy(full.getJSONObject("runtime"));
        missingHostSnapshot.remove("host_snapshot");
        expectRejected(withRuntime(full, missingHostSnapshot));
        expectRejected(withRuntime(full, copy(full.getJSONObject("runtime"))
                .put("admission_snapshot", new JSONObject().put("authority_revision", -1))));
        expectRejected(withRuntime(full, copy(full.getJSONObject("runtime"))
                .put("admission_snapshot", new JSONObject().put("authority_revision", "23"))));

        JSONObject deliberatelyOversizedProjection = new JSONObject()
                .put("padding", repeat('x',
                        ConnectionHubRuntimeEvidenceProjection.MAX_UTF8_BYTES + 1));
        expectBoundRejected(deliberatelyOversizedProjection);
        System.out.println("Connection Hub bounded runtime evidence projection passed"
                + " sourceUtf8Bytes=" + fullBytes.length
                + " projectionUtf8Bytes=" + projectedBytes.length
                + " maxUtf8Bytes=" + ConnectionHubRuntimeEvidenceProjection.MAX_UTF8_BYTES);
    }

    private static JSONObject largeAuthorityEvidence() {
        JSONArray retained = new JSONArray();
        String payload = repeat('r', 1024);
        for (int index = 0; index < 640; index++) {
            retained.put(new JSONObject()
                    .put("receipt_id", "retained-history-entry-" + index)
                    .put("payload", payload));
        }
        JSONObject runtime = new JSONObject()
                .put("$schema", ConnectionHubRuntimeEvidenceProjection.SOURCE_RUNTIME_SCHEMA)
                .put("provider_epoch_id",
                        "epoch.provider.0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .put("host_snapshot", new JSONObject().put("authority_revision", 17))
                .put("admission_snapshot", new JSONObject().put("authority_revision", 23))
                .put("committed_mutation_receipts", retained);
        return new JSONObject()
                .put("$schema", ConnectionHubRuntimeEvidenceProjection.SOURCE_EVIDENCE_SCHEMA)
                .put("bridge_kind", "standalone_process_jni")
                .put("local_acceptance_rules", false)
                .put("decision_owner_id", ConnectionHubRuntimeEvidenceProjection.DECISION_OWNER)
                .put("runtime", runtime);
    }

    private static JSONObject copy(JSONObject value) {
        return new JSONObject(value.toString());
    }

    private static JSONObject withRuntime(JSONObject full, JSONObject runtime) {
        return copy(full).put("runtime", runtime);
    }

    private static void expectRejected(final JSONObject value) throws Exception {
        try {
            ConnectionHubRuntimeEvidenceProjection.project(value);
            throw new AssertionError("invalid authority evidence was projected");
        } catch (IllegalStateException expected) {
            // Expected fail-closed boundary.
        }
    }

    private static void expectBoundRejected(final JSONObject value) {
        try {
            ConnectionHubRuntimeEvidenceProjection.requireWithinBound(value);
            throw new AssertionError("oversized projection was accepted");
        } catch (IllegalStateException expected) {
            // Expected fail-closed boundary.
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
