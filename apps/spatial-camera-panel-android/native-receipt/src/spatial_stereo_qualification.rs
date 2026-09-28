//! Bounded app-owned source-set observations. No flag or submit call is pixel proof.
use std::sync::Mutex;
use crate::stereo_input_set::StereoFrameIdentity;
pub(crate) const WORD_COUNT:usize=160;
const HISTORY:usize=64;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub(crate) struct SourceFact {
    pub identity:StereoFrameIdentity,pub geometry_revision:Option<u64>,pub config_revision:u64,
    pub prefix:u32,pub observed_at_ns:u64,pub content_serial:u64,pub processing_codes:[u32;4],
}
#[derive(Clone,Copy,Debug)]
pub(crate) struct FrameFact {
    pub arm_generation:u64,pub ordinal:u64,pub surface:u64,pub revision:u64,pub policies:[u32;6],
    pub sources:[Option<SourceFact>;2],pub demanded:[usize;2],pub final_draw:bool,pub geometry_sampled:bool,
    pub available:[bool;2],
}
#[derive(Clone,Copy,Default)]
struct OriginStats {first:u64,last:u64,distinct:u64,max_gap:u64,max_age:u64,missing:u64,transitions:u64,
    removed_at:u64,removals:u64,last_identity:Option<StereoFrameIdentity>}
