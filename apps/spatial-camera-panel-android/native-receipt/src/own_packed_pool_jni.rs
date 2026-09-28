//! JNI transport to the capture actor; it cannot install an owner or runtime hold contract.
use std::{cell::RefCell,collections::HashMap,sync::OnceLock,io::Read,rc::Rc};
use jni::{JNIEnv,objects::JClass,sys::{jint,jlong,jlongArray}};
use crate::{own_packed_pool_policy::*,own_packed_pool::*,stereo_input_set::{RetainedStereoFrame,SourceEpoch}};
type Publisher=Rc<dyn Fn(RetainedStereoFrame<PackedLease>)->Result<(),String>>;
struct Registry {limits:Option<HoldLimits>,pools:HashMap<u64,OwnPackedPool>,publisher:Option<Publisher>,bind:Option<Rc<dyn Fn(SourceEpoch)->Result<(),String>>>,retire:Option<Rc<dyn Fn(SourceEpoch)->Result<(),String>>>}
thread_local! {static REGISTRY:RefCell<Registry>=RefCell::new(Registry {limits:None,pools:HashMap::new(),publisher:None,bind:None,retire:None});}
pub(crate) fn install_capture_publisher(publish:Publisher)->Result<(),String> {REGISTRY.with(|r|{let mut r=r.try_borrow_mut().map_err(|_|"reentrant publisher install")?;if !r.pools.is_empty(){return Err("physical capture pool already exists".into());}r.publisher=Some(publish);Ok(())})}
pub(crate) fn install_capture_epoch_callbacks(bind:Rc<dyn Fn(SourceEpoch)->Result<(),String>>,retire:Rc<dyn Fn(SourceEpoch)->Result<(),String>>)->Result<(),String> {REGISTRY.with(|r|{let mut r=r.try_borrow_mut().map_err(|_|"reentrant epoch install")?;if !r.pools.is_empty(){return Err("physical pools exist".into());}r.bind=Some(bind);r.retire=Some(retire);Ok(())})}
pub(crate) fn process_generation()->Result<u64,String> { static PROCESS:OnceLock<Result<u64,String>>=OnceLock::new();PROCESS.get_or_init(|| {
 let mut bytes=[0u8;8];std::fs::File::open("/dev/urandom").and_then(|mut f|f.read_exact(&mut bytes)).map_err(|e|e.to_string())?;
 let value=u64::from_ne_bytes(bytes)&i64::MAX as u64; if value==0 {Err("process epoch unavailable".into())}else{Ok(value)}
}).clone() }
// Called ONLY by admitted native capture-owner setup on its actual EGL actor.
// Replacement is refused while prior physical pools remain retained.
pub(crate) fn install_capture_owner(limits:HoldLimits)->Result<(),String> {REGISTRY.with(|r| {let mut r=r.try_borrow_mut().map_err(|_|"reentrant capture owner")?;if !r.pools.is_empty(){return Err("old capture pools pending".into());}if limits.slots==0 || limits.gpu_uses==0 || limits.bytes==0 {return Err("missing admitted holds".into());}r.limits=Some(limits);Ok(())})}
fn pool<T>(id:jlong,action:impl FnOnce(&mut OwnPackedPool)->Result<T,String>)->Result<T,String> {if id<=0 {return Err("invalid pool identity".into());} REGISTRY.with(|r| {let mut r=r.try_borrow_mut().map_err(|_|"reentrant pool operation")?;action(r.pools.get_mut(&(id as u64)).ok_or("pool not owned by this capture actor")?)})}
fn words(env:&mut JNIEnv<'_>,result:Result<Vec<i64>,String>)->jlongArray {match result {Ok(v)=>match env.new_long_array(v.len() as i32) {Ok(a)=>if env.set_long_array_region(&a,0,&v).is_ok(){a.into_raw()}else{std::ptr::null_mut()},Err(_)=>std::ptr::null_mut()},Err(e)=>{let _=env.throw_new("java/lang/IllegalStateException",e);std::ptr::null_mut()}}}
fn status(env:&mut JNIEnv<'_>,result:Result<(),String>) {if let Err(e)=result {let _=env.throw_new("java/lang/IllegalStateException",e);}}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeCreate(mut env:JNIEnv<'_>,_class:JClass<'_>,width:jint,height:jint)->jlong {
 let admitted=unsafe{crate::own_stereo_capture_runtime::claim_capture_actor()};
 if let Err(e)=admitted {let _=env.throw_new("java/lang/IllegalStateException",e);return 0;}
 let result:Result<i64,String>=REGISTRY.with(|r| {let mut r=r.try_borrow_mut().map_err(|_|"reentrant create")?;
 if width<=0 || height<=0 {return Err("invalid geometry".into());}if !r.pools.is_empty(){return Err("capture already owns physical pool".into());}
 let limits=r.limits.ok_or("capture owner/hold contract unavailable")?;if r.publisher.is_none() || r.bind.is_none() || r.retire.is_none(){return Err("Own retained publisher unavailable".into());}
 let p=unsafe {OwnPackedPool::create(width as u32,height as u32,limits,process_generation()?)?};let id=p.generation;let failure=p.initialization_error.clone();r.pools.insert(id,p);if let Some(e)=failure {Err(e)}else{Ok(id as i64)}});
 let result=result.and_then(|id| {let (bind,epoch)=REGISTRY.with(|r|{let r=r.borrow();(r.bind.as_ref().unwrap().clone(),r.pools[&(id as u64)].epoch)});if let Err(e)=bind(epoch) {let _=pool(id,|p|{p.stop();Ok(())});Err(e)}else{crate::own_stereo_capture_runtime::bind_active_epoch(epoch)?;Ok(id)}});
 match result {Ok(id)=>id,Err(e)=>{if REGISTRY.with(|r|r.borrow().pools.is_empty()){crate::own_stereo_capture_runtime::release_capture_claim_after_terminal();}let _=env.throw_new("java/lang/IllegalStateException",e);0}}
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeBegin(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong)->jlongArray {
 words(&mut env,pool(id,|p|unsafe {p.begin()}.map(|t|t.map(|t|vec![t.pool as i64,t.serial as i64,t.framebuffer as i64]).unwrap_or_default())))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeFinish(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong,serial:jlong,fbo:jint,pair:jlong,left:jlong,right:jlong,left_ns:jlong,right_ns:jlong) {
 let r=if serial<=0 || fbo<=0 || pair<=0 || left<0 || right<0 {Err("invalid native ticket/pair".into())}else {pool(id,|p|unsafe {p.finish(WriteTicket{pool:id as u64,serial:serial as u64,framebuffer:fbo as u32},PairIdentity{pair_id:pair as u64,left_frame:left as u64,right_frame:right as u64,left_ns,right_ns})})};status(&mut env,r)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeQuarantine(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong,serial:jlong,fbo:jint) {
 status(&mut env,pool(id,|p|p.quarantine(WriteTicket{pool:id as u64,serial:serial as u64,framebuffer:fbo as u32})))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeStop(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong) {status(&mut env,stop_actor(id))}
// Actor scheduler invokes this independently of Java finish. Callbacks receive actual retained
// contents AFTER dropping the registry borrow, and may reenter without deadlock.
pub(crate) fn poll_capture_actor(id:u64,publish:impl FnOnce(Vec<RetainedStereoFrame<PackedLease>>))->Result<(),String> {
 let frames=pool(id as i64,|p|{Ok(p.poll_ready())})?;publish(frames);Ok(())
}
pub(crate) unsafe fn close_capture_actor(id:u64)->Result<bool,String> {
 let closed=pool(id as i64,|p|Ok(p.close_idle()))?;if closed {REGISTRY.with(|r|{r.borrow_mut().pools.remove(&id);});crate::own_stereo_capture_runtime::release_capture_claim_after_terminal();}Ok(closed)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativePoll(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong)->jlongArray {
 let result=(||->Result<Vec<i64>,String> {
  let (publisher,capacity)=REGISTRY.with(|r|{let r=r.try_borrow().map_err(|_|"reentrant capture poll")?;Ok::<_,String>((r.publisher.clone().ok_or("Own publisher unavailable")?,r.limits.ok_or("hold limits unavailable")?.gpu_uses))})?;
  let frames=pool(id,|p|{let frames=p.poll_ready();Ok(if p.accepting(){frames}else{Vec::new()})})?;
  let mut encoder=None;
  for frame in frames {let lease=frame.lease.clone();let pair=lease.contents().pair;if let Err(e)=publisher(frame) {let serial=lease.contents().version.slot_serial;let _=pool(id,|p|{p.quarantine_content(serial);Ok(())});return Err(e);}encoder=Some((lease,pair));}
  if let Some((lease,pair))=encoder {if let Some(token)=crate::own_packed_encoder::offer(lease,capacity)? {return Ok(vec![token as i64,pair.pair_id as i64,pair.left_frame as i64,pair.right_frame as i64,pair.left_ns,pair.right_ns]);}}
  Ok(Vec::new())
 })();words(&mut env,result)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeRetire(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong)->jni::sys::jboolean {
 let result=(||->Result<bool,String>{stop_actor(id)?;crate::own_packed_encoder::discard_pool_offers(id as u64)?;unsafe{close_capture_actor(id as u64)}})();match result {Ok(b)=>b as u8,Err(e)=>{let _=env.throw_new("java/lang/IllegalStateException",e);0}}
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeImportEncoder(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong)->jlongArray {words(&mut env,unsafe{crate::own_packed_encoder::import(id as u64)})}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeFinishEncoder(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong) {status(&mut env,unsafe{crate::own_packed_encoder::finish(id as u64)})}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeReleaseEncoder(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong) {status(&mut env,crate::own_packed_encoder::release_unsubmitted(id as u64))}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativePollEncoder(mut env:JNIEnv<'_>,_class:JClass<'_>,id:jlong)->jni::sys::jboolean {match unsafe{crate::own_packed_encoder::poll_retired(id as u64)} {Ok(b)=>b as u8,Err(e)=>{let _=env.throw_new("java/lang/IllegalStateException",e);0}}}

fn stop_actor(id:i64)->Result<(),String> {
 let epoch=pool(id,|p|{p.stop();Ok(p.epoch)})?;
 let retire=REGISTRY.with(|r|r.try_borrow().map_err(|_|"reentrant retire".to_string())?.retire.clone().ok_or_else(||"epoch retirement callback unavailable".to_string()))?;
 retire(epoch)?;crate::own_stereo_capture_runtime::retire_active_epoch(epoch)?;crate::own_packed_encoder::discard_pool_offers(id as u64)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_captureConfigured(_env:JNIEnv<'_>,_class:JClass<'_>)->jni::sys::jboolean {crate::own_stereo_capture_runtime::capture_configured() as u8}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeOwnImageFresh(_env:JNIEnv<'_>,_class:JClass<'_>)->jni::sys::jboolean {crate::own_stereo_capture_runtime::own_image_fresh() as u8}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_captureRouteSelected(_env:JNIEnv<'_>,_class:JClass<'_>)->jni::sys::jboolean {crate::own_stereo_capture_runtime::capture_route_selected() as u8}
