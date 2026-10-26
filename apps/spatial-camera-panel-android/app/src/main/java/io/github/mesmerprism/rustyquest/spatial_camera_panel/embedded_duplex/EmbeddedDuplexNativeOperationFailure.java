package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Fixed JNI error carrier. Its full closed payload is validated by the original owner. */
public final class EmbeddedDuplexNativeOperationFailure extends IllegalStateException {
    public EmbeddedDuplexNativeOperationFailure(String serializedFailure) {
        super(serializedFailure);
    }

    org.json.JSONObject validatedFailure() throws Exception {
        org.json.JSONObject value = new org.json.JSONObject(getMessage());
        requireKeys(value, "$schema", "code", "operation", "session_id", "observed_at_ms", "current_session");
        if (!"rusty.quest.embedded_duplex.operation_failure.v1".equals(value.getString("$schema"))
                || !java.util.Arrays.asList("runtime_command","pair_ceremony","pair_status","renew_authority","fresh_pair_setup","fresh_pair_continue","reestablish_peer_roots","prepare_peer_start","abort_prepared_peer_start")
                    .contains(value.getString("operation"))) throw new IllegalArgumentException("native failure schema");
        String code = value.getString("code");
        if (code.equals("rejected_unknown")) {
            if (!value.isNull("session_id") || !value.isNull("observed_at_ms") || !value.isNull("current_session"))
                throw new IllegalArgumentException("native unknown failure context");
        } else if (code.equals("pair_session_not_current")) {
            org.json.JSONObject receipt = value.getJSONObject("current_session");
            requireKeys(receipt, "$schema", "session_id", "current", "rejection_reason", "decision_id", "peer_ids",
                    "topology_contract_id", "validated_at_ms", "expires_at_ms");
            if (!"rusty.manifold.peer.session_current_receipt.v2".equals(receipt.getString("$schema"))
                    || !(receipt.get("current") instanceof Boolean) || receipt.getBoolean("current")
                    || !value.getString("session_id").equals(receipt.getString("session_id"))
                    || !java.util.Arrays.asList("not_found","revoked","expired","peer_not_current",
                        "rendezvous_not_current","topology_mismatch").contains(receipt.getString("rejection_reason"))
                    || !integer(value.get("observed_at_ms")) || !integer(receipt.get("validated_at_ms"))
                    || value.getLong("observed_at_ms") != receipt.getLong("validated_at_ms"))
                throw new IllegalArgumentException("native pair failure context");
            // Preserve all actual subject/decision/expiry fields, including null Options and zero observation time.
            if (!(receipt.get("session_id") instanceof String)
                    || !(receipt.isNull("decision_id") || receipt.get("decision_id") instanceof String)
                    || !(receipt.isNull("topology_contract_id") || receipt.get("topology_contract_id") instanceof String)
                    || !(receipt.isNull("expires_at_ms") || integer(receipt.get("expires_at_ms"))))
                throw new IllegalArgumentException("native pair failure subject");
            org.json.JSONArray peers = receipt.getJSONArray("peer_ids");
            for (int i=0;i<peers.length();i++) if (!(peers.get(i) instanceof String))
                throw new IllegalArgumentException("native pair peer subject");
        } else throw new IllegalArgumentException("native failure code");
        return value;
    }
    private static boolean integer(Object value) {
        return (value instanceof Integer || value instanceof Long) && ((Number)value).longValue() >= 0;
    }
    private static void requireKeys(org.json.JSONObject value, String... expected) {
        java.util.HashSet<String> keys = new java.util.HashSet<>(); value.keys().forEachRemaining(keys::add);
        if (!keys.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))))
            throw new IllegalArgumentException("native failure closed fields");
    }
}
