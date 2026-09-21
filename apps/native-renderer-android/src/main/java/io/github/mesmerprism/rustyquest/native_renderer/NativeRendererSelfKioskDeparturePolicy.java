package io.github.mesmerprism.rustyquest.native_renderer;

/** Counts only departures after confirmed own presentation; focus noise alone never counts. */
final class NativeRendererSelfKioskDeparturePolicy {
    private long generation;
    private long sequence;
    private boolean confirmed;
    private boolean absent;
    private long candidateSince = -1L;
    private long episode;

    long observe(long nextGeneration, boolean present, boolean lifecycleDeparture,
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
        if (!confirmed || !lifecycleDeparture) return 0L;
        if (candidateSince < 0L) candidateSince = nowMs;
        if (nowMs - candidateSince < 750L) return 0L;
        absent = true;
        confirmed = false;
        episode = ++sequence;
        return episode;
    }
}
