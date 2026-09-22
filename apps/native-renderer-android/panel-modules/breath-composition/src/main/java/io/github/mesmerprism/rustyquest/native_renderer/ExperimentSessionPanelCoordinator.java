package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure UI coordinator. Native receipts remain authoritative; constructing or hydrating this
 * object never starts a session or writes settings.
 */
final class ExperimentSessionPanelCoordinator {
    static final String SCHEMA = "rusty.quest.experiment_session.command.v1";
    static final String CONDITION_ONE = "condition-a";
    static final String CONDITION_TWO = "condition-b";
    static final long COMPLETION_THRESHOLD_MS = 30_000L;

    enum LaunchKind { EXPLICIT_COLD_ROOT, INTERNAL_MAIN, RECREATION_MAIN }

    static final class NativeCommand {
        final String operation;
        final String operationId;
        final long expectedGeneration;
        final String condition;
        final int breathGuidanceBiasPercent;

        NativeCommand(String operation, String operationId, long expectedGeneration, String condition) {
            this(operation, operationId, expectedGeneration, condition, 0);
        }

        NativeCommand(String operation, String operationId, long expectedGeneration,
                String condition, int breathGuidanceBiasPercent) {
            this.operation = operation;
            this.operationId = operationId;
            this.expectedGeneration = expectedGeneration;
            this.condition = condition == null ? "" : condition;
            this.breathGuidanceBiasPercent = Math.max(0,
                Math.min(100, breathGuidanceBiasPercent));
        }
    }

    static final class NativeReceipt {
        final String operationId;
        final boolean accepted;
        final boolean durable;
        final long generation;
        final long revision;
        final String phase;
        final String controlState;
        final String activeCondition;
        final boolean recording;
        final String completion;
        final long activeTimeMs;
        final boolean recovery;
        final String storageStatus;
        final boolean kioskRequested;
        final long completedOne;
        final long completedTwo;
        final long stoppedEarlyOne;
        final long stoppedEarlyTwo;
        final boolean perConditionCountsAvailable;
        final boolean countsAvailable;
        final boolean countsInvalid;
        final long completedTotal;
        final long stoppedEarlyTotal;
        final long errors;
        final String routeAction;
        final long routeActionRevision;
        final String detail;

        NativeReceipt(
            String operationId,
            boolean accepted,
            boolean durable,
            long generation,
            long revision,
            String phase,
            String activeCondition,
            boolean recording,
            boolean recovery,
            String storageStatus,
            boolean kioskRequested,
            long completedOne,
            long completedTwo,
            long stoppedEarlyOne,
            long stoppedEarlyTwo,
            boolean perConditionCountsAvailable,
            boolean countsAvailable,
            boolean countsInvalid,
            long completedTotal,
            long stoppedEarlyTotal,
            long errors,
            String routeAction,
            long routeActionRevision,
            String detail
        ) {
            this(
                operationId, accepted, durable, generation, revision, phase,
                "", activeCondition, recording, recovery, storageStatus, kioskRequested,
                completedOne, completedTwo, stoppedEarlyOne, stoppedEarlyTwo,
                perConditionCountsAvailable, countsAvailable, countsInvalid,
                completedTotal, stoppedEarlyTotal, errors, routeAction,
                routeActionRevision, detail
            );
        }

        NativeReceipt(
            String operationId,
            boolean accepted,
            boolean durable,
            long generation,
            long revision,
            String phase,
            String controlState,
            String activeCondition,
            boolean recording,
            boolean recovery,
            String storageStatus,
            boolean kioskRequested,
            long completedOne,
            long completedTwo,
            long stoppedEarlyOne,
            long stoppedEarlyTwo,
            boolean perConditionCountsAvailable,
            boolean countsAvailable,
            boolean countsInvalid,
            long completedTotal,
            long stoppedEarlyTotal,
            long errors,
            String routeAction,
            long routeActionRevision,
            String detail
        ) {
            this(
                operationId, accepted, durable, generation, revision, phase,
                controlState, activeCondition, recording, "", 0L, recovery,
                storageStatus, kioskRequested, completedOne, completedTwo,
                stoppedEarlyOne, stoppedEarlyTwo, perConditionCountsAvailable,
                countsAvailable, countsInvalid, completedTotal, stoppedEarlyTotal,
                errors, routeAction, routeActionRevision, detail
            );
        }

