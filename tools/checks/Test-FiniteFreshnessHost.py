"""Production freshness accounting and bridge bodies; platform leaves are modeled.

No Android, GPU, camera, pixel, media acceptance or replay authority is tested.
Supply a host org.json jar and the local Kotlin compiler dependency directory.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys

sys.dont_write_bytecode = True


def item(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, quoted, escaped = 0, False, False
    for end in range(brace, len(text)):
        char = text[end]
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise ValueError('Incomplete production body')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--stage', choices=['native', 'lifecycle', 'java', 'kotlin', 'all'], default='all')
    parser.add_argument('--json-jar')
    parser.add_argument('--native-fixture', help='Actual maximum-native.json from a native stage')
    parser.add_argument('--kotlin-cache', help='Gradle modules-2/files-2.1 directory')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(args.output).resolve()
    out.mkdir(exist_ok=False, parents=True)
    app = root / 'apps/spatial-camera-panel-android'
    native = app / 'native-receipt/src'
    java = app / 'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
    inputs = [pathlib.Path(__file__)]

    def put(name, value):
        path = out / name
        path.parent.mkdir(exist_ok=True, parents=True)
        path.write_text(value, encoding='utf-8')
        return path

    def run(name, argv):
        result = subprocess.run([str(x) for x in argv], cwd=root, capture_output=True)
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError(name + ' failed; retained stdout/stderr')
        return result.stdout.decode('utf-8')

    def cargo(lib):
        put('Cargo.toml', '[package]\nname="finite_freshness_host"\nversion="0.0.0"\nedition="2021"\n'
            '[dependencies]\nserde_json="1"\n[workspace]\n'
            '[lints.rust]\nunexpected_cfgs={level="warn",check-cfg=["cfg(rq_environment_depth_spatial_sdk_api_layer)"]}\n')
        put('src/lib.rs', lib)
        return run('native', ['cargo', 'test', '--manifest-path', out / 'Cargo.toml', '--offline', '--', '--nocapture', '--test-threads=1'])

    if args.stage in ('native', 'all'):
        lib = '#![allow(dead_code)]\npub(crate)const PROJECTION_COMPOSITION_READBACK_CAPTURE:u32=0x8000;\n'
        for name in ['stereo_input_set', 'spatial_stereo_dropouts', 'stereo_bank_mask_v1',
                     'spatial_stereo_qualification', 'spatial_sdk_depth_handoff', 'camera_hwb_projection_readback']:
            path = native / (name + '.rs')
            inputs.append(path)
            lib += '#[path=' + json.dumps(path.as_posix()) + ']mod ' + name + ';\n'
        result = cargo(lib)
        marker = 'FINITE_MAX_NATIVE_JSON '
        maximum = next(line.split(marker, 1)[1] for line in result.splitlines() if marker in line)
        put('maximum-native.json', maximum)
        clean_marker='FINITE_CLEAN_NATIVE_PAIR '
        clean=next(line.split(clean_marker,1)[1] for line in result.splitlines() if clean_marker in line)
        put('clean-native-pair.json',clean)
    elif args.stage in ('java', 'kotlin'):
        # Consume the actual production serializer fixture from a separate native run.
        fixture = pathlib.Path(args.native_fixture).resolve(strict=True)
        inputs.append(fixture)
        maximum = fixture.read_text()
        put('maximum-native.json', maximum)

    if args.stage in ('lifecycle', 'all'):
        path = native / 'embedded_duplex/peer_lifecycle.rs'
        inputs.append(path)
        body = item(path.read_text(), 'fn observe_admitted_start(') + '\n' + item(path.read_text(), 'fn observe_completed_start(')
        # Only the telemetry clock/mutex leaf is modeled here. This tests actual
        # owner tuple extraction; production accounting is compiled separately.
        lib = r'''#![allow(dead_code)]
use serde_json::Value;
#[cfg(test)]use serde_json::json;
mod spatial_stereo_qualification {
 pub static CALLS:std::sync::Mutex<Vec<(Option<u64>,Option<String>,Option<String>,Option<String>)>>=std::sync::Mutex::new(Vec::new());
 pub static ADMITTED:std::sync::Mutex<Vec<(Option<u64>,Option<String>,Option<String>,Option<String>)>>=std::sync::Mutex::new(Vec::new());
 pub fn admitted_media_start(s:Option<u64>,o:Option<&str>,a:Option<&str>,i:Option<&str>){ADMITTED.lock().unwrap().push((s,o.map(str::to_owned),a.map(str::to_owned),i.map(str::to_owned)));}
 pub fn completed_media_start(s:Option<u64>,o:Option<&str>,a:Option<&str>,i:Option<&str>){CALLS.lock().unwrap().push((s,o.map(str::to_owned),a.map(str::to_owned),i.map(str::to_owned)));}
}
#[derive(Default)]struct Retired{total:u64}
#[derive(Default)]struct State{retired_lifetimes:Retired,start_observation_original_id:Option<String>,start_mutation:Option<Value>,activation:Option<Value>,activation_id:Option<String>}
''' + body + r'''
#[test]fn actual_completed_start_tuple_and_failed_start_never_fabricate_completion(){
 let mut s=State::default();s.start_observation_original_id=Some("actual.original".into());
 s.start_mutation=Some(json!({"mutation":{"platform_action":{"action_id":"actual.action"}}}));
 s.activation_id=Some("actual.activation".into());
 s.activation=Some(json!({"activation":{"status":"pending","activation_id":"actual.activation"}}));
 observe_admitted_start(&s);
 assert_eq!(spatial_stereo_qualification::ADMITTED.lock().unwrap()[0],(Some(1),Some("actual.original".into()),Some("actual.action".into()),Some("actual.activation".into())));
 observe_completed_start(&s);assert!(spatial_stereo_qualification::CALLS.lock().unwrap().is_empty());
 s.activation.as_mut().unwrap()["activation"]["status"]=json!("completed");
 observe_completed_start(&s);s.retired_lifetimes.total=1;observe_completed_start(&s);
 s.retired_lifetimes.total=u64::MAX;observe_completed_start(&s);
 let calls=spatial_stereo_qualification::CALLS.lock().unwrap();
 assert_eq!(calls[0],(Some(1),Some("actual.original".into()),Some("actual.action".into()),Some("actual.activation".into())));
 assert_eq!(calls[1].0,Some(2));assert_eq!(calls[2].0,None);
}
'''
        # Keep all-stage native fixture and logs intact in a separate crate.
        if args.stage == 'all':
            previous = out
            out = out / 'lifecycle'
            out.mkdir()
            cargo(lib)
            out = previous
        else:
            cargo(lib)

    if args.stage in ('java', 'all'):
        jar = pathlib.Path(args.json_jar).resolve(strict=True)
        inputs.append(jar)
        path = java / 'embedded_duplex/EmbeddedDuplexProcessHost.java'
        inputs.append(path)
        body = item(path.read_text(), 'private CompletableFuture<String> captureDiagnostic(')
        # The exact production asynchronous composition, validation and wrapper
        # serialization execute. OS time, JNI and retained resource snapshots are
        # primitive leaves; the maximum Native body is from production Rust.
        media = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
        capture = media / 'PackedStereoCaptureOwner.java'
        inputs += [capture, media / 'CaptureFrameTrace.java', media / 'ReceiverStageTrace.java']
        maximum_stages = put('MaximumStages.java', r'''
package io.github.mesmerprism.rustyquest.media;
import org.json.*;import java.lang.reflect.*;import java.util.*;
public class MaximumStages {
 static void max(Object object,int width)throws Exception{
  for(Field f:object.getClass().getDeclaredFields()){
   if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Class<?> t=f.getType();
   if(t==long.class)f.setLong(object,Long.MAX_VALUE);
   else if(t==boolean.class)f.setBoolean(object,true);
   else if(t==long[].class)Arrays.fill((long[])f.get(object),Long.MAX_VALUE);
   else if(t==long[][].class){long[][] rows=(long[][])f.get(object);for(int n=0;n<rows.length;n++){rows[n]=new long[width];Arrays.fill(rows[n],Long.MAX_VALUE);}}
   else if(t.isArray()&&!t.getComponentType().isPrimitive()){for(Object child:(Object[])f.get(object))if(child!=null)max(child,width);}
  }
 }
 public static JSONObject receiver()throws Exception{ReceiverStageTrace t=new ReceiverStageTrace(Long.MAX_VALUE);max(t,8);return t.snapshot(Long.MAX_VALUE);}
 public static JSONObject source()throws Exception{
  MaximumStages t=new MaximumStages();max(t.leftFrameTrace,3);max(t.rightFrameTrace,3);
  for(CaptureFrameTrace trace:new CaptureFrameTrace[]{t.leftFrameTrace,t.rightFrameTrace}){Field f=CaptureFrameTrace.class.getDeclaredField("processEpoch");f.setAccessible(true);f.set(trace,"e".repeat(32));}
  return t.diagnosticDropoutSnapshot();
 }
 long captureInstance=Long.MAX_VALUE;boolean stopRequested=true,startupSettled=true;
 CaptureFrameTrace leftFrameTrace=new CaptureFrameTrace(),rightFrameTrace=new CaptureFrameTrace();
''' + item(capture.read_text(), 'public JSONObject diagnosticDropoutSnapshot(') + '\n}\n')
        lineage_path = java / 'embedded_duplex/EmbeddedDuplexDropoutLineage.java'
        inputs.append(lineage_path)
        lineage = item(lineage_path.read_text(), 'static void requireCurrent(')
        source = r'''
import org.json.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
class Fence{long generation(){return Long.MAX_VALUE;}String epochId(){return "e".repeat(32);}void requireLive(long x){}}
class EmbeddedDuplexResources{
 boolean ownAppCaptureEnabled(){return true;}JSONObject sourceDropoutSnapshot()throws Exception{return io.github.mesmerprism.rustyquest.media.MaximumStages.source();}
 JSONObject receiverStageSnapshot()throws Exception{return io.github.mesmerprism.rustyquest.media.MaximumStages.receiver();}JSONObject sourceSnapshot(){throw new AssertionError();}
}
class EmbeddedDuplexDropoutLineage{''' + lineage + r'''}
class ConcurrentStereoQualification{
 static String raw;static String status(String c,String e,String r,String f,String a){return first(c).toString();}
 static JSONObject first(String c){JSONArray words=new JSONArray();for(int n=0;n<160;n++)words.put(Long.MAX_VALUE);return new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","read").put("native_snapshot",words).put("challenge",c).put("process_epoch_id","e".repeat(32)).put("runtime_config_sha256","a".repeat(64)).put("feature_lock_sha256","b".repeat(64)).put("apk_sha256","c".repeat(64)).put("arm_generation",Long.MAX_VALUE).put("native_process_generation",Long.MAX_VALUE);}
 static String diagnosticArmContext(String c,String e){return "{}";}static String dropoutObservation(String c,String e){return raw;}
}
public class FiniteBridgeMain{
 enum Phase{READY,FAILED}AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);boolean localFixture;
 EmbeddedDuplexResources resources=new EmbeddedDuplexResources();Fence processFence=new Fence();String runtimeConfigSha256="a".repeat(64),ownFeatureLockSha256="b".repeat(64);
 void requireFreshProcess(){}CompletableFuture<String> concurrentQualificationOnLane(boolean a,String c,boolean b,Object d,Object e){return CompletableFuture.completedFuture(ConcurrentStereoQualification.first(c).toString());}
 CompletableFuture<String> submit(Callable<String> c){try{return CompletableFuture.completedFuture(c.call());}catch(Exception e){return CompletableFuture.failedFuture(e);}}
''' + body + r'''
 public static void main(String[] args)throws Exception{
  ConcurrentStereoQualification.raw=Files.readString(Path.of(args[0]));FiniteBridgeMain h=new FiniteBridgeMain();
  String raw=h.captureDiagnostic("d".repeat(64),true).get();JSONObject result=new JSONObject(raw);
  if(!result.getString("schema").equals("rusty.quest.embedded_duplex.stream_dropout_diagnostic.v2"))throw new AssertionError("v2 wrapper missing");
  if(result.length()!=13||result.getBoolean("qualification_claimed"))throw new AssertionError("closed wrapper changed");
  if(!result.getJSONObject("native_dropout_observation").toString().equals(new JSONObject(ConcurrentStereoQualification.raw).toString()))throw new AssertionError("Native JSON clipped");
  if(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw new AssertionError("wrapper cap");
  System.out.println("PASS actual wrapper maximum_native="+ConcurrentStereoQualification.raw.length()+" actual_maximum_stage_serializers="+raw.length());
  ConcurrentStereoQualification.raw=new JSONObject().put("payload","x".repeat(32768)).toString();
  try{h.captureDiagnostic("challenge",true).get();throw new AssertionError("oversize accepted");}catch(ExecutionException expected){if(!(expected.getCause() instanceof IllegalStateException))throw expected;}
 }
}
'''
        source_path = put('FiniteBridgeMain.java', source)
        clock = put('android/os/SystemClock.java', 'package android.os;public class SystemClock{static long n=1;public static long elapsedRealtimeNanos(){return n++;}}')
        classes = out / 'classes'
        classes.mkdir()
        run('javac', ['javac', '-cp', jar, '-d', classes, source_path, clock, maximum_stages,
                      media / 'CaptureFrameTrace.java', media / 'ReceiverStageTrace.java'])
        run('java', ['java', '-cp', str(classes) + os.pathsep + str(jar), 'FiniteBridgeMain', out / 'maximum-native.json'])

    if args.stage in ('kotlin', 'all'):
        path = java / 'StereoBankControls.kt'
        inputs.append(path)
        body = item(path.read_text(), 'fun concurrentDropoutObservation(')
        source = r'''
object FiniteKotlinBridge {
 var payload="";fun challengeWords(c:String)=longArrayOf(-1,2)
 fun nativeReadConcurrentStereoDropouts(a:Long,b:Long,c:Long):String?=payload
''' + body + r'''
}
fun main(args:Array<String>){
 val maximum=java.io.File(args[0]).readText();FiniteKotlinBridge.payload=maximum
 check(FiniteKotlinBridge.concurrentDropoutObservation("challenge",1)==maximum)
 FiniteKotlinBridge.payload="x".repeat(16384);check(FiniteKotlinBridge.concurrentDropoutObservation("challenge",1).length==16384)
 FiniteKotlinBridge.payload="x".repeat(16385)
 try{FiniteKotlinBridge.concurrentDropoutObservation("challenge",1);error("oversize accepted")}catch(expected:IllegalArgumentException){}
 println("PASS actual Kotlin body cap=16384 maximum_native="+maximum.length)
}
'''
        source_path = put('FiniteKotlinBridge.kt', source)
        cache = pathlib.Path(args.kotlin_cache).resolve(strict=True)
        jars = []
        for group, name, version in [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.0'),
                                     ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.0'),
                                     ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.0'),
                                     ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                                     ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.2.0'),
                                     ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                                     ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                                     ('org.jetbrains', 'annotations', '13.0')]:
            found = list((cache / group / name / version).glob('*/*.jar'))
            if found:
                jars += found
        stdlib = next(jar for jar in jars if jar.name.startswith('kotlin-stdlib-'))
        inputs += jars
        classes = out / 'kotlin-classes'
        classes.mkdir()
        run('kotlinc', ['java', '-cp', os.pathsep.join(str(jar) for jar in jars), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes, source_path])
        run('kotlin', ['java', '-cp', str(classes) + os.pathsep + str(stdlib), 'FiniteKotlinBridgeKt', out / 'maximum-native.json'])

    pins = [{'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()} for path in inputs]
    put('report.json', json.dumps({'status': 'passed', 'stage': args.stage, 'source_inputs': pins,
        'modeled_leaves': ['OS/JNI clock', 'owner callback transport', 'Android resource lifecycle', 'closed maximum qualification input snapshot'],
        'device_calls': 0, 'apk_built': False, 'media_qualification': False}, indent=2))
    print('PASS finite freshness stage=' + args.stage)


if __name__ == '__main__':
    main()
