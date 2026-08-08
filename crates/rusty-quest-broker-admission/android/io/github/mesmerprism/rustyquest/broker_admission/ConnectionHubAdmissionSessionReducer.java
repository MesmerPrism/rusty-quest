package io.github.mesmerprism.rustyquest.broker_admission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure state reducer for one Android Connection Hub admission lifecycle.
 *
 * <p>The Android adapter serializes events onto one looper and executes the returned effects. This
 * class owns no Binder, clock, identity, grant, token, surface, or application-effect authority.
 * Every asynchronous event is fenced by the binding generation and every reply/deadline is fenced
 * by a correlation id.
 */
public final class ConnectionHubAdmissionSessionReducer {
    public static final long DEFAULT_OPERATION_TIMEOUT_MS = 3_000L;
    public static final int MAX_READ_ONLY_ATTEMPTS = 2;
    public static final int MAX_EQUIVALENT_REGISTRATION_ATTEMPTS = 2;

    public enum Phase {
        IDLE,
        BINDING,
        AWAITING_CONNECTION,
        CONNECTED,
        AWAITING_EVIDENCE,
        AWAITING_TOKEN,
        AWAITING_USE,
        AWAITING_REGISTRATION,
        REGISTERED,
        OUTCOME_UNKNOWN,
        CLOSED
    }

    public enum EventType {
        START,
        BIND_RETURNED,
        CONNECTED,
        DEATH_LINKED,
        REPLY,
        DEADLINE,
        NULL_BINDING,
        BINDING_DIED,
        BINDER_DIED,
        DISCONNECTED,
        ELIGIBILITY_LOST,
        CLOSE
    }

    public enum EffectType {
        BIND_SERVICE,
        LINK_DEATH,
        SEND_RUNTIME_EVIDENCE,
        SEND_ISSUE_TOKEN,
        SEND_AUTHORIZE_USE,
        SEND_REGISTER_SURFACE,
        SEND_UNREGISTER_SURFACE,
        UNLINK_DEATH,
        UNBIND_SERVICE,
        MARKER
    }

    public enum OperationKind {
        NONE,
        RUNTIME_EVIDENCE,
        ISSUE_TOKEN,
        AUTHORIZE_USE,
        REGISTER_SURFACE
    }

    public enum CommandRetryPolicy {
        READ_ONLY_BOUNDED,
        DESIRED_STATE_REVISION,
        OUTCOME_UNKNOWN_ON_AMBIGUITY
    }

    public static final class PendingOperation {
        private final OperationKind kind;
        private final long logicalOperationId;
        private final int attempt;
        private final String correlationId;
        private final String registrationId;
        private final long deadlineAtMs;

        private PendingOperation(
                OperationKind kind,
                long logicalOperationId,
                int attempt,
                String correlationId,
                String registrationId,
                long deadlineAtMs) {
            this.kind = kind;
            this.logicalOperationId = logicalOperationId;
            this.attempt = attempt;
            this.correlationId = correlationId;
            this.registrationId = registrationId;
            this.deadlineAtMs = deadlineAtMs;
        }

        public OperationKind getKind() { return kind; }
        public long getLogicalOperationId() { return logicalOperationId; }
        public int getAttempt() { return attempt; }
        public String getCorrelationId() { return correlationId; }
        public String getRegistrationId() { return registrationId; }
        public long getDeadlineAtMs() { return deadlineAtMs; }
    }

    public static final class State {
        private final long processGeneration;
        private final long bindingGeneration;
        private final long sessionGeneration;
        private final long nextLogicalOperationId;
        private final String brokerEpochId;
        private final Phase phase;
        private final PendingOperation pending;
        private final boolean bound;
        private final boolean deathLinked;
        private final boolean registered;
        private final boolean cleanupIssued;
        private final String terminalReason;
        private final String lastPositiveStage;

