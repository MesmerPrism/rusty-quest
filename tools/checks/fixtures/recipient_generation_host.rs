// Exact production selection/log bodies; enclosing Native/registry owners are models.
#![allow(dead_code)]
use std::sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicUsize, Ordering}};
mod owner_failure { use crate::rusty_quest_broker_authority; PRODUCTION_OWNER_FAILURE }
use owner_failure::Stage as OwnerFailureStage;
#[derive(Clone)] struct Binding { generation:u64, record_sha256:String }
struct Capability { generation:u64, binding:Binding }
impl Capability { fn require_live(&self)->Result<(),String>{Ok(())} }
#[derive(Clone)] struct Host { capability:Arc<Capability>, local_peer_id:String, remote_peer_id:String, route_configuration_sha256:String, config_sha256:String }
struct Request { native_executor_generation:u64, app_process_generation:u64, app_record_sha256:String, config_sha256:String }
struct Decoder { executor_generation:u64 }
struct Arena { request:Request, completed:AtomicBool, remote_decoder:Mutex<Option<Decoder>> }
#[derive(Default)] struct ProcessState { host:Option<Host>, prepared_peer_start:Option<Arc<Arena>>, initializing:bool, lease_integrity_failed:bool }
fn process()->&'static Mutex<ProcessState>{static P:OnceLock<Mutex<ProcessState>>=OnceLock::new();P.get_or_init(||Mutex::new(ProcessState::default()))}
PRODUCTION_HELD
PRODUCTION_TARGET_GENERATION

