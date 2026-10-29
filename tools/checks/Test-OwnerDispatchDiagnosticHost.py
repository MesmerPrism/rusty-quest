#!/usr/bin/env python3
"""Real loopback endpoint and modeled Android Log. No Rust, APK, device or cleanup qualification."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def pin(path):
    path = Path(path).resolve()
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = Path(args.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    media = root / 'crates/rusty-quest-media-stream-android/android/library/src'
    endpoint = media / 'main/java/io/github/mesmerprism/rustyquest/media/OwnerDispatchTcpEndpoint.java'
    test = media / 'test/java/io/github/mesmerprism/rustyquest/media/OwnerDispatchDiagnosticHost.java'
    javac = Path(args.java_home) / 'bin/javac.exe'
    java = Path(args.java_home) / 'bin/java.exe'
    before = [pin(p) for p in [endpoint, test, __file__, javac, java]]
    log = out / 'Log.java'
    log.write_text('''package android.util;
public final class Log {
 public static volatile boolean fail;
 private static final java.util.List<String> lines=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static int w(String tag,String message){if(fail)throw new IllegalStateException("PRIVATE_LOG_FAILURE");lines.add(tag+": "+message);return 0;}
 public static java.util.List<String> entries(){return new java.util.ArrayList<>(lines);}
 public static void clear(){lines.clear();}
}
''', encoding='utf-8')
    classes = out / 'classes'
    classes.mkdir()
    checks = []
    success = False
    try:
        commands = [
            ('javac', [str(javac), '--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', str(classes), str(log), str(endpoint), str(test)]),
            ('controls', [str(java), '-cp', str(classes), 'io.github.mesmerprism.rustyquest.media.OwnerDispatchDiagnosticHost', hashlib.sha256(b'SENSITIVE_SIGNED_FRAME').hexdigest()]),
        ]
        for name, command in commands:
            result = subprocess.run(command, capture_output=True, timeout=30)
            (out / (name+'.stdout')).write_bytes(result.stdout)
            (out / (name+'.stderr')).write_bytes(result.stderr)
            checks.append({'name': name, 'argv': command, 'exit': result.returncode,
                           'stdout': pin(out/(name+'.stdout')), 'stderr': pin(out/(name+'.stderr'))})
            if result.returncode:
                print(result.stderr.decode(errors='replace'), file=sys.stderr)
                return result.returncode
        success = True
        print((out/'controls.stdout').read_text())
        return 0
    finally:
        after = [pin(p['path']) for p in before]
        (out/'RESULT.json').write_text(json.dumps({'passed': success and before == after, 'source_and_tools_before': before,
            'source_and_tools_after': after, 'checks': checks, 'modeled_log': pin(log), 'limits': __doc__}, indent=2))
        if before != after:
            raise AssertionError('Source or tool changed during the focused check')


if __name__ == '__main__':
    sys.exit(main())
