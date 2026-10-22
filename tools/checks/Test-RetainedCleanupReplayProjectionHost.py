"""Actual signed cleanup server regressions using an explicit compatible supplier.

Only scratch manifests replace the selected Manifold path; copied production
sources are byte-exact. The default sibling graph is never changed.
"""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,json,pathlib,re,shutil,subprocess

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--manifold-root',required=True);a=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2];supplier=pathlib.Path(a.manifold_root).resolve(strict=True)
    out=pathlib.Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False);graph=out/'quest';(graph/'crates').mkdir(parents=True)
    names=['rusty-quest-media-stream-android','rusty-quest-media-stream','rusty-quest-device-link']
    pins=[]
    def pin(p):
        raw=p.read_bytes();return dict(path=str(p.resolve()),bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
    for name in names:
        source=root/'crates'/name;dest=graph/'crates'/name;dest.mkdir();shutil.copytree(source/'src',dest/'src')
        for path in sorted((source/'src').rglob('*')):
            if path.is_file():assert path.read_bytes()==(dest/'src'/path.relative_to(source/'src')).read_bytes();pins.append(pin(path))
        original=(source/'Cargo.toml').read_text()
        selected=re.sub(r'path = "\.\./\.\./\.\./rusty-manifold/crates/([^"]+)"',lambda m:'path = '+json.dumps((supplier/'crates'/m.group(1)).as_posix()),original)
        (dest/'Cargo.toml').write_text(selected,encoding='utf-8')
    workspace=(root/'Cargo.toml').read_text();metadata=workspace[workspace.index('[workspace.package]'):workspace.index('[profile.',workspace.index('[workspace.package]'))] if '[profile.' in workspace else workspace[workspace.index('[workspace.package]'):]
    (graph/'Cargo.toml').write_text('[workspace]\nmembers='+json.dumps(['crates/'+n for n in names])+'\nresolver="2"\n'+metadata,encoding='utf-8')
    argv=['cargo','test','--offline','--manifest-path',str(graph/'Cargo.toml'),'--target-dir',str(out/'cargo'),'-p','rusty-quest-media-stream-android','retained_cleanup_server::tests','--','--test-threads=1']
    r=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (out/'rust.stdout').write_bytes(r.stdout);(out/'rust.stderr').write_bytes(r.stderr)
    result=dict(passed=r.returncode==0,exit_code=r.returncode,argv=argv,source_pins=pins,
        selected_manifold_root=str(supplier),selected_manifold_commit=subprocess.check_output(['git','--no-optional-locks','-C',str(supplier),'rev-parse','HEAD'],text=True).strip(),
        scratch_only_manifest_path_selection=True,production_sources_byte_exact=True,device_calls=0,apk_or_android_native_build=False)
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(r.stdout.decode(errors='replace'));print(json.dumps({k:v for k,v in result.items() if k!='source_pins'}));sys.exit(r.returncode)
if __name__=='__main__':main()
