"""Actual diagnostic seams, with modeled Android facts; no Cargo/APK/device work."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, json, pathlib, re, subprocess, zipfile

def method(text, signature):
    start=text.index(signature); brace=text.index('{',start); depth=0; quoted=False; escape=False
    for end in range(brace,len(text)):
        char=text[end]
        if quoted:
            if escape: escape=False
            elif char=='\\': escape=True
            elif char=='"': quoted=False
        elif char=='"': quoted=True
        elif char=='{': depth+=1
        elif char=='}':
            depth-=1
            if depth==0:return text[start:end+1]
    raise AssertionError('production method incomplete')

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True)
    p.add_argument('--android-jar',required=True,help='Selected compileSdk android.jar; desktop JSON is runtime-host-only')
    a=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.mkdir(exist_ok=False)
    before=subprocess.check_output(['git','--no-optional-locks','-C',str(root),'status','--porcelain=v1'])
    def run(name,argv):
        r=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        (out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
        if r.returncode:raise RuntimeError(name+' failed; retained stdout/stderr')
    app=root/'apps/spatial-camera-panel-android';java=app/'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    # Desktop org.json can expose APIs absent from Android. Compile the complete
    # production helper against the selected platform API before host execution.
    android_jar=pathlib.Path(a.android_jar).resolve(strict=True)
    with zipfile.ZipFile(android_jar) as platform:
        assert {'android/app/Activity.class','org/json/JSONObject.class'}<=set(platform.namelist()), 'Selected Android platform API jar required'
    android_classes=out/'android-api-classes';android_classes.mkdir()
    run('android-api-javac',['javac','--release','17','-cp',str(android_jar),'-d',str(android_classes),str(java/'EmbeddedDuplexPreparationDiagnostic.java')])
    run('rustc',['rustc','--edition','2021','--test',str(app/'host-tests/embedded_duplex/preparation_diagnostic_tests.rs'),'-o',str(out/'rust-tests.exe')])
    run('rust-tests',[str(out/'rust-tests.exe')])
    native=app/'native-receipt/src/embedded_duplex'
    runtime=(native/'runtime_host.rs').read_text()
    declaration=re.search(r'(pub\(crate\)\s+mod preparation_diagnostic;)',runtime).group(1)
    layout='''#![allow(dead_code)]
mod embedded_duplex {
 #[path = NATIVE_GUARD] pub(crate) mod preparation_native_guard;
 mod runtime_host { #[path = CORE] DECLARATION }
 mod java_bridge {
  pub fn actual_sibling_reference(attempt:&super::runtime_host::preparation_diagnostic::Attempt) {
   let _=super::runtime_host::preparation_diagnostic::JavaGuard::parse("arm_validation");
   attempt.finish(false);
  }
 }
}
fn main() {}
'''.replace('CORE',json.dumps((native/'preparation_diagnostic.rs').as_posix())).replace('NATIVE_GUARD',json.dumps((native/'preparation_native_guard.rs').as_posix())).replace('DECLARATION',declaration)
    layout_path=out/'actual-module-layout.rs';layout_path.write_text(layout,encoding='utf-8')
    run('module-layout',['rustc','--edition','2021',str(layout_path),'-o',str(out/'module-layout.exe')])
    private_path=out/'private-module-negative.rs';private_path.write_text(layout.replace(declaration,'mod preparation_diagnostic;'),encoding='utf-8')
    denied=subprocess.run(['rustc','--edition','2021',str(private_path),'-o',str(out/'private-module-negative.exe')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (out/'private-module-negative.stdout').write_bytes(denied.stdout);(out/'private-module-negative.stderr').write_bytes(denied.stderr)
    assert denied.returncode!=0 and b'E0603' in denied.stderr,'Private sibling visibility negative did not reject correctly'
    text=(java/'EmbeddedDuplexProcessHost.java').read_text()
    signatures=['static JSONObject preparedStartTarget(EmbeddedDuplexPlatform actual)',
        'private static JSONObject preparedStartTarget(EmbeddedDuplexPlatform actual,',
        'static JSONObject preparedStartEligibility(EmbeddedDuplexPlatform actual)',
        'static JSONObject preparedStartEligibility(EmbeddedDuplexPlatform actual,']
    bodies='\n'.join(method(text,sig) for sig in signatures)
    # These are the unchanged production Java admission bodies, compiled with
    # test-only dependencies. The actual Trace/decoder class is compiled whole.
    harness=r'''
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;import java.util.*;import java.util.concurrent.atomic.*;
class EmbeddedDuplexPlatform{}
class EmbeddedDuplexOriginals{
 static class Record{JSONObject row=new JSONObject().put("operation","prepare_peer_start");AtomicBoolean desiredStop=new AtomicBoolean();String id(){return "c".repeat(32);}}
 static Record capture(){EmbeddedDuplexProcessHost.reads++;if(EmbeddedDuplexProcessHost.reads==2){EmbeddedDuplexProcessHost.hit("original_binding");}return new Record();}
}
class ConcurrentStereoQualification{static JSONObject retirementArmObservation(String e,String c,long n){EmbeddedDuplexProcessHost.hit("arm_observation");return new JSONObject();}}
class EmbeddedDuplexPeerRoots{static void arm(JSONObject a){EmbeddedDuplexProcessHost.hit("arm_validation");}}
class EmbeddedDuplexProcessHost{
 enum Phase{READY,CLOSING}static EmbeddedDuplexProcessHost instance;
 AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);Object nativeConfigurationContext=new Object();
 String runtimeConfigSha256="b".repeat(64);long nativeExecutorGeneration=19;Fence processFence=new Fence();Resources resources=new Resources();
 static String fail;static int identities,reads;static List<String> calls=new ArrayList<>();
 static class Fence{String epochId(){return "epoch";}}
 static class Resources{JSONObject preparationTarget(String c){hit("target_metadata");return new JSONObject();}}
 static void hit(String name){calls.add(name);if(name.equals(fail))throw new IllegalStateException("modeled private exception");}
 static JSONObject freshClosureIdentity(EmbeddedDuplexPlatform p){hit(++identities==1?"target_actor":"actor_identity");return new JSONObject();}
 static void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record r){hit("target_original_actor");}
 static JSONObject freshPhysical(EmbeddedDuplexPlatform p,JSONObject b,boolean r){hit("physical_observation");return new JSONObject();}
 BODIES
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  int count=0;
  for(String failure:new String[]{"target_actor","target_fields","target_original_actor","target_metadata","actor_identity","arm_observation","arm_validation","original_binding","physical_observation"}){
   instance=new EmbeddedDuplexProcessHost();fail=failure;identities=0;reads=0;calls.clear();
   if(failure.equals("target_fields"))instance.phase.set(Phase.CLOSING);
   EmbeddedDuplexPreparationDiagnostic.EligibilityTrace trace=new EmbeddedDuplexPreparationDiagnostic.EligibilityTrace();
   try{preparedStartEligibility(new EmbeddedDuplexPlatform(),trace);throw new AssertionError("failure accepted");}
   catch(IllegalStateException expected){JSONObject result=new JSONObject(trace.result(null));check(result.getString("failure_guard").equals(failure));check(!result.toString().contains("private exception"));}
   for(String call:calls)check(Collections.frequency(calls,call)==1);count++;
  }
  instance=new EmbeddedDuplexProcessHost();fail=null;identities=0;reads=0;calls.clear();
  JSONObject value=preparedStartEligibility(new EmbeddedDuplexPlatform(),new EmbeddedDuplexPreparationDiagnostic.EligibilityTrace());
  check(value.has("physical_eligibility"));check(calls.equals(Arrays.asList("target_actor","target_original_actor","target_metadata","actor_identity","arm_observation","arm_validation","original_binding","physical_observation")));
  System.out.println("Actual production eligibility bodies: "+count+" injected guard failures and unchanged successful callback order; no SDK/runtime dependencies");
 }
}
'''.replace('BODIES',bodies)
    generated=out/'EmbeddedDuplexProcessHost.java';generated.write_text(harness,encoding='utf-8')
    classes=out/'classes';classes.mkdir()
    run('javac',['javac','--release','17','-cp',a.json_jar,'-d',str(classes),str(java/'EmbeddedDuplexPreparationDiagnostic.java'),str(app/'host-tests/embedded_duplex/EmbeddedDuplexPreparationDiagnosticHostTest.java'),str(generated)])
    import os
    cp=str(classes)+os.pathsep+a.json_jar
    package='io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.'
    run('java-decoder',['java','-cp',cp,package+'EmbeddedDuplexPreparationDiagnosticHostTest'])
    run('java-production-eligibility',['java','-cp',cp,package+'EmbeddedDuplexProcessHost'])
    read=method(text,'static CompletableFuture<String> preparationDiagnosticRead(')
    for prohibited in ['forApplication(', 'ensureProcessFence(', 'submit(', 'freshPhysical(', 'preparedStartEligibility(', 'preparedStartTarget(', 'noMedia']:
        assert prohibited not in read,prohibited
    assert read.count('EmbeddedDuplexNative.runtimeCommand(')==1 and '"prepared_peer_start_diagnostic"' in read
    service=(java/'EmbeddedDuplexRuntimeService.java').read_text();assert 'forApplication' not in method(service,'public static CompletableFuture<String> preparationDiagnosticRead(')
    provider=(app/'app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexOperatorProvider.kt').read_text()
    assert provider.index('METHOD_PREPARATION_DIAGNOSTIC')<provider.index('EmbeddedDuplexOriginals.forApplication(app)')
    contract=(app/'app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexOperatorContract.kt').read_text()
    selector=method(contract,'internal fun preparationDiagnosticOriginal(')
    assert 'fields.keys == setOf("original_id")' in selector and 'id is String' in selector and '[0-9a-f]{32}' in selector
    native=app/'native-receipt/src/embedded_duplex';prepared=(native/'prepared_start.rs').read_text();runtime=(native/'runtime_host.rs').read_text()
    diag=method(prepared,'pub(super) fn diagnostic(')
    for prohibited in ['callbacks.', 'clock.', 'source(host)', 'run(', 'held(host)', 'status(host)']: assert prohibited not in diag,prohibited
    assert runtime.index('return prepared_start::diagnostic(&host, input)')<runtime.index('let now = host.clock.now_ms()?;',runtime.index('fn command('))
    stages=re.findall(r'attempt\.call\(\s*Stage::(\w+)',prepared,re.S)
    assert stages==['Capability','PriorArchive','OriginalRead','JavaEligibility','OriginalMatch','NativeEligibility','PhysicalEligibility','ResourceTarget','Entropy','ConstructorBinding','ResourceTargetDigest','RequestReservation','SourceSnapshot','ArenaInstall','SdkPreparation'],stages
    assert before==subprocess.check_output(['git','--no-optional-locks','-C',str(root),'status','--porcelain=v1']), 'Host checks changed source status'
    java_controls=int(re.search(r'(\d+)',(out/'java-decoder.stdout').read_text()).group(1))
    rust_groups=int(re.search(r'test result: ok\. (\d+) passed',(out/'rust-tests.stdout').read_text()).group(1))
    java_enum=re.search(r'enum NativeGuard\s*\{([^}]+)\}',(java/'EmbeddedDuplexPreparationDiagnostic.java').read_text(),re.S).group(1)
    java_guards={s.strip().lower() for s in java_enum.split(',')}
    rust_guards=set(re.findall(r'Self::\w+\s*=>\s*"([a-z_]+)"',(native/'preparation_native_guard.rs').read_text()))-{'call_failed','predicate_rejected'}
    assert java_guards==rust_guards,'Closed Java/native guard vocabulary differs'
    result=dict(passed=True,rust_test_groups=rust_groups,java_decoder_controls=java_controls,production_java_failure_points=9,
        source_boundary_controls=6,native_sibling_layout_controls=2,source_status_unchanged=True,cargo_or_apk_build=False,device_effects=0,
        android_api_compile=True,android_jar_sha256=hashlib.sha256(android_jar.read_bytes()).hexdigest())
    (out/'RESULT.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__': main()
