package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Native-derived peer-session readback. A pair session is never a media route. */
public final class EmbeddedDuplexPairStatus {
    public final boolean selectedFreshPolicy;
    public final String exactNativeObservation;
    public final JSONObject historicalFailureCause;
    public final JSONObject freshSetupObservation;
    /** Optional elapsed-estimate observation; never session or cleanup authority. */
    public final Boolean ceremonyWaitWarning;
    public final String state;
    public final String sessionId;
    public final boolean localSessionCurrent;
    public final boolean remoteSessionCurrent;
    public final long localSessionExpiresAtMs;
    // Native enum observations are diagnostics, never renewed authority.
    public final String localSessionRejectionReason;
    public final String remoteSessionRejectionReason;
    public final long localSessionValidatedAtMs;
    public final long remoteSessionValidatedAtMs;
    public final String lastStep;
    public final String lastFailureCode;
    public final boolean routeCurrent;
    public final boolean mediaEffectProven;

    private EmbeddedDuplexPairStatus(String state, String sessionId, boolean localCurrent,
            boolean remoteCurrent, long expiresAtMs, String lastStep, String lastFailureCode,
            String localReason, String remoteReason, long localValidatedAtMs, long remoteValidatedAtMs,
            boolean freshPolicy,String exactNative,JSONObject cause,JSONObject freshObservation,
            Boolean ceremonyWaitWarning) {
        if (!("not_started".equals(state) || "in_progress".equals(state)
                || "cleanup_pending".equals(state)
                || "peer_session_current_route_unverified".equals(state))
                || sessionId != null && !sessionId.matches("session\\.duplex\\.[0-9a-f]{64}")
                || expiresAtMs < 0L
                || lastStep != null && !lastStep.matches("[a-z][a-z0-9_]{0,63}")
                || lastFailureCode != null && !lastFailureCode.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("pair status invalid");
        }
        this.selectedFreshPolicy=freshPolicy;this.exactNativeObservation=exactNative;
        this.historicalFailureCause=cause;this.freshSetupObservation=freshObservation;
        this.ceremonyWaitWarning = ceremonyWaitWarning;
        this.state = state;
        this.sessionId = sessionId;
        this.localSessionCurrent = localCurrent;
        this.remoteSessionCurrent = remoteCurrent;
        this.localSessionExpiresAtMs = expiresAtMs;
        this.localSessionRejectionReason = localReason;
        this.remoteSessionRejectionReason = remoteReason;
        this.localSessionValidatedAtMs = localValidatedAtMs;
        this.remoteSessionValidatedAtMs = remoteValidatedAtMs;
        this.lastStep = lastStep;
        this.lastFailureCode = lastFailureCode;
        this.routeCurrent = false;
        this.mediaEffectProven = false;
    }

    private static void requireObservationSchema(JSONObject receipt) {
        if (receipt != null && !"rusty.manifold.peer.session_current_receipt.v2"
                .equals(receipt.optString("$schema", null))) {
            throw new IllegalStateException("pair current-session schema mismatch");
        }
    }

    private static JSONObject observation(JSONObject value, String key) throws Exception {
        return observation(value,key,false);
    }
    private static JSONObject observation(JSONObject value,String key,boolean strictFresh) throws Exception {
        if (!value.has(key) || value.isNull(key)) return null;
        JSONObject receipt = value.getJSONObject(key);
        requireObservationSchema(receipt);
        if(strictFresh)requireCurrentReceipt(receipt);
        return receipt;
    }

    private static String rejectionReason(JSONObject receipt, String session) throws Exception {
        if (receipt == null) return null;
        // Reject a foreign observation even when its current flag is false.
        if (session == null || !session.equals(receipt.getString("session_id"))) {
            throw new IllegalStateException("pair rejection subject mismatch");
        }
        if (!receipt.has("rejection_reason") || receipt.isNull("rejection_reason")) return null;
        String reason = receipt.getString("rejection_reason");
        if (receipt.getBoolean("current") || !("not_found".equals(reason)
                || "revoked".equals(reason) || "expired".equals(reason)
                || "peer_not_current".equals(reason) || "rendezvous_not_current".equals(reason)
                || "topology_mismatch".equals(reason))) {
            throw new IllegalStateException("pair rejection code invalid");
        }
        return reason;
    }

    private static long validatedAt(JSONObject receipt) throws Exception {
        if (receipt == null || !receipt.has("validated_at_ms")) return 0L;
        long value = receipt.getLong("validated_at_ms");
        if (value < 0L) throw new IllegalStateException("pair observation time invalid");
        return value;
    }

