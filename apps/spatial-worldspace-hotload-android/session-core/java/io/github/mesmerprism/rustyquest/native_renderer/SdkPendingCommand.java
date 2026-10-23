package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;

/** Exact UI correlation retirement. Unknown outcomes are settled locally and never replayed. */
final class SdkPendingCommand {
    static final class Pending {
        final Object token;final ExperimentSessionUiCommandReceipt receipt;
        Pending(Object token,ExperimentSessionControlCapability.Fence fence,String id){this.token=token;receipt=new ExperimentSessionUiCommandReceipt(fence,id);}
    }
    private Pending current;
    private String outcome="none";
    synchronized Pending install(Object token,ExperimentSessionControlCapability.Fence fence,String id){
        if(current!=null)throw new IllegalStateException("session-operation-pending");
        current=new Pending(token,fence,id);outcome="pending-not-applied";return current;
    }
    synchronized Pending current(){return current;}
    synchronized void ifCurrent(Pending expected,Runnable publication){if(current==expected)publication.run();}
    synchronized String outcome(){return outcome;}
    synchronized void retireToken(Object token){if(current!=null&&current.token==token)settle(current,"owner-retired-outcome-unknown-no-replay");}
    synchronized void settle(Pending expected,String result){if(current==expected){current=null;outcome=result;}}
    synchronized String observe(Pending expected,JSONObject state)throws Exception{
        if(current!=expected)return "obsolete-correlation";
        String result=expected.receipt.observe(state);
        if("applied".equals(result)||"rejected".equals(result)||"owner-changed-outcome-unknown".equals(result))settle(expected,result);
        return result;
    }
}
