#!/usr/bin/env python3
"""Selected Broker producer to actual Quest current-receipt/clock bodies.

Supply an isolated, already-resolved Broker manifest and registry; writes stay
under a fresh target output. Phase/clock fixtures are explicit, not Android proof.
"""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,json,pathlib,subprocess,shutil,os,importlib.util

def ref(p):
 b=p.read_bytes();return dict(path=str(p),bytes=len(b),sha256=hashlib.sha256(b).hexdigest())

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--predecessor',action='store_true');p.add_argument('--output',required=True);p.add_argument('--fixture-manifest',required=True);p.add_argument('--registry',required=True)
 a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 spec=importlib.util.spec_from_file_location('sdk_items',root/'tools/checks/Test-PreparedSdkHost.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 native=root/'apps/spatial-camera-panel-android/native-receipt';source=native/'src/embedded_duplex/fresh_pair_setup.rs';clock=native/'src/embedded_duplex/preparation_clock.rs';text=source.read_text()
 if a.predecessor:
  text=subprocess.check_output(['git','show','HEAD:'+source.relative_to(root).as_posix()],cwd=root).decode()
 (out/'effective-fresh-pair-setup.rs').write_text(text)
 fixture=native/'host-tests/CurrentReceiptHost.rs';broker=out/'crates/broker';shutil.copytree(root/'crates/rusty-quest-broker-authority/src',broker/'src')
 manifest=pathlib.Path(a.fixture_manifest).resolve();registry=pathlib.Path(a.registry).resolve();(broker/'Cargo.toml').write_bytes(manifest.read_bytes());cargo_home=out.parent/'cargo-home'
 if not cargo_home.exists():shutil.copytree(registry,cargo_home)
 for f in ['media-stream-sessions/display-composite-mediaprojection-h264.plan.json','media-runtime-products/camera2-surface.binding.json','device-link/direct-p2p-socket-route.pass.json']:
  dest=out/'fixtures'/f;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes((root/'fixtures'/f).read_bytes())
 bodies='\n'.join(m.item(text,n) for n in ['fn peers(','fn check_current(','fn authenticated_context_time(','fn phase<']).replace('crate::embedded_duplex::preparation_clock::','clock_body::')
 # On the unchanged predecessor run the real strict validator, producing RED.
 remote=m.item(text,'fn check_remote_current(') if 'fn check_remote_current(' in text else 'fn check_remote_current(h:&Host,_id:&str,v:&Value,s:&str,e:u64)->Result<(),String>{check_current(h,v,s,e)}'
 harness=r'''mod actual_current_consumer {
use serde_json::{Value,json};use rusty_manifold_peer::*;
use std::sync::{Arc,atomic::{AtomicU64,AtomicBool,AtomicUsize,Ordering},Mutex,OnceLock};
#[path=CLOCK] pub(crate) mod clock_body;
mod embedded_duplex{pub(crate)use super::clock_body as preparation_clock;}
struct Clock(Arc<AtomicU64>);impl Clock{fn now_ms(&self)->Result<u64,String>{Ok(self.0.load(Ordering::SeqCst))}}
struct Capability{live:AtomicBool,polls:AtomicUsize}impl Capability{fn require_live(&self)->Result<(),String>{self.polls.fetch_add(1,Ordering::SeqCst);if self.live.load(Ordering::SeqCst){Ok(())}else{Err("actual capability retired".into())}}}
struct Host{local_peer_id:String,remote_peer_id:String,clock:Clock,capability:Capability,pair_state:Arc<()>}
struct Identity{pair_state:Arc<()>}
#[derive(Default)]struct Process{closing:bool,initializing:bool,lease_integrity_failed:bool,fresh_setup_request:Option<String>,fresh_setup_stop_request:Option<String>,host:Option<Identity>}
fn process()->&'static Mutex<Process>{static P:OnceLock<Mutex<Process>>=OnceLock::new();P.get_or_init(||Mutex::new(Process::default()))}
struct FreshSetupPhase<'a>{host:&'a Host,request:&'a str}
fn host(now:u64,q:bool)->Host{let pair_state=Arc::new(());*process().lock().unwrap()=Process{fresh_setup_request:Some("actual-phase".into()),host:Some(Identity{pair_state:pair_state.clone()}),..Process::default()};Host{local_peer_id:if q{"peer.quest-a"}else{"peer.quest-b"}.into(),remote_peer_id:if q{"peer.quest-b"}else{"peer.quest-a"}.into(),clock:Clock(Arc::new(AtomicU64::new(now))),capability:Capability{live:AtomicBool::new(true),polls:AtomicUsize::new(0)},pair_state}}
fn safe_decode(_:impl std::fmt::Display)->String{"fixture decode".into()}
PRODUCTION
REMOTE
pub(super)fn validate(v:&Value,s:&str,e:u64,n:u64,q:bool)->Result<(),String>{check_current(&host(n,q),v,s,e)}
pub(super)fn remote_after_catchup(v:&Value,s:&str,e:u64,n:u64,end:u64,q:bool)->Result<(),String>{
 let h=host(n,q);let time=h.clock.0.clone();let worker=std::thread::spawn(move||{std::thread::sleep(std::time::Duration::from_millis(15));let gate=process().try_lock().expect("no phase mutex held across wait");time.store(end,Ordering::SeqCst);drop(gate)});
 let result=check_remote_current(&h,"actual-phase",v,s,e);worker.join().unwrap();if result.is_ok(){assert!(h.capability.polls.load(Ordering::SeqCst)>0);assert!(v["validated_at_ms"].as_u64().unwrap()<=h.clock.now_ms().unwrap());}result
}
pub(super)fn refuse_before_wait(v:&Value,s:&str,e:u64,n:u64,q:bool){let h=host(n,q);assert!(check_remote_current(&h,"actual-phase",v,s,e).is_err());assert_eq!(h.capability.polls.load(Ordering::SeqCst),0);}
pub(super)fn remote_refusals(v:&Value,s:&str,e:u64,n:u64,q:bool){
 let h=host(n,q);assert!(check_remote_current(&h,"foreign-phase",v,s,e).is_err());h.capability.live.store(false,Ordering::SeqCst);assert!(check_remote_current(&h,"actual-phase",v,s,e).is_err());
 let issued=v["validated_at_ms"].as_u64().unwrap();remote_after_catchup(v,s,e,issued-2000,issued,q).unwrap();assert!(check_remote_current(&host(issued-2001,q),"actual-phase",v,s,e).is_err());
 assert!(remote_after_catchup(v,s,e,n,e,q).is_err()); // real expiry crossed during wait
 let h=Arc::new(host(n,q));let second=h.clone();let worker=std::thread::spawn(move||{std::thread::sleep(std::time::Duration::from_millis(10));process().lock().unwrap().fresh_setup_stop_request=Some("actual-phase".into());second.capability.live.store(false,Ordering::SeqCst)});assert!(check_remote_current(&h,"actual-phase",v,s,e).is_err());worker.join().unwrap();
 for kind in 0..6 {let h=host(n,q);let worker=std::thread::spawn(move||{std::thread::sleep(std::time::Duration::from_millis(10));let mut gate=process().lock().unwrap();match kind{0=>gate.closing=true,1=>gate.initializing=true,2=>gate.lease_integrity_failed=true,3=>gate.fresh_setup_request=Some("foreign-phase".into()),4=>gate.host=None,_=>gate.fresh_setup_stop_request=Some("actual-phase".into())}});assert!(check_remote_current(&h,"actual-phase",v,s,e).is_err());worker.join().unwrap();}
 let mut unknown=v.clone();unknown["unrecognized_authority"]=json!(true);refuse_before_wait(&unknown,s,e,n,q);
 let mut badtime=v.clone();badtime["validated_at_ms"]=json!("not-a-time");refuse_before_wait(&badtime,s,e,n,q);
 let mut old=v.clone();old["session_id"]=json!("session.retired-life");refuse_before_wait(&old,s,e,n,q);
}
}
'''.replace('CLOCK',json.dumps(clock.as_posix())).replace('PRODUCTION',bodies).replace('REMOTE',remote)
 (broker/'src/current_receipt_host.rs').write_text(harness+fixture.read_text())
 suite=broker/'src/source_bound_roots_tests.rs';s=suite.read_text();i=s.rfind('}');suite.write_text(s[:i]+'\ninclude!("current_receipt_host.rs");\n'+s[i:])
 env=os.environ.copy();env['CARGO_HOME']=str(cargo_home)
 cmd=['cargo','test','--offline','--lib','--manifest-path',str(broker/'Cargo.toml'),'--target-dir',str(out.parent/'cargo-target'),'remote_current_receipt_production_controls','--','--nocapture']
 proc=subprocess.run(cmd,cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE);(out/'cargo.stdout').write_bytes(proc.stdout);(out/'cargo.stderr').write_bytes(proc.stderr)
 result=dict(schema='rusty.quest.current_receipt_host_result.v1',exit=proc.returncode,argv=cmd,predecessor=a.predecessor,source_refs=[ref(x)for x in [out/'effective-fresh-pair-setup.rs',source,clock,fixture,pathlib.Path(__file__).resolve(),manifest]],stdout=ref(out/'cargo.stdout'),stderr=ref(out/'cargo.stderr'),limits='Actual selected Broker/Manifold producer plus extracted Quest consumer and actual clock helper; actual phase body with modeled Host/capability/process fields and clock samples. No Android/JNI/network/current device receipt or second media lifetime proof.')
 if proc.returncode==0:
  assert text.count('check_remote_current(')==5
  assert 'check_remote_current(host,id,&b.payload["initiator_current"],&a.session,expiry,)?' in ''.join(text.split())
  assert 'check_remote_current(host, id, &remote_current' in text
  assert 'check_remote_current(host, id, &final_remote' in text
  complete=m.item(text,'fn complete(');assert 'check_remote_current(\n        host,\n        id,' in complete
  assert 'check_current(host, &actual, &a.session, expiry)?' in complete
  assert 'check_current(host, &local_current, &session, expiry)?' in text
  baseline=subprocess.check_output(['git','show','HEAD:apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/fresh_pair_setup.rs'],cwd=root).decode()
  assert m.item(baseline,'fn check_current(')==m.item(text,'fn check_current(')
  clock_cmd=['rustc','--edition','2021','--test',str(clock),'-o',str(out/'clock-tests.exe')]
  for label,argv in [('clock-compile',clock_cmd),('clock-tests',[str(out/'clock-tests.exe')])]:
   c=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE);(out/(label+'.stdout')).write_bytes(c.stdout);(out/(label+'.stderr')).write_bytes(c.stderr);assert c.returncode==0,label
  decode=m.item(text,'fn decode(');exchange=m.item(text,'fn exchange(')
  assert '.verify_strict(' in decode and exchange.index('decode(host, &reply)')<exchange.index('Ok(b.payload)')
  assert 'b.kind != response || b.request_id != id' in exchange and 'fresh reply phase differs' in exchange
  wrapper=m.item(text,'fn check_remote_current(')
  assert wrapper.index('receipt.peer_ids != peers(host)?')<wrapper.index('authenticated_context_time(')<wrapper.index('check_current(host, value, session, expiry)')
  assert not any(token in wrapper for token in ['.callbacks','.authority','exchange(','sign('])
  result['clock_helper_unit_tests']=3;result['four_remote_two_strict_local_wiring']=True;result['original_strict_validator_byte_equal']=True
 (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(ref(out/'RESULT.json')));return proc.returncode
if __name__=='__main__':raise SystemExit(main())
