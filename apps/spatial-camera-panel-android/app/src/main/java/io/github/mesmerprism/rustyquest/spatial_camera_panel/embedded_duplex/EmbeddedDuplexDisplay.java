package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Platform display effects invoked only by the embedded product host. */
public interface EmbeddedDuplexDisplay {
    default void resumeOwnProjection(java.util.function.BooleanSupplier ownerAlive) {
        if (ownerAlive==null || !ownerAlive.getAsBoolean()) throw new IllegalStateException("Own resume superseded");
        activateOwnProjection();
    }
    default void activateOwnProjection() { throw new IllegalStateException("Own display carrier unavailable"); }
    default void requestWholeProjectionStop() { throw new IllegalStateException("whole projection stop unavailable"); }
    long ensureLocalCaptureStopped();
    long preparePeerProjection();
    default long reserveDormantPeerProjection() { throw new IllegalStateException("dormant reader route unavailable"); }
    /** Selected preparation retains its actual Original Stop signal while Pending. */
    default long reserveDormantPeerProjection(java.util.function.BooleanSupplier desiredStop) {
        throw new IllegalStateException("cancellable dormant reader route unavailable");
    }
    default String prepareDormantPeerRenderer(String preparationId,long generation,java.util.function.BooleanSupplier desiredStop){throw new IllegalStateException("retained renderer preparation unavailable");}
    default String abortDormantPeerRenderer(String preparationId){throw new IllegalStateException("retained renderer abort unavailable");}
    /** Metadata only, after exact durable archive and actual terminal owner joins. */
    default void retireArchivedPreparedRenderer(String preparationId){throw new IllegalStateException("prepared renderer metadata retirement unavailable");}
    void bindPeerProjection(long routeGeneration, long decoderToken, long readerGeneration);
    void activatePeerProjection(long routeGeneration, long decoderToken, long readerGeneration);
    long[] currentProjection(long routeGeneration);
    void retirePeerProjection();
    void restoreLocalAfterProductCleanup();
}
