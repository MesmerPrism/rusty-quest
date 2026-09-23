package io.github.mesmerprism.rustyquest.media;

/** Asynchronous source that transfers each emitted frame through a lease. */
public interface StereoFrameSource extends AutoCloseable {
    StereoFrameSubscription subscribe(StereoFrameSubscription.Listener listener);
    MediaRuntimeSnapshot snapshot();
    @Override void close();
}
