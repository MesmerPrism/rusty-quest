package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Finite fresh-bootstrap issuance policy; never extends an already issued credential. */
public final class EmbeddedDuplexSetupCredentials {
    public static final long MIN_HORIZON_MS = 300_000L;
    public static final long MAX_HORIZON_MS = 3_600_000L;
    public final String policyId;
    public final long horizonMs, issuedAtMs, expiresAtMs;

    private EmbeddedDuplexSetupCredentials(boolean fixture, long horizonMs, long issuedAtMs) {
        if (horizonMs < MIN_HORIZON_MS || horizonMs > MAX_HORIZON_MS || horizonMs % 60_000L != 0L
                || fixture && horizonMs != MIN_HORIZON_MS || issuedAtMs <= 0L
                || issuedAtMs > Long.MAX_VALUE - horizonMs)
            throw new IllegalArgumentException("setup credential issuance bounds");
        this.policyId = fixture ? "local_diagnostic_five_minutes_v1" : "finite_real_peer_setup_v1";
        this.horizonMs = horizonMs;
        this.issuedAtMs = issuedAtMs;
        this.expiresAtMs = Math.addExact(issuedAtMs, horizonMs);
    }

    static EmbeddedDuplexSetupCredentials issue(boolean fixture, long horizonMs, long issuedAtMs) {
        return new EmbeddedDuplexSetupCredentials(fixture, horizonMs, issuedAtMs);
    }
}
