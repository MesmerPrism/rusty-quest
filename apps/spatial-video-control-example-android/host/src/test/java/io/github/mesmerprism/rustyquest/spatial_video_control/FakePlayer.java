package io.github.mesmerprism.rustyquest.spatial_video_control;

/** Callback-driven fake: effects become visible only when a test calls flush(). */
final class FakePlayer implements PlayerPort {
    private Snapshot state =
            new Snapshot(0, "synthetic-grid-1s", false, "ready", 0);
    private Listener listener;
    private AcceptedEffect pending;

    @Override
    public synchronized Snapshot snapshot() {
        return state;
    }

    @Override
    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized void selectVideo(AcceptedEffect effect) {
        requireCommand(effect, "select_video");
        queue(effect);
    }

    @Override
    public synchronized void play(AcceptedEffect effect) {
        requireCommand(effect, "play");
        queue(effect);
    }

    @Override
    public synchronized void pause(AcceptedEffect effect) {
        requireCommand(effect, "pause");
        queue(effect);
    }

    @Override
    public synchronized void cancelPending(AcceptedEffect effect) {
        if (pending != null
                && pending.requestId().equals(effect.requestId())
                && pending.acceptedCommandReceiptId().equals(effect.acceptedCommandReceiptId())) {
            pending = null;
        }
    }

    synchronized void flush() {
        AcceptedEffect effect = pending;
        if (effect == null) {
            throw new IllegalStateException("no pending effect");
        }
        pending = null;
        state =
                switch (effect.command()) {
                    case "select_video" ->
                            new Snapshot(
                                    state.revision() + 1,
                                    effect.videoId(),
                                    false,
                                    "ready",
                                    0);
                    case "play" ->
                            new Snapshot(
                                    state.revision() + 1,
                                    state.selectedVideoId(),
                                    true,
                                    "ready",
                                    state.positionMs());
                    case "pause" ->
                            new Snapshot(
                                    state.revision() + 1,
                                    state.selectedVideoId(),
                                    false,
                                    "ready",
                                    state.positionMs());
                    default -> throw new IllegalStateException("unknown effect");
                };
        listener.onApplied(new AppliedEffect(effect, state));
    }

    synchronized void observeReadyPosition(long positionMs) {
        state =
                new Snapshot(
                        state.revision() + 1,
                        state.selectedVideoId(),
                        state.playing(),
                        "ready",
                        positionMs);
        if (listener != null) {
            listener.onStateObserved(state);
        }
    }

    private void queue(AcceptedEffect effect) {
        if (pending != null) {
            throw new IllegalStateException("fake player already has pending effect");
        }
        if (effect.expectedPlayerRevision() != state.revision()) {
            listener.onFailed(effect, "fake_player_revision_mismatch");
            return;
        }
        pending = effect;
    }

    private static void requireCommand(AcceptedEffect effect, String command) {
        if (!effect.command().equals(command)) {
            throw new IllegalArgumentException("wrong player method");
        }
    }
}
