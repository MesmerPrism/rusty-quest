package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
public final class ExperimentSessionUiControlHandoffTest {
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static final class TestOwner implements ExperimentSessionUiControlHandoff.Owner {
  long now=1000;int returns,dispatches;String outcome="";JSONObject value;Runnable callback;boolean dispatchFails;
  TestOwner()throws Exception{value=new JSONObject().put("recording",true).put("control_state","armed").put("generation",7L).put("runtime_epoch",3L).put("session_key","utc-ns-00000000000000000042").put("condition_id","condition-a").put("non_audio_profile_sha256",ExperimentSessionControlCapabilityTest.repeat("1",64)).put("activation_binding_sha256",ExperimentSessionControlCapabilityTest.repeat("2",64)).put("presentation","unfocused");}
  public JSONObject read(){return value;}public void returnVr(){returns++;}public void dispatch(JSONObject command)throws Exception{dispatches++;require(command.getString("operator_origin").equals("ui-session-v2"),"origin");if(dispatchFails)throw new Exception("unknown dispatch");}public void schedule(Runnable r,long delay){callback=r;}public long nowMs(){return now;}public long nowNs(){return now*1000000;}public void outcome(String s){outcome=s;}
 }
 static ExperimentSessionControlCapability.Fence fence(String op){return new ExperimentSessionControlCapability.Fence("", "utc-ns-00000000000000000042","condition-a",ExperimentSessionControlCapabilityTest.repeat("1",64),ExperimentSessionControlCapabilityTest.repeat("2",64),op,7,3,0,0,0);}
 public static void main(String[]a)throws Exception{
  TestOwner o=new TestOwner();ExperimentSessionUiControlHandoff h=new ExperimentSessionUiControlHandoff(o,fence("start"),"ui-original");h.run();require(o.returns==1&&o.dispatches==0,"handoff does not start");o.now+=100;o.value.put("presentation","immersive-active");o.callback.run();h.run();require(o.dispatches==1&&!h.pending()&&o.outcome.equals("native-queued-not-applied"),"one actual presentation dispatch");
  for(String key:new String[]{"generation","runtime_epoch","session_key","condition_id","non_audio_profile_sha256","activation_binding_sha256","control_state","recording"}){TestOwner bad=new TestOwner();ExperimentSessionUiControlHandoff c=new ExperimentSessionUiControlHandoff(bad,fence("start"),"ui-stale");c.run();bad.value.put(key,key.equals("generation")||key.equals("runtime_epoch")?99:key.equals("recording")?false:"changed");bad.value.put("presentation","immersive-active");bad.callback.run();require(bad.dispatches==0&&!c.pending(),"changed tuple denies "+key);}
  TestOwner timeout=new TestOwner();ExperimentSessionUiControlHandoff c=new ExperimentSessionUiControlHandoff(timeout,fence("start"),"ui-timeout");c.run();timeout.now+=5001;timeout.callback.run();require(timeout.dispatches==0&&!c.pending(),"bounded timeout no start");
  TestOwner regression=new TestOwner();c=new ExperimentSessionUiControlHandoff(regression,fence("start"),"ui-clock");regression.now--;c.run();require(regression.returns==0&&regression.dispatches==0,"clock regression denied");
  TestOwner resume=new TestOwner();resume.value.put("control_state","paused").put("presentation","immersive-active");c=new ExperimentSessionUiControlHandoff(resume,fence("resume"),"ui-resume");c.run();require(resume.dispatches==1&&resume.returns==0,"already immersive real resume");
  TestOwner failed=new TestOwner();failed.value.put("presentation","immersive-active");failed.dispatchFails=true;c=new ExperimentSessionUiControlHandoff(failed,fence("start"),"ui-failed");c.run();c.run();require(failed.dispatches==1&&!c.pending(),"unknown dispatch never replay");
  System.out.println("PASS production UI handoff exact-owner changes/real presentation/timeout/once-only dispatch controls");
 }
}
