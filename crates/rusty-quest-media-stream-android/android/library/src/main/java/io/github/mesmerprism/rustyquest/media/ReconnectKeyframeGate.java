package io.github.mesmerprism.rustyquest.media;

/** Requires codec configuration and a keyframe after each transport connection. */
public final class ReconnectKeyframeGate {
    private long generation;
    private boolean configurationSent;
    private boolean keyframeSeen;
    public synchronized void connected(long generation) {
        if (generation <= 0) throw new IllegalArgumentException("generation");
        this.generation = generation;
        configurationSent = false;
        keyframeSeen = false;
    }
    public synchronized void configurationSent(long generation) {
        require(generation); configurationSent = true;
    }
    public synchronized boolean accept(long generation, boolean keyframe) {
        require(generation);
        if (!configurationSent) return false;
        if (keyframe) keyframeSeen = true;
        return keyframeSeen;
    }
    private void require(long expected) {
        if (generation != expected) throw new IllegalStateException("stale transport generation");
    }
}
