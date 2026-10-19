package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

/** System IME transport to the original mesh editor. Owns no text or keyboard rendering. */
public final class NativeSystemKeyboard implements AutoCloseable {
    private static final String TAG="RQNativeSystemIme";
    private final Activity activity;
    private final EditText editor;
    private final BooleanSupplier current;
    private final Runnable closed;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final EditorProxy proxy;
    private final InputMethodManager imm;
    private final Runnable requestAgain=new Runnable(){public void run(){request();}};
    private final Runnable selectionChanged=new Runnable(){public void run(){observeSelection();}};
    private final TextWatcher watcher=new TextWatcher(){
        public void beforeTextChanged(CharSequence text,int start,int count,int after){}
        public void onTextChanged(CharSequence text,int start,int before,int count){notifySelectionChanged();}
        public void afterTextChanged(Editable text){}
    };
    private final ViewTreeObserver.OnPreDrawListener selectionObserver=new ViewTreeObserver.OnPreDrawListener(){
        public boolean onPreDraw(){observeSelection();return true;}
    };
    private ViewTreeObserver observedTree;
    private ConnectionLease lease;
    private boolean retired,observedImeVisible;
    private int attempts,lastStart=Integer.MIN_VALUE,lastEnd,lastComposingStart,lastComposingEnd;

