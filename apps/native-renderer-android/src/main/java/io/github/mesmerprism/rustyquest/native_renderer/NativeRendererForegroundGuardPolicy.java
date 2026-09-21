package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Pure decision policy for the experiment shell's foreground guard.
 *
 * <p>A platform observation adapter is an adapter only: it classifies an observed
 * foreground surface, supplies a monotonic event time, and executes the returned decision.
 * This class owns transition generations, distinct Home episodes, and the terminal-exit
 * latch so a delayed recovery callback cannot relaunch either activity after exit begins.</p>
 */
final class NativeRendererForegroundGuardPolicy {
    static final String NATIVE_ACTIVITY = "android.app.NativeActivity";
    static final String CONTROL_PANEL_ACTIVITY =
        "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity";

    private static final long HOME_WINDOW_MS = 5_000L;

    enum Presentation {
        IMMERSIVE(NATIVE_ACTIVITY),
        PANEL(CONTROL_PANEL_ACTIVITY);

        final String componentClass;

        Presentation(String componentClass) {
            this.componentClass = componentClass;
        }
    }

    enum Decision {
        NONE,
        RECOVER_IMMERSIVE,
        RECOVER_PANEL,
        BEGIN_TERMINAL_EXIT
    }

    private final long[] homeEpisodeTimesMs = new long[3];
    private final long[] homeEpisodeIds = new long[3];
    private int homeEpisodeCount;
    private boolean armed;
    private boolean terminalExit;
    private boolean recoveryPending;
    private long transitionGeneration;
    private long lastObservedEventMs = Long.MIN_VALUE;
    private Presentation desiredPresentation = Presentation.IMMERSIVE;

    void arm(long generation, Presentation presentation) {
        requirePositiveGeneration(generation);
        if (terminalExit) {
            throw new IllegalStateException("terminal exit is irreversible");
        }
        armed = true;
        transitionGeneration = generation;
        desiredPresentation = requirePresentation(presentation);
        recoveryPending = false;
        lastObservedEventMs = Long.MIN_VALUE;
        clearHomeEpisodes();
    }

    boolean beginTransition(long generation, Presentation presentation) {
        if (!armed || terminalExit || generation <= transitionGeneration) {
            return false;
        }
        transitionGeneration = generation;
        desiredPresentation = requirePresentation(presentation);
        recoveryPending = false;
        clearHomeEpisodes();
        return true;
    }

    boolean observeAllowedComponent(String componentClass, long generation, long eventMs) {
        if (!armed
            || terminalExit
            || generation != transitionGeneration
            || !isAllowedComponent(componentClass)
            || !desiredPresentation.componentClass.equals(componentClass)
            || !acceptEventTime(eventMs)) {
            return false;
        }
        recoveryPending = false;
        return true;
    }

    /**
     * Records a foreground outside the two allowed components.
     *
     * @param exactHomeSurface true only after the adapter has matched the platform Home surface
     * @param generation transition generation attached to the foreground observation
     * @param homeEpisodeId positive adapter-owned identity for one physical Home episode
     * @param eventMs monotonic event time; regressing/stale deliveries are ignored
     */
    Decision observeDisallowedForeground(
            boolean exactHomeSurface,
            long generation,
            long homeEpisodeId,
            long eventMs) {
        return observeDeparture(exactHomeSurface, generation, homeEpisodeId, eventMs);
    }

    /** A recovered own-Activity departure, not evidence of a physical HOME button. */
    Decision observeSelfDeparture(long generation, long departureId, long eventMs) {
        return observeDeparture(departureId > 0L, generation, departureId, eventMs);
    }

    Decision beginTerminalExit() {
        if (!armed || terminalExit) return Decision.NONE;
        armed = false;
        terminalExit = true;
        recoveryPending = false;
        return Decision.BEGIN_TERMINAL_EXIT;
    }

    private Decision observeDeparture(boolean countEscape, long generation,
            long homeEpisodeId, long eventMs) {
        if (!armed
            || terminalExit
            || generation != transitionGeneration
            || !acceptEventTime(eventMs)) {
            return Decision.NONE;
        }

        recoveryPending = true;
        if (!countEscape
            || homeEpisodeId <= 0L
            || containsHomeEpisode(homeEpisodeId)) {
            return recoveryDecision();
        }

        pruneHomeEpisodes(eventMs);
        homeEpisodeTimesMs[homeEpisodeCount] = eventMs;
        homeEpisodeIds[homeEpisodeCount] = homeEpisodeId;
        homeEpisodeCount += 1;
        if (homeEpisodeCount < 3) {
            return recoveryDecision();
        }

        // Terminal exit is latched and the guard is disarmed before the adapter is told to exit.
        armed = false;
        terminalExit = true;
        recoveryPending = false;
        return Decision.BEGIN_TERMINAL_EXIT;
    }

    Decision claimRecovery(long generation) {
        if (!armed
            || terminalExit
            || !recoveryPending
            || generation != transitionGeneration) {
            return Decision.NONE;
        }
        recoveryPending = false;
        return recoveryDecision();
    }

    void cancelRecovery() {
        recoveryPending = false;
    }

    boolean isArmed() {
        return armed;
    }

    boolean isTerminalExit() {
        return terminalExit;
    }

    boolean isRecoveryPending() {
        return recoveryPending;
    }

    long transitionGeneration() {
        return transitionGeneration;
    }

    static boolean isAllowedComponent(String componentClass) {
        return NATIVE_ACTIVITY.equals(componentClass)
            || CONTROL_PANEL_ACTIVITY.equals(componentClass);
    }

    private Decision recoveryDecision() {
        return desiredPresentation == Presentation.PANEL
            ? Decision.RECOVER_PANEL
            : Decision.RECOVER_IMMERSIVE;
    }

    private boolean acceptEventTime(long eventMs) {
        if (eventMs < 0L || eventMs <= lastObservedEventMs) {
            return false;
        }
        lastObservedEventMs = eventMs;
        return true;
    }

    private void pruneHomeEpisodes(long nowMs) {
        int firstRetained = 0;
        while (firstRetained < homeEpisodeCount
            && nowMs - homeEpisodeTimesMs[firstRetained] > HOME_WINDOW_MS) {
            firstRetained += 1;
        }
        if (firstRetained == 0) {
            return;
        }
        int retained = homeEpisodeCount - firstRetained;
        for (int index = 0; index < retained; index += 1) {
            homeEpisodeTimesMs[index] = homeEpisodeTimesMs[firstRetained + index];
            homeEpisodeIds[index] = homeEpisodeIds[firstRetained + index];
        }
        homeEpisodeCount = retained;
    }

    private boolean containsHomeEpisode(long homeEpisodeId) {
        for (int index = 0; index < homeEpisodeCount; index += 1) {
            if (homeEpisodeIds[index] == homeEpisodeId) {
                return true;
            }
        }
        return false;
    }

    private void clearHomeEpisodes() {
        homeEpisodeCount = 0;
    }

    private static void requirePositiveGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    private static Presentation requirePresentation(Presentation presentation) {
        if (presentation == null) {
            throw new IllegalArgumentException("presentation is required");
        }
        return presentation;
    }
}
