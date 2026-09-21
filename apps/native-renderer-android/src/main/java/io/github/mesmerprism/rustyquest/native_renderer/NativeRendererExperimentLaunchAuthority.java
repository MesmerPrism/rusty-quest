package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Process-local, one-shot admission for launcher-path provenance.
 *
 * <p>The forwarded extras are not user authentication. Admission additionally depends on the
 * exported launcher Activity having accepted Android's fresh exact MAIN/LAUNCHER route.</p>
 */
final class NativeRendererExperimentLaunchAuthority {
    static final String PROVENANCE_EXPLICIT_USER_LAUNCH = "explicit-user-launch-v1";
    static final String EXTRA_LAUNCH_PROVENANCE = "native_renderer_launch_provenance";
    static final String EXTRA_LAUNCH_EPOCH = "native_renderer_launch_epoch";

    private static long lastIssuedEpoch;
    private static long pendingEpoch;

    private NativeRendererExperimentLaunchAuthority() {
    }

    /** Called only by {@link NativeRendererExperimentLauncherActivity}. */
    static synchronized long issueFromLauncher(long monotonicCandidate) {
        if (lastIssuedEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("experiment launch epoch exhausted");
        }
        long positiveCandidate = monotonicCandidate > 0L ? monotonicCandidate : 1L;
        long issued = Math.max(positiveCandidate, lastIssuedEpoch + 1L);
        lastIssuedEpoch = issued;
        pendingEpoch = issued;
        return issued;
    }

    /**
     * Consumes the current launch exactly once. Bare MAIN, spoofed provenance, recreation and
     * replay all reject without creating another epoch.
     */
    static synchronized boolean consume(String provenance, long epoch) {
        if (!PROVENANCE_EXPLICIT_USER_LAUNCH.equals(provenance)
                || epoch <= 0L
                || epoch != pendingEpoch) {
            return false;
        }
        pendingEpoch = 0L;
        return true;
    }

    static synchronized void invalidatePending() {
        pendingEpoch = 0L;
    }
}
