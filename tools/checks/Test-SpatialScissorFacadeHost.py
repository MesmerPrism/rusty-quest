"""Real JVM/JNI optional scissor facade and source renderer leaf; no Android/device/Cargo."""
import argparse,pathlib,hashlib,json,subprocess,os

def body(text,name):
 s=text.index(name);e=text.index('{',s)+1;d=1
 while d:d+=(text[e]=='{')-(text[e]=='}');e+=1
 return text[s:e]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--host-deps',type=pathlib.Path,required=True);p.add_argument('--java-home',type=pathlib.Path,required=True);p.add_argument('--module-manifest',type=pathlib.Path,required=True);a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=a.output.resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 src=root/'apps/spatial-camera-panel-android/native-receipt/src';java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialScissorModule.java';fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/SpatialScissorFacadeHostProof.java'
 inputs=[src/'spatial_scissor_module.rs',src/'spatial_scissor_module/jni.rs',src/'spatial_video_projection.rs',java,fixture,pathlib.Path(__file__).resolve()];pins={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in inputs}
 core=(src/'spatial_scissor_module.rs').read_text(encoding='utf-8');marker='#[cfg(target_os="android")]\nmod jni;'
 if marker not in core:marker='#[cfg(target_os = "android")]\nmod jni;'
 assert marker in core
 core=core.replace(marker,'#[path="'+(src/'spatial_scissor_module/jni.rs').as_posix()+'"]\nmod jni;')
 (out/'core.rs').write_text(core,encoding='utf-8');renderer=(src/'spatial_video_projection.rs').read_text(encoding='utf-8')
 hook='\n'.join(body(renderer,n) for n in ['fn target_rect_to_scissor(', 'fn normalized_interval_to_pixels('])
 wrapper='''#![allow(dead_code)]
#[path="core.rs"] mod spatial_scissor_module;
mod vk { pub struct Extent2D{pub width:u32,pub height:u32} pub struct Offset2D{pub x:i32,pub y:i32} pub struct Rect2D{pub offset:Offset2D,pub extent:Extent2D} }
HOOK
#[no_mangle] pub extern "system" fn Java_SpatialScissorFacadeHostProof_nativeProject(mut env:jni::JNIEnv,_class:jni::objects::JClass,width:jni::sys::jint,height:jni::sys::jint,rect:jni::objects::JFloatArray)->jni::sys::jintArray {
 let mut r=[0.;4];if env.get_float_array_region(&rect,0,&mut r).is_err(){return std::ptr::null_mut();}
 let p=target_rect_to_scissor(vk::Extent2D{width:width as u32,height:height as u32},r);let array=env.new_int_array(4).unwrap();env.set_int_array_region(&array,0,&[p.offset.x,p.offset.y,p.extent.width as i32,p.extent.height as i32]).unwrap();array.into_raw()
}'''.replace('HOOK',hook).replace('mut env:jni::JNIEnv','env:jni::JNIEnv')
 (out/'bridge.rs').write_text(wrapper,encoding='utf-8');shim=out/'android/content/Context.java';shim.parent.mkdir(parents=True);shim.write_text('package android.content; public abstract class Context {public abstract java.io.File getNoBackupFilesDir();public abstract java.io.File getCodeCacheDir();}')
 extern=[];deps={}
 for name in ['sha2','jni']:
  files=list(a.host_deps.glob('lib'+name+'-*.rlib'));assert len(files)==1;extern+=['--extern',name+'='+str(files[0])];deps[str(files[0])]=hashlib.sha256(files[0].read_bytes()).hexdigest()
 commands=[]
 def run(name,args):
  v=subprocess.run([str(x) for x in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/(name+'.log')).write_bytes(v.stdout);commands.append({'name':name,'exit_code':v.returncode});return v.returncode
 compile_exit=run('native-compile',['rustc','--edition','2021','--crate-type','cdylib',out/'bridge.rs','-L','dependency='+str(a.host_deps),*extern,'-D','warnings','-o',out/'spatial_camera_panel_native_receipt.dll'])
 javac=run('java-compile',[a.java_home/'bin/javac.exe','-d',out,shim,java,fixture]) if compile_exit==0 else None
 tested=run('jvm',[a.java_home/'bin/java.exe','-Xcheck:jni','-Djava.library.path='+str(out),'-cp',out,'SpatialScissorFacadeHostProof',a.module_manifest.resolve(),out/'context']) if javac==0 else None
 unchanged=all(hashlib.sha256(pathlib.Path(n).read_bytes()).hexdigest()==h for n,h in pins.items());passed=compile_exit==0 and javac==0 and tested==0 and unchanged
 v={'passed':passed,'source_pins':pins,'source_unchanged':unchanged,'dependency_pins':deps,'commands':commands,'scope':__doc__,'limits':'Exact product core/JNI/facade and renderer calculation body; one Android cfg enables the JNI producer on the host, Context directory and Vulkan PODs modeled. No full application/native package/Android/SDK/GPU proof.','device_calls':0};(out/'RESULT.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps({'passed':passed,'output':str(out)}));return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