        NativeReceipt(
            String operationId,
            boolean accepted,
            boolean durable,
            long generation,
            long revision,
            String phase,
            String controlState,
            String activeCondition,
            boolean recording,
            String completion,
            long activeTimeMs,
            boolean recovery,
            String storageStatus,
            boolean kioskRequested,
            long completedOne,
            long completedTwo,
            long stoppedEarlyOne,
            long stoppedEarlyTwo,
            boolean perConditionCountsAvailable,
            boolean countsAvailable,
            boolean countsInvalid,
            long completedTotal,
            long stoppedEarlyTotal,
            long errors,
            String routeAction,
            long routeActionRevision,
            String detail
        ) {
            this.operationId = safe(operationId);
            this.accepted = accepted;
            this.durable = durable;
            this.generation = Math.max(0L, generation);
            this.revision = Math.max(0L, revision);
            this.phase = safe(phase);
            this.controlState = safe(controlState);
            this.activeCondition = safe(activeCondition);
            this.recording = recording;
            this.completion = safe(completion);
            this.activeTimeMs = Math.max(0L, activeTimeMs);
            this.recovery = recovery;
            this.storageStatus = safe(storageStatus);
            this.kioskRequested = kioskRequested;
            this.completedOne = completedOne;
            this.completedTwo = completedTwo;
            this.stoppedEarlyOne = stoppedEarlyOne;
            this.stoppedEarlyTwo = stoppedEarlyTwo;
            this.perConditionCountsAvailable = perConditionCountsAvailable;
            this.countsAvailable = countsAvailable;
            this.countsInvalid = countsInvalid;
            this.completedTotal = completedTotal;
            this.stoppedEarlyTotal = stoppedEarlyTotal;
            this.errors = errors;
            this.routeAction = safe(routeAction);
            this.routeActionRevision = Math.max(0L, routeActionRevision);
            this.detail = safe(detail);
        }
    }

    private final AtomicLong operationSequence = new AtomicLong(0L);
    private ExperimentSessionPanelState state = ExperimentSessionPanelState.initial();
    private long routeEventGeneration;
    private long routeEventAllocation;
    private long trustedLaunchEpoch;
    private long runtimeEpoch;
    private boolean freshRuntimeExpected;
    private String lastRejectedOperationId = "";

    synchronized boolean acceptRuntimeEpoch(long epoch) {
        if (epoch <= 0L || epoch == runtimeEpoch) return false;
        if (runtimeEpoch != 0L && (!freshRuntimeExpected || epoch < runtimeEpoch)) return false;
        runtimeEpoch = epoch;
        freshRuntimeExpected = false;
        state = ExperimentSessionPanelState.initial();
        lastRejectedOperationId = "";
        routeEventGeneration = 0L;
        routeEventAllocation = 0L;
        return true;
    }

    synchronized ExperimentSessionPanelState snapshot() { return state; }

    synchronized long allocateRouteEvent() {
        routeEventAllocation = Math.max(routeEventAllocation, routeEventGeneration) + 1L;
        return routeEventAllocation;
    }

    synchronized boolean admitTrustedColdLaunch(long launchEpoch) {
        if (launchEpoch <= 0L || launchEpoch <= trustedLaunchEpoch) {
            return false;
        }
        trustedLaunchEpoch = launchEpoch;
        freshRuntimeExpected = true;
        setRoute(ExperimentSessionPanelState.Route.EXPERIMENTER);
        return true;
    }

    synchronized void onLaunch(
        LaunchKind kind,
        ExperimentSessionPanelState.Route requestedRoute,
        long eventGeneration
    ) {
        if (eventGeneration > 0L && eventGeneration <= routeEventGeneration) {
            return;
        }
        if (eventGeneration > 0L) {
            routeEventGeneration = eventGeneration;
            routeEventAllocation = Math.max(routeEventAllocation, eventGeneration);
        }
        if (kind != LaunchKind.EXPLICIT_COLD_ROOT
                && requestedRoute != null
                && kind != LaunchKind.RECREATION_MAIN) {
            setRoute(requestedRoute);
        }
    }

    synchronized void openDeveloper(long eventGeneration) {
        if (!admitRouteEvent(eventGeneration)) {
            return;
        }
        setRoute(ExperimentSessionPanelState.Route.DEVELOPER);
    }

    synchronized void returnFromDeveloper() {
        setRoute(ExperimentSessionPanelState.Route.EXPERIMENTER);
    }

    synchronized NativeCommand start(String condition) {
        return start(condition, 0);
    }

