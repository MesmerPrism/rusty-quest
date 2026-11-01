"""Run actual Wi-Fi producer, gateway serializer and Polar receipt bodies with inert I/O."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_gateway_dedupe import method

PACKAGE = 'io/github/mesmerprism/rustyquest/native_renderer'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--json-jar', type=Path, required=True)
    p.add_argument('--jdk-bin', type=Path)
    p.add_argument('--source-ref', help='Read gateway/receipt bodies from an exact Git ref for retained red proof')
    p.add_argument('--case', choices=('gateway', 'scan', 'epoch', 'all'), default='all')
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[2]
    root = repo / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / PACKAGE
    gateway = (root / 'ExperimentSessionCommandGateway.java').read_text(encoding='utf-8')
    module = (root / 'BreathCompositionPanelModule.java').read_text(encoding='utf-8')
    polar = (repo / 'apps/native-renderer-android/panel-modules/polar/src/main/java' / PACKAGE / 'PolarSensorRuntime.java').read_text(encoding='utf-8')
    if args.source_ref:
        gateway = subprocess.check_output(['git', 'show', args.source_ref + ':' + (root / 'ExperimentSessionCommandGateway.java').relative_to(repo).as_posix()], cwd=repo, text=True, encoding='utf-8')
        polar_path = 'apps/native-renderer-android/panel-modules/polar/src/main/java/' + PACKAGE + '/PolarSensorRuntime.java'
        polar = subprocess.check_output(['git', 'show', args.source_ref + ':' + polar_path], cwd=repo, text=True, encoding='utf-8')
    source = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.*;
final class ControlPanelActivity {
 static JSONObject raw;static int reads;
 static String applyExperimentSessionCommand(String command)throws Exception {WifiSnapshotHarness.check("status".equals(new JSONObject(command).getString("operation")),"status cannot mutate experiment");reads++;return raw.toString();}
 static String nativeReadBreathCompositionStatus()throws Exception{return new JSONObject().put("snapshot",new JSONObject().put("feature_lock_active",true).put("activation_binding_matches",true).put("activation_binding_sha256",WifiSnapshotHarness.repeat("b",64))).toString();}
 static String conditionBreathGuidanceReadiness(String c){return "pattern-ready";}
 static String conditionAudioReadiness(String c){SystemClock.ns+=1000000L;return "track-ready";}
}
final class NativeRendererSelfKioskApplication {static String foregroundForOwnApp(Object context){return "vr";}}
final class BreathCompositionPanelModule {boolean canRenderRemotePolarInPlace(){return false;}}
final class ExperimentSessionRecordingExport {static boolean selected(Object context){return true;}}
final class SystemClock {static long ms=1000;static long elapsedRealtime(){return ms;}static long ns=1000000000L;static long elapsedRealtimeNanos(){return ++ns;}}
interface WifiDelegate {ExperimentSessionWifiSnapshot.Read wifiSnapshot() throws Exception;}
class ExperimentSessionBleServer {static final class CommandRequest {}
 boolean live=true;long generation=9,nativeEpoch=7;long currentGeneration(){return generation;}long currentNativeEpoch(){return nativeEpoch;}
 boolean acceptsStatusRead(long g,long e){return live&&g==generation&&e==nativeEpoch;}
 void updateReceiptForGeneration(long g,String id,String state,String detail){}
}
final class ExperimentControlMode {static final int CHOOSE=0,DIRECT=1,REMOTE=2;
 static final class Snapshot {boolean selected=true;int mode=2;long packed=10;String name="remote";String wireName(){return name;}}
 Snapshot state=new Snapshot();Snapshot readSnapshot(){return state;}}
public final class WifiSnapshotHarness {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 static JSONObject response(long revision,long active)throws Exception {
  return new JSONObject().put("schema","rusty.quest.experiment_session.response.v1").put("command_status","accepted").put("readback",
   new JSONObject().put("runtime_epoch",7).put("generation",3).put("revision",revision).put("phase","active").put("control_state","running")
   .put("condition_id","condition-a").put("completion","not-reached").put("active_time_ms",active).put("storage_status","ready")
   .put("initialization_status","ready").put("inventory_status","ready").put("recording_root_status","ready")
   .put("last_operation_status","none").put("last_operation_id","").put("profile_identity_bound",true).put("audio_identity_bound",true)
   .put("session_key","utc-ns-00000000000000000042").put("non_audio_profile_sha256",repeat("a",64)).put("activation_binding_sha256",repeat("b",64))
   .put("polar_input",new JSONObject().put("required",true).put("fresh",false).put("pause_latched",true)
    .put("reason","acc-stale").put("diagnostics_observed",true).put("clock","rust-instant").put("timeout_ms",1000).put("accepted_age_ms",1234)
    .put("ingress_count",13).put("accepted_count",12).put("rejected_count",1)
    .put("last_pause",new JSONObject().put("reason","acc-stale").put("interruption_revision",4)
     .put("deadline_elapsed_realtime_ns",2000).put("detected_elapsed_realtime_ns",3000).put("fresh_at_detection",false))
    .put("private_unbounded",repeat("x",20000))));
 }
 static String repeat(String s,int n){return String.join("",java.util.Collections.nCopies(n,s));}
 Object context=new Object();
 long identity=11,epoch=7,lifecycleRevision=1;
 JSONObject buildIdentity=new JSONObject().put("v",1).put("version_code",61).put("version_name","0.1.61");
 ExperimentSessionControlCapability session=new ExperimentSessionControlCapability();PolarStopBleCapability polar=new PolarStopBleCapability();
 ExperimentControlMode controlMode=new ExperimentControlMode();
 class Carrier extends ExperimentSessionBleServer {
  boolean acceptsNativeOwner(long i,long e){return live&&i==identity&&e==epoch;}}
 Carrier carrier=new Carrier();
 class Delegate implements WifiDelegate {
  int legacyReads;boolean replace;JSONObject raw=response(9,900),setup;
  ExperimentSessionWifiSnapshot wifiObservation;long wifiGeneration,wifiEpoch;
  class RemotePending {ExperimentSessionControlCapability.Fence sessionFence;String operation,nativeId,browserId;long serverGeneration;int requestedBias;}
  RemotePending remotePending;Object remoteTerminal;String remoteSetupPendingId="";Object remoteContext=new Object();
  java.lang.ref.WeakReference<BreathCompositionPanelModule> remotePanel=new java.lang.ref.WeakReference<>(null);
  Delegate()throws Exception{ControlPanelActivity.raw=raw;}
  ExperimentSessionBleServer remoteServer(){return carrier;}
  String remoteKioskState(){return repeat("x",900);}
  void expireRemotePendingObservation(long now){}
  JSONObject snapshot()throws Exception{legacyReads++;return new JSONObject().put("p","RUNNING").put("g",3).put("r",1).put("ms",5).put("pending",false)
    .put("o",new JSONObject().put("v",1).put("s","observed").put("e","7").put("i","1").put("g",3).put("r",1).put("a",0))
    .put("h","vr-session").put("c","condition-a").put("rec",true).put("co","NOT_REACHED").put("st","ready").put("padding",repeat("x",900));}
  JSONObject sessionControlReadback()throws Exception{return raw.getJSONObject("readback");}
  __READBACK__
  __PRODUCER__
  JSONObject polarSetupReadback(){return setup;}JSONObject polarStopReadback(){if(replace)carrier.generation++;return new JSONObject()
   .put("connected",false).put("acc_requested",true).put("acc_pmd_ack_running",true)
   .put("diagnostics",new JSONObject().put("clock","java-nano-time").put("observed_at_monotonic_ns",42).put("callback_count",15).put("acc_callback_count",14)
    .put("decode_count",13).put("jni_submit_count",12).put("callback_age_ms",6).put("gatt_state",0).put("gatt_status",8).put("disconnect_count",1).put("private",repeat("x",20000)))
   .put("selected_sensor",repeat("x",20000));}
 }
 Delegate delegate=new Delegate();
 WifiSnapshotHarness()throws Exception{}
 __SNAPSHOT__
 void gateway()throws Exception {
  JSONObject status;
  try{status=snapshot();}catch(Exception oldBudget){throw new AssertionError("full Wi-Fi snapshot must bypass compact BLE budget",oldBudget);}
  check(status.getInt("wifi_snapshot")==1,"full Wi-Fi marker");
  check(status.getJSONObject("build").getInt("version_code")==61,"Wi-Fi installed identity projected");
  JSONObject diagnostic=status.getJSONObject("pd"),nativeInput=diagnostic.getJSONObject("native"),javaInput=diagnostic.getJSONObject("java");
  check(nativeInput.getBoolean("diagnostics_observed")&&"acc-stale".equals(nativeInput.getString("reason"))&&nativeInput.getLong("accepted_age_ms")==1234,"current native reason/age retained");
  check("acc-stale".equals(nativeInput.getJSONObject("last_pause").getString("reason")),"original pause retained");
  check(!nativeInput.has("private_unbounded")&&!javaInput.has("private")&&!javaInput.has("selected_sensor"),"bounded projection excludes raw/private objects");
  check(javaInput.getInt("callback_count")==15&&javaInput.getInt("jni_submit_count")==12&&!javaInput.getBoolean("connected"),"separate Java callback/submission/link observations");
  for(String field:new String[]{"v","m","control_mode","o","p","g","r","c","rec","co","ms","st","pending","h","pi"})check(status.has(field),"mandatory "+field);
  check(status.getJSONObject("pi").keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("r","f","h"))),"exact pi booleans");
  check(status.getJSONObject("o").getLong("r")==status.getLong("r")&&status.getLong("r")==9&&status.getLong("ms")==900,"one read revision and clock");
  check(status.getJSONObject("o").getLong("g")==status.getLong("g"),"one read generation");
  check(status.getJSONObject("o").getLong("a")>=2,"serialization age retains original read completion");
  check(ControlPanelActivity.reads==1&&delegate.legacyReads==0,"status uses one coherent read and no mutation");
  JSONObject q=status.getJSONObject("q");check(session.admit(q.getString("s"),3,"pause",1000,9,11,7).generation==3,"capability exact native fence");
  check(status.toString().getBytes("UTF-8").length>480&&status.getString("k").length()==900,"full status fields retained");
  check(q.getString("s").equals(snapshot().getJSONObject("q").getString("s")),"same owner read preserves grant reference");
  delegate.raw=response(10,901);ControlPanelActivity.raw=delegate.raw;delegate.replace=true;denySnapshot("replaced carrier");
  carrier.live=false;denySnapshot("unavailable owner");
  try{session.admit(q.getString("s"),3,"pause",1000,9,11,7);throw new AssertionError("failed snapshot retained grant");}catch(SecurityException expected){}
  JSONObject oversized=new JSONObject(status.toString()).put("k",repeat("x",17000));
  try{ExperimentSessionWifiSnapshot.bounded(oversized);throw new AssertionError("16KiB bound");}catch(IllegalStateException expected){}
  WifiSnapshotHarness modes=new WifiSnapshotHarness();
  for(String mode:new String[]{"choose","direct","remote"}){modes.controlMode.state.name=mode;check(mode.equals(modes.snapshot().getString("control_mode")),"explicit "+mode);}
  modes.controlMode.state.selected=false;check(modes.snapshot().has("control_mode"),"unselected owner still explicit");
  modes.delegate.raw=response(10,0);modes.delegate.raw.getJSONObject("readback").put("phase","idle").put("control_state","idle").put("condition_id",JSONObject.NULL)
   .put("session_key",JSONObject.NULL).put("polar_input",new JSONObject().put("required",false).put("fresh",false).put("pause_latched",false));
  ControlPanelActivity.raw=modes.delegate.raw;modes.delegate.setup=new JSONObject().put("s","owner").put("g",7).put("r",8).put("p",true).put("b",true)
   .put("c",false).put("x","").put("sc",true).put("a",false).put("e",false).put("d",new JSONArray().put("aaaaaaaaaaaa").put("bbbbbbbbbbbb").put("cccccccccccc").put("dddddddddddd"));
  JSONObject idle=modes.snapshot();check(idle.has("y")&&idle.getJSONObject("y").getJSONArray("d").length()==4&&idle.getJSONObject("pi").length()==3&&!idle.has("q"),"full idle setup keeps original y and mandatory pi");
  for(String field:new String[]{"g","r","ms","rec","co","h","st","o","pi"})check(idle.has(field),"idle setup retains "+field);
 }
 void denySnapshot(String why)throws Exception{try{snapshot();}catch(SecurityException|IllegalStateException expected){return;}throw new AssertionError(why);}
 static void observation()throws Exception {
  ExperimentSessionWifiSnapshot observer=new ExperimentSessionWifiSnapshot();
  ExperimentSessionWifiSnapshot.Read read=observer.observe(response(9,900),1000000000L,1000000001L,7);
  read.statusAt(1002000000L,7);
  check(read.status.getJSONObject("pd").getJSONObject("native").getLong("accepted_age_ms")==1234&&read.status.getJSONObject("o").getLong("a")==2,"cached diagnostic ages retain original observation witness");
  check(read.status.getJSONObject("o").keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("v","s","e","i","g","r","a"))),"exact observed o schema");
  try{read.statusAt(1000000000L,7);throw new AssertionError("serialization clock regression");}catch(IllegalStateException expected){}
  try{observer.observe(response(8,899),2000000000L,2000000001L,7);throw new AssertionError("regressed revision");}catch(IllegalStateException expected){}
  try{observer.observe(response(10,901),2000000000L,2000000001L,8);throw new AssertionError("wrong epoch");}catch(IllegalStateException expected){}
  try{observer.observe(response(10,901),2000000000L,8000000001L,7);throw new AssertionError("stale read");}catch(IllegalStateException expected){}
  JSONObject missing=response(10,901);missing.getJSONObject("readback").remove("polar_input");
  try{observer.observe(missing,3000000000L,3000000001L,7);throw new AssertionError("missing required pi");}catch(IllegalStateException expected){}
  JSONObject idle=response(10,0);idle.getJSONObject("readback").put("phase","idle").put("control_state","idle").put("condition_id",JSONObject.NULL).put("session_key",JSONObject.NULL)
   .put("polar_input",new JSONObject().put("required",false).put("fresh",false).put("pause_latched",false));
  JSONObject legacy=new ExperimentSessionWifiSnapshot().observe(idle,4000000000L,4000000001L,7).status;
  check(legacy.getJSONObject("pi").length()==3,"idle pi always present");
  check(legacy.getJSONObject("pd").getJSONObject("native").isNull("accepted_age_ms"),"legacy missing age is unavailable, never zero");
 }
 static JSONObject idleWithEpoch(Object epoch)throws Exception {
  JSONObject idle=response(10,0);idle.getJSONObject("readback").put("phase","idle").put("control_state","idle")
   .put("condition_id",JSONObject.NULL).put("session_key",JSONObject.NULL).put("profile_identity_bound",false).put("audio_identity_bound",false)
   .put("runtime_epoch",epoch).put("polar_input",new JSONObject().put("required",false).put("fresh",false).put("pause_latched",false));
  return idle;
 }
 static void rawEpochs()throws Exception {
  for(Object bad:new Object[]{7.5,"7",Boolean.TRUE,JSONObject.NULL,0L,-1L,8L}) {
   WifiSnapshotHarness h=new WifiSnapshotHarness();JSONObject grant=h.snapshot().getJSONObject("q");
   h.delegate.raw=idleWithEpoch(bad);ControlPanelActivity.raw=h.delegate.raw;
   boolean denied=false;
   try{h.snapshot();}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){denied=true;}
   check(denied,"malformed/mismatched raw idle epoch accepted: "+bad);
   try{h.session.admit(grant.getString("s"),3,"pause",1000,9,11,7);throw new AssertionError("invalid raw epoch retained prior grant: "+bad);}catch(SecurityException expected){}
   h.delegate.raw=response(9,900);ControlPanelActivity.raw=h.delegate.raw;
   check(h.snapshot().has("q"),"invalid raw epoch mutated native observation: "+bad);
  }
  for(long valid:new long[]{7L,9007199254740992L,Long.MAX_VALUE}) {
   WifiSnapshotHarness h=new WifiSnapshotHarness();h.epoch=valid;h.carrier.nativeEpoch=valid;
   h.delegate.raw=idleWithEpoch(valid);ControlPanelActivity.raw=h.delegate.raw;
   JSONObject emitted=h.snapshot();
   check(emitted.getInt("wifi_snapshot")==1&&"IDLE".equals(emitted.getString("p")),"valid int64 idle epoch unavailable: "+valid);
   check(Long.toString(valid).equals(emitted.getJSONObject("o").getString("e")),"valid int64 epoch truncated: "+valid);
  }
 }
 static void scan()throws Exception {
  PolarReceiptHarness h=new PolarReceiptHarness();
  SystemClock.ms=31000;check("pending".equals(h.remoteSetupReceipt("original",7).getString("state")),"Scan must stay pending at31seconds");
  SystemClock.ms=45000;check("pending".equals(h.remoteSetupReceipt("original",7).getString("state")),"Scan must stay pending at45seconds while owner scanning");
  h.scanning=false;check("confirmed".equals(h.remoteSetupReceipt("original",7).getString("state")),"actual scan completion confirms original ID");
  SystemClock.ms=90000;check("confirmed".equals(h.remoteSetupReceipt("original",7).getString("state")),"original confirmation retained");
  h=new PolarReceiptHarness();SystemClock.ms=60000;check("outcome_unknown".equals(h.remoteSetupReceipt("original",7).getString("state")),"unresolved Scan unknown at60seconds");
  h.scanning=false;check("outcome_unknown".equals(h.remoteSetupReceipt("original",7).getString("state")),"unknown original never retrospectively replayed or confirmed");
  h=new PolarReceiptHarness();h.setupPendingOperation="connect";SystemClock.ms=31000;check("outcome_unknown".equals(h.remoteSetupReceipt("original",7).getString("state")),"other deadline unchanged");
  h=new PolarReceiptHarness();h.setupReceipts.clear();h.setupPendingId="";SystemClock.ms=0;
  JSONObject command=new JSONObject().put("id","original").put("op","scan").put("d","").put("s","owner").put("g",7).put("r",1);
  check("pending".equals(h.dispatchRemoteSetup(command,7).getString("state")),"real scan dispatch pending");
  SystemClock.ms=31000;check("pending".equals(h.dispatchRemoteSetup(command,7).getString("state"))&&h.panel.effects==1,"original retry cannot replay scan");
  SystemClock.ms=60000;check("outcome_unknown".equals(h.dispatchRemoteSetup(command,7).getString("state"))&&h.panel.effects==1,"unknown original stays once only");
 }
 public static void main(String[]args)throws Exception {
  if(!"scan".equals(args[0])){
   if(!"epoch".equals(args[0])){new WifiSnapshotHarness().gateway();observation();}
   rawEpochs();
  }
  if(!"gateway".equals(args[0])&&!"epoch".equals(args[0]))scan();
  System.out.println("PASS production full Wi-Fi projection/gateway and scan receipt: "+args[0]);
 }
}
final class PolarReceiptHarness {
 java.util.Map<String,String> setupReceipts=new java.util.LinkedHashMap<>(),setupOutcomes=new java.util.LinkedHashMap<>();
 java.util.Map<String,Long> setupEffectTimes=new java.util.LinkedHashMap<>();
 String setupPendingId="original",setupPendingOperation="scan",setupPendingCandidate="";long setupPendingSince=0;boolean scanning=true;
 long setupRevision=1;
 class Panel {int effects;long remoteSetupEffectNs(String op){return 42;}
  void selectRemoteCandidate(String d){}void handleCommand(String op){effects++;scanning=true;}
  JSONObject remoteSetupProjection()throws Exception{return remoteSetupStatus(7);}}Panel panel=new Panel();
 PolarReceiptHarness(){setupReceipts.put("original","original canonical command");}
 JSONObject remoteSetupStatus(long epoch)throws Exception{return new JSONObject().put("sc",scanning).put("x","").put("c",false).put("a",false).put("e",false).put("p",true).put("b",true).put("s","owner");}
 __DISPATCH__
 __RECEIPT__
}
'''.replace('__SNAPSHOT__', method(gateway, 'private JSONObject snapshot(')).replace('__RECEIPT__', method(polar, 'synchronized JSONObject remoteSetupReceipt(')).replace('__READBACK__', method(module, 'private JSONObject sessionControlReadback(JSONObject response)')).replace('__PRODUCER__', method(module, 'public ExperimentSessionWifiSnapshot.Read wifiSnapshot(')).replace('__DISPATCH__', method(polar, 'synchronized JSONObject dispatchRemoteSetup('))
    with tempfile.TemporaryDirectory(prefix='rq-wifi-snapshot-') as temp:
        out = Path(temp)
        fixture = out / 'WifiSnapshotHarness.java'
        helper = root / 'ExperimentSessionWifiSnapshot.java'
        fixture.write_text(source, encoding='utf-8')
        looper = out / 'android/os/Looper.java'
        looper.parent.mkdir(parents=True)
        looper.write_text('package android.os; public class Looper {static final Looper MAIN=new Looper();public static Looper myLooper(){return MAIN;}public static Looper getMainLooper(){return MAIN;}}', encoding='utf-8')
        names = ('ExperimentSessionBleProtocol', 'ExperimentSessionControlCapability', 'ExperimentSessionPanelState', 'ExperimentSessionPanelCoordinator', 'ExperimentSessionPanelViewPolicy', 'ExperimentSessionAndroidShell', 'ExperimentSessionJsonCodec')
        sources = [root / (n + '.java') for n in names] + [looper]
        sources.append(repo / 'apps/native-renderer-android/src/main/java' / PACKAGE / 'NativeRendererWriterAcknowledgedExitPolicy.java')
        sources.append(helper)
        exe = lambda name: str(args.jdk_bin / (name + '.exe')) if args.jdk_bin else name
        subprocess.run([exe('javac'), '--release', '8', '-encoding', 'UTF-8', '-cp', str(args.json_jar), '-d', str(out), *map(str, sources), str(fixture)], check=True)
        subprocess.run([exe('java'), '-cp', str(out) + os.pathsep + str(args.json_jar), 'io.github.mesmerprism.rustyquest.native_renderer.WifiSnapshotHarness', args.case], check=True)


if __name__ == '__main__':
    main()
