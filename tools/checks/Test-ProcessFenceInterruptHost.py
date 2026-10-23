#!/usr/bin/env python3
"""Whole production fence, real host lock/classic I/O, explicit Android Os leaves."""
import argparse,hashlib,json,os,pathlib,subprocess,sys
sys.dont_write_bytecode=True
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--prepared-classes',required=True);p.add_argument('--android-jar');a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex';host=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
 sources=[java/(name+'.java')for name in ('EmbeddedDuplexProcessFence','EmbeddedDuplexProcessIncarnation','EmbeddedDuplexAbandonment','EmbeddedDuplexAbandonmentRequest','EmbeddedDuplexHistoricalAbandonment','EmbeddedDuplexKnownBirthAbandonment','EmbeddedDuplexOriginalScopeDisposition')]
 sources+=[host/'ProcessFenceInterruptHostTest.java'];sources+=list((host/'android-system-host/android/system').glob('*.java'))
 classes=out/'classes';classes.mkdir();rows=[]
 def ref(x):
  x=pathlib.Path(x);b=x.read_bytes();return dict(path=str(x.resolve()),bytes=len(b),sha256=hashlib.sha256(b).hexdigest())
 def run(name,args):
  with(out/(name+'.stdout')).open('xb')as x,(out/(name+'.stderr')).open('xb')as y:r=subprocess.run(args,cwd=root,stdout=x,stderr=y)
  rows.append(dict(name=name,argv=args,exit=r.returncode,stdout=ref(out/(name+'.stdout')),stderr=ref(out/(name+'.stderr'))));return r.returncode
 prepared=pathlib.Path(a.prepared_classes).resolve(strict=True);dependency_refs=[ref(x)for x in sorted(prepared.rglob('*.class'))]
 if not dependency_refs:raise ValueError('compiled production prepared path required')
 cp=a.json_jar+os.pathsep+str(prepared)
 code=run('compile',['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)])
 if code==0:code=run('controls',['java','-cp',str(classes)+os.pathsep+cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ProcessFenceInterruptHostTest',str(out/'data')])
 witnesses=[]
 if code==0 and a.android_jar:
  android=out/'android-api-classes';android.mkdir();code=run('android-api',['javac','--release','17','-encoding','UTF-8','-cp',a.android_jar,'-d',str(android),*map(str,sources[:7])])
  if code==0:
   code=run('android-api-javap',['javap','-p','-s','-v','-classpath',str(android),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexProcessFence'])
   witnesses.append(ref(android/'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessFence.class'))
 assert dependency_refs==[ref(x)for x in sorted(prepared.rglob('*.class'))],'borrowed prepared classes changed'
 value=dict(passed=code==0,checks=rows,source_refs=[ref(x)for x in sources],dependency_refs=dependency_refs,compiled_api_witnesses=witnesses,android_api_compiled=bool(witnesses),limits=__doc__)
 (out/'RESULT.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(ref(out/'RESULT.json')));return code
if __name__=='__main__':sys.exit(main())
