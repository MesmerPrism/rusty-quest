package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Host/JVM conformance for Hub lifecycle, identity, bounds, replay, and revoke. */
public final class ConnectionHubCoreTest {
    private static final HubProviderIdentity PROVIDER = new HubProviderIdentity(
            10082,
            "io.github.example.provider",
            repeat("ab", 32));

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("protocol vector path required");
        ConnectionHubProtocolVectorsTest vectors = ConnectionHubProtocolVectorsTest.load(args[0]);
        InMemoryStore store = new InMemoryStore();
        FakeAuthority authority = new FakeAuthority();
        HubSurfaceRegistry firstRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime first = new ConnectionHubRuntime(
                authority, store, firstRegistry, seededRandom());
        first.startRequested();
        first.noteListenerStarted();
        assertTrue(first.desiredRunning(), "explicit start did not persist desired state");

        JSONObject pair = new JSONObject()
                .put("$schema", ConnectionHubProtocol.PAIR_REQUEST_SCHEMA)
                .put("pairing_code", first.pairingCodeForWearer())
                .put("controller_identity_sha256", repeat("cd", 32));
        JSONObject pairReceipt = first.pair(pair, "wearer.test.evidence");
        vectors.validate("pair_request", pair);
        vectors.validate("pair_receipt", pairReceipt);
        assertTrue(pairReceipt.getBoolean("accepted"), "pairing was rejected");
        String cookie = pairReceipt.getString("session");
        assertEquals(1L, pairReceipt.getLong("transport_epoch"));

        HubSurfaceDescriptor descriptor = descriptor(PROVIDER);
        first.registerSurface(
                PROVIDER,
                "provider.instance.1",
                "{\"retained\":true}",
                registration(),
                immediateEndpoint());
        assertEquals(1, firstRegistry.snapshot().size());
        first.unregisterProvider(PROVIDER, "provider.instance.1", "provider_stopped");
        assertEquals(0, firstRegistry.snapshot().size());
        assertTrue(first.desiredRunning(), "provider stop incorrectly stopped Hub connection");

        // Simulated service process recreation: provider registry is empty, logical
        // session remains and Manifold replaces only its physical transport epoch.
        HubSurfaceRegistry secondRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime second = new ConnectionHubRuntime(
                authority, store, secondRegistry, seededRandom());
        second.noteListenerStarted();
        assertEquals(2L, second.requireSession(cookie).transportEpoch);
        assertEquals(0, secondRegistry.snapshot().size());
        assertEquals(3L, second.replaceTransport(cookie).transportEpoch);

        second.registerSurface(
                PROVIDER,
                "provider.instance.2",
                "{\"retained\":true}",
                registration(),
                immediateEndpoint());
        JSONObject command = new JSONObject()
                .put("$schema", ConnectionHubProtocol.SURFACE_COMMAND_SCHEMA)
                .put("type", "surface.command")
                .put("request_id", "request.replay.probe")
                .put("surface_id", descriptor.surfaceId())
                .put("command", "command.example.play")
                .put("args", new JSONObject());
        vectors.validate("surface_command", command);
        vectors.validate("surface_snapshot", second.snapshotEvent());
        ReceiptCapture firstCommand = new ReceiptCapture();
        second.handleCommand(cookie, 3, command, firstCommand);
        vectors.validate("command_receipt", firstCommand.value);
        assertTrue(firstCommand.value.getBoolean("accepted"), "first command was rejected");
        ReceiptCapture replay = new ReceiptCapture();
        second.handleCommand(cookie, 3, command, replay);
        assertTrue(!replay.value.getBoolean("accepted"), "replayed request was accepted");
        assertEquals("replayed_request", replay.value.getString("status"));

        expectSecurity(new Runnable() {
            @Override public void run() {
                secondRegistry.register(
                        descriptor(new HubProviderIdentity(
                                10083,
                                "io.github.example.attacker",
                                repeat("ef", 32))),
                        "{}",
                        "provider.attacker.1",
                        immediateEndpoint());
            }
        });
        expectIllegal(new Runnable() {
            @Override public void run() {
                new HubSurfaceDescriptor(
                        1,
                        repeat("x", ConnectionHubProtocol.MAX_SURFACE_ID_CHARS + 1),
                        "Too long",
                        "Rejected",
                        PROVIDER,
                        Collections.singletonList(new HubSurfaceDescriptor.Command(
                                "command.example.play", "Play", "capability.example.play")),
                        "sha256:0000000000000000000000000000000000000000000000000000000000000000");
            }
        });
        expectIllegal(new Runnable() {
            @Override public void run() {
                HubSurfaceRegistry.requireBoundedJson(
                        "{\"value\":\"" + repeat("x", 5000) + "\"}",
                        "state");
            }
        });

