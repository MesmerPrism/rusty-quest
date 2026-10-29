"""Exact fence JNI callback with warm jni/sha2 and an installed host JVM; no Cargo/Android/device."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess
from importlib.machinery import SourceFileLoader


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    p.add_argument('--output', type=pathlib.Path, required=True)
    p.add_argument('--dependency-dir', type=pathlib.Path, required=True)
    p.add_argument('--java-home', type=pathlib.Path, required=True)
    a = p.parse_args(); root = a.root.resolve(); out = a.output.resolve()
    if not out.is_relative_to(root / 'target'):
        raise ValueError('Fresh ignored owner target required')
    out.mkdir(parents=True, exist_ok=False)
    path = root / 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/native_fence_jni.rs'
    fixture = root / 'tools/checks/fixtures/native_fence_callback_host.rs'
    fence = path.with_name('process_fence.rs')
    extractor_path = root / 'tools/checks/Test-NativeFenceFailureHost.py'
    inputs = [path, fence, fixture, pathlib.Path(__file__).resolve(), extractor_path]
    pins = {str(x.relative_to(root)): hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs}
    extract = SourceFileLoader('fence_native_extract', str(extractor_path)).load_module().block
    source = path.read_text()
    names = ['struct JavaAppFenceSource', 'impl AppFenceSource for JavaAppFenceSource', 'fn capture_source(', 'fn callback_string(', 'fn callback_string_observed(', 'fn bounded_fence_string(', 'fn fence_exception_summary(', 'fn emit_fence_callback_failure(']
    bodies = [extract(source, name) for name in names if name in source]
    bodies.insert(0, extract(fence.read_text(), 'pub(super) trait AppFenceSource').replace('pub(super) ', ''))
    if 'fn fence_exception_summary(' not in source:
        bodies.append('fn fence_exception_summary(_: &mut JNIEnv, _: &JObject) -> jni::errors::Result<String> { Err(jni::errors::Error::NullPtr("missing first exception evidence")) }')
    if 'fn callback_string_observed(' not in source:
        bodies.append('fn callback_string_observed(env: &mut JNIEnv, object: &JObject, method: &str, max: usize, _: Option<&AtomicBool>) -> Result<String,String> { callback_string(env, object, method, max) }')
    # Only the existing Android marker leaf is enabled on the host, with a recording sink.
    production = '\n'.join(bodies).replace('#[cfg(target_os = "android")]', '')
    generated = fixture.read_text().replace('// ACTUAL_CALLBACK_FUNCTIONS', production)
    (out / 'controls.rs').write_text(generated)
    java = '''public final class FenceCallbackFixture {
      public int mode;
      public String nativeAdmissionRecord() { if (mode==1) return badRecord(); if (mode==2) return hostileRecord(); return normalRecord(); }
      public String normalRecord() { return "held-record"; }
      public String badRecord() { throw new IllegalStateException("private outer payload", new java.io.IOException("private inner payload")); }
      public String hostileRecord() { throw new Hostile(); }
      public Throwable cycle() { Throwable a = new IllegalStateException("cycle A"); Throwable b = new java.io.IOException("cycle B"); a.initCause(b); b.initCause(a); return a; }
      public Throwable deep() { Throwable result = new java.io.IOException("bottom"); for (int i=0; i<5; i++) result = new IllegalStateException("level"+i, result); return result; }
      static final class Hostile extends IllegalStateException {
        Hostile() { super("stored message"); }
        public String getMessage() { throw new AssertionError("getMessage must not run"); }
        public synchronized Throwable getCause() { throw new AssertionError("getCause must not run"); }
      }
    }'''
    (out / 'FenceCallbackFixture.java').write_text(java)
    dep = a.dependency_dir.resolve(); jhome = a.java_home.resolve()
    extern = []; dependencies = {}
    for name in ['jni', 'sha2']:
        files = list(dep.glob('lib' + name + '-*.rlib'))
        if len(files) != 1: raise ValueError('Exact warm ' + name + ' rlib required')
        extern += ['--extern', name + '=' + str(files[0])]
        dependencies[str(files[0])] = hashlib.sha256(files[0].read_bytes()).hexdigest()
    env = os.environ.copy()
    env['PATH'] = str(jhome / 'bin/server') + os.pathsep + str(jhome / 'bin') + os.pathsep + env['PATH']
    env['FENCE_CALLBACK_CLASSES'] = str(out)
    def run(label, command):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, timeout=60)
        (out / (label + '.log')).write_bytes(result.stdout)
        return result.returncode
    javac = run('javac', [str(jhome / 'bin/javac.exe'), '-d', str(out), str(out / 'FenceCallbackFixture.java')])
    compile_exit = run('compile', ['rustc', '--edition', '2021', '--test', str(out / 'controls.rs'), '-L', 'dependency=' + str(dep), '-L', 'native=' + str(jhome / 'lib'), '-l', 'jvm'] + extern + ['-o', str(out / 'controls.exe')]) if javac == 0 else None
    controls_exit = run('controls', [str(out / 'controls.exe'), '--test-threads=1', '--nocapture']) if compile_exit == 0 else None
    unchanged = all(hashlib.sha256((root / n).read_bytes()).hexdigest() == h for n, h in pins.items())
    report = {'passed': javac == 0 and compile_exit == 0 and controls_exit == 0 and unchanged,
              'javac_exit': javac, 'compile_exit': compile_exit, 'controls_exit': controls_exit,
              'source_pins': pins, 'source_unchanged': unchanged, 'dependency_pins': dependencies,
              'generated_sha256': hashlib.sha256((out / 'controls.rs').read_bytes()).hexdigest(),
              'jvm_sha256': hashlib.sha256((jhome / 'bin/server/jvm.dll').read_bytes()).hexdigest(),
              'extracted_functions': [n for n in names if n in source], 'scope': __doc__,
              'limits': 'Actual extracted callback and fingerprint producer with real host JNI pending exceptions/Throwable fields/UTF16 and SHA256. Android marker sink modeled. No ART, packaged library, Android compile or device proof.',
              'device_calls': 0, 'apk_builds': 0}
    (out / 'RESULT.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': report['passed'], 'compile_exit': compile_exit, 'controls_exit': controls_exit, 'output': str(out)}))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
