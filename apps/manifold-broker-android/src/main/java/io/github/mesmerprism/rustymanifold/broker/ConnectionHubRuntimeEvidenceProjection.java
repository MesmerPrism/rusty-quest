package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/** Bounded compatibility projection of full Rust authority evidence for Binder clients. */
public final class ConnectionHubRuntimeEvidenceProjection {
    static final String PROJECTION_SCHEMA =
            "rusty.quest.broker.runtime_evidence.transport_projection.v1";
    static final String RUNTIME_PROJECTION_SCHEMA =
            "rusty.quest.broker.runtime_evidence.runtime_projection.v1";
    static final String SOURCE_EVIDENCE_SCHEMA =
            "rusty.quest.broker.runtime_evidence.v1";
    static final String SOURCE_RUNTIME_SCHEMA =
            "rusty.manifold.broker.runtime_evidence.v5";
    static final String DECISION_OWNER = "module.runtime.host";
    static final int MAX_UTF8_BYTES = 32 * 1024;

    private static final Pattern DOTTED_ID =
            Pattern.compile("^[a-z0-9](?:[a-z0-9._-]{0,254}[a-z0-9])?$");
    private static final Pattern BRIDGE_KIND = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern REVISION = Pattern.compile("^(?:0|[1-9][0-9]{0,18})$");

    private ConnectionHubRuntimeEvidenceProjection() {
    }

    public static JSONObject project(JSONObject authorityEvidence) throws Exception {
        if (authorityEvidence == null
                || !hasExactString(authorityEvidence, "$schema", SOURCE_EVIDENCE_SCHEMA)
                || !Boolean.FALSE.equals(authorityEvidence.opt("local_acceptance_rules"))
                || !hasExactString(authorityEvidence, "decision_owner_id", DECISION_OWNER)) {
            throw new IllegalStateException("runtime evidence authority binding mismatch");
        }

        String bridgeKind = requireBridgeKind(authorityEvidence, "bridge_kind");
        JSONObject runtime = requireObject(authorityEvidence, "runtime");
        if (!hasExactString(runtime, "$schema", SOURCE_RUNTIME_SCHEMA)) {
            throw new IllegalStateException("runtime evidence source schema mismatch");
        }
        String providerEpochId = requireDottedId(runtime, "provider_epoch_id");
        long hostRevision = requireRevision(requireObject(runtime, "host_snapshot"));
        long admissionRevision = requireRevision(requireObject(runtime, "admission_snapshot"));

        byte[] sourceBytes = authorityEvidence.toString().getBytes(StandardCharsets.UTF_8);
        JSONObject projection = new JSONObject()
                .put("$schema", PROJECTION_SCHEMA)
                .put("projection_kind", "binder_compatibility")
                .put("bridge_kind", bridgeKind)
                .put("local_acceptance_rules", false)
                .put("decision_owner_id", DECISION_OWNER)
                .put("source_evidence", new JSONObject()
                        .put("schema_id", SOURCE_EVIDENCE_SCHEMA)
                        .put("runtime_schema_id", SOURCE_RUNTIME_SCHEMA)
                        .put("sha256", sha256Hex(sourceBytes))
                        .put("utf8_bytes", sourceBytes.length))
                .put("runtime", new JSONObject()
                        .put("$schema", RUNTIME_PROJECTION_SCHEMA)
                        .put("provider_epoch_id", providerEpochId)
                        .put("host_snapshot", new JSONObject()
                                .put("authority_revision", hostRevision))
                        .put("admission_snapshot", new JSONObject()
                                .put("authority_revision", admissionRevision)))
                .put("projection", new JSONObject()
                        .put("max_utf8_bytes", MAX_UTF8_BYTES)
                        .put("authority_history_included", false)
                        .put("full_evidence_retained_by_authority", true));
        requireWithinBound(projection);
        return projection;
    }

    static void requireWithinBound(JSONObject projection) {
        int bytes = projection.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_UTF8_BYTES) {
            throw new IllegalStateException("runtime evidence Binder projection exceeds bound");
        }
    }

    private static JSONObject requireObject(JSONObject parent, String key) {
        Object value = parent.opt(key);
        if (!(value instanceof JSONObject)) {
            throw new IllegalStateException("runtime evidence missing required object");
        }
        return (JSONObject) value;
    }

    private static boolean hasExactString(JSONObject parent, String key, String expected) {
        Object value = parent.opt(key);
        return value instanceof String && expected.equals(value);
    }

    private static String requireDottedId(JSONObject parent, String key) {
        Object value = parent.opt(key);
        if (!(value instanceof String) || !DOTTED_ID.matcher((String) value).matches()) {
            throw new IllegalStateException("runtime evidence identifier mismatch");
        }
        return (String) value;
    }

    private static String requireBridgeKind(JSONObject parent, String key) {
        Object value = parent.opt(key);
        if (!(value instanceof String) || !BRIDGE_KIND.matcher((String) value).matches()) {
            throw new IllegalStateException("runtime evidence bridge mismatch");
        }
        return (String) value;
    }

    private static long requireRevision(JSONObject snapshot) {
        Object value = snapshot.opt("authority_revision");
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double) {
            throw new IllegalStateException("runtime evidence revision type mismatch");
        }
        String encoded = value.toString();
        if (!REVISION.matcher(encoded).matches()) {
            throw new IllegalStateException("runtime evidence revision value mismatch");
        }
        try {
            return Long.parseLong(encoded);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("runtime evidence revision exceeds signed long", invalid);
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}