        JSONObject revoke = new JSONObject()
                .put("$schema", ConnectionHubProtocol.REVOKE_REQUEST_SCHEMA)
                .put("session", cookie)
                .put("reason", "test_complete");
        vectors.validate("revoke_request", revoke);
        JSONObject revokeReceipt = second.revoke(revoke);
        vectors.validate("revoke_receipt", revokeReceipt);
        assertTrue(revokeReceipt.getBoolean("applied"), "revoke failed");

        JSONObject damagedPair = new JSONObject(pair.toString()).put("unexpected", true);
        assertTrue(!first.pair(damagedPair, "wearer.test.evidence").getBoolean("accepted"),
                "unknown pair field was accepted");
        expectSecurity(new Runnable() {
            @Override public void run() { second.requireSession(cookie); }
        });
        System.out.println("Connection Hub core tests passed");
    }

    private static HubSurfaceDescriptor descriptor(HubProviderIdentity identity) {
        java.util.List<HubSurfaceDescriptor.Command> commands = Arrays.asList(
                new HubSurfaceDescriptor.Command(
                        "command.example.pause", "Pause", "capability.example.pause"),
                new HubSurfaceDescriptor.Command(
                        "command.example.play", "Play", "capability.example.play"));
        return new HubSurfaceDescriptor(
                1,
                "surface.example.controls",
                "Example controls",
                "Host conformance provider",
                identity,
                commands,
                HubSurfaceDescriptor.contractSha256(
                        "surface.example.controls",
                        "Example controls",
                        "Host conformance provider",
                        commands));
    }

    private static JSONObject registration() throws Exception {
        return new JSONObject()
                .put("$schema", ConnectionHubProtocol.SURFACE_REGISTRATION_SCHEMA)
                .put("schema_version", 1)
                .put("surface_id", "surface.example.controls")
                .put("display_label", "Example controls")
                .put("description", "Host conformance provider")
                .put("commands", new org.json.JSONArray()
                        .put(new JSONObject()
                                .put("command", "command.example.pause")
                                .put("display_label", "Pause")
                                .put("required_controller_capability", "capability.example.pause"))
                        .put(new JSONObject()
                                .put("command", "command.example.play")
                                .put("display_label", "Play")
                                .put("required_controller_capability", "capability.example.play")))
                .put("surface_contract_sha256", descriptor(PROVIDER).surfaceContractSha256())
                .put("state", new JSONObject().put("playing", false));
    }

    private static HubSurfaceRegistry.Endpoint immediateEndpoint() {
        return new HubSurfaceRegistry.Endpoint() {
            @Override public void dispatch(
                    HubSurfaceRegistry.CommandDispatch dispatch,
                    HubSurfaceRegistry.CommandResultCallback callback) {
                callback.onResult(true, "provider_applied", "{\"playing\":true}");
            }
        };
    }

    private static SecureRandom seededRandom() throws Exception {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(new byte[] {1, 2, 3, 4});
        return random;
    }

    private static final class ReceiptCapture implements ConnectionHubRuntime.CommandReceiptSink {
        JSONObject value;
        @Override public void onReceipt(JSONObject receipt) { value = receipt; }
    }

    private static final class InMemoryStore implements ConnectionHubStateStore {
        State state = State.stopped();
        @Override public State load() { return state; }
        @Override public void save(State state) { this.state = state; }
        @Override public void clear() { state = State.stopped(); }
    }

    private static final class FakeAuthority implements ConnectionHubAuthorityPort {
        long revision = 1;
        long transportEpoch = 1;
        boolean sessionActive;
        final Set<String> consumed = new HashSet<>();

        @Override public Receipt trustAndOpenSession(String requestId, String controller, String evidence, long now) {
            sessionActive = true; revision += 1;
            return applied("open_session", requestId, "session.test", transportEpoch, null, null, null);
        }
        @Override public Receipt replaceTransport(String requestId, String session, long expected, long now) {
            if (!sessionActive || expected != transportEpoch) return Receipt.rejected("stale_transport_epoch");
            transportEpoch += 1; revision += 1;
            return applied("replace_transport", requestId, session, transportEpoch, null, null, null);
        }
        @Override public Receipt registerProvider(String requestId, HubProviderIdentity identity, String instance, String admissionUseRequestId, long now) {
            if (admissionUseRequestId == null || admissionUseRequestId.isEmpty()) return Receipt.rejected("provider_not_admitted");
            revision += 1; return applied("register_provider", requestId, null, 0, null, null, null);
        }
        @Override public Receipt registerSurface(String requestId, String instance, HubSurfaceDescriptor descriptor, long now) {
            revision += 1; return applied("register_surface", requestId, null, 0, null, null, null);
        }
        @Override public Receipt unregisterSurface(String requestId, String instance, String surface, long now) {
            revision += 1; return applied("unregister_surface", requestId, null, 0, null, null, null);
        }
        @Override public Receipt unregisterProvider(String requestId, String instance, String reason, long now) {
            revision += 1; return applied("unregister_provider", requestId, null, 0, null, null, null);
        }
        @Override public Receipt acquireSurfaceLease(String requestId, String session, long epoch, String surface, long now) {
            revision += 1;
            Receipt base = applied("acquire_surface_lease", requestId, session, epoch, surface, null, null);
            return new Receipt(true, "applied", base.authorityReceiptJson, session, epoch, now + 60_000L, "lease.test.surface");
        }
        @Override public Receipt releaseSurfaceLease(String requestId, String session, String lease, String reason, long now) {
            revision += 1; return applied("release_surface_lease", requestId, session, transportEpoch, null, null, null);
        }
        @Override public Receipt authorizeCommand(String requestId, String session, long epoch, String lease, String surface, String command, String paramsSha256, long now) {
            if (!sessionActive) return Receipt.rejected("session_revoked");
            if (epoch != transportEpoch) return Receipt.rejected("stale_transport_epoch");
            if (!consumed.add(requestId)) return Receipt.rejected("replayed_request");
            revision += 1; return applied("authorize_surface_command", requestId, session, epoch, surface, command, requestId);
        }
        @Override public Receipt revokeSession(String requestId, String session, String reason, long now) {
            sessionActive = false; revision += 1;
            return applied("revoke_session", requestId, session, transportEpoch, null, null, null);
        }
        @Override public Receipt forgetAll(String requestId, String reason, long now) {
            sessionActive = false; revision += 1;
            return applied("forget_controller", requestId, null, 0, null, null, null);
        }
        @Override public Receipt expire(String requestId, long now) { return applied("expire", requestId, null, 0, null, null, null); }
        @Override public Receipt reconcileAfterRestart(String requestId, long now) {
            return applied("reconcile_restart", requestId, null, 0, null, null, null);
        }
        @Override public String exportOpaqueState() { return "fake-authority-state-v1"; }
        @Override public Receipt restoreOpaqueState(String state, long now) {
            return "fake-authority-state-v1".equals(state)
                    ? applied("restore", "restore.test", null, 0, null, null, null)
                    : Receipt.rejected("state_rejected");
        }

        private Receipt applied(String operation, String requestId, String session, long epoch,
                String surface, String command, String authorizationRequest) {
            try {
                JSONObject json = new JSONObject()
                        .put("$schema", "rusty.manifold.connection_hub.receipt.v1")
                        .put("operation", operation)
                        .put("request_id", requestId)
                        .put("applied", true)
                        .put("prior_authority_revision", revision - 1)
                        .put("resulting_authority_revision", revision);
                if (authorizationRequest != null) {
                    json.put("command_authorization", new JSONObject()
                            .put("request_id", authorizationRequest)
                            .put("surface_id", surface)
                            .put("command_id", command)
                            .put("typed_params_sha256", "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
                            .put("proves_application_effect", false));
                }
            return new Receipt(true, "applied", json.toString(), session, epoch,
                    System.currentTimeMillis() + 60_000L, null);
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }

    private static void expectSecurity(Runnable action) {
        try { action.run(); throw new AssertionError("expected SecurityException"); }
        catch (SecurityException expected) {}
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(long expected, long actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    private static void assertEquals(int expected, int actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    private static void assertEquals(String expected, String actual) { if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    private static String repeat(String value, int count) { StringBuilder output = new StringBuilder(); for (int i=0;i<count;i++) output.append(value); return output.toString(); }
}
