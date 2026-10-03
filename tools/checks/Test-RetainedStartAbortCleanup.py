#!/usr/bin/env python3
"""Target-free checks of an explicitly pinned modeled supplier composition.

This runner never creates an APK, contacts a device, or admits source. The
input pins every loaded local Rust/Java source and tool before invocation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def check_pins(rows):
    seen = set()
    for row in rows:
        path = Path(row['path']).resolve()
        if str(path).casefold() in seen:
            raise ValueError('duplicate source/tool pin')
        seen.add(str(path).casefold())
        expected = row['sha256']
        if len(expected) != 64 or any(c not in '0123456789abcdef' for c in expected):
            raise ValueError('invalid source/tool SHA')
        if path.stat().st_size != row['size_bytes'] or digest(path) != expected:
            raise ValueError('source/tool pin changed: ' + str(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', required=True)
    parser.add_argument('--inputs-sha256', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if digest(args.inputs) != args.inputs_sha256:
        raise ValueError('input spec changed')
    spec = json.loads(Path(args.inputs).read_text(encoding='utf-8-sig'))
    if spec['schema'] != 'local.quest.retained_start_abort_host_inputs.v1':
        raise ValueError('input schema')
    root = Path(spec['model_quest_root']).resolve()
    model = json.loads(Path(spec['model_receipt']['path']).read_text(encoding='utf-8-sig'))
    if model.get('modeled_compile_only') is not True or model.get('source_admission') is not False:
        raise ValueError('not a modeled compile graph')
    if not any(row['commit'] == '091dabcbef02e0f93fbbcb6eaf0fc7d14307253c'
               for row in model['repositories']):
        raise ValueError('exact owner supplier absent')
    tools = spec['tools']
    rows = spec['source_files'] + list(tools.values()) + [spec['model_receipt']]
    check_pins(rows)
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    results = []

    def run(name, argv, env=None):
        started = time.monotonic()
        stdout = output / (name + '.stdout.txt')
        stderr = output / (name + '.stderr.txt')
        with stdout.open('xb') as out, stderr.open('xb') as err:
            child = subprocess.Popen(argv, cwd=root, env=env, stdout=out, stderr=err)
            try:
                code = child.wait(timeout=240)
            except subprocess.TimeoutExpired:
                # Kill only this task-owned process tree; retain raw prefixes.
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(child.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                else:
                    child.kill()
                child.wait(timeout=10)
                code = -1
        results.append({'name': name, 'native_exit': code,
                        'elapsed_seconds': time.monotonic() - started,
                        'stdout_sha256': digest(stdout), 'stderr_sha256': digest(stderr)})
        if code != 0:
            raise ValueError(name + ' failed; actual streams retained')

    cargo = tools['cargo']['path']
    env = dict(os.environ)
    env['RUSTC'] = tools['rustc']['path']
    env['ANDROID_NDK_HOME'] = spec['ndk_root']
    env['GLSLC'] = tools['glslc']['path']
    env['RUSTY_QUEST_SPATIAL_ENVIRONMENT_DEPTH_OWNER'] = 'spatial-sdk-api-layer'
    status = 'failed'
    try:
        run('media', [cargo, 'test', '--offline', '--locked', '-p', 'rusty-quest-media-stream', '--lib'], env)
        run('retained-transport', [cargo, 'test', '--offline', '--locked', '-p',
            'rusty-quest-media-stream-android', 'retained_', '--', '--nocapture'], env)
        for name in ['retained_failed_start_uses_live_revoker_and_original_target_stop',
                     'concurrent_actual_22_coupled_cycles_preserve_two_live_resource_graphs']:
            run(name, [cargo, 'test', '--offline', '--locked', '-p',
                'rusty-quest-broker-authority', name, '--', '--nocapture'], env)
            if '1 passed;' not in (output / (name + '.stdout.txt')).read_text(encoding='utf-8'):
                raise ValueError('focused owner test did not execute')
        run('android-native', [cargo, 'check', '--offline', '--locked', '-p',
            'spatial-camera-panel-native-receipt', '--target', 'aarch64-linux-android'], env)
        run('neutral-consumer', [cargo, 'check', '--offline', '--locked', '--manifest-path',
            str(root / 'apps/media-stream-conformance-android/native/Cargo.toml')], env)
        module = Path(spec['quest_source_root']) / 'crates/rusty-quest-media-stream-android'
        classes = output / 'classes'
        classes.mkdir()
        classpath = tools['json_jar']['path'] + os.pathsep + tools['android_jar']['path']
        run('registry-javac', [tools['javac']['path'], '-source', '8', '-target', '8',
            '-encoding', 'UTF-8', '-classpath', classpath, '-sourcepath',
            str(module / 'android/library/src/main/java'), '-d', str(classes),
            str(module / 'android/library/src/test/java/io/github/mesmerprism/rustyquest/media/MediaOwnerLifecycleAdversarialMain.java')])
        run('registry-java', [tools['java']['path'], '-cp', str(classes) + os.pathsep + classpath,
            'io.github.mesmerprism.rustyquest.media.MediaOwnerLifecycleAdversarialMain'])
        if 'rusty.quest.android.media.owner-lifecycle.v1:pass' not in (output / 'registry-java.stdout.txt').read_text():
            raise ValueError('registry result marker absent')
        check_pins(rows)
        status = 'passed'
    finally:
        receipt = {'schema': 'local.quest.retained_start_abort_host_result.v1', 'status': status,
                   'device_calls': 0, 'apk_built': False, 'source_admission': False,
                   'inputs_sha256': args.inputs_sha256, 'checks': results,
                   'source_tool_pins': rows,
                   'limitations': ['modeled provider resources; no physical terminal proof',
                       'missing remote original remains Pending',
                       'Java 8 language/bytecode on JDK17 with Android35 API classpath; no APK proof']}
        (output / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