        private State(
                long processGeneration,
                long bindingGeneration,
                long sessionGeneration,
                long nextLogicalOperationId,
                String brokerEpochId,
                Phase phase,
                PendingOperation pending,
                boolean bound,
                boolean deathLinked,
                boolean registered,
                boolean cleanupIssued,
                String terminalReason,
                String lastPositiveStage) {
            this.processGeneration = processGeneration;
            this.bindingGeneration = bindingGeneration;
            this.sessionGeneration = sessionGeneration;
            this.nextLogicalOperationId = nextLogicalOperationId;
            this.brokerEpochId = brokerEpochId;
            this.phase = phase;
            this.pending = pending;
            this.bound = bound;
            this.deathLinked = deathLinked;
            this.registered = registered;
            this.cleanupIssued = cleanupIssued;
            this.terminalReason = terminalReason;
            this.lastPositiveStage = lastPositiveStage;
        }

        public long getProcessGeneration() { return processGeneration; }
        public long getBindingGeneration() { return bindingGeneration; }
        public long getSessionGeneration() { return sessionGeneration; }
        public String getBrokerEpochId() { return brokerEpochId; }
        public Phase getPhase() { return phase; }
        public PendingOperation getPending() { return pending; }
        public boolean isBound() { return bound; }
        public boolean isDeathLinked() { return deathLinked; }
        public boolean isRegistered() { return registered; }
        public boolean isCleanupIssued() { return cleanupIssued; }
        public String getTerminalReason() { return terminalReason; }
        public String getLastPositiveStage() { return lastPositiveStage; }
    }

    public static final class Event {
        private final EventType type;
        private final long bindingGeneration;
        private final long nowMs;
        private final boolean positive;
        private final String correlationId;
        private final String reason;
        private final String brokerEpochId;

        private Event(
                EventType type,
                long bindingGeneration,
                long nowMs,
                boolean positive,
                String correlationId,
                String reason,
                String brokerEpochId) {
            this.type = type;
            this.bindingGeneration = bindingGeneration;
            this.nowMs = nowMs;
            this.positive = positive;
            this.correlationId = safe(correlationId);
            this.reason = safe(reason);
            this.brokerEpochId = safe(brokerEpochId);
        }

        public static Event start(long nowMs) {
            return new Event(EventType.START, 0L, nowMs, true, "", "", "");
        }

        public static Event bindReturned(long bindingGeneration, boolean accepted, long nowMs) {
            return new Event(EventType.BIND_RETURNED, bindingGeneration, nowMs, accepted, "", "", "");
        }

        public static Event connected(long bindingGeneration, long nowMs) {
            return simple(EventType.CONNECTED, bindingGeneration, nowMs);
        }

        public static Event deathLinked(long bindingGeneration, long nowMs) {
            return simple(EventType.DEATH_LINKED, bindingGeneration, nowMs);
        }

        public static Event reply(
                long bindingGeneration,
                String correlationId,
                boolean applied,
                String reason,
                String brokerEpochId,
                long nowMs) {
            return new Event(
                    EventType.REPLY,
                    bindingGeneration,
                    nowMs,
                    applied,
                    correlationId,
                    reason,
                    brokerEpochId);
        }

        public static Event deadline(long bindingGeneration, String correlationId, long nowMs) {
            return new Event(
                    EventType.DEADLINE,
                    bindingGeneration,
                    nowMs,
                    false,
                    correlationId,
                    "deadline",
                    "");
        }

        public static Event nullBinding(long bindingGeneration, long nowMs) {
            return simple(EventType.NULL_BINDING, bindingGeneration, nowMs);
        }

        public static Event bindingDied(long bindingGeneration, long nowMs) {
            return simple(EventType.BINDING_DIED, bindingGeneration, nowMs);
        }

        public static Event binderDied(long bindingGeneration, long nowMs) {
            return simple(EventType.BINDER_DIED, bindingGeneration, nowMs);
        }

        public static Event disconnected(long bindingGeneration, long nowMs) {
            return simple(EventType.DISCONNECTED, bindingGeneration, nowMs);
        }

