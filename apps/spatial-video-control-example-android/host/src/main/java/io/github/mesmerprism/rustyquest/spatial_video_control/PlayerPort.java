package io.github.mesmerprism.rustyquest.spatial_video_control;

public interface PlayerPort {
    record Snapshot(
            long revision,
            String selectedVideoId,
            boolean playing,
            String playbackState,
            long positionMs) {}

    record AcceptedEffect(
            String requestId,
            String command,
            long expectedPlayerRevision,
            long acceptedAuthorityRevision,
            String videoId) {}

    record AppliedEffect(AcceptedEffect cause, Snapshot state) {}

    interface Listener {
        void onApplied(AppliedEffect effect);

        void onFailed(AcceptedEffect effect, String reason);
    }

    Snapshot snapshot();

    void setListener(Listener listener);

    void selectVideo(AcceptedEffect effect);

    void play(AcceptedEffect effect);

    void pause(AcceptedEffect effect);
}
