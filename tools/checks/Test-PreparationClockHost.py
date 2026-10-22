#!/usr/bin/env python3
"""Actual context wait/wiring controls; clock/phase fixture, no Android authority."""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,json,pathlib,subprocess,re
from importlib.util import spec_from_file_location,module_from_spec

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 spec=spec_from_file_location('sdk_items',root/'tools/checks/Test-PreparedSdkHost.py');sdk=module_from_spec(spec);spec.loader.exec_module(sdk)
 native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex';setup=(native/'fresh_pair_setup.rs').read_text();prep=(native/'fresh_pair_preparation.rs').read_text()
 module=native/'preparation_clock.rs';raw=module.read_bytes()
 wrapper=sdk.item(setup,'fn authenticated_context_time(')
 fixture='''#![allow(dead_code)]
 use std::sync::{Arc,atomic::{AtomicU64,AtomicBool,Ordering}};
 #[path=CLOCK] pub(crate) mod clock;
 mod embedded_duplex{pub(crate) use crate::clock as preparation_clock;}
 struct Clock(Arc<AtomicU64>);impl Clock{fn now_ms(&self)->Result<u64,String>{Ok(self.0.load(Ordering::SeqCst))}}
 struct Host{clock:Clock,stopped:AtomicBool}
 fn phase(h:&Host,id:&str)->Result<(),String>{if id!="actual-phase"||h.stopped.load(Ordering::SeqCst){Err("actual phase/Stop changed".into())}else{Ok(())}}
 WRAPPER
 #[test]fn actual_wrapper_waits_for_real_sample_then_original_strict_gate(){let now=Arc::new(AtomicU64::new(100000));let h=Host{clock:Clock(now.clone()),stopped:AtomicBool::new(false)};let worker=std::thread::spawn(move||{std::thread::sleep(std::time::Duration::from_millis(10));now.store(101000,Ordering::SeqCst)});let actual=authenticated_context_time(&h,"actual-phase",101000).unwrap();worker.join().unwrap();assert_eq!(actual,101000);assert!(101000<=actual);assert_eq!(h.clock.now_ms().unwrap(),101000);}
 #[test]fn actual_wrapper_refuses_foreign_phase_stop_and_beyond_bound(){let h=Host{clock:Clock(Arc::new(AtomicU64::new(100000))),stopped:AtomicBool::new(false)};assert!(authenticated_context_time(&h,"foreign",101000).is_err());assert!(authenticated_context_time(&h,"actual-phase",102001).is_err());h.stopped.store(true,Ordering::SeqCst);assert!(authenticated_context_time(&h,"actual-phase",101000).is_err());}
 '''.replace('CLOCK',json.dumps(module.as_posix())).replace('WRAPPER',wrapper)
 (out/'clock-wrapper.rs').write_text(fixture,encoding='utf-8')
 def run(name,argv):
  r=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr);assert r.returncode==0,name
 run('compile',['rustc','--edition','2021','--test',str(out/'clock-wrapper.rs'),'-o',str(out/'clock.exe')]);run('tests',[str(out/'clock.exe')])
 # Guard placement joins the actual unchanged signed-envelope/supplier path.
 sign=sdk.item(setup,'fn sign_setup(');apply=sdk.item(setup,'fn apply_setup(')
 assert sign.index('fresh_pair_setup_transaction_id(c)?')<sign.index('authenticated_context_time(')<sign.index('fresh_setup_physical_eligibility()')<sign.index('sign_validated_fresh_pair_setup(')
 assert sign.index('view.observed_at_ms > c.issued_at_ms')<sign.index('authenticated_context_time(')
 assert apply.index('verify_fresh_pair_setup_request(request)?')<apply.index('a.context.as_ref() != Some(&request.context)')<apply.index('authenticated_context_time(')<apply.index('mark_dispatch(')<apply.index('.apply_fresh_pair_setup(')
 sign2=sdk.item(prep,'fn sign(');apply2=sdk.item(prep,'fn apply(')
 assert sign2.index('fresh_phase_prepare_transaction_id(context)?')<sign2.index('authenticated_context_time(')<sign2.index('mark_dispatch(')<sign2.index('sign_validated_fresh_phase_prepare(')
 assert apply2.index('verify_fresh_phase_prepare_request(request)?')<apply2.index('a.context.as_ref() != Some(&request.context)')<apply2.index('authenticated_context_time(')<apply2.index('mark_dispatch(')<apply2.index('.apply_fresh_phase_prepare(')
 for text,signature in [(setup,'pub(super) fn handle_frame('),(prep,'pub(super) fn handle(')]:
  body=sdk.item(text,signature)
  if text==setup:assert body.index('decode(')<body.index('preparation::handle(')
 # All three constructors use actual observed-time max only as a wait target;
 # the emitted issued time is returned by the real clock, not a fabricated max.
 assert re.sub(r'\s+','',setup).count('letobserved=views.iter().map(|view|view.observed_at_ms).max()')==1
 assert re.sub(r'\s+','',prep).count('letobserved=views.iter().map(|view|view.observed_at_ms).max()')==2
 assert 'issued_at_ms: now.saturating_sub(ISSUE_BACKDATE_MS)' in setup
 assert re.search(r'\.saturating_sub\(\s*crate::embedded_duplex::pair_lifetime_policy::ISSUE_BACKDATE_MS\s*,?\s*\)',(native/'prepared_start.rs').read_text())
 assert 'max_future_skew_ms: 0' in (native/'runtime_host.rs').read_text()
 assert raw==module.read_bytes()
 report={'passed':True,'actual_wait_groups':5,'production_callsite_controls':8,'source_pins':[{ 'path':str(native/name),'sha256':hashlib.sha256((native/name).read_bytes()).hexdigest()} for name in ['preparation_clock.rs','fresh_pair_setup.rs','fresh_pair_preparation.rs','prepared_start.rs']],'limits':__doc__}
 (out/'RESULT.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
if __name__=='__main__':main()
