package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Closed manifest-selected physical native owner; no Intent/runtime class selector. */
final class NativeRendererActivityComponents {
    static final String STOCK_NATIVE = "android.app.NativeActivity";
    static final String PRODUCT_NATIVE = "io.github.mesmerprism.rustyquest.native_renderer.NativeProductPanelActivity";
    static final String PRODUCT_METADATA = "rusty.quest.native_renderer.original_panel_native_host";
    static String nativeClass(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            return info.metaData != null && info.metaData.getBoolean(PRODUCT_METADATA, false) ? PRODUCT_NATIVE : STOCK_NATIVE;
        } catch (PackageManager.NameNotFoundException impossible) {
            throw new IllegalStateException("selected-native-activity-unavailable", impossible);
        }
    }
    static boolean isNative(Activity activity) {
        return activity != null && nativeClass(activity).equals(activity.getClass().getName());
    }
    /** Pure guard retains its logical IMMERSIVE component; physical receipt stays exact. */
    static String policyClass(String physical) { return PRODUCT_NATIVE.equals(physical) ? STOCK_NATIVE : physical; }
}
