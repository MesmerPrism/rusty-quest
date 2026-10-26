"""Run actual Original store and extracted Host/Service callers with modeled OS leaves; no APK/device."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import uuid


def extract_method(source, signature):
    """Keep exact source bytes through the balanced body; ignore Java literals/comments."""
    start = source.index(signature)
    body = source.index('{', start)
    depth, index, state = 0, body, 'code'
    while index < len(source):
        char = source[index]
        following = source[index:index + 2]
        if state == 'code':
            if following == '//': state = 'line'; index += 1
            elif following == '/*': state = 'comment'; index += 1
            elif char == '"': state = 'string'
            elif char == "'": state = 'char'
            elif char == '{': depth += 1
            elif char == '}':
                depth -= 1
                if depth == 0: return source[start:index + 1]
        elif state == 'line':
            if char == '\n': state = 'code'
        elif state == 'comment':
            if following == '*/': state = 'code'; index += 1
        elif char == '\\': index += 1
        elif (state == 'string' and char == '"') or (state == 'char' and char == "'"): state = 'code'
        index += 1
    raise ValueError('Unclosed source method: ' + signature)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--java-home', type=pathlib.Path)
    parser.add_argument('--json-jar', type=pathlib.Path)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--support-classpath', default='', help='Current compiled production fresh-helper classes; not runtime proof')
    args = parser.parse_args()
    root = args.root.resolve()
    out = (args.output or root / 'target' / ('originals-host-' + uuid.uuid4().hex)).resolve()
    if not out.is_relative_to(root / 'target'):
        raise ValueError('Fresh output must be beneath repository target')
    out.mkdir(parents=True, exist_ok=False)
    java_home = args.java_home or pathlib.Path(os.environ.get('JAVA_HOME') or pathlib.Path(shutil.which('javac')).parent.parent)
    jar = args.json_jar
    if jar is None:
        jars = list((pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('json-20240303.jar'))
        if len(jars) != 1:
            raise ValueError('Supply the pinned org.json20240303 host jar')
        jar = jars[0]
    if hashlib.sha256(jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
        raise ValueError('Host JSON dependency differs')
    package = 'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    production = root / 'apps/spatial-camera-panel-android/app/src/main/java' / package
    fixture = root / 'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexOriginalsHostTest.java'
    models = {
        'android/os/Bundle.java': 'package android.os; public class Bundle extends java.util.HashMap<String,Object>{ public Bundle(){} public Bundle(Bundle b){super(b);} public void putString(String k,String v){put(k,v);} public String getString(String k){return (String)get(k);} public void putFloat(String k,float v){put(k,v);} }',
        'android/content/Context.java': 'package android.content; public abstract class Context { public Context getApplicationContext(){return this;} public abstract java.io.File getNoBackupFilesDir(); public String getPackageName(){return "fixture.package";} }',
        'android/system/Os.java': 'package android.system; public final class Os {public static void chmod(String p,int v){} public static java.io.FileDescriptor open(String p,int f,int v){return new java.io.FileDescriptor();} public static void fsync(java.io.FileDescriptor f){} public static void close(java.io.FileDescriptor f){} }',
        'android/system/OsConstants.java': 'package android.system; public final class OsConstants {public static final int O_RDONLY=0,O_CLOEXEC=0,O_NOFOLLOW=0;}',
        'android/os/Process.java': 'package android.os; public final class Process { public static int myPid(){return 42;} public static int myUid(){return 10001;} }',
        'io/github/mesmerprism/rustyquest/spatial_camera_panel/BuildConfig.java': 'package io.github.mesmerprism.rustyquest.spatial_camera_panel; public final class BuildConfig { public static final String APPLICATION_ID="fixture.package"; }',
    }
    host_source = (production / 'EmbeddedDuplexProcessHost.java').read_text(encoding='utf-8')
    service_source = (production / 'EmbeddedDuplexRuntimeService.java').read_text(encoding='utf-8')
    host_methods = [extract_method(host_source, signature) for signature in (
        'static synchronized EmbeddedDuplexProcessHost forApplication(',
        'private void ensureProcessFence(', 'static long currentAppGeneration(',
        'static JSONObject originalActor(', 'static boolean originalActorSettled(',
        'private <T> CompletableFuture<T> submit(')]
    service_methods = [extract_method(service_source, signature) for signature in (
        'public static Bundle beginOriginal(', 'public static Bundle queryOriginal(',
        'public static Bundle acknowledgeOriginal(')]
    executor_match = re.search(r'private final [\w.]+ commands =', host_source)
    if executor_match is None: raise ValueError('Actual command executor declaration unavailable')
    executor_start = executor_match.start()
    executor_end = host_source.index('\n    });', executor_start) + len('\n    });')
    actual_executor = host_source[executor_start:executor_end]
    imports = 'package ' + package.replace('/', '.') + ';\nimport android.content.Context; import android.os.Bundle; import org.json.JSONObject; import java.util.concurrent.*; import java.util.concurrent.atomic.AtomicReference; import java.util.function.Supplier;\n'
    host_leaves = """
      private enum Phase {NEW, PROVISIONING, BOOTSTRAPPING, READY, CLOSING, FAILED}
      private static volatile EmbeddedDuplexProcessHost instance;
      private final Context applicationContext;
      private volatile EmbeddedDuplexProcessFence processFence;
      private long nativeExecutorGeneration;
      private String nativeAppRecordSha256,terminalWholeReceipt;
      private JSONObject originalClosedActor;
      private EmbeddedDuplexOriginals.Record activeCommandOriginal;
      private Object platform,resources;
      private final AtomicReference<Phase> phase=new AtomicReference<>(Phase.NEW);
      private EmbeddedDuplexProcessHost(Context context){applicationContext=context;}
      private String checkpointSnapshot(){return null;}
      private String evidenceSnapshot(){return null;}
      private interface Work<T>{T run()throws Exception;}
      <T> CompletableFuture<T> harnessSubmit(java.util.concurrent.Callable<T> work){return submit(work::call);}
      void harnessReady(String hash,long generation){nativeAppRecordSha256=hash;nativeExecutorGeneration=generation;platform=new Object();resources=new Object();phase.set(Phase.READY);}
      void harnessNativeGeneration(long generation){nativeExecutorGeneration=generation;}
      void harnessDropResources(){resources=null;}
      void harnessClose()throws Exception{commands.shutdown();if(!commands.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("command lane did not settle");if(processFence!=null)processFence.close();}
    """
    original_source = (production / 'EmbeddedDuplexOriginals.java').read_text(encoding='utf-8')
    exercised = {re.search(r'(\w+)\s*\(', method).group(1) for method in host_methods}
    compile_only = []
    for name in sorted(set(re.findall(r'EmbeddedDuplexProcessHost\.(\w+)\(', original_source)) - exercised):
        declaration = re.search(r'(?m)^    (?:public )?static [^\n{]*?\b' + re.escape(name) + r'\(', host_source)
        if declaration is None: raise ValueError('Missing actual Host API: ' + name)
        method = extract_method(host_source, declaration.group().strip())
        header = method[:method.index('{')]
        # These fresh physical/resource APIs are outside this finite/custody regression's execution scope.
        # Any accidental invocation fails; no replacement result is supplied for an untested owner.
        compile_only.append(header + '{throw new UnsupportedOperationException("fresh physical owner outside this regression");}')
    if compile_only and not args.support_classpath:
        raise ValueError('Expanded fresh producer requires --support-classpath with current compiled production helper classes')
    models[package + '/EmbeddedDuplexProcessHost.java'] = imports + 'final class EmbeddedDuplexProcessHost {\n' + host_leaves + actual_executor + '\n' + '\n'.join(host_methods + compile_only) + '\n}'
    models[package + '/EmbeddedDuplexRuntimeService.java'] = imports + 'public final class EmbeddedDuplexRuntimeService {\n' + '\n'.join(service_methods) + '\n}'
    # Redirect only the OS filesystem root; identity parsing, companion and fence producers remain actual source.
    incarnation_source = (production / 'EmbeddedDuplexProcessIncarnation.java').read_text(encoding='utf-8')
    models[package + '/EmbeddedDuplexProcessIncarnation.java'] = incarnation_source.replace('Paths.get("/proc")', 'Paths.get(System.getProperty("host.proc"))')
    proc = out / 'proc'
    (proc / 'sys/kernel/random').mkdir(parents=True)
    (proc / '42').mkdir()
    (proc / 'sys/kernel/random/boot_id').write_text('00112233-4455-6677-8899-aabbccddeeff\n')
    (proc / '42/stat').write_text('42 (modeled host process) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 123456 21 22\n')
    (out / 'runtime').mkdir()
    model_paths = []
    for rel, body in models.items():
        path = out / 'models' / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
        model_paths.append(str(path))
    classes = out / 'classes'
    classes.mkdir()
    sources = [production / 'EmbeddedDuplexCanonicalJson.java', production / 'EmbeddedDuplexOriginals.java', production / 'EmbeddedDuplexProcessFence.java', fixture]
    for name in ('EmbeddedDuplexFreshLifecycle.java', 'EmbeddedDuplexNativeOperationFailure.java'):
        if (production / name).exists(): sources.append(production / name)
    source_paths = sources + [production / name for name in ('EmbeddedDuplexProcessHost.java', 'EmbeddedDuplexRuntimeService.java', 'EmbeddedDuplexProcessIncarnation.java')] + [pathlib.Path(__file__).resolve()]
    source_pins = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_paths}
    command = [str(java_home / 'bin/javac.exe'), '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join([str(jar), args.support_classpath]) if args.support_classpath else str(jar), '-d', str(classes)] + model_paths + [str(p) for p in sources]
    with (out / 'compile.log').open('wb') as stream:
        subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=60)
    with (out / 'controls.log').open('wb') as stream:
        subprocess.run([str(java_home / 'bin/java.exe'), '-Djava.io.tmpdir=' + str(out / 'runtime'), '-Dhost.proc=' + str(proc), '-cp', os.pathsep.join((str(classes), str(jar), args.support_classpath)), package.replace('/', '.') + '.EmbeddedDuplexOriginalsHostTest'], stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=60)
    if any(hashlib.sha256((root / relative).read_bytes()).hexdigest() != digest for relative, digest in source_pins.items()):
        raise ValueError('Source changed during the exact-body host controls; rerun against stable inputs')
    result = {'status': 'passed', 'sources': source_pins, 'scope': 'Production Original store and exact extracted Host submit/forApplication/actor/settled plus RuntimeService begin/query/ACK; real command executor, Future, fence and file operations. Android/process-root/native/resources leaves modeled.', 'extracted_methods': [{'sha256': hashlib.sha256(method.encode()).hexdigest(), 'source': 'EmbeddedDuplexProcessHost.java' if method in host_methods else 'EmbeddedDuplexRuntimeService.java', 'signature': method[:method.index(chr(123))].strip()} for method in host_methods + service_methods], 'modeled_leaves': ['Android Context/Bundle/Process/system APIs', 'proc root path only; real identity parser', 'checkpoint/evidence journal reads return absent', 'retained platform/resources and native actor facts initialized by host fixture'], 'generated_sources': {str(pathlib.Path(path).relative_to(out)): hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest() for path in model_paths}, 'compile_only_unexercised_host_apis': [method[:method.index('{')].strip() for method in compile_only], 'support_classpath': args.support_classpath, 'limitations': ['No APK, device, native effects or physical-cleanup claim.', 'Fresh physical/resource Host methods are compile-only boundaries that reject invocation; this regression executes the accepted finite/custody caller path.']}
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'status': 'passed', 'output': str(out)}))


if __name__ == '__main__':
    main()
