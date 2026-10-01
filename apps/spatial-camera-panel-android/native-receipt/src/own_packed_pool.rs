//! Capture actor-owned pool; physical operations never run under a shared registry lock.
use std::{os::fd::OwnedFd, sync::atomic::{AtomicU64, Ordering}};
use crate::{android_hardware_buffer::AndroidHardwareBufferHandle as Ahb,
 packed_ahb_gl_primitives as gl, pinned_packed_contents::*, stereo_input_set::*};
static NEXT: AtomicU64 = AtomicU64::new(1);
pub(crate) fn serial() -> Result<u64,String> { NEXT.fetch_update(Ordering::Relaxed,Ordering::Relaxed,|n| n.checked_add(1).filter(|v| *v <= i64::MAX as u64)).map_err(|_| "generation exhausted".into()) }
use crate::own_packed_pool_policy::*;
pub(crate) type PackedLease = PinnedPackedLease<Ahb,PairIdentity>;
enum State { Free, Writing(u64), Pending(u64,PairIdentity,OwnedFd), Ready(ContentOwner<Ahb,PairIdentity>), Quarantined, FailedProducer(OwnedFd) }
struct Slot { allocation:Option<gl::GlAllocation>, state:State }
pub(crate) struct OwnPackedPool { ext:gl::Extensions, slots:Vec<Slot>, limits:HoldLimits,
 pub generation:u64,pub epoch:SourceEpoch, accepting:bool, last_pair:u64,
 ready_order:ReadyObservationOrder, pub initialization_error:Option<String>,
 // Failed export physical handles stay retained, including failed sync destruction.
 failures:Vec<gl::FenceExportError> }
