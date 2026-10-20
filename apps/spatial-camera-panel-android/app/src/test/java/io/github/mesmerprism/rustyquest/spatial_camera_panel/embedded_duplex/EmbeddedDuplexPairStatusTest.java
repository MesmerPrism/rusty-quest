package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

/** Proposed parser controls only; modeled typed receipts, no native authority/effects. */
public final class EmbeddedDuplexPairStatusTest {
    private static final String SCHEMA = "rusty.manifold.peer.session_current_receipt.v2";
    private static final String SESSION = "session.duplex." + "a".repeat(64);
    private static final String FOREIGN = "session.duplex." + "b".repeat(64);
    private static final long OBSERVED = 60000L;
    private static final String[] REASONS = {"not_found", "revoked", "expired",
            "peer_not_current", "rendezvous_not_current", "topology_mismatch"};

    private static JSONObject observation(boolean current, String reason) throws Exception {
        return new JSONObject().put("$schema", SCHEMA).put("session_id", SESSION)
                .put("current", current).put("rejection_reason", reason == null ? JSONObject.NULL : reason)
                .put("decision_id", current ? "decision.test.pair" : JSONObject.NULL)
                .put("peer_ids", current ? new org.json.JSONArray().put("peer.a").put("peer.b") : new org.json.JSONArray())
                .put("topology_contract_id", current ? "topology.test.pair" : JSONObject.NULL)
                .put("validated_at_ms", OBSERVED)
                .put("expires_at_ms", current ? 100000L : JSONObject.NULL);
    }

    private static EmbeddedDuplexPairStatus parse(String localKey, JSONObject local,
            JSONObject remote) throws Exception {
        JSONObject outer = new JSONObject()
                .put("$schema", localKey.equals("local_current_session")
                        ? "rusty.quest.embedded_duplex.pair_ceremony_result.v1"
                        : "rusty.quest.embedded_duplex.pair_status.v1")
                .put("state", "cleanup_pending").put("session_id", SESSION)
                .put("route_current", false).put("media_effect_proven", false);
        if (local != null) outer.put(localKey, local);
        if (remote != null) outer.put("remote_current_session", remote);
        return EmbeddedDuplexPairStatus.parse(outer.toString());
    }

    @Test public void sixExistingCodesRetainPeerAndOriginalObservationTime() throws Exception {
        for (String key : new String[]{"native_current_session", "local_current_session"}) {
            for (String reason : REASONS) {
                EmbeddedDuplexPairStatus result = parse(key,
                        observation(false, reason), observation(false, reason));
                assertEquals(reason, result.localSessionRejectionReason);
                assertEquals(reason, result.remoteSessionRejectionReason);
                assertEquals(OBSERVED, result.localSessionValidatedAtMs);
                assertEquals(OBSERVED, result.remoteSessionValidatedAtMs);
                assertEquals(0L, result.localSessionExpiresAtMs);
                assertFalse(result.localSessionCurrent); assertFalse(result.remoteSessionCurrent);
                assertFalse(result.routeCurrent); assertFalse(result.mediaEffectProven);
            }
        }
    }

