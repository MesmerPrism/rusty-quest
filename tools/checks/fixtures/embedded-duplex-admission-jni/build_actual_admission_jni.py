from pathlib import Path
import re, json, subprocess, sys, argparse
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--output',required=True);p.add_argument('--cargo',required=True);p.add_argument('--target',required=True)
a=p.parse_args();owner=Path(a.repo).resolve();native=owner/'apps/spatial-camera-panel-android/native-receipt/src';selected_native=native;candidate=True;out=Path(a.output).resolve()
out.mkdir();(out/'src').mkdir()
runtime = (selected_native/'own_stereo_capture_runtime.rs').read_text()
fresh = re.search(r'(?ms)^pub\(crate\) fn own_image_fresh\(\).*?^}', runtime).group()
admit = re.search(r'(?ms)^pub\(crate\) fn concurrent_peer_admission\(.*?^}', runtime).group()
jni = re.search(r'(?ms)^pub extern "system" fn Java_[^\r\n]*_concurrentPeerAdmission\(.*?^}', (selected_native/'own_packed_pool_jni.rs').read_text()).group()
if candidate:
    admit += '\n'+re.search(r'(?ms)^pub\(crate\) fn concurrent_peer_admission_observed\(.*?^}', runtime).group()
source = '''#![allow(dead_code)]
use std::sync::{Arc,Mutex,OnceLock,atomic::{AtomicBool,AtomicU64,Ordering}};
use jni::{JNIEnv,objects::JClass,sys::{jlong,jlongArray,jint}};
'''
for module in ['stereo_input_set', 'stereo_source_payload', 'peer_projection_runtime']:
    selected = selected_native if candidate and module in ['stereo_source_payload','stereo_input_set'] else native
    source += f'#[path=r#"{selected/module}.rs"#] mod {module};\n'
source += '''
use stereo_input_set::{SourceEpoch,StereoFrameIdentity,RetainedStereoFrame,StereoOrigin};
use stereo_source_payload::OwnPeerSourceSet;
static ACTIVE_EPOCH:Mutex<Option<SourceEpoch>>=Mutex::new(None);
static CLAIMED:AtomicBool=AtomicBool::new(true);
static CONFIGURED:AtomicBool=AtomicBool::new(true);
static RACE:AtomicBool=AtomicBool::new(false);
static CLOCK:AtomicU64=AtomicU64::new(1000);
static EPOCH:AtomicU64=AtomicU64::new(40);
static PUBLISHED_EPOCH:AtomicU64=AtomicU64::new(40);
static QUIESCENT:AtomicBool=AtomicBool::new(true);
static PROCESS:AtomicU64=AtomicU64::new(30);
fn shared_sources()->&'static Arc<OwnPeerSourceSet<u32,u32>> {static SET:OnceLock<Arc<OwnPeerSourceSet<u32,u32>>>=OnceLock::new(); SET.get_or_init(||Arc::new(OwnPeerSourceSet::default()))}
fn capture_claimed()->bool{CLAIMED.load(Ordering::Acquire)}
fn capture_configured()->bool{CONFIGURED.load(Ordering::Acquire)}
mod camera_hwb_probe{pub fn local_camera_acquisition_quiescent()->bool{super::QUIESCENT.load(super::Ordering::Acquire)}}
mod own_packed_pool_jni{pub fn process_generation()->Result<u64,String>{Ok(super::PROCESS.load(super::Ordering::Acquire))}}
fn publish(epoch:SourceEpoch,sequence:u64,observed:u64){shared_sources().publish_own(RetainedStereoFrame{identity:StereoFrameIdentity{epoch,pair_sequence:sequence,left_timestamp_ns:2,right_timestamp_ns:3,packed_pts_ns:3,calibration_revision:None},observed_at_ns:observed,lease:7}).unwrap();}
mod own_packed_pool {
 pub fn monotonic_ns()->Option<u64> {
  let sampled=super::CLOCK.load(super::Ordering::Acquire);
  if super::RACE.swap(false,super::Ordering::AcqRel) {
   // Deterministic permitted interleaving: source publishes after clock sample,
   // before consumer acquires the actual source-set mutex.
   let epoch=super::SourceEpoch{process_generation:30,source_generation:super::PUBLISHED_EPOCH.load(super::Ordering::Acquire)};
   let(tx,rx)=std::sync::mpsc::channel();
   std::thread::spawn(move||{super::CLOCK.store(sampled+1,super::Ordering::Release);super::publish(epoch,2,sampled+1);let _=tx.send(());});
   // Old consumer observes new frame against old clock; fixed consumer owns
   // the source mutex, so publication waits and the existing frame is coherent.
   let _=rx.recv_timeout(std::time::Duration::from_millis(100));
  }
  Some(sampled)
 }
}
'''
exports = 'concurrent_peer_admission_observed' if candidate else 'concurrent_peer_admission'
source += 'mod own_stereo_capture_runtime { pub(crate) use super::'+exports+'; }\n'+fresh+'\n'+admit+'\n'+jni.replace('pub extern ', '#[no_mangle]\npub extern ')+'\n'
source += '''
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_fixtureConfigure(_env:JNIEnv<'_>,_class:JClass<'_>,mode:jint){
 CLOCK.store(1000,Ordering::Release);RACE.store(mode==1,Ordering::Release);
 CLAIMED.store(mode!=2,Ordering::Release);CONFIGURED.store(mode!=3,Ordering::Release);QUIESCENT.store(mode!=5,Ordering::Release);PROCESS.store(if mode==7 {31}else{30},Ordering::Release);
 let epoch=SourceEpoch{process_generation:30,source_generation:EPOCH.fetch_add(1,Ordering::AcqRel)};
 PUBLISHED_EPOCH.store(epoch.source_generation,Ordering::Release);*ACTIVE_EPOCH.lock().unwrap()=Some(epoch);shared_sources().bind(StereoOrigin::OwnStereo,epoch).unwrap();publish(epoch,1,if mode==9 {1001}else{999});
 if mode==10 {CLOCK.store(4_000_001_000,Ordering::Release);}
 if mode==4 {*ACTIVE_EPOCH.lock().unwrap()=Some(SourceEpoch{process_generation:30,source_generation:epoch.source_generation+1});}
 let requested=[1,1,1,0,0,10,20,0,0,0,1|4|8|32|64,0,0,1,0,0];peer_projection_runtime::request_source(requested,true);
}
'''
(out/'src/lib.rs').write_text(source)
(out/'Cargo.toml').write_text('[package]\nname="actual-admission-jni-fixture"\nversion="0.1.0"\nedition="2021"\n[lib]\ncrate-type=["cdylib"]\n[dependencies]\njni="=0.21.1"\n')
for args in [['generate-lockfile','--offline'], ['build','--offline','--locked','--target-dir',a.target]]:
    p=subprocess.run([a.cargo,*args,'--manifest-path',str(out/'Cargo.toml')],capture_output=True,text=True)
    (out/(args[0]+'.stdout')).write_text(p.stdout);(out/(args[0]+'.stderr')).write_text(p.stderr)
    print(args[0],p.returncode)
    if p.returncode: print(p.stderr);raise SystemExit(p.returncode)
library=Path(a.target).resolve()/'debug'/('actual_admission_jni_fixture.dll' if sys.platform=='win32' else ('libactual_admission_jni_fixture.dylib' if sys.platform=='darwin' else 'libactual_admission_jni_fixture.so'))
(out/'library-path.txt').write_text(str(library))
print(library)
