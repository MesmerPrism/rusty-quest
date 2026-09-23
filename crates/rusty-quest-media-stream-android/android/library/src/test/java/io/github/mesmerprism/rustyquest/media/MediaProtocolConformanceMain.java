package io.github.mesmerprism.rustyquest.media;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Pure Java 8 host checks used by the repository build without an Android runtime. */
public final class MediaProtocolConformanceMain {
    public static void main(String[] args) {
        exactPtsAndRelease();
        pairExpiry();
        generationCancellation();
        reconnectGate();
        registryProtocol();
        registryEvidence();
        registryNoMediaBarrier();
        ownerDispatchTcpEndpoint();
        ownerDispatchCloseAbortsRead();
        System.out.println("rusty.quest.android.media.host-conformance.v1:pass");
    }
    private static void exactPtsAndRelease() {
        AtomicInteger releases = new AtomicInteger();
        ExactPresentationTracker tracker = new ExactPresentationTracker(2);
        StereoFrameLease lease = lease(1, 1, 10, releases);
        tracker.submit(lease);
        expectFailure(() -> tracker.take(11));
        StereoFrameLease matched = tracker.take(10);
        matched.close(); matched.close();
        require(releases.get() == 1, "lease was not released exactly once");
        expectFailure(() -> tracker.take(10));
    }
    private static void pairExpiry() {
        AtomicInteger releases = new AtomicInteger();
        StereoFramePairer pairer = new StereoFramePairer(2, 100);
        pairer.offer(StereoFramePairer.Eye.LEFT, lease(2, 1, 20, releases), 0);
        require(pairer.expire(99) == 0, "pair expired early");
        require(pairer.expire(100) == 1 && releases.get() == 1, "pair expiry failed");
        StereoFrameLease left = lease(2, 2, 21, releases);
        StereoFrameLease right = lease(2, 2, 21, releases);
        pairer.offer(StereoFramePairer.Eye.LEFT, left, 200);
        StereoFramePairer.Pair pair = pairer.offer(StereoFramePairer.Eye.RIGHT, right, 201);
        require(pair != null, "exact pair missing"); pair.close(); pair.close();
        require(releases.get() == 3, "paired leases not exactly once");
    }
    private static void generationCancellation() {
        CancellationHandle handle = new CancellationHandle(7);
        handle.requireCurrent(7); handle.cancel();
        expectFailure(() -> handle.requireCurrent(7));
        expectFailure(() -> new CancellationHandle(8).requireCurrent(7));
    }
    private static void reconnectGate() {
        ReconnectKeyframeGate gate = new ReconnectKeyframeGate(); gate.connected(3);
        require(!gate.accept(3, true), "accepted keyframe before config");
        gate.configurationSent(3);
        require(!gate.accept(3, false), "accepted delta before keyframe");
        require(gate.accept(3, true) && gate.accept(3, false), "keyframe gate failed");
        gate.connected(4); expectFailure(() -> gate.accept(3, true));
    }
    private static void registryProtocol() {
        AtomicInteger calls = new AtomicInteger();
        MediaOwnerProvider provider = new MediaOwnerProvider() {
            @Override public MediaProviderReadback execute(MediaOwnerAction action,
                    CancellationHandle cancellation) {
                calls.incrementAndGet(); cancellation.requireCurrent(9);
                return new MediaProviderReadback(action,"handle.source",1,"started","receipt.source");
            }
            @Override public MediaProviderReadback compensate(MediaOwnerAction action,
                    CancellationHandle cancellation) {
                calls.incrementAndGet();
                return new MediaProviderReadback(action,"handle.source",2,"stopped","receipt.stop");
            }
            @Override public MediaRuntimeSnapshot snapshot() {
                return new MediaRuntimeSnapshot(9,1,"started",false,"","handle.source");
            }
        };
        MediaProductBinding binding = new MediaProductBinding.Builder("test")
                .bind("source","owner.source","camera2","camera.stereo",provider).build();
        PackagedAndroidMediaOwnerRegistry registry = new PackagedAndroidMediaOwnerRegistry(9,binding);
        String ticket = ticket(9,"capability.1","source","owner.source","camera2","camera.stereo");
        String readback = registry.execute(ticket,false);
        require(readback.equals(registry.execute(ticket,false))&&calls.get()==1,"duplicate executed twice");
        require(registry.verify(ticket,readback),"issued readback did not verify");
        expectFailure(() -> registry.execute(ticket(8,"capability.2","source","owner.source","camera2","camera.stereo"),false));
        expectFailure(() -> registry.execute(ticket(9,"capability.3","sink","owner.sink","decoder","surface"),false));
        registry.close(); expectFailure(() -> registry.execute(ticket,false));
    }
    private static void registryEvidence() {
        EvidenceProvider provider = new EvidenceProvider();
        MediaProductBinding binding = new MediaProductBinding.Builder("evidence-test")
                .bind("source", "owner.source", "camera2", "camera.stereo", provider).build();
        PackagedAndroidMediaOwnerRegistry registry = new PackagedAndroidMediaOwnerRegistry(9, binding);
        String request = ticket(9, "evidence.1", "source", "owner.source", "camera2", "camera.stereo");
        String readback = registry.execute(request, false);
        require(registry.verifyAndReadEvidence(request, readback + " ") == null,
                "non-issued readback bytes verified");
        provider.revision = 2;
        require(registry.verifyAndReadEvidence(request, readback) == null,
                "stale provider revision verified");
        provider.revision = 1;
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        provider.barrier = new CyclicBarrier(2);
        try {
            Future<String> first = contenders.submit(() -> registry.verifyAndReadEvidence(request, readback));
            Future<String> second = contenders.submit(() -> registry.verifyAndReadEvidence(request, readback));
            String a = first.get(5, TimeUnit.SECONDS);
            String b = second.get(5, TimeUnit.SECONDS);
            require((a == null) != (b == null), "receipt consumption did not have exactly one winner");
            JSONObject evidence = new JSONObject(a == null ? b : a);
            require("rusty.quest.android.media.verified_owner_effect.v1".equals(evidence.getString("$schema")),
                    "evidence schema missing");
            require(evidence.getLong("provider_state_revision") == 1 && !evidence.getBoolean("terminal"),
                    "evidence did not retain actual provider state");
            require(evidence.getString("readback_sha256").matches("sha256:[0-9a-f]{64}")
                    && evidence.getString("detail_sha256").matches("sha256:[0-9a-f]{64}"),
                    "evidence digest missing");
            require(registry.verifyAndReadEvidence(request, readback) == null, "receipt consumed twice");
        } catch (Exception failure) {
            throw new AssertionError("concurrent evidence verification failed", failure);
        } finally {
            provider.barrier = null;
            contenders.shutdownNow();
        }
        String compensation = registry.execute(request, true);
        require(registry.verifyAndReadEvidence(request, compensation) == null,
                "compensation accepted while platform handles remained live");
        provider.terminal = true;
        require(registry.verifyAndReadEvidence(request, compensation) != null,
                "terminal compensation did not verify");
        registry.close();
    }
    private static void registryNoMediaBarrier() {
        AtomicInteger attempts = new AtomicInteger();
        MediaOwnerProvider provider = new MediaOwnerProvider() {
            @Override public MediaProviderReadback execute(MediaOwnerAction action,
                    CancellationHandle cancellation) {
                attempts.incrementAndGet();
                return new MediaProviderReadback(action, "handle.no-media", 1, "started", "receipt.no-media");
            }
            @Override public MediaProviderReadback compensate(MediaOwnerAction action,
                    CancellationHandle cancellation) { return execute(action, cancellation); }
            @Override public MediaRuntimeSnapshot snapshot() {
                return new MediaRuntimeSnapshot(9, 1, "started", false, "", "handle.no-media");
            }
        };
        MediaProductBinding binding = new MediaProductBinding.Builder("no-media")
                .bind("source", "owner.source", "camera2", "camera.stereo", provider).build();
        String ticket = ticket(9, "no-media.1", "source", "owner.source", "camera2", "camera.stereo");
        PackagedAndroidMediaOwnerRegistry unused = new PackagedAndroidMediaOwnerRegistry(9, binding);
        unused.closeIfNeverAttempted();
        unused.closeIfNeverAttempted();
        expectFailure(() -> unused.execute(ticket, false));
        require(attempts.get() == 0, "provider started after no-media close");

        PackagedAndroidMediaOwnerRegistry completed = new PackagedAndroidMediaOwnerRegistry(9, binding);
        String readback = completed.execute(ticket, false);
        require(completed.verifyAndReadEvidence(ticket, readback) != null,
                "completed effect did not verify");
        expectFailure(completed::closeIfNeverAttempted);
        require(attempts.get() == 1, "completed effect history was lost");

        MediaOwnerProvider failing = new MediaOwnerProvider() {
            @Override public MediaProviderReadback execute(MediaOwnerAction action,
                    CancellationHandle cancellation) { throw new IllegalStateException("provider failed after entry"); }
            @Override public MediaProviderReadback compensate(MediaOwnerAction action,
                    CancellationHandle cancellation) { return execute(action, cancellation); }
            @Override public MediaRuntimeSnapshot snapshot() {
                return new MediaRuntimeSnapshot(9, 0, "stopped", true, "", "handle.failed");
            }
        };
        MediaProductBinding failedBinding = new MediaProductBinding.Builder("failed-no-media")
                .bind("source", "owner.source", "camera2", "camera.stereo", failing).build();
        PackagedAndroidMediaOwnerRegistry failed = new PackagedAndroidMediaOwnerRegistry(9, failedBinding);
        expectFailure(() -> failed.execute(ticket, false));
        expectFailure(failed::closeIfNeverAttempted);
    }

