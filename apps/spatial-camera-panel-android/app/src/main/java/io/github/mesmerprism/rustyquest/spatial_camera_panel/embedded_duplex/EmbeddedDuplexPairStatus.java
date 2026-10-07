package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Native-derived peer-session readback. A pair session is never a media route. */
public final class EmbeddedDuplexPairStatus {
    public final String state;
    public final String sessionId;
    public final boolean localSessionCurrent;
    public final boolean remoteSessionCurrent;
    public final long localSessionExpiresAtMs;
    public final String lastStep;
    public final String lastFailureCode;
    public final boolean routeCurrent;
    public final boolean mediaEffectProven;

    private EmbeddedDuplexPairStatus(String state, String sessionId, boolean localCurrent,
            boolean remoteCurrent, long expiresAtMs, String lastStep, String lastFailureCode) {
        if (!("not_started".equals(state) || "in_progress".equals(state)
                || "cleanup_pending".equals(state)
                || "peer_session_current_route_unverified".equals(state))
                || sessionId != null && !sessionId.matches("session\\.duplex\\.[0-9a-f]{64}")
                || expiresAtMs < 0L
                || lastStep != null && !lastStep.matches("[a-z][a-z0-9_]{0,63}")
                || lastFailureCode != null && !lastFailureCode.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("pair status invalid");
        }
        this.state = state;
        this.sessionId = sessionId;
        this.localSessionCurrent = localCurrent;
        this.remoteSessionCurrent = remoteCurrent;
        this.localSessionExpiresAtMs = expiresAtMs;
        this.lastStep = lastStep;
        this.lastFailureCode = lastFailureCode;
        this.routeCurrent = false;
        this.mediaEffectProven = false;
    }

    static EmbeddedDuplexPairStatus parse(String nativeJson) throws Exception {
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
                current, remoteCurrent, expires, lastStep, lastFailureCode);
    }
}
