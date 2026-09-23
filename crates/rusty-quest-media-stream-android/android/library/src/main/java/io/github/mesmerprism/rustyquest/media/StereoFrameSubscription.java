package io.github.mesmerprism.rustyquest.media;

/** A generation-bound source subscription. */
public interface StereoFrameSubscription extends AutoCloseable {
    interface Listener {
        void onFrame(StereoFrameLease frame);
        void onTerminal(MediaRuntimeSnapshot terminal);
    }
    long generation();
    boolean isClosed();
    @Override void close();
}
