"""Actual default loader inventory boundary with candidate debug assets; no APK/device/Gradle."""
import argparse, hashlib, json, os, pathlib, subprocess, sys
sys.dont_write_bytecode = True

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2])
    for name in ('output','java-home','android-jar','json-jar'):p.add_argument('--'+name,type=pathlib.Path,required=True)
    p.add_argument('--reference-classes',type=pathlib.Path,nargs='+',required=True)
    a=p.parse_args();root=a.root.resolve();out=a.output.resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
    classes=out/'classes';classes.mkdir();package='io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    loader=root/'apps/spatial-camera-panel-android/app/src/main/java'/package/'EmbeddedDuplexPackagedInputs.java'
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/GuardedModeAssetInventoryHost.java'
    cp=os.pathsep.join(map(str,[a.android_jar,a.json_jar,*a.reference_classes]));records=[]
    inputs=[loader,fixture,pathlib.Path(__file__).resolve(),a.android_jar,a.json_jar]
    def digest(path):
        if path.is_dir():
            rows=[file.relative_to(path).as_posix()+' '+digest(file)+'\n' for file in sorted(path.rglob('*')) if file.is_file()]
            return hashlib.sha256(''.join(rows).encode()).hexdigest()
        with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
    inputs.extend(a.reference_classes)
    inputs.extend([a.java_home/'bin/javac.exe',a.java_home/'bin/java.exe'])
    pins={str(path):digest(path) for path in inputs}
    for path in (root/'apps/spatial-camera-panel-android/app/src/debug/assets').rglob('*'):
        if path.is_file():pins[str(path)]=digest(path)
    commands=[('compile',[a.java_home/'bin/javac.exe','--release','17','-classpath',cp,'-d',classes,loader,fixture]),('inventory-controls',[a.java_home/'bin/java.exe','-cp',str(classes)+os.pathsep+cp,package.replace('/','.')+'.GuardedModeAssetInventoryHost',root/'apps/spatial-camera-panel-android/app/src/debug/assets'])]
    for name,args in commands:
        child=subprocess.run(list(map(str,args)),capture_output=True)
        (out/(name+'.stdout')).write_bytes(child.stdout);(out/(name+'.stderr')).write_bytes(child.stderr)
        records.append(dict(name=name,argv=list(map(str,args)),exit_code=child.returncode,reaped=True,streams_closed=True))
        if child.returncode:break
    result=dict(passed=all(x['exit_code']==0 for x in records),checks=records,sources=pins,source_unchanged=all(digest(pathlib.Path(path))==sha for path,sha in pins.items()),limits='Actual current loader method and candidate debug overlay. Manifest open is an explicit fixture sentinel; full manifest, Android signer, Native and device behavior are not entered.')
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n')
    for row in records:
        if row['exit_code']:sys.stderr.buffer.write((out/(row['name']+'.stderr')).read_bytes());break
    print(json.dumps(dict(passed=result['passed'],checks=len(records))))
    return 0 if result['passed'] and result['source_unchanged'] else 1

if __name__=='__main__':raise SystemExit(main())
