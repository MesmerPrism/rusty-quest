#!/usr/bin/env python3
"""Run production initialization/diagnostic bodies with modeled capability and host.
No Android, JNI, device, or cleanup acceptance is claimed.
"""
import argparse, hashlib, json, subprocess, os
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',required=True);p.add_argument('--output',required=True)
    a=p.parse_args();root=Path(a.root).resolve();out=Path(a.output).resolve()
    out.mkdir(parents=True,exist_ok=False);(out/'src').mkdir()
    source=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/runtime_host.rs'
    raw=source.read_bytes();text=raw.decode()
    state=text[text.index('#[derive(Default)]\nstruct ProcessState'):text.index('struct HostLease(Host);')]
    # JNI lookup is not modeled as evidence: controls supply the capability directly.
    state=state[:state.index('pub(super) fn initialization_failure_read()')]
    initialization=text[text.index('struct InitializationGuard;'):text.index('fn authenticate_own_capture_lock(')]
    start=text.index('    let removed = {',text.index('fn close_no_media_runtime_with_capability'))
    end=text.index('    let disposition =',start)
    cleanup='fn commit_cleanup(expected_sha: &str) -> Result<(),String> {\n'+text[start:end]+'drop(removed); Ok(()) }\n'
    harness=PRELUDE+state+initialization+cleanup+TESTS
    (out/'Cargo.toml').write_text('[package]\nname="initialization-failure-controls"\nversion="0.0.0"\nedition="2021"\n[dependencies]\nserde_json="1"\n[workspace]\n')
    (out/'src/lib.rs').write_text(harness)
    run=subprocess.run(['cargo','test','--offline','--','--test-threads=1'],cwd=out,capture_output=True)
    (out/'stdout.log').write_bytes(run.stdout);(out/'stderr.log').write_bytes(run.stderr)
    assert source.read_bytes()==raw,'production source changed'
    if run.returncode == 0: java_controls(root,out)
    result=dict(exit=run.returncode,source_sha256=hashlib.sha256(raw).hexdigest(),
        limits='Production state/initializer/diagnostic and cleanup-commit bodies plus Java owner diagnostic method; modeled host, capability, route and JNI. Cleanup prerequisites outside commit block not executed. No Android typecheck, device validation, or cleanup claim.')
    (out/'RESULT.json').write_text(json.dumps(result,indent=2))
    print(run.stdout.decode(errors='replace'));print(run.stderr.decode(errors='replace'))
    raise SystemExit(run.returncode)

