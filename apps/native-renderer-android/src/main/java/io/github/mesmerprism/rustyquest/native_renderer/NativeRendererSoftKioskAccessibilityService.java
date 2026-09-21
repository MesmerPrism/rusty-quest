package io.github.mesmerprism.rustyquest.native_renderer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consent-enabled same-APK foreground recovery adapter.
 *
 * <p>The service observes only top-level window focus/state. It never retrieves UI content,
 * injects input, performs a global action, kills a process, writes data, or claims that a session
 * was saved. Third-Home dispatches a typed request to the app-owned panel/controller; that owner
 * must await its recording receipt before finishing app activities.</p>
 */
public final class NativeRendererSoftKioskAccessibilityService extends AccessibilityService {
    private static final String TAG = "RqNativeSoftKiosk";
    private static final long RECOVERY_DELAY_MS = 300L;
    private static final int FOCUS_RELEVANT_WINDOW_CHANGES =
        AccessibilityEvent.WINDOWS_CHANGE_ACTIVE | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED;
    private static final String PANEL_CLASS =
        "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity";
    private static final String EXTRA_PANEL_ROUTE = "native_renderer_panel_route";
    private static final String EXTRA_PANEL_ROUTE_GENERATION =
        "native_renderer_panel_route_generation";
    private static final String EXTRA_PANEL_ROUTE_PROVENANCE =
        "native_renderer_panel_route_provenance";
    private static final String PROVENANCE_SOFT_KIOSK_RECOVERY = "soft-kiosk-recovery";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NativeRendererHomeEpisodePolicy homeEpisodePolicy =
        new NativeRendererHomeEpisodePolicy();
    private final NativeRendererSoftKioskRecoveryTimerGate recoveryTimerGate =
        new NativeRendererSoftKioskRecoveryTimerGate();
    private final NativeRendererSoftKioskCoordinator.TimingObserver timingObserver =
        new NativeRendererSoftKioskCoordinator.TimingObserver() {
            @Override
            public void onGuardTimingChanged(
                    final long generation,
                    final long nextDeadlineMs,
                    final boolean shouldCancelRecovery) {
                Runnable update = new Runnable() {
                    @Override
                    public void run() {
                        if (!adapterConnected) {
                            return;
                        }
                        NativeRendererSoftKioskCoordinator.Snapshot snapshot =
                            coordinator == null ? null : coordinator.snapshot();
                        if (snapshot == null || snapshot.generation != generation) {
                            return;
                        }
                        if (shouldCancelRecovery) {
                            cancelRecovery();
                        }
                        replaceDeadlineTimer(generation, nextDeadlineMs);
                    }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    update.run();
                } else {
                    handler.post(update);
                }
            }
        };
    private NativeRendererSoftKioskCoordinator coordinator;
    private ExecutorService homeResolverExecutor;
    private long homeResolverAttempt;
    private NativeRendererHomeEpisodePolicy.HomeSurface homeSurface;
    private Runnable pendingRecovery;
    private Runnable pendingDeadline;
    private long homePolicyGeneration;
    private boolean adapterConnected;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0L;
        info.flags = 0;
        setServiceInfo(info);

