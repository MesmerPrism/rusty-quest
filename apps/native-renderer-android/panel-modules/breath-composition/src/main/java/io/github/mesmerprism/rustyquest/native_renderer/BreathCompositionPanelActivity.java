package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Original 2D Android owner; product builders and handlers live once in the controller. */
public class BreathCompositionPanelActivity extends Activity implements PanelModule {
    public static final String MODULE_ID = BreathCompositionPanelModule.MODULE_ID;
    private BreathCompositionPanelModule controller;
    public final String panelModuleId() { return MODULE_ID; }
    public static AutoCloseable attachNativeMeshSessionControls(Activity actual,android.widget.LinearLayout carrier,java.util.function.BooleanSupplier current) {
        return BreathCompositionPanelModule.attachNativeMeshSessionControls(actual,carrier,current);
    }
    public static boolean ownsPanelActivity(Activity actual) { return BreathCompositionPanelModule.controllerFor(actual)!=null; }
    public static void resumeNativeMeshImmersive(Activity actual) { BreathCompositionPanelModule.resumeNativeMeshImmersive(actual); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(BreathCompositionPanelModule.deliverToNativeOwner(getIntent(),true)){finish();return;}
        controller = BreathCompositionPanelModule.createFor(this, false, state);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); if(current())controller.onNewIntent(intent); }
    @Override protected void onSaveInstanceState(Bundle state) { if(current())controller.onSaveInstanceState(state); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); if (current()) controller.onResume(); }
    @Override protected void onPause() { if (current()) controller.onPause(); super.onPause(); }
    private boolean current(){return controller!=null&&controller.hostCurrent();}
    @Override protected void onDestroy() { BreathCompositionPanelModule retired=controller;controller=null;if(retired!=null)retired.onDestroy(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request,result,data); if(current())controller.onActivityResult(request,result,data); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(current())controller.onRequestPermissionsResult(request,permissions,results); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return current()&&controller.dispatchKeyEvent(event)||super.dispatchKeyEvent(event); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return current()&&controller.dispatchGenericMotionEvent(event)||super.dispatchGenericMotionEvent(event); }
}
