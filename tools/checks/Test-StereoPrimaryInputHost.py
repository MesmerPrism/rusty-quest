"""Execute the production guide continuation with modeled native recording only."""
import argparse,json,pathlib,subprocess

def block(text, marker):
    start=text.index(marker); end=text.index('{',start)+1; depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--repo-root',type=pathlib.Path,required=True)
    p.add_argument('--output-root',type=pathlib.Path,required=True)
    p.add_argument('--rustc',required=True);a=p.parse_args()
    root=a.repo_root.resolve();out=a.output_root.resolve()
    if out.exists() or not out.is_relative_to(root/'target'):raise ValueError('Fresh owned output required')
    out.mkdir(parents=True)
    base=root/'apps/spatial-camera-panel-android/native-receipt/src'
    runtime=(base/'spatial_public_multistack_runtime.rs').read_text()
    method=block(runtime,'    unsafe fn record_spatial_public_guide_continuation(')
    schedule=block(runtime,'const SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE:') if False else runtime.split('const SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE:',1)[1].split('\n];',1)[0]
    schedule='const SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE:'+schedule+'\n];'
    harness='''
use std::cell::RefCell;
mod ash {pub struct Device;}
mod vk {pub struct CommandBuffer;pub struct DescriptorSet;}
struct CameraLatencyStereoReprojection;
struct CameraHwbGpuTimestampTracker;
#[derive(Clone,Copy)]struct CameraHwbGpuTimestampStage;
impl CameraHwbGpuTimestampStage {fn from_guide_pass_index(_:usize)->Option<Self>{Some(Self)}}
impl CameraHwbGpuTimestampTracker {
 fn write_stage_start(&mut self,_:&ash::Device,_:vk::CommandBuffer,_:usize,_:CameraHwbGpuTimestampStage){}
 fn write_stage_end(&mut self,_:&ash::Device,_:vk::CommandBuffer,_:usize,_:CameraHwbGpuTimestampStage){}
}
#[derive(Clone,Copy)]enum SpatialGuideBlurStage {PreHorizontal,PreVertical,PostHorizontal,PostVertical}
#[derive(Clone,Copy)]enum PublicGuideBlurDirection {Horizontal,Vertical}
#[derive(Clone,Copy)]enum SpatialPublicGuidePassKind {Opaque{pipeline_index:usize},PublicBlur{direction:PublicGuideBlurDirection,stage:SpatialGuideBlurStage}}
struct SpatialPublicGuidePassStep {input_target:Option<usize>,output_target:usize,kind:SpatialPublicGuidePassKind}
struct SpatialPublicGuidePassPlan {requested_pass_count:usize,edge:bool}
impl SpatialPublicGuidePassPlan {fn edge_window_selected(&self)->bool{self.edge}}
struct SpatialPublicGuidePassRecord {count:usize}
impl SpatialPublicGuidePassRecord {
 fn recorded(_:SpatialPublicGuidePassPlan,count:usize)->Self{Self{count}}
 fn not_recorded(_:SpatialPublicGuidePassPlan,_:&str)->Self{Self{count:0}}
}
struct Policy;
impl Policy{fn for_stage(&self,_:SpatialGuideBlurStage)->Self{Self}}
fn current_spatial_guide_processing_policy()->Policy{Policy}
struct Owner {recorded:RefCell<Vec<(usize,usize)>>,available:bool,fail_target:Option<usize>}
impl Owner {
 fn guide_pass_execution_available(&self)->bool{self.available}
 unsafe fn record_opaque_guide_pass_for_stereo(&self,_:&ash::Device,_:vk::CommandBuffer,_:vk::DescriptorSet,pipeline:usize,target:usize,_:f32,_:CameraLatencyStereoReprojection,_:f32)->Result<(),String>{self.recorded.borrow_mut().push((pipeline,target));if self.fail_target==Some(target){Err("injected-recording-failure".into())}else{Ok(())}}
 unsafe fn record_public_blur_pass_for_stereo(&self,_:&ash::Device,_:vk::CommandBuffer,source:usize,target:usize,_:PublicGuideBlurDirection,_:Policy)->Result<(),String>{self.recorded.borrow_mut().push((source,target));if self.fail_target==Some(target){Err("injected-recording-failure".into())}else{Ok(())}}
METHOD
}
fn run(o:&Owner,skip:usize,count:usize,edge:bool)->Result<SpatialPublicGuidePassRecord,String>{unsafe{o.record_spatial_public_guide_continuation(&ash::Device,vk::CommandBuffer,&mut CameraHwbGpuTimestampTracker,0,vk::DescriptorSet,0.0,CameraLatencyStereoReprojection,0.0,SpatialPublicGuidePassPlan{requested_pass_count:count,edge},false,skip)}}
fn main(){
 let o=Owner{recorded:RefCell::new(vec![]),available:true,fail_target:None};
 assert_eq!(run(&o,1,6,false).unwrap().count,6);
 assert_eq!(*o.recorded.borrow(),vec![(0,1),(1,2),(3,3),(3,1),(1,4)]);
 // Raw already contains the blended source; production continuation must not
 // redraw original camera pass0 over it. Full original path still draws0.
 assert!(!o.recorded.borrow().contains(&(0,0)));o.recorded.borrow_mut().clear();
 assert_eq!(run(&o,0,6,false).unwrap().count,6);assert_eq!(o.recorded.borrow()[0],(0,0));
 o.recorded.borrow_mut().clear();assert!(run(&o,2,6,false).is_err());assert!(o.recorded.borrow().is_empty());
 assert!(run(&o,1,0,false).is_err());assert_eq!(run(&o,1,6,true).unwrap().count,0);assert!(o.recorded.borrow().is_empty());
 let missing=Owner{recorded:RefCell::new(vec![]),available:false,fail_target:None};assert_eq!(run(&missing,1,6,false).unwrap().count,0);
 let failed=Owner{recorded:RefCell::new(vec![]),available:true,fail_target:Some(3)};assert!(run(&failed,1,6,false).is_err());assert_eq!(*failed.recorded.borrow(),vec![(0,1),(1,2),(3,3)]);
 println!("Actual guide continuation: primary raw preserved, pass1..5 ordering, original pass0, partial/failure/edge/resource controls PASS; Vulkan modeled");
}
'''.replace('METHOD',method)
    # Native argument marker types are Copy just as their Vulkan value handles.
    harness=harness.replace('pub struct CommandBuffer;pub struct DescriptorSet;','#[derive(Clone,Copy)]pub struct CommandBuffer;#[derive(Clone,Copy)]pub struct DescriptorSet;').replace('struct CameraLatencyStereoReprojection;','#[derive(Clone,Copy)]struct CameraLatencyStereoReprojection;')
    source=out/'continuation.rs';source.write_text(schedule+'\n'+harness)
    exe=out/'continuation.exe'
    results=[]
    for label,argv in [('compile',[a.rustc,'--edition=2021',str(source),'-o',str(exe)]),('controls',[str(exe)])]:
        result=subprocess.run(argv,capture_output=True)
        (out/(label+'.stdout')).write_bytes(result.stdout);(out/(label+'.stderr')).write_bytes(result.stderr)
        results.append({'name':label,'exit':result.returncode})
        if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
    (out/'RESULT.json').write_text(json.dumps({'status':'passed','checks':results,'scope':'actual production continuation/schedule; native effects modeled, no GPU/device claim'},indent=2))
    print((out/'controls.stdout').read_text())
if __name__=='__main__':main()
