package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.provider.Settings;

/** Executes extracted actual dispatcher/common UI handler; no Android settings acceptance. */
public final class PolarLocationSettingsTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static PolarSensorPanel panel(int failures){
        NativeRendererSelfKioskApplication.begins=0;NativeRendererSelfKioskApplication.ends=0;
        PolarSensorPanel panel=new PolarSensorPanel();panel.activity=new Activity(failures);return panel;
    }
    private static void disposition(PolarSensorPanel.OperatorCommandStatus result,String dispatch,String effect){
        check(dispatch.equals(result.dispatchStatus)&&effect.equals(result.effectStatus),"truthful dispatch/effect disposition");
        check(result.operationGeneration==1&&"open_location_settings".equals(result.command),"original operation generation/command");
    }
    public static void main(String[] args){
        PolarSensorPanel detached=panel(0);detached.activity=null;
        PolarSensorPanel.OperatorCommandStatus rejected=detached.handleCommand("open_location_settings");
        disposition(rejected,"rejected","unavailable");
        check("panel-not-attached".equals(rejected.reasonCode),"no attached owner reason");
        check(NativeRendererSelfKioskApplication.begins==0&&NativeRendererSelfKioskApplication.ends==0&&!detached.locationSettingsPromptPending,"unattached no prompt effects");

        PolarSensorPanel primary=panel(0);
        disposition(primary.handleCommand(" OPEN_LOCATION_SETTINGS "),"accepted","pending");
        check(primary.activity.attempts.size()==1&&Settings.ACTION_LOCATION_SOURCE_SETTINGS.equals(primary.activity.attempts.get(0)),"same primary settings request");
        check(NativeRendererSelfKioskApplication.begins==1&&NativeRendererSelfKioskApplication.ends==0,"one original prompt begin");
        check(primary.locationSettingsPromptPending&&"location-settings-opened".equals(primary.statusState),"primary remains pending, not effective settings proof");

        PolarSensorPanel fallback=panel(1);
        disposition(fallback.handleCommand("open_location_settings"),"accepted","pending");
        check(fallback.activity.attempts.size()==2&&Settings.ACTION_LOCATION_SOURCE_SETTINGS.equals(fallback.activity.attempts.get(0))&&Settings.ACTION_SETTINGS.equals(fallback.activity.attempts.get(1)),"existing general settings fallback only");
        check(NativeRendererSelfKioskApplication.begins==1&&NativeRendererSelfKioskApplication.ends==0&&fallback.locationSettingsPromptPending,"fallback retains same pending prompt");
        check(fallback.markers.contains("status=settings-opened fallback=general-settings"),"original fallback marker");

        PolarSensorPanel failed=panel(2);
        disposition(failed.handleCommand("open_location_settings"),"accepted","unavailable");
        check(failed.activity.attempts.size()==2,"both failures never retry or launch a third target");
        check(NativeRendererSelfKioskApplication.begins==1&&NativeRendererSelfKioskApplication.ends==1&&!failed.locationSettingsPromptPending,"both failures end original prompt");
        check("location-settings-unavailable".equals(failed.statusState),"original unavailable UI state");

        for(int failures=0;failures<=2;failures++){
            PolarSensorPanel ui=panel(failures);ui.uiOpenForTest();
            java.util.List<String> attempts=new java.util.ArrayList<>(ui.activity.attempts);
            int begins=NativeRendererSelfKioskApplication.begins,ends=NativeRendererSelfKioskApplication.ends;
            PolarSensorPanel cli=panel(failures);cli.handleCommand("open_location_settings");
            check(attempts.equals(cli.activity.attempts)&&begins==NativeRendererSelfKioskApplication.begins&&ends==NativeRendererSelfKioskApplication.ends,"UI/CLI actual common handler trace parity");
            check(ui.locationSettingsPromptPending==cli.locationSettingsPromptPending&&ui.statusState.equals(cli.statusState),"UI/CLI same pending/unavailable state");
        }
        PolarSensorPanel unknown=panel(0);
        check("rejected".equals(unknown.handleCommand("not-a-settings-command").dispatchStatus)&&unknown.activity.attempts.isEmpty()&&NativeRendererSelfKioskApplication.begins==0,"closed unknown command has no settings effect");
        System.out.println("PolarLocationSettingsTest PASS checks="+checks);
    }
}
