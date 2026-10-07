#!/usr/bin/env python3
"""Focused target-free controls of production diagnostic functions and call-site joins.
JNI environment behavior is modeled; no APK, device, acceptance, or cleanup claim.
"""
import argparse, hashlib, json, subprocess
from pathlib import Path

def pin(p):
    b=p.read_bytes();return {'path':str(p),'sha256':hashlib.sha256(b).hexdigest(),'size_bytes':len(b)}

def main():
    args=argparse.ArgumentParser(description=__doc__);args.add_argument('--root',required=True);args.add_argument('--output',required=True);args.add_argument('--rustc',required=True);v=args.parse_args()
    root=Path(v.root).resolve();output=Path(v.output).resolve();output.mkdir(parents=True,exist_ok=False)
    native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
    paths=[native/n for n in ['owner_failure.rs','mod.rs','runtime_host.rs','retained_cleanup_host.rs','peer_lifecycle.rs','java_bridge.rs','process_fence.rs']]
    paths += [root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexPlatform.java',Path(v.rustc).resolve(),Path(__file__).resolve()]
    pins=[pin(p) for p in paths]
    runtime=(native/'runtime_host.rs').read_text();executor=(native/'retained_cleanup_host.rs').read_text();life=(native/'peer_lifecycle.rs').read_text();bridge=(native/'java_bridge.rs').read_text();java=paths[7].read_text()
    assert 'owner_failure::observe(host.cleanup.prepare_frame(&bytes)' in runtime
    assert 'owner_failure::observe((||{Checkout::take(host.cleanup_server.clone())?.get().handle(&request)})()' in runtime
    assert 'owner_failure::observe(result, &self.cleanup.failure, failure_stage)' in executor
    assert 'owner_failure_diagnostic().ok()' not in life and '"owner_failure_diagnostic_status":owner_diagnostic_status' in life
    for stage in ['OrdinaryCallback','LocalProjection','LocalCallback','RemotePrepare','RemotePrepareProof','RemoteProjection','RemoteCommit','RemoteExchange','RemoteProof','SourceReadback']:
        assert 'failure_stage = OwnerFailureStage::'+stage+';' in executor
    retained=java[java.index('public String executeRetainedCleanupAndVerify'):java.index('public synchronized void persistCleanupPreparations')]
    assert 'catch (Exception failure)' in retained and 'recordOwnerFailure(stage, ticket, failure);' in retained and 'throw failure;' in retained
    # Run the exact JNI exception-clearing function body with a minimal modeled environment.
    start=bridge.index("\nfn checked_call<'local, T>(");end=bridge.index('\nfn valid_key_id',start);checked=bridge[start:end]
    harness=r"""
use std::marker::PhantomData;
mod jni { pub mod errors { pub type Result<T> = std::result::Result<T, ()>; } }
struct JNIEnv<'local> { pending: bool, state_failed: bool, clears: usize, lifetime: PhantomData<&'local ()> }
impl JNIEnv<'_> {
 fn exception_check(&mut self) -> jni::errors::Result<bool> { if self.state_failed { Err(()) } else { Ok(self.pending) } }
 fn exception_clear(&mut self) -> jni::errors::Result<()> { self.pending=false; self.clears+=1; Ok(()) }
}
"""+checked+'\n#[path="'+str(native/'owner_failure.rs').replace('\\','/')+'"] mod owner_failure;\n'+r"""
#[test] fn actual_java_pending_exception_is_cleared_and_classified_without_payload() {
 let mut env=JNIEnv {pending:true,state_failed:false,clears:0,lifetime:PhantomData};
 let result=checked_call(&mut env,Ok(7),"java_bridge.owner_diagnostic_call");
 assert_eq!(result,Err("java_bridge.owner_diagnostic_call.exception".into()));
 assert!(!env.pending);assert_eq!(env.clears,1);
 assert_eq!(owner_failure::diagnostic(result),(None,"JAVA_CALLBACK_REJECTED"));
}
#[test] fn actual_jni_error_preserves_finite_code_and_clears_pending_exception() {
 let mut env=JNIEnv {pending:true,state_failed:false,clears:0,lifetime:PhantomData};
 let result=checked_call::<()>(&mut env,Err(()),"java_bridge.owner_diagnostic_call");
 assert_eq!(result,Err("java_bridge.owner_diagnostic_call".into()));assert_eq!(env.clears,1);
 assert_eq!(owner_failure::diagnostic(result),(None,"JAVA_CALLBACK_REJECTED"));
}
#[test] fn actual_exception_state_failure_is_explicit_and_success_is_preserved() {
 let mut env=JNIEnv {pending:false,state_failed:true,clears:0,lifetime:PhantomData};
 assert_eq!(owner_failure::diagnostic(checked_call(&mut env,Ok(4),"java_bridge.owner_diagnostic_call")),(None,"JAVA_EXCEPTION_STATE_UNAVAILABLE"));
 env.state_failed=false;
 assert_eq!(owner_failure::diagnostic(checked_call(&mut env,Ok(4),"java_bridge.owner_diagnostic_call")),(Some(4),"AVAILABLE"));
}
"""
    source=output/'actual-checked-call-modeled-env.rs';source.write_text(harness);binary=output/'diagnostic-controls.exe';results=[]
    for name,argv in [('rustc',[v.rustc,'--edition','2021','--test',str(source),'-o',str(binary)]),('controls',[str(binary),'--nocapture'])]:
        process=subprocess.run(argv,capture_output=True,timeout=60);stdout=output/(name+'.stdout');stderr=output/(name+'.stderr');stdout.write_bytes(process.stdout);stderr.write_bytes(process.stderr);results.append({'name':name,'exit':process.returncode,'stdout':pin(stdout),'stderr':pin(stderr)});assert process.returncode==0,process.stderr.decode(errors='replace')
    assert b'8 passed;' in (output/'controls.stdout').read_bytes()
    assert pins==[pin(p) for p in paths]
    result={'schema':'rusty.quest.retained_owner_diagnostic_host_controls.v1','status':'passed','production_source_pins':pins,'checks':results,'device_calls':0,'apk_built':False,'source_admission':False,'limits':['Exact production pure diagnostic/exception functions with modeled JNI environment; no actual JNI attachment or Android callback execution','Call-site joins are static assertions, not retained executor effect execution','No Android native crate typecheck, APK, installed behavior or terminal cleanup claim']}
    (output/'RESULT.json').write_text(json.dumps(result,indent=2));print('eight production diagnostic/JNI-body host controls passed; Android typecheck not claimed')
if __name__=='__main__':main()