#[derive(Clone,Copy)]
struct PixelFact {frame:FrameFact,now:u64,count:u64,hash:u64,format:u32,width:u32,height:u32,flags:u32,contract:u32}
struct State {
    process:u64,challenge:[u64;2],generation:u64,armed:bool,carrier_live:bool,cleanup:u64,
    armed_at:u64,pending_since:u64,first_recorded:u64,last_recorded:u64,recorded:u64,entered:u64,
    first_gpu:u64,last_gpu:u64,gpu:u64,max_gap:u64,first_both:u64,last_both:u64,both:u64,max_both_gap:u64,
    own_after_peer:u64,peer_removed_at:u64,peer_removed_count:u64,origins:[OriginStats;2],
    pending:Option<FrameFact>,last:Option<FrameFact>,history:[Option<FrameFact>;HISTORY],next:usize,
    pixel:Option<PixelFact>,pixel_count:u64,pixel_unavailable:u64,
    foreign_enabled:bool,capability_mask:u64,sdk_session:u64,readback_requested:bool,
}
impl State {
    const fn empty()->Self {Self{process:0,challenge:[0;2],generation:0,armed:false,carrier_live:false,cleanup:0,
        armed_at:0,pending_since:0,first_recorded:0,last_recorded:0,recorded:0,entered:0,first_gpu:0,last_gpu:0,gpu:0,
        max_gap:0,first_both:0,last_both:0,both:0,max_both_gap:0,own_after_peer:0,peer_removed_at:0,peer_removed_count:0,
        origins:[OriginStats{first:0,last:0,distinct:0,max_gap:0,max_age:0,missing:0,transitions:0,removed_at:0,removals:0,last_identity:None};2],
        pending:None,last:None,history:[None;HISTORY],next:0,pixel:None,pixel_count:0,pixel_unavailable:0,foreign_enabled:false,capability_mask:0,sdk_session:0,readback_requested:false}}
    fn arm(&mut self,process:u64,challenge:[u64;2],now:u64)->u64 {
        let Some(generation)=self.generation.checked_add(1) else{return 0};
        let (live,foreign,capabilities,session,cleanup)=(self.carrier_live,self.foreign_enabled,self.capability_mask,self.sdk_session,self.cleanup);
        *self=Self::empty();self.generation=generation;self.process=process;
        self.foreign_enabled=foreign;self.capability_mask=capabilities;self.sdk_session=session;
        self.challenge=challenge;self.armed=true;self.carrier_live=live;self.armed_at=now;
        self.cleanup=if cleanup==3{3}else if live{1}else{cleanup};
        if live||cleanup==3{self.pending_since=now;}generation
    }
    fn record(&mut self,fact:FrameFact,now:u64) {
        if !self.armed||fact.arm_generation!=self.generation||fact.ordinal==0||fact.surface==0||now<self.armed_at||now<self.last_recorded{return;}
        if fact.sources.iter().flatten().any(|source|source.identity.epoch.process_generation!=self.process
            ||source.observed_at_ns>now||![1,3,4,6].contains(&source.prefix)){return;}
        if self.cleanup!=3{self.cleanup=1;}if self.pending_since==0{self.pending_since=now;}
        self.first_recorded=if self.recorded==0{now}else{self.first_recorded};self.last_recorded=now;
        self.recorded=self.recorded.saturating_add(1);self.pending=Some(fact);
    }
    fn retire(&mut self,ordinal:u64,surface:u64,now:u64) {
        let Some(fact)=self.pending else{return};
        if fact.ordinal!=ordinal||fact.surface!=surface||now<self.last_recorded||now<self.last_gpu{return;}
        if !self.armed||fact.arm_generation!=self.generation{self.pending=None;return;}
        self.pending=None;self.first_gpu=if self.gpu==0{now}else{self.first_gpu};
        if self.last_gpu!=0{self.max_gap=self.max_gap.max(now.saturating_sub(self.last_gpu));}
        self.last_gpu=now;self.gpu=self.gpu.saturating_add(1);
        for origin in 0..2 {
            let stats=&mut self.origins[origin];
            let Some(source)=fact.sources[origin] else{stats.missing=stats.missing.saturating_add(1);continue};
            stats.max_age=stats.max_age.max(now.saturating_sub(source.observed_at_ns));
            if !fact.final_draw{continue;}
            if stats.last_identity!=Some(source.identity) {
                if let Some(previous)=stats.last_identity {
                    if previous.epoch!=source.identity.epoch{stats.transitions=stats.transitions.saturating_add(1);}
                }
                if stats.last!=0{stats.max_gap=stats.max_gap.max(now.saturating_sub(stats.last));}
                if stats.distinct==0{stats.first=now;}
                stats.last=now;stats.distinct=stats.distinct.saturating_add(1);stats.last_identity=Some(source.identity);
            }
        }
        if fact.final_draw&&fact.sources.iter().all(Option::is_some) {
            if self.both==0{self.first_both=now;}else{self.max_both_gap=self.max_both_gap.max(now.saturating_sub(self.last_both));}
            self.last_both=now;self.both=self.both.saturating_add(1);
        }
        if fact.final_draw&&fact.sources[0].is_some()&&!fact.available[1]&&self.peer_removed_at!=0&&now>=self.peer_removed_at {
            self.own_after_peer=self.own_after_peer.saturating_add(1);
        }
        self.last=Some(fact);self.history[self.next]=Some(fact);self.next=(self.next+1)%HISTORY;
    }
    fn pixel(&mut self,ordinal:u64,surface:u64,now:u64,count:u64,hash:u64,format:u32,width:u32,height:u32,flags:u32,contract:u32) {
        if !self.armed{return;}
        let Some(frame)=self.history.iter().flatten().find(|f|f.arm_generation==self.generation&&f.ordinal==ordinal&&f.surface==surface&&f.final_draw).copied() else{return};
        self.pixel_count=self.pixel_count.saturating_add(1);
        self.pixel=Some(PixelFact{frame,now,count,hash,format,width,height,flags,contract});
    }
    fn words(&self,now:Option<u64>,selected:bool,pipeline:bool,capture_claimed:bool)->[i64;WORD_COUNT] {
        let mut w=[0i64;WORD_COUNT];
        let values=[1,WORD_COUNT as u64,self.process,self.challenge[0],self.challenge[1],self.generation,self.armed as u64,
            selected as u64,pipeline as u64,self.cleanup,self.first_recorded,self.last_recorded,self.recorded,self.entered,
            self.first_gpu,self.last_gpu,self.gpu,self.max_gap,self.pixel_count,self.pixel_unavailable,self.peer_removed_count,
            self.peer_removed_at,self.own_after_peer,self.origins[1].transitions,self.pending.map_or(0,|f|f.ordinal),
            self.last.map_or(0,|f|f.ordinal),self.last.map_or(0,|f|f.surface),self.last.map_or(0,|f|f.revision)];
        for (i,value) in values.into_iter().enumerate(){w[i]=value as i64;}
        if let Some(f)=self.last {
            for i in 0..6{w[28+i]=f.policies[i] as i64;}
            let origin=f.policies[3] as usize;w[34]=origin as i64;
            if let Some(source)=f.sources.get(origin).copied().flatten(){
                w[35]=(f.final_draw&&f.geometry_sampled&&source.prefix>=6) as i64;
                w[36]=source.identity.calibration_revision.is_some() as i64;
                w[37]=source.identity.calibration_revision.unwrap_or(0) as i64;w[38]=source.prefix as i64;
            }
        }
        if let Some(p)=self.pixel {
            let v=[p.frame.ordinal,p.frame.surface,p.now,p.count,p.hash,p.format as u64,p.width as u64,p.height as u64,p.flags as u64,p.contract as u64];
            for(i,value)in v.into_iter().enumerate(){w[39+i]=value as i64;}
            for origin in 0..2{if let Some(s)=p.frame.sources[origin]{let b=128+origin*5;
                w[b]=1;w[b+1]=s.identity.epoch.process_generation as i64;w[b+2]=s.identity.epoch.source_generation as i64;
                w[b+3]=s.identity.pair_sequence as i64;w[b+4]=s.prefix as i64;}}
            for i in 0..6{w[138+i]=p.frame.policies[i] as i64;}w[144]=p.frame.revision as i64;
            w[145]=p.frame.policies[3] as i64;w[146]=p.frame.geometry_sampled as i64;
            for origin in 0..2{if let Some(s)=p.frame.sources[origin]{w[147+origin*3]=s.identity.left_timestamp_ns;
                w[148+origin*3]=s.identity.right_timestamp_ns;w[149+origin*3]=s.identity.packed_pts_ns;}}
        }
        for(i,value)in [self.first_both,self.last_both,self.both,self.max_both_gap,now.is_some() as u64,now.unwrap_or(0),
            self.origins[0].distinct,self.origins[1].distinct,self.origins[0].max_gap,self.origins[1].max_gap,
            self.origins[0].max_age,self.origins[1].max_age,self.pending_since,self.armed_at].into_iter().enumerate(){w[49+i]=value as i64;}
        w[63]=capture_claimed as i64;
        w[153]=self.foreign_enabled as i64;w[154]=self.capability_mask as i64;w[155]=self.sdk_session as i64;
        w[156]=self.carrier_live as i64;w[157]=self.pending.is_some() as i64;w[158]=self.last.map_or(0,|f|f.final_draw as i64);
        w[159]=self.pixel.is_some() as i64;
        for origin in 0..2 {
            let b=64+origin*32;let stats=self.origins[origin];w[b+1]=origin as i64;
            if let Some(s)=self.last.and_then(|f|f.sources[origin]) {
                let v=[1,origin as u64,s.identity.epoch.process_generation,s.identity.epoch.source_generation,s.identity.pair_sequence,
                    s.identity.left_timestamp_ns as u64,s.identity.right_timestamp_ns as u64,s.identity.packed_pts_ns as u64,
                    s.identity.calibration_revision.is_some() as u64,s.identity.calibration_revision.unwrap_or(0),s.geometry_revision.is_some() as u64,
                    s.geometry_revision.unwrap_or(0),s.config_revision,s.prefix as u64,s.observed_at_ns,self.last_gpu,
                    self.last_gpu.saturating_sub(s.observed_at_ns),s.content_serial];
                for(i,value)in v.into_iter().enumerate(){w[b+i]=value as i64;}
                for i in 0..4{w[b+27+i]=s.processing_codes[i] as i64;}
            }
            let v=[stats.first,stats.last,stats.distinct,stats.max_gap,stats.missing,stats.transitions,stats.removed_at,stats.removals,
                self.last.map_or(0,|f|f.demanded[origin] as u64)];
            for(i,value)in v.into_iter().enumerate(){w[b+18+i]=value as i64;}
            w[b+31]=self.last.map_or(0,|f|f.available[origin] as i64);
        }
        w
    }
}
static STATE:Mutex<State>=Mutex::new(State::empty());
#[cfg(target_os="android")]
fn now_ns()->Option<u64> {
    let mut value=libc::timespec{tv_sec:0,tv_nsec:0};
    if unsafe{libc::clock_gettime(libc::CLOCK_MONOTONIC,&mut value)}!=0||value.tv_sec<0||value.tv_nsec<0{return None;}
    (value.tv_sec as u64).checked_mul(1_000_000_000)?.checked_add(value.tv_nsec as u64)
}
#[cfg(not(target_os="android"))]
fn now_ns()->Option<u64> {None}
pub(crate) fn arm_generation()->u64 {STATE.lock().map_or(0,|s|if s.armed{s.generation}else{0})}
pub(crate) fn record_frame(frame:FrameFact){if let Some(now)=now_ns(){if let Ok(mut s)=STATE.lock(){s.record(frame,now);}}}
pub(crate) fn observe_final_draw(geometry_sampled:bool){if let Ok(mut s)=STATE.lock(){if let Some(f)=s.pending.as_mut(){f.final_draw=true;f.geometry_sampled=geometry_sampled;}}}
pub(crate) fn observe_submission_entry(){if let Ok(mut s)=STATE.lock(){if s.armed&&s.pending.is_some(){s.entered=s.entered.saturating_add(1);}}}
pub(crate) fn gpu_retired(ordinal:u64,surface:u64){if let Some(now)=now_ns(){if let Ok(mut s)=STATE.lock(){s.retire(ordinal,surface,now);}}}
pub(crate) fn peer_removed(epoch:crate::stereo_input_set::SourceEpoch){if let Some(now)=now_ns(){if let Ok(mut s)=STATE.lock(){if s.armed&&epoch.process_generation==s.process {
    s.peer_removed_at=now;s.peer_removed_count=s.peer_removed_count.saturating_add(1);s.origins[1].removed_at=now;s.origins[1].removals=s.origins[1].removals.saturating_add(1);
}}}}
pub(crate) fn carrier_live(){if let Some(now)=now_ns(){if let Ok(mut s)=STATE.lock(){s.carrier_live=true;if s.armed{if s.cleanup!=3{s.cleanup=1;}s.pending_since=now;}}}}
pub(crate) fn carrier_device(foreign_enabled:bool,capability_mask:u64,sdk_session:u64){if let Ok(mut s)=STATE.lock(){s.foreign_enabled=foreign_enabled;s.capability_mask=capability_mask;s.sdk_session=sdk_session;}}
pub(crate) fn carrier_cleanup(terminal:bool){if let Ok(mut s)=STATE.lock(){s.carrier_live=false;if !terminal||s.cleanup==3{s.cleanup=3;}else{s.cleanup=2;}}}
pub(crate) fn readback_complete(ordinal:u64,surface:u64,count:u64,hash:u64,format:u32,width:u32,height:u32,flags:u32,contract:u32){if let Some(now)=now_ns(){if let Ok(mut s)=STATE.lock(){s.pixel(ordinal,surface,now,count,hash,format,width,height,flags,contract);}}}
pub(crate) fn readback_unavailable(){if let Ok(mut s)=STATE.lock(){if s.armed{s.pixel_unavailable=s.pixel_unavailable.saturating_add(1);}}}
pub(crate) fn take_requested_readback()->bool {STATE.lock().map_or(false,|mut s|{let requested=s.armed&&s.readback_requested;s.readback_requested=false;requested})}

