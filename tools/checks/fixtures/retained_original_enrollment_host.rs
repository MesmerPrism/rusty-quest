// Exact native retaining method; authority is separately covered by real broker tests.
#![allow(dead_code,unused_macros)]
use std::{collections::BTreeMap, sync::{Arc, Mutex, atomic::{AtomicBool,AtomicU64,Ordering}, mpsc::{self,Sender,Receiver}}, thread, time::Duration};
#[path="ADMISSION_PATH"] mod cleanup_admission;
#[derive(Clone, PartialEq)] enum MediaStreamPlatformOperation { Start, Stop }
#[derive(Clone)] enum MediaStreamOwnerActionKind { ArmCleanup, ArmReceiver, Start, Stop }
#[derive(Clone, Debug)] enum OwnerKind { Sink }
#[derive(Clone)] struct AndroidMediaExecutionTicket { operation:MediaStreamPlatformOperation, action_kind:MediaStreamOwnerActionKind, owner_kind:OwnerKind, owner_id:String, provider_kind:String, resource_id:String }
#[derive(Clone)] struct OwnerDispatchAuthorityProjection {
 schema_id:String,authority_peer_id:String,executor_peer_id:String,peer_session_id:String,route_grant_id:String,route_authority_revision:u64,
 authority_runtime_host_id:String,authority_provider_epoch_id:String,platform_runtime_spec_id:String,authority_client_id:String,authority_runtime_lease_id:String,
 signed_topology_sha256:String,route_configuration_sha256:String,authorization_kind:String,route_authority_evidence_sha256:String,expires_at_ms:u64,
}
#[derive(Clone)] struct ModelWitness;
#[derive(Clone)] struct AuthenticatedOwnerEffect;
#[derive(Clone,Copy)] struct AndroidMediaExecutionMode;
#[derive(Clone)] struct Original { ticket:AndroidMediaExecutionTicket, effect:Option<AuthenticatedOwnerEffect>, source_authority:Option<OwnerDispatchAuthorityProjection>, retained_enrollment:Option<ModelWitness> }
#[derive(Clone,Default)] struct PreparedState { originals:BTreeMap<String,Original> }
#[derive(Default)] struct Events { capture:u64, persist:u64, effect:u64, fail_capture:bool, fail_persist:bool, fail_effect:bool, effect_saw_witness:bool, projection_checks:u64, observed_at:Option<u64>, effect_expiry:Option<u64> }
#[derive(Clone)]
struct Authority {events:Arc<Mutex<Events>>,current:Arc<Mutex<OwnerDispatchAuthorityProjection>>,live:Arc<AtomicBool>}
impl Authority { fn capture_retained_original_enrollment(&self,_:&OwnerDispatchAuthorityProjection,_:&AndroidMediaExecutionTicket,_:&str,_:&[u8;32],_:u64)->Result<ModelWitness,String>{let mut e=self.events.lock().unwrap();e.capture+=1;if e.fail_capture {Err("capture denied".into())}else{Ok(ModelWitness)}} }
#[derive(Clone)]struct Clock(Arc<AtomicU64>);impl Clock {fn now_ms(&self)->Result<u64,String>{Ok(self.0.load(Ordering::SeqCst))}}
trait CurrentOwnerProjectionSource{fn current_projection(&self,ticket:&AndroidMediaExecutionTicket,local:&str,mode:AndroidMediaExecutionMode,now:u64)->Result<OwnerDispatchAuthorityProjection,String>;}
mod rusty_quest_broker_authority {
 pub struct QuestEmbeddedDuplexProjectionSource{authority:super::Authority,grant:String,peer:String}
 impl QuestEmbeddedDuplexProjectionSource{pub fn new(authority:super::Authority,grant:String,peer:String)->Self{Self{authority,grant,peer}}}
 impl super::CurrentOwnerProjectionSource for QuestEmbeddedDuplexProjectionSource{
  fn current_projection(&self,_:&super::AndroidMediaExecutionTicket,local:&str,_:super::AndroidMediaExecutionMode,now:u64)->Result<super::OwnerDispatchAuthorityProjection,String>{
   {let mut e=self.authority.events.lock().unwrap();e.projection_checks+=1;e.observed_at=Some(now);}
   if !self.authority.live.load(super::Ordering::SeqCst){return Err("modeled authority revoked".into());}
   let current=self.authority.current.lock().unwrap().clone();
   if current.expires_at_ms<=now{return Err("modeled authority expired".into());}
   if current.route_grant_id!=self.grant||current.authority_peer_id!=self.peer||current.executor_peer_id!=local{return Err("modeled current source binding differs".into());}
   Ok(current)
  }
 }
}
#[derive(Debug,PartialEq,Eq)]enum Observation{Attempt,Finished(Result<(),String>)}
#[derive(Default)]struct ObservedAdmission { actual:cleanup_admission::Admission, observer:Mutex<Option<Sender<Observation>>>, failed_commit_hook:Mutex<Option<Cleanup>> }
impl ObservedAdmission {
 fn enter(&self)->Result<cleanup_admission::Lease<'_>,String>{
  if let Some(tx)=self.observer.lock().unwrap().as_ref(){tx.send(Observation::Attempt).unwrap();}
  self.actual.enter()
 }
 fn try_enter(&self)->Result<Option<cleanup_admission::Lease<'_>>,String>{
  let writer=self.failed_commit_hook.lock().unwrap().take();
  if let Some(cleanup)=writer{
   let _lease=self.actual.enter()?;
   assert_eq!(cleanup.persist(PreparedState::default()),Err("durability denied".into()));
  }
  self.actual.try_enter()
 }
}
struct PersistBlock { entered:Sender<()>, release:Receiver<()> }
#[derive(Clone)]struct Cleanup { authority:Authority, clock:Clock, local:String, remote:String, remote_key_id:String, remote_key:[u8;32], serial:Arc<ObservedAdmission>, state:Arc<Mutex<PreparedState>>, poisoned:Arc<AtomicBool>, callbacks:Callbacks, requester:Arc<Mutex<Option<()>>> }
impl Cleanup {
 PRODUCTION_REQUIRE_STATE
 PRODUCTION_PERSIST
 PRODUCTION_CONSTRUCTION
 PRODUCTION_FRESH_PREFIX
}
#[derive(Debug,PartialEq,Eq)]struct Value;
mod serde_json{pub fn to_value<T>(_:&T)->Result<super::Value,()>{Ok(super::Value)}pub fn from_value<T>(value:T)->Result<T,String>{Ok(value)}}
macro_rules! json{($value:expr)=>{$value.clone()}}
fn safe_decode(value:String)->String{value}
struct RetainedCleanupReplaySnapshot;
enum NativeGuard{CleanupState,CleanupPublicKey,CleanupSerial}
enum NativeReason{CallFailed}
struct NativeTrace<'a>(std::marker::PhantomData<&'a ()>);
impl NativeTrace<'_>{fn mark(&self,_:NativeGuard,_:NativeReason){}}
// Encoding and the AtomicFile callback are the only modeled persistence leaves.
fn encode<T>(_:&T)->Result<Vec<u8>,String>{Ok(b"{}".to_vec())}
trait AuthenticatedOwnerRegistry { fn execute_and_verify(&mut self,authority:Option<&OwnerDispatchAuthorityProjection>,ticket:&AndroidMediaExecutionTicket,mode:AndroidMediaExecutionMode)->Result<AuthenticatedOwnerEffect,String>; }
#[derive(Clone)]struct Callbacks { events:Arc<Mutex<Events>>, state:Arc<Mutex<PreparedState>>, persist_block:Arc<Mutex<Option<PersistBlock>>> }
impl Callbacks {
 fn local_public_key(&self)->Result<[u8;32],String>{Ok([1;32])}
 fn persist_cleanup_preparations(&self,_:&str)->Result<(),String>{
  assert!(self.state.try_lock().is_ok(),"actual persistence must release state before callback");
  self.events.lock().unwrap().persist+=1;
  let block=self.persist_block.lock().unwrap().take();
  if let Some(block)=block{block.entered.send(()).unwrap();block.release.recv_timeout(Duration::from_secs(10)).expect("test writer release missing");}
  if self.events.lock().unwrap().fail_persist{Err("durability denied".into())}else{Ok(())}
 }
}
impl AuthenticatedOwnerRegistry for Callbacks {
 fn execute_and_verify(&mut self,authority:Option<&OwnerDispatchAuthorityProjection>,_:&AndroidMediaExecutionTicket,_:AndroidMediaExecutionMode)->Result<AuthenticatedOwnerEffect,String>{
  let mut e=self.events.lock().unwrap();e.effect+=1;e.effect_saw_witness=self.state.lock().unwrap().originals.values().any(|o|o.retained_enrollment.is_some());
  e.effect_expiry=authority.map(|a|a.expires_at_ms);
  if e.fail_effect{Err("modeled provider uncertainty".into())}else{Ok(AuthenticatedOwnerEffect)}
 }
}
struct RetainingRegistry { cleanup:Cleanup, callbacks:Callbacks }
PRODUCTION_KEY
PRODUCTION_REGISTRY
fn setup()->(RetainingRegistry,Arc<Mutex<Events>>,AndroidMediaExecutionTicket){
 let events=Arc::new(Mutex::new(Events::default()));let state=Arc::new(Mutex::new(PreparedState::default()));
 let callbacks=Callbacks{events:events.clone(),state:state.clone(),persist_block:Arc::new(Mutex::new(None))};
 let cleanup=Cleanup{authority:Authority{events:events.clone(),current:Arc::new(Mutex::new(local_projection())),live:Arc::new(AtomicBool::new(true))},clock:Clock(Arc::new(AtomicU64::new(42))),local:"peer.local".into(),remote:"peer.remote".into(),remote_key_id:"key.remote".into(),remote_key:[1;32],serial:Arc::new(ObservedAdmission::default()),state,poisoned:Arc::new(AtomicBool::new(false)),callbacks:callbacks.clone(),requester:Arc::new(Mutex::new(None))};
 let ticket=AndroidMediaExecutionTicket{operation:MediaStreamPlatformOperation::Start,action_kind:MediaStreamOwnerActionKind::ArmReceiver,owner_kind:OwnerKind::Sink,owner_id:"sink".into(),provider_kind:"provider".into(),resource_id:"resource".into()};
 (RetainingRegistry{cleanup,callbacks},events,ticket)
}
fn local_projection()->OwnerDispatchAuthorityProjection{OwnerDispatchAuthorityProjection{
 schema_id:"projection".into(),authority_peer_id:"peer.local".into(),executor_peer_id:"peer.local".into(),peer_session_id:"session".into(),route_grant_id:"grant".into(),route_authority_revision:1,
 authority_runtime_host_id:"host".into(),authority_provider_epoch_id:"epoch".into(),platform_runtime_spec_id:"runtime".into(),authority_client_id:"client".into(),authority_runtime_lease_id:"lease".into(),
 signed_topology_sha256:"topology".into(),route_configuration_sha256:"configuration".into(),authorization_kind:"current".into(),route_authority_evidence_sha256:"observation.old".into(),expires_at_ms:100,
}}
fn remote()->OwnerDispatchAuthorityProjection{let mut value=local_projection();value.authority_peer_id="peer.remote".into();value}
#[test] fn uncertain_provider_keeps_pre_effect_witness(){let(mut r,e,t)=setup();r.cleanup.authority.live.store(false,Ordering::SeqCst);e.lock().unwrap().fail_effect=true;assert_eq!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).err().unwrap(),"modeled provider uncertainty");let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,1,1));assert_eq!(e.projection_checks,0,"receiving remote original must not borrow receiver's different local Broker");assert!(e.effect_saw_witness);let state=r.cleanup.state.lock().unwrap();let o=&state.originals[&key(&t)];assert!(o.retained_enrollment.is_some()&&o.effect.is_none());}
#[test] fn own_projection_preserves_ordinary_path(){let(mut r,e,t)=setup();let own=local_projection();r.execute_and_verify(Some(&own),&t,AndroidMediaExecutionMode).unwrap();let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,2,1));assert!(!e.effect_saw_witness);}
#[test] fn no_projection_preserves_ordinary_path(){let(mut r,e,t)=setup();r.execute_and_verify(None,&t,AndroidMediaExecutionMode).unwrap();assert_eq!(e.lock().unwrap().capture,0);}
#[test] fn stop_and_abort_do_not_replace_original(){for abort in [false,true]{let(mut r,e,mut t)=setup();t.action_kind=MediaStreamOwnerActionKind::Stop;if !abort{t.operation=MediaStreamPlatformOperation::Stop;}r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).unwrap();let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,0,1));}}
#[test] fn capture_denial_precedes_provider(){let(mut r,e,t)=setup();e.lock().unwrap().fail_capture=true;assert!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).is_err());let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,0,0));}
#[test] fn failed_durability_precedes_provider(){let(mut r,e,t)=setup();e.lock().unwrap().fail_persist=true;assert_eq!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).err().unwrap(),"durability denied");assert!(r.cleanup.poisoned.load(Ordering::SeqCst));assert_eq!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).err().unwrap(),"cleanup durable commit uncertain");let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,1,0));assert!(r.cleanup.state.lock().unwrap().originals.is_empty());}
#[test] fn unpaired_projection_precedes_provider(){let(mut r,e,t)=setup();let mut foreign=remote();foreign.authority_peer_id="peer.foreign".into();assert!(r.execute_and_verify(Some(&foreign),&t,AndroidMediaExecutionMode).is_err());let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,0,0));}
#[test] fn physical_state_guard_precedes_capture(){let(mut r,e,t)=setup();r.cleanup.poisoned.store(true,Ordering::SeqCst);assert_eq!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).err().unwrap(),"cleanup durable commit uncertain");assert_eq!(e.lock().unwrap().capture,0);}
#[test] fn successful_effect_retains_witness_and_effect(){let(mut r,e,t)=setup();r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).unwrap();let e=e.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,2,1));let state=r.cleanup.state.lock().unwrap();let o=&state.originals[&key(&t)];assert!(o.retained_enrollment.is_some()&&o.effect.is_some());}

