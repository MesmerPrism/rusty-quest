#!/usr/bin/env python3
"""Execute production clock sampler and native renewal bodies with modeled OS/host.
No Android, JNI, LAN, authority acceptance or actual failure-cause claim.
"""
import argparse, hashlib, json, subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root, out = Path(args.root).resolve(), Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=False); (out/'src').mkdir()
    caller = root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/peer_lifecycle.rs'
    clock = root/'crates/rusty-quest-broker-authority/src/concurrent_clock_read.rs'
    raw, clock_raw = caller.read_bytes(), clock.read_bytes()
    text = raw.decode()
    body = text[text.index('fn renew_state_after_signed_pair('):text.index('fn read_os_authority_sample(')]
    pending = text[text.index('pub(super) fn renew_after_signed_pair('):text.index('fn renew(host:')]
    (out/'src/concurrent_clock_read.rs').write_bytes(clock_raw)
    (out/'src/lib.rs').write_text('#![allow(dead_code, noop_method_call)]\nmod concurrent_clock_read;\nmod caller {\n'+PRELUDE+body+pending+TESTS+'\n}\n')
    (out/'Cargo.toml').write_text('[package]\nname="concurrent-clock-controls"\nversion="0.0.0"\nedition="2021"\n[dependencies]\nserde_json="1"\n[workspace]\n')
    result = subprocess.run(['cargo','test','--offline','--','--test-threads=1'],cwd=out,capture_output=True)
    (out/'stdout.log').write_bytes(result.stdout); (out/'stderr.log').write_bytes(result.stderr)
    assert caller.read_bytes() == raw and clock.read_bytes() == clock_raw
    report = dict(exit=result.returncode, caller_sha256=hashlib.sha256(raw).hexdigest(),
                  clock_sha256=hashlib.sha256(clock_raw).hexdigest(),
                  limits='Unmodified production sampler, validator, renew_state_after_signed_pair and renew_after_signed_pair bodies. Modeled OS reads, provider and capability; no JNI/Android typecheck, deployed fix, or actual failure-cause claim.')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2))
    print(result.stdout.decode(errors='replace')); print(result.stderr.decode(errors='replace'))
    raise SystemExit(result.returncode)


PRELUDE = r'''
use crate::concurrent_clock_read::*;
use serde_json::{Value,json};
use std::cell::{RefCell,RefMut};
use std::collections::VecDeque;
thread_local! { static OS: RefCell<VecDeque<ConcurrentClockSample>> = RefCell::new(VecDeque::new()); }
fn read_os_authority_sample() -> Result<ConcurrentClockSample,ConcurrentClockRejection> {
    OS.with(|v| v.borrow_mut().pop_front().ok_or(ConcurrentClockRejection::OsReadUnavailable))
}
struct Capability; impl Capability { fn require_live(&self)->Result<(),String>{Ok(())} }
#[derive(Default)] struct State {
    renewed_session_proofs:Vec<Value>,renewals:Vec<Value>,authority_clock_read:Option<Value>,
    renewal_pending:bool,last_failure:Option<String>,last_failure_action:Option<&'static str>,
}
struct Provider { prior:ConcurrentClockBounds,effects:usize,wall:Option<u64> }
impl Provider {
    fn read_concurrent_peer_clock_detailed(&self,w:i64,m:u64,u:u64)->Result<u64,ConcurrentClockRejection>{
        validate_concurrent_clock(self.prior,None,w,m,u)
    }
    fn renew_concurrent_peer_authority(&mut self,_c:&u64,_v:&Value,now:u64,_e:&str)->Result<Value,()>{
        self.effects+=1; self.wall=Some(now); Ok(json!({"actual_fixture_effect":self.effects}))
    }
}
struct Host<'a>{capability:Capability,provider:&'a RefCell<Provider>,peer_lifecycle:&'a RefCell<State>}
struct Checkout<'a,T>{value:RefMut<'a,T>}
impl<'a,T> Checkout<'a,T>{
    fn take(value:&'a RefCell<T>)->Result<Self,String>{Ok(Self{value:value.borrow_mut()})}
    fn get(&mut self)->&mut T{&mut self.value}
}
fn require_renewable_media(_s:&State)->Result<(),String>{Ok(())}
fn entropy()->Result<String,String>{Ok("fixture".into())}
fn projection(_h:&Host)->Result<(),String>{Ok(())}
fn safe_decode(_e:serde_json::Error)->String{"fixture_decode".into()}
'''

