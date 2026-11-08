#!/usr/bin/env python3
"""Compile actual layer wrappers with fake downstream PFNs; no Vulkan runtime.

The fixture uses real NDK Vulkan/loader declarations and the production recorder.
It does not attest Android loader order, VVL handle domains, or GPU behavior.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--vulkan-include', type=Path, required=True,
                   help='NDK sysroot/usr/include (contains vulkan/vk_layer.h)')
    p.add_argument('--cxx', default='g++')
    p.add_argument('--android-clang', type=Path,
                   help='Optional installed NDK clang++ for actual Android ABI syntax-only check')
    p.add_argument('--mutation-controls', action='store_true')
    a = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / 'apps/spatial-camera-panel-android/app/src/main/cpp/vulkan_provenance'
    layer = source / 'vulkan_provenance_layer.cpp'
    if not layer.is_file():
        raise AssertionError('Production Vulkan provenance layer is not implemented')
    out = a.output.resolve()
    out.relative_to((root / 'target').resolve())
    out.mkdir(parents=True, exist_ok=False)
    fixture = Path(__file__).with_name('fixtures') / 'vulkan_provenance_layer_host.cpp'
    paths = [layer, source / 'provenance_recorder.hpp', source / 'provenance_recorder.cpp', fixture, Path(__file__).resolve()]
    pins = {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest() for f in paths}
    compiler = shutil.which(a.cxx) or a.cxx
    exe = out / 'vulkan-provenance-layer-host.exe'
    command = [compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror', '-pthread',
               '-idirafter', str(a.vulkan_include.resolve()), '-I', str(source),
               str(fixture), str(source / 'provenance_recorder.cpp'), '-o', str(exe)]
    subprocess.run(command, check=True)
    result = subprocess.run([str(exe)], text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise AssertionError(f'Actual wrapper fixture failed ({result.returncode}): {result.stderr}')
    trace_text = result.stdout.split('TRACE\n', 1)[1].split('END_TRACE\n', 1)[0]
    capped_text = result.stdout.split('CAPPED_TRACE\n', 1)[1].split('END_CAPPED_TRACE\n', 1)[0]
    chain_text = result.stdout.split('CHAIN_TRACE\n', 1)[1].split('END_CHAIN_TRACE\n', 1)[0]
    def unique_keys(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise AssertionError('Duplicate JSON field: '+key)
            obj[key] = value
        return obj
    rows = [json.loads(line, object_pairs_hook=unique_keys) for line in trace_text.splitlines()]
    capped = [json.loads(line, object_pairs_hook=unique_keys) for line in capped_text.splitlines()]
    chain_rows = [json.loads(line, object_pairs_hook=unique_keys) for line in chain_text.splitlines()]
    def matching(op, phase=None):
        return [r for r in rows if r['op'] == op and (phase is None or r['phase'] == phase)]
    def one(op, phase=None):
        found = matching(op, phase)
        assert len(found) == 1, (op, phase, found)
        return found[0]
    assert rows[0]['phase'] == 'header' and rows[0]['coverage_complete'] == 0
    assert not [r for r in rows if r['phase'] == 'incomplete'], rows
    assert [r['sequence'] for r in rows] == list(range(1, len(rows)+1))
    begin = {r['call']: r for r in rows if r['phase'] == 'begin'}
    end = {r['call']: r for r in rows if r['phase'] == 'end'}
    assert begin.keys() == end.keys() and 0 not in begin
    for call, b in begin.items():
        assert b['op'] == end[call]['op']
        assert b['instanceGeneration'] and b['deviceGeneration']
    for r in rows:
        if r['phase'] == 'note':
            assert r['call'] == 0 and r['parentCall'] in begin
    writes = matching('descriptor_write')
    assert [r['writeIndex'] for r in writes] == [0,1,2,3]
    assert [r['dstBinding'] for r in writes] == [7,8,9,19]
    images = [r for r in matching('descriptor_image') if r['writeIndex'] == 3]
    assert [(r['dstBinding'],r['dstArrayElement'],r['imageIndex'],r['imageView']) for r in images] == [
        (19,5,0,0x220),(19,5,1,0x221)]
    copy = one('descriptor_copy')
    assert (copy['srcSet'],copy['srcBinding'],copy['srcArrayElement'],copy['dstSet'],copy['dstBinding'],copy['dstArrayElement']) == (
        0x440,19,6,0x441,27,8)
    assert one('memory_dedicated')['image'] == 0x110
    assert one('memory_import_fd')['fd'] == 42
    assert [r['planeAspect'] for r in matching('image_memory_plane')] == [32,32]
    assert one('vkAllocateMemory','end')['memory'] == one('vkFreeMemory','begin')['memory'] == 0x330
    assert one('image_view_info')['image'] == one('vkDestroyImage','begin')['image'] == 0x110
    assert one('vkCreateImageView','end')['imageView'] == one('vkDestroyImageView','begin')['imageView'] == 0x220
    failed_image = [r for r in matching('vkCreateImage','end') if r['result'] == -2]
    assert len(failed_image) == 1 and failed_image[0]['image'] == 0
    assert one('vkBindImageMemory','end')['result'] == -2
    assert one('vkBindImageMemory2','begin')['deviceGeneration'] != one('vkBindImageMemory2KHR','begin')['deviceGeneration']
    assert [(r['imageIndex'],r['image']) for r in matching('swapchain_image')] == [(0,0x771),(1,0x772)]
    assert [r['result'] for r in matching('vkGetSwapchainImagesKHR','end')] == [0,5]
    assert one('debug_name')['object'] == 0x220 and one('debug_name')['nameBytes'] == 16
    assert one('vkSetDebugUtilsObjectNameEXT','end')['result'] == -13
    assert len([r for r in matching('coverage_gap') if r['gapReason'] == 5]) == 2
    assert len([r for r in matching('coverage_gap') if r['gapReason'] == 6]) == 2
    assert capped[-1]['phase'] == 'incomplete' and capped[-1]['reason'] == 'event-limit'
    assert not [r for r in chain_rows if r['phase'] == 'incomplete']
    chain_device = next(r for r in chain_rows if r['op'] == 'vkCreateDevice' and r['phase'] == 'end')
    assert chain_device['createdDeviceGeneration'] > max(r.get('deviceGeneration',0) for r in rows)
    targets = [r for r in chain_rows if r['op'] == 'dispatch_target']
    assert len(targets) == 8 and all(r['address'] for r in targets)
    assert all('module_base' in r and 'offset' in r and 'path_hash' in r for r in targets)
    def fnv64(name):
        value = 14695981039346656037
        for byte in name.encode():
            value = ((value ^ byte) * 1099511628211) & ((1 << 64)-1)
        return value
    create_target = next(r for r in targets if r['fn_hash'] == fnv64('nextCreateImage'))
    actual_create = next(r for r in chain_rows if r['op'] == 'vkCreateImage' and r['phase'] == 'begin')
    assert actual_create['nextFunction'] == create_target['address']
    assert actual_create['instanceGeneration'] > max(r.get('instanceGeneration',0) for r in rows)
    syntax_command = None
    if a.android_clang:
        sysroot = a.vulkan_include.resolve().parent.parent
        syntax_command = [str(a.android_clang.resolve()), '--target=aarch64-linux-android34',
            '--sysroot='+str(sysroot), '-std=c++20', '-Wall', '-Wextra', '-Werror', '-fsyntax-only',
            '-DVK_USE_PLATFORM_ANDROID_KHR', '-DRQ_VULKAN_PROVENANCE_APP_ID="org.example.rqvp.fixture"',
            str(layer), str(source / 'provenance_recorder.cpp')]
        subprocess.run(syntax_command, check=True)
    mutations = []
    if a.mutation_controls:
        original = layer.read_text()
        cases = [
            ('wrong-write-binding', '{"dstBinding", w.dstBinding}', '{"dstBinding", n}', 'json'),
            ('lost-destroy-key', 'const auto dispatchKey = key(device);', 'const auto dispatchKey = static_cast<void*>(nullptr);', 'abort'),
            ('unsupported-alias', 'auto original = state->gdpa(device, name); if (!original) return nullptr;',
             'auto original = state->gdpa(device, name);', 'abort'),
        ]
        for name, old, new, kind in cases:
            assert old in original, name
            mutant = out / (name+'.cpp')
            mutant.write_text(original.replace(old,new))
            mutant_fixture = out / (name+'-fixture.cpp')
            mutant_fixture.write_text(fixture.read_text().replace('"vulkan_provenance_layer.cpp"',
                '"'+mutant.as_posix()+'"'))
            mutant_exe = out / (name+'.exe')
            mutant_command = command.copy()
            mutant_command[mutant_command.index(str(fixture))] = str(mutant_fixture)
            mutant_command[-1] = str(mutant_exe)
            subprocess.run(mutant_command, check=True)
            bad = subprocess.run([str(mutant_exe)], text=True, capture_output=True, timeout=30)
            if kind == 'abort':
                assert bad.returncode != 0 and 'Assertion failed' in bad.stderr, name
            else:
                assert bad.returncode == 0, (name,bad.stderr)
                bad_trace = bad.stdout.split('TRACE\n',1)[1].split('END_TRACE\n',1)[0]
                observed = [json.loads(line)['dstBinding'] for line in bad_trace.splitlines()
                            if json.loads(line)['op'] == 'descriptor_write']
                assert observed != [7,8,9,19], name
            mutations.append(dict(name=name,status='rejected'))
    summary = f'Actual wrappers: 2-instance/device custody, exact aliases/args/results, reentry, destroy poison, off/cap forwarding; {len(rows)+len(chain_rows)} real JSONL rows and {len(mutations)} mutations validated.'
    print(summary)
    if pins != {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest() for f in paths}:
        raise AssertionError('Production/fixture input changed during checks')
    (out / 'RESULT.json').write_text(json.dumps(dict(status='pass', inputs=pins,
        command=command, android_syntax_command=syntax_command, rows_validated=len(rows)+len(chain_rows),
        mutations=mutations, summary=summary,
        scope='actual wrappers and recorder; fake Vulkan PFNs; no loader/VVL/GPU proof'), indent=2)+'\n')


if __name__ == '__main__':
    main()
