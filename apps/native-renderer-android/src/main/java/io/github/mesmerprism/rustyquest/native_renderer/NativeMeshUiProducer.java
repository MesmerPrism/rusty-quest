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
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
    private volatile long rasterEpoch=1;
    private volatile long pageContentEpoch=1;
    private final NativePageSnapshot nativePage=new NativePageSnapshot();
    private final java.util.concurrent.atomic.AtomicBoolean nativePageRequested=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean nativePageStopped;
    private final android.os.Handler pagePolls=new android.os.Handler(android.os.Looper.getMainLooper());
    private ViewTreeObserver pageTree;
    private long lastPageObservation;
    private long lastPageMetrics;
    private String lastPageFailure="";
    private View observedPageContent;
    private final ViewTreeObserver.OnPreDrawListener pageDraw=new ViewTreeObserver.OnPreDrawListener(){
        public boolean onPreDraw(){observeNativePage(false);return true;}
    };
    private final Runnable pagePoll=new Runnable(){public void run(){
        if(current!=NativeMeshUiProducer.this||root==null)return;
        observeNativePage(true);refreshPaintWatches();reportPaintMetrics();pagePolls.postDelayed(this,100);
    }};
    private final IdentityHashMap<Window,PaintWatch> paintWatches=new IdentityHashMap<Window,PaintWatch>();
    private long paintMetricsStarted,paintDrawCallbacks,paintFrameReports,paintDroppedReports;
    private long paintDrawDurationNs,paintFrameDurationNs,paintDrawDurationSamples,paintFrameDurationSamples;
    private long paintMetricFailures,paintObservations,paintSnapshotChanges,paintObserveSpanNs;
    private int paintWindowsUnwatched;
    private RasterLayout rootRaster;
    private final android.os.Handler resizePolls=new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile ResizeRequest resizeRequest;
    private boolean resizeStopped;
    private final ArrayList<Surface> uncertainSurfaces=new ArrayList<Surface>();
    private final java.util.WeakHashMap<Window,WindowRasterState> rasterWindows=new java.util.WeakHashMap<Window,WindowRasterState>();
    private final java.util.WeakHashMap<RasterLayout,RasterSpacing> rasterSpacing=new java.util.WeakHashMap<RasterLayout,RasterSpacing>();
    /** Keep the original pixel layout and resource density; raster scale changes sampling only. */
    private static final class RasterLayout extends FrameLayout {
        private float scale;
        private final boolean fixedLogicalViewport;
        RasterLayout(Context context,View child,float scale,boolean fixedLogicalViewport){
            super(context);this.scale=scale;this.fixedLogicalViewport=fixedLogicalViewport;addView(child,new FrameLayout.LayoutParams(-1,-1));
            child.setPivotX(0);child.setPivotY(0);child.setScaleX(scale);child.setScaleY(scale);
        }
        void setRasterScale(float value){
            scale=value;View child=getChildAt(0);child.setPivotX(0);child.setPivotY(0);
            child.setScaleX(value);child.setScaleY(value);requestLayout();invalidate();
        }
        private int logicalSpec(int spec){return View.MeasureSpec.makeMeasureSpec(
            Math.round(View.MeasureSpec.getSize(spec)/scale),View.MeasureSpec.getMode(spec));}
        @Override protected void onMeasure(int width,int height){
            super.onMeasure(fixedLogicalViewport?View.MeasureSpec.makeMeasureSpec(1024,View.MeasureSpec.EXACTLY):logicalSpec(width),
                fixedLogicalViewport?View.MeasureSpec.makeMeasureSpec(768,View.MeasureSpec.EXACTLY):logicalSpec(height));
            setMeasuredDimension(Math.round(getMeasuredWidth()*scale),Math.round(getMeasuredHeight()*scale));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            super.onLayout(changed,0,0,fixedLogicalViewport?1024:Math.round((right-left)/scale),
                fixedLogicalViewport?768:Math.round((bottom-top)/scale));
        }
    }
    private View rasterLayout(View child){return new RasterLayout(child.getContext(),child,rasterScale,child==root);}
    private static int scaledOffset(int value,float scale){return Math.round(value*scale);}
    private static int scaledDimension(int value,float scale){return value>0?scaledOffset(value,scale):value;}
    /** Only logical scalar snapshots: values never retain their weak Window keys. */
    private static final class WindowRasterState {
        final int width,height,x,y;
        boolean decorCaptured;
        int left,top,right,bottom,minWidth,minHeight;
        WindowRasterState(WindowManager.LayoutParams attributes){
            width=attributes.width;height=attributes.height;x=attributes.x;y=attributes.y;
        }
        void captureDecor(View decor){
            if(decorCaptured)return;
            left=decor.getPaddingLeft();top=decor.getPaddingTop();right=decor.getPaddingRight();bottom=decor.getPaddingBottom();
            minWidth=decor.getMinimumWidth();minHeight=decor.getMinimumHeight();decorCaptured=true;
        }
    }
    /** Physical-parent parameters are always recomputed from their original logical values. */
    private static final class RasterSpacing {
        final int width,height,left,top,right,bottom,start,end;final boolean margins,relative;
        RasterSpacing(ViewGroup.LayoutParams params){
            width=params.width;height=params.height;margins=params instanceof ViewGroup.MarginLayoutParams;
            if(margins){ViewGroup.MarginLayoutParams value=(ViewGroup.MarginLayoutParams)params;
                left=value.leftMargin;top=value.topMargin;right=value.rightMargin;bottom=value.bottomMargin;
                relative=value.isMarginRelative();start=relative?value.getMarginStart():0;end=relative?value.getMarginEnd():0;
            }else{left=top=right=bottom=start=end=0;relative=false;}
        }
        void apply(ViewGroup.LayoutParams params,float scale){
            params.width=scaledDimension(width,scale);params.height=scaledDimension(height,scale);
            if(margins&&params instanceof ViewGroup.MarginLayoutParams){
                ViewGroup.MarginLayoutParams value=(ViewGroup.MarginLayoutParams)params;
                value.leftMargin=scaledOffset(left,scale);value.topMargin=scaledOffset(top,scale);
                value.rightMargin=scaledOffset(right,scale);value.bottomMargin=scaledOffset(bottom,scale);
                if(relative){value.setMarginStart(scaledOffset(start,scale));value.setMarginEnd(scaledOffset(end,scale));}
            }
        }
    }
    private void scaleModalContent(Window window){
        ViewGroup content=window.findViewById(android.R.id.content);
        if(content==null||content.getChildCount()!=1)throw new IllegalStateException("mesh-modal-content-unavailable");
        View child=content.getChildAt(0);
        RasterLayout wrapped=child instanceof RasterLayout?(RasterLayout)child:null;
        ViewGroup.LayoutParams params=child.getLayoutParams();
        if(wrapped==null){
            RasterSpacing original=new RasterSpacing(params);
            content.removeView(child);wrapped=(RasterLayout)rasterLayout(child);rasterSpacing.put(wrapped,original);
            original.apply(params,rasterScale);content.addView(wrapped,params);
        }else{
            RasterSpacing original=rasterSpacing.get(wrapped);
            if(original==null)throw new IllegalStateException("mesh-modal-logical-spacing-unavailable");
            original.apply(params,rasterScale);wrapped.setRasterScale(rasterScale);wrapped.setLayoutParams(params);
        }
    }
    private int rasterOffset(int logical){return scaledOffset(logical,rasterScale);}
    private int rasterDimension(int logical){return scaledDimension(logical,rasterScale);}
    private WindowRasterState logicalWindow(Window window){
        WindowRasterState original=rasterWindows.get(window);
        if(original==null){original=new WindowRasterState(window.getAttributes());rasterWindows.put(window,original);}
        return original;
    }
    private void scaleWindowPlacement(Window window,WindowManager.LayoutParams attributes){
        WindowRasterState original=logicalWindow(window);
        // MATCH_PARENT/WRAP_CONTENT, gravity and fractional margins retain platform semantics.
        attributes.width=scaledDimension(original.width,rasterScale);attributes.height=scaledDimension(original.height,rasterScale);
        attributes.x=scaledOffset(original.x,rasterScale);attributes.y=scaledOffset(original.y,rasterScale);
    }
    private void scaleWindowInsets(Window window){
        WindowRasterState original=logicalWindow(window);View decor=window.getDecorView();original.captureDecor(decor);
        decor.setPadding(scaledOffset(original.left,rasterScale),scaledOffset(original.top,rasterScale),
            scaledOffset(original.right,rasterScale),scaledOffset(original.bottom,rasterScale));
        decor.setMinimumWidth(scaledOffset(original.minWidth,rasterScale));
        decor.setMinimumHeight(scaledOffset(original.minHeight,rasterScale));
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
    /** Presentation identity only; original dialogs and listeners retain action ownership. */
    static final class WindowRegistry<T> {
        private final IdentityHashMap<T,Long> values=new IdentityHashMap<T,Long>();
        private long next=1;
        boolean opened(T window){
            if(window==null)throw new IllegalArgumentException("window-owner-required");
            if(values.containsKey(window))return false;
            if(next<=0)throw new IllegalStateException("window-identity-exhausted");
            values.put(window,next++);return true;
        }
        boolean closed(T window){return values.remove(window)!=null;}
        Long get(T window){return values.get(window);}
    }
    private final WindowRegistry<Dialog> windowIds=new WindowRegistry<Dialog>();
    private long widgetRevision;
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
        boolean interactive=applied&&geometry.optBoolean("frame_panel_visible",false)&&geometry.optBoolean("tracking_valid",true)&&visibilityObserved&&effectiveVisible&&visibilityRequest==0
            &&!(resizeRequest!=null&&resizeRequest.state==0&&resizeRequest.mutating);
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
            .put("raster_scroll_admission_y",resizeRequest==null||resizeRequest.scroll==null?0:resizeRequest.scroll.firstVerticalY())
            .put("raster_scroll_commit_y",resizeRequest==null?0:resizeRequest.commitScrollY)
            .put("raster_scroll_current_y",resizeRequest==null||resizeRequest.scroll==null?0:resizeRequest.scroll.currentFirstVerticalY())
            .put("raster_scroll_restore_count",resizeRequest==null?0:resizeRequest.restoreCount)
            .put("raster_scroll_snapshot_view_count",resizeRequest==null?0:resizeRequest.admissionViewCount)
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
        producer.pageContentEpoch++;
        producer.nativePage.invalidate();
        producer.cancelPointer();
        if(contains(content,producer.editor))producer.closeKeyboard();
        if(producer.selectionMode!=null&&contains(content,producer.selectionMode.source))producer.selectionMode.finish();
    }

    /** XR reads immutable cached bytes only; the first selected read starts UI-thread observation. */
    public static byte[] readNativePage(long generation,long seenRevision,long seenAtlasEpoch){
        final NativeMeshUiProducer producer=current;
        if(producer==null||producer.generation!=generation||cancelledGeneration==generation||producer.nativePageStopped)return null;
        if(producer.nativePageRequested.compareAndSet(false,true))producer.activity.runOnUiThread(new Runnable(){public void run(){
            if(current!=producer||producer.root==null)return;
            producer.pageTree=producer.root.getViewTreeObserver();producer.pageTree.addOnPreDrawListener(producer.pageDraw);
            producer.pagePolls.post(producer.pagePoll);
        }});
        long content=producer.pageContentEpoch,raster=producer.rasterEpoch;
        byte[] value=producer.nativePage.read(generation,content,raster,seenRevision,seenAtlasEpoch);
        return current==producer&&content==producer.pageContentEpoch&&raster==producer.rasterEpoch?value:null;
    }

    private void observeNativePage(boolean force){
        long now=SystemClock.uptimeMillis();
        if(!force&&now-lastPageObservation<32)return;
        lastPageObservation=now;paintObservations++;
        boolean active=current==this&&root!=null&&panelAttachment instanceof ProductPanelAttachment
            &&((ProductPanelAttachment)panelAttachment).presentationActive()&&ownerAttachment.resumed()
            &&visibilityObserved&&effectiveVisible&&visibilityRequest==0&&!resizeInputSuppressed();
        View content=productCarrier;
        if(content!=observedPageContent){
            observedPageContent=content;pageContentEpoch++;nativePage.invalidate();
        }
        if(!active||keyboard!=null){
            nativePage.invalidate();reportPageStatus(active?"legacy-keyboard":"owner-inactive",false,now);return;
        }
        NativePageSnapshot.Layer[] layers=nativePageLayers(content);
        if(layers==null){nativePage.invalidate();reportPageStatus("window-roots-unavailable",false,now);return;}
        long started=System.nanoTime();
        boolean changed=nativePage.observe(layers,root,cursor,generation,pageContentEpoch,rasterEpoch,rasterScale,true);
        paintObserveSpanNs+=Math.max(0,System.nanoTime()-started);
        if(changed)paintSnapshotChanges++;
        reportPageStatus(nativePage.failure(),changed,now);
    }

    /** Actual platform window order and dim policy; no synthesized modal or callback owner. */
    private NativePageSnapshot.Layer[] nativePageLayers(View content){
        if(content==null)return null;
        ArrayList<NativePageSnapshot.Layer> layers=new ArrayList<NativePageSnapshot.Layer>();
        layers.add(new NativePageSnapshot.Layer(content,0,0));
        for(Dialog modal:modals){
            if(!modal.isShowing())continue;
            Window window=modal.getWindow();Long id=windowIds.get(modal);
            if(window==null||id==null||id<=0||layers.size()>=8)return null;
            WindowManager.LayoutParams attributes=window.getAttributes();
            float dim=(attributes.flags&WindowManager.LayoutParams.FLAG_DIM_BEHIND)!=0?attributes.dimAmount:0;
            layers.add(new NativePageSnapshot.Layer(window.getDecorView(),id,dim));
        }
        return layers.toArray(new NativePageSnapshot.Layer[layers.size()]);
    }

    private void reportPageStatus(String reason,boolean changed,long now){
        if(!reason.equals(lastPageFailure)){
            lastPageFailure=reason;
            Log.i(TAG,"event=native-page-observation generation="+generation+" contentEpoch="+pageContentEpoch
                +" status="+("none".equals(reason)?"complete":"fallback")+" reason="+reason+nativePage.counts()+" textLogged=false visibleProof=false");
        }
        if(changed&&now-lastPageMetrics>=2000){lastPageMetrics=now;Log.i(TAG,"event=native-page-metrics generation="+generation+nativePage.counts()+" textLogged=false visibleProof=false");}
    }

    /** Passive window observations. Neither listener requests a draw or changes View state. */
    private final class PaintWatch {
        final Window window;final ViewTreeObserver tree;
        final ViewTreeObserver.OnDrawListener draw=new ViewTreeObserver.OnDrawListener(){public void onDraw(){
            if(!closed&&current==NativeMeshUiProducer.this)paintDrawCallbacks++;
        }};
        final Window.OnFrameMetricsAvailableListener frames=new Window.OnFrameMetricsAvailableListener(){
            public void onFrameMetricsAvailable(Window ignored,FrameMetrics metrics,int dropped){
                if(closed||current!=NativeMeshUiProducer.this)return;
                paintFrameReports++;paintDroppedReports+=Math.max(0,dropped);
                // Consume immediately: Android reuses the FrameMetrics object after this callback.
                try{
                    long drawNs=metrics.getMetric(FrameMetrics.DRAW_DURATION),totalNs=metrics.getMetric(FrameMetrics.TOTAL_DURATION);
                    if(drawNs>=0){paintDrawDurationNs+=drawNs;paintDrawDurationSamples++;}
                    if(totalNs>=0){paintFrameDurationNs+=totalNs;paintFrameDurationSamples++;}
                }catch(RuntimeException unavailable){paintMetricFailures++;}
            }
        };
        boolean closed,drawAttached,framesAttached;
        PaintWatch(Window value){
            window=value;tree=value.getDecorView().getViewTreeObserver();
            try{if(tree.isAlive()){tree.addOnDrawListener(draw);drawAttached=true;}}
            catch(RuntimeException unavailable){paintMetricFailures++;}
            if(android.os.Build.VERSION.SDK_INT>=24)try{window.addOnFrameMetricsAvailableListener(frames,pagePolls);framesAttached=true;}
            catch(RuntimeException unavailable){paintMetricFailures++;}
        }
        void close(){
            closed=true;
            try{if(drawAttached&&tree.isAlive())tree.removeOnDrawListener(draw);}
            catch(RuntimeException unavailable){paintMetricFailures++;}
            try{if(framesAttached)window.removeOnFrameMetricsAvailableListener(frames);}
            catch(RuntimeException unavailable){paintMetricFailures++;}
        }
    }
    private void addPaintWindow(IdentityHashMap<Window,Boolean> wanted,Window window){
        if(window==null||wanted.containsKey(window))return;
        if(wanted.size()>=9){paintWindowsUnwatched++;return;}
        wanted.put(window,Boolean.TRUE);
    }
    private void refreshPaintWatches(){
        if(!nativePageRequested.get()||nativePageStopped||current!=this||root==null)return;
        if(paintMetricsStarted==0)paintMetricsStarted=SystemClock.uptimeMillis();
        paintWindowsUnwatched=0;
        IdentityHashMap<Window,Boolean> wanted=new IdentityHashMap<Window,Boolean>();
        if(presentation!=null&&presentation.isShowing())addPaintWindow(wanted,presentation.getWindow());
        for(Dialog modal:modals)if(modal.isShowing())addPaintWindow(wanted,modal.getWindow());
        if(keyboard!=null&&keyboard.isShowing())addPaintWindow(wanted,keyboard.getWindow());
        for(Window window:new ArrayList<Window>(paintWatches.keySet())){
            PaintWatch watch=paintWatches.get(window);
            if(!wanted.containsKey(window)||!watch.tree.isAlive()||watch.tree!=window.getDecorView().getViewTreeObserver()){
                watch.close();paintWatches.remove(window);
            }
        }
        for(Window window:wanted.keySet())if(!paintWatches.containsKey(window))paintWatches.put(window,new PaintWatch(window));
    }
    private void reportPaintMetrics(){
        long now=SystemClock.uptimeMillis(),elapsed=now-paintMetricsStarted;
        if(paintMetricsStarted==0||elapsed<2000)return;
        Log.i(TAG,"event=native-page-paint generation="+generation+" uptimeMs="+now+" windowMs="+elapsed
            +" watchedWindows="+paintWatches.size()+" unwatchedWindows="+paintWindowsUnwatched
            +" windowDrawCallbacks="+paintDrawCallbacks+" hwuiReports="+paintFrameReports+" droppedReports="+paintDroppedReports
            +" hwuiDrawDurationSamples="+paintDrawDurationSamples+" hwuiDrawDurationNsSum="+paintDrawDurationNs
            +" hwuiFrameDurationSamples="+paintFrameDurationSamples+" hwuiFrameDurationNsSum="+paintFrameDurationNs
            +" metricFailures="+paintMetricFailures+" snapshotObservations="+paintObservations
            +" snapshotChanges="+paintSnapshotChanges+" snapshotObserveSpanNs="+paintObserveSpanNs
            +" systemImeExternal="+(systemKeyboard!=null)+" offscreenPaintSuppressed=false evidence=passive-window-callbacks-not-gpu-cost-or-presentation textLogged=false visibleProof=false");
        paintMetricsStarted=now;paintDrawCallbacks=paintFrameReports=paintDroppedReports=0;
        paintDrawDurationNs=paintFrameDurationNs=paintDrawDurationSamples=paintFrameDurationSamples=0;
        paintMetricFailures=paintObservations=paintSnapshotChanges=paintObserveSpanNs=0;
    }

    private void stopNativePage(){
        nativePageStopped=true;
        pagePolls.removeCallbacksAndMessages(null);
        if(pageTree!=null&&pageTree.isAlive())pageTree.removeOnPreDrawListener(pageDraw);
        pageTree=null;nativePage.retire();
        for(PaintWatch watch:paintWatches.values())watch.close();paintWatches.clear();
    }

    /** Only the explicitly requested native view projection adds window transition fencing. */
    private void nativeWindowChanged(){
        if(!nativePageRequested.get()||nativePageStopped)return;
        pageContentEpoch++;nativePage.invalidate();cancelPointer();refreshPaintWatches();
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
        nativePage.invalidate();
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
            if(windowIds.closed(dialog))nativeWindowChanged();
        }});
        dialog.show();
        scaleWindowInsets(window);
        scaleModalContent(window);
        // Existing non-native routes retain their original show-call identity rotation.
        if(!nativePageRequested.get())windowIds.closed(dialog);
        boolean opened=windowIds.opened(dialog);
        if(!isKeyboard){
            boolean listed=false;
            if(nativePageRequested.get())for(Dialog shown:modals)if(shown==dialog){listed=true;break;}
            if(!listed)modals.add(dialog);
        }
        if(opened)nativeWindowChanged();
    }

    private boolean viewCurrent(View source){
        if(current!=this||root==null||source==null||!source.isAttachedToWindow()||!source.isEnabled())return false;
        if(contains(root,source))return true;
        for(Dialog modal:modals)if(modal.isShowing()&&contains(modal.getWindow().getDecorView(),source))return true;
        return false;
    }
    private boolean editorCurrent(){return viewCurrent(editor);}

    private void openKeyboard(EditText field) {
        if(resizeInputSuppressed())return;
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
        nativePage.invalidate();
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

    /** Original view state, never a rebuilt widget tree or a second edit buffer. */
    private static final class ViewScrollState {
        final View view;final int x,y;final boolean smooth;
        ViewScrollState(View view){
            this.view=view;x=view.getScrollX();y=view.getScrollY();
            smooth=view instanceof ScrollView?((ScrollView)view).isSmoothScrollingEnabled():
                view instanceof android.widget.HorizontalScrollView&&((android.widget.HorizontalScrollView)view).isSmoothScrollingEnabled();
        }
        void cancelMotion(){
            if(view instanceof ScrollView)((ScrollView)view).fling(0);
            else if(view instanceof android.widget.HorizontalScrollView)((android.widget.HorizontalScrollView)view).fling(0);
        }
        void freeze(){
            cancelMotion();
            if(view instanceof ScrollView){((ScrollView)view).setSmoothScrollingEnabled(false);}
            else if(view instanceof android.widget.HorizontalScrollView){
                ((android.widget.HorizontalScrollView)view).setSmoothScrollingEnabled(false);
            }
        }
        void restoreSmooth(){
            if(view instanceof ScrollView)((ScrollView)view).setSmoothScrollingEnabled(smooth);
            else if(view instanceof android.widget.HorizontalScrollView)((android.widget.HorizontalScrollView)view).setSmoothScrollingEnabled(smooth);
        }
    }
    private static final class ScrollSnapshot {
        final ArrayList<ViewScrollState> values=new ArrayList<ViewScrollState>();
        static ScrollSnapshot capture(View root){ScrollSnapshot result=new ScrollSnapshot();result.add(root);return result;}
        private void add(View view){
            values.add(new ViewScrollState(view));
            if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)add(group.getChildAt(i));}
        }
        boolean samePositions(ScrollSnapshot other){
            if(other==null||values.size()!=other.values.size())return false;
            for(int i=0;i<values.size();i++){ViewScrollState a=values.get(i),b=other.values.get(i);
                if(a.view!=b.view||a.x!=b.x||a.y!=b.y)return false;
            }
            return true;
        }
        void freeze(){for(ViewScrollState value:values)value.freeze();}
        boolean restore(){boolean changed=false;for(ViewScrollState value:values){
            if(value.view.getScrollX()!=value.x||value.view.getScrollY()!=value.y){value.view.scrollTo(value.x,value.y);changed=true;}
            value.cancelMotion();
        }return changed;}
        void restoreSmooth(){for(ViewScrollState value:values)value.restoreSmooth();}
        int firstVerticalY(){for(ViewScrollState value:values)if(value.view instanceof ScrollView)return value.y;return 0;}
        int currentFirstVerticalY(){for(ViewScrollState value:values)if(value.view instanceof ScrollView)return value.view.getScrollY();return 0;}
    }
    /** One admitted writer handoff. State1 also owes completed layout and original scroll. */
    private static final class ResizeRequest {
        final long id;final int width,height;Surface target;
        volatile int state;volatile int deferrals;volatile String reason="queued";
        boolean mutating,rollingBack;int oldWidth,oldHeight,stableFrames,restoreCount,restoreAttempts,admissionViewCount,commitScrollY;float oldScale;
        ScrollSnapshot observedScroll,scroll;ViewTreeObserver observer;ViewTreeObserver.OnPreDrawListener listener;
        ViewTreeObserver commitObserver;Runnable frameCommit;long frameSerial;boolean framePending;
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
    /** 0pending/deferred,1writer+layout committed,-1safe rejection/cancel,-2uncertain mutation. */
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
    private boolean resizeInputSuppressed(){
        ResizeRequest request=resizeRequest;return request!=null&&request.state==0&&request.mutating;
    }
    private boolean resizeOwnerCurrent(ResizeRequest request){
        return request==resizeRequest&&request.state==0&&current==this&&cancelledGeneration!=generation
            &&!resizeStopped&&!activity.isFinishing()&&!activity.isDestroyed();
    }
    private void removeResizeObserver(ResizeRequest request){
        if(request.observer!=null&&request.listener!=null&&request.observer.isAlive())request.observer.removeOnPreDrawListener(request.listener);
        request.observer=null;request.listener=null;
        if(request.commitObserver!=null&&request.frameCommit!=null&&request.commitObserver.isAlive())request.commitObserver.unregisterFrameCommitCallback(request.frameCommit);
        request.commitObserver=null;request.frameCommit=null;request.framePending=false;request.frameSerial++;
    }
    private boolean releaseResizeTarget(ResizeRequest request){
        Surface target=request.target;request.target=null;
        if(target!=null)try{target.release();}catch(RuntimeException failure){uncertainSurfaces.add(target);return false;}
        return true;
    }
    private void abortResizeSafely(ResizeRequest request,String reason){
        removeResizeObserver(request);if(request.scroll!=null)request.scroll.restoreSmooth();
        request.reason=reason;request.state=releaseResizeTarget(request)?-1:-2;
    }
    private void deferResize(final ResizeRequest request,String reason){
        request.scroll=null;request.observedScroll=null;request.stableFrames=0;
        request.reason=reason;if(request.deferrals<Integer.MAX_VALUE)request.deferrals++;
        if(!resizePolls.postDelayed(new Runnable(){public void run(){applyResize(request);}},100))abortResizeSafely(request,"queue-unavailable");
    }
    private void applyResize(final ResizeRequest request){
        synchronized(this){
            if(request!=resizeRequest||request.state!=0||request.mutating||request.listener!=null)return;
            if(!resizeOwnerCurrent(request)){abortResizeSafely(request,"owner-retired");return;}
            String busy=resizeBusyReason();if(busy!=null){deferResize(request,busy);return;}
            if(!request.target.isValid()){abortResizeSafely(request,"surface-invalid");return;}
            if(android.os.Build.VERSION.SDK_INT<29||!rootRaster.isHardwareAccelerated()){abortResizeSafely(request,"frame-commit-unavailable");return;}
            if(request.scroll==null){request.scroll=ScrollSnapshot.capture(root);request.admissionViewCount=request.scroll.values.size();
                Log.i(TAG,"event=raster-scroll-admission generation="+generation+" request="+request.id+" scrollY="+request.scroll.firstVerticalY()+" views="+request.admissionViewCount);}
            request.reason="awaiting-scroll-stability";request.observedScroll=ScrollSnapshot.capture(root);
            request.observer=rootRaster.getViewTreeObserver();
            request.listener=new ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){
                synchronized(NativeMeshUiProducer.this){
                    if(!resizeOwnerCurrent(request))return true;
                    String busy=resizeBusyReason();
                    if(busy!=null){removeResizeObserver(request);deferResize(request,busy);return true;}
                    ScrollSnapshot now=ScrollSnapshot.capture(root);
                    boolean unchanged=now.samePositions(request.observedScroll);
                    request.stableFrames=unchanged?request.stableFrames+1:0;
                    if(!unchanged){request.scroll=now;request.admissionViewCount=now.values.size();
                        Log.i(TAG,"event=raster-scroll-rebased-before-mutation generation="+generation+" request="+request.id+" scrollY="+now.firstVerticalY());}
                    request.observedScroll=now;
                    if(request.stableFrames<2){rootRaster.postInvalidateOnAnimation();return true;}
                    removeResizeObserver(request);beginResizeMutation(request);return false;
                }
            }};
            request.observer.addOnPreDrawListener(request.listener);rootRaster.invalidate();
        }
    }
    private void beginResizeMutation(final ResizeRequest request){
        nativePage.invalidate();
        request.oldWidth=rasterWidth;request.oldHeight=rasterHeight;request.oldScale=rasterScale;request.mutating=true;
        try{
            request.scroll.freeze();display.setSurface(null);display.resize(request.width,request.height,densityDpi);
            rasterScale=request.width/1024f;rootRaster.setRasterScale(rasterScale);display.setSurface(request.target);
            awaitResizeLayout(request);
        }catch(RuntimeException failure){rollbackResize(request);}
    }
    private void rollbackResize(final ResizeRequest request){
        removeResizeObserver(request);
        try{
            display.setSurface(null);display.resize(request.oldWidth,request.oldHeight,densityDpi);
            rasterScale=request.oldScale;rootRaster.setRasterScale(rasterScale);display.setSurface(surface);
            request.rollingBack=true;request.restoreAttempts=0;awaitResizeLayout(request);
        }catch(RuntimeException failure){
            if(request.scroll!=null)request.scroll.restoreSmooth();request.reason="mutation-uncertain";request.state=-2;
        }
    }
    private void awaitResizeLayout(final ResizeRequest request){
        request.reason=request.rollingBack?"awaiting-rollback-layout":"awaiting-layout-scroll-restore";
        request.observer=rootRaster.getViewTreeObserver();
        request.listener=new ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){
            synchronized(NativeMeshUiProducer.this){
                if(!resizeOwnerCurrent(request))return true;
                int width=request.rollingBack?request.oldWidth:request.width,height=request.rollingBack?request.oldHeight:request.height;
                if(!resizePhysicalLayoutReady(width,height)){rootRaster.invalidate();return false;}
                try{
                    if(request.scroll.restore()){
                        request.restoreCount++;request.restoreAttempts++;
                        if(request.restoreAttempts>8){
                            if(!request.rollingBack)rollbackResize(request);
                            else{removeResizeObserver(request);request.scroll.restoreSmooth();request.reason="scroll-restore-uncertain";request.state=-2;}
                            return false;
                        }
                        rootRaster.invalidate();return false;
                    }
                    if(!request.framePending)registerRestoredFrame(request);
                    return true;
                }catch(RuntimeException failure){
                    if(request.rollingBack){removeResizeObserver(request);request.scroll.restoreSmooth();request.reason="rollback-layout-uncertain";request.state=-2;}
                    else rollbackResize(request);
                    return false;
                }
            }
        }};
        request.observer.addOnPreDrawListener(request.listener);rootRaster.requestLayout();rootRaster.invalidate();
    }    private boolean resizePhysicalLayoutReady(int width,int height){
        View decor=presentation==null?null:presentation.getWindow().getDecorView();
        return decor!=null&&!decor.isLayoutRequested()&&!rootRaster.isLayoutRequested()&&!root.isLayoutRequested()
            &&decor.getWidth()==width&&decor.getHeight()==height&&rootRaster.getWidth()==width&&rootRaster.getHeight()==height;
    }
    private void registerRestoredFrame(final ResizeRequest request){
        if(android.os.Build.VERSION.SDK_INT<29||!rootRaster.isHardwareAccelerated())throw new IllegalStateException("mesh-frame-commit-unavailable");
        request.reason="awaiting-restored-frame-commit";request.framePending=true;
        final long serial=++request.frameSerial;request.commitObserver=rootRaster.getViewTreeObserver();
        request.frameCommit=new Runnable(){public void run(){
            resizePolls.post(new Runnable(){public void run(){finishRestoredFrame(request,serial);}});
        }};
        request.commitObserver.registerFrameCommitCallback(request.frameCommit);
    }
    private void finishRestoredFrame(ResizeRequest request,long serial){
        synchronized(this){
            if(!resizeOwnerCurrent(request)||serial!=request.frameSerial||!request.framePending)return;
            request.framePending=false;request.commitObserver=null;request.frameCommit=null;
            int width=request.rollingBack?request.oldWidth:request.width,height=request.rollingBack?request.oldHeight:request.height;
            if(!resizePhysicalLayoutReady(width,height)||!ScrollSnapshot.capture(root).samePositions(request.scroll)){
                request.reason="layout-changed-before-frame-ack";rootRaster.invalidate();return;
            }
            request.scroll.restore();request.commitScrollY=request.scroll.currentFirstVerticalY();
            removeResizeObserver(request);request.scroll.restoreSmooth();
            if(request.rollingBack){abortResizeSafely(request,"rolled-back-frame-restored");return;}
            Surface previous=surface;surface=request.target;request.target=null;rasterWidth=request.width;rasterHeight=request.height;
            rasterEpoch=request.id;request.reason="writer-frame-scroll-committed";request.state=1;
            try{previous.release();}catch(RuntimeException failure){uncertainSurfaces.add(previous);}
            Log.i(TAG,"event=raster-writer-committed generation="+generation+" request="+request.id+" epoch="+rasterEpoch
                +" width="+rasterWidth+" height="+rasterHeight+" scrollRetained=true admissionScrollY="+request.scroll.firstVerticalY()
                +" commitScrollY="+request.commitScrollY+" restoreCount="+request.restoreCount+" evidence=restored-frame-swapchain-commit");
        }
    }    private void stopResizeObservation(){
        synchronized(this){
            resizeStopped=true;resizePolls.removeCallbacksAndMessages(null);
            if(resizeRequest!=null){
                removeResizeObserver(resizeRequest);
                if(resizeRequest.scroll!=null)resizeRequest.scroll.restoreSmooth();
                if(resizeRequest.state==0)resizeRequest.reason="producer-stopped";
            }
        }
    }    public static void start(Activity activity, Surface surface, int width, int height, long generation) {
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
        final long contentEpoch=owner.pageContentEpoch,rasterEpoch=owner.rasterEpoch;
        if (owner.queuedPointers.incrementAndGet() > 16) {
            owner.queuedPointers.decrementAndGet();
            owner.pointerQueueInvalid = true;
            return;
        }
        owner.activity.runOnUiThread(new Runnable() { @Override public void run() {
            try {
            if (current != owner || owner.root == null) return;
            if(!NativePageSnapshot.Wire.current(generation,contentEpoch,rasterEpoch,
                    owner.generation,owner.pageContentEpoch,owner.rasterEpoch)){owner.cancelPointer();return;}
            if(owner.resizeInputSuppressed())return;
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
        stopNativePage();
        stopResizeObservation();

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
