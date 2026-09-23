package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure identity gate that prevents foreground noise from postponing a recovery deadline. */
final class NativeRendererSoftKioskRecoveryTimerGate {
    enum Offer { NEW, REPLACED, UNCHANGED }

    private long generation;
    private long episodeId;
    private long deadlineMs = Long.MIN_VALUE;

    Offer offer(long nextGeneration, long nextEpisodeId, long nowMs, long delayMs) {
        if (nextGeneration <= 0L || nextEpisodeId <= 0L || nowMs < 0L || delayMs < 0L) {
            throw new IllegalArgumentException("valid timer identity and monotonic time are required");
        }
        if (generation == nextGeneration && episodeId == nextEpisodeId
                && deadlineMs != Long.MIN_VALUE) {
            return Offer.UNCHANGED;
        }
        Offer result = deadlineMs == Long.MIN_VALUE ? Offer.NEW : Offer.REPLACED;
        generation = nextGeneration;
        episodeId = nextEpisodeId;
        deadlineMs = saturatingAdd(nowMs, delayMs);
        return result;
    }

    boolean consume(long expectedGeneration, long expectedEpisodeId) {
        if (!matches(expectedGeneration, expectedEpisodeId)) {
            return false;
        }
        clear();
        return true;
    }

    boolean matches(long expectedGeneration, long expectedEpisodeId) {
        return deadlineMs != Long.MIN_VALUE
            && generation == expectedGeneration
            && episodeId == expectedEpisodeId;
    }

    long deadlineMs() {
        return deadlineMs;
    }

    void clear() {
        generation = 0L;
        episodeId = 0L;
        deadlineMs = Long.MIN_VALUE;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
