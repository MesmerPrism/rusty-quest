"""Compile and execute the production neutral metadata recorder; no Android/Vulkan/GPU."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cxx', type=Path, default=shutil.which('clang++') or shutil.which('g++'))
    parser.add_argument('--vcvars', type=Path)
    parser.add_argument('--windows-sdk', type=Path, help='Existing Windows Kits root when vcvars omits its SDK include/library paths')
    args = parser.parse_args()
    if args.cxx is None:
        parser.error('An existing host C++ compiler is required via --cxx or PATH')
    root, out = args.root.resolve(), args.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    source = root / 'apps/spatial-camera-panel-android/app/src/main/cpp/vulkan_provenance/provenance_recorder.cpp'
    header = source.with_suffix('.hpp')
    fixture = root / 'tools/checks/fixtures/vulkan_provenance_recorder_host.cpp'
    cxx = args.cxx.resolve(strict=True)
    env = os.environ.copy()
    if args.vcvars:
        result = subprocess.run(f'cmd.exe /d /c call "{args.vcvars.resolve(strict=True)}" >nul && set', capture_output=True)
        if result.returncode:
            raise RuntimeError('VC environment setup failed:\n' + result.stderr.decode(errors='replace'))
        for line in result.stdout.decode(errors='replace').splitlines():
            key, separator, value = line.partition('=')
            if separator and key:
                env[key] = value
    if args.windows_sdk:
        sdk = args.windows_sdk.resolve(strict=True)
        versions = [path for path in (sdk / 'Include').iterdir() if (path / 'ucrt/stddef.h').is_file()]
        version = max(versions, key=lambda path: tuple(map(int, path.name.split('.'))))
        env['INCLUDE'] = env.get('INCLUDE', '') + ';' + ';'.join(str(version / kind) for kind in ('ucrt', 'shared', 'um', 'winrt'))
        env['LIB'] = env.get('LIB', '') + ';' + ';'.join(str(sdk / 'Lib' / version.name / kind / 'x64') for kind in ('ucrt', 'um'))
    inputs = [source, header, fixture, Path(__file__).resolve()]
    pins = [dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in inputs]
    (out / 'inputs.json').write_text(json.dumps(dict(sources=pins, compiler=dict(path=str(cxx), sha256=hashlib.sha256(cxx.read_bytes()).hexdigest())), indent=2))
    executable = out / ('recorder-host.exe' if os.name == 'nt' else 'recorder-host')
    commands = [('compile', [str(cxx), '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-I', str(header.parent), str(source), str(fixture), '-o', str(executable)]), ('controls', [str(executable)])]
    if cxx.name.lower() == 'cl.exe':
        commands[0] = ('compile', [str(cxx), '/nologo', '/std:c++17', '/EHsc', '/O1', '/W4', '/WX', '/I' + str(header.parent), str(source), str(fixture), '/Fe' + str(executable), '/Fo' + str(out) + os.sep])
    elif os.name != 'nt':
        commands[0][1].insert(1, '-pthread')
    output = ''
    for name, command in commands:
        try:
            child = subprocess.run(command, env=env, capture_output=True, timeout=60 if name == 'compile' else 20)
        except subprocess.TimeoutExpired as failure:
            (out / (name + '.stdout')).write_bytes(failure.stdout or b'')
            (out / (name + '.stderr')).write_bytes(failure.stderr or b'')
            (out / (name + '.process.json')).write_text(json.dumps(dict(argv=command, exit_code=None, timed_out=True), indent=2))
            raise
        (out / (name + '.stdout')).write_bytes(child.stdout)
        (out / (name + '.stderr')).write_bytes(child.stderr)
        (out / (name + '.process.json')).write_text(json.dumps(dict(argv=command, exit_code=child.returncode), indent=2))
        if child.returncode:
            raise RuntimeError(name + ' failed:\n' + child.stdout.decode(errors='replace') + child.stderr.decode(errors='replace'))
        if name == 'controls':
            output = child.stdout.decode()
    cases = {}
    for line in output.splitlines():
        case, raw = line.split('\t', 1)
        assert len(raw.encode()) + 1 <= 2048, 'Oversized event row'
        row = json.loads(raw, object_pairs_hook=unique_object)
        cases.setdefault(case, []).append(row)
        assert set(('version', 'sequence', 'ns', 'tid', 'pid', 'op', 'phase', 'call', 'result')) <= row.keys()
        assert row['version'] == 1
        assert all(isinstance(value, int) and not isinstance(value, bool) for key, value in row.items() if key not in ('op', 'phase', 'reason'))
    for case, rows in cases.items():
        sequence = [row['sequence'] for row in rows]
        assert sequence == sorted(set(sequence)), (case, 'Duplicate or unordered writer sequence')
        assert rows[-1]['phase'] == 'incomplete' and rows[-1]['coverage_complete'] == 0 and rows[-1]['window_ended'] == 1, (case, 'Finite trace falsely implies process coverage')
    pairs = cases['pairs']
    assert pairs[0]['phase'] == 'header' and pairs[0]['coverage_complete'] == 0
    assert [row['phase'] for row in pairs] == ['header', 'begin', 'begin', 'end', 'end', 'incomplete']
    assert pairs[1]['handle'] == 18446744073709551615 and pairs[4]['result'] == -2147483648
    assert pairs[1]['call'] == pairs[4]['call'] and pairs[2]['call'] == pairs[3]['call']
    for case, reason in [('event_limit', 'event-limit'), ('byte_limit', 'byte-limit'), ('time_limit', 'duration-limit'), ('tiny_limit', 'event-limit'), ('sink_failure', 'sink-failure'), ('sink_exception', 'sink-failure'), ('queue_limit', 'queue-limit'), ('clock_regression', 'clock-regressed'), ('row_limit', 'row-limit')]:
        assert cases[case][-1]['reason'] == reason, (case, cases[case][-1])
    for case in ('reentrant', 'reentrant_thread'):
        assert [row['phase'] for row in cases[case]] == ['header', 'note', 'incomplete']
    assert len(cases['queue_limit']) <= 18
    assert len(cases['row_limit']) == 2
    assert len(cases['many_fields']) == 3 and all(cases['many_fields'][1]['f' + str(index)] == 18446744073709551615 for index in range(20))
    assert len(cases['event_limit']) <= 4 and len(cases['tiny_limit']) <= 1
    for index in range(8):
        rows = cases['invalid_' + str(index)]
        assert len(rows) == 2 and rows[-1]['reason'] == 'invalid-metadata'
        assert all('private/path' not in str(row) and row['op'] != 'after_invalid' for row in rows)
    concurrent = cases['concurrent']
    assert [row['phase'] for row in concurrent] == ['header'] + ['begin'] * 8 + ['end'] * 8 + ['incomplete']
    starts = {row['call']: row['tid'] for row in concurrent if row['phase'] == 'begin'}
    ends = {row['call']: row['tid'] for row in concurrent if row['phase'] == 'end'}
    assert len(starts) == 8 and starts == ends
    assert pins == [dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in inputs], 'Production/fixture inputs changed during host validation'
    (out / 'rows.json').write_text(json.dumps(cases, indent=2))
    (out / 'report.json').write_text(json.dumps(dict(inputs=pins, cases=len(cases), rows=sum(map(len, cases.values())), compiler=str(cxx), no_device_or_gpu=True), indent=2))
    print(f'Actual C++ recorder controls passed: {len(cases)} cases; bounded metadata, finite tails, paired unsigned identities, limits/failure closure and concurrent/reentrant writers. No Android/Vulkan/GPU proof.')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, 'Duplicate JSON key'
        result[key] = value
    return result


if __name__ == '__main__':
    main()
