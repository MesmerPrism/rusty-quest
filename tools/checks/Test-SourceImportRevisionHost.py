"""Execute actual import revision/cleanup/recording bodies with a modeled GPU refresh leaf."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def block(text, marker):
    start = text.index(marker)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


HARNESS = r'''
#![allow(dead_code,unused_imports,unused_variables,non_upper_case_globals)]
use std::sync::{Mutex,atomic::{AtomicBool,AtomicU64,AtomicUsize,Ordering}};
use std::thread;
static REV:AtomicU64=AtomicU64::new(1);
static POLICY:AtomicUsize=AtomicUsize::new(0);
static HOOK:AtomicUsize=AtomicUsize::new(0);
static IMAGES:AtomicUsize=AtomicUsize::new(0);
static NORMALIZERS:AtomicUsize=AtomicUsize::new(0);
static FENCE_READY:AtomicBool=AtomicBool::new(true);
static GPU_CALLS:AtomicUsize=AtomicUsize::new(0);
static READS:AtomicUsize=AtomicUsize::new(0);
static ITERATION:AtomicUsize=AtomicUsize::new(0);
static TRACE:Mutex<Vec<String>>=Mutex::new(Vec::new());
fn event(name:&str){TRACE.lock().unwrap().push(format!("{name}:{}",ITERATION.load(Ordering::SeqCst)));}
mod ash {
 pub mod vk {
  pub type DescriptorPool=u64;pub type DescriptorSet=u64;
  #[derive(Clone,Copy,Debug,PartialEq,Eq)]pub struct Fence(pub u64);
  impl Fence{pub fn null()->Self{Self(0)}}
  pub struct PhysicalDeviceMemoryProperties;
  #[allow(non_camel_case_types)]#[derive(Debug)]pub enum Result{ERROR_OUT_OF_DATE_KHR,ERROR_UNKNOWN}
 }
 pub struct Device;
 impl Device{
  pub unsafe fn get_fence_status(&self,_:vk::Fence)->Result<bool,&'static str>{crate::event("retire");Ok(crate::FENCE_READY.load(crate::Ordering::SeqCst))}
  pub unsafe fn reset_fences(&self,_:&[vk::Fence])->Result<(),&'static str>{crate::event("reset");Ok(())}
  pub unsafe fn destroy_descriptor_pool(&self,_:u64,_:Option<()> ){}
 }
 pub mod android {pub mod external_memory_android_hardware_buffer {pub struct Device;}}
}
use ash::vk;
mod ahb{pub type AhbVulkanDevice=crate::ash::android::external_memory_android_hardware_buffer::Device;}
struct CameraHwbProbeResources{sampler_ycbcr_conversion:Option<u64>}
#[derive(Clone,Copy)]struct CameraHwbProbeMode;
impl CameraHwbProbeMode{const RawColorProjection:Self=Self;fn descriptor_binding_count(self)->usize{2}}
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]struct GuidePolicy{preblur_kernel:u32,preblur_input:u32,postblur_kernel:u32,camera_sampling:u32}
mod spatial_guide_processing{
 pub type SpatialGuideProcessingPolicy=crate::GuidePolicy;
 pub fn current_spatial_guide_processing_policy()->crate::GuidePolicy{crate::GuidePolicy{preblur_kernel:crate::POLICY.load(crate::Ordering::SeqCst)as u32,preblur_input:0,postblur_kernel:0,camera_sampling:0}}
}
mod spatial_public_multistack_runtime{
 pub fn read_mask_policy()->([u32;6],u64,[u32;3]){let n=crate::READS.fetch_add(1,crate::Ordering::SeqCst);if crate::HOOK.load(crate::Ordering::SeqCst)==5&&n==2{crate::REV.store(2,crate::Ordering::SeqCst);}([0,0,1,1,0,1],crate::REV.load(crate::Ordering::SeqCst),[0,0,0])}
 pub fn read_control_policy()->([u32;6],u64){let(w,r,_)=read_mask_policy();(w,r)}
}
#[derive(Clone,Copy)]struct StereoFrameIdentity;
#[derive(Clone)]struct Version{slot_serial:u64}
#[derive(Clone)]struct Contents{version:Version}
#[derive(Clone)]struct Own(Contents);
impl Own{fn contents(&self)->&Contents{&self.0}}
#[derive(Clone)]struct Peer{import_sequence:u64}
#[derive(Clone)]enum StereoSourceLease{Own(Own),Peer(Peer)}
struct Frame{identity:StereoFrameIdentity,lease:StereoSourceLease,observed_at_ns:u64}
type NormalizerCompatibility=u64;
struct PackedSbsNormalizer;
impl PackedSbsNormalizer{unsafe fn destroy(self,_:&ash::Device){NORMALIZERS.fetch_add(1,Ordering::SeqCst);}}
struct AhbVulkanSampledImage;
impl AhbVulkanSampledImage{unsafe fn destroy(self,_:&ash::Device){IMAGES.fetch_add(1,Ordering::SeqCst);}}
struct StereoGuideKey{origin:usize,frame:StereoFrameIdentity,geometry_revision:Option<u64>,processing_revision:u64,processing_policy:GuidePolicy}
struct StereoRecordingSource<'a>{key:StereoGuideKey,lease:Box<StereoSourceLease>,descriptor_set:u64,normalizer:&'a mut PackedSbsNormalizer,packed_image:&'a AhbVulkanSampledImage,transition_source:bool,foreign_queue_family:Option<u32>,reprojection:(),overscan_uv:f32,observed_at_ns:u64,content_serial:u64}
struct StereoRecordingInputs<'a>{sources:[Option<StereoRecordingSource<'a>>;2],policy:StereoBankPolicyUniformV1,demanded_prefixes:[usize;2],retirement_fence:vk::Fence,processing_policy:GuidePolicy,frame_ordinal:u64,surface_generation:u64,control_revision:u64}
#[derive(Clone,Copy)]struct StereoBankPolicyUniformV1{region_origins:[u32;4],guide_origins:[u32;4],source_state:[u32;4]}
mod stereo_bank_transport_v1{pub type StereoBankPolicyUniformV1=crate::StereoBankPolicyUniformV1;}
mod stereo_bank_mask_v1{pub fn validate(_:[u32;3])->Result<bool,String>{Ok(false)}}
fn policy()->StereoBankPolicyUniformV1{StereoBankPolicyUniformV1{region_origins:[0;4],guide_origins:[0;4],source_state:[0;4]}}
mod camera_latency_diagnostics{pub fn current_camera_latency_stereo_reprojection(_:Option<()>,_:Option<()>){}}
PRODUCTION_POLICY
PRODUCTION_CODES
PRODUCTION_IMPORTED
PRODUCTION_IMPORTS
PRODUCTION_ADMISSION
impl StereoSourceImports{
 PRODUCTION_RETIRE
 PRODUCTION_REFRESH
 PRODUCTION_CLEAR
 PRODUCTION_REFRESH_RETIRED
 PRODUCTION_RECORDING
 PRODUCTION_ADMIT
 PRODUCTION_DESTROY
}
PRODUCTION_DROP
unsafe fn modeled_gpu_refresh(imports:&mut StereoSourceImports,_:&ash::Device)->Result<(),String>{
 GPU_CALLS.fetch_add(1,Ordering::SeqCst);
 event("gpu");
 imports.clear_retired_sources(&ash::Device);
 for source in &mut imports.sources{*source=Some(ImportedSource{compatibility:0,normalizer:PackedSbsNormalizer,image:AhbVulkanSampledImage,frame:Frame{identity:StereoFrameIdentity,lease:StereoSourceLease::Peer(Peer{import_sequence:1}),observed_at_ns:1}});}
 match HOOK.load(Ordering::SeqCst){1=>{REV.store(2,Ordering::SeqCst);},2=>{POLICY.store(1,Ordering::SeqCst);},3=>return Err("modeled GPU import failure".into()),4=>{REV.store(0,Ordering::SeqCst);},_=>{}}
 Ok(())
}
fn imports()->StereoSourceImports{StereoSourceImports{pool:1,sets:[1,2],sources:[None,None],pending:None,foreign_queue_family:Some(1),control_revision:0,processing_policy:[0;4],processing_snapshot:Default::default()}}
fn reset(){REV.store(1,Ordering::SeqCst);POLICY.store(0,Ordering::SeqCst);HOOK.store(0,Ordering::SeqCst);IMAGES.store(0,Ordering::SeqCst);NORMALIZERS.store(0,Ordering::SeqCst);GPU_CALLS.store(0,Ordering::SeqCst);FENCE_READY.store(true,Ordering::SeqCst);READS.store(0,Ordering::SeqCst);ITERATION.store(0,Ordering::SeqCst);TRACE.lock().unwrap().clear();}
unsafe fn refresh(i:&mut StereoSourceImports)->Result<REFRESH_TYPE,String>{i.refresh(&ash::Device,&vk::PhysicalDeviceMemoryProperties,&ash::android::external_memory_android_hardware_buffer::Device,&CameraHwbProbeResources{sampler_ycbcr_conversion:None},CameraHwbProbeMode,100,1)}
#[test]fn actual_refresh_revision_advance_is_nonfatal_and_retires_both_sources(){reset();let mut i=imports();HOOK.store(1,Ordering::SeqCst);let result=unsafe{refresh(&mut i)};assert!(result.is_ok(),"legitimate R1 to R2 must be a nonfatal handover");assert!(i.sources.iter().all(Option::is_none));assert_eq!(IMAGES.load(Ordering::SeqCst),2);assert_eq!(NORMALIZERS.load(Ordering::SeqCst),2);assert!(i.pending.is_none());unsafe{i.destroy(&ash::Device).unwrap();}}
#[test]fn actual_between_refresh_record_revision_advance_is_nonfatal(){reset();let mut i=imports();unsafe{refresh(&mut i).unwrap();}REV.store(2,Ordering::SeqCst);let result=BASELINE_RECORD;assert!(result.is_ok(),"legitimate R1 to R2 before recording must be nonfatal");drop(result);unsafe{i.destroy(&ash::Device).unwrap();}}
#[test]fn actual_fatal_gpu_zero_and_pending_controls_stay_errors(){for hook in [3,4]{reset();let mut i=imports();HOOK.store(hook,Ordering::SeqCst);let result=unsafe{refresh(&mut i)};assert!(result.is_err());if hook==3{assert_eq!(result.err().unwrap(),"modeled GPU import failure");}assert_eq!(IMAGES.load(Ordering::SeqCst),2);assert_eq!(NORMALIZERS.load(Ordering::SeqCst),2);assert!(i.pending.is_none());unsafe{i.destroy(&ash::Device).unwrap();}}
 reset();let mut i=imports();i.pending=Some(vk::Fence(9));assert!(unsafe{refresh(&mut i)}.is_err());assert_eq!(GPU_CALLS.load(Ordering::SeqCst),0);FENCE_READY.store(false,Ordering::SeqCst);assert!(unsafe{i.retire_after_fence(&ash::Device)}.is_err());assert_eq!(i.pending,Some(vk::Fence(9)));FENCE_READY.store(true,Ordering::SeqCst);unsafe{i.retire_after_fence(&ash::Device).unwrap();i.destroy(&ash::Device).unwrap();}}
POSTFIX_CONTROLS
'''

POSTFIX = r'''
#[test]fn actual_processing_handover_defers_drift_then_records_imported_snapshot(){
 reset();let mut i=imports();HOOK.store(2,Ordering::SeqCst);
 let drift=unsafe{refresh(&mut i)};
 assert!(i.sources.iter().all(Option::is_none));assert!(i.pending.is_none());
 assert_eq!(IMAGES.load(Ordering::SeqCst),2);assert_eq!(NORMALIZERS.load(Ordering::SeqCst),2);
 assert!(drift.is_ok(),"processing change during refresh must defer nonfatally");assert!(!drift.unwrap());
 HOOK.store(0,Ordering::SeqCst);assert!(unsafe{refresh(&mut i).unwrap()});
 POLICY.store(2,Ordering::SeqCst);
 let admission=i.recording_admission(policy(),1,vk::Fence(9)).unwrap().expect("stable imported policy remains recordable");
 assert!(i.pending.is_none());assert!(!TRACE.lock().unwrap().iter().any(|e|e.starts_with("reset:")||e.starts_with("acquire:")));
 POLICY.store(3,Ordering::SeqCst);
 let inputs=i.recording_inputs(admission,[1,1],1,1).unwrap();
 assert_eq!(inputs.processing_policy.preblur_kernel,1);
 assert!(inputs.sources.iter().flatten().all(|s|s.key.processing_policy.preblur_kernel==1));
 drop(inputs);assert_eq!(i.pending,Some(vk::Fence(9)));
 unsafe{i.retire_after_fence(&ash::Device).unwrap();i.destroy(&ash::Device).unwrap();}
 assert_eq!(IMAGES.load(Ordering::SeqCst),4);assert_eq!(NORMALIZERS.load(Ordering::SeqCst),4);
}
mod spatial_stereo_source_import{pub fn synchronize_peer_source()->Result<(),String>{Ok(())}}
struct Graph{camera_resources:CameraHwbProbeResources}
struct Swapchain;
impl Swapchain{unsafe fn acquire_next_image(&self,_:u64,_:u64,_:u64,_:vk::Fence)->Result<(u32,bool),vk::Result>{event("acquire");Ok((0,false))}}
unsafe fn run_common(hook:usize)->Result<Vec<String>,String>{COMMON_LOOP}
unsafe fn run_peer(hook:usize)->Result<Vec<String>,String>{PEER_LOOP}
#[test]fn actual_both_loop_handover_paths_skip_before_reset_acquire_then_record_coherently(){
 for run in [run_common as unsafe fn(usize)->Result<Vec<String>,String>,run_peer]{for hook in [1,5]{reset();let trace=unsafe{run(hook).unwrap()};assert!(!trace.iter().any(|e|e=="reset:1"||e=="acquire:1"));let reset=trace.iter().position(|e|e=="reset:2").unwrap();let acquire=trace.iter().position(|e|e=="acquire:2").unwrap();assert!(reset<acquire);assert_eq!(trace.iter().filter(|e|e.starts_with("acquire:")).count(),1);assert_eq!(IMAGES.load(Ordering::SeqCst),4);assert_eq!(NORMALIZERS.load(Ordering::SeqCst),4);}}
}
#[test]fn actual_recording_rejects_structural_regression_and_keeps_captured_policy_after_live_change(){
 reset();let mut i=imports();unsafe{refresh(&mut i).unwrap();}
 for revision in [0]{assert!(i.recording_admission(policy(),revision,vk::Fence(9)).is_err());}
 assert!(i.recording_admission(policy(),1,vk::Fence::null()).is_err());
 i.control_revision=2;assert!(i.recording_admission(policy(),1,vk::Fence(9)).is_err());i.control_revision=1;
 let admission=i.recording_admission(policy(),1,vk::Fence(9)).unwrap().unwrap();assert!(i.pending.is_none());
 REV.store(2,Ordering::SeqCst);POLICY.store(2,Ordering::SeqCst);
 let inputs=i.recording_inputs(admission,[1,1],1,1).unwrap();assert_eq!(inputs.control_revision,1);assert_eq!(inputs.processing_policy.preblur_kernel,0);drop(inputs);assert_eq!(i.pending,Some(vk::Fence(9)));
 assert!(i.recording_admission(policy(),2,vk::Fence(9)).is_err());unsafe{i.retire_after_fence(&ash::Device).unwrap();i.destroy(&ash::Device).unwrap();}
}
'''

LOOP = r'''
 HOOK.store(hook,Ordering::SeqCst);
 let device=&ash::Device;let memory_properties=vk::PhysicalDeviceMemoryProperties;
 let ahb_device=&ash::android::external_memory_android_hardware_buffer::Device;
 let processing_graph=Graph{camera_resources:CameraHwbProbeResources{sampler_ycbcr_conversion:None}};
 let mut stereo_source_imports=Some(imports());stereo_source_imports.as_mut().unwrap().pending=Some(vk::Fence(9));
 let frame_fence=vk::Fence(9);let swapchain_loader=Swapchain;let swapchain=1;let image_available=1;
 for iteration in 1..=2{ITERATION.store(iteration,Ordering::SeqCst);
  ACTUAL_PRE_ACQUIRE
  let(admission,prefixes)=stereo_admission.unwrap();
  let i=stereo_source_imports.as_mut().unwrap();assert!(i.pending.is_none());
  // Only modeled WSI leaves ran. The exact post-acquire method must consume
  // the captured selection despite a later UI/control update.
  REV.store(3,Ordering::SeqCst);POLICY.store(3,Ordering::SeqCst);
  let inputs=i.recording_inputs(admission,prefixes,1,1)?;assert_eq!(inputs.control_revision,2);assert_eq!(inputs.processing_policy.preblur_kernel,0);drop(inputs);
  assert_eq!(i.pending,Some(frame_fence));FENCE_READY.store(false,Ordering::SeqCst);assert!(i.retire_after_fence(device).is_err());FENCE_READY.store(true,Ordering::SeqCst);i.retire_after_fence(device)?;
  stereo_source_imports.take().unwrap().destroy(device)?;
  return Ok(TRACE.lock().unwrap().clone());
 }
 Err("no coherent iteration reached WSI".into())
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, required=True)
    parser.add_argument('--rustc', required=True)
    args = parser.parse_args()
    root, out = args.repo_root.resolve(), args.output_root.resolve()
    out.mkdir(parents=True, exist_ok=False)
    path = root/'apps/spatial-camera-panel-android/native-receipt/src/spatial_stereo_source_import.rs'
    original = path.read_bytes()
    text = original.decode()
    selected = 'pub(crate) fn recording_admission(' in text
    source_files = {path: original}
    replacements = {
        'PRODUCTION_POLICY': block(text, 'fn normalizer_processing_policy('),
        'PRODUCTION_IMPORTED': block(text, 'struct ImportedSource'),
        'PRODUCTION_IMPORTS': block(text, 'pub(crate) struct StereoSourceImports'),
        'PRODUCTION_RETIRE': block(text, 'pub(crate) unsafe fn retire_after_fence('),
        'PRODUCTION_REFRESH': block(text, 'pub(crate) unsafe fn refresh('),
        'PRODUCTION_CLEAR': block(text, 'unsafe fn clear_retired_sources('),
        'PRODUCTION_REFRESH_RETIRED': block(text, 'unsafe fn refresh_retired('),
        'PRODUCTION_RECORDING': block(text, 'pub(crate) fn recording_inputs('),
        'PRODUCTION_DESTROY': block(text, 'pub(crate) unsafe fn destroy('),
        'PRODUCTION_DROP': block(text, 'impl Drop for StereoSourceImports'),
    }
    # Only the contiguous Vulkan/source snapshot/import/update work is a modeled
    # leaf. Both surrounding production guards and cleanup/mutation stay exact.
    body = replacements['PRODUCTION_REFRESH_RETIRED']
    start = body.index('        let frames =')
    end = body.index('        let current_revision =', start)
    replacements['PRODUCTION_REFRESH_RETIRED'] = body[:start]+'        modeled_gpu_refresh(self, device)?;\n'+body[end:]
    replacements['REFRESH_TYPE'] = 'bool' if selected else '()'
    replacements['BASELINE_RECORD'] = ('i.recording_admission(policy(),2,vk::Fence(9)).map(|v|{assert!(v.is_none());v})' if selected else 'i.recording_inputs(policy(),2,[1,1],vk::Fence(9),1,1)')
    replacements['PRODUCTION_CODES'] = block(text,'fn normalizer_processing_policy_codes(') if selected else ''
    replacements['PRODUCTION_ADMISSION'] = block(text,'pub(crate) struct StereoRecordingAdmission') if selected else ''
    replacements['PRODUCTION_ADMIT'] = block(text,'pub(crate) fn recording_admission(') if selected else ''
    replacements['POSTFIX_CONTROLS'] = ''
    if selected:
        postfix = POSTFIX
        for key, filename in [('COMMON_LOOP','camera_hwb_source_set.rs'),('PEER_LOOP','camera_hwb_probe.rs')]:
            loop_path = path.with_name(filename)
            source_files[loop_path] = loop_path.read_bytes()
            loop_text = source_files[loop_path].decode()
            refresh_at = loop_text.index('if !imports.refresh(')
            segment_start = loop_text.rfind('            if let Some(imports) = stereo_source_imports.as_mut()',0,refresh_at)
            segment_end = loop_text.index('            let command_buffer =',refresh_at)
            segment = loop_text[segment_start:segment_end]
            postfix = postfix.replace(key,LOOP.replace('ACTUAL_PRE_ACQUIRE',segment))
        replacements['POSTFIX_CONTROLS'] = postfix
    code = HARNESS
    for key in sorted(replacements, key=len, reverse=True):
        code = code.replace(key, replacements[key])
    generated = out/'revision.rs'
    generated.write_text(code, encoding='utf-8')
    results = []
    for name, command in [('compile',[args.rustc,'--edition=2021','--test',str(generated),'-o',str(out/'revision.exe')]),('controls',[str(out/'revision.exe'),'--test-threads=1','--nocapture'])]:
        result = subprocess.run(command,capture_output=True,timeout=70)
        (out/(name+'.stdout')).write_bytes(result.stdout)
        (out/(name+'.stderr')).write_bytes(result.stderr)
        results.append(dict(name=name,exit_code=result.returncode))
        if result.returncode:
            break
    assert all(p.read_bytes()==b for p,b in source_files.items()), 'Production changed during run; preserve and rerun'
    report = dict(passed=all(r['exit_code']==0 for r in results),runs=results,
        source_sha256=hashlib.sha256(original).hexdigest(),generated_sha256=hashlib.sha256(generated.read_bytes()).hexdigest(),
        source_pins=[dict(path=str(p),sha256=hashlib.sha256(b).hexdigest()) for p,b in source_files.items()],
        scope='Actual revision guards, refresh wrapper cleanup, recording/pending/fence/destroy methods and both pre-acquire loop segments; modeled GPU snapshot/import loop and WSI leaves; no Android/driver/WSI execution')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
