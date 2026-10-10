"""Actual receiver/resource methods with modeled platform members; no device effects."""
import argparse,json,subprocess,hashlib
from pathlib import Path

def method(text,marker):
    start=text.index(marker); opening=text.index('{',start);depth=0
    for i in range(opening,len(text)):
        if text[i]=='{':depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:return text[start:i+1]
    raise ValueError('unclosed production method')

PREFIX="""import io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;
public final class UnusedIncomingHost {
 interface Snapshot {MediaRuntimeSnapshot snapshot();}
 static class Receiver implements Snapshot {
  long generation=1,preparationRevision,connectionGeneration;String preparationState="unprepared";
  boolean surfaceReleased,projectionRetired=true;Object staged,provider;Runtime receiver;
  static class Runtime {String state="unprepared";boolean terminal;MediaRuntimeSnapshot snapshot(){return new MediaRuntimeSnapshot(1,1,state,terminal,"modeled owner","receiver");}}
"""
MIDDLE="""
 }
 static class Pipeline {boolean stopped=true;void requireStopped(){if(!stopped)throw new IllegalStateException("pending");}}
 static class Provider {boolean terminal=true;MediaRuntimeSnapshot snapshot(){return new MediaRuntimeSnapshot(1,1,"state",terminal,"modeled owner","owner");}}
 static class Outgoing {Provider[] p=new Provider[6];Outgoing(){for(int i=0;i<6;i++)p[i]=new Provider();}
 Provider provider(String name){String[] names={"source","processor","route","socket","codec","cleanup"};for(int i=0;i<6;i++)if(names[i].equals(name))return p[i];throw new AssertionError(name);}}
 Receiver incoming=new Receiver();Pipeline pipeline=new Pipeline();Outgoing outgoing=new Outgoing();
"""
SUFFIX="""
 static int count;
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
 public static void main(String[] args){
  UnusedIncomingHost h=new UnusedIncomingHost();require(!h.productResourcesTerminal(),"baseline unused receiver not terminal");
  h.closeUnusedIncomingForWholeProduct();require(h.productResourcesTerminal()&&h.incoming.preparationRevision==1,"never-prepared becomes terminal");
  h.closeUnusedIncomingForWholeProduct();require(h.incoming.preparationRevision==1,"idempotence does not revise owner");
  for(int damage=0;damage<5;damage++){h=new UnusedIncomingHost();if(damage==0)h.incoming.staged=new Object();if(damage==1)h.incoming.receiver=new Receiver.Runtime();if(damage==2)h.incoming.provider=new Object();if(damage==3)h.incoming.connectionGeneration=2;if(damage==4)h.incoming.projectionRetired=false;
   boolean denied=false;try{h.closeUnusedIncomingForWholeProduct();}catch(IllegalStateException expected){denied=true;}
   require(denied&&!h.productResourcesTerminal(),"never-started identity/barrier damage "+damage);
  }
  for(String state:new String[]{"receiving","receiver_armed","preparation_failed","cleanup_pending"}){h=new UnusedIncomingHost();h.incoming.preparationState=state;h.closeUnusedIncomingForWholeProduct();require(!h.productResourcesTerminal()&&h.incoming.preparationRevision==0&&!h.incoming.surfaceReleased,"used receiver cannot gain cleanup "+state);}
  h=new UnusedIncomingHost();h.incoming.preparationState="stopped";h.incoming.surfaceReleased=true;h.incoming.receiver=new Receiver.Runtime();h.incoming.receiver.state="stopped";h.incoming.receiver.terminal=true;h.closeUnusedIncomingForWholeProduct();require(h.productResourcesTerminal()&&h.incoming.preparationRevision==0,"typed stopped receiver unchanged");
  h=new UnusedIncomingHost();h.pipeline.stopped=false;h.closeUnusedIncomingForWholeProduct();require(!h.productResourcesTerminal(),"pipeline physical barrier retained");
  for(int owner=0;owner<6;owner++){h=new UnusedIncomingHost();h.outgoing.p[owner].terminal=false;h.closeUnusedIncomingForWholeProduct();require(!h.productResourcesTerminal(),"outgoing owner barrier retained "+owner);}
  if(count!=20)throw new AssertionError("count="+count);System.out.println("PASS20 actual unused-incoming/resource controls; platform model only");
 }
}
"""

p=argparse.ArgumentParser(description=__doc__)
for key in ['root','output','java-home']:p.add_argument('--'+key,required=True)
a=p.parse_args();r=Path(a.root).resolve();out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False)
b=r/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
receiver=b/'EmbeddedDuplexReceiver.java';resources=b/'EmbeddedDuplexResources.java';host=b/'EmbeddedDuplexProcessHost.java'
rx=receiver.read_text();rs=resources.read_text();hs=host.read_text()
# Bind the exercised helper to the actual whole-close branch, before JNI dispatch.
start=hs.index('if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE) {',hs.index('if (peerAction != null)'))
end=hs.index('if (peerAction == EmbeddedDuplexPeerAction.START)',start)
assert hs[start:end].count('retained.closeUnusedIncomingForWholeProduct();')==1
assert hs.count('retained.closeUnusedIncomingForWholeProduct();')==1
actual_rx=method(rx,'@Override public MediaRuntimeSnapshot snapshot(')+"\n"+method(rx,'synchronized void closeUnstartedAndVerify(')
actual_rs=method(rs,'void closeUnusedIncomingForWholeProduct(')+"\n"+method(rs,'boolean productResourcesTerminal(')
java=PREFIX+actual_rx+MIDDLE+actual_rs+SUFFIX
(out/'UnusedIncomingHost.java').write_text(java,encoding='utf-8')
media=r/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/MediaRuntimeSnapshot.java'
tools=[Path(a.java_home)/'bin/javac.exe',Path(a.java_home)/'bin/java.exe']
for label,argv in [('compile',[str(tools[0]),'--release','17','-Xlint:all','-Werror','-d',str(out),str(media),str(out/'UnusedIncomingHost.java')]),('controls',[str(tools[1]),'-cp',str(out),'UnusedIncomingHost'])]:
    x=subprocess.run(argv,capture_output=True);(out/(label+'.stdout')).write_bytes(x.stdout);(out/(label+'.stderr')).write_bytes(x.stderr)
    (out/(label+'.result.json')).write_text(json.dumps({'argv':argv,'exit_code':x.returncode}),encoding='utf-8')
    if x.returncode:raise RuntimeError(label+' failed; raw output preserved')
report={'status':'pass','controls':20,'source_pins':[{'path':str(x),'sha256':hashlib.sha256(x.read_bytes()).hexdigest(),'bytes':x.stat().st_size} for x in [receiver,resources,host,media,*tools]],'limits':['Actual receiver snapshot/typed close and resource helper/barrier bodies; other platform members modeled','Caller linkage source checked; no full ProcessHost/Android/JNI compilation or device cleanup proof'],'device_effects':False}
(out/'RESULT.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print('PASS20 actual unused-incoming/resource controls; platform model only')
