"""Compose exact receiver cleanup/snapshot bodies with actual packaged registry.
Only Android reader/projection seams are modeled; no physical terminal claim.
"""
import argparse,json,hashlib,subprocess
from pathlib import Path
def method(text,marker):
    start=text.index(marker);opening=text.index('{',start);depth=0
    for i in range(opening,len(text)):
        if text[i]=='{':depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:return text[start:i+1]
    raise ValueError('unclosed method')
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',required=True);p.add_argument('--output',required=True);p.add_argument('--java-home',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
root=Path(a.root).resolve();out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False)
receiver=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexReceiver.java'
text=receiver.read_text();cleanup=method(text,'private MediaProviderReadback cleanupUnprepared(');snapshot=method(text,'@Override public MediaRuntimeSnapshot snapshot(');close=method(text,'synchronized void closeUnstartedAndVerify(')
java='''
import io.github.mesmerprism.rustyquest.media.*;
import org.json.JSONObject;
public final class UnpreparedReceiverRegistryHost implements MediaOwnerProvider {
 long generation=1,preparationRevision;boolean surfaceReleased,projectionRetired,retireFailed;String preparationState="preparation_failed";Staged staged;Receiver receiver;Object provider;long connectionGeneration;
 static class Staged{boolean terminal;Staged(boolean t){terminal=t;}boolean release(){return terminal;}}
 static class Receiver{MediaRuntimeSnapshot snapshot(){throw new AssertionError("unprepared only");}}
 void retireProjection(){if(retireFailed)throw new IllegalStateException("retained projection");projectionRetired=true;}
 public MediaProviderReadback execute(MediaOwnerAction action,CancellationHandle cancellation){cancellation.requireCurrent(generation);return cleanupUnprepared(action);}
 public MediaProviderReadback compensate(MediaOwnerAction action,CancellationHandle cancellation){return execute(action,cancellation);}
'''+cleanup+'\n'+snapshot+'\n'+close+'''
 static JSONObject ticket(String kind)throws Exception{return new JSONObject().put("$schema",MediaOwnerAction.SCHEMA).put("capability","host-capability").put("executor_generation",1).put("action_id","start.abort").put("authority_epoch_id","host-epoch").put("media_acceptance_authority_revision",2).put("expected_runtime_revision",1).put("client_id","host-client").put("lease_id","host-lease").put("sequence",1).put("operation","start").put("owner_kind","sink").put("action_kind",kind).put("owner_id","host-owner").put("provider_kind","host-provider").put("resource_id","host-resource");}
 static PackagedAndroidMediaOwnerRegistry registry(UnpreparedReceiverRegistryHost h){return new PackagedAndroidMediaOwnerRegistry(1,new MediaProductBinding.Builder("host-product").bind("sink","host-owner","host-provider","host-resource",h).build());}
 public static void main(String[]args)throws Exception{
  for(String kind:new String[]{"stop","cleanup"}){UnpreparedReceiverRegistryHost h=new UnpreparedReceiverRegistryHost();h.staged=new Staged(true);PackagedAndroidMediaOwnerRegistry r=registry(h);String ticket=ticket(kind).toString();String readback=r.execute(ticket,true);String verified=r.verifyAndReadEvidence(ticket,readback);if(verified==null)throw new AssertionError("fresh registry snapshot rejected "+kind);JSONObject proof=new JSONObject(verified);String state=kind.equals("stop")?"stopped":"cleaned";if(!proof.getString("observed_state").equals(state)||!h.snapshot().state().equals(state)||!proof.getBoolean("terminal"))throw new AssertionError("state/physical proof mismatch");h.closeUnstartedAndVerify();if(r.verifyAndReadEvidence(ticket,readback)!=null)throw new AssertionError("receipt consumed twice");}
  for(int barrier=0;barrier<2;barrier++){UnpreparedReceiverRegistryHost h=new UnpreparedReceiverRegistryHost();h.staged=new Staged(barrier!=0);h.retireFailed=barrier==1;PackagedAndroidMediaOwnerRegistry r=registry(h);boolean denied=false;try{r.execute(ticket("stop").toString(),true);}catch(IllegalStateException expected){denied=true;}if(!denied||h.snapshot().terminal())throw new AssertionError("physical pending was accepted");}
  UnpreparedReceiverRegistryHost h=new UnpreparedReceiverRegistryHost();h.staged=new Staged(true);PackagedAndroidMediaOwnerRegistry r=registry(h);String ticket=ticket("stop").toString();String readback=r.execute(ticket,true);h.preparationState="cleaned";if(r.verifyAndReadEvidence(ticket,readback)!=null)throw new AssertionError("inconsistent snapshot accepted");h.preparationState="stopped";h.projectionRetired=false;if(r.verifyAndReadEvidence(ticket,readback)!=null)throw new AssertionError("nonterminal snapshot accepted");
  System.out.println("PASS 6 composed actual registry/receiver cases incl strict mismatched snapshot and retained physical barriers; Android seams modeled");
 }
}
'''
(out/'UnpreparedReceiverRegistryHost.java').write_text(java)
media=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
sources=[media/(n+'.java') for n in ['PackagedAndroidMediaOwnerRegistry','AndroidMediaOwnerRegistry','MediaOwnerAction','MediaProviderReadback','MediaOwnerProvider','MediaProductBinding','CancellationHandle','MediaRuntimeSnapshot']]
jar=Path(a.json_jar);cp=str(out)+';'+str(jar)
for name,command in [('compile',[str(Path(a.java_home)/'bin/javac.exe'),'--release','17','-Xlint:all','-Werror','-cp',str(jar),'-d',str(out),*[str(s) for s in sources],str(out/'UnpreparedReceiverRegistryHost.java')]),('controls',[str(Path(a.java_home)/'bin/java.exe'),'-cp',cp,'UnpreparedReceiverRegistryHost'])]:
    run=subprocess.run(command,capture_output=True,timeout=60);(out/(name+'.stdout')).write_bytes(run.stdout);(out/(name+'.stderr')).write_bytes(run.stderr);assert run.returncode==0,run.stderr.decode(errors='replace')
report={'status':'passed','cases':6,'sources':[{'path':str(s),'sha256':hashlib.sha256(s.read_bytes()).hexdigest()} for s in [receiver,*sources,jar]],'limits':['Actual packaged registry/classes and exact receiver cleanup/snapshot/close method bodies; Android reader/projection/other receiver members modeled','No actual JNI/Android typecheck/APK/installed behavior or physical terminal qualification'],'device_calls':0}
(out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
