package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Process-held pre-Start intent. It grants no route or media owner effect. */
public final class EmbeddedDuplexStartPreflight {
    public final String state;
    public final String sessionId;
    public final long sessionExpiresAtMs;
    public final String runtimeConfigSha256;
    public final String enrollmentRecordSha256;
    public final long displayGeneration;
    public final boolean peerRouteProven;
    public final boolean mediaEffectProven;

    private EmbeddedDuplexStartPreflight(EmbeddedDuplexPairStatus pair, String configSha,
            String enrollmentSha, long generation) {
        this.state = "start_preflight_intent";
        this.sessionId = pair.sessionId;
        this.sessionExpiresAtMs = pair.localSessionExpiresAtMs;
        this.runtimeConfigSha256 = configSha;
        this.enrollmentRecordSha256 = enrollmentSha;
        this.displayGeneration = generation;
        this.peerRouteProven = false;
        this.mediaEffectProven = false;
    }

    static EmbeddedDuplexStartPreflight prepare(EmbeddedDuplexPairStatus pair, String configSha,
            String enrollmentSha, long generation) {
        if (pair == null || !pair.localSessionCurrent
                || !"peer_session_current_route_unverified".equals(pair.state)
                || pair.routeCurrent || pair.mediaEffectProven
                || pair.sessionId == null || pair.localSessionExpiresAtMs <= 0L
                || configSha == null || !configSha.matches("[0-9a-f]{64}")
                || enrollmentSha == null || !enrollmentSha.matches("[0-9a-f]{64}")
                || generation <= 0L) {
            throw new IllegalStateException("current paired pre-Start lineage unavailable");
        }
        return new EmbeddedDuplexStartPreflight(pair, configSha, enrollmentSha, generation);
    }

    boolean matches(String configSha, String enrollmentSha, long generation) {
        return runtimeConfigSha256.equals(configSha)
                && enrollmentRecordSha256.equals(enrollmentSha)
                && displayGeneration == generation;
    }
}
