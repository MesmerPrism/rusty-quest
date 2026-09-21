package io.github.mesmerprism.rustyquest.native_renderer;

public final class NativeRendererExperimentShellPolicyTest {
    public static void main(String[] args) {
        exactAllowedComponentsAndGenerationAreRequired();
        tripleHomeRequiresDistinctRecoveredEpisodes();
        repeatedTimestampRecoveryTailsAndOldGenerationsReject();
        staleHeldAndUnrelatedEventsCannotExit();
        terminalExitCancelsEveryRecoveryPath();
        writerAcknowledgementPrecedesFinish();
        recreationAndTerminalExitInvalidateHandoffLaunches();
        System.out.println("NativeRendererExperimentShellPolicyTest PASS");
    }

    private static void exactAllowedComponentsAndGenerationAreRequired() {
        NativeRendererForegroundGuardPolicy policy = new NativeRendererForegroundGuardPolicy();
        policy.arm(7L, NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE);
        require(NativeRendererForegroundGuardPolicy.isAllowedComponent("android.app.NativeActivity"));
        require(NativeRendererForegroundGuardPolicy.isAllowedComponent(
            "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity"));
        reject(NativeRendererForegroundGuardPolicy.isAllowedComponent(
            "io.github.mesmerprism.rustyquest.native_renderer.QuestionnairePanelActivity"));
        reject(policy.observeAllowedComponent("android.app.NativeActivity", 6L, 10L));
        require(policy.observeAllowedComponent("android.app.NativeActivity", 7L, 11L));
        require(policy.beginTransition(8L, NativeRendererForegroundGuardPolicy.Presentation.PANEL));
        reject(policy.beginTransition(8L, NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE));
        reject(policy.observeAllowedComponent("android.app.NativeActivity", 8L, 12L));
        require(policy.observeAllowedComponent(
            "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity", 8L, 13L));
    }

