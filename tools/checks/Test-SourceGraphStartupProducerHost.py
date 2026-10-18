"""Compile actual startup/selection bodies with modeled OS, renderer and join seams."""
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
#![allow(dead_code, non_snake_case)]
use std::sync::{Arc, Mutex, LazyLock, Condvar};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant};
use std::sync::mpsc::{self, Sender, Receiver};
struct ANativeWindow;
mod vk {pub type ANativeWindow=super::ANativeWindow;}
#[path=r"READINESS_PATH"] mod source_graph_startup;
mod stereo_input_set { #[derive(Clone,Copy,Debug,PartialEq,Eq)] EPOCH }
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)] CLAIM
CLAIM_IMPL
#[derive(Default)] OWNER
static PEER_COMMON_GRAPH_SESSION: LazyLock<Mutex<PeerCommonGraphSessionOwner>>=LazyLock::new(||Mutex::new(PeerCommonGraphSessionOwner::default()));
static ACTIVE_PEER_COMMON_GRAPH_WORKERS:AtomicUsize=AtomicUsize::new(0);
const CAMERA_HWB_PROBE_MAX_FRAMES:u32=1800;
static RELEASES:AtomicUsize=AtomicUsize::new(0);
static GENERATION:AtomicU64=AtomicU64::new(42);
static SOURCE:AtomicU64=AtomicU64::new(7);
static ROUTE:AtomicBool=AtomicBool::new(true);
unsafe fn ACameraNativeWindow_release(_w:*mut ANativeWindow){RELEASES.fetch_add(1,Ordering::SeqCst);}
fn publish_acquisition_stopped_if_quiescent(){}
fn log_marker(value:String){LOGS.lock().unwrap().push(value);}
fn marker_token(value:&str)->&str{value}
static LOGS:Mutex<Vec<String>>=Mutex::new(Vec::new());
mod own_packed_pool_jni {pub fn process_generation()->Result<u64,String>{Ok(crate::GENERATION.load(crate::Ordering::SeqCst))}}
mod own_packed_pool {pub fn monotonic_ns()->Option<u64>{Some(1)}}
mod own_stereo_capture_runtime {
 pub fn capture_route_selected()->bool{crate::ROUTE.load(crate::Ordering::SeqCst)}
 pub fn own_image_fresh_observed()->Result<crate::stereo_input_set::SourceEpoch,String>{Ok(crate::stereo_input_set::SourceEpoch{process_generation:crate::GENERATION.load(crate::Ordering::SeqCst),source_generation:crate::SOURCE.load(crate::Ordering::SeqCst)})}
 pub fn capture_claimed()->bool{true} pub fn capture_configured()->bool{true}
 pub fn concurrent_peer_epoch_is_current(proof:[i64;5])->bool{proof[3]==crate::GENERATION.load(crate::Ordering::SeqCst)as i64&&proof[4]==crate::SOURCE.load(crate::Ordering::SeqCst)as i64}
}
mod own_packed_gpu_holds {pub fn renderer_holds_retired(_e:crate::stereo_input_set::SourceEpoch)->bool{true}}
mod spatial_public_multistack_runtime {pub fn source_banks_enabled()->bool{false}}
mod spatial_stereo_qualification {pub fn physical_cleanup_terminal()->bool{true}}
mod peer_projection_runtime {
 pub const SOURCE_ABI_WORDS:usize=8;pub const SOURCE_PEER:i64=2;
 #[derive(Debug)]pub struct SourceReceipt;
 pub fn request_source_with_current_carrier(_w:[i64;8],_p:[i64;3])->SourceReceipt{SourceReceipt}
}
mod stereo_renderer_terminal {
 pub type GraphToken=u64;
 pub fn begin_after_owner_claim(_p:u64,_r:u64)->Option<u64>{let t=crate::TOKEN.fetch_add(1,crate::Ordering::SeqCst);crate::FINISH.lock().unwrap().push((t,None));Some(t)}
 pub fn finish(t:Option<u64>,r:Result<(),&str>,_n:u64){if let Some(t)=t{crate::FINISH.lock().unwrap().iter_mut().find(|x|x.0==t).unwrap().1=Some(r.map_err(str::to_owned));}crate::current().event(8);}
 pub fn failure(_t:Option<u64>,_s:&str,_n:u64){crate::current().event(4);}
 pub fn record_cleanup(_t:Option<u64>,_r:bool,_n:u64){}
}
static TOKEN:AtomicU64=AtomicU64::new(1);
static FINISH:Mutex<Vec<(u64,Option<Result<(),String>>)>>=Mutex::new(Vec::new());
enum Command {Ready, ExitOk, ExitErr, ReadyExit, LateClean}
struct Control {tx:Sender<Command>,rx:Mutex<Receiver<Command>>,events:Mutex<u32>,cv:Condvar,spawn_exits:bool,ready_accepted:AtomicBool}
impl Control {
 fn new(spawn_exits:bool)->Arc<Self>{let(tx,rx)=mpsc::channel();Arc::new(Self{tx,rx:Mutex::new(rx),events:Mutex::new(0),cv:Condvar::new(),spawn_exits,ready_accepted:AtomicBool::new(false)})}
 fn event(&self,n:u32){let mut e=self.events.lock().unwrap();*e|=n;self.cv.notify_all();}
 fn wait(&self,n:u32){let start=Instant::now();let mut e=self.events.lock().unwrap();while *e&n!=n{assert!(start.elapsed()<Duration::from_secs(25),"modeled seam stalled {n}");e=self.cv.wait_timeout(e,Duration::from_millis(100)).unwrap().0;}}
 fn send(&self,c:Command){self.tx.send(c).unwrap();}
}
static CURRENT:Mutex<Option<Arc<Control>>>=Mutex::new(None);
fn current()->Arc<Control>{CURRENT.lock().unwrap().as_ref().unwrap().clone()}
// Real OS threads and JoinHandle::is_finished/join; only the OS spawn edge is modeled.
mod thread {
 pub type JoinHandle<T>=std::thread::JoinHandle<T>;
 pub struct Builder(std::thread::Builder);
 impl Builder {
  pub fn new()->Self{Self(std::thread::Builder::new())}
  pub fn name(self,s:String)->Self{Self(self.0.name(s))}
  pub fn spawn<F,T>(self,f:F)->std::io::Result<JoinHandle<T>>where F:FnOnce()->T+Send+'static,T:Send+'static{
   let c=crate::current();let h=self.0.spawn(f)?;
   if c.spawn_exits{c.wait(8);while !h.is_finished(){std::thread::yield_now();}}
   Ok(h)
  }
 }
}
unsafe fn render_source_set_common_graph(_w:*mut vk::ANativeWindow,_width:u32,_height:u32,_frames:u32,cancellation:Arc<AtomicBool>,_d:Option<u64>,readiness:&source_graph_startup::Readiness,ready_tx:mpsc::SyncSender<Result<(),String>>)->Result<(),String>{
 let c=current();c.event(1);
 loop {let command=c.rx.lock().unwrap().recv().unwrap();match command{
  Command::Ready|Command::ReadyExit=>{
   // Exact production producer transition after resource setup, not a live-thread proxy.
   PRODUCER_READY
   c.ready_accepted.store(true,Ordering::SeqCst);c.event(2);
   if matches!(command,Command::ReadyExit){return Ok(());}
  },
  Command::ExitOk=>return Ok(()),Command::ExitErr=>return Err("modeled-renderer-setup-failure".into()),
  Command::LateClean=>{assert!(cancellation.load(Ordering::SeqCst));assert!(!readiness.publish_ready());c.event(16);return Ok(());}
 }}
}
START_FUNCTION
SELECT_FUNCTION

