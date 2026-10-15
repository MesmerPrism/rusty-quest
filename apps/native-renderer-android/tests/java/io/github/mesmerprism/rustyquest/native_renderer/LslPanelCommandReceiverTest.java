package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Actual receiver with real org.json; only Android/native/store/lock effects are doubles. */
public final class LslPanelCommandReceiverTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void reset() {
        ControlPanelActivity.calls=0; ControlPanelActivity.raw=null;
        ControlPanelActivity.noLink=false;
        LslPanelConfigStore.saves=0; LslPanelConfigStore.resets=0;
        LslPanelConfigStore.saved="previous-next-launch-config";
        LslPanelConfigStore.saveResult=true; LslPanelConfigStore.resetResult=true;
        LslMulticastLockManager.calls=0;
    }
    private static String request(String operation) throws Exception {
        JSONObject q=new JSONObject().put("schema","rusty.quest.native_renderer.lsl.panel_command.v1")
            .put("operation",operation);
        if (!"status".equals(operation)) q.put("request_id","original-request-1")
            .put("app_session_id","original-app-session").put("generation",7);
        if ("apply".equals(operation)) q.put("config",new JSONObject().put("enabled",true));
        return q.toString();
    }
    private static void response(String state,boolean enabled) throws Exception {
        ControlPanelActivity.response=new JSONObject()
            .put("schema","rusty.quest.native_renderer.lsl.status.v1")
            .put("response_status",state).put("response_reason","fixture-owner")
            .put("app_session_id","original-app-session").put("generation",7)
            .put("last_request_id","original-request-1")
            .put("config",new JSONObject().put("enabled",enabled)).toString();
    }
    private static Intent intent(String raw,String scope) {
        Intent intent=new Intent(LslPanelCommandReceiver.ACTION_COMMAND)
            .putExtra(LslPanelCommandReceiver.EXTRA_COMMAND_BASE64,
                Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP|Base64.NO_PADDING));
        if(scope!=null)intent.putExtra(LslPanelCommandReceiver.EXTRA_PERSISTENCE_SCOPE,scope);
        return intent;
    }
    private static JSONObject dispatch(Intent intent) throws Exception {
        LslPanelCommandReceiver receiver=new LslPanelCommandReceiver();
        receiver.onReceive(new Context(),intent);
        check(receiver.testResultData()!=null,"ordered receiver must return a receipt");
        JSONObject result=new JSONObject(new String(Base64.decode(receiver.testResultData(),Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8));
        check(receiver.testResultCode()==("rejected".equals(result.optString("response_status"))
            ?Activity.RESULT_CANCELED:Activity.RESULT_OK),"receipt/result code agree");
        return result;
    }
    private static void noEffects(String reason) {
        check(LslPanelConfigStore.saves==0&&LslPanelConfigStore.resets==0,reason+": no store effects");
        check(LslMulticastLockManager.calls==0,reason+": no multicast effects");
    }
    public static void main(String[] args) throws Exception {
        for(String operation:new String[]{"apply","disable","reset"}) {
            reset(); boolean enabled="apply".equals(operation); response("accepted",enabled);
            String raw=request(operation); JSONObject receipt=dispatch(intent(raw,"runtime-only"));
            check(ControlPanelActivity.calls==1&&raw.equals(ControlPanelActivity.raw),"runtime exact native request once: "+operation);
            check(LslPanelConfigStore.saves==0&&LslPanelConfigStore.resets==0,"runtime-only must not persist: "+operation);
            check("previous-next-launch-config".equals(LslPanelConfigStore.saved),"runtime preserves saved next-launch bytes: "+operation);
            check(LslMulticastLockManager.calls==1&&LslMulticastLockManager.enabled==enabled,"runtime original multicast owner: "+operation);
            check(!receipt.getBoolean("persisted")&&"runtime-only".equals(receipt.getString("persistence_scope")),"runtime receipt is explicit");
            check(receipt.getLong("generation")==7&&"original-request-1".equals(receipt.getString("last_request_id")),"native tuple forwarded");
        }
        for(String scope:new String[]{null,"persistent"}) {
            reset(); response("accepted",true);
            JSONObject apply=dispatch(intent(request("apply"),scope));
            check(LslPanelConfigStore.saves==1&&LslPanelConfigStore.resets==0,"legacy/explicit persistent save retained");
            check(apply.getBoolean("persisted")&&"persistent".equals(apply.getString("persistence_scope")),"persistent receipt");
            reset(); response("accepted",false);
            dispatch(intent(request("reset"),scope));
            check(LslPanelConfigStore.saves==1&&LslPanelConfigStore.resets==1,"legacy reset preserves existing save then reset behavior");
            check("default-next-launch-config".equals(LslPanelConfigStore.saved),"legacy reset applied");
        }
        for(String state:new String[]{"status","rejected"}) {
            for(String scope:new String[]{"runtime-only","persistent"}) {
                reset(); response(state,false);
                JSONObject result=dispatch(intent(request("status".equals(state)?"status":"apply"),scope));
                check(ControlPanelActivity.calls==1,"read/rejected reaches original native owner once");
                check(state.equals(result.getString("response_status")),"original disposition preserved");
                noEffects(state);
            }
        }
        reset(); response("accepted",true);
        JSONObject badScope=dispatch(new Intent(LslPanelCommandReceiver.ACTION_COMMAND)
            .putExtra(LslPanelCommandReceiver.EXTRA_PERSISTENCE_SCOPE,"runtime-only ")
            .putExtra(LslPanelCommandReceiver.EXTRA_COMMAND_BASE64,"not base64"));
        check("unsupported-persistence-scope".equals(badScope.getString("response_reason")),"scope rejected before decoding/native");
        check(ControlPanelActivity.calls==0,"invalid scope before native"); noEffects("bad scope");
        reset();response("accepted",true);
        JSONObject nullScope=dispatch(intent(request("apply"),null)
            .putExtra(LslPanelCommandReceiver.EXTRA_PERSISTENCE_SCOPE,(String)null));
        check("unsupported-persistence-scope".equals(nullScope.getString("response_reason")),"explicit null scope is not absent legacy default");
        check(ControlPanelActivity.calls==0,"explicit null scope before native");noEffects("null scope");
        reset();response("accepted",true);
        JSONObject wrongTypeScope=dispatch(intent(request("apply"),null)
            .putExtra(LslPanelCommandReceiver.EXTRA_PERSISTENCE_SCOPE,1));
        check("unsupported-persistence-scope".equals(wrongTypeScope.getString("response_reason")),"present wrong-type scope is not absent legacy default");
        check(ControlPanelActivity.calls==0,"wrong-type scope before native");noEffects("wrong-type scope");
        for(String payload:new String[]{null,"","%%invalid%%",Base64.encodeToString("not-json".getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)}) {
            reset(); response("accepted",true);
            Intent invalid=new Intent(LslPanelCommandReceiver.ACTION_COMMAND).putExtra(LslPanelCommandReceiver.EXTRA_PERSISTENCE_SCOPE,"runtime-only");
            if(payload!=null)invalid.putExtra(LslPanelCommandReceiver.EXTRA_COMMAND_BASE64,payload);
            check("rejected".equals(dispatch(invalid).getString("response_status")),"malformed request rejects");
            check(ControlPanelActivity.calls==0,"malformed request no native call"); noEffects("malformed request");
        }
        reset(); response("accepted",true);
        StringBuilder huge=new StringBuilder();for(int i=0;i<32769;i++)huge.append('A');
        dispatch(new Intent(LslPanelCommandReceiver.ACTION_COMMAND).putExtra(LslPanelCommandReceiver.EXTRA_COMMAND_BASE64,huge.toString()));
        check(ControlPanelActivity.calls==0,"oversized encoded input no native");noEffects("oversized");
        reset();response("accepted",true);
        dispatch(new Intent(LslPanelCommandReceiver.ACTION_COMMAND).putExtra(LslPanelCommandReceiver.EXTRA_COMMAND_BASE64,
            Base64.encodeToString(new byte[16385],Base64.NO_WRAP|Base64.NO_PADDING)));
        check(ControlPanelActivity.calls==0,"decoded byte bound enforced before native");noEffects("decoded oversized");
        reset(); response("accepted",true);ControlPanelActivity.noLink=true;
        JSONObject unlinked=dispatch(intent(request("apply"),"runtime-only"));
        check("native-runtime-not-running".equals(unlinked.getString("response_reason")),"actual missing JNI disposition");
        check(ControlPanelActivity.calls==1,"missing JNI attempted once");noEffects("missing JNI");
        reset();ControlPanelActivity.response="not-json";
        dispatch(intent(request("apply"),"runtime-only"));noEffects("malformed native response");
        check(ControlPanelActivity.calls==1,"malformed response never replays native");
        reset(); response("accepted",true); LslPanelConfigStore.saveResult=false;
        check(!dispatch(intent(request("apply"),"persistent")).getBoolean("persisted"),"persistent failure cannot report saved");
        reset(); response("accepted",true);
        LslPanelCommandReceiver unordered=new LslPanelCommandReceiver();unordered.testOrdered(false);
        unordered.onReceive(new Context(),intent(request("apply"),"runtime-only"));
        check(unordered.testResultData()==null&&ControlPanelActivity.calls==0,"unordered broadcast inert");noEffects("unordered");
        reset();dispatch(new Intent("wrong-action"));check(ControlPanelActivity.calls==0,"wrong action inert");noEffects("wrong action");
        System.out.println("LslPanelCommandReceiverTest PASS checks="+checks);
    }
}
