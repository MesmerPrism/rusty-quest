"""Dependency-free CPU scissor leaf controls; fresh evidence, no Cargo or device effects."""
import argparse, hashlib, json, pathlib, subprocess, time, uuid
import os, re, shutil, zipfile


def block(text, name):
    start = text.index(name); end = text.index('{', start) + 1; depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}'); end += 1
    return text[start:end]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rustc', type=pathlib.Path, required=True)
    p.add_argument('--output-parent', type=pathlib.Path)
    p.add_argument('--android-ndk', type=pathlib.Path)
    p.add_argument('--android-api', type=int, default=26)
    a = p.parse_args(); root = pathlib.Path(__file__).resolve().parent
    out = (a.output_parent or root / '.local') / ('scissor-' + uuid.uuid4().hex)
    out.mkdir(parents=True, exist_ok=False); out = out.resolve()
    owner = root.parents[1] / 'apps/spatial-camera-panel-android/native-receipt/src/spatial_video_projection.rs'
    inputs = [pathlib.Path(__file__).resolve(), owner, *sorted((root/'src').glob('*.rs')), *sorted((root/'scissor').glob('*'))]
    tool_paths = [a.rustc.resolve()]
    if a.android_ndk:
        ndk=a.android_ndk.resolve(); prebuilt=ndk/'toolchains/llvm/prebuilt/windows-x86_64'
        clang=prebuilt/'bin/clang.exe';readelf=prebuilt/'bin/llvm-readelf.exe';objdump=prebuilt/'bin/llvm-objdump.exe'
        tool_paths += [clang,readelf,objdump,ndk/'source.properties',ndk/'meta/platforms.json',prebuilt/'sysroot/usr/include/stdint.h']
    tool_pins = {str(x): hashlib.sha256(x.read_bytes()).hexdigest() for x in tool_paths}
    pins = {str(x.resolve()): hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs if x.is_file()}
    reference = block(owner.read_text(), 'fn normalized_interval_to_pixels(').replace('normalized_interval_to_pixels(', 'reference_interval(', 1)
    driver = out / 'units.rs'
    common_modules = '\n'.join(f'#[path="{(root/path).as_posix()}"] mod {name};' for path,name in [('src/abi.rs','abi'),('scissor/abi.rs','scissor_abi'),('scissor/policy.rs','policy')])
    compiled = f'mod compiled {{ const GENERATION:u64=1; const VARIANT:u32=0; include!("{(root/"scissor/plugin.rs").as_posix()}"); }}'
    driver.write_text('#![allow(dead_code)]\n' + common_modules + f'\n#[path="{(root/"scissor/runtime.rs").as_posix()}"] mod scissor_runtime;\n' + compiled + '\n' + reference + '\n#[cfg(test)] mod tests {\n' + (root/'scissor/tests.rs').read_text() + '\n}\n')
    commands = []; passed = False; error = None; artifacts=[]; android_prepared=False; host_executed=False; package_pin=None
    generated_pins={str(driver):hashlib.sha256(driver.read_bytes()).hexdigest()}
    def artifact(path, kind, generation=None):
        row={'path':str(path),'kind':kind,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size}
        if generation is not None: row['generation']=generation
        artifacts.append(row)
    def run(name, command):
        start = time.monotonic(); result = subprocess.run([str(x) for x in command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (out/(name+'.log')).write_bytes(result.stdout)
        commands.append({'name':name,'arguments':[str(x) for x in command],'exit_code':result.returncode,'elapsed_seconds':time.monotonic()-start})
        if result.returncode: raise RuntimeError('Tool failed: '+name)
        return result.stdout.decode(errors='replace')
    try:
        run('rustc-version',[a.rustc,'-vV'])
        run('unit-compile',[a.rustc,'--edition','2021','--test',driver,'-D','warnings','-o',out/'units.exe'])
        run('units',[out/'units.exe','--nocapture'])
        fixtures=[('a1',1,0,False),('b2',2,0,True),('a3',3,0,False),('wrong-version',4,1,False),('short-table',5,2,False),('wrong-contract',6,3,False),('bad-behavior',7,4,False),('null-function',8,5,False)]
        def modules(directory, suffix, extra):
            directory.mkdir(); manifest=[]
            for name,generation,variant,phase_b in fixtures:
                source=directory/(name+'.rs');source.write_text('#![allow(dead_code)]\n'+common_modules+f'\nmod compiled {{ const GENERATION:u64={generation};const VARIANT:u32={variant};include!("{(root/"scissor/plugin.rs").as_posix()}"); }}\n')
                generated_pins[str(source)]=hashlib.sha256(source.read_bytes()).hexdigest()
                binary=directory/('rq-scissor-'+name+'-'+out.name.removeprefix('scissor-')+suffix)
                args=[a.rustc,'--edition','2021','--crate-type','cdylib','--crate-name',name.replace('-','_'),source,'-D','warnings','-C','opt-level=2','-C','panic=abort','-C','strip=debuginfo',*extra,'-o',binary]
                if suffix=='.so' and extra: args+=['-C','link-arg=-Wl,-soname,'+binary.name]
                if phase_b: args+=['--cfg','phase_b']
                run(directory.name+'-'+name,args)
                binary.chmod(0o444)
                sha=hashlib.sha256(binary.read_bytes()).hexdigest();manifest.append(f'{name}\t{generation}\t{sha}\t{binary.name}')
                artifact(binary,'module',generation)
            (directory/'manifest.tsv').write_text('\n'.join(manifest)+'\n',newline='\n')
            return directory/'manifest.tsv'
        host_manifest=modules(out/'host-modules','.dll' if os.name=='nt' else '.so',[])
        run('host-compile',[a.rustc,'--edition','2021',root/'scissor/host.rs','-D','warnings','-o',out/'host.exe'])
        artifact(out/'host.exe','host-proof-driver')
        host_proof=run('host-execution',[out/'host.exe',host_manifest])
        if 'SCISSOR_PROOF_PASSED' not in host_proof: raise ValueError('Missing actual host proof')
        fresh_proof=run('host-fresh-process',[out/'host.exe',host_manifest])
        if fresh_proof!=host_proof: raise ValueError('Fresh host process did not reproduce zero-state admission/proof')
        host_executed=True
        if a.android_ndk:
            meta=json.loads((ndk/'meta/platforms.json').read_text())
            if a.android_api<26 or a.android_api>meta['max']: raise ValueError('Unsupported Android API')
            flags=['--target','aarch64-linux-android','-C','linker='+str(clang),'-C','link-arg=--target=aarch64-linux-android'+str(a.android_api)]
            for tool in [clang,readelf,objdump]: run(tool.stem+'-version',[tool,'--version'])
            manifest=modules(out/'android-modules','.so',flags)
            (out/'layout.c').write_text(f'#include "{(root/"scissor/scissor.h").as_posix()}"\n')
            run('android-c-layout',[clang,'--target=aarch64-linux-android'+str(a.android_api),'-c',out/'layout.c','-o',out/'layout.o'])
            instructions={}
            for line in manifest.read_text().splitlines():
                name,_,_,filename=line.split('\t');elf=run('elf-'+name,[readelf,'--file-header','--dynamic','--dyn-syms','--wide',manifest.parent/filename])
                if not all(re.search(pattern,elf) for pattern in [r'Class:\s+ELF64',r'Machine:\s+AArch64',r'Type:\s+DYN']): raise ValueError('ELF type/architecture')
                exports=re.findall(r'^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+\S+\s+(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\S+\s+(\S+)\s*$',elf,re.M)
                if exports!=['rq_scissor_api']: raise ValueError('Unexpected module exports: '+str(exports))
                if not re.search(r'\(SONAME\).*\['+re.escape(filename)+r'\]',elf): raise ValueError('Module SONAME mismatch')
                needed=re.findall(r'\(NEEDED\).*\[([^\]]+)\]',elf)
                if not needed or set(needed)-{'libc.so','libdl.so','libm.so','liblog.so'}: raise ValueError('Nonplatform dependency')
                if name in ['a1','b2','a3']:
                    symbols=run('symbols-'+name,[readelf,'--symbols','--wide',manifest.parent/filename])
                    found=re.findall(r'^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+FUNC\s+LOCAL\s+DEFAULT\s+\S+\s+(\S*compiled\S*project\S*)\s*$',symbols,re.M)
                    if len(found)!=1: raise ValueError('Exact compiled project function required')
                    disassembly=run('project-'+name,[objdump,'--disassemble-symbols='+found[0],manifest.parent/filename])
                    words=re.findall(r'^\s*[0-9a-fA-F]+:\s+([0-9a-fA-F]{8})\s+.+$',disassembly,re.M)
                    if not words: raise ValueError('No actual projection instructions')
                    instructions[name]=words
            if instructions['a1']!=instructions['a3'] or instructions['a1']==instructions['b2']: raise ValueError('Actual Android A/B/restoration code differs from contract')
            run('android-host-compile',[a.rustc,'--edition','2021',root/'scissor/host.rs','-D','warnings',*flags,'-C','opt-level=2','-C','strip=debuginfo','-o',out/'android-modules/scissor-proof'])
            artifact(out/'android-modules/scissor-proof','android-proof-driver')
            elf=run('android-driver-elf',[readelf,'--file-header','--dynamic','--program-headers','--wide',out/'android-modules/scissor-proof'])
            if not re.search(r'Machine:\s+AArch64',elf) or '/system/bin/linker64' not in elf: raise ValueError('Android driver architecture/interpreter')
            needed=re.findall(r'\(NEEDED\).*\[([^\]]+)\]',elf)
            if not needed or set(needed)-{'libc.so','libdl.so','libm.so','liblog.so'}: raise ValueError('Nonplatform driver dependency')
            package=out/'android-device-package';package.mkdir()
            for name in [line.split('\t')[3] for line in manifest.read_text().splitlines()]+['manifest.tsv','scissor-proof']:
                shutil.copyfile(manifest.parent/name,package/name)
                (package/name).chmod(0o444)
            sums=''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(package.iterdir()))
            (package/'SHA256SUMS').write_bytes(sums.encode('ascii'))
            (package/'SHA256SUMS').chmod(0o444)
            archive=out/'android-device-package.zip'
            with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
                for f in sorted(package.iterdir()): z.write(f,f.name)
            archive.chmod(0o444)
            package_pin={'path':str(archive),'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'files':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(package.iterdir())},'executed':False}
            android_prepared=True
        passed = True
    except Exception as failure: error = str(failure)
    unchanged = all(hashlib.sha256(pathlib.Path(n).read_bytes()).hexdigest()==h for n,h in pins.items())
    tools_unchanged=all(hashlib.sha256(pathlib.Path(n).read_bytes()).hexdigest()==h for n,h in tool_pins.items())
    generated_unchanged=all(hashlib.sha256(pathlib.Path(n).read_bytes()).hexdigest()==h for n,h in generated_pins.items())
    artifacts_unchanged=all(hashlib.sha256(pathlib.Path(row['path']).read_bytes()).hexdigest()==row['sha256'] for row in artifacts)
    receipt = {'passed':passed and unchanged and tools_unchanged and generated_unchanged and artifacts_unchanged,'source_unchanged':unchanged,'source_pins':pins,'tool_pins':tool_pins,'tools_unchanged':tools_unchanged,'generated_pins':generated_pins,'generated_unchanged':generated_unchanged,'compiler':str(a.rustc.resolve()),'compiler_sha256':hashlib.sha256(a.rustc.read_bytes()).hexdigest(),'commands':commands,'failure':error,'artifacts':artifacts,'artifacts_unchanged':artifacts_unchanged,'host_executed':host_executed,'android_prepared':android_prepared,'android_executed':False,'android_device_package':package_pin,'device_calls':0,'claim':__doc__}
    (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n'); print(json.dumps({'passed':receipt['passed'],'output':str(out),'failure':error}))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__': raise SystemExit(main())
