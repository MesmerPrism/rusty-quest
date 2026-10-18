"""Compile a closed SDK 0.14 shader catalog. No Gradle, device or network effects."""
import argparse, hashlib, json, pathlib, re, shutil, subprocess, tempfile

# Closed workload material ABI. Any row addition requires fresh packaged shaders/catalog.
MATERIAL_UNIFORM_ROWS = 10
MATERIAL_UNIFORM_BYTES = MATERIAL_UNIFORM_ROWS * 16


# Opt-in normal SDK0.14 primary compilation. Old catalogs remain immutable.
SDK_PRIMARY_COMPILE_PROFILE = dict(schema='rusty.quest.sdk014.compile_profile.v1',
    optimization='performance', optimizationFlag='-O',
    targetEnvironment='default-vulkan1.0', hsrFlags=['GRAPHICS_HSR'])

def sdk_primary_command(glslc, source, headers, flags, target):
    return [str(glslc), '-O', '-I'+str(headers),
            *['-D'+flag+'=1' for flag in flags], str(source), '-o', str(target)]

def selected_sdk_primary_profile(value, required=False):
    if value is None and not required:
        return False
    if value != SDK_PRIMARY_COMPILE_PROFILE:
        raise ValueError('exact SDK0.14 primary performance compile profile required')
    return True

def digest(data): return hashlib.sha256(data).hexdigest()
def run(args):
    result=subprocess.run(args,capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr or result.stdout)
    return result.stdout
def abi(disassembly):
    definitions, decorations, members, variables = {}, {}, {}, []
    for line in disassembly.splitlines():
        tokens = line.strip().split()
        if not tokens: continue
        if tokens[0] == 'OpDecorate' and tokens[2] not in ('RelaxedPrecision',):
            decorations.setdefault(tokens[1], []).append(tokens[2:])
        if tokens[0] == 'OpMemberDecorate': members.setdefault(tokens[1], []).append(tokens[2:])
        if len(tokens)>3 and tokens[1]=='=':
            definitions[tokens[0]]=tokens[2:]
            if tokens[2]=='OpVariable' and tokens[4] in ('UniformConstant','Uniform','StorageBuffer','PushConstant','Input','Output'): variables.append(tokens[0])
    def expand(value, seen=()):
        if not value.startswith('%'): return value
        if value in seen: raise ValueError('recursive ABI type')
        return [ [expand(x, seen+(value,)) for x in definitions[value]], sorted(decorations.get(value,[])), sorted(members.get(value,[])) ]
    records=[expand(v) for v in variables]
    return json.dumps(sorted(records,key=lambda x:json.dumps(x,sort_keys=True)),sort_keys=True,separators=(',',':'))

def material_uniform_bytes(disassembly):
    definitions, decorations, offsets = {}, {}, {}
    for line in disassembly.splitlines():
        tokens=line.strip().split()
        if len(tokens)>3 and tokens[1]=='=': definitions[tokens[0]]=tokens[2:]
        if len(tokens)>3 and tokens[0]=='OpDecorate': decorations.setdefault(tokens[1],[]).append(tokens[2:])
        if len(tokens)==5 and tokens[0]=='OpMemberDecorate' and tokens[3]=='Offset':
            offsets.setdefault(tokens[1],{})[int(tokens[2])]=int(tokens[4])
    selected=[name for name,ops in decorations.items() if ['DescriptorSet','3'] in ops and ['Binding','0'] in ops
        and definitions.get(name,[])[0:1]==['OpVariable']]
    if len(selected)!=1: raise ValueError('One material uniform at set3/binding0 required')
    variable=definitions[selected[0]];pointer=definitions[variable[1]]
    if variable[2]!='Uniform' or pointer[0:2]!=['OpTypePointer','Uniform']: raise ValueError('Material uniform storage changed')
    structure=pointer[2];members=definitions[structure]
    if members[0]!='OpTypeStruct' or len(members)!=MATERIAL_UNIFORM_ROWS+1 or offsets.get(structure)!={i:i*16 for i in range(MATERIAL_UNIFORM_ROWS)}:
        raise ValueError('Material uniform must contain exactly '+str(MATERIAL_UNIFORM_ROWS)+' contiguous vec4 rows')
    for member in members[1:]:
        vector=definitions[member]
        if vector[0]!='OpTypeVector' or vector[2]!='4' or definitions[vector[1]]!=['OpTypeFloat','32']:
            raise ValueError('Material row type changed')
    return MATERIAL_UNIFORM_BYTES

VERTEX = ['workload.vert'+s+'.spv' for s in ('', '.mv', '.2uv', '.2uv.mv', '.tangent', '.tangent.mv', '.skin', '.skin.mv')]
FRAGMENT = ['workload.frag'+s+'.spv' for s in ('', '.mv', '.tangent', '.tangent.mv')]


