package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Source-selected fresh issuance policy; never extends an already issued credential. */
public final class EmbeddedDuplexSetupCredentials {
    public static final long MIN_HORIZON_MS = 300_000L;
    public static final long MAX_HORIZON_MS = 3_600_000L;
    public final String policyId;
    public final long horizonMs, issuedAtMs, expiresAtMs;

    private EmbeddedDuplexSetupCredentials(boolean fixture, long horizonMs, long issuedAtMs, boolean development) {
        if (horizonMs < MIN_HORIZON_MS || horizonMs > (development ? selectedHorizon(MAX_HORIZON_MS) : MAX_HORIZON_MS) || horizonMs % 60_000L != 0L
                || fixture && horizonMs != MIN_HORIZON_MS || issuedAtMs <= 0L
                || issuedAtMs > Long.MAX_VALUE - horizonMs)
            throw new IllegalArgumentException("setup credential issuance bounds");
        this.policyId = fixture ? "local_diagnostic_five_minutes_v1" : development ? "development_real_peer_setup_v1" : "finite_real_peer_setup_v1";
        this.horizonMs = horizonMs;
        this.issuedAtMs = issuedAtMs;
        this.expiresAtMs = Math.addExact(issuedAtMs, horizonMs);
    }

    public static org.json.JSONObject developmentPolicy() {
        String raw = io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON;
        if (raw.isEmpty()) return null;
        try {
            org.json.JSONObject policy = new org.json.JSONObject(raw);
            long horizon = policy.getLong("delegated_authority_horizon_ms");
            if (policy.length() != 3
                    || !"rusty.manifold.development.lifetime_policy.v1".equals(policy.getString("$schema"))
                    || !"policy.manifold.development-lifetime.v1".equals(policy.getString("policy_id"))
                    || horizon <= MAX_HORIZON_MS || horizon % 60_000L != 0L
                    || horizon != io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.EMBEDDED_DUPLEX_SETUP_HORIZON_MS)
                throw new IllegalArgumentException("development lifetime source binding");
            return policy;
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("development lifetime contract", error);
        }
    }

    public static long selectedHorizon(long finiteMs) {
        org.json.JSONObject policy = developmentPolicy();
        if (policy == null) return finiteMs;
        try { return policy.getLong("delegated_authority_horizon_ms"); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    static EmbeddedDuplexSetupCredentials issueSelected(boolean fixture, long horizonMs, long issuedAtMs) {
        return new EmbeddedDuplexSetupCredentials(fixture, horizonMs, issuedAtMs,
                !fixture && developmentPolicy() != null);
    }

    static EmbeddedDuplexSetupCredentials issue(boolean fixture, long horizonMs, long issuedAtMs) {
        return new EmbeddedDuplexSetupCredentials(fixture, horizonMs, issuedAtMs, false);
    }
}
