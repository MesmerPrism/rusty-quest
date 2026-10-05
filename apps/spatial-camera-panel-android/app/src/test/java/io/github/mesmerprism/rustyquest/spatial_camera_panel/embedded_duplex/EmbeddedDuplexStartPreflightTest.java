package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmbeddedDuplexStartPreflightTest {
    private static final String SESSION = "session.duplex." + "a".repeat(64);
    private static final String CONFIG = "b".repeat(64);
    private static final String ENROLLMENT = "c".repeat(64);

    private static EmbeddedDuplexPairStatus pair(boolean current) throws Exception {
        return pair(current, 100000L);
    }

    private static EmbeddedDuplexPairStatus pair(boolean current, long expiry) throws Exception {
        return EmbeddedDuplexPairStatus.parse("{\"$schema\":\"rusty.quest.embedded_duplex.pair_status.v1\","
                + "\"state\":\"" + (current ? "peer_session_current_route_unverified" : "in_progress") + "\","
                + "\"session_id\":\"" + SESSION + "\","
                + "\"native_current_session\":{\"current\":" + current + ","
                + "\"session_id\":\"" + SESSION + "\",\"expires_at_ms\":" + expiry + "},"
                + "\"route_current\":false,\"media_effect_proven\":false}");
    }

    @Test public void currentSignedSessionRetainsExactPreStartLineage() throws Exception {
        EmbeddedDuplexStartPreflight decision = EmbeddedDuplexStartPreflight.prepare(
                pair(true), CONFIG, ENROLLMENT, 7L);
        assertEquals("start_preflight_intent", decision.state);
        assertEquals(SESSION, decision.sessionId);
        assertEquals(100000L, decision.sessionExpiresAtMs);
        assertEquals(CONFIG, decision.runtimeConfigSha256);
        assertEquals(ENROLLMENT, decision.enrollmentRecordSha256);
        assertTrue(decision.matches(CONFIG, ENROLLMENT, 7L));
        assertFalse(decision.matches(CONFIG, ENROLLMENT, 8L));
        assertFalse(decision.matches("d".repeat(64), ENROLLMENT, 7L));
        assertTrue(decision.sameLineage(EmbeddedDuplexStartPreflight.prepare(
                pair(true), CONFIG, ENROLLMENT, 7L)));
        assertFalse(decision.sameLineage(EmbeddedDuplexStartPreflight.prepare(
                pair(true, 100001L), CONFIG, ENROLLMENT, 7L)));
        assertFalse(decision.peerRouteProven);
        assertFalse(decision.mediaEffectProven);
    }

    @Test public void pendingOrUnboundSessionCannotPrepareStart() throws Exception {
        assertThrows(IllegalStateException.class, () ->
                EmbeddedDuplexStartPreflight.prepare(pair(false), CONFIG, ENROLLMENT, 7L));
        assertThrows(IllegalStateException.class, () ->
                EmbeddedDuplexStartPreflight.prepare(pair(true), CONFIG, null, 7L));
        assertThrows(IllegalStateException.class, () ->
                EmbeddedDuplexStartPreflight.prepare(pair(true), CONFIG, ENROLLMENT, 0L));
    }
}
