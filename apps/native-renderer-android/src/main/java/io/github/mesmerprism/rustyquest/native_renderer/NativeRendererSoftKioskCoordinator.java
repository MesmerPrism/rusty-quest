package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure, process-local authority joining app presentation to foreground-guard observations. */
final class NativeRendererSoftKioskCoordinator {
    static final String ACTION_TERMINAL_SAVE_AND_EXIT =
        "io.github.mesmerprism.rustyquest.native_renderer.action.TERMINAL_SAVE_AND_EXIT";
    static final String EXTRA_TERMINAL_ROUTE = "native_renderer_terminal_route";
    static final String EXTRA_GUARD_GENERATION = "native_renderer_guard_generation";
    static final String EXTRA_HOME_EPISODE = "native_renderer_home_episode";
    static final String TERMINAL_ROUTE_SAVE_AND_EXIT = "save_and_exit";
    static final String PANEL_ROUTE_EXPERIMENTER = "experimenter";
    static final String PANEL_ROUTE_DEVELOPER = "developer";

    private static final long MAX_TRANSITION_MS = 5_000L;
    private static final long MAX_SYSTEM_PROMPT_MS = 60_000L;
    private static final int MAX_RECOVERY_ATTEMPTS_PER_EPISODE = 3;
    private static final NativeRendererSoftKioskCoordinator PROCESS_INSTANCE =
        new NativeRendererSoftKioskCoordinator();

    enum ServiceState { MISSING_OR_DISABLED, CONNECTED, INTERRUPTED, REVOKED }
    enum HomeSurfaceState { UNKNOWN, RESOLVING, RESOLVED, UNAVAILABLE }
    enum Effectiveness {
        READY_ARMED,
        READY_DISARMED,
        NEEDS_ACCESSIBILITY_SETUP,
        WATCHDOG_STARTING,
        INTERRUPTED,
        REVOKED,
        DEGRADED_HOME_RESOLVING,
        UNAVAILABLE_HOME_SURFACE,
        TERMINAL
    }
    enum ActionKind {
        NONE,
        NOT_EFFECTIVE,
        SUPPRESSED_ALLOWED_PROMPT,
        SUPPRESSED_TRANSITION,
        RECOVER_IMMERSIVE,
        RECOVER_PANEL,
        RECOVERY_EXHAUSTED,
        BEGIN_TERMINAL_EXIT
    }

    interface TimingObserver {
        void onGuardTimingChanged(long generation, long nextDeadlineMs, boolean cancelRecovery);
    }

    static final class Action {
        final ActionKind kind;
        final long generation;
        final long homeEpisodeId;
        final long recoveryEpisodeId;
        final int recoveryAttempt;
        final boolean deadlineExpired;
        final NativeRendererForegroundGuardPolicy.Presentation presentation;
        final String panelRoute;

        private Action(ActionKind kind, long generation, long homeEpisodeId,
                long recoveryEpisodeId, int recoveryAttempt, boolean deadlineExpired,
                NativeRendererForegroundGuardPolicy.Presentation presentation,
                String panelRoute) {
            this.kind = kind;
            this.generation = generation;
            this.homeEpisodeId = homeEpisodeId;
            this.recoveryEpisodeId = recoveryEpisodeId;
            this.recoveryAttempt = recoveryAttempt;
            this.deadlineExpired = deadlineExpired;
            this.presentation = presentation;
            this.panelRoute = panelRoute;
        }
    }

    static final class Snapshot {
        final long generation;
        final long explicitLaunchEpoch;
        final NativeRendererForegroundGuardPolicy.Presentation presentation;
        final String panelRoute;
        final boolean armed;
        final boolean terminal;
        final ServiceState serviceState;
        final HomeSurfaceState homeSurfaceState;
        final Effectiveness effectiveness;
        final long nextDeadlineMs;
        final long recoveryEpisodeId;
        final int recoveryAttempts;
        final boolean recoveryExhausted;

