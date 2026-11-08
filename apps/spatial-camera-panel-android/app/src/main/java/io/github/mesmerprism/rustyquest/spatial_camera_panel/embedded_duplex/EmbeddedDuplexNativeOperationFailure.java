package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Fixed JNI error carrier. Its full closed payload is validated by the original owner. */
public final class EmbeddedDuplexNativeOperationFailure extends IllegalStateException {
    public EmbeddedDuplexNativeOperationFailure(String serializedFailure) {
        super(serializedFailure);
    }

    org.json.JSONObject validatedFailure() throws Exception {
        if (getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32*1024)
            throw new IllegalArgumentException("native failure byte bound");
        org.json.JSONObject value = new org.json.JSONObject(getMessage());
        if ("rusty.quest.embedded_duplex.operation_failure.v2".equals(value.optString("$schema"))) {
            requireKeys(value, "$schema", "code", "operation", "diagnostic");
            if (!"fresh_setup_rejected".equals(string(value,"code")) || !"fresh_pair_setup".equals(string(value,"operation")))
                throw new IllegalArgumentException("fresh failure operation");
            org.json.JSONObject d=value.getJSONObject("diagnostic");
            requireKeys(d,"owner","stage","reason","request_id","phase_id","original_id","app_generation",
                "native_generation","app_record_sha256","config_sha256");
            String stage=string(d,"stage");
            if (!java.util.Set.of("host_lookup","clock","input","flight","initiator","allocate_identity","current_phase",
                "stage_successor","archive_prior","begin","begin_capability","begin_eligibility","begin_physical",
                "begin_binding","begin_reservation","begin_authority","view_exchange","context","finish_phase",
                "archive_receipt","archive_renewal","archive_java","archive_ack","archive_history","archive_lifetime","current_validation").contains(stage)
                || !java.util.Set.of("owner_rejected","guard_refused","receipt_mismatch","binding_mismatch").contains(string(d,"reason")))
                throw new IllegalArgumentException("fresh failure stage/reason");
            String owner=java.util.Set.of("archive_java","begin_physical").contains(stage)?"quest_java":
                java.util.Set.of("archive_receipt","archive_history","begin_authority").contains(stage)?"manifold":"quest_native";
            if(!owner.equals(string(d,"owner")))throw new IllegalArgumentException("fresh failure owner");
            for(String k:new String[]{"request_id","phase_id"})if(!d.isNull(k)&&!string(d,k).matches("[a-z][a-z0-9_.-]{0,255}"))
                throw new IllegalArgumentException("fresh failure request/phase");
            if(!d.isNull("original_id")&&!string(d,"original_id").matches("[0-9a-f]{32}"))throw new IllegalArgumentException("fresh failure Original");
            for(String k:new String[]{"app_generation","native_generation"})if(!d.isNull(k)&&(!integer(d.get(k))||d.getLong(k)==0))
                throw new IllegalArgumentException("fresh failure generation");
            if(!d.isNull("app_record_sha256")&&!string(d,"app_record_sha256").matches("[0-9a-f]{64}")
                ||!d.isNull("config_sha256")&&!string(d,"config_sha256").matches("(?:sha256:)?[0-9a-f]{64}"))
                throw new IllegalArgumentException("fresh failure digest");
            return value;
        }
        requireKeys(value, "$schema", "code", "operation", "session_id", "observed_at_ms", "current_session");
        if (!"rusty.quest.embedded_duplex.operation_failure.v1".equals(string(value,"$schema"))
                || !java.util.Arrays.asList("runtime_command","pair_ceremony","pair_status","renew_authority","fresh_pair_setup","fresh_pair_continue","reestablish_peer_roots","prepare_peer_start","abort_prepared_peer_start")
                    .contains(string(value,"operation"))) throw new IllegalArgumentException("native failure schema");
        String code = string(value,"code");
        if (code.equals("rejected_unknown")) {
            if (!value.isNull("session_id") || !value.isNull("observed_at_ms") || !value.isNull("current_session"))
                throw new IllegalArgumentException("native unknown failure context");
        } else if (code.equals("pair_session_not_current")) {
            org.json.JSONObject receipt = value.getJSONObject("current_session");
            requireKeys(receipt, "$schema", "session_id", "current", "rejection_reason", "decision_id", "peer_ids",
                    "topology_contract_id", "validated_at_ms", "expires_at_ms");
            if (!"rusty.manifold.peer.session_current_receipt.v2".equals(string(receipt,"$schema"))
                    || !(receipt.get("current") instanceof Boolean) || receipt.getBoolean("current")
                    || !string(value,"session_id").equals(string(receipt,"session_id"))
                    || !java.util.Arrays.asList("not_found","revoked","expired","peer_not_current",
                        "rendezvous_not_current","topology_mismatch").contains(string(receipt,"rejection_reason"))
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
    private static String string(org.json.JSONObject value,String key) throws Exception {
        Object field=value.get(key);
        // Android JSONObject.getString can coerce Boolean/Number values.
        if(!(field instanceof String))throw new IllegalArgumentException("native failure String type");
        return (String)field;
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
