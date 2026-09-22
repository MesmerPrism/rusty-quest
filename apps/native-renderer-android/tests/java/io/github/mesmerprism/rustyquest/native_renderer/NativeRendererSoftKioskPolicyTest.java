package io.github.mesmerprism.rustyquest.native_renderer;

public final class NativeRendererSoftKioskPolicyTest {
    private static final String APP = "io.github.mesmerprism.rustyquest.native_renderer.test";
    private static final String HOME_PACKAGE = "com.meta.home";
    private static final String HOME_CLASS = "com.meta.home.HomeActivity";

    public static void main(String[] args) {
        oneTwoAndThreeHomeEpisodesRecoverThenExit();
        spacedHomeEpisodesReachTripleWhenRecoveryNeverSucceeds();
        duplicateAndTailEventsDoNotCreateEpisodes();
        staleGenerationAndExternalComponentSpoofReject();
        allowedPromptAndBoundedTransitionSuppressOnlyTheirExactCases();
        promptAndTransitionDeadlinesRecoverWithoutNewEvents();
        promptImmediatelyInvalidatesPendingRecovery();
        timingCallbacksReplaceOldGenerationAndCancelRecovery();
        recoveryTimerPreservesOriginalDeadlineUnderContinuousNoise();
        promptAndTransitionSuppressOnlyTheBoundSurface();
        externalApplicationRecoversDesiredSurface();
        recoveryAttemptsAreBoundedPerEpisode();
        terminalExitCancelsRecoveryAndCannotRelaunch();
        missingInterruptedAndRevokedServicesAreNotEffective();
        laterExplicitColdLaunchEpochRearmsButStaleEpochDoesNot();
        terminalIntentAdmissionIsExact();
        launchAuthorityIsOneShotAndRejectsSpoofReplayAndRecreation();
        launcherAdmissionRejectsRecreationAndIntentAnomalies();
        selfWatchdogDoesNotNeedHomeResolutionOrAccessibility();
        selfWatchdogAllowsBothOwnedSurfacesAndRecoversAppSelectedTarget();
        selfDepartureNeedsRecoveryAndIgnoresFocusNoiseAndPrompts();
        selfPromptAndExplicitExitCancelPendingReturn();
        explicitExitCreatesItsOwnAdmissionBeforeKioskArm();
        immersiveOwnerDestructionDisarmsRecoveryAndAllowsFreshLaunch();
        System.out.println("NativeRendererSoftKioskPolicyTest PASS");
    }

    private static void oneTwoAndThreeHomeEpisodesRecoverThenExit() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            10L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        observeAllowed(coordinator, 10L, 100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);

