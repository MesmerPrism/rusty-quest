package io.github.mesmerprism.rustyquest.native_renderer;
import android.app.NativeActivity;
import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.ContextThemeWrapper;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/** Genuine single NativeActivity; retained controls never replace its XR window. */
public final class ShowcaseNativeActivity extends NativeActivity {
    private static WeakReference<ShowcaseNativeActivity> owner=new WeakReference<>(null);
    private ShowcaseControls controls;
    private boolean current;
    private boolean resumed;
    private Boolean requestedVisibility;
    private BooleanSupplier currentProducer;
    @Override protected void onCreate(Bundle state){super.onCreate(state);owner=new WeakReference<>(this);current=true;controls=new ShowcaseControls(this,new ShowcaseControls.Host(){public boolean current(){return ShowcaseNativeActivity.this.current&&currentProducer!=null&&currentProducer.getAsBoolean()&&!isFinishing()&&!isDestroyed();}public void closePanel(){requestVisibility(false);}
        @Override public EditText createEditor(Context context){return new MeshEditor(context);}
        @Override public Spinner createSpinner(Context context,String label){return new MeshChoice(context,label);}
    });ShowcaseNativeBridge.initialize(this);}
    private final class MeshChoice extends Spinner {
        private final String label;

        MeshChoice(Context context,String label) {
            super(context,Spinner.MODE_DIALOG);
            this.label=label;
        }

        @Override public boolean performClick() {
            ShowcaseNativeActivity activity=ShowcaseNativeActivity.this;
            BooleanSupplier producer=currentProducer;
            SpinnerAdapter choices=getAdapter();
            if (!showcaseOwnerResumed() || producer==null || !producer.getAsBoolean()
                    || !isAttachedToWindow() || !isShown() || !isEnabled()
                    || choices==null || choices.getCount()==0
                    || !NativeMeshUiProducer.panelVisibilityConfirmed(activity,true)) return false;

            CharSequence[] labels=new CharSequence[choices.getCount()];
            for (int i=0;i<labels.length;i++) {
                labels[i]=ShowcaseControls.displayChoice(String.valueOf(choices.getItem(i)));
            }
            Context themed=new ContextThemeWrapper(getContext(),activity.getTheme());
            AlertDialog dialog=new AlertDialog.Builder(themed)
                .setTitle(label)
                .setSingleChoiceItems(labels,getSelectedItemPosition(),new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog,int selected) {
                        if (showcaseOwnerResumed() && currentProducer==producer && producer.getAsBoolean()
                                && isAttachedToWindow() && isShown() && isEnabled() && getAdapter()==choices
                                && NativeMeshUiProducer.panelVisibilityConfirmed(activity,true)) {
                            setSelection(selected);
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("Cancel",null)
                .create();
            NativeMeshUiProducer.showProductDialog(activity,dialog);
            return true;
        }
    }

    /** Keep the real editor and route a completed Vulkan tap through the existing IME bridge. */
    private final class MeshEditor extends EditText {
        MeshEditor(Context context) {
            super(context);
            setShowSoftInputOnFocus(false);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            boolean handled = super.onTouchEvent(event);
            BooleanSupplier producer = currentProducer;
            if (handled && producer != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
                post(new Runnable() {
                    @Override public void run() {
                        ShowcaseNativeActivity activity = ShowcaseNativeActivity.this;
                        if (showcaseOwnerResumed() && currentProducer == producer
                                && producer.getAsBoolean() && isShown()
                                && NativeMeshUiProducer.panelVisibilityConfirmed(activity, true)) {
                            NativeMeshUiProducer.requestProductTextInput(activity, MeshEditor.this);
                        }
                    }
                });
            }
            return handled;
        }
    }

    static ShowcaseNativeActivity current(){ShowcaseNativeActivity value=owner.get();return value!=null&&value.current&&!value.isFinishing()&&!value.isDestroyed()?value:null;}
    /** Producer startup may follow onResume; focus alone is not this lifecycle witness. */
    public boolean showcaseOwnerResumed(){return current&&resumed&&!isFinishing()&&!isDestroyed();}
    static AutoCloseable attach(Activity actual,final LinearLayout carrier,final BooleanSupplier producer)throws Exception {
        final ShowcaseNativeActivity value=current();
        if(actual!=value||value==null||value.controls==null||producer==null||!producer.getAsBoolean())throw new IllegalStateException("retained-production-panel-owner-unavailable");
        value.currentProducer=producer;
        final View form=value.controls.attach(carrier.getContext());ViewGroup previous=(ViewGroup)form.getParent();if(previous!=null)previous.removeView(form);carrier.addView(form,new LinearLayout.LayoutParams(-1,-1));
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable(){public void run(){if(value.requestedVisibility!=null&&producer.getAsBoolean())value.requestVisibility(value.requestedVisibility);}});
        return new NativeMeshUiProducer.ProductPanelAttachment(){private boolean closed;public Activity owner(){return value;}public boolean presentationActive(){return !closed&&producer.getAsBoolean()&&ShowcaseNativeActivity.current()==value;}public void visibilityChanged(boolean visible){value.controls.visibility(visible);}public void outcome(String detail){/* submitted visibility remains in the producer receipt */}public void close(){if(closed)return;closed=true;if(form.getParent()==carrier)carrier.removeView(form);if(value.currentProducer==producer)value.currentProducer=null;value.controls.visibility(false);}};
    }
    void requestVisibility(final boolean visible){requestedVisibility=visible;runOnUiThread(new Runnable(){public void run(){if(!current)return;try{NativeMeshUiProducer.requestProductPanelVisibility(ShowcaseNativeActivity.this,visible);}catch(Exception unavailable){android.util.Log.w("RQShowcase","showcase-panel-visibility-unavailable");}}});}
    static void open(){ShowcaseNativeActivity value=current();if(value!=null)value.requestVisibility(true);}
    static void toggle(){ShowcaseNativeActivity value=current();if(value==null)return;org.json.JSONObject status=NativeMeshUiProducer.productPanelVisibility(value);if("applied".equals(status.optString("status")))value.requestVisibility(!status.optBoolean("visible"));else if(value.requestedVisibility==null)value.requestVisibility(true);}
    @Override protected void onResume(){super.onResume();resumed=true;if(controls!=null){controls.resumed();NativeMeshUiProducer.productOwnerReady(this);}}
    @Override protected void onPause(){resumed=false;if(controls!=null)controls.paused();super.onPause();}
    @Override protected void onDestroy(){current=false;NativeMeshUiProducer.retireActivityProducer(this);if(controls!=null){controls.close();controls=null;}if(owner.get()==this)owner=new WeakReference<>(null);super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(controls!=null)controls.permissions(code,permissions,results);}
}
