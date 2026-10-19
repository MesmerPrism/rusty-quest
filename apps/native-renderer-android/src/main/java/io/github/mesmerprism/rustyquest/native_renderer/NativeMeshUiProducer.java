package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.CheckBox;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.json.JSONArray;

/** App-owned Android widgets rendered into the native OpenXR mesh texture, not another Activity. */
public final class NativeMeshUiProducer {
    /** The retained production Activity owns all actions; this adapter owns its pixels only. */
    public interface ProductPanelAttachment extends AutoCloseable {
        Activity owner();
        default boolean presentationActive(){return true;}
        void visibilityChanged(boolean visible);
        void outcome(String detail);
    }
static final class VisibilityReadback {
    static Boolean applied(JSONObject receipt,long generation,long requestRevision){
        if(receipt==null||generation<=0||requestRevision<0
                ||!"rusty.quest.native_mesh_ui.panel_visibility.v1".equals(receipt.opt("schema"))
                ||!"applied".equals(receipt.opt("status"))
                ||!"native-submitted-frame".equals(receipt.opt("evidence"))
                ||!Boolean.FALSE.equals(receipt.opt("visible_confirmation")))return null;
        Object owner=receipt.opt("generation"),requested=receipt.opt("request_revision"),effective=receipt.opt("effective_revision");
        if(!integer(owner)||!integer(requested)||!integer(effective))return null;
        long revision=((Number)requested).longValue();
        if(((Number)owner).longValue()!=generation||revision<0
                ||requestRevision>0&&revision!=requestRevision
                ||((Number)effective).longValue()!=revision)return null;
        Object visible=receipt.opt("effective_visible"),target=receipt.opt("requested_visible");
        return visible instanceof Boolean&&visible.equals(target)?(Boolean)visible:null;
    }
    private static boolean integer(Object value){return value instanceof Integer||value instanceof Long;}
}

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
    private Surface surface;
    private float rasterScale;
    private int rasterWidth,rasterHeight,densityDpi;
    private long rasterEpoch=1;
    private RasterLayout rootRaster;
    private final android.os.Handler resizePolls=new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile ResizeRequest resizeRequest;
    private boolean resizeStopped;
    private final ArrayList<Surface> uncertainSurfaces=new ArrayList<Surface>();
    private final java.util.WeakHashMap<Window,Float> rasterWindows=new java.util.WeakHashMap<Window,Float>();
    /** Keep the original pixel layout and resource density; raster scale changes sampling only. */
    private static final class RasterLayout extends FrameLayout {
        private float scale;
        RasterLayout(Context context,View child,float scale){
            super(context);this.scale=scale;addView(child,new FrameLayout.LayoutParams(-1,-1));
            child.setPivotX(0);child.setPivotY(0);child.setScaleX(scale);child.setScaleY(scale);
        }
        void setRasterScale(float value){
            scale=value;View child=getChildAt(0);child.setPivotX(0);child.setPivotY(0);
            child.setScaleX(value);child.setScaleY(value);requestLayout();invalidate();
        }
        private int logicalSpec(int spec){return View.MeasureSpec.makeMeasureSpec(
            Math.round(View.MeasureSpec.getSize(spec)/scale),View.MeasureSpec.getMode(spec));}
        @Override protected void onMeasure(int width,int height){
            super.onMeasure(logicalSpec(width),logicalSpec(height));
            setMeasuredDimension(Math.round(getMeasuredWidth()*scale),Math.round(getMeasuredHeight()*scale));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            super.onLayout(changed,0,0,Math.round((right-left)/scale),Math.round((bottom-top)/scale));
        }
    }
    private View rasterLayout(View child){return new RasterLayout(child.getContext(),child,rasterScale);}
    private static int scaledOffset(int value,float ratio){return Math.round(value*ratio);}
    private static int scaledDimension(int value,float ratio){return value>0?scaledOffset(value,ratio):value;}
    private void scaleModalContent(Window window){
        ViewGroup content=window.findViewById(android.R.id.content);
        if(content==null||content.getChildCount()!=1)throw new IllegalStateException("mesh-modal-content-unavailable");
        View child=content.getChildAt(0);
        RasterLayout wrapped=child instanceof RasterLayout?(RasterLayout)child:null;
        float prior=wrapped==null?1f:wrapped.scale;
        float ratio=rasterScale/prior;
        if(ratio==1f)return;
        ViewGroup.LayoutParams params=child.getLayoutParams();
        // Values belong to the physical parent. Existing wrappers already have prior-scale spacing.
        params.width=scaledDimension(params.width,ratio);params.height=scaledDimension(params.height,ratio);
        if(params instanceof ViewGroup.MarginLayoutParams){
            ViewGroup.MarginLayoutParams margins=(ViewGroup.MarginLayoutParams)params;
            margins.leftMargin=scaledOffset(margins.leftMargin,ratio);margins.topMargin=scaledOffset(margins.topMargin,ratio);
            margins.rightMargin=scaledOffset(margins.rightMargin,ratio);margins.bottomMargin=scaledOffset(margins.bottomMargin,ratio);
        }
        if(wrapped!=null){wrapped.setRasterScale(rasterScale);wrapped.setLayoutParams(params);}
        else{content.removeView(child);content.addView(rasterLayout(child),params);}
    }
    private int rasterOffset(int logical){return scaledOffset(logical,rasterScale);}
    private int rasterDimension(int logical){return scaledDimension(logical,rasterScale);}
    private float windowRasterRatio(Window window){
        Float prior=rasterWindows.get(window);return rasterScale/(prior==null?1f:prior.floatValue());
    }
    private void scaleWindowPlacement(Window window,WindowManager.LayoutParams attributes){
        float ratio=windowRasterRatio(window);if(ratio==1f)return;
        // MATCH_PARENT/WRAP_CONTENT, gravity and fractional margins retain platform semantics.
        attributes.width=scaledDimension(attributes.width,ratio);attributes.height=scaledDimension(attributes.height,ratio);
        attributes.x=scaledOffset(attributes.x,ratio);attributes.y=scaledOffset(attributes.y,ratio);
    }
    private void scaleWindowInsets(Window window){
        float ratio=windowRasterRatio(window);
        if(ratio!=1f){
            View decor=window.getDecorView();
            decor.setPadding(scaledOffset(decor.getPaddingLeft(),ratio),scaledOffset(decor.getPaddingTop(),ratio),
                scaledOffset(decor.getPaddingRight(),ratio),scaledOffset(decor.getPaddingBottom(),ratio));
            decor.setMinimumWidth(scaledOffset(decor.getMinimumWidth(),ratio));
            decor.setMinimumHeight(scaledOffset(decor.getMinimumHeight(),ratio));
        }
        rasterWindows.put(window,Float.valueOf(rasterScale));
    }    private VirtualDisplay display;
    private Presentation presentation;
    private View root;
    private AutoCloseable panelAttachment;
    private View cursor;
    private TextView status;
    private long downTime;
    private boolean down;
    private long lastMove;
    private final AtomicInteger queuedPointers = new AtomicInteger();
    private volatile boolean pointerQueueInvalid;
    private final android.os.Handler visibilityPolls = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean visibilityObserved;
    private boolean effectiveVisible;
    private long visibilityRequest;
    private final IdentityHashMap<Dialog,Long> windowIds=new IdentityHashMap<Dialog,Long>();
    private long nextWindowId=1,widgetRevision;
    private String widgetSignature="";
    private final ArrayList<Dialog> modals = new ArrayList<Dialog>();
    private Dialog pointerDialog;
    private Dialog keyboard;
    private NativeSystemKeyboard systemKeyboard;
    private long editorEpoch;
    private EditText editor;
    private TextInputSession input;
    private EditorInfo editorInfo;
    private boolean capitals, symbols;
    private MeshActionMode selectionMode;
    private LinearLayout productCarrier;
    private TextView productTitle;
    private Class<?> productModule;
    private boolean productSelectionResolved;
    private boolean awaitingProductOwner;
    private OwnerAttachmentState ownerAttachment = new OwnerAttachmentState();
    private android.app.Application.ActivityLifecycleCallbacks ownerLifecycle;
    private final ThreadLocal<BootstrapReturnScope> bootstrapReturn=new ThreadLocal<BootstrapReturnScope>();

