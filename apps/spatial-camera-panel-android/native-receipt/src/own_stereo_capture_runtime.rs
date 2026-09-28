//! One process-owned Own/Peer source set and capture admission, shared by actual renderer.
use std::sync::{Arc,Mutex,OnceLock,atomic::{AtomicBool,Ordering}};
use crate::{own_packed_pool::PackedLease,own_packed_pool_policy::HoldLimits,
 stereo_source_payload::{OwnPeerSourceSet,install_own_capture_adapter},
 spatial_video_projection_native_stream::SpatialVideoProjectionFrame};
include!(concat!(env!("OUT_DIR"),"/own_capture_build.rs"));
include!(concat!(env!("OUT_DIR"),"/spatial_source_bank_build.rs"));
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
pub(crate) fn capture_route_selected()->bool {CAPTURE_ROUTE_SELECTED.load(Ordering::Acquire) || (OWN_CAPTURE_PROVIDER_SELECTED && SOURCE_BANK_SHADER_COMPILED && OWN_CAPTURE_HOLD_LIMITS.is_some())}
pub(crate) fn capture_claimed()->bool {CAPTURE_CLAIMED.load(Ordering::Acquire)}
pub(crate) unsafe fn claim_capture_actor()->Result<(),String> {
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
pub(crate) fn own_image_fresh()->bool {
 let Some(now)=crate::own_packed_pool::monotonic_ns() else{return false;};
 let Some(epoch)=ACTIVE_EPOCH.lock().ok().and_then(|v|*v) else{return false;};
 let Ok(snapshot)=shared_sources().snapshot(now,3_000_000_000) else{return false;};
 snapshot[0].as_ref().is_some_and(|frame|frame.identity.epoch==epoch)
}

pub(crate) fn prepare_capture_bootstrap()->Result<bool,String> {
 if !OWN_CAPTURE_PROVIDER_SELECTED {return Ok(false);}
 if !SOURCE_BANK_SHADER_COMPILED {return Err("selected Own/Peer bank shader did not compile".into());}
 let (slots,bytes,gpu_uses)=OWN_CAPTURE_HOLD_LIMITS.ok_or("selected Own capture hold contract absent")?;
 if !capture_claimed() {configure_capture_limits(HoldLimits{slots,bytes,gpu_uses})?;}
 if !capture_configured() {return Err("Own capture runtime configuration unavailable".into());}Ok(true)
}

pub(crate) fn own_capture_provider_requested()->bool {OWN_CAPTURE_PROVIDER_SELECTED}
