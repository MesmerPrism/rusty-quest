package io.github.mesmerprism.rustymanifold.broker;

import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Effect;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.EffectType;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Result;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.State;

import java.util.List;

/** Cross-composition lifecycle matrix for the published Spatial provider reducer. */
public final class ConnectionHubSpatialCompositionTest {
    public static void main(String[] args) {
        testSpatialStartsBeforeHubThenRebinds();
        testHubStartsBeforeSpatial();
        testEquivalentRegistrationRetryAndStaleGeneration();
        testCleanupExactlyOnce();
        System.out.println("Connection Hub Spatial composition tests passed");
    }

    private static void testSpatialStartsBeforeHubThenRebinds() {
        State state = ConnectionHubAdmissionSessionReducer.initial(31L);
        Result started = reduce(state, Event.start(1L), EffectType.BIND_SERVICE);
        state = started.getState();
        long unavailableGeneration = state.getBindingGeneration();
        Result rejected = ConnectionHubAdmissionSessionReducer.reduce(
                state, Event.bindReturned(unavailableGeneration, false, 2L));
        require(rejected.getState().getPhase()
                        == ConnectionHubAdmissionSessionReducer.Phase.CLOSED,
                "unavailable Hub did not close generation");

        Result rebound = reduce(rejected.getState(), Event.start(3L), EffectType.BIND_SERVICE);
        require(rebound.getState().getBindingGeneration() == unavailableGeneration + 1L,
                "Hub arrival did not create a fresh binding generation");
        require(rebound.getState().getSessionGeneration()
                        == rejected.getState().getSessionGeneration() + 1L,
                "Hub arrival did not create a fresh session generation");
        require(rebound.getState().getLastPositiveStage().equals("bind_requested"),
                "rebind did not retain a generation marker");
    }

    private static void testHubStartsBeforeSpatial() {
        State registered = register(ConnectionHubAdmissionSessionReducer.initial(41L), 10L);
        require(registered.isRegistered(), "Hub-first composition did not register");
        require(registered.getSessionGeneration() > 0L,
                "Hub-first composition omitted session generation");
    }

    private static void testEquivalentRegistrationRetryAndStaleGeneration() {
        State state = toRegisterPending(ConnectionHubAdmissionSessionReducer.initial(51L), 20L);
        String registrationId = state.getPending().getRegistrationId();
        String correlation = state.getPending().getCorrelationId();
        Result retry = reduce(
                state,
                Event.deadline(
                        state.getBindingGeneration(),
                        correlation,
                        state.getPending().getDeadlineAtMs()),
                EffectType.SEND_REGISTER_SURFACE);
        require(registrationId.equals(retry.getState().getPending().getRegistrationId()),
                "registration retry changed registration identity");

        State current = retry.getState();
        State stale = ConnectionHubAdmissionSessionReducer.reduce(
                current,
                Event.reply(
                        current.getBindingGeneration() - 1L,
                        current.getPending().getCorrelationId(),
                        true,
                        "",
                        "epoch.stale",
                        30L)).getState();
        require(stale == current, "stale generation mutated current registration");

        State wrongCorrelation = ConnectionHubAdmissionSessionReducer.reduce(
                current,
                Event.reply(
                        current.getBindingGeneration(),
                        current.getPending().getCorrelationId() + ".wrong",
                        true,
                        "",
                        "epoch.current",
                        31L)).getState();
        require(wrongCorrelation == current, "correlation mismatch mutated registration");
    }

    private static void testCleanupExactlyOnce() {
        State registered = register(ConnectionHubAdmissionSessionReducer.initial(61L), 40L);
        Result first = ConnectionHubAdmissionSessionReducer.reduce(
                registered,
                Event.eligibilityLost(registered.getBindingGeneration(), 50L));
        require(count(first.getEffects(), EffectType.SEND_UNREGISTER_SURFACE) == 1,
                "eligibility loss did not unregister exactly once");
        require(count(first.getEffects(), EffectType.UNLINK_DEATH) == 1,
                "eligibility loss did not unlink exactly once");
        require(count(first.getEffects(), EffectType.UNBIND_SERVICE) == 1,
                "eligibility loss did not unbind exactly once");
        Result second = ConnectionHubAdmissionSessionReducer.reduce(
                first.getState(),
                Event.close(first.getState().getBindingGeneration(), 51L));
        require(count(second.getEffects(), EffectType.SEND_UNREGISTER_SURFACE) == 0
                        && count(second.getEffects(), EffectType.UNLINK_DEATH) == 0
                        && count(second.getEffects(), EffectType.UNBIND_SERVICE) == 0,
                "duplicate cleanup escaped generation fence");
    }

    private static State register(State state, long now) {
        State pending = toRegisterPending(state, now);
        return ConnectionHubAdmissionSessionReducer.reduce(
                pending,
                Event.reply(
                        pending.getBindingGeneration(),
                        pending.getPending().getCorrelationId(),
                        true,
                        "",
                        "epoch.current",
                        now + 8L)).getState();
    }

    private static State toRegisterPending(State state, long now) {
        Result next = reduce(state, Event.start(now), EffectType.BIND_SERVICE);
        state = next.getState();
        long binding = state.getBindingGeneration();
        state = reduce(state, Event.bindReturned(binding, true, now + 1L),
                EffectType.MARKER).getState();
        state = reduce(state, Event.connected(binding, now + 2L),
                EffectType.LINK_DEATH).getState();
        state = reduce(state, Event.deathLinked(binding, now + 3L),
                EffectType.SEND_RUNTIME_EVIDENCE).getState();
        for (int index = 0; index < 3; index++) {
            next = ConnectionHubAdmissionSessionReducer.reduce(
                    state,
                    Event.reply(
                            binding,
                            state.getPending().getCorrelationId(),
                            true,
                            "",
                            "epoch.current",
                            now + 4L + index));
            state = next.getState();
        }
        require(state.getPending() != null
                        && state.getPending().getKind()
                            == ConnectionHubAdmissionSessionReducer.OperationKind.REGISTER_SURFACE,
                "composition did not reach registration");
        return state;
    }

    private static Result reduce(State state, Event event, EffectType expected) {
        Result result = ConnectionHubAdmissionSessionReducer.reduce(state, event);
        require(count(result.getEffects(), expected) == 1,
                "expected effect missing: " + expected);
        return result;
    }

    private static int count(List<Effect> effects, EffectType type) {
        int count = 0;
        for (Effect effect : effects) if (effect.getType() == type) count += 1;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
