package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Pure exact-Home episode classifier for the same-APK soft kiosk.
 *
 * <p>Accessibility can emit several state/focus events for one physical Home press. An episode
 * remains open through the short shell-tail debounce. A later exact Home observation opens a
 * fresh episode even if recovery failed and the app surface was never observed again. A short
 * confirmation quiet period also rejects a trailing shell event after recovery.</p>
 */
final class NativeRendererHomeEpisodePolicy {
    static final long HOME_EPISODE_DEBOUNCE_MS = 180L;
    private static final long CONFIRMED_TARGET_TAIL_QUIET_MS = 180L;

    enum Kind {
        NOT_HOME,
        NEW_EPISODE,
        REPEATED_EPISODE,
        CONFIRMED_TARGET_TAIL,
        STALE
    }

    static final class HomeSurface {
        final String packageName;
        final String className;

        HomeSurface(String packageName, String className) {
            this.packageName = requireNonEmpty(packageName, "Home package");
            this.className = requireNonEmpty(className, "Home class");
        }

        boolean matches(String observedPackage, String observedClass) {
            return packageName.equals(observedPackage) && className.equals(observedClass);
        }
    }

    static final class Observation {
        final Kind kind;
        final long episodeId;

        private Observation(Kind kind, long episodeId) {
            this.kind = kind;
            this.episodeId = episodeId;
        }

        static Observation of(Kind kind, long episodeId) {
            return new Observation(kind, episodeId);
        }
    }

    private long generation;
    private HomeSurface homeSurface;
    private long lastObservedMs = Long.MIN_VALUE;
    private long lastHomeMs = Long.MIN_VALUE;
    private long lastTargetConfirmationMs = Long.MIN_VALUE;
    private long episodeId;
    private boolean episodeOpen;

    void reset(long nextGeneration, HomeSurface nextHomeSurface) {
        if (nextGeneration <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        generation = nextGeneration;
        homeSurface = nextHomeSurface;
        lastObservedMs = Long.MIN_VALUE;
        lastHomeMs = Long.MIN_VALUE;
        lastTargetConfirmationMs = Long.MIN_VALUE;
        episodeId = 0L;
        episodeOpen = false;
    }

    boolean observeAllowedTarget(long observedGeneration, long eventMs) {
        if (!accept(observedGeneration, eventMs)) {
            return false;
        }
        episodeOpen = false;
        lastTargetConfirmationMs = eventMs;
        return true;
    }

    Observation observe(
            long observedGeneration,
            String packageName,
            String className,
            long eventMs) {
        if (!accept(observedGeneration, eventMs)) {
            return Observation.of(Kind.STALE, episodeId);
        }
        if (homeSurface == null || !homeSurface.matches(packageName, className)) {
            return Observation.of(Kind.NOT_HOME, 0L);
        }
        if (episodeOpen
                && lastHomeMs != Long.MIN_VALUE
                && eventMs - lastHomeMs <= HOME_EPISODE_DEBOUNCE_MS) {
            lastHomeMs = eventMs;
            return Observation.of(Kind.REPEATED_EPISODE, episodeId);
        }
        if (lastTargetConfirmationMs != Long.MIN_VALUE
                && eventMs - lastTargetConfirmationMs <= CONFIRMED_TARGET_TAIL_QUIET_MS) {
            return Observation.of(Kind.CONFIRMED_TARGET_TAIL, episodeId);
        }
        if (episodeId == Long.MAX_VALUE) {
            throw new IllegalStateException("Home episode identity exhausted");
        }
        episodeId += 1L;
        episodeOpen = true;
        lastHomeMs = eventMs;
        return Observation.of(Kind.NEW_EPISODE, episodeId);
    }

    private boolean accept(long observedGeneration, long eventMs) {
        if (generation <= 0L
                || observedGeneration != generation
                || eventMs < 0L
                || eventMs <= lastObservedMs) {
            return false;
        }
        lastObservedMs = eventMs;
        return true;
    }

    private static String requireNonEmpty(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