        private Snapshot(long generation, long explicitLaunchEpoch,
                NativeRendererForegroundGuardPolicy.Presentation presentation, String panelRoute,
                boolean armed, boolean terminal, ServiceState serviceState,
                HomeSurfaceState homeSurfaceState, Effectiveness effectiveness,
                long nextDeadlineMs, long recoveryEpisodeId, int recoveryAttempts) {
            this.generation = generation;
            this.explicitLaunchEpoch = explicitLaunchEpoch;
            this.presentation = presentation;
            this.panelRoute = panelRoute;
            this.armed = armed;
            this.terminal = terminal;
            this.serviceState = serviceState;
            this.homeSurfaceState = homeSurfaceState;
            this.effectiveness = effectiveness;
            this.nextDeadlineMs = nextDeadlineMs;
            this.recoveryEpisodeId = recoveryEpisodeId;
            this.recoveryAttempts = recoveryAttempts;
            this.recoveryExhausted = recoveryEpisodeId > 0L
                && recoveryAttempts >= MAX_RECOVERY_ATTEMPTS_PER_EPISODE;
        }
    }

    private static final class PromptLease {
        final long generation;
        final String packageName;
        final String className;
        final long expiresAtMs;

        PromptLease(long generation, String packageName, String className, long expiresAtMs) {
            this.generation = generation;
            this.packageName = packageName;
            this.className = className;
            this.expiresAtMs = expiresAtMs;
        }

        boolean matches(long observedGeneration, String observedPackage, String observedClass,
                long nowMs) {
            return generation == observedGeneration && nowMs <= expiresAtMs
                && packageName.equals(observedPackage) && className.equals(observedClass);
        }
    }

    private static final class DeferredForeground {
        final String packageName;
        final String className;
        final boolean exactAllowedTarget;
        final long eventMs;

        DeferredForeground(String packageName, String className, boolean exactAllowedTarget,
                long eventMs) {
            this.packageName = packageName;
            this.className = className;
            this.exactAllowedTarget = exactAllowedTarget;
            this.eventMs = eventMs;
        }
    }

    private NativeRendererForegroundGuardPolicy policy;
    private NativeRendererForegroundGuardPolicy.Presentation presentation =
        NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE;
    private String panelRoute = PANEL_ROUTE_EXPERIMENTER;
    private ServiceState serviceState = ServiceState.MISSING_OR_DISABLED;
    private HomeSurfaceState homeSurfaceState = HomeSurfaceState.UNKNOWN;
    private long generation;
    private long explicitLaunchEpoch;
    private long transitionDeadlineMs = Long.MIN_VALUE;
    private long terminalHomeEpisode;
    private boolean armed;
    private boolean terminal;
    private PromptLease promptLease;
    private DeferredForeground deferredForeground;
    private TimingObserver timingObserver;
    private long recoveryEpisodeSequence;
    private long activeRecoveryEpisodeId;
    private long activeHomeEpisodeId;
    private String activeRecoveryPackage;
    private String activeRecoveryClass;
    private boolean activeRecoveryIsHome;
    private int recoveryAttempts;
    private boolean selfWatchdog;
    private long selfPromptDeadlineMs = Long.MIN_VALUE;

    static NativeRendererSoftKioskCoordinator process() { return PROCESS_INSTANCE; }

    synchronized void useSelfWatchdog() { selfWatchdog = true; }

    synchronized boolean selfRecoverySuppressed(long nowMs) {
        return nowMs <= selfPromptDeadlineMs || promptActive(nowMs) || transitionActive(nowMs);
    }

    synchronized void beginSelfSystemPrompt(long nowMs) {
        if (!selfWatchdog || !armed || terminal) return;
        selfPromptDeadlineMs = saturatingAdd(nowMs, MAX_SYSTEM_PROMPT_MS);
        if (policy != null) policy.cancelRecovery();
        clearRecoveryEpisode();
        notifyTimingChanged(true);
    }

    synchronized void endSelfSystemPrompt() { selfPromptDeadlineMs = Long.MIN_VALUE; }

    synchronized Action observeOwnSurface(String component, long observedGeneration, long nowMs) {
        if (!selfWatchdog || serviceState != ServiceState.CONNECTED || !armed
                || terminal || policy == null) {
            return action(ActionKind.NOT_EFFECTIVE, 0L, false, 0);
        }
        if (observedGeneration != generation || nowMs < 0L) {
            return action(ActionKind.NONE, 0L, false, 0);
        }
        if (policy.observeOwnedComponent(component, generation, nowMs)) {
            // This records what the app brought forward. It does not request a
            // panel/VR switch; only an external departure may request recovery.
            presentation = NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY
                .equals(component)
                ? NativeRendererForegroundGuardPolicy.Presentation.PANEL
                : NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE;
            transitionDeadlineMs = Long.MIN_VALUE;
            deferredForeground = null;
            clearRecoveryEpisode();
            notifyTimingChanged(true);
        }
        return action(ActionKind.NONE, 0L, false, 0);
    }