TESTS = r'''
#[cfg(test)] mod native_caller_controls {
 use super::*;
 fn provider()->RefCell<Provider>{RefCell::new(Provider{prior:ConcurrentClockBounds{
    healthy:true,identity_matches:true,wall_ms:1000,monotonic_ns:1_000_000_000,sequence:7},effects:0,wall:None})}
 fn install(samples:Vec<ConcurrentClockSample>){OS.with(|v|*v.borrow_mut()=samples.into());}
 fn slow()->ConcurrentClockSample{ConcurrentClockSample{wall_ms:1000,before_ns:1_000_000_000,after_ns:1_020_000_000}}
 fn narrow()->ConcurrentClockSample{ConcurrentClockSample{wall_ms:1020,before_ns:1_020_000_000,after_ns:1_020_001_000}}
 #[test] fn actual_native_caller_samples_then_mutates_once(){
    let p=provider();let s=RefCell::new(State::default());let h=Host{capability:Capability,provider:&p,peer_lifecycle:&s};
    install(vec![slow(),narrow()]); let proof=json!({"fixture":"same_signed_pair"});
    renew_after_signed_pair(&h,&proof).unwrap();
    assert_eq!(p.borrow().effects,1);assert_eq!(p.borrow().wall,Some(1020));
    let st=s.borrow();assert!(!st.renewal_pending);assert!(st.last_failure.is_none());
    assert_eq!(st.renewals.len(),1);assert_eq!(st.renewed_session_proofs,vec![proof]);
    let d=st.authority_clock_read.as_ref().unwrap();assert_eq!(d["samples"].as_array().unwrap().len(),2);
    assert_eq!(d["samples"][0]["rejection"],"excessive_read_uncertainty");
    assert!(d["samples"][1]["rejection"].is_null());
 }
 #[test] fn actual_pending_caller_exhaustion_preserves_history_and_failure(){
    let p=provider();let mut initial=State::default();initial.renewals.push(json!({"old":1}));
    initial.renewed_session_proofs.push(json!({"old_proof":1}));let s=RefCell::new(initial);
    let h=Host{capability:Capability,provider:&p,peer_lifecycle:&s};install(vec![slow();3]);
    assert_eq!(renew_after_signed_pair(&h,&json!({"new_proof":2})).unwrap_err(),"current OS authority clock rejected");
    let st=s.borrow();assert!(st.renewal_pending);assert_eq!(st.renewals,vec![json!({"old":1})]);
    assert_eq!(st.renewed_session_proofs,vec![json!({"old_proof":1})]);assert_eq!(p.borrow().effects,0);
    assert_eq!(st.last_failure_action,Some("renew_authority"));assert_eq!(st.last_failure.as_deref(),Some("current OS authority clock rejected"));
    assert_eq!(st.authority_clock_read.as_ref().unwrap()["samples"].as_array().unwrap().len(),3);
 }
 #[test] fn actual_caller_hard_failure_wide_read_does_not_resample(){
    let p=provider();p.borrow_mut().prior.healthy=false;let s=RefCell::new(State::default());
    let h=Host{capability:Capability,provider:&p,peer_lifecycle:&s};install(vec![slow(),narrow()]);
    assert!(renew_after_signed_pair(&h,&json!({"proof":1})).is_err());
    assert_eq!(p.borrow().effects,0);OS.with(|v|assert_eq!(v.borrow().len(),1));
    assert_eq!(s.borrow().authority_clock_read.as_ref().unwrap()["samples"][0]["rejection"],"unhealthy");
    assert!(s.borrow().renewal_pending);
 }
 #[test] fn already_joined_same_proof_does_not_read_or_repeat_effect(){
    let p=provider();let mut initial=State::default();let proof=json!({"old_proof":1});
    initial.renewed_session_proofs.push(proof.clone());initial.renewals.push(json!({"old":1}));let s=RefCell::new(initial);
    let h=Host{capability:Capability,provider:&p,peer_lifecycle:&s};install(vec![]);
    assert_eq!(renew_after_signed_pair(&h,&proof).unwrap(),json!({"old":1}));assert_eq!(p.borrow().effects,0);
    assert!(s.borrow().authority_clock_read.is_none());
 }
}
'''

if __name__ == '__main__': main()
