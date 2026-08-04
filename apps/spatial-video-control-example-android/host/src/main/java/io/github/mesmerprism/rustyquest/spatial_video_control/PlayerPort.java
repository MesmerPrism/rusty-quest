package io.github.mesmerprism.rustyquest.spatial_video_control;

public interface PlayerPort {
    /**
     * Effective Quest state. Revision advances for semantic selection,
     * play/pause, and playback-state transitions; position is an observational
     * field and may update without invalidating a pending controller command.
     */
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
            ManifoldAuthorityPort.AuthorityRevisions acceptedAuthorityRevisions,
            String acceptedCommandReceiptId,
            String videoId) {}

    record AppliedEffect(AcceptedEffect cause, Snapshot state) {}

    interface Listener {
        void onApplied(AppliedEffect effect);

        void onFailed(AcceptedEffect effect, String reason);

        void onStateObserved(Snapshot state);
    }

    Snapshot snapshot();

    void setListener(Listener listener);

    void selectVideo(AcceptedEffect effect);

    void play(AcceptedEffect effect);

    void pause(AcceptedEffect effect);

    /**
     * Cancels one still-pending submission after the bounded coordinator
     * deadline. Cancellation never claims an application effect.
     */
    void cancelPending(AcceptedEffect effect);
}