mod rusty_quest_media_stream {
    #[derive(Clone,Copy,PartialEq)] pub enum MediaStreamPlatformOperation { Start, Stop }
    #[derive(Clone,Copy)] pub enum MediaStreamOwnerActionKind { Start, Stop, Cleanup }
    pub struct MediaStreamPlatformAction { pub operation:MediaStreamPlatformOperation, pub action_id:String }
    pub struct MediaStreamOwnerAction { pub action_kind:MediaStreamOwnerActionKind }
}
use rusty_quest_media_stream::MediaStreamPlatformOperation;
trait AndroidMediaOwnerExecutor {
    fn executor_generation_for(&self,action:&rusty_quest_media_stream::MediaStreamPlatformAction,owner:&rusty_quest_media_stream::MediaStreamOwnerAction)->Result<u64,String>;
}
struct Ordinary { calls:AtomicUsize, absent:AtomicBool }
impl AndroidMediaOwnerExecutor for Ordinary {
    fn executor_generation_for(&self,_:&rusty_quest_media_stream::MediaStreamPlatformAction,_:&rusty_quest_media_stream::MediaStreamOwnerAction)->Result<u64,String>{
        self.calls.fetch_add(1,Ordering::SeqCst);
        if self.absent.load(Ordering::SeqCst){Err("authenticated target executor generation unavailable".into())}else{Ok(5)}
    }
}
struct Cleanup { generation:u64, requester:Mutex<Option<()>>, failure:Mutex<Option<&'static str>> }
struct Executor { cleanup:Cleanup, ordinary:Ordinary }
impl AndroidMediaOwnerExecutor for Executor { PRODUCTION_RETAINED_SELECTION }

mod rusty_quest_broker_authority {
    use sha2::{Digest,Sha256};
    PRODUCTION_HASHERS
}
mod camera_hwb_marker {
    use std::sync::Mutex;
    pub static LOG:Mutex<Option<String>>=Mutex::new(None);
    pub fn log_camera_hwb_marker(value:String){*LOG.lock().unwrap()=Some(value);}
}
struct DiagnosticHost { owner_dispatch_failure:Mutex<Option<&'static str>> }
fn reject(host:&DiagnosticHost,error:String)->String { PRODUCTION_REJECTION }

#[test] fn recipient_current_held_actor_selects_remote_five_and_rejects_stale_or_absent_binding() {
    let host=Host{capability:Arc::new(Capability{generation:7,binding:Binding{generation:3,record_sha256:"record".into()}}),local_peer_id:"source".into(),remote_peer_id:"target".into(),route_configuration_sha256:"route".into(),config_sha256:"config".into()};
    let arena=Arc::new(Arena{request:Request{native_executor_generation:7,app_process_generation:3,app_record_sha256:"record".into(),config_sha256:"config".into()},completed:AtomicBool::new(true),remote_decoder:Mutex::new(Some(Decoder{executor_generation:5}))});
    *process().lock().unwrap()=ProcessState{host:Some(host),prepared_peer_start:Some(arena.clone()),..Default::default()};
    assert_eq!(target_executor_generation("source","target",7,"route"),Ok(5));
    assert_eq!(target_executor_generation("source","foreign",7,"route"),Err("prepared target actor binding differs".into()));
    assert_eq!(target_executor_generation("source","target",8,"route"),Err("prepared target actor binding differs".into()));
    process().lock().unwrap().host.as_mut().unwrap().config_sha256="new actor".into();
    assert_eq!(target_executor_generation("source","target",7,"route"),Err("actual prepared held actor differs".into()));
    process().lock().unwrap().host.as_mut().unwrap().config_sha256="config".into();
    *arena.remote_decoder.lock().unwrap()=None;
    assert_eq!(target_executor_generation("source","target",7,"route"),Err("authenticated target executor generation unavailable".into()));
}
#[test] fn recipient_retained_source_carrier_survives_retired_metadata_and_ordinary_compensation_does_not_bypass_selection() {
    use rusty_quest_media_stream::*;
    let executor=Executor{cleanup:Cleanup{generation:7,requester:Mutex::new(None),failure:Mutex::new(None)},ordinary:Ordinary{calls:AtomicUsize::new(0),absent:AtomicBool::new(false)}};
    let mut action=MediaStreamPlatformAction{operation:MediaStreamPlatformOperation::Start,action_id:"start".into()};
    let mut owner=MediaStreamOwnerAction{action_kind:MediaStreamOwnerActionKind::Start};
    assert_eq!(executor.executor_generation_for(&action,&owner),Ok(5));
    action.action_id="start.abort".into();owner.action_kind=MediaStreamOwnerActionKind::Stop;
    assert_eq!(executor.executor_generation_for(&action,&owner),Ok(5));
    *executor.cleanup.requester.lock().unwrap()=Some(());
    executor.ordinary.absent.store(true,Ordering::SeqCst); // Prepared metadata has retired.
    assert_eq!(executor.executor_generation_for(&action,&owner),Ok(7));
    action.operation=MediaStreamPlatformOperation::Stop;
    assert_eq!(executor.executor_generation_for(&action,&owner),Ok(7));
    assert_eq!(executor.ordinary.calls.load(Ordering::SeqCst),2);
    *executor.cleanup.requester.lock().unwrap()=None;
    assert!(executor.executor_generation_for(&action,&owner).is_err());
    assert_eq!(*executor.cleanup.failure.lock().unwrap(),Some("ORDINARY_TARGET_GENERATION_PROOF"));
    let first=camera_hwb_marker::LOG.lock().unwrap().clone().unwrap();
    let error="authenticated target executor generation unavailable";
    assert!(first.starts_with("status=owner-effect-failed stage=ORDINARY_TARGET_GENERATION_PROOF "));
    assert!(first.contains(&format!("error_sha256={} error_bytes={}",rusty_quest_broker_authority::packaged_json_sha256(error),error.len())));
    assert!(!first.contains(error));
    assert!(owner_failure::observe::<()>(Err("later private cleanup failure".into()),&executor.cleanup.failure,OwnerFailureStage::SourceReadback).is_err());
    assert_eq!(*camera_hwb_marker::LOG.lock().unwrap(),Some(first));
}
#[test] fn recipient_rejection_logs_exact_error_fingerprint_without_private_text_or_new_authority() {
    for (error,stage,hash,bytes) in [
        ("authenticated owner executor differs","TARGET_EXECUTOR_GENERATION","TARGET_ERROR_SHA",36usize),
        ("private error é","FRAME_OR_DISPATCH_BOUNDARY","PRIVATE_ERROR_SHA",16usize),
    ] {
        let host=DiagnosticHost{owner_dispatch_failure:Mutex::new(None)};
        assert_eq!(reject(&host,error.into()),"owner dispatch rejected");
        assert_eq!(*host.owner_dispatch_failure.lock().unwrap(),Some(stage));
        let log=camera_hwb_marker::LOG.lock().unwrap().clone().unwrap();
        assert!(log.contains(&format!("stage={stage}")));
        assert!(log.contains(&format!("error_sha256={hash} error_bytes={bytes}")));
        assert!(!log.contains(error));assert!(log.len()<250);
    }
}
