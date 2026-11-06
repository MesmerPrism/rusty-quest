"""Actual ordinary Start/Original/preflight/slot bodies; Android, SDK and Native authority/effects are model leaves."""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    p.add_argument('--output', type=pathlib.Path)
    p.add_argument('--java-home', type=pathlib.Path, default=os.environ.get('JAVA_HOME'))
    p.add_argument('--json-jar', type=pathlib.Path, default=os.environ.get('RUSTY_QUEST_HOST_JSON_JAR'))
    p.add_argument('--support-classpath', default=os.environ.get('RUSTY_QUEST_APP_TEST_CLASSPATH', ''))
    p.add_argument('--smoke', action='store_true')
    a = p.parse_args()
    root = a.root.resolve()
    if not a.java_home or not a.json_jar or not a.support_classpath:
        raise ValueError('Select JDK17, pinned org.json20240303 and current compiled app helper classpath')
    out = (a.output or root / 'target' / ('ordinary-start-' + uuid.uuid4().hex)).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    package = 'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    source = root / 'apps/spatial-camera-panel-android/app/src/main/java' / package
    fixtures = root / 'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
    host_path = source / 'EmbeddedDuplexProcessHost.java'
    service_path = source / 'EmbeddedDuplexRuntimeService.java'
    host = host_path.read_text(encoding='utf-8')
    service = service_path.read_text(encoding='utf-8')
    paths = [host_path, service_path, source / 'EmbeddedDuplexStartIntentSlot.java',
             fixtures / 'EmbeddedDuplexOrdinaryStartHostTest.java',
             fixtures / 'EmbeddedDuplexStartIntentSlotHostTest.java', pathlib.Path(__file__).resolve()]
    def pin(path):
        path = pathlib.Path(path)
        return {'path': str(path.resolve()), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    before = [pin(path) for path in paths]
    checks = []
    def run(name, command):
        r = subprocess.run(command, cwd=root, capture_output=True, timeout=90)
        stdout, stderr = out / (name + '.stdout'), out / (name + '.stderr')
        stdout.write_bytes(r.stdout)
        stderr.write_bytes(r.stderr)
        checks.append({'name': name, 'argv': command, 'exit': r.returncode, 'stdout': pin(stdout), 'stderr': pin(stderr)})
        if r.returncode:
            raise RuntimeError(name + ': ' + r.stderr.decode('utf-8', errors='replace')[-6000:])
    original_gate = root / 'tools/checks/Test-EmbeddedDuplexOriginalsHost.py'
    original_out = out / 'originals'
    run('existing-originals', [sys.executable, '-B', str(original_gate), '--root', str(root),
         '--output', str(original_out), '--java-home', str(a.java_home), '--json-jar', str(a.json_jar),
         '--support-classpath', a.support_classpath])
    loader = importlib.util.spec_from_file_location('actual_source_methods', original_gate)
    module = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(module)
    extract = module.extract_method
    signatures = [
        'CompletableFuture<String> ordinaryStart(', 'private String ordinaryStartChallenge(',
        'private void requireOrdinaryStartContext(', 'CompletableFuture<String> stopCurrentPeer(',
        'CompletableFuture<EmbeddedDuplexStartPreflight> prepareStartPreflight(', 'boolean preflightLive(',
        'private void requireFreshProcess(', 'private static <T> CompletableFuture<T> failed(',
        'private <T,R> java.util.function.Function<T,R> ownedContinuation(',
        'private <R> R ownedContinuationCall(',
        'CompletableFuture<String> peerLifecycle(EmbeddedDuplexPeerAction action, String challenge)',
        'private CompletableFuture<String> peerLifecycle(EmbeddedDuplexPeerAction action, String challenge, Runnable',
        'private <T> CompletableFuture<T> appOriginal(', 'private static boolean freshSelected(',
        'private static android.os.Bundle receiptOriginalBundle(',
    ]
    methods = [extract(host, signature) for signature in signatures]
    models = {path.relative_to(original_out / 'models').as_posix(): path.read_text(encoding='utf-8')
              for path in (original_out / 'models').rglob('*.java')}
    key = package + '/EmbeddedDuplexProcessHost.java'
    generated = models[key]
    leaves = r'''
      private final Object attachmentGate=new Object();
      private long attachmentGeneration=7;
      private boolean localFixture;
      private String enrollmentRecordSha256="c".repeat(64),ownFeatureLockSha256="f".repeat(64);
      {runtimeConfigSha256="b".repeat(64);}
      private final EmbeddedDuplexStartIntentSlot startIntent=new EmbeddedDuplexStartIntentSlot();
      private final Display display=new Display();
      private static final class Display { boolean cleanupPending(){return false;} }
      private static final class Platform {
        void requirePreparedNativeStatus(JSONObject status,boolean ready)throws Exception{
          if(!ready)throw new AssertionError("ordinary Start must require the actual ready owner");
          if(!status.getBoolean("modeled_sdk_ready"))throw new IllegalStateException("modeled SDK owner not ready");
        }
      }
      String harnessEpoch(){return processFence.epochId();}
      void harnessAttachment(long value){attachmentGeneration=value;}
      void harnessPhaseFailed(){phase.set(Phase.FAILED);}
      void harnessRestore(){phase.set(Phase.READY);}
      private CompletableFuture<String> peerLifecycleLegacy(EmbeddedDuplexPeerAction action,String challenge){
        throw new AssertionError("legacy Native behavior is not modeled by this selected-preparation test");
      }
      // Native/SDK authority and execution boundary only. The above caller bodies remain exact source.
      private CompletableFuture<String> peerLifecycleEntered(EmbeddedDuplexPeerAction action,String challenge,boolean whole,Runnable beforeStart){
        if(action==EmbeddedDuplexPeerAction.STOP && EmbeddedDuplexNative.entered!=null){
          EmbeddedDuplexNative.stopSignals++;
          EmbeddedDuplexNative.entered.desiredStop.set(true);
        }
        return submit(()->{
          if(action==EmbeddedDuplexPeerAction.START){
            EmbeddedDuplexStartPreflight starting=startIntent.pending();
            if(!preflightLive(starting))throw new IllegalStateException("current paired Start intent unavailable");
            if(beforeStart!=null)beforeStart.run();
            startIntent.dispatch(starting);
            EmbeddedDuplexNative.calls++;
            EmbeddedDuplexNative.entered=EmbeddedDuplexOriginals.capture();
            EmbeddedDuplexNative.sdkConsumed=true;
            EmbeddedDuplexNative.enteredLatch.countDown();
            if(EmbeddedDuplexNative.grantExpired)throw new IllegalStateException("modeled Native authority expired");
            EmbeddedDuplexNative.effects++;
            while(EmbeddedDuplexNative.hold && !EmbeddedDuplexNative.entered.desiredStop.get())Thread.sleep(5);
            if(EmbeddedDuplexNative.throwAfterEntry)throw new IllegalStateException("modeled unknown Native outcome");
            JSONObject value=EmbeddedDuplexStartIntentSlotHostTest.fresh(EmbeddedDuplexNative.pending?"pending":"active");
            value.put("native_executor_generation",nativeExecutorGeneration).put("app_process_generation",processFence.generation());
            startIntent.acknowledge(starting,value.toString());
            return EmbeddedDuplexNative.wrapper(challenge,"start",value);
          }
          if(action!=EmbeddedDuplexPeerAction.STOP)throw new AssertionError("ordinary commands must not select Whole or setup");
          JSONObject value=EmbeddedDuplexStartIntentSlotHostTest.fresh("terminal");
          value.put("action","peer_stop").put("native_executor_generation",nativeExecutorGeneration).put("app_process_generation",processFence.generation());
          return EmbeddedDuplexNative.wrapper(challenge,"peer_stop",value);
        });
      }
    '''
    # The current Originals model already owns the typed platform and lifecycle fields.
    platform_key = package + '/EmbeddedDuplexPlatform.java'
    models[platform_key] = models[platform_key][:-1] + extract(leaves, 'void requirePreparedNativeStatus(') + '\n}'
    models[key] = generated[:-1] + leaves + '\n' + '\n'.join(methods) + '\n}'
    service_key = package + '/EmbeddedDuplexRuntimeService.java'
    service_methods = [extract(service, signature) for signature in (
        'public static CompletableFuture<String> ordinaryStart(', 'public static CompletableFuture<String> stopCurrentPeer(')]
    models[service_key] = models[service_key][:-1] + '\n' + '\n'.join(service_methods) + '\n}'
    models['android/content/Context.java'] = models['android/content/Context.java'].replace(
        'public String getPackageName()', 'public int checkSelfPermission(String permission){return 0;} public String getPackageName()')
    models['android/os/Bundle.java'] = models['android/os/Bundle.java'].replace(
        'public void putString(', 'public void putBoolean(String k,boolean v){put(k,v);} public void putString(')
    models['android/Manifest.java'] = 'package android; public final class Manifest {public static final class permission {public static final String CAMERA="android.permission.CAMERA";}}'
    models['android/content/pm/PackageManager.java'] = 'package android.content.pm; public final class PackageManager {public static final int PERMISSION_GRANTED=0;}'
    models['io/github/mesmerprism/rustyquest/spatial_camera_panel/BuildConfig.java'] = '''
package io.github.mesmerprism.rustyquest.spatial_camera_panel;
public final class BuildConfig {public static final String APPLICATION_ID="fixture.package";public static String EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2";}
'''
    models[package + '/ConcurrentStereoQualification.java'] = '''
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
final class ConcurrentStereoQualification {
 static String epoch,challenge="d".repeat(32);static long generation=17;
 static String currentHubChallenge(String e,String c,String f){if(!e.equals(epoch)||!c.equals("b".repeat(64))||!f.equals("f".repeat(64)))throw new IllegalStateException("modeled ARM absent");return challenge;}
 static boolean currentArmActor(String e,String ch,String c,long g){return e.equals(epoch)&&ch.equals(challenge)&&c.equals("b".repeat(64))&&g==generation;}
 static JSONObject retirementArmObservation(String e,String c,long g)throws Exception{return currentArmActor(e,challenge,c,g)?new JSONObject().put("binding","established").put("challenge",challenge):new JSONObject().put("binding","not_established");}
}
'''
    models[package + '/EmbeddedDuplexNative.java'] = '''
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
final class EmbeddedDuplexNative {
 static boolean sdkReady=true,sdkConsumed,pairPresent=true,expired,grantExpired,pending,hold,throwAfterEntry;
 static int calls,effects,stopSignals;static EmbeddedDuplexOriginals.Record entered;
 static Runnable pairReadHook;static final java.util.List<String> originals=new java.util.ArrayList<>();
 static java.util.concurrent.CountDownLatch enteredLatch=new java.util.concurrent.CountDownLatch(1);
 static String runtimeCommand(String command,String body)throws Exception{
  EmbeddedDuplexOriginals.Record current=EmbeddedDuplexOriginals.capture();
  if(current==null)throw new AssertionError("read lost its actual Start Original");
  originals.add(current.id());
  if(command.equals("prepared_peer_start_status"))return new JSONObject().put("modeled_sdk_ready",sdkReady&&!sdkConsumed).toString();
  if(!command.equals("pair_status"))throw new AssertionError("ordinary Start must not call setup, Pair, diagnostics or a generic command");
  JSONObject local=new JSONObject().put("current",pairPresent).put("session_id","session.duplex."+"a".repeat(64)).put("expires_at_ms",System.currentTimeMillis()+(expired?-1000:60000));
  String result=new JSONObject().put("$schema","rusty.quest.embedded_duplex.pair_status.v1").put("state","peer_session_current_route_unverified")
   .put("session_id","session.duplex."+"a".repeat(64)).put("native_current_session",local).put("route_current",false).put("media_effect_proven",false).toString();
  if(pairReadHook!=null)pairReadHook.run();
  return result;
 }
 static String wrapper(String challenge,String action,JSONObject value)throws Exception{
  return new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action",action).put("challenge",challenge).put("peer_lifecycle",value).toString();
 }
 static void reset(){sdkReady=true;sdkConsumed=false;pairPresent=true;expired=false;grantExpired=false;pending=false;hold=false;throwAfterEntry=false;calls=effects=stopSignals=0;entered=null;pairReadHook=null;originals.clear();enteredLatch=new java.util.concurrent.CountDownLatch(1);}
}
'''
    model_paths = []
    for relative, body in models.items():
        path = out / 'models' / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
        model_paths.append(path)
    # Reuse the exact existing preflight-live fixture declarations, not its unrelated legacy static tail.
    legacy = (root / 'tools/checks/Test-EmbeddedDuplexStartIntentHost.ps1').read_text(encoding='utf-8')
    prefix = __import__('re').search(r"\$harness=@'\r?\n(.*?)\r?\n'@", legacy, __import__('re').S).group(1)
    live = out / 'models/PreflightLiveHarness.java'
    live.write_text(prefix + '\n' + extract(host, 'boolean preflightLive(') + '\n}', encoding='utf-8')
    model_paths.append(live)
    classes = out / 'classes'
    classes.mkdir()
    cp = os.pathsep.join([str(a.json_jar), a.support_classpath])
    production = [source / name for name in ('EmbeddedDuplexCanonicalJson.java', 'EmbeddedDuplexOriginals.java',
        'EmbeddedDuplexProcessFence.java', 'EmbeddedDuplexStartPreflight.java', 'EmbeddedDuplexStartIntentSlot.java',
        'EmbeddedDuplexPairStatus.java', 'EmbeddedDuplexPeerAction.java', 'EmbeddedDuplexFreshLifecycle.java',
        'EmbeddedDuplexNativeOperationFailure.java')]
    run('compile', [str(a.java_home / 'bin/javac.exe'), '--release', '17', '-encoding', 'UTF-8', '-cp', cp,
        '-d', str(classes), *map(str, model_paths + production + [
            fixtures / 'EmbeddedDuplexStartIntentSlotHostTest.java', fixtures / 'EmbeddedDuplexOrdinaryStartHostTest.java'])])
    runtime = os.pathsep.join([str(classes), cp])
    common = [str(a.java_home / 'bin/java.exe'), '-Dhost.proc=' + str(original_out / 'proc'), '-cp', runtime]
    run('slot-controls', common + [package.replace('/', '.') + '.EmbeddedDuplexStartIntentSlotHostTest'])
    run('caller-controls', common + [package.replace('/', '.') + '.EmbeddedDuplexOrdinaryStartHostTest',
        str(out / 'runtime'), 'smoke' if a.smoke else 'full'])
    # This fixed callback must run in the existing lane before the one-use dispatch and Native call.
    lane = extract(host, 'private CompletableFuture<String> concurrentQualificationOnLane(boolean arm, String challenge, boolean policyAction, long[] policy, EmbeddedDuplexPeerAction peerAction, boolean maskAction, boolean hubAction, java.util.concurrent.atomic.AtomicBoolean cancelled, Runnable')
    assert lane.index('beforeStart.run()') < lane.index('startIntent.dispatch(starting)') < lane.index('String nativeReceipt =')
    assert before == [pin(path) for path in paths], 'Relevant source changed during qualification'
    result = {'status': 'passed', 'smoke': a.smoke, 'source_pins': before, 'checks': checks,
        'extracted_methods': [{'signature': m[:m.index('{')].strip(), 'sha256': hashlib.sha256(m.encode()).hexdigest()}
                              for m in methods + service_methods],
        'generated_sources': [pin(path) for path in model_paths],
        'support_inputs': [pin(path) for path in a.support_classpath.split(os.pathsep) if path and pathlib.Path(path).is_file()],
        'limits': __doc__ + ' No Native grant, SDK/hardware readiness, APK/device, physical cleanup or Product adoption proof.'}
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'passed', 'output': str(out)}))


if __name__ == '__main__':
    main()
