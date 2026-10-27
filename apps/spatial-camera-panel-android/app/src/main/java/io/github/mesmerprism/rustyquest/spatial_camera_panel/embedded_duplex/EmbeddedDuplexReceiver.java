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
        default void start(java.util.function.BooleanSupplier currentAuthority)throws Exception{
            if(!currentAuthority.getAsBoolean())throw new IllegalStateException("receiver startup authority no longer current");
            start();
        }
        MediaRuntimeSnapshot snapshot();
        default void prepareDormantDecoder(org.json.JSONObject format)throws Exception {throw new IllegalStateException("dormant decoder unavailable");}
        default boolean preparedDecoderHeld(){return false;}
        default boolean preparedDecoderOwned(){return false;}
        default boolean preparedDeliveryOpened(){return false;}
        default long preparedPeerDeliveryCount(){throw new IllegalStateException("prepared delivery counter unavailable");}
        default void requestPreparedStop(){throw new IllegalStateException("prepared Stop unavailable");}
        default void abortPrepared(){throw new IllegalStateException("prepared abort unavailable");}
        /** Exact local receiver lifetime, independent of transaction publication. */
        default void stopOwnedLocalResources(){throw new IllegalStateException("local receiver retirement unavailable");}
        /** Closed enum/counter fields only; never exception text or endpoints. */
        default String closedCounters() { return "counters=UNAVAILABLE"; }
        default org.json.JSONObject diagnosticStages() throws Exception {
            return new org.json.JSONObject().put("status", "unsupported")
                    .put("qualification_claimed", false);
        }
    }

    interface RuntimeFactory {
        /** Same native lifetime boundary as this factory's decoder/reader resources. */
        default void retireNativeGeneration(long generation) {
            EmbeddedDuplexNative.retireReceiverGeneration(generation);
        }
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
    private volatile long incarnation;
    private volatile boolean dormantPrepared;
    private volatile String dormantPreparationId;
    private volatile String dormantRendererReadback;
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

    EmbeddedDuplexReceiver(long generation, EmbeddedDuplexDisplay display, String bindHost,
            int bindPort, String expectedSourceHost, int width, int height, int fpsCap,
            PackedStereoMediaReceiver.Bounds bounds) {
        this(generation, display, bindHost, bindPort, width, height, fpsCap, bounds,
                new ProductionRuntimeFactory(expectedSourceHost));
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
    synchronized org.json.JSONObject prepareDormantDecoder(String id,org.json.JSONObject format,java.util.function.BooleanSupplier stop)throws Exception {
        if(stop==null)throw new IllegalArgumentException("actual desired Stop unavailable");
        if(dormantPreparationId!=null)throw new IllegalStateException("actual dormant decoder already entered");dormantPreparationId=id;
        prepare(true,format,stop);dormantRendererReadback=display.prepareDormantPeerRenderer(id,staged.routeGeneration(),stop);
        return new org.json.JSONObject(dormantRendererReadback);
    }
    boolean preparedDecoderClosed(){ReceiverRuntime held=receiver;return dormantPrepared &&held!=null&&held.preparedDecoderHeld();}
    /** Caller has durably retained the exact old PreparedStart archive. No SDK work. */
    synchronized void retireArchivedPreparedMetadata(String id) {
        if(id==null||!id.matches("[0-9a-f]{32}")
            ||dormantPreparationId!=null&&!dormantPreparationId.equals(id))
            throw new IllegalStateException("archived decoder ID differs");
        MediaRuntimeSnapshot actual=snapshot();
        if(!surfaceReleased||!projectionRetired||!actual.terminal()||actual.generation()!=generation)
            throw new IllegalStateException("archived decoder cleanup remains Pending");
        display.retireArchivedPreparedRenderer(id);
        dormantPreparationId=null;dormantRendererReadback=null;dormantPrepared=false;
    }
    boolean preparedDecoderOwned(){ReceiverRuntime held=receiver;return dormantPrepared&&held!=null&&held.preparedDecoderOwned();}
    boolean preparedDeliveryOpened(){ReceiverRuntime held=receiver;return dormantPrepared&&held!=null&&held.preparedDeliveryOpened();}
    long preparedPeerDeliveryCount(){ReceiverRuntime held=receiver;return held==null?0L:held.preparedPeerDeliveryCount();}
    void requestPreparedStop(){ReceiverRuntime held=receiver;if(dormantPrepared&&held!=null)held.requestPreparedStop();}
    synchronized org.json.JSONObject abortDormantDecoder(String id)throws Exception {
        if(dormantPreparationId!=null&&!dormantPreparationId.equals(id))throw new IllegalStateException("actual dormant cleanup ID differs");
        // Same receiver monitor serializes against the complete JNI prepare call.
        // No terminal result can escape while that call owns a temporary window.
        org.json.JSONObject renderer=new org.json.JSONObject(display.abortDormantPeerRenderer(id));
        if("unprepared".equals(preparationState)){closeUnstartedAndVerify();return renderer;}
        if(!dormantPrepared && !"preparation_failed".equals(preparationState))
            throw new IllegalStateException("receiver is not an actual dormant/partially entered preparation");
        ReceiverRuntime held=receiver;if(held!=null)held.abortPrepared();
        if(held!=null)releaseStoppedReaderAndProjection();
        else {
            // A thrown stage/create call does not prove no effect. Retire the actual
            // generation and every installed reader; failures retain these owners.
            runtimeFactory.retireNativeGeneration(generation);
            if(staged!=null && !surfaceReleased && !staged.release())throw new IllegalStateException("dormant reader cleanup pending");
            surfaceReleased=true;retireProjection();preparationState="cleaned";preparationRevision++;
        }
        if(!snapshot().terminal())throw new IllegalStateException("actual dormant decoder cleanup remains Pending");return renderer;
    }
    private void prepare(){prepare(false,null,null);}
    private void prepare(boolean dormant,org.json.JSONObject format,java.util.function.BooleanSupplier stop) {
        if("cleaned".equals(preparationState) || "stopped".equals(preparationState)) {
            if(!surfaceReleased || !projectionRetired || !snapshot().terminal())
                throw new IllegalStateException("prior receiver cleanup unresolved");
            staged=null;receiver=null;provider=null;
            surfaceReleased=false;connectionGeneration=0L;
        } else if (!"unprepared".equals(preparationState)) {
            throw new IllegalStateException("receiver preparation already attempted");
        }
        final long preparingIncarnation=Math.addExact(incarnation,1L);
        incarnation=preparingIncarnation;
        preparationState = "preparing";
        preparationRevision++;
        projectionRetired = false;
        try {
            armStage = ArmStage.PEER_PROJECTION;
            long routeGeneration = dormant?display.reserveDormantPeerProjection(stop):display.preparePeerProjection();
            dormantPrepared=dormant;
            armStage = ArmStage.READER_STAGE;
            staged = runtimeFactory.stage(width, height, 4, fpsCap, routeGeneration);
            armStage = ArmStage.READER_IDENTITY;
            if (staged == null || staged.readerGeneration() <= 0L) {
                throw new IllegalStateException("embedded reader generation unavailable");
            }
            final ProjectionResource preparingProjection=staged;
            armStage = ArmStage.RECEIVER_CREATE;
            receiver = runtimeFactory.create(staged, sourceHost, sourcePort,
                    generation, bounds, new PackedStereoMediaReceiver.FrameLifecycleListener() {
                @Override public boolean onFrameReadyForRender(long receiverGeneration,
                        long connection, long presentationTimeNs,
                        PackedStereoMediaReceiver.FrameIdentity identity) {
                    if (preparingIncarnation!=incarnation || surfaceReleased || receiverGeneration != generation || connection <= 0L) return false;
                    connectionGeneration = connection;
                    return EmbeddedDuplexNative.registerReceiverFrame(
                            identityWords(preparingProjection,connection, presentationTimeNs, identity));
                }
                @Override public void onFrameRendered(long receiverGeneration, long connection,
                        long presentationTimeNs, PackedStereoMediaReceiver.FrameIdentity identity) {
                    if (preparingIncarnation!=incarnation || surfaceReleased || receiverGeneration != generation
                            || connection != connectionGeneration
                            || !EmbeddedDuplexNative.recordReceiverFrameRendered(
                                    identityWords(preparingProjection,connection, presentationTimeNs, identity))) {
                        throw new IllegalStateException("embedded rendered frame identity rejected");
                    }
                }
                @Override public void onConnectionRetired(long receiverGeneration, long connection) {
                    if (preparingIncarnation!=incarnation || receiverGeneration != generation) return;
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
            if(dormant) {
                receiver.prepareDormantDecoder(format);
                if(!receiver.preparedDecoderHeld())throw new IllegalStateException("actual dormant decoder readiness absent");
                preparationState="dormant_prepared";
            } else {
                display.bindPeerProjection(staged.routeGeneration(),staged.decoderToken(),staged.readerGeneration());
                preparationState="prepared";
            }
        } catch (Exception failure) {
            failedArmStage = armStage;
            preparationState = "preparation_failed";
            preparationRevision++;
            // The registry retains this object, including the exact staged handle.
            // Its compensating action performs and verifies cleanup, with retries.
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new IllegalStateException("receiver preparation failed", failure);
        }
    }

    /** Closed observations only; start may already have performed its internal Stop. */
    String activationDiagnostic() {
        ReceiverRuntime current = receiver;
        if (current == null) return "receiverState=UNPREPARED connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1";
        MediaRuntimeSnapshot snapshot = current.snapshot();
        if (snapshot == null) return "receiverState=UNAVAILABLE connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1";
        String[] detail = snapshot.detail().split(",", 5);
        if (detail.length < 4 || !detail[0].startsWith("packets=")
                || !detail[1].startsWith("frames=") || !detail[2].startsWith("reconnects=")
                || !detail[3].startsWith("connection=")) {
            return "receiverState=" + closedReceiverState(snapshot.state())
                    + " connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1";
        }
        return "receiverState=" + closedReceiverState(snapshot.state())
                + " connection=" + closedReceiverState(detail[3].substring(11))
                + " packets=" + closedCounter(detail[0].substring(8))
                + " frames=" + closedCounter(detail[1].substring(7))
                + " reconnects=" + closedCounter(detail[2].substring(11))
                + " " + current.closedCounters();
    }

    private static String closedReceiverState(String state) {
        if (state == null) return "UNAVAILABLE";
        switch (state) {
            case "new": return "NEW";
            case "receiver_armed": return "ARMED";
            case "connecting": return "CONNECTING";
            case "listening": return "LISTENING";
            case "decoder_configured": return "DECODER_CONFIGURED";
            case "receiving": return "RECEIVING";
            case "waiting_reconnect": return "WAITING_RECONNECT";
            case "stopping": return "STOPPING";
            case "stopped": return "STOPPED";
            case "failed": return "FAILED";
            default: return "UNAVAILABLE";
        }
    }

    private static long closedCounter(String value) {
        if (!value.matches("[0-9]{1,19}")) return -1L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException unavailable) { return -1L; }
    }

    public long routeGeneration() { return staged == null ? 0L : staged.routeGeneration(); }
    public long decoderToken() { return staged == null ? 0L : staged.decoderToken(); }
    public long readerGeneration() { return staged == null ? 0L : staged.readerGeneration(); }

    /** Called only after Start; the embedded receiver waits for an actual Surface image. */
    public void awaitFirstSurfaceImage() throws Exception { awaitFirstSurfaceImage(()->true); }
    public void awaitFirstSurfaceImage(java.util.function.BooleanSupplier currentAuthority) throws Exception {
        ReceiverRuntime current = receiver;
        if (current == null || surfaceReleased) throw new IllegalStateException("receiver not prepared");
        current.start(currentAuthority);
    }

    /** Current exact native image acquisition; no codec callback or GPU proof is implied. */
    public long[] currentAcquiredFrameTimed(long maxAgeNs) {
        long connection = connectionGeneration;
        ReceiverRuntime current = receiver;
        ProjectionResource projection = staged;
        if (surfaceReleased || connection <= 0L || current == null || projection == null
                || !"receiving".equals(current.snapshot().state())) return null;
        long[] evidence = EmbeddedDuplexNative.currentReceiverAcquiredFrameTimed(generation,
                connection, projection.routeGeneration(), projection.decoderToken(),
                projection.readerGeneration(), maxAgeNs);
        return connection == connectionGeneration && projection == staged && !surfaceReleased
                && exactV2(evidence, EmbeddedDuplexNative.ACQUIRED_TIMED_OBSERVATION_WORDS,
                        connection, projection) ? evidence : null;
    }

    /** GPU-fence-retired native effect for the current exact acquisition. */
    public long[] currentEffectiveFrameTimed(long maxAgeNs) {
        long connection = connectionGeneration;
        ReceiverRuntime current = receiver;
        ProjectionResource projection = staged;
        if (surfaceReleased || connection <= 0L || current == null || projection == null
                || !"receiving".equals(current.snapshot().state())) return null;
        long[] evidence = EmbeddedDuplexNative.currentReceiverEffectiveFrameTimed(generation,
                connection, projection.routeGeneration(), projection.decoderToken(),
                projection.readerGeneration(), maxAgeNs);
        return connection == connectionGeneration && projection == staged && !surfaceReleased
                && exactV2(evidence, EmbeddedDuplexNative.EFFECTIVE_TIMED_OBSERVATION_WORDS,
                        connection, projection) ? evidence : null;
    }

    private boolean exactV2(long[] evidence, int length, long connection,
            ProjectionResource projection) {
        return evidence != null && evidence.length == length
                && evidence[0] == EmbeddedDuplexNative.FRAME_EVIDENCE_VERSION
                && evidence[1] == generation && evidence[2] == connection
                && evidence[3] == projection.routeGeneration()
                && evidence[4] == projection.decoderToken()
                && evidence[5] == projection.readerGeneration();
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
        cancellation.requireCurrent(generation);
        if(action.executorGeneration()!=generation)throw new IllegalArgumentException("receiver executor generation mismatch");
        if ("arm_receiver".equals(action.actionKind())) {
            if(dormantPrepared) {
                if(!"dormant_prepared".equals(preparationState)||receiver==null||!receiver.preparedDecoderHeld())
                    throw new IllegalStateException("actual prepared decoder not ready");
                long committed=display.preparePeerProjection();
                if(committed!=staged.routeGeneration())throw new IllegalStateException("actual dormant route commit differs");
                display.bindPeerProjection(committed,staged.decoderToken(),staged.readerGeneration());
                preparationState="prepared";
            } else prepare();
        }
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
        cancellation.requireCurrent(generation);
        if(action.executorGeneration()!=generation)throw new IllegalArgumentException("receiver executor generation mismatch");
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

    /** Pure coherent observation; never releases or verifies an owner effect. */
    synchronized org.json.JSONObject physicalSnapshot() throws Exception {
        MediaRuntimeSnapshot current = snapshot();
        return new org.json.JSONObject()
                .put("generation", current.generation()).put("incarnation", incarnation)
                .put("provider_handle_id", current.providerHandleId())
                .put("provider_state_revision", current.revision()).put("state", current.state())
                .put("terminal", current.terminal()).put("surface_released", surfaceReleased)
                .put("projection_retired", projectionRetired);
    }

    /** Retire the held local decoder/reader/projection, retaining ticket history.
     * This does not mint a signed remote Stop or settle any old transaction. */
    synchronized void retireOwnedLocalResources() {
        if(("cleaned".equals(preparationState)||"stopped".equals(preparationState))&&surfaceReleased&&projectionRetired)return;
        if("unprepared".equals(preparationState)){closeUnstartedAndVerify();return;}
        ReceiverRuntime held=receiver;
        if(held!=null){held.stopOwnedLocalResources();releaseStoppedReaderAndProjection(true);}
        else {
            runtimeFactory.retireNativeGeneration(generation);
            if(staged!=null&&!surfaceReleased)awaitOwnedReaderRelease(staged);
            surfaceReleased=true;retireProjection();preparationState="cleaned";preparationRevision++;
        }
        if(!snapshot().terminal())throw new IllegalStateException("actual local receiver retirement remains Pending");
    }

    /** Closes only the receiver that has never entered an owner Sink effect. */
    synchronized void closeUnstartedAndVerify() {
        if (("cleaned".equals(preparationState) || "stopped".equals(preparationState))
                && surfaceReleased && projectionRetired) return;
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
        preparationState = "stop".equals(action.actionKind()) ? "stopped" : "cleaned";
        preparationRevision++;
        return new MediaProviderReadback(action, "embedded-receiver." + generation,
                preparationRevision, preparationState,
                "embedded-receiver-cleanup." + generation + "." + preparationRevision);
    }

    private void releaseStoppedReaderAndProjection() { releaseStoppedReaderAndProjection(false); }

    private void awaitOwnedReaderRelease(ProjectionResource actual) {
        long warningAt=System.nanoTime()+10_000_000_000L;
        while(!actual.release()){
            if(staged!=actual)throw new IllegalStateException("actual Whole reader owner changed");
            if(System.nanoTime()>=warningAt){
                System.err.println("embedded Whole reader cleanup remains Pending generation="+generation);
                warningAt=System.nanoTime()+10_000_000_000L;
            }
            try{Thread.sleep(50L);}catch(InterruptedException stopped){
                Thread.currentThread().interrupt();throw new IllegalStateException("actual Whole reader cleanup interrupted",stopped);
            }
        }
    }
    private void releaseStoppedReaderAndProjection(boolean owningWhole) {
        MediaRuntimeSnapshot stopped = receiver.snapshot();
        if (!stopped.terminal() || stopped.generation() != generation) {
            throw new IllegalStateException("receiver still owns decoder resources");
        }
        if (!surfaceReleased) {
            runtimeFactory.retireNativeGeneration(generation);
            if(owningWhole)awaitOwnedReaderRelease(staged);
            else if (!staged.release()) {
                throw new IllegalStateException("embedded reader cleanup remains pending");
            }
            surfaceReleased = true;
        }
        retireProjection();
        preparationState="cleaned";
        preparationRevision++;
    }

    private void retireProjection() {
        if (projectionRetired) return;
        display.retirePeerProjection();
        projectionRetired = true;
    }

    private long[] identityWords(ProjectionResource projection,long connection, long ptsNs,
            PackedStereoMediaReceiver.FrameIdentity identity) {
        return new long[] { generation, connection, projection.routeGeneration(), projection.decoderToken(),
                projection.readerGeneration(), ptsNs, identity.sourceElapsedNs, identity.sourceUnixNs,
                identity.pairId, identity.leftSourceFrame, identity.rightSourceFrame,
                identity.leftSensorTimestampNs, identity.rightSensorTimestampNs, identity.pairDeltaNs };
    }

    org.json.JSONObject diagnosticStages() throws Exception {
        ReceiverRuntime current = receiver;
        if (current == null) return new org.json.JSONObject().put("status", "unprepared")
                .put("qualification_claimed", false).put("receiver_generation", generation);
        return current.diagnosticStages();
    }

    private static final class ProductionRuntimeFactory implements RuntimeFactory {
        private final String expectedSourceHost;
        ProductionRuntimeFactory() { this.expectedSourceHost = null; }
        ProductionRuntimeFactory(String expectedSourceHost) {
            if (expectedSourceHost == null || expectedSourceHost.isEmpty()) {
                throw new IllegalArgumentException("accepted source host absent");
            }
            this.expectedSourceHost = expectedSourceHost;
        }
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
            PackedStereoMediaReceiver.SurfaceAcquisitionProbe probe = (receiverGeneration, connection) -> {
                long[] acquired = EmbeddedDuplexNative.currentReceiverAcquiredFrameTimed(
                        receiverGeneration, connection, staged.routeGeneration,
                        staged.decoderToken, staged.readerGeneration, 500_000_000L);
                if (acquired == null) return null;
                if (acquired.length != EmbeddedDuplexNative.ACQUIRED_TIMED_OBSERVATION_WORDS
                        || acquired[0] != EmbeddedDuplexNative.FRAME_EVIDENCE_VERSION
                        || acquired[1] != receiverGeneration || acquired[2] != connection
                        || acquired[3] != staged.routeGeneration
                        || acquired[4] != staged.decoderToken
                        || acquired[5] != staged.readerGeneration) return null;
                return new PackedStereoMediaReceiver.AcquiredFrame(acquired[1], acquired[2],
                        acquired[6], acquired[7], acquired[8], acquired[9], acquired[10],
                        acquired[11], acquired[12], acquired[13], acquired[14]);
            };
            return new ProductionReceiverRuntime(new PackedStereoMediaReceiver(staged.surface,
                    sourceHost, sourcePort, generation, bounds, null, listener,
                    expectedSourceHost, probe));
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
        @Override public void prepareDormantDecoder(org.json.JSONObject format)throws Exception{receiver.prepareDormantDecoder(format);}
        @Override public boolean preparedDecoderHeld(){return receiver.preparedDecoderHeld();}
        @Override public boolean preparedDecoderOwned(){return receiver.preparedDecoderOwned();}
        @Override public boolean preparedDeliveryOpened(){return receiver.preparedDeliveryOpened();}
        @Override public long preparedPeerDeliveryCount(){return receiver.preparedPeerDeliveryCount();}
        @Override public void requestPreparedStop(){receiver.requestPreparedStop();}
        @Override public void abortPrepared(){receiver.stopAndVerify();}
        @Override public void stopOwnedLocalResources(){receiver.stopAndVerify();}
        @Override public void start() throws Exception { receiver.start(); }
        @Override public void start(java.util.function.BooleanSupplier currentAuthority)throws Exception{receiver.start(currentAuthority);}
        @Override public MediaRuntimeSnapshot snapshot() { return receiver.snapshot(); }
        @Override public String closedCounters() { return receiver.closedActivationCounters(); }
        @Override public org.json.JSONObject diagnosticStages() throws Exception {
            return receiver.diagnosticStageSnapshot();
        }
    }
}
