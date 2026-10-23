package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.EditText;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** App-owned Android widgets rendered into the native OpenXR mesh texture, not another Activity. */
public final class NativeMeshUiProducer {
    // NativeActivity's platform dlopen is not this application's JNI classloader registration.
    // Register explicitly so widget callbacks resolve without another optional Java bridge.
    static { System.loadLibrary("rusty_quest_native_renderer"); }
    private static final String TAG = "RQNativeMeshUi";
    private static volatile NativeMeshUiProducer current;
    private static volatile Activity producerActivity;
    private static volatile long cancelledGeneration;
    private static final HashMap<Long, StopReceipt> stopReceipts = new HashMap<Long, StopReceipt>();
    private static final class StopReceipt { volatile int state; }
    private final Activity activity;
    private final long generation;
    private final Surface surface;
    private VirtualDisplay display;
    private Presentation presentation;
    private View root;
    private View cursor;
    private TextView status;
    private long downTime;
    private boolean down;
    private long lastMove;
    private final AtomicInteger queuedPointers = new AtomicInteger();
    private volatile boolean pointerQueueInvalid;

    private NativeMeshUiProducer(Activity activity, Surface surface, long generation) {
        this.activity = activity;
        this.surface = surface;
        this.generation = generation;
    }

    public static void start(Activity activity, Surface surface, int width, int height, long generation) {
        if (width != 1024 || height != 768 || generation <= 0 || !surface.isValid()) {
            surface.release();
            return;
        }
        producerActivity = activity;
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            if (cancelledGeneration == generation || activity.isFinishing() || activity.isDestroyed()) {
                surface.release(); return;
            }
            if (current != null && !current.close()) {
                surface.release();
                Log.e(TAG, "event=producer-failed reason=previous-producer-not-retired");
                return;
            }
            NativeMeshUiProducer owner = new NativeMeshUiProducer(activity, surface, generation);
            current = owner;
            try {
                DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
                owner.display = manager.createVirtualDisplay("Rusty Quest mesh UI", width, height, 160, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                if (owner.display == null) throw new IllegalStateException("VirtualDisplay unavailable");
                owner.presentation = new Presentation(activity, owner.display.getDisplay()) {
                    @Override protected void onCreate(Bundle state) {
                        super.onCreate(state);
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        owner.build(this.getContext());
                        setContentView(owner.root);
                    }
                };
                owner.presentation.show();
                Log.i(TAG, "event=producer-started generation=" + generation + " source=android-presentation width=1024 height=768");
            } catch (RuntimeException error) {
                Log.e(TAG, "event=producer-failed generation=" + generation + " reason=" + error.getClass().getSimpleName());
                if (owner.close() && current == owner) current = null;
            }
        }});
    }

    private void build(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 24, 40, 24);
        layout.setBackgroundColor(Color.rgb(24, 30, 38));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        FrameLayout frame = new FrameLayout(context);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        cursor = new View(context);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.WHITE);
        dot.setStroke(2, Color.rgb(0, 120, 255));
        cursor.setBackground(dot);
        cursor.setClickable(false);
        cursor.setFocusable(false);
        cursor.setVisibility(View.INVISIBLE);
        frame.addView(cursor, new FrameLayout.LayoutParams(16, 16));
        root = frame;
        TextView title = text(context, "Native world-space integration", 27);
        layout.addView(title);
        status = text(context, "Live Android UI • waiting for control", 18);
        layout.addView(status);
        if (nativeFeedbackSelected()) {
            title.setText("Native development hotload");
            final TextView feedback = text(context, "Waiting for render owner", 18);
            layout.addView(feedback);
            button(context, layout, "Compute with current CPU generation (input 4)", 14);
            EditText notes = new EditText(context);
            notes.setHint("Retained UI text / focus test");
            notes.setTextColor(Color.WHITE);
            notes.setSingleLine(true);
            layout.addView(notes, new LinearLayout.LayoutParams(-1, 76));
            feedback.post(new Runnable() { @Override public void run() {
                if (current != NativeMeshUiProducer.this || root == null) return;
                String value = nativeFeedbackStatus(generation);
                feedback.setText(value == null ? "Native readback unavailable" : value);
                feedback.postDelayed(this, 500);
            }});
        }
        button(context, layout, "Start / stop diagnostic animation", 1);
        button(context, layout, "Recorded reference overlay (not selected in this test)", 2);
        button(context, layout, "Toggle joint and wireframe diagnostics", 3);
        button(context, layout, "Toggle fingertip graft copies", 4);
        layout.addView(text(context, "Particle size", 18));
        SeekBar scale = new SeekBar(context);
        scale.setMax(1000);
        scale.setProgress(500);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) submit(5, value / 1000.0f); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(scale, new LinearLayout.LayoutParams(-1, 76));
        button(context, layout, "Panel farther / nearer", 6);
        button(context, layout, "Panel larger / smaller", 7);
        button(context, layout, "Toggle camera output", 8);
        button(context, layout, "Toggle particles", 9);
        button(context, layout, "Toggle base hand meshes", 10);
        button(context, layout, "Cycle effect index 0–6", 11);
        layout.addView(text(context, "Graft size", 18));
        SeekBar graft = new SeekBar(context);
        graft.setMax(1000); graft.setProgress(474);
        if (nativeFeedbackSelected()) graft.setEnabled(false);
        graft.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) submit(12, value / 1000.0f); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(graft, new LinearLayout.LayoutParams(-1, 76));
        button(context, layout, "Reset panel pose and size", 13);
        layout.addView(text(context, "Hand pinch or controller trigger: click / drag.", 16));
    }

    private static TextView text(Context context, String value, int size) {
        TextView text = new TextView(context);
        text.setText(value); text.setTextSize(size); text.setTextColor(Color.WHITE);
        text.setPadding(0, 4, 0, 4);
        return text;
    }

    private void button(Context context, LinearLayout parent, String label, int code) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        // This combined test is locked to live hands; no unselected replay feature is activated.
        if (code == 2) button.setEnabled(false);
        if (nativeFeedbackSelected() && (code == 3 || code == 4 || code == 8 || code == 10 || code == 11)) button.setEnabled(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { submit(code, 1.0f); }
        });
        parent.addView(button, new LinearLayout.LayoutParams(-1, 68));
    }

    private void submit(int code, float value) {
        boolean accepted = nativeControl(generation, code, value);
        status.setText("Control " + code + (accepted ? " accepted by native state" : " rejected") + " • value " + value);
    }

    /** All events are generation-bound and delivered on the Android UI thread. */
    public static void pointer(long generation, int action, float x, float y, long eventMillis) {
        NativeMeshUiProducer owner = current;
        if (owner == null || owner.generation != generation || action < 0 || action > 5
                || !Float.isFinite(x) || !Float.isFinite(y)) return;
        if (owner.queuedPointers.incrementAndGet() > 16) {
            owner.queuedPointers.decrementAndGet();
            owner.pointerQueueInvalid = true;
            return;
        }
        owner.activity.runOnUiThread(new Runnable() { @Override public void run() {
            try {
            if (current != owner || owner.root == null) return;
            if (owner.pointerQueueInvalid) { owner.cancelPointer(); return; }
            long now = SystemClock.uptimeMillis();
            if (action == 5) { owner.cursor.setVisibility(View.INVISIBLE); return; }
            owner.cursor.setTranslationX(Math.max(0, Math.min(1008, x - 8)));
            owner.cursor.setTranslationY(Math.max(0, Math.min(752, y - 8)));
            owner.cursor.setVisibility(View.VISIBLE);
            if (action == 4) return;
            int motion;
            if (action == 0) {
                if (owner.down) return;
                owner.down = true; owner.downTime = now; motion = MotionEvent.ACTION_DOWN;
            } else if (action == 1) {
                if (!owner.down || now - owner.lastMove < 8) return;
                owner.lastMove = now; motion = MotionEvent.ACTION_MOVE;
            } else {
                if (!owner.down) return;
                owner.down = false; motion = action == 2 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL;
            }
            MotionEvent event = MotionEvent.obtain(owner.downTime, now, motion,
                    Math.max(0, Math.min(1023, x)), Math.max(0, Math.min(767, y)), 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            owner.root.dispatchTouchEvent(event);
            event.recycle();
            } finally {
                if (owner.queuedPointers.decrementAndGet() == 0) owner.pointerQueueInvalid = false;
            }
        }});
    }

    /** Admission only. Native must pump NativeActivity events while polling stopStatus. */
    public static boolean requestStop(long generation) {
        if (generation <= 0) return false;
        cancelledGeneration = generation;
        Activity activity = producerActivity;
        if (activity == null) return false;
        final StopReceipt receipt;
        synchronized (stopReceipts) {
            if (stopReceipts.containsKey(generation)) return true;
            if (stopReceipts.size() >= 8) return false;
            receipt = new StopReceipt();
            stopReceipts.put(generation, receipt);
        }
        Log.i(TAG, "event=producer-stop-requested generation=" + generation);
        // Always enqueue behind any pending start, even if current is still null.
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            boolean released = false;
            try {
                NativeMeshUiProducer owner = current;
                if (owner == null || owner.generation != generation) { released = true; }
                else if (owner.close()) { current = null; released = true; }
            } finally {
                receipt.state = released ? 1 : -1;
                Log.i(TAG, "event=producer-stop-ack generation=" + generation + " released=" + released);
            }
        }});
        return true;
    }

    /** 0 pending/unknown, 1 all writer resources closed, -1 close failed. Generation-specific. */
    public static int stopStatus(long generation) {
        synchronized (stopReceipts) {
            StopReceipt receipt = stopReceipts.get(generation);
            if (receipt == null) return 0;
            int result = receipt.state;
            if (result != 0) stopReceipts.remove(generation);
            return result;
        }
    }

    private boolean close() {
        boolean released = true;
        try { cancelPointer(); } catch (RuntimeException error) { released = false; }
        try { if (presentation != null) { presentation.dismiss(); presentation = null; } }
        catch (RuntimeException error) { released = false; }
        try { if (display != null) { display.release(); display = null; } }
        catch (RuntimeException error) { released = false; }
        try { surface.release(); } catch (RuntimeException error) { released = false; }
        root = null;
        Log.i(TAG, "event=producer-stopped generation=" + generation + " released=" + released);
        return released;
    }

    private void cancelPointer() {
        if (down && root != null) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            root.dispatchTouchEvent(event); event.recycle(); down = false;
        }
        if (cursor != null) cursor.setVisibility(View.INVISIBLE);
    }

    private static native boolean nativeControl(long generation, int code, float value);
    private static native boolean nativeFeedbackSelected();
    private static native String nativeFeedbackStatus(long generation);
}
