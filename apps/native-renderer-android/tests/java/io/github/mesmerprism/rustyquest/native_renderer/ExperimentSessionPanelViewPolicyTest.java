package io.github.mesmerprism.rustyquest.native_renderer;

public final class ExperimentSessionPanelViewPolicyTest {
    public static void main(String[] args) {
        independentPageScrollAndRecreation();
        changingReadinessPreservesNavigation();
        truthfulStageAndReadiness();
        conciseReadinessCards();
        System.out.println("ExperimentSessionPanelViewPolicyTest PASS");
    }

    private static void independentPageScrollAndRecreation() {
        ExperimentSessionPanelViewPolicy.Navigation navigation = new ExperimentSessionPanelViewPolicy.Navigation();
        navigation.rememberScroll(180);
        navigation.select(ExperimentSessionPanelViewPolicy.Page.CONTROLS);
        check(navigation.scrollY() == 0, "first visit starts at top");
        navigation.rememberScroll(420);
        navigation.select(ExperimentSessionPanelViewPolicy.Page.CONDITION);
        // A view replacement remembers the page that was actually rendered, not the new page.
        navigation.rememberScroll(ExperimentSessionPanelViewPolicy.Page.CONTROLS, 460);
        check(navigation.scrollY() == 0, "old page cannot overwrite new page scroll");
        navigation.select(ExperimentSessionPanelViewPolicy.Page.CONTROLS);
        check(navigation.scrollY() == 460, "back restores controls reading position");
        ExperimentSessionPanelViewPolicy.Navigation recreated = new ExperimentSessionPanelViewPolicy.Navigation();
        recreated.restore(navigation.page().name(), navigation.savedScroll());
        check(recreated.page() == ExperimentSessionPanelViewPolicy.Page.CONTROLS && recreated.scrollY() == 460,
            "Activity recreation retains page and scroll");
        int[] detached = navigation.savedScroll();
        detached[1] = 0;
        check(navigation.scrollY() == 460, "saved state cannot mutate active navigation");
        recreated.select(ExperimentSessionPanelViewPolicy.Page.PREPARE);
        check(recreated.scrollY() == 180, "each page has independent scroll");
        recreated.restore("obsolete", new int[] {-25});
        check(recreated.page() == ExperimentSessionPanelViewPolicy.Page.PREPARE && recreated.scrollY() == 0,
            "invalid restoration falls back safely");
    }

