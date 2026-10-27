#!/usr/bin/env python3
"""Execute current-root lookup/general admission bodies with modeled capability leaves."""
import argparse,hashlib,json,subprocess
from pathlib import Path

def extract(text,sig):
 s=text.index(sig);i=text.index('{',s)+1;d=1
 while d:
  if text[i]=='{':d+=1
  elif text[i]=='}':d-=1
  i+=1
 return text[s:i]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--scratch-root',type=Path);a=p.parse_args()
 root=Path(__file__).resolve().parents[2];out=a.output.resolve()
 permitted=a.scratch_root.resolve() if a.scratch_root else root/'target'
 if out==permitted or out.exists() or not out.is_relative_to(permitted):raise ValueError('fresh output beneath the selected scratch root required')
 out.mkdir(parents=True);src=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/runtime_host.rs';fixture=root/'apps/spatial-camera-panel-android/native-receipt/host-tests/whole_cleanup_admission.rs'
 pins={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in [src,fixture,Path(__file__)]}
 methods='\n'.join(extract(src.read_text(),s) for s in ['fn require_general_host_gate(','fn host_for_cleanup_lookup(','fn require_general_host_admission('])
 generated=out/'controls.rs';generated.write_text(fixture.read_text().replace('/* ACTUAL_GATE_METHODS */',methods))
 cmd=['rustc','--edition','2021','--test',str(generated),'-o',str(out/'controls')];c=subprocess.run(cmd,capture_output=True);(out/'compile.stderr').write_bytes(c.stderr)
 if c.returncode:raise RuntimeError(c.stderr.decode())
 argv=[str(out/'controls'),'--test-threads=1'];q=subprocess.run(argv,capture_output=True);(out/'stdout').write_bytes(q.stdout);(out/'stderr').write_bytes(q.stderr)
 for x,pin in pins.items():
  if hashlib.sha256(Path(x).read_bytes()).hexdigest()!=pin:raise ValueError('source changed '+x)
 (out/'RESULT.json').write_text(json.dumps({'exit_code':q.returncode,'command':argv,'compile_command':cmd,'source_pins':pins,'scope':'Actual Native lookup and ordinary gate bodies; modeled capability/process slots; actual protocol authentication separately qualified; no Android/JNI/hardware'},indent=2)+'\n')
 print(q.stdout.decode());raise SystemExit(q.returncode)
if __name__=='__main__':main()