    synchronized Action observeSelfDeparture(long observedGeneration, long departureId, long nowMs) {
        if (!selfWatchdog || serviceState != ServiceState.CONNECTED || !armed || terminal
                || policy == null || generation != observedGeneration || nowMs < 0L) {
            return action(ActionKind.NOT_EFFECTIVE, 0L, false, 0);
        }
        if (selfRecoverySuppressed(nowMs)) return action(ActionKind.SUPPRESSED_TRANSITION, 0L, false, 0);
        ensureRecoveryEpisode("own-app", "own-presentation-absent", false, 0L);
        return fromPolicyDecision(policy.observeSelfDeparture(generation, departureId, nowMs),
            departureId, false, 0);
    }

    synchronized Action requestExplicitTerminalExit() {
        if (terminal) return action(ActionKind.NONE, 0L, false, 0);
        if (policy == null || !armed) {
            long basis = Math.max(generation, explicitLaunchEpoch);
            if (basis == Long.MAX_VALUE) return action(ActionKind.NONE, 0L, false, 0);
            long terminalGeneration = Math.max(1L, basis + 1L);
            NativeRendererForegroundGuardPolicy replacement =
                new NativeRendererForegroundGuardPolicy();
            replacement.arm(terminalGeneration, presentation);
            policy = replacement;
            generation = terminalGeneration;
            armed = true;
            transitionDeadlineMs = Long.MIN_VALUE;
            terminalHomeEpisode = 0L;
            selfPromptDeadlineMs = Long.MIN_VALUE;
            promptLease = null;
            deferredForeground = null;
            clearRecoveryEpisode();
        }
        return fromPolicyDecision(policy.beginTerminalExit(), Long.MAX_VALUE, false, 0);
    }

    synchronized void releaseImmersiveOwner() {
        if (!terminal) {
            policy = null;
            armed = false;
        }
        transitionDeadlineMs = Long.MIN_VALUE;
        selfPromptDeadlineMs = Long.MIN_VALUE;
        promptLease = null;
        deferredForeground = null;
        clearRecoveryEpisode();
        notifyTimingChanged(true);
    }

    synchronized void setTimingObserver(TimingObserver observer) { timingObserver = observer; }

    synchronized void clearTimingObserver(TimingObserver observer) {
        if (timingObserver == observer) timingObserver = null;
    }

    synchronized boolean armFromExplicitColdLaunch(long launchEpoch,
            NativeRendererForegroundGuardPolicy.Presentation requestedPresentation,
            String requestedPanelRoute) {
        if (launchEpoch <= 0L || launchEpoch <= explicitLaunchEpoch || requestedPresentation == null) {
            return false;
        }
        NativeRendererForegroundGuardPolicy replacement = new NativeRendererForegroundGuardPolicy();
        replacement.arm(launchEpoch, requestedPresentation);
        policy = replacement;
        generation = launchEpoch;
        explicitLaunchEpoch = launchEpoch;
        presentation = requestedPresentation;
        panelRoute = normalizePanelRoute(requestedPanelRoute);
        transitionDeadlineMs = Long.MIN_VALUE;
        terminalHomeEpisode = 0L;
        armed = true;
        terminal = false;
        selfPromptDeadlineMs = Long.MIN_VALUE;
        promptLease = null;
        deferredForeground = null;
        clearRecoveryEpisode();
        notifyTimingChanged(true);
        return true;
    }

    synchronized boolean beginTransition(long nextGeneration,
            NativeRendererForegroundGuardPolicy.Presentation requestedPresentation,
            String requestedPanelRoute, long nowMs, long durationMs) {
        if (policy == null || !armed || terminal || requestedPresentation == null || nowMs < 0L
                || durationMs < 0L || durationMs > MAX_TRANSITION_MS
                || !policy.beginTransition(nextGeneration, requestedPresentation)) return false;
        generation = nextGeneration;
        presentation = requestedPresentation;
        panelRoute = normalizePanelRoute(requestedPanelRoute);
        transitionDeadlineMs = saturatingAdd(nowMs, durationMs);
        promptLease = null;
        deferredForeground = null;
        clearRecoveryEpisode();
        notifyTimingChanged(true);
        return true;
    }