def prepare_baseline(source, output, tools, headers, config_path):
    """Compile only the explicitly supplied consumer; no app source or presets."""
    source, output, headers, config_path = map(pathlib.Path, (source, output, headers, config_path))
    output = output.absolute()
    if output.exists():
        raise ValueError('output exists; choose a fresh catalog directory')
    config = json.loads(config_path.read_text())
    if config['hsr_flags'] != ['GRAPHICS_HSR']:
        raise ValueError('Closed HSR configuration required')
    for stage, key, names in (('vert', 'vertex_flags', VERTEX), ('frag', 'fragment_flags', FRAGMENT)):
        actual = ['workload.'+stage+''.join(config['flag_map'][f] for f in flags)+'.spv' for flags in config[key]]
        if actual != names:
            raise ValueError('Closed variant order required')
    paths = [source/'workload.vert', source/'workload.frag', config_path, *tools.values(), *sorted(headers.rglob('*.glsl'))]
    if not list(headers.rglob('*.glsl')):
        raise ValueError('SDK headers required')
    def held():
        result = {}
        for item in paths:
            path = pathlib.Path(item).absolute()
            if any(p.is_symlink() or p.is_junction() for p in (path, *path.parents)) or not path.is_file():
                raise ValueError('Regular non-reparse inputs required')
            if not 0 < path.stat().st_size <= 16777216:
                raise ValueError('Bounded input required')
            result[str(path)] = digest(path.read_bytes())
        return result
    pins = held()
    header_pins = {p.relative_to(headers).as_posix(): digest(p.read_bytes()) for p in sorted(headers.rglob('*.glsl'))}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = pathlib.Path(tempfile.mkdtemp(prefix=output.name+'-prepare-', dir=output.parent))
    try:
        files = []
        for stage, key in (('vert', 'vertex_flags'), ('frag', 'fragment_flags')):
            original = (source/('workload.'+stage)).read_bytes()
            src = temporary/('input.'+stage)
            src.write_bytes(original)
            for flags in config[key]:
                name = 'workload.'+stage+''.join(config['flag_map'][f] for f in flags)+'.spv'
                target = temporary/'baseline'/name
                target.parent.mkdir(exist_ok=True)
                run(sdk_primary_command(tools['glslc'], src, headers, flags+config['hsr_flags'], target))
                run([str(tools['spirv-val']), '--target-env', 'vulkan1.0', str(target)])
                disassembly = run([str(tools['spirv-dis']), str(target)])
                material_uniform_bytes(disassembly)
                data = target.read_bytes()
                if not 20 <= len(data) <= 1048576:
                    raise ValueError('Bounded shader bytes required')
                files.append(dict(path=name, bytes=len(data), sha256=digest(data),
                                  sourceSha256=digest(original), abiSha256=digest(abi(disassembly).encode()),
                                  defines=flags+config['hsr_flags']))
        if held() != pins or header_pins != {p.relative_to(headers).as_posix(): digest(p.read_bytes()) for p in sorted(headers.rglob('*.glsl'))}:
            raise ValueError('Input changed during compilation')
        result = dict(schema='rusty.quest.sdk_hotload.catalog.v1', sdkVersion='0.14.0',
                      materialUniformBytes=MATERIAL_UNIFORM_BYTES, compileProfile=SDK_PRIMARY_COMPILE_PROFILE,
                      shaderConfigSha256=digest(config_path.read_bytes()),
                      tools={k:digest(pathlib.Path(v).read_bytes()) for k,v in tools.items()}, headers=header_pins,
                      batches=[dict(name='baseline', files=files, vertexPaths=VERTEX, fragmentPaths=FRAGMENT)])
        (temporary/'catalog.json').write_text(json.dumps(result, indent=2)+'\n')
        for item in temporary.glob('input.*'):
            item.unlink()
        if output.exists():
            raise ValueError('output appeared during preparation')
        temporary.rename(output)
        return result
    finally:
        if temporary.exists():
            resolved = temporary.resolve()
            if temporary.is_symlink() or resolved.parent != output.parent or not resolved.name.startswith(output.name+'-prepare-'):
                raise ValueError('Temporary cleanup escaped output parent')
            if any(p.is_symlink() or p.is_junction() for p in temporary.rglob('*')):
                raise ValueError('Temporary cleanup refuses reparse traversal')
            shutil.rmtree(resolved)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-directory','output','glslc','spirv-val','spirv-dis','headers','shader-config'):
        parser.add_argument('--'+name, required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = prepare_baseline(args.source_directory, args.output,
                              {k:getattr(args,k.replace('-','_')) for k in ('glslc','spirv-val','spirv-dis')},
                              args.headers, args.shader_config)
    print(json.dumps(dict(schema=result['schema'], shaders=12, deviceEffects=False)))


if __name__ == '__main__':
    main()
