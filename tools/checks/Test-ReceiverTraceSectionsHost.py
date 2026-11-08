#!/usr/bin/env python3
"""Actual receiver method bodies with modeled Android Trace/codec/native leaves.

Host controls establish section placement and unchanged Java semantics, not
Android trace enablement, device timing, decoder output or network performance.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
import subprocess
import sys


def block(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 0
    for end in range(brace, len(text)):
        if text[end] == '{': depth += 1
        elif text[end] == '}':
            depth -= 1
            if depth == 0: return text[start:end + 1]
    raise ValueError('Unclosed production item: ' + signature)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--android-jar', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = Path(args.output).resolve()
    output.relative_to(root / 'target')
    output.mkdir(parents=True, exist_ok=False)
    source_root = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java'
    media = source_root / 'io/github/mesmerprism/rustyquest/media'
    receiver = media / 'PackedStereoMediaReceiver.java'
    fixtures = Path(__file__).with_name('fixtures')
    selected = [Path(__file__), receiver, fixtures / 'receiver_trace_android_Trace.java.in',
                fixtures / 'ReceiverSocketTraceHost.java.in', fixtures / 'ReceiverMethodTraceHost.java.in']
    def ref(path):
        raw = path.read_bytes()
        return dict(path=str(path), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
    source = receiver.read_text()
    generated = output / 'ReceiverSocketTraceHost.java'
    generated.write_text((fixtures / 'ReceiverSocketTraceHost.java.in').read_text()
                         .replace('PRODUCTION_COUNTING_INPUT', block(source, 'private final class CountingInputStream')))
    trace = output / 'android/os/Trace.java'
    trace.parent.mkdir(parents=True)
    trace.write_text((fixtures / 'receiver_trace_android_Trace.java.in').read_text())
    helper = media / 'ReceiverTraceSections.java'
    host_sources = [trace, generated]
    template = (fixtures / 'ReceiverMethodTraceHost.java.in').read_text()
    methods = {
        'PRODUCTION_CODEC_FLAGS': 'private static int codecInputFlags(',
        'PRODUCTION_QUEUE': 'private void queuePacket(',
        'PRODUCTION_INPUT_PRESSURE': 'private void logInputPressure(',
        'PRODUCTION_CAPACITY': 'private void awaitIdentityCapacity(',
        'PRODUCTION_DRAIN': 'private void drain(',
        'PRODUCTION_ACQUIRE': 'private void pollSurfaceAcquisition(',
    }
    for marker, signature in methods.items(): template = template.replace(marker, block(source, signature))
    release = 'private static void releaseOutput('
    template = template.replace('PRODUCTION_RELEASE', block(source, release) if release in source else '')
    first = source.index('            transportStage = "PACKET_READ";')
    last = source.index('            transportStage = "DECODE";', first)
    template = template.replace('PRODUCTION_READ_FRAGMENT', source[first:last])
    method_host = output / 'ReceiverMethodTraceHost.java'; method_host.write_text(template)
    clock = output / 'android/os/SystemClock.java'
    clock.write_text('package android.os;public final class SystemClock{'
                     'public static long elapsedRealtime(){return System.nanoTime()/1000000L;}'
                     'public static long elapsedRealtimeNanos(){return System.nanoTime();}}')
    log = output / 'android/util/Log.java'
    log.parent.mkdir(parents=True)
    log.write_text('package android.util;public final class Log{'
                   'public static int i(String tag,String text){return 0;}}')
    host_sources.extend([method_host, clock, log])
    if helper.exists(): host_sources.append(helper); selected.append(helper)
    checks = []
    def run(name, command):
        with (output / (name + '.stdout')).open('xb') as stdout, (output / (name + '.stderr')).open('xb') as stderr:
            result = subprocess.run(command, cwd=root, stdout=stdout, stderr=stderr, timeout=60)
        checks.append(dict(name=name, argv=command, exit=result.returncode,
                           stdout=ref(output / (name + '.stdout')), stderr=ref(output / (name + '.stderr'))))
        return result.returncode
    classes = output / 'host-classes'
    classes.mkdir()
    status = run('host-compile', ['javac', '--release', '8', '-Xlint:all', '-Werror', '-encoding', 'UTF-8',
                                '-d', str(classes), *map(str, host_sources)])
    if status == 0:
        status = run('socket-controls', ['java', '-cp', str(classes),
                                        'io.github.mesmerprism.rustyquest.media.ReceiverSocketTraceHost'])
    if status == 0:
        status = run('method-controls', ['java', '-cp', str(classes),
                                        'io.github.mesmerprism.rustyquest.media.ReceiverMethodTraceHost'])
    if status == 0:
        sources = sorted(source_root.rglob('*.java'))
        argfile = output / 'android-sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources) + '\n')
        classes = output / 'android-classes'; classes.mkdir()
        status = run('android-api-compile', ['javac', '--release', '8', '-encoding', 'UTF-8',
                                            '-cp', args.android_jar, '-d', str(classes), '@' + str(argfile)])
        selected.extend(sources)
    result = dict(passed=status == 0, checks=checks, source_pins=[ref(p) for p in selected],
                  tools=[ref(Path(shutil.which(name))) for name in ('javac', 'java')],
                  android_api=ref(Path(args.android_jar)), limits=__doc__)
    (output / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(passed=result['passed'], result=ref(output / 'RESULT.json'))))
    return status


if __name__ == '__main__': sys.exit(main())
