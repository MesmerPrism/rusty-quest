package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
/** One current-owner handoff; genuine presentation readback precedes one native dispatch. */
final class ExperimentSessionUiControlHandoff implements Runnable {
 /** A deliberately requested Activity-to-immersive transfer may outlive its originating UI. */
 static final class TransferScope {
  private final long launchEpoch;private boolean transferred,retired;
  TransferScope(long epoch){if(epoch<0)throw new IllegalArgumentException("launch-epoch");launchEpoch=epoch;}
  void transfer(){if(retired)throw new IllegalStateException("transfer-retired");transferred=true;}
  void abortTransfer(){transferred=false;retired=true;}
  boolean transferred(){return transferred;}
  boolean retire(boolean navigation){if(navigation||!transferred)retired=true;return retired;}
  boolean current(long epoch,boolean originCurrent){return !retired&&launchEpoch==epoch&&(transferred||originCurrent);}
 }
 interface Owner {
  JSONObject read() throws Exception;
  void returnVr() throws Exception;
  void dispatch(JSONObject command) throws Exception;
  void schedule(Runnable callback,long delay);
  long nowMs(); long nowNs();
  void outcome(String state);
  default boolean presentationReady(){return true;}
 }
 private final Owner owner;private final ExperimentSessionControlCapability.Fence fence;private final String id;
 private long opened;private boolean handoff,done;
 ExperimentSessionUiControlHandoff(Owner owner,ExperimentSessionControlCapability.Fence fence,String id){
  if(!"start".equals(fence.operation)&&!"resume".equals(fence.operation))throw new IllegalArgumentException("handoff-operation-invalid");
  this.owner=owner;this.fence=fence;this.id=id;opened=owner.nowMs();
 }
 boolean pending(){return !done;}
 void cancel(){if(!done)finish("owner-retired-no-dispatch");}
 @Override public void run(){if(done)return;
  try{
   long now=owner.nowMs();if(now<opened||now-opened>5000){finish("handoff-timeout-no-dispatch");return;}
   JSONObject current=owner.read();
   String state="start".equals(fence.operation)?"armed":"paused";
   // Native readback owns recording state through phase; no recording member is emitted.
   if(!"active".equals(current.opt("phase"))
       || !state.equals(current.optString("control_state"))
       || ExperimentSessionControlCapability.positive(current,"generation")!=fence.generation
       || ExperimentSessionControlCapability.positive(current,"runtime_epoch")!=fence.epoch
       || !fence.session.equals(ExperimentSessionControlCapability.string(current,"session_key"))
       || !fence.condition.equals(ExperimentSessionControlCapability.string(current,"condition_id"))
       || !fence.profile.equals(ExperimentSessionControlCapability.string(current,"non_audio_profile_sha256"))
       || !fence.binding.equals(ExperimentSessionControlCapability.string(current,"activation_binding_sha256"))) {
    finish("owner-changed-no-dispatch");return;
   }
   if("immersive-active".equals(current.optString("presentation"))&&owner.presentationReady()){
    done=true;owner.dispatch(fence.nativeCommand(id,owner.nowNs()).put("operator_origin","ui-session-v2"));
    owner.outcome("native-queued-not-applied");return;
   }
   if(!handoff){handoff=true;owner.returnVr();}
   owner.schedule(this,100);
  }catch(Exception failure){finish("owner-unavailable-or-dispatch-unknown-no-replay");}
 }
 private void finish(String state){done=true;owner.outcome(state);}
}
