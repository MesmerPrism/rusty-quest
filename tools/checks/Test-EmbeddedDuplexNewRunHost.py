"""Actual NewRun store/fence/Original/caller bodies; explicit Android/Native leaf models. No APK/device."""
import argparse, hashlib, importlib.util, json, os, pathlib, shutil, subprocess, sys, uuid, tomllib

def native_controls(root,out):
 native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
 harness=out/'native';harness.mkdir()
 prefix=['wsl.exe','--'] if os.name=='nt' else []
 cargo=subprocess.check_output(prefix+['bash','-lc','command -v cargo'],text=True,encoding='utf-8').strip() if prefix else shutil.which('cargo')
 rustc=subprocess.check_output(prefix+['bash','-lc','command -v rustc'],text=True,encoding='utf-8').strip() if prefix else shutil.which('rustc')
 if not cargo or not rustc:raise ValueError('Native Unix Rust toolchain unavailable')
 def linux(path):
  if not prefix:return str(path)
  return subprocess.check_output(prefix+['wslpath','-a',path.as_posix()],text=True,encoding='utf-8').strip()
 def command(name,args):
  r=subprocess.run(prefix+args,capture_output=True,timeout=240)
  (harness/(name+'.stdout')).write_bytes(r.stdout);(harness/(name+'.stderr')).write_bytes(r.stderr)
  if r.returncode:raise RuntimeError(name+': '+r.stderr.decode('utf-8',errors='replace')[-5000:])
 lock=tomllib.loads((root/'Cargo.lock').read_text(encoding='utf-8'))
 versions={name:next(x['version'] for x in lock['package'] if x['name']==name) for name in ['serde','serde_json','libc']}
 manifest='[package]\nname="quest-new-run-native-controls"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[lib]\npath="lib.rs"\n[dependencies]\n'
 for name,version in versions.items():manifest+=name+'={version="='+version+'"'+(',features=["derive"]' if name=='serde' else '')+'}\n'
 (harness/'Cargo.toml').write_text(manifest,encoding='utf-8')
 text=(native/'runtime_host.rs').read_text(encoding='utf-8')
 def extract(signature):
  start=text.index(signature);opening=text.index('{',start);depth=1;i=opening+1
  while depth:
   if text[i]=='{':depth+=1
   if text[i]=='}':depth-=1
   i+=1
  return text[start:i]
 parts=[extract(x) for x in ['fn require_operational_run_binding(','fn operational_run_state_refusal(','fn transition_operational_run_state(','mod operational_run_controls {']]
 model="""
 #[derive(Default)]
 struct ProcessState {
 operational_run_id:Option<String>,operational_run_initialization_floor:u64,initialization_attempt:u64,initialization_failure:Option<()>,initializing:bool,closing:bool,
 host_leases:usize,lease_integrity_failed:bool,root_reestablishment:Option<()>,last_root_reestablishment:Option<()>,root_terminal_custody:Option<()>,prepared_peer_start:Option<()>,
 prepared_archive_cursor:Option<()>,terminal_fresh_owner_body:Option<String>,terminal_fresh_owner_ack:Option<()>,fresh_setup_request:Option<String>,fresh_setup_stop_request:Option<String>,fresh_setup_stop_sequence:u64,
 retired_fresh_owner_fenced:bool,host:Option<()>,partial_construction:Option<()>,last_partial_retirement:Option<()>,last_closed_sha256:Option<String>,last_closed_runtime_configuration_binding:Option<()>,completed_no_media_local:Option<()>,last_peer_close_receipt:Option<String>
 }
 """
 (harness/'runtime_controls.rs').write_text(model+'\n'.join(parts),encoding='utf-8')
 lib="""#![allow(dead_code)]
 extern crate self as rusty_quest_broker_authority;
 pub fn packaged_json_sha256(value:&str)->String {
 use std::io::Write;use std::process::{Command,Stdio};
 let mut p=Command::new("sha256sum").stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
 p.stdin.take().unwrap().write_all(value.as_bytes()).unwrap();let r=p.wait_with_output().unwrap();assert!(r.status.success());String::from_utf8(r.stdout).unwrap().split_whitespace().next().unwrap().into()
 }
 """
 lib+='#[path='+json.dumps(linux(native/'process_fence.rs'))+'] mod process_fence;\n#[cfg(test)] mod runtime_controls;\n'
 (harness/'lib.rs').write_text(lib,encoding='utf-8')
 manifest=linux(harness/'Cargo.toml');target=linux(harness/'target')
 # A unique actual TMPDIR prevents old retained fixture pid names from being reused.
 tmp=subprocess.check_output(prefix+['mktemp','-d','/tmp/quest-new-run-XXXXXX'],text=True,encoding='utf-8').strip()
 command('resolve',['env','RUSTC='+rustc,cargo,'generate-lockfile','--offline','--manifest-path',manifest])
 command('controls',['env','TMPDIR='+tmp,'RUSTC='+rustc,cargo,'test','--locked','--offline','--manifest-path',manifest,'--target-dir',target,'--','--test-threads=1'])
 return {'native_pins':{x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in [native/'process_fence.rs',native/'runtime_host.rs',native/'native_fence_jni.rs']},'generated_pins':{x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in harness.iterdir() if x.is_file()},'scope':'Exact production Unix fence/inline tests plus extracted actual runtime run-binding/metadata guards; enclosing runtime owners/platform modeled; SHA256 seam uses actual sha256sum. Full Android/JNI compile separate.'}


