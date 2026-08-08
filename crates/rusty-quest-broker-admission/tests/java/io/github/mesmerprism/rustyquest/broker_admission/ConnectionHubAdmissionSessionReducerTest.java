package io.github.mesmerprism.rustyquest.broker_admission;

import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.CommandRetryPolicy;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Effect;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.EffectType;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Phase;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Result;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.State;
import java.util.List;

public final class ConnectionHubAdmissionSessionReducerTest {
    public static void main(String[] args) {
        testHappyPathAndGenerationFence();
        testReadOnlyRetryAndStaleDeadline();
        testAmbiguousTokenOutcomeDoesNotRetry();
        testEquivalentRegistrationRetryKeepsIdentity();
        testLifecycleFaultMatrix();
        testDelayedDuplicateAndReorderedEvents();
        testLifecycleFaultsCleanupExactlyOnce();
        testCommandRetryClasses();
        System.out.println("ConnectionHubAdmissionSessionReducerTest passed");
    }

    private static void testHappyPathAndGenerationFence() {
        State state = ConnectionHubAdmissionSessionReducer.initial(7L);
        Result result = reduce(state, Event.start(10L), EffectType.BIND_SERVICE);
        state = result.getState();
        long binding = state.getBindingGeneration();
        state = reduce(state, Event.bindReturned(binding, true, 11L), EffectType.MARKER).getState();
        state = reduce(state, Event.connected(binding, 12L), EffectType.LINK_DEATH).getState();
        result = reduce(state, Event.deathLinked(binding, 13L), EffectType.SEND_RUNTIME_EVIDENCE);
        state = result.getState();
        String evidence = only(result).getCorrelationId();

        State unchanged = ConnectionHubAdmissionSessionReducer.reduce(
                state, Event.reply(binding - 1L, evidence, true, "", "epoch.stale", 14L))
                .getState();
        require(unchanged == state, "stale binding callback mutated state");

        result = reduce(state, Event.reply(binding, evidence, true, "", "epoch.current", 15L),
                EffectType.SEND_ISSUE_TOKEN);
        state = result.getState();
        require("epoch.current".equals(state.getBrokerEpochId()), "broker epoch was not retained");
        result = reduce(state, applied(state, result, 16L), EffectType.SEND_AUTHORIZE_USE);
        state = result.getState();
        result = reduce(state, applied(state, result, 17L), EffectType.SEND_REGISTER_SURFACE);
        state = result.getState();
        result = reduce(state, applied(state, result, 18L), EffectType.MARKER);
        state = result.getState();
        require(state.getPhase() == Phase.REGISTERED && state.isRegistered(),
                "happy path did not register");
        require("registration_applied".equals(state.getLastPositiveStage()),
                "last positive stage missing");
    }

    private static void testReadOnlyRetryAndStaleDeadline() {
        State state = toEvidence(11L);
        String first = state.getPending().getCorrelationId();
        long logical = state.getPending().getLogicalOperationId();
        long binding = state.getBindingGeneration();
        State early = ConnectionHubAdmissionSessionReducer.reduce(
                state, Event.deadline(binding, first, state.getPending().getDeadlineAtMs() - 1L))
                .getState();
        require(early == state, "early deadline mutated state");
        Result retried = reduce(
                state,
                Event.deadline(binding, first, state.getPending().getDeadlineAtMs()),
                EffectType.SEND_RUNTIME_EVIDENCE);
        require(retried.getState().getPending().getAttempt() == 2,
                "read-only evidence did not retry exactly once");
        require(retried.getState().getPending().getLogicalOperationId() == logical,
                "read-only retry changed logical operation");
        require(!retried.getState().getPending().getCorrelationId().equals(first),
                "retry reused attempt correlation");
        State stale = ConnectionHubAdmissionSessionReducer.reduce(
                retried.getState(),
                Event.deadline(binding, first, retried.getState().getPending().getDeadlineAtMs()))
                .getState();
        require(stale == retried.getState(), "old attempt deadline mutated retry");
    }

