//! Exact production completed-successor reservation function; process/authority
//! storage and Java callback leaves are explicit host models, not JNI execution.
use serde_json::{json, Value};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

struct Binding { generation:u64,record_sha256:String }
struct Capability { generation:u64,binding:Binding }
struct Host { local_peer_id:String,remote_peer_id:String,capability:Capability,config_sha256:String,pair_state:Arc<Mutex<PairState>>,callbacks:Callbacks }
struct Callbacks { calls:Mutex<Vec<(String,String)>>,physical:Value,fail:bool,change_old:bool,owner:Arc<Mutex<PairState>> }
impl Callbacks {
 fn stage_incoming_completed_fresh_setup_successor(&self,old:&str,new:&str)->Result<(),String>{
  assert!(process().try_lock().is_ok(),"Native process lock crossed Java callback");
  assert!(self.owner.try_lock().is_ok(),"Native pair lock crossed Java callback");
  self.calls.lock().unwrap().push((old.into(),new.into()));
  if self.fail{return Err("modeled actual Java admission refusal".into())}Ok(())
 }
 fn bind_fresh_setup_original(&self,_:&str)->Result<(),String>{Ok(())}
}
#[derive(Clone)] struct Attempt { id:String,completed:bool }
#[derive(Clone)] struct Reservation {id:String,session:String,original_binding:Value,initial_original_binding:Value,authority_or_signature_entered:bool,declined_before_dispatch:bool,failure:Option<String>,prime_pending:Option<Value>,prime_progress:Vec<Value>,prime_origin:Option<Value>,prime_joined:Vec<Value>}
struct Fresh {active:Option<Attempt>,reservation:Option<Reservation>,archive_reservation:Option<Reservation>,prepared_start:Option<String>}
struct PairState {fresh:Fresh}
struct Installed {pair_state:Arc<Mutex<PairState>>}
struct Process {closing:bool,initializing:bool,lease_integrity_failed:bool,host_leases:u64,fresh_setup_request:Option<String>,host:Option<Installed>}
static PROCESS:OnceLock<Mutex<Process>>=OnceLock::new();
fn process()->&'static Mutex<Process>{PROCESS.get_or_init(||Mutex::new(Process{closing:false,initializing:false,lease_integrity_failed:false,host_leases:1,fresh_setup_request:None,host:None}))}
fn state(host:&Host)->Result<MutexGuard<'_,PairState>,String>{host.pair_state.lock().map_err(|_|"modeled state poisoned".into())}
fn record_failure(_:&Host,_:&str,_:&str){} // Diagnostic side channel only.
enum IncomingPhysicalScope{Initial}
fn physical_observation(host:&Host,_:&str,_:&str,_:IncomingPhysicalScope)->Result<Value,String>{
 if host.callbacks.change_old{state(host)?.fresh.active=Some(Attempt{id:"ceremony.foreign".into(),completed:true});}
 Ok(host.callbacks.physical.clone())
}
mod peer_lifecycle {use super::*;pub fn fresh_setup_eligibility(_:&Host)->Result<Value,String>{Ok(json!({"local":{"state":"never_started"}}))}}
mod prepared {use super::*;fn require_uncommitted(_:&Host,_:&Value)->Result<(),String>{Ok(())}
PRODUCTION_PHYSICAL_VALIDATOR
}
fn validate_physical(host:&Host,native:Value,physical:Value)->Result<Value,String>{prepared::validate_physical(host,&native,&physical)?;Ok(physical)}
PRODUCTION_STAGE
fn reservation(id:&str)->Reservation{Reservation{id:id.into(),session:"session.old".into(),original_binding:json!({"kind":"incoming_fresh_setup","native_request_id":id}),initial_original_binding:Value::Null,authority_or_signature_entered:true,declined_before_dispatch:false,failure:None,prime_pending:None,prime_progress:vec![],prime_origin:None,prime_joined:vec![]}}
fn host(physical:Value)->Host{
 let pair_state=Arc::new(Mutex::new(PairState{fresh:Fresh{active:Some(Attempt{id:"ceremony.old".into(),completed:true}),reservation:Some(reservation("ceremony.old")),archive_reservation:None,prepared_start:None}}));
 *process().lock().unwrap()=Process{closing:false,initializing:false,lease_integrity_failed:false,host_leases:1,fresh_setup_request:None,host:Some(Installed{pair_state:pair_state.clone()})};
 Host{local_peer_id:"peer.b".into(),remote_peer_id:"peer.a".into(),capability:Capability{generation:9,binding:Binding{generation:7,record_sha256:"a".repeat(64)}},config_sha256:"b".repeat(64),pair_state:pair_state.clone(),callbacks:Callbacks{calls:Mutex::new(vec![]),physical,fail:false,change_old:false,owner:pair_state}}
}
fn untouched(h:&Host){let s=state(h).unwrap();assert!(s.fresh.archive_reservation.is_none());assert_eq!(s.fresh.reservation.as_ref().unwrap().id,"ceremony.old");assert!(s.fresh.prepared_start.is_none());}
fn main(){
 let args:Vec<String>=std::env::args().collect();let value:Value=serde_json::from_str(&std::fs::read_to_string(&args[1]).unwrap()).unwrap();let mut controls=0;
 let h=host(value.clone());assert!(stage_completed_successor(&h,"ceremony.next","session.next").unwrap());
 assert_eq!(*h.callbacks.calls.lock().unwrap(),vec![("ceremony.old".into(),"ceremony.next".into())]);
 {let s=state(&h).unwrap();assert_eq!(s.fresh.active.as_ref().unwrap().id,"ceremony.old");assert_eq!(s.fresh.archive_reservation.as_ref().unwrap().id,"ceremony.old");assert_eq!(s.fresh.reservation.as_ref().unwrap().id,"ceremony.next");assert_eq!(s.fresh.prepared_start.as_deref(),Some("ceremony.next"));}controls+=1;
 let h=host(value.clone());state(&h).unwrap().fresh.active.as_mut().unwrap().completed=false;assert!(!stage_completed_successor(&h,"ceremony.next","session.next").unwrap());assert!(h.callbacks.calls.lock().unwrap().is_empty());untouched(&h);controls+=1;
 let h=host(value.clone());assert!(stage_completed_successor(&h,"ceremony.old","session.next").is_err());assert!(h.callbacks.calls.lock().unwrap().is_empty());untouched(&h);controls+=1;
 let mut h=host(value.clone());h.callbacks.fail=true;assert!(stage_completed_successor(&h,"ceremony.next","session.next").is_err());untouched(&h);controls+=1;
 for fault in 0..4{let h=host(value.clone());{let mut p=process().lock().unwrap();match fault{0=>p.closing=true,1=>p.host_leases=2,2=>p.fresh_setup_request=Some("ceremony.other".into()),_=>p.lease_integrity_failed=true}}
 assert!(stage_completed_successor(&h,"ceremony.next","session.next").is_err());untouched(&h);controls+=1;}
 let mut h=host(value.clone());h.callbacks.change_old=true;assert!(stage_completed_successor(&h,"ceremony.next","session.next").is_err());untouched(&h);controls+=1;
 let mut bad=value;bad["native_executor_generation"]=json!(10);let h=host(bad);assert!(stage_completed_successor(&h,"ceremony.next","session.next").is_err());untouched(&h);controls+=1;
 println!("PASS {controls} exact Native completed-successor guard/reservation controls; Java/authority/process storage leaves modeled, no JNI/Manifold effects");
}
