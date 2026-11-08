"""Compile current app Java and exercise real frozen USB inputs; native derivation modeled."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jvm-spec',type=Path,required=True)
    p.add_argument('--profile-classes',type=Path,required=True)
    p.add_argument('--android-jar',type=Path,required=True)
    p.add_argument('--assets',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--native-deriver',type=Path,help='Exact current feature-enabled pure derivation host executable, not JNI/runtime')
    a=p.parse_args();root=Path(__file__).resolve().parents[2];out=a.output.resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
    spec=json.loads(a.jvm_spec.read_text(encoding='utf-8'));home=Path(spec['java_home'])
    sources=sorted((root/'apps/spatial-camera-panel-android/app/src/main/java').rglob('*.java'))
    sources+=sorted((root/'crates/rusty-quest-media-stream-android/android/library/src/main/java').rglob('*.java'))
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/UsbAdbRelayInputsHost.java'
    sources.append(fixture)
    before={p:p.read_bytes() for p in sources}
    cp=os.pathsep.join([str(out/'classes'),str(a.profile_classes.resolve()),*spec['runtime_jars'],str(a.android_jar.resolve()),*spec['support_classes']])
    (out/'classes').mkdir()
    compile_args=['--release','17','-encoding','UTF-8','-cp',cp,'-d',str(out/'classes'),*map(str,sources)]
    argfile=out/'javac.args';argfile.write_text('\n'.join(json.dumps(x.replace('\\','/')) for x in compile_args),encoding='utf-8')
    checks=[]
    native_before=a.native_deriver.read_bytes() if a.native_deriver else None
    controls=[str(home/'bin/java.exe'),'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.UsbAdbRelayInputsHost',str(a.assets.resolve())]
    if a.native_deriver:controls.append(str(a.native_deriver.resolve()))
    for label,argv in [('compile',[str(home/'bin/javac.exe'),'@'+str(argfile)]),('controls',controls)]:
        r=subprocess.run(argv,capture_output=True,timeout=90);(out/(label+'.stdout')).write_bytes(r.stdout);(out/(label+'.stderr')).write_bytes(r.stderr);checks.append(dict(name=label,exit_code=r.returncode))
        if r.returncode:print(r.stderr.decode(errors='replace')[-5000:]);break
    unchanged=all(p.read_bytes()==b for p,b in before.items()) and (native_before is None or a.native_deriver.read_bytes()==native_before);passed=unchanged and len(checks)==2 and all(c['exit_code']==0 for c in checks)
    report=dict(passed=passed,checks=checks,source_unchanged=unchanged,source_pins=[dict(path=str(p),sha256=hashlib.sha256(b).hexdigest()) for p,b in before.items()],limits=__doc__,device_calls=0)
    report['native_derivation']='actual pure native function' if a.native_deriver else 'modeled'
    if a.native_deriver:report['native_deriver']=dict(path=str(a.native_deriver.resolve()),sha256=hashlib.sha256(native_before).hexdigest())
    (out/'RESULT.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=passed,checks=checks,result=str(out/'RESULT.json'))));raise SystemExit(0 if passed else 1)


if __name__=='__main__':main()
