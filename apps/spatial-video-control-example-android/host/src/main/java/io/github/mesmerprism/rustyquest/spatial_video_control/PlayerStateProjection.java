package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.util.Objects;

/** Pure projection of callback-observed Media3 state into Quest player state. */
public final class PlayerStateProjection {
    public record Observation(
            String selectedVideoId,
            boolean playing,
            String playbackState,
            long positionMs) {
        public Observation {
            Objects.requireNonNull(selectedVideoId, "selectedVideoId");
            Objects.requireNonNull(playbackState, "playbackState");
            if (positionMs < 0) {
                throw new IllegalArgumentException("position must be non-negative");
            }
        }
    }

    private PlayerStateProjection() {}

    /**
     * Advances the command-concurrency revision for selection, play/pause, or
     * playback-state transitions. Position is observational and may change at
     * the same revision so periodic progress does not make every controller
     * command stale.
     */
    public static PlayerPort.Snapshot apply(
            PlayerPort.Snapshot previous, Observation observed) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(observed, "observed");
        boolean semanticChanged =
                !observed.selectedVideoId().equals(previous.selectedVideoId())
                        || observed.playing() != previous.playing()
                        || !observed.playbackState().equals(previous.playbackState());
        return new PlayerPort.Snapshot(
                semanticChanged ? previous.revision() + 1 : previous.revision(),
                observed.selectedVideoId(),
                observed.playing(),
                observed.playbackState(),
                observed.positionMs());
    }
}
