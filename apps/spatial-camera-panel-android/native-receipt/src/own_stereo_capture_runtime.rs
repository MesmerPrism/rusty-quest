//! One process-owned Own/Peer source set and capture admission, shared by actual renderer.
use std::sync::{Arc,Mutex,OnceLock,atomic::{AtomicBool,Ordering}};
use crate::{own_packed_pool::PackedLease,own_packed_pool_policy::HoldLimits,
 stereo_source_payload::{OwnPeerSourceSet,install_own_capture_adapter},
 spatial_video_projection_native_stream::SpatialVideoProjectionFrame};
include!(concat!(env!("OUT_DIR"),"/own_capture_build.rs"));
include!(concat!(env!("OUT_DIR"),"/spatial_source_bank_build.rs"));
#[path="local_rollback_policy.rs"] mod local_rollback_policy;
static LOCAL_ROLLBACK_SELECTED:AtomicBool=AtomicBool::new(false);
static CAPTURE_ROUTE_SELECTED:AtomicBool=AtomicBool::new(false);
static CAPTURE_CLAIMED:AtomicBool=AtomicBool::new(false);
static ACTIVE_EPOCH:Mutex<Option<crate::stereo_input_set::SourceEpoch>>=Mutex::new(None);
static LIMITS:Mutex<Option<HoldLimits>>=Mutex::new(None);
pub(crate) fn shared_sources()->&'static Arc<OwnPeerSourceSet<PackedLease,SpatialVideoProjectionFrame>> {
 static SOURCES:OnceLock<Arc<OwnPeerSourceSet<PackedLease,SpatialVideoProjectionFrame>>>=OnceLock::new();SOURCES.get_or_init(||Arc::new(OwnPeerSourceSet::default()))
}
// Native renderer installs the actual accepted runtime hold contract; Java supplies none.
pub(crate) fn configure_capture_limits(limits:HoldLimits)->Result<(),String> {
 if limits.slots==0 || limits.bytes==0 || limits.gpu_uses==0 {return Err("missing admitted Own hold contract".into());}
 let mut config=LIMITS.lock().map_err(|_|"capture configuration poisoned")?;
 if CAPTURE_CLAIMED.load(Ordering::Acquire) {return if *config==Some(limits) {Ok(())} else {Err("capture owner already claimed with a different hold contract".into())};}*config=Some(limits);Ok(())
}
pub(crate) fn capture_route_selected()->bool {!LOCAL_ROLLBACK_SELECTED.load(Ordering::Acquire) && (CAPTURE_ROUTE_SELECTED.load(Ordering::Acquire) || (OWN_CAPTURE_PROVIDER_SELECTED && SOURCE_BANK_SHADER_COMPILED && OWN_CAPTURE_HOLD_LIMITS.is_some()))}
// Called only with the closed native host lock held. No request/timeout releases an owner.
pub(crate) fn select_local_after_terminal(host_closed:bool)->Result<(),String> {
 let epoch=ACTIVE_EPOCH.lock().map_err(|_|"capture epoch unavailable")?;
 let facts=local_rollback_policy::CleanupFacts{host_closed,own_unclaimed:!capture_claimed(),epoch_retired:epoch.is_none(),
  renderer_terminal:crate::spatial_stereo_qualification::physical_cleanup_terminal(),
  banks_inactive:!crate::spatial_public_multistack_runtime::source_banks_enabled()};
 if !local_rollback_policy::allow_local(facts){return Err("Local rollback physical cleanup remains Pending".into());}
 LOCAL_ROLLBACK_SELECTED.store(true,Ordering::Release);Ok(())
}
pub(crate) fn capture_claimed()->bool {CAPTURE_CLAIMED.load(Ordering::Acquire)}
pub(crate) unsafe fn claim_capture_actor()->Result<(),String> {
 if LOCAL_ROLLBACK_SELECTED.load(Ordering::Acquire){return Err("Own route disabled until a fresh app process".into());}
 // Actual EGL owner, process claim and supplier contract precede any platform allocation.
 let _=crate::packed_ahb_gl_primitives::Extensions::load_current()?;
 let limits={let config=LIMITS.lock().map_err(|_|"capture configuration poisoned")?;
 let limits=config.ok_or("renderer Own hold contract unavailable")?;
 CAPTURE_CLAIMED.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).map_err(|_|"another capture owner remains physical Pending")?;limits};
 if let Err(e)=install_own_capture_adapter(shared_sources().clone(),limits) {CAPTURE_CLAIMED.store(false,Ordering::Release);return Err(e);}CAPTURE_ROUTE_SELECTED.store(true,Ordering::Release);Ok(())
}
// Only exact capture registry physical retirement can call this; not stop request/timeout.
pub(crate) fn release_capture_claim_after_terminal() {CAPTURE_CLAIMED.store(false,Ordering::Release);}

pub(crate) fn capture_configured()->bool {LIMITS.lock().ok().is_some_and(|v|v.is_some())}

