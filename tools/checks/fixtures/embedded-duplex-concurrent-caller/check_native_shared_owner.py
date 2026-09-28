from pathlib import Path
import argparse,json,subprocess,os,hashlib
p=argparse.ArgumentParser();p.add_argument('--source-root',required=True);p.add_argument('--output-directory',required=True);p.add_argument('--rust-compiler',required=True);p.add_argument('--java',required=True);p.add_argument('--javac',required=True);p.add_argument('--class-path',required=True);p.add_argument('--fixture-java',required=True);args=p.parse_args()
c=Path(args.source_root).resolve();out=Path(args.output_directory).resolve();out.mkdir(exist_ok=False);native_executable=out/('native.exe' if os.name=='nt' else 'native')
def block(text,start):
 a=text.index(start);b=text.index('{',a);d=1;e=b+1
 while d:d+=(text[e]=='{')-(text[e]=='}');e+=1
 return text[a:e]
probe=(c/'camera_hwb_probe.rs').read_text();ingress=(c/'peer_projection_ingress.rs').read_text();own=(c/'own_stereo_capture_runtime.rs').read_text()
claimsrc=(c/'peer_projection_ingress.rs').read_text()
claim=block(claimsrc,'pub(crate) struct PeerSessionClaimState')+'\n'+block(claimsrc,'impl PeerSessionClaimState')
effects=ingress[ingress.index('        let concurrent_own ='):ingress.index('        let accepted =',ingress.index('        let concurrent_own ='))]
accepted=ingress[ingress.index('        let accepted =',ingress.index('        let concurrent_own =')):ingress.index('        unsafe {',ingress.index('        let concurrent_own ='))]
predicate=block(ingress,'if accepted && receipt.words[2] != peer_projection_runtime::SOURCE_LOCAL')
bound=block(ingress,'        unsafe {\n            if accepted && receipt.words[2]')
src='''#![allow(dead_code,non_snake_case)]
use std::sync::{Arc,Mutex,LazyLock,atomic::{AtomicBool,Ordering}};use std::{thread,io::{self,Read}};
#[path="'''+str(c/'peer_projection_runtime.rs').replace('\\','/')+'''"] mod peer_projection_runtime;
#[derive(Clone,Copy,Debug,Default,Eq,PartialEq)]
'''+claim+'''
#[derive(Clone,Copy)]struct BoundSurface{route_generation:i64,launch_challenge:i64,surface_generation:i64,window_address:usize}
static BOUND_SURFACE:Mutex<Option<BoundSurface>>=Mutex::new(Some(BoundSurface{route_generation:1,launch_challenge:10,surface_generation:20,window_address:99}));
mod stereo_input_set {#[derive(Clone,Copy,Debug,Eq,PartialEq)]pub struct SourceEpoch{pub process_generation:u64,pub source_generation:u64}}
mod own_packed_pool_jni{pub fn process_generation()->Result<u64,String>{Ok(7)}}
mod own_packed_pool{pub fn monotonic_ns()->Option<u64>{Some(1000)}}
static CLAIMED:AtomicBool=AtomicBool::new(true);static CONFIGURED:AtomicBool=AtomicBool::new(true);static QUIESCENT:AtomicBool=AtomicBool::new(true);static FRESH:AtomicBool=AtomicBool::new(true);
mod own_stereo_capture_runtime{use super::*;use stereo_input_set::SourceEpoch;
static ACTIVE_EPOCH:Mutex<Option<SourceEpoch>>=Mutex::new(Some(SourceEpoch{process_generation:7,source_generation:40})); static FRAME_EPOCH:Mutex<Option<SourceEpoch>>=Mutex::new(Some(SourceEpoch{process_generation:7,source_generation:40}));
pub fn capture_claimed()->bool{CLAIMED.load(Ordering::Acquire)}pub fn capture_configured()->bool{CONFIGURED.load(Ordering::Acquire)}
struct Identity{epoch:SourceEpoch}struct Frame{identity:Identity}struct Sources;
impl Sources{fn own_current(&self,_clock:fn()->Option<u64>,_age:u64)->Result<Frame,&'static str>{if !FRESH.load(Ordering::Acquire){return Err("mock physical freshness expired");}Ok(Frame{identity:Identity{epoch:*FRAME_EPOCH.lock().unwrap().as_ref().unwrap()}})}}fn shared_sources()->Sources{Sources}
'''+block(own,'pub(crate) fn own_image_fresh_observed')+'\n'+block(own,'pub(crate) fn concurrent_peer_admission(')+'\n'+block(own,'pub(crate) fn concurrent_peer_admission_observed')+'\n'+block(own,'pub(crate) fn concurrent_peer_epoch_is_current')+'''
pub fn change_epoch(){ACTIVE_EPOCH.lock().unwrap().as_mut().unwrap().source_generation+=1;}
}
mod camera_hwb_probe {use super::*;
static STOP_CAMERA_HWB_PROBE:AtomicBool=AtomicBool::new(false);
static PEER_COMMON_GRAPH_SESSION:LazyLock<Mutex<PeerCommonGraphSessionOwner>>=LazyLock::new(||Mutex::new(PeerCommonGraphSessionOwner::default()));
#[derive(Default)]
'''+block(probe,'struct PeerCommonGraphSessionOwner')+'\n'+block(probe,'pub(crate) fn select_peer_source_with_own_actor')+'\n'+block(probe,'pub(crate) fn request_camera_hwb_probe_stop')+'\n'+block(probe,'fn stop_peer_common_graph_session')+'''
pub fn local_camera_acquisition_quiescent()->bool{QUIESCENT.load(Ordering::Acquire)}
fn publish_acquisition_stopped_if_quiescent(){} // No physical terminal claim.
pub fn change_window(){PEER_COMMON_GRAPH_SESSION.lock().unwrap().window_address=98;}
pub fn install(mode:&str)->Arc<AtomicBool>{let cancel=Arc::new(AtomicBool::new(false));let c=cancel.clone();let worker=thread::spawn(move||{while !c.load(Ordering::Acquire){thread::yield_now();}});let mut o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();assert!(o.claim.claim(if mode=="claim"{8}else{7}));o.cancellation=Some(cancel.clone());o.worker=Some(worker);o.window_address=if mode=="window"{98}else{99};if mode=="cancelled"{cancel.store(true,Ordering::Release);}cancel}
}
unsafe fn ANativeWindow_release(_window:*mut u8){RELEASES.fetch_add(1,Ordering::AcqRel);}
static RELEASES:std::sync::atomic::AtomicUsize=std::sync::atomic::AtomicUsize::new(0);
unsafe fn replace_bound_surface(binding:Option<BoundSurface>){let mut old=BOUND_SURFACE.lock().unwrap();if old.is_some(){RELEASES.fetch_add(1,Ordering::AcqRel);}*old=binding;}
fn select(input:[i64;16],window:*mut u8)->peer_projection_runtime::SourceReceipt{use peer_projection_runtime::SourceReceipt;let words=Some(input);
'''+effects+accepted+predicate+bound+'''
receipt}
fn single_case_main(){let mode=std::env::args().nth(1).unwrap_or("happy".into());let cancel=camera_hwb_probe::install(&mode);let mut text=String::new();io::stdin().read_to_string(&mut text).unwrap();let v:Vec<i64>=text.split(',').map(|v|v.trim().parse().unwrap()).collect();let mut words:[i64;16]=v.try_into().unwrap();
let old=[1,1,1,0,0,10,20,0,0,0,109,0,0,1,0,0];assert_eq!(peer_projection_runtime::request_source(old,true).words[1],1);
let proof=own_stereo_capture_runtime::concurrent_peer_admission(1,10,20).unwrap();
let before=peer_projection_runtime::read_source(1);
match mode.as_str(){"epoch"=>own_stereo_capture_runtime::change_epoch(),"fresh"=>FRESH.store(false,Ordering::Release),"local-active"=>QUIESCENT.store(false,Ordering::Release),"configured"=>CONFIGURED.store(false,Ordering::Release),"carrier"=>BOUND_SURFACE.lock().unwrap().as_mut().unwrap().surface_generation=21,"counter"=>{let mut rival=old;rival[1]=2;peer_projection_runtime::request_source(rival,true);words[1]=10;},"exclusive-peer"=>CLAIMED.store(false,Ordering::Release),"disabled"=>{words[2]=0;words[10]=128;},"local"=>{words[2]=1;words[10]=109;words[13]=1;},_=>{}}
let receipt=if matches!(mode.as_str(),"epoch"|"fresh"|"local-active"|"configured"|"counter"){camera_hwb_probe::select_peer_source_with_own_actor(words,99,proof).unwrap_or_else(||peer_projection_runtime::SourceReceipt::unavailable(words[1],4))}else{select(words,99usize as *mut u8)};
let stopped=cancel.load(Ordering::Acquire);
let reject=matches!(mode.as_str(),"epoch"|"fresh"|"local-active"|"configured"|"carrier"|"window"|"claim"|"cancelled"|"counter");
if reject {assert!(matches!(receipt.words[11],3|5),"must reject {mode}");let current=peer_projection_runtime::read_source(if mode=="counter"{2}else{1});if mode!="counter"{assert_eq!(before,current,"rejection must preserve old receipt {mode}");}else{assert_eq!(current.words[1],2);}assert_eq!(stopped,mode=="cancelled","rejection must preserve actor {mode}");}
else{assert_eq!(receipt.words[11],0);assert_eq!(stopped,matches!(mode.as_str(),"exclusive-peer"|"disabled"));}
println!("{}",receipt.words.iter().map(|v|v.to_string()).collect::<Vec<_>>().join(","));println!("cancelled={stopped}");if !stopped{camera_hwb_probe::request_camera_hwb_probe_stop();}}
'''
src += 'fn main(){if std::env::args().len()>1{single_case_main();return;}use std::io::BufRead;CLAIMED.store(false,Ordering::Release);*BOUND_SURFACE.lock().unwrap()=None;let mut cancel:Option<Arc<AtomicBool>>=None;\nfor line in io::stdin().lock().lines(){let text=line.unwrap();if text=="START_OWN"{assert!(BOUND_SURFACE.lock().unwrap().is_none());assert_eq!(peer_projection_runtime::read_source(2).words[2],0);assert!(RELEASES.load(Ordering::Acquire)>=1);CLAIMED.store(true,Ordering::Release);cancel=Some(camera_hwb_probe::install("happy"));println!("OWN_LIVE_BOUND_NONE");continue;}\nif text.starts_with("PROOF,"){let v:Vec<i64>=text[6..].split(\',\').map(|v|v.parse().unwrap()).collect();let p=own_stereo_capture_runtime::concurrent_peer_admission(v[0],v[1],v[2]).unwrap();println!("{}",p.iter().map(|v|v.to_string()).collect::<Vec<_>>().join(","));continue;}\nif text.starts_with("BREAK,"){match &text[6..]{"fresh"=>FRESH.store(false,Ordering::Release),"epoch"=>own_stereo_capture_runtime::change_epoch(),"local"=>QUIESCENT.store(false,Ordering::Release),"configuration"=>CONFIGURED.store(false,Ordering::Release),"window"=>{camera_hwb_probe::change_window();},_=>panic!("unknown closed test case")};println!("BROKEN");continue;}\nif text=="END_REJECTED"{assert!(cancel.as_ref().is_some_and(|c|!c.load(Ordering::Acquire)));assert!(BOUND_SURFACE.lock().unwrap().is_none());assert_eq!(peer_projection_runtime::read_source(2).words[2],0);camera_hwb_probe::request_camera_hwb_probe_stop();println!("REJECTION_CONFIRMED");break;}\nif text=="END"{assert!(cancel.as_ref().is_some_and(|c|!c.load(Ordering::Acquire)));assert!(BOUND_SURFACE.lock().unwrap().is_some_and(|b|b.route_generation==3&&b.window_address==99));camera_hwb_probe::request_camera_hwb_probe_stop();println!("END_CONFIRMED");break;}\nlet words:Vec<i64>=text.split(\',\').map(|v|v.parse().unwrap()).collect();let receipt=select(words.try_into().unwrap(),99usize as *mut u8);println!("{}",receipt.words.iter().map(|v|v.to_string()).collect::<Vec<_>>().join(","));\n}}\n'
(out/'native.rs').write_text(src)
compiled=subprocess.run([args.rust_compiler,'--edition=2021',str(out/'native.rs'),'-o',str(native_executable)],capture_output=True,text=True);(out/'compiler.txt').write_text(compiled.stdout+compiled.stderr);assert compiled.returncode==0,compiled.stderr
request='1,2,2,0,0,10,20,0,0,0,639,0,0,0,0,0'
results=[]
for mode in ['happy','epoch','fresh','local-active','configured','carrier','window','claim','cancelled','counter','exclusive-peer','disabled','local']:
 p=subprocess.run([str(native_executable),mode],input=request,capture_output=True,text=True);assert p.returncode==0,(mode,p.stdout,p.stderr);results.append({'case':mode,'stdout':p.stdout.strip()})

