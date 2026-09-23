package io.github.mesmerprism.rustyquest.media;

import java.util.concurrent.atomic.AtomicBoolean;

/** Generation-bound cancellation. Replacing a registry invalidates every older handle. */
public final class CancellationHandle {
    private final long generation;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public CancellationHandle(long generation) {
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
    }
    public long generation() { return generation; }
    public boolean cancel() { return cancelled.compareAndSet(false, true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void requireCurrent(long expectedGeneration) {
        if (cancelled.get() || generation != expectedGeneration) {
            throw new IllegalStateException("media execution generation is stale");
        }
    }
}
