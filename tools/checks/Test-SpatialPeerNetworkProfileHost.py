#!/usr/bin/env python3
"""Inert endpoint-data pilot: actual strict/profile/selector sources, cached app value types, no Android effects."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time


def main():
    started = time.monotonic()
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jvm-spec', type=pathlib.Path, required=True)
    p.add_argument('--output', type=pathlib.Path, required=True)
    a = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = a.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    spec = json.loads(a.jvm_spec.read_text(encoding='utf-8'))
    java = pathlib.Path(spec['java_home']) / 'bin/java.exe'
    javac = java.with_name('javac.exe')
    compiler = os.pathsep.join(spec['compiler_jars'])
    support = spec['support_classes']
    classes = out / 'classes'
    classes.mkdir()
    cp = os.pathsep.join([str(classes), *spec['runtime_jars'], *support])
    package = 'io/github/mesmerprism/rustyquest/spatial_camera_panel'
    app = root / 'apps/spatial-camera-panel-android/app'
    source = app / 'src/main/java' / package
    paths = [source / name for name in ('StrictJsonByteIngress.kt', 'SpatialCameraControlProfile.kt',
                                       'SpatialPeerNetworkProfilePilot.kt')]
    paths.append(app / 'src/test/java' / package / 'SpatialCameraControlProfileTest.kt')
    def pin(path):
        path = pathlib.Path(path)
        return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    pins = [pin(path) for path in [*paths, pathlib.Path(__file__)]]
    # Keep cached helper bytecode identifiable even when its directory name stays fixed.
    support_pins = []
    for directory in support:
        tree = hashlib.sha256()
        files = sorted(path for path in pathlib.Path(directory).rglob('*') if path.is_file())
        for path in files:
            tree.update(path.relative_to(directory).as_posix().encode('utf-8') + b'\0')
            tree.update(hashlib.sha256(path.read_bytes()).digest())
        support_pins.append({'path': directory, 'files': len(files), 'sha256': tree.hexdigest()})
    checks = []
    def run(name, args):
        args = [str(arg) for arg in args]
        argfile = out / (name + '.args')
        argfile.write_text('\n'.join(json.dumps(arg.replace('\\', '/')) for arg in args[1:]), encoding='utf-8')
        r = subprocess.run([args[0], '@' + str(argfile)], cwd=root, capture_output=True, timeout=60)
        (out / (name + '.stdout')).write_bytes(r.stdout)
        (out / (name + '.stderr')).write_bytes(r.stderr)
        checks.append({'name': name, 'exit': r.returncode})
        if r.returncode:
            print(r.stdout.decode(errors='replace'))
            print(r.stderr.decode(errors='replace'), file=sys.stderr)
        return r.returncode
    code = run('compile', [java, '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                          '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp,
                          '-Xfriend-paths=' + ','.join(support), '-d', classes, *paths])
    if not code:
        runner = out / 'EndpointProfileMain.java'
        runner.write_text('''import org.junit.runner.*;
import org.junit.runner.notification.*;
public class EndpointProfileMain {
 public static void main(String[] names) throws Exception {
  Class<?> test=Class.forName("io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraControlProfileTest");
  int failures=0;
  for(String name:names){Result r=new JUnitCore().run(Request.method(test,name));
   for(Failure f:r.getFailures())System.err.println(f.getTrace());
   failures+=r.getFailureCount();
  }
  System.out.println("endpoint profile checks="+names.length+" failures="+failures);
  if(failures!=0)System.exit(1);
 }
}''', encoding='utf-8')
        code = run('runner', [javac, '--release', '17', '-cp', cp, '-d', classes, runner])
        if not code:
            code = run('tests', [java, '-cp', cp, 'EndpointProfileMain',
                'endpointPilotSelectsTwoProfilesOnOneResidentConfiguration',
                'endpointPilotRefusesMalformedAndUnsupportedUpdatesPreservingAcceptedSelection',
                'endpointPilotDoesNotEnableExistingActivityParser'])
    result = {'passed': code == 0, 'checks': checks, 'source_pins': pins,
              'jvm_spec': pin(a.jvm_spec), 'support_snapshot_pins': support_pins,
              'tool_pins': [pin(path) for path in [java, javac, *spec['compiler_jars'], *spec['runtime_jars']]],
              'elapsed_seconds': round(time.monotonic() - started, 3), 'limits': __doc__}
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'passed': code == 0, 'checks': checks}))
    return code


if __name__ == '__main__':
    sys.exit(main())
