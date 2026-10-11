package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure owner state machine driven only by the native intended schedule projection.
 * No timer, classifier phase, file path, or media-position clock can generate a cue.
 * Calls run on the owning audio executor; Backend has an independent background channel.
 */
final class BreathPhaseAudioEngine {
    interface Backend {
        void startBackground();
        void pauseBackground();
        void resumeBackground();
        void stopBackground();
        void replaceCue(String phase);
        void stopCue();
    }
    private final Backend backend;
    private final boolean enabled;
    private long generation;
    private long segment = -1;
    private long lastTime = -1;
    private boolean running;
    private boolean closed;
    private boolean terminal;
    BreathPhaseAudioEngine(boolean enabled, Backend backend) {
        if (backend == null) throw new IllegalArgumentException("audio backend required");
        this.enabled = enabled; this.backend = backend;
    }
    boolean running() { return running && !closed; }
    void start(long nextGeneration) {
        if (!enabled || closed || nextGeneration <= generation) return;
        if (generation > 0) { backend.stopCue(); backend.stopBackground(); }
        generation = nextGeneration; segment = -1; lastTime = -1; terminal = false; running = true;
        backend.startBackground();
    }
    void pause(long selectedGeneration) {
        if (!enabled || closed || selectedGeneration != generation || !running) return;
        running = false; backend.stopCue(); backend.pauseBackground();
    }
    void resume(long selectedGeneration) {
        if (!enabled || closed || selectedGeneration != generation || running || terminal) return;
        running = true; backend.resumeBackground();
        // Current segment remains consumed; a mid-phase resume never repeats its cue.
    }
    void stop(long selectedGeneration) {
        if (!enabled || closed || selectedGeneration != generation || generation == 0) return;
        backend.stopCue(); backend.stopBackground(); running = false; terminal = true;
    }
    void close() { if (!closed && enabled) { backend.stopCue(); backend.stopBackground(); running = false; closed = true; } }
    void gap(long selectedGeneration) { if (enabled && !closed && selectedGeneration == generation) backend.stopCue(); }
    void sample(long selectedGeneration, long index, long startMs, long endMs,
                long activeTimeMs, String phase) {
        if (!enabled || closed || !running || selectedGeneration != generation) return;
        if (index < 0 || startMs < 0 || endMs <= startMs || activeTimeMs < startMs
                || activeTimeMs >= endMs || !("inhale".equals(phase)
                || "hold".equals(phase) || "exhale".equals(phase))) {
            backend.stopCue(); return;
        }
        if (activeTimeMs < lastTime || index < segment) {
            // No replay after a backward seek/regression in the authoritative clock.
            backend.stopCue(); running = false; backend.pauseBackground(); return;
        }
        if (index == segment) { lastTime = activeTimeMs; return; }
        backend.stopCue();
        segment = index; lastTime = activeTimeMs;
        // Current segment gets one cue even after a seek; skipped segments never burst.
        backend.replaceCue(phase);
    }
}
