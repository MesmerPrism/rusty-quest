"""Current v2 whole Java fence with real host locks/I/O; Android Os syscall leaves modeled."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--android-jar')
    parser.add_argument('--fence-source', help='Captured baseline fence source for a RED comparison; default is current production')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = Path(args.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    main = root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    host = root / 'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
    production = [main / (name + '.java') for name in
                  ('EmbeddedDuplexProcessFence', 'EmbeddedDuplexProcessIncarnation', 'EmbeddedDuplexCanonicalJson')]
    if args.fence_source:
        production[0] = Path(args.fence_source).resolve(strict=True)
    sources = production + [host / 'ProcessFenceInterruptHostTest.java']
    sources += sorted((host / 'android-system-host/android/system').glob('*.java'))

    def ref(path):
        path = Path(path)
        data = path.read_bytes()
        return dict(path=str(path.resolve()), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())

    pin_paths = sources + [Path(__file__)]
    pins = [ref(path) for path in pin_paths]
    rows = []

    def run(name, argv):
        with (out / (name + '.stdout')).open('xb') as stdout, (out / (name + '.stderr')).open('xb') as stderr:
            try:
                code = subprocess.run(argv, cwd=root, stdout=stdout, stderr=stderr, timeout=30).returncode
            except subprocess.TimeoutExpired:
                stderr.write(b'Focused subprocess exceeded 30-second bound\n')
                code = 124
        rows.append(dict(name=name, argv=argv, exit=code,
                         stdout=ref(out / (name + '.stdout')), stderr=ref(out / (name + '.stderr'))))
        return code

    classes = out / 'classes'
    classes.mkdir()
    code = run('compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.json_jar,
                           '-d', str(classes), *map(str, sources)])
    if code == 0:
        code = run('controls', ['java', '-cp', str(classes) + os.pathsep + args.json_jar,
                               'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ProcessFenceInterruptHostTest',
                               str(out / 'data')])
    api_compiled = False
    if code == 0 and args.android_jar:
        android = out / 'android-api-classes'
        android.mkdir()
        code = run('android-api', ['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.android_jar,
                                  '-d', str(android), *map(str, production)])
        api_compiled = code == 0
    unchanged = pins == [ref(path) for path in pin_paths]
    result = dict(passed=code == 0 and unchanged, source_unchanged=unchanged, checks=rows, source_refs=pins,
                  dependency_refs=[ref(args.json_jar)] + ([ref(args.android_jar)] if args.android_jar else []),
                  android_api_compiled=api_compiled, device_calls=0, limits=__doc__)
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(ref(out / 'RESULT.json')))
    return code if code else (0 if unchanged else 1)


if __name__ == '__main__':
    sys.exit(main())
