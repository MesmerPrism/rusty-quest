package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
/** Exercises the actual canonical handoff, including a tick retained before retirement. */
public final class SessionHandoffRetirementTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static final String SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final class Owner implements ExperimentSessionUiControlHandoff.Owner {
        Runnable queued;int dispatches;String outcome="";boolean immersed;
        public JSONObject read()throws Exception{return new JSONObject().put("phase","active").put("control_state","paused").put("generation",2).put("runtime_epoch",7)
            .put("session_key","session").put("condition_id","condition-a").put("non_audio_profile_sha256",SHA).put("activation_binding_sha256",SHA).put("presentation",immersed?"immersive-active":"unfocused");}
        public void returnVr(){}public void dispatch(JSONObject command){dispatches++;}public void schedule(Runnable callback,long delay){queued=callback;}
        public long nowMs(){return 100;}public long nowNs(){return 100000000;}public void outcome(String state){outcome=state;}
    }
    public static void main(String[] args){
        ExperimentSessionControlCapability.Fence fence=new ExperimentSessionControlCapability.Fence("","session","condition-a",SHA,SHA,"resume",2,7,0,0,0);
        Owner old=new Owner();ExperimentSessionUiControlHandoff retired=new ExperimentSessionUiControlHandoff(old,fence,"old");retired.run();
        check(retired.pending()&&old.queued!=null,"fixture did not enter real proof wait");Runnable oldTick=old.queued;
        retired.cancel();oldTick.run();check(!retired.pending()&&old.dispatches==0&&"owner-retired-no-dispatch".equals(old.outcome),"retired continuation replayed or stayed pending");
        Owner next=new Owner();next.immersed=true;ExperimentSessionUiControlHandoff replacement=new ExperimentSessionUiControlHandoff(next,fence,"new");
        replacement.run();oldTick.run();check(!replacement.pending()&&next.dispatches==1&&old.dispatches==0,"replacement blocked or old tick replayed");
        System.out.println("SessionHandoffRetirementTest PASS");
    }
}
