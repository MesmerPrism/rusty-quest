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
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Shared, low-rate return-to-immersive lifecycle primitive owned by the Android shell. */
final class PanelImmersiveHandoff {
    private static final String TAG = "RQNativeRenderer";
    private static final String STATUS_FILE = "renderer_focus_state.json";
    private static final long POLL_MS = 250L;
    private static final long RELAUNCH_MS = 1000L;
    private static final long TIMEOUT_MS = 4000L;
    private static final long STABLE_MS = 750L;
    private static final long FRESH_MS = 2000L;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pending;
    private boolean panelPaused;
    private int generation;
    private long baselineFrame = -1L;
    private long startedAtMs;
    private long lastLaunchAtMs;
    private long stableStartedAtMs = -1L;
    private long stableFrame = -1L;
    private Runnable poll;

    PanelImmersiveHandoff(Activity activity) {
        this.activity = activity;
    }

    void request() {
        if (pending) {
            return;
        }
        FocusState baseline = readFocusState();
        baselineFrame = isCurrent(baseline) ? baseline.frameCount : -1L;
        startedAtMs = SystemClock.elapsedRealtime();
        lastLaunchAtMs = 0L;
        stableStartedAtMs = -1L;
        stableFrame = -1L;
        panelPaused = false;
        pending = true;
        generation += 1;
        launch("initial", generation);
        schedule(generation);
    }

    void onPanelPaused() {
        if (pending) {
            panelPaused = true;
            marker("status=panel-paused generation=" + generation);
        }
    }

    void onPanelDestroyed() {
        if (!pending) {
            cancel();
        } else {
            marker("status=probe-retained-after-destroy generation=" + generation);
        }
    }

    private void launch(String source, int expectedGeneration) {
        lastLaunchAtMs = SystemClock.elapsedRealtime();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(activity.getPackageName(), "android.app.NativeActivity"));
        intent.addCategory("com.oculus.intent.category.VR");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        marker("status=intent-dispatched source=" + source + " generation=" + expectedGeneration);
    }

    private void poll(int expectedGeneration) {
        if (!pending || generation != expectedGeneration) {
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
                cancel();
                marker("status=verified frame=" + state.frameCount
                    + " panelPaused=true panelTaskRetained=true generation=" + expectedGeneration);
                return;
            }
        } else {
            stableStartedAtMs = -1L;
            stableFrame = -1L;
        }
        if (nowMs - startedAtMs >= TIMEOUT_MS) {
            pending = false;
            cancel();
            marker("status=timeout panelTaskRetained=true panelPaused=" + panelPaused
                + " generation=" + expectedGeneration);
            return;
        }
        if (stableStartedAtMs < 0L && nowMs - lastLaunchAtMs >= RELAUNCH_MS) {
            launch("reassert", expectedGeneration);
        }
        schedule(expectedGeneration);
    }

    private void schedule(final int expectedGeneration) {
        poll = new Runnable() {
            @Override public void run() { poll(expectedGeneration); }
        };
        handler.postDelayed(poll, POLL_MS);
    }

    private void cancel() {
        if (poll != null) {
            handler.removeCallbacks(poll);
            poll = null;
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
