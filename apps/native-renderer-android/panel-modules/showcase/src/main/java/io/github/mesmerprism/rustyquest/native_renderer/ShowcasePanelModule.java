package io.github.mesmerprism.rustyquest.native_renderer;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import org.json.JSONObject;
/** Legacy feature-off Activity wrapper; the form/controller is shared with the genuine native host. */
public class ShowcasePanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID="visual-showcase-controls";
    private ShowcaseControls controls;
    private boolean resumed;
    private TripleBSequence tripleB=new TripleBSequence(5000);
    public String panelModuleId(){return MODULE_ID;}
    static JSONObject visualPayload(JSONObject snapshot)throws Exception{return ShowcaseControls.visualPayload(snapshot);}
    protected void onCreate(Bundle saved){super.onCreate(saved);tripleB=new TripleBSequence(panelPressWindowMillis());ShowcaseNativeBridge.initialize(this);ShowcaseNativeBridge.attach(this);controls=new ShowcaseControls(this,new ShowcaseControls.Host(){public boolean current(){return !isFinishing()&&!isDestroyed();}public void closePanel(){finish();}public android.widget.EditText createEditor(android.content.Context context){return new android.widget.EditText(context);}public android.widget.Spinner createSpinner(android.content.Context context,String label){return new android.widget.Spinner(context);}});try{setContentView(controls.attach(this));}catch(Exception error){throw new IllegalStateException("showcase-form-unavailable",error);}}
    protected void onResume(){super.onResume();resumed=true;tripleB.reset();if(controls!=null)controls.resumed();}
    protected void onPause(){resumed=false;tripleB.reset();if(controls!=null)controls.paused();super.onPause();}
    protected void onDestroy(){if(controls!=null)controls.close();ShowcaseNativeBridge.detach(this);super.onDestroy();}
    public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(controls!=null)controls.permissions(code,permissions,results);}
    public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);tripleB.reset();}
    public void closePanelAndReturnToImmersive(){finish();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()!=KeyEvent.KEYCODE_BUTTON_B)return super.dispatchKeyEvent(event);
        if(!resumed||!hasWindowFocus()||event.isCanceled()){tripleB.reset();return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN)tripleB.down(event.getEventTime(),event.getDownTime(),event.getRepeatCount());
        else if(event.getAction()==KeyEvent.ACTION_UP){if(tripleB.up(event.getEventTime(),event.getDownTime()))closePanelAndReturnToImmersive();}
        else tripleB.reset();
        return true;
    }
    private long panelPressWindowMillis(){
        // Same packaged property/default/bounds as SameApkPanelActionSettings.
        try(java.io.InputStream input=getAssets().open("native-app-settings.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>1048576)return 5000;}
            JSONObject values=new JSONObject(bytes.toString("UTF-8")).getJSONObject("values");
            JSONObject entry=values.optJSONObject("debug.rustyquest.native_renderer.control_panel.right_secondary_action.window_seconds");
            if(entry==null)return 5000;
            float seconds=Float.parseFloat(entry.getString("value").trim());
            if(Float.isNaN(seconds)||Float.isInfinite(seconds))return 5000;
            return (long)(Math.max(0.25f,Math.min(30f,seconds))*1000f);
        }catch(Exception unavailable){return 5000;}
    }
    static final class TripleBSequence {
        private final long windowMillis;
        private long firstDown=-1,pressedDown=-1,lastTime=-1;
        private int complete;
        TripleBSequence(long windowMillis){if(windowMillis<250||windowMillis>30000)throw new IllegalArgumentException("panel-window-bound");this.windowMillis=windowMillis;}
        void reset(){firstDown=-1;pressedDown=-1;lastTime=-1;complete=0;}
        private boolean observe(long now){if(now<0||lastTime>now){reset();return false;}lastTime=now;return true;}
        void down(long now,long downTime,int repeats){
            if(!observe(now)||repeats!=0||pressedDown>=0)return;
            if(downTime<0||downTime>now){reset();return;}
            if(firstDown>=0&&now-firstDown>windowMillis){complete=0;firstDown=-1;}
            if(firstDown<0)firstDown=now;
            pressedDown=downTime;
        }
        boolean up(long now,long downTime){
            if(!observe(now))return false;
            if(pressedDown<0)return false; // Includes the opening press's orphan key-up.
            if(downTime!=pressedDown||now-firstDown>windowMillis){reset();return false;}
            pressedDown=-1;
            if(++complete<3)return false;
            reset();return true;
        }
    }
}
