package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** One real native owner and native main loop; delegates original product UI callbacks. */
public final class NativeProductPanelActivity extends NativeActivity {
    private BreathCompositionPanelModule controller;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ControlPanelActivity.originalPanelHostCreated(this);
        controller = BreathCompositionPanelModule.createFor(this, true, state);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); if(current())controller.onNewIntent(intent); }
    @Override protected void onSaveInstanceState(Bundle state) { if(current())controller.onSaveInstanceState(state); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); if(current())controller.onResume(); }
    @Override protected void onPause() { if(current())controller.onPause(); super.onPause(); }
    private boolean current(){return controller!=null&&controller.hostCurrent();}
    @Override protected void onDestroy() { BreathCompositionPanelModule retired=controller;controller=null;if(retired!=null)retired.onDestroy(); super.onDestroy(); }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); if(current())controller.onActivityResult(request,result,data); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(current())controller.onRequestPermissionsResult(request,permissions,results); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return current()&&controller.dispatchKeyEvent(event)||super.dispatchKeyEvent(event); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return current()&&controller.dispatchGenericMotionEvent(event)||super.dispatchGenericMotionEvent(event); }
}
