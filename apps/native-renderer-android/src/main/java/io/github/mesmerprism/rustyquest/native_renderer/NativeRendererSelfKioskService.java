package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Same-process watchdog: the process-local coordinator remains the only guard authority. */
public final class NativeRendererSelfKioskService extends Service {
    private static final String TAG = "RustyQuestSelfKiosk";
    private static final String CHANNEL = "native_renderer_self_kiosk";
    private static final Object TERMINAL_LOCK = new Object();
    private static NativeRendererSoftKioskCoordinator.Action pendingTerminal;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NativeRendererSelfKioskDeparturePolicy departures = new NativeRendererSelfKioskDeparturePolicy();
    private final NativeRendererSoftKioskCoordinator coordinator = NativeRendererSoftKioskCoordinator.process();
    private long nextAttemptMs;
    private long observedGeneration;
    private long generationStartedUnixMs;
    private long missingSinceMs = -1L;
    private long lastDepartureId;
    private boolean returnPending;
    private static volatile String readback = "watchdog starting; return unverified";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            evaluate();
            NativeRendererSoftKioskCoordinator.Snapshot state = coordinator.snapshot();
            if (hasPendingTerminal() || (state.armed && !state.terminal))
                handler.postDelayed(this, 250L);
        }
    };

    public static String readback() { return readback; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "App session guard",
            NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Session guard active")
            .setContentText("Returns this app to its active session; exit in the app controls.")
            .setOngoing(true).build();
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startForeground(60612, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(60612, notification);
        coordinator.useSelfWatchdog();
        coordinator.updateServiceState(NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        handler.removeCallbacks(tick);
        handler.post(tick);
        // A dead process loses its explicit launch authority; never silently re-arm on restart.
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        coordinator.updateServiceState(NativeRendererSoftKioskCoordinator.ServiceState.INTERRUPTED);
        super.onDestroy();
    }

    private void evaluate() {
        NativeRendererSoftKioskCoordinator.Snapshot state = coordinator.snapshot();
        long now = SystemClock.uptimeMillis();
        if (state.terminal || !state.armed) {
            if (hasPendingTerminal()) {
                if (now >= nextAttemptMs) {
                    nextAttemptMs = now + 1_500L;
                    resumePendingTerminal(this);
                }
                return;
            }
            stopSelf();
            return;
        }
        if (!(getApplication() instanceof NativeRendererSelfKioskApplication)) { stopSelf(); return; }
        NativeRendererSelfKioskApplication app = (NativeRendererSelfKioskApplication) getApplication();
        if (observedGeneration != state.generation) {
            observedGeneration = state.generation;
            generationStartedUnixMs = System.currentTimeMillis();
            nextAttemptMs = 0L;
            missingSinceMs = -1L;
            returnPending = false;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean protectedState = power == null || !power.isInteractive()
            || (keyguard != null && keyguard.isKeyguardLocked());
        boolean suppressed = protectedState || coordinator.selfRecoverySuppressed(now);
        String component = state.presentation.componentClass;
        boolean present = app.isResumed(component) && (state.presentation
            == NativeRendererForegroundGuardPolicy.Presentation.IMMERSIVE
                ? rendererFocused() : app.hasWindowFocus(component));
        long departure = departures.observe(state.generation, present,
            app.hasDeparture(component, state.generation), suppressed, now);
        if (departure > 0L && departure != lastDepartureId) {
            lastDepartureId = departure;
            Log.i(TAG, "status=own-departure generation=" + state.generation + " episode=" + departure
                + " component=" + component + " physical_home=false");
        }
        if (suppressed) {
            app.discardDepartures();
            missingSinceMs = -1L;
            readback = "suspended for transition, permission, sleep or lock";
            return;
        }
        if (present) {
            coordinator.observeOwnSurface(component, state.generation, now);
            missingSinceMs = -1L;
            if (returnPending) {
                returnPending = false;
                Log.i(TAG, "status=self-return-confirmed generation=" + state.generation
                    + " component=" + component + " evidence=own-lifecycle-and-presentation");
            }
            readback = "own presentation confirmed; overlay access "
                + (Settings.canDrawOverlays(this) ? "granted" : "absent (background return unverified)");
            return;
        }
        if (missingSinceMs < 0L) missingSinceMs = now;
        // Focus-only absence can request recovery but is never counted as a Home-like departure.
        if (now - missingSinceMs < 750L) return;
        NativeRendererSoftKioskCoordinator.Action action =
            coordinator.observeSelfDeparture(state.generation, departure, now);
        if (action.kind == NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT) {
            handler.removeCallbacksAndMessages(null);
            dispatchTerminal(this, action);
            return;
        }
        if (action.kind == NativeRendererSoftKioskCoordinator.ActionKind.RECOVERY_EXHAUSTED) {
            readback = "return unconfirmed; retry budget exhausted";
            return;
        }
        if (now < nextAttemptMs) return;
        NativeRendererSoftKioskCoordinator.Action claimed = coordinator.claimRecovery(
            state.generation, action.recoveryEpisodeId, now);
        if (claimed.kind != NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE
                && claimed.kind != NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL) return;
        nextAttemptMs = now + 1_500L;
        try {
            startActivity(recoveryIntent(claimed));
            returnPending = true;
            readback = "return requested; awaiting own lifecycle/focus confirmation";
            Log.i(TAG, "status=self-return-requested confirmed=false attempt=" + claimed.recoveryAttempt
                + " generation=" + claimed.generation + " can_draw_overlays=" + Settings.canDrawOverlays(this)
                + " departure_source=own_lifecycle physical_home=false");
        } catch (RuntimeException error) {
            readback = "return blocked; " + error.getClass().getSimpleName();
            Log.w(TAG, "status=self-return-failed confirmed=false", error);
        }
    }

    private boolean rendererFocused() {
        File file = new File(getFilesDir(), "renderer_focus_state.json");
        if (file.length() <= 0L || file.length() > 16_384L) return false;
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int used = 0;
            int count;
            while (used < buffer.length && (count = stream.read(buffer, used, buffer.length - used)) > 0)
                used += count;
            JSONObject state = new JSONObject(new String(buffer, 0, used, StandardCharsets.UTF_8));
            long stamp = state.optLong("updated_at_unix_ms", 0L);
            long age = System.currentTimeMillis() - stamp;
            return "rusty.quest.native_renderer.renderer_focus_state.v1".equals(state.optString("schema"))
                && NativeRendererForegroundGuardPolicy.NATIVE_ACTIVITY.equals(state.optString("activity"))
                && "FOCUSED".equals(state.optString("session_state"))
                && state.optBoolean("submitted", false) && state.optLong("frame_count", -1L) >= 0L
                && stamp >= generationStartedUnixMs && age >= 0L && age <= 3_000L;
        } catch (Exception ignored) { return false; }
    }

    private Intent recoveryIntent(NativeRendererSoftKioskCoordinator.Action action) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
            .setComponent(new ComponentName(getPackageName(), action.presentation.componentClass))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action.presentation == NativeRendererForegroundGuardPolicy.Presentation.PANEL) {
            intent.addCategory("com.oculus.intent.category.2D")
                .putExtra("native_renderer_panel_route", action.panelRoute)
                .putExtra("native_renderer_panel_route_generation", action.generation)
                .putExtra("native_renderer_panel_route_provenance", "soft-kiosk-recovery");
        } else intent.addCategory("com.oculus.intent.category.VR").addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    static void dispatchTerminal(Context context, NativeRendererSoftKioskCoordinator.Action action) {
        if (action.kind != NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT) return;
        synchronized (TERMINAL_LOCK) {
            if (pendingTerminal == null) pendingTerminal = action;
        }
        NativeRendererExperimentLaunchAuthority.invalidatePending();
        PanelImmersiveHandoff.cancelActiveForTerminalExit();
        resumePendingTerminal(context);
    }

    static boolean resumePendingTerminal(Context context) {
        NativeRendererSoftKioskCoordinator.Action action;
        synchronized (TERMINAL_LOCK) { action = pendingTerminal; }
        if (action == null || context == null) return false;
        Intent intent = new Intent(NativeRendererSoftKioskCoordinator.ACTION_TERMINAL_SAVE_AND_EXIT)
            .setComponent(new ComponentName(context.getPackageName(),
                NativeRendererForegroundGuardPolicy.CONTROL_PANEL_ACTIVITY))
            .addCategory("com.oculus.intent.category.2D")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(NativeRendererSoftKioskCoordinator.EXTRA_TERMINAL_ROUTE,
                NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT)
            .putExtra(NativeRendererSoftKioskCoordinator.EXTRA_GUARD_GENERATION, action.generation)
            .putExtra(NativeRendererSoftKioskCoordinator.EXTRA_HOME_EPISODE, action.homeEpisodeId);
        try {
            context.startActivity(intent);
            readback = "terminal save and exit pending acknowledgement; saved=false";
            Log.i(TAG, "status=terminal-save-exit-requested admitted=false saved=false physical_home=false"
                + " generation=" + action.generation + " departure=" + action.homeEpisodeId);
            return true;
        } catch (RuntimeException error) {
            readback = "terminal dispatch failed; saved=false";
            Log.e(TAG, "status=terminal-save-exit-dispatch-failed saved=false", error);
            return false;
        }
    }

    static void acknowledgeTerminalIntent(long generation, long departureId) {
        synchronized (TERMINAL_LOCK) {
            if (pendingTerminal == null || pendingTerminal.generation != generation
                    || pendingTerminal.homeEpisodeId != departureId) return;
            pendingTerminal = null;
        }
        readback = "terminal save and exit admitted; awaiting writer acknowledgement";
        Log.i(TAG, "status=terminal-save-exit-admitted saved=false generation=" + generation
            + " departure=" + departureId);
    }

    private static boolean hasPendingTerminal() {
        synchronized (TERMINAL_LOCK) { return pendingTerminal != null; }
    }
}