    synchronized NativeCommand start(String condition, int breathGuidanceBiasPercent) {
        if (!CONDITION_ONE.equals(condition) && !CONDITION_TWO.equals(condition)) {
            return null;
        }
        if (state.hasActiveSession() || !state.pendingOperationId.isEmpty()) {
            return null;
        }
        String operationId = nextOperationId("start");
        lastRejectedOperationId = "";
        state = copy(
            state.route,
            ExperimentSessionPanelState.Phase.STARTING,
            state.generation,
            state.revision,
            condition,
            operationId,
            state.routeActionRevision,
            state.counts,
            state.polar,
            false,
            ExperimentSessionPanelState.Completion.NOT_REACHED,
            0L,
            state.recovery,
            "preparing",
            state.kioskRequested,
            "Preparing recording and audio."
        );
        return new NativeCommand("start", operationId, state.generation, condition,
            breathGuidanceBiasPercent);
    }

    synchronized NativeCommand arm(String condition) {
        return arm(condition, 0);
    }

    synchronized NativeCommand arm(String condition, int breathGuidanceBiasPercent) {
        if (!CONDITION_ONE.equals(condition) && !CONDITION_TWO.equals(condition)) {
            return null;
        }
        if (state.hasActiveSession() || !state.pendingOperationId.isEmpty()) {
            return null;
        }
        String operationId = nextOperationId("arm");
        lastRejectedOperationId = "";
        state = copy(
            state.route,
            ExperimentSessionPanelState.Phase.ARMING,
            state.generation,
            state.revision,
            condition,
            operationId,
            state.routeActionRevision,
            state.counts,
            state.polar,
            false,
            ExperimentSessionPanelState.Completion.NOT_REACHED,
            0L,
            state.recovery,
            "preparing",
            state.kioskRequested,
            "Preparing recording and audio; audio remains silent."
        );
        return new NativeCommand("arm", operationId, state.generation, condition,
            breathGuidanceBiasPercent);
    }

    synchronized NativeCommand restartToExperimenter(long eventGeneration) {
        if (!admitRouteEvent(eventGeneration)) {
            return null;
        }
        if (!state.hasActiveSession()) {
            setRoute(ExperimentSessionPanelState.Route.EXPERIMENTER);
            return null;
        }
        if (state.phase == ExperimentSessionPanelState.Phase.SAVING) {
            return null;
        }
        String operationId = nextOperationId("restart");
        state = copy(
            ExperimentSessionPanelState.Route.EXPERIMENTER,
            ExperimentSessionPanelState.Phase.SAVING,
            state.generation,
            state.revision,
            state.activeCondition,
            operationId,
            state.routeActionRevision,
            state.counts,
            state.polar,
            state.recording,
            state.completion,
            state.activeTimeMs,
            state.recovery,
            "finalizing",
            state.kioskRequested,
            "Saving…"
        );
        return new NativeCommand("restart-to-experimenter", operationId, state.generation, "");
    }

    synchronized boolean awaitNativeRestartRoute(
        long eventGeneration,
        long expectedSessionGeneration,
        String expectedOperationId
    ) {
        if (eventGeneration <= routeEventGeneration
                || expectedSessionGeneration <= 0L
                || expectedSessionGeneration != state.generation
                || expectedOperationId == null
                || expectedOperationId.trim().isEmpty()
                || !state.hasActiveSession()) {
            return false;
        }
        routeEventGeneration = eventGeneration;
        routeEventAllocation = Math.max(routeEventAllocation, eventGeneration);
        state = copy(
            ExperimentSessionPanelState.Route.EXPERIMENTER,
            ExperimentSessionPanelState.Phase.SAVING,
            state.generation,
            state.revision,
            state.activeCondition,
            expectedOperationId.trim(),
            state.routeActionRevision,
            state.counts,
            state.polar,
            state.recording,
            state.completion,
            state.activeTimeMs,
            state.recovery,
            "finalizing",
            state.kioskRequested,
            "Saving… awaiting exact native receipt."
        );
        return true;
    }

    synchronized boolean admitIdleExperimenterRecall(long eventGeneration) {
        if (eventGeneration <= routeEventGeneration || state.hasActiveSession()) {
            return false;
        }
        routeEventGeneration = eventGeneration;
        routeEventAllocation = Math.max(routeEventAllocation, eventGeneration);
        setRoute(ExperimentSessionPanelState.Route.EXPERIMENTER);
        return true;
    }

