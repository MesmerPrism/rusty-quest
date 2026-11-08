"""Typecheck the production provider against Android API; Activity SDK leaves modeled."""
import argparse, hashlib, json, os, pathlib, subprocess, sys, xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
p=argparse.ArgumentParser()
for name in ('repo-root','output-root','java-home','kotlin-lib','android-jar'):p.add_argument('--'+name,type=pathlib.Path,required=True)
a=p.parse_args();root=a.repo_root.resolve();out=a.output_root.resolve()
if out.exists() or not out.is_relative_to(root/'target'):raise ValueError('Fresh fixture-owned target required')
out.mkdir(parents=True)
app=root/'apps/spatial-camera-panel-android/app/src';suffix='java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
debug=app/'debug'/suffix
source=[debug/'SpatialBackgroundDiagnosticContract.java',debug/'SpatialBackgroundDiagnosticRegistry.java',debug/'SpatialBackgroundDiagnosticProvider.kt',app/'main'/suffix/'SpatialBackgroundControl.kt']
for s in source:assert s.is_file(), 'Missing background diagnostic definition: '+s.name
ns={'android':'http://schemas.android.com/apk/res/android'};attr='{'+ns['android']+'}'
manifest=ET.parse(app/'debug/AndroidManifest.xml').getroot()
providers=[x for x in manifest.findall('application/provider') if x.attrib.get(attr+'name')=='.SpatialBackgroundDiagnosticProvider']
assert len(providers)==1
for k,v in {'authorities':'${applicationId}.spatial-background-diagnostic','permission':'android.permission.DUMP','exported':'true','grantUriPermissions':'false'}.items():assert providers[0].attrib[attr+k]==v
for s in (app/'main').rglob('*'):
    if s.is_file() and s.suffix in ('.kt','.java','.xml'):
        text=s.read_text(encoding='utf-8-sig')
        assert 'SpatialBackgroundDiagnosticProvider' not in text and '.spatial-background-diagnostic' not in text, 'Debug route leaked into main: '+str(s)
for name in ('SpatialBackgroundDiagnosticContract','SpatialBackgroundDiagnosticRegistry','SpatialBackgroundDiagnosticProvider'):
    assert not list((app/'main').rglob(name+'.*')), 'Debug class in release/main roots'
stub=out/'ActivityLeaf.kt'
stub.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
object BuildConfig { const val DEBUG=true }
enum class SpatialBackgroundMode { Black, Video }
class SpatialCameraPanelActivity:android.app.Activity(){
 internal fun backgroundControlObservation()=SpatialBackgroundControlObservation()
 internal fun selectBackgroundControlMode(mode:SpatialBackgroundMode)=SpatialBackgroundControlObservation()
}
''',encoding='utf-8')
classes=out/'classes';classes.mkdir()
libs=sorted(a.kotlin_lib.glob('*.jar'));stdlib=next(x for x in libs if x.name.startswith('kotlin-stdlib-'))
pins=[dict(path=str(s),sha256=hashlib.sha256(s.read_bytes()).hexdigest()) for s in [*source,a.android_jar,*libs]]
commands=[('java',[str(a.java_home/'bin/javac.exe'),'-encoding','UTF-8','--release','17','-d',str(classes),*map(str,source[:2])]),('provider',[str(a.java_home/'bin/java.exe'),'-cp',os.pathsep.join(map(str,libs)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',os.pathsep.join(map(str,[a.android_jar,stdlib,classes])),'-d',str(classes),str(source[2]),str(source[3]),str(stub)])]
for name,command in commands:
    r=subprocess.run(command,capture_output=True,timeout=60)
    (out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
    (out/(name+'.process.json')).write_text(json.dumps(dict(argv=command,exit_code=r.returncode),indent=2))
    if r.returncode:sys.stdout.buffer.write(r.stdout);sys.stderr.buffer.write(r.stderr);raise SystemExit(r.returncode)
for pin in pins:assert hashlib.sha256(pathlib.Path(pin['path']).read_bytes()).hexdigest()==pin['sha256']
(out/'RESULT.json').write_text(json.dumps(dict(status='passed',scope='actual-provider-api-typecheck-modeled-activity-leaf',release_source_route_absent=True,release_apk_dex_inspected=False,inputs=pins),indent=2)+'\n')
print('Production debug provider Android typecheck and source/manifest isolation passed; no APK, release DEX, SDK lifecycle or device proof.')
