package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.media.CancellationHandle;
import io.github.mesmerprism.rustyquest.media.MediaOwnerAction;
import io.github.mesmerprism.rustyquest.media.MediaOwnerProvider;
import io.github.mesmerprism.rustyquest.media.MediaProviderReadback;
import io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaReceiver;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialStereoVideoPlayback;

/** Owns one shared receiver and the common graph's host reader for its lifetime. */
public final class EmbeddedDuplexReceiver implements MediaOwnerProvider {
    interface ProjectionResource {
        long routeGeneration();
        long decoderToken();
        long readerGeneration();
        boolean release();
    }

    interface ReceiverRuntime {
        MediaOwnerProvider provider();
        void start() throws Exception;
        MediaRuntimeSnapshot snapshot();
    }

    interface RuntimeFactory {
        ProjectionResource stage(int width, int height, int imageCount, int fpsCap,
                long routeGeneration);
        ReceiverRuntime create(ProjectionResource projection, String sourceHost, int sourcePort,
                long generation, PackedStereoMediaReceiver.Bounds bounds,
                PackedStereoMediaReceiver.FrameLifecycleListener listener);
    }

    private final long generation;
    private final EmbeddedDuplexDisplay display;
    private final RuntimeFactory runtimeFactory;
    private final String sourceHost;
    private final int sourcePort, width, height, fpsCap;
    private final PackedStereoMediaReceiver.Bounds bounds;
    private volatile ProjectionResource staged;
    private volatile ReceiverRuntime receiver;
    private volatile MediaOwnerProvider provider;
    private volatile String preparationState = "unprepared";
    private volatile long preparationRevision;
    private volatile long connectionGeneration;
    private volatile boolean surfaceReleased;
    private volatile boolean projectionRetired = true;
    enum ArmStage { NONE, PEER_PROJECTION, READER_STAGE, READER_IDENTITY, RECEIVER_CREATE,
        PROVIDER_GETTER, PEER_BIND, RECEIVER_EFFECT }
    private volatile ArmStage armStage = ArmStage.NONE;
    private volatile ArmStage failedArmStage = ArmStage.NONE;
    String failedArmStage() { return failedArmStage.name(); }

    public EmbeddedDuplexReceiver(long generation, EmbeddedDuplexDisplay display, String sourceHost,
            int sourcePort, int width, int height, int fpsCap,
            PackedStereoMediaReceiver.Bounds bounds) {
        this(generation, display, sourceHost, sourcePort, width, height, fpsCap, bounds,
                new ProductionRuntimeFactory());
    }

    EmbeddedDuplexReceiver(long generation, EmbeddedDuplexDisplay display, String sourceHost,
            int sourcePort, int width, int height, int fpsCap,
            PackedStereoMediaReceiver.Bounds bounds, RuntimeFactory runtimeFactory) {
        if (generation <= 0L || display == null || bounds == null || runtimeFactory == null) {
            throw new IllegalArgumentException("receiver binding");
        }
        this.generation = generation;
        this.display = display;
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.width = width;
        this.height = height;
        this.fpsCap = fpsCap;
        this.bounds = bounds;
        this.runtimeFactory = runtimeFactory;
    }

    // Allocate only inside the authenticated Sink effect, after this retryable owner
    // has already been installed in the registry. A failed preparation retains it.
    private void prepare() {
        if (!"unprepared".equals(preparationState)) {
            throw new IllegalStateException("receiver preparation already attempted");
        }
        preparationState = "preparing";
        preparationRevision++;
        projectionRetired = false;
        try {
            armStage = ArmStage.PEER_PROJECTION;
            long routeGeneration = display.preparePeerProjection();
            armStage = ArmStage.READER_STAGE;
            staged = runtimeFactory.stage(width, height, 4, fpsCap, routeGeneration);
            armStage = ArmStage.READER_IDENTITY;
            if (staged == null || staged.readerGeneration() <= 0L) {
                throw new IllegalStateException("embedded reader generation unavailable");
            }
            armStage = ArmStage.RECEIVER_CREATE;
            receiver = runtimeFactory.create(staged, sourceHost, sourcePort,
                    generation, bounds, new PackedStereoMediaReceiver.FrameLifecycleListener() {
                @Override public boolean onFrameReadyForRender(long receiverGeneration,
                        long connection, long presentationTimeNs,
                        PackedStereoMediaReceiver.FrameIdentity identity) {
                    if (surfaceReleased || receiverGeneration != generation || connection <= 0L) return false;
                    connectionGeneration = connection;
                    return EmbeddedDuplexNative.registerReceiverFrame(
                            identityWords(connection, presentationTimeNs, identity));
                }
                @Override public void onFrameRendered(long receiverGeneration, long connection,
                        long presentationTimeNs, PackedStereoMediaReceiver.FrameIdentity identity) {
                    if (surfaceReleased || receiverGeneration != generation
                            || connection != connectionGeneration
                            || !EmbeddedDuplexNative.recordReceiverFrameRendered(
                                    identityWords(connection, presentationTimeNs, identity))) {
                        throw new IllegalStateException("embedded rendered frame identity rejected");
                    }
                }
                @Override public void onConnectionRetired(long receiverGeneration, long connection) {
                    if (receiverGeneration != generation) return;
                    EmbeddedDuplexNative.retireReceiverConnection(receiverGeneration, connection);
                    if (connectionGeneration == connection) connectionGeneration = 0L;
                }
            });
            armStage = ArmStage.PROVIDER_GETTER;
            if (receiver == null || receiver.provider() == null) {
                throw new IllegalStateException("embedded receiver unavailable");
            }
            provider = receiver.provider();
            armStage = ArmStage.PEER_BIND;
            display.bindPeerProjection(staged.routeGeneration(), staged.decoderToken(),
                    staged.readerGeneration());
            preparationState = "prepared";
        } catch (RuntimeException failure) {
            failedArmStage = armStage;
            preparationState = "preparation_failed";
            preparationRevision++;
            // The registry retains this object, including the exact staged handle.
            // Its compensating action performs and verifies cleanup, with retries.
            throw failure;
        }
    }

