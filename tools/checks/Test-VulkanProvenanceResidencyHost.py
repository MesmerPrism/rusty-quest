"""Real Linux dlopen/dlclose over unchanged production Android-admission layer/core.

Run with an existing Linux toolchain (for example an already-installed WSL distro).
Android log/property/open leaves and downstream Vulkan functions are modeled.
No Android loader, driver, GPU, APK or device execution is claimed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess


def pin(path, root=None):
    return dict(path=str(path.relative_to(root)) if root else str(path), bytes=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def flags_1(path):
    data = path.read_bytes()
    assert data[:6] == b'\x7fELF\x02\x01', 'Expected actual little-endian ELF64 DSO'
    phoff = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    values = []
    for index in range(count):
        header = phoff + index * size
        if struct.unpack_from('<I', data, header)[0] != 2:
            continue
        offset = struct.unpack_from('<Q', data, header + 8)[0]
        length = struct.unpack_from('<Q', data, header + 32)[0]
        for item in range(offset, offset + length, 16):
            tag, value = struct.unpack_from('<qQ', data, item)
            if tag == 0:
                break
            if tag == 0x6ffffffb:
                values.append(value)
    return values


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, 'Duplicate JSON event key'
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vulkan-include', type=Path, required=True,
                        help='Existing NDK sysroot/usr/include; used after Linux system headers')
    parser.add_argument('--cxx', default='g++')
    parser.add_argument('--readelf', default='readelf')
    args = parser.parse_args()
    if os.name != 'posix':
        parser.error('This control requires a real Linux ELF loader; run in an existing Linux/WSL environment')
    root, out = args.root.resolve(), args.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    source = root / 'apps/spatial-camera-panel-android/app/src/main/cpp/vulkan_provenance'
    fixture = root / 'tools/checks/fixtures/vulkan_provenance_residency'
    cxx = Path(shutil.which(args.cxx) or args.cxx).resolve(strict=True)
    readelf = Path(shutil.which(args.readelf) or args.readelf).resolve(strict=True)
    inputs = [source / 'vulkan_provenance_layer.cpp', source / 'provenance_recorder.cpp',
              source / 'provenance_recorder.hpp', Path(__file__).resolve(), *sorted(fixture.rglob('*.*'))]
    pins = [pin(path, root) for path in inputs]
    header_pins = [pin(args.vulkan_include / 'vulkan' / name) for name in ('vk_layer.h', 'vulkan_core.h', 'vk_platform.h')]
    (out / 'inputs.json').write_text(json.dumps(dict(sources=pins, headers=header_pins,
                                                   tools=[pin(cxx), pin(readelf)]), indent=2))

    def run(name, command, timeout=60):
        try:
            result = subprocess.run(list(map(str, command)), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            (out / (name + '.stdout')).write_bytes(failure.stdout or b'')
            (out / (name + '.stderr')).write_bytes(failure.stderr or b'')
            (out / (name + '.process.json')).write_text(json.dumps(dict(argv=list(map(str, command)), timed_out=True), indent=2))
            raise
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        (out / (name + '.process.json')).write_text(json.dumps(dict(argv=list(map(str, command)), exit_code=result.returncode), indent=2))
        assert result.returncode == 0, name + ': ' + result.stderr.decode(errors='replace')
        return result.stdout.decode()

    run('compiler-version', [cxx, '--version'])
    run('readelf-version', [readelf, '--version'])
    # Host-only: GNU-unique C++ symbols can implicitly pin a glibc DSO and make
    # the unloadable negative control a false positive. Production NDK source
    # and compile settings are untouched; actual symbol tables are checked below.
    common = [cxx, '-std=c++20', '-O1', '-Wall', '-Wextra', '-Werror', '-pthread',
              '-fno-gnu-unique', '-idirafter', args.vulkan_include.resolve()]
    driver = out / 'driver'
    run('compile-driver', [*common, fixture / 'driver.cpp', '-ldl', '-o', driver])
    artifacts = {}
    for name, resident in [('unloadable', False), ('nodelete', True)]:
        library = out / ('libresidency_' + name + '.so')
        command = [*common, '-fPIC', '-shared', '-D__ANDROID__', '-DRQ_VULKAN_PROVENANCE_APP_ID="runtime.host"',
                   '-I', fixture, '-I', source, source / 'vulkan_provenance_layer.cpp',
                   source / 'provenance_recorder.cpp', fixture / 'host_platform.cpp',
                   '-Wl,--wrap=open', '-Wl,-Bsymbolic-functions', '-ldl', '-o', library]
        if resident:
            command.insert(-2, '-Wl,-z,nodelete')
        run('compile-' + name, command)
        dynamic = run('dynamic-' + name, [readelf, '-dW', library])
        symbols = run('symbols-' + name, [readelf, '-Ws', library])
        observed = flags_1(library)
        assert any(value & 8 for value in observed) == resident, 'Actual DF_1_NODELETE disagrees with linked variant'
        assert ('NODELETE' in dynamic) == resident
        assert 'UNIQUE' not in symbols, 'Host GNU-unique symbols would invalidate unloadable negative control'
        artifacts[name] = dict(pin=pin(library), DT_FLAGS_1=observed, DF_1_NODELETE=resident)

    results = {}
    for name, library_name, selected, resident in [('unloadable', 'unloadable', True, False),
                                                  ('nodelete', 'nodelete', True, True),
                                                  ('off_frozen', 'nodelete', False, True)]:
        case = out / name
        case.mkdir()
        sink = case / 'trace.jsonl'
        output = run('run-' + name, [driver, out / ('libresidency_' + library_name + '.so'),
                                     case / 'cmdline.bin', sink, '1' if selected else '0',
                                     '1' if resident else '0'])
        state = json.loads(output)
        assert state['resident_after_probe_close'] == resident
        assert state['resident_after_sdk_close'] == resident
        assert state['live_gpu_objects'] == 0
        assert state['instances_created'] == state['instances_destroyed'] == 2
        assert state['devices_created'] == state['devices_destroyed'] == 1
        assert state['images_created'] == state['images_destroyed'] == 1
        log = (out / ('run-' + name + '.stderr')).read_text()
        if not selected:
            assert not sink.exists(), 'Initially off admission reopened after ambient selection changed'
            assert log.count('host-property-read selected=0') == 1 and 'capture-open ' not in log
            rows = []
        else:
            assert stat.S_IMODE(sink.stat().st_mode) == 0o600
            rows = [json.loads(line, object_pairs_hook=unique_keys) for line in sink.read_text().splitlines()]
            assert all(len(line.encode()) + 1 <= 2048 for line in sink.read_text().splitlines())
            assert [row['sequence'] for row in rows] == list(range(1, len(rows) + 1))
            assert sum(row['phase'] == 'header' for row in rows) == 1
            assert not [row for row in rows if row['phase'] == 'incomplete']
            assert log.count('capture-open ') == 1
            if resident:
                assert 'capture-unavailable' not in log and log.count('host-sink-open ') == 1
                instances = [row for row in rows if row['op'] == 'vkCreateInstance' and row['phase'] == 'end']
                assert [row['createdInstanceGeneration'] for row in instances] == [1, 2]
                devices = [row for row in rows if row['op'] == 'vkCreateDevice' and row['phase'] == 'end']
                assert len(devices) == 1 and devices[0]['createdDeviceGeneration'] == 3
                images = [row for row in rows if row['op'] == 'vkCreateImage' and row['phase'] == 'end']
                assert len(images) == 1 and images[0]['instanceGeneration'] == 2 and images[0]['deviceGeneration'] == 3
                assert images[0]['image'] == 0x551
                begins = {row['call']: row for row in rows if row['phase'] == 'begin'}
                ends = {row['call']: row for row in rows if row['phase'] == 'end'}
                assert begins.keys() == ends.keys()
                assert all(begins[call]['op'] == ends[call]['op'] for call in begins)
            else:
                assert len(rows) == 9 and all(row['op'] not in ('vkCreateDevice', 'vkCreateImage') for row in rows)
                assert log.count('host-property-read selected=1') == 2 and log.count('host-sink-open ') == 2
                assert 'fixed-sink-open-failed errno=17' in log
            assert all('exclusive=1' in line for line in log.splitlines() if line.startswith('host-sink-open '))
        results[name] = dict(state=state, trace=pin(sink) if sink.exists() else None, rows=len(rows))
    assert pins == [pin(path, root) for path in inputs], 'Production or fixture source changed during validation'
    report = dict(status='pass', sources=pins, artifacts=artifacts, cases=results,
                  modeled_leaves=['Android log', 'Android property', 'two exact open path redirects', 'downstream Vulkan PFNs'],
                  real_host_behaviors=['ELF DT_FLAGS_1', 'dlopen', 'dlclose', 'RTLD_NOLOAD', 'O_EXCL', 'read/write', 'production capture/recorder state'],
                  host_only_fno_gnu_unique=True, android_loader_or_gpu_proven=False)
    (out / 'RESULT.json').write_text(json.dumps(report, indent=2))
    print('Actual DSO residency controls passed: unloadable probe-only/EEXIST RED, NODELETE continuous instance/device/image GREEN, initially-off admission frozen; all fake objects destroyed. Real Linux loader, modeled Android/Vulkan leaves.')


if __name__ == '__main__':
    main()
