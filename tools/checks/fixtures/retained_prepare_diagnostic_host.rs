// Executes the extracted production outgoing prepare block. Platform/authority seams
// are modeled; this is not a signed remote exchange or cleanup-completion test.
use std::{cell::Cell, collections::{BTreeMap, BTreeSet}, sync::Mutex};
#[path = "OWNER_FAILURE_SOURCE"] mod owner_failure;
use owner_failure::Stage as OwnerFailureStage;
#[derive(Clone, Copy, PartialEq)] enum Fault { None, Authority, Entropy, Encode, Sign, Exchange }
thread_local! { static FAULT: Cell<Fault> = const { Cell::new(Fault::None) }; }
fn fault() -> Fault { FAULT.with(Cell::get) }
fn fresh() -> Result<String,String> { if fault()==Fault::Entropy {Err("original entropy error".into())} else {Ok("modeled nonce".into())} }
fn encode<T>(_: &T) -> Result<Vec<u8>,String> { if fault()==Fault::Encode {Err("original encode error".into())} else {Ok(vec![])} }
fn prepare_domain(_: &str) -> Result<&'static [u8],String> {Ok(b"modeled domain")}
fn encode_signature_base64(_: &[u8]) -> String {"modeled signature".into()}
const PREPARE_MAGIC: &[u8]=b"RQCP1\0";
#[derive(Clone)] struct Ticket {capability:String}
fn is_retained_start_abort_ticket(_: &Ticket) -> bool {false}
#[derive(Clone,Copy)] enum AndroidMediaExecutionMode { Ordinary, CompensateUncertain }
struct Requester {id:String,lease:String}
#[allow(dead_code)] struct Prepare {
 schema_id:String,dispatch_id:String,source_ticket:Ticket,requester_id:String,
 requester_lease_id:String,route_grant_id:String,sequence:u64,issued_at_ms:u64,
 signer_key_id:String,signature_base64:String,authority:Option<()>,
}
struct PendingRemote {request:Prepare,prepared:Option<()>,commit:Option<()>,response:Option<Vec<u8>>}
struct Callbacks;
impl Callbacks {
 fn key_id(&self)->&str {"modeled-key"}
 fn sign(&self,_:&[u8])->Result<Vec<u8>,String> {if fault()==Fault::Sign {Err("original sign error".into())}else{Ok(vec![])}}
 fn exchange(&self,_:&[u8],_:usize)->Result<Vec<u8>,String> {if fault()==Fault::Exchange {Err("original exchange error".into())}else{Ok(vec![])}}
}
struct Cleanup {local:String,remote:String,callbacks:Callbacks}
impl Cleanup {fn projection(&self,_:&str,_:&str,_:&str,_:&str,_:&str,_:u64)->Result<(),String>{if fault()==Fault::Authority{Err("original authority error".into())}else{Ok(())}}}
struct Executor {cleanup:Cleanup,active:BTreeMap<String,String>,pending:BTreeMap<String,PendingRemote>,uncertain:BTreeSet<String>,sequence:u64}
impl Executor {
 fn new()->Self {Self{cleanup:Cleanup{local:"local".into(),remote:"remote".into(),callbacks:Callbacks},active:BTreeMap::new(),pending:BTreeMap::new(),uncertain:BTreeSet::new(),sequence:0}}
 fn execute_prepare(&mut self,peer_id:&String,slot:&Mutex<Option<&'static str>>)->Result<(),String> {
  let ticket=&Ticket{capability:"capability".into()};let requester=Requester{id:"requester".into(),lease:"lease".into()};
  let grant="grant".to_owned();let now:u64=1;let mode=AndroidMediaExecutionMode::Ordinary;
  let mut failure_stage=OwnerFailureStage::RemotePrepare;
  let result=(|| {
PRODUCTION_PREPARE_BLOCK
   let _=bytes;
   }
   Ok(())
  })();
  owner_failure::observe(result,slot,failure_stage)
 }
}
#[test] fn exact_production_prepare_failures_preserve_errors_and_first_stage() {
 for (fault_value,expected_error,expected_stage) in [
  (Fault::Authority,"original authority error","RETAINED_REMOTE_REQUESTER_AUTHORITY"),
  (Fault::Entropy,"original entropy error","RETAINED_REMOTE_ENTROPY"),
  (Fault::Encode,"original encode error","RETAINED_REMOTE_PREPARE_ENCODE"),
  (Fault::Sign,"original sign error","RETAINED_REMOTE_PREPARE_SIGN"),
  (Fault::Exchange,"original exchange error","RETAINED_REMOTE_PREPARE_EXCHANGE"),
 ] {
  FAULT.with(|v|v.set(fault_value));let mut e=Executor::new();let slot=Mutex::new(None);
  assert_eq!(e.execute_prepare(&"remote".into(),&slot),Err(expected_error.into()));
  assert_eq!(*slot.lock().unwrap(),Some(expected_stage));
  assert!(owner_failure::observe::<()>(Err("later outer error".into()),&slot,OwnerFailureStage::RemotePrepare).is_err());
  assert_eq!(*slot.lock().unwrap(),Some(expected_stage));
 }
}
#[test] fn exact_production_peer_binding_sequence_capacity_and_reused_exchange() {
 FAULT.with(|v|v.set(Fault::None));
 let mut e=Executor::new();let slot=Mutex::new(None);
 assert_eq!(e.execute_prepare(&"foreign".into(),&slot),Err("cleanup unbound peer".into()));
 assert_eq!(*slot.lock().unwrap(),Some("RETAINED_REMOTE_PEER_BIND"));
 let mut e=Executor::new();e.sequence=u64::MAX;let slot=Mutex::new(None);
 assert_eq!(e.execute_prepare(&"remote".into(),&slot),Err("cleanup sequence exhausted".into()));
 assert_eq!(*slot.lock().unwrap(),Some("RETAINED_REMOTE_SEQUENCE"));
 let mut e=Executor::new();for i in 0..256 {
  e.execute_prepare(&"remote".into(),&Mutex::new(None)).unwrap();
  let p=e.pending.pop_first().unwrap().1;e.active.clear();e.pending.insert(format!("other{i}"),p);
 }
 let slot=Mutex::new(None);
 assert_eq!(e.execute_prepare(&"remote".into(),&slot),Err("cleanup pending capacity".into()));
 assert_eq!(*slot.lock().unwrap(),Some("RETAINED_REMOTE_PENDING_CAPACITY"));
 let mut e=Executor::new();let slot=Mutex::new(None);
 e.execute_prepare(&"remote".into(),&slot).unwrap();assert_eq!(*slot.lock().unwrap(),None);
 FAULT.with(|v|v.set(Fault::Exchange));
 assert_eq!(e.execute_prepare(&"remote".into(),&slot),Err("original exchange error".into()));
 assert_eq!(*slot.lock().unwrap(),Some("RETAINED_REMOTE_PREPARE_EXCHANGE"));
 assert_eq!(e.pending.len(),1); // Genuine retry path retains its existing preparation.
}
