"""Exact production JNI inspection body with modeled JNI/platform leaves; separate real Java writer controls."""
import argparse,hashlib,json,pathlib,shutil,subprocess,tomllib,uuid

def body(source,signature):
    start=source.index(signature);opening=source.index('{',start);depth=1;end=opening+1
    while depth:
        if source[end]=='{':depth+=1
        elif source[end]=='}':depth-=1
        end+=1
    return source[start:end]

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2]);p.add_argument('--output',type=pathlib.Path);a=p.parse_args()
    root=a.root.resolve();out=(a.output or root/'target'/('cold-inspection-'+uuid.uuid4().hex)).resolve()
    if not out.is_relative_to(root/'target'):raise ValueError('Output must be fresh below target')
    out.mkdir(parents=True,exist_ok=False)
    native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/native_fence_jni.rs'
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/NativeRunInspectionCallerHost.rs'
    sources=[native,fixture,pathlib.Path(__file__).resolve()];pins={str(x.relative_to(root)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sources}
    actual=native.read_text(encoding='utf-8');projection=body(actual,'fn inspection_projection(');caller=body(actual,'pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_inspectOperationalRun(')
    source=fixture.read_text(encoding='utf-8').replace('// ACTUAL_PRODUCTION_INSPECTION_BODY',projection+'\n'+caller)
    (out/'lib.rs').write_text(source,encoding='utf-8')
    lock=tomllib.loads((root/'Cargo.lock').read_text(encoding='utf-8'));version=next(x['version'] for x in lock['package'] if x['name']=='serde_json')
    (out/'Cargo.toml').write_text('[package]\nname="quest-cold-pending-inspection-controls"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[lib]\npath="lib.rs"\n[dependencies]\nserde_json="='+version+'"\n',encoding='utf-8')
    cargo=shutil.which('cargo');result=subprocess.run([cargo,'test','--offline','--manifest-path',str(out/'Cargo.toml'),'--target-dir',str(out/'target'),'--','--test-threads=1'],cwd=root,capture_output=True,timeout=120)
    (out/'controls.stdout').write_bytes(result.stdout);(out/'controls.stderr').write_bytes(result.stderr)
    unchanged=all(hashlib.sha256((root/path).read_bytes()).hexdigest()==digest for path,digest in pins.items())
    report={'passed':result.returncode==0 and unchanged,'exit_code':result.returncode,'sources':pins,'source_unchanged':unchanged,'extracted_methods':['inspection_projection','EmbeddedDuplexNative_inspectOperationalRun'],'scope':__doc__,'modeled_leaves':['JNI callback lookup and return carrier','AppBinding parse result; production Java record validation is exercised separately','Native registry/platform/handoff observations'],'device_calls':0,'apk_builds':0}
    (out/'RESULT.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({'passed':report['passed'],'output':str(out)}));return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