    private static void testAmbiguousTokenOutcomeDoesNotRetry() {
        State state = toEvidence(13L);
        Result token = ConnectionHubAdmissionSessionReducer.reduce(
                state,
                Event.reply(state.getBindingGeneration(), state.getPending().getCorrelationId(),
                        true, "", "epoch.13", 100L));
        state = token.getState();
        String correlation = state.getPending().getCorrelationId();
        Result timeout = ConnectionHubAdmissionSessionReducer.reduce(
                state,
                Event.deadline(state.getBindingGeneration(), correlation,
                        state.getPending().getDeadlineAtMs()));
        require(timeout.getState().getPhase() == Phase.OUTCOME_UNKNOWN,
                "ambiguous token issue was not outcome_unknown");
        require(timeout.getState().getPending() == null,
                "ambiguous token issue remained retryable");
        require(count(timeout.getEffects(), EffectType.UNBIND_SERVICE) == 1,
                "ambiguous outcome did not clean up once");

        State awaitingUse = toEvidence(14L);
        awaitingUse = replyApplied(awaitingUse, 110L).getState();
        awaitingUse = replyApplied(awaitingUse, 111L).getState();
        Result useTimeout = ConnectionHubAdmissionSessionReducer.reduce(
                awaitingUse,
                Event.deadline(
                        awaitingUse.getBindingGeneration(),
                        awaitingUse.getPending().getCorrelationId(),
                        awaitingUse.getPending().getDeadlineAtMs()));
        require(useTimeout.getState().getPhase() == Phase.OUTCOME_UNKNOWN,
                "ambiguous token use was not outcome_unknown");
        require(useTimeout.getState().getPending() == null,
                "ambiguous token use remained retryable");
    }

    private static void testEquivalentRegistrationRetryKeepsIdentity() {
        State state = toEvidence(17L);
        Result next = replyApplied(state, 200L);
        state = next.getState();
        next = replyApplied(state, 201L);
        state = next.getState();
        next = replyApplied(state, 202L);
        state = next.getState();
        String registration = state.getPending().getRegistrationId();
        long logical = state.getPending().getLogicalOperationId();
        String first = state.getPending().getCorrelationId();
        Result retry = reduce(
                state,
                Event.deadline(state.getBindingGeneration(), first,
                        state.getPending().getDeadlineAtMs()),
                EffectType.SEND_REGISTER_SURFACE);
        require(registration.equals(retry.getState().getPending().getRegistrationId()),
                "equivalent registration retry changed registration identity");
        require(logical == retry.getState().getPending().getLogicalOperationId(),
                "equivalent registration retry changed logical operation");
        Result unknown = ConnectionHubAdmissionSessionReducer.reduce(
                retry.getState(),
                Event.deadline(retry.getState().getBindingGeneration(),
                        retry.getState().getPending().getCorrelationId(),
                        retry.getState().getPending().getDeadlineAtMs()));
        require(unknown.getState().getPhase() == Phase.OUTCOME_UNKNOWN,
                "second registration timeout did not become outcome_unknown");
    }

    private static void testLifecycleFaultMatrix() {
        State binding = ConnectionHubAdmissionSessionReducer.reduce(
                ConnectionHubAdmissionSessionReducer.initial(19L), Event.start(1L)).getState();
        assertTerminal(
                ConnectionHubAdmissionSessionReducer.reduce(
                        binding, Event.bindReturned(binding.getBindingGeneration(), false, 2L)),
                "bind_rejected",
                0,
                0,
                0);

        State awaiting = ConnectionHubAdmissionSessionReducer.reduce(
                binding, Event.bindReturned(binding.getBindingGeneration(), true, 2L)).getState();
        assertTerminal(
                ConnectionHubAdmissionSessionReducer.reduce(
                        awaiting, Event.nullBinding(awaiting.getBindingGeneration(), 3L)),
                "null_binding",
                0,
                0,
                1);
        assertTerminal(
                ConnectionHubAdmissionSessionReducer.reduce(
                        awaiting, Event.bindingDied(awaiting.getBindingGeneration(), 3L)),
                "binding_died",
                0,
                0,
                1);
        assertTerminal(
                ConnectionHubAdmissionSessionReducer.reduce(
                        awaiting, Event.disconnected(awaiting.getBindingGeneration(), 3L)),
                "service_disconnected",
                0,
                0,
                1);

        State evidence = toEvidence(20L);
        assertTerminal(
                ConnectionHubAdmissionSessionReducer.reduce(
                        evidence, Event.binderDied(evidence.getBindingGeneration(), 4L)),
                "binder_died",
                0,
                1,
                1);

        State registered = toRegistered(21L);
        Result eligibility = ConnectionHubAdmissionSessionReducer.reduce(
                registered,
                Event.eligibilityLost(registered.getBindingGeneration(), 5L));
        assertTerminal(eligibility, "eligibility_lost", 1, 1, 1);
        require("registration_applied".equals(eligibility.getState().getLastPositiveStage()),
                "terminal generation lost its last-positive stage");
    }

