package io.github.mesmerprism.rustyquest.media;

import java.util.concurrent.atomic.AtomicInteger;

/** Pure Java 8 host checks used by the repository build without an Android runtime. */
public final class MediaProtocolConformanceMain {
    public static void main(String[] args) {
        exactPtsAndRelease();
        pairExpiry();
        generationCancellation();
        reconnectGate();
        registryProtocol();
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
