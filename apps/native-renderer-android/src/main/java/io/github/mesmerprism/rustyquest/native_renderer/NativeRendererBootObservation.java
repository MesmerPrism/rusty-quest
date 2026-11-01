package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

/** Bounded app-private observations only; never launch admission or presentation evidence. */
final class NativeRendererBootObservation {
    private static final String STORE = "native_boot_observation";
    private static final String SELECTION_METADATA = "rusty.quest.experiment.boot_autostart";

    enum Admission {
        NOT_SELECTED("not-selected"), OVERLAY_MISSING("overlay-missing"),
        BOOT_COUNT_UNAVAILABLE("boot-count-unavailable"), ALREADY_ATTEMPTED("already-attempted"),
        STORAGE_FAILED("admission-storage-failed"), ADMITTED("admitted"),
        PLATFORM_CHECK_FAILED("platform-check-failed");
        final String reason;
        Admission(String reason) { this.reason = reason; }
    }

    private NativeRendererBootObservation() { }

    static synchronized void received(Context context) {
        try {
            Receipt receipt = new Receipt(context, bootCount(context));
            receipt.editor.putInt("receiver_deliveries", increment(receipt.integer("receiver_deliveries")))
                .putString("receiver_last_reason", "received");
            receipt.first("receiver_first");
            receipt.stamp("receiver_last");
            receipt.save();
        } catch (RuntimeException unavailable) { /* Diagnostics cannot change launch behavior. */ }
    }

    static synchronized void admission(Context context, int bootCount, Admission outcome) {
        try {
            Receipt receipt = new Receipt(context, bootCount);
            receipt.editor.putString("receiver_last_reason", outcome.reason);
            if (outcome == Admission.ADMITTED) {
                receipt.editor.putString("admission_outcome", "committed");
                receipt.first("admission");
            } else if (outcome == Admission.STORAGE_FAILED && receipt.integer("admission_order") == 0) {
                receipt.editor.putString("admission_outcome", "storage-failed");
            }
            receipt.save();
        } catch (RuntimeException unavailable) { /* No admission reads this store. */ }
    }

    static synchronized void platformCheckFailed(Context context) {
        try {
            admission(context, bootCount(context), Admission.PLATFORM_CHECK_FAILED);
        } catch (RuntimeException unavailable) { }
    }

    static synchronized void dispatch(Context context, int bootCount, boolean requested) {
        try {
            Receipt receipt = new Receipt(context, bootCount);
            receipt.editor.putString("dispatch_outcome", requested
                ? "requested-unconfirmed" : "platform-launch-failed");
            receipt.stamp("dispatch");
            receipt.save();
        } catch (RuntimeException unavailable) { /* No retry or launch effect. */ }
    }

    static void created(Activity activity, boolean ownPanel) { activity(activity, "created", false, ownPanel); }
    static void resumed(Activity activity, boolean ownPanel) { activity(activity, "resumed", false, ownPanel); }
    static void trustedLaunch(Activity activity) { activity(activity, "trusted_launch", true, false); }

    private static synchronized void activity(Activity activity, String event, boolean trusted, boolean ownPanel) {
        try {
            if (activity == null || !activity.getApplicationContext().getPackageName()
                    .equals(activity.getPackageName())) return;
            ApplicationInfo info = activity.getPackageManager().getApplicationInfo(
                activity.getPackageName(), PackageManager.GET_META_DATA);
            if (info.metaData == null || !info.metaData.getBoolean(
                    SELECTION_METADATA, false)) return;
            boolean nativeOwner = NativeRendererActivityComponents.isNative(activity);
            if (!nativeOwner && (trusted || !ownPanel)) return;
            Receipt receipt = new Receipt(activity, bootCount(activity));
            if (nativeOwner) receipt.editor.putString("native_component", activity.getClass().getName());
            receipt.first(trusted ? "trusted_launch" : (nativeOwner ? "native_" : "panel_") + event);
            receipt.save();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // A missing observation must never suppress an Activity callback or trusted epoch.
        }
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : Math.max(0, value) + 1;
    }

    /** One fixed schema, replaced at the first observation of another boot identity. */
    private static final class Receipt {
        final SharedPreferences state;
        final SharedPreferences.Editor editor;
        final boolean fresh;
        final int order;
        final long elapsedMs;

        Receipt(Context context, int bootCount) {
            state = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
            fresh = state.getInt("boot_count", -1) != bootCount;
            editor = state.edit();
            if (fresh) editor.clear();
            // Unknown identity remains explicitly -1. It cannot join observations to a real boot.
            if (fresh || state.getString("schema", "").isEmpty()) {
                editor.putString("schema", "rusty.quest.boot_observation.v1")
                    .putInt("boot_count", bootCount).putInt("receiver_deliveries", 0)
                    .putString("receiver_last_reason", "not-observed")
                    .putString("admission_outcome", "not-admitted")
                    .putString("dispatch_outcome", "not-requested")
                    .putString("native_component", "not-observed");
                for (String event : new String[]{"receiver_first", "receiver_last", "admission", "dispatch",
                        "native_created", "native_resumed", "trusted_launch", "panel_created", "panel_resumed"}) {
                    editor.putInt(event + "_order", 0).putLong(event + "_elapsed_ms", -1L);
                }
            }
            order = increment(integer("observation_sequence"));
            elapsedMs = SystemClock.elapsedRealtime();
            editor.putInt("observation_sequence", order);
        }

        int integer(String key) { return fresh ? 0 : state.getInt(key, 0); }
        void first(String event) { if (integer(event + "_order") == 0) stamp(event); }
        void stamp(String event) {
            editor.putInt(event + "_order", order).putLong(event + "_elapsed_ms", elapsedMs);
        }
        void save() { editor.commit(); } // Best effort only; never feeds launch admission.
    }
}