    private static void tripleHomeRequiresDistinctRecoveredEpisodes() {
        NativeRendererForegroundGuardPolicy policy = new NativeRendererForegroundGuardPolicy();
        policy.arm(1L, NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE);
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.observeDisallowedForeground(true, 1L, 101L, 1_000L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.observeDisallowedForeground(true, 1L, 101L, 1_020L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.claimRecovery(1L));
        require(policy.observeAllowedComponent("android.app.NativeActivity", 1L, 1_100L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.observeDisallowedForeground(true, 1L, 102L, 1_400L));
        require(policy.observeAllowedComponent("android.app.NativeActivity", 1L, 1_500L));
        equal(NativeRendererForegroundGuardPolicy.Decision.BEGIN_TERMINAL_EXIT,
            policy.observeDisallowedForeground(true, 1L, 103L, 1_800L));
    }

    private static void repeatedTimestampRecoveryTailsAndOldGenerationsReject() {
        NativeRendererForegroundGuardPolicy policy = new NativeRendererForegroundGuardPolicy();
        policy.arm(9L, NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE);
        equal(NativeRendererForegroundGuardPolicy.Decision.NONE,
            policy.observeDisallowedForeground(true, 8L, 200L, 900L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.observeDisallowedForeground(true, 9L, 201L, 900L));
        reject(policy.observeAllowedComponent("android.app.NativeActivity", 9L, 900L));
        equal(NativeRendererForegroundGuardPolicy.Decision.NONE,
            policy.observeDisallowedForeground(true, 9L, 202L, 900L));
        require(policy.observeAllowedComponent("android.app.NativeActivity", 9L, 1_000L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.observeDisallowedForeground(true, 9L, 201L, 1_100L));
        require(policy.observeAllowedComponent("android.app.NativeActivity", 9L, 1_200L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
            policy.observeDisallowedForeground(true, 9L, 202L, 1_300L));
        reject(policy.isTerminalExit());
    }

    private static void staleHeldAndUnrelatedEventsCannotExit() {
        NativeRendererForegroundGuardPolicy policy = new NativeRendererForegroundGuardPolicy();
        policy.arm(2L, NativeRendererForegroundGuardPolicy.Presentation.PANEL);
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL,
            policy.observeDisallowedForeground(false, 2L, 0L, 2_000L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL,
            policy.observeDisallowedForeground(true, 2L, 301L, 2_100L));
        equal(NativeRendererForegroundGuardPolicy.Decision.NONE,
            policy.observeDisallowedForeground(true, 2L, 302L, 2_050L));
        require(policy.observeAllowedComponent(
            "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity", 2L, 2_200L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL,
            policy.observeDisallowedForeground(true, 2L, 302L, 8_000L));
        require(policy.observeAllowedComponent(
            "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity", 2L, 8_100L));
        equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL,
            policy.observeDisallowedForeground(true, 2L, 303L, 8_400L));
        reject(policy.isTerminalExit());
    }

    private static void terminalExitCancelsEveryRecoveryPath() {
        NativeRendererForegroundGuardPolicy policy = new NativeRendererForegroundGuardPolicy();
        policy.arm(3L, NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE);
        for (int index = 0; index < 2; index += 1) {
            equal(NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE,
                policy.observeDisallowedForeground(
                    true, 3L, 400L + index, 100L + index * 200L));
            require(policy.observeAllowedComponent(
                "android.app.NativeActivity", 3L, 200L + index * 200L));
        }
        equal(NativeRendererForegroundGuardPolicy.Decision.BEGIN_TERMINAL_EXIT,
            policy.observeDisallowedForeground(true, 3L, 402L, 500L));
        reject(policy.isArmed());
        require(policy.isTerminalExit());
        reject(policy.isRecoveryPending());
        equal(NativeRendererForegroundGuardPolicy.Decision.NONE, policy.claimRecovery(3L));
        equal(NativeRendererForegroundGuardPolicy.Decision.NONE,
            policy.observeDisallowedForeground(false, 3L, 0L, 600L));
        reject(policy.beginTransition(4L, NativeRendererForegroundGuardPolicy.Presentation.PANEL));
    }

    private static void writerAcknowledgementPrecedesFinish() {
        NativeRendererWriterAcknowledgedExitPolicy policy =
            new NativeRendererWriterAcknowledgedExitPolicy();
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.NONE,
            policy.requestFinish(12L, "stop-12"));
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.REQUEST_WRITER_STOP,
            policy.beginExit(12L, "stop-12"));
        require(policy.isGuardDisarmed());
        reject(policy.isRecoveryAllowed());
        equal(12L, policy.sessionGeneration());
        equal("stop-12", policy.stopOperationId());
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.NONE,
            policy.requestFinish(11L, "stop-12"));
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.NONE,
            policy.requestFinish(12L, "stop-other"));
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.WAIT_FOR_WRITER_ACK,
            policy.requestFinish(12L, "stop-12"));
        reject(policy.acknowledgeWriter(11L, "stop-12", "wrong-session", true));
        reject(policy.acknowledgeWriter(12L, "stop-other", "wrong-operation", true));
        reject(policy.acknowledgeWriter(12L, "stop-12", "", true));
        require(policy.acknowledgeWriter(12L, "stop-12", "writer-receipt-1", false));
        reject(policy.acknowledgeWriter(12L, "stop-12", "replayed-receipt", true));
        require(policy.isWriterAcknowledged());
        equal("writer-receipt-1", policy.writerReceiptId());
        reject(policy.writerCompletedCleanly());
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.FINISH_AND_REMOVE_TASK,
            policy.requestFinish(12L, "stop-12"));
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.NONE,
            policy.requestFinish(12L, "stop-12"));
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.NONE,
            policy.beginExit(12L, "stop-12"));

        NativeRendererWriterAcknowledgedExitPolicy idleExit =
            new NativeRendererWriterAcknowledgedExitPolicy();
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.REQUEST_WRITER_STOP,
            idleExit.beginExit(0L, "idle-exit"));
        require(idleExit.acknowledgeWriter(0L, "idle-exit", "idle-shutdown-ack", true));
        equal(NativeRendererWriterAcknowledgedExitPolicy.Action.FINISH_AND_REMOVE_TASK,
            idleExit.requestFinish(0L, "idle-exit"));

        NativeRendererWriterAcknowledgedExitPolicy invalid =
            new NativeRendererWriterAcknowledgedExitPolicy();
        try {
            invalid.beginExit(-1L, "negative-generation");
            throw new AssertionError("negative generation was accepted");
        } catch (IllegalArgumentException expected) {
            // Exact idle generation zero is valid; negative generations are not.
        }
    }

    private static void recreationAndTerminalExitInvalidateHandoffLaunches() {
        PanelImmersiveHandoffLifecyclePolicy initialLaunch =
            new PanelImmersiveHandoffLifecyclePolicy();
        PanelImmersiveHandoffLifecyclePolicy.Registration initialRegistration =
            initialLaunch.register(11L);
        require(initialRegistration.admitted);
        PanelImmersiveHandoffLifecyclePolicy.Registration initialExplicitLaunch =
            initialLaunch.admitExplicitLaunchEpoch(11L);
        require(initialExplicitLaunch.admitted);
        require(initialExplicitLaunch.generation > initialRegistration.generation);
        reject(initialLaunch.admitExplicitLaunchEpoch(11L).admitted);
        long initialRequest = initialLaunch.beginRequest(11L);
        require(initialLaunch.canLaunch(11L, initialRequest));

        PanelImmersiveHandoffLifecyclePolicy lifecycle =
            new PanelImmersiveHandoffLifecyclePolicy();
        PanelImmersiveHandoffLifecyclePolicy.Registration first = lifecycle.register(1L);
        require(first.admitted);
        long firstRequest = lifecycle.beginRequest(1L);
        require(lifecycle.canLaunch(1L, firstRequest));
        PanelImmersiveHandoffLifecyclePolicy.Registration replacement = lifecycle.register(2L);
        require(replacement.admitted);
        equal(1L, replacement.replacedOwnerToken);
        reject(lifecycle.canLaunch(1L, firstRequest));
        long replacementRequest = lifecycle.beginRequest(2L);
        require(lifecycle.canLaunch(2L, replacementRequest));
        require(lifecycle.hasOwner());
        long terminalGeneration = lifecycle.beginTerminalExit();
        require(terminalGeneration > replacementRequest);
        require(lifecycle.isTerminalExit());
        reject(lifecycle.hasOwner());
        reject(lifecycle.canLaunch(2L, replacementRequest));
        equal(-1L, lifecycle.beginRequest(2L));
        reject(lifecycle.release(2L));

        PanelImmersiveHandoffLifecyclePolicy.Registration cachedProcessRecreation =
            lifecycle.register(3L);
        reject(cachedProcessRecreation.admitted);
        require(lifecycle.isTerminalExit());
        require(lifecycle.hasPendingExplicitLaunch());
        equal(-1L, lifecycle.beginRequest(3L));
        reject(lifecycle.canLaunch(3L, cachedProcessRecreation.generation));
        reject(lifecycle.admitExplicitLaunchEpoch(2L).admitted);

        PanelImmersiveHandoffLifecyclePolicy.Registration explicitRelaunch =
            lifecycle.admitExplicitLaunchEpoch(3L);
        require(explicitRelaunch.admitted);
        reject(lifecycle.isTerminalExit());
        reject(lifecycle.hasPendingExplicitLaunch());
        long explicitRelaunchRequest = lifecycle.beginRequest(3L);
        require(lifecycle.canLaunch(3L, explicitRelaunchRequest));
        reject(lifecycle.canLaunch(2L, replacementRequest));
        require(lifecycle.release(3L));
        reject(lifecycle.hasOwner());

        PanelImmersiveHandoffLifecyclePolicy ordinaryCleanup =
            new PanelImmersiveHandoffLifecyclePolicy();
        ordinaryCleanup.register(9L);
        require(ordinaryCleanup.hasOwner());
        require(ordinaryCleanup.release(9L));
        reject(ordinaryCleanup.hasOwner());
    }

    private static void require(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void reject(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