def java_controls(root,out):
    source=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessHost.java'
    raw=source.read_bytes();text=raw.decode()
    method=text[text.index('    CompletableFuture<String> ownerFailureDiagnosticRead('):text.index('    CompletableFuture<String> ownCaptureDiagnostic(')]
    jars=sorted((Path(os.environ['USERPROFILE'])/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'))
    assert jars,'Cached owner org.json jar required'
    (out/'DiagnosticJavaHost.java').write_text(JAVA_PREFIX+method+JAVA_TESTS)
    for name,args in [('javac',['javac','-cp',str(jars[-1]),'-d',str(out),str(out/'DiagnosticJavaHost.java')]),('java',['java','-cp',str(out)+os.pathsep+str(jars[-1]),'DiagnosticJavaHost'])]:
        run=subprocess.run(args,capture_output=True);(out/(name+'.stdout')).write_bytes(run.stdout);(out/(name+'.stderr')).write_bytes(run.stderr)
        assert run.returncode==0,run.stderr.decode(errors='replace')
    assert source.read_bytes()==raw

JAVA_PREFIX=r"""
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
public final class DiagnosticJavaHost {
 enum Phase{NEW,READY,FAILED}
 final AtomicReference<Phase> phase=new AtomicReference<>(Phase.FAILED);
 boolean localFixture=false; Object platform=new Object();
 static final class Fence {boolean live=true;long generation(){return 29;}String epochId(){return "test.epoch";}void requireLive(long g){if(!live||g!=29)throw new IllegalStateException("changed");}}
 Fence processFence=new Fence();long nativeExecutorGeneration=7;
 String nativeAppRecordSha256="bbbb",runtimeConfigSha256="aaaa",ownFeatureLockSha256="cccc";
 static final class EmbeddedDuplexNative {static String value;static String ownerFailureDiagnosticRead(){return value;}}
 interface Task {String run()throws Exception;}
 CompletableFuture<String> submit(Task t){try{return CompletableFuture.completedFuture(t.run());}catch(Exception e){CompletableFuture<String> f=new CompletableFuture<>();f.completeExceptionally(e);return f;}}
 void requireFreshProcess(){processFence.requireLive(29);}
"""
JAVA_TESTS=r"""
 static JSONObject nativeValue(){return new JSONObject().put("schema","rusty.quest.embedded_duplex.owner_diagnostic_native_read.v1").put("native_executor_generation",7).put("app_process_generation",29).put("initialization_failure",new JSONObject().put("schema","rusty.quest.embedded_duplex.initialization_failure.v1").put("attempt",1).put("runtime_config_sha256","aaaa").put("app_record_sha256","bbbb").put("host_published",false).put("cleanup_certified",false).put("authority_granted",false));}
 static void yes(boolean v){if(!v)throw new AssertionError();}
 static void denied(DiagnosticJavaHost h){try{h.ownerFailureDiagnosticRead("a".repeat(32)).join();throw new AssertionError("accepted stale input");}catch(CompletionException expected){}}
 public static void main(String[] args){
  DiagnosticJavaHost h=new DiagnosticJavaHost();JSONObject good=nativeValue();EmbeddedDuplexNative.value=good.toString();
  JSONObject result=new JSONObject(h.ownerFailureDiagnosticRead("a".repeat(32)).join());yes(!result.getBoolean("qualification_claimed"));yes(result.getString("runtime_config_sha256").equals("aaaa"));
  for(String field:new String[]{"runtime_config_sha256","app_record_sha256","schema"}){JSONObject bad=nativeValue();bad.getJSONObject("initialization_failure").put(field,"changed");EmbeddedDuplexNative.value=bad.toString();denied(h);}
  for(String field:new String[]{"host_published","cleanup_certified","authority_granted"}){JSONObject bad=nativeValue();bad.getJSONObject("initialization_failure").put(field,true);EmbeddedDuplexNative.value=bad.toString();denied(h);}
  for(String field:new String[]{"native_executor_generation","app_process_generation"}){JSONObject bad=nativeValue().put(field,99);EmbeddedDuplexNative.value=bad.toString();denied(h);}
  EmbeddedDuplexNative.value=good.toString();h.phase.set(Phase.READY);denied(h);h.phase.set(Phase.FAILED);h.processFence.live=false;denied(h);h.processFence.live=true;
  JSONObject normal=nativeValue();normal.remove("initialization_failure");normal.put("ordinary_callback","unchanged");EmbeddedDuplexNative.value=normal.toString();h.phase.set(Phase.READY);JSONObject observed=new JSONObject(h.ownerFailureDiagnosticRead("a".repeat(32)).join()).getJSONObject("native_observation");yes(observed.similar(normal));
  System.out.println("Java production diagnostic method: exact identity/authority/phase denials and unchanged normal payload PASS; modeled JNI and fence");
 }
}
"""

PRELUDE=r"""
#![allow(dead_code)]
use serde_json::json;
use std::sync::{Mutex,OnceLock,Arc,atomic::{AtomicBool,Ordering}};
mod process_fence {
 #[derive(Clone,PartialEq,Eq)] pub struct AppBinding {pub generation:u64,pub record_sha256:String}
}
mod rusty_quest_broker_authority {
#[allow(dead_code)] pub enum QuestBrokerRuntimeError {
 SchemaMismatch,
 LegacyRuntimeConfigRequiresRebuild,
 BridgeModeMismatch,
 InvalidProviderEpoch,
 InvalidAuthorityClock,
 ControlLeaseBootstrap,
 RebindConfigMismatch,
 PackagedDigestMismatch,
 PackagedConfigDigestMismatch,
 PackagedProductLockMismatch,
 ClientLockInvalid,
 GrantClosureMismatch,
 MediaSelectionMismatch,
 MediaCrossBindingMismatch,
 MediaProductFeatureMismatch,
 MediaLifecycleAuthorityMismatch,
 MediaPeerRuntimeConfig,
 RuntimeLockPoisoned,
 MediaDescriptorBinding(String),
 MediaRuntime(String),
 MediaPeerRuntime(String),
 Adapter(String),
 ControlLeaseAuthority(String),
 RuntimeState(String),
 Admission(String),
 AdmissionProjection(String),
 Decode(String),
 Encode(String),
 NotInitialized,
}
}
mod tested {
use super::*;
#[derive(Clone)] struct NativeCapability {generation:u64,binding:process_fence::AppBinding,live:Arc<AtomicBool>}
impl NativeCapability {fn require_live(&self)->Result<(),String>{if self.live.load(Ordering::SeqCst){Ok(())}else{Err("live identity changed".into())}}}
struct Host;
fn process()-> &'static Mutex<ProcessState>{static P:OnceLock<Mutex<ProcessState>>=OnceLock::new();P.get_or_init(||Mutex::new(ProcessState::default()))}
fn prepared_route()-> &'static Mutex<Option<(String,())>>{static R:OnceLock<Mutex<Option<(String,())>>>=OnceLock::new();R.get_or_init(||Mutex::new(None))}
fn exact_staged_route(sha:&str)->Result<(),String>{if prepared_route().lock().unwrap().as_ref().map(|v|v.0.as_str())==Some(sha){Ok(())}else{Err("route changed".into())}}
"""
TESTS=r"""
fn reset()->NativeCapability{*process().lock().unwrap()=ProcessState::default();*prepared_route().lock().unwrap()=Some(("a".repeat(64),()));NativeCapability{generation:7,binding:process_fence::AppBinding{generation:29,record_sha256:"b".repeat(64)},live:Arc::new(AtomicBool::new(true))}}
fn fail(c:&NativeCapability,reason:&str){let r:Result<(),String>=initialize_with_capability(c,&"a".repeat(64),||Err::<(),_>(reason.into()),|_,_|panic!("published failed construction"));assert_eq!(r,Err(reason.into()));}
fn read(c:&NativeCapability)->Result<Option<serde_json::Value>,String>{initialization_failure_read_with_capability(c)}
#[test]fn failure_before_publication_retains_exact_identity(){let c=reset();fail(&c,"packaged runtime initialization rejected");let v=read(&c).unwrap().unwrap();assert_eq!(v["initialization_failure"]["reason_code"],"runtime_provider_rejected");assert_eq!(v["initialization_failure"]["runtime_config_sha256"],"a".repeat(64));assert_eq!(v["initialization_failure"]["app_record_sha256"],"b".repeat(64));assert_eq!(v["native_executor_generation"],7);assert_eq!(v["app_process_generation"],29);assert_eq!(v["initialization_failure"]["cleanup_certified"],false);assert!(process().lock().unwrap().host.is_none());}
#[test]fn actual_typed_provider_mapping_never_formats_payloads(){use rusty_quest_broker_authority::QuestBrokerRuntimeError as E;assert_eq!(provider_initialization_reason(&E::PackagedConfigDigestMismatch),"provider_config_digest_rejected");assert_eq!(provider_initialization_reason(&E::MediaLifecycleAuthorityMismatch),"provider_media_lifecycle_authority_rejected");assert_eq!(provider_initialization_reason(&E::ControlLeaseBootstrap),"provider_control_lease_bootstrap_rejected");for error in [E::Admission("secret admission payload".into()),E::ControlLeaseAuthority("private authority".into()),E::Decode("private config".into()),E::NotInitialized]{let reason=provider_initialization_reason(&error);assert!(!reason.contains("secret"));assert!(!reason.contains("private"));assert_eq!(initialization_reason(reason),reason);}assert_eq!(provider_initialization_reason(&E::NotInitialized),"provider_unclassified_rejected");}
#[test]fn unclassified_error_is_redacted(){let c=reset();fail(&c,"credential=secret, config=private");let v=read(&c).unwrap().unwrap().to_string();assert!(v.contains("unclassified_initialization_failure"));assert!(!v.contains("secret"));assert!(!v.contains("private"));}
#[test]fn every_identity_axis_denies(){let c=reset();fail(&c,"invalid embedded input");for axis in 0..3{let mut other=c.clone();match axis{0=>other.generation+=1,1=>other.binding.generation+=1,_=>other.binding.record_sha256="c".repeat(64)}assert!(read(&other).is_err());}c.live.store(false,Ordering::SeqCst);assert!(read(&c).is_err());}
#[test]fn route_and_busy_denials(){let c=reset();fail(&c,"invalid embedded input");process().lock().unwrap().initializing=true;assert!(read(&c).is_err());process().lock().unwrap().initializing=false;process().lock().unwrap().closing=true;assert!(read(&c).is_err());process().lock().unwrap().closing=false;prepared_route().lock().unwrap().as_mut().unwrap().0="c".repeat(64);assert!(read(&c).is_err());}
#[test]fn next_attempt_clears_old_before_build_and_success_uses_host_path(){let c=reset();fail(&c,"invalid embedded input");initialize_with_capability(&c,&"a".repeat(64),||{assert!(process().lock().unwrap().initialization_failure.is_none());assert!(read(&c).is_err());Ok(())},|s,_|{s.host=Some(Host);Ok(())}).unwrap();assert!(read(&c).unwrap().is_none());}
#[test]fn attempt_number_changes_and_exhaustion_denies(){let c=reset();fail(&c,"invalid embedded input");fail(&c,"invalid embedded pair binding");assert_eq!(read(&c).unwrap().unwrap()["initialization_failure"]["attempt"],2);process().lock().unwrap().initialization_attempt=u64::MAX;let r=initialize_with_capability(&c,&"a".repeat(64),||panic!("must not run"),|_,_:()|Ok(()));assert!(r.is_err());assert!(read(&c).is_err());}
#[test]fn failed_postcheck_never_publishes_or_borrows_old_failure(){let c=reset();fail(&c,"invalid embedded input");let r:Result<(),String>=initialize_with_capability(&c,&"a".repeat(64),||{c.live.store(false,Ordering::SeqCst);Ok(())},|_,_|panic!("must not publish"));assert!(r.is_err());assert!(read(&c).is_err());assert!(!process().lock().unwrap().initializing);}
#[test]fn actual_cleanup_commit_clears_observation_only_after_matching_idle_route(){let c=reset();fail(&c,"invalid embedded input");process().lock().unwrap().closing=true;assert!(commit_cleanup(&"c".repeat(64)).is_err());assert!(process().lock().unwrap().initialization_failure.is_some());process().lock().unwrap().host_leases=1;assert!(commit_cleanup(&"a".repeat(64)).is_err());assert!(process().lock().unwrap().initialization_failure.is_some());process().lock().unwrap().host_leases=0;commit_cleanup(&"a".repeat(64)).unwrap();assert!(process().lock().unwrap().initialization_failure.is_none());assert!(prepared_route().lock().unwrap().is_none());assert!(read(&c).is_err());}
#[test]fn panic_clears_gate_without_inventing_failure_or_cleanup(){let c=reset();fail(&c,"invalid embedded input");let r=std::panic::catch_unwind(||{let _:Result<(),String>=initialize_with_capability(&c,&"a".repeat(64),||panic!("abort construction"),|_,_:()|Ok(()));});assert!(r.is_err());assert!(!process().lock().unwrap().initializing);assert!(process().lock().unwrap().initialization_failure.is_none());assert!(prepared_route().lock().unwrap().is_some());}
}
"""
if __name__=='__main__':main()
