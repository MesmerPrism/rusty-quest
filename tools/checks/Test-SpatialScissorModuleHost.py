"""Actual optional product scissor client with existing immutable native images; no APK/device/Cargo."""
import argparse,hashlib,json,pathlib,subprocess,os

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--host-deps',type=pathlib.Path,required=True);p.add_argument('--module-manifest',type=pathlib.Path,required=True)
 a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=a.output.resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 source=root/'apps/spatial-camera-panel-android/native-receipt/src/spatial_scissor_module.rs';fixture=root/'tools/checks/fixtures/spatial_scissor_module_host.rs';renderer=source.with_name('spatial_video_projection.rs')
 pins={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in [source,renderer,fixture,pathlib.Path(__file__).resolve()]}
 def block(text,name):
  start=text.index(name);end=text.index('{',start)+1;depth=1
  while depth: depth+=(text[end]=='{')-(text[end]=='}');end+=1
  return text[start:end]
 r=renderer.read_text(encoding='utf-8');hook='\n'.join(block(r,name) for name in ['fn target_rect_to_scissor(', 'fn normalized_interval_to_pixels('])
 generated=out/'controls.rs';generated.write_text(fixture.read_text(encoding='utf-8').replace('PRODUCTION_MODULE',source.as_posix()).replace('// ACTUAL_RENDERER_SCISSOR_HOOK',hook),encoding='utf-8')
 sha=list(a.host_deps.glob('libsha2-*.rlib'));assert len(sha)==1
 common=['rustc','--edition','2021','--test',str(generated),'-L','dependency='+str(a.host_deps),'--extern','sha2='+str(sha[0]),'-D','warnings','-o',str(out/'controls.exe')]
 commands=[]
 def run(name,args):
  r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env={**os.environ,'SCISSOR_MODULE_MANIFEST':str(a.module_manifest.resolve())});(out/(name+'.log')).write_bytes(r.stdout);commands.append({'name':name,'exit_code':r.returncode});return r.returncode
 compiled=run('compile',common);tested=run('controls',[str(out/'controls.exe'),'--test-threads=1','--nocapture']) if compiled==0 else None
 unchanged=all(hashlib.sha256(pathlib.Path(n).read_bytes()).hexdigest()==h for n,h in pins.items());passed=compiled==0 and tested==0 and unchanged
 report={'passed':passed,'commands':commands,'source_pins':pins,'source_unchanged':unchanged,'sha2_pin':hashlib.sha256(sha[0].read_bytes()).hexdigest(),'module_manifest':{'path':str(a.module_manifest.resolve()),'sha256':hashlib.sha256(a.module_manifest.read_bytes()).hexdigest()},'scope':__doc__,'device_calls':0}
 (out/'RESULT.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':passed,'output':str(out)}));return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