    private static void keys(JSONObject value,String... expected){
        java.util.HashSet<String> actual=new java.util.HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!actual.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))))throw new IllegalArgumentException("pair closed fields");
    }
    private static void requireCurrentReceipt(JSONObject receipt)throws Exception{
        keys(receipt,"$schema","session_id","current","rejection_reason","decision_id","peer_ids",
            "topology_contract_id","validated_at_ms","expires_at_ms");
        if(!(receipt.get("current") instanceof Boolean) || receipt.getLong("validated_at_ms")<0
                || !receipt.isNull("expires_at_ms") && receipt.getLong("expires_at_ms")<0
                || !(receipt.isNull("decision_id") || receipt.get("decision_id") instanceof String)
                || !(receipt.isNull("topology_contract_id") || receipt.get("topology_contract_id") instanceof String))
            throw new IllegalArgumentException("pair current receipt fields");
        org.json.JSONArray peers=receipt.getJSONArray("peer_ids");
        for(int i=0;i<peers.length();i++)if(!(peers.get(i) instanceof String))throw new IllegalArgumentException("pair peer IDs");
        if(receipt.getBoolean("current") && !receipt.isNull("rejection_reason"))throw new IllegalArgumentException("current pair rejection");
        if(!receipt.getBoolean("current") && receipt.isNull("rejection_reason"))throw new IllegalArgumentException("noncurrent pair reason absent");
    }
    private static JSONObject requireFreshStatus(JSONObject value)throws Exception{
        if("rusty.quest.embedded_duplex.fresh_pair_setup_status.v2".equals(value.getString("$schema"))){
            EmbeddedDuplexFreshStatus.require(value);
            for(String key:new String[]{"local_current","remote_current","final_local_current"})observation(value,key,true);
            return value;
        }
        return requireFreshStatusV1(value);
    }
    private static Boolean waitWarning(JSONObject value) throws Exception {
        if (!value.has("ceremony_wait_warning")) return null;
        Object warning = value.get("ceremony_wait_warning");
        if (!(warning instanceof Boolean)) throw new IllegalArgumentException("pair wait warning Boolean");
        return (Boolean) warning;
    }
    private static JSONObject requireFreshStatusV1(JSONObject value)throws Exception{
        keys(value,"$schema","state","request_id","session_id","reservation","failure","local_setup","remote_setup",
            "local_current","remote_current","final_local_current","prior_pair","eligibility","completed_setups");
        if(!"rusty.quest.embedded_duplex.fresh_pair_setup_status.v1".equals(value.getString("$schema"))
                || !java.util.Arrays.asList("idle","pending_before_view","pending","completed").contains(value.getString("state"))
                || value.getLong("completed_setups")<0)throw new IllegalArgumentException("fresh pair status");
        if(!value.isNull("reservation")){
            JSONObject reservation=value.getJSONObject("reservation");
            keys(reservation,"id","session","original_binding","authority_or_signature_entered","declined_before_dispatch","failure","prime_pending","prime_progress");
            if(!(reservation.get("authority_or_signature_entered") instanceof Boolean)
                    || !(reservation.get("declined_before_dispatch") instanceof Boolean))throw new IllegalArgumentException("fresh reservation flags");
            if(!value.isNull("request_id") && !value.getString("request_id").equals(reservation.getString("id")))
                throw new IllegalArgumentException("fresh reservation request differs");
            if(!reservation.isNull("prime_pending"))keys(reservation.getJSONObject("prime_pending"),"step","request","entered");
            org.json.JSONArray progress=reservation.getJSONArray("prime_progress");
            for(int i=0;i<progress.length();i++)keys(progress.getJSONObject(i),"step","request","receipt");
        }
        for(String key:new String[]{"local_current","remote_current","final_local_current"})observation(value,key,true);
        return value;
    }
    static EmbeddedDuplexPairStatus parseFresh(String nativeJson) throws Exception {
        JSONObject value = new JSONObject(nativeJson);
        String schema = value.getString("$schema");
        boolean freshPolicy="rusty.quest.embedded_duplex.pair_status.v2".equals(schema);
        boolean freshResultV2="rusty.quest.embedded_duplex.fresh_pair_setup_result.v2".equals(schema);
        boolean freshResult=freshResultV2 || "rusty.quest.embedded_duplex.fresh_pair_setup_result.v1".equals(schema);
        JSONObject cause=null,freshObservation=null;
        Boolean ceremonyWaitWarning = waitWarning(value);
        if(freshPolicy){
            String[] fields = {"$schema","state","session_id","native_current_session","last_step","last_failure_code",
                "route_current","media_effect_proven","renewal","last_failure_cause","fresh_setup"};
            if (value.has("ceremony_wait_warning")) {
                fields = java.util.Arrays.copyOf(fields, fields.length + 1);
                fields[fields.length - 1] = "ceremony_wait_warning";
            }
            keys(value, fields);
            freshObservation=requireFreshStatus(value.getJSONObject("fresh_setup"));
            if(!value.isNull("last_failure_cause"))cause=new EmbeddedDuplexNativeOperationFailure(value.getJSONObject("last_failure_cause").toString()).validatedFailure();
        }else if(freshResult){
            if(freshResultV2){
                keys(value,"$schema","request_id","session_id","state","local_current_session","remote_current_session",
                    "final_local_current_session","final_remote_current_session","route_current","media_effect_proven","phase_request_id","phase_archive");
                EmbeddedDuplexIncomingFreshPhases.id(value.getString("phase_request_id"));EmbeddedDuplexFreshStatus.cursor(value.getJSONObject("phase_archive"));
            }else keys(value,"$schema","request_id","session_id","state","local_current_session","remote_current_session",
                "final_local_current_session","final_remote_current_session","route_current","media_effect_proven");
            observation(value,"local_current_session",true);observation(value,"remote_current_session",true);
        }
        if (!(freshPolicy || freshResult || "rusty.quest.embedded_duplex.pair_status.v1".equals(schema)
                || "rusty.quest.embedded_duplex.pair_ceremony_result.v1".equals(schema))
                || value.getBoolean("route_current") || value.getBoolean("media_effect_proven")) {
            throw new IllegalStateException("pair readback contract mismatch");
        }
        JSONObject local = observation(value, "native_current_session",freshPolicy || freshResult);
        JSONObject ceremonyLocal = observation(value, "local_current_session",freshPolicy || freshResult);
        if (local == null) local = ceremonyLocal;
        JSONObject remote = observation(value, "remote_current_session",freshPolicy || freshResult);
        if(freshResult){local=observation(value,"final_local_current_session",true);remote=observation(value,"final_remote_current_session",true);}
        else if(freshPolicy && "completed".equals(freshObservation.getString("state"))) {
            // Native current-local is a new actual read. The retained remote observation is never refreshed here.
            remote=observation(freshObservation,"remote_current",true);
        }
        boolean current = local != null && local.getBoolean("current");
        boolean remoteCurrent = remote != null && remote.getBoolean("current");
        String session = value.isNull("session_id") ? null : value.getString("session_id");
        if (current && (session == null || !session.equals(local.getString("session_id")))
                || remoteCurrent && (session == null || !session.equals(remote.getString("session_id")))) {
            throw new IllegalStateException("pair session readback mismatch");
        }
        long expires = current && !local.isNull("expires_at_ms")
                ? local.getLong("expires_at_ms") : 0L;
        String lastStep = value.has("last_step") && !value.isNull("last_step")
                ? value.getString("last_step") : null;
        String lastFailureCode = value.has("last_failure_code") && !value.isNull("last_failure_code")
                ? value.getString("last_failure_code") : null;
        return new EmbeddedDuplexPairStatus(value.getString("state"), session,
                current, remoteCurrent, expires, lastStep, lastFailureCode,
                rejectionReason(local, session), rejectionReason(remote, session),
                validatedAt(local), validatedAt(remote),freshPolicy || freshResult,nativeJson,cause,freshObservation,
                ceremonyWaitWarning);
    }


    static EmbeddedDuplexPairStatus parseLegacy(String nativeJson) throws Exception {
        JSONObject value = new JSONObject(nativeJson);
        String schema = value.getString("$schema");
        if (!("rusty.quest.embedded_duplex.pair_status.v1".equals(schema)
                || "rusty.quest.embedded_duplex.pair_ceremony_result.v1".equals(schema))
                || value.getBoolean("route_current") || value.getBoolean("media_effect_proven")) {
            throw new IllegalStateException("pair readback contract mismatch");
        }
        JSONObject local = value.optJSONObject("native_current_session");
        if (local == null) local = value.optJSONObject("local_current_session");
        JSONObject remote = value.optJSONObject("remote_current_session");
        boolean current = local != null && local.getBoolean("current");
        boolean remoteCurrent = remote != null && remote.getBoolean("current");
        String session = value.isNull("session_id") ? null : value.getString("session_id");
        if (current && (session == null || !session.equals(local.getString("session_id")))
                || remoteCurrent && (session == null || !session.equals(remote.getString("session_id")))) {
            throw new IllegalStateException("pair session readback mismatch");
        }
        long expires = current && !local.isNull("expires_at_ms")
                ? local.getLong("expires_at_ms") : 0L;
        String lastStep = value.has("last_step") && !value.isNull("last_step")
                ? value.getString("last_step") : null;
        String lastFailureCode = value.has("last_failure_code") && !value.isNull("last_failure_code")
                ? value.getString("last_failure_code") : null;
        return new EmbeddedDuplexPairStatus(value.getString("state"), session,
                current, remoteCurrent, expires, lastStep, lastFailureCode, waitWarning(value));
    }
    private EmbeddedDuplexPairStatus(String state,String sessionId,boolean localCurrent,boolean remoteCurrent,
            long expiresAtMs,String lastStep,String lastFailureCode,Boolean ceremonyWaitWarning) {
        this(state,sessionId,localCurrent,remoteCurrent,expiresAtMs,lastStep,lastFailureCode,null,null,0L,0L,false,null,null,null,ceremonyWaitWarning);
    }
    static EmbeddedDuplexPairStatus parse(String nativeJson) throws Exception {
        String schema=new JSONObject(nativeJson).getString("$schema");
        return schema.equals("rusty.quest.embedded_duplex.pair_status.v2") || schema.startsWith("rusty.quest.embedded_duplex.fresh_pair_setup_result.")
                ? parseFresh(nativeJson) : parseLegacy(nativeJson);
    }
}