    synchronized boolean accept(NativeReceipt receipt) {
        if (receipt == null || receipt.revision < state.revision || receipt.generation < state.generation) {
            return false;
        }
        boolean pendingMatch = !state.pendingOperationId.isEmpty()
            && state.pendingOperationId.equals(receipt.operationId);
        boolean repeatedRejectedOperation = state.phase == ExperimentSessionPanelState.Phase.ERROR
            && state.pendingOperationId.isEmpty()
            && !lastRejectedOperationId.isEmpty()
            && lastRejectedOperationId.equals(receipt.operationId)
            && !receipt.accepted;
        if (repeatedRejectedOperation) {
            state = copy(
                state.route,
                ExperimentSessionPanelState.Phase.ERROR,
                state.generation,
                Math.max(state.revision, receipt.revision),
                state.activeCondition,
                "",
                state.routeActionRevision,
                state.counts,
                state.polar,
                state.recording,
                state.completion,
                state.activeTimeMs,
                receipt.recovery,
                emptyAs(receipt.storageStatus, state.storageStatus),
                receipt.kioskRequested,
                emptyAs(state.detail, emptyAs(receipt.detail, "Native command rejected."))
            );
            return true;
        }
        if (state.phase == ExperimentSessionPanelState.Phase.SAVING
                && !state.pendingOperationId.isEmpty()
                && receipt.generation != state.generation) {
            return false;
        }
        if (!state.pendingOperationId.isEmpty() && !pendingMatch) {
            return false;
        }
        if (pendingMatch && !receipt.accepted) {
            lastRejectedOperationId = receipt.operationId;
            state = copy(
                state.route,
                ExperimentSessionPanelState.Phase.ERROR,
                state.generation,
                Math.max(state.revision, receipt.revision),
                state.activeCondition,
                "",
                state.routeActionRevision,
                state.counts,
                state.polar,
                state.recording,
                state.completion,
                state.activeTimeMs,
                receipt.recovery,
                emptyAs(receipt.storageStatus, "error"),
                receipt.kioskRequested,
                emptyAs(receipt.detail, "Native command rejected.")
            );
            return true;
        }
        if (state.phase == ExperimentSessionPanelState.Phase.SAVING) {
            boolean exactRoute = receipt.durable
                && "show-experimenter".equals(receipt.routeAction)
                && receipt.routeActionRevision > state.routeActionRevision;
            if (!exactRoute || (!state.pendingOperationId.isEmpty() && !pendingMatch)) {
                return false;
            }
        }
        if ((state.phase == ExperimentSessionPanelState.Phase.STARTING
                || state.phase == ExperimentSessionPanelState.Phase.ARMING) && pendingMatch) {
            boolean exactActive = receipt.accepted
                && ("active".equals(receipt.phase) || "recording".equals(receipt.phase));
            if (!exactActive) {
                return false;
            }
        }
        if (pendingMatch && receipt.accepted) {
            lastRejectedOperationId = "";
        }
        ExperimentSessionPanelState.Phase phase = parsePhase(
            receipt.phase, receipt.controlState, state.phase
        );
        String pending = pendingMatch ? "" : state.pendingOperationId;
        boolean showExperimenter = receipt.accepted && receipt.durable
            && "show-experimenter".equals(receipt.routeAction);
        state = copy(
            showExperimenter || state.phase == ExperimentSessionPanelState.Phase.SAVING
                ? ExperimentSessionPanelState.Route.EXPERIMENTER : state.route,
            phase,
            receipt.generation,
            receipt.revision,
            emptyAs(receipt.activeCondition, phase == ExperimentSessionPanelState.Phase.IDLE ? "none" : state.activeCondition),
            pending,
            Math.max(state.routeActionRevision, receipt.routeActionRevision),
            receipt.perConditionCountsAvailable
                ? ExperimentSessionPanelState.Counts.withBreakdown(
                    receipt.completedTotal,
                    receipt.stoppedEarlyTotal,
                    receipt.errors,
                    receipt.completedOne,
                    receipt.completedTwo,
                    receipt.stoppedEarlyOne,
                    receipt.stoppedEarlyTwo
                )
                : (receipt.countsAvailable
                    ? ExperimentSessionPanelState.Counts.totalsOnly(
                    receipt.completedTotal,
                    receipt.stoppedEarlyTotal,
                    receipt.errors
                    )
                    : (receipt.countsInvalid
                        ? ExperimentSessionPanelState.Counts.invalid()
                        : ExperimentSessionPanelState.Counts.unknown())),
            state.polar,
            receipt.recording,
            parseCompletion(receipt.completion),
            receipt.activeTimeMs,
            receipt.recovery,
            emptyAs(receipt.storageStatus, state.storageStatus),
            receipt.kioskRequested,
            receipt.detail
        );
        return true;
    }