#[cfg(target_os="android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeRequestConcurrentStereoReadback(
    _:jni::JNIEnv<'_>,_:jni::objects::JClass<'_>,hi:jni::sys::jlong,lo:jni::sys::jlong,generation:jni::sys::jlong)->jni::sys::jboolean {
    if !crate::own_stereo_capture_runtime::capture_route_selected(){return 0;}
    let Ok(process)=crate::own_packed_pool_jni::process_generation() else{return 0};
    STATE.lock().map_or(0,|mut s|{if !s.armed||s.process!=process||s.challenge!=[hi as u64,lo as u64]||s.generation!=generation as u64{return 0;}s.readback_requested=true;1})
}

#[cfg(target_os="android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeArmConcurrentStereoQualification(
    _:jni::JNIEnv<'_>,_:jni::objects::JClass<'_>,hi:jni::sys::jlong,lo:jni::sys::jlong)->jni::sys::jlong {
    if !crate::own_stereo_capture_runtime::capture_route_selected()||(hi==0&&lo==0){return 0;}
    let (Ok(process),Some(now))=(crate::own_packed_pool_jni::process_generation(),now_ns()) else{return 0};
    STATE.lock().map_or(0,|mut s|s.arm(process,[hi as u64,lo as u64],now) as i64)
}
#[cfg(target_os="android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeDisarmConcurrentStereoQualification(
    _:jni::JNIEnv<'_>,_:jni::objects::JClass<'_>,hi:jni::sys::jlong,lo:jni::sys::jlong,generation:jni::sys::jlong)->jni::sys::jboolean {
    let Ok(process)=crate::own_packed_pool_jni::process_generation() else{return 0};
    STATE.lock().map_or(0,|mut s|{
        if !s.armed||s.process!=process||s.challenge!=[hi as u64,lo as u64]||s.generation!=generation as u64{return 0;}
        s.armed=false;1
    })
}
#[cfg(target_os="android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeReadConcurrentStereoQualification(
    mut env:jni::JNIEnv<'_>,_:jni::objects::JClass<'_>)->jni::sys::jlongArray {
    let selected=crate::own_stereo_capture_runtime::capture_route_selected();
    let mut words=STATE.lock().map_or([0;WORD_COUNT],|s|s.words(now_ns(),selected,
        crate::spatial_public_multistack_runtime::source_banks_enabled(),crate::own_stereo_capture_runtime::capture_claimed()));
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    {
        let binding=crate::spatial_sdk_depth_handoff::spatial_depth_device_binding();
        let current=binding.filter(|binding|binding.session_generation==words[155] as u64&&words[156]!=0);
        words[153]=current.as_ref().map_or(0,|binding|(binding.enabled_capability_mask & crate::spatial_sdk_depth_handoff::SPATIAL_DEPTH_CAP_FOREIGN_QUEUE_OWNERSHIP_V2 !=0) as i64);
        words[154]=current.as_ref().map_or(0,|binding|binding.enabled_capability_mask as i64);
    }
    match env.new_long_array(WORD_COUNT as i32){Ok(array)=>{if env.set_long_array_region(&array,0,&words).is_err(){return std::ptr::null_mut();}array.into_raw()},Err(_)=>std::ptr::null_mut()}
}