    private static void testDelayedDuplicateAndReorderedEvents() {
        State evidence = toEvidence(22L);
        String evidenceCorrelation = evidence.getPending().getCorrelationId();
        long binding = evidence.getBindingGeneration();

        State wrongCorrelation = ConnectionHubAdmissionSessionReducer.reduce(
                evidence,
                Event.reply(binding, evidenceCorrelation + ".delayed", true, "", "epoch.bad", 6L))
                .getState();
        require(wrongCorrelation == evidence, "delayed unknown reply mutated state");

        Result token = ConnectionHubAdmissionSessionReducer.reduce(
                evidence,
                Event.reply(binding, evidenceCorrelation, true, "", "epoch.good", 7L));
        State awaitingToken = token.getState();
        State duplicate = ConnectionHubAdmissionSessionReducer.reduce(
                awaitingToken,
                Event.reply(binding, evidenceCorrelation, true, "", "epoch.good", 8L))
                .getState();
        require(duplicate == awaitingToken, "duplicate prior-operation reply mutated state");
        State reorderedDeadline = ConnectionHubAdmissionSessionReducer.reduce(
                awaitingToken,
                Event.deadline(binding, evidenceCorrelation,
                        awaitingToken.getPending().getDeadlineAtMs()))
                .getState();
        require(reorderedDeadline == awaitingToken,
                "reordered prior-operation deadline mutated state");

        State timeoutEvidence = toEvidence(24L);
        Result firstTimeout = ConnectionHubAdmissionSessionReducer.reduce(
                timeoutEvidence,
                Event.deadline(
                        timeoutEvidence.getBindingGeneration(),
                        timeoutEvidence.getPending().getCorrelationId(),
                        timeoutEvidence.getPending().getDeadlineAtMs()));
        State retried = firstTimeout.getState();
        Result secondTimeout = ConnectionHubAdmissionSessionReducer.reduce(
                retried,
                Event.deadline(
                        retried.getBindingGeneration(),
                        retried.getPending().getCorrelationId(),
                        retried.getPending().getDeadlineAtMs()));
        assertTerminal(secondTimeout, "runtime_evidence_timeout", 0, 1, 1);
    }

    private static void testLifecycleFaultsCleanupExactlyOnce() {
        State registered = toRegistered(23L);
        Result close = ConnectionHubAdmissionSessionReducer.reduce(
                registered, Event.close(registered.getBindingGeneration(), 500L));
        require(count(close.getEffects(), EffectType.SEND_UNREGISTER_SURFACE) == 1,
                "close did not unregister once");
        require(count(close.getEffects(), EffectType.UNLINK_DEATH) == 1,
                "close did not unlink death once");
        require(count(close.getEffects(), EffectType.UNBIND_SERVICE) == 1,
                "close did not unbind once");
        Result duplicate = ConnectionHubAdmissionSessionReducer.reduce(
                close.getState(), Event.close(close.getState().getBindingGeneration(), 501L));
        require(count(duplicate.getEffects(), EffectType.SEND_UNREGISTER_SURFACE) == 0
                        && count(duplicate.getEffects(), EffectType.UNBIND_SERVICE) == 0,
                "duplicate close repeated cleanup");

        State evidence = toEvidence(29L);
        Result died = ConnectionHubAdmissionSessionReducer.reduce(
                evidence, Event.binderDied(evidence.getBindingGeneration(), 600L));
        require(count(died.getEffects(), EffectType.SEND_UNREGISTER_SURFACE) == 0,
                "dead Binder attempted remote unregister");
        require(count(died.getEffects(), EffectType.UNBIND_SERVICE) == 1,
                "dead Binder did not release binding");
    }

