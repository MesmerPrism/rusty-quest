#![allow(dead_code,unused_imports)]
use serde::{Serialize,Deserialize};
use serde_json::{json,Value};
use std::collections::{BTreeMap,BTreeSet};
use std::sync::{Arc,Mutex,atomic::{AtomicBool,Ordering}};
use embedded_duplex::preparation_native_guard::{Guard as NativeGuard,FailureReason as NativeReason,Trace as NativeTrace};
use embedded_duplex::runtime_host::preparation_diagnostic::{Actor,Attempt,Journal,Stage,Reason,State};

// Explicit dependency models. Production control/validation bodies below are
// extracted unchanged; no fixture claims signature or platform qualification.
#[derive(Clone,Debug,Serialize,Deserialize,PartialEq)]
enum OwnerDispatchStatus{Completed,Uncertain}
#[derive(Clone,Copy,Debug,Serialize,Deserialize,PartialEq)]
enum MediaStreamPlatformOperation{Start,Stop}
#[derive(Clone,Copy,Debug,Serialize,Deserialize,PartialEq)]
enum MediaStreamOwnerActionKind{ArmReceiver,Stop}
#[derive(Clone,Copy,Debug,Serialize,Deserialize,PartialEq)]
enum MediaStreamOwnerKind{Sink}
const ANDROID_MEDIA_READBACK_SCHEMA:&str="readback";
const OWNER_DISPATCH_RESPONSE_SCHEMA:&str="dispatch";
const PRODUCT_ACTIVATION_RESPONSE_SCHEMA:&str="activation";
const VERIFIED_OWNER_EFFECT_SCHEMA:&str="verified";
const RETAINED_CLEANUP_DISPATCH_RESPONSE_SCHEMA:&str="cleanup";
const MAX_OWNER_DISPATCH_REPLAYS:usize=256;
#[derive(Clone,Debug,Serialize,Deserialize,PartialEq)]
struct AndroidMediaOwnerReadback{
 schema_id:String,owner_kind:MediaStreamOwnerKind,provider_kind:String,executor_generation:u64,
 provider_state_revision:u64,provider_handle_id:String,resource_id:String,authority_epoch_id:String,
 client_id:String,lease_id:String,receipt_id:String,operation:MediaStreamPlatformOperation,
 action_kind:MediaStreamOwnerActionKind,observed_state:String,
}
#[derive(Clone,Debug,Serialize,Deserialize)]
struct Verified{
 schema_id:String,readback_sha256:String,receipt_id:String,executor_generation:u64,
 provider_state_revision:u64,observed_state:String,provider_handle_id:String,terminal:bool,
}
#[derive(Clone,Debug,Serialize,Deserialize)]
struct Effect{readback_json:String,readback:AndroidMediaOwnerReadback,verified:Verified}
#[derive(Clone,Debug,Serialize,Deserialize)]
struct Response{
 schema_id:String,dispatch_id:String,executor_peer_id:String,signer_key_id:String,
 status:OwnerDispatchStatus,failure:Option<String>,request_sha256:String,signature_base64:String,effect:Option<Effect>,
}
type RetainedCleanupDispatchResponse=Response;
#[derive(Clone,Debug,Serialize,Deserialize)]
struct ActivationReadback{activated:bool,activation_id:String}
#[derive(Clone,Debug,Serialize,Deserialize)]
struct ActivationResponse{schema_id:String,activation_id:String,signer_key_id:String,status:OwnerDispatchStatus,failure:Option<String>,readback:Option<ActivationReadback>,signature_base64:String}
#[derive(Clone,Default,Serialize)]
struct OwnerDispatchReplaySnapshot{pending_request_sha256:BTreeMap<String,String>,terminal:BTreeMap<String,Response>}
#[derive(Clone,Default,Serialize)]
struct ProductActivationReplaySnapshot{pending_request_sha256:BTreeMap<String,String>,terminal:BTreeMap<String,ActivationResponse>}
#[derive(Clone,Default,Serialize)]
struct Terminal{response_bytes:Vec<u8>,request_sha256:String}
#[derive(Clone,Default,Serialize)]
struct RetainedCleanupReplaySnapshot{pending_request_sha256:BTreeMap<String,String>,terminal:BTreeMap<String,Terminal>,invalid:bool}
impl RetainedCleanupReplaySnapshot{fn validate(&self)->Result<(),&'static str>{if self.invalid{Err("modeled replay malformed")}else{Ok(())}}}
struct VerifyingKey;struct Signature;
impl VerifyingKey{
 fn from_bytes(key:&[u8;32])->Result<Self,&'static str>{if key[0]==0{Err("modeled key unavailable")}else{Ok(Self)}}
 fn verify_strict(&self,bytes:&[u8],_:&Signature)->Result<(),&'static str>{if bytes==b"bad"{Err("modeled signature invalid")}else{Ok(())}}
}
impl Signature{fn from_bytes(_:&[u8;64])->Self{Self}}
fn decode_signature_base64(s:&str)->Result<[u8;64],String>{if s=="decode_fail"{Err("modeled signature decode".into())}else{Ok([0;64])}}
fn response_signing_bytes(r:&Response)->Result<Vec<u8>,String>{Ok(r.signature_base64.as_bytes().to_vec())}
fn retained_cleanup_response_signing_bytes(r:&Response)->Result<Vec<u8>,String>{response_signing_bytes(r)}
fn product_activation_response_signing_bytes(r:&ActivationResponse)->Result<Vec<u8>,String>{Ok(r.signature_base64.as_bytes().to_vec())}
fn packaged_json_sha256(_:&str)->String{"a".repeat(64)}
#[derive(Default)]struct Binding{generation:u64,record_sha256:String}
struct Capability{generation:u64,binding:Binding,live:bool}
impl Capability{fn require_live(&self)->Result<(),String>{if self.live{Ok(())}else{Err("modeled capability stale".into())}}}
#[derive(Default)]struct Life{
 renewal_pending:bool,whole_close_requested:bool,client:Option<String>,start_mutation:Option<Value>,activation:Option<Value>,
 stop_mutation:Option<Value>,stop_completion:Option<Value>,route_receipt:Option<Value>,route_termination:Option<Value>,route_cleanup:Option<Value>,
 termination_action:Option<&'static str>,stop_effect:Option<Value>,renewal_count:u64,renewals:Vec<Value>,current_lifetime_renewal_count:u64,
}
fn media_stop_effect(l:&Life)->Option<&Value>{l.stop_effect.as_ref()}
#[derive(Default)]struct Sender{pending:Option<Flight>}
struct Flight{response:Option<ActivationResponse>,client_id:Value}
#[derive(Clone)]struct Server<T:Clone>{replay:T}
impl<T:Clone> Server<T>{fn replay_snapshot(&self)->T{self.replay.clone()}}
struct Provider{evidence:Result<String,String>}
impl Provider{fn evidence_json(&self)->Result<String,String>{self.evidence.clone()}}
struct Callbacks{public_key:[u8;32],key_failed:bool,key_id:&'static str}
impl Callbacks{
 fn key_id(&self)->&str{self.key_id}
 fn local_public_key(&self)->Result<[u8;32],String>{if self.key_failed{Err("modeled Java key callback".into())}else{Ok(self.public_key)}}
}
struct Original{effect:Option<Value>}
#[derive(Default)]struct PreparedState{originals:BTreeMap<String,Original>,prepared:BTreeMap<String,Value>}
struct Cleanup{callbacks:Callbacks,serial:Mutex<Option<()>>,state:Mutex<PreparedState>,local:String,generation:u64,valid:bool}
impl Cleanup{fn require_state(&self)->Result<(),String>{if self.valid{Ok(())}else{Err("modeled cleanup poison".into())}}}
struct Host{
 capability:Capability,peer_lifecycle:Mutex<Option<Life>>,activation_sender:Mutex<Option<Sender>>,
 server:Mutex<Option<Server<OwnerDispatchReplaySnapshot>>>,activation_server:Mutex<Option<Server<ProductActivationReplaySnapshot>>>,
 cleanup_server:Mutex<Option<Server<RetainedCleanupReplaySnapshot>>>,owner_effect_attempted:AtomicBool,
 local_public_key:[u8;32],callbacks:Callbacks,restored_owner_replay:bool,cleanup:Cleanup,
 provider:Mutex<Option<Provider>>,config_sha256:String,
}
fn safe_decode(e:serde_json::Error)->String{e.to_string()}
fn json_value(s:&str)->Result<Value,String>{serde_json::from_str(s).map_err(safe_decode)}
mod embedded_duplex{
 use super::*;
 #[path=GUARD_SOURCE]pub(crate) mod preparation_native_guard;
 mod remote_owner_close{use super::*;RECEIVER_BODY}
 pub(crate) mod runtime_host{
  use super::*;
  #[path=JOURNAL_SOURCE]pub(crate) mod preparation_diagnostic;
  pub(crate) mod peer_lifecycle{use super::*;PEER_BODY}
  pub(crate) fn observed(h:&Host,observer:&dyn Fn(NativeGuard,NativeReason))->Result<Value,String>{peer_lifecycle::fresh_setup_eligibility_observed(h,observer)}
  pub(crate) fn cleanup_only(h:&Host,replay:&RetainedCleanupReplaySnapshot,observer:&dyn Fn(NativeGuard,NativeReason))->Result<(),String>{let trace=NativeTrace::new(observer);trace.run(||h.cleanup.fresh_setup_quiescence_observed(replay,&trace))}
  impl Cleanup{CLEANUP_BODY}
 }
}
fn host()->Host{
 Host{capability:Capability{generation:57,binding:Binding{generation:65,record_sha256:"b".repeat(64)},live:true},
 peer_lifecycle:Mutex::new(Some(Life::default())),activation_sender:Mutex::new(Some(Sender::default())),
 server:Mutex::new(Some(Server{replay:OwnerDispatchReplaySnapshot::default()})),activation_server:Mutex::new(Some(Server{replay:ProductActivationReplaySnapshot::default()})),
 cleanup_server:Mutex::new(Some(Server{replay:RetainedCleanupReplaySnapshot::default()})),owner_effect_attempted:AtomicBool::new(false),
 local_public_key:[1;32],callbacks:Callbacks{public_key:[1;32],key_failed:false,key_id:"key"},restored_owner_replay:false,
 cleanup:Cleanup{callbacks:Callbacks{public_key:[1;32],key_failed:false,key_id:"key"},serial:Mutex::new(Some(())),state:Mutex::new(PreparedState::default()),local:"local".into(),generation:57,valid:true},
 provider:Mutex::new(Some(Provider{evidence:Ok(json!({"media_pending_action":null}).to_string())})),config_sha256:"c".repeat(64)}
}
fn actor()->Actor{Actor{app_generation:65,app_record:"b".repeat(64),native_generation:57,config:"c".repeat(64)}}
fn response(schema:&str,id:&str,generation:u64,stop:bool)->Response{
 let r=AndroidMediaOwnerReadback{schema_id:ANDROID_MEDIA_READBACK_SCHEMA.into(),owner_kind:MediaStreamOwnerKind::Sink,
 provider_kind:"meta_spatial_sdk_packed_stereo_sink".into(),executor_generation:generation,provider_state_revision:if stop{2}else{1},provider_handle_id:"handle".into(),resource_id:"resource".into(),authority_epoch_id:"epoch".into(),client_id:"client".into(),lease_id:"lease".into(),receipt_id:id.into(),operation:if stop{MediaStreamPlatformOperation::Stop}else{MediaStreamPlatformOperation::Start},action_kind:if stop{MediaStreamOwnerActionKind::Stop}else{MediaStreamOwnerActionKind::ArmReceiver},observed_state:if stop{"stopped".into()}else{"receiver_armed".into()}};
 let v=Verified{schema_id:VERIFIED_OWNER_EFFECT_SCHEMA.into(),readback_sha256:"sha256:".to_owned()+&"a".repeat(64),receipt_id:r.receipt_id.clone(),executor_generation:generation,provider_state_revision:r.provider_state_revision,observed_state:r.observed_state.clone(),provider_handle_id:r.provider_handle_id.clone(),terminal:stop};
 Response{schema_id:schema.into(),dispatch_id:id.into(),executor_peer_id:"local".into(),signer_key_id:"key".into(),status:OwnerDispatchStatus::Completed,failure:None,request_sha256:"sha256:".to_owned()+&"a".repeat(64),signature_base64:"good".into(),effect:Some(Effect{readback_json:serde_json::to_string(&r).unwrap(),readback:r,verified:v})}
}
fn activation()->ActivationResponse{ActivationResponse{schema_id:PRODUCT_ACTIVATION_RESPONSE_SCHEMA.into(),activation_id:"activation".into(),signer_key_id:"key".into(),status:OwnerDispatchStatus::Completed,failure:None,readback:Some(ActivationReadback{activated:true,activation_id:"activation".into()}),signature_base64:"good".into()}}
fn signed_history(h:&Host){let mut s=h.server.lock().unwrap();let r=&mut s.as_mut().unwrap().replay;r.terminal.insert("start".into(),response("dispatch","start",57,false));r.terminal.insert("stop".into(),response("dispatch","stop",57,true));}
fn cleanup_response(h:&Host,mutate:impl FnOnce(&mut Response)){
 let mut r=response("cleanup","stop",57,true);mutate(&mut r);
 h.cleanup_server.lock().unwrap().as_mut().unwrap().replay.terminal.insert("stop".into(),Terminal{response_bytes:serde_json::to_vec(&r).unwrap(),request_sha256:r.request_sha256});
}
fn run(h:&Host)->(Result<Value,String>,Journal){
 let journal=Arc::new(Mutex::new(Journal::default()));let attempt=Attempt::begin(journal.clone(),actor());attempt.bind_original(&"c".repeat(32));
 let result=attempt.call(Stage::NativeEligibility,Reason::CallFailed,||embedded_duplex::runtime_host::observed(h,&|g,r|attempt.native_failure(g,r)));
 attempt.finish(result.is_ok());let state=journal.lock().unwrap().clone();(result,state)
}
fn expect(h:&Host,guard:NativeGuard,error:&str){
 let (result,journal)=run(h);assert_eq!(result,Err(error.into()));let v=journal.read(&actor(),Some(&"c".repeat(32))).unwrap().unwrap();
 assert_eq!(v.native_guard,Some(guard));assert_eq!(v.stage,Some(Stage::NativeEligibility));assert_eq!(v.reason,Some(Reason::CallFailed));assert!(!v.arena_installed);assert!(v.physical.is_none());assert!(!format!("{v:?}").contains(error));
}
#[test]fn healthy_actual_production_chain_and_signed_history_preserve_success(){
 let h=host();let(r,j)=run(&h);assert!(r.is_ok());assert_eq!(j.read(&actor(),None).unwrap().unwrap().state,State::Succeeded);
 signed_history(&h);assert!(run(&h).0.is_ok());cleanup_response(&h,|_|{});assert!(run(&h).0.is_ok());
}
#[test]fn actual_local_slots_and_current_lifecycle_fail_before_history(){
 let mut h=host();h.capability.live=false;h.restored_owner_replay=true;expect(&h,NativeGuard::Capability,"modeled capability stale");
 let h=host();*h.peer_lifecycle.lock().unwrap()=None;expect(&h,NativeGuard::LocalSlot,"fresh local lifecycle checked out");
 let h=host();h.peer_lifecycle.lock().unwrap().as_mut().unwrap().renewal_pending=true;expect(&h,NativeGuard::LocalState,"fresh setup has pending renewal or whole close");
 let h=host();h.owner_effect_attempted.store(true,Ordering::SeqCst);expect(&h,NativeGuard::LocalState,"fresh setup requires actual NeverStarted or earned normal FullyStopped");
 let h=host();*h.activation_sender.lock().unwrap()=None;expect(&h,NativeGuard::ActivationSlot,"fresh activation sender checked out");
 let h=host();h.activation_sender.lock().unwrap().as_mut().unwrap().pending=Some(Flight{response:None,client_id:Value::Null});expect(&h,NativeGuard::ActivationFlight,"fresh activation request pending or terminal history differs");
 let h=host();*h.server.lock().unwrap()=None;expect(&h,NativeGuard::DispatchSlot,"fresh dispatch slot checked out");
 let h=host();*h.activation_server.lock().unwrap()=None;expect(&h,NativeGuard::ActivationReplaySlot,"fresh activation slot checked out");
 let h=host();*h.cleanup_server.lock().unwrap()=None;expect(&h,NativeGuard::CleanupReplaySlot,"fresh cleanup slot checked out");
 let h=host();h.server.lock().unwrap().as_mut().unwrap().replay.pending_request_sha256.insert("id".into(),"hash".into());expect(&h,NativeGuard::InboundPending,"fresh setup retained inbound original pending");
}
#[test]fn actual_receiver_nested_predicates_and_signature_paths_are_distinct(){
 let mut h=host();h.restored_owner_replay=true;expect(&h,NativeGuard::ReceiverRestoredHistory,"fresh incoming history unresolved or restored");
 let h=host();h.activation_server.lock().unwrap().as_mut().unwrap().replay.terminal.insert("activation".into(),activation());expect(&h,NativeGuard::ReceiverActivationWithoutHistory,"fresh activation without incoming history");
 let mut h=host();signed_history(&h);h.local_public_key=[0;32];expect(&h,NativeGuard::ReceiverKey,"remote-owner closure key");
 let mut h=host();signed_history(&h);h.callbacks.key_id="";expect(&h,NativeGuard::ReceiverCompleteHistory,"remote-owner closure requires current complete receiver history");
 let cases:[(NativeGuard,&str,fn(&mut Response));9]=[
 (NativeGuard::ReceiverResponseIdentity,"remote-owner terminal response identity",|r|r.failure=Some("modeled".into())),
 (NativeGuard::ReceiverSignature,"remote-owner terminal signature",|r|r.signature_base64="bad".into()),
 (NativeGuard::ReceiverEffect,"remote-owner terminal effect absent",|r|r.effect=None),
 (NativeGuard::ReceiverReadbackDecode,"remote-owner readback JSON",|r|r.effect.as_mut().unwrap().readback_json="bad".into()),
 (NativeGuard::ReceiverReadbackBinding,"remote-owner authenticated registry readback differs",|r|r.effect.as_mut().unwrap().readback.executor_generation=54),
 (NativeGuard::ReceiverState,"remote-owner receiver state is not a completed Start or Stop",|r|r.effect.as_mut().unwrap().verified.terminal=true),
 (NativeGuard::ReceiverRevision,"remote-owner duplicate provider revision",|r|{let e=r.effect.as_mut().unwrap();e.readback.provider_state_revision=1;e.verified.provider_state_revision=1;e.readback_json=serde_json::to_string(&e.readback).unwrap();}),
 (NativeGuard::ReceiverLatestStop,"remote-owner latest receiver state is not stopped after Start",|r|{let e=r.effect.as_mut().unwrap();e.readback.provider_handle_id="other".into();e.verified.provider_handle_id="other".into();e.readback_json=serde_json::to_string(&e.readback).unwrap();}),
 (NativeGuard::ReceiverCompleteHistory,"remote-owner closure requires current complete receiver history",|_|{}),
 ];
 for(g,e,m)in cases{if g==NativeGuard::ReceiverCompleteHistory{continue;}let h=host();signed_history(&h);let id=if matches!(g,NativeGuard::ReceiverRevision|NativeGuard::ReceiverLatestStop){"stop"}else{"start"};m(h.server.lock().unwrap().as_mut().unwrap().replay.terminal.get_mut(id).unwrap());expect(&h,g,e);}
 for(g,bad)in[(NativeGuard::ReceiverActivationIdentity,false),(NativeGuard::ReceiverActivationSignature,true)]{let h=host();signed_history(&h);let mut a=activation();if bad{a.signature_base64="bad".into()}else{a.failure=Some("modeled".into())}h.activation_server.lock().unwrap().as_mut().unwrap().replay.terminal.insert("activation".into(),a);expect(&h,g,if bad{"remote-owner activation signature"}else{"remote-owner activation history is incomplete"});}
}
#[test]fn actual_cleanup_nested_old_generation_and_other_guards_retain_original_error(){
 let mut h=host();h.cleanup.valid=false;expect(&h,NativeGuard::CleanupState,"modeled cleanup poison");
 let mut h=host();h.cleanup.callbacks.key_failed=true;expect(&h,NativeGuard::CleanupPublicKey,"modeled Java key callback");
 let h=host();*h.cleanup.serial.lock().unwrap()=None;expect(&h,NativeGuard::CleanupSerial,"fresh cleanup mutation checked out");
 let h=host();h.cleanup.state.lock().unwrap().originals.insert("id".into(),Original{effect:None});expect(&h,NativeGuard::CleanupForwardOriginal,"fresh cleanup forward original unresolved");
 let h=host();h.cleanup.state.lock().unwrap().prepared.insert("id".into(),Value::Null);expect(&h,NativeGuard::CleanupPreparedTerminal,"fresh cleanup Prepare lacks terminal original");
 let h=host();h.cleanup_server.lock().unwrap().as_mut().unwrap().replay.invalid=true;expect(&h,NativeGuard::CleanupReplayShape,"modeled replay malformed");
 let h=host();let mut replay=RetainedCleanupReplaySnapshot::default();replay.pending_request_sha256.insert("old".into(),"hash".into());let seen=std::cell::Cell::new(None);let r=embedded_duplex::runtime_host::cleanup_only(&h,&replay,&|g,r|seen.set(Some((g,r))));assert_eq!(r,Err("fresh cleanup original pending".into()));assert_eq!(seen.get(),Some((NativeGuard::CleanupPending,NativeReason::PredicateRejected)));
 let h=host();cleanup_response(&h,|_|{});h.cleanup_server.lock().unwrap().as_mut().unwrap().replay.terminal.get_mut("stop").unwrap().response_bytes=b"bad".to_vec();expect(&h,NativeGuard::CleanupResponseDecode,"fresh cleanup response decode");
 let cases:[(NativeGuard,fn(&mut Response));6]=[
 (NativeGuard::CleanupResponseIdentity,|r|r.signer_key_id="other".into()),
 (NativeGuard::CleanupResponseCompleted,|r|r.failure=Some("modeled".into())),
 (NativeGuard::CleanupResponseTerminal,|r|r.effect.as_mut().unwrap().verified.terminal=false),
 (NativeGuard::CleanupResponseGeneration,|r|r.effect.as_mut().unwrap().readback.executor_generation=54),
 (NativeGuard::CleanupResponseStop,|r|r.effect.as_mut().unwrap().readback.operation=MediaStreamPlatformOperation::Start),
 (NativeGuard::CleanupResponseSignature,|r|r.signature_base64="bad".into())];
 for(g,m)in cases{let h=host();cleanup_response(&h,m);expect(&h,g,if g==NativeGuard::CleanupResponseSignature{"fresh cleanup terminal signature"}else{"fresh cleanup terminal original not completed"});}
}
#[test]fn actual_provider_failures_and_busy_slots_do_not_install_arena(){
 let h=host();*h.provider.lock().unwrap()=None;expect(&h,NativeGuard::ProviderSlot,"fresh provider checked out");
 let h=host();h.provider.lock().unwrap().as_mut().unwrap().evidence=Err("private provider exception".into());expect(&h,NativeGuard::ProviderEvidence,"fresh broker evidence unavailable");
 let h=host();h.provider.lock().unwrap().as_mut().unwrap().evidence=Ok("bad".into());let(r,j)=run(&h);assert!(r.is_err());assert_eq!(j.read(&actor(),None).unwrap().unwrap().native_guard,Some(NativeGuard::ProviderDecode));
 let h=host();h.provider.lock().unwrap().as_mut().unwrap().evidence=Ok(json!({"media_pending_action":"active"}).to_string());expect(&h,NativeGuard::BrokerPending,"fresh broker effect pending");
 let h=host();let _held=h.peer_lifecycle.lock().unwrap();expect(&h,NativeGuard::LocalSlot,"fresh local lifecycle busy");
 let h=host();let _held=h.activation_sender.lock().unwrap();expect(&h,NativeGuard::ActivationSlot,"fresh activation sender busy");
 let h=host();let _held=h.cleanup.state.lock().unwrap();expect(&h,NativeGuard::CleanupStateLock,"fresh cleanup retained state busy");
}
#[test]fn production_cursor_first_failure_is_immutable_and_observer_is_after_guard_unwind(){
 let mut h=host();h.restored_owner_replay=true;let journal=Arc::new(Mutex::new(Journal::default()));let a=Attempt::begin(journal.clone(),actor());let calls=std::cell::Cell::new(0);
 let result=embedded_duplex::runtime_host::observed(&h,&|g,r|{assert!(h.peer_lifecycle.try_lock().is_ok());assert!(h.activation_sender.try_lock().is_ok());calls.set(calls.get()+1);a.native_failure(g,r)});assert_eq!(result,Err("fresh incoming history unresolved or restored".into()));assert_eq!(calls.get(),1);
 a.native_failure(NativeGuard::ReceiverRestoredHistory,NativeReason::PredicateRejected);a.native_failure(NativeGuard::BrokerPending,NativeReason::CallFailed);a.finish(true);
 let v=journal.lock().unwrap().read(&actor(),None).unwrap().unwrap();assert_eq!(v.native_guard,Some(NativeGuard::ReceiverRestoredHistory));assert_eq!(v.native_reason,Some(NativeReason::PredicateRejected));
}
