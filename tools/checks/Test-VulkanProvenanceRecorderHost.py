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
    parser.add_argument('--mutation-controls', action='store_true')
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
    # Fixture includes the entire actual .cpp once, so the worst-scalar tail
    # also exercises the production formatter without exporting a test API.
    commands = [('compile', [str(cxx), '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-I', str(header.parent), str(fixture), '-o', str(executable)]), ('controls', [str(executable)])]
    if cxx.name.lower() == 'cl.exe':
        commands[0] = ('compile', [str(cxx), '/nologo', '/std:c++17', '/EHsc', '/O1', '/W4', '/WX', '/I' + str(header.parent), str(fixture), '/Fe' + str(executable), '/Fo' + str(out) + os.sep])
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
        assert rows[-1]['queue_capacity'] == 1024
        assert 0 <= rows[-1]['queued_rows'] <= 1023
        assert rows[-1]['drainer_active'] in (0, 1)
        assert rows[-1]['dropped_events_are_lower_bound'] == 1 and rows[-1]['post_terminal_events_counted'] == 0
        for header_row in (r for r in rows if r['phase'] == 'header'):
            assert header_row['max_queue_rows'] == 1024
    pairs = cases['pairs']
    assert pairs[0]['phase'] == 'header' and pairs[0]['coverage_complete'] == 0
    assert [row['phase'] for row in pairs] == ['header', 'begin', 'begin', 'end', 'end', 'incomplete']
    assert pairs[1]['handle'] == 18446744073709551615 and pairs[4]['result'] == -2147483648
    assert pairs[1]['call'] == pairs[4]['call'] and pairs[2]['call'] == pairs[3]['call']
    for case, reason in [('event_limit', 'event-limit'), ('byte_limit', 'byte-limit'), ('time_limit', 'duration-limit'), ('tiny_limit', 'event-limit'), ('sink_failure', 'sink-failure'), ('sink_exception', 'sink-failure'), ('queue_limit', 'queue-limit'), ('clock_regression', 'clock-regressed'), ('row_limit', 'row-limit')]:
        assert cases[case][-1]['reason'] == reason, (case, cases[case][-1])
    validate_known_loss(cases)
    for case in ('reentrant', 'reentrant_thread'):
        assert [row['phase'] for row in cases[case]] == ['header', 'note', 'incomplete']
    overflow = cases['queue_limit']
    assert len(overflow) == 1025 and sum(r['phase'] == 'incomplete' for r in overflow) == 1
    assert [r['item'] for r in overflow if r['phase'] == 'note'] == list(range(1023))
    assert overflow[-1]['queued_rows'] == 1023 and overflow[-1]['drainer_active'] == 1
    burst = cases['blocked_burst']
    assert len(burst) == 258 and burst[-1]['reason'] == 'finite_tail'
    assert [r['sequence'] for r in burst] == list(range(1, 259))
    begins = {r['call']: (r['tid'], r['writer'], r['item']) for r in burst if r['phase'] == 'begin'}
    ends = {r['call']: (r['tid'], r['writer'], r['item']) for r in burst if r['phase'] == 'end'}
    assert len(begins) == 128 and begins == ends
    assert set(begins.values()) == {(w+1,w,i) for w in range(4) for i in range(32)}
    assert len(cases['reentrant_burst']) == 258 and cases['reentrant_burst'][-1]['reason'] == 'finite_tail'
    assert len(cases['row_limit']) == 2
    assert len(cases['many_fields']) == 3 and all(cases['many_fields'][1]['f' + str(index)] == 18446744073709551615 for index in range(20))
    assert len(cases['event_limit']) <= 4 and len(cases['tiny_limit']) <= 1
    for index in range(8):
        rows = cases['invalid_' + str(index)]
        assert len(rows) == 2 and rows[-1]['reason'] == 'invalid-metadata'
        assert all('private/path' not in str(row) and row['op'] != 'after_invalid' for row in rows)
    for index in range(6):
        rows = cases['reserved_' + str(index)]
        assert len(rows) == 2 and rows[-1]['reason'] == 'invalid-metadata'
    assert len(cases['max_tail']) == 1 and len(json.dumps(cases['max_tail'][0], separators=(',',':')).encode())+1 <= 640
    concurrent = cases['concurrent']
    assert [row['phase'] for row in concurrent] == ['header'] + ['begin'] * 8 + ['end'] * 8 + ['incomplete']
    starts = {row['call']: row['tid'] for row in concurrent if row['phase'] == 'begin'}
    ends = {row['call']: row['tid'] for row in concurrent if row['phase'] == 'end'}
    assert len(starts) == 8 and starts == ends
    mutations = []
    if args.mutation_controls:
        changes = [
            ('queue16', 'header', 'kQueueRows = 1024', 'kQueueRows = 16', '256-row queued burst lost call pairs'),
            ('allocation_when_disabled', 'source', 'if (selected) queue_.reset', 'if (true) queue_.reset', 'constructor or disabled path allocated metadata storage'),
            ('short_tail_reserve', 'source', 'kTerminalReserve = 640', 'kTerminalReserve = 384', 'worst-scalar terminal row exceeds bounded terminal reserve'),
            ('overflow_dropped_zero', 'source', 'stop_locked(ns, tid, "queue-limit", 1);', 'stop_locked(ns, tid, "queue-limit", 0);', 'known-loss-validator'),
            ('failed_current_row_uncounted', 'source', 'static_cast<uint64_t>(queued_) + 1', 'static_cast<uint64_t>(queued_)', 'known-loss-validator'),
        ]
        for name, kind, old, new, expected in changes:
            directory = out / name
            directory.mkdir()
            raw_source, raw_header = source.read_text(), header.read_text()
            assert old in (raw_source if kind == 'source' else raw_header), name
            (directory / source.name).write_text(raw_source.replace(old,new) if kind == 'source' else raw_source)
            (directory / header.name).write_text(raw_header.replace(old,new) if kind == 'header' else raw_header)
            mutant_exe = directory / executable.name
            replacements = {str(header.parent): str(directory), '/I'+str(header.parent): '/I'+str(directory),
                str(executable): str(mutant_exe), '/Fe'+str(executable): '/Fe'+str(mutant_exe),
                '/Fo'+str(out)+os.sep: '/Fo'+str(directory)+os.sep}
            command = [replacements.get(arg,arg) for arg in commands[0][1]]
            compiled = subprocess.run(command, env=env, capture_output=True, timeout=60)
            (directory / 'compile.stdout').write_bytes(compiled.stdout)
            (directory / 'compile.stderr').write_bytes(compiled.stderr)
            if compiled.returncode:
                raise RuntimeError(name+' mutation did not compile:\n'+compiled.stderr.decode(errors='replace'))
            bad = subprocess.run([str(mutant_exe)], env=env, capture_output=True, timeout=20)
            (directory / 'controls.stdout').write_bytes(bad.stdout)
            (directory / 'controls.stderr').write_bytes(bad.stderr)
            if expected == 'known-loss-validator':
                assert bad.returncode == 0, (name, bad.stderr.decode(errors='replace'))
                mutant_cases = {}
                for line in bad.stdout.decode().splitlines():
                    case, raw = line.split('\t',1)
                    mutant_cases.setdefault(case,[]).append(json.loads(raw,object_pairs_hook=unique_object))
                try:
                    validate_known_loss(mutant_cases)
                except AssertionError:
                    pass
                else:
                    raise AssertionError(name+' was not rejected by the actual loss validator')
            else:
                assert bad.returncode != 0 and expected in bad.stderr.decode(errors='replace'), (name,bad.returncode,bad.stderr)
            mutations.append(dict(name=name,status='rejected',detector=expected))
    assert pins == [dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in inputs], 'Production/fixture inputs changed during host validation'
    (out / 'rows.json').write_text(json.dumps(cases, indent=2))
    (out / 'report.json').write_text(json.dumps(dict(inputs=pins, cases=len(cases), rows=sum(map(len, cases.values())), compiler=str(cxx), mutations=mutations, no_device_or_gpu=True), indent=2))
    print(f'Actual C++ recorder controls passed: {len(cases)} cases, {len(mutations)} rejected mutations; heap1024 queue, 256-row blocked-sink burst, truthful lower-bound loss, allocation failure/off, paired identities, limits/failure closure and concurrent/reentrant writers. No Android/Vulkan/GPU proof.')


def validate_known_loss(cases):
    for case in ('sink_failure', 'sink_exception'):
        assert len(cases[case]) == 2 and cases[case][-1]['dropped_events'] == 1, (case, cases[case][-1])
    lost = cases['queued_sink_failure']
    assert len(lost) == 2 and lost[-1]['reason'] == 'sink-failure' and lost[-1]['dropped_events'] == 8, lost
    assert lost[-1]['queued_rows'] == 0 and lost[-1]['drainer_active'] == 1
    assert all(r['op'] not in ('discarded', 'after_tail') for r in lost)
    assert cases['queue_limit'][-1]['dropped_events'] == 1, cases['queue_limit'][-1]


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, 'Duplicate JSON key'
        result[key] = value
    return result


if __name__ == '__main__':
    main()
