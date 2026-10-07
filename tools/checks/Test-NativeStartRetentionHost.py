"""Execute exact production retention and unprepared receiver cleanup methods.
Android/JNI/persistence seams are modeled; no installed cleanup claim.
"""
import argparse, hashlib, json, subprocess
from pathlib import Path

def method(text, marker):
    start=text.index(marker); opening=text.index('{',start); depth=0
    for i in range(opening,len(text)):
        if text[i]=='{': depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0: return text[start:i+1]
    raise ValueError('unclosed production method')

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--root',required=True);p.add_argument('--output',required=True)
p.add_argument('--rustc',required=True);p.add_argument('--java-home',required=True)
a=p.parse_args();root=Path(a.root).resolve();out=Path(a.output).resolve()
out.mkdir(parents=True,exist_ok=False)
native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
retained=native/'retained_cleanup_host.rs'; receiver=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexReceiver.java'
registry=method(retained.read_text(),'fn execute_and_verify(')
# This seam must not synthesize an undeclared production dependency. Android's
# public owner API supplies the action kind through the existing wildcard import.
android_api=root/'crates/rusty-quest-media-stream-android/src/lib.rs'
assert 'pub use rusty_quest_media_stream::{MediaStreamOwnerActionKind, MediaStreamPlatformOperation};' in android_api.read_text()
assert 'rusty_quest_media_stream::' not in registry
rust=r'''
use std::{collections::BTreeMap,sync::{Arc,Mutex}};
#[derive(Clone,Copy,PartialEq,Debug)] enum MediaStreamOwnerActionKind {ArmCleanup,ArmReceiver,Start,Stop,Cleanup}
use MediaStreamOwnerActionKind as Kind;
#[derive(Clone,Copy,PartialEq)] enum MediaStreamPlatformOperation {Start,Stop}
#[derive(Clone)] struct AndroidMediaExecutionTicket {operation:MediaStreamPlatformOperation,action_kind:Kind,action_id:String}
#[derive(Clone,PartialEq,Debug)] struct OwnerDispatchAuthorityProjection(u8);
#[derive(Clone,PartialEq,Debug)] struct AuthenticatedOwnerEffect(u8);
#[derive(Clone,Copy)] enum AndroidMediaExecutionMode {Execute,CompensateUncertain}
trait AuthenticatedOwnerRegistry {fn execute_and_verify(&mut self,authority:Option<&OwnerDispatchAuthorityProjection>,ticket:&AndroidMediaExecutionTicket,mode:AndroidMediaExecutionMode)->Result<AuthenticatedOwnerEffect,String>;}
#[derive(Clone)] struct Original {ticket:AndroidMediaExecutionTicket,effect:Option<AuthenticatedOwnerEffect>,source_authority:Option<OwnerDispatchAuthorityProjection>}
#[derive(Clone,Default)] struct State {originals:BTreeMap<String,Original>}
struct Cleanup {serial:Arc<Mutex<Option<()>>>,state:Mutex<State>,fail_persist:bool}
impl Cleanup {fn require_state(&self)->Result<(),String>{Ok(())} fn persist(&self,state:State)->Result<(),String>{if self.fail_persist {Err("persist failed".into())}else{*self.state.lock().unwrap()=state;Ok(())}}}
struct Checkout;impl Checkout{fn take(_:Arc<Mutex<Option<()>>>)->Result<Self,String>{Ok(Self)}}
fn key(_: &AndroidMediaExecutionTicket)->String {"same-owner".into()}
struct JavaOwnerCallbacks{calls:usize,fail:bool}
impl AuthenticatedOwnerRegistry for JavaOwnerCallbacks {fn execute_and_verify(&mut self,_:Option<&OwnerDispatchAuthorityProjection>,_:&AndroidMediaExecutionTicket,_:AndroidMediaExecutionMode)->Result<AuthenticatedOwnerEffect,String>{self.calls+=1;if self.fail {Err("uncertain".into())}else{Ok(AuthenticatedOwnerEffect(3))}}}
struct RetainingRegistry{cleanup:Cleanup,callbacks:JavaOwnerCallbacks}
impl AuthenticatedOwnerRegistry for RetainingRegistry {
''' + registry + r'''
}
fn registry()->RetainingRegistry{RetainingRegistry{cleanup:Cleanup{serial:Arc::new(Mutex::new(Some(()))),state:Mutex::new(State::default()),fail_persist:false},callbacks:JavaOwnerCallbacks{calls:0,fail:false}}}
fn ticket(kind:Kind)->AndroidMediaExecutionTicket{AndroidMediaExecutionTicket{operation:MediaStreamPlatformOperation::Start,action_kind:kind,action_id:if matches!(kind,Kind::Stop|Kind::Cleanup){"start.abort"}else{"start"}.into()}}
#[test]fn uncertain_forward_then_successful_abort_preserves_true_original(){
 for kind in [Kind::ArmCleanup,Kind::ArmReceiver,Kind::Start]{
  let mut r=registry();r.callbacks.fail=true;let original=ticket(kind);let authority=OwnerDispatchAuthorityProjection(7);
  assert!(r.execute_and_verify(Some(&authority),&original,AndroidMediaExecutionMode::Execute).is_err());
  r.callbacks.fail=false;
  for reverse in [Kind::Stop,Kind::Cleanup]{assert!(r.execute_and_verify(Some(&OwnerDispatchAuthorityProjection(9)),&ticket(reverse),AndroidMediaExecutionMode::CompensateUncertain).is_ok());}
  let state=r.cleanup.state.lock().unwrap();let kept=&state.originals["same-owner"];
  assert_eq!(kept.ticket.action_id,"start");assert_eq!(kept.ticket.action_kind,kind);assert_eq!(kept.effect,None);assert_eq!(kept.source_authority,Some(authority));
  assert_eq!(ticket(Kind::Stop).action_id,format!("{}.abort",kept.ticket.action_id));
 }
}
#[test]fn completed_forward_effect_and_projection_survive_failed_reverse(){let mut r=registry();r.execute_and_verify(Some(&OwnerDispatchAuthorityProjection(7)),&ticket(Kind::ArmReceiver),AndroidMediaExecutionMode::Execute).unwrap();r.callbacks.fail=true;assert!(r.execute_and_verify(None,&ticket(Kind::Stop),AndroidMediaExecutionMode::CompensateUncertain).is_err());let state=r.cleanup.state.lock().unwrap();assert_eq!(state.originals["same-owner"].effect,Some(AuthenticatedOwnerEffect(3)));assert_eq!(state.originals["same-owner"].ticket.action_id,"start");}
#[test]fn reverse_without_forward_does_not_invent_original(){let mut r=registry();for kind in [Kind::Stop,Kind::Cleanup]{r.execute_and_verify(None,&ticket(kind),AndroidMediaExecutionMode::CompensateUncertain).unwrap();}assert!(r.cleanup.state.lock().unwrap().originals.is_empty());let mut stop=ticket(Kind::ArmReceiver);stop.operation=MediaStreamPlatformOperation::Stop;r.execute_and_verify(None,&stop,AndroidMediaExecutionMode::Execute).unwrap();assert!(r.cleanup.state.lock().unwrap().originals.is_empty());}
#[test]fn failed_forward_persistence_prevents_callback(){let mut r=registry();r.cleanup.fail_persist=true;assert!(r.execute_and_verify(None,&ticket(Kind::Start),AndroidMediaExecutionMode::Execute).is_err());assert_eq!(r.callbacks.calls,0);}
'''
rust+='\n#[path="'+str(native/'owner_failure.rs').replace('\\','/')+'"] mod owner_failure;\n'
(out/'retention.rs').write_text(rust)
cleanup=method(receiver.read_text(),'private MediaProviderReadback cleanupUnprepared(')
java=r'''
public final class NativeStartReceiverHost {
 long generation=1,preparationRevision=0;boolean surfaceReleased=false,projectionRetired=false,retireFailed=false;String preparationState="preparation_failed";Staged staged;
 static final class Staged{boolean terminal;Staged(boolean value){terminal=value;}boolean release(){return terminal;}}
 void retireProjection(){if(retireFailed)throw new IllegalStateException("pending projection");projectionRetired=true;}
 static final class MediaOwnerAction{String kind;MediaOwnerAction(String value){kind=value;}String actionKind(){return kind;}}
 static final class MediaProviderReadback{String state;long revision;MediaProviderReadback(MediaOwnerAction action,String handle,long rev,String observed,String receipt){state=observed;revision=rev;}}
''' + cleanup + r'''
 public static void main(String[]args){
  for(String kind:new String[]{"stop","cleanup"}){NativeStartReceiverHost h=new NativeStartReceiverHost();h.staged=new Staged(true);MediaProviderReadback r=h.cleanupUnprepared(new MediaOwnerAction(kind));if(!r.state.equals(kind.equals("stop")?"stopped":"cleaned")||!h.surfaceReleased||!h.projectionRetired||r.revision==0)throw new AssertionError("terminal contract");}
  NativeStartReceiverHost h=new NativeStartReceiverHost();h.staged=new Staged(false);boolean rejected=false;try{h.cleanupUnprepared(new MediaOwnerAction("stop"));}catch(IllegalStateException expected){rejected=true;}if(!rejected||h.surfaceReleased||h.projectionRetired)throw new AssertionError("retained reader falsely terminal");
  h=new NativeStartReceiverHost();h.retireFailed=true;rejected=false;try{h.cleanupUnprepared(new MediaOwnerAction("stop"));}catch(IllegalStateException expected){rejected=true;}if(!rejected||h.projectionRetired)throw new AssertionError("retained projection falsely terminal");
  System.out.println("PASS 4 exact unprepared cleanup controls; Android reader/projection seams modeled");
 }
}
'''
(out/'NativeStartReceiverHost.java').write_text(java)
commands=[('rust-compile',[a.rustc,'--edition','2021','--test',str(out/'retention.rs'),'-o',str(out/'retention.exe')]),('rust-controls',[str(out/'retention.exe')]),('java-compile',[str(Path(a.java_home)/'bin/javac.exe'),'--release','17','-Xlint:all','-Werror','-d',str(out),str(out/'NativeStartReceiverHost.java')]),('java-controls',[str(Path(a.java_home)/'bin/java.exe'),'-cp',str(out),'NativeStartReceiverHost'])]
results=[]
for name,args in commands:
    run=subprocess.run(args,capture_output=True,timeout=60);(out/(name+'.stdout')).write_bytes(run.stdout);(out/(name+'.stderr')).write_bytes(run.stderr)
    results.append({'name':name,'exit':run.returncode});assert run.returncode==0,run.stderr.decode(errors='replace')
report={'status':'passed','checks':results,'production_sources':[{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for path in [retained,receiver,native/'owner_failure.rs']], 'device_calls':0,'limits':['Exact production RetainingRegistry method with modeled serialized checkout/persistence/Java registry seams','Exact production cleanupUnprepared method with modeled reader/projection terminal callbacks','Production finite cause latch tests; no actual JNI/Android native typecheck/APK/installed recovery or device acceptance']}
(out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