    private static void ownerDispatchTcpEndpoint() {
        AtomicInteger calls = new AtomicInteger();
        OwnerDispatchTcpEndpoint endpoint = null;
        try {
            endpoint = new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(), 0, request -> {
                calls.incrementAndGet();
                byte[] response = request.clone();
                for (int i = 0; i < response.length / 2; i++) {
                    byte swap = response[i];
                    response[i] = response[response.length - 1 - i];
                    response[response.length - 1 - i] = swap;
                }
                return response;
            });
            require(endpoint.port() > 0 && endpoint.ready(), "ephemeral endpoint was not ready");
            byte[] request = "exact-owner-frame".getBytes(StandardCharsets.US_ASCII);
            byte[] expected = request.clone();
            for (int i = 0; i < expected.length / 2; i++) {
                byte swap = expected[i];
                expected[i] = expected[expected.length - 1 - i];
                expected[expected.length - 1 - i] = swap;
            }
            byte[] response = OwnerDispatchTcpEndpoint.exchange(
                    InetAddress.getLoopbackAddress(), endpoint.port(), request);
            require(Arrays.equals(response, expected) && calls.get() == 1,
                    "loopback transport changed bytes or repeated the handler");

            final int port = endpoint.port();
            expectIoFailure(() -> OwnerDispatchTcpEndpoint.exchange(
                    InetAddress.getLoopbackAddress(), port,
                    new byte[OwnerDispatchTcpEndpoint.MAX_FRAME_BYTES + 1]));
            sendMalformedFrame(endpoint.port(), OwnerDispatchTcpEndpoint.MAX_FRAME_BYTES + 1, null);
            sendMalformedFrame(endpoint.port(), 8, new byte[] {1, 2, 3});
            require(calls.get() == 1, "malformed frame reached handler");
        } catch (Exception failure) {
            throw new AssertionError("owner dispatch loopback failed", failure);
        } finally {
            if (endpoint != null) endpoint.close();
        }
        require(endpoint != null && endpoint.terminal(), "closed endpoint was not terminal");

