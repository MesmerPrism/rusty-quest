//! Physical encoder input lifetime: one offered CPU lease and one worker-owned input.
use std::{cell::RefCell,collections::HashMap,os::fd::OwnedFd,sync::{Mutex,OnceLock}};
use crate::{own_packed_pool::{serial,PackedLease},packed_ahb_gl_primitives as gl};
struct Offer {lease:PackedLease,imported:bool,reservation:u64}
fn offers()->&'static Mutex<HashMap<u64,Offer>> {static O:OnceLock<Mutex<HashMap<u64,Offer>>>=OnceLock::new();O.get_or_init(||Mutex::new(HashMap::new()))}
pub(crate) fn offer(lease:PackedLease,accepted_gpu_capacity:usize)->Result<Option<u64>,String> {
 let mut o=offers().lock().map_err(|_|"encoder offer registry poisoned")?;let pool=lease.contents().version.pool_generation;
 let this_pool:Vec<_>=o.values().filter(|v|v.lease.contents().version.pool_generation==pool).collect();
 if this_pool.len()>=accepted_gpu_capacity || this_pool.iter().any(|v|!v.imported) {return Ok(None);}
 let id=serial()?;let Some(reservation)=crate::own_packed_gpu_holds::reserve_encoder(&lease)? else{return Ok(None);};o.insert(id,Offer{lease,imported:false,reservation});Ok(Some(id))
}
pub(crate) fn release_unsubmitted(id:u64)->Result<(),String> {let mut o=offers().lock().map_err(|_|"encoder registry poisoned")?;if o.get(&id).is_some_and(|v|!v.imported) {if let Some(item)=o.remove(&id){crate::own_packed_gpu_holds::release_encoder(item.reservation)?;}}Ok(())}
pub(crate) fn discard_pool_offers(pool:u64)->Result<(),String> {let mut o=offers().lock().map_err(|_|"encoder registry poisoned")?;let ids:Vec<_>=o.iter().filter(|(_,v)|!v.imported && v.lease.contents().version.pool_generation==pool).map(|(id,_)|*id).collect();for id in ids {if let Some(item)=o.remove(&id){crate::own_packed_gpu_holds::release_encoder(item.reservation)?;}}Ok(())}
struct Input {ext:gl::Extensions,allocation:Option<gl::GlAllocation>,fence:Option<OwnedFd>,failed:Option<gl::FenceExportError>,quarantined:bool}
impl Drop for Input {fn drop(&mut self) {if let Some(a)=self.allocation.take(){std::mem::forget(a);}if let Some(f)=self.fence.take(){std::mem::forget(f);}if let Some(f)=self.failed.take(){std::mem::forget(f);}}}
thread_local! {static INPUT:RefCell<HashMap<u64,Input>>=RefCell::new(HashMap::new());}
pub(crate) unsafe fn import(id:u64)->Result<Vec<i64>,String> {
 INPUT.with(|i| {let mut i=i.try_borrow_mut().map_err(|_|"reentrant encoder import")?;if !i.is_empty(){return Err("encoder physical input pending".into());}
 let ext=gl::Extensions::load_current()?;
 let lease={let mut o=offers().lock().map_err(|_|"encoder registry poisoned")?;let pool=o.get(&id).ok_or("expired encoder lease")?.lease.contents().version.pool_generation; if o.values().any(|v|v.imported && v.lease.contents().version.pool_generation==pool) {return Err("pool encoder physical input pending".into());} let item=o.get_mut(&id).ok_or("expired encoder lease")?;if item.imported {return Err("encoder token replay".into());}item.imported=true;item.lease.clone()};
 // Keep opaque content token owned globally BEFORE any import/draw callback or worker unwind.
 let descriptor=lease.contents().allocation.descriptor();
 match gl::import_current(&ext,lease.contents().allocation.clone()) {
  Ok(a)=> {let words=vec![a.texture as i64,descriptor.width as i64,descriptor.height as i64];i.insert(id,Input{ext,allocation:Some(a),fence:None,failed:None,quarantined:false});Ok(words)},
  Err(err)=> {let message=err.message;i.insert(id,Input{ext,allocation:err.retained,fence:None,failed:None,quarantined:true});Err(message)}
 }
 })
}
pub(crate) unsafe fn finish(id:u64)->Result<(),String> {INPUT.with(|i| {let mut i=i.try_borrow_mut().map_err(|_|"reentrant encoder finish")?;let input=i.get_mut(&id).ok_or("input not owned by this worker")?;
 if input.quarantined || input.fence.is_some(){return Err("encoder input quarantined/already submitted".into());}
 match gl::export_producer_fence(&input.ext) {Ok(fd)=>{input.fence=Some(fd);Ok(())},Err(e)=>{let message=e.message.clone();input.failed=Some(e);input.quarantined=true;Err(message)}}
})}
pub(crate) unsafe fn poll_retired(id:u64)->Result<bool,String> {INPUT.with(|i| {let mut i=i.try_borrow_mut().map_err(|_|"reentrant encoder poll")?;let input=i.get_mut(&id).ok_or("input not owned by this worker")?;
 input.ext.require_current()?;if input.quarantined {return Ok(false);}let Some(fd)=&input.fence else{return Ok(false);};
 match gl::poll_producer_fence(fd) {Ok(false)=>return Ok(false),Err(e)=>{input.quarantined=true;return Err(e);},Ok(true)=>{}}
 if let Some(a)=input.allocation.take(){if let Err((e,a))=gl::destroy_idle_on_producer_thread(&input.ext,a) {input.allocation=Some(a);input.quarantined=true;return Err(e);}}
 // The positive fence observation and completed GL teardown make this exact
 // sync fd safe to close. Input::drop deliberately leaks uncertain fences.
 drop(input.fence.take());
 i.remove(&id);if let Some(item)=offers().lock().map_err(|_|"encoder registry poisoned")?.remove(&id){crate::own_packed_gpu_holds::release_encoder(item.reservation)?;}Ok(true)
})}