    synchronized boolean allowExactSystemPrompt(long expectedGeneration, String packageName,
            String className, long nowMs, long durationMs) {
        if (!armed || terminal || expectedGeneration != generation || isBlank(packageName)
                || isBlank(className) || nowMs < 0L || durationMs <= 0L
                || durationMs > MAX_SYSTEM_PROMPT_MS) return false;
        promptLease = new PromptLease(generation, packageName, className,
            saturatingAdd(nowMs, durationMs));
        deferredForeground = null;
        if (policy != null) policy.cancelRecovery();
        notifyTimingChanged(true);
        return true;
    }

    synchronized Action observeForeground(String packageName, String className,
            boolean exactAllowedTarget, boolean exactHomeSurface, long homeEpisodeId,
            long observedGeneration, long eventMs) {
        if (serviceState != ServiceState.CONNECTED || (!selfWatchdog && homeSurfaceState != HomeSurfaceState.RESOLVED)
                || !armed || policy == null || terminal) {
            return action(ActionKind.NOT_EFFECTIVE, homeEpisodeId, false, 0);
        }
        if (observedGeneration != generation || eventMs < 0L) {
            return action(ActionKind.NONE, homeEpisodeId, false, 0);
        }
        boolean allowedComponent = exactAllowedTarget
            && NativeRendererForegroundGuardPolicy.isAllowedComponent(className);
        if (!exactHomeSurface && allowedComponent) {
            if (presentation.componentClass.equals(className)) {
                if (policy.observeAllowedComponent(className, generation, eventMs)) {
                    transitionDeadlineMs = Long.MIN_VALUE;
                    deferredForeground = null;
                    clearRecoveryEpisode();
                    notifyTimingChanged(true);
                }
                return action(ActionKind.NONE, homeEpisodeId, false, 0);
            }
            if (transitionActive(eventMs)) {
                deferredForeground = new DeferredForeground(packageName, className, true, eventMs);
                return action(ActionKind.SUPPRESSED_TRANSITION, homeEpisodeId, false, 0);
            }
        }
        if (!exactHomeSurface && promptLease != null) {
            if (promptLease.matches(generation, packageName, className, eventMs)) {
                deferredForeground = new DeferredForeground(
                    packageName, className, exactAllowedTarget, eventMs);
                return action(ActionKind.SUPPRESSED_ALLOWED_PROMPT, homeEpisodeId, false, 0);
            }
            if (eventMs > promptLease.expiresAtMs) promptLease = null;
        }
        if (!exactHomeSurface && transitionActive(eventMs)) {
            deferredForeground = new DeferredForeground(
                packageName, className, exactAllowedTarget, eventMs);
            return action(ActionKind.SUPPRESSED_TRANSITION, homeEpisodeId, false, 0);
        }
        ensureRecoveryEpisode(packageName, className, exactHomeSurface, homeEpisodeId);
        NativeRendererForegroundGuardPolicy.Decision decision = policy.observeDisallowedForeground(
            exactHomeSurface, generation, exactHomeSurface ? homeEpisodeId : 0L, eventMs);
        return fromPolicyDecision(decision, homeEpisodeId, false, 0);
    }

