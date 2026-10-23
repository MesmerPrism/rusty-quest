#!/usr/bin/env python3
"""Direct-source native diagnostic controls plus Android JNI module typecheck; no APK/device effects."""
import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root, output = Path(args.root).resolve(), Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    native = root / 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/operation_diagnostics.rs'
    core = root / 'crates/rusty-quest-media-stream/src/diagnostics.rs'
    paths = [native, core, core.with_name('diagnostics_tests.rs'), Path(__file__).resolve()]
    before = {str(path): path.read_bytes() for path in paths}
    (output / 'core/src').mkdir(parents=True)
    (output / 'src').mkdir()
    (output / 'Cargo.toml').write_text('''[package]
name="native-operation-diagnostic-controls"
version="0.0.0"
edition="2021"
[workspace]
members=["core"]
[dependencies]
rusty-quest-media-stream={path="core"}
serde_json="1"
sha2="0.10"
[target.'cfg(target_os = "android")'.dependencies]
jni="0.21"
''')
    (output / 'core/Cargo.toml').write_text('''[package]
name="rusty-quest-media-stream"
version="0.0.0"
edition="2021"
[dependencies]
serde={version="1",features=["derive"]}
serde_json="1"
sha2="0.10"
''')
    (output / 'src/lib.rs').write_text('#[path=' + json.dumps(native.as_posix()) + ']\nmod operation_diagnostics;\n')
    (output / 'core/src/lib.rs').write_text('#[path=' + json.dumps(core.as_posix()) + ']\nmod diagnostics;\npub use diagnostics::*;\n')
    checks = []
    for name, argv in [('host', ['cargo', 'test', '--offline', '--workspace']),
                       ('android-jni', ['cargo', 'check', '--offline', '--target', 'aarch64-linux-android'])]:
        environment = dict(os.environ, RQ_DIAGNOSTIC_FIXTURE_DIR=str(output / 'production-collection-fixture'))
        result = subprocess.run(argv, cwd=output, capture_output=True, timeout=180, env=environment)
        (output / (name + '.stdout')).write_bytes(result.stdout)
        (output / (name + '.stderr')).write_bytes(result.stderr)
        checks.append({'name': name, 'exit_code': result.returncode})
        if result.returncode:
            print(result.stderr.decode(errors='replace')[-12000:])
            break
    unchanged = all(path.read_bytes() == before[str(path)] for path in paths)
    result = {'schema': 'rusty.quest.native_operation_diagnostic_controls.v1',
              'status': 'passed' if unchanged and len(checks) == 2 and all(c['exit_code'] == 0 for c in checks) else 'failed',
              'checks': checks, 'source_unchanged': unchanged,
              'source_pins': [{'path': name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in before.items()],
              'device_calls': 0, 'apk_built': False,
              'limits': ['Production diagnostic modules compile by direct file reference; dependency-only core facade excludes unrelated Manifold code.',
                         'Android typecheck covers this JNI diagnostic module, not the entire native runtime or actual JVM attachment/file sink execution.']}
    (output / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result['checks']))
    raise SystemExit(0 if result['status'] == 'passed' else 1)


if __name__ == '__main__':
    main()