none_cases=[]
for case in ['fresh','epoch','local','configuration','window']:
 text='\n'.join(['1,1,1,0,0,10,20,0,0,0,109,0,0,1,0,0','1,2,0,0,0,10,20,0,0,0,128,0,0,0,0,0','START_OWN','PROOF,2,10,20','BREAK,'+case,'1,3,2,0,0,10,20,0,0,0,639,0,0,0,0,0','END_REJECTED'])+'\n'
 p=subprocess.run([str(native_executable)],input=text,capture_output=True,text=True);assert p.returncode==0,(case,p.stdout,p.stderr)
 lines=p.stdout.splitlines();words=[int(x) for x in lines[-2].split(',')];assert words[11] in [3,5] and lines[-1]=='REJECTION_CONFIRMED';none_cases.append({'case':case,'stdout':p.stdout})

classes=out/'java';classes.mkdir()
subprocess.run([args.javac,'--release','8','-Xlint:all','-Werror','-cp',args.class_path,'-d',str(classes),args.fixture_java],check=True,capture_output=True)
run=subprocess.run([args.java,'-DnativeHarness='+str(native_executable),'-cp',str(classes)+os.pathsep+args.class_path,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ConcurrentNativeCallerRegression'],capture_output=True,text=True);(out/'caller.stdout').write_text(run.stdout);(out/'caller.stderr').write_text(run.stderr);assert run.returncode==0,(run.stdout,run.stderr)
record={'status':'passed','native_cases':results,'bound_none_rejections':none_cases,'complete_caller_stdout':run.stdout,'source_hashes':{name:hashlib.sha256((c/name).read_bytes()).hexdigest() for name in ['peer_projection_runtime.rs','peer_projection_ingress.rs','camera_hwb_probe.rs','own_stereo_capture_runtime.rs']},'scope':'actual current compiled Kotlin Display/Router and Java Receiver/Registry -> ABI -> actual native selection/route CAS/shared owner stop and bound effects; defaultLocal1/stale-poll/Disabled2/BoundNone/Own/currentNative2/Peer3','physical_limit':'Looper, clock, actual frame and capture state observations, Android window/SDK supplied by mocks; Activity Local retirement uses exact request and confirmation shape; no Android JNI environment/device/render/physical retirement claim'}
(out/'result.json').write_text(json.dumps(record,indent=2));print(run.stdout);print('PASS 13 native cases + 5 BoundNone rejection cases')