    /** One original-facade visibility shortcut, not a blanket visibility suppression. */
    static final class BootstrapReturnScope {
        private final Object owner;
        private boolean bypassed;
        BootstrapReturnScope(Object owner){this.owner=owner;}
        boolean bypass(Object candidate,boolean visible){
            if(visible||candidate!=owner||bypassed)return false;
            bypassed=true;return true;
        }
    }

    /** Immutable input route origin, captured before leaving the renderer thread. */
    static final class ProductRouteIdentity {
        final Object producer,activity;
        final long generation;
        ProductRouteIdentity(Object producer,Object activity,long generation){
            this.producer=producer;this.activity=activity;this.generation=generation;
        }
        boolean matches(Object currentProducer,Object actualActivity,long currentGeneration){
            return generation>0&&producer!=null&&activity!=null&&producer==currentProducer
                &&activity==actualActivity&&generation==currentGeneration;
        }
    }
    static Object captureProductRouteOwner(Activity actual,long generation){
        NativeMeshUiProducer producer=current;
        return producer!=null&&producer.activity==actual&&producer.generation==generation&&generation>0
            ?new ProductRouteIdentity(producer,actual,generation):null;
    }
    static boolean productRouteOwnerCurrent(Object captured,Activity actual){
        NativeMeshUiProducer producer=productProducer(actual);
        return captured instanceof ProductRouteIdentity&&producer!=null
            &&((ProductRouteIdentity)captured).matches(producer,actual,producer.generation)
            &&((ProductPanelAttachment)producer.panelAttachment).presentationActive();
    }

    /** Current-generation presentation rendezvous only; owns no session or command replay. */
    static final class OwnerAttachmentState {
        private Object owner;
        private boolean bootstrapIssued, returnIssued, resumed, retired;
        private Boolean desiredVisibility;
        synchronized boolean bootstrap() { if(retired||bootstrapIssued)return false;bootstrapIssued=true;return true; }
        synchronized boolean ready(Object candidate) {
            if(retired||candidate==null||owner!=null&&owner!=candidate)return false;
            owner=candidate;return true;
        }
        synchronized boolean returnToImmersive() {
            if(retired||owner==null||!bootstrapIssued&&resumed||returnIssued)return false;
            returnIssued=true;return true;
        }
        /** Android multi-resume is not immersive focus. A bootstrap still owes
         * its one original handoff. Never hold this state monitor over callbacks. */
        void ownerReady(Object candidate,Runnable originalHandoff,Runnable attach){
            if(!ready(candidate))return;
            if(returnToImmersive())originalHandoff.run();
            if(resumed())attach.run();
        }
        synchronized void resumed(boolean value) { if(!retired)resumed=value; }
        synchronized boolean resumed() { return !retired&&resumed; }
        synchronized boolean visibility(Object candidate,boolean visible) {
            if(retired||owner!=candidate||candidate==null)return false;
            desiredVisibility=visible;return true;
        }
        synchronized Boolean takeVisibility(Object candidate) {
            if(retired||owner!=candidate)return null;
            Boolean value=desiredVisibility;desiredVisibility=null;return value;
        }
        synchronized void destroyed(Object candidate) { if(owner==candidate){owner=null;desiredVisibility=null;returnIssued=false;} }
        synchronized void retire() { retired=true;owner=null;desiredVisibility=null; }
    }

    /** Readback includes explicitly static controls only, never arbitrary displayed text. */
    static final class WidgetMetadata {
        private static final HashSet<String> LABELS=new HashSet<String>(Arrays.asList(
            "Approve","Cancel","Done","Connect until host expiry","Shift","Symbols","Space","←","→","Delete","Enter",
            "Resume VR","Experimenter","Return to VR","Open Polar connection","Turn phone control off","Turn phone control on",
            "Require pairing code","Allow open control","Replace trusted controller","Approve trusted LAN controller",
            "Approve encrypted relay controller only","Create temporary hotspot","Create Wi-Fi Direct group",
            "Show temporary network join details","Connect approved HTTPS relay","Stop relay and temporary networks","Revoke network controller",
            "Arm Condition 1","Arm Condition 2","Start official run","Pause official run","Resume official run","Apply live bias",
            "Refresh status","Save session & prepare next","Back","Next","Open developer settings","Save and exit",
            "Home","Particles","Breath","Polar","LSL","Status","Next: Controls","Next: Choose condition","Session status","Prepare next run",
            "Apply breath mapping","Apply Polar state tuning","Refresh breath state","Refresh effective state","Apply LSL configuration",
            "Disable LSL","Reset LSL defaults","Refresh particle state","Read canonical effective snapshot","Apply selection",
            "Start calibration","Reset","Refresh readback","Refresh effective"));
        static JSONObject labels(String role,CharSequence buttonLabel,CharSequence hint)throws org.json.JSONException {
            JSONObject result=new JSONObject();
            if(("button".equals(role)||"checkbox".equals(role))&&buttonLabel!=null){
                String value=buttonLabel.toString();
                if(LABELS.contains(value)||value.length()==1&&value.charAt(0)>=33&&value.charAt(0)<=126)result.put("static_label",value);
            }
            if("textbox".equals(role)&&hint!=null){
                String value=hint.toString();if(value.length()<=256&&!value.contains("\u0000"))result.put("hint",value);
            }
            return result;
        }
    }

    static final class GeometryReadback {
        static boolean applied(JSONObject value,long generation){
            if(value==null||!"rusty.quest.native_mesh_ui.panel_geometry.v1".equals(value.opt("schema"))
                ||!"applied".equals(value.opt("status"))||!"native-submitted-frame".equals(value.opt("evidence"))
                ||!Boolean.FALSE.equals(value.opt("visible_confirmation"))||!(value.opt("frame_panel_visible") instanceof Boolean)
                ||!integer(value.opt("generation"))||value.optLong("generation",0)!=generation||generation<=0
                ||!integer(value.opt("pose_revision"))||value.optLong("pose_revision",-1)<0
                ||!integer(value.opt("effective_pose_revision"))||value.optLong("pose_revision",-1)!=value.optLong("effective_pose_revision",-2)
                ||!integer(value.opt("submitted_frame"))||value.optLong("submitted_frame",-1)<0
                ||!Arrays.asList("LOCAL","STAGE").contains(value.opt("reference_space"))
                ||!Integer.valueOf(1024).equals(value.opt("surface_width_pixels"))||!Integer.valueOf(768).equals(value.opt("surface_height_pixels")))return false;
            double[] right=vector(value.optJSONArray("right")),up=vector(value.optJSONArray("up"));
            return vector(value.optJSONArray("center"))!=null&&right!=null&&up!=null
                &&Math.abs(dot(right,right)-1)<0.01&&Math.abs(dot(up,up)-1)<0.01&&Math.abs(dot(right,up))<0.01
                &&positive(value.opt("width_meters"))&&positive(value.opt("height_meters"));
        }
        private static boolean integer(Object value){return value instanceof Integer||value instanceof Long;}
        private static boolean positive(Object value){return value instanceof Number&&Double.isFinite(((Number)value).doubleValue())&&((Number)value).doubleValue()>0;}
        private static double[] vector(JSONArray value){
            if(value==null||value.length()!=3)return null;double[] result=new double[3];
            for(int i=0;i<3;i++){Object item=value.opt(i);if(!(item instanceof Number)||!Double.isFinite(((Number)item).doubleValue()))return null;result[i]=((Number)item).doubleValue();}return result;
        }
        private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    }

