#!/usr/bin/env python3
"""Endpoint replacement/derivation contract probe. Uses cached JVM/Rust dependencies; never builds an APK."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('jvm-spec', 'compiled-profile-tests', 'java-support', 'android-jar', 'rust-deps', 'assets', 'output'):
        p.add_argument('--' + name, type=pathlib.Path, required=True)
    p.add_argument('--rustc', default=shutil.which('rustc'))
    a = p.parse_args()
    started = time.monotonic()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = a.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    spec = json.loads(a.jvm_spec.read_text(encoding='utf-8'))
    java = pathlib.Path(spec['java_home']) / 'bin/java.exe'
    javac = java.with_name('javac.exe')
    fixtures = root / 'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
    pkg = 'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    source = root / 'apps/spatial-camera-panel-android/app/src/main/java' / pkg
    native_source = root / 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/packaged_route.rs'
    classes = out / 'classes'
    classes.mkdir()
    support = [str(a.compiled_profile_tests.resolve()), *spec['support_classes'], str(a.java_support.resolve())]
    cp = os.pathsep.join([str(classes), *spec['runtime_jars'], *support, str(a.android_jar.resolve())])
    checks = []
    def pin(path):
        path = pathlib.Path(path).resolve()
        return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    def run(name, args):
        args = [str(x) for x in args]
        (out / (name + '.command.json')).write_text(json.dumps(args, indent=2), encoding='utf-8')
        if name in ('java', 'kotlin', 'behavior'):
            argfile = out / (name + '.args')
            argfile.write_text('\n'.join(json.dumps(x.replace('\\', '/')) for x in args[1:]), encoding='utf-8')
            args = [args[0], '@' + str(argfile)]
        result = subprocess.run(args, cwd=root, capture_output=True, timeout=90)
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        checks.append({'name': name, 'exit': result.returncode})
        if result.returncode:
            print(result.stdout.decode(errors='replace'))
            print(result.stderr.decode(errors='replace')[-5000:], file=sys.stderr)
        return result.returncode
    generated = out / 'probe.rs'
    generated.write_text('#[path=' + json.dumps(native_source.as_posix()) + '] mod packaged_route;\n' +
                         (fixtures / 'EndpointRouteReaderProbe.rs').read_text(encoding='utf-8'), encoding='utf-8')
    libraries = []
    externs = []
    for crate in ('serde', 'serde_json', 'rusty_quest_broker_authority', 'rusty_quest_media_stream'):
        matches = list(a.rust_deps.glob('lib' + crate + '-*.rlib'))
        if len(matches) != 1:
            raise ValueError('Select one cached host dependency for ' + crate)
        libraries.append(matches[0])
        externs += ['--extern', crate + '=' + str(matches[0])]
    binary = out / 'route-reader.exe'
    code = run('native', [a.rustc, '--edition=2021', '--crate-name', 'endpoint_route_reader',
                          '-A', 'dead_code', generated, '-L', 'dependency=' + str(a.rust_deps),
                          *externs, '-o', binary])
    java_sources = [source / (name + '.java') for name in ('EmbeddedDuplexPackagedInputs',
                    'EmbeddedDuplexBootstrap', 'EmbeddedDuplexEnrollmentRequest',
                    'EmbeddedDuplexSetupCredentials', 'EmbeddedDuplexOwnProjectionFailure')]
    # No Context overload is executed. Disable that branch explicitly; the tested
    # AssetReader overload receives exact pinned fixture facts instead of Android.
    build_config = out / 'BuildConfig.java'
    build_config.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel;
public final class BuildConfig {
 public static final boolean EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=false;
 public static final String EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256="";
 public static final String EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON="";
 public static final long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=3600000L;
}
''', encoding='utf-8')
    kotlin_sources = [fixtures / (name + '.kt') for name in ('EndpointRouteCandidate', 'EndpointRouteDerivationHost')]
    if not code:
        code = run('java', [javac, '--release', '17', '-cp', cp, '-d', classes, build_config, *java_sources])
    if not code:
        code = run('kotlin', [java, '-cp', os.pathsep.join(spec['compiler_jars']),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
            '-classpath', cp, '-Xfriend-paths=' + ','.join(support), '-d', classes, *kotlin_sources])
    if not code:
        code = run('behavior', [java, '-cp', cp,
            'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EndpointRouteDerivationHost',
            a.assets.resolve(), binary, out])
    support_pins = []
    for directory in support:
        tree = hashlib.sha256()
        files = sorted(x for x in pathlib.Path(directory).rglob('*') if x.is_file())
        for path in files:
            tree.update(path.relative_to(directory).as_posix().encode() + b'\0')
            tree.update(hashlib.sha256(path.read_bytes()).digest())
        support_pins.append({'path': directory, 'files': len(files), 'sha256': tree.hexdigest()})
    result = {'passed': code == 0, 'checks': checks, 'elapsed_seconds': round(time.monotonic() - started, 3),
              'source_pins': [pin(x) for x in [*java_sources, *kotlin_sources, native_source,
                  fixtures / 'EndpointRouteReaderProbe.rs', pathlib.Path(__file__)]],
              'assets': [pin(x) for x in sorted(a.assets.iterdir())],
              'dependency_pins': [pin(x) for x in [*libraries, a.android_jar]],
              'support_snapshot_pins': support_pins, 'jvm_spec': pin(a.jvm_spec),
              'generated_pins': [pin(generated), pin(build_config)],
              'modeled_leaves': ['Android Context/BuildConfig path disabled; exact fixture AssetReader overload used',
                                'native runtime-authority assembler body; Bootstrap projection alone tested'],
              'limits': __doc__}
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'passed': code == 0, 'checks': checks}))
    return code


if __name__ == '__main__':
    sys.exit(main())