    @Test public void validShapeWrongOrMissingNestedSchemaHitsSchemaGuard() throws Exception {
        for (String key : new String[]{"native_current_session", "local_current_session"}) {
            for (boolean current : new boolean[]{false, true}) {
                for (boolean remote : new boolean[]{false, true}) {
                    JSONObject bad = observation(current, current ? null : "expired");
                    bad.put("$schema", "rusty.manifold.peer.session_current_receipt.v999");
                    IllegalStateException failure = assertThrows(IllegalStateException.class,
                            () -> parse(key, remote ? null : bad, remote ? bad : null));
                    assertEquals("pair current-session schema mismatch", failure.getMessage());
                    bad.remove("$schema");
                    failure = assertThrows(IllegalStateException.class,
                            () -> parse(key, remote ? null : bad, remote ? bad : null));
                    assertEquals("pair current-session schema mismatch", failure.getMessage());
                }
            }
        }
        // Even an unselected alias is present data: native-local preference may
        // not hide a differently typed ceremony-local observation.
        JSONObject outer = new JSONObject()
                .put("$schema", "rusty.quest.embedded_duplex.pair_status.v1")
                .put("state", "cleanup_pending").put("session_id", SESSION)
                .put("route_current", false).put("media_effect_proven", false)
                .put("native_current_session", observation(false, "expired"))
                .put("local_current_session", observation(false, "expired")
                        .put("$schema", "rusty.manifold.peer.session_current_receipt.v999"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> EmbeddedDuplexPairStatus.parse(outer.toString()));
        assertEquals("pair current-session schema mismatch", failure.getMessage());
    }

    @Test public void currentAndRejectedForeignSubjectsBothRefuse() throws Exception {
        for (String key : new String[]{"native_current_session", "local_current_session"}) {
            for (boolean current : new boolean[]{false, true}) {
                for (boolean remote : new boolean[]{false, true}) {
                    JSONObject bad = observation(current, current ? null : "expired")
                            .put("session_id", FOREIGN);
                    IllegalStateException failure = assertThrows(IllegalStateException.class,
                            () -> parse(key, remote ? null : bad, remote ? bad : null));
                    assertEquals(current ? "pair session readback mismatch"
                            : "pair rejection subject mismatch", failure.getMessage());
                }
            }
        }
    }

    @Test public void noObservationRemainsAbsentWithoutGuessedExpiryCause() throws Exception {
        EmbeddedDuplexPairStatus result = EmbeddedDuplexPairStatus.parse(new JSONObject()
                .put("$schema", "rusty.quest.embedded_duplex.pair_status.v1")
                .put("state", "not_started").put("session_id", JSONObject.NULL)
                .put("native_current_session", JSONObject.NULL)
                .put("route_current", false).put("media_effect_proven", false).toString());
        assertNull(result.sessionId);
        assertNull(result.localSessionRejectionReason); assertNull(result.remoteSessionRejectionReason);
        assertEquals(0L, result.localSessionValidatedAtMs); assertEquals(0L, result.remoteSessionValidatedAtMs);
        assertFalse(result.localSessionCurrent); assertFalse(result.remoteSessionCurrent);
    }

    @Test public void currentObservationHasNoRejectionAndRetainsTime() throws Exception {
        EmbeddedDuplexPairStatus result = parse("local_current_session",
                observation(true, null), observation(true, null));
        assertTrue(result.localSessionCurrent); assertTrue(result.remoteSessionCurrent);
        assertNull(result.localSessionRejectionReason); assertNull(result.remoteSessionRejectionReason);
        assertEquals(OBSERVED, result.remoteSessionValidatedAtMs);
        assertEquals(100000L, result.localSessionExpiresAtMs);
    }

    @Test public void contradictionOrUnknownCodeHitsCodeGuard() throws Exception {
        for (boolean remote : new boolean[]{false, true}) {
            for (JSONObject bad : new JSONObject[]{observation(true, "expired"),
                    observation(false, "fresh_setup_fence_invented")}) {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> parse("native_current_session", remote ? null : bad, remote ? bad : null));
                assertEquals("pair rejection code invalid", failure.getMessage());
            }
        }
    }

    @Test public void negativeObservationTimeRefusesAndMissingTimeIsNotFreshened() throws Exception {
        for (boolean remote : new boolean[]{false, true}) {
            JSONObject bad = observation(false, "expired").put("validated_at_ms", -1L);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> parse("native_current_session", remote ? null : bad, remote ? bad : null));
            assertEquals("pair observation time invalid", failure.getMessage());
            bad.remove("validated_at_ms");
            EmbeddedDuplexPairStatus result = parse("native_current_session", remote ? null : bad, remote ? bad : null);
            assertEquals(0L, remote ? result.remoteSessionValidatedAtMs : result.localSessionValidatedAtMs);
            assertEquals("expired", remote ? result.remoteSessionRejectionReason : result.localSessionRejectionReason);
        }
    }
}
