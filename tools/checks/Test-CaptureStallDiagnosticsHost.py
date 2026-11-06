#!/usr/bin/env python3
"""Finite capture diagnostics, real JVM monitor contention and extracted production wiring.

Android callback data/clock and native pool are explicit modeled leaves. Full shared
library API compilation is separate. No APK, device, HAL, timing or media acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def block(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth = 0
    for end in range(opening, len(text)):
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise AssertionError('Incomplete source block: ' + signature)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--android-jar', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = Path(args.output).resolve()
    output.relative_to(root / 'target')
    output.mkdir(parents=True, exist_ok=False)
    library = root / 'crates/rusty-quest-media-stream-android/android/library/src'
    main_root = library / 'main/java'
    package = 'io/github/mesmerprism/rustyquest/media'
    main_sources = main_root / package
    test = library / 'test/java' / package / 'CaptureStallDiagnosticsMain.java'
    template = Path(__file__).with_name('fixtures') / 'capture_stall_wiring.java.in'
    owner = main_sources / 'PackedStereoCaptureOwner.java'
    compositor = main_sources / 'PackedStereoGlCompositor.java'
    sources = [main_sources / 'CaptureStallDiagnostics.java', main_sources / 'CaptureFrameTrace.java',
               main_sources / 'PackedStereoPoolExecutor.java', test]
    def ref(path):
        data = path.read_bytes()
        return dict(path=str(path), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    pins = [ref(p) for p in [Path(__file__), template, owner, compositor, *sources]]
    checks = []
    def run(name, command):
        with (output / (name + '.stdout')).open('xb') as stdout, (output / (name + '.stderr')).open('xb') as stderr:
            result = subprocess.run(command, cwd=root, stdout=stdout, stderr=stderr, timeout=90)
        checks.append(dict(name=name, argv=command, exit=result.returncode,
                           stdout=ref(output / (name + '.stdout')), stderr=ref(output / (name + '.stderr'))))
        return result.returncode
    production = owner.read_text()
    callback = block(production, 'new CameraCaptureSession.CaptureCallback()')
    offer = block(production, 'public void offerEncoderFrame(')
    poll = block(compositor.read_text(), 'private void pollCapturePool(')
    snapshot = block(production, 'JSONObject captureDiagnosticSnapshot(')
    arm = block(production, 'public void armDiagnosticTrace(')
    stop = block(production, 'public void requestStop(')
    generated = output / 'CaptureStallWiringHost.java'
    generated.write_text(template.read_text().replace('PRODUCTION_CAPTURE_CALLBACK', callback)
                         .replace('PRODUCTION_ENCODER_OFFER', offer).replace('PRODUCTION_POOL_POLL', poll)
                         .replace('PRODUCTION_SNAPSHOT', snapshot).replace('PRODUCTION_ARM', arm)
                         .replace('PRODUCTION_STOP', stop))
    clock = output / 'android/os/SystemClock.java'
    clock.parent.mkdir(parents=True)
    clock.write_text('package android.os;public final class SystemClock{public static volatile long now=1000000000L;'
                     'public static long elapsedRealtimeNanos(){return now;}}')
    classes = output / 'classes'
    classes.mkdir()
    status = run('host-compile', ['javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
                                '-cp', args.json_jar, '-d', str(classes), *map(str, sources), str(generated), str(clock)])
    if status == 0:
        for entry in ['CaptureStallDiagnosticsMain', 'CaptureStallWiringHost']:
            extra = [str(output / 'populated-probe.json')] if entry == 'CaptureStallDiagnosticsMain' else []
            status = run(entry, ['java', '-cp', str(classes) + os.pathsep + args.json_jar,
                                 'io.github.mesmerprism.rustyquest.media.' + entry, *extra])
            if status:
                break
    if status == 0:
        android_classes = output / 'android-classes'
        android_classes.mkdir()
        argfile = output / 'android-sources.args'
        android_sources = sorted(main_root.rglob('*.java'))
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in android_sources) + '\n')
        status = run('android-api-compile', ['javac', '--release', '8', '-encoding', 'UTF-8',
                                           '-cp', args.android_jar, '-d', str(android_classes), '@' + str(argfile)])
        pins.extend(ref(p) for p in android_sources)
    result = dict(passed=status == 0, checks=checks, source_pins=pins,
                  dependencies=[ref(Path(args.json_jar)), ref(Path(args.android_jar))], limits=__doc__)
    receipt = output / 'RESULT.json'
    receipt.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(passed=result['passed'], receipt=ref(receipt))))
    return status


if __name__ == '__main__':
    sys.exit(main())