    synchronized Action claimRecovery(long expectedGeneration, long expectedRecoveryEpisodeId,
            long nowMs) {
        if (serviceState != ServiceState.CONNECTED || (!selfWatchdog && homeSurfaceState != HomeSurfaceState.RESOLVED)
                || !armed || terminal || policy == null || expectedGeneration != generation
                || expectedRecoveryEpisodeId <= 0L
                || expectedRecoveryEpisodeId != activeRecoveryEpisodeId || nowMs < 0L
                || recoverySuppressed(nowMs) || (selfWatchdog && selfRecoverySuppressed(nowMs))) {
            return action(ActionKind.NONE, 0L, false, 0);
        }
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS_PER_EPISODE) {
            policy.cancelRecovery();
            return action(ActionKind.RECOVERY_EXHAUSTED, 0L, false, recoveryAttempts);
        }
        NativeRendererForegroundGuardPolicy.Decision decision = policy.claimRecovery(expectedGeneration);
        if (decision == NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE
                || decision == NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL) {
            recoveryAttempts += 1;
        }
        return fromPolicyDecision(decision, 0L, false, recoveryAttempts);
    }

    synchronized Action evaluateDeadline(long expectedGeneration, long nowMs) {
        if (expectedGeneration != generation || nowMs < 0L) {
            return action(ActionKind.NONE, 0L, false, 0);
        }
        boolean expired = false;
        if (promptLease != null && nowMs > promptLease.expiresAtMs) {
            promptLease = null;
            expired = true;
        }
        if (transitionDeadlineMs != Long.MIN_VALUE && nowMs > transitionDeadlineMs) {
            transitionDeadlineMs = Long.MIN_VALUE;
            expired = true;
        }
        if (!expired || promptActive(nowMs) || transitionActive(nowMs)) {
            return action(ActionKind.NONE, 0L, expired, 0);
        }
        if (deferredForeground != null) {
            DeferredForeground deferred = deferredForeground;
            deferredForeground = null;
            Action observed = observeForeground(deferred.packageName, deferred.className,
                deferred.exactAllowedTarget, false, 0L, expectedGeneration,
                Math.max(nowMs, deferred.eventMs + 1L));
            return copyWithDeadlineExpired(observed);
        }
        if (policy != null && policy.isRecoveryPending()) {
            return action(recoveryKind(), 0L, true, 0);
        }
        return action(ActionKind.NONE, 0L, true, 0);
    }

    synchronized void updateServiceState(ServiceState nextState) {
        if (nextState == null) throw new IllegalArgumentException("service state is required");
        serviceState = nextState;
        if (nextState != ServiceState.CONNECTED && policy != null) {
            policy.cancelRecovery();
            deferredForeground = null;
            promptLease = null;
            transitionDeadlineMs = Long.MIN_VALUE;
            notifyTimingChanged(true);
        }
    }

    synchronized void updateHomeSurfaceState(HomeSurfaceState nextState) {
        if (nextState == null) throw new IllegalArgumentException("Home surface state is required");
        homeSurfaceState = nextState;
        if (nextState != HomeSurfaceState.RESOLVED && policy != null) {
            policy.cancelRecovery();
            deferredForeground = null;
            promptLease = null;
            transitionDeadlineMs = Long.MIN_VALUE;
            notifyTimingChanged(true);
        }
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(generation, explicitLaunchEpoch, presentation, panelRoute, armed,
            terminal, serviceState, homeSurfaceState, effectiveness(), nextDeadlineMs(),
            activeRecoveryEpisodeId, recoveryAttempts);
    }

    synchronized boolean admitsTerminalIntent(String action, String route, long intentGeneration,
            long homeEpisodeId) {
        return terminal && !armed && ACTION_TERMINAL_SAVE_AND_EXIT.equals(action)
            && TERMINAL_ROUTE_SAVE_AND_EXIT.equals(route) && intentGeneration == generation
            && homeEpisodeId > 0L && homeEpisodeId == terminalHomeEpisode;
    }

    private Action fromPolicyDecision(NativeRendererForegroundGuardPolicy.Decision decision,
            long homeEpisodeId, boolean deadlineExpired, int recoveryAttempt) {
        if (decision == NativeRendererForegroundGuardPolicy.Decision.RECOVER_IMMERSIVE
                || decision == NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL) {
            if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS_PER_EPISODE && recoveryAttempt == 0) {
                policy.cancelRecovery();
                return action(ActionKind.RECOVERY_EXHAUSTED, homeEpisodeId, deadlineExpired,
                    recoveryAttempts);
            }
            return action(decision == NativeRendererForegroundGuardPolicy.Decision.RECOVER_PANEL
                ? ActionKind.RECOVER_PANEL : ActionKind.RECOVER_IMMERSIVE, homeEpisodeId,
                deadlineExpired, recoveryAttempt);
        }
        if (decision == NativeRendererForegroundGuardPolicy.Decision.BEGIN_TERMINAL_EXIT) {
            armed = false;
            terminal = true;
            transitionDeadlineMs = Long.MIN_VALUE;
            promptLease = null;
            deferredForeground = null;
            terminalHomeEpisode = homeEpisodeId;
            notifyTimingChanged(true);
            return action(ActionKind.BEGIN_TERMINAL_EXIT, homeEpisodeId, false, 0);
        }
        return action(ActionKind.NONE, homeEpisodeId, deadlineExpired, recoveryAttempt);
    }

    private Action copyWithDeadlineExpired(Action source) {
        return new Action(source.kind, source.generation, source.homeEpisodeId,
            source.recoveryEpisodeId, source.recoveryAttempt, true, source.presentation,
            source.panelRoute);
    }

    private Action action(ActionKind kind, long homeEpisodeId, boolean deadlineExpired,
            int recoveryAttempt) {
        return new Action(kind, generation, homeEpisodeId, activeRecoveryEpisodeId,
            recoveryAttempt, deadlineExpired, presentation, panelRoute);
    }

    private ActionKind recoveryKind() {
        return presentation == NativeRendererForegroundGuardPolicy.Presentation.PANEL
            ? ActionKind.RECOVER_PANEL : ActionKind.RECOVER_IMMERSIVE;
    }

    private boolean promptActive(long nowMs) {
        if (promptLease == null) return false;
        if (nowMs <= promptLease.expiresAtMs) return true;
        promptLease = null;
        return false;
    }

    private boolean recoverySuppressed(long nowMs) {
        if (promptLease != null) {
            if (nowMs > promptLease.expiresAtMs) {
                promptLease = null;
            } else if (!activeRecoveryIsHome
                    && promptLease.matches(
                        generation, activeRecoveryPackage, activeRecoveryClass, nowMs)) {
                return true;
            }
        }
        return !activeRecoveryIsHome && transitionActive(nowMs);
    }

    private boolean transitionActive(long nowMs) {
        if (transitionDeadlineMs == Long.MIN_VALUE) return false;
        if (nowMs <= transitionDeadlineMs) return true;
        transitionDeadlineMs = Long.MIN_VALUE;
        return false;
    }

    private long nextDeadlineMs() {
        long result = transitionDeadlineMs;
        if (promptLease != null && (result == Long.MIN_VALUE || promptLease.expiresAtMs < result)) {
            result = promptLease.expiresAtMs;
        }
        return result;
    }

    private void ensureRecoveryEpisode(String packageName, String className,
            boolean exactHomeSurface, long homeEpisodeId) {
        if (activeRecoveryEpisodeId > 0L) {
            if (!exactHomeSurface) {
                // All external component churn remains one departure until the desired app
                // surface is confirmed, a presentation generation changes, or a distinct exact
                // Home episode is admitted.
                activeRecoveryPackage = packageName;
                activeRecoveryClass = className;
                return;
            }
            if (homeEpisodeId > 0L && activeHomeEpisodeId == homeEpisodeId) {
                return;
            }
        }
        if (recoveryEpisodeSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("recovery episode identity exhausted");
        }
        recoveryEpisodeSequence += 1L;
        activeRecoveryEpisodeId = recoveryEpisodeSequence;
        activeHomeEpisodeId = exactHomeSurface ? homeEpisodeId : 0L;
        activeRecoveryPackage = packageName;
        activeRecoveryClass = className;
        activeRecoveryIsHome = exactHomeSurface;
        recoveryAttempts = 0;
    }

    private void clearRecoveryEpisode() {
        activeRecoveryEpisodeId = 0L;
        activeHomeEpisodeId = 0L;
        activeRecoveryPackage = null;
        activeRecoveryClass = null;
        activeRecoveryIsHome = false;
        recoveryAttempts = 0;
    }

    private Effectiveness effectiveness() {
        if (terminal) return Effectiveness.TERMINAL;
        if (serviceState == ServiceState.MISSING_OR_DISABLED) {
            return selfWatchdog ? Effectiveness.WATCHDOG_STARTING : Effectiveness.NEEDS_ACCESSIBILITY_SETUP;
        }
        if (serviceState == ServiceState.INTERRUPTED) return Effectiveness.INTERRUPTED;
        if (serviceState == ServiceState.REVOKED) return Effectiveness.REVOKED;
        if (selfWatchdog) return armed ? Effectiveness.READY_ARMED : Effectiveness.READY_DISARMED;
        if (homeSurfaceState == HomeSurfaceState.UNAVAILABLE) {
            return Effectiveness.UNAVAILABLE_HOME_SURFACE;
        }
        if (homeSurfaceState != HomeSurfaceState.RESOLVED) {
            return Effectiveness.DEGRADED_HOME_RESOLVING;
        }
        return armed ? Effectiveness.READY_ARMED : Effectiveness.READY_DISARMED;
    }

    private void notifyTimingChanged(boolean cancelRecovery) {
        if (timingObserver != null) {
            timingObserver.onGuardTimingChanged(generation, nextDeadlineMs(), cancelRecovery);
        }
    }

    private static String normalizePanelRoute(String route) {
        return PANEL_ROUTE_DEVELOPER.equals(route) ? PANEL_ROUTE_DEVELOPER
            : PANEL_ROUTE_EXPERIMENTER;
    }
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
