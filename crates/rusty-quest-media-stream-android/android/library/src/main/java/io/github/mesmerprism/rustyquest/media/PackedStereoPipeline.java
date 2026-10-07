package io.github.mesmerprism.rustyquest.media;

/** Connected packed-stereo pipeline lifecycle consumed by exact owner providers. */
public interface PackedStereoPipeline {
    void validateRoute();
    void startSocket();
    void startCodec() throws Exception;
    void startProcessor() throws Exception;
    void startSource() throws Exception;
    void stopAndVerify(String reason);
    void requireStopped();
    void finishCleanup();
    /** Fresh graph only after physical retirement; old implementations remain one-shot. */
    default void recreateAfterVerifiedCleanup() {
        throw new IllegalStateException("pipeline reincarnation unsupported");
    }
    boolean failed();
    boolean terminal();
    String observedState();
    String handleId();
    void close();
}
