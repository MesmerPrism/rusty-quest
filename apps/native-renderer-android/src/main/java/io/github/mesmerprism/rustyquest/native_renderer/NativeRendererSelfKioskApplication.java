package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Selected manifest owner: observes this APK only; no global foreground inspection. */
public final class NativeRendererSelfKioskApplication extends Application {
    private final Map<String, WeakReference<Activity>> activities = new HashMap<>();
    private final Map<String, Boolean> resumed = new HashMap<>();
    private final Map<String, Long> departures = new HashMap<>();
    private long panelLeaveGeneration;

    @Override public void onCreate() {
        super.onCreate();
        NativeRendererSoftKioskCoordinator.process().useSelfWatchdog();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { remember(a); }
            @Override public void onActivityStarted(Activity a) { remember(a); }
            @Override public void onActivityResumed(Activity a) {
                if (!own(a)) return;
                remember(a);
                resumed.put(a.getClass().getName(), true);
                departures.remove(a.getClass().getName());
                NativeRendererSelfKioskService.resumePendingTerminal(a);
            }
            @Override public void onActivityPaused(Activity a) {
                if (!own(a)) return;
                String name = a.getClass().getName();
                resumed.put(name, false);
                NativeRendererSoftKioskCoordinator c = NativeRendererSoftKioskCoordinator.process();
                long generation = c.snapshot().generation;
                if (!a.isChangingConfigurations() && !a.isFinishing()
                        && !c.selfRecoverySuppressed(SystemClock.uptimeMillis())
                        && (NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY.equals(name)
                            || panelLeaveGeneration == generation)) {
                    departures.put(name, generation);
                }
                panelLeaveGeneration = 0L;
            }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) {
                if (!own(a)) return;
                WeakReference<Activity> current = activities.get(a.getClass().getName());
                if (current != null && current.get() == a) {
                    activities.remove(a.getClass().getName());
                    resumed.remove(a.getClass().getName());
                    departures.remove(a.getClass().getName());
                }
            }
        });
    }

    private boolean own(Activity activity) {
        return activity != null && getPackageName().equals(activity.getPackageName())
            && NativeRendererForegroundGuardPolicy.isAllowedComponent(activity.getClass().getName());
    }

    private void remember(Activity a) {
        if (own(a)) activities.put(a.getClass().getName(), new WeakReference<>(a));
    }

    boolean isResumed(String component) { return Boolean.TRUE.equals(resumed.get(component)); }

    boolean hasWindowFocus(String component) {
        WeakReference<Activity> reference = activities.get(component);
        Activity activity = reference == null ? null : reference.get();
        return activity != null && isResumed(component) && activity.hasWindowFocus();
    }

    boolean hasDeparture(String component, long generation) {
        Long observed = departures.get(component);
        return observed != null && observed.longValue() == generation;
    }

    void discardDepartures() { departures.clear(); panelLeaveGeneration = 0L; }

    /**
     * Finish every other live Activity owned by this APK after durable terminal cleanup.
     *
     * <p>The panel may be a non-root Activity above NativeActivity in the same task, and Quest's
     * task organizer may omit or reshape task inventory. Retaining weak lifecycle references gives
     * terminal exit a direct fallback without inspecting or controlling any other application.</p>
     */
    public static int finishOwnedActivitiesForTerminalExit(Activity terminalActivity) {
        if (terminalActivity == null
                || !(terminalActivity.getApplicationContext()
                    instanceof NativeRendererSelfKioskApplication)) {
            return 0;
        }
        NativeRendererSelfKioskApplication owner =
            (NativeRendererSelfKioskApplication) terminalActivity.getApplicationContext();
        ArrayList<Activity> targets = new ArrayList<>();
        for (WeakReference<Activity> reference : owner.activities.values()) {
            Activity candidate = reference == null ? null : reference.get();
            if (candidate != null && candidate != terminalActivity && owner.own(candidate)
                    && !candidate.isFinishing()) {
                targets.add(candidate);
            }
        }
        int dispatched = 0;
        for (Activity candidate : targets) {
            Log.i("RustyQuestSelfKiosk", "status=terminal-owned-activity-finish-dispatched"
                + " activity=" + candidate.getClass().getName()
                + " task_id=" + candidate.getTaskId());
            candidate.finishAndRemoveTask();
            dispatched += 1;
        }
        return dispatched;
    }

    public static void armed(Context context) {
        if (!(context.getApplicationContext() instanceof NativeRendererSelfKioskApplication)) return;
        try {
            context.startForegroundService(new Intent(context, NativeRendererSelfKioskService.class));
        } catch (RuntimeException error) {
            Log.w("RustyQuestSelfKiosk", "status=watchdog-start-failed", error);
        }
    }

    public static void userLeaveHint(Activity activity) {
        Context app = activity.getApplicationContext();
        if (!(app instanceof NativeRendererSelfKioskApplication)) return;
        NativeRendererSelfKioskApplication owner = (NativeRendererSelfKioskApplication) app;
        if (owner.own(activity)) owner.panelLeaveGeneration =
            NativeRendererSoftKioskCoordinator.process().snapshot().generation;
    }

    public static void beginSystemPrompt(Activity activity) {
        Context app = activity.getApplicationContext();
        if (!(app instanceof NativeRendererSelfKioskApplication)) return;
        ((NativeRendererSelfKioskApplication) app).discardDepartures();
        NativeRendererSoftKioskCoordinator.process().beginSelfSystemPrompt(SystemClock.uptimeMillis());
    }

    public static void endSystemPrompt(Activity activity) {
        if (!(activity.getApplicationContext() instanceof NativeRendererSelfKioskApplication)) return;
        ((NativeRendererSelfKioskApplication) activity.getApplicationContext()).discardDepartures();
        NativeRendererSoftKioskCoordinator.process().endSelfSystemPrompt();
    }

    public static void requestSaveAndExit(Activity activity) {
        if (!(activity.getApplicationContext() instanceof NativeRendererSelfKioskApplication)) return;
        NativeRendererSoftKioskCoordinator.Action action =
            NativeRendererSoftKioskCoordinator.process().requestExplicitTerminalExit();
        if (action.kind == NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT) {
            NativeRendererSelfKioskService.dispatchTerminal(activity, action);
        } else {
            NativeRendererSelfKioskService.resumePendingTerminal(activity);
        }
    }
}
