package io.github.mesmerprism.rustyquest.native_renderer;

/** Counts sustained own-surface departures after a confirmed presentation. */
final class NativeRendererSelfKioskDeparturePolicy {
    private long generation;
    private long sequence;
    private boolean confirmed;
    private boolean absent;
    private long candidateSince = -1L;
    private long episode;

    long observe(long nextGeneration, boolean present, boolean observedDeparture,
            boolean suppressed, long nowMs) {
        if (generation != nextGeneration) {
            generation = nextGeneration;
            confirmed = false;
            absent = false;
            candidateSince = -1L;
            episode = 0L;
        }
        if (suppressed) {
            // A prompt, handoff, lock or sleep cannot become an escape retrospectively.
            confirmed = false;
            candidateSince = -1L;
            episode = 0L;
            return 0L;
        }
        if (present) {
            confirmed = true;
            absent = false;
            candidateSince = -1L;
            episode = 0L;
            return 0L;
        }
        if (absent) return episode;
        if (!confirmed || !observedDeparture) return 0L;
        if (candidateSince < 0L) candidateSince = nowMs;
        if (nowMs - candidateSince < 250L) return 0L;
        absent = true;
        confirmed = false;
        episode = ++sequence;
        return episode;
    }
}