        public static Event eligibilityLost(long bindingGeneration, long nowMs) {
            return simple(EventType.ELIGIBILITY_LOST, bindingGeneration, nowMs);
        }

        public static Event close(long bindingGeneration, long nowMs) {
            return simple(EventType.CLOSE, bindingGeneration, nowMs);
        }

        private static Event simple(EventType type, long bindingGeneration, long nowMs) {
            return new Event(type, bindingGeneration, nowMs, true, "", "", "");
        }
    }

    public static final class Effect {
        private final EffectType type;
        private final OperationKind operation;
        private final long bindingGeneration;
        private final long sessionGeneration;
        private final long logicalOperationId;
        private final int attempt;
        private final String correlationId;
        private final String registrationId;
        private final long deadlineAtMs;
        private final String marker;

        private Effect(
                EffectType type,
                OperationKind operation,
                long bindingGeneration,
                long sessionGeneration,
                long logicalOperationId,
                int attempt,
                String correlationId,
                String registrationId,
                long deadlineAtMs,
                String marker) {
            this.type = type;
            this.operation = operation;
            this.bindingGeneration = bindingGeneration;
            this.sessionGeneration = sessionGeneration;
            this.logicalOperationId = logicalOperationId;
            this.attempt = attempt;
            this.correlationId = correlationId;
            this.registrationId = registrationId;
            this.deadlineAtMs = deadlineAtMs;
            this.marker = marker;
        }

        public EffectType getType() { return type; }
        public OperationKind getOperation() { return operation; }
        public long getBindingGeneration() { return bindingGeneration; }
        public long getSessionGeneration() { return sessionGeneration; }
        public long getLogicalOperationId() { return logicalOperationId; }
        public int getAttempt() { return attempt; }
        public String getCorrelationId() { return correlationId; }
        public String getRegistrationId() { return registrationId; }
        public long getDeadlineAtMs() { return deadlineAtMs; }
        public String getMarker() { return marker; }
    }

    public static final class Result {
        private final State state;
        private final List<Effect> effects;

        private Result(State state, List<Effect> effects) {
            this.state = state;
            this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
        }

        public State getState() { return state; }
        public List<Effect> getEffects() { return effects; }
    }

    private ConnectionHubAdmissionSessionReducer() {}

    public static State initial(long processGeneration) {
        if (processGeneration <= 0L) {
            throw new IllegalArgumentException("processGeneration must be positive");
        }
        return new State(
                processGeneration,
                0L,
                0L,
                1L,
                "",
                Phase.IDLE,
                null,
                false,
                false,
                false,
                false,
                "",
                "created");
    }