pub(crate) fn bind_active_epoch(epoch:crate::stereo_input_set::SourceEpoch)->Result<(),String> { *ACTIVE_EPOCH.lock().map_err(|_|"active capture epoch poisoned")?=Some(epoch);Ok(()) }
pub(crate) fn retire_active_epoch(epoch:crate::stereo_input_set::SourceEpoch)->Result<(),String> {let mut active=ACTIVE_EPOCH.lock().map_err(|_|"active capture epoch poisoned")?;if *active==Some(epoch){*active=None;}Ok(())}
pub(crate) fn own_image_fresh()->bool {own_image_fresh_observed().is_ok()}
pub(crate) fn own_image_fresh_observed()->Result<crate::stereo_input_set::SourceEpoch,&'static str> {
 let active=ACTIVE_EPOCH.lock().map_err(|_|"concurrent Own native admission ACTIVE_STATE")?;
 let epoch=(*active).ok_or("concurrent Own native admission ACTIVE_EPOCH")?;
 let frame=shared_sources().own_current(crate::own_packed_pool::monotonic_ns,3_000_000_000)?;
 if frame.identity.epoch!=epoch {return Err("concurrent Own native admission FRAME_EPOCH");}
 Ok(epoch)
}

pub(crate) fn prepare_capture_bootstrap()->Result<bool,String> {
 if LOCAL_ROLLBACK_SELECTED.load(Ordering::Acquire) || !OWN_CAPTURE_PROVIDER_SELECTED {return Ok(false);}
 if !SOURCE_BANK_SHADER_COMPILED {return Err("selected Own/Peer bank shader did not compile".into());}
 let (slots,bytes,gpu_uses)=OWN_CAPTURE_HOLD_LIMITS.ok_or("selected Own capture hold contract absent")?;
 if !capture_claimed() {configure_capture_limits(HoldLimits{slots,bytes,gpu_uses})?;}
 if !capture_configured() {return Err("Own capture runtime configuration unavailable".into());}Ok(true)
}

pub(crate) fn own_capture_provider_requested()->bool {OWN_CAPTURE_PROVIDER_SELECTED}

// Selection rechecks the already admitted native epoch while the shared renderer owner is held.
// No route mutation or replacement capture actor is authorized by this proof.
pub(crate) fn concurrent_peer_epoch_is_current(proof:[i64;5])->bool {
 if !capture_claimed() || !capture_configured() || !crate::camera_hwb_probe::local_camera_acquisition_quiescent() {return false;}
 let Ok(epoch)=own_image_fresh_observed() else {return false;};
 let Ok(process)=crate::own_packed_pool_jni::process_generation() else {return false;};
 proof[3]>0 && proof[4]>0 && epoch.process_generation==process
  && i64::try_from(epoch.process_generation).ok()==Some(proof[3])
  && i64::try_from(epoch.source_generation).ok()==Some(proof[4]) && capture_claimed()
}

// Read-only proof from the actual claimed capture actor and current retained frame epoch.
pub(crate) fn concurrent_peer_admission(route:i64,challenge:i64,surface:i64)->Option<[i64;5]> {
 concurrent_peer_admission_observed(route,challenge,surface).ok()
}
// Fixed owner-local categories only; no raw handle, ticket, path or exception text.
pub(crate) fn concurrent_peer_admission_observed(route:i64,challenge:i64,surface:i64)->Result<[i64;5],&'static str> {
 if route<=0 || challenge<=0 || surface<=0 {return Err("concurrent Own native admission INPUT");}
 if !capture_claimed() || !capture_configured() {return Err("concurrent Own native admission CAPTURE");}
 let _first_epoch=own_image_fresh_observed()?;
 if !crate::camera_hwb_probe::local_camera_acquisition_quiescent() {return Err("concurrent Own native admission LOCAL");}
 let receipt=crate::peer_projection_runtime::read_source(route);
 if receipt.words[1]!=route || receipt.words[5]!=challenge || receipt.words[6]!=surface {return Err("concurrent Own native admission CARRIER");}
 let active=ACTIVE_EPOCH.lock().map_err(|_|"concurrent Own native admission ACTIVE_STATE")?;
 let epoch=(*active).ok_or("concurrent Own native admission ACTIVE_EPOCH")?;
 let frame=shared_sources().own_current(crate::own_packed_pool::monotonic_ns,3_000_000_000)?;
 if frame.identity.epoch!=epoch {return Err("concurrent Own native admission FRAME_EPOCH");}
 if !capture_claimed() {return Err("concurrent Own native admission SUPERSEDED");}
 if epoch.process_generation!=crate::own_packed_pool_jni::process_generation().map_err(|_|"concurrent Own native admission PROCESS_EPOCH")? || epoch.source_generation==0 {return Err("concurrent Own native admission PROCESS_EPOCH");}
 Ok([route,challenge,surface,i64::try_from(epoch.process_generation).map_err(|_|"concurrent Own native admission BOUNDS")?,i64::try_from(epoch.source_generation).map_err(|_|"concurrent Own native admission BOUNDS")?])
}
