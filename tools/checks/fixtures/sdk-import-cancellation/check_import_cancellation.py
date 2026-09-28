from pathlib import Path
import subprocess,json,hashlib
import argparse,os
parser=argparse.ArgumentParser();parser.add_argument('--repo-root',required=True);parser.add_argument('--output',required=True);parser.add_argument('--rust-compiler',default='rustc');args=parser.parse_args()
r=Path(args.repo_root)/'apps/spatial-camera-panel-android/native-receipt/src';c=r;out=Path(args.output);out.mkdir()
executable=out/('check.exe' if os.name=='nt' else 'check')
def block(s,key):
 a=s.index(key);b=s.index('{',a);depth=1;i=b+1
 while depth:
  depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
src=(c/'spatial_stereo_source_import.rs').read_text();graph=(c/'camera_hwb_source_set.rs').read_text()
methods='\n'.join(block(src,key) for key in ['pub(crate) unsafe fn retire_after_fence','pub(crate) fn cancel_sdk_unsubmitted','pub(crate) unsafe fn destroy'])
drop=block(src,'impl Drop for StereoSourceImports');case=block(graph,'match retirement.action()')
sdk=(r/'spatial_sdk_depth_handoff.rs').read_text()+'''
pub(crate) fn fixture_result(request:u64,session:u64,accepted:bool,success:bool,typed:bool)->SpatialDepthRequestResultV1{
 let mut r=SpatialDepthRequestResultV1::default();r.request_id=request;r.status=if success{0}else{-1};r.vk_result=if success{0}else{-1};r.qualification_flags=if accepted{QUALIFICATION_QUEUE_SUBMIT_ACCEPTED}else{0};
 if typed{r.struct_size=size_of::<SpatialDepthRequestResultV1>() as u32;r.abi_version=ABI_V1;r.generation=session;r.kind=1;r.state=4;r.completed_monotonic_ns=1;}r
}
'''
(out/'sdk.rs').write_text(sdk)
pool=(r/'pinned_packed_contents.rs').read_text().split('#[cfg(test)]')[0]
code='''#![allow(dead_code,unused_imports)]
mod pinned{PINNED}
#[path="sdk.rs"] mod spatial_sdk_depth_handoff;
mod ash{pub mod vk{#[derive(Clone,Copy)] pub struct Fence(pub u64);pub trait Handle{fn as_raw(&self)->u64;}impl Handle for Fence{fn as_raw(&self)->u64{self.0}}}
pub struct Device{pub signaled:bool}impl Device{pub fn get_fence_status(&self,_:vk::Fence)->Result<bool,String>{Ok(self.signaled)}pub unsafe fn destroy_descriptor_pool(&self,_:u64,_:Option<()>){}}
}
use ash::vk;use spatial_sdk_depth_handoff::*;
struct Image;impl Image{unsafe fn destroy(self,_:&ash::Device){}}
struct Normalizer;impl Normalizer{unsafe fn destroy(self,_:&ash::Device){}}
struct ImportedSource{image:Image,normalizer:Normalizer,frame:pinned::PinnedPackedLease<(),u64>}
struct StereoSourceImports{sources:[Option<ImportedSource>;2],pending:Option<vk::Fence>,pool:u64}
impl StereoSourceImports{METHODS}
DROP
struct Targets;impl Targets{fn cancel_stereo_sdk_unsubmitted(&mut self,_:&SpatialUnsubmittedProof)->Result<(),String>{Ok(())}}
struct Graph{public_guide_targets:Option<Targets>}
struct Readback;impl Readback{fn cancel_unsubmitted(&mut self,_:&str){}}
struct Binding{session_generation:u64}
unsafe fn cancellation(mut stereo_source_imports:Option<StereoSourceImports>,retirement:SpatialSubmitRetirementState,device:&ash::Device)->Result<(),String>{
 let shutdown_reason=Some("source-set-session-cancelled-during-sdk-retirement");let mut processing_graph=Graph{public_guide_targets:None};let mut projection_readback=Readback;let sdk_binding=Binding{session_generation:2};
 loop{CASE
 if retirement.action()==SpatialSubmitRetirementAction::Wait{return Err("fixture bounded unresolved observation".into());}
 }Ok(())
}
fn owner()->pinned::ContentOwner<(),u64>{pinned::ContentOwner::from_observed_producer(pinned::PackedContents{version:pinned::ContentVersion{process_generation:1,source_generation:1,pool_generation:1,slot_serial:1},allocation:(),pair:1})}
fn imports(o:&mut pinned::ContentOwner<(),u64>)->StereoSourceImports{StereoSourceImports{sources:[Some(ImportedSource{image:Image,normalizer:Normalizer,frame:o.retain()}),None],pending:Some(vk::Fence(7)),pool:1}}
fn state(accepted:bool,success:bool,typed:bool,fence:bool)->SpatialSubmitRetirementState{let mut s=SpatialSubmitRetirementState::new_bound(3,2,7);assert!(s.observe_terminal(fixture_result(3,2,accepted,success,typed)));if fence{s.observe_fence();}s}
fn main(){unsafe{
 for (name,s,signaled,released) in [
 ("accepted success/fence",state(true,true,false,true),true,true),
 ("accepted failure/fence",state(true,false,false,true),true,true),
 ("typed unsubmitted",state(false,false,true,false),false,true),
 ("unsubmitted without typed proof",state(false,false,false,false),false,false),
 ("accepted pending fence",state(true,true,false,false),false,false),
 ("fence observation counterevidence",state(true,true,false,true),false,false)]{
 let mut o=owner();let i=imports(&mut o);assert!(cancellation(Some(i),s,&ash::Device{signaled}).is_err());assert_eq!(o.unreferenced(),released,"{name}");println!("PASS {name}");}
 for (session,request,fence) in [(9,3,7),(2,9,7),(2,3,9)]{let mut o=owner();let mut i=imports(&mut o);i.pending=Some(vk::Fence(fence));let s=state(false,false,true,false);assert!(i.cancel_sdk_unsubmitted(session,request,s.unsubmitted_proof().unwrap()).is_err());drop(i);assert!(!o.unreferenced());}
 println!("PASS exact session/request/fence mismatch quarantine");
}}
'''.replace('PINNED',pool).replace('METHODS',methods).replace('DROP',drop).replace('CASE',case)
(out/'main.rs').write_text(code)
run=subprocess.run([args.rust_compiler,'--edition=2021',str(out/'main.rs'),'-o',str(executable)],capture_output=True);(out/'compile.stderr').write_bytes(run.stderr)
if run.returncode:raise SystemExit(run.stderr.decode())
run=subprocess.run([str(executable)],capture_output=True);(out/'test.stdout').write_bytes(run.stdout);(out/'test.stderr').write_bytes(run.stderr)
(out/'result.json').write_text(json.dumps(dict(exit=run.returncode,scope='actual full SDK state+typed result predicate, actual cancellation match and extracted import retire/destroy/Drop, actual pinned owner; Vulkan handles/fence/destruction and bank callback mocked, no device/JNI claim',source_sha256={name:hashlib.sha256((c/name).read_bytes()).hexdigest() for name in ['spatial_stereo_source_import.rs','camera_hwb_source_set.rs']}),indent=2));print(run.stdout.decode());raise SystemExit(run.returncode)
