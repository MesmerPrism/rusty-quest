package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/** Manifest-selected receiver. It never changes permissions or launches another package. */
public final class NativeRendererBootReceiver extends BroadcastReceiver {
    private static final String TAG = "RustyQuestBootLaunch";

    @Override public void onReceive(Context context, Intent received) {
        if (context == null || received == null
                || !NativeRendererBootLaunchPolicy.BOOT_ACTION.equals(received.getAction())) return;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
            boolean selected = info.metaData != null && info.metaData.getBoolean(
                NativeRendererBootLaunchPolicy.SELECTION_METADATA, false);
            boolean overlayGranted = Settings.canDrawOverlays(context);
            int bootCount = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
            SharedPreferences state = context.getSharedPreferences("native_boot_launch", Context.MODE_PRIVATE);
            if (!NativeRendererBootLaunchPolicy.admits(received.getAction(), selected, overlayGranted,
                    bootCount, state.getInt("last_attempted_boot_count", -1))) {
                Log.i(TAG, "status=not-dispatched selected=" + selected
                    + " overlay_granted=" + overlayGranted + " boot_count=" + bootCount);
                return;
            }
            // Persist admission before the effect; a duplicate boot delivery never replays it.
            if (!state.edit().putInt("last_attempted_boot_count", bootCount).commit()) {
                Log.w(TAG, "status=not-dispatched reason=admission-storage-failed");
                return;
            }
            Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(context.getPackageName(), "android.app.NativeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            Log.i(TAG, "status=launch-requested-unconfirmed boot_count=" + bootCount);
        } catch (Exception failure) {
            Log.w(TAG, "status=not-confirmed reason=platform-launch-failed", failure);
        }
    }
}
