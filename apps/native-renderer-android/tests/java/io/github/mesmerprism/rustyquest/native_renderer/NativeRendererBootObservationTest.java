package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Real receiver/Application/launch authority bodies over host Android boundaries. */
public final class NativeRendererBootObservationTest {
    private static int checks;
    private static final String RECEIPT = "native_boot_observation";
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Context.MemoryPreferences receipt() {
        return (Context.MemoryPreferences) new Context().getSharedPreferences(RECEIPT, 0);
    }
    private static Context.MemoryPreferences admission() {
        return (Context.MemoryPreferences) new Context().getSharedPreferences("native_boot_launch", 0);
    }
    private static NativeRendererSelfKioskApplication reset(boolean selected, boolean product) {
        NativeRendererExperimentLaunchAuthority.invalidatePending();
        Context.preferences.clear(); Context.preferenceAccesses.clear(); Context.launches.clear(); Context.beforeStart=null; Context.launchFails=false;
        PackageManager.missing=false; PackageManager.lookupFails=false; PackageManager.metadata=new android.os.Bundle();
        PackageManager.metadata.put("rusty.quest.experiment.boot_autostart", selected);
        PackageManager.metadata.put("rusty.quest.native_renderer.original_panel_native_host", product);
        Settings.overlay=true; Settings.overlayFails=false; Settings.Global.bootCount=7;
        NativeRendererSelfKioskApplication app=new NativeRendererSelfKioskApplication();
        Context.application=app; app.onCreate(); return app;
    }
    private static void boot(Context context) {
        new NativeRendererBootReceiver().onReceive(context, new Intent("android.intent.action.BOOT_COMPLETED"));
    }
    private static Activity nativeActivity(boolean product) {
        Activity activity=product?new NativeProductPanelActivity():new NativeActivity();
        activity.intent.setClassName("test.app", activity.getClass().getName()); return activity;
    }
    private static void outcome(String wanted) {
        check(wanted.equals(receipt().getString("receiver_last_reason", "missing")), "receiver reason: "+wanted);
    }
    private static void unselectedReceiverIsInert(String selection) {
        NativeRendererSelfKioskApplication app=reset(false,false);
        if("missing".equals(selection))PackageManager.metadata.remove("rusty.quest.experiment.boot_autostart");
        if("null-metadata".equals(selection))PackageManager.metadata=null;
        if("unavailable".equals(selection))PackageManager.missing=true;
        if("lookup-failed".equals(selection))PackageManager.lookupFails=true;
        if("false-platform-check-failed".equals(selection))Settings.overlayFails=true;
        Context.MemoryPreferences retained=new Context.MemoryPreferences();
        retained.values.put("sentinel", "prior-receipt");
        Context.preferences.put(RECEIPT,retained);
        boot(app);
        check(Context.preferenceAccesses.getOrDefault(RECEIPT,0)==0,
            "false/unknown selection must perform zero diagnostic preference access: "+selection);
        check(retained.values.size()==1 && "prior-receipt".equals(retained.values.get("sentinel")),
            "false/unknown selection must preserve an existing diagnostic file: "+selection);
        check(Context.launches.isEmpty(), "false/unknown selection cannot launch: "+selection);
    }
    public static void main(String[] args) {
        for (boolean product : new boolean[]{false,true}) {
            NativeRendererSelfKioskApplication app=reset(true,product);
            Context.beforeStart=()->check(admission().getInt("last_attempted_boot_count", -1)==7,
                "launch must follow committed admission");
            boot(app);
            check(receipt().getInt("boot_count", -1)==7, "receipt must bind the observed boot count");
            check("rusty.quest.boot_observation.v1".equals(receipt().getString("schema", "")), "typed receipt");
            outcome("admitted");
            check("committed".equals(receipt().getString("admission_outcome", "")), "committed admission");
            check("requested-unconfirmed".equals(receipt().getString("dispatch_outcome", "")), "dispatch is unconfirmed");
            check(Context.launches.size()==1, "one dispatch");
            Intent launch=Context.launches.get(0);
            check((product?"io.github.mesmerprism.rustyquest.native_renderer.NativeProductPanelActivity":"android.app.NativeActivity")
                .equals(launch.getComponent().getClassName()), "manifest-selected physical host");
            check("test.app".equals(launch.getComponent().getPackageName()) && "android.intent.action.MAIN".equals(launch.getAction())
                && launch.getCategories().size()==1 && launch.getCategories().contains("android.intent.category.LAUNCHER")
                && launch.flags==0x10000000, "closed own front door");
            check(receipt().getInt("native_created_order",0)==0 && receipt().getInt("trusted_launch_order",0)==0,
                "dispatch alone must not fabricate Activity or trusted launch");
            boot(app); outcome("already-attempted");
            check(Context.launches.size()==1 && receipt().getInt("receiver_deliveries",0)==2, "duplicate delivery never dispatches");
            check("requested-unconfirmed".equals(receipt().getString("dispatch_outcome", "")), "duplicate preserves original dispatch");

            Activity wrong=nativeActivity(!product);
            app.callbacks.onActivityCreated(wrong,null); app.callbacks.onActivityResumed(wrong);
            check(NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(wrong,false)==0, "unselected physical native host rejects");
            check(receipt().getInt("native_created_order",0)==0, "unselected physical host cannot become native witness");
            Activity actual=nativeActivity(product);
            if(product)app.callbacks.onActivityCreated(actual,null);
            long epoch=NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(actual,false);
            check(epoch>0, "selected physical cold route admitted");
            if(!product)app.callbacks.onActivityCreated(actual,null);
            app.callbacks.onActivityResumed(actual);
            int trusted=receipt().getInt("trusted_launch_order",0), created=receipt().getInt("native_created_order",0), resumed=receipt().getInt("native_resumed_order",0);
            check(trusted>0 && created>0 && (product?created<trusted:trusted<created) && created<resumed && trusted<resumed,
                "record actual callback/trusted ordering in either direction");
            check(actual.getClass().getName().equals(receipt().getString("native_component","")), "physical native witness stays exact");
            app.callbacks.onActivityCreated(actual,null); app.callbacks.onActivityResumed(actual);
            check(receipt().getInt("native_created_order",0)==created && receipt().getInt("native_resumed_order",0)==resumed, "first lifecycle witnesses retained");
            ControlPanelActivity panel=new ControlPanelActivity();
            app.callbacks.onActivityCreated(panel,null); app.callbacks.onActivityResumed(panel);
            check(receipt().getInt("panel_created_order",0)>resumed && receipt().getInt("panel_resumed_order",0)>receipt().getInt("panel_created_order",0), "own panel observations separate");
            check(receipt().getInt("native_resumed_order",0)==resumed, "panel cannot overwrite native resume");
            Activity foreign=new NativeActivity(); foreign.packageName="other.app";
            java.util.Map<String,?> before=receipt().getAll();
            app.callbacks.onActivityCreated(foreign,null); app.callbacks.onActivityResumed(foreign);
            check(before.equals(receipt().getAll()), "foreign package is inert");

            for(int i=0;i<1000;i++)boot(app);
            check(receipt().getAll().size()<=32, "receipt has bounded fixed fields");
            for(Object value:receipt().getAll().values())if(value instanceof String)check(((String)value).length()<=128, "bounded closed string values");
            receipt().values.put("receiver_deliveries",Integer.MAX_VALUE); receipt().values.put("observation_sequence",Integer.MAX_VALUE);
            boot(app);
            check(receipt().getInt("receiver_deliveries",0)==Integer.MAX_VALUE && receipt().getInt("observation_sequence",0)==Integer.MAX_VALUE, "observation counters saturate");
            Settings.Global.bootCount=8; Context.beforeStart=null; boot(app);
            check(Context.launches.size()==2 && receipt().getInt("boot_count",-1)==8 && receipt().getInt("receiver_deliveries",0)==1,
                "next boot has a fresh bounded receipt and unchanged admission");
            check(receipt().getInt("trusted_launch_order",0)==0 && receipt().getInt("native_resumed_order",0)==0, "no stale lifecycle evidence across boots");
        }

        NativeRendererSelfKioskApplication early=reset(true,false);
        Activity earlyActivity=nativeActivity(false); early.callbacks.onActivityCreated(earlyActivity,null);
        int earlyOrder=receipt().getInt("native_created_order",0);
        check(earlyOrder>0 && receipt().getInt("receiver_deliveries",0)==0
            && "not-requested".equals(receipt().getString("dispatch_outcome","")), "manual Activity observation cannot fabricate receiver delivery");
        boot(early);
        check(receipt().getInt("native_created_order",0)==earlyOrder && receipt().getInt("receiver_first_order",0)>earlyOrder,
            "late receiver observation preserves earlier same-boot Activity ordering");
        for(String selection:new String[]{"false", "missing", "null-metadata", "unavailable", "lookup-failed", "false-platform-check-failed"}) {
            unselectedReceiverIsInert(selection);
        }
        NativeRendererSelfKioskApplication app=reset(false,false); Activity unselected=nativeActivity(false);
        app.callbacks.onActivityCreated(unselected,null); app.callbacks.onActivityResumed(unselected);
        check(NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(unselected,false)>0,
            "boot diagnostics selection cannot alter a normal trusted launch");
        check(Context.preferences.isEmpty() && Context.preferenceAccesses.getOrDefault(RECEIPT,0)==0,
            "unselected lifecycle diagnostics have zero preference access");
        app=reset(true,false); Settings.overlay=false; boot(app); outcome("overlay-missing");
        check(Context.launches.isEmpty() && admission().getInt("last_attempted_boot_count",-1)==-1, "overlay prerequisite cannot consume admission");
        Settings.overlay=true; boot(app);
        check(Context.launches.size()==1, "a subsequent admitted delivery uses the unchanged real admission store");
        app=reset(true,false); Settings.Global.bootCount=-1; boot(app); outcome("boot-count-unavailable");
        check(Context.launches.isEmpty(), "unknown boot identity denies");
        app=reset(true,false); admission().failCommit=true; boot(app); outcome("admission-storage-failed");
        check(Context.launches.isEmpty(), "admission storage failure prevents dispatch");
        check("not-requested".equals(receipt().getString("dispatch_outcome","")), "failed admission never claims dispatch");
        app=reset(true,false); Context.launchFails=true; boot(app); outcome("admitted");
        check("platform-launch-failed".equals(receipt().getString("dispatch_outcome","")), "dispatch platform failure separate");
        Context.launchFails=false; boot(app); check(Context.launches.isEmpty(), "platform failure never permits retry");
        app=reset(true,false); Settings.overlayFails=true; boot(app); outcome("platform-check-failed");
        check(Context.launches.isEmpty(), "platform prerequisite failure");
        for(boolean throwsInstead:new boolean[]{false,true}) {
            app=reset(true,false); receipt().failCommit=!throwsInstead; receipt().throwOnAccess=throwsInstead;
            boot(app); boot(app);
            check(Context.launches.size()==1 && admission().getInt("last_attempted_boot_count",-1)==7, "diagnostic failure cannot alter once-per-boot admission");
            Activity actual=nativeActivity(false);
            long epoch=NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(actual,false);
            check(epoch>0, "diagnostic failure cannot deny trusted launch");
            app.callbacks.onActivityCreated(actual,null); app.callbacks.onActivityResumed(actual);
            check(app.isResumed("android.app.NativeActivity"), "diagnostic failure cannot break lifecycle");
        }
        app=reset(true,false);
        new NativeRendererBootReceiver().onReceive(app,new Intent("android.intent.action.USER_PRESENT"));
        new NativeRendererBootReceiver().onReceive(app,null); new NativeRendererBootReceiver().onReceive(null,new Intent());
        check(Context.preferences.isEmpty() && Context.launches.isEmpty(), "unaccepted broadcasts have no diagnostic or launch effects");
        System.out.println("Boot receipt / receiver / physical host / lifecycle production controls passed: "+checks);
    }
}
