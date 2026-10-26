package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Actual process-owned slot and preflight code; modeled native boundary, no effects. */
public final class EmbeddedDuplexStartIntentSlotHostTest {
    private static final String CONFIG = "b".repeat(64), ENROLLMENT = "c".repeat(64);
    private static int cases;
    private static void check(boolean value) { if (!value) throw new AssertionError(); cases++; }
    private interface Action { void run() throws Exception; }
    private static void deny(Action action) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { cases++; return; }
        throw new AssertionError("expected rejection");
    }
    private static void denyMalformed(Action action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException | org.json.JSONException expected) { cases++; return; }
        throw new AssertionError("expected malformed receipt rejection");
    }
    private static EmbeddedDuplexStartPreflight intent(long expiry, String session, String config,
            String enrollment, long display) throws Exception {
        JSONObject local = new JSONObject().put("current", true).put("session_id", session)
                .put("expires_at_ms", expiry);
        JSONObject pair = new JSONObject().put("$schema", "rusty.quest.embedded_duplex.pair_status.v1")
                .put("state", "peer_session_current_route_unverified").put("session_id", session)
                .put("native_current_session", local).put("route_current", false).put("media_effect_proven", false);
        return EmbeddedDuplexStartPreflight.prepare(EmbeddedDuplexPairStatus.parse(pair.toString()),
                config, enrollment, display);
    }
    private static EmbeddedDuplexStartPreflight intent(long expiry) throws Exception {
        return intent(expiry, "session.duplex." + "a".repeat(64), CONFIG, ENROLLMENT, 7L);
    }
    private static JSONObject result(String status) throws Exception {
        return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1")
                .put("action", "start").put("config_sha256", CONFIG).put("native_executor_generation", 1)
                .put("app_process_generation", 1).put("status", status)
                .put("last_failure", JSONObject.NULL).put("renewal_pending", false);
    }
    // These are parser controls, not authority/activation or Native execution evidence.
    static JSONObject ongoing(String status) throws Exception {
        return result(status).put("$schema", "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3")
                .put("ongoing_media_policy", new JSONObject().put("$schema", "rusty.manifold.peer.ongoing_media_policy.v2"))
                .put("current_lifetime_renewal_count", 0L).put("current_owner_validation", JSONObject.NULL);
    }
    static JSONObject fresh(String status) throws Exception {
        JSONObject value = ongoing(status).put("$schema", EmbeddedDuplexFreshLifecycle.SCHEMA);
        for (String key : new String[]{"authority_projection", "broker_evidence", "start_mutation", "stop_mutation",
                "route_receipt", "route_termination", "route_cleanup", "termination_action", "revoker_adoption",
                "media_completion", "media_stop_effect_receipt", "owner_failure_diagnostic", "owner_failure_diagnostic_status",
                "native_owner_dispatch_failure", "activation", "renewal_receipts", "renewal_first_request_id",
                "renewal_last_request_id", "peer_physical_cleanup", "native_host_physical_cleanup",
                "whole_app_physical_cleanup", "last_failure_action", "last_failure_cause"}) value.put(key, JSONObject.NULL);
        value.put("observed_at_ms", 1L).put("renewal_total_completed", 0L)
                .put("retired_lifetimes", new JSONObject().put("total", 0L).put("first", JSONObject.NULL)
                    .put("last", JSONObject.NULL).put("recent", new org.json.JSONArray()).put("history_sha256", JSONObject.NULL));
        EmbeddedDuplexFreshLifecycle.require(value);
        return value;
    }
    public static void main(String[] args) throws Exception {
        // The runner copies the exact production preflightLive method; only host dependencies are modeled.
        PreflightLiveHarness host = new PreflightLiveHarness();
        EmbeddedDuplexStartPreflight live = intent(System.currentTimeMillis() + 60000);
        host.startIntent.prepare(live); check(host.preflightLive(live));
        host.startIntent.dispatch(live); check(!host.preflightLive(live));
        host.startIntent.afterVerifiedCleanup();
        EmbeddedDuplexStartPreflight expired = intent(System.currentTimeMillis() - 1);
        host.startIntent.prepare(expired); check(!host.preflightLive(expired));
        host.startIntent.afterVerifiedCleanup(); host.startIntent.prepare(live);
        host.processFence.recovery = true; check(!host.preflightLive(live)); host.processFence.recovery = false;
        host.processFence.stale = true; check(!host.preflightLive(live)); host.processFence.stale = false;
        host.attachmentGeneration++; check(!host.preflightLive(live)); host.attachmentGeneration--;
        host.runtimeConfigSha256 = "d".repeat(64); check(!host.preflightLive(live)); host.runtimeConfigSha256 = CONFIG;
        host.enrollmentRecordSha256 = "d".repeat(64); check(!host.preflightLive(live)); host.enrollmentRecordSha256 = ENROLLMENT;
        host.phase.set(PreflightLiveHarness.Phase.FAILED); check(!host.preflightLive(live));
        host.phase.set(PreflightLiveHarness.Phase.READY); host.closeInFlight = true; check(!host.preflightLive(live));
        EmbeddedDuplexStartIntentSlot slot = new EmbeddedDuplexStartIntentSlot();
        EmbeddedDuplexStartPreflight first = intent(100000), renewed = intent(200000);
        slot.prepare(first); check(slot.pending() == first);
        // Exact pending lineage still includes expiry; renewal cannot replace an unconsumed intent.
        deny(() -> slot.prepare(renewed));
        deny(() -> slot.prepare(intent(100000, "session.duplex." + "d".repeat(64), CONFIG, ENROLLMENT, 7)));
        deny(() -> slot.prepare(intent(100000, first.sessionId, "d".repeat(64), ENROLLMENT, 7)));
        deny(() -> slot.prepare(intent(100000, first.sessionId, CONFIG, "d".repeat(64), 7)));
        deny(() -> slot.prepare(intent(100000, first.sessionId, CONFIG, ENROLLMENT, 8)));
        EmbeddedDuplexStartPreflight replacement = intent(100000);
        slot.prepare(replacement); deny(() -> slot.dispatch(first));
        slot.dispatch(replacement); check(slot.pending() == null);
        deny(() -> slot.dispatch(replacement)); deny(() -> slot.prepare(renewed));
        // A returned Pending receipt and a thrown native operation retain the consumed obligation.
        slot.acknowledge(replacement, result("pending").toString()); deny(() -> slot.prepare(renewed));
        deny(() -> slot.acknowledge(first, result("active").toString()));
        deny(() -> slot.acknowledge(replacement, result("active").put("config_sha256", "d".repeat(64)).toString()));
        deny(() -> slot.prepare(renewed));
        deny(() -> slot.acknowledge(replacement, result("active").put("action", "peer_status").toString()));
        deny(() -> slot.acknowledge(replacement, result("active").put("native_executor_generation", 0).toString()));
        deny(() -> slot.acknowledge(replacement, result("active").put("app_process_generation", 0).toString()));
        JSONObject missingFailure = result("active"); missingFailure.remove("last_failure");
        slot.acknowledge(replacement, missingFailure.toString()); deny(() -> slot.prepare(renewed));
        slot.afterVerifiedCleanup(); slot.prepare(renewed); check(slot.pending() == renewed);
        slot.dispatch(renewed); // Models native throwing after dispatch: no acknowledgement occurs.
        deny(() -> slot.prepare(intent(300000))); deny(() -> slot.dispatch(renewed));
        slot.afterVerifiedCleanup(); slot.prepare(renewed); slot.dispatch(renewed);
        slot.acknowledge(renewed, result("active").put("last_failure", "uncertain").toString());
        deny(() -> slot.prepare(intent(300000)));
        slot.afterVerifiedCleanup(); slot.prepare(renewed); slot.dispatch(renewed);
        slot.acknowledge(renewed, result("active").put("renewal_pending", true).toString());
        deny(() -> slot.prepare(intent(300000)));
        slot.afterVerifiedCleanup(); slot.prepare(renewed); slot.dispatch(renewed);
        slot.acknowledge(renewed, result("active").toString());
        EmbeddedDuplexStartPreflight afterRenewal = intent(300000);
        slot.prepare(afterRenewal); check(slot.pending() == afterRenewal);
        check(!afterRenewal.sameLineage(renewed)); check(afterRenewal.sessionExpiresAtMs == 300000);
        deny(() -> slot.dispatch(renewed));
        slot.afterVerifiedCleanup(); check(slot.pending() == null);
        deny(() -> slot.dispatch(afterRenewal));
        for (boolean selectedFresh : new boolean[]{false, true}) {
            JSONObject active = selectedFresh ? fresh("active") : ongoing("active");
            slot.prepare(first); slot.dispatch(first); slot.acknowledge(first, active.toString());
            slot.prepare(renewed); check(slot.pending() == renewed); slot.dispatch(renewed);
            slot.acknowledge(renewed, (selectedFresh ? fresh("pending") : ongoing("pending")).toString());
            deny(() -> slot.prepare(first));
            JSONObject malformed = selectedFresh ? fresh("active") : ongoing("active");
            malformed.remove(selectedFresh ? "retired_lifetimes" : "ongoing_media_policy");
            denyMalformed(() -> slot.acknowledge(renewed, malformed.toString())); deny(() -> slot.prepare(first));
            slot.afterVerifiedCleanup();
        }
        slot.prepare(first); slot.dispatch(first);
        deny(() -> slot.acknowledge(first, ongoing("active").put("current_lifetime_renewal_count", "0").toString()));
        deny(() -> slot.prepare(renewed)); slot.afterVerifiedCleanup();
        slot.prepare(first); slot.dispatch(first);
        deny(() -> slot.acknowledge(first, fresh("active").put("$schema", "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v99").toString()));
        deny(() -> slot.prepare(renewed)); slot.afterVerifiedCleanup();
        System.out.println("Start intent lifecycle: " + cases + " cases PASS; modeled native boundary; no device/physical-cleanup proof");
    }
}
