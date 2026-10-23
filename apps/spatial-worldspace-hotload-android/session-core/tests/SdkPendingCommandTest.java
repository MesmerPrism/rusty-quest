package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
/** Exact production correlation slot and canonical receipt; never invokes/replays a native command. */
public final class SdkPendingCommandTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static final String SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static ExperimentSessionControlCapability.Fence fence(long epoch){return new ExperimentSessionControlCapability.Fence("","session","condition-a",SHA,SHA,"resume",2,epoch,0,0,0);}
    static JSONObject state(long epoch,String id)throws Exception{return new JSONObject().put("phase","active").put("control_state","running").put("generation",2).put("runtime_epoch",epoch)
        .put("session_key","session").put("condition_id","condition-a").put("non_audio_profile_sha256",SHA).put("activation_binding_sha256",SHA)
        .put("operator_operation_id",id).put("operator_origin","ui-session-v2").put("operator_operation_status","applied");}
    public static void main(String[] args)throws Exception{
        SdkPendingCommand slot=new SdkPendingCommand();Object oldToken=new Object(),nextToken=new Object();
        SdkPendingCommand.Pending old=slot.install(oldToken,fence(7),"old");slot.retireToken(oldToken);
        check(slot.current()==null&&slot.outcome().contains("unknown-no-replay"),"retirement claimed success or remained pending");
        SdkPendingCommand.Pending next=slot.install(nextToken,fence(8),"next");
        slot.retireToken(oldToken);slot.settle(old,"rejected");slot.observe(old,state(7,"old"));
        check(slot.current()==next,"old completion cleared a replacement correlation");
        check("applied".equals(slot.observe(next,state(8,"next")))&&slot.current()==null,"actual replacement receipt did not settle");
        SdkPendingCommand.Pending changed=slot.install(nextToken,fence(8),"unknown");
        check("owner-changed-outcome-unknown".equals(slot.observe(changed,state(9,"other")))&&slot.current()==null,"owner change permanently blocked later actions");
        check(slot.install(new Object(),fence(9),"new-action")!=null,"new action remained blocked");
        System.out.println("SdkPendingCommandTest PASS");
    }
}
