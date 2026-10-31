"""Execute production mode adapter and gateway dispatch with inert Android/JNI boundaries."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_gateway_dedupe import method


def run_java(args, sources, fixture, name):
    with tempfile.TemporaryDirectory(prefix="rq-control-mode-") as temporary:
        output = Path(temporary)
        source = output / (name + ".java")
        source.write_text(fixture, encoding="utf-8")
        classpath = os.pathsep.join((str(args.json_jar), str(args.android_jar)))
        subprocess.run([str(args.jdk_bin / "javac.exe"), "--release", "8", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(output), *map(str, sources), str(source)], check=True)
        subprocess.run([str(args.jdk_bin / "java.exe"), "-cp", str(output) + os.pathsep + classpath,
                        "io.github.mesmerprism.rustyquest.native_renderer." + name], check=True)


def gateway(args, root):
    source = (root / "ExperimentSessionCommandGateway.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class ExperimentSessionBleServer {static final class CommandRequest {String id;CommandRequest(String value){id=value;}}}
final class ControlModeGatewayTest {
 Object protocol=new Object();long identity=1,epoch=7;int dispatches;
 ExperimentSessionCommandJournal journal=new ExperimentSessionCommandJournal();
 class NativeFixture implements ExperimentControlMode.NativeAccess {long value=9;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 class Carrier {boolean acceptsNativeOwner(long i,long e){return i==identity&&e==epoch;}
  void updateReceipt(String id,String state,String detail){try{journal.observe(new JSONObject().put("id",id).put("state",state).put("detail",detail));}catch(Exception failure){throw new RuntimeException(failure);}}}
 Carrier carrier=new Carrier();
 class Delegate {void dispatch(ExperimentSessionBleServer.CommandRequest request){dispatches++;carrier.updateReceipt(request.id,"pending","owner dispatch");}}
 Delegate delegate=new Delegate();
 static void requireMain(){}
 JSONObject snapshot(){return new JSONObject();}
 JSONObject monitorSnapshot(JSONObject body)throws Exception{return ExperimentSessionGatewayProtocol.monitorQuery(body);}
 ExperimentSessionBleServer.CommandRequest parse(JSONObject body)throws Exception{return new ExperimentSessionBleServer.CommandRequest(body.getString("id"));}
 __OWNER__
 interface Checked {void run()throws Exception;}
 static void deny(Checked action)throws Exception {try{action.run();}catch(SecurityException expected){return;}throw new AssertionError("Non-Remote command reached actual main-thread dispatch");}
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 static JSONObject command(String id)throws Exception{return new JSONObject().put("id",id).put("op","arm");}
 public static void main(String[] ignored)throws Exception {
  ControlModeGatewayTest h=new ControlModeGatewayTest();h.nativeOwner.value=20;
  deny(()->h.ownerExchange("command",command("1111111111111111")));check(h.dispatches==0,"Choose dispatched");
  h.nativeOwner.value=29;deny(()->h.ownerExchange("command",command("2222222222222222")));check(h.dispatches==0,"Direct dispatched");
  h.ownerExchange("status",new JSONObject());
  h.nativeOwner.value=38;JSONObject original=command("3333333333333333");h.ownerExchange("command",original);check(h.dispatches==1,"Remote unavailable");
  h.carrier.updateReceipt("3333333333333333","confirmed","native callback");h.nativeOwner.value=45;
  check(h.ownerExchange("receipt",new JSONObject().put("id","3333333333333333")).getString("state").equals("confirmed"),"Direct lost original receipt");
  check(h.ownerExchange("command",original).getString("state").equals("confirmed")&&h.dispatches==1,"retry replayed original");
  // A network thread can enqueue in Remote; ownerExchange executes after switching Direct.
  JSONObject queued=command("4444444444444444");h.nativeOwner.value=54;Runnable dispatch=()->{try{deny(()->h.ownerExchange("command",queued));}catch(Exception failure){throw new RuntimeException(failure);}};
  h.nativeOwner.value=61;dispatch.run();check(h.dispatches==1,"queued command ignored current mode");
  h.nativeOwner.value=9;h.ownerExchange("command",command("5555555555555555"));check(h.dispatches==2,"legacy gateway changed");
  System.out.println("PASS production main-thread gateway mode admission, Choose/Direct denial, Remote dispatch, legacy behavior, queued switch and original receipts");
 }
}
'''.replace("__OWNER__", method(source, "private JSONObject ownerExchange("))
    run_java(args, [root / name for name in ("ExperimentControlMode.java", "ExperimentSessionGatewayProtocol.java", "ExperimentSessionCommandJournal.java", "ExperimentSessionBleProtocol.java", "ExperimentSessionControlCapability.java")], fixture, "ControlModeGatewayTest")


def panel(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
final class ControlModePanelTest {
 static class View {String name;View(String value){name=value;}}
 static class TextView extends View {TextView(){super("text");}}
 static final class ScrollView extends View {ScrollView(Object context){super("scroll");}void setBackgroundColor(int c){}void addView(View v){}}
 static final class LinearLayout extends View {static final int VERTICAL=1;LinearLayout(Object context){super("layout");}void setOrientation(int i){}void setPadding(int a,int b,int c,int d){}void addView(View v){}}
 static final String PANEL_ROUTE_EXPERIMENTER="experimenter";static final int PANEL_BG=0;
 String panelRoute="experimenter",breathCompositionPanelTopic="home",renderedPanelRoute,renderedPanelTopic;
 Object renderedExperimenterPage;ScrollView panelScrollView;
 TextView controlModeSessionReadback,controlModeNetworkReadback;
 static class Navigation {Object page(){return null;}}Navigation experimenterNavigation=new Navigation();
 static class NativeFixture implements ExperimentControlMode.NativeAccess {long value=20;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 void clearExperimenterProjectionViews(){}int dp(int value){return value;}
 View buildExperimenterWorkflowView(){return new View("direct-controls");}
 View buildExperimentControlModeView(ExperimentControlMode.Snapshot snapshot){return new View(snapshot.wireName());}
 void appendBreathCompositionPanelHeader(LinearLayout root){}void appendUnifiedParticleControls(LinearLayout root){}
 void appendBreathCompositionBreathControls(LinearLayout root){}void appendBreathCompositionPolarControls(LinearLayout root){}
 void appendBreathCompositionLslControls(LinearLayout root){}void appendBreathCompositionStatus(LinearLayout root){}void appendBreathCompositionPanelHome(LinearLayout root){}
 __BUILD__
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored){ControlModePanelTest panel=new ControlModePanelTest();
  check(panel.buildBreathCompositionControlPanelView().name.equals("choose"),"Fresh selected panel exposed Direct controls before choice");
  panel.nativeOwner.value=30;check(panel.buildBreathCompositionControlPanelView().name.equals("remote"),"Remote recall exposed Direct controls");
  panel.panelRoute="developer";check(panel.buildBreathCompositionControlPanelView().name.equals("remote"),"Remote developer route exposed controls");
  panel.nativeOwner.value=37;panel.panelRoute="experimenter";check(panel.buildBreathCompositionControlPanelView().name.equals("direct-controls"),"Direct lost original workflow");
  panel.nativeOwner.value=9;check(panel.buildBreathCompositionControlPanelView().name.equals("direct-controls"),"legacy panel changed");
  System.out.println("PASS production panel routing: fresh chooser, Remote recall/developer isolation, original Direct and legacy workflow");
 }
}
'''.replace("__BUILD__", method(source, "private View buildBreathCompositionControlPanelView("))
    run_java(args, [root / "ExperimentControlMode.java"], fixture, "ControlModePanelTest")


def presentation(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class ExperimentSessionCommandGateway {
 static ExperimentSessionCommandGateway current;boolean trusted;String state="disabled";
 static ExperimentSessionCommandGateway existing(){return current;}boolean trustedLanAllowed(){return trusted;}
 JSONObject productPanelReadback()throws Exception{return new JSONObject().put("state",state);}
}
final class NativeMeshUiProducer {static int requests;static boolean visible=true,available=true;
 static boolean requestPanelVisibility(Object owner,boolean next){requests++;visible=next;return available;}}
final class ControlModePresentationTest {
 static final class NativeFixture implements ExperimentControlMode.NativeAccess {long value=20;
  public long initialize(boolean selected){return value;}public long read(){return value;}
  public long select(long expected,int requested){if(value==expected)value=((value>>>3)+1)*8+4+requested;return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 ExperimentSessionUiControlHandoff uiControlHandoff;String message="";int rebuilds;Object hostActivity(){return this;}
 static class RemotePending {String operation;}RemotePending remotePending;boolean rejectRemotePolarForMode(RemotePending pending){return true;}
 void updateStatus(String detail){message=detail;}void replaceBreathCompositionPanelContent(String topic){rebuilds++;}
 void requestPolarStartupFromModeChoice(){} // Acquisition is exercised by test_polar_native_mode_startup.py.
 __SELECT__
 __HIDE__
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored)throws Exception {
  ControlModePresentationTest panel=new ControlModePresentationTest();ExperimentControlMode.Snapshot choose=panel.controlMode.readSnapshot();
  panel.selectExperimentControlMode(choose,2);
  check(panel.nativeOwner.value==30&&NativeMeshUiProducer.requests==1&&!NativeMeshUiProducer.visible&&panel.rebuilds==1,"Explicit Remote did not hide without enrollment");
  ExperimentSessionCommandGateway gateway=new ExperimentSessionCommandGateway();ExperimentSessionCommandGateway.current=gateway;
  gateway.trusted=true;gateway.state="listener-unavailable";panel.hideRemoteControlPanel();check(NativeMeshUiProducer.requests==2,"listener readiness blocked explicit Return to VR");
  gateway.state="trusted-lan-listening";panel.hideRemoteControlPanel();check(NativeMeshUiProducer.requests==3&&!NativeMeshUiProducer.visible,"Remote did not request actual native panel hiding");
  panel.selectExperimentControlMode(choose,2);check(NativeMeshUiProducer.requests==3,"stale choice repeated hide");
  panel.selectExperimentControlMode(panel.controlMode.readSnapshot(),1);check(panel.nativeOwner.value==37&&NativeMeshUiProducer.requests==3,"Direct changed presentation/session implicitly");
  panel.hideRemoteControlPanel();check(NativeMeshUiProducer.requests==3,"Direct hidden by stale Remote callback");
  panel.selectExperimentControlMode(panel.controlMode.readSnapshot(),2);check(panel.nativeOwner.value==46&&NativeMeshUiProducer.requests==4,"enrolled Remote selection did not hide");
  System.out.println("PASS production mode selection presentation: native CAS readback, stale choice, Direct return, explicit Remote hide regardless of LAN readiness; no device acknowledgement claimed");
 }
}
'''
    fixture = fixture.replace("__SELECT__", method(source, "private void selectExperimentControlMode("))
    fixture = fixture.replace("__HIDE__", method(source, "private void hideRemoteControlPanel("))
    run_java(args, [root / name for name in ("ExperimentControlMode.java", "ExperimentSessionUiControlHandoff.java", "ExperimentSessionControlCapability.java", "ExperimentSessionBleProtocol.java")], fixture, "ControlModePresentationTest")


def local_click(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
final class ControlModeLocalClickTest {
 static class View {interface OnClickListener {void onClick(View view);}}
 static class Button extends View {OnClickListener listener;Button(Object context){}void setText(String text){}void setTextSize(int size){}void setAllCaps(boolean all){}
  public void setOnClickListener(OnClickListener value){listener=value;}void click(){if(listener!=null)listener.onClick(this);}}
 static class NativeFixture implements ExperimentControlMode.NativeAccess {long value=21;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 int effects;void styleButton(Button button,boolean selected){}void updateStatus(String message){}
 boolean localExperimentMutationAllowed(){return controlMode.readSnapshot().localMutationsAllowed();}
 __BUTTON__
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored){ControlModeLocalClickTest panel=new ControlModeLocalClickTest();Button old=panel.button("Save");old.setOnClickListener(view->panel.effects++);
  panel.nativeOwner.value=30;old.click();check(panel.effects==0,"Retained Direct button mutated the experiment in Remote");
  panel.nativeOwner.value=36;old.click();check(panel.effects==0,"Retained Direct button mutated before choice");
  panel.nativeOwner.value=45;old.click();check(panel.effects==1,"Direct callback lost");
  panel.nativeOwner.value=9;old.click();check(panel.effects==2,"legacy callback lost");
  System.out.println("PASS production retained local button dispatch denied in Choose/Remote, original Direct and legacy handlers retained");
 }
}
'''.replace("__BUTTON__", method(source, "private Button button("))
    run_java(args, [root / "ExperimentControlMode.java"], fixture, "ControlModeLocalClickTest")


def close_panel(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
final class ExperimentSessionPanelState {boolean hasActiveSession(){return true;}}
final class ExperimentSessionPanelViewPolicy {static boolean canReturnToImmersive(ExperimentSessionPanelState state){return false;}}
final class ControlPanelActivity {static int closes;static void closePanelAndReturnToImmersive(Object owner){closes++;}}
final class NativeMeshUiProducer {static int requests;static boolean visible=true;static boolean requestPanelVisibility(Object owner,boolean next){requests++;visible=next;return true;}}
final class ControlModePanelCloseTest {
 static final class NativeFixture implements ExperimentControlMode.NativeAccess {long value=30;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 static final class Coordinator {ExperimentSessionPanelState snapshot(){return new ExperimentSessionPanelState();}}static final Coordinator EXPERIMENT_SESSION_PANEL=new Coordinator();
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 Object hostActivity(){return this;}void updateStatus(String detail){}void refreshExperimenterAfterReadback(){}
 __CLOSE__
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored){ControlModePanelCloseTest panel=new ControlModePanelCloseTest();panel.closePanelAndReturnToImmersive();
  check(NativeMeshUiProducer.requests==1&&!NativeMeshUiProducer.visible,"Remote triple-B did not close during pending session work");
  panel.nativeOwner.value=36;panel.closePanelAndReturnToImmersive();check(NativeMeshUiProducer.requests==2,"Choose triple-B unavailable");
  panel.nativeOwner.value=45;panel.closePanelAndReturnToImmersive();check(NativeMeshUiProducer.requests==2&&ControlPanelActivity.closes==0,"Direct lost original pending-session guard");
  System.out.println("PASS production Choose/Remote panel close is presentation-only during session work; Direct keeps original guard");
 }
}
'''.replace("__CLOSE__", method(source, "private void closePanelAndReturnToImmersive("))
    run_java(args, [root / "ExperimentControlMode.java"], fixture, "ControlModePanelCloseTest")


def status(args, root):
    source = (root / "ExperimentSessionCommandGateway.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class ControlModeStatusTest {
 static class SystemClock {static long elapsedRealtime(){return 1000;}}
 static class NativeFixture implements ExperimentControlMode.NativeAccess {long value=9;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 long identity=1,epoch=7;ExperimentSessionControlCapability session=new ExperimentSessionControlCapability();PolarStopBleCapability polar=new PolarStopBleCapability();
 class Carrier {long currentGeneration(){return 2;}}Carrier carrier=new Carrier();
 class Delegate {
  JSONObject snapshot()throws Exception{return new JSONObject().put("g",7).put("p","IDLE").put("pending",false).put("f",String.join("",java.util.Collections.nCopies(400,"x"))).put("q",new JSONObject().put("fixture","present"));}
  JSONObject sessionControlReadback()throws Exception{return new JSONObject().put("phase","idle").put("control_state","idle").put("profile_identity_bound",false);}
  JSONObject polarSetupReadback(){return null;}JSONObject polarStopReadback(){return null;}
 }Delegate delegate=new Delegate();
 __SNAPSHOT__
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored)throws Exception {ControlModeStatusTest h=new ControlModeStatusTest();
  JSONObject legacy=h.snapshot();check(!legacy.has("control_mode")&&legacy.getInt("v")==1&&legacy.getString("m").equals("gated"),"legacy status changed");
  // Appending mode before projection would exceed 480 and incorrectly drop existing q.
  check(legacy.has("q")&&legacy.toString().length()>455,"fixture did not exercise bounded BLE budget boundary");
  for(long value:new long[]{20,29,38}){h.nativeOwner.value=value;JSONObject observed=h.snapshot();
   String expected=value==20?"choose":value==29?"direct":"remote";
   check(observed.getString("control_mode").equals(expected),"LAN mode missing/wrong");
   check(observed.getInt("breath_monitor")==1,"monitor capability missing");observed.remove("control_mode");observed.remove("breath_monitor");check(observed.toString().equals(legacy.toString()),"mode changed existing BLE projection");
  }
  h.nativeOwner.value=9;check(!h.snapshot().has("control_mode"),"legacy status retained mode");
  System.out.println("PASS production LAN status optional control_mode after actual bounded BLE projection; legacy omission and existing projected fields preserved");
 }
}
'''.replace("__SNAPSHOT__", method(source, "private JSONObject snapshot("))
    run_java(args, [root / name for name in ("ExperimentControlMode.java", "ExperimentSessionBleProtocol.java", "ExperimentSessionControlCapability.java")], fixture, "ControlModeStatusTest")


def mode_view(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.*;
final class ControlModeViewTest {
 static class View {String label="";List<View> children=new ArrayList<>();interface OnClickListener {void onClick(View view);}void addView(View child){children.add(child);}}
 static class ScrollView extends View {ScrollView(Object owner){}void setBackgroundColor(int value){}}
 static class LinearLayout extends View {static final int VERTICAL=1;LinearLayout(Object owner){}void setOrientation(int value){}void setPadding(int a,int b,int c,int d){}}
 static class TextView extends View {TextView(String value){label=value;}}
 static class Button extends View {OnClickListener listener;Button(String value){label=value;}void setOnClickListener(OnClickListener value){listener=value;}void click(){listener.onClick(this);}}
 static final int PANEL_BG=0,PANEL_FG=1,PANEL_MUTED=2;
 ScrollView panelScrollView;TextView controlModeSessionReadback,status;int selected=-1,hides;
 int dp(int value){return value;}TextView text(String value,int size,int color){return new TextView(value);}
 Button operatorButton(String value){return new Button(value);}void refreshControlModeReadback(){}
 void appendExperimenterNetworkControl(LinearLayout root){root.addView(new TextView("LAN enrollment"));}
 void selectExperimentControlMode(ExperimentControlMode.Snapshot current,int mode){selected=mode;}void hideRemoteControlPanel(){hides++;}
 __VIEW__
 static View find(View root,String text){if(root.label.equals(text))return root;for(View child:root.children){View result=find(child,text);if(result!=null)return result;}return null;}
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored){ControlModeViewTest panel=new ControlModeViewTest();
  View choose=panel.buildExperimentControlModeView(new ExperimentControlMode.Snapshot(20));
  check(find(choose,"Direct") instanceof Button&&find(choose,"Remote") instanceof Button&&find(choose,"LAN enrollment")!=null,"initial choice missing controls/setup");
  ((Button)find(choose,"Direct")).click();check(panel.selected==1,"Direct chose wrong native mode");
  ((Button)find(choose,"Remote")).click();check(panel.selected==2,"Remote chose wrong native mode");
  View remote=panel.buildExperimentControlModeView(new ExperimentControlMode.Snapshot(30));
  check(find(remote,"Switch to Direct") instanceof Button&&find(remote,"Direct")==null&&find(remote,"Remote")==null&&find(remote,"LAN enrollment")!=null,"Remote recall lost switch/setup or restored initial chooser");
  ((Button)find(remote,"Switch to Direct")).click();check(panel.selected==1,"Remote switch to Direct unavailable");
  ((Button)find(remote,"Return to VR")).click();check(panel.hides==1,"Remote explicit return unavailable");
  System.out.println("PASS actual mode view builders: initial Direct/Remote choice, LAN enrollment and reopened Remote switch/return actions");
 }
}
'''.replace("__VIEW__", method(source, "private View buildExperimentControlModeView("))
    run_java(args, [root / "ExperimentControlMode.java"], fixture, "ControlModeViewTest")


def slider(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
final class ControlModeSliderTest {
 static class SeekBar {}
 static class NativeFixture implements ExperimentControlMode.NativeAccess {long value=30;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);int effects;
 boolean localExperimentMutationAllowed(){return controlMode.readSnapshot().localMutationsAllowed();}
 class SliderControl {Runnable onUserChange=()->effects++;void refresh(){}
  class Listener {__PROGRESS__}Listener listener=new Listener();
 }
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored){ControlModeSliderTest panel=new ControlModeSliderTest();SliderControl retained=panel.new SliderControl();
  retained.listener.onProgressChanged(new SeekBar(),50,true);check(panel.effects==0,"Retained Direct slider mutated experiment in Remote");
  panel.nativeOwner.value=36;retained.listener.onProgressChanged(new SeekBar(),50,true);check(panel.effects==0,"Choose slider mutated experiment");
  panel.nativeOwner.value=45;retained.listener.onProgressChanged(new SeekBar(),50,true);check(panel.effects==1,"Direct slider callback lost");
  retained.listener.onProgressChanged(new SeekBar(),50,false);check(panel.effects==1,"programmatic slider update mutated experiment");
  panel.nativeOwner.value=9;retained.listener.onProgressChanged(new SeekBar(),50,true);check(panel.effects==2,"legacy slider callback lost");
  System.out.println("PASS production retained slider mutation blocked in Choose/Remote, Direct and legacy callbacks preserved");
 }
}
'''.replace("__PROGRESS__", method(source, "public void onProgressChanged(SeekBar bar, int progress, boolean fromUser)"))
    run_java(args, [root / "ExperimentControlMode.java"], fixture, "ControlModeSliderTest")


def handoff(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class ControlPanelActivity {static int effects;static String applyExperimentSessionCommand(String command)throws Exception {effects++;return new JSONObject().put("command_status","queued").toString();}}
final class ControlModeDelayedHandoffTest {
 static class NativeFixture implements ExperimentControlMode.NativeAccess {long value=21;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){if(e==value)value=((value>>>3)+1)*8+4+m;return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 ExperimentSessionUiControlHandoff uiControlHandoff;int rebuilds,hides;
 static class RemotePending {String operation;}RemotePending remotePending;boolean rejectRemotePolarForMode(RemotePending pending){return true;}
 void replaceBreathCompositionPanelContent(String topic){rebuilds++;}void updateStatus(String message){}void hideRemoteControlPanel(){hides++;}
 void requestPolarStartupFromModeChoice(){} // Acquisition is exercised by test_polar_native_mode_startup.py.
 __SELECT__
 class Owner implements ExperimentSessionUiControlHandoff.Owner {
  final ExperimentControlMode.Snapshot operatorMode=controlMode.readSnapshot();Runnable scheduled;JSONObject value;String outcome="";
  Owner(String operation)throws Exception {value=new JSONObject().put("phase","active").put("control_state",operation.equals("start")?"armed":"paused").put("generation",7).put("runtime_epoch",3).put("session_key","session").put("condition_id","condition-a").put("non_audio_profile_sha256","profile").put("activation_binding_sha256","binding").put("presentation","unfocused");}
  public JSONObject read(){return value;}public void returnVr(){}public boolean presentationReady(){return true;}
  __DISPATCH__
  public void schedule(Runnable callback,long delay){scheduled=callback;}public long nowMs(){return 1000;}public long nowNs(){return 1000000000;}public void outcome(String state){outcome=state;}
 }
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 ExperimentSessionUiControlHandoff task(Owner owner,String operation){return new ExperimentSessionUiControlHandoff(owner,new ExperimentSessionControlCapability.Fence("","session","condition-a","profile","binding",operation,7,3,0,0,0),"ui-original");}
 public static void main(String[] ignored)throws Exception {
  for(String operation:new String[]{"start","resume"})for(long changed:new long[]{30,37}){
   ControlModeDelayedHandoffTest panel=new ControlModeDelayedHandoffTest();Owner owner=panel.new Owner(operation);ExperimentSessionUiControlHandoff task=panel.task(owner,operation);task.run();
   int before=ControlPanelActivity.effects;panel.nativeOwner.value=changed;owner.value.put("presentation","immersive-active");owner.scheduled.run();owner.scheduled.run();
   check(ControlPanelActivity.effects==before&&!task.pending(),"Delayed "+operation+" crossed native mode/revision change "+changed);
  }
  for(long initial:new long[]{21,9}){
   ControlModeDelayedHandoffTest panel=new ControlModeDelayedHandoffTest();panel.nativeOwner.value=initial;Owner owner=panel.new Owner("start");ExperimentSessionUiControlHandoff task=panel.task(owner,"start");task.run();int before=ControlPanelActivity.effects;
   owner.value.put("presentation","immersive-active");owner.scheduled.run();owner.scheduled.run();check(ControlPanelActivity.effects==before+1&&!task.pending(),"Direct/legacy handoff lost once-only native dispatch");
   if(initial==21){panel.uiControlHandoff=task;String admittedOutcome=owner.outcome;panel.selectExperimentControlMode(panel.controlMode.readSnapshot(),2);
    check(ControlPanelActivity.effects==before+1&&owner.outcome.equals(admittedOutcome),"mode switch changed already-admitted native action/receipt");}
  }
  ControlModeDelayedHandoffTest panel=new ControlModeDelayedHandoffTest();Owner owner=panel.new Owner("start");panel.uiControlHandoff=panel.task(owner,"start");panel.uiControlHandoff.run();
  panel.selectExperimentControlMode(panel.controlMode.readSnapshot(),2);check(!panel.uiControlHandoff.pending(),"Successful mode switch retained pending handoff");
  int before=ControlPanelActivity.effects;owner.value.put("presentation","immersive-active");owner.scheduled.run();check(ControlPanelActivity.effects==before,"Canceled handoff replayed");
  System.out.println("PASS actual delayed UI handoff: Start/Resume fenced at dispatch by captured native mode/revision, Direct-Remote-Direct denied, switch cancels pending only, Direct/legacy once-only retained");
 }
}
'''.replace("__SELECT__", method(source, "private void selectExperimentControlMode("))
    fixture = fixture.replace("__DISPATCH__", method(source, "public void dispatch(JSONObject command) throws Exception"))
    run_java(args, [root / name for name in ("ExperimentControlMode.java", "ExperimentSessionUiControlHandoff.java", "ExperimentSessionControlCapability.java", "ExperimentSessionBleProtocol.java")], fixture, "ControlModeDelayedHandoffTest")


def open_polar(args, root):
    source = (root / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.*;
final class ControlPanelActivity {}
final class Intent {static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_SINGLE_TOP=2;Map<String,Object> extras=new HashMap<>();Intent(Object context,Class<?> cls){}Intent setAction(String a){return this;}Intent addCategory(String a){return this;}Intent addFlags(int a){return this;}Intent putExtra(String a,Object b){extras.put(a,b);return this;}boolean getBooleanExtra(String key,boolean fallback){return extras.containsKey(key)?(Boolean)extras.get(key):fallback;}String getStringExtra(String key){return (String)extras.get(key);}void removeExtra(String key){extras.remove(key);}}
final class NativeMeshUiProducer {static boolean requestPanelVisibility(Object owner,boolean visible){return true;}}
final class SystemClock {static long elapsedRealtime(){return 100;}}
final class ViewTreeObserver {interface OnDrawListener {void onDraw();}List<OnDrawListener> listeners=new ArrayList<>();boolean isAlive(){return true;}void addOnDrawListener(OnDrawListener l){listeners.add(l);}void removeOnDrawListener(OnDrawListener l){listeners.remove(l);}void draw(){for(OnDrawListener l:new ArrayList<>(listeners))l.onDraw();}}
final class View {static final int VISIBLE=0;ViewTreeObserver observer=new ViewTreeObserver();List<Runnable> posts=new ArrayList<>();boolean isAttachedToWindow(){return true;}boolean isShown(){return true;}int getWindowVisibility(){return 0;}ViewTreeObserver getViewTreeObserver(){return observer;}void post(Runnable action){posts.add(action);}void invalidate(){}void drain(){for(Runnable action:new ArrayList<>(posts)){posts.remove(action);action.run();}}}
final class ExperimentSessionBleServer {String state="";int confirmations,rejections;long currentGeneration(){return 1;}void updateReceipt(String id,String status,String detail){state=status;if(status.equals("rejected"))rejections++;}boolean updateReceiptForGeneration(long g,String id,String status,String detail){if(g!=1)return false;state=status;if(status.equals("confirmed"))confirmations++;if(status.equals("rejected"))rejections++;return true;}}
final class ControlModeOpenPolarTest {
 static class NativeFixture implements ExperimentControlMode.NativeAccess {long value=9;public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 static class RemotePending {String browserId,operation;long serverGeneration=1,startedAtElapsedMs=100;RemotePending(String id,String ignored,String op){browserId=id;operation=op;}}
 static class Request {String id="0123456789abcdef",operation="open-polar";}
 static class Context {void startActivity(Intent i){throw new AssertionError("unexpected external Activity");}}Context remoteContext=new Context();
 static class Coordinator {long allocateRouteEvent(){return 1;}}Coordinator EXPERIMENT_SESSION_PANEL=new Coordinator();
 static final String ACTION_OPEN_PANEL="open",EXTRA_PANEL_ROUTE="route",PANEL_ROUTE_EXPERIMENTER="experimenter",EXTRA_PANEL_ROUTE_GENERATION="generation",EXTRA_REMOTE_OPEN_POLAR="polar";
 RemotePending remotePending;boolean remotePanelResumed=true,experimentShellDestroyed;long breathCompositionPanelNavigationEpoch=1;String breathCompositionPanelTopic="home";
 ExperimentSessionPanelRenderReceipt remoteRenderReceipt;View remoteRenderView;ViewTreeObserver.OnDrawListener remoteDrawListener;
 ExperimentSessionBleServer server=new ExperimentSessionBleServer();View content=new View();int renders;
 Object hostActivity(){return this;}View currentPanelContent(){return content;}boolean isFinishing(){return false;}ExperimentSessionBleServer remoteServer(){return server;}
 boolean localExperimentMutationAllowed(){return controlMode.readSnapshot().localMutationsAllowed();}
 void replaceBreathCompositionPanelContent(String topic){renders++;breathCompositionPanelTopic=topic;breathCompositionPanelNavigationEpoch++;content=new View();}
 __ROUTE_HELPER__
 __CANCEL__
 __REJECT_HELPER__
 __OBSERVE__
 __INTENT__
 void dispatch(Request request){ControlModeOpenPolarTest panel=this;__BRANCH__}
 void drawAndDrain(){content.getViewTreeObserver().draw();content.drain();}
 static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
 public static void main(String[] ignored){
  for(long mode:new long[]{20,30}){ControlModeOpenPolarTest panel=new ControlModeOpenPolarTest();panel.nativeOwner.value=mode;panel.dispatch(new Request());panel.drawAndDrain();
   check(panel.server.state.equals("rejected")&&panel.server.confirmations==0&&panel.renders==0,"Remote/Choose generic mode card falsely confirmed Polar rendering");}
  for(long mode:new long[]{21,9}){ControlModeOpenPolarTest panel=new ControlModeOpenPolarTest();panel.nativeOwner.value=mode;panel.dispatch(new Request());check(panel.server.state.equals("pending"),"Direct Polar request unavailable");panel.drawAndDrain();
   check(panel.server.state.equals("confirmed")&&panel.server.confirmations==1&&panel.renders==1,"Direct/legacy actual Polar render receipt lost");}
  ControlModeOpenPolarTest drift=new ControlModeOpenPolarTest();drift.nativeOwner.value=21;drift.dispatch(new Request());drift.content.getViewTreeObserver().draw();drift.nativeOwner.value=30;drift.content.drain();
  check(drift.server.state.equals("rejected")&&drift.server.confirmations==0,"Mode change after draw falsely confirmed Polar content");
  ControlModeOpenPolarTest intent=new ControlModeOpenPolarTest();intent.nativeOwner.value=30;intent.remotePending=new RemotePending("0123456789abcdef","","open-polar");
  intent.handleRemoteOpenPolarIntent(new Intent(intent,ControlPanelActivity.class).putExtra(EXTRA_REMOTE_OPEN_POLAR,true).putExtra("experiment_session_remote_command_id","0123456789abcdef"));
  check(intent.server.state.equals("rejected")&&intent.renders==0,"queued Polar intent rendered generic Remote screen");
  System.out.println("PASS production open-polar delegate and draw completion: Choose/Remote rejected, post-draw mode change rejected, Direct/legacy actual render receipt preserved");
 }
}
'''
    anchors = {"__BRANCH__": 'if ("open-polar".equals(request.operation))', "__ROUTE_HELPER__": "private boolean canRenderRemotePolarInPlace(",
               "__CANCEL__": "private void cancelRemotePolarRender(", "__OBSERVE__": "private void observeRemotePolarRender(", "__INTENT__": "private void handleRemoteOpenPolarIntent("}
    for token, anchor in anchors.items():
        fixture = fixture.replace(token, method(source, anchor))
    reject_anchor = "private boolean rejectRemotePolarForMode("
    fixture = fixture.replace("__REJECT_HELPER__", method(source, reject_anchor) if reject_anchor in source else "")
    run_java(args, [root / "ExperimentControlMode.java", root / "ExperimentSessionPanelRenderReceipt.java"], fixture, "ControlModeOpenPolarTest")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--json-jar", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--jdk-bin", type=Path, required=True)
    parser.add_argument("--case", choices=("all", "handoff", "presentation", "open-polar", "status"), default="all")
    args = parser.parse_args()
    root = args.repo / "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer"
    if args.case == "handoff":
        handoff(args, root)
        return
    if args.case == "presentation":
        presentation(args, root)
        return
    if args.case == "open-polar":
        open_polar(args, root)
        return
    if args.case == "status":
        status(args, root)
        return
    gateway(args, root)
    test = args.repo / "apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentControlModeTest.java"
    run_java(args, [root / "ExperimentControlMode.java"], test.read_text(encoding="utf-8"), "ExperimentControlModeTest")
    panel(args, root)
    presentation(args, root)
    local_click(args, root)
    close_panel(args, root)
    status(args, root)
    mode_view(args, root)
    slider(args, root)
    handoff(args, root)
    open_polar(args, root)


if __name__ == "__main__":
    main()
