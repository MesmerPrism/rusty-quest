package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Pure edge/deduplication policy for closing the experimenter panel with a second triple-B.
 *
 * <p>Quest may project one physical controller press through Android key events, generic motion
 * events, or both. The aggregate-down edge prevents the two routes from double-counting one
 * press. A release is required between presses and all three presses must fit in one bounded
 * monotonic window.</p>
 */
final class ExperimenterPanelShortcutPolicy {
    static final int REQUIRED_PRESSES = 3;
    static final long DEFAULT_WINDOW_MS = 5_000L;

    private final long windowMs;
    private boolean keyDown;
    private boolean motionDown;
    private boolean aggregateDown;
    private int pressCount;
    private long firstPressAtMs = -1L;

    ExperimenterPanelShortcutPolicy() {
        this(DEFAULT_WINDOW_MS);
    }

    ExperimenterPanelShortcutPolicy(long windowMs) {
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be positive");
        }
        this.windowMs = windowMs;
    }

    boolean onKeySecondary(boolean down, boolean repeated, long nowMs) {
        keyDown = down;
        return update(nowMs, down && repeated);
    }

    boolean onMotionSecondary(boolean down, long nowMs) {
        motionDown = down;
        return update(nowMs, false);
    }

    boolean isMotionDown() {
        return motionDown;
    }

    void cancel() {
        keyDown = false;
        motionDown = false;
        aggregateDown = false;
        pressCount = 0;
        firstPressAtMs = -1L;
    }

    private boolean update(long nowMs, boolean repeatedKeyDown) {
        boolean down = keyDown || motionDown;
        boolean risingEdge = down && !aggregateDown;
        aggregateDown = down;
        if (!risingEdge || repeatedKeyDown) {
            return false;
        }

        if (firstPressAtMs < 0L || nowMs < firstPressAtMs
                || nowMs - firstPressAtMs > windowMs) {
            pressCount = 1;
            firstPressAtMs = nowMs;
            return false;
        }

        pressCount += 1;
        if (pressCount < REQUIRED_PRESSES) {
            return false;
        }
        pressCount = 0;
        firstPressAtMs = -1L;
        return true;
    }
}