        coordinator = NativeRendererSoftKioskCoordinator.process();
        adapterConnected = true;
        coordinator.setTimingObserver(timingObserver);
        coordinator.updateServiceState(
            NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED);
        coordinator.updateHomeSurfaceState(
            NativeRendererSoftKioskCoordinator.HomeSurfaceState.RESOLVING);
        homeSurface = null;
        resolveHomeSurfaceOffMain();
        Log.i(TAG, "status=service-connected reads_ui_content=false explicit_enablement=true");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || coordinator == null || !shouldObserve(event)) {
            return;
        }
        String packageName = text(event.getPackageName());
        String className = text(event.getClassName());
        if (packageName.isEmpty() || className.isEmpty()) {
            return;
        }
        NativeRendererSoftKioskCoordinator.Snapshot snapshot = coordinator.snapshot();
        if (snapshot.generation <= 0L) {
            return;
        }
        ensureHomePolicy(snapshot.generation);
        long eventMs = event.getEventTime();
        boolean exactAllowedTarget = getPackageName().equals(packageName)
            && NativeRendererForegroundGuardPolicy.isAllowedComponent(className);

        boolean exactHome = false;
        long homeEpisodeId = 0L;
        if (exactAllowedTarget) {
            homeEpisodePolicy.observeAllowedTarget(snapshot.generation, eventMs);
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            NativeRendererHomeEpisodePolicy.Observation home = homeEpisodePolicy.observe(
                snapshot.generation,
                packageName,
                className,
                eventMs);
            if (home.kind == NativeRendererHomeEpisodePolicy.Kind.STALE
                    || home.kind == NativeRendererHomeEpisodePolicy.Kind.CONFIRMED_TARGET_TAIL
                    || home.kind == NativeRendererHomeEpisodePolicy.Kind.REPEATED_EPISODE) {
                return;
            }
            exactHome = home.kind == NativeRendererHomeEpisodePolicy.Kind.NEW_EPISODE;
            homeEpisodeId = exactHome ? home.episodeId : 0L;
        }

        NativeRendererSoftKioskCoordinator.Action action = coordinator.observeForeground(
            packageName,
            className,
            exactAllowedTarget,
            exactHome,
            homeEpisodeId,
            snapshot.generation,
            eventMs);
        apply(action);
    }

    @Override
    public void onInterrupt() {
        adapterConnected = false;
        cancelRecovery();
        cancelDeadline();
        if (coordinator != null) {
            coordinator.updateServiceState(
                NativeRendererSoftKioskCoordinator.ServiceState.INTERRUPTED);
        }
        Log.w(TAG, "status=service-interrupted effective=false");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        adapterConnected = false;
        cancelRecovery();
        cancelDeadline();
        if (coordinator != null) {
            coordinator.clearTimingObserver(timingObserver);
            coordinator.updateServiceState(
                NativeRendererSoftKioskCoordinator.ServiceState.REVOKED);
        }
        shutdownHomeResolver();
        Log.w(TAG, "status=service-unbound effective=false");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        adapterConnected = false;
        cancelRecovery();
        cancelDeadline();
        shutdownHomeResolver();
        if (coordinator != null
                && coordinator.snapshot().serviceState
                    == NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED) {
            coordinator.updateServiceState(
                NativeRendererSoftKioskCoordinator.ServiceState.INTERRUPTED);
        }
        if (coordinator != null) {
            coordinator.clearTimingObserver(timingObserver);
        }
        super.onDestroy();
    }

    private void apply(NativeRendererSoftKioskCoordinator.Action action) {
        switch (action.kind) {
            case RECOVER_IMMERSIVE:
            case RECOVER_PANEL:
                scheduleRecovery(action);
                break;
            case BEGIN_TERMINAL_EXIT:
                // Terminal is already latched and disarmed by the coordinator. Remove every
                // recovery before dispatching the app-owned save/finalize request.
                cancelRecovery();
                NativeRendererExperimentLaunchAuthority.invalidatePending();
                PanelImmersiveHandoff.cancelActiveForTerminalExit();
                dispatchTerminalSaveAndExit(action);
                break;
            case SUPPRESSED_ALLOWED_PROMPT:
                Log.i(TAG, "status=event-suppressed reason=allowed-system-prompt");
                break;
            case SUPPRESSED_TRANSITION:
                Log.i(TAG, "status=event-suppressed reason=bounded-app-transition");
                break;
            case RECOVERY_EXHAUSTED:
                cancelRecovery();
                Log.w(TAG, "status=recovery-exhausted generation=" + action.generation
                    + " recovery_episode=" + action.recoveryEpisodeId
                    + " attempts=" + action.recoveryAttempt);
                break;
            case NOT_EFFECTIVE:
            case NONE:
            default:
                break;
        }
    }

    private void scheduleRecovery(NativeRendererSoftKioskCoordinator.Action observed) {
        final long generation = observed.generation;
        final long recoveryEpisodeId = observed.recoveryEpisodeId;
        if (recoveryEpisodeId <= 0L) {
            return;
        }
        long nowMs = SystemClock.uptimeMillis();
        NativeRendererSoftKioskRecoveryTimerGate.Offer offer = recoveryTimerGate.offer(
            generation, recoveryEpisodeId, nowMs, RECOVERY_DELAY_MS);
        if (offer == NativeRendererSoftKioskRecoveryTimerGate.Offer.UNCHANGED) {
            Log.i(TAG, "status=recovery-coalesced generation=" + generation
                + " recovery_episode=" + recoveryEpisodeId);
            return;
        }
        if (pendingRecovery != null) {
            handler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }
        pendingRecovery = new Runnable() {
            @Override
            public void run() {
                pendingRecovery = null;
                if (!recoveryTimerGate.consume(generation, recoveryEpisodeId)) {
                    return;
                }
                NativeRendererSoftKioskCoordinator.Snapshot snapshot = coordinator.snapshot();
                if (snapshot.generation != generation || snapshot.terminal || !snapshot.armed) {
                    return;
                }
                NativeRendererSoftKioskCoordinator.Action claim =
                    coordinator.claimRecovery(
                        generation,
                        recoveryEpisodeId,
                        SystemClock.uptimeMillis());
                if (claim.kind == NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_IMMERSIVE
                        || claim.kind
                            == NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL) {
                    try {
                        startActivity(recoveryIntent(claim));
                        Log.i(TAG, "status=recovery-requested generation=" + generation
                            + " recovery_episode=" + recoveryEpisodeId
                            + " attempt=" + claim.recoveryAttempt
                            + " destination=" + claim.presentation.name().toLowerCase());
                    } catch (RuntimeException error) {
                        Log.e(TAG, "status=recovery-failed generation=" + generation
                            + " error=" + error.getClass().getSimpleName());
                    }
                }
                if (claim.kind == NativeRendererSoftKioskCoordinator.ActionKind.RECOVERY_EXHAUSTED) {
                    apply(claim);
                }
            }
        };
        long deadlineMs = recoveryTimerGate.deadlineMs();
        handler.postDelayed(
            pendingRecovery,
            deadlineMs == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : Math.max(0L, deadlineMs - SystemClock.uptimeMillis()));
    }

    private Intent recoveryIntent(NativeRendererSoftKioskCoordinator.Action action) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        if (action.kind == NativeRendererSoftKioskCoordinator.ActionKind.RECOVER_PANEL) {
            intent.setComponent(new ComponentName(getPackageName(), PANEL_CLASS));
            intent.addCategory("com.oculus.intent.category.2D");
            intent.putExtra(EXTRA_PANEL_ROUTE, action.panelRoute);
            intent.putExtra(EXTRA_PANEL_ROUTE_GENERATION, action.generation);
            intent.putExtra(EXTRA_PANEL_ROUTE_PROVENANCE, PROVENANCE_SOFT_KIOSK_RECOVERY);
        } else {
            intent.setComponent(new ComponentName(getPackageName(), "android.app.NativeActivity"));
            intent.addCategory("com.oculus.intent.category.VR");
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        return intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void dispatchTerminalSaveAndExit(NativeRendererSoftKioskCoordinator.Action action) {
        Intent intent = new Intent(NativeRendererSoftKioskCoordinator.ACTION_TERMINAL_SAVE_AND_EXIT)
            .setComponent(new ComponentName(getPackageName(), PANEL_CLASS))
            .addCategory("com.oculus.intent.category.2D")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(
                NativeRendererSoftKioskCoordinator.EXTRA_TERMINAL_ROUTE,
                NativeRendererSoftKioskCoordinator.TERMINAL_ROUTE_SAVE_AND_EXIT)
            .putExtra(
                NativeRendererSoftKioskCoordinator.EXTRA_GUARD_GENERATION,
                action.generation)
            .putExtra(
                NativeRendererSoftKioskCoordinator.EXTRA_HOME_EPISODE,
                action.homeEpisodeId);
        try {
            startActivity(intent);
            Log.i(TAG, "status=terminal-save-exit-requested saved=false generation="
                + action.generation + " home_episode=" + action.homeEpisodeId);
        } catch (RuntimeException error) {
            Log.e(TAG, "status=terminal-save-exit-dispatch-failed saved=false generation="
                + action.generation + " error=" + error.getClass().getSimpleName());
        }
    }

    private void cancelRecovery() {
        if (pendingRecovery != null) {
            handler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }
        recoveryTimerGate.clear();
    }

    private void replaceDeadlineTimer(final long generation, final long deadlineMs) {
        cancelDeadline();
        if (deadlineMs == Long.MIN_VALUE || coordinator == null || !adapterConnected) {
            return;
        }
        long nowMs = SystemClock.uptimeMillis();
        final long delayMs = deadlineMs >= Long.MAX_VALUE - 1L
            ? Long.MAX_VALUE
            : Math.max(0L, deadlineMs + 1L - nowMs);
        pendingDeadline = new Runnable() {
            @Override
            public void run() {
                pendingDeadline = null;
                NativeRendererSoftKioskCoordinator.Snapshot snapshot = coordinator.snapshot();
                if (snapshot.generation != generation) {
                    return;
                }
                NativeRendererSoftKioskCoordinator.Action expiry =
                    coordinator.evaluateDeadline(generation, SystemClock.uptimeMillis());
                if (expiry.deadlineExpired) {
                    Log.i(TAG, "status=guard-deadline-expired generation=" + generation
                        + " action=" + expiry.kind.name().toLowerCase());
                }
                apply(expiry);
                NativeRendererSoftKioskCoordinator.Snapshot after = coordinator.snapshot();
                if (after.generation == generation) {
                    replaceDeadlineTimer(generation, after.nextDeadlineMs);
                }
            }
        };
        handler.postDelayed(pendingDeadline, delayMs);
    }

    private void cancelDeadline() {
        if (pendingDeadline != null) {
            handler.removeCallbacks(pendingDeadline);
            pendingDeadline = null;
        }
    }

    private void ensureHomePolicy(long generation) {
        if (homePolicyGeneration == generation) {
            return;
        }
        homePolicyGeneration = generation;
        homeEpisodePolicy.reset(generation, homeSurface);
    }

    private void resolveHomeSurfaceOffMain() {
        shutdownHomeResolver();
        final long attempt = ++homeResolverAttempt;
        homeResolverExecutor = Executors.newSingleThreadExecutor();
        homeResolverExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final NativeRendererHomeEpisodePolicy.HomeSurface resolved = resolveHomeSurface();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (attempt != homeResolverAttempt
                                || coordinator.snapshot().serviceState
                                    != NativeRendererSoftKioskCoordinator.ServiceState.CONNECTED) {
                            return;
                        }
                        homeSurface = resolved;
                        homePolicyGeneration = 0L;
                        coordinator.updateHomeSurfaceState(resolved == null
                            ? NativeRendererSoftKioskCoordinator.HomeSurfaceState.UNAVAILABLE
                            : NativeRendererSoftKioskCoordinator.HomeSurfaceState.RESOLVED);
                        Log.i(TAG, resolved == null
                            ? "status=home-surface-unavailable triple_home_effective=false"
                            : "status=home-surface-resolved package=" + resolved.packageName
                                + " class=" + resolved.className);
                    }
                });
            }
        });
    }

    private NativeRendererHomeEpisodePolicy.HomeSurface resolveHomeSurface() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(
            query,
            PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
        if (activity == null || text(activity.packageName).isEmpty() || text(activity.name).isEmpty()) {
            return null;
        }
        String className = activity.name;
        if (className.startsWith(".")) {
            className = activity.packageName + className;
        } else if (className.indexOf('.') < 0) {
            className = activity.packageName + "." + className;
        }
        return new NativeRendererHomeEpisodePolicy.HomeSurface(activity.packageName, className);
    }

    private void shutdownHomeResolver() {
        homeResolverAttempt += 1L;
        if (homeResolverExecutor != null) {
            homeResolverExecutor.shutdownNow();
            homeResolverExecutor = null;
        }
    }

    private static boolean shouldObserve(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return true;
        }
        return event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            && (event.getWindowChanges() == 0
                || (event.getWindowChanges() & FOCUS_RELEVANT_WINDOW_CHANGES) != 0);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
