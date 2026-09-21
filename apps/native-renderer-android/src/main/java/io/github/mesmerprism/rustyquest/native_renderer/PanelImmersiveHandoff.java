package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Shared, low-rate return-to-immersive lifecycle primitive owned by the Android shell. */
final class PanelImmersiveHandoff {
    interface CompletionListener {
        long currentSessionGeneration();
        void onCompletion(long sessionGeneration, boolean stable);
    }
    private static volatile CompletionListener completionListener;
    static void setCompletionListener(CompletionListener listener) { completionListener = listener; }
    private CompletionListener requestCompletionListener;
    private long requestSessionGeneration;
    private void notifyCompletion(boolean stable) {
        CompletionListener listener = requestCompletionListener;
        requestCompletionListener = null;
        if (listener != null) listener.onCompletion(requestSessionGeneration, stable);
    }
    private static final String TAG = "RQNativeRenderer";
    private static final String STATUS_FILE = "renderer_focus_state.json";
    private static final long POLL_MS = 250L;
    private static final long RELAUNCH_MS = 1000L;
    private static final long TIMEOUT_MS = 4000L;
    private static final long STABLE_MS = 750L;
    private static final long FRESH_MS = 2000L;
    private static final Object APPLICATION_LOCK = new Object();
    private static final AtomicLong NEXT_OWNER_TOKEN = new AtomicLong(1L);
    private static final PanelImmersiveHandoffLifecyclePolicy APPLICATION_LIFECYCLE =
        new PanelImmersiveHandoffLifecyclePolicy();
    private static WeakReference<PanelImmersiveHandoff> activeHandoff =
        new WeakReference<PanelImmersiveHandoff>(null);

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long ownerToken = NEXT_OWNER_TOKEN.getAndIncrement();
    private boolean pending;
    private boolean panelPaused;
    private boolean panelDestroyed;
    private long generation;
    private long baselineFrame = -1L;
    private long startedAtMs;
    private long lastLaunchAtMs;
    private long stableStartedAtMs = -1L;
    private long stableFrame = -1L;
    private Runnable poll;

    PanelImmersiveHandoff(Activity activity) {
        this.activity = activity;
        PanelImmersiveHandoff predecessor;
        PanelImmersiveHandoffLifecyclePolicy.Registration registration;
        synchronized (APPLICATION_LOCK) {
            predecessor = activeHandoff.get();
            registration = APPLICATION_LIFECYCLE.register(ownerToken);
            generation = registration.generation;
            activeHandoff = registration.admitted
                ? new WeakReference<PanelImmersiveHandoff>(this)
                : new WeakReference<PanelImmersiveHandoff>(null);
        }
        if (predecessor != null && predecessor != this) {
            predecessor.cancelForReplacement(generation);
        }
    }

    void request() {
        if (pending) {
            return;
        }
        long requestGeneration;
        synchronized (APPLICATION_LOCK) {
            requestGeneration = APPLICATION_LIFECYCLE.beginRequest(ownerToken);
            if (requestGeneration <= 0L || activeHandoff.get() != this) {
                marker("status=request-rejected reason=inactive-or-terminal ownerToken=" + ownerToken);
                return;
            }
            generation = requestGeneration;
        }
        FocusState baseline = readFocusState();
        requestCompletionListener = completionListener;
        requestSessionGeneration = requestCompletionListener == null
            ? 0L : requestCompletionListener.currentSessionGeneration();
        baselineFrame = isCurrent(baseline) ? baseline.frameCount : -1L;
        startedAtMs = SystemClock.elapsedRealtime();
        lastLaunchAtMs = 0L;
        stableStartedAtMs = -1L;
        stableFrame = -1L;
        panelPaused = false;
        pending = true;
        if (launch("initial", requestGeneration)) {
            schedule(requestGeneration);
        } else {
            pending = false;
            cancelCallbacks();
            notifyCompletion(false);
            releaseOwnershipIfDestroyed();
        }
    }

    /**
     * Opens a new application-lifetime epoch after a verified explicit cold user launch.
     * Activity recreation, internal MAIN intents, and recovery callbacks must never call this.
     */
    boolean admitExplicitColdUserLaunch() {
        PanelImmersiveHandoff predecessor;
        PanelImmersiveHandoffLifecyclePolicy.Registration registration;
        synchronized (APPLICATION_LOCK) {
            predecessor = activeHandoff.get();
            registration = APPLICATION_LIFECYCLE.admitExplicitLaunchEpoch(ownerToken);
            if (!registration.admitted) {
                return false;
            }
            generation = registration.generation;
            activeHandoff = new WeakReference<PanelImmersiveHandoff>(this);
        }
        if (predecessor != null && predecessor != this) {
            predecessor.cancelForReplacement(generation);
        }
        marker("status=explicit-launch-epoch-admitted generation=" + generation
            + " ownerToken=" + ownerToken);
        return true;
    }

    void onPanelPaused() {
        if (pending && isCurrentOwner(generation)) {
            panelPaused = true;
            marker("status=panel-paused generation=" + generation);
        }
    }

    void onPanelDestroyed() {
        panelDestroyed = true;
        if (!pending) {
            cancelCallbacks();
            releaseOwnership();
        } else {
            marker("status=probe-retained-after-destroy generation=" + generation);
        }
    }

    static void cancelForTerminalExit(Activity activity) {
        cancelActiveForTerminalExit();
    }

    static void cancelActiveForTerminalExit() {
        PanelImmersiveHandoff handoff;
        long terminalGeneration;
        synchronized (APPLICATION_LOCK) {
            terminalGeneration = APPLICATION_LIFECYCLE.beginTerminalExit();
            handoff = activeHandoff.get();
            activeHandoff = new WeakReference<PanelImmersiveHandoff>(null);
        }
        if (handoff != null) {
            handoff.cancelForTerminalExit(terminalGeneration);
        }
    }

