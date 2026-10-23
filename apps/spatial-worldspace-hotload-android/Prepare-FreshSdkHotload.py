"""Prepare twelve fixed SDK graphics overrides; no APK, upload or device effects.

Reuses the packaged catalog's actual compiler/header/reflection contract. ABI
refusal is a host admission check, not proof of SDK rejection or shader safety.
"""
import argparse
import importlib.util
import json
import pathlib
import shutil
import tempfile

spec = importlib.util.spec_from_file_location('sdk_catalog', pathlib.Path(__file__).with_name('Prepare-SdkHotloadCatalog.py'))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)
VERTEX = ['workload.vert'+s+'.spv' for s in ('', '.mv', '.2uv', '.2uv.mv', '.tangent', '.tangent.mv', '.skin', '.skin.mv')]
FRAGMENT = ['workload.frag'+s+'.spv' for s in ('', '.mv', '.tangent', '.tangent.mv')]


def record(path):
    p = pathlib.Path(path)
    return dict(bytes=p.stat().st_size, sha256=catalog.digest(p.read_bytes()))


def headers(root):
    return {p.relative_to(root).as_posix(): record(p)['sha256'] for p in sorted(root.rglob('*.glsl'))}


def prepare(source, baseline_path, output, tools, header_root, config_path, revert=False):
    output = pathlib.Path(output).resolve()
    if output.exists():
        raise ValueError('output exists; use a fresh batch directory')
    baseline_path, config_path, header_root = map(pathlib.Path, (baseline_path, config_path, header_root))
    held = {str(p): record(p) for p in [baseline_path, config_path, *tools.values()]}
    baseline = json.loads(baseline_path.read_text())
    if baseline['schema'] != 'rusty.quest.sdk_hotload.catalog.v1' or baseline['sdkVersion'] != '0.14.0':
        raise ValueError('selected SDK0.14 packaged catalog required')
    original = next(b for b in baseline['batches'] if b['name'] == 'baseline')
    if original['vertexPaths'] != VERTEX or original['fragmentPaths'] != FRAGMENT:
        raise ValueError('fixed workload shader names required')
    entries = {e['path']: e for e in original['files']}
    if len(original['files']) != 12 or set(entries) != set(VERTEX+FRAGMENT):
        raise ValueError('complete unique baseline required')
    original_files = {name: baseline_path.parent/'baseline'/name for name in VERTEX+FRAGMENT}
    for name, path in original_files.items():
        if path.is_symlink() or record(path) != {k: entries[name][k] for k in ('bytes', 'sha256')}:
            raise ValueError('actual packaged baseline bytes differ: '+name)
        held[str(path)] = record(path)
    header_pins = headers(header_root)
    if not header_pins or header_pins != baseline['headers']:
        raise ValueError('selected SDK headers differ')
    if record(config_path)['sha256'] != baseline['shaderConfigSha256']:
        raise ValueError('selected SDK variant configuration differs')
    if {name: record(path)['sha256'] for name, path in tools.items()} != baseline['tools']:
        raise ValueError('selected compiler/validator/reflector differs')
    config = json.loads(config_path.read_text())
    if config['hsr_flags'] != ['GRAPHICS_HSR']:
        raise ValueError('selected HSR configuration differs')
    sources = {}
    if not revert:
        source = pathlib.Path(source)
        for stage in ('vert', 'frag'):
            path = source/('workload.'+stage)
            if path.is_symlink() or not 0 < path.stat().st_size <= 131072:
                raise ValueError('bounded regular workload source required')
            sources[stage] = path.read_bytes()
            held[str(path)] = record(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = pathlib.Path(tempfile.mkdtemp(prefix=output.name+'-prepare-', dir=output.parent))
    try:
        files = []
        for stage, key, names in (('vert', 'vertex_flags', VERTEX), ('frag', 'fragment_flags', FRAGMENT)):
            flags_list = config[key]
            if ['workload.'+stage+''.join(config['flag_map'][f] for f in flags)+'.spv' for flags in flags_list] != names:
                raise ValueError('fixed SDK variant order required')
            if not revert:
                shader = temporary/('input.'+stage)
                shader.write_bytes(sources[stage])
            for name, flags in zip(names, flags_list):
                target = temporary/name
                if revert:
                    target.write_bytes(original_files[name].read_bytes())
                else:
                    catalog.run([str(tools['glslc']), str(shader), '-I'+str(header_root), '--target-env=vulkan1.1', '-Werror',
                                 *['-D'+f+'=1' for f in flags+config['hsr_flags']], '-o', str(target)])
                    catalog.run([str(tools['spirv-val']), str(target)])
                    disassembly = catalog.run([str(tools['spirv-dis']), str(target)])
                    if catalog.material_uniform_bytes(disassembly) != 80 or catalog.digest(catalog.abi(disassembly).encode()) != entries[name]['abiSha256']:
                        raise ValueError('ABI/resource change refused before upload: '+name)
                info = record(target)
                if not 20 <= info['bytes'] <= 1048576:
                    raise ValueError('bounded SPIR-V required')
                files.append(dict(relative=name, **info, abiSha256=entries[name]['abiSha256']))
        for path, value in held.items():
            if record(path) != value:
                raise ValueError('input changed during preparation')
        if headers(header_root) != header_pins:
            raise ValueError('headers changed during preparation')
        for path in temporary.glob('input.*'):
            path.unlink()
        changed = [f['relative'] for f in files if f['sha256'] != entries[f['relative']]['sha256']]
        if not revert and not changed:
            raise ValueError('fresh source produced no changed shader bytes')
        manifest = dict(schema='rusty.quest.sdk_hotload.fresh_batch.v1', sdkVersion='0.14.0',
                        status='host-validated-not-uploaded', baselineCatalog=record(baseline_path),
                        mode='exact-baseline-revert' if revert else 'fresh-source', files=files, changed=changed,
                        sourceSha256={s: catalog.digest(b) for s, b in sources.items()},
                        tools=baseline['tools'], headers=header_pins, gpuCompletionProof=False, sdkSampledProof=False)
        (temporary/'MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n')
        if output.exists():
            raise ValueError('output appeared during preparation')
        temporary.rename(output)
        return manifest
    finally:
        if temporary.exists():
            resolved = temporary.resolve()
            if temporary.is_symlink() or resolved.parent != output.parent or not resolved.name.startswith(output.name+'-prepare-'):
                raise ValueError('temporary cleanup escaped output parent')
            if any(p.is_symlink() or p.is_junction() for p in temporary.rglob('*')):
                raise ValueError('temporary cleanup refuses reparse traversal')
            shutil.rmtree(resolved)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline-catalog', 'output', 'glslc', 'spirv-val', 'spirv-dis', 'headers', 'shader-config'):
        parser.add_argument('--'+name, required=True, type=pathlib.Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--source-directory', type=pathlib.Path)
    mode.add_argument('--baseline-revert', action='store_true')
    args = parser.parse_args()
    result = prepare(args.source_directory, args.baseline_catalog, args.output,
                     {'glslc': args.glslc, 'spirv-val': args.spirv_val, 'spirv-dis': args.spirv_dis},
                     args.headers, args.shader_config, args.baseline_revert)
    print(json.dumps(dict(status=result['status'], mode=result['mode'], changed=len(result['changed']))))


if __name__ == '__main__':
    main()
