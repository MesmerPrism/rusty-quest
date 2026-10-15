package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

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
    private final Surface surface;
    private VirtualDisplay display;
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
    private final ArrayList<Dialog> modals = new ArrayList<Dialog>();
    private Dialog pointerDialog;
    private Dialog keyboard;
    private EditText editor;
    private TextInputSession input;
    private EditorInfo editorInfo;
    private boolean capitals, symbols;
    private MeshActionMode selectionMode;

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
        void key(int code){if(valid()){
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
        }});
        dialog.show();
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
        if(field==editor&&keyboard!=null&&editorCurrent())return;
        closeKeyboard();editor=field;
        if(!editorCurrent()){retireEditor();return;}
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
        keyboard.setContentView(keys);
    }

    private void editorKey(int code){input.key(code);}

    private void retireEditor(){
        TextInputSession prior=input;input=null;editorInfo=null;editor=null;capitals=false;symbols=false;
        if(prior!=null)prior.close();
    }
    private void closeKeyboard(){
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
        return x>=origin[0]&&y>=origin[1]&&x<origin[0]+decor.getWidth()&&y<origin[1]+decor.getHeight();
    }
    private Dialog dialogAt(float x,float y){
        if(hit(keyboard,x,y))return keyboard;
        for(int i=modals.size()-1;i>=0;i--)if(modals.get(i).isShowing())return modals.get(i);
        return null;
    }
    private void dispatchPointer(MotionEvent event){
        if(pointerDialog==null){root.dispatchTouchEvent(event);return;}
        View decor=pointerDialog.getWindow().getDecorView();int[] origin=new int[2];decor.getLocationOnScreen(origin);
        event.offsetLocation(-origin[0],-origin[1]);pointerDialog.dispatchTouchEvent(event);
    }

    private NativeMeshUiProducer(Activity activity, Surface surface, long generation) {
        this.activity = activity;
        this.surface = surface;
        this.generation = generation;
    }

    public static void start(Activity activity, Surface surface, int width, int height, long generation) {
        if (width != 1024 || height != 768 || generation <= 0 || !surface.isValid()) {
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
            NativeMeshUiProducer owner = new NativeMeshUiProducer(activity, surface, generation);
            current = owner;
            try {
                DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
                owner.display = manager.createVirtualDisplay("Rusty Quest mesh UI", width, height, 160, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                if (owner.display == null) throw new IllegalStateException("VirtualDisplay unavailable");
                owner.presentation = new Presentation(activity, owner.display.getDisplay()) {
                    @Override protected void onCreate(Bundle state) {
                        super.onCreate(state);
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        owner.build(this.getContext());
                        setContentView(owner.root);
                    }
                };
                owner.presentation.show();
                Log.i(TAG, "event=producer-started generation=" + generation + " source=android-presentation width=1024 height=768");
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
        try {
            // The closed panel source selection owns this optional adapter and all session effects.
            Class<?> module=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity");
            Object attachment=module.getMethod("attachNativeMeshSessionControls",Activity.class,LinearLayout.class,
                java.util.function.BooleanSupplier.class).invoke(null,activity,layout,
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return current==NativeMeshUiProducer.this && root!=null;
                        }
                    });
            if(!(attachment instanceof AutoCloseable))throw new IllegalStateException("panel-attachment-retirement-required");
            panelAttachment=(AutoCloseable)attachment;
            layout.removeView(title);layout.removeView(status);
            observePanelVisibility();
            return; // Selected local product owns its full UI; integration diagnostics are not appended.
        } catch(ClassNotFoundException unselected) {
            // Neutral mesh-only apps do not acquire experiment or recording authority.
        } catch(NoSuchMethodException unselected) {
            // A different closed panel module does not expose this product adapter.
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

    /** True means this actual product owner was handled, including a rejected request. */
    public static boolean requestPanelVisibility(Activity productOwner, boolean visible) {
        NativeMeshUiProducer producer=productProducer(productOwner);
        if(producer==null||!((ProductPanelAttachment)producer.panelAttachment).presentationActive())return false;
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
        if(producer==null||!(producer.panelAttachment instanceof ProductPanelAttachment)
                ||((ProductPanelAttachment)producer.panelAttachment).owner()!=productOwner
                ||!((ProductPanelAttachment)producer.panelAttachment).presentationActive())return true;
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
        Log.i(TAG, "event=producer-stop-requested generation=" + generation);
        // Always enqueue behind any pending start, even if current is still null.
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            boolean released = false;
            try {
                NativeMeshUiProducer owner = current;
                if (owner == null || owner.generation != generation) { released = true; }
                else if (owner.close()) { current = null; released = true; }
            } finally {
                receipt.state = released ? 1 : -1;
                Log.i(TAG, "event=producer-stop-ack generation=" + generation + " released=" + released);
            }
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
        visibilityPolls.removeCallbacksAndMessages(null);
        try { closeTransientInput(); } catch(RuntimeException error){released=false;}
        try { cancelPointer(); } catch (RuntimeException error) { released = false; }
        try { if(panelAttachment!=null){panelAttachment.close();panelAttachment=null;} }
        catch(Exception error){released=false;}
        try { if (presentation != null) { presentation.dismiss(); presentation = null; } }
        catch (RuntimeException error) { released = false; }
        try { if (display != null) { display.release(); display = null; } }
        catch (RuntimeException error) { released = false; }
        try { surface.release(); } catch (RuntimeException error) { released = false; }
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
}
