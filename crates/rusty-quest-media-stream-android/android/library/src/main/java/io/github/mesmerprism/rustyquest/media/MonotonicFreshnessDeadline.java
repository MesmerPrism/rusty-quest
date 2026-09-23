package io.github.mesmerprism.rustyquest.media;

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
}