    private static void testCommandRetryClasses() {
        require(ConnectionHubAdmissionSessionReducer.commandRetryPolicy("command.hub.get_state")
                        == CommandRetryPolicy.READ_ONLY_BOUNDED,
                "read-only command class drifted");
        require(ConnectionHubAdmissionSessionReducer.commandRetryPolicy("command.player.pause")
                        == CommandRetryPolicy.DESIRED_STATE_REVISION,
                "pause lost desired-state retry class");
        require(ConnectionHubAdmissionSessionReducer.commandRetryPolicy("command.player.next")
                        == CommandRetryPolicy.OUTCOME_UNKNOWN_ON_AMBIGUITY,
                "next became blindly retryable");
    }

    private static State toEvidence(long processGeneration) {
        State state = ConnectionHubAdmissionSessionReducer.initial(processGeneration);
        state = ConnectionHubAdmissionSessionReducer.reduce(state, Event.start(0L)).getState();
        long binding = state.getBindingGeneration();
        state = ConnectionHubAdmissionSessionReducer.reduce(
                state, Event.bindReturned(binding, true, 1L)).getState();
        state = ConnectionHubAdmissionSessionReducer.reduce(
                state, Event.connected(binding, 2L)).getState();
        return ConnectionHubAdmissionSessionReducer.reduce(
                state, Event.deathLinked(binding, 3L)).getState();
    }

    private static State toRegistered(long processGeneration) {
        State state = toEvidence(processGeneration);
        for (int i = 0; i < 4; i++) {
            state = replyApplied(state, 10L + i).getState();
        }
        return state;
    }

    private static Result replyApplied(State state, long nowMs) {
        return ConnectionHubAdmissionSessionReducer.reduce(
                state,
                Event.reply(
                        state.getBindingGeneration(),
                        state.getPending().getCorrelationId(),
                        true,
                        "",
                        "epoch.test",
                        nowMs));
    }

    private static Event applied(State state, Result effectSource, long nowMs) {
        return Event.reply(
                state.getBindingGeneration(),
                only(effectSource).getCorrelationId(),
                true,
                "",
                "epoch.current",
                nowMs);
    }

    private static Result reduce(State state, Event event, EffectType expected) {
        Result result = ConnectionHubAdmissionSessionReducer.reduce(state, event);
        require(only(result).getType() == expected,
                "expected " + expected + " but got " + only(result).getType());
        return result;
    }

    private static Effect only(Result result) {
        require(result.getEffects().size() == 1, "expected exactly one effect");
        return result.getEffects().get(0);
    }

    private static int count(List<Effect> effects, EffectType type) {
        int count = 0;
        for (Effect effect : effects) {
            if (effect.getType() == type) count++;
        }
        return count;
    }

    private static void assertTerminal(
            Result result,
            String reason,
            int unregisterCount,
            int unlinkCount,
            int unbindCount) {
        require(result.getState().getPhase() == Phase.CLOSED,
                reason + " did not close the generation");
        require(reason.equals(result.getState().getTerminalReason()),
                reason + " terminal reason drifted");
        require(count(result.getEffects(), EffectType.SEND_UNREGISTER_SURFACE) == unregisterCount,
                reason + " unregister count drifted");
        require(count(result.getEffects(), EffectType.UNLINK_DEATH) == unlinkCount,
                reason + " unlink count drifted");
        require(count(result.getEffects(), EffectType.UNBIND_SERVICE) == unbindCount,
                reason + " unbind count drifted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
