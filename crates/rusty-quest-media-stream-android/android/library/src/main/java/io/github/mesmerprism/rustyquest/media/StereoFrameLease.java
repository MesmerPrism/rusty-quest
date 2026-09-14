package io.github.mesmerprism.rustyquest.media;

import java.util.concurrent.atomic.AtomicBoolean;

/** Exactly-once ownership of a stereo frame's platform resources. */
public final class StereoFrameLease implements AutoCloseable {
    private final StereoFrameIdentity identity;
    private final Runnable releaser;
    private final AtomicBoolean released = new AtomicBoolean();

    public StereoFrameLease(StereoFrameIdentity identity, Runnable releaser) {
        if (identity == null || releaser == null) throw new NullPointerException();
        this.identity = identity;
        this.releaser = releaser;
    }
    public StereoFrameIdentity identity() { return identity; }
    public boolean isReleased() { return released.get(); }
    @Override public void close() {
        if (released.compareAndSet(false, true)) releaser.run();
    }
}
