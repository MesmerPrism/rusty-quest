package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Platform display effects invoked only by the embedded product host. */
public interface EmbeddedDuplexDisplay {
    default void activateOwnProjection() { throw new IllegalStateException("Own display carrier unavailable"); }
    default void requestWholeProjectionStop() { throw new IllegalStateException("whole projection stop unavailable"); }
    long ensureLocalCaptureStopped();
    long preparePeerProjection();
    void bindPeerProjection(long routeGeneration, long decoderToken, long readerGeneration);
    void activatePeerProjection(long routeGeneration, long decoderToken, long readerGeneration);
    long[] currentProjection(long routeGeneration);
    void retirePeerProjection();
    void restoreLocalAfterProductCleanup();
}