    private void cancelForTerminalExit(long terminalGeneration) {
        pending = false;
        panelPaused = false;
        generation = terminalGeneration;
        cancelCallbacks();
        marker("status=terminal-cancelled generation=" + generation
            + " recoveryAllowed=false relaunchAllowed=false");
    }

    private void cancelForReplacement(long replacementGeneration) {
        pending = false;
        panelPaused = false;
        generation = replacementGeneration;
        cancelCallbacks();
        marker("status=replacement-cancelled generation=" + replacementGeneration
            + " relaunchAllowed=false");
    }

    private boolean launch(String source, long expectedGeneration) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(activity.getPackageName(), "android.app.NativeActivity"));
        intent.addCategory("com.oculus.intent.category.VR");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        synchronized (APPLICATION_LOCK) {
            // This check and dispatch are atomic with application-wide replacement/terminal exit.
            if (!APPLICATION_LIFECYCLE.canLaunch(ownerToken, expectedGeneration)
                    || activeHandoff.get() != this) {
                marker("status=intent-suppressed source=" + source
                    + " generation=" + expectedGeneration + " reason=stale-or-terminal");
                return false;
            }
            lastLaunchAtMs = SystemClock.elapsedRealtime();
            activity.startActivity(intent);
        }
        marker("status=intent-dispatched source=" + source + " generation=" + expectedGeneration);
        return true;
    }

    private void poll(long expectedGeneration) {
        if (!pending || generation != expectedGeneration || !isCurrentOwner(expectedGeneration)) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        FocusState state = readFocusState();
        boolean qualifies = panelPaused
            && isCurrent(state)
            && "FOCUSED".equals(state.sessionState)
            && state.submitted
            && state.frameCount > Math.max(0L, baselineFrame);
        if (qualifies) {
            if (stableStartedAtMs < 0L) {
                stableStartedAtMs = nowMs;
                stableFrame = state.frameCount;
            } else if (state.frameCount > stableFrame && nowMs - stableStartedAtMs >= STABLE_MS) {
                pending = false;
                cancelCallbacks();
                releaseOwnershipIfDestroyed();
                marker("status=verified frame=" + state.frameCount
                    + " panelPaused=true panelTaskRetained=true generation=" + expectedGeneration);
                notifyCompletion(true);
                return;
            }
        } else {
            stableStartedAtMs = -1L;
            stableFrame = -1L;
        }
        if (nowMs - startedAtMs >= TIMEOUT_MS) {
            pending = false;
            cancelCallbacks();
            releaseOwnershipIfDestroyed();
            marker("status=timeout panelTaskRetained=true panelPaused=" + panelPaused
                + " generation=" + expectedGeneration);
            notifyCompletion(false);
            return;
        }
        if (stableStartedAtMs < 0L && nowMs - lastLaunchAtMs >= RELAUNCH_MS) {
            if (!launch("reassert", expectedGeneration)) {
                pending = false;
                cancelCallbacks();
                notifyCompletion(false);
                releaseOwnershipIfDestroyed();
                return;
            }
        }
        schedule(expectedGeneration);
    }

    private void schedule(final long expectedGeneration) {
        poll = new Runnable() {
            @Override public void run() { poll(expectedGeneration); }
        };
        handler.postDelayed(poll, POLL_MS);
    }

    private void cancelCallbacks() {
        if (poll != null) {
            handler.removeCallbacks(poll);
            poll = null;
        }
    }

    private boolean isCurrentOwner(long expectedGeneration) {
        synchronized (APPLICATION_LOCK) {
            return activeHandoff.get() == this
                && APPLICATION_LIFECYCLE.canLaunch(ownerToken, expectedGeneration);
        }
    }

    private void releaseOwnershipIfDestroyed() {
        if (panelDestroyed) {
            releaseOwnership();
        }
    }

    private void releaseOwnership() {
        synchronized (APPLICATION_LOCK) {
            if (activeHandoff.get() == this) {
                activeHandoff = new WeakReference<PanelImmersiveHandoff>(null);
            }
            APPLICATION_LIFECYCLE.release(ownerToken);
        }
    }

    private FocusState readFocusState() {
        FileInputStream input = null;
        try {
            input = activity.openFileInput(STATUS_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return new FocusState(new JSONObject(body.toString()));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception ignored) { }
            }
        }
    }

    private boolean isCurrent(FocusState state) {
        return state != null
            && "rusty.quest.native_renderer.renderer_focus_state.v1".equals(state.schema)
            && "android.app.NativeActivity".equals(state.activity)
            && state.updatedAtUnixMs > 0L
            && Math.max(0L, System.currentTimeMillis() - state.updatedAtUnixMs) <= FRESH_MS;
    }

    private static void marker(String body) {
        Log.i(TAG, "RUSTY_QUEST_NATIVE_RENDERER channel=panel-immersive-handoff " + body);
    }

    private static final class FocusState {
        final String schema;
        final String activity;
        final String sessionState;
        final long updatedAtUnixMs;
        final long frameCount;
        final boolean submitted;

        FocusState(JSONObject json) {
            schema = json.optString("schema", "");
            activity = json.optString("activity", "");
            sessionState = json.optString("session_state", "");
            updatedAtUnixMs = json.optLong("updated_at_unix_ms", 0L);
            frameCount = json.optLong("frame_count", -1L);
            submitted = json.optBoolean("submitted", false);
        }
    }
}