fn overlap(fail_commit:bool)->(Observation,Result<(),String>,Result<(),String>,Arc<Mutex<Events>>,Arc<AtomicBool>){
 let(mut registry,events,ticket)=setup();events.lock().unwrap().fail_persist=fail_commit;
 let(entered_tx,entered_rx)=mpsc::channel();let(release_tx,release_rx)=mpsc::channel();
 *registry.cleanup.callbacks.persist_block.lock().unwrap()=Some(PersistBlock{entered:entered_tx,release:release_rx});
 let writer_cleanup=registry.cleanup.clone();let serial=registry.cleanup.serial.clone();let poison=registry.cleanup.poisoned.clone();
 let writer=thread::spawn(move||{let _lease=writer_cleanup.serial.enter()?;writer_cleanup.persist(PreparedState::default())});
 entered_rx.recv_timeout(Duration::from_secs(10)).expect("actual persist did not enter callback");
 let was_poisoned=poison.load(Ordering::SeqCst);
 let(observe_tx,observe_rx)=mpsc::channel();*serial.observer.lock().unwrap()=Some(observe_tx.clone());
 let contender=thread::spawn(move||{let result=registry.execute_and_verify(Some(&remote()),&ticket,AndroidMediaExecutionMode).map(|_|());observe_tx.send(Observation::Finished(result.clone())).unwrap();result});
 let first=observe_rx.recv_timeout(Duration::from_secs(10)).expect("contender neither attempted admission nor finished");
 let effects_before_release=events.lock().unwrap().effect;
 // Always release and join both real threads before asserting the observed order.
 release_tx.send(()).unwrap();let writer_result=writer.join().unwrap();let contender_result=contender.join().unwrap();
 assert!(was_poisoned);assert_eq!(effects_before_release,0);
 (first,writer_result,contender_result,events,poison)
}
#[test]fn concurrent_successful_commit_waits_then_checks_actual_clean_state(){
 let(first,writer,contender,events,poison)=overlap(false);
 assert_eq!(first,Observation::Attempt,"live writer uncertainty must be checked after serialized admission");assert_eq!(writer,Ok(()));assert_eq!(contender,Ok(()));assert!(!poison.load(Ordering::SeqCst));
 let e=events.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(1,3,1));assert!(e.effect_saw_witness);
}
#[test]fn concurrent_failed_commit_keeps_uncertainty_and_never_executes_provider(){
 let(first,writer,contender,events,poison)=overlap(true);
 assert_eq!(first,Observation::Attempt);assert_eq!(writer,Err("durability denied".into()));assert_eq!(contender,Err("cleanup durable commit uncertain".into()));assert!(poison.load(Ordering::SeqCst));
 let e=events.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,1,0));
}
#[test]fn same_thread_actual_admission_reentry_is_runtime_busy_without_execution(){
 let(mut r,events,t)=setup();let serial=r.cleanup.serial.clone();let _lease=serial.enter().unwrap();
 assert_eq!(r.execute_and_verify(Some(&remote()),&t,AndroidMediaExecutionMode).err().unwrap(),"runtime busy");let e=events.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,0,0));
}
#[test]fn construction_observer_rechecks_failed_commit_after_nonwaiting_admission(){
 let(r,events,_)=setup();let serial=r.cleanup.serial.clone();
 {let _writer=serial.actual.enter().unwrap();assert!(serial.actual.try_enter().unwrap().is_none());assert_eq!(r.cleanup.construction_snapshot(),Err("construction cleanup requester active".into()));}
 events.lock().unwrap().fail_persist=true;
 *serial.failed_commit_hook.lock().unwrap()=Some(r.cleanup.clone());
 let observed=r.cleanup.construction_snapshot();
 assert!(r.cleanup.poisoned.load(Ordering::SeqCst));
 assert_eq!(observed,Err("cleanup durable commit uncertain".into()));
 let e=events.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,1,0));
}
#[test]fn fresh_setup_observer_rechecks_failed_commit_after_nonwaiting_admission(){
 let(r,events,_)=setup();let serial=r.cleanup.serial.clone();let trace=NativeTrace(std::marker::PhantomData);let replay=RetainedCleanupReplaySnapshot;
 {let _writer=serial.actual.enter().unwrap();assert!(serial.actual.try_enter().unwrap().is_none());assert_eq!(r.cleanup.fresh_setup_quiescence_observed(&replay,&trace),Err("fresh cleanup mutation checked out".into()));}
 events.lock().unwrap().fail_persist=true;
 *serial.failed_commit_hook.lock().unwrap()=Some(r.cleanup.clone());
 let observed=r.cleanup.fresh_setup_quiescence_observed(&replay,&trace);
 assert!(r.cleanup.poisoned.load(Ordering::SeqCst));
 assert_eq!(observed,Err("cleanup durable commit uncertain".into()));
 let e=events.lock().unwrap();assert_eq!((e.capture,e.persist,e.effect),(0,1,0));
}
enum AuthorityChange{RouteRevision,HeldExpired,Revoked,ObservationOnly}
fn local_after_wait(change:AuthorityChange)->(Result<(),String>,Arc<Mutex<Events>>,bool){
 let(mut registry,events,ticket)=setup();let retained_key=key(&ticket);let cleanup=registry.cleanup.clone();let serial=cleanup.serial.clone();let held=local_projection();
 let(entered_tx,entered_rx)=mpsc::channel();let(release_tx,release_rx)=mpsc::channel();
 let writer_serial=serial.clone();let writer=thread::spawn(move||{let _lease=writer_serial.actual.enter().unwrap();entered_tx.send(()).unwrap();release_rx.recv_timeout(Duration::from_secs(10)).unwrap();});
 entered_rx.recv_timeout(Duration::from_secs(10)).unwrap();
 let(tx,rx)=mpsc::channel();*serial.observer.lock().unwrap()=Some(tx);
 let contender=thread::spawn(move||registry.execute_and_verify(Some(&held),&ticket,AndroidMediaExecutionMode).map(|_|()));
 let first=rx.recv_timeout(Duration::from_secs(10)).unwrap();let no_early_effect={let e=events.lock().unwrap();e.persist==0&&e.effect==0};
 match change{
  AuthorityChange::RouteRevision=>cleanup.authority.current.lock().unwrap().route_authority_revision+=1,
  AuthorityChange::HeldExpired=>{cleanup.clock.0.store(101,Ordering::SeqCst);cleanup.authority.current.lock().unwrap().expires_at_ms=200;},
  AuthorityChange::Revoked=>cleanup.authority.live.store(false,Ordering::SeqCst),
  AuthorityChange::ObservationOnly=>{cleanup.clock.0.store(43,Ordering::SeqCst);let mut current=cleanup.authority.current.lock().unwrap();current.route_authority_evidence_sha256="observation.new".into();current.expires_at_ms=200;},
 }
 release_tx.send(()).unwrap();writer.join().unwrap();let result=contender.join().unwrap();
 assert_eq!(first,Observation::Attempt);assert!(no_early_effect);
 let retained=cleanup.state.lock().unwrap().originals.contains_key(&retained_key);
 (result,events,retained)
}
#[test]fn local_route_changed_during_wait_is_rechecked_before_persist_or_effect(){
 let(result,events,retained)=local_after_wait(AuthorityChange::RouteRevision);assert_eq!(result,Err("cleanup admission current authority differs".into()));
 let e=events.lock().unwrap();assert_eq!((e.projection_checks,e.capture,e.persist,e.effect),(1,0,0,0));assert!(!retained);
}
#[test]fn local_held_expiry_or_revocation_during_wait_precedes_provider(){
 for(change,expected)in[(AuthorityChange::HeldExpired,"cleanup admission current authority differs"),(AuthorityChange::Revoked,"modeled authority revoked")]{
  let(result,events,retained)=local_after_wait(change);assert_eq!(result,Err(expected.into()));let e=events.lock().unwrap();assert_eq!((e.projection_checks,e.capture,e.persist,e.effect),(1,0,0,0));assert!(!retained);
 }
}
#[test]fn local_refreshed_observation_digest_keeps_semantic_route_and_held_expiry(){
 let(result,events,retained)=local_after_wait(AuthorityChange::ObservationOnly);assert_eq!(result,Ok(()));let e=events.lock().unwrap();assert_eq!((e.projection_checks,e.capture,e.persist,e.effect),(1,0,2,1));assert_eq!(e.observed_at,Some(43));assert_eq!(e.effect_expiry,Some(100));assert!(retained);
}