    /** Explicit APK feature selection; runtime properties cannot turn this route on. */
    public static boolean selected(Activity activity){
        try{
            PackageInfo info=activity.getPackageManager().getPackageInfo(activity.getPackageName(),PackageManager.GET_CONFIGURATIONS);
            if(info.reqFeatures!=null)for(FeatureInfo feature:info.reqFeatures)
                if("oculus.software.overlay_keyboard".equals(feature.name))return true;
        }catch(PackageManager.NameNotFoundException unavailable){return false;}
        return false;
    }
    public static NativeSystemKeyboard open(Activity activity,EditText editor,BooleanSupplier current,Runnable closed){
        requireMain();
        if(activity==null||editor==null||current==null||closed==null)throw new IllegalArgumentException("system-ime-owner-required");
        if(!selected(activity)||activity.isFinishing()||activity.isDestroyed()||!editor.isAttachedToWindow()||!current.getAsBoolean())
            throw new IllegalStateException("system-ime-owner-unavailable");
        return new NativeSystemKeyboard(activity,editor,current,closed);
    }
    private NativeSystemKeyboard(Activity activity,EditText editor,BooleanSupplier current,Runnable closed){
        this.activity=activity;this.editor=editor;this.current=current;this.closed=closed;
        proxy=new EditorProxy(activity);
        proxy.setFocusable(true);proxy.setFocusableInTouchMode(true);proxy.setAlpha(0f);proxy.setVisibility(View.VISIBLE);
        try{
        activity.addContentView(proxy,new ViewGroup.LayoutParams(1,1));
        Context displayContext=proxy.getDisplay()==null?proxy.getContext():proxy.getContext().createDisplayContext(proxy.getDisplay());
        imm=displayContext.getSystemService(InputMethodManager.class);
        if(imm==null)throw new IllegalStateException("system-ime-service-unavailable");
        editor.addTextChangedListener(watcher);
        observedTree=editor.getViewTreeObserver();observedTree.addOnPreDrawListener(selectionObserver);
        if(android.os.Build.VERSION.SDK_INT>=30)proxy.setOnApplyWindowInsetsListener((view,insets)->{
            boolean visible=insets.isVisible(WindowInsets.Type.ime());
            if(visible)observedImeVisible=true;
            else if(observedImeVisible&&!retired)handler.post(()->close());
            return insets;
        });
        handler.post(requestAgain);
        }catch(RuntimeException failure){
            retired=true;handler.removeCallbacks(requestAgain);handler.removeCallbacks(selectionChanged);
            // Construction never published this session: clean each attached resource without
            // masking the original failure or invoking the owner's normal close callback.
            try{editor.removeTextChangedListener(watcher);}catch(RuntimeException ignored){}
            try{if(observedTree!=null&&observedTree.isAlive())observedTree.removeOnPreDrawListener(selectionObserver);}catch(RuntimeException ignored){}
            observedTree=null;
            try{if(lease!=null)lease.retire(true);}catch(RuntimeException ignored){}
            lease=null;
            try{proxy.setOnApplyWindowInsetsListener(null);}catch(RuntimeException ignored){}
            try{removeProxy();}catch(RuntimeException ignored){}
            Log.w(TAG,"event=system-ime-construction-failed textLogged=false visibleProof=false");
            throw failure;
        }
    }

    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("system-ime-requires-main-thread");}
    private boolean live(){return !retired&&!activity.isFinishing()&&!activity.isDestroyed()&&editor.isAttachedToWindow()&&current.getAsBoolean();}
    private void request(){
        if(!live()){close();return;}
        if(++attempts>2)return;
        final boolean accepted;
        try{
            proxy.requestFocus();imm.restartInput(proxy);
            accepted=imm.showSoftInput(proxy,InputMethodManager.SHOW_IMPLICIT);
        }catch(RuntimeException unavailable){
            Log.w(TAG,"event=system-ime-request-failed textLogged=false visibleProof=false");
            close();return;
        }
        Log.i(TAG,"event=system-ime-request attempt="+attempts+" attached="+proxy.isAttachedToWindow()
            +" focused="+proxy.hasFocus()+" windowFocused="+proxy.hasWindowFocus()+" tokenPresent="+(proxy.getWindowToken()!=null)
            +" proxyDisplay="+(proxy.getDisplay()==null?-1:proxy.getDisplay().getDisplayId())+" accepted="+accepted+" textLogged=false visibleProof=false");
        observeSelection();
        if(!accepted){
            if(attempts<2)handler.postDelayed(requestAgain,150);
            else close(); // A later explicit field tap may request a new session.
        }
    }
    /** Optional original PanelEditText selection callback; pre-draw also observes selection-only edits. */
    public void notifySelectionChanged(){
        if(Looper.myLooper()==Looper.getMainLooper())observeSelection();
        else handler.post(selectionChanged);
    }
    private void observeSelection(){
        if(!live())return;
        int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        Editable text=editor.getText();
        int composingStart=BaseInputConnection.getComposingSpanStart(text),composingEnd=BaseInputConnection.getComposingSpanEnd(text);
        if(start==lastStart&&end==lastEnd&&composingStart==lastComposingStart&&composingEnd==lastComposingEnd)return;
        lastStart=start;lastEnd=end;lastComposingStart=composingStart;lastComposingEnd=composingEnd;
        try{imm.updateSelection(proxy,start,end,composingStart,composingEnd);}
        catch(RuntimeException unavailable){Log.w(TAG,"event=system-ime-selection-update-failed textLogged=false");}
    }
    @Override public void close(){
        requireMain();if(retired)return;retired=true;
        handler.removeCallbacks(requestAgain);handler.removeCallbacks(selectionChanged);
        editor.removeTextChangedListener(watcher);
        if(observedTree!=null&&observedTree.isAlive())observedTree.removeOnPreDrawListener(selectionObserver);
        observedTree=null;
        try{
            if(lease!=null){
                try{lease.retire(true);}catch(RuntimeException failure){Log.w(TAG,"event=finish-composition-failed textLogged=false");}
                lease=null;
            }
            try{if(imm.isActive(proxy))imm.hideSoftInputFromWindow(proxy.getWindowToken(),0);}
            catch(RuntimeException unavailable){Log.w(TAG,"event=system-ime-hide-failed textLogged=false visibleProof=false");}
        }finally{
            proxy.setOnApplyWindowInsetsListener(null);proxy.clearFocus();removeProxy();
            Log.i(TAG,"event=system-ime-retired textLogged=false visibleProof=false");
            closed.run();
        }
    }
    private void removeProxy(){if(proxy.getParent() instanceof ViewGroup)((ViewGroup)proxy.getParent()).removeView(proxy);}
    private final class EditorProxy extends View {
        EditorProxy(Context context){super(context);}
        @Override public boolean onCheckIsTextEditor(){return live();}
        @Override public InputConnection onCreateInputConnection(EditorInfo info){
            if(!live())return null;
            if(lease!=null)lease.retire(false);
            // Populate the framework's actual EditorInfo using the original field, preserving
            // input type, password transformation, selection, editor actions and input filters.
            InputConnection original=editor.onCreateInputConnection(info);
            if(original==null){lease=null;return null;}
            lease=new ConnectionLease(original);return lease.guarded;
        }
        @Override public boolean onKeyPreIme(int code,KeyEvent event){
            if(code==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)close();return true;}
            return super.onKeyPreIme(code,event);
        }
        // Loss of window focus is expected while Meta's keyboard overlay owns focus.
    }
    private final class ConnectionLease {
        final InputConnection original;
        final InputConnectionWrapper wrapper;
        final InputConnection guarded;
        boolean active=true;
        ConnectionLease(InputConnection original){
            this.original=original;wrapper=new InputConnectionWrapper(original,true);
            guarded=(InputConnection)Proxy.newProxyInstance(InputConnection.class.getClassLoader(),new Class<?>[]{InputConnection.class},
                (object,method,arguments)->invoke(object,method,arguments));
        }
        Object invoke(Object object,Method method,Object[] arguments)throws Throwable{
            if(method.getDeclaringClass()==Object.class){
                if("toString".equals(method.getName()))return "NativeSystemKeyboardConnection";
                if("hashCode".equals(method.getName()))return System.identityHashCode(object);
                if("equals".equals(method.getName()))return object==arguments[0];
            }
            if("getHandler".equals(method.getName()))return handler;
            if(!active||!live())return empty(method.getReturnType());
            try{return method.invoke(wrapper,arguments);}catch(InvocationTargetException error){throw error.getCause();}
        }
        void retire(boolean finish){
            if(!active)return;active=false;
            try{if(finish)original.finishComposingText();}finally{wrapper.setTarget(null);}
        }
    }
    private static Object empty(Class<?> type){
        if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;
        if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==byte.class)return (byte)0;
        if(type==short.class)return (short)0;if(type==char.class)return (char)0;return null;
    }
}