        NativeRendererSoftKioskCoordinator.Action first =
            observeHome(coordinator, 10L, 1L, 1_000L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE, first.kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            coordinator.claimRecovery(10L, first.recoveryEpisodeId, 1_010L).kind);
        observeAllowed(coordinator, 10L, 1_100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);

        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            observeHome(coordinator, 10L, 2L, 2_000L).kind);
        observeAllowed(coordinator, 10L, 2_100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);

        NativeRendererSoftKioskCoordinator.Action terminal =
            observeHome(coordinator, 10L, 3L, 3_000L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT, terminal.kind);
        reject(coordinator.snapshot().armed);
        require(coordinator.snapshot().terminal);
    }

    private static void spacedHomeEpisodesReachTripleWhenRecoveryNeverSucceeds() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            11L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            observeHome(coordinator, 11L, 1L, 1_000L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            observeHome(coordinator, 11L, 2L, 1_300L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT,
            observeHome(coordinator, 11L, 3L, 1_600L).kind);

        NativeRendererHomeEpisodePolicy classifier = new NativeRendererHomeEpisodePolicy();
        classifier.reset(11L,
            new NativeRendererHomeEpisodePolicy.HomeSurface(HOME_PACKAGE, HOME_CLASS));
        equal(NativeRendererHomeEpisodePolicy.Kind.NEW_EPISODE,
            classifier.observe(11L, HOME_PACKAGE, HOME_CLASS, 1_000L).kind);
        equal(NativeRendererHomeEpisodePolicy.Kind.REPEATED_EPISODE,
            classifier.observe(11L, HOME_PACKAGE, HOME_CLASS, 1_020L).kind);
        equal(NativeRendererHomeEpisodePolicy.Kind.NEW_EPISODE,
            classifier.observe(11L, HOME_PACKAGE, HOME_CLASS, 1_300L).kind);
        equal(NativeRendererHomeEpisodePolicy.Kind.NEW_EPISODE,
            classifier.observe(11L, HOME_PACKAGE, HOME_CLASS, 1_600L).kind);
    }

    private static void duplicateAndTailEventsDoNotCreateEpisodes() {
        NativeRendererHomeEpisodePolicy classifier = new NativeRendererHomeEpisodePolicy();
        classifier.reset(7L,
            new NativeRendererHomeEpisodePolicy.HomeSurface(HOME_PACKAGE, HOME_CLASS));
        require(classifier.observeAllowedTarget(7L, 100L));
        NativeRendererHomeEpisodePolicy.Observation first =
            classifier.observe(7L, HOME_PACKAGE, HOME_CLASS, 400L);
        equal(NativeRendererHomeEpisodePolicy.Kind.NEW_EPISODE, first.kind);
        equal(1L, first.episodeId);
        NativeRendererHomeEpisodePolicy.Observation duplicate =
            classifier.observe(7L, HOME_PACKAGE, HOME_CLASS, 410L);
        equal(NativeRendererHomeEpisodePolicy.Kind.REPEATED_EPISODE, duplicate.kind);
        equal(1L, duplicate.episodeId);
        require(classifier.observeAllowedTarget(7L, 500L));
        equal(NativeRendererHomeEpisodePolicy.Kind.CONFIRMED_TARGET_TAIL,
            classifier.observe(7L, HOME_PACKAGE, HOME_CLASS, 600L).kind);
        NativeRendererHomeEpisodePolicy.Observation second =
            classifier.observe(7L, HOME_PACKAGE, HOME_CLASS, 700L);
        equal(NativeRendererHomeEpisodePolicy.Kind.NEW_EPISODE, second.kind);
        equal(2L, second.episodeId);
        equal(NativeRendererHomeEpisodePolicy.Kind.STALE,
            classifier.observe(6L, HOME_PACKAGE, HOME_CLASS, 800L).kind);
    }

    private static void staleGenerationAndExternalComponentSpoofReject() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            20L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            coordinator.observeForeground(
                APP,
                NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
                true,
                false,
                0L,
                19L,
                100L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL,
            coordinator.observeForeground(
                "com.example.spoof",
                NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
                false,
                false,
                0L,
                20L,
                200L).kind);
    }

    private static void allowedPromptAndBoundedTransitionSuppressOnlyTheirExactCases() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            30L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        require(coordinator.allowExactSystemPrompt(
            30L, "com.android.settings", "com.android.settings.BluetoothSettings",
            100L, 1_000L));
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_ALLOWED_PROMPT,
            coordinator.observeForeground(
                "com.android.settings", "com.android.settings.BluetoothSettings",
                false, false, 0L, 30L, 200L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            coordinator.observeForeground(
                "com.example.other", "com.android.settings.BluetoothSettings",
                false, false, 0L, 30L, 300L).kind);

        observeAllowed(coordinator, 30L, 400L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);
        require(coordinator.beginTransition(
            31L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER,
            500L,
            1_000L));
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_TRANSITION,
            coordinator.observeForeground(
                APP,
                NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY,
                true, false, 0L, 31L, 600L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_TRANSITION,
            coordinator.observeForeground(
                "com.example.external", "com.example.ExternalActivity",
                false, false, 0L, 31L, 700L).kind);
        // Physical Home remains authoritative during a transition grace.
        NativeRendererSoftKioskCoordinator.Action homeDuringTransition =
            observeHome(coordinator, 31L, 1L, 800L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL,
            homeDuringTransition.kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL,
            coordinator.claimRecovery(
                31L, homeDuringTransition.recoveryEpisodeId, 900L).kind);
    }

    private static void promptAndTransitionDeadlinesRecoverWithoutNewEvents() {
        NativeRendererSoftKioskCoordinator prompt = ready(
            32L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        TimingTrace promptTiming = new TimingTrace();
        prompt.setTimingObserver(promptTiming);
        require(prompt.allowExactSystemPrompt(
            32L, "com.android.settings", "com.android.settings.BluetoothSettings",
            100L, 500L));
        require(promptTiming.cancelRecovery);
        equal(600L, promptTiming.deadlineMs);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_ALLOWED_PROMPT,
            prompt.observeForeground(
                "com.android.settings", "com.android.settings.BluetoothSettings",
                false, false, 0L, 32L, 200L).kind);
        NativeRendererSoftKioskCoordinator.Action promptExpiry =
            prompt.evaluateDeadline(32L, 601L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            promptExpiry.kind);
        require(promptExpiry.deadlineExpired);

        NativeRendererSoftKioskCoordinator transition = ready(
            33L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        require(transition.beginTransition(
            34L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER,
            1_000L,
            500L));
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_TRANSITION,
            transition.observeForeground(
                "com.example.external", "com.example.ExternalActivity",
                false, false, 0L, 34L, 1_100L).kind);
        NativeRendererSoftKioskCoordinator.Action transitionExpiry =
            transition.evaluateDeadline(34L, 1_501L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL,
            transitionExpiry.kind);
        require(transitionExpiry.deadlineExpired);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            transition.evaluateDeadline(33L, 1_600L).kind);
    }

    private static void promptImmediatelyInvalidatesPendingRecovery() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            35L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        NativeRendererSoftKioskCoordinator.Action pending = coordinator.observeForeground(
            "com.example.external", "com.example.ExternalActivity",
            false, false, 0L, 35L, 100L);
        TimingTrace timing = new TimingTrace();
        coordinator.setTimingObserver(timing);
        require(coordinator.allowExactSystemPrompt(
            35L, "com.android.settings", "com.android.settings.BluetoothSettings",
            110L, 500L));
        require(timing.cancelRecovery);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            coordinator.claimRecovery(
                35L, pending.recoveryEpisodeId, 120L).kind);
    }

    private static void timingCallbacksReplaceOldGenerationAndCancelRecovery() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            36L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        TimingTrace timing = new TimingTrace();
        coordinator.setTimingObserver(timing);
        require(coordinator.allowExactSystemPrompt(
            36L, "com.android.settings", "com.android.settings.BluetoothSettings",
            100L, 900L));
        equal(36L, timing.generation);
        equal(1_000L, timing.deadlineMs);
        require(coordinator.beginTransition(
            37L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER,
            200L,
            300L));
        equal(37L, timing.generation);
        equal(500L, timing.deadlineMs);
        require(timing.cancelRecovery);
        require(coordinator.armFromExplicitColdLaunch(
            38L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER));
        equal(38L, timing.generation);
        equal(Long.MIN_VALUE, timing.deadlineMs);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            coordinator.evaluateDeadline(36L, 1_001L).kind);
    }

    private static void recoveryTimerPreservesOriginalDeadlineUnderContinuousNoise() {
        NativeRendererSoftKioskRecoveryTimerGate gate =
            new NativeRendererSoftKioskRecoveryTimerGate();
        equal(NativeRendererSoftKioskRecoveryTimerGate.Offer.NEW,
            gate.offer(90L, 7L, 1_000L, 300L));
        equal(1_300L, gate.deadlineMs());
        equal(NativeRendererSoftKioskRecoveryTimerGate.Offer.UNCHANGED,
            gate.offer(90L, 7L, 1_100L, 300L));
        equal(NativeRendererSoftKioskRecoveryTimerGate.Offer.UNCHANGED,
            gate.offer(90L, 7L, 1_200L, 300L));
        equal(1_300L, gate.deadlineMs());
        equal(NativeRendererSoftKioskRecoveryTimerGate.Offer.REPLACED,
            gate.offer(90L, 8L, 1_250L, 300L));
        equal(1_550L, gate.deadlineMs());
        reject(gate.consume(90L, 7L));
        require(gate.consume(90L, 8L));
        equal(Long.MIN_VALUE, gate.deadlineMs());
    }

    private static void promptAndTransitionSuppressOnlyTheBoundSurface() {
        NativeRendererSoftKioskCoordinator prompt = ready(
            91L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        require(prompt.allowExactSystemPrompt(
            91L, "com.android.settings", "com.android.settings.BluetoothSettings",
            100L, 1_000L));
        NativeRendererSoftKioskCoordinator.Action unrelated = prompt.observeForeground(
            "com.example.unrelated", "com.example.UnrelatedActivity",
            false, false, 0L, 91L, 200L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            unrelated.kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            prompt.claimRecovery(91L, unrelated.recoveryEpisodeId, 250L).kind);

        NativeRendererSoftKioskCoordinator.Action home =
            observeHome(prompt, 91L, 1L, 300L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE, home.kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            prompt.claimRecovery(91L, home.recoveryEpisodeId, 350L).kind);

        NativeRendererSoftKioskCoordinator exactPrompt = ready(
            92L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        require(exactPrompt.allowExactSystemPrompt(
            92L, "com.android.settings", "com.android.settings.BluetoothSettings",
            100L, 1_000L));
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_ALLOWED_PROMPT,
            exactPrompt.observeForeground(
                "com.android.settings", "com.android.settings.BluetoothSettings",
                false, false, 0L, 92L, 200L).kind);
    }

    private static void externalApplicationRecoversDesiredSurface() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            40L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER);
        NativeRendererSoftKioskCoordinator.Action action = coordinator.observeForeground(
            "com.example.external", "com.example.ExternalActivity",
            false, false, 0L, 40L, 100L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL, action.kind);
        equal(NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER, action.panelRoute);
    }

    private static void recoveryAttemptsAreBoundedPerEpisode() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            41L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        long episode = 0L;
        String[] packages = {
            "com.example.external.one",
            "com.example.external.two",
            "com.example.external.three"
        };
        String[] classes = {
            "com.example.ExternalOne",
            "com.example.ExternalTwo",
            "com.example.ExternalThree"
        };
        for (int attempt = 1; attempt <= 3; attempt += 1) {
            NativeRendererSoftKioskCoordinator.Action observed = coordinator.observeForeground(
                packages[attempt - 1], classes[attempt - 1],
                false, false, 0L, 41L, 100L + attempt * 100L);
            if (episode == 0L) {
                episode = observed.recoveryEpisodeId;
            } else {
                equal(episode, observed.recoveryEpisodeId);
            }
            NativeRendererSoftKioskCoordinator.Action claimed = coordinator.claimRecovery(
                41L, episode, 150L + attempt * 100L);
            equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE, claimed.kind);
            equal(attempt, claimed.recoveryAttempt);
        }
        NativeRendererSoftKioskCoordinator.Action exhausted = coordinator.observeForeground(
            "com.example.external.four", "com.example.ExternalFour",
            false, false, 0L, 41L, 600L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVERY_EXHAUSTED, exhausted.kind);
        equal(episode, exhausted.recoveryEpisodeId);
        require(coordinator.snapshot().recoveryExhausted);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVERY_EXHAUSTED,
            coordinator.claimRecovery(41L, episode, 700L).kind);
    }

    private static void terminalExitCancelsRecoveryAndCannotRelaunch() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            50L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        for (int index = 0; index < 2; index += 1) {
            observeHome(coordinator, 50L, index + 1L, 1_000L + index * 1_000L);
            observeAllowed(coordinator, 50L, 1_100L + index * 1_000L,
                NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);
        }
        equal(NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT,
            observeHome(coordinator, 50L, 3L, 3_000L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            coordinator.claimRecovery(50L, 1L, 3_010L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NOT_EFFECTIVE,
            coordinator.observeForeground(
                "com.example.external", "com.example.ExternalActivity",
                false, false, 0L, 50L, 3_100L).kind);
        reject(coordinator.beginTransition(
            51L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER,
            3_200L,
            1_000L));
    }

    private static void missingInterruptedAndRevokedServicesAreNotEffective() {
        NativeRendererSoftKioskCoordinator coordinator = new NativeRendererSoftKioskCoordinator();
        require(coordinator.armFromExplicitColdLaunch(
            60L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER));
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.NEEDS_ACCESSIBILITY_SETUP,
            coordinator.snapshot().effectiveness);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NOT_EFFECTIVE,
            observeHome(coordinator, 60L, 1L, 100L).kind);
        coordinator.updateServiceState(
            NativeRendererSoftKioskCoordinator.ServiceState.INTERRUPTED);
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.INTERRUPTED,
            coordinator.snapshot().effectiveness);
        coordinator.updateServiceState(
            NativeRendererSoftKioskCoordinator.ServiceState.REVOKED);
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.REVOKED,
            coordinator.snapshot().effectiveness);

        NativeRendererSoftKioskCoordinator unresolved = new NativeRendererSoftKioskCoordinator();
        unresolved.updateServiceState(NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
        require(unresolved.armFromExplicitColdLaunch(
            61L, NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER));
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.DEGRADED_HOME_RESOLVING,
            unresolved.snapshot().effectiveness);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NOT_EFFECTIVE,
            observeHome(unresolved, 61L, 1L, 200L).kind);
        unresolved.updateHomeSurfaceState(
            NativeRendererSoftKioskCoordinator.HomeSurfaceState.UNAVAILABLE);
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.UNAVAILABLE_HOME_SURFACE,
            unresolved.snapshot().effectiveness);
    }

    private static void laterExplicitColdLaunchEpochRearmsButStaleEpochDoesNot() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            70L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        observeHome(coordinator, 70L, 1L, 1_000L);
        observeAllowed(coordinator, 70L, 1_100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);
        observeHome(coordinator, 70L, 2L, 2_000L);
        observeAllowed(coordinator, 70L, 2_100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);
        observeHome(coordinator, 70L, 3L, 3_000L);
        reject(coordinator.armFromExplicitColdLaunch(
            70L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER));
        require(coordinator.armFromExplicitColdLaunch(
            71L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_DEVELOPER));
        reject(coordinator.snapshot().terminal);
        require(coordinator.snapshot().armed);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL,
            coordinator.observeForeground(
                "com.example.external", "com.example.ExternalActivity",
                false, false, 0L, 71L, 4_000L).kind);
    }

    private static void terminalIntentAdmissionIsExact() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            80L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        observeHome(coordinator, 80L, 1L, 1_000L);
        observeAllowed(coordinator, 80L, 1_100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);
        observeHome(coordinator, 80L, 2L, 2_000L);
        observeAllowed(coordinator, 80L, 2_100L,
            NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY);
        observeHome(coordinator, 80L, 3L, 3_000L);
        require(coordinator.admitsTerminalIntent(
            NativeRendererSoftKioskCoordinator.ACTION_TERMINAL_SAVE_AND_EXIT,
            NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT,
            80L,
            3L));
        reject(coordinator.admitsTerminalIntent(
            NativeRendererSoftKioskCoordinator.ACTION_TERMINAL_SAVE_AND_EXIT,
            NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT,
            79L,
            3L));
        reject(coordinator.admitsTerminalIntent(
            IntentNames.FORGED_ACTION,
            NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT,
            80L,
            3L));
    }

    private static void launchAuthorityIsOneShotAndRejectsSpoofReplayAndRecreation() {
        NativeRendererExperimentLaunchAuthority.invalidatePending();
        equal(0L, NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(null, false));
        long epoch = issueAdmittedLaunchForTest(9_000L);
        equal(9_000L, epoch);
        reject(NativeRendererExperimentLaunchAuthority.consume(null, epoch));
        reject(NativeRendererExperimentLaunchAuthority.consume("internal-main", epoch));
        reject(NativeRendererExperimentLaunchAuthority.consume(
            NativeRendererExperimentLaunchAuthority.PROVENANCE_EXPLICIT_USER_LAUNCH,
            epoch + 1L));
        require(NativeRendererExperimentLaunchAuthority.consume(
            NativeRendererExperimentLaunchAuthority.PROVENANCE_EXPLICIT_USER_LAUNCH,
            epoch));
        // Saved-state recreation and replay see the same extras but no pending one-shot.
        reject(NativeRendererExperimentLaunchAuthority.consume(
            NativeRendererExperimentLaunchAuthority.PROVENANCE_EXPLICIT_USER_LAUNCH,
            epoch));
        long later = issueAdmittedLaunchForTest(8_000L);
        require(later > epoch);
        NativeRendererExperimentLaunchAuthority.invalidatePending();
        reject(NativeRendererExperimentLaunchAuthority.consume(
            NativeRendererExperimentLaunchAuthority.PROVENANCE_EXPLICIT_USER_LAUNCH,
            later));
    }

    private static void launcherAdmissionRejectsRecreationAndIntentAnomalies() {
        String launcher = "android.app.NativeActivity";
        require(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.MAIN", true, 1, false,
            APP, launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            true, "android.intent.action.MAIN", true, 1, false,
            APP, launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.VIEW", true, 1, false,
            APP, launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.MAIN", false, 0, false,
            APP, launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.MAIN", true, 2, false,
            APP, launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.MAIN", true, 1, true,
            APP, launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.MAIN", true, 1, false,
            "com.example.spoof", launcher, APP, launcher));
        reject(NativeRendererExperimentLauncherPolicy.admits(
            false, "android.intent.action.MAIN", true, 1, false,
            APP, "com.example.SpoofLauncher", APP, launcher));
    }

    private static long issueAdmittedLaunchForTest(long candidate) {
        try {
            java.lang.reflect.Method method = NativeRendererExperimentLaunchAuthority.class
                .getDeclaredMethod("issueAdmitted", long.class);
            method.setAccessible(true);
            return ((Long) method.invoke(null, Long.valueOf(candidate))).longValue();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not exercise private admitted-launch issuer", error);
        }
    }

    private static void selfWatchdogDoesNotNeedHomeResolutionOrAccessibility() {
        NativeRendererSoftKioskCoordinator c = new NativeRendererSoftKioskCoordinator();
        c.useSelfWatchdog();
        require(c.armFromExplicitColdLaunch(100L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE, "experimenter"));
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.WATCHDOG_STARTING, c.snapshot().effectiveness);
        c.updateServiceState(NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
        equal(NativeRendererSoftKioskCoordinator.Effectiveness.READY_ARMED, c.snapshot().effectiveness);
        c.observeOwnSurface(NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY, 100L, 10L);
        for (int i = 1; i <= 3; i++) {
            NativeRendererSoftKioskCoordinator.Action a = c.observeSelfDeparture(100L, i, i * 1_000L);
            equal(i == 3 ? NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT
                : NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE, a.kind);
            if (i < 3) c.observeOwnSurface(NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY,
                100L, i * 1_000L + 1L);
        }
        require(c.snapshot().terminal);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE, c.claimRecovery(100L, 1L, 3_100L).kind);
    }

    private static void selfWatchdogAllowsBothOwnedSurfacesAndRecoversAppSelectedTarget() {
        equal(NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
            NativeRendererForegroundGuardPolicy.visibleOwnedComponent(true, true));
        equal(NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY,
            NativeRendererForegroundGuardPolicy.visibleOwnedComponent(false, true));
        require(NativeRendererForegroundGuardPolicy.visibleOwnedComponent(false, false) == null);

        NativeRendererSoftKioskCoordinator c = new NativeRendererSoftKioskCoordinator();
        c.useSelfWatchdog();
        require(c.armFromExplicitColdLaunch(130L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE, "experimenter"));
        c.updateServiceState(NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            c.observeOwnSurface(NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
                130L, 100L).kind);
        equal(NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            c.snapshot().presentation);
        c.observeOwnSurface("com.example.ForeignActivity", 130L, 200L);
        equal(NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            c.snapshot().presentation);
        NativeRendererSoftKioskCoordinator.Action fromPanel =
            c.observeSelfDeparture(130L, 0L, 1_000L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE, fromPanel.kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            c.claimRecovery(130L, fromPanel.recoveryEpisodeId, 1_001L).kind);

        require(c.beginTransition(131L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER, 1_100L, 5_000L));
        c.observeOwnSurface(NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY,
            131L, 1_200L);
        equal(NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            c.snapshot().presentation);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_TRANSITION,
            c.observeSelfDeparture(131L, 0L, 1_250L).kind);
        c.observeOwnSurface(NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
            131L, 1_300L);
        NativeRendererSoftKioskCoordinator.Action fromVr =
            c.observeSelfDeparture(131L, 0L, 2_000L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL, fromVr.kind);
        c.observeOwnSurface(NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
            130L, 2_100L);
        equal(NativeRendererForegroundGuardPolicy.Presentation.PANEL,
            c.snapshot().presentation);

        require(c.beginTransition(132L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER, 2_200L, 5_000L));
        c.observeOwnSurface(NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY,
            132L, 2_250L);
        equal(NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            c.snapshot().presentation);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_TRANSITION,
            c.observeSelfDeparture(132L, 0L, 2_300L).kind);
        c.observeOwnSurface(NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY,
            132L, 2_400L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE,
            c.observeSelfDeparture(132L, 0L, 3_000L).kind);
    }

    private static void selfDepartureNeedsRecoveryAndIgnoresFocusNoiseAndPrompts() {
        NativeRendererSelfKioskDeparturePolicy p = new NativeRendererSelfKioskDeparturePolicy();
        equal(0L, p.observe(1L, true, false, false, 0L));
        equal(0L, p.observe(1L, false, false, false, 1_000L));
        equal(0L, p.observe(1L, false, false, false, 2_000L));
        equal(0L, p.observe(1L, false, true, false, 2_100L));
        long first = p.observe(1L, false, true, false, 2_850L);
        require(first > 0L);
        equal(first, p.observe(1L, false, true, false, 9_000L));
        equal(0L, p.observe(1L, true, false, false, 10_000L));
        equal(0L, p.observe(1L, false, true, true, 11_000L));
        equal(0L, p.observe(1L, false, true, false, 12_000L));
        equal(0L, p.observe(1L, false, true, false, 13_000L));
        p.observe(1L, true, false, false, 14_000L);
        p.observe(1L, false, true, false, 15_000L);
        require(p.observe(1L, false, true, false, 15_750L) > first);
        // A different desired presentation must first acquire its own confirmed foreground.
        equal(0L, p.observe(2L, false, true, false, 16_000L));
        equal(0L, p.observe(2L, false, true, false, 17_000L));
    }

    private static void explicitExitCreatesItsOwnAdmissionBeforeKioskArm() {
        NativeRendererSoftKioskCoordinator coordinator = new NativeRendererSoftKioskCoordinator();
        NativeRendererSoftKioskCoordinator.Action exit = coordinator.requestExplicitTerminalExit();
        equal(NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT, exit.kind);
        require(exit.generation > 0L);
        require(coordinator.admitsTerminalIntent(
            NativeRendererSoftKioskCoordinator.ACTION_TERMINAL_SAVE_AND_EXIT,
            NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT,
            exit.generation,
            Long.MAX_VALUE));
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            coordinator.requestExplicitTerminalExit().kind);
    }

    private static void immersiveOwnerDestructionDisarmsRecoveryAndAllowsFreshLaunch() {
        NativeRendererSoftKioskCoordinator coordinator = ready(
            120L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER);
        coordinator.releaseImmersiveOwner();
        reject(coordinator.snapshot().armed);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NOT_EFFECTIVE,
            coordinator.observeSelfDeparture(120L, 1L, 1_000L).kind);
        require(coordinator.armFromExplicitColdLaunch(
            121L,
            NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
            NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER));
        require(coordinator.snapshot().armed);
    }

    private static void selfPromptAndExplicitExitCancelPendingReturn() {
        NativeRendererSoftKioskCoordinator c = new NativeRendererSoftKioskCoordinator();
        c.useSelfWatchdog();
        c.updateServiceState(NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
        require(c.armFromExplicitColdLaunch(200L,
            NativeRendererForegroundGuardPolicy.Presentation.PANEL, "experimenter"));
        NativeRendererSoftKioskCoordinator.Action pending = c.observeSelfDeparture(200L, 1L, 1_000L);
        c.beginSelfSystemPrompt(1_001L);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            c.claimRecovery(200L, pending.recoveryEpisodeId, 1_002L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.SUPPRESSED_TRANSITION,
            c.observeSelfDeparture(200L, 2L, 2_000L).kind);
        c.endSelfSystemPrompt();
        NativeRendererSoftKioskCoordinator.Action exit = c.requestExplicitTerminalExit();
        equal(NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT, exit.kind);
        require(c.admitsTerminalIntent(NativeRendererSoftKioskCoordinator.ACTION_TERMINAL_SAVE_AND_EXIT,
            NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT, 200L, exit.homeEpisodeId));
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            c.claimRecovery(200L, pending.recoveryEpisodeId, 2_100L).kind);
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE, c.requestExplicitTerminalExit().kind);
    }

    private static NativeRendererSoftKioskCoordinator ready(
            long epoch,
            NativeRendererForegroundGuardPolicy.Presentation presentation,
            String route) {
        NativeRendererSoftKioskCoordinator coordinator =
            new NativeRendererSoftKioskCoordinator();
        coordinator.updateServiceState(
            NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
        require(coordinator.armFromExplicitColdLaunch(epoch, presentation, route));
        coordinator.updateHomeSurfaceState(
            NativeRendererSoftKioskCoordinator.HomeSurfaceState.RESOLVED);
        return coordinator;
    }

    private static NativeRendererSoftKioskCoordinator.Action observeHome(
            NativeRendererSoftKioskCoordinator coordinator,
            long generation,
            long episode,
            long eventMs) {
        return coordinator.observeForeground(
            HOME_PACKAGE,
            HOME_CLASS,
            false,
            true,
            episode,
            generation,
            eventMs);
    }

    private static void observeAllowed(
            NativeRendererSoftKioskCoordinator coordinator,
            long generation,
            long eventMs,
            String className) {
        equal(NativeRendererSoftKioskCoordinator.ActionKind.NONE,
            coordinator.observeForeground(
                APP,
                className,
                true,
                false,
                0L,
                generation,
                eventMs).kind);
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

    private static final class IntentNames {
        static final String FORGED_ACTION = "com.example.FORGED";
    }

    private static final class TimingTrace
            implements NativeRendererSoftKioskCoordinator.TimingObserver {
        long generation;
        long deadlineMs;
        boolean cancelRecovery;

        @Override
        public void onGuardTimingChanged(
                long observedGeneration,
                long observedDeadlineMs,
                boolean shouldCancelRecovery) {
            generation = observedGeneration;
            deadlineMs = observedDeadlineMs;
            cancelRecovery = shouldCancelRecovery;
        }
    }
}