    public long routeGeneration() { return staged == null ? 0L : staged.routeGeneration(); }
    public long decoderToken() { return staged == null ? 0L : staged.decoderToken(); }
    public long readerGeneration() { return staged == null ? 0L : staged.readerGeneration(); }

    /** Called only after the full ordered product Start has completed. */
    public void awaitFirstRenderedFrame() throws Exception {
        ReceiverRuntime current = receiver;
        if (current == null || surfaceReleased) throw new IllegalStateException("receiver not prepared");
        current.start();
    }

    /** Null means there is no current matching render AND native acquisition. */
    public long[] currentFrame(long maxAgeNs) {
        long connection = connectionGeneration;
        ReceiverRuntime current = receiver;
        if (surfaceReleased || connection <= 0L || current == null
                || !"receiving".equals(current.snapshot().state())) return null;
        long[] evidence = EmbeddedDuplexNative.currentReceiverFrame(generation, connection,
                staged.routeGeneration(), staged.decoderToken(), staged.readerGeneration(), maxAgeNs);
        return connection == connectionGeneration && !surfaceReleased
                && evidence != null && evidence.length == EmbeddedDuplexNative.FRAME_OBSERVATION_WORDS
                ? evidence : null;
    }

    /** Native-clock receipt witness, preserving the exact 17-word frame ABI. */
    public long[] currentTimedFrame(long maxAgeNs) {
        long connection = connectionGeneration;
        ReceiverRuntime current = receiver;
        if (surfaceReleased || connection <= 0L || current == null
                || !"receiving".equals(current.snapshot().state())) return null;
        long[] evidence = EmbeddedDuplexNative.currentReceiverFrameTimed(generation, connection,
                staged.routeGeneration(), staged.decoderToken(), staged.readerGeneration(), maxAgeNs);
        return connection == connectionGeneration && !surfaceReleased
                && evidence != null && evidence.length == EmbeddedDuplexNative.FRAME_TIMED_OBSERVATION_WORDS
                ? evidence : null;
    }

    @Override public synchronized MediaProviderReadback execute(MediaOwnerAction action,
            CancellationHandle cancellation) throws Exception {
        if ("arm_receiver".equals(action.actionKind())) prepare();
        if (provider == null) {
            if (!("stop".equals(action.actionKind()) || "cleanup".equals(action.actionKind()))) {
                throw new IllegalStateException("receiver preparation incomplete");
            }
            return cleanupUnprepared(action);
        }
        MediaProviderReadback result;
        try {
            armStage = ArmStage.RECEIVER_EFFECT;
            result = provider.execute(action, cancellation);
        } catch (Exception failure) {
            if ("arm_receiver".equals(action.actionKind())) failedArmStage = armStage;
            throw failure;
        }
        if ("stop".equals(action.actionKind()) || "cleanup".equals(action.actionKind())) {
            releaseStoppedReaderAndProjection();
        }
        return result;
    }

    @Override public synchronized MediaProviderReadback compensate(MediaOwnerAction action,
            CancellationHandle cancellation) throws Exception {
        if (provider == null) return cleanupUnprepared(action);
        MediaProviderReadback result = provider.compensate(action, cancellation);
        releaseStoppedReaderAndProjection();
        return result;
    }

    @Override public MediaRuntimeSnapshot snapshot() {
        if (receiver == null || ("preparation_failed".equals(preparationState)
                && !(surfaceReleased && projectionRetired))) {
            return new MediaRuntimeSnapshot(generation, preparationRevision, preparationState,
                    surfaceReleased && projectionRetired, "embedded host reader preparation",
                    "embedded-receiver." + generation);
        }
        MediaRuntimeSnapshot current = receiver.snapshot();
        return new MediaRuntimeSnapshot(current.generation(), current.revision(), current.state(),
                current.terminal() && surfaceReleased && projectionRetired,
                current.detail(), current.providerHandleId());
    }

