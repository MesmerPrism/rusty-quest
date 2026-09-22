package io.github.mesmerprism.rustyquest.native_renderer;

public final class ExperimentSessionPanelCoordinatorTest {
    public static void main(String[] args) {
        launchAndRoutePolicy();
        armControlStatePolicy();
        rejectedArmRemainsVisibleAndRetryable();
        startDeveloperAndRestartReceiptPolicy();
        independentCompletionProjection();
        polarAndViewPolicy();
        guidanceBiasPendingPolicy();
        closedRuntimeEpochResetsGenerationButRecreationDoesNot();
        System.out.println("ExperimentSessionPanelCoordinatorTest PASS");
    }

    private static void guidanceBiasPendingPolicy() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        check(coordinator.pendingBreathGuidanceBiasPercent() == -1,
            "guidance bias starts unresolved until packaged readiness is known");
        check(coordinator.updatePendingBreathGuidanceBiasPercent(67)
                && coordinator.pendingBreathGuidanceBiasPercent() == 67,
            "developer guidance value is retained by the process coordinator");
        coordinator.openDeveloper(coordinator.allocateRouteEvent());
        coordinator.returnFromDeveloper();
        coordinator.onLaunch(
            ExperimentSessionPanelCoordinator.LaunchKind.RECREATION_MAIN,
            null,
            0L
        );
        check(coordinator.pendingBreathGuidanceBiasPercent() == 67,
            "panel routing and Activity recreation retain the pending guidance value");
        ExperimentSessionPanelCoordinator.NativeCommand arm = coordinator.arm(
            ExperimentSessionPanelCoordinator.CONDITION_ONE,
            coordinator.pendingBreathGuidanceBiasPercent()
        );
        check(arm != null && arm.breathGuidanceBiasPercent == 67,
            "the next arm locks the retained developer guidance value");
        check(!coordinator.updatePendingBreathGuidanceBiasPercent(20)
                && coordinator.pendingBreathGuidanceBiasPercent() == 67,
            "an arming or active session cannot silently change its guidance identity");