        AtomicInteger uncertainCalls = new AtomicInteger();
        try (OwnerDispatchTcpEndpoint lost = new OwnerDispatchTcpEndpoint(
                InetAddress.getLoopbackAddress(), 0, request -> {
                    uncertainCalls.incrementAndGet();
                    throw new IOException("simulated response loss");
                })) {
            expectIoFailure(() -> OwnerDispatchTcpEndpoint.exchange(
                    InetAddress.getLoopbackAddress(), lost.port(), new byte[] {7}));
            require(uncertainCalls.get() == 1,
                    "lost response was retried or replaced with a synthetic success");
        } catch (IOException failure) {
            throw new AssertionError("lost-response endpoint setup failed", failure);
        }
    }
    private static void ownerDispatchCloseAbortsRead() {
        OwnerDispatchTcpEndpoint endpoint = null;
        Socket pending = null;
        try {
            AtomicInteger calls = new AtomicInteger();
            endpoint = new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(), 0, request -> {
                calls.incrementAndGet();
                return request;
            });
            pending = new Socket(InetAddress.getLoopbackAddress(), endpoint.port());
            DataOutputStream output = new DataOutputStream(pending.getOutputStream());
            output.writeInt(16);
            output.writeByte(1);
            output.flush();
            awaitTrackedSocket(endpoint);
            endpoint.close();
            require(endpoint.terminal(), "close did not terminate listener, workers, deadlines, and sockets");
            require(calls.get() == 0, "partial request reached handler during close");
            require(countThreads("rusty-owner-control-") == 0,
                    "owner endpoint thread remained after terminal close");
        } catch (Exception failure) {
            throw new AssertionError("pending endpoint read was not aborted", failure);
        } finally {
            if (pending != null) try { pending.close(); } catch (IOException ignored) { }
            if (endpoint != null && !endpoint.terminal()) endpoint.close();
        }
    }
    private static void sendMalformedFrame(int port, int declaredLength, byte[] content)
            throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(2_000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(declaredLength);
            if (content != null) output.write(content);
            output.flush();
            socket.shutdownOutput();
            require(socket.getInputStream().read() == -1, "malformed request received a response");
        }
    }
    private static int countThreads(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) count++;
        }
        return count;
    }
    private static void awaitTrackedSocket(OwnerDispatchTcpEndpoint endpoint) throws Exception {
        java.lang.reflect.Field field = OwnerDispatchTcpEndpoint.class.getDeclaredField("sockets");
        field.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (((java.util.Set<?>) field.get(endpoint)).isEmpty()
                && System.nanoTime() < deadline) Thread.sleep(10);
        require(!((java.util.Set<?>) field.get(endpoint)).isEmpty(),
                "pending connection was not accepted for close test");
    }
    private static void expectIoFailure(IoCall call) {
        try { call.run(); throw new AssertionError("expected I/O failure"); }
        catch (IOException expected) { }
    }
    private interface IoCall { void run() throws IOException; }
    private static final class EvidenceProvider implements MediaOwnerProvider {
        volatile long revision = 1;
        volatile String state = "started";
        volatile boolean terminal;
        volatile CyclicBarrier barrier;
        @Override public MediaProviderReadback execute(MediaOwnerAction action, CancellationHandle cancellation) {
            return new MediaProviderReadback(action, "handle.evidence", revision, state, "receipt.evidence");
        }
        @Override public MediaProviderReadback compensate(MediaOwnerAction action, CancellationHandle cancellation) {
            revision = 2;
            state = "stopped";
            return new MediaProviderReadback(action, "handle.evidence", revision, state, "receipt.compensation");
        }
        @Override public MediaRuntimeSnapshot snapshot() {
            CyclicBarrier current = barrier;
            if (current != null) {
                try { current.await(3, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException("snapshot rendezvous failed", failure); }
            }
            return new MediaRuntimeSnapshot(9, revision, state, terminal, "bounded provider detail", "handle.evidence");
        }
    }
    private static String ticket(long generation,String capability,String ownerKind,String ownerId,
            String providerKind,String resourceId) {
        return "{\"$schema\":\"rusty.quest.android.media.execution-ticket.v1\","
                + "\"capability\":\""+capability+"\",\"executor_generation\":"+generation+","
                + "\"action_id\":\"action.1\",\"authority_epoch_id\":\"epoch.1\","
                + "\"media_acceptance_authority_revision\":1,\"expected_runtime_revision\":2,"
                + "\"client_id\":\"client.1\",\"lease_id\":\"lease.1\",\"sequence\":1,"
                + "\"operation\":\"start\",\"owner_kind\":\""+ownerKind+"\","
                + "\"action_kind\":\"start\",\"owner_id\":\""+ownerId+"\","
                + "\"provider_kind\":\""+providerKind+"\",\"resource_id\":\""+resourceId+"\"}";
    }
    private static StereoFrameLease lease(long generation, long frame, long pts,
            AtomicInteger releases) {
        return new StereoFrameLease(new StereoFrameIdentity(generation, frame, pts * 1000,
                pts * 1000 + 1, pts), releases::incrementAndGet);
    }
    private static void expectFailure(Runnable call) {
        try { call.run(); throw new AssertionError("expected failure"); }
        catch (IllegalStateException expected) { }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