    /** Closes only the receiver that has never entered an owner Sink effect. */
    synchronized void closeUnstartedAndVerify() {
        if ("cleaned".equals(preparationState) && surfaceReleased && projectionRetired) return;
        if (!"unprepared".equals(preparationState) || staged != null || receiver != null
                || provider != null || connectionGeneration != 0L) {
            throw new IllegalStateException("receiver needs typed owner cleanup");
        }
        surfaceReleased = true;
        preparationState = "cleaned";
        preparationRevision++;
        if (!snapshot().terminal()) {
            throw new IllegalStateException("unstarted receiver cleanup remains pending");
        }
    }

    private MediaProviderReadback cleanupUnprepared(MediaOwnerAction action) {
        if (staged != null && !surfaceReleased && !staged.release()) {
            preparationState = "cleanup_pending";
            preparationRevision++;
            throw new IllegalStateException("embedded reader cleanup remains pending");
        }
        surfaceReleased = true;
        retireProjection();
        preparationState = "cleaned";
        preparationRevision++;
        return new MediaProviderReadback(action, "embedded-receiver." + generation,
                preparationRevision, preparationState,
                "embedded-receiver-cleanup." + generation + "." + preparationRevision);
    }

    private void releaseStoppedReaderAndProjection() {
        MediaRuntimeSnapshot stopped = receiver.snapshot();
        if (!stopped.terminal() || stopped.generation() != generation) {
            throw new IllegalStateException("receiver still owns decoder resources");
        }
        if (!surfaceReleased) {
            EmbeddedDuplexNative.retireReceiverGeneration(generation);
            if (!staged.release()) {
                throw new IllegalStateException("embedded reader cleanup remains pending");
            }
            surfaceReleased = true;
        }
        retireProjection();
    }

    private void retireProjection() {
        if (projectionRetired) return;
        display.retirePeerProjection();
        projectionRetired = true;
    }

    private long[] identityWords(long connection, long ptsNs,
            PackedStereoMediaReceiver.FrameIdentity identity) {
        return new long[] { generation, connection, staged.routeGeneration(), staged.decoderToken(),
                staged.readerGeneration(), ptsNs, identity.sourceElapsedNs, identity.sourceUnixNs,
                identity.pairId, identity.leftSourceFrame, identity.rightSourceFrame,
                identity.leftSensorTimestampNs, identity.rightSensorTimestampNs, identity.pairDeltaNs };
    }

    private static final class ProductionRuntimeFactory implements RuntimeFactory {
        @Override public ProjectionResource stage(int width, int height, int imageCount, int fpsCap,
                long routeGeneration) {
            return new ProductionProjectionResource(
                    SpatialStereoVideoPlayback.stageEmbeddedProjectionPeerSurface(
                            width, height, imageCount, fpsCap, routeGeneration));
        }

        @Override public ReceiverRuntime create(ProjectionResource projection, String sourceHost,
                int sourcePort, long generation, PackedStereoMediaReceiver.Bounds bounds,
                PackedStereoMediaReceiver.FrameLifecycleListener listener) {
            if (!(projection instanceof ProductionProjectionResource)) {
                throw new IllegalArgumentException("production projection resource");
            }
            SpatialStereoVideoPlayback.EmbeddedProjectionPeerSurface staged =
                    ((ProductionProjectionResource) projection).staged;
            return new ProductionReceiverRuntime(new PackedStereoMediaReceiver(staged.surface,
                    sourceHost, sourcePort, generation, bounds, null, listener));
        }
    }

    private static final class ProductionProjectionResource implements ProjectionResource {
        private final SpatialStereoVideoPlayback.EmbeddedProjectionPeerSurface staged;

        ProductionProjectionResource(SpatialStereoVideoPlayback.EmbeddedProjectionPeerSurface staged) {
            if (staged == null) throw new IllegalArgumentException("staged projection resource");
            this.staged = staged;
        }

        @Override public long routeGeneration() { return staged.routeGeneration; }
        @Override public long decoderToken() { return staged.decoderToken; }
        @Override public long readerGeneration() { return staged.readerGeneration; }
        @Override public boolean release() {
            return SpatialStereoVideoPlayback.releaseEmbeddedProjectionPeerSurface(staged);
        }
    }

    private static final class ProductionReceiverRuntime implements ReceiverRuntime {
        private final PackedStereoMediaReceiver receiver;

        ProductionReceiverRuntime(PackedStereoMediaReceiver receiver) {
            this.receiver = receiver;
        }

        @Override public MediaOwnerProvider provider() { return receiver.provider(); }
        @Override public void start() throws Exception { receiver.start(); }
        @Override public MediaRuntimeSnapshot snapshot() { return receiver.snapshot(); }
    }
}
