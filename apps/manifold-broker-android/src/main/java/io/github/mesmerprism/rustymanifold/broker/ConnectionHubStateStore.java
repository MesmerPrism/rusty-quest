package io.github.mesmerprism.rustymanifold.broker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable desired lifecycle and opaque authority/session transport projections. */
public interface ConnectionHubStateStore {
    final class State {
        public final boolean desiredRunning;
        public final String authorityEnvelope;
        public final Map<String, SessionProjection> sessionProjections;
        public final long generation;
        public final String pendingOperation;

        public State(
                boolean desiredRunning,
                String authorityEnvelope,
                Map<String, SessionProjection> sessionProjections) {
            this(desiredRunning, authorityEnvelope, sessionProjections, 0, "");
        }

        public State(
                boolean desiredRunning,
                String authorityEnvelope,
                Map<String, SessionProjection> sessionProjections,
                long generation,
                String pendingOperation) {
            this.desiredRunning = desiredRunning;
            this.authorityEnvelope = authorityEnvelope == null ? "" : authorityEnvelope;
            this.sessionProjections = Collections.unmodifiableMap(
                    new LinkedHashMap<>(sessionProjections));
            this.generation = Math.max(0, generation);
            this.pendingOperation = pendingOperation == null ? "" : pendingOperation;
        }

        public static State stopped() {
            return new State(false, "", Collections.<String, SessionProjection>emptyMap(), 0, "");
        }
    }

    final class SessionProjection {
        public final String logicalSessionId;
        public final long transportEpoch;
        public final long expiresAtMs;

        public SessionProjection(String logicalSessionId, long transportEpoch, long expiresAtMs) {
            this.logicalSessionId = logicalSessionId;
            this.transportEpoch = transportEpoch;
            this.expiresAtMs = expiresAtMs;
        }
    }

    State load();
    void save(State state);
    void clear();
}
