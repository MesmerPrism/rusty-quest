package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;

/** Remote preparation belongs to the selected application and its foreground owner. */
final class NativeRendererSelfKioskRemotePreparation {
    private NativeRendererSelfKioskRemotePreparation() {
    }

    static boolean consumeRemotePreparation(Activity activity, long epoch, long xrGeneration) {
        synchronized (NativeRendererExperimentLaunchAuthority.class) {
            if (activity == null
                    || !(activity.getApplicationContext() instanceof NativeRendererSelfKioskApplication)) {
                return false;
            }
            if (!NativeRendererExperimentLaunchAuthority.consumeRemoteLaunch(activity, epoch, xrGeneration)) {
                return false;
            }
            if (!NativeRendererSoftKioskCoordinator.process().armFromExplicitColdLaunch(epoch,
                    NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE,
                    NativeRendererSoftKioskCoordinator.PANEL_ROUTE_EXPERIMENTER)) return false;
        }
        // Preserve synchronous service dispatch after releasing the launch/coordinator locks.
        NativeRendererSelfKioskApplication.armed(activity);
        return true;
    }
}
