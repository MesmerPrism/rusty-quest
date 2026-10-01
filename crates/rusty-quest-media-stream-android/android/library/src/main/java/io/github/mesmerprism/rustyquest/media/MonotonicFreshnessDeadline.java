package io.github.mesmerprism.rustyquest.media;

import java.util.function.LongSupplier;

/** Small monotonic progress deadline shared by runtime health and host tests. */
final class MonotonicFreshnessDeadline {
    private final long maxSilence;
    private long lastProgress = -1L;
    MonotonicFreshnessDeadline(long maxSilence) {
        if (maxSilence <= 0L) throw new IllegalArgumentException("maxSilence");
        this.maxSilence=maxSilence;
    }
    synchronized void progress(long now) {
        if (now < 0L || (lastProgress >= 0L && now < lastProgress))
            throw new IllegalArgumentException("non-monotonic progress");
        lastProgress=now;
    }
    synchronized boolean observed() { return lastProgress >= 0L; }
    synchronized boolean fresh(long now) {
        return lastProgress >= 0L && now >= lastProgress && now-lastProgress <= maxSilence;
    }

    /** Sample the monotonic clock under the progress lock so a concurrent producer
     * cannot move lastProgress past an earlier caller-side clock sample. */
    synchronized boolean freshAtCurrentTime(LongSupplier clock) {
        if (clock == null) throw new NullPointerException("clock");
        return fresh(clock.getAsLong());
    }
}
