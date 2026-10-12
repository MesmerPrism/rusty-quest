"""Actual retired refresh/control snapshot seams; native resources modeled."""
import argparse
import hashlib
import json
import pathlib
import subprocess


def block(text, marker):
    start=text.index(marker);i=text.index('{',start)+1;depth=1
    while depth:
        depth+=(text[i]=='{')-(text[i]=='}');i+=1
    return text[start:i]


def main():
    p=argparse.ArgumentParser()
    for name in ('repo-root','output-root'):
        p.add_argument('--'+name,type=pathlib.Path,required=True)
    p.add_argument('--baseline-root',type=pathlib.Path);p.add_argument('--rustc',required=True);a=p.parse_args()
    root=a.repo_root.resolve();out=a.output_root.resolve()
    if not out.is_relative_to(root/'target') or out.exists():raise ValueError('Unused owned target required')
    out.mkdir(parents=True)
    rel='apps/spatial-camera-panel-android/native-receipt/src'
    base=root/rel
    fixture=root/'tools/checks/fixtures/stereo_source_refresh_baseline.rs'
    retained=fixture.read_text()
    def seam(name):return retained.split('// '+name+'_BEGIN\n',1)[1].split('// '+name+'_END',1)[0].rstrip()
    baseline=seam('REFRESH');old_consumer=seam('CONSUMER');normalizer=seam('NORMALIZER')
    new=(base/'spatial_stereo_source_import.rs').read_text()
    current=block(new,'    pub(crate) unsafe fn refresh(')
    if a.baseline_root:
        old=(a.baseline_root.resolve()/rel/'spatial_stereo_source_import.rs').read_text()
        assert baseline.strip()==block(old,'    pub(crate) unsafe fn refresh(').strip()
    consumer_start=new.index('        if !crate::stereo_source_refresh_policy::recording_snapshot_valid(')
    consumer_end=new.index('        let [own, peer]',consumer_start)
    consumer=new[consumer_start:consumer_end]
    words=block(new,'fn processing_policy_words(')
    source=(base/'camera_hwb_source_set.rs').read_text()
    start=source.index('            let recording_control =')
    end=source.index('            device\n                .reset_fences',start)
    loop=source[start:end]
    cancel_start=source.index('            if cancellation.load(Ordering::Acquire) {',source.index('        while max_frames'))
    cancellation=block(source[cancel_start:], '            if cancellation.load')
    start=source.index('                let (words, revision, mask) = recording_control;')
    end=source.index('                Some(imports.recording_inputs(',start)
    policy=source[start:end]
    transport=(base/'stereo_bank_transport_v1.rs').read_text()
    uniform=block(transport,'pub(crate) struct StereoBankPolicyUniformV1')
    stub='''
mod ash {pub struct Device;pub mod android {pub mod external_memory_android_hardware_buffer {pub struct Device;}}}
mod vk {pub struct PhysicalDeviceMemoryProperties;#[derive(Clone,Copy,PartialEq,Eq)]pub struct Fence(pub u64);impl Fence{pub fn null()->Self{Self(0)}}}
mod thread {pub fn yield_now(){YIELDS.fetch_add(1,std::sync::atomic::Ordering::SeqCst);}pub static YIELDS:std::sync::atomic::AtomicU64=std::sync::atomic::AtomicU64::new(0);}
mod spatial_guide_processing {#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub struct SpatialGuideProcessingPolicy{pub preblur_kernel:u32,pub preblur_input:u32,pub postblur_kernel:u32,pub camera_sampling:u32}pub static CURRENT:std::sync::atomic::AtomicU64=std::sync::atomic::AtomicU64::new(1);pub fn current_spatial_guide_processing_policy()->SpatialGuideProcessingPolicy{let v=CURRENT.load(std::sync::atomic::Ordering::SeqCst)as u32;SpatialGuideProcessingPolicy{preblur_kernel:v,preblur_input:v,postblur_kernel:v,camera_sampling:v}}}
WORDS
static CANCEL:std::sync::atomic::AtomicBool=std::sync::atomic::AtomicBool::new(false);
struct CameraHwbProbeResources;
#[derive(Clone,Copy)]enum CameraHwbProbeMode{RawColorProjection}
mod stereo_bank_transport_v1 {UNIFORM}
mod spatial_public_multistack_runtime {
 use std::sync::atomic::{AtomicU64,Ordering};pub static REV:AtomicU64=AtomicU64::new(1);
 pub fn read_mask_policy()->([u32;6],u64,[u32;3]){([0,0,0,0,2,2],REV.load(Ordering::SeqCst),crate::stereo_bank_mask_v1::pack(true,0.5,0.05,1.0,true,false).unwrap())}
}
mod spatial_stereo_source_import {pub fn synchronize_peer_source()->Result<(),String>{Ok(())}}
struct Graph {camera_resources:CameraHwbProbeResources}
struct Owner {pending:Option<vk::Fence>,cleared:u32,next:Option<Result<(),String>>,late_change:bool,import_revision:u64,control_revision:u64,processing_policy:[u32;4],processing_snapshot:spatial_guide_processing::SpatialGuideProcessingPolicy,cancel_on_race:bool,refreshes:u32}
impl Owner {
 fn retire_after_fence(&mut self,_d:&ash::Device)->Result<(),String>{self.pending=None;Ok(())}
 fn clear_retired_sources(&mut self,_d:&ash::Device){assert!(self.pending.is_none());self.cleared+=1;self.import_revision=0;self.control_revision=0;}
 unsafe fn refresh_retired(&mut self,_d:&ash::Device,_m:&vk::PhysicalDeviceMemoryProperties,_a:&ash::android::external_memory_android_hardware_buffer::Device,_r:&CameraHwbProbeResources,_mode:CameraHwbProbeMode,_age:u64,revision:u64)->Result<(),String>{
  self.refreshes+=1;let captured=spatial_guide_processing::current_spatial_guide_processing_policy();let result=self.next.take().unwrap_or(Ok(()));if result.is_err(){if self.cancel_on_race{CANCEL.store(true,std::sync::atomic::Ordering::SeqCst);}spatial_public_multistack_runtime::REV.store(revision+1,std::sync::atomic::Ordering::SeqCst)}else{self.import_revision=revision;self.control_revision=revision;self.processing_snapshot=captured;self.processing_policy=processing_policy_words(captured);if self.late_change{spatial_public_multistack_runtime::REV.store(revision+1,std::sync::atomic::Ordering::SeqCst);spatial_guide_processing::CURRENT.store(revision+1,std::sync::atomic::Ordering::SeqCst)}}result
 }
 PRODUCTION_REFRESH
 fn consumer(&mut self,processing_revision:u64,fence:vk::Fence)->Result<spatial_guide_processing::SpatialGuideProcessingPolicy,String>{CONSUMER Ok(processing_policy)}
}
fn owner(next:Result<(),String>)->Owner{Owner{pending:None,cleared:0,next:Some(next),late_change:false,import_revision:0,control_revision:0,processing_policy:[0;4],processing_snapshot:spatial_guide_processing::current_spatial_guide_processing_policy(),cancel_on_race:false,refreshes:0}}
unsafe fn call(o:&mut Owner)->Result<RETURN,String>{o.refresh(&ash::Device,&vk::PhysicalDeviceMemoryProperties,&ash::android::external_memory_android_hardware_buffer::Device,&CameraHwbProbeResources,CameraHwbProbeMode::RawColorProjection,1_000_000_000,1)}
'''.replace('UNIFORM',uniform).replace('WORDS',words).replace('CONSUMER',consumer)
    old_source=(stub.replace('PRODUCTION_REFRESH',baseline).replace('RETURN','()').replace(consumer,old_consumer)+normalizer)+'''
fn main(){let mut o=owner(Err("stereo-normalizer-control-changed-during-refresh".into()));assert_eq!(unsafe{call(&mut o)},Err("stereo-normalizer-control-changed-during-refresh".into()));assert_eq!(o.cleared,1);
 spatial_guide_processing::CURRENT.store(1,std::sync::atomic::Ordering::SeqCst);let mut late=owner(Ok(()));unsafe{call(&mut late)}.unwrap();spatial_guide_processing::CURRENT.store(2,std::sync::atomic::Ordering::SeqCst);assert_eq!(late.consumer(1,vk::Fence(1)),Err("stereo-recording-admission-invalid".into()));
 println!("Installed production baseline aborts legitimate control race after retired clear: reproduced");}
'''
    new_source=stub.replace('PRODUCTION_REFRESH',current).replace('RETURN','bool')+'''
unsafe fn cycle(o:&mut Owner)->Result<Vec<(u64,u32)>,String>{
 use std::sync::atomic::Ordering;let cancellation=&CANCEL;
 let device=&ash::Device;let memory_properties=vk::PhysicalDeviceMemoryProperties;
 let ahb_device=&ash::android::external_memory_android_hardware_buffer::Device;
 let processing_graph=Graph{camera_resources:CameraHwbProbeResources};let mut recorded=vec![];
 for _ in 0..2 {
  CANCELLATION
  let mut stereo_source_imports=Some(&mut *o);
  LOOP
  POLICY
  assert_eq!(prefixes,[6,6]);assert_eq!(policy.guide_origins[2],mask[0]);assert_eq!(policy.guide_origins[3],mask[1]);assert_eq!(policy.source_state[3],mask[2]);
  assert_eq!(revision,o.import_revision);let processing=o.consumer(revision,vk::Fence(1))?;recorded.push((revision,processing.preblur_kernel));
  o.next=Some(Ok(()));
 }
 Ok(recorded)
}
fn main(){
 let mut o=owner(Err("stereo-normalizer-control-changed-during-refresh".into()));
 assert_eq!(unsafe{call(&mut o)},Ok(false));assert_eq!(o.cleared,1);o.next=Some(Ok(()));assert_eq!(unsafe{call(&mut o)},Ok(true));
 for error in ["stereo-normalizer-control-unavailable","device-lost","peer-source-binding-stale"] {let mut o=owner(Err(error.into()));assert_eq!(unsafe{call(&mut o)},Err(error.into()));assert_eq!(o.cleared,1);}
 let mut fatal=owner(Ok(()));unsafe{call(&mut fatal)}.unwrap();
 assert!(fatal.consumer(1,vk::Fence::null()).is_err());assert!(fatal.consumer(0,vk::Fence(1)).is_err());assert!(fatal.consumer(2,vk::Fence(1)).is_err());fatal.processing_policy[0]+=1;assert!(fatal.consumer(1,vk::Fence(1)).is_err());fatal.processing_policy[0]-=1;assert!(fatal.consumer(1,vk::Fence(1)).is_ok());assert!(fatal.consumer(1,vk::Fence(1)).is_err());
 let mut o=owner(Ok(()));o.pending=Some(vk::Fence(1));assert_eq!(unsafe{call(&mut o)},Err("stereo-import-replacement-before-retirement".into()));assert_eq!(o.cleared,0);
 // Race on first refresh clears retired imports and defers BEFORE fence reset;
 // a later stable refresh records the new snapshot, without restarting owners.
 spatial_public_multistack_runtime::REV.store(1,std::sync::atomic::Ordering::SeqCst);spatial_guide_processing::CURRENT.store(1,std::sync::atomic::Ordering::SeqCst);
 let mut o=owner(Err("stereo-normalizer-control-changed-during-refresh".into()));
 let raced=unsafe{cycle(&mut o)}.unwrap();assert_eq!(raced,vec![(2,1)]);assert_eq!(o.cleared,1);assert_eq!(thread::YIELDS.load(std::sync::atomic::Ordering::SeqCst),1);
 spatial_public_multistack_runtime::REV.store(1,std::sync::atomic::Ordering::SeqCst);
 let mut cancelled=owner(Err("stereo-normalizer-control-changed-during-refresh".into()));cancelled.cancel_on_race=true;assert!(unsafe{cycle(&mut cancelled)}.unwrap().is_empty());assert_eq!(cancelled.refreshes,1);CANCEL.store(false,std::sync::atomic::Ordering::SeqCst);
 // A change after successful refresh uses the captured snapshot for that frame.
 spatial_public_multistack_runtime::REV.store(1,std::sync::atomic::Ordering::SeqCst);
 let mut o=owner(Ok(()));o.late_change=true;
 let recorded=unsafe{cycle(&mut o)}.unwrap();assert_eq!(recorded,vec![(1,1),(2,2)]);assert_eq!(o.cleared,0);
 println!("Actual retired refresh pending/fatal/control deferral and immutable mask snapshot controls PASS; resources modeled");
}
'''.replace('LOOP',loop).replace('POLICY',policy).replace('CANCELLATION',cancellation)
    results=[]
    modules='#[path='+json.dumps((base/'stereo_source_refresh_policy.rs').as_posix())+']mod stereo_source_refresh_policy;\n#[path='+json.dumps((base/'stereo_bank_mask_v1.rs').as_posix())+']mod stereo_bank_mask_v1;\n'
    for name,text in [('baseline',old_source),('candidate',new_source)]:
        f=out/(name+'.rs');f.write_text(modules+text);exe=out/(name+'.exe')
        for label,argv in [('compile',[a.rustc,'--edition=2021',str(f),'-o',str(exe)]),('controls',[str(exe)])]:
            r=subprocess.run(argv,capture_output=True);(out/(name+'-'+label+'.stdout')).write_bytes(r.stdout);(out/(name+'-'+label+'.stderr')).write_bytes(r.stderr);results.append({'name':name+'-'+label,'exit':r.returncode})
            if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    test=out/'policy-tests.exe'
    for label,argv in [('compile',[a.rustc,'--edition=2021','--test',str(out/'candidate.rs'),'-o',str(test)]),('controls',[str(test)])]:
        r=subprocess.run(argv,capture_output=True);(out/('policy-'+label+'.stdout')).write_bytes(r.stdout);(out/('policy-'+label+'.stderr')).write_bytes(r.stderr);results.append({'name':'policy-'+label,'exit':r.returncode})
        if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    assert 'normalizer_processing_policy()' not in consumer and 'current_spatial_guide_processing_policy()' not in consumer
    assert 'let processing_policy = self.processing_snapshot' in consumer
    assert 'self.processing_snapshot = processing_snapshot' in new
    assert 'revision: inputs.control_revision' in (base/'spatial_stereo_banks.rs').read_text()
    for name in ('camera_hwb_source_set.rs','camera_hwb_probe.rs'):
        t=(base/name).read_text();begin=t.index('            let recording_control =');reset=t.index('.reset_fences',begin)
        assert begin<t.index('if !imports.refresh(',begin)<t.index('continue;',begin)<reset
        assert 'recording_control.1' in t[begin:reset] and 'thread::yield_now()' in t[begin:reset]
    paths=[base/n for n in ('spatial_stereo_source_import.rs','camera_hwb_source_set.rs','camera_hwb_probe.rs','stereo_source_refresh_policy.rs')]+[fixture]
    (out/'RESULT.json').write_text(json.dumps({'status':'passed','checks':results,'source_pins':{str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in paths},'limits':'Actual extracted refresh/loop/policy seams; fence, imports and control-change timing modeled. Does not prove current Q failure cause or Android/GPU/device qualification.'},indent=2))
    print((out/'baseline-controls.stdout').read_text());print((out/'candidate-controls.stdout').read_text())
    print((out/'policy-controls.stdout').read_text())


if __name__=='__main__':main()