    /** App-owned visible geometry. It exposes no widget mutation or generic callback route. */
    public static JSONObject productPanelGeometry(Activity productOwner){
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null||!((ProductPanelAttachment)producer.panelAttachment).presentationActive())return visibilityUnavailable("not-selected");
        try{return producer.widgetGeometry();}catch(Exception unavailable){return visibilityUnavailable("unavailable");}
    }

    private JSONObject widgetGeometry()throws Exception {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("mesh-geometry-requires-main-thread");
        JSONObject geometry=new JSONObject(nativeReadPanelGeometry(generation));
        boolean applied=GeometryReadback.applied(geometry,generation);
        if("applied".equals(geometry.optString("status"))&&!applied)geometry=visibilityUnavailable("unavailable");
        boolean interactive=applied&&geometry.optBoolean("frame_panel_visible",false)&&visibilityObserved&&effectiveVisible&&visibilityRequest==0;
        JSONArray windows=new JSONArray();
        addWindow(windows,root,0,"root",modals.isEmpty()&&interactive);
        for(int i=0;i<modals.size();i++){
            Dialog modal=modals.get(i);if(!modal.isShowing())continue;
            addWindow(windows,modal.getWindow().getDecorView(),windowIds.get(modal),"modal",i==modals.size()-1&&interactive);
        }
        if(keyboard!=null&&keyboard.isShowing())addWindow(windows,keyboard.getWindow().getDecorView(),windowIds.get(keyboard),"keyboard",interactive);
        String signature=windows.toString();if(!signature.equals(widgetSignature)){widgetSignature=signature;widgetRevision++;}
        return new JSONObject().put("schema","rusty.quest.native_mesh_ui.widget_geometry.v1").put("generation",generation)
            .put("widget_revision",widgetRevision).put("surface_width_pixels",1024).put("surface_height_pixels",768)
            .put("interactive",interactive).put("native_geometry",geometry).put("windows",windows)
            .put("raster_width_pixels",rasterWidth).put("raster_height_pixels",rasterHeight).put("raster_epoch",rasterEpoch)
            .put("raster_resize_request_id",resizeRequest==null?0:resizeRequest.id)
            .put("raster_resize_status",resizeRequest==null?1:resizeRequest.state)
            .put("raster_resize_deferrals",resizeRequest==null?0:resizeRequest.deferrals)
            .put("raster_resize_defer_reason",resizeRequest==null?"none":resizeRequest.reason)
            .put("pointer_capture_active",down).put("capture_window_id",pointerDialog==null?0:windowIds.get(pointerDialog))
            .put("evidence","current-android-view-layout; native-geometry-qualified-separately").put("visible_confirmation",false);
    }

    private JSONArray bounds(View view)throws org.json.JSONException {
        Rect local=new Rect();if(!view.isAttachedToWindow()||!view.isShown()||!view.getGlobalVisibleRect(local))return null;
        // Global-visible bounds are root-relative; modal/keyboard roots have their own screen origin.
        int[] windowOrigin=new int[2];view.getRootView().getLocationOnScreen(windowOrigin);
        local.offset(windowOrigin[0],windowOrigin[1]);
        local.set((int)Math.floor(local.left/rasterScale),(int)Math.floor(local.top/rasterScale),
            (int)Math.ceil(local.right/rasterScale),(int)Math.ceil(local.bottom/rasterScale));
        if(!local.intersect(0,0,1024,768)||local.isEmpty())return null;
        return new JSONArray(new int[]{local.left,local.top,local.right,local.bottom});
    }
    private void addWindow(JSONArray windows,View decor,long id,String layer,boolean interactive)throws Exception {
        JSONArray widgets=new JSONArray();collectWidgets(decor,"0",widgets,interactive,id,0);
        windows.put(new JSONObject().put("window_id",id).put("layer",layer).put("interactive",interactive)
            .put("bounds_pixels",bounds(decor)).put("layout_pending",decor.isLayoutRequested())
            .put("pointer_rule","keyboard".equals(layer)?"bounds-first":"modal".equals(layer)?"top-modal-fallback":"root-fallback")
            .put("widgets",widgets));
    }
    private void collectWidgets(View view,String path,JSONArray widgets,boolean interactive,long windowId,int depth)throws Exception {
        if(depth>64||widgets.length()>=512)return;
        JSONArray rect=bounds(view);if(rect==null)return;
        String role=view instanceof EditText?"textbox":view instanceof CheckBox?"checkbox":view instanceof Button?"button"
            :view instanceof Spinner?"combobox":view instanceof SeekBar?"slider":view instanceof ScrollView?"scroll"
            :view.getParent() instanceof AdapterView?"option":"";
        if(!role.isEmpty()){
            float x=(rect.getInt(0)+rect.getInt(2))*0.5f,y=(rect.getInt(1)+rect.getInt(3))*0.5f;
            Dialog target=dialogAt(x,y);long targetId=target==null?0:windowIds.get(target);
            JSONObject node=WidgetMetadata.labels(role,view instanceof Button?((Button)view).getText():null,view instanceof EditText?((EditText)view).getHint():null)
                .put("node_path",path).put("role",role).put("resource_id",view.getId()).put("bounds_pixels",rect)
                .put("hit_point_pixels",new JSONArray(new float[]{x,y})).put("enabled",view.isEnabled())
                .put("interactive",interactive&&view.isEnabled()&&targetId==windowId&&!view.isLayoutRequested()).put("focused",view.hasFocus());
            if(view.getParent() instanceof AdapterView)node.put("option_index",((AdapterView<?>)view.getParent()).getPositionForView(view));
            widgets.put(node);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectWidgets(group.getChildAt(i),path+"/"+i,widgets,interactive,windowId,depth+1);}
    }

    /** Android still owns allowed selection actions (including password restrictions). */
    private final class MeshActionMode extends ActionMode {
        final View source;final ActionMode.Callback callback;final Menu menu;final int type;
        CharSequence title="",subtitle="";View custom;AlertDialog dialog;boolean active;
        MeshActionMode(View source,ActionMode.Callback callback,int type){
            this.source=source;this.callback=callback;this.type=type;
            menu=new PopupMenu(presentation.getContext(),root).getMenu();
        }
        boolean start(){
            active=true;
            if(!callback.onCreateActionMode(this,menu)){active=false;return false;}
            if(active)invalidate();return active;
        }
        public void invalidate(){
            if(!active||!viewCurrent(source)){finish();return;}
            callback.onPrepareActionMode(this,menu);if(!active)return;
            final ArrayList<MenuItem> items=new ArrayList<MenuItem>();
            for(int i=0;i<menu.size();i++){MenuItem item=menu.getItem(i);if(item.isVisible()&&item.isEnabled())items.add(item);}
            if(items.isEmpty()){finish();return;}
            CharSequence[] labels=new CharSequence[items.size()];for(int i=0;i<items.size();i++)labels[i]=items.get(i).getTitle();
            if(dialog!=null)dialog.dismiss();
            dialog=new AlertDialog.Builder(presentation.getContext()).setItems(labels,new android.content.DialogInterface.OnClickListener(){
                public void onClick(android.content.DialogInterface ignored,int which){
                    if(active&&viewCurrent(source))callback.onActionItemClicked(MeshActionMode.this,items.get(which));
                    finish();
                }}).create();
            dialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener(){public void onCancel(android.content.DialogInterface ignored){finish();}});
            showModal(dialog,false);
        }
        public void finish(){
            if(!active)return;active=false;if(selectionMode==this)selectionMode=null;
            if(dialog!=null){dialog.dismiss();dialog=null;}callback.onDestroyActionMode(this);
        }
        public void setTitle(CharSequence value){title=value;}
        public void setTitle(int value){title=presentation.getContext().getText(value);}
        public void setSubtitle(CharSequence value){subtitle=value;}
        public void setSubtitle(int value){subtitle=presentation.getContext().getText(value);}
        public void setCustomView(View value){custom=value;}
        public Menu getMenu(){return menu;}
        public CharSequence getTitle(){return title;}
        public CharSequence getSubtitle(){return subtitle;}
        public View getCustomView(){return custom;}
        public MenuInflater getMenuInflater(){return new MenuInflater(presentation.getContext());}
        @Override public int getType(){return type;}
    }

    /** One original editor connection; queued controls cannot retarget a replacement editor. */
    static final class TextInputSession {
        private final InputConnection delegate;
        private final java.util.function.BooleanSupplier current;
        private boolean closed;
        TextInputSession(InputConnection delegate,java.util.function.BooleanSupplier current){this.delegate=delegate;this.current=current;}
        boolean valid(){return !closed&&current.getAsBoolean();}
        void commitText(String text,int position){if(valid())delegate.commitText(text,position);}
        void performEditorAction(int action){if(valid())delegate.performEditorAction(action);}
        void deletePrevious(){
            if(!valid())return;
            CharSequence selected=delegate.getSelectedText(0);
            if(!valid())return;
            if(selected!=null&&selected.length()>0){delegate.commitText("",1);return;}
            boolean deleted=false;
            try { deleted=delegate.deleteSurroundingTextInCodePoints(1,0); }
            catch(AbstractMethodError unsupported){ /* Older original editor implementation. */ }
            if(deleted||!valid())return;
            CharSequence before=delegate.getTextBeforeCursor(2,0);
            if(!valid()||before==null||before.length()==0)return;
            int count=before.length(),units=1;
            char last=before.charAt(count-1);
            if(Character.isLowSurrogate(last)){
                if(count<2||!Character.isHighSurrogate(before.charAt(count-2)))return;
                units=2;
            }else if(Character.isHighSurrogate(last))return;
            delegate.deleteSurroundingText(units,0);
        }
        void key(int code){if(code==KeyEvent.KEYCODE_DEL){deletePrevious();return;}if(valid()){
            delegate.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));
            delegate.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));
        }}
        void close(){if(closed)return;closed=true;delegate.finishComposingText();}
    }

    /** Pure input presentation rules; the real EditText still filters/validates every commit. */
    static final class TextInputRules {
        static String[] rows(int type,boolean caps,boolean symbols) {
            int kind=type&android.text.InputType.TYPE_MASK_CLASS;
            if(kind==android.text.InputType.TYPE_CLASS_NUMBER) {
                String extras=((type&android.text.InputType.TYPE_NUMBER_FLAG_SIGNED)!=0?"-":"")
                    +((type&android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)!=0?".":"");
                return new String[]{"1234567890",extras};
            }
            if(kind==android.text.InputType.TYPE_CLASS_PHONE)return new String[]{"1234567890","+*#()- ."};
            if(kind==android.text.InputType.TYPE_CLASS_DATETIME)return new String[]{"1234567890","/:.- "};
            if(symbols)return new String[]{"!@#$%^&*()","[]{}<>_=+-","\\|;:\"',.?/","`~"};
            return caps?new String[]{"1234567890","QWERTYUIOP","ASDFGHJKL","ZXCVBNM"}
                :new String[]{"1234567890","qwertyuiop","asdfghjkl","zxcvbnm"};
        }
        static boolean multiline(int type){return (type&android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_TEXT
            &&(type&android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0;}
    }

    private static NativeMeshUiProducer productProducer(Activity productOwner) {
        NativeMeshUiProducer producer=current;
        return producer!=null&&producer.root!=null&&producer.presentation!=null&&producer.panelAttachment instanceof ProductPanelAttachment
            &&((ProductPanelAttachment)producer.panelAttachment).owner()==productOwner?producer:null;
    }

    /** Uses the captured display/window token. The original builder retains all callbacks. */
    public static void showProductDialog(Activity productOwner,AlertDialog dialog) {
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null||producer.presentation==null||producer.root==null)
            throw new IllegalStateException("current-mesh-dialog-owner-unavailable");
        producer.showModal(dialog,false);
    }

    public static void requestProductTextInput(Activity productOwner,EditText field) {
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer!=null)producer.openKeyboard(field);
    }

    public static ActionMode startProductActionMode(Activity productOwner,View source,ActionMode.Callback callback,int type){
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null||!producer.viewCurrent(source))return null;
        if(producer.selectionMode!=null)producer.selectionMode.finish();producer.closeKeyboard();
        MeshActionMode mode=producer.new MeshActionMode(source,callback,type);producer.selectionMode=mode;
        if(!mode.start()){if(producer.selectionMode==mode)producer.selectionMode=null;return null;}return mode;
    }

    /** Retire transient input when the actual Activity reclaims its content or mesh hides. */
    public static void retireProductInput(Activity productOwner) {
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer!=null)producer.closeTransientInput();
    }

    public static void productContentRemoved(Activity productOwner,View content) {
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null)return;
        if(contains(content,producer.editor))producer.closeKeyboard();
        if(producer.selectionMode!=null&&contains(content,producer.selectionMode.source))producer.selectionMode.finish();
    }

    private static boolean contains(View parent,View child) {
        if(parent==null||child==null)return false;
        for(View node=child;node!=null;) {
            if(node==parent)return true;
            node=node.getParent() instanceof View?(View)node.getParent():null;
        }
        return false;
    }

    private void showModal(final Dialog dialog,boolean isKeyboard) {
        if(current!=this||presentation==null||root==null)throw new IllegalStateException("mesh-window-retired");
        Window window=dialog.getWindow();
        WindowManager.LayoutParams attributes=window.getAttributes();
        scaleWindowPlacement(window,attributes);
        attributes.type=WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        attributes.token=presentation.getWindow().getDecorView().getWindowToken();
        if(attributes.token==null)throw new IllegalStateException("mesh-window-token-unavailable");
        window.setAttributes(attributes);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener(){public void onDismiss(android.content.DialogInterface ignored){
            modals.remove(dialog);
            if(pointerDialog==dialog)cancelPointer();
            if(selectionMode!=null&&contains(dialog.getWindow().getDecorView(),selectionMode.source))selectionMode.finish();
            if(dialog==keyboard){keyboard=null;retireEditor();}
            else if(contains(dialog.getWindow().getDecorView(),editor))closeKeyboard();
            windowIds.remove(dialog);
        }});
        dialog.show();
        scaleWindowInsets(window);
        scaleModalContent(window);
        windowIds.put(dialog,nextWindowId++);
        if(!isKeyboard)modals.add(dialog);
    }

    private boolean viewCurrent(View source){
        if(current!=this||root==null||source==null||!source.isAttachedToWindow()||!source.isEnabled())return false;
        if(contains(root,source))return true;
        for(Dialog modal:modals)if(modal.isShowing()&&contains(modal.getWindow().getDecorView(),source))return true;
        return false;
    }
    private boolean editorCurrent(){return viewCurrent(editor);}

    private void openKeyboard(EditText field) {
        if(field==editor&&(keyboard!=null||systemKeyboard!=null)&&editorCurrent())return;
        closeKeyboard();editor=field;
        if(!editorCurrent()){retireEditor();return;}
        if(NativeSystemKeyboard.selected(activity)){
            final EditText requested=field;
            final long requestedEpoch=++editorEpoch;
            final NativeSystemKeyboard opened;
            try{opened=NativeSystemKeyboard.open(activity,field,
                new java.util.function.BooleanSupplier(){public boolean getAsBoolean(){
                    return editorEpoch==requestedEpoch&&editor==requested&&editorCurrent();
                }},new Runnable(){public void run(){
                    if(editorEpoch==requestedEpoch){systemKeyboard=null;retireEditor();}
                }});}catch(RuntimeException unavailable){
                Log.w("RQNativeSystemIme","event=system-ime-open-failed textLogged=false visibleProof=false");
                retireEditor();return;
            }
            if(editorEpoch!=requestedEpoch||!editorCurrent())opened.close();
            else systemKeyboard=opened;
            return;
        }
        editor.requestFocus();editorInfo=new EditorInfo();InputConnection connection=editor.onCreateInputConnection(editorInfo);
        if(connection==null){retireEditor();return;}
        final EditText requested=editor;
        input=new TextInputSession(connection,new java.util.function.BooleanSupplier(){public boolean getAsBoolean(){
            return editor==requested&&editorCurrent();
        }});
        // The actual EditText keeps its password transformation and InputType filters.
        // Neither field contents nor committed text enters a label, log, or receipt.
        capitals=false;symbols=false;
        keyboard=new Dialog(presentation.getContext());
        rebuildKeyboard();
        showModal(keyboard,true);
        Window window=keyboard.getWindow();
        window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);
    }

    private void keyboardButton(LinearLayout row,String label,final Runnable action,float weight) {
        final TextInputSession requested=input;
        Button key=new Button(presentation.getContext());key.setText(label);key.setTextSize(16);
        key.setOnClickListener(new View.OnClickListener(){public void onClick(View ignored){
            if(input!=requested||!requested.valid())return;action.run();
        }});
        row.addView(key,new LinearLayout.LayoutParams(0,46,weight));
    }

    private void rebuildKeyboard() {
        LinearLayout keys=new LinearLayout(presentation.getContext());keys.setOrientation(LinearLayout.VERTICAL);
        keys.setBackgroundColor(Color.rgb(24,30,38));keys.setPadding(6,6,6,6);
        for(String chars:TextInputRules.rows(editorInfo.inputType,capitals,symbols)) {
            if(chars.isEmpty())continue;
            LinearLayout row=new LinearLayout(presentation.getContext());keys.addView(row);
            for(int i=0;i<chars.length();i++) {
                final String value=chars.substring(i,i+1);
                keyboardButton(row,value,new Runnable(){public void run(){input.commitText(value,1);}},1);
            }
        }
        LinearLayout actions=new LinearLayout(presentation.getContext());keys.addView(actions);
        keyboardButton(actions,"Shift",new Runnable(){public void run(){capitals=!capitals;rebuildKeyboard();}},1);
        keyboardButton(actions,"Symbols",new Runnable(){public void run(){symbols=!symbols;rebuildKeyboard();}},1.2f);
        keyboardButton(actions,"Space",new Runnable(){public void run(){input.commitText(" ",1);}},1.5f);
        keyboardButton(actions,"←",new Runnable(){public void run(){editorKey(KeyEvent.KEYCODE_DPAD_LEFT);}},0.7f);
        keyboardButton(actions,"→",new Runnable(){public void run(){editorKey(KeyEvent.KEYCODE_DPAD_RIGHT);}},0.7f);
        keyboardButton(actions,"Delete",new Runnable(){public void run(){editorKey(KeyEvent.KEYCODE_DEL);}},1);
        keyboardButton(actions,"Enter",new Runnable(){public void run(){
            if(TextInputRules.multiline(editorInfo.inputType))input.commitText("\n",1);
            else input.performEditorAction(editorInfo.imeOptions&EditorInfo.IME_MASK_ACTION);
        }},1);
        keyboardButton(actions,"Done",new Runnable(){public void run(){closeKeyboard();}},1);
        keyboard.setContentView(rasterLayout(keys));
    }

    private void editorKey(int code){input.key(code);}

    private void retireEditor(){
        ++editorEpoch;
        TextInputSession prior=input;input=null;editorInfo=null;editor=null;capitals=false;symbols=false;
        if(prior!=null)prior.close();
    }
    private void closeKeyboard(){
        NativeSystemKeyboard system=systemKeyboard;systemKeyboard=null;
        if(system!=null)system.close();
        Dialog prior=keyboard;keyboard=null;retireEditor();
        if(prior!=null){if(pointerDialog==prior)cancelPointer();prior.dismiss();}
    }
    private void closeTransientInput(){
        cancelPointer();if(selectionMode!=null)selectionMode.finish();closeKeyboard();
        for(Dialog modal:new ArrayList<Dialog>(modals))modal.dismiss();modals.clear();
    }

    private boolean hit(Dialog dialog,float x,float y){
        if(dialog==null||!dialog.isShowing())return false;
        View decor=dialog.getWindow().getDecorView();int[] origin=new int[2];decor.getLocationOnScreen(origin);
        return x*rasterScale>=origin[0]&&y*rasterScale>=origin[1]&&x*rasterScale<origin[0]+decor.getWidth()&&y*rasterScale<origin[1]+decor.getHeight();
    }
    private Dialog dialogAt(float x,float y){
        if(hit(keyboard,x,y))return keyboard;
        for(int i=modals.size()-1;i>=0;i--)if(modals.get(i).isShowing())return modals.get(i);
        return null;
    }
    private void dispatchPointer(MotionEvent event){
        if(pointerDialog==null){root.dispatchTouchEvent(event);return;}
        View decor=pointerDialog.getWindow().getDecorView();int[] origin=new int[2];decor.getLocationOnScreen(origin);
        event.setLocation(event.getX()*rasterScale-origin[0],event.getY()*rasterScale-origin[1]);pointerDialog.dispatchTouchEvent(event);
    }

    private NativeMeshUiProducer(Activity activity, Surface surface, long generation, int width) {
        this.activity = activity;
        this.surface = surface;
        this.rasterScale = width/1024f;
        this.rasterWidth=width;this.rasterHeight=width*3/4;
        this.generation = generation;
    }

    /** One admitted writer handoff. State1 is attachment acknowledgement, never pixel acceptance. */
    private static final class ResizeRequest {
        final long id;final int width,height;Surface target;
        volatile int state;volatile int deferrals;volatile String reason="queued";
        ResizeRequest(long id,Surface target,int width,int height){this.id=id;this.target=target;this.width=width;this.height=height;}
    }
    private static boolean rasterExtentSupported(int width,int height){
        return width==1024&&height==768||width==1536&&height==1152||width==2048&&height==1536;
    }
    /** False retains caller ownership; true transfers only this Java Surface wrapper. */
    public static boolean requestResize(long generation,long requestId,Surface target,int width,int height){
        NativeMeshUiProducer owner=current;
        if(owner==null||owner.generation!=generation||requestId<=0||target==null
                ||!rasterExtentSupported(width,height)||!target.isValid())return false;
        final ResizeRequest request;
        synchronized(owner){
            ResizeRequest prior=owner.resizeRequest;
            if(current!=owner||cancelledGeneration==generation||owner.resizeStopped||prior!=null&&(prior.state==0||prior.state==-2||requestId<=prior.id))return false;
            request=new ResizeRequest(requestId,target,width,height);owner.resizeRequest=request;
            if(!owner.resizePolls.post(new Runnable(){public void run(){owner.applyResize(request);}})){
                owner.resizeRequest=prior;return false;
            }
        }
        return true;
    }
    /** 0pending/deferred,1writer committed,-1safe rejection/cancel,-2uncertain mutation. */
    public static int resizeStatus(long generation,long requestId){
        NativeMeshUiProducer owner=current;
        if(owner==null||owner.generation!=generation)return -2;
        ResizeRequest request=owner.resizeRequest;
        return request!=null&&request.id==requestId?request.state:-2;
    }
    private String resizeBusyReason(){
        if(!ownerAttachment.resumed())return "owner-not-resumed";
        if(editor!=null||systemKeyboard!=null)return "editor-active";
        if(keyboard!=null&&keyboard.isShowing())return "keyboard-active";
        if(selectionMode!=null)return "selection-active";
        for(Dialog modal:modals)if(modal.isShowing())return "modal-active";
        if(down||pointerDialog!=null||queuedPointers.get()>0||pointerQueueInvalid)return "pointer-active";
        if(root==null||rootRaster==null||presentation==null||!presentation.isShowing()||display==null)return "writer-unavailable";
        if(root.isLayoutRequested()||rootRaster.isLayoutRequested())return "layout-pending";
        return null;
    }
    private boolean releaseResizeTarget(ResizeRequest request){
        Surface target=request.target;request.target=null;
        if(target!=null)try{target.release();}catch(RuntimeException failure){uncertainSurfaces.add(target);return false;}
        return true;
    }
    private void abortResizeSafely(ResizeRequest request,String reason){
        request.reason=reason;
        request.state=releaseResizeTarget(request)?-1:-2;
    }
    private void applyResize(final ResizeRequest request){
        synchronized(this){
            if(request!=resizeRequest||request.state!=0)return;
            if(current!=this||cancelledGeneration==generation||resizeStopped||activity.isFinishing()||activity.isDestroyed()){
                abortResizeSafely(request,"owner-retired");return;
            }
            String busy=resizeBusyReason();
            if(busy!=null){
                request.reason=busy;if(request.deferrals<Integer.MAX_VALUE)request.deferrals++;
                if(!resizePolls.postDelayed(new Runnable(){public void run(){applyResize(request);}},100)){
                    abortResizeSafely(request,"queue-unavailable");
                }
                return;
            }
            if(!request.target.isValid()){
                abortResizeSafely(request,"surface-invalid");return;
            }
            Surface previous=surface;int oldWidth=rasterWidth,oldHeight=rasterHeight;float oldScale=rasterScale;
            try{
                display.setSurface(null);
                display.resize(request.width,request.height,densityDpi);
                rasterScale=request.width/1024f;rootRaster.setRasterScale(rasterScale);
                display.setSurface(request.target);
                surface=request.target;request.target=null;rasterWidth=request.width;rasterHeight=request.height;
                rasterEpoch=request.id;request.reason="writer-committed";request.state=1;
                try{previous.release();}catch(RuntimeException releaseFailure){uncertainSurfaces.add(previous);}
                Log.i(TAG,"event=raster-writer-committed generation="+generation+" request="+request.id
                    +" epoch="+rasterEpoch+" width="+rasterWidth+" height="+rasterHeight+" evidence=writer-attachment-only");
            }catch(RuntimeException failure){
                boolean restored=false;
                try{
                    display.setSurface(null);display.resize(oldWidth,oldHeight,densityDpi);
                    rasterScale=oldScale;rootRaster.setRasterScale(oldScale);display.setSurface(previous);restored=true;
                }catch(RuntimeException rollbackFailure){ /* Both wrappers remain owned until display release. */ }
                if(restored){abortResizeSafely(request,"rolled-back");}
                else{request.reason="mutation-uncertain";request.state=-2;}
                Log.e(TAG,"event=raster-writer-failed generation="+generation+" request="+request.id+" state="+request.state);
            }
        }
    }
    public static void start(Activity activity, Surface surface, int width, int height, long generation) {
        if (!((width==1024&&height==768)||(width==1536&&height==1152)||(width==2048&&height==1536))
                || generation <= 0 || !surface.isValid()) {
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
            NativeMeshUiProducer owner = new NativeMeshUiProducer(activity, surface, generation, width);
            current = owner;
            try {
                owner.ownerAttachment.resumed(owner.activityResumed());
                owner.observeOwnerLifecycle();
                DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
                int densityDpi=NativeRendererActivityComponents.PRODUCT_NATIVE.equals(activity.getClass().getName())
                    ?activity.getResources().getDisplayMetrics().densityDpi:160;
                if(densityDpi<=0)throw new IllegalStateException("actual-activity-density-unavailable");
                owner.densityDpi=densityDpi;
                owner.display = manager.createVirtualDisplay("Rusty Quest mesh UI", width, height, densityDpi, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                if (owner.display == null) throw new IllegalStateException("VirtualDisplay unavailable");
                owner.presentation = new Presentation(activity, owner.display.getDisplay()) {
                    @Override protected void onCreate(Bundle state) {
                        super.onCreate(state);
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        owner.build(this.getContext());
                        owner.rootRaster=(RasterLayout)owner.rasterLayout(owner.root);
                        setContentView(owner.rootRaster);
                    }
                };
                owner.presentation.show();
                Log.i(TAG, "event=producer-started generation=" + generation + " source=android-presentation width="+width+" height="+height+" densityDpi="+densityDpi);
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
        FrameLayout frame = new FrameLayout(context);
        frame.addView(layout, new FrameLayout.LayoutParams(-1, -1));
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
        productCarrier=layout;productTitle=title;
        try {
            // The closed panel source selection owns this optional adapter and all session effects.
            productModule=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity");
            productModule.getMethod("attachNativeMeshSessionControls",Activity.class,LinearLayout.class,
                java.util.function.BooleanSupplier.class);
            productSelectionResolved=true;
            if(!attachProductOwner()&&(awaitingProductOwner||!ownerAttachment.resumed()))bootstrapProductOwner();
            return; // Selected local product owns its full UI; integration diagnostics are not appended.
        } catch(ClassNotFoundException unselected) {
            // Neutral mesh-only apps do not acquire experiment or recording authority.
            productSelectionResolved=true;
        } catch(NoSuchMethodException unselected) {
            // A different closed panel module does not expose this product adapter.
            productModule=null;
            productSelectionResolved=true;
        } catch(Exception failure) {
            status.setText("Session panel unavailable; app owner did not attach.");
            Log.e(TAG,"event=session-panel-unavailable",failure);
            return; // A failed selected owner must not expose integration controls.
        }
        frame.removeView(layout);
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.addView(layout);
        frame.addView(scroll,0,new FrameLayout.LayoutParams(-1,-1));
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
        graft.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) submit(12, value / 1000.0f); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(graft, new LinearLayout.LayoutParams(-1, 76));
        button(context, layout, "Reset panel pose and size", 13);
        layout.addView(text(context, "Hand pinch or controller trigger: click / drag.", 16));
    }

    private void observeOwnerLifecycle() {
        ownerLifecycle=new android.app.Application.ActivityLifecycleCallbacks(){
            public void onActivityResumed(Activity value){if(value==activity&&current==NativeMeshUiProducer.this){
                ownerAttachment.resumed(true);attachProductOwner();
            }}
            public void onActivityPaused(Activity value){if(value==activity){
                closeTransientInput();ownerAttachment.resumed(false);
            }}
            public void onActivityDestroyed(Activity value){
                if(value==activity)ownerAttachment.retire();
                if(panelAttachment instanceof ProductPanelAttachment&&((ProductPanelAttachment)panelAttachment).owner()==value){
                    try{panelAttachment.close();}catch(Exception ignored){}panelAttachment=null;
                }
                ownerAttachment.destroyed(value);
            }
            public void onActivityCreated(Activity value,Bundle saved){}
            public void onActivityStarted(Activity value){}
            public void onActivityStopped(Activity value){}
            public void onActivitySaveInstanceState(Activity value,Bundle saved){}
        };
        activity.getApplication().registerActivityLifecycleCallbacks(ownerLifecycle);
    }

    private boolean activityResumed() {
        try {
            // The selected original Application already tracks actual Activity lifecycle.
            // Keep neutral mesh-only compositions free of a mandatory kiosk dependency.
            Object application=activity.getApplication();
            return Boolean.TRUE.equals(application.getClass().getDeclaredMethod("isResumed",String.class)
                .invoke(application,NativeRendererActivityComponents.policyClass(activity.getClass().getName())));
        }catch(ReflectiveOperationException unavailable){return activity.hasWindowFocus();}
    }

    private boolean attachProductOwner() {
        if(current!=this||root==null||productModule==null||panelAttachment!=null||!ownerAttachment.resumed())return panelAttachment!=null;
        try {
            Object attachment=productModule.getMethod("attachNativeMeshSessionControls",Activity.class,LinearLayout.class,
                java.util.function.BooleanSupplier.class).invoke(null,activity,productCarrier,
                    new java.util.function.BooleanSupplier(){public boolean getAsBoolean(){
                        return current==NativeMeshUiProducer.this&&root!=null;
                    }});
            if(!(attachment instanceof ProductPanelAttachment))throw new IllegalStateException("panel-attachment-retirement-required");
            panelAttachment=(ProductPanelAttachment)attachment;
            awaitingProductOwner=false;
            Activity actual=((ProductPanelAttachment)attachment).owner();ownerAttachment.ready(actual);
            productCarrier.removeView(productTitle);productCarrier.removeView(status);
            Boolean desired=ownerAttachment.takeVisibility(actual);
            if(desired!=null)requestVisibility(desired);
            observePanelVisibility();
            Log.i(TAG,"event=product-owner-attached generation="+generation+" source=actual-activity");
            return true;
        } catch(Exception failure) {
            Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException?failure.getCause():failure;
            if(cause instanceof IllegalStateException&&"retained-production-panel-owner-unavailable".equals(cause.getMessage())){
                awaitingProductOwner=true;
                status.setText("Waiting for the original app panel…");
                return false;
            }
            status.setText("Session panel unavailable; app owner did not attach.");
            awaitingProductOwner=false;
            Log.e(TAG,"event=session-panel-unavailable generation="+generation,failure);
            return false;
        }
    }

    private void bootstrapProductOwner() {
        // The original shared controller is owned by this actual NativeActivity.
        // Never create a Shell-managed 2D Activity to keep that controller alive.
        if(NativeRendererActivityComponents.PRODUCT_NATIVE.equals(activity.getClass().getName()))return;
        if(productModule==null||!ownerAttachment.bootstrap()||current!=this||root==null)return;
        try {
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setComponent(new android.content.ComponentName(activity.getPackageName(),productModule.getName()))
                .addCategory("com.oculus.intent.category.2D")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            Log.i(TAG,"event=product-owner-bootstrap-requested generation="+generation);
        }catch(RuntimeException failure){Log.e(TAG,"event=product-owner-bootstrap-unavailable generation="+generation,failure);}
    }

    /** Called by the real selected Activity after its original builders/lifecycle are ready. */
    public static void productOwnerReady(Activity actual) {
        NativeMeshUiProducer producer=current;
        if(producer==null||producer.root==null||producer.productModule==null
                ||actual.isFinishing()||actual.isDestroyed())return;
        try { if(!Boolean.TRUE.equals(producer.productModule.getMethod("ownsPanelActivity",Activity.class).invoke(null,actual)))return; }
        catch(ReflectiveOperationException unselected){return;}
        if(actual==producer.activity){
            if(producer.ownerAttachment.ready(actual))producer.attachProductOwner();
            return;
        }
        producer.ownerAttachment.ownerReady(actual,new Runnable(){public void run(){
            try{
                producer.bootstrapReturn.set(new BootstrapReturnScope(actual));
                producer.productModule.getMethod("resumeNativeMeshImmersive",Activity.class).invoke(null,actual);
            }catch(Exception failure){Log.e(TAG,"event=product-owner-return-unavailable generation="+producer.generation,failure);}
            finally{producer.bootstrapReturn.remove();}
        }},new Runnable(){public void run(){producer.attachProductOwner();}});
    }

    /** True means this actual product owner was handled, including a rejected request. */
    public static boolean requestPanelVisibility(Activity productOwner, boolean visible) {
        NativeMeshUiProducer selected=current;
        BootstrapReturnScope bootstrap=selected==null?null:selected.bootstrapReturn.get();
        if(bootstrap!=null&&bootstrap.bypass(productOwner,visible))
            return false; // Force the existing full facade handoff even if already attached.
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null||!((ProductPanelAttachment)producer.panelAttachment).presentationActive()){
            NativeMeshUiProducer pending=current;
            if(pending!=null&&pending.productModule!=null&&pending.root!=null)pending.ownerAttachment.visibility(productOwner,visible);
            return false; // The original Activity route must still resume the actual immersive owner.
        }
        final ProductPanelAttachment binding=(ProductPanelAttachment)producer.panelAttachment;
        producer.activity.runOnUiThread(new Runnable(){public void run(){
            if(current!=producer||producer.root==null||producer.panelAttachment!=binding)return;
            producer.requestVisibility(visible);
        }});
        return true;
    }

    private long requestVisibility(boolean visible){
        ProductPanelAttachment attachment=(ProductPanelAttachment)panelAttachment;
        long revision=nativeSetPanelVisible(generation,visible);
        if(revision<=0){attachment.outcome("Panel visibility request was not admitted; refresh the current panel.");return 0;}
        if(!visible)closeTransientInput();visibilityRequest=revision;visibilityObserved=false;
        attachment.outcome(visible?"Opening the panel; waiting for renderer acknowledgement.":"Returning to VR; waiting for renderer acknowledgement.");
        Log.i(TAG,"event=panel-visibility-requested generation="+generation+" revision="+revision+" visible="+visible);
        return revision;
    }

    private static JSONObject visibilityUnavailable(String status){
        JSONObject result=new JSONObject();try{return result.put("selected",!"not-selected".equals(status)).put("status",status);}catch(Exception impossible){return result;}
    }
    private JSONObject visibilityReceipt(long revision){
        try {
            JSONObject receipt=new JSONObject(nativeReadPanelVisibility(generation,revision));
            Object identity=receipt.opt("generation");
            if((identity instanceof Integer||identity instanceof Long)&&((Number)identity).longValue()==generation)return receipt;
        }catch(Exception unavailable){ /* A failed owner read has no effective visibility proof. */ }
        return visibilityUnavailable("unavailable");
    }

    /** Exact current producer only; no standalone Activity fallback becomes effective=true. */
    public static JSONObject productPanelVisibility(Activity productOwner){
        NativeMeshUiProducer producer=productProducer(productOwner);
        return producer==null||!((ProductPanelAttachment)producer.panelAttachment).presentationActive()
            ?visibilityUnavailable("not-selected"):producer.visibilityReceipt(0);
    }

    public static JSONObject requestProductPanelVisibility(Activity productOwner,boolean visible)throws org.json.JSONException {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("mesh-visibility-requires-main-thread");
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null||!((ProductPanelAttachment)producer.panelAttachment).presentationActive())return visibilityUnavailable("not-selected");
        long revision=producer.requestVisibility(visible);
        return new JSONObject().put("status",revision>0?"queued":"rejected")
            .put("panel_visibility",revision>0?producer.visibilityReceipt(revision):visibilityUnavailable("request-rejected"));
    }

    public static boolean panelVisibilityConfirmed(Activity productOwner,boolean visible){
        NativeMeshUiProducer producer=current;
        if(producer==null)return productOwner==null||!NativeRendererActivityComponents.PRODUCT_NATIVE.equals(productOwner.getClass().getName()); // Only the existing 2D route has its separate foreground proof.
        if(!(producer.panelAttachment instanceof ProductPanelAttachment)
                ||((ProductPanelAttachment)producer.panelAttachment).owner()!=productOwner
                ||!((ProductPanelAttachment)producer.panelAttachment).presentationActive())return producer.productSelectionResolved&&producer.productModule==null;
        return producer.visibilityObserved&&producer.visibilityRequest==0&&producer.effectiveVisible==visible;
    }

    private void observePanelVisibility(){
        if(!(panelAttachment instanceof ProductPanelAttachment))return;
        visibilityPolls.post(new Runnable(){public void run(){
            if(current!=NativeMeshUiProducer.this||root==null||!(panelAttachment instanceof ProductPanelAttachment))return;
            try {
                JSONObject receipt=new JSONObject(nativeReadPanelVisibility(generation,visibilityRequest));
                if("stale".equals(receipt.optString("status")))visibilityRequest=0;
                Boolean applied=VisibilityReadback.applied(receipt,generation,visibilityRequest);
                if(applied!=null){
                    boolean visible=applied;
                    if(!visibilityObserved||visible!=effectiveVisible){
                        visibilityObserved=true;effectiveVisible=visible;
                        if(!visible)closeTransientInput();
                        ((ProductPanelAttachment)panelAttachment).visibilityChanged(visible);
                        Log.i(TAG,"event=panel-visibility-observed generation="+generation+" visible="+visible);
                    }
                    visibilityRequest=0;
                }else visibilityObserved=false;
            }catch(Exception unavailable){ /* No receipt means no effective visibility transition. */ }
            visibilityPolls.postDelayed(this,100);
        }});
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
                owner.pointerDialog=owner.dialogAt(x,y);
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
            owner.dispatchPointer(event);
            if(motion==MotionEvent.ACTION_UP||motion==MotionEvent.ACTION_CANCEL)owner.pointerDialog=null;
            event.recycle();
            } finally {
                if (owner.queuedPointers.decrementAndGet() == 0) owner.pointerQueueInvalid = false;
            }
        }});
    }

    /** The actual selected NativeActivity calls this on main before its blocking super destroy.
     * An old Activity cannot retire a replacement producer. This returns resource closure,
     * not native reader retirement; native still consumes the same generation receipt. */
    static boolean retireActivityProducer(Activity actual) {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())return false;
        NativeMeshUiProducer owner=current;
        if(owner==null||owner.activity!=actual||owner.generation<=0)return false;
        StopReceipt receipt=stopReceipt(owner.generation);
        if(receipt==null)return false;
        cancelledGeneration=owner.generation;
        finishStop(owner,owner.generation,receipt);
        return receipt.state==1;
    }

    private static StopReceipt stopReceipt(long generation) {
        synchronized(stopReceipts) {
            StopReceipt receipt=stopReceipts.get(generation);
            if(receipt!=null)return receipt;
            if(stopReceipts.size()>=8)return null;
            receipt=new StopReceipt();stopReceipts.put(generation,receipt);return receipt;
        }
    }

    private static void finishStop(NativeMeshUiProducer expected,long generation,StopReceipt receipt) {
        if(receipt.state!=0)return;
        boolean released=false;
        try {
            NativeMeshUiProducer owner=current;
            if(owner==null||owner.generation!=generation)released=true;
            else if(owner==expected&&owner.close()) { if(current==owner)current=null;released=true; }
        } catch(RuntimeException failure) {
            Log.e(TAG,"event=producer-stop-failed generation="+generation,failure);
        } finally {
            receipt.state=released?1:-1;
            Log.i(TAG,"event=producer-stop-ack generation="+generation+" released="+released);
        }
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
        NativeMeshUiProducer resizeOwner=current;
        if(resizeOwner!=null&&resizeOwner.generation==generation)synchronized(resizeOwner){
            resizeOwner.resizeStopped=true;resizeOwner.resizePolls.removeCallbacksAndMessages(null);
            ResizeRequest pending=resizeOwner.resizeRequest;
            if(pending!=null&&pending.state==0)pending.reason="stop-requested";
        }
        Log.i(TAG, "event=producer-stop-requested generation=" + generation);
        // Always enqueue behind any pending start, even if current is still null.
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            finishStop(current,generation,receipt);
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
        synchronized(this){
            resizeStopped=true;resizePolls.removeCallbacksAndMessages(null);
            if(resizeRequest!=null&&resizeRequest.state==0)resizeRequest.reason="producer-stopped";
        }
        ownerAttachment.retire();
        if(ownerLifecycle!=null){activity.getApplication().unregisterActivityLifecycleCallbacks(ownerLifecycle);ownerLifecycle=null;}
        visibilityPolls.removeCallbacksAndMessages(null);
        try { closeTransientInput(); } catch(RuntimeException error){released=false;}
        try { cancelPointer(); } catch (RuntimeException error) { released = false; }
        try { if(panelAttachment!=null){panelAttachment.close();panelAttachment=null;} }
        catch(Exception error){released=false;}
        try { if (presentation != null) { presentation.dismiss(); presentation = null; } }
        catch (RuntimeException error) { released = false; }
        try { if (display != null) { display.release(); display = null; } }
        catch (RuntimeException error) { released = false; }
        if(display==null){
            try { surface.release(); } catch (RuntimeException error) { released = false; }
            if(resizeRequest!=null){
                boolean candidateReleased=releaseResizeTarget(resizeRequest);
                if(!candidateReleased)released=false;
                if(resizeRequest.state==0)resizeRequest.state=candidateReleased?-1:-2;
            }
            for(Surface retained:uncertainSurfaces)try{retained.release();}catch(RuntimeException error){released=false;}
            uncertainSurfaces.clear();
        }else if(resizeRequest!=null&&resizeRequest.state==0){resizeRequest.reason="display-release-failed";resizeRequest.state=-2;}
        root = null;
        Log.i(TAG, "event=producer-stopped generation=" + generation + " released=" + released);
        return released;
    }

    private void cancelPointer() {
        if (down && root != null) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            dispatchPointer(event); event.recycle(); down = false;
        }
        pointerDialog=null;
        if (cursor != null) cursor.setVisibility(View.INVISIBLE);
    }

    private static native boolean nativeControl(long generation, int code, float value);
    private static native long nativeSetPanelVisible(long generation, boolean visible);
    private static native String nativeReadPanelVisibility(long generation, long requestRevision);
    private static native String nativeReadPanelGeometry(long generation);
}