    public static Result reduce(State state, Event event) {
        if (state == null || event == null) {
            throw new IllegalArgumentException("state and event are required");
        }
        if (event.type == EventType.START) {
            if (state.phase != Phase.IDLE && state.phase != Phase.CLOSED) {
                return unchanged(state, "start_ignored_active_generation");
            }
            long binding = state.bindingGeneration + 1L;
            long session = state.sessionGeneration + 1L;
            State next = copy(
                    state,
                    binding,
                    session,
                    state.nextLogicalOperationId,
                    "",
                    Phase.BINDING,
                    null,
                    false,
                    false,
                    false,
                    false,
                    "",
                    "bind_requested");
            return with(next, effect(EffectType.BIND_SERVICE, next, null, "bind_requested"));
        }

        if (event.bindingGeneration != state.bindingGeneration) {
            return unchanged(state, "stale_binding_event_ignored");
        }

        switch (event.type) {
            case BIND_RETURNED:
                if (state.phase != Phase.BINDING) {
                    return unchanged(state, "late_bind_result_ignored");
                }
                if (!event.positive) {
                    return terminal(state, "bind_rejected", false);
                }
                return with(
                        copy(state, state.bindingGeneration, state.sessionGeneration,
                                state.nextLogicalOperationId, state.brokerEpochId,
                                Phase.AWAITING_CONNECTION, null, true, false, false, false, "",
                                "bind_returned"),
                        marker(state, "bind_returned"));
            case CONNECTED:
                if (state.phase != Phase.AWAITING_CONNECTION && state.phase != Phase.BINDING) {
                    return unchanged(state, "late_connected_ignored");
                }
                State connected = copy(state, state.bindingGeneration, state.sessionGeneration,
                        state.nextLogicalOperationId, state.brokerEpochId, Phase.CONNECTED, null,
                        true, false, false, false, "", "connected");
                return with(connected,
                        effect(EffectType.LINK_DEATH, connected, null, "death_link_requested"));
            case DEATH_LINKED:
                if (state.phase != Phase.CONNECTED) {
                    return unchanged(state, "late_death_link_ignored");
                }
                State linked = copy(state, state.bindingGeneration, state.sessionGeneration,
                        state.nextLogicalOperationId, state.brokerEpochId, Phase.CONNECTED, null,
                        true, true, false, false, "", "death_linked");
                return begin(linked, OperationKind.RUNTIME_EVIDENCE, 1, 0L, "", event.nowMs);
            case REPLY:
                return handleReply(state, event);
            case DEADLINE:
                return handleDeadline(state, event);
            case NULL_BINDING:
                return terminal(state, "null_binding", false);
            case BINDING_DIED:
                return terminal(state, "binding_died", false);
            case BINDER_DIED:
                return terminal(state, "binder_died", false);
            case DISCONNECTED:
                return terminal(state, "service_disconnected", false);
            case ELIGIBILITY_LOST:
                return terminal(state, "eligibility_lost", true);
            case CLOSE:
                return terminal(state, "closed", true);
            default:
                return unchanged(state, "event_ignored");
        }
    }

    public static CommandRetryPolicy commandRetryPolicy(String command) {
        String value = safe(command).toLowerCase(Locale.ROOT);
        if (value.endsWith(".get_state") || value.endsWith(".describe")) {
            return CommandRetryPolicy.READ_ONLY_BOUNDED;
        }
        if (value.endsWith(".pause") || value.endsWith(".resume") || value.endsWith(".play")) {
            return CommandRetryPolicy.DESIRED_STATE_REVISION;
        }
        return CommandRetryPolicy.OUTCOME_UNKNOWN_ON_AMBIGUITY;
    }

    private static Result handleReply(State state, Event event) {
        PendingOperation pending = state.pending;
        if (pending == null || !pending.correlationId.equals(event.correlationId)) {
            return unchanged(state, "stale_reply_ignored");
        }
        if (!event.positive) {
            return terminal(state,
                    event.reason.isEmpty() ? "operation_rejected" : event.reason,
                    true);
        }
        State cleared = copy(state, state.bindingGeneration, state.sessionGeneration,
                state.nextLogicalOperationId,
                event.brokerEpochId.isEmpty() ? state.brokerEpochId : event.brokerEpochId,
                state.phase, null, state.bound, state.deathLinked, state.registered,
                state.cleanupIssued, "", positiveMarker(pending.kind));
        switch (pending.kind) {
            case RUNTIME_EVIDENCE:
                return begin(cleared, OperationKind.ISSUE_TOKEN, 1, 0L, "", event.nowMs);
            case ISSUE_TOKEN:
                return begin(cleared, OperationKind.AUTHORIZE_USE, 1, 0L, "", event.nowMs);
            case AUTHORIZE_USE:
                return begin(cleared, OperationKind.REGISTER_SURFACE, 1, 0L,
                        registrationId(cleared), event.nowMs);
            case REGISTER_SURFACE:
                State registered = copy(cleared, cleared.bindingGeneration,
                        cleared.sessionGeneration, cleared.nextLogicalOperationId,
                        cleared.brokerEpochId, Phase.REGISTERED, null, true, true, true,
                        false, "", "registration_applied");
                return with(registered, marker(registered, "registration_applied"));
            default:
                return unchanged(cleared, "reply_without_operation_ignored");
        }
    }