def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2]);p.add_argument('--output',type=pathlib.Path);p.add_argument('--java-home',type=pathlib.Path);p.add_argument('--json-jar',type=pathlib.Path);p.add_argument('--support-classpath',default=os.environ.get('RUSTY_QUEST_APP_TEST_CLASSPATH',''));p.add_argument('--smoke',action='store_true');p.add_argument('--native-controls',action='store_true');a=p.parse_args()
 if not a.support_classpath:raise ValueError('Select current source-compiled app helpers with --support-classpath or RUSTY_QUEST_APP_TEST_CLASSPATH')
 root=a.root.resolve();out=(a.output or root/'target'/('new-run-'+uuid.uuid4().hex)).resolve()
 if not out.is_relative_to(root/'target'):raise ValueError('Output must be new under target')
 out.mkdir(parents=True,exist_ok=False)
 java=a.java_home or pathlib.Path(os.environ.get('JAVA_HOME') or pathlib.Path(shutil.which('javac')).parent.parent)
 jars=list((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('*.jar'));jar=a.json_jar or jars[0]
 if hashlib.sha256(jar.read_bytes()).hexdigest()!='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':raise ValueError('Host JSON pin changed')
 def run(name,argv):
  r=subprocess.run(argv,cwd=root,capture_output=True,timeout=120);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
  if r.returncode:raise RuntimeError(name+' failed: '+r.stderr.decode('utf-8',errors='replace')[-5000:])
 old=root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py';baseline=out/'originals'
 run('originals',[sys.executable,str(old),'--root',str(root),'--output',str(baseline),'--java-home',str(java),'--json-jar',str(jar),'--support-classpath',a.support_classpath])
 spec=importlib.util.spec_from_file_location('extract_actual',old);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);extract=m.extract_method
 pkg='io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex';source=root/'apps/spatial-camera-panel-android/app/src/main/java'/pkg
 activity_path=source.parent/'SpatialCameraPanelActivity.kt'
 activity=activity_path.read_text(encoding='utf-8')
 callback=extract(activity,'private fun onEmbeddedDuplexWholeCloseVerified(')
 selection_ui=extract(activity,'fun captureEmbeddedDuplexRunSelectionReconciliation(')
 assert 'completedSelectionCacheMatches(' in selection_ui and 'embeddedDuplexDiagnosticFuture = null' in selection_ui
 assert selection_ui.index('val runtime = embeddedDuplexRuntimeFuture') < selection_ui.index('return java.util.function.LongConsumer')
 assert 'wholeCloseRolloverCurrent' not in callback
 assert callback.index('val completed = embeddedDuplexRuntimeFuture') < callback.index('runOnUiThread')
 assert callback.index('proof.matchesCompletedRuntime(completed, embeddedDuplexRuntimeFuture,') < callback.index('embeddedDuplexRuntimeFuture = null')
 gate_path=source.parent/'EmbeddedDuplexDiagnosticActivityGate.kt'
 gate_source=gate_path.read_text(encoding='utf-8')
 assert 'bookkeeping = WeakReference(activity)' in gate_source and 'bookkeeping = null' not in gate_source
 host=(source/'EmbeddedDuplexProcessHost.java').read_text(encoding='utf-8');service=(source/'EmbeddedDuplexRuntimeService.java').read_text(encoding='utf-8')
 assert service.index('captureRunSelectionReconciliation()') < service.index('.selectRun(runId,true,legacyWholeOriginalId).whenComplete(reconcile)')
 models={x.relative_to(baseline/'models').as_posix():x.read_text(encoding='utf-8') for x in (baseline/'models').rglob('*.java')}
 key=pkg+'/EmbeddedDuplexProcessHost.java';body=models[key]
 old_ctor='private EmbeddedDuplexProcessHost(Context context){applicationContext=context;runContext=new EmbeddedDuplexRunContext(context.getNoBackupFilesDir(),context.getNoBackupFilesDir(),null,0);}'
 if old_ctor not in body:raise ValueError('Original harness model shape changed')
 body=body.replace('private EmbeddedDuplexRunSelectionStore runSelection;','').replace(old_ctor,extract(host,'private EmbeddedDuplexProcessHost(Context applicationContext)')).replace('private final EmbeddedDuplexRunContext runContext;','private volatile EmbeddedDuplexRunContext runContext;')
 # Only enclosing Android/Native/resource observations are replaced. The selected owner methods remain exact.
 fields="""
 private final EmbeddedDuplexRunSelectionStore runSelection;
 private final Object runRequestGate=new Object();private int runRequests;private final Object attachmentGate=new Object();private String diagnosticChallenge;
 private boolean closeInFlight,displayDetaching;private String runtimeConfigSha256,terminalWholeChallenge;
 private Object wholeCloseRollover,retainedWholeNativeReceipt,lastClosedActorProof,noMediaClose;private RetainedWholeClose retainedWholeClose;private static final class RetainedWholeClose{EmbeddedDuplexOriginals.Record original;}
 private Object nativeConfigurationContext,setupCredentials,pendingReview,lastBootstrapFailure;
 private CompletableFuture<Void> wholeCallbackMarker;private EmbeddedDuplexPlatform wholeCallbackPlatform;
 boolean harnessJoinWhole(EmbeddedDuplexPlatform value)throws Exception{return joinWholeCallbackOwner(value);}
 int fixtureWrites;CompletableFuture<EmbeddedDuplexEnrollment> fixtureWrite;
 CompletableFuture<EmbeddedDuplexEnrollment> replaceEnrollment(EmbeddedDuplexEnrollmentDraft draft){synchronized(attachmentGate){if(!phase.compareAndSet(Phase.NEW,Phase.PROVISIONING))return failed(new IllegalStateException("provisioning phase busy"));fixtureWrites++;fixtureWrite=new CompletableFuture<>();return fixtureWrite;}}
 void harnessFinishFixture(){phase.set(Phase.NEW);fixtureWrite.complete(null);}

 long attachmentGeneration=7;void harnessAttachment(long value){attachmentGeneration=value;}
 private final Display display=new Display();private static final class Display{boolean cleanupPending(){return false;}void detachAfterCleanup(long generation,Runnable close){if(generation<=0)throw new AssertionError("missing display owner");close.run();}}
 """
 signatures=['<T>T withRunRequest(', 'CompletableFuture<String> selectRun(', 'CompletableFuture<String> runStatus()', 'private String runStatusOnLane()', 'private static <T> CompletableFuture<T> failed(', 'private boolean joinWholeCallbackOwner(', 'void armDiagnosticChallenge(', 'CompletableFuture<EmbeddedDuplexEnrollment> provisionLocalDiagnosticFixture(']
 methods=[extract(host,x) for x in signatures]
 models[key]=body[:-1]+fields+'\n'+'\n'.join(methods)+'\n}'
 service_methods=[extract(service,x) for x in ['public static CompletableFuture<String> newRun(', 'public static CompletableFuture<String> resumeRun(', 'public static CompletableFuture<String> runStatus(', 'public static String selectedRunId(', 'public static <T>T withRunRequest(', 'public static Bundle queryOriginal(Context context,String runId,String originalId,String challenge)']]
 key=pkg+'/EmbeddedDuplexRuntimeService.java';models[key]=models[key][:-1]+'\n'+'\n'.join(service_methods)+'\n}'
 models['io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexDiagnosticActivityGate.java']="""package io.github.mesmerprism.rustyquest.spatial_camera_panel;
 public final class EmbeddedDuplexDiagnosticActivityGate {
 public static boolean present,paused;public static java.util.concurrent.CompletableFuture<?> cached;public static long attachment;public static Runnable posted;public static int reconciled;
 public static java.util.function.BiConsumer<String,Throwable> captureRunSelectionReconciliation(){
 final boolean had=present;final java.util.concurrent.CompletableFuture<?> a=cached;final long generation=attachment;
 return (receipt,failure)->{if(!had||failure!=null)return;try{long selected=new org.json.JSONObject(receipt).getLong("app_display_generation");posted=()->{
 if(io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexWholeCloseRollover.completedSelectionCacheMatches(a,cached,generation,attachment,selected)){cached=null;attachment=selected;reconciled++;}};
 if(!paused)posted.run();}catch(Exception invalid){throw new AssertionError(invalid);}};
 }
 public static void resume(){paused=false;if(posted!=null)posted.run();}
 }
 """
 diagnostic_path=source/'EmbeddedDuplexDiagnosticService.java';diagnostic=diagnostic_path.read_text(encoding='utf-8')
 for signature in ['public static void arm(', 'public static String provisionLocalFixture(', 'public static boolean requestRun(', 'public static String read(']:
  actual=extract(diagnostic,signature)
  assert 'requireLegacyTransport(context);' in actual
 models[pkg+'/EmbeddedDuplexEnrollmentDraft.java']='package '+pkg.replace('/','.')+'; final class EmbeddedDuplexEnrollmentDraft {}'
 models[pkg+'/EmbeddedDuplexEnrollmentResolver.java']='package '+pkg.replace('/','.')+''' ; final class EmbeddedDuplexEnrollmentResolver {
 static java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
 static EmbeddedDuplexEnrollmentDraft localDiagnosticDraft(android.content.Context c)throws Exception{entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("private draft release timeout");return new EmbeddedDuplexEnrollmentDraft();}
 }'''
 models[pkg+'/EmbeddedDuplexDiagnosticService.java']='package '+pkg.replace('/','.')+'; import android.content.Context; final class EmbeddedDuplexDiagnosticService {'+extract(diagnostic,'private static void requireLegacyTransport(')+' static void harnessRequire(Context c){requireLegacyTransport(c);} }'
 models[pkg+'/EmbeddedDuplexNative.java']="""package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
 final class EmbeddedDuplexNative {static boolean eligible=true;static String actualRun;static Long nativeGeneration;
 static String inspectOperationalRun(Object source)throws Exception{return new org.json.JSONObject().put("$schema","rusty.quest.embedded_duplex.operational_run_native.v1")
 .put("globally_eligible",eligible).put("operational_run_id",actualRun==null?org.json.JSONObject.NULL:actualRun).put("native_generation",nativeGeneration==null?org.json.JSONObject.NULL:nativeGeneration).toString();}}
 """
 models[pkg+'/EmbeddedDuplexPlatform.java']='package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;final class EmbeddedDuplexPlatform{final java.util.concurrent.CompletableFuture<Void> retired=new java.util.concurrent.CompletableFuture<>();int retirementCalls,controlCloses;void closeControlAfterProductCleanup(){controlCloses++;}java.util.concurrent.CompletableFuture<Void> retireProcessCallbacksAsync(){retirementCalls++;return retired;}}'
 models[pkg+'/OwnStereoCaptureRuntime.java']='package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;final class OwnStereoCaptureRuntime{static OwnStereoCaptureRuntime currentForApplication(){return null;}String physicalCleanupState(){throw new AssertionError("unmodeled Own owner");}}'
 generated=[]
 for name,value in models.items():
  path=out/'models'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value,encoding='utf-8');generated.append(path)
 names=['EmbeddedDuplexCanonicalJson.java','EmbeddedDuplexRunContext.java','EmbeddedDuplexRunSelectionStore.java','EmbeddedDuplexOriginals.java','EmbeddedDuplexProcessFence.java','EmbeddedDuplexFreshLifecycle.java','EmbeddedDuplexNativeOperationFailure.java','EmbeddedDuplexWholeCloseRollover.java']
 production=[source/name for name in names];fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexRunContextHostTest.java';original_fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexOriginalsHostTest.java';inputs=production+[fixture,original_fixture,activity_path,gate_path,source/'EmbeddedDuplexProcessHost.java',source/'EmbeddedDuplexRuntimeService.java',old,diagnostic_path,pathlib.Path(__file__).resolve()]
 pins={str(x.relative_to(root)):hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs}
 classes=out/'classes';classes.mkdir();cp=os.pathsep.join([str(jar),a.support_classpath])
 run('compile',[str(java/'bin/javac.exe'),'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes)]+[str(x) for x in generated+production+[fixture,original_fixture]])
 argv=[str(java/'bin/java.exe'),'-Dhost.proc='+str(baseline/'proc'),'-cp',os.pathsep.join([str(classes),cp]),pkg.replace('/','.')+'.EmbeddedDuplexRunContextHostTest']
 run('store',argv);run('caller',argv+['caller'])
 if any(hashlib.sha256((root/x).read_bytes()).hexdigest()!=h for x,h in pins.items()):raise ValueError('Inputs changed during controls')
 result={'status':'passed','source_pins':pins,'scope':'Actual run selector/store/fence/Original/service bodies and real filesystem/Future; Native/Android/current-resource leaves modeled. Existing 76 Original assertions retained.','limits':['No Native runtime/hardware/private enrollment byte claim; separate actual Native controls and Android composition required.'],'generated_pins':{str(x.relative_to(out)):hashlib.sha256(x.read_bytes()).hexdigest() for x in generated},'extracted_methods':[{'signature':x[:x.index('{')].strip(),'sha256':hashlib.sha256(x.encode()).hexdigest()} for x in methods+service_methods]}
 if a.native_controls:result['native_controls']=native_controls(root,out)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'passed','output':str(out)}))
if __name__=='__main__':main()
