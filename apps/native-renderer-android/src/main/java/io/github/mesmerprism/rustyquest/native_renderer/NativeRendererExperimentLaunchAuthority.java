package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Process-local, one-shot admission for NativeActivity launcher-path provenance.
 *
 * <p>The forwarded extras are not user authentication. Admission depends on Android starting the
 * exported NativeActivity through its fresh exact MAIN/LAUNCHER route. The already-running
 * immersive process later consumes the epoch when it opens the same-package 2D panel.</p>
 */
final class NativeRendererExperimentLaunchAuthority {
    static final String PROVENANCE_EXPLICIT_USER_LAUNCH = "explicit-user-launch-v1";
    static final String EXTRA_LAUNCH_PROVENANCE = "native_renderer_launch_provenance";
    static final String EXTRA_LAUNCH_EPOCH = "native_renderer_launch_epoch";

    private static long lastIssuedEpoch;
    private static long pendingEpoch;
    private static boolean nativeActivityCreateSeen;

    private NativeRendererExperimentLaunchAuthority() {
    }

    /** Called from android_on_create while the exact NativeActivity reference is still valid. */
    static synchronized long issueFromNativeActivity(Activity activity, boolean recreation) {
        Intent incoming = activity == null ? null : activity.getIntent();
        ComponentName component = incoming == null ? null : incoming.getComponent();
        int categoryCount = incoming == null || incoming.getCategories() == null
            ? 0
            : incoming.getCategories().size();
        if (incoming == null || !NativeRendererExperimentLauncherPolicy.admits(
                recreation,
                incoming.getAction(),
                incoming.getCategories() != null
                    && incoming.getCategories().contains(Intent.CATEGORY_LAUNCHER),
                categoryCount,
                incoming.getData() != null || incoming.getSelector() != null,
                component == null ? null : component.getPackageName(),
                component == null ? null : component.getClassName(),
                activity.getPackageName(),
                "android.app.NativeActivity")
                || nativeActivityCreateSeen) {
            return 0L;
        }
        nativeActivityCreateSeen = true;
        return issueAdmitted(SystemClock.elapsedRealtimeNanos());
    }

    private static long issueAdmitted(long monotonicCandidate) {
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

    /** Read-only bridge from the live immersive process to the exact pending panel handoff. */
    static synchronized long pendingEpochForImmersiveStartup() {
        return pendingEpoch;
    }

    static synchronized void invalidatePending() {
        pendingEpoch = 0L;
        nativeActivityCreateSeen = false;
    }
}
