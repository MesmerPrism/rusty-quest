package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class EmbeddedDuplexActivationGateTest {
    @Test public void exactArmAndProofActivateOnlyAfterFreshNativeReadback() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        JSONObject result = new JSONObject(gate.activate("activation.1",
                authority().toString(), proof().toString()));
        assertTrue(result.getBoolean("activated"));
        assertEquals("grant.route.1", result.getString("route_grant_id"));
        assertEquals(1, target.awaited);
        assertEquals(1, target.activated);
    }

    @Test public void wrongProofAndWrongArmLineageRejectBeforeGraph() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate wrongArm = new EmbeddedDuplexActivationGate(target, () -> 1000L);
        EmbeddedDuplexActivationGate.MediaTicket foreign = ticket();
        foreign = new EmbeddedDuplexActivationGate.MediaTicket(foreign.generation,
                foreign.expectedRuntimeRevision, foreign.actionId, "epoch.foreign", foreign.clientId,
                foreign.leaseId, foreign.operation, foreign.ownerKind, foreign.actionKind);
        EmbeddedDuplexActivationGate.MediaTicket exactForeign = foreign;
        assertThrows(IllegalStateException.class, () -> wrongArm.afterVerifiedOwnerEffect(
                authority(), exactForeign, readback(), verified(), false));

        EmbeddedDuplexActivationGate gate = armed(target);
        JSONObject damaged = proof();
        damaged.put("runtime_spec_id", "runtime.foreign");
        assertThrows(IllegalStateException.class,
                () -> gate.activate("activation.2", authority().toString(), damaged.toString()));
        assertEquals(0, target.awaited);
    }

    @Test public void cleanupFencePreventsLaterActivation() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        EmbeddedDuplexActivationGate.MediaTicket stop = new EmbeddedDuplexActivationGate.MediaTicket(
                7, 9, "action.start.1", "epoch.provider.1", "client.1", "lease.1",
                "stop", "sink", "stop");
        gate.beforeOwnerEffect(authority(), stop, false);
        assertThrows(IllegalStateException.class,
                () -> gate.activate("activation.3", authority().toString(), proof().toString()));
        assertEquals(0, target.awaited);
    }

    @Test public void uncertainActivationRetainsTargetAndCannotReenter() throws Exception {
        FakeTarget target = new FakeTarget();
        target.failAwait = true;
        EmbeddedDuplexActivationGate gate = armed(target);
        assertThrows(IllegalStateException.class,
                () -> gate.activate("activation.4", authority().toString(), proof().toString()));
        target.failAwait = false;
        assertThrows(IllegalStateException.class,
                () -> gate.activate("activation.4", authority().toString(), proof().toString()));
        assertEquals(1, target.awaited);
        assertEquals(0, target.activated);
        assertTrue(target.retained);
    }

    @Test public void cleanupCallbackCanRunWhileDisplayAwaitsAndFencesActivation() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        EmbeddedDuplexActivationGate.MediaTicket stop = new EmbeddedDuplexActivationGate.MediaTicket(
                7, 9, "action.stop.1", "epoch.provider.1", "client.1", "lease.1",
                "stop", "sink", "stop");
        JSONObject authority = authority();
        target.onAwait = () -> {
            Thread cleanup = new Thread(() -> gate.beforeOwnerEffect(authority, stop, false));
            cleanup.start();
            try { cleanup.join(1000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("cleanup callback interrupted", interrupted);
            }
            if (cleanup.isAlive()) throw new AssertionError("display callback held activation lock");
        };
        assertThrows(IllegalStateException.class,
                () -> gate.activate("activation.5", authority.toString(), proof().toString()));
        assertEquals(1, target.awaited);
        assertEquals(0, target.activated);
    }

    @Test public void actualIncomingGateRejectionsRetainClosedReasons() throws Exception {
        String[] expected = {"INCOMING_ARM_ORDER", "INCOMING_ARM_PROJECTION",
                "INCOMING_ARM_EVIDENCE", "INCOMING_ARM_EVIDENCE"};
        for (int i = 0; i < expected.length; i++) {
            FakeTarget target = new FakeTarget();
            EmbeddedDuplexActivationGate gate = new EmbeddedDuplexActivationGate(target, () -> 1000L);
            JSONObject authority = authority(), verified = verified();
            if (i == 0) gate.beforeOwnerEffect(authority, new EmbeddedDuplexActivationGate.MediaTicket(
                    7, 9, "stop.1", "epoch.provider.1", "client.1", "lease.1", "stop", "sink", "stop"), false);
            if (i == 1) authority.put("authorization_kind", "foreign");
            if (i == 2) verified.put("extra", "secret");
            if (i == 3) verified.put("observed_state", "stopped");
            final JSONObject currentAuthority = authority, currentVerified = verified;
            Exception original = assertThrows(IllegalStateException.class, () ->
                    gate.afterVerifiedOwnerEffect(currentAuthority, ticket(), readback(), currentVerified, false));
            EmbeddedDuplexPlatform platform = diagnosticOnlyPlatform();
            platform.recordOwnerFailure(EmbeddedDuplexPlatform.OwnerStage.INCOMING_ARM_VERIFICATION,
                    parsedTicket(), original);
            JSONObject diagnostic = new JSONObject(platform.ownerFailureDiagnostic());
            assertEquals(expected[i], diagnostic.getString("provider_reason"));
            assertEquals("INCOMING_ARM_VERIFICATION", diagnostic.getString("stage"));
            assertEquals("ARM_RECEIVER", diagnostic.getString("action"));
            assertEquals("sink", diagnostic.getString("owner"));
            assertEquals("NONE", diagnostic.getString("cause"));
            assertEquals(7, diagnostic.length());
            assertEquals(0, target.activated);
            platform.recordOwnerFailure(EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION,
                    parsedTicket(), new IllegalStateException("secret cleanup error"));
            assertEquals(diagnostic.toString(), platform.ownerFailureDiagnostic());
        }
    }

    @Test public void unknownIncomingGateMessagesStayClosedAndUnchanged() throws Exception {
        for (String message : new String[] {"secret", "incoming Sink arm evidence;secret",
                " incoming Sink activation order", "incoming Sink arm evidence\nsecret"}) {
            Exception original = new IllegalStateException(message);
            EmbeddedDuplexPlatform platform = diagnosticOnlyPlatform();
            platform.recordOwnerFailure(EmbeddedDuplexPlatform.OwnerStage.INCOMING_ARM_VERIFICATION,
                    parsedTicket(), original);
            String diagnostic = platform.ownerFailureDiagnostic();
            assertEquals("INCOMING_ARM_UNAVAILABLE", new JSONObject(diagnostic).getString("provider_reason"));
            assertTrue(!diagnostic.contains("secret"));
            assertEquals(message, original.getMessage());
        }
    }

    // Host-only diagnostic fixture: no Android constructor, process capability, registry or effects.
    // The production recorder itself is exercised after a real production Gate rejection.
    private static EmbeddedDuplexPlatform diagnosticOnlyPlatform() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field singleton = type.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        EmbeddedDuplexPlatform platform = (EmbeddedDuplexPlatform) type.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), EmbeddedDuplexPlatform.class);
        for (String name : new String[] {"failedOwnerAction", "failedSinkStage", "failedOwnerKind", "failedCause"}) {
            java.lang.reflect.Field field = EmbeddedDuplexPlatform.class.getDeclaredField(name);
            field.setAccessible(true); field.set(platform, "NONE");
        }
        java.lang.reflect.Field stage = EmbeddedDuplexPlatform.class.getDeclaredField("failedOwnerStage");
        stage.setAccessible(true); stage.set(platform, EmbeddedDuplexPlatform.OwnerStage.NONE);
        java.lang.reflect.Field reason = EmbeddedDuplexPlatform.class.getDeclaredField("failedProviderReason");
        reason.setAccessible(true); reason.set(platform, EmbeddedDuplexPlatform.ProviderReason.NONE);
        return platform;
    }

    private static io.github.mesmerprism.rustyquest.media.MediaOwnerAction parsedTicket() throws Exception {
        JSONObject value = new JSONObject().put("$schema", "rusty.quest.android.media.execution-ticket.v1")
                .put("capability", "modeled.capability").put("executor_generation", 7)
                .put("action_id", "action.start.1").put("authority_epoch_id", "epoch.provider.1")
                .put("media_acceptance_authority_revision", 3).put("expected_runtime_revision", 9)
                .put("client_id", "client.1").put("lease_id", "lease.1").put("sequence", 0)
                .put("operation", "start").put("owner_kind", "sink").put("action_kind", "arm_receiver")
                .put("owner_id", "owner.sink").put("provider_kind", "provider.sink").put("resource_id", "sink.1");
        return io.github.mesmerprism.rustyquest.media.MediaOwnerAction.parse(value.toString());
    }
    private static EmbeddedDuplexActivationGate armed(FakeTarget target) throws Exception {
        EmbeddedDuplexActivationGate gate = new EmbeddedDuplexActivationGate(target, () -> 1000L);
        gate.afterVerifiedOwnerEffect(authority(), ticket(), readback(), verified(), false);
        return gate;
    }

    private static EmbeddedDuplexActivationGate.MediaTicket ticket() {
        return new EmbeddedDuplexActivationGate.MediaTicket(7, 9, "action.start.1",
                "epoch.provider.1", "client.1", "lease.1", "start", "sink", "arm_receiver");
    }

    private static JSONObject authority() throws Exception {
        return new JSONObject()
                .put("$schema", "rusty.quest.c1.owner_projection.v1")
                .put("authority_peer_id", "peer.a").put("executor_peer_id", "peer.b")
                .put("peer_session_id", "session.peer.1").put("route_grant_id", "grant.route.1")
                .put("route_authority_revision", 3).put("authority_runtime_host_id", "host.a")
                .put("authority_provider_epoch_id", "epoch.provider.1")
                .put("platform_runtime_spec_id", "runtime.incoming.1")
                .put("authority_client_id", "client.1").put("authority_runtime_lease_id", "lease.1")
                .put("signed_topology_sha256", digest('1'))
                .put("route_configuration_sha256", digest('2'))
                .put("route_authority_evidence_sha256", digest('3'))
                .put("expires_at_ms", 2000).put("authorization_kind", "current_route");
    }

    private static JSONObject proof() throws Exception {
        JSONArray receipts = new JSONArray();
        receipts.put("receipt.cleanup").put("receipt.sink.arm").put("receipt.route")
                .put("receipt.socket").put("receipt.codec").put("receipt.processor")
                .put("receipt.source");
        return new JSONObject().put("action_id", "action.start.1")
                .put("provider_epoch_id", "epoch.provider.1").put("client_id", "client.1")
                .put("lease_id", "lease.1").put("runtime_spec_id", "runtime.incoming.1")
                .put("resulting_runtime_revision", 10).put("owner_receipt_ids", receipts)
                .put("completion_sha256", digest('4'));
    }

    private static JSONObject readback() throws Exception {
        return new JSONObject().put("receipt_id", "receipt.sink.arm");
    }

    private static JSONObject verified() throws Exception {
        return new JSONObject().put("$schema", "rusty.quest.android.media.verified_owner_effect.v1")
                .put("receipt_id", "receipt.sink.arm").put("readback_sha256", digest('5'))
                .put("executor_generation", 7).put("provider_state_revision", 2)
                .put("observed_state", "receiver_armed").put("terminal", false)
                .put("provider_handle_id", "receiver.7").put("detail_sha256", digest('6'));
    }

    private static String digest(char value) {
        char[] encoded = new char[64];
        java.util.Arrays.fill(encoded, value);
        return "sha256:" + new String(encoded);
    }

    private static final class FakeTarget implements EmbeddedDuplexActivationGate.Target {
        int awaited;
        int activated;
        boolean failAwait;
        boolean retained = true;
        Runnable onAwait;

        @Override public long generation() { return 7; }
        @Override public String incomingRuntimeSpecId() { return "runtime.incoming.1"; }
        @Override public void awaitFirstRenderedFrame() {
            awaited++;
            if (onAwait != null) onAwait.run();
            if (failAwait) throw new IllegalStateException("receiver uncertain");
        }
        @Override public long[] currentIncomingFrame(long maxAgeNs) {
            long[] words = new long[17]; words[0] = 7; words[2] = 41; words[3] = 42; words[4] = 43;
            return words;
        }
        @Override public long routeGeneration() { return 41; }
        @Override public long decoderToken() { return 42; }
        @Override public long readerGeneration() { return 43; }
        @Override public void activateIncomingProjection() { activated++; }
        @Override public long[] currentProjection() {
            long[] words = new long[16]; words[0] = 1; words[1] = 41; words[2] = 2;
            words[3] = 42; words[4] = 43; words[9] = 100; words[11] = 1;
            return words;
        }
    }
}
