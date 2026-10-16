"""Actual STATUS composition/history with modeled platform facts; no device.

Compiles production signed-history Rust, actual Java STATUS call-site and pure
snapshot/composition bodies. No Android effects or physical cleanup claim.
"""
import argparse,hashlib,json,os,pathlib,subprocess

def method(text,marker):
    start=text.index(marker);opening=text.index('{',start);depth=0
    for i in range(opening,len(text)):
        if text[i]=='{':depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:return text[start:i+1]
    raise ValueError('Unclosed production method')

def main():
    p=argparse.ArgumentParser();p.add_argument('--repo-root',type=pathlib.Path,required=True);p.add_argument('--supplier-quest-root',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True)
    p.add_argument('--cargo-target-dir',type=pathlib.Path)
    for key in ('cargo','javac','java','json-jar'):p.add_argument('--'+key,required=True)
    a=p.parse_args();root=a.repo_root.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    def run(label,argv):
        environment=dict(os.environ)
        if label=='rust':environment['RUSTY_QUEST_RECEIVER_OBSERVATION_FIXTURE']=str(out/'SIGNED_HISTORY_FIXTURE.json')
        value=subprocess.run(argv,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180,env=environment)
        (out/(label+'.stdout')).write_bytes(value.stdout);(out/(label+'.stderr')).write_bytes(value.stderr)
        (out/(label+'.result.json')).write_text(json.dumps(dict(argv=argv,exit_code=value.returncode)),encoding='utf-8')
        if value.returncode:raise RuntimeError(label+' failed; retained streams')
    rust=out/'rust';(rust/'src').mkdir(parents=True)
    module=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/remote_owner_close.rs'
    (rust/'src/lib.rs').write_text('#[path='+json.dumps(module.as_posix())+']\nmod remote_owner_close;\n',encoding='utf-8')
    deps='\n'.join(name+'={path='+json.dumps((a.supplier_quest_root.resolve()/'crates'/name).as_posix())+'}' for name in ('rusty-quest-media-stream-android','rusty-quest-media-stream','rusty-quest-broker-authority'))
    (rust/'Cargo.toml').write_text('[package]\nname="remote-receiver-observation-host"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\ned25519-dalek={version="=2.1.1",default-features=false,features=["std","zeroize"]}\nserde_json="1"\n'+deps+'\n',encoding='utf-8')
    run('rust',[a.cargo,'test','--offline','--manifest-path',str(rust/'Cargo.toml'),'--target-dir',str(a.cargo_target_dir.resolve() if a.cargo_target_dir else out/'cargo'),'--','--test-threads=1'])
    app=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    host=(app/'EmbeddedDuplexProcessHost.java').read_text(encoding='utf-8');receiver=(app/'EmbeddedDuplexReceiver.java').read_text(encoding='utf-8');resources=(app/'EmbeddedDuplexResources.java').read_text(encoding='utf-8')
    bodies='\n'.join(method(host,marker) for marker in ('    private static JSONObject receiverPhysicalObservation(', '    private static JSONObject pendingReceiverObservation(', '    static JSONObject composeRemoteReceiverObservation('))
    start=host.index('                EmbeddedDuplexResources observedReceiverResources =');end=host.index('                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE)',start)
    status=host[start:end]
    physical=method(receiver,'    synchronized org.json.JSONObject physicalSnapshot(')
    forward=method(resources,'    JSONObject receiverPhysicalSnapshot(')
    java=out/'java';java.mkdir();harness=java/'ReceiverObservationHarness.java'
    harness.write_text(PREFIX.replace('PHYSICAL_BODY',physical).replace('FORWARD_BODY',forward)+bodies+'\nString status(EmbeddedDuplexPeerAction peerAction)throws Exception{boolean noMediaFallback=false;String receiptConfig="config";\n'+status+'\nreturn nativeReceipt;}\n'+SUFFIX,encoding='utf-8')
    snapshot=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/MediaRuntimeSnapshot.java'
    run('javac',[a.javac,'--release','17','-encoding','UTF-8','-cp',a.json_jar,'-d',str(java),str(snapshot),str(harness)])
    run('java',[a.java,'-cp',str(java)+os.pathsep+a.json_jar,'ReceiverObservationHarness',str(out/'SIGNED_HISTORY_FIXTURE.json')])
    pins=[]
    for path in (module,app/'EmbeddedDuplexProcessHost.java',app/'EmbeddedDuplexReceiver.java',app/'EmbeddedDuplexResources.java',snapshot,pathlib.Path(__file__).resolve()):
        raw=path.read_bytes();pins.append(dict(path=str(path),bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
    (out/'RESULT.json').write_text(json.dumps(dict(status='pass',source_pins=pins,platform_facts='modeled',device_effects=False,android_cleanup_qualified=False),indent=2),encoding='utf-8')

PREFIX='''import org.json.*;
import io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;
public final class ReceiverObservationHarness {
 static String fixturePath;
 static final class Receiver {
  long incarnation=1;boolean surfaceReleased=true,projectionRetired=true;int effects,reads;
  MediaRuntimeSnapshot current=new MediaRuntimeSnapshot(3,2,"stopped",true,"MODELED","receiver:g3");
  MediaRuntimeSnapshot snapshot(){reads++;return current;}
 PHYSICAL_BODY
 }
 static final class EmbeddedDuplexResources {Receiver incoming=new Receiver();int reads;int changeOnRead;
  FORWARD_BODY
 }
 enum EmbeddedDuplexPeerAction {STATUS(5),START(1),WHOLE_APP_CLOSE(6);final int word;EmbeddedDuplexPeerAction(int w){word=w;}}
 static final class Fence {long generation(){return 7;}}
 EmbeddedDuplexResources resources=new EmbeddedDuplexResources();Fence processFence=new Fence();long nativeExecutorGeneration=3;
 String retainedWholeNativeReceipt=null;String noMediaWholeProof(String c){throw new AssertionError("whole close");}
 static final class EmbeddedDuplexNative {
  static ReceiverObservationHarness owner;static int calls;static String fault="";
  static String peerLifecycle(int word)throws Exception {
   if(word!=5)throw new AssertionError("effect dispatch");calls++;
   if(calls==2&&fault.equals("error"))throw new IllegalStateException("MODELED unavailable");
   JSONObject value=life();
   if(calls==2&&fault.equals("history"))value.getJSONObject("remote_receiver_observation").getJSONObject("signed_history").put("replay_sha256","c".repeat(64));
   JSONObject history=value.getJSONObject("remote_receiver_observation").getJSONObject("signed_history");
   if(fault.equals("history-generation"))history.put("executor_generation",4);
   if(fault.equals("history-physical"))history.put("physical_cleanup_claimed",true);
   if(fault.equals("target"))history.getJSONArray("latest_stop_targets").getJSONObject(0).put("provider_handle_id","foreign");
   if(fault.equals("duplicate-target"))history.getJSONArray("latest_stop_targets").put(history.getJSONArray("latest_stop_targets").getJSONObject(0));
   if(fault.equals("oversize"))value.put("MODELED_retained_existing_field","x".repeat(261000));
   if(calls==1&&fault.equals("rearm"))owner.resources.incoming.incarnation++;
   if(calls==1&&fault.equals("resources"))owner.resources=new EmbeddedDuplexResources();
   if(fault.equals("no-history"))value.put("remote_receiver_observation",new JSONObject().put("status","pending"));
   return value.toString();
  }
 }
'''
SUFFIX='''
 static JSONObject life()throws Exception{
  JSONObject history=new JSONObject(java.nio.file.Files.readString(java.nio.file.Path.of(fixturePath)));
  JSONObject observation=new JSONObject().put("$schema","rusty.quest.embedded_duplex.remote_receiver_observation.v1").put("scope","peer_receiver_only").put("status","pending").put("physical_cleanup","pending").put("pending_reason","java_physical_observation_required").put("config_sha256","config").put("native_executor_generation",3).put("app_process_generation",7).put("signed_history",history);
  return new JSONObject().put("status","pending").put("peer_physical_cleanup","pending").put("native_host_physical_cleanup","pending").put("whole_app_physical_cleanup","pending").put("last_failure","MODELED retained").put("remote_receiver_observation",observation);
 }
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 static ReceiverObservationHarness fresh(String fault){ReceiverObservationHarness h=new ReceiverObservationHarness();EmbeddedDuplexNative.owner=h;EmbeddedDuplexNative.calls=0;EmbeddedDuplexNative.fault=fault;return h;}
 static JSONObject run(ReceiverObservationHarness h)throws Exception{return new JSONObject(h.status(EmbeddedDuplexPeerAction.STATUS));}
 static void pending(JSONObject result,String label)throws Exception{check(result.getJSONObject("remote_receiver_observation").getString("status").equals("pending"),label);check(result.getString("peer_physical_cleanup").equals("pending")&&result.getString("whole_app_physical_cleanup").equals("pending")&&result.getString("last_failure").equals("MODELED retained"),"original status changed");}
 public static void main(String[] argv)throws Exception {
  fixturePath=argv[0];
  ReceiverObservationHarness h=fresh("");JSONObject first=run(h);check(first.getJSONObject("remote_receiver_observation").getString("status").equals("terminal"),"real methods positive join");check(EmbeddedDuplexNative.calls==2,"two readonly STATUS observations");
  check(first.getJSONObject("remote_receiver_observation").getJSONArray("joined_stop_receipt_ids").getString(0).equals("receipt.stop.2"),"actual Rust fixture not joined");
  check(first.getString("peer_physical_cleanup").equals("pending")&&first.getString("whole_app_physical_cleanup").equals("pending"),"scope promotion");
  for(int i=0;i<4;i++){JSONObject value=run(h);check(value.getJSONObject("remote_receiver_observation").getString("status").equals("terminal"),"repeat pure read");}
  check(h.resources.incoming.effects==0&&h.resources.incoming.current.revision()==2&&h.resources.incoming.incarnation==1,"read mutated receiver");
  for(String fault:new String[]{"history","rearm","resources","error","no-history","history-generation","history-physical","target","duplicate-target"}){h=fresh(fault);pending(run(h),fault);if(fault.equals("no-history"))check(EmbeddedDuplexNative.calls==1,"missing history repeated native read");}
  h=fresh("oversize");JSONObject large=run(h);check(!large.has("remote_receiver_observation")&&large.getString("MODELED_retained_existing_field").length()==261000,"optional observation broke existing bound");
  for(int fault=0;fault<7;fault++){
   h=fresh("");Receiver r=h.resources.incoming;
   if(fault==0)r.surfaceReleased=false;if(fault==1)r.projectionRetired=false;
   if(fault==2)r.current=new MediaRuntimeSnapshot(3,2,"stopped",false,"MODELED","receiver:g3");
   if(fault==3)r.current=new MediaRuntimeSnapshot(4,2,"stopped",true,"MODELED","receiver:g3");
   if(fault==4)r.current=new MediaRuntimeSnapshot(3,3,"stopped",true,"MODELED","receiver:g3");
   if(fault==5)r.current=new MediaRuntimeSnapshot(3,2,"stopped",true,"MODELED","foreign");
   if(fault==6)r.current=new MediaRuntimeSnapshot(3,2,"prepared",true,"MODELED","receiver:g3");
   pending(run(h),"physical fault"+fault);check(r.effects==0,"fault read invoked effect");
  }
  System.out.println("Actual STATUS call-site/snapshot/accessor: 20 groups PASS; actual Rust signed-history fixture consumed, modeled platform, zero effects.");
 }
}
'''
if __name__=='__main__':main()
