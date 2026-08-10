package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        testRegistryRuntimeLockOrder();
        testLanAndLoopbackListener();

        FakeAuthority staleProviderAuthority = new FakeAuthority();
        staleProviderAuthority.rejectNextProviderIdentityCollision = true;
        HubSurfaceRegistry recoveredRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime recoveredRuntime = new ConnectionHubRuntime(
                staleProviderAuthority,
                new InMemoryStore(),
                recoveredRegistry,
                seededRandom());
        ConnectionHubAuthorityPort.Receipt recoveredProvider = recoveredRuntime.registerSurface(
                PROVIDER,
                "provider.instance.recovered.1",
                "admission.recovered.1",
                registration(),
                immediateEndpoint());
        assertTrue(recoveredProvider.applied,
                "empty-registry stale provider collision was not reconciled");
        assertEquals(1, staleProviderAuthority.reconcileCalls);
        assertEquals(1, recoveredRegistry.snapshot().size());

        staleProviderAuthority.rejectNextProviderIdentityCollision = true;
        ConnectionHubAuthorityPort.Receipt liveCollision = recoveredRuntime.registerSurface(
                PROVIDER,
                "provider.instance.concurrent.1",
                "admission.concurrent.1",
                registration(),
                immediateEndpoint());
        assertTrue(!liveCollision.applied,
                "live provider collision unexpectedly evicted an active surface");
        assertEquals(1, staleProviderAuthority.reconcileCalls);
        assertEquals(1, recoveredRegistry.snapshot().size());

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
        HubProviderIdentity sampleProvider = new HubProviderIdentity(
                10083, "io.github.example.sample", repeat("bc", 32));
        first.registerSurface(sampleProvider, "provider.instance.sample.1", "admission.sample.1",
                registration(), immediateEndpoint());
        first.unregisterProvider(sampleProvider, "provider.instance.sample.1", "provider_stopped");
        first.registerSurface(PROVIDER, "provider.instance.3", "admission.spatial.2",
                registration(), immediateEndpoint());
        first.unregisterProvider(PROVIDER, "provider.instance.3", "provider_stopped");
        assertEquals(0, firstRegistry.snapshot().size());

        // Android reports provider Binder death on a Binder thread while the
        // replacement provider registers through the admission-service main
        // thread. The full durable unregister/register transitions must be
        // serialized; otherwise the replacement observes a transient prepared
        // write-ahead mutation and fails with nested_authority_mutation.
        FakeAuthority handoffAuthority = new FakeAuthority();
        HubSurfaceRegistry handoffRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime handoffRuntime = new ConnectionHubRuntime(
                handoffAuthority, new InMemoryStore(), handoffRegistry, seededRandom());
        handoffRuntime.registerSurface(
                PROVIDER, "provider.instance.handoff.spatial", "admission.handoff.spatial",
                registration(), immediateEndpoint());
        handoffAuthority.blockNextUnregisterSurface = true;
        AtomicReference<Throwable> unregisterFailure = new AtomicReference<>();
        AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
        Thread unregistering = new Thread(() -> {
            try {
                handoffRuntime.unregisterProvider(
                        PROVIDER, "provider.instance.handoff.spatial", "provider_binder_died");
            } catch (Throwable failure) { unregisterFailure.set(failure); }
        }, "hub-provider-binder-death");
        unregistering.start();
        assertTrue(handoffAuthority.unregisterSurfaceEntered.await(2, TimeUnit.SECONDS),
                "provider unregister did not reach the deterministic authority barrier");
        Thread replacing = new Thread(() -> {
            try {
                handoffRuntime.registerSurface(
                        sampleProvider,
                        "provider.instance.handoff.sample",
                        "admission.handoff.sample",
                        registration(),
                        immediateEndpoint());
            } catch (Throwable failure) { replacementFailure.set(failure); }
        }, "hub-provider-replacement");
        replacing.start();
        Thread.sleep(100);
        assertTrue(replacing.isAlive(),
                "replacement provider did not wait for the in-flight durable unregister");
        handoffAuthority.allowUnregisterSurface.countDown();
        unregistering.join(2000);
        replacing.join(2000);
        assertTrue(!unregistering.isAlive() && !replacing.isAlive(),
                "serialized provider handoff did not terminate");
        assertTrue(unregisterFailure.get() == null && replacementFailure.get() == null,
                "serialized provider handoff returned a runtime failure");
        assertEquals(1, handoffRegistry.snapshot().size());
        assertEquals(sampleProvider.stableKey(),
                handoffRegistry.snapshot().get(0).descriptor.providerIdentity().stableKey());

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
        long beforeCoalescedState = secondRegistry.revision();
        second.updateSurfaceState(PROVIDER, descriptor.surfaceId(),
                new JSONObject().put("playing", false));
        assertEquals(beforeCoalescedState, secondRegistry.revision());
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

        ReceiptCapture staleTransport = new ReceiptCapture();
        second.handleCommand(cookie, 2,
                new JSONObject(command.toString()).put("request_id", "request.stale.transport"),
                staleTransport);
        assertTrue(!staleTransport.value.getBoolean("accepted"),
                "stale socket epoch re-entered after rotation");

        for (int index = 0;
                index < ConnectionHubProtocol.MAX_COMMANDS_PER_SESSION_PER_WINDOW - 2;
                index += 1) {
            ReceiptCapture allowed = new ReceiptCapture();
            second.handleCommand(cookie, 3,
                    new JSONObject(command.toString())
                            .put("request_id", "request.rate." + index),
                    allowed);
            assertTrue(allowed.value.getBoolean("accepted"),
                    "bounded command unexpectedly rejected at " + index);
        }
        ReceiptCapture rateLimited = new ReceiptCapture();
        second.handleCommand(cookie, 3,
                new JSONObject(command.toString()).put("request_id", "request.rate.blocked"),
                rateLimited);
        assertTrue(!rateLimited.value.getBoolean("accepted"),
                "per-session command rate limit did not close");
        assertEquals("command_rate_limited", rateLimited.value.getString("status"));

        for (int index = 0;
                index < ConnectionHubProtocol.MAX_SURFACE_STATE_UPDATES_PER_WINDOW;
                index += 1) {
            second.updateSurfaceState(PROVIDER, descriptor.surfaceId(),
                    new JSONObject().put("tick", index));
        }
        expectIllegalState(new Runnable() {
            @Override public void run() {
                second.updateSurfaceState(PROVIDER, descriptor.surfaceId(),
                        new JSONObject().put("tick", "blocked"));
            }
        });

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

        FailingCommitStore failingPairStore = new FailingCommitStore();
        FakeAuthority failingPairAuthority = new FakeAuthority();
        ConnectionHubRuntime failingPairRuntime = new ConnectionHubRuntime(
                failingPairAuthority, failingPairStore, new HubSurfaceRegistry(), seededRandom());
        failingPairRuntime.startRequested();
        failingPairRuntime.noteListenerStarted();
        failingPairStore.failCommittedAfterPending = true;
        JSONObject failedPair = failingPairRuntime.pair(new JSONObject()
                .put("$schema", ConnectionHubProtocol.PAIR_REQUEST_SCHEMA)
                .put("pairing_code", failingPairRuntime.pairingCodeForWearer())
                .put("controller_identity_sha256", repeat("12", 32)), "wearer.test.evidence");
        assertTrue(!failedPair.getBoolean("accepted"), "durability failure returned pair success");
        assertTrue(!failingPairRuntime.listenerEnabled(), "durability failure did not fail-stop listener");
        assertTrue(!failingPairStore.state.pendingOperation.isEmpty(), "write-ahead marker was not retained");
        failingPairStore.failCommittedAfterPending = false;
        ConnectionHubRuntime reconciled = new ConnectionHubRuntime(
                new FakeAuthority(), failingPairStore, new HubSurfaceRegistry(), seededRandom());
        assertTrue(reconciled.status().getString("status").startsWith("restart_reconciled_pending_"),
                "startup did not report pending-generation reconciliation");

        FailingCommitStore failingProviderStore = new FailingCommitStore();
        HubSurfaceRegistry failingProviderRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime failingProviderRuntime = new ConnectionHubRuntime(
                new FakeAuthority(), failingProviderStore, failingProviderRegistry, seededRandom());
        failingProviderStore.failCommittedAfterPending = true;
        expectIllegalState(new Runnable() {
            @Override public void run() {
                try {
                    failingProviderRuntime.registerSurface(
                            PROVIDER, "provider.instance.fail", "admission.fail",
                            registration(), immediateEndpoint());
                } catch (RuntimeException runtime) { throw runtime; }
                catch (Exception checked) { throw new IllegalStateException(checked); }
            }
        });
        assertEquals(0, failingProviderRegistry.snapshot().size());
        expectSecurity(new Runnable() {
            @Override public void run() { second.requireSession(cookie); }
        });

        // Lease projections expire independently of sessions. They are renewed
        // after one hour, recover once from an authority-side inactive lease,
        // and are deliberately rebuilt rather than persisted across restart.
        ManualClock leaseClock = new ManualClock(1_000_000L);
        InMemoryStore leaseStore = new InMemoryStore();
        FakeAuthority leaseAuthority = new FakeAuthority();
        HubSurfaceRegistry leaseRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime leaseRuntime = new ConnectionHubRuntime(
                leaseAuthority, leaseStore, leaseRegistry, seededRandom(), leaseClock);
        leaseRuntime.startRequested();
        leaseRuntime.noteListenerStarted();
        JSONObject leasePair = leaseRuntime.pair(new JSONObject()
                .put("$schema", ConnectionHubProtocol.PAIR_REQUEST_SCHEMA)
                .put("pairing_code", leaseRuntime.pairingCodeForWearer())
                .put("controller_identity_sha256", repeat("34", 32)), "wearer.test.evidence");
        String leaseCookie = leasePair.getString("session");
        leaseRuntime.registerSurface(PROVIDER, "provider.instance.lease.1", "admission.lease.1",
                registration(), immediateEndpoint());
        ReceiptCapture leaseInitial = new ReceiptCapture();
        leaseRuntime.handleCommand(leaseCookie, 1,
                new JSONObject(command.toString()).put("request_id", "request.lease.initial"),
                leaseInitial);
        assertTrue(leaseInitial.value.getBoolean("accepted"), "initial leased command rejected");
        assertEquals(1, leaseAuthority.leaseAcquisitions);
        leaseClock.advance(3_600_001L);
        ReceiptCapture leaseRenewed = new ReceiptCapture();
        leaseRuntime.handleCommand(leaseCookie, 1,
                new JSONObject(command.toString()).put("request_id", "request.lease.renewed"),
                leaseRenewed);
        assertTrue(leaseRenewed.value.getBoolean("accepted"), "expired lease was not renewed");
        assertEquals(2, leaseAuthority.leaseAcquisitions);
        leaseAuthority.invalidateLeases();
        ReceiptCapture leaseReconciled = new ReceiptCapture();
        leaseRuntime.handleCommand(leaseCookie, 1,
                new JSONObject(command.toString()).put("request_id", "request.lease.reconciled"),
                leaseReconciled);
        assertTrue(leaseReconciled.value.getBoolean("accepted"),
                "inactive authority lease was not reacquired once");
        assertEquals(3, leaseAuthority.leaseAcquisitions);
        leaseRuntime.unregisterProvider(PROVIDER, "provider.instance.lease.1", "restart");
        HubSurfaceRegistry restartedLeaseRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime restartedLeaseRuntime = new ConnectionHubRuntime(
                leaseAuthority, leaseStore, restartedLeaseRegistry, seededRandom(), leaseClock);
        restartedLeaseRuntime.noteListenerStarted();
        restartedLeaseRuntime.registerSurface(PROVIDER, "provider.instance.lease.2",
                "admission.lease.2", registration(), immediateEndpoint());
        ReceiptCapture leaseAfterRestart = new ReceiptCapture();
        restartedLeaseRuntime.handleCommand(leaseCookie, 2,
                new JSONObject(command.toString()).put("request_id", "request.lease.restart"),
                leaseAfterRestart);
        assertTrue(leaseAfterRestart.value.getBoolean("accepted"),
                "restart did not rebuild an authority lease");
        assertEquals(4, leaseAuthority.leaseAcquisitions);

        // Canonical v2 sequencing is authority-derived and remains exact across
        // owner-history rollover, process restart/transport replacement, and
        // authenticated keepalives beyond the original session deadline.
        ManualClock v2Clock = new ManualClock(10_000_000L);
        InMemoryStore v2Store = new InMemoryStore();
        FakeAuthority v2Authority = new FakeAuthority();
        HubSurfaceRegistry v2Registry = new HubSurfaceRegistry();
        ConnectionHubRuntime v2Runtime = new ConnectionHubRuntime(
                v2Authority, v2Store, v2Registry, seededRandom(), v2Clock);
        v2Runtime.startRequested();
        v2Runtime.noteListenerStarted();
        JSONObject v2Pair = v2Runtime.pair(new JSONObject()
                .put("$schema", ConnectionHubProtocol.PAIR_REQUEST_SCHEMA)
                .put("pairing_code", v2Runtime.pairingCodeForWearer())
                .put("controller_identity_sha256", repeat("56", 32)), "wearer.test.evidence");
        String v2Cookie = v2Pair.getString("session");
        v2Runtime.registerSurface(PROVIDER, "provider.instance.v2.1", "admission.v2.1",
                registration(), immediateEndpoint());
        JSONObject v2CommandOne = new JSONObject()
                .put("$schema", ConnectionHubProtocol.SURFACE_COMMAND_SCHEMA_V2)
                .put("type", "surface.command")
                .put("request_sequence", 1)
                .put("request_id", "request.example.1")
                .put("surface_id", descriptor.surfaceId())
                .put("command", "command.example.play")
                .put("args", new JSONObject());
        String v2CommandOneRaw = ConnectionHubRuntime.canonicalJson(v2CommandOne);
        ReceiptCapture v2First = new ReceiptCapture();
        v2Runtime.handleCommand(v2Cookie, 1, v2CommandOne, v2CommandOneRaw, true, v2First);
        assertTrue(v2First.value.getBoolean("accepted"), "canonical v2 command rejected");
        assertEquals(2, v2First.value.getLong("next_external_request_sequence"));
        assertEquals("sha256:6cc14780d7cbe3c4d7ba5dcb2d39a7d4fe0dee95ec0bcb01bab84e263d90d69f",
                v2Authority.lastExternalRequestSha256);
        assertTrue(v2Runtime.forceHistoryRolloverForDebug().applied,
                "debug owner-history rollover failed");
        ReceiptCapture oldExactReplay = new ReceiptCapture();
        v2Runtime.handleCommand(
                v2Cookie, 1, v2CommandOne, v2CommandOneRaw, true, oldExactReplay);
        assertTrue(!oldExactReplay.value.getBoolean("accepted"),
                "exact old v2 frame replayed across owner-history rollover");
        assertEquals(2, oldExactReplay.value.getLong("next_external_request_sequence"));
        JSONObject v2CommandTwo = new JSONObject(v2CommandOne.toString())
                .put("request_sequence", 2)
                .put("request_id", "request.v2.two");
        ReceiptCapture afterRollover = new ReceiptCapture();
        v2Runtime.handleCommand(v2Cookie, 1, v2CommandTwo,
                ConnectionHubRuntime.canonicalJson(v2CommandTwo), true, afterRollover);
        assertTrue(afterRollover.value.getBoolean("accepted"),
                "live provider/surface/lease failed across owner-history rollover");
        assertEquals(3, afterRollover.value.getLong("next_external_request_sequence"));
        v2Runtime.unregisterProvider(PROVIDER, "provider.instance.v2.1", "process_restart");
        HubSurfaceRegistry v2RestartRegistry = new HubSurfaceRegistry();
        ConnectionHubRuntime v2Restart = new ConnectionHubRuntime(
                v2Authority, v2Store, v2RestartRegistry, seededRandom(), v2Clock);
        v2Restart.noteListenerStarted();
        assertEquals(2, v2Restart.requireSession(v2Cookie).transportEpoch);
        assertEquals(3, v2Restart.requireSession(v2Cookie).nextExternalRequestSequence);
        v2Restart.registerSurface(PROVIDER, "provider.instance.v2.2", "admission.v2.2",
                registration(), immediateEndpoint());
        JSONObject v2CommandThree = new JSONObject(v2CommandOne.toString())
                .put("request_sequence", 3)
                .put("request_id", "request.v2.three");
        ReceiptCapture afterRestart = new ReceiptCapture();
        v2Restart.handleCommand(v2Cookie, 2, v2CommandThree,
                ConnectionHubRuntime.canonicalJson(v2CommandThree), true, afterRestart);
        assertTrue(afterRestart.value.getBoolean("accepted"),
                "v2 command failed after restart transport resynchronization");
        v2Clock.advance(29L * 24L * 60L * 60L * 1000L);
        JSONObject keepaliveFour = new JSONObject()
                .put("$schema", ConnectionHubProtocol.KEEPALIVE_SCHEMA_V2)
                .put("type", "keepalive")
                .put("request_sequence", 4);
        JSONObject keepaliveReceipt = v2Restart.handleKeepalive(
                v2Cookie, 2, keepaliveFour, ConnectionHubRuntime.canonicalJson(keepaliveFour));
        assertTrue(keepaliveReceipt.getBoolean("accepted"), "v2 keepalive rejected");
        assertEquals(5, keepaliveReceipt.getLong("next_external_request_sequence"));
        v2Clock.advance(2L * 24L * 60L * 60L * 1000L);
        v2Restart.requireSession(v2Cookie);
        JSONObject keepaliveFive = new JSONObject(keepaliveFour.toString())
                .put("request_sequence", 5);
        JSONObject beyondOriginalTtl = v2Restart.handleKeepalive(
                v2Cookie, 2, keepaliveFive, ConnectionHubRuntime.canonicalJson(keepaliveFive));
        assertTrue(beyondOriginalTtl.getBoolean("accepted"),
                "keepalive did not slide the session beyond its original TTL");
        assertEquals(6, beyondOriginalTtl.getLong("next_external_request_sequence"));
        System.out.println("Connection Hub core tests passed");
    }

    /**
     * Reproduces the handshake/runtime -> registry edge against the provider
     * registry -> runtime-listener edge. Event-sink delivery must remain
     * lock-free or the two threads deadlock in this forced ordering.
     */
    private static void testRegistryRuntimeLockOrder() throws Exception {
        final HubSurfaceRegistry registry = new HubSurfaceRegistry();
        final ConnectionHubRuntime runtime = new ConnectionHubRuntime(
                new FakeAuthority(), new InMemoryStore(), registry, seededRandom());
        runtime.addEventSink(new ConnectionHubRuntime.EventSink() {
            @Override public void broadcast(JSONObject event) {}
            @Override public void closeLogicalSession(String logicalSessionId, String reason) {}
            @Override public void closeAllSessions(String reason) {}
        });
        final CountDownLatch runtimeOwned = new CountDownLatch(1);
        final CountDownLatch registryOwned = new CountDownLatch(1);
        final CountDownLatch enterProviderCallback = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread handshake = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    synchronized (runtime) {
                        runtimeOwned.countDown();
                        if (!registryOwned.await(1, TimeUnit.SECONDS)) {
                            throw new AssertionError("provider did not acquire registry monitor");
                        }
                        enterProviderCallback.countDown();
                        synchronized (registry) {
                            // Models the authenticated baseline snapshot boundary.
                        }
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }
        }, "hub-runtime-registry-order-test");
        Thread provider = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!runtimeOwned.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("handshake did not acquire runtime monitor");
                    }
                    synchronized (registry) {
                        registryOwned.countDown();
                        if (!enterProviderCallback.await(1, TimeUnit.SECONDS)) {
                            throw new AssertionError("handshake did not start registry wait");
                        }
                        registry.register(
                                descriptor(PROVIDER),
                                "{}",
                                "provider.instance.lock-order",
                                immediateEndpoint());
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }
        }, "hub-provider-registry-order-test");
        handshake.setDaemon(true);
        provider.setDaemon(true);
        handshake.start();
        provider.start();
        handshake.join(2000);
        provider.join(2000);
        assertTrue(!handshake.isAlive() && !provider.isAlive(),
                "runtime/registry event delivery lock inversion detected");
        assertTrue(failure.get() == null,
                "runtime/registry lock-order test failed: " + failure.get());
    }

    private static void testLanAndLoopbackListener() throws Exception {
        ConnectionHubRuntime runtime = new ConnectionHubRuntime(
                new FakeAuthority(), new InMemoryStore(), new HubSurfaceRegistry(), seededRandom());
        ConnectionHubHttpServer server = new ConnectionHubHttpServer(
                runtime,
                new ConnectionHubHttpServer.AssetLoader() {
                    @Override public ConnectionHubHttpServer.Asset load(String path) {
                        return new ConnectionHubHttpServer.Asset(
                                "text/plain; charset=utf-8",
                                "test".getBytes(StandardCharsets.UTF_8));
                    }
                });
        assertTrue(ConnectionHubHttpServer.lanAndLoopbackBindAddress().isAnyLocalAddress(),
                "Hub listener bind address is not the IPv4 wildcard");
        int port = server.start(0);
        try {
            assertStatusReachable(InetAddress.getByName("127.0.0.1"), port, "loopback");
            assertStatusReachable(firstNonLoopbackIpv4Address(), port, "LAN");
        } finally {
            server.close();
        }
    }

    private static InetAddress firstNonLoopbackIpv4Address() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address
                        && !address.isAnyLocalAddress()
                        && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        }
        throw new AssertionError("host has no non-loopback IPv4 address for LAN listener proof");
    }

    private static void assertStatusReachable(InetAddress address, int port, String route)
            throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write((
                    "GET /v1/status HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int count;
            while ((count = socket.getInputStream().read(buffer)) >= 0) {
                response.write(buffer, 0, count);
            }
            String raw = new String(response.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(raw.startsWith("HTTP/1.1 200 OK"),
                    "Hub status was not reachable through " + route + " address "
                            + address.getHostAddress());
        }
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

    private static final class FailingCommitStore implements ConnectionHubStateStore {
        State state = State.stopped();
        boolean failCommittedAfterPending;
        @Override public State load() { return state; }
        @Override public void save(State next) {
            if (failCommittedAfterPending
                    && !state.pendingOperation.isEmpty()
                    && next.pendingOperation.isEmpty()) {
                throw new IllegalStateException("injected committed-state failure");
            }
            state = next;
        }
        @Override public void clear() { state = State.stopped(); }
    }

    private static final class FakeAuthority implements ConnectionHubAuthorityPort {
        long revision = 1;
        long transportEpoch = 1;
        long nextExternalRequestSequence = 1;
        long sessionExpiresAtMs;
        boolean sessionActive;
        final Set<String> consumed = new HashSet<>();
        final Set<String> activeProviders = new HashSet<>();
        final Map<String, Long> leaseExpiries = new LinkedHashMap<>();
        int leaseAcquisitions;
        int reconcileCalls;
        boolean rejectNextProviderIdentityCollision;
        volatile boolean blockNextUnregisterSurface;
        final CountDownLatch unregisterSurfaceEntered = new CountDownLatch(1);
        final CountDownLatch allowUnregisterSurface = new CountDownLatch(1);
        String lastExternalRequestSha256;

        @Override public Receipt trustAndOpenSession(String requestId, String controller, String evidence, long now) {
            sessionActive = true; revision += 1;
            nextExternalRequestSequence = 1;
            sessionExpiresAtMs = now + 30L * 24L * 60L * 60L * 1000L;
            Receipt base = applied("open_session", requestId, "session.test", transportEpoch, null, null, null);
            return new Receipt(true, "applied", base.authorityReceiptJson, "session.test",
                    transportEpoch, sessionExpiresAtMs, null, nextExternalRequestSequence);
        }
        @Override public Receipt replaceTransport(String requestId, String session, long expected, long now) {
            if (!sessionActive || expected != transportEpoch) return Receipt.rejected("stale_transport_epoch");
            transportEpoch += 1; revision += 1;
            sessionExpiresAtMs = now + 30L * 24L * 60L * 60L * 1000L;
            Receipt base = applied("replace_transport", requestId, session, transportEpoch, null, null, null);
            return new Receipt(true, "applied", base.authorityReceiptJson, session,
                    transportEpoch, sessionExpiresAtMs, null, nextExternalRequestSequence);
        }
        @Override public Receipt refreshAuthenticatedActivity(String requestId, String session,
                long epoch, long sequence, String externalSha256, long now) {
            return applyAuthenticatedActivity(
                    "refresh_authenticated_activity", requestId, session, epoch,
                    sequence, externalSha256, now, null, null);
        }
        @Override public Receipt registerProvider(String requestId, HubProviderIdentity identity, String instance, String admissionUseRequestId, long now) {
            if (admissionUseRequestId == null || admissionUseRequestId.isEmpty()) return Receipt.rejected("provider_not_admitted");
            if (rejectNextProviderIdentityCollision) {
                rejectNextProviderIdentityCollision = false;
                return Receipt.rejected("rejected_some(identitycollision)");
            }
            if (!activeProviders.add(instance)) return Receipt.rejected("rejected_identitycollision");
            revision += 1; return applied("register_provider", requestId, null, 0, null, null, null);
        }
        @Override public Receipt registerSurface(String requestId, String instance, HubSurfaceDescriptor descriptor, long now) {
            revision += 1; return applied("register_surface", requestId, null, 0, null, null, null);
        }
        @Override public Receipt unregisterSurface(String requestId, String instance, String surface, long now) {
            if (blockNextUnregisterSurface) {
                blockNextUnregisterSurface = false;
                unregisterSurfaceEntered.countDown();
                try {
                    if (!allowUnregisterSurface.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out awaiting provider handoff test release");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("provider handoff test interrupted", interrupted);
                }
            }
            revision += 1; return applied("unregister_surface", requestId, null, 0, null, null, null);
        }
        @Override public Receipt unregisterProvider(String requestId, String instance, String reason, long now) {
            activeProviders.remove(instance);
            revision += 1; return applied("unregister_provider", requestId, null, 0, null, null, null);
        }
        @Override public Receipt acquireSurfaceLease(String requestId, String session, long epoch,
                String providerInstanceId, String surface, long now) {
            revision += 1;
            leaseAcquisitions += 1;
            String leaseId = "lease.test.surface." + leaseAcquisitions;
            leaseExpiries.put(leaseId, now + 3_600_000L);
            Receipt base = applied("acquire_surface_lease", requestId, session, epoch, surface, null, null);
            return new Receipt(true, "applied", base.authorityReceiptJson, session, epoch,
                    now + 3_600_000L, leaseId);
        }
        @Override public Receipt releaseSurfaceLease(String requestId, String session, String lease, String reason, long now) {
            revision += 1; return applied("release_surface_lease", requestId, session, transportEpoch, null, null, null);
        }
        @Override public Receipt authorizeCommand(String requestId, String session, long epoch,
                String lease, String surface, String command, String paramsSha256,
                long sequence, String externalSha256, long now) {
            if (!sessionActive) return Receipt.rejected("session_revoked");
            if (epoch != transportEpoch) return Receipt.rejected("stale_transport_epoch");
            Long leaseExpiry = leaseExpiries.get(lease);
            if (leaseExpiry == null || leaseExpiry <= now) {
                return Receipt.rejected("rejected_surfaceleasenotactive");
            }
            if (!consumed.add(requestId)) return projectedRejection("replayed_request");
            return applyAuthenticatedActivity(
                    "authorize_surface_command", requestId, session, epoch,
                    sequence, externalSha256, now, surface, command);
        }
        @Override public Receipt revokeSession(String requestId, String session, String reason, long now) {
            sessionActive = false; revision += 1;
            return applied("revoke_session", requestId, session, transportEpoch, null, null, null);
        }
        @Override public Receipt forgetAll(String requestId, String reason, long now) {
            sessionActive = false; revision += 1;
            return applied("forget_controller", requestId, null, 0, null, null, null);
        }
        @Override public Receipt expire(String requestId, long now) {
            leaseExpiries.entrySet().removeIf(item -> item.getValue() <= now);
            return applied("expire", requestId, null, 0, null, null, null);
        }
        @Override public Receipt reconcileAfterRestart(String requestId, long now) {
            reconcileCalls += 1;
            activeProviders.clear();
            return applied("reconcile_restart", requestId, null, 0, null, null, null);
        }
        @Override public Receipt forceHistoryRollover(String requestId, long now) {
            revision += 1;
            return applied("history_rollover", requestId, null, 0, null, null, null);
        }
        @Override public String exportOpaqueState() { return "fake-authority-state-v1"; }
        @Override public Receipt restoreOpaqueState(String state, long now) {
            return "fake-authority-state-v1".equals(state)
                    ? applied("restore", "restore.test", null, 0, null, null, null)
                    : Receipt.rejected("state_rejected");
        }

        void invalidateLeases() { leaseExpiries.clear(); }

        private Receipt applyAuthenticatedActivity(
                String operation,
                String requestId,
                String session,
                long epoch,
                long sequence,
                String externalSha256,
                long now,
                String surface,
                String command) {
            if (!sessionActive || epoch != transportEpoch) {
                return projectedRejection("stale_transport_epoch");
            }
            if (sequence != nextExternalRequestSequence
                    || externalSha256 == null
                    || !externalSha256.matches("sha256:[0-9a-f]{64}")) {
                return projectedRejection("replayed_request");
            }
            lastExternalRequestSha256 = externalSha256;
            nextExternalRequestSequence += 1;
            sessionExpiresAtMs = now + 30L * 24L * 60L * 60L * 1000L;
            revision += 1;
            Receipt base = applied(operation, requestId, session, epoch, surface, command,
                    command == null ? null : requestId);
            return new Receipt(true, "applied", base.authorityReceiptJson, session, epoch,
                    sessionExpiresAtMs, null, nextExternalRequestSequence);
        }

        private Receipt projectedRejection(String status) {
            return new Receipt(false, status, "{}", "session.test", transportEpoch,
                    sessionExpiresAtMs, null, nextExternalRequestSequence);
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

    private static final class ManualClock implements ConnectionHubRuntime.Clock {
        long nowMs;
        ManualClock(long nowMs) { this.nowMs = nowMs; }
        @Override public long nowMs() { return nowMs; }
        void advance(long deltaMs) { nowMs += deltaMs; }
    }

    private static void expectSecurity(Runnable action) {
        try { action.run(); throw new AssertionError("expected SecurityException"); }
        catch (SecurityException expected) {}
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void expectIllegalState(Runnable action) {
        try { action.run(); throw new AssertionError("expected IllegalStateException"); }
        catch (IllegalStateException expected) {}
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(long expected, long actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    private static void assertEquals(int expected, int actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    private static void assertEquals(String expected, String actual) { if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    private static String repeat(String value, int count) { StringBuilder output = new StringBuilder(); for (int i=0;i<count;i++) output.append(value); return output.toString(); }
}
