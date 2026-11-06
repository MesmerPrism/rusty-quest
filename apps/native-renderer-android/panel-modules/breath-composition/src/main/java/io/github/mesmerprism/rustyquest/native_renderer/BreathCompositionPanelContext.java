package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Actual Activity context and presentation ports for the shared original controller.
 * This is a Context wrapper, never an Activity or an Android lifecycle owner. */
abstract class BreathCompositionPanelContext extends ContextThemeWrapper {
    private final Activity activity;
    private final boolean nativePresentation;
    private View content;
    private Intent panelIntent;
    private boolean retired;

    BreathCompositionPanelContext(Activity activity, boolean nativePresentation) {
        super(activity, activity.getTheme());
        if (activity == null) throw new IllegalArgumentException("actual-activity-required");
        this.activity = activity;
        this.nativePresentation = nativePresentation;
        this.panelIntent = activity.getIntent();
    }
    final Activity hostActivity() { return activity; }
    final boolean nativePresentation() { return nativePresentation; }
    final boolean hostCurrent() { return !retired && !activity.isFinishing() && !activity.isDestroyed(); }
    final void retireHost() { retired = true; }
    final Application getApplication() { return activity.getApplication(); }
    final Intent getIntent() { return nativePresentation ? panelIntent : activity.getIntent(); }
    final void setIntent(Intent intent) {
        panelIntent = intent;
        // A UI route is not a replacement physical MAIN/LAUNCHER launch.
        if (!nativePresentation) activity.setIntent(intent);
    }
    final boolean isFinishing() { return retired || activity.isFinishing(); }
    final boolean isDestroyed() { return retired || activity.isDestroyed(); }
    final void runOnUiThread(Runnable runnable) { activity.runOnUiThread(runnable); }
    final View hostContent() { return content; }
    public void setContentView(View view) {
        content = view;
        // NativeActivity's content belongs to android_native_app_glue. Only its
        // Presentation carrier may display the original product controls.
        if (!nativePresentation) activity.setContentView(view);
    }
    final View findViewById(int id) { return nativePresentation ? content == null ? null : content.findViewById(id) : activity.findViewById(id); }
    final void startActivityForResult(Intent intent, int requestCode) { activity.startActivityForResult(intent, requestCode); }
    protected void onCreate(Bundle state) { }
    protected void onSaveInstanceState(Bundle state) { }
    protected void onNewIntent(Intent intent) { }
    protected void onResume() { }
    protected void onPause() { }
    protected void onDestroy() { retireHost(); }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) { }
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { }
    public boolean dispatchKeyEvent(KeyEvent event) { return false; }
    public boolean dispatchGenericMotionEvent(MotionEvent event) { return false; }
}