impl OwnPackedPool {
 pub(crate) unsafe fn create(w:u32,h:u32,limits:HoldLimits,process_generation:u64)->Result<Self,String> {
  limits.validate(w,h)?; if process_generation==0 {return Err("missing accepted process owner".into());}
  let ext=gl::Extensions::load_current()?; let generation=serial()?;
  let mut pool=Self {ext,slots:Vec::new(),limits,generation,epoch:SourceEpoch {process_generation,source_generation:serial()?},accepting:true,last_pair:0,ready_order:ReadyObservationOrder::default(),initialization_error:None,failures:Vec::new()};
  crate::own_packed_gpu_holds::install_pool(generation,pool.epoch,limits.gpu_uses)?;
  let mut allocated_bytes=0u64;
  for _ in 0..limits.slots { match gl::allocate_current(&pool.ext,w,h) { Ok(allocation)=>{
    let d=allocation.allocation.descriptor();
    let bytes=u64::from(d.stride).checked_mul(u64::from(d.height)).and_then(|v|v.checked_mul(4));
    let total=bytes.and_then(|v|allocated_bytes.checked_add(v));
    pool.slots.push(Slot{allocation:Some(allocation),state:State::Free});
    if total.is_none_or(|v|v>limits.bytes) {pool.accepting=false;pool.initialization_error=Some("actual allocation stride exceeds byte contract".into());let _=pool.close_idle();return Ok(pool);}
    allocated_bytes=total.unwrap();
   }, Err(err)=> {
    if let Some(allocation)=err.retained { pool.slots.push(Slot{allocation:Some(allocation),state:State::Quarantined}); }
    pool.accepting=false; // No partially constructed uncertain allocation may be released by unwinding.
    let _=pool.close_idle(); pool.initialization_error=Some(err.message); return Ok(pool);
  } } } Ok(pool)
 }
 pub(crate) unsafe fn begin(&mut self)->Result<Option<WriteTicket>,String> {
  self.ext.require_current()?; if !self.accepting {return Err("capture stopped".into());}
  for slot in &mut self.slots {
   let reusable=match &mut slot.state { State::Free=>true,State::Ready(owner)=>owner.unreferenced(),_=>false };
   if reusable {let ticket=WriteTicket {pool:self.generation,serial:serial()?,framebuffer:slot.allocation.as_ref().ok_or("allocation retired")?.framebuffer};slot.state=State::Writing(ticket.serial);return Ok(Some(ticket));}
  } Ok(None)
 }
 fn writing(&self,t:WriteTicket)->Result<usize,String> {
  if t.pool!=self.generation {return Err("stale pool ticket".into());}
  self.slots.iter().position(|s| matches!(s.state,State::Writing(n) if s.allocation.as_ref().is_some_and(|a|ticket_matches(t,self.generation,n,a.framebuffer)))).ok_or("stale slot ticket".into())
 }
 pub(crate) unsafe fn finish(&mut self,t:WriteTicket,pair:PairIdentity)->Result<(),String> {
  let i=self.writing(t)?;
  if let Err(e)=self.ext.require_current().and_then(|_|pair.validate()).and_then(|_| if pair.pair_id>self.last_pair {Ok(())} else {Err("replayed pair".into())}) { self.slots[i].state=State::Quarantined;return Err(e); }
  self.last_pair=pair.pair_id;
  match gl::export_producer_fence(&self.ext) { Ok(fd)=>{self.slots[i].state=State::Pending(t.serial,pair,fd);Ok(())},Err(e)=>{let message=e.message.clone();self.failures.push(e);self.slots[i].state=State::Quarantined;Err(message)} }
 }
 pub(crate) fn quarantine(&mut self,t:WriteTicket)->Result<(),String> {let i=self.writing(t)?;self.slots[i].state=State::Quarantined;Ok(())}
 // Returned retained frames are dispatched after actor access ends. No Java callback is readiness.
 pub(crate) fn poll_ready(&mut self)->Vec<RetainedStereoFrame<PackedLease>> {
  let mut frames=Vec::new();
  for slot in &mut self.slots {
   let observed=match &slot.state {State::Pending(n,p,fd)=>match gl::poll_producer_fence(fd) {Ok(true)=>Some((*n,*p)),Ok(false)=>None,Err(_)=>{let old=std::mem::replace(&mut slot.state,State::Quarantined);if let State::Pending(_,_,fd)=old {slot.state=State::FailedProducer(fd);}None}},_=>None};
   if let Some((n,pair))=observed {
    let Some(observed_at_ns)=monotonic_ns() else {slot.state=State::Quarantined;continue;};
    let allocation=slot.allocation.as_ref().expect("pending has allocation").allocation.clone();
    let mut owner=ContentOwner::from_observed_producer(PackedContents {version:ContentVersion {process_generation:self.epoch.process_generation,source_generation:self.epoch.source_generation,pool_generation:self.generation,slot_serial:n},allocation,pair});
    frames.push(RetainedStereoFrame { identity:StereoFrameIdentity {epoch:self.epoch,pair_sequence:pair.pair_id,left_timestamp_ns:pair.left_ns,right_timestamp_ns:pair.right_ns,packed_pts_ns:pair.pts(),calibration_revision:None}, lease:owner.retain(),observed_at_ns:observed_at_ns });
    slot.state=State::Ready(owner);
   }
  }
  frames.sort_by_key(|f|f.identity.pair_sequence);
  let batch_observed_ns=frames.iter().map(|f|f.observed_at_ns).max().unwrap_or(0);
  frames.into_iter().filter_map(|mut frame| {
   let ordered_ns=self.ready_order.admit(frame.identity.pair_sequence,batch_observed_ns)?;
   frame.observed_at_ns=ordered_ns;
   Some(frame)
  }).collect()
 }
 pub(crate) fn quarantine_content(&mut self,serial:u64) {for s in &mut self.slots {let matching=match &mut s.state {State::Ready(o)=>o.retain().contents().version.slot_serial==serial,_=>false};if matching{s.state=State::Quarantined;}}}
 pub(crate) fn accepting(&self)->bool {self.accepting}
 pub(crate) fn stop(&mut self) {self.accepting=false;}
 pub(crate) unsafe fn close_idle(&mut self)->bool {
  self.accepting=false; let _=self.poll_ready();
  if !self.failures.is_empty() {return false;}
  for slot in &mut self.slots {
   let idle=match &mut slot.state {State::Free=>true,State::Ready(o)=>o.unreferenced(),_=>false};
   if !idle {continue;} if let Some(a)=slot.allocation.take() {match gl::destroy_idle_on_producer_thread(&self.ext,a) {Ok(())=>slot.state=State::Free,Err((_,a))=>{slot.allocation=Some(a);slot.state=State::Quarantined;}}}
  } self.slots.iter().all(|s|s.allocation.is_none()) && matches!(crate::own_packed_gpu_holds::retire_pool_if_empty(self.generation),Ok(true))
 }
}
impl Drop for OwnPackedPool {fn drop(&mut self) {
 // A destructor is not observed retirement. Keep all physically uncertain handles/content alive.
 for slot in self.slots.drain(..) {if slot.allocation.is_some() {std::mem::forget(slot);}}
 for failure in self.failures.drain(..) {std::mem::forget(failure);}
}}
pub(crate) fn publish_own<L>(set:&mut StereoInputSet<L>,frame:RetainedStereoFrame<PackedLease>,wrap:impl FnOnce(PackedLease)->L)->Result<(),PublishError> {
 set.publish(StereoOrigin::OwnStereo,RetainedStereoFrame {identity:frame.identity,observed_at_ns:frame.observed_at_ns,lease:wrap(frame.lease)})
}

pub(crate) fn monotonic_ns()->Option<u64> {let mut ts:libc::timespec=unsafe{std::mem::zeroed()};if unsafe{libc::clock_gettime(libc::CLOCK_MONOTONIC,&mut ts)}!=0 || ts.tv_sec<0 || ts.tv_nsec<0 {return None;} (ts.tv_sec as u64).checked_mul(1_000_000_000)?.checked_add(ts.tv_nsec as u64)}
