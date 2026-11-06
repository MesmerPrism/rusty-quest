package io.github.mesmerprism.rustyquest.native_renderer;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Same app-private readback and generated JNI facade used by the canonical Activity. */
final class NativeLocalPanelBridge implements NativeLocalControlController.Host {
    interface Target { boolean current(); void outcome(String value); }
    final Context application;final Target target;private JSONObject packagedValues=new JSONObject();final Handler handler=new Handler(Looper.getMainLooper());
    NativeLocalPanelBridge(Context context,Target target){application=context.getApplicationContext();this.target=target;
        try(InputStream input=application.getAssets().open("native-app-settings.json")){
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int count;
            while((count=input.read(bytes))!=-1){if(output.size()+count>1048576)throw new IllegalStateException("settings-too-large");output.write(bytes,0,count);}
            JSONObject root=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            if("rusty.quest.native_app_settings.v1".equals(root.optString("schema"))&&root.optJSONObject("values")!=null)packagedValues=root.getJSONObject("values");
        }catch(Exception unavailable){ /* UI fallbacks stay explicitly provisional, never effective receipts. */ }
    }
    double setting(String suffix,double fallback){
        JSONObject entry=packagedValues.optJSONObject("native_renderer.private_particles."+suffix);
        if(entry==null)return fallback;
        try{double value=Double.parseDouble(entry.getString("value"));return Double.isFinite(value)?value:fallback;}catch(Exception error){return fallback;}
    }
    public boolean current(){return target.current();}
    public long nowMs(){return SystemClock.elapsedRealtime();}public long wallMs(){return System.currentTimeMillis();}
    public void schedule(Runnable task,long delay){handler.postDelayed(task,delay);}
    public void outcome(String value){if(current())target.outcome(value);}
    public JSONObject particles()throws Exception{
        if(!current())throw new IllegalStateException("panel-owner-retired");
        try(InputStream input=application.openFileInput("private_particle_dynamics_status.json")){
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int count;
            while((count=input.read(bytes))!=-1){if(output.size()+count>262144)throw new IllegalStateException("particle-status-too-large");output.write(bytes,0,count);}
            JSONObject value=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            if(!"rusty.quest.native_renderer.private_particle_dynamics_status.v1".equals(value.optString("schema")))throw new IllegalStateException("particle-status-schema");return value;
        }
    }
    public JSONObject breath()throws Exception{if(!current())throw new IllegalStateException("panel-owner-retired");return new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus());}
    public JSONObject applyParticles(JSONObject command)throws Exception{if(!current())throw new IllegalStateException("panel-owner-retired");return new JSONObject(ControlPanelActivity.nativeSubmitLivePrivateParticleDynamics(command.toString()));}
    public JSONObject applyBreath(JSONObject command)throws Exception{if(!current())throw new IllegalStateException("panel-owner-retired");return new JSONObject(ControlPanelActivity.nativeApplyBreathCompositionCommand(command.toString()));}
    void cancelQueued(){handler.removeCallbacksAndMessages(null);}
    void close(){cancelQueued();}
}