        ExperimentSessionPanelCoordinator fresh = new ExperimentSessionPanelCoordinator();
        check(fresh.updatePendingBreathGuidanceBiasPercent(42),
            "fresh coordinator accepts pre-arm guidance tuning");
        check(fresh.acceptRuntimeEpoch(100L)
                && fresh.pendingBreathGuidanceBiasPercent() == -1,
            "a fresh native runtime resets runtime-only guidance tuning");
    }

    private static void armControlStatePolicy() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        ExperimentSessionPanelCoordinator.NativeCommand arm =
            coordinator.arm(ExperimentSessionPanelCoordinator.CONDITION_ONE);
        check(arm != null && "arm".equals(arm.operation)
                && coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.ARMING,
            "condition selection emits an arm command and visible preparation state");
        check(coordinator.accept(new ExperimentSessionPanelCoordinator.NativeReceipt(
            arm.operationId, true, false, 1L, 1L, "active", "armed",
            ExperimentSessionPanelCoordinator.CONDITION_ONE, true, false, "ready", true,
            0L, 0L, 0L, 0L, true, true, false, 0L, 0L, 0L,
            "none", 0L, "armed"
        )), "durable native control state is accepted");
        check(coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.ARMED
                && ExperimentSessionPanelViewPolicy.canReturnToImmersive(coordinator.snapshot()),
            "armed control state closes preparation and admits the particle scene");
    }

    private static void rejectedArmRemainsVisibleAndRetryable() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        ExperimentSessionPanelCoordinator.NativeCommand arm =
            coordinator.arm(ExperimentSessionPanelCoordinator.CONDITION_ONE);
        ExperimentSessionPanelCoordinator.NativeReceipt rejected =
            new ExperimentSessionPanelCoordinator.NativeReceipt(
                arm.operationId, false, false, 0L, 2L, "idle", "idle",
                ExperimentSessionPanelCoordinator.CONDITION_ONE, false, false, "ready", true,
                0L, 0L, 0L, 0L, true, true, false, 0L, 0L, 0L,
                "none", 0L,
                "Condition was not armed: the packaged study profile identity did not match."
            );
        check(coordinator.accept(rejected), "matching rejected arm receipt is accepted as failure evidence");
        check(coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.ERROR
                && coordinator.snapshot().detail.contains("not armed"),
            "arm rejection remains explicit");
        check(coordinator.accept(rejected), "repeated status projection remains admissible");
        check(coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.ERROR,
            "repeated rejected receipt cannot collapse into idle");
        check(ExperimentSessionPanelViewPolicy.project(coordinator.snapshot()).startEnabled,
            "a non-recording arm rejection permits an explicit retry");
        ExperimentSessionPanelCoordinator.NativeCommand retry =
            coordinator.arm(ExperimentSessionPanelCoordinator.CONDITION_ONE);
        check(retry != null && !retry.operationId.equals(arm.operationId),
            "retry allocates a fresh arm operation");
    }

    private static void independentCompletionProjection() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        ExperimentSessionPanelCoordinator.NativeCommand arm =
            coordinator.arm(ExperimentSessionPanelCoordinator.CONDITION_ONE);
        check(coordinator.accept(new ExperimentSessionPanelCoordinator.NativeReceipt(
            arm.operationId, true, false, 1L, 1L, "active", "running",
            ExperimentSessionPanelCoordinator.CONDITION_ONE, true,
            "durable", 30_004L, false, "ready", true,
            0L, 0L, 0L, 0L, true, true, false, 0L, 0L, 0L,
            "none", 0L, "completion-reached"
        )), "completion readback accepted");
        check(coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.RUNNING
                && coordinator.snapshot().recording
                && coordinator.snapshot().completion
                    == ExperimentSessionPanelState.Completion.DURABLE
                && coordinator.snapshot().activeTimeMs == 30_004L,
            "condition completion remains independent from active recording state");
        check(ExperimentSessionPanelViewPolicy.stageTitle(coordinator.snapshot())
                .contains("recording continues"),
            "durable completion receives the distinct operator label");
    }

    private static void closedRuntimeEpochResetsGenerationButRecreationDoesNot() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        coordinator.admitTrustedColdLaunch(1L);
        check(coordinator.acceptRuntimeEpoch(101L), "initial runtime admitted");
        ExperimentSessionPanelCoordinator.NativeCommand start = coordinator.start("condition-a");
        coordinator.accept(receipt(start.operationId, true, true, 1L, 9L, "active", true,
            "none", 0L, 0L, 0L, 0L, 0L));
        check(!coordinator.acceptRuntimeEpoch(101L), "recreation retains runtime epoch");
        check(coordinator.snapshot().generation == 1L && coordinator.snapshot().recording,
            "recreation retains generation and recording");
        check(!coordinator.acceptRuntimeEpoch(102L), "new runtime requires explicit launch");
        coordinator.admitTrustedColdLaunch(2L);
        check(coordinator.acceptRuntimeEpoch(102L), "closed runtime replacement admitted");
        check(coordinator.snapshot().generation == 0L, "fresh native runtime is generation zero");
        ExperimentSessionPanelCoordinator.NativeCommand next = coordinator.start("condition-a");
        check(next != null && next.expectedGeneration == 0L, "relaunch can start from generation zero");
        check(coordinator.accept(receipt(next.operationId, true, true, 1L, 1L, "active", true,
            "none", 0L, 0L, 0L, 0L, 0L)), "new runtime generation one accepted");
        check(!coordinator.acceptRuntimeEpoch(101L), "old epoch cannot reset fresh runtime");
    }

    private static void launchAndRoutePolicy() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        check(!coordinator.snapshot().hasActiveSession(), "construction must not create a session");
        long developerEvent = coordinator.allocateRouteEvent();
        coordinator.openDeveloper(developerEvent);
        coordinator.onLaunch(
            ExperimentSessionPanelCoordinator.LaunchKind.EXPLICIT_COLD_ROOT,
            ExperimentSessionPanelState.Route.EXPERIMENTER,
            0L
        );
        check(
            coordinator.snapshot().route == ExperimentSessionPanelState.Route.DEVELOPER,
            "untrusted explicit launch kind cannot reset route"
        );
        check(coordinator.admitTrustedColdLaunch(10L), "trusted fresh launch epoch");
        check(!coordinator.admitTrustedColdLaunch(10L), "trusted launch epoch replay rejects");
        check(
            coordinator.snapshot().route == ExperimentSessionPanelState.Route.EXPERIMENTER,
            "trusted cold root opens experimenter"
        );
        coordinator.openDeveloper(coordinator.allocateRouteEvent());
        coordinator.onLaunch(
            ExperimentSessionPanelCoordinator.LaunchKind.INTERNAL_MAIN,
            null,
            0L
        );
        check(coordinator.snapshot().route == ExperimentSessionPanelState.Route.DEVELOPER,
            "bare internal MAIN cannot reset route");
        coordinator.onLaunch(
            ExperimentSessionPanelCoordinator.LaunchKind.RECREATION_MAIN,
            ExperimentSessionPanelState.Route.EXPERIMENTER,
            0L
        );
        check(
            coordinator.snapshot().route == ExperimentSessionPanelState.Route.DEVELOPER,
            "recreation MAIN must not reset route"
        );
        coordinator.returnFromDeveloper();
        check(
            coordinator.snapshot().route == ExperimentSessionPanelState.Route.EXPERIMENTER,
            "explicit developer return"
        );
        check(
            coordinator.start("condition-3") == null,
            "only the exact two conditions are admitted"
        );
    }

    private static void startDeveloperAndRestartReceiptPolicy() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        ExperimentSessionPanelCoordinator.NativeCommand start =
            coordinator.start(ExperimentSessionPanelCoordinator.CONDITION_ONE);
        check(start != null && "start".equals(start.operation), "Start must create one command");
        check(coordinator.start(ExperimentSessionPanelCoordinator.CONDITION_TWO) == null,
            "hydration/double click cannot create another session");
        check(!coordinator.accept(receipt(
            "wrong", true, true, 0L, 1L, "active", true,
            "show-immersive", 1L, 0L, 0L, 0L, 0L
        )), "wrong operation receipt must reject");
        check(coordinator.accept(receipt(
            start.operationId, true, true, 1L, 2L, "active", true,
            "none", 0L, 0L, 0L, 0L, 0L
        )), "exact accepted active Start receipt");
        long sessionGeneration = coordinator.snapshot().generation;
        coordinator.openDeveloper(10L);
        coordinator.returnFromDeveloper();
        check(coordinator.snapshot().generation == sessionGeneration && coordinator.snapshot().recording,
            "developer round trip must preserve session and recording");

        check(coordinator.accept(receipt(
            "", true, true, 1L, 3L, "active", true,
            "", 1L, 2L, 1L, 3L, 4L
        )), "async newer count projection");
        check(coordinator.snapshot().counts.completedTotal() == 3L,
            "async completed counts");
        check(coordinator.snapshot().counts.stoppedEarlyTotal() == 7L,
            "async stopped-early counts");
        check(!coordinator.accept(receipt(
            "", true, true, 0L, 99L, "idle", false,
            "", 2L, 99L, 99L, 99L, 99L
        )), "old session generation must reject");

        ExperimentSessionPanelCoordinator.NativeCommand restart = coordinator.restartToExperimenter(11L);
        check(restart != null && coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.SAVING,
            "explicit restart-to-experimenter enters Saving");
        check(!coordinator.accept(receipt(
            restart.operationId, true, false, 1L, 4L, "idle", false,
            "show-experimenter", 2L, 3L, 1L, 3L, 4L
        )), "non-durable B receipt cannot leave Saving");
        check(coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.SAVING,
            "Saving remains visible until durable receipt");
        check(coordinator.accept(receipt(
            restart.operationId, true, true, 1L, 5L, "idle", false,
            "show-experimenter", 2L, 3L, 1L, 3L, 4L
        )), "exact durable B receipt");
        check(coordinator.snapshot().phase == ExperimentSessionPanelState.Phase.IDLE
                && coordinator.snapshot().route == ExperimentSessionPanelState.Route.EXPERIMENTER,
            "B receipt resets to experimenter home");

        ExperimentSessionPanelCoordinator bridgeObserver = new ExperimentSessionPanelCoordinator();
        ExperimentSessionPanelCoordinator.NativeCommand bridgeStart = bridgeObserver.start(
            ExperimentSessionPanelCoordinator.CONDITION_ONE
        );
        check(bridgeObserver.accept(receipt(
            bridgeStart.operationId, true, true, 1L, 1L, "active", true,
            "none", 0L, 0L, 0L, 0L, 0L
        )), "bridge precondition active session");
        check(bridgeObserver.awaitNativeRestartRoute(5L, 1L, "native-b"),
            "current generation B route admitted");
        check(!bridgeObserver.awaitNativeRestartRoute(5L, 1L, "native-b"),
            "B route event replay rejects");
        check(!bridgeObserver.accept(receipt(
            "native-b", true, false, 1L, 1L, "active", true,
            "none", 0L, 0L, 0L, 0L, 0L
        )), "bridge route must stay Saving before native durable receipt");
        check(!bridgeObserver.accept(receipt(
            "native-b", true, true, 1L, 2L, "idle", false,
            "none", 1L, 0L, 0L, 0L, 0L
        )), "idle alone cannot satisfy the exact B route receipt");
        check(bridgeObserver.snapshot().phase == ExperimentSessionPanelState.Phase.SAVING,
            "idle without exact route action leaves Saving fenced");
        check(bridgeObserver.accept(receipt(
            "native-b", true, true, 1L, 3L, "idle", false,
            "show-experimenter", 1L, 1L, 0L, 0L, 1L
        )), "bridge route accepts exact durable native receipt");
        ExperimentSessionPanelCoordinator.NativeCommand secondStart = bridgeObserver.start(
            ExperimentSessionPanelCoordinator.CONDITION_TWO
        );
        check(bridgeObserver.accept(receipt(
            secondStart.operationId, true, true, 2L, 4L, "active", true,
            "none", 1L, 1L, 0L, 0L, 1L
        )), "new session generation");
        check(!bridgeObserver.awaitNativeRestartRoute(6L, 1L, "native-b"),
            "stale B receipt cannot enter Saving during new session");
        check(bridgeObserver.snapshot().phase == ExperimentSessionPanelState.Phase.RECORDING,
            "stale B leaves new session active");

        ExperimentSessionPanelCoordinator missingCounts = new ExperimentSessionPanelCoordinator();
        check(ExperimentSessionPanelViewPolicy.project(missingCounts.snapshot())
                .countLine.contains("unavailable"),
            "missing counts are unknown, not zero");
        check(missingCounts.accept(new ExperimentSessionPanelCoordinator.NativeReceipt(
            "", true, true, 0L, 1L, "idle", "none", false, false, "ready", true,
            0L, 0L, 0L, 0L, false, false, false, 0L, 0L, 0L,
            "none", 0L, "counts omitted"
        )), "receipt with omitted counts accepted as status");
        check(!missingCounts.snapshot().counts.totalsAvailable
                && ExperimentSessionPanelViewPolicy.project(missingCounts.snapshot())
                    .countLine.contains("unavailable"),
            "omitted native counts remain explicitly unavailable");
        check(missingCounts.accept(new ExperimentSessionPanelCoordinator.NativeReceipt(
            "", true, true, 0L, 2L, "idle", "none", false, false, "ready", true,
            2L, 3L, 4L, 5L, true, true, false, 8L, 12L, 7L,
            "none", 0L, "errors"
        )), "counts projection accepted");
        check(missingCounts.snapshot().counts.errors == 7L,
            "native aggregate errors survive per-condition projection");
        check(missingCounts.snapshot().counts.completedTotal() == 8L
                && missingCounts.snapshot().counts.stoppedEarlyTotal() == 12L,
            "authoritative totals remain independent from condition buckets");
        check(ExperimentSessionPanelViewPolicy.project(missingCounts.snapshot())
                .countLine.contains("Unclassified/recovered: completed 3, stopped early 3"),
            "unclassified recovered dispositions remain visible");
        check(missingCounts.accept(new ExperimentSessionPanelCoordinator.NativeReceipt(
            "", true, true, 0L, 3L, "idle", "none", false, false, "ready", true,
            0L, 0L, 0L, 0L, false, false, true, 0L, 0L, 0L,
            "none", 0L, "malformed counts"
        )), "malformed count status accepted without inventing zeros");
        check(missingCounts.snapshot().counts.invalid
                && ExperimentSessionPanelViewPolicy.project(missingCounts.snapshot())
                    .countLine.contains("invalid native readback"),
            "invalid native counts are explicit, never zero");
    }

    private static void polarAndViewPolicy() {
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.PERMISSION_REQUIRED,
            ExperimentSessionPanelState.Polar.UNKNOWN,
            0,
            1L,
            10L,
            0L,
            true,
            "permission"
        ));
        ExperimentSessionPanelViewPolicy.ViewState permission =
            ExperimentSessionPanelViewPolicy.project(coordinator.snapshot());
        check(permission.bluetoothLine.contains("permission required") && permission.showPolarFallback,
            "permission unavailable projection");
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.OFF,
            ExperimentSessionPanelState.Polar.NOT_FOUND,
            0,
            1L,
            11L,
            0L,
            true,
            "off"
        ));
        check(ExperimentSessionPanelViewPolicy.project(coordinator.snapshot()).bluetoothLine.contains("off"),
            "Bluetooth off projection");
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.ON,
            ExperimentSessionPanelState.Polar.MULTIPLE,
            2,
            1L,
            12L,
            0L,
            true,
            "multiple"
        ));
        ExperimentSessionPanelViewPolicy.ViewState multiple =
            ExperimentSessionPanelViewPolicy.project(coordinator.snapshot());
        check(multiple.polarLine.contains("multiple sensors") && multiple.showPolarFallback,
            "multiple Polar sensors require dedicated page");
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.ON,
            ExperimentSessionPanelState.Polar.LOCATION_SERVICES_DISABLED,
            0,
            1L,
            13L,
            0L,
            true,
            "location services disabled"
        ));
        check(
            ExperimentSessionPanelViewPolicy.project(coordinator.snapshot()).polarLine
                .contains("location services must be enabled"),
            "location-services prerequisite is explicit"
        );
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.ON,
            ExperimentSessionPanelState.Polar.CONNECTED,
            1,
            1L,
            14L,
            0L,
            true,
            "connected"
        ));
        check(!ExperimentSessionPanelViewPolicy.project(coordinator.snapshot()).showPolarFallback,
            "fresh connected projection succeeds");
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.ON,
            ExperimentSessionPanelState.Polar.FAILED,
            0,
            1L,
            9L,
            0L,
            true,
            "older"
        ));
        check(coordinator.snapshot().polar.polar == ExperimentSessionPanelState.Polar.CONNECTED,
            "stale async Polar projection rejects");
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.ON,
            ExperimentSessionPanelState.Polar.FAILED,
            0,
            0L,
            99L,
            0L,
            true,
            "old-generation"
        ));
        check(coordinator.snapshot().polar.polar == ExperimentSessionPanelState.Polar.CONNECTED,
            "old Polar generation rejects even with newer timestamp");
    }

    private static ExperimentSessionPanelCoordinator.NativeReceipt receipt(
        String operation,
        boolean accepted,
        boolean durable,
        long generation,
        long revision,
        String phase,
        boolean recording,
        String route,
        long routeRevision,
        long completeOne,
        long completeTwo,
        long earlyOne,
        long earlyTwo
    ) {
        return new ExperimentSessionPanelCoordinator.NativeReceipt(
            operation, accepted, durable, generation, revision, phase,
            recording ? ExperimentSessionPanelCoordinator.CONDITION_ONE : "none",
            recording, false, "ready", true,
            completeOne, completeTwo, earlyOne, earlyTwo,
            true, true, false, completeOne + completeTwo, earlyOne + earlyTwo, 0L,
            route, routeRevision, "test"
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
