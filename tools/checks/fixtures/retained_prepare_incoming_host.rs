// Exact extracted native incoming methods; Java durability and clock are modeled.
use super::*;
use crate as rusty_quest_broker_authority;
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
use std::collections::BTreeMap;
use serde::{Serialize, Deserialize};
use serde_json::json;
use ed25519_dalek::{Signature, VerifyingKey, SigningKey, Signer};
use rusty_quest_media_stream_android::*;
use rusty_quest_media_stream::MediaStreamPlatformOperation;
#[path = "OWNER_FAILURE_SOURCE"] mod owner_failure;
use owner_failure::Stage as OwnerFailureStage;
const PREPARE_MAGIC: &[u8] = b"RQCP1\0";
const DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v1\0";
const ABORT_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_abort_prepare.v2\0";
const REMOTE_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v3\0";
const REMOTE_ABORT_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_abort_prepare.v4\0";
PRODUCTION_TYPES
PRODUCTION_FREE_FUNCTIONS
fn fresh() -> Result<String,String> { Ok("00112233445566778899aabbccddeeff".into()) }
fn safe_decode(_: serde_json::Error) -> String { "modeled type decode".into() }
struct AuthorityClock(Arc<std::sync::atomic::AtomicU64>);
impl AuthorityClock { fn now_ms(&self)->Result<u64,String>{Ok(self.0.load(Ordering::SeqCst))} }
struct Callbacks { key: SigningKey, id: String, saved: Mutex<String> }
impl Callbacks {
 fn key_id(&self)->&str{&self.id}
 fn sign(&self,b:&[u8])->Result<[u8;64],String>{Ok(self.key.sign(b).to_bytes())}
 fn persist_cleanup_preparations(&self,b:&str)->Result<(),String>{*self.saved.lock().unwrap()=b.into();Ok(())}
}
struct Checkout;
impl Checkout { fn take(_:Arc<Mutex<Option<()>>>)->Result<Self,String>{Ok(Self)} }
struct Cleanup {
 authority: crate::QuestEmbeddedDuplexAuthority, callbacks: Callbacks, clock: AuthorityClock,
 local:String,remote:String,remote_key_id:String,remote_key:[u8;32],generation:u64,
 poisoned:Arc<AtomicBool>,serial:Arc<Mutex<Option<()>>>,state:Arc<Mutex<PreparedState>>,
 failure:Arc<Mutex<Option<&'static str>>>,
}
impl Cleanup { PRODUCTION_CLEANUP_METHODS }
impl RetainedCleanupAuthoritySource for Cleanup { PRODUCTION_CURRENT_SOURCE }
pub(super) fn exercise(authority: crate::QuestEmbeddedDuplexAuthority, source_authority: crate::QuestEmbeddedDuplexAuthority,
 projection: &RetainedCleanupAuthorityProjection, original_projection: &OwnerDispatchAuthorityProjection,
 original_ticket: &AndroidMediaExecutionTicket, source_ticket: &AndroidMediaExecutionTicket,
 source_key: &SigningKey, source_key_id: &str, target_key: &SigningKey, target_key_id: &str, now:u64) {
 let clock=Arc::new(std::sync::atomic::AtomicU64::new(now));
 let mut state=PreparedState::default();
 state.originals.insert(key(original_ticket), Original{ticket:original_ticket.clone(),effect:None,source_authority:Some(original_projection.clone())});
 let receiver=Cleanup{authority,callbacks:Callbacks{key:target_key.clone(),id:target_key_id.into(),saved:Mutex::new(String::new())},
  clock:AuthorityClock(clock.clone()),local:projection.executor_peer_id.clone(),remote:projection.authority_peer_id.clone(),
  remote_key_id:source_key_id.into(),remote_key:source_key.verifying_key().to_bytes(),generation:9,
  poisoned:Arc::new(AtomicBool::new(false)),serial:Arc::new(Mutex::new(Some(()))),state:Arc::new(Mutex::new(state)),failure:Arc::new(Mutex::new(None))};
 let mut request=Prepare{schema_id:"rusty.quest.android.media.retained_cleanup_prepare_request.v3".into(),
  dispatch_id:"fixture.actual-two-store.prepare".into(),source_ticket:source_ticket.clone(),requester_id:projection.requester_id.clone(),
  requester_lease_id:projection.requester_runtime_lease_id.clone(),route_grant_id:projection.route_grant_id.clone(),sequence:1,
  issued_at_ms:now,signer_key_id:source_key_id.into(),signature_base64:String::new(),authority:Some(projection.clone())};
 fn frame(mut request:Prepare, key:&SigningKey)->Vec<u8>{request.signature_base64.clear();let mut b=prepare_domain(&request.schema_id).unwrap().to_vec();b.extend(encode(&request).unwrap());request.signature_base64=encode_signature_base64(&key.sign(&b).to_bytes());let mut bytes=PREPARE_MAGIC.to_vec();bytes.extend(encode(&request).unwrap());bytes}
 let signed=frame(request.clone(),source_key);
 let returned=receiver.prepare_frame(&signed).expect("actual native incoming prepare joins separate receiving store");
 let prepared:RetainedCleanupPreparedStop=serde_json::from_slice(&returned).unwrap();
 verify_retained_cleanup_prepared(&prepared,source_ticket,&receiver.local,target_key_id,&target_key.verifying_key().to_bytes()).unwrap();
 assert_eq!(returned,receiver.prepare_frame(&signed).expect("exact replay retains response"));
 let commit=RetainedCleanupDispatchRequest{schema_id:RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA.into(),dispatch_id:prepared.dispatch_id.clone(),sequence:1,
  issued_at_ms:now,target_peer_id:receiver.local.clone(),cleanup:RetainedCleanupExecutionTicket{schema_id:RETAINED_CLEANUP_TICKET_SCHEMA.into(),authority:prepared.authority.clone(),
   target:prepared.target_ticket.clone(),mode:AndroidMediaExecutionMode::Execute,owner_effect_sha256:String::new()},signer_key_id:source_key_id.into(),signature_base64:String::new()};
 let live=receiver.current_source(&commit,now+1).expect("actual commit source revalidates signed prepare/current enrollment");
 assert_eq!(live.projection,prepared.authority);assert_eq!(live.expected_target,prepared.target_ticket);
 let original_state=receiver.state.lock().unwrap().clone();
 // Signed damaged projections are rejected even under the enrolled source key.
 for field in 0..6 {
  request.dispatch_id=format!("fixture.damaged.{field}");let mut changed=projection.clone();
  match field{0=>changed.target_client_id="client.foreign".into(),1=>changed.provider_epoch_id="epoch.foreign".into(),2=>changed.executor_peer_id=receiver.remote.clone(),
   3=>changed.requester_id="revoker.untrusted".into(),4=>changed.expires_at_ms=now,_=>changed.signed_topology_sha256=format!("sha256:{}","f".repeat(64))}
  request.authority=Some(changed);assert!(receiver.prepare_frame(&frame(request.clone(),source_key)).is_err());
 }
 request.authority=Some(projection.clone());request.dispatch_id="fixture.wrong-signature".into();assert!(receiver.prepare_frame(&frame(request.clone(),target_key)).is_err());
 request.dispatch_id=prepared.dispatch_id.clone();request.sequence=2;assert!(receiver.prepare_frame(&frame(request.clone(),source_key)).is_err());
 assert_eq!(receiver.state.lock().unwrap().prepared.len(),1);
 receiver.state.lock().unwrap().originals.get_mut(&key(original_ticket)).unwrap().source_authority=None;
 request.dispatch_id="fixture.no-start-provenance".into();assert!(receiver.prepare_frame(&frame(request.clone(),source_key)).is_err());
 *receiver.state.lock().unwrap()=original_state;
 receiver.state.lock().unwrap().originals.get_mut(&key(original_ticket)).unwrap().ticket.authority_epoch_id="epoch.changed".into();
 assert!(receiver.current_source(&commit,now+1).is_err());
 receiver.state.lock().unwrap().originals.get_mut(&key(original_ticket)).unwrap().ticket=original_ticket.clone();
 clock.store(projection.expires_at_ms,Ordering::SeqCst);assert!(receiver.current_source(&commit,projection.expires_at_ms).is_err());
 // New signed v4 abort is an original-Start rollback, never a new Start.
 clock.store(now,Ordering::SeqCst);
 let mut abort_ticket=original_ticket.clone();abort_ticket.action_id=format!("{}.abort",original_ticket.action_id);
 abort_ticket.action_kind=rusty_quest_media_stream::MediaStreamOwnerActionKind::Stop;
 assert!(is_retained_start_abort_ticket(&abort_ticket));
 request.schema_id="rusty.quest.android.media.retained_abort_prepare_request.v4".into();
 request.source_ticket=abort_ticket.clone();request.authority=Some(projection.clone());request.dispatch_id="fixture.remote.abort".into();request.sequence=1;
 let abort_bytes=frame(request.clone(),source_key);
 let abort_returned=receiver.prepare_frame(&abort_bytes).expect("signed v4 abort joins distinct receiver authority");
 let abort_prepared:RetainedCleanupPreparedStop=serde_json::from_slice(&abort_returned).unwrap();
 assert_eq!(abort_prepared.schema_id,"rusty.quest.android.media.retained_abort_prepared_stop.v2");
 verify_retained_cleanup_prepared(&abort_prepared,&abort_ticket,&receiver.local,target_key_id,&target_key.verifying_key().to_bytes()).unwrap();
 assert_eq!(abort_returned,receiver.prepare_frame(&abort_bytes).unwrap());
 let mut abort_commit=commit.clone();abort_commit.dispatch_id=abort_prepared.dispatch_id.clone();abort_commit.cleanup.target=abort_prepared.target_ticket.clone();
 assert_eq!(receiver.current_source(&abort_commit,now+1).unwrap().projection,*projection);
 request.dispatch_id="fixture.remote.wrong-abort".into();request.source_ticket.action_id="different-original.abort".into();
 assert!(receiver.prepare_frame(&frame(request.clone(),source_key)).is_err());
 request.source_ticket=source_ticket.clone();
 // Old persisted records decode without new provenance; the new route denies them.
 let old=serde_json::json!({"ticket":original_ticket,"effect":null});let old:Original=serde_json::from_value(old).unwrap();assert!(old.source_authority.is_none());
 // Legacy v1 retains its original local-store requirement, with no new fields.
 let mut legacy_state=PreparedState::default();legacy_state.originals.insert(key(original_ticket),old);
 let legacy=Cleanup{authority:source_authority,callbacks:Callbacks{key:target_key.clone(),id:target_key_id.into(),saved:Mutex::new(String::new())},
  clock:AuthorityClock(Arc::new(std::sync::atomic::AtomicU64::new(now))),local:receiver.local.clone(),remote:receiver.remote.clone(),
  remote_key_id:source_key_id.into(),remote_key:source_key.verifying_key().to_bytes(),generation:9,
  poisoned:Arc::new(AtomicBool::new(false)),serial:Arc::new(Mutex::new(Some(()))),state:Arc::new(Mutex::new(legacy_state)),failure:Arc::new(Mutex::new(None))};
 request.schema_id="rusty.quest.android.media.retained_cleanup_prepare_request.v1".into();request.authority=None;request.dispatch_id="fixture.legacy".into();request.sequence=1;
 let returned=legacy.prepare_frame(&frame(request.clone(),source_key)).expect("legacy local-store prepare unchanged");
 let old_prepared:RetainedCleanupPreparedStop=serde_json::from_slice(&returned).unwrap();assert_eq!(old_prepared.authority,*projection);
 assert!(legacy.state.lock().unwrap().remote_requests.is_empty());
 request.authority=Some(projection.clone());request.dispatch_id="fixture.legacy-with-new-field".into();assert!(legacy.prepare_frame(&frame(request.clone(),source_key)).is_err());
 request.schema_id="rusty.quest.android.media.retained_abort_prepare_request.v2".into();request.authority=None;
 request.source_ticket=abort_ticket.clone();request.dispatch_id="fixture.legacy.abort".into();
 let legacy_abort=legacy.prepare_frame(&frame(request.clone(),source_key)).expect("legacy v2 abort strict local-store path unchanged");
 let legacy_abort:RetainedCleanupPreparedStop=serde_json::from_slice(&legacy_abort).unwrap();
 assert_eq!(legacy_abort.schema_id,"rusty.quest.android.media.retained_abort_prepared_stop.v2");
 verify_retained_cleanup_prepared(&legacy_abort,&abort_ticket,&legacy.local,target_key_id,&target_key.verifying_key().to_bytes()).unwrap();
 assert!(legacy.state.lock().unwrap().remote_requests.is_empty());
 println!("EXACT_NATIVE_ABORT: signed v4 distinct-store prepare/replay/current-source; legacy v2 local compatibility; wrong original rejected");
 println!("EXACT_NATIVE_INCOMING: prepare/replay/commit and signed negative joins; Java persistence/clock/checkout modeled, no target effect");
}
