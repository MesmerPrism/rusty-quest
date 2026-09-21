package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * UI-free launcher trampoline for experiment-profile APKs.
 *
 * <p>Only Android starting this exact exported component with a fresh, anomaly-free
 * MAIN/LAUNCHER intent creates launcher-path provenance and a process-local one-shot epoch.
 * Extras alone never authenticate a user launch. Ordinary internal MAIN, soft-kiosk recovery,
 * Activity recreation, and terminal exit never route through this authority.</p>
 */
public final class NativeRendererExperimentLauncherActivity extends Activity {
    private static final String CONTROL_PANEL_CLASS =
        "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        forwardExplicitLauncherIntent(getIntent(), state != null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forwardExplicitLauncherIntent(intent, false);
    }

    private void forwardExplicitLauncherIntent(Intent incoming, boolean recreation) {
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
                getPackageName(),
                getClass().getName())) {
            finish();
            return;
        }
        long epoch = NativeRendererExperimentLaunchAuthority.issueFromLauncher(
            SystemClock.elapsedRealtimeNanos());
        Intent panel = new Intent(Intent.ACTION_MAIN)
            .setComponent(new ComponentName(getPackageName(), CONTROL_PANEL_CLASS))
            .addCategory("com.oculus.intent.category.2D")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(
                NativeRendererExperimentLaunchAuthority.EXTRA_LAUNCH_PROVENANCE,
                NativeRendererExperimentLaunchAuthority.PROVENANCE_EXPLICIT_USER_LAUNCH)
            .putExtra(NativeRendererExperimentLaunchAuthority.EXTRA_LAUNCH_EPOCH, epoch);
        startActivity(panel);
        finish();
    }
}