    private static void changingReadinessPreservesNavigation() {
        ExperimentSessionPanelViewPolicy.Navigation navigation = new ExperimentSessionPanelViewPolicy.Navigation();
        navigation.select(ExperimentSessionPanelViewPolicy.Page.CONTROLS);
        navigation.rememberScroll(320);
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        for (int i = 1; i <= 100; i++) {
            coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
                ExperimentSessionPanelState.Bluetooth.ON,
                i % 2 == 0 ? ExperimentSessionPanelState.Polar.CONNECTED : ExperimentSessionPanelState.Polar.SCANNING,
                1, 1L, i, 0L, true, "synthetic"));
            ExperimentSessionPanelViewPolicy.project(coordinator.snapshot());
        }
        check(navigation.page() == ExperimentSessionPanelViewPolicy.Page.CONTROLS && navigation.scrollY() == 320,
            "repeated asynchronous readiness projection cannot navigate or reset scroll");
    }

    private static void truthfulStageAndReadiness() {
        ExperimentSessionPanelState initial = ExperimentSessionPanelState.initial();
        check(ExperimentSessionPanelViewPolicy.storageTone(initial) != ExperimentSessionPanelViewPolicy.Tone.READY,
            "unobserved storage is not ready");
        check(ExperimentSessionPanelViewPolicy.polarTone(initial) != ExperimentSessionPanelViewPolicy.Tone.READY,
            "unobserved Polar is not ready");
        ExperimentSessionPanelState running = state(ExperimentSessionPanelState.Phase.RECORDING, true, false);
        check(ExperimentSessionPanelViewPolicy.stageInstruction(running).contains("Right Grip + B"),
            "running state explains pause");
        check(ExperimentSessionPanelViewPolicy.stageInstruction(running).contains("does not stop recording"),
            "audio completion cannot imply run finalization");
        ExperimentSessionPanelState complete = state(
            ExperimentSessionPanelState.Phase.RECORDING,
            true,
            ExperimentSessionPanelState.Completion.DURABLE,
            false
        );
        check(ExperimentSessionPanelViewPolicy.stageTitle(complete)
                .contains("Condition complete · recording continues")
                && ExperimentSessionPanelViewPolicy.stageInstruction(complete)
                    .contains("recording continue until Save and exit")
                && ExperimentSessionPanelViewPolicy.project(complete).statusLine
                    .contains("Official condition: complete · Recording: active"),
            "official completion is distinct from continuing physiology recording");
        ExperimentSessionPanelState completionPending = state(
            ExperimentSessionPanelState.Phase.RECORDING,
            true,
            ExperimentSessionPanelState.Completion.PERSISTENCE_PENDING,
            false
        );
        check(ExperimentSessionPanelViewPolicy.stageTitle(completionPending)
                .contains("confirming completion"),
            "elapsed condition time is not called complete before persistence");
        ExperimentSessionPanelState saving = state(ExperimentSessionPanelState.Phase.SAVING, false, false);
        check(ExperimentSessionPanelViewPolicy.stageInstruction(saving).contains("finish saving"),
            "saving instructs operator to wait");
        check(!ExperimentSessionPanelViewPolicy.project(saving).startEnabled,
            "saving cannot arm another run");
        ExperimentSessionPanelState arming = state(ExperimentSessionPanelState.Phase.ARMING, true, false);
        check(!ExperimentSessionPanelViewPolicy.canReturnToImmersive(arming),
            "operator cannot leave before audio preparation is acknowledged");
        ExperimentSessionPanelState armed = state(ExperimentSessionPanelState.Phase.ARMED, true, false);
        check(ExperimentSessionPanelViewPolicy.canReturnToImmersive(armed),
            "armed run can return to the particle scene");
        ExperimentSessionPanelState audioHold = state(ExperimentSessionPanelState.Phase.ERROR, true, false);
        check(ExperimentSessionPanelViewPolicy.canReturnToImmersive(audioHold)
                && ExperimentSessionPanelViewPolicy.stageInstruction(audioHold).contains("Do not use resume"),
            "technical hold permits only the explicit finish path");
        ExperimentSessionPanelState armFailure = new ExperimentSessionPanelState(
            ExperimentSessionPanelState.Route.EXPERIMENTER,
            ExperimentSessionPanelState.Phase.ERROR,
            0L,
            2L,
            "condition-a",
            "",
            0L,
            ExperimentSessionPanelState.Counts.unknown(),
            ExperimentSessionPanelState.PolarProjection.unknown(),
            false,
            false,
            "ready",
            true,
            "Condition was not armed: packaged profile mismatch."
        );
        check(ExperimentSessionPanelViewPolicy.stageTitle(armFailure).contains("not armed")
                && ExperimentSessionPanelViewPolicy.stageInstruction(armFailure).contains("Choose condition")
                && ExperimentSessionPanelViewPolicy.project(armFailure).statusLine.contains("packaged profile mismatch")
                && ExperimentSessionPanelViewPolicy.project(armFailure).startEnabled,
            "arm admission failure stays visible, distinct from technical hold, and retryable");
        ExperimentSessionPanelState recovery = state(ExperimentSessionPanelState.Phase.RECOVERY, false, true);
        check(ExperimentSessionPanelViewPolicy.storageTone(recovery) == ExperimentSessionPanelViewPolicy.Tone.ATTENTION,
            "recovery overrides superficially ready storage");
        ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
        coordinator.updatePolar(new ExperimentSessionPanelState.PolarProjection(
            ExperimentSessionPanelState.Bluetooth.ON, ExperimentSessionPanelState.Polar.CONNECTED,
            1, 1L, 1L, 0L, false, "stale"));
        check(ExperimentSessionPanelViewPolicy.polarTone(coordinator.snapshot()) != ExperimentSessionPanelViewPolicy.Tone.READY,
            "connected but stale is not ready");
    }

    private static void conciseReadinessCards() {
        ExperimentSessionPanelViewPolicy.ReadinessCard kioskReady =
            ExperimentSessionPanelViewPolicy.kioskCard("ready");
        check(kioskReady.tone == ExperimentSessionPanelViewPolicy.Tone.READY
                && kioskReady.label.contains("In-app session guard ready")
                && kioskReady.detail.isEmpty() && !kioskReady.showAction,
            "ready kiosk is one concise visual status without a setup action");
        ExperimentSessionPanelViewPolicy.ReadinessCard kioskPermission =
            ExperimentSessionPanelViewPolicy.kioskCard("permission-required");
        check(kioskPermission.tone == ExperimentSessionPanelViewPolicy.Tone.ATTENTION
                && !kioskPermission.showAction
                && kioskPermission.detail.contains("Restart the app"),
            "legacy permission state never exposes an irrelevant special-access action");
        ExperimentSessionPanelViewPolicy.ReadinessCard storageReady =
            ExperimentSessionPanelViewPolicy.storageCard(
                state(ExperimentSessionPanelState.Phase.IDLE, false, false));
        check(storageReady.tone == ExperimentSessionPanelViewPolicy.Tone.READY
                && storageReady.label.contains("Recording ready")
                && storageReady.detail.isEmpty(),
            "ready recording storage is a concise positive status");
        ExperimentSessionPanelState storageError = new ExperimentSessionPanelState(
            ExperimentSessionPanelState.Route.EXPERIMENTER,
            ExperimentSessionPanelState.Phase.IDLE,
            0L,
            0L,
            "none",
            "",
            0L,
            ExperimentSessionPanelState.Counts.unknown(),
            ExperimentSessionPanelState.PolarProjection.unknown(),
            false,
            false,
            "error",
            true,
            "recording-root-error"
        );
        check(ExperimentSessionPanelViewPolicy.storageCard(storageError).tone
                == ExperimentSessionPanelViewPolicy.Tone.ATTENTION,
            "a genuine storage fault remains visible and is never painted ready");
    }

    private static ExperimentSessionPanelState state(ExperimentSessionPanelState.Phase phase,
            boolean recording, boolean recovery) {
        return state(phase, recording, ExperimentSessionPanelState.Completion.UNKNOWN, recovery);
    }

    private static ExperimentSessionPanelState state(
            ExperimentSessionPanelState.Phase phase,
            boolean recording,
            ExperimentSessionPanelState.Completion completion,
            boolean recovery) {
        return new ExperimentSessionPanelState(ExperimentSessionPanelState.Route.EXPERIMENTER,
            phase, 1L, 1L, "condition-a", "", 0L, ExperimentSessionPanelState.Counts.unknown(),
            ExperimentSessionPanelState.PolarProjection.unknown(), recording, completion, 30_000L,
            recovery, "ready", true, "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
