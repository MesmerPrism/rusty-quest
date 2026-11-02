package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

/** App-lifetime projection of the native settings owner. No sensor history sink. */
public final class ShowcaseNativeBridge {
    public static final String PRODUCT="visual-showcase";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static Context application;
    private static ShowcaseGateway gateway;
    private static ToneGenerator cue;
    private static boolean active;
    private static WeakReference<Activity> panel=new WeakReference<>(null);
    private static long nextCue;
    private static final java.util.LinkedHashMap<String,JSONObject> stopReads=new java.util.LinkedHashMap<>();
    public static native String nativeShowcaseInitialize(String defaults,String settings,String migrations);
    public static native String nativeShowcaseCommand(String json);
    public static native String nativeShowcaseStatus();
    public static native String nativeShowcaseReceipt(String id);
    public static native String nativeShowcaseSettings();

    public static void initialize(Context context) {
        MAIN.post(new Runnable(){public void run(){
            if(application!=null)return;
            application=context.getApplicationContext();
            try {
                System.loadLibrary("rusty_quest_native_renderer");
                if(!(context instanceof Activity))throw new IllegalStateException("actual-activity-required");
                String defaults=NativeAppSettingsReader.readAsset((Activity)context,"showcase-default-visual.json");
                String migrations=NativeAppSettingsReader.readAsset((Activity)context,"showcase-builtin-migrations.json");
                if(defaults.getBytes(StandardCharsets.UTF_8).length>65536||migrations.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("bounded-showcase-assets-required");
                String saved=application.getSharedPreferences("visual_showcase_settings",Context.MODE_PRIVATE).getString("settings","");
                JSONObject initialized=new JSONObject(nativeShowcaseInitialize(defaults,saved,migrations));
                if(!PRODUCT.equals(initialized.optString("product"))||!initialized.optBoolean("ready")||!initialized.optString("epoch").matches("[1-9][0-9]{0,18}"))throw new IllegalStateException("native-initialization-unavailable");
                PolarSensorRuntime.reopenClosedFromExplicitLaunch();
                PolarSensorRuntime.forApplication(application).ensureAutoConnection();
                gateway=new ShowcaseGateway(application);
                active=true;
                MAIN.post(tick);
            } catch(Exception|UnsatisfiedLinkError error) {
                android.util.Log.e("RQShowcase","showcase-initialization-unavailable",error);
                shutdown();
            }
        }});
    }

    static JSONObject status() throws Exception {
        JSONObject state=new JSONObject(nativeShowcaseStatus());
        if(!PRODUCT.equals(state.getString("product")))throw new SecurityException("product-mismatch");
        JSONObject setup=PolarSensorRuntime.existingRemoteSetupStatus(Long.parseLong(state.getString("epoch")));
        if(setup!=null)state.put("polar_setup",setup);
        return state;
    }

    static JSONObject command(JSONObject command) throws Exception {
        if(!PRODUCT.equals(command.optString("product")))throw new SecurityException("product-mismatch");
        JSONObject receipt=new JSONObject(nativeShowcaseCommand(command.toString()));
        // Only the native allowlisted settings projection may enter persistent storage.
        // Rejected commands cannot persist a caller-supplied object.
        String settings=nativeShowcaseSettings();
        if(settings.length()>262144)throw new IllegalStateException("settings-size");
        new JSONObject(settings);
        application.getSharedPreferences("visual_showcase_settings",Context.MODE_PRIVATE).edit().putString("settings",settings).apply();
        return receipt;
    }

    /** Called by the native command owner only after closed envelope validation.
     * Pending radio operations are reported pending, never claimed effective. */
    public static String executePlatform(String operation,String payload) {
        try {
            if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("platform-main-thread-required");
            JSONObject value=new JSONObject(payload);
            if("set-phone-control".equals(operation)) {
                if(value.length()!=1||!(value.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("closed-consent-required");
                boolean enabled=value.getBoolean("enabled");setPhoneControl(enabled);
                return new JSONObject().put("status","applied").put("effective",new JSONObject().put("enabled",phoneControl())).toString();
            }
            PolarSensorRuntime runtime=PolarSensorRuntime.forApplication(application);
            long epoch=Long.parseLong(new JSONObject(nativeShowcaseStatus()).getString("epoch"));
            JSONObject before=runtime.remoteSetupStatus(epoch);
            if("polar-disconnect".equals(operation)) {
                if(value.length()!=0)throw new IllegalArgumentException("closed-payload-required");
                runtime.dispatchFromPanel("disconnect",java.util.UUID.randomUUID().toString().replace("-",""));
                JSONObject effective=runtime.remoteSetupStatus(epoch);
                return new JSONObject().put("status",!effective.optBoolean("c")?"applied":"pending").put("effective",effective).toString();
            }
            String op;
            if("polar-scan".equals(operation))op="scan";
            else if("polar-select".equals(operation))op="select";
            else if("polar-connect".equals(operation))op="connect";
            else if("polar-start-acc".equals(operation))op="start-acc";
            else if("polar-stop-acc".equals(operation)) {
                if(value.length()!=0)throw new IllegalArgumentException("closed-payload-required");
                JSONObject beforeStop=PolarSensorRuntime.existingProjectedStopState();
                JSONObject request=new JSONObject().put("schema",PolarProjectedStopActions.SCHEMA).put("operation","stop_acc").put("operation_id",beforeStop.getString("stop_acc_operation_id"));
                for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor","acc_revision","ecg_revision"})request.put(field,beforeStop.get(field));
                JSONObject result=PolarSensorRuntime.dispatchExistingProjectedStop(request,java.util.UUID.randomUUID().toString().replace("-",""));
                JSONObject current=PolarSensorRuntime.existingProjectedStopState();
                String id=java.util.UUID.randomUUID().toString();
                stopReads.put(id,new JSONObject().put("epoch",Long.toString(epoch)).put("owner",current.getString("owner_epoch")).put("revision",current.getLong("acc_revision")).put("started",android.os.SystemClock.elapsedRealtime()));
                while(stopReads.size()>64)stopReads.remove(stopReads.keySet().iterator().next());
                return new JSONObject().put("status","pending").put("effective",runtime.remoteSetupStatus(epoch)).put("acquisition_receipt",new JSONObject().put("id",id).put("state","pending")).toString();
            }
            else throw new IllegalArgumentException("unsupported-platform-operation");
            if(value.length()!=("select".equals(op)?1:0))throw new IllegalArgumentException("closed-payload-required");
            String id=java.util.UUID.randomUUID().toString();
            JSONObject request=new JSONObject().put("id",id).put("op",op).put("s",before.getString("s")).put("g",epoch).put("r",before.getLong("r")).put("d","select".equals(op)?value.getString("device_id"):"");
            JSONObject receipt=runtime.dispatchRemoteSetup(request,epoch);
            return new JSONObject().put("status","confirmed".equals(receipt.optString("state"))?"applied":"pending").put("effective",runtime.remoteSetupStatus(epoch)).put("acquisition_receipt",receipt).toString();
        }catch(Exception error){try{return new JSONObject().put("status","rejected").put("reason",error.getMessage()).toString();}catch(Exception impossible){return "{\"status\":\"rejected\"}";}}
    }

    public static String readPlatformReceipt(String id) {
        try {
            if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("platform-main-thread-required");
            long epoch=Long.parseLong(new JSONObject(nativeShowcaseStatus()).getString("epoch"));
            JSONObject stop=stopReads.get(id);
            if(stop!=null){
                JSONObject current=PolarSensorRuntime.existingProjectedStopState();
                boolean bound=Long.toString(epoch).equals(stop.getString("epoch"))&&stop.getString("owner").equals(current.getString("owner_epoch"))&&stop.getLong("revision")==current.getLong("acc_revision");
                boolean effective=bound&&!current.getBoolean("acc_requested")&&!current.getBoolean("acc_pmd_ack_running");
                String status=effective?"applied":!bound||android.os.SystemClock.elapsedRealtime()-stop.getLong("started")>30000?"unknown":"pending";
                return new JSONObject().put("status",status).put("effective",current).toString();
            }
            JSONObject receipt=PolarSensorRuntime.existingRemoteSetupReceipt(id,epoch);
            String state=receipt.optString("state");
            return new JSONObject().put("status","confirmed".equals(state)?"applied":"pending".equals(state)?"pending":"unknown").put("effective",PolarSensorRuntime.existingRemoteSetupStatus(epoch)).put("acquisition_receipt",receipt).toString();
        }catch(Exception error){return "{\"status\":\"unknown\"}";}
    }

    static void setPhoneControl(boolean enabled){
        application.getSharedPreferences("visual_showcase_settings",Context.MODE_PRIVATE).edit().putBoolean("phone_control",enabled).apply();
        try{gateway.reconcile(status());}catch(Exception ignored){}
    }
    static boolean phoneControl(){return application!=null&&application.getSharedPreferences("visual_showcase_settings",Context.MODE_PRIVATE).getBoolean("phone_control",false);}
    static JSONObject connectionDetails()throws Exception{return gateway==null?new JSONObject():gateway.connectionDetails();}
    static void requestDiscoveryPermissions(Activity host){if(gateway!=null)gateway.requestPermissions(host);}
    static void discoveryPermissionResult(){if(gateway!=null)gateway.permissionResult();}

    public static void openPanel(Context context) {
        MAIN.post(new Runnable(){public void run(){initialize(context);ControlPanelActivity.openShowcasePanel(context);}});
    }
    public static void togglePanel(Context context) {
        MAIN.post(new Runnable(){public void run(){ControlPanelActivity.toggleShowcasePanel(context);}});
    }
    static Activity currentPanel(){return panel.get();}
    static void attach(Activity value){panel=new WeakReference<>(value);}
    static void detach(Activity value){if(panel.get()==value)panel=new WeakReference<>(null);}
    public static void resume(){MAIN.post(new Runnable(){public void run(){active=true;MAIN.removeCallbacks(tick);MAIN.post(tick);}});}
    public static void suspend(){MAIN.post(new Runnable(){public void run(){active=false;MAIN.removeCallbacks(tick);releaseCue();}});}
    public static void shutdown(){MAIN.post(new Runnable(){public void run(){active=false;MAIN.removeCallbacks(tick);releaseCue();if(gateway!=null)gateway.close();gateway=null;PolarSensorRuntime.closeShowcaseFromOwner();stopReads.clear();application=null;}});}
    private static void releaseCue(){if(cue!=null){cue.release();cue=null;}nextCue=0;}
    private static final Runnable tick=new Runnable(){public void run(){
        if(!active||application==null)return;
        try {
            JSONObject state=status();
            if(gateway!=null)gateway.reconcile(state);
            JSONObject availability=state.optJSONObject("availability");
            boolean missing=availability!=null&&availability.optBoolean("selected_polar_unavailable",false);
            long now=android.os.SystemClock.elapsedRealtime();
            if(missing&&now>=nextCue){
                if(cue==null)cue=new ToneGenerator(AudioManager.STREAM_MUSIC,12);
                cue.startTone(ToneGenerator.TONE_PROP_BEEP,100);nextCue=now+3000;
            } else if(!missing)releaseCue();
        } catch(Exception|UnsatisfiedLinkError unavailable){releaseCue();}
        MAIN.postDelayed(this,250);
    }};
}
