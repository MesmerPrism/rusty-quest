#!/usr/bin/env python3
"""Real native effective-input assembly and broker admission; one frozen JVM candidate, no Android/Cargo/APK."""
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
    for name in ('jvm-spec', 'compiled-profile-tests', 'owner-libs', 'warm-rust-deps', 'assets', 'output'):
        p.add_argument('--' + name, type=pathlib.Path, required=True)
    p.add_argument('--rustc', default=shutil.which('rustc'))
    a = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = a.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    spec = json.loads(a.jvm_spec.read_text(encoding='utf-8'))
    owners = json.loads((a.owner_libs / 'BUILD.json').read_text())
    if not owners['passed']:
        raise ValueError('Current matching owner source compilation required')
    fixtures = root / 'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
    production = root / 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
    modules = [production / (name + '.rs') for name in ('packaged_route', 'packaged_config', 'development_lifetime_policy')]
    generated = out / 'probe.rs'
    generated.write_text('\n'.join('#[path=' + json.dumps(path.as_posix()) + '] mod ' + path.stem + ';' for path in modules) + '\n' +
                         (fixtures / 'EndpointAuthorityProbe.rs').read_text(), encoding='utf-8')
    base_manifest = a.assets / 'product-input-manifest.json'
    env = dict(os.environ,
        RQ_ENDPOINT_BASE_MANIFEST_SHA256=hashlib.sha256(base_manifest.read_bytes()).hexdigest(),
        RUSTY_QUEST_COMPILED_EMBEDDED_DUPLEX_LIFETIME_POLICY='development-v1')
    checks = []
    def run(name, command):
        command = [str(x) for x in command]
        (out / (name + '.command.json')).write_text(json.dumps(command, indent=2), encoding='utf-8')
        if name in ('kotlin', 'behavior'):
            arguments = out / (name + '.args')
            arguments.write_text('\n'.join(json.dumps(x.replace('\\', '/')) for x in command[1:]), encoding='utf-8')
            command = [command[0], '@' + str(arguments)]
        result = subprocess.run(command, cwd=root, capture_output=True, timeout=90, env=env)
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        checks.append({'name': name, 'exit': result.returncode})
        if result.returncode:
            print(result.stdout.decode(errors='replace'))
            print(result.stderr.decode(errors='replace')[-6000:], file=sys.stderr)
        return result.returncode
    external = []
    for crate in ('serde', 'serde_json'):
        libraries = list(a.warm_rust_deps.glob('lib' + crate + '-*.rlib'))
        if len(libraries) != 1:
            raise ValueError('Select one cached dependency for ' + crate)
        external += ['--extern', crate + '=' + str(libraries[0])]
    for crate in ('rusty_manifold_model', 'rusty_manifold_admission', 'rusty_quest_media_stream',
                  'rusty_quest_feature_activation', 'rusty_quest_broker_admission', 'rusty_quest_broker_authority'):
        external += ['--extern', crate + '=' + str(a.owner_libs / ('lib' + crate + '.rlib'))]
    binary = out / 'authority-probe.exe'
    code = run('native', [a.rustc, '--edition=2021', '--crate-name', 'endpoint_authority_probe', '-A', 'dead_code',
        generated, '-L', 'dependency=' + str(a.owner_libs), '-L', 'dependency=' + str(a.warm_rust_deps), *external, '-o', binary])
    classes = out / 'classes'
    classes.mkdir()
    support = [str(a.compiled_profile_tests.resolve()), *spec['support_classes']]
    cp = os.pathsep.join([str(classes), *spec['runtime_jars'], *support])
    java = pathlib.Path(spec['java_home']) / 'bin/java.exe'
    sources = [fixtures / (name + '.kt') for name in ('EndpointRouteCandidate', 'EndpointAuthorityHost')]
    if not code:
        code = run('kotlin', [java, '-cp', os.pathsep.join(spec['compiler_jars']),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
            '-classpath', cp, '-Xfriend-paths=' + ','.join(support), '-d', classes, *sources])
    if not code:
        code = run('behavior', [java, '-cp', cp,
            'io.github.mesmerprism.rustyquest.spatial_camera_panel.EndpointAuthorityHost',
            a.assets.resolve(), binary, out])
    def pin(path):
        path = pathlib.Path(path).resolve()
        return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    result = {'passed': code == 0, 'checks': checks, 'elapsed_seconds': round(time.monotonic() - started, 3),
        'source_pins': [pin(x) for x in [*modules, *sources, fixtures / 'EndpointAuthorityProbe.rs', pathlib.Path(__file__)]],
        'owner_build': pin(a.owner_libs / 'BUILD.json'), 'owner_outputs': owners['outputs'],
        'base_assets': [pin(x) for x in sorted(a.assets.iterdir())], 'jvm_spec': pin(a.jvm_spec),
        'profile_compilation_result': pin(a.compiled_profile_tests.parent / 'RESULT.json'),
        'generated_probe': pin(generated), 'compiled_policy': 'development-v1',
        'base_fixture_manifest_sha256': env['RQ_ENDPOINT_BASE_MANIFEST_SHA256'],
        'limits': __doc__}
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'passed': code == 0, 'checks': checks}))
    return code


if __name__ == '__main__':
    sys.exit(main())