    synchronized void updatePolar(ExperimentSessionPanelState.PolarProjection projection) {
        if (projection == null
                || projection.generation < state.polar.generation
                || (projection.generation == state.polar.generation
                    && state.polar.observedAtUnixMs > 0L
                    && projection.observedAtUnixMs < state.polar.observedAtUnixMs)) {
            return;
        }
        state = copy(
            state.route, state.phase, state.generation, state.revision, state.activeCondition,
            state.pendingOperationId, state.routeActionRevision, state.counts, projection,
            state.recording, state.completion, state.activeTimeMs, state.recovery,
            state.storageStatus, state.kioskRequested, state.detail
        );
    }

    private boolean admitRouteEvent(long generation) {
        if (generation <= 0L) {
            generation = routeEventGeneration + 1L;
        }
        if (generation <= routeEventGeneration) {
            return false;
        }
        routeEventGeneration = generation;
        routeEventAllocation = Math.max(routeEventAllocation, generation);
        return true;
    }

    private void setRoute(ExperimentSessionPanelState.Route route) {
        state = copy(
            route, state.phase, state.generation, state.revision, state.activeCondition,
            state.pendingOperationId, state.routeActionRevision, state.counts, state.polar,
            state.recording, state.completion, state.activeTimeMs, state.recovery,
            state.storageStatus, state.kioskRequested, state.detail
        );
    }

    private String nextOperationId(String prefix) {
        return String.format(Locale.US, "panel-%s-%d", prefix, operationSequence.incrementAndGet());
    }

    private static ExperimentSessionPanelState.Phase parsePhase(
        String value,
        String controlState,
        ExperimentSessionPanelState.Phase fallback
    ) {
        String control = safe(controlState).toLowerCase(Locale.US);
        if ("arming".equals(control)) return ExperimentSessionPanelState.Phase.ARMING;
        if ("armed".equals(control)) return ExperimentSessionPanelState.Phase.ARMED;
        if ("running".equals(control)) return ExperimentSessionPanelState.Phase.RUNNING;
        if ("paused".equals(control)) return ExperimentSessionPanelState.Phase.PAUSED;
        if ("finalizing".equals(control)) return ExperimentSessionPanelState.Phase.FINALIZING;
        if ("audio-error".equals(control)) return ExperimentSessionPanelState.Phase.ERROR;
        String normalized = safe(value).toLowerCase(Locale.US);
        if ("idle".equals(normalized) || "closed".equals(normalized)) return ExperimentSessionPanelState.Phase.IDLE;
        if ("preparing".equals(normalized)) return ExperimentSessionPanelState.Phase.STARTING;
        if ("active".equals(normalized) || "recording".equals(normalized)) return ExperimentSessionPanelState.Phase.RECORDING;
        if ("finalizing-restart".equals(normalized) || "saving".equals(normalized)) return ExperimentSessionPanelState.Phase.SAVING;
        if ("recovery".equals(normalized)) return ExperimentSessionPanelState.Phase.RECOVERY;
        if ("error".equals(normalized) || "failed".equals(normalized)) return ExperimentSessionPanelState.Phase.ERROR;
        return fallback;
    }

    private static ExperimentSessionPanelState.Completion parseCompletion(String value) {
        String normalized = safe(value).toLowerCase(Locale.US);
        if ("not-reached".equals(normalized)) {
            return ExperimentSessionPanelState.Completion.NOT_REACHED;
        }
        if ("persistence-pending".equals(normalized)) {
            return ExperimentSessionPanelState.Completion.PERSISTENCE_PENDING;
        }
        if ("durable".equals(normalized)) {
            return ExperimentSessionPanelState.Completion.DURABLE;
        }
        return ExperimentSessionPanelState.Completion.UNKNOWN;
    }

    private static ExperimentSessionPanelState copy(
        ExperimentSessionPanelState.Route route,
        ExperimentSessionPanelState.Phase phase,
        long generation,
        long revision,
        String activeCondition,
        String pendingOperation,
        long routeActionRevision,
        ExperimentSessionPanelState.Counts counts,
        ExperimentSessionPanelState.PolarProjection polar,
        boolean recording,
        ExperimentSessionPanelState.Completion completion,
        long activeTimeMs,
        boolean recovery,
        String storageStatus,
        boolean kioskRequested,
        String detail
    ) {
        return new ExperimentSessionPanelState(
            route, phase, generation, revision, activeCondition, pendingOperation,
            routeActionRevision, counts, polar, recording, completion, activeTimeMs,
            recovery, storageStatus, kioskRequested, detail
        );
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String emptyAs(String value, String fallback) {
        return safe(value).isEmpty() ? fallback : value;
    }
}