#[cfg(test)]mod producer_tests {
 use super::*;
 fn setup(spawn_exits:bool)->Arc<Control>{
  assert_eq!(ACTIVE_PEER_COMMON_GRAPH_WORKERS.load(Ordering::SeqCst),0);
  *PEER_COMMON_GRAPH_SESSION.lock().unwrap()=PeerCommonGraphSessionOwner::default();
  GENERATION.store(42,Ordering::SeqCst);SOURCE.store(7,Ordering::SeqCst);ROUTE.store(true,Ordering::SeqCst);
  RELEASES.store(0,Ordering::SeqCst);LOGS.lock().unwrap().clear();FINISH.lock().unwrap().clear();
  let c=Control::new(spawn_exits);*CURRENT.lock().unwrap()=Some(c.clone());c
 }
 fn begin()->thread::JoinHandle<i64>{std::thread::spawn(||unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)})}
 fn select(window:usize)->bool{select_peer_source_with_own_actor([0,0,2,0,0,11,12,0],window,[1,11,12,GENERATION.load(Ordering::SeqCst)as i64,SOURCE.load(Ordering::SeqCst)as i64]).is_some()}
 fn wait_until(f:impl Fn()->bool){let start=Instant::now();while !f(){assert!(start.elapsed()<Duration::from_secs(25));std::thread::yield_now();}}
 fn retire(c:&Control){
  let worker={let mut o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();o.worker.take()};
  if let Some(w)=worker{if !w.is_finished(){c.send(Command::ExitOk);}w.join().unwrap();}
  *PEER_COMMON_GRAPH_SESSION.lock().unwrap()=PeerCommonGraphSessionOwner::default();
  assert_eq!(ACTIVE_PEER_COMMON_GRAPH_WORKERS.load(Ordering::SeqCst),0);
 }
 fn ready(c:&Control)->i64{let s=begin();c.wait(1);assert!(!s.is_finished());c.send(Command::Ready);let r=s.join().unwrap();assert_eq!(r,1);r}

 #[test]fn actual_delayed_setup_and_pending_live_reuse_and_consumer_are_distinct(){
  let c=setup(false);let s=begin();c.wait(1);
  assert!(!s.is_finished());assert!(!select(10));
  assert_eq!(unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)},0);
  assert_eq!(ACTIVE_PEER_COMMON_GRAPH_WORKERS.load(Ordering::SeqCst),1);
  std::thread::sleep(Duration::from_millis(30));assert!(!s.is_finished());
  c.send(Command::Ready);assert_eq!(s.join().unwrap(),1);assert!(select(10));
  assert_eq!(unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)},1);
  retire(&c);assert_eq!(RELEASES.load(Ordering::SeqCst),3);
 }
 #[test]fn exact_window_source_epoch_process_epoch_cancel_and_ready_required_for_reuse(){
  let c=setup(false);ready(&c);
  assert_eq!(unsafe{start_source_set_common_graph(11usize as *mut ANativeWindow,100,100,0)},0);assert!(!select(11));
  SOURCE.store(8,Ordering::SeqCst);assert_eq!(unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)},0);
  SOURCE.store(7,Ordering::SeqCst);GENERATION.store(43,Ordering::SeqCst);
  assert_eq!(unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)},0);
  GENERATION.store(42,Ordering::SeqCst);
  {let o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();o.cancellation.as_ref().unwrap().store(true,Ordering::SeqCst);}
  assert_eq!(unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)},0);assert!(!select(10));
  {let o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();o.cancellation.as_ref().unwrap().store(false,Ordering::SeqCst);o.startup_readiness.as_ref().unwrap().fail();}
  assert_eq!(unsafe{start_source_set_common_graph(10usize as *mut ANativeWindow,100,100,0)},0);assert!(!select(10));retire(&c);
 }
 #[test]fn source_epoch_drift_after_publication_is_not_admitted(){
  let c=setup(false);let s=begin();c.wait(1);SOURCE.store(8,Ordering::SeqCst);c.send(Command::Ready);
  wait_until(||PEER_COMMON_GRAPH_SESSION.lock().unwrap().worker.is_none());
  assert!(!s.is_finished());c.send(Command::ExitOk);assert_eq!(s.join().unwrap(),0);
  assert_eq!(PEER_COMMON_GRAPH_SESSION.lock().unwrap().claim.generation(),None);retire(&c);
 }
 #[test]fn ready_consumer_rejects_stale_owner_epoch_despite_fresh_current_proof(){
  let c=setup(false);ready(&c);assert!(select(10));
  // Proof is fresh and current according to the modeled Own/native authority,
  // but this actual renderer still owns the previously admitted source epoch.
  SOURCE.store(8,Ordering::SeqCst);assert!(!select(10));SOURCE.store(7,Ordering::SeqCst);
  GENERATION.store(43,Ordering::SeqCst);
  {let mut o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();assert!(o.claim.release(42));assert!(o.claim.claim(43));}
  assert!(!select(10));
  GENERATION.store(42,Ordering::SeqCst);
  {let mut o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();assert!(o.claim.release(43));assert!(o.claim.claim(42));}
  assert!(select(10));retire(&c);
 }
 #[test]fn queued_ready_then_actual_worker_exit_never_admits(){
  let c=setup(true);c.send(Command::ReadyExit);assert_eq!(begin().join().unwrap(),0);
  assert!(!select(10));assert_eq!(PEER_COMMON_GRAPH_SESSION.lock().unwrap().claim.generation(),None);
  assert_eq!(RELEASES.load(Ordering::SeqCst),1);retire(&c);
 }
 #[test]fn failed_setup_is_joined_and_never_admitted(){
  let c=setup(false);let s=begin();c.wait(1);c.send(Command::ExitErr);assert_eq!(s.join().unwrap(),0);
  assert!(FINISH.lock().unwrap().iter().any(|x|x.1.as_ref().is_some_and(Result::is_err)));
  assert_eq!(PEER_COMMON_GRAPH_SESSION.lock().unwrap().claim.generation(),None);retire(&c);
 }
 // Install another incarnation with the SAME numeric generation to exercise Arc ABA.
 fn replace_claim()->(Arc<AtomicBool>,Arc<source_graph_startup::Readiness>,Sender<()>,thread::JoinHandle<()>){
  let cancel=Arc::new(AtomicBool::new(false));let ready=Arc::new(source_graph_startup::Readiness::default());assert!(ready.publish_ready());
  let(tx,rx)=mpsc::channel();let worker=std::thread::spawn(move||{rx.recv().unwrap();});
  let old={let mut o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();let old=o.worker.take();o.cancellation=Some(cancel.clone());o.startup_readiness=Some(ready.clone());o.worker=Some(worker);o.window_address=20;old};
  (cancel,ready,tx,old.unwrap_or_else(||std::thread::spawn(||{})))
 }
 #[test]fn old_starter_after_ready_cannot_cancel_or_release_replacement_same_generation(){
  let c=setup(false);let s=begin();c.wait(1);
  let(cancel,ready,tx,old)=replace_claim();c.send(Command::Ready);
  assert_eq!(s.join().unwrap(),0);assert!(!cancel.load(Ordering::SeqCst));assert_eq!(ready.state(),source_graph_startup::State::Ready);
  {let o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();assert_eq!(o.claim.generation(),Some(42));assert!(Arc::ptr_eq(o.startup_readiness.as_ref().unwrap(),&ready));assert!(o.worker.is_some());}
  c.send(Command::ExitOk);old.join().unwrap();tx.send(()).unwrap();retire(&c);
 }
 #[test]fn timeout_join_then_late_clean_exit_cannot_succeed_or_release_new_incarnation(){
  let c=setup(false);let s=begin();c.wait(1);
  let(old_cancel,old_ready)={let o=PEER_COMMON_GRAPH_SESSION.lock().unwrap();(o.cancellation.as_ref().unwrap().clone(),o.startup_readiness.as_ref().unwrap().clone())};
  wait_until(||old_cancel.load(Ordering::SeqCst)&&PEER_COMMON_GRAPH_SESSION.lock().unwrap().worker.is_none());
  assert!(!s.is_finished());assert_eq!(old_ready.state(),source_graph_startup::State::Failed);
  let(cancel,ready,tx,dummy)=replace_claim();dummy.join().unwrap();
  c.send(Command::LateClean);assert_eq!(s.join().unwrap(),0);c.wait(16);
  assert_eq!(old_ready.state(),source_graph_startup::State::Failed);
  assert!(!cancel.load(Ordering::SeqCst));assert_eq!(ready.state(),source_graph_startup::State::Ready);
  assert_eq!(PEER_COMMON_GRAPH_SESSION.lock().unwrap().claim.generation(),Some(42));
  assert!(FINISH.lock().unwrap().iter().all(|x|x.1.as_ref().is_some_and(Result::is_err)));
  assert!(LOGS.lock().unwrap().iter().any(|x|x.contains("outcome=deadline")));
  tx.send(()).unwrap();retire(&c);assert_eq!(RELEASES.load(Ordering::SeqCst),1);
 }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, required=True)
    parser.add_argument('--rustc', required=True)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    out = args.output_root.resolve()
    if out.exists():
        raise ValueError('Output must be create-new')
    out.mkdir(parents=True)
    base = root/'apps/spatial-camera-panel-android/native-receipt/src'
    paths = [base/name for name in ('camera_hwb_source_set.rs','camera_hwb_probe.rs','source_graph_startup.rs','peer_projection_ingress.rs','stereo_input_set.rs')]
    held = {str(p):p.read_bytes() for p in paths}
    source, probe, _, ingress, epochs = [held[str(p)].decode() for p in paths]
    producer_start = source.index('        if cancellation.load(Ordering::Acquire) || !readiness.publish_ready()')
    producer_end = source.index('        while max_frames',producer_start)
    code = HARNESS.replace('READINESS_PATH',(base/'source_graph_startup.rs').as_posix())
    replacements = {
        'START_FUNCTION':block(source,'pub(crate) unsafe fn start_source_set_common_graph('),
        'SELECT_FUNCTION':block(probe,'pub(crate) fn select_peer_source_with_own_actor('),
        'OWNER':block(probe,'struct PeerCommonGraphSessionOwner'),
        'CLAIM_IMPL':block(ingress,'impl PeerSessionClaimState'),
        'CLAIM':block(ingress,'pub(crate) struct PeerSessionClaimState'),
        'EPOCH':block(epochs,'pub(crate) struct SourceEpoch'),
        'PRODUCER_READY':source[producer_start:producer_end],
    }
    # The function, owner, producer readiness edge, selector and claim bodies are unchanged.
    for key, value in replacements.items():
        code=code.replace(key,value)
    fixture=out/'producer.rs';fixture.write_text(code,encoding='utf-8')
    runs=[]
    for name, command in [('compile',[args.rustc,'--edition=2021','--test',str(fixture),'-o',str(out/'producer.exe')]),('controls',[str(out/'producer.exe'),'--test-threads=1','--nocapture'])]:
        result=subprocess.run(command,capture_output=True,timeout=70)
        (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
        runs.append(dict(name=name,exit_code=result.returncode))
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace')+result.stdout.decode(errors='replace'))
    assert all(p.read_bytes()==held[str(p)] for p in paths),'Production source changed during qualification; retain and rerun'
    result=dict(passed=True,scope='actual startup/selector/readiness/claim bodies; modeled renderer/OS/window release/physical cleanup, real threads/channels/joins; no Android or driver claim',runs=runs,
        source_pins=[dict(path=str(p),bytes=len(held[str(p)]),sha256=hashlib.sha256(held[str(p)]).hexdigest()) for p in paths],
        selected_body_sha256={key:hashlib.sha256(value.encode()).hexdigest() for key,value in replacements.items()},
        harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':
    main()
