package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Closed manifest-selected physical native owner; no Intent/runtime class selector. */
final class NativeRendererActivityComponents {
    static final String STOCK_NATIVE = "android.app.NativeActivity";
    static final String PRODUCT_NATIVE = "io.github.mesmerprism.rustyquest.native_renderer.NativeProductPanelActivity";
    static final String SHOWCASE_NATIVE = "io.github.mesmerprism.rustyquest.native_renderer.ShowcaseNativeActivity";
    static final String SHOWCASE_METADATA = "rusty.quest.native_renderer.showcase_panel_native_host";
    static final String PRODUCT_METADATA = "rusty.quest.native_renderer.original_panel_native_host";
    static String nativeClass(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            boolean original=info.metaData!=null&&info.metaData.getBoolean(PRODUCT_METADATA,false);
            boolean showcase=info.metaData!=null&&info.metaData.getBoolean(SHOWCASE_METADATA,false);
            if(original&&showcase)throw new IllegalStateException("ambiguous-native-activity-selection");
            return showcase?SHOWCASE_NATIVE:original?PRODUCT_NATIVE:STOCK_NATIVE;
        } catch (PackageManager.NameNotFoundException impossible) {
            throw new IllegalStateException("selected-native-activity-unavailable", impossible);
        }
    }
    static boolean retainedNative(String name){return PRODUCT_NATIVE.equals(name)||SHOWCASE_NATIVE.equals(name);}
    static boolean showcaseSelected(Context context){return context!=null&&(SHOWCASE_NATIVE.equals(context.getClass().getName())||SHOWCASE_NATIVE.equals(nativeClass(context)));}
    static boolean isNative(Activity activity) {
        return activity != null && nativeClass(activity).equals(activity.getClass().getName());
    }
    /** Pure guard retains its logical IMMERSIVE component; physical receipt stays exact. */
    static String policyClass(String physical) { return retainedNative(physical) ? STOCK_NATIVE : physical; }
}
