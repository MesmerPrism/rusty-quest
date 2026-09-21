package io.github.mesmerprism.rustyquest.native_renderer;

/** Immutable presentation projection for the experimenter panel. */
final class ExperimentSessionPanelState {
    enum Route { EXPERIMENTER, DEVELOPER }
    enum Phase {
        IDLE, STARTING, ARMING, ARMED, RUNNING, PAUSED, RECORDING,
        FINALIZING, SAVING, RECOVERY, ERROR, UNAVAILABLE
    }
    enum Bluetooth { ON, OFF, UNSUPPORTED, PERMISSION_REQUIRED, TURNING, UNKNOWN }
    enum Polar {
        CONNECTED, SCANNING, CONNECTING, MULTIPLE, NOT_FOUND,
        LOCATION_SERVICES_DISABLED, FAILED, STALE, UNKNOWN
    }

    static final class Counts {
        final long completedOne;
        final long completedTwo;
        final long stoppedEarlyOne;
        final long stoppedEarlyTwo;
        final long completedObservedTotal;
        final long stoppedEarlyObservedTotal;
        final long errors;
        final boolean totalsAvailable;
        final boolean perConditionAvailable;
        final boolean invalid;

        Counts(long completedOne, long completedTwo, long stoppedEarlyOne, long stoppedEarlyTwo) {
            this(completedOne, completedTwo, stoppedEarlyOne, stoppedEarlyTwo, 0L);
        }

        Counts(
            long completedOne,
            long completedTwo,
            long stoppedEarlyOne,
            long stoppedEarlyTwo,
            long errors
        ) {
            this.completedOne = nonNegative(completedOne);
            this.completedTwo = nonNegative(completedTwo);
            this.stoppedEarlyOne = nonNegative(stoppedEarlyOne);
            this.stoppedEarlyTwo = nonNegative(stoppedEarlyTwo);
            this.completedObservedTotal = this.completedOne + this.completedTwo;
            this.stoppedEarlyObservedTotal = this.stoppedEarlyOne + this.stoppedEarlyTwo;
            this.errors = nonNegative(errors);
            this.totalsAvailable = true;
            this.perConditionAvailable = true;
            this.invalid = false;
        }

        private Counts(
            long completed,
            long stoppedEarly,
            long errors,
            boolean totalsAvailable,
            boolean perConditionAvailable,
            boolean invalid,
            long completedOne,
            long completedTwo,
            long stoppedEarlyOne,
            long stoppedEarlyTwo
        ) {
            this.completedOne = nonNegative(completedOne);
            this.completedTwo = nonNegative(completedTwo);
            this.stoppedEarlyOne = nonNegative(stoppedEarlyOne);
            this.stoppedEarlyTwo = nonNegative(stoppedEarlyTwo);
            this.completedObservedTotal = nonNegative(completed);
            this.stoppedEarlyObservedTotal = nonNegative(stoppedEarly);
            this.errors = nonNegative(errors);
            this.totalsAvailable = totalsAvailable;
            this.perConditionAvailable = perConditionAvailable;
            this.invalid = invalid;
        }

        static Counts totalsOnly(long completed, long stoppedEarly, long errors) {
            return new Counts(completed, stoppedEarly, errors, true, false, false, 0L, 0L, 0L, 0L);
        }

        static Counts withBreakdown(
            long completed,
            long stoppedEarly,
            long errors,
            long completedOne,
            long completedTwo,
            long stoppedEarlyOne,
            long stoppedEarlyTwo
        ) {
            return new Counts(
                completed, stoppedEarly, errors, true, true, false,
                completedOne, completedTwo, stoppedEarlyOne, stoppedEarlyTwo
            );
        }

        static Counts unknown() {
            return new Counts(0L, 0L, 0L, false, false, false, 0L, 0L, 0L, 0L);
        }

        static Counts invalid() {
            return new Counts(0L, 0L, 0L, false, false, true, 0L, 0L, 0L, 0L);
        }

        long completedTotal() { return completedObservedTotal; }
        long stoppedEarlyTotal() { return stoppedEarlyObservedTotal; }

        private static long nonNegative(long value) { return Math.max(0L, value); }
    }

    static final class PolarProjection {
        final Bluetooth bluetooth;
        final Polar polar;
        final int candidateCount;
        final long generation;
        final long observedAtUnixMs;
        final long deadlineElapsedMs;
        final boolean fresh;
        final String detail;

        PolarProjection(
            Bluetooth bluetooth,
            Polar polar,
            int candidateCount,
            long generation,
            long observedAtUnixMs,
            long deadlineElapsedMs,
            boolean fresh,
            String detail
        ) {
            this.bluetooth = bluetooth == null ? Bluetooth.UNKNOWN : bluetooth;
            this.polar = polar == null ? Polar.UNKNOWN : polar;
            this.candidateCount = Math.max(0, candidateCount);
            this.generation = Math.max(0L, generation);
            this.observedAtUnixMs = Math.max(0L, observedAtUnixMs);
            this.deadlineElapsedMs = Math.max(0L, deadlineElapsedMs);
            this.fresh = fresh;
            this.detail = detail == null ? "" : detail;
        }

        static PolarProjection unknown() {
            return new PolarProjection(
                Bluetooth.UNKNOWN, Polar.UNKNOWN, 0, 0L, 0L, 0L, false, "not-observed"
            );
        }
    }

    final Route route;
    final Phase phase;
    final long generation;
    final long revision;
    final String activeCondition;
    final String pendingOperationId;
    final long routeActionRevision;
    final Counts counts;
    final PolarProjection polar;
    final boolean recording;
    final boolean recovery;
    final String storageStatus;
    final boolean kioskRequested;
    final String detail;

    ExperimentSessionPanelState(
        Route route,
        Phase phase,
        long generation,
        long revision,
        String activeCondition,
        String pendingOperationId,
        long routeActionRevision,
        Counts counts,
        PolarProjection polar,
        boolean recording,
        boolean recovery,
        String storageStatus,
        boolean kioskRequested,
        String detail
    ) {
        this.route = route == null ? Route.EXPERIMENTER : route;
        this.phase = phase == null ? Phase.UNAVAILABLE : phase;
        this.generation = Math.max(0L, generation);
        this.revision = Math.max(0L, revision);
        this.activeCondition = activeCondition == null ? "none" : activeCondition;
        this.pendingOperationId = pendingOperationId == null ? "" : pendingOperationId;
        this.routeActionRevision = Math.max(0L, routeActionRevision);
        this.counts = counts == null ? Counts.unknown() : counts;
        this.polar = polar == null ? PolarProjection.unknown() : polar;
        this.recording = recording;
        this.recovery = recovery;
        this.storageStatus = storageStatus == null ? "unknown" : storageStatus;
        this.kioskRequested = kioskRequested;
        this.detail = detail == null ? "" : detail;
    }

    static ExperimentSessionPanelState initial() {
        return new ExperimentSessionPanelState(
            Route.EXPERIMENTER,
            Phase.IDLE,
            0L,
            0L,
            "none",
            "",
            0L,
            Counts.unknown(),
            PolarProjection.unknown(),
            false,
            false,
            "awaiting-native-readback",
            true,
            "No session. Choose Start to create one."
        );
    }

    boolean hasActiveSession() {
        return recording || phase == Phase.STARTING || phase == Phase.ARMING
            || phase == Phase.ARMED || phase == Phase.RUNNING || phase == Phase.PAUSED
            || phase == Phase.RECORDING || phase == Phase.FINALIZING
            || phase == Phase.SAVING || phase == Phase.RECOVERY;
    }
}
