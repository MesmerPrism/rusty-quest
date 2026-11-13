package io.github.mesmerprism.rustyquest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicBoolean;

/** NativeActivity calls start only after creating the single immutable-generation native reader. */
public final class NativeMetaInputDiagnostic {
    static {
        // NativeActivity loads the entry library for its native lifecycle.
        // Java native methods also need this application classloader's load.
        System.loadLibrary("rusty_quest_native_renderer");
    }

    private static final String TAG = "RustyQuestMetaInput";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static Context ownContext;
    private static ServiceConnection ownConnection;
    private static boolean bindInvoked;
    private static final NativeHeldReviewGate.Cleanup OWN_UNBIND=new NativeHeldReviewGate.Cleanup();

    private NativeMetaInputDiagnostic() {}

    public static native Surface nativeAcquireSurface(long generation);
    public static native long nativeGetGeneration();
    public static native long[] nativeGetDescriptor(long generation);
    public static native boolean nativeSetMode(long generation, int mode, long sequence);
    public static native long[] nativeGetRenderStatus(long generation);
    public static native boolean nativeHeldReviewEnabled();
    public static native long nativeHeldReviewGeneration();
    public static native boolean nativeRequestHeldReview(String request);
    public static native String nativeHeldReviewStatus();
    static void disarmForReview() { ARMED.set(false); }

    public static void start(Context context) {
        final Context app = context.getApplicationContext();
        if (app == null || app.getApplicationInfo().uid != Process.myUid()
                || (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new SecurityException("own debuggable application context required");
        }
        if (!STARTED.compareAndSet(false, true)) return; // One reader/binding lifetime per process.
        if (nativeGetGeneration() <= 0) throw new IllegalStateException("native reader not initialized");
        ARMED.set(true);
        if (!MAIN.post(new Runnable() {
            @Override public void run() { bindOwn(app); }
        })) {
            ARMED.set(false);
            throw new IllegalStateException("publisher main dispatch unavailable");
        }
    }

    private static void bindOwn(Context app) {
        if (!ARMED.get()) return;
        ownContext = app;
        ownConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Log.i(TAG, "publisher binding connected armed=" + ARMED.get()
                        + " binderPresent=" + (binder != null));
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                ARMED.set(false); Log.i(TAG, "publisher binding disconnected");
            }
            @Override public void onBindingDied(ComponentName name) {
                ARMED.set(false); unbindOwn();
            }
            @Override public void onNullBinding(ComponentName name) {
                ARMED.set(false); unbindOwn();
            }
        };
        // The Shell peeker must use this exact explicit component, with no action/data/categories.
        Intent intent = new Intent(app, NativeMetaInputSurfaceService.class);
        bindInvoked = true;
        try {
            boolean accepted = app.bindService(intent, ownConnection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "publisher bind returned accepted=" + accepted);
            if (!accepted) { ARMED.set(false); unbindOwn(); }
        } catch (RuntimeException error) {
            ARMED.set(false); Log.e(TAG, "publisher bind failed", error); unbindOwn();
        }
    }

    /** Disarms immediately; unbinds only the app's exact connection on the main queue. */
    public static void stop(Context unused) {
        ARMED.set(false);
        unbindOwn();
    }

    private static void unbindOwn() {
        OWN_UNBIND.request(Looper.myLooper()==Looper.getMainLooper(),MAIN::post,()->{
            if(!bindInvoked)return;
            if(ownContext==null||ownConnection==null)throw new IllegalStateException("binding owner missing");
            bindInvoked=false; // No replay; the original owner retains any failed/unknown result.
            ownContext.unbindService(ownConnection);Log.i(TAG,"publisher own unbind returned");
        });
    }
    /** Render-owner join only. Main-thread callers observe Pending without blocking that queue. */
    public static int awaitHeldReviewCleanup() {
        return OWN_UNBIND.await(Looper.myLooper()==Looper.getMainLooper(),2000);
    }

    static boolean isArmed() { return ARMED.get(); }
}
