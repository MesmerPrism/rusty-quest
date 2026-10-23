"""Compile a closed SDK 0.14 shader catalog. No Gradle, device or network effects."""
import argparse, hashlib, json, pathlib, re, shutil, subprocess, tempfile

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
    if members[0]!='OpTypeStruct' or len(members)!=6 or offsets.get(structure)!={i:i*16 for i in range(5)}:
        raise ValueError('Material uniform must contain five vec4 rows at offsets0/16/32/48/64')
    for member in members[1:]:
        vector=definitions[member]
        if vector[0]!='OpTypeVector' or vector[2]!='4' or definitions[vector[1]]!=['OpTypeFloat','32']:
            raise ValueError('Material row type changed')
    return 80

def main():
    p=argparse.ArgumentParser()
    for name in ('glslc','spirv-val','spirv-dis','headers','shader-config','output'): p.add_argument('--'+name,required=True)
    p.add_argument('--development-feedback',action='store_true')
    a=p.parse_args(); config=json.loads(pathlib.Path(a.shader_config).read_text())
    if config['hsr_flags']!=['GRAPHICS_HSR'] or len(config['vertex_flags'])!=8 or len(config['fragment_flags'])!=4:
        raise ValueError('Closed SDK shader variant configuration changed')
    source=pathlib.Path(__file__).parent/'app/src/shaders'; output=pathlib.Path(a.output).resolve(); output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists(): raise ValueError('output exists; choose a fresh numbered catalog directory')
    temporary=pathlib.Path(tempfile.mkdtemp(prefix=output.name+'-prepare-',dir=output.parent))
    baseline={}; catalog={'schema':'rusty.quest.sdk_hotload.catalog.v1','sdkVersion':'0.14.0','batches':[],
        'materialUniformBytes':80,'developmentFeedback':a.development_feedback,'shaderConfigSha256':digest(pathlib.Path(a.shader_config).read_bytes()),
        'tools':{name:digest(pathlib.Path(value).read_bytes()) for name,value in (('glslc',a.glslc),('spirv-val',a.spirv_val),('spirv-dis',a.spirv_dis))},
        'headers':{f.relative_to(pathlib.Path(a.headers)).as_posix():digest(f.read_bytes()) for f in sorted(pathlib.Path(a.headers).rglob('*.glsl'))}}
    try:
        for batch in ('baseline','tint','offset'):
            record={'name':batch,'files':[],'vertexPaths':[],'fragmentPaths':[]}
            for stage,key in (('vert','vertex_flags'),('frag','fragment_flags')):
                original_bytes=(source/('workload.'+stage)).read_bytes()
                original=original_bytes.decode().replace('\r\n','\n'); text=original
                if batch=='tint' and stage=='frag':
                    if text.count('outColor=c;')!=1: raise ValueError('Tint patch anchor changed')
                    text=text.replace('outColor=c;','outColor=vec4(c.rgb*vec3(1.0,.28,.8),c.a);')
                if batch=='offset' and stage=='vert':
                    if text.count('gl_Position=getClipFromWorld()*world;')!=1: raise ValueError('Offset patch anchor changed')
                    text=text.replace('gl_Position=getClipFromWorld()*world;', 'world.x+=.15;gl_Position=getClipFromWorld()*world;')
                src=temporary/('input.'+stage); src.write_bytes(text.encode())
                for flags in config[key]:
                    suffix=''.join(config['flag_map'][f] for f in flags)
                    relative='workload.'+stage+suffix+'.spv'; target=temporary/'hotload'/batch/relative; target.parent.mkdir(parents=True,exist_ok=True)
                    run([a.glslc,str(src),'-I'+str(pathlib.Path(a.headers)),*[ '-D'+f+'=1' for f in flags+config['hsr_flags']],'-DRQ_DEVELOPMENT_FEEDBACK='+str(int(a.development_feedback)),'-o',str(target)])
                    run([a.spirv_val,str(target)])
                    disassembly=run([a.spirv_dis,str(target)])
                    if material_uniform_bytes(disassembly)!=catalog['materialUniformBytes']:
                        raise ValueError('Reflected material size differs from catalog')
                    reflected=abi(disassembly); abi_hash=digest(reflected.encode())
                    if batch=='baseline': baseline[relative]=abi_hash
                    elif baseline[relative]!=abi_hash: raise ValueError('ABI changed: '+relative)
                    data=target.read_bytes(); record['files'].append({'path':relative,'sha256':digest(data),'bytes':len(data),'originalSourceSha256':digest(original_bytes),'sourceSha256':digest(text.encode()),'abiSha256':abi_hash,'defines':flags+config['hsr_flags']})
                    record['vertexPaths' if stage=='vert' else 'fragmentPaths'].append(relative)
            record['abiSha256']=digest(json.dumps(baseline,sort_keys=True).encode()); catalog['batches'].append(record)
        (temporary/'hotload/catalog.json').write_text(json.dumps(catalog,indent=2)+'\n')
        for f in temporary.glob('input.*'): f.unlink()
        if output.exists(): raise ValueError('output appeared during preparation')
        temporary.rename(output)
        print(json.dumps({'catalogSha256':digest((output/'hotload/catalog.json').read_bytes()),'batches':3,'validatedShaders':36}))
    finally:
        if temporary.exists():
            resolved=temporary.resolve()
            if temporary.is_symlink() or resolved.parent!=output.parent or not resolved.name.startswith(output.name+'-prepare-'):
                raise ValueError('temporary cleanup path escaped output parent')
            if any(p.is_symlink() or p.is_junction() for p in temporary.rglob('*')):
                raise ValueError('temporary cleanup refuses link or reparse traversal')
            shutil.rmtree(resolved)
if __name__=='__main__': main()
