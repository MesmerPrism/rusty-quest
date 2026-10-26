// Exact native retaining method; authority is separately covered by real broker tests.
use std::{collections::BTreeMap, sync::{Arc, Mutex}};
#[derive(Clone, PartialEq)] enum MediaStreamPlatformOperation { Start, Stop }
#[derive(Clone)] enum MediaStreamOwnerActionKind { ArmCleanup, ArmReceiver, Start, Stop }
#[derive(Clone, Debug)] enum OwnerKind { Sink }
#[derive(Clone)] struct AndroidMediaExecutionTicket { operation:MediaStreamPlatformOperation, action_kind:MediaStreamOwnerActionKind, owner_kind:OwnerKind, owner_id:String, provider_kind:String, resource_id:String }
#[derive(Clone)] struct OwnerDispatchAuthorityProjection { authority_peer_id:String, executor_peer_id:String }
#[derive(Clone)] struct ModelWitness;
#[derive(Clone)] struct AuthenticatedOwnerEffect;
#[derive(Clone)] struct AndroidMediaExecutionMode;
#[derive(Clone)] struct Original { ticket:AndroidMediaExecutionTicket, effect:Option<AuthenticatedOwnerEffect>, source_authority:Option<OwnerDispatchAuthorityProjection>, retained_enrollment:Option<ModelWitness> }
#[derive(Clone,Default)] struct PreparedState { originals:BTreeMap<String,Original> }
#[derive(Default)] struct Events { capture:u64, persist:u64, effect:u64, fail_capture:bool, fail_persist:bool, fail_effect:bool, fail_state:bool, effect_saw_witness:bool }
struct Authority(Arc<Mutex<Events>>);
impl Authority { fn capture_retained_original_enrollment(&self,_:&OwnerDispatchAuthorityProjection,_:&AndroidMediaExecutionTicket,_:&str,_:&[u8;32],_:u64)->Result<ModelWitness,String>{let mut e=self.0.lock().unwrap();e.capture+=1;if e.fail_capture {Err("capture denied".into())}else{Ok(ModelWitness)}} }
struct Clock;impl Clock {fn now_ms(&self)->Result<u64,String>{Ok(42)}}
struct Checkout;impl Checkout {fn take(_:Arc<Mutex<Option<()>>>)->Result<Self,String>{Ok(Self)}}
struct Cleanup { authority:Authority, clock:Clock, local:String, remote:String, remote_key_id:String, remote_key:[u8;32], serial:Arc<Mutex<Option<()>>>, state:Arc<Mutex<PreparedState>>, events:Arc<Mutex<Events>> }
impl Cleanup {
 fn require_state(&self)->Result<(),String>{if self.events.lock().unwrap().fail_state{Err("state denied".into())}else{Ok(())}}
 fn persist(&self,next:PreparedState)->Result<(),String>{let mut e=self.events.lock().unwrap();e.persist+=1;if e.fail_persist{return Err("durability denied".into())}*self.state.lock().unwrap()=next;Ok(())}
}
trait AuthenticatedOwnerRegistry { fn execute_and_verify(&mut self,authority:Option<&OwnerDispatchAuthorityProjection>,ticket:&AndroidMediaExecutionTicket,mode:AndroidMediaExecutionMode)->Result<AuthenticatedOwnerEffect,String>; }
struct Callbacks { events:Arc<Mutex<Events>>, state:Arc<Mutex<PreparedState>> }
impl AuthenticatedOwnerRegistry for Callbacks {
 fn execute_and_verify(&mut self,_:Option<&OwnerDispatchAuthorityProjection>,_:&AndroidMediaExecutionTicket,_:AndroidMediaExecutionMode)->Result<AuthenticatedOwnerEffect,String>{
  let mut e=self.events.lock().unwrap();e.effect+=1;e.effect_saw_witness=self.state.lock().unwrap().originals.values().any(|o|o.retained_enrollment.is_some());
  if e.fail_effect{Err("modeled provider uncertainty".into())}else{Ok(AuthenticatedOwnerEffect)}
 }
}
struct RetainingRegistry { cleanup:Cleanup, callbacks:Callbacks }
PRODUCTION_KEY
PRODUCTION_REGISTRY
fn setup()->(RetainingRegistry,Arc<Mutex<Events>>,AndroidMediaExecutionTicket){
 let events=Arc::new(Mutex::new(Events::default()));let state=Arc::new(Mutex::new(PreparedState::default()));
 let callbacks=Callbacks{events:events.clone(),state:state.clone()};
 let cleanup=Cleanup{authority:Authority(events.clone()),clock:Clock,local:"peer.local".into(),remote:"peer.remote".into(),remote_key_id:"key.remote".into(),remote_key:[1;32],serial:Arc::new(Mutex::new(Some(()))),state,events:events.clone()};
 let ticket=AndroidMediaExecutionTicket{operation:MediaStreamPlatformOperation::Start,action_kind:MediaStreamOwnerActionKind::ArmReceiver,owner_kind:OwnerKind::Sink,owner_id:"sink".into(),provider_kind:"provider".into(),resource_id:"resource".into()};
 (RetainingRegistry{cleanup,callbacks},events,ticket)
}
fn remote()->OwnerDispatchAuthorityProjection{OwnerDispatchAuthorityProjection{authority_peer_id:"peer.remote".into(),executor_peer_id:"peer.local".into()}}
#[test] fn uncertain_provider_keeps_pre_effect_witness(){let(mut r,e,t)=setup();e.lock().unwrap().fail_effect=true;assert!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).is_err());let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,1,1));assert!(e.effect_saw_witness);let state=r.cleanup.state.lock().unwrap();let o=&state.originals[&key(&t)];assert!(o.retained_enrollment.is_some()&&o.effect.is_none());}
#[test] fn own_projection_preserves_ordinary_path(){let(mut r,e,t)=setup();let own=OwnerDispatchAuthorityProjection{authority_peer_id:"peer.local".into(),executor_peer_id:"peer.local".into()};r.execute_and_verify(Some(&own),&t,AndroidMediaExecutionMode).unwrap();let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,2,1));assert!(!e.effect_saw_witness);}
#[test] fn no_projection_preserves_ordinary_path(){let(mut r,e,t)=setup();r.execute_and_verify(None,&t,AndroidMediaExecutionMode).unwrap();assert_eq!(e.lock().unwrap().capture,0);}
#[test] fn stop_and_abort_do_not_replace_original(){for abort in [false,true]{let(mut r,e,mut t)=setup();t.action_kind=MediaStreamOwnerActionKind::Stop;if !abort{t.operation=MediaStreamPlatformOperation::Stop;}r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).unwrap();let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,0,1));}}
#[test] fn capture_denial_precedes_provider(){let(mut r,e,t)=setup();e.lock().unwrap().fail_capture=true;assert!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).is_err());let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,0,0));}
#[test] fn failed_durability_precedes_provider(){let(mut r,e,t)=setup();e.lock().unwrap().fail_persist=true;assert!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).is_err());let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,1,0));assert!(r.cleanup.state.lock().unwrap().originals.is_empty());}
#[test] fn unpaired_projection_precedes_provider(){let(mut r,e,t)=setup();let mut foreign=remote();foreign.authority_peer_id="peer.foreign".into();assert!(r.execute_and_verify(Some(&foreign),&t,AndroidMediaExecutionMode).is_err());let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,0,0));}
#[test] fn physical_state_guard_precedes_capture(){let(mut r,e,t)=setup();e.lock().unwrap().fail_state=true;assert!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).is_err());assert_eq!(e.lock().unwrap().capture,0);}
#[test] fn successful_effect_retains_witness_and_effect(){let(mut r,e,t)=setup();r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).unwrap();let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,2,1));let state=r.cleanup.state.lock().unwrap();let o=&state.originals[&key(&t)];assert!(o.retained_enrollment.is_some()&&o.effect.is_some());}
