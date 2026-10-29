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
    parser.add_argument('--incarnation-source', help='Captured baseline incarnation source for a RED comparison')
    parser.add_argument('--android-read-source', help='Pinned official Android14 Files.java; model only its readAllBytes primitive on the host')
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
    if args.incarnation_source:
        production[1] = Path(args.incarnation_source).resolve(strict=True)
    sources = list(production)
    android_read = None
    if args.android_read_source:
        reference = Path(args.android_read_source).resolve(strict=True)
        data = reference.read_bytes()
        if hashlib.sha256(data).hexdigest() != '2c75989395d4a7f096efa01067b939c9cc4eaf05f81e9bf651c5a74d10a91a57':
            raise ValueError('Exact reviewed Android14 libcore Files.java required')
        text = data.decode('utf-8')

        def method(signature):
            start = text.index(signature)
            opening = text.index('{', start)
            depth = 1
            end = opening + 1
            while depth:
                depth += (text[end] == '{') - (text[end] == '}')
                end += 1
            return text[start:end]

        generated = out / 'android-read-leaf'
        generated.mkdir()
        leaf = generated / 'AndroidReadAllBytesHost.java'
        leaf.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;\n'
                        'import java.io.*;import java.nio.file.*;import java.nio.channels.*;import java.util.Arrays;\n'
                        '/** Source-extracted Android14 read primitive, not an installed ART claim. */\n'
                        'final class AndroidReadAllBytesHost {\n'
                        'private static final int BUFFER_SIZE=8192,MAX_BUFFER_SIZE=Integer.MAX_VALUE-8;\n'
                        + method('private static byte[] read(InputStream source, int initialSize)') + '\n'
                        + method('public static byte[] readAllBytes(Path path)') + '\n}\n', encoding='utf-8')
        body = production[1].read_text(encoding='utf-8')
        marker = 'return Files.readAllBytes(path);'
        count = body.count(marker)
        if count > 1:
            raise ValueError('Ambiguous companion read leaf')
        if count:
            companion = generated / production[1].name
            companion.write_text(body.replace(marker, 'return AndroidReadAllBytesHost.readAllBytes(path);'), encoding='utf-8')
            sources[1] = companion
        sources.append(leaf)
        android_read = dict(reference=str(reference), primitive_substituted=bool(count),
                            scope='Exact production fence/loader guards; only Android14 readAllBytes leaf substituted; installed ART provenance unproven')
    sources += [host / 'ProcessFenceInterruptHostTest.java']
    sources += sorted((host / 'android-system-host/android/system').glob('*.java'))

    def ref(path):
        path = Path(path)
        data = path.read_bytes()
        return dict(path=str(path.resolve()), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())

    pin_paths = list(dict.fromkeys(production + sources + [Path(__file__)]
                                  + ([Path(args.android_read_source)] if args.android_read_source else [])))
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
    result['android_read_leaf'] = android_read
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(ref(out / 'RESULT.json')))
    return code if code else (0 if unchanged else 1)


if __name__ == '__main__':
    sys.exit(main())
