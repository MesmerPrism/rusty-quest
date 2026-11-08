#!/usr/bin/env python3
"""Actual pump/LAN/helper with deterministic stream/socket/Android leaves; no network or APK."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--java-home', type=Path, required=True)
    p.add_argument('--mutation-controls', action='store_true')
    a = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = a.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    media = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
    fixtures = Path(__file__).resolve().with_name('fixtures') / 'sender_phase_trace'
    paths = [media / name for name in ('BoundedPacketPump.java','PackedStereoLanTransport.java','SenderTraceSections.java')]
    paths += sorted(fixtures.glob('*.java')) + [Path(__file__).resolve()]
    pins = {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest() for f in paths}
    # Whole production LAN class; replace only the external Socket leaf import.
    # The same-package fixture Socket resolves every unchanged production use.
    lan = media / 'PackedStereoLanTransport.java'
    text = lan.read_text()
    assert text.count('import java.net.Socket;') == 1
    generated = out / lan.name
    generated.write_text(text.replace('import java.net.Socket;', ''), encoding='utf-8')
    sources = [media / 'BoundedPacketPump.java', media / 'SenderTraceSections.java', generated]
    sources += sorted(fixtures.glob('*.java'))
    classes = out / 'classes'
    compile_command = [str(a.java_home / 'bin/javac.exe'), '--release', '17', '-Xlint:all',
        '-Werror', '-encoding', 'UTF-8', '-d', str(classes), *map(str,sources)]
    subprocess.run(compile_command, check=True)
    result = subprocess.run([str(a.java_home / 'bin/java.exe'), '-ea', '-cp', str(classes),
        'io.github.mesmerprism.rustyquest.media.SenderPhaseTraceHost'], text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise AssertionError(result.stdout + result.stderr)
    print(result.stdout, end='')
    mutations=[]
    if a.mutation_controls:
        cases=[
            ('pump-write-finally', 'BoundedPacketPump.java',
             'finally { SenderTraceSections.end(writeBegan); }', 'finally {}', 'open span after terminal outcome'),
            ('lan-flush-finally', 'PackedStereoLanTransport.java',
             'finally { SenderTraceSections.end(flushBegan); }', 'finally {}', 'open span after terminal outcome'),
            ('constructor-gate', 'BoundedPacketPump.java',
             'DEFAULT_MAX_QUEUED_BYTES, threadName, senderTraceDebuggable);',
             'DEFAULT_MAX_QUEUED_BYTES, threadName, false);', 'operation span pump_write'),
        ]
        for name, filename, old, new, failure in cases:
            directory=out/name;directory.mkdir()
            original=(media/filename).read_text()
            assert original.count(old)==1, name
            changed=directory/filename
            changed.write_text(original.replace(old,new).replace('import java.net.Socket;',''),encoding='utf-8')
            mutated=[changed if item.name==filename else item for item in sources]
            mutant_classes=directory/'classes'
            subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','17','-Xlint:all','-Werror',
                '-encoding','UTF-8','-d',str(mutant_classes),*map(str,mutated)],check=True)
            bad=subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(mutant_classes),
                'io.github.mesmerprism.rustyquest.media.SenderPhaseTraceHost'],text=True,capture_output=True,timeout=30)
            assert bad.returncode!=0 and failure in bad.stderr,(name,bad.returncode,bad.stdout,bad.stderr)
            mutations.append(dict(name=name,status='rejected',reason=failure))
        print(f'{len(mutations)} actual-source mutation controls rejected.')
    if pins != {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest() for f in paths}:
        raise AssertionError('Production/fixture inputs changed during check')
    (out / 'RESULT.json').write_text(json.dumps(dict(status='pass', inputs=pins, command=compile_command,
        stdout=result.stdout, mutations=mutations, scope=__doc__, transformed_leaf='LAN import java.net.Socket removed; all method bodies unchanged'), indent=2)+'\n')


if __name__ == '__main__':
    main()