#[cfg(test)]
mod tests {
    use super::*;use crate::stereo_input_set::SourceEpoch;
    fn source(epoch:u64,sequence:u64)->SourceFact {SourceFact{identity:StereoFrameIdentity{epoch:SourceEpoch{process_generation:7,source_generation:epoch},pair_sequence:sequence,
        left_timestamp_ns:sequence as i64*10,right_timestamp_ns:sequence as i64*10+1,packed_pts_ns:sequence as i64*20,calibration_revision:None},geometry_revision:None,
        config_revision:3,prefix:6,observed_at_ns:10,content_serial:sequence,processing_codes:[0;4]}}
    fn frame(generation:u64,ordinal:u64,peer:Option<SourceFact>)->FrameFact {FrameFact{arm_generation:generation,ordinal,surface:8,revision:3,policies:[0,1,0,0,2,2],
        sources:[Some(source(1,ordinal)),peer],demanded:[6,6],final_draw:true,geometry_sampled:false,available:[true,peer.is_some()]}}
    #[test]fn submit_and_recording_do_not_create_gpu_or_pixel_evidence(){let mut s=State::empty();let a=s.arm(7,[1,2],10);s.record(frame(a,1,Some(source(2,1))),20);
        let w=s.words(Some(30),true,true,true);assert_eq!(w[12],1);assert_eq!(w[16],0);assert_eq!(w[18],0);assert_eq!(w[9],1);}
    #[test]fn exact_retirement_and_readback_identity_are_required(){let mut s=State::empty();let a=s.arm(7,[1,2],10);s.record(frame(a,1,Some(source(2,1))),20);
        s.retire(2,8,30);assert_eq!(s.gpu,0);s.retire(1,8,30);s.pixel(1,9,31,4,5,1,2,2,0,1);assert_eq!(s.pixel_count,0);
        s.pixel(1,8,31,4,5,1,2,2,0,1);assert_eq!(s.pixel_count,1);let w=s.words(Some(40),true,true,true);assert_eq!(w[39],1);assert_eq!(w[132],6);assert_eq!(w[145],0);assert_eq!(w[35],0);}
    #[test]fn peer_restart_and_own_after_removal_preserve_epoch_facts(){let mut s=State::empty();let a=s.arm(7,[1,2],10);
        s.record(frame(a,1,Some(source(2,1))),20);s.retire(1,8,30);s.peer_removed_at=35;
        s.record(frame(a,2,None),40);s.retire(2,8,50);assert_eq!(s.own_after_peer,1);
        s.record(frame(a,3,Some(source(3,1))),60);s.retire(3,8,70);assert_eq!(s.origins[1].transitions,1);assert_eq!(s.origins[1].max_gap,40);}
    #[test]fn arm_rejects_old_inflight_generation_and_history_is_bounded(){let mut s=State::empty();let old=s.arm(7,[1,2],10);let a=s.arm(7,[3,4],20);
        s.record(frame(old,1,None),30);assert_eq!(s.recorded,0);for ordinal in 1..=100{s.record(frame(a,ordinal,None),ordinal*10+30);s.retire(ordinal,8,ordinal*10+31);}
        assert_eq!(s.history.iter().flatten().count(),HISTORY);s.pixel(1,8,1101,1,1,1,1,1,0,1);assert_eq!(s.pixel_count,0);assert_eq!(s.gpu,100);}
    #[test]fn rearm_cannot_clear_physical_quarantine_and_disarm_does_not_retire_gpu(){let mut s=State::empty();s.cleanup=3;let a=s.arm(7,[1,2],10);
        assert_eq!(s.cleanup,3);s.record(frame(a,1,None),20);s.armed=false;assert!(s.pending.is_some());assert_eq!(s.cleanup,3);
        s.retire(1,8,30);assert_eq!(s.gpu,0);assert!(s.pending.is_none());assert_eq!(s.cleanup,3);}
}

/// Cancel only the exact typed never-submitted SDK frame. No GPU or rendered counters advance.
pub(crate) fn cancel_sdk_unsubmitted(ordinal:u64,surface:u64,proof:&crate::spatial_sdk_depth_handoff::SpatialUnsubmittedProof){
    if ordinal==0||ordinal>u32::MAX as u64||surface==0||surface>u32::MAX as u64{return;}
    if let Ok(mut s)=STATE.lock(){
        if s.pending.is_some_and(|f|f.ordinal==ordinal&&f.surface==surface)
            &&proof.matches_request(s.sdk_session,(surface<<32)|ordinal){s.pending=None;}
    }
}
