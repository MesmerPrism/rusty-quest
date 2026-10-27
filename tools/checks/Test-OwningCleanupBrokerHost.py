#!/usr/bin/env python3
"""Compile/test actual combined Broker cleanup source with frozen policy API hunks.

Only a temporary module-path composition and a three-way authority merge are
created. Both owners remain untouched. The real explicitly selected Quest and
Manifold DTO dependencies are used; this is not an Android application graph.
"""
import argparse,hashlib,json,re,subprocess,tomllib
from pathlib import Path

def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--supplier-quest-root',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--scratch-root',type=Path);p.add_argument('--filter',default='embedded_duplex::authority::tests');a=p.parse_args()
    root=Path(__file__).resolve().parents[2];supplier=a.supplier_quest_root.resolve();out=a.output.resolve();runner=digest(Path(__file__))
    permitted=a.scratch_root.resolve() if a.scratch_root else root/'target'
    if out==permitted or out.exists() or not out.is_relative_to(permitted):raise ValueError('fresh output beneath the selected scratch root required')
    out.mkdir(parents=True);broker=root/'crates/rusty-quest-broker-authority';primary=supplier/'crates/rusty-quest-broker-authority'
    relative='src/embedded_duplex/authority.rs';ours=broker/relative;theirs=primary/relative
    gitfile=root/'.git'
    gitdir=None
    if gitfile.is_file():
        value=gitfile.read_text().strip().removeprefix('gitdir: ')
        if len(value)>2 and value[1:3]==':/' and str(root).startswith('/mnt/'):
            value='/mnt/'+value[0].lower()+value[2:]
        gitdir=Path(value)
        if (gitdir/'commondir').is_file():gitdir=(gitdir/(gitdir/'commondir').read_text().strip()).resolve()
    git=['git','--git-dir',str(gitdir)] if gitdir else ['git','-C',str(root)]
    base=subprocess.check_output(git+['show','7dfc94dbadd413bf1624fb109182ce85d9477483:crates/rusty-quest-broker-authority/'+relative])
    for name,raw in [('base.rs',base),('ours.rs',ours.read_bytes()),('theirs.rs',theirs.read_bytes())]:(out/name).write_bytes(raw.replace(b'\r\n',b'\n'))
    merged=subprocess.run(git+['merge-file','-p',str(out/'ours.rs'),str(out/'base.rs'),str(out/'theirs.rs')],capture_output=True)
    (out/'merge.stderr').write_bytes(merged.stderr)
    if merged.returncode:raise ValueError('authority source merge requires owner resolution: '+merged.stdout.decode()[-3000:])
    authority=out/'authority.rs';authority.write_bytes(merged.stdout)
    runtime=out/'runtime.rs'
    runtime_text=(primary/'src/runtime.rs').read_text()
    def bind_include(m):
        value=m.group(2)
        target=(broker/'src/coupled_renewal_tests.rs') if value=='coupled_renewal_tests.rs' else (primary/'src'/value).resolve()
        return m.group(1)+json.dumps(str(target))+m.group(3)
    runtime_text=re.sub(r'(include(?:_str|_bytes)?!\(\s*)"([^"]+)"(\s*\))',bind_include,runtime_text)
    runtime_text=re.sub(r'(#\[path\s*=\s*)"([^"]+)"(\])',lambda m:m.group(1)+json.dumps(str((primary/'src'/m.group(2)).resolve()))+m.group(3),runtime_text)
    runtime.write_text(runtime_text)
    lib=out/'lib.rs';lib.write_text('mod embedded_duplex { #[path='+json.dumps(str(authority))+'] mod authority; pub use authority::*; }\npub use embedded_duplex::*;\n#[path='+json.dumps(str(runtime))+'] mod runtime;\npub use runtime::*;\n')
    # The protocol source remains the cleanup owner source, not an old supplier copy.
    protocol=out/'protocol';protocol.mkdir()
    media_overlay=out/'media';media_overlay.mkdir()
    contracts_overlay=out/'contracts';contracts_overlay.mkdir()
    def manifest(crate,name,libpath):
        data=tomllib.loads((crate/'Cargo.toml').read_text());text='[package]\nname='+json.dumps(name)+'\nversion="0.0.0"\nedition="2021"\n[workspace]\n[lib]\npath='+json.dumps(str(libpath))+'\n'
        if name in ['rusty-quest-media-stream-android','rusty-quest-media-stream']:text+='[features]\ndefault=[]\ntest-support=[]\n'
        text+='[dependencies]\n'
        for n,d in data['dependencies'].items():
            if isinstance(d,str):text+=n+'='+json.dumps(d)+'\n';continue
            d=dict(d)
            if 'path' in d:
                d['path']=str((supplier/'crates'/n) if n.startswith('rusty-quest-') else (supplier.parent/'rusty-manifold/crates'/n))
                if n=='rusty-quest-media-stream-android':d['path']=str(protocol);d['features']=['test-support']
                if n=='rusty-quest-media-stream':d['path']=str(media_overlay)
                if n=='rusty-quest-broker-contracts':d['path']=str(contracts_overlay)
            text+=n+'={'+','.join(k+'='+json.dumps(v) for k,v in d.items())+'}\n'
        return text
    (contracts_overlay/'Cargo.toml').write_text(manifest(supplier/'crates/rusty-quest-broker-contracts','rusty-quest-broker-contracts',supplier/'crates/rusty-quest-broker-contracts/src/lib.rs').replace('[workspace]\n',''))
    (media_overlay/'Cargo.toml').write_text(manifest(root/'crates/rusty-quest-media-stream','rusty-quest-media-stream',root/'crates/rusty-quest-media-stream/src/lib.rs').replace('[workspace]\n',''))
    (protocol/'Cargo.toml').write_text(manifest(root/'crates/rusty-quest-media-stream-android','rusty-quest-media-stream-android',root/'crates/rusty-quest-media-stream-android/src/lib.rs').replace('[workspace]\n',''))
    (out/'Cargo.toml').write_text(manifest(broker,'rusty-quest-broker-authority',lib))
    host=subprocess.check_output(['rustc','-Vv'],text=True).split('host: ',1)[1].splitlines()[0]
    meta=subprocess.run(['cargo','metadata','--offline','--manifest-path',str(out/'Cargo.toml'),'--format-version','1','--filter-platform',host],capture_output=True)
    (out/'metadata.stderr').write_bytes(meta.stderr)
    if meta.returncode:raise RuntimeError(meta.stderr.decode())
    (out/'metadata.json').write_bytes(meta.stdout)
    files={Path(__file__).resolve(),ours,theirs,primary/'src/runtime.rs',broker/'Cargo.toml',broker/'src/lib.rs',broker/'src/coupled_renewal_tests.rs',out/'Cargo.toml',lib,authority,runtime}
    dirs=[root/'crates/rusty-quest-media-stream-android',root/'crates/rusty-quest-media-stream',primary/'src',supplier/'crates/rusty-quest-broker-contracts']
    files.add(supplier.parent/'rusty-manifold/crates/rusty-manifold-admission/contracts/development-lifetime-policy.v1.json')
    for pkg in json.loads(meta.stdout)['packages']:
        if pkg['source'] is None:
            m=Path(pkg['manifest_path']);files.add(m)
            if m.parent not in [out,protocol,media_overlay,contracts_overlay]:dirs.append(m.parent)
    for directory in dirs:
        for f in directory.rglob('*'):
            if f.is_file() and 'target' not in f.relative_to(directory).parts and f.suffix in {'.rs','.json','.toml'}:files.add(f)
    pins=[{'path':str(f),'sha256':digest(f),'bytes':f.stat().st_size} for f in sorted(files)]
    cmd=['cargo','test','--offline','--locked','--manifest-path',str(out/'Cargo.toml'),a.filter,'--','--test-threads=1']
    q=subprocess.run(cmd,capture_output=True);(out/'stdout').write_bytes(q.stdout);(out/'stderr').write_bytes(q.stderr)
    for row in pins:
        if digest(Path(row['path']))!=row['sha256']:raise ValueError('input changed '+row['path'])
    if digest(Path(__file__))!=runner:raise ValueError('runner changed')
    (out/'RESULT.json').write_text(json.dumps({'exit_code':q.returncode,'source_pins':pins,'command':cmd,'runner_sha256':runner,'scope':'Actual merged Broker cleanup and primary policy source, real source DTO dependencies; isolated host package; no APK/JNI/hardware'},indent=2)+'\n')
    print(q.stdout.decode());print(q.stderr.decode()[-7000:] if q.returncode else '')
    raise SystemExit(q.returncode)
if __name__=='__main__':main()
