package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;

/** Inert public observation producer. The caller owns subscription authentication.
 * No network, credentials, command dispatch or physical freshness is implied. */
final class ExperimentSessionStatusObservation {
    private static final long MAX = 9007199254740991L;
    private final String channel;
    private final long epoch;
    private long sequence, readId, generation, revision, ageMs;
    private ExperimentSessionPanelState observedState;
    private boolean visible, exhausted;

    // A new runtime epoch requires a new separately authenticated subscription.
    ExperimentSessionStatusObservation(String channel, long epoch) {
        if (channel == null || !channel.matches("[A-Za-z0-9_-]{1,64}") || epoch <= 0L)
            throw new IllegalArgumentException("selected channel and positive runtime epoch required");
        this.channel = channel;
        this.epoch = epoch;
    }

    synchronized String project(ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot) {
        ExperimentSessionPanelState s = snapshot == null ? null : snapshot.state;
        boolean valid = !exhausted && snapshot != null && snapshot.observed && s != null
            && snapshot.runtimeEpoch == epoch && snapshot.readId > 0L
            && snapshot.ageMs >= 0L && snapshot.ageMs <= MAX
            && s.generation >= generation && s.generation <= MAX
            && (s.generation > generation || s.revision >= revision)
            && s.revision <= MAX && s.activeTimeMs <= MAX
            && s.phase != ExperimentSessionPanelState.Phase.UNAVAILABLE;
        if (valid && snapshot.readId > readId) {
            if (!advance()) return unavailable();
            readId = snapshot.readId; generation = s.generation; revision = s.revision;
            ageMs = snapshot.ageMs; observedState = s; visible = true;
        } else if (valid && snapshot.readId == readId && visible
                && observedState == s && snapshot.ageMs >= ageMs) {
            // Polls may advance the lower-bound age, never the observation sequence.
            ageMs = snapshot.ageMs;
        } else {
            if (visible) advance();
            visible = false;
            return unavailable();
        }
        String completion = s.completion == ExperimentSessionPanelState.Completion.NOT_REACHED
            ? "NONE" : s.completion.name();
        String result = prefix() + "\"source_state\":\"fresh\",\"source_age_ms\":" + ageMs
            + ",\"status\":{\"phase\":\"" + s.phase.name()
            + "\",\"foreground\":\"unknown\",\"recording\":" + s.recording
            + ",\"active_ms\":" + s.activeTimeMs + ",\"completion\":\"" + completion + "\"}}";
        if (result.getBytes(StandardCharsets.UTF_8).length > 1024) {
            advance(); visible = false; return unavailable();
        }
        return result;
    }

    private boolean advance() {
        // Reserve the final safe sequence for unavailable, so a consumer can
        // retire the preceding live packet rather than ignore a duplicate.
        if (sequence >= MAX - 1L) {
            sequence = MAX; exhausted = true; visible = false; return false;
        }
        sequence++; return true;
    }
    private String prefix() {
        return "{\"schema\":\"experiment.status.observation.v1\",\"channel\":\"" + channel
            + "\",\"epoch\":\"" + epoch + "\",\"sequence\":" + sequence
            + ",\"generation\":" + generation + ",\"revision\":" + revision + ",";
    }
    private String unavailable() {
        return prefix() + "\"source_state\":\"unknown\",\"source_age_ms\":null,\"status\":null}";
    }
}