    private static Result handleDeadline(State state, Event event) {
        PendingOperation pending = state.pending;
        if (pending == null || !pending.correlationId.equals(event.correlationId)) {
            return unchanged(state, "stale_deadline_ignored");
        }
        if (event.nowMs < pending.deadlineAtMs) {
            return unchanged(state, "early_deadline_ignored");
        }
        if (pending.kind == OperationKind.RUNTIME_EVIDENCE
                && pending.attempt < MAX_READ_ONLY_ATTEMPTS) {
            return begin(state, pending.kind, pending.attempt + 1,
                    pending.logicalOperationId, "", event.nowMs);
        }
        if (pending.kind == OperationKind.REGISTER_SURFACE
                && pending.attempt < MAX_EQUIVALENT_REGISTRATION_ATTEMPTS) {
            return begin(state, pending.kind, pending.attempt + 1,
                    pending.logicalOperationId, pending.registrationId, event.nowMs);
        }
        if (pending.kind == OperationKind.ISSUE_TOKEN
                || pending.kind == OperationKind.AUTHORIZE_USE
                || pending.kind == OperationKind.REGISTER_SURFACE) {
            return outcomeUnknown(state, pending.kind.name().toLowerCase(Locale.ROOT) + "_timeout");
        }
        return terminal(state, "runtime_evidence_timeout", true);
    }

    private static Result begin(
            State state,
            OperationKind kind,
            int attempt,
            long existingLogicalId,
            String existingRegistrationId,
            long nowMs) {
        long logicalId = existingLogicalId > 0L
                ? existingLogicalId
                : state.nextLogicalOperationId;
        long nextLogical = existingLogicalId > 0L
                ? state.nextLogicalOperationId
                : state.nextLogicalOperationId + 1L;
        String registration = kind == OperationKind.REGISTER_SURFACE
                ? (existingRegistrationId.isEmpty() ? registrationId(state) : existingRegistrationId)
                : "";
        String correlation = correlationId(state, kind, logicalId, attempt);
        PendingOperation pending = new PendingOperation(
                kind, logicalId, attempt, correlation, registration,
                nowMs + DEFAULT_OPERATION_TIMEOUT_MS);
        Phase phase = phaseFor(kind);
        State next = copy(state, state.bindingGeneration, state.sessionGeneration, nextLogical,
                state.brokerEpochId, phase, pending, state.bound, state.deathLinked,
                state.registered, false, "", "send_" + kind.name().toLowerCase(Locale.ROOT));
        return with(next, operationEffect(next, pending));
    }

    private static Result outcomeUnknown(State state, String reason) {
        State unknown = copy(state, state.bindingGeneration, state.sessionGeneration,
                state.nextLogicalOperationId, state.brokerEpochId, Phase.OUTCOME_UNKNOWN, null,
                state.bound, state.deathLinked, state.registered, false, reason,
                state.lastPositiveStage);
        return cleanup(unknown, reason, true);
    }

    private static Result terminal(State state, String reason, boolean transportAvailable) {
        if (state.phase == Phase.CLOSED && state.cleanupIssued) {
            return unchanged(state, "cleanup_already_issued");
        }
        State closed = copy(state, state.bindingGeneration, state.sessionGeneration,
                state.nextLogicalOperationId, state.brokerEpochId, Phase.CLOSED, null,
                state.bound, state.deathLinked, state.registered, state.cleanupIssued,
                reason, state.lastPositiveStage);
        return cleanup(closed, reason, transportAvailable);
    }

