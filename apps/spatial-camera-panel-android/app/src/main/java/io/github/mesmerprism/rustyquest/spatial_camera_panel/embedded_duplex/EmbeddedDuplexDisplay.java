package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Platform display effects invoked only by the embedded product host. */
public interface EmbeddedDuplexDisplay {
    long ensureLocalCaptureStopped();
    long preparePeerProjection();
    void bindPeerProjection(long routeGeneration, long decoderToken, long readerGeneration);
    void activatePeerProjection(long routeGeneration, long decoderToken, long readerGeneration);
    long[] currentProjection(long routeGeneration);
    void retirePeerProjection();
    void restoreLocalAfterProductCleanup();
}
