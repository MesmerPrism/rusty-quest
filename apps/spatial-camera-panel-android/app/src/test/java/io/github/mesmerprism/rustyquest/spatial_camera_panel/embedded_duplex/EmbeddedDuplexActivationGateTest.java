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

    @Test public void verifiedStopAdmitsDistinctAuthenticatedReceiverWithRestartedRevision() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        verifyStop(gate, stop(), authority(), stopped());
        target.nextReceiver();
        dispatchArm(gate, target, freshAuthority(), freshTicket(), freshVerified());
        assertEquals(1, target.providerCalls);
        assertEquals(0, target.activated);
    }

    @Test public void failedUnverifiedAndCompensatingStopNeverReopenProviderDispatch() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            FakeTarget target = new FakeTarget();
            EmbeddedDuplexActivationGate gate = armed(target);
            EmbeddedDuplexActivationGate.MediaTicket stop = stop();
            gate.beforeOwnerEffect(authority(), stop, mode == 2);
            if (mode == 1) {
                JSONObject invalid = stopped().put("terminal", false);
                gate.afterVerifiedOwnerEffect(authority(), stop, readback(), invalid, false);
            } else if (mode == 2) {
                gate.afterVerifiedOwnerEffect(authority(), stop, readback(), stopped(), true);
            }
            target.nextReceiver();
            assertThrows(IllegalStateException.class, () -> dispatchArm(
                    gate, target, freshAuthority(), freshTicket(), freshVerified()));
            assertEquals(0, target.providerCalls);
        }
    }

    @Test public void staleActionRouteAndExpiredAuthorityDenyBeforeProvider() throws Exception {
        for (int damage = 0; damage < 3; damage++) {
            FakeTarget target = new FakeTarget();
            EmbeddedDuplexActivationGate gate = armed(target);
            verifyStop(gate, stop(), authority(), stopped());
            target.nextReceiver();
            JSONObject auth = freshAuthority();
            EmbeddedDuplexActivationGate.MediaTicket arm = damage == 0 ? ticket() : freshTicket();
            if (damage == 1) auth.put("route_grant_id", "grant.route.1");
            if (damage == 2) auth.put("expires_at_ms", 1000);
            assertThrows(IllegalStateException.class, () -> dispatchArm(
                    gate, target, auth, arm, freshVerified()));
            assertEquals(0, target.providerCalls);
        }
    }

    @Test public void wrongTerminalHandleRevisionSubjectAndGenerationCannotGrantRestart() throws Exception {
        for (int damage = 0; damage < 4; damage++) {
            FakeTarget target = new FakeTarget();
            EmbeddedDuplexActivationGate gate = armed(target);
            JSONObject terminal = stopped();
            JSONObject auth = authority();
            if (damage == 0) terminal.put("provider_handle_id", "foreign.receiver");
            if (damage == 1) terminal.put("provider_state_revision", 2);
            if (damage == 2) auth.put("authority_client_id", "foreign.client");
            if (damage == 3) terminal.put("executor_generation", 8);
            verifyStop(gate, stop(), auth, terminal);
            target.nextReceiver();
            assertThrows(IllegalStateException.class, () -> dispatchArm(
                    gate, target, freshAuthority(), freshTicket(), freshVerified()));
            assertEquals(0, target.providerCalls);
        }
    }

    @Test public void reusedNativeIdentityOrReceiverHandleCannotCompleteFreshArm() throws Exception {
        for (int damage = 0; damage < 4; damage++) {
            FakeTarget target = new FakeTarget();
            EmbeddedDuplexActivationGate gate = armed(target);
            verifyStop(gate, stop(), authority(), stopped());
            target.nextReceiver();
            JSONObject next = freshVerified();
            if (damage == 0) next.put("provider_handle_id", "receiver.7");
            if (damage == 1) target.route = 41;
            if (damage == 2) target.decoder = 42;
            if (damage == 3) target.reader = 43;
            assertThrows(IllegalStateException.class, () -> dispatchArm(
                    gate, target, freshAuthority(), freshTicket(), next));
            assertThrows(IllegalStateException.class, () -> gate.activate(
                    "old.activation", authority().toString(), proof().toString()));
            assertEquals(1, target.providerCalls);
            assertEquals(0, target.awaited);
        }
    }

    @Test public void oldTerminalCallbackCannotReopenAfterASecondStopBegan() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        EmbeddedDuplexActivationGate.MediaTicket first = stop();
        gate.beforeOwnerEffect(authority(), first, false);
        gate.beforeOwnerEffect(authority(), stop(), false);
        gate.afterVerifiedOwnerEffect(authority(), first, readback(), stopped(), false);
        target.nextReceiver();
        assertThrows(IllegalStateException.class, () -> dispatchArm(
                gate, target, freshAuthority(), freshTicket(), freshVerified()));
        assertEquals(0, target.providerCalls);
    }

    @Test public void validUnarmedCleanupDoesNotBecomeRestartAuthority() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = new EmbeddedDuplexActivationGate(target, () -> 1000L);
        verifyStop(gate, stop(), authority(), stopped());
        target.nextReceiver();
        assertThrows(IllegalStateException.class, () -> dispatchArm(
                gate, target, freshAuthority(), freshTicket(), freshVerified()));
        assertEquals(0, target.providerCalls);
    }

    @Test public void retainedV2VerifiedStopBindsOriginalArmBeforeFreshCurrentRoute() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        JSONObject retained = new JSONObject()
                .put("$schema", "rusty.quest.android.media.retained_cleanup_projection.v2")
                .put("provider_epoch_id", "epoch.provider.1").put("target_client_id", "client.1")
                .put("target_runtime_lease_id", "lease.1")
                .put("platform_runtime_spec_id", "runtime.incoming.1").put("expires_at_ms", 2000);
        // Platform/native registry authenticate the full v2 projection before this gate callback.
        verifyStop(gate, stop(), retained, stopped());
        target.nextReceiver();
        dispatchArm(gate, target, freshAuthority(), freshTicket(), freshVerified());
        assertEquals(1, target.providerCalls);
    }

    @Test public void wrongFreshTicketSubjectDeniesBeforeProvider() throws Exception {
        for (String field : new String[] {"authority_client_id", "authority_runtime_lease_id",
                "authority_provider_epoch_id", "platform_runtime_spec_id"}) {
            FakeTarget target = new FakeTarget();
            EmbeddedDuplexActivationGate gate = armed(target);
            verifyStop(gate, stop(), authority(), stopped());
            target.nextReceiver();
            JSONObject auth = freshAuthority().put(field, "foreign");
            assertThrows(IllegalStateException.class, () -> dispatchArm(
                    gate, target, auth, freshTicket(), freshVerified()));
            assertEquals(0, target.providerCalls);
        }
    }

    @Test public void reopenedReceiverRequiresNewProofAndKeepsUncertainActivationFence() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        verifyStop(gate, stop(), authority(), stopped());
        target.nextReceiver();
        dispatchArm(gate, target, freshAuthority(), freshTicket(), freshVerified());
        assertThrows(IllegalStateException.class, () -> gate.activate(
                "old.proof", freshAuthority().toString(), proof().toString()));
        assertEquals(0, target.awaited);
        target.failAwait = true;
        JSONObject freshProof = proof().put("action_id", "action.start.2")
                .put("lease_id", "lease.2").put("resulting_runtime_revision", 12);
        assertThrows(IllegalStateException.class, () -> gate.activate(
                "new.proof", freshAuthority().toString(), freshProof.toString()));
        assertEquals(1, target.awaited);
        assertThrows(IllegalStateException.class, () -> gate.activate(
                "new.proof.retry", freshAuthority().toString(), freshProof.toString()));
        assertEquals(1, target.awaited);
        assertEquals(0, target.activated);
    }

    @Test public void actualPackagedRegistryTerminalReadbackReopensOnlyVerifiedStop() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        io.github.mesmerprism.rustyquest.media.MediaOwnerProvider provider =
                new io.github.mesmerprism.rustyquest.media.MediaOwnerProvider() {
            @Override public io.github.mesmerprism.rustyquest.media.MediaProviderReadback execute(
                    io.github.mesmerprism.rustyquest.media.MediaOwnerAction action,
                    io.github.mesmerprism.rustyquest.media.CancellationHandle cancellation) {
                return new io.github.mesmerprism.rustyquest.media.MediaProviderReadback(
                        action, "receiver.7", 3, "stopped", "receipt.sink.stop");
            }
            @Override public io.github.mesmerprism.rustyquest.media.MediaProviderReadback compensate(
                    io.github.mesmerprism.rustyquest.media.MediaOwnerAction action,
                    io.github.mesmerprism.rustyquest.media.CancellationHandle cancellation) {
                throw new AssertionError("not a compensation fixture");
            }
            @Override public io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot snapshot() {
                return new io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot(
                        7, 3, "stopped", true, "modeled terminal provider", "receiver.7");
            }
        };
        io.github.mesmerprism.rustyquest.media.MediaProductBinding binding =
                new io.github.mesmerprism.rustyquest.media.MediaProductBinding.Builder("test.product")
                .bind("sink", "sink.1", "provider.sink", "resource.sink", provider).build();
        try (io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry registry =
                new io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry(7, binding)) {
            String action = new JSONObject()
                    .put("$schema", "rusty.quest.android.media.execution-ticket.v1")
                    .put("capability", "modeled.owner.capability").put("executor_generation", 7)
                    .put("action_id", "action.stop.1").put("authority_epoch_id", "epoch.provider.1")
                    .put("media_acceptance_authority_revision", 1).put("expected_runtime_revision", 10)
                    .put("client_id", "client.1").put("lease_id", "lease.1").put("sequence", 6)
                    .put("operation", "stop").put("owner_kind", "sink").put("action_kind", "stop")
                    .put("owner_id", "sink.1").put("provider_kind", "provider.sink")
                    .put("resource_id", "resource.sink").toString();
            EmbeddedDuplexActivationGate.MediaTicket ticket = stop();
            gate.beforeOwnerEffect(authority(), ticket, false);
            String raw = registry.execute(action, false);
            String verified = registry.verifyAndReadEvidence(action, raw);
            assertTrue(verified != null);
            gate.afterVerifiedOwnerEffect(authority(), ticket,
                    new JSONObject(raw), new JSONObject(verified), false);
            // The registry consumed this exact receipt; it cannot be verified twice.
            assertTrue(registry.verifyAndReadEvidence(action, raw) == null);
            target.nextReceiver();
            dispatchArm(gate, target, freshAuthority(), freshTicket(), freshVerified());
            assertEquals(1, target.providerCalls);
        }
    }

    @Test public void failedFreshArmCannotReuseTheOldStopFenceForAnotherProviderDispatch() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        verifyStop(gate, stop(), authority(), stopped());
        target.nextReceiver();
        JSONObject invalid = freshVerified().put("terminal", true);
        assertThrows(IllegalStateException.class, () -> dispatchArm(
                gate, target, freshAuthority(), freshTicket(), invalid));
        assertThrows(IllegalStateException.class, () -> dispatchArm(
                gate, target, freshAuthority(), freshTicket(), freshVerified()));
        assertEquals(1, target.providerCalls);
    }

    @Test public void registryVerifiedPartialFreshArmCleanupSucceedsWithoutGrantingRestart() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        verifyStop(gate, stop(), authority(), stopped());
        target.nextReceiver();
        assertThrows(IllegalStateException.class, () -> dispatchArm(gate, target,
                freshAuthority(), freshTicket(), freshVerified().put("terminal", true)));
        try (CleanupFixture current = new CleanupFixture("lease.2", "receiver.8", 2, "stop")) {
            current.executeAndVerify(gate, freshAuthority());
            assertEquals(1, current.providerCalls);
        }
        // Actual terminal cleanup succeeded, but no verified arm binds the new handle.
        assertThrows(IllegalStateException.class, () -> dispatchArm(gate, target,
                freshAuthority(), freshTicket(), freshVerified()));
        assertEquals(1, target.providerCalls);
    }

    @Test public void registryVerifiedCleanupKindDoesNotBecomeAStopReopeningReceipt() throws Exception {
        FakeTarget target = new FakeTarget();
        EmbeddedDuplexActivationGate gate = armed(target);
        try (CleanupFixture current = new CleanupFixture("lease.1", "receiver.7", 3, "cleanup")) {
            current.executeAndVerify(gate, authority());
            assertEquals(1, current.providerCalls);
        }
        target.nextReceiver();
        assertThrows(IllegalStateException.class, () -> dispatchArm(gate, target,
                freshAuthority(), freshTicket(), freshVerified()));
        assertEquals(0, target.providerCalls);
    }

    private static final class CleanupFixture implements AutoCloseable {
        final io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry registry;
        final String action;
        final EmbeddedDuplexActivationGate.MediaTicket ticket;
        int providerCalls;
        CleanupFixture(String lease, String handle, long revision, String kind) throws Exception {
            io.github.mesmerprism.rustyquest.media.MediaOwnerProvider provider =
                    new io.github.mesmerprism.rustyquest.media.MediaOwnerProvider() {
                @Override public io.github.mesmerprism.rustyquest.media.MediaProviderReadback execute(
                        io.github.mesmerprism.rustyquest.media.MediaOwnerAction action,
                        io.github.mesmerprism.rustyquest.media.CancellationHandle cancellation) {
                    providerCalls++;
                    return new io.github.mesmerprism.rustyquest.media.MediaProviderReadback(
                            action, handle, revision, "stopped", "receipt.cleanup.fixture");
                }
                @Override public io.github.mesmerprism.rustyquest.media.MediaProviderReadback compensate(
                        io.github.mesmerprism.rustyquest.media.MediaOwnerAction action,
                        io.github.mesmerprism.rustyquest.media.CancellationHandle cancellation) {
                    throw new AssertionError("unexpected compensation");
                }
                @Override public io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot snapshot() {
                    return new io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot(
                            7, revision, "stopped", true, "modeled cleanup provider", handle);
                }
            };
            registry = new io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry(7,
                    new io.github.mesmerprism.rustyquest.media.MediaProductBinding.Builder("test.product")
                    .bind("sink", "sink.1", "provider.sink", "resource.sink", provider).build());
            action = new JSONObject().put("$schema", "rusty.quest.android.media.execution-ticket.v1")
                    .put("capability", "modeled.owner.capability").put("executor_generation", 7)
                    .put("action_id", "action.cleanup.fixture").put("authority_epoch_id", "epoch.provider.1")
                    .put("media_acceptance_authority_revision", 1).put("expected_runtime_revision", 12)
                    .put("client_id", "client.1").put("lease_id", lease).put("sequence", 6)
                    .put("operation", "stop").put("owner_kind", "sink").put("action_kind", kind)
                    .put("owner_id", "sink.1").put("provider_kind", "provider.sink")
                    .put("resource_id", "resource.sink").toString();
            ticket = new EmbeddedDuplexActivationGate.MediaTicket(7, 12, "action.cleanup.fixture",
                    "epoch.provider.1", "client.1", lease, "stop", "sink", kind);
        }
        void executeAndVerify(EmbeddedDuplexActivationGate gate, JSONObject authority) throws Exception {
            gate.beforeOwnerEffect(authority, ticket, false);
            String raw = registry.execute(action, false);
            String verified = registry.verifyAndReadEvidence(action, raw);
            assertTrue(verified != null);
            gate.afterVerifiedOwnerEffect(authority, ticket, new JSONObject(raw),
                    new JSONObject(verified), false);
            assertTrue(registry.verifyAndReadEvidence(action, raw) == null);
        }
        @Override public void close() { registry.close(); }
    }

    private static void dispatchArm(EmbeddedDuplexActivationGate gate, FakeTarget target,
            JSONObject authority, EmbeddedDuplexActivationGate.MediaTicket ticket,
            JSONObject verified) throws Exception {
        gate.beforeOwnerEffect(authority, ticket, false);
        target.providerCalls++;
        gate.afterVerifiedOwnerEffect(authority, ticket, readback(), verified, false);
    }

    private static void verifyStop(EmbeddedDuplexActivationGate gate,
            EmbeddedDuplexActivationGate.MediaTicket ticket, JSONObject authority,
            JSONObject verified) throws Exception {
        gate.beforeOwnerEffect(authority, ticket, false);
        gate.afterVerifiedOwnerEffect(authority, ticket, readback(), verified, false);
    }

    private static EmbeddedDuplexActivationGate.MediaTicket stop() {
        return new EmbeddedDuplexActivationGate.MediaTicket(7, 10, "action.stop.1",
                "epoch.provider.1", "client.1", "lease.1", "stop", "sink", "stop");
    }

    private static EmbeddedDuplexActivationGate.MediaTicket freshTicket() {
        return new EmbeddedDuplexActivationGate.MediaTicket(7, 11, "action.start.2",
                "epoch.provider.1", "client.1", "lease.2", "start", "sink", "arm_receiver");
    }

    private static JSONObject freshAuthority() throws Exception {
        return authority().put("route_grant_id", "grant.route.2")
                .put("authority_runtime_lease_id", "lease.2");
    }

    private static JSONObject stopped() throws Exception {
        return verified().put("provider_state_revision", 3).put("observed_state", "stopped")
                .put("terminal", true);
    }

    private static JSONObject freshVerified() throws Exception {
        return verified().put("provider_handle_id", "receiver.8").put("provider_state_revision", 1);
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
        int providerCalls;
        long route = 41, decoder = 42, reader = 43;
        void nextReceiver() { route = 51; decoder = 52; reader = 53; }
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
        @Override public long routeGeneration() { return route; }
        @Override public long decoderToken() { return decoder; }
        @Override public long readerGeneration() { return reader; }
        @Override public void activateIncomingProjection() { activated++; }
        @Override public long[] currentProjection() {
            long[] words = new long[16]; words[0] = 1; words[1] = 41; words[2] = 2;
            words[3] = 42; words[4] = 43; words[9] = 100; words[11] = 1;
            return words;
        }
    }
}