    private static Result cleanup(State state, String reason, boolean transportAvailable) {
        if (state.cleanupIssued) {
            return unchanged(state, "cleanup_already_issued");
        }
        List<Effect> effects = new ArrayList<>();
        if (state.registered && transportAvailable) {
            effects.add(effect(EffectType.SEND_UNREGISTER_SURFACE, state, null,
                    "cleanup_unregister"));
        }
        if (state.deathLinked) {
            effects.add(effect(EffectType.UNLINK_DEATH, state, null, "cleanup_unlink_death"));
        }
        if (state.bound) {
            effects.add(effect(EffectType.UNBIND_SERVICE, state, null, "cleanup_unbind"));
        }
        State cleaned = copy(state, state.bindingGeneration, state.sessionGeneration,
                state.nextLogicalOperationId, state.brokerEpochId, state.phase, null,
                false, false, false, true, reason, state.lastPositiveStage);
        effects.add(marker(cleaned, "terminal_" + reason));
        return new Result(cleaned, effects);
    }

    private static Result unchanged(State state, String marker) {
        return with(state, marker(state, marker));
    }

    private static Result with(State state, Effect effect) {
        return new Result(state, Collections.singletonList(effect));
    }

    private static Effect operationEffect(State state, PendingOperation pending) {
        EffectType type;
        switch (pending.kind) {
            case RUNTIME_EVIDENCE: type = EffectType.SEND_RUNTIME_EVIDENCE; break;
            case ISSUE_TOKEN: type = EffectType.SEND_ISSUE_TOKEN; break;
            case AUTHORIZE_USE: type = EffectType.SEND_AUTHORIZE_USE; break;
            case REGISTER_SURFACE: type = EffectType.SEND_REGISTER_SURFACE; break;
            default: throw new IllegalArgumentException("operation effect requires a pending operation");
        }
        return effect(type, state, pending, "");
    }

    private static Effect marker(State state, String marker) {
        return effect(EffectType.MARKER, state, null, marker);
    }

    private static Effect effect(
            EffectType type,
            State state,
            PendingOperation pending,
            String marker) {
        return new Effect(
                type,
                pending == null ? OperationKind.NONE : pending.kind,
                state.bindingGeneration,
                state.sessionGeneration,
                pending == null ? 0L : pending.logicalOperationId,
                pending == null ? 0 : pending.attempt,
                pending == null ? "" : pending.correlationId,
                pending == null ? "" : pending.registrationId,
                pending == null ? 0L : pending.deadlineAtMs,
                marker);
    }

    private static Phase phaseFor(OperationKind kind) {
        switch (kind) {
            case RUNTIME_EVIDENCE: return Phase.AWAITING_EVIDENCE;
            case ISSUE_TOKEN: return Phase.AWAITING_TOKEN;
            case AUTHORIZE_USE: return Phase.AWAITING_USE;
            case REGISTER_SURFACE: return Phase.AWAITING_REGISTRATION;
            default: throw new IllegalArgumentException("operation has no phase");
        }
    }

    private static String positiveMarker(OperationKind kind) {
        return kind.name().toLowerCase(Locale.ROOT) + "_applied";
    }

    private static String registrationId(State state) {
        return "registration.hub.p" + state.processGeneration
                + ".b" + state.bindingGeneration
                + ".s" + state.sessionGeneration;
    }

    private static String correlationId(
            State state,
            OperationKind kind,
            long logicalId,
            int attempt) {
        return "request.hub." + kind.name().toLowerCase(Locale.ROOT)
                + ".p" + state.processGeneration
                + ".b" + state.bindingGeneration
                + ".s" + state.sessionGeneration
                + ".o" + logicalId
                + ".a" + attempt;
    }

    private static State copy(
            State source,
            long bindingGeneration,
            long sessionGeneration,
            long nextLogicalOperationId,
            String brokerEpochId,
            Phase phase,
            PendingOperation pending,
            boolean bound,
            boolean deathLinked,
            boolean registered,
            boolean cleanupIssued,
            String terminalReason,
            String lastPositiveStage) {
        return new State(
                source.processGeneration,
                bindingGeneration,
                sessionGeneration,
                nextLogicalOperationId,
                brokerEpochId,
                phase,
                pending,
                bound,
                deathLinked,
                registered,
                cleanupIssued,
                safe(terminalReason),
                safe(lastPositiveStage));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
