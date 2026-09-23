package io.github.mesmerprism.rustyquest.media;

/** Immutable provider state projected from actual owned resources. */
public final class MediaRuntimeSnapshot {
    private final long generation;
    private final long revision;
    private final String state;
    private final boolean terminal;
    private final String detail;
    private final String providerHandleId;

    public MediaRuntimeSnapshot(long generation, long revision, String state,
            boolean terminal, String detail) {
        this(generation, revision, state, terminal, detail, "unbound");
    }
    public MediaRuntimeSnapshot(long generation, long revision, String state,
            boolean terminal, String detail, String providerHandleId) {
        if (generation <= 0 || revision < 0 || state == null || state.isEmpty()) {
            throw new IllegalArgumentException("invalid runtime snapshot");
        }
        this.generation = generation;
        this.revision = revision;
        this.state = state;
        this.terminal = terminal;
        this.detail = detail == null ? "" : detail;
        if (providerHandleId == null || providerHandleId.isEmpty()) {
            throw new IllegalArgumentException("providerHandleId");
        }
        this.providerHandleId = providerHandleId;
    }
    public long generation() { return generation; }
    public long revision() { return revision; }
    public String state() { return state; }
    public boolean terminal() { return terminal; }
    public String detail() { return detail; }
    public String providerHandleId() { return providerHandleId; }
}
