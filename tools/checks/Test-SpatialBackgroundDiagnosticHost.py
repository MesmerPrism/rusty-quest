"""Compile actual closed contract/custody with a deterministic scheduling fixture."""
import argparse, hashlib, json, os, pathlib, subprocess, sys
sys.dont_write_bytecode = True
p=argparse.ArgumentParser()
for name in ('repo-root','output-root','java-home'):p.add_argument('--'+name,type=pathlib.Path,required=True)
a=p.parse_args();root=a.repo_root.resolve();out=a.output_root.resolve()
if out.exists() or not out.is_relative_to(root/'target'):raise ValueError('Fresh fixture-owned target required')
out.mkdir(parents=True)
base=root/'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
sources=[base/(name+'.java') for name in ('SpatialBackgroundDiagnosticContract','SpatialBackgroundDiagnosticRegistry')]
for source in sources:
    assert source.is_file(), 'Background diagnostic production contract/custody is not implemented: '+source.name
fixture=root/'apps/spatial-camera-panel-android/host-tests/background_diagnostic/BackgroundDiagnosticHost.java'
sources.append(fixture);classes=out/'classes';classes.mkdir()
pins=[dict(path=str(s.relative_to(root)),sha256=hashlib.sha256(s.read_bytes()).hexdigest()) for s in sources]
(out/'INPUTS.json').write_text(json.dumps(pins,indent=2)+'\n')
commands=[('compile',[str(a.java_home/'bin/javac.exe'),'-encoding','UTF-8','--release','17','-d',str(classes),*map(str,sources)]),('controls',[str(a.java_home/'bin/java.exe'),'-cp',str(classes),'io.github.mesmerprism.rustyquest.spatial_camera_panel.BackgroundDiagnosticHost'])]
for name,command in commands:
    r=subprocess.run(command,capture_output=True,timeout=60)
    (out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
    (out/(name+'.process.json')).write_text(json.dumps(dict(argv=command,exit_code=r.returncode),indent=2))
    if r.returncode:sys.stdout.buffer.write(r.stdout);sys.stderr.buffer.write(r.stderr);raise SystemExit(r.returncode)
    if name=='controls':sys.stdout.buffer.write(r.stdout)
for pin,source in zip(pins,sources):assert hashlib.sha256(source.read_bytes()).hexdigest()==pin['sha256']
(out/'RESULT.json').write_text(json.dumps(dict(status='passed',scope='contract-custody-host-only',inputs=pins),indent=2)+'\n')
