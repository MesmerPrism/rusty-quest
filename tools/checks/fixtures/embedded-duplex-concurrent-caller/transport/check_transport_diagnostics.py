from pathlib import Path
import argparse,json,subprocess,os,hashlib
p=argparse.ArgumentParser()
for n in ['repo-root','java-home','compiled-owner-class-path','host-json-jar','output-directory']:p.add_argument('--'+n,required=True)
a=p.parse_args();root=Path(a.repo_root).resolve();out=Path(a.output_directory).resolve();out.mkdir(exist_ok=False);classes=out/'classes';classes.mkdir()
media=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
sources=[media/(n+'.java') for n in ['PackedStereoMediaSourceRuntime','PackedStereoMediaOwnerSet','PackedStereoMediaReceiver','RmanvidPacketReader','PackedStereoStreamMetadata','PackagedAndroidMediaOwnerRegistry','MediaOwnerAction','MediaProductBinding','MediaProviderReadback','MediaOwnerProvider','CancellationHandle','MediaRuntimeSnapshot','AndroidMediaOwnerRegistry','PackedStereoPipeline']]
fixtures=list((Path(__file__).parent/'fixtures').glob('*.java'))
suffix='.exe' if os.name=='nt' else '';java=Path(a.java_home)/'bin';cp=a.host_json_jar+os.pathsep+a.compiled_owner_class_path
steps=[('compile',[str(java/('javac'+suffix)),'--release','8','-Xlint:all','-Werror','-cp',cp,'-d',str(classes)]+list(map(str,sources+fixtures))),('callback-diagnostics',[str(java/('java'+suffix)),'-cp',str(classes)+os.pathsep+cp,'io.github.mesmerprism.rustyquest.media.RenderCallbackDiagnosticCase']),('test',[str(java/('java'+suffix)),'-cp',str(classes)+os.pathsep+cp,'io.github.mesmerprism.rustyquest.media.SocketCase'])]
rows=[]
for name,args in steps:
 with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:result=subprocess.run(args,stdout=stdout,stderr=stderr,timeout=40)
 rows.append({'operation':name,'exit_code':result.returncode})
 if result.returncode:break
(out/'result.json').write_text(json.dumps({'results':rows,'source_pins':[{'path':str(f.relative_to(root)).replace('\\','/'),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in sources],'scope':'actual source Registry/OwnerSet/Socket/metadata, real loopback TCP, actual Receiver retry/parse code; synthetic profile/full-size packets; Android lifecycle and decoder config mocked, no JNI/device/physical cause/cleanup proof'},indent=2))
if len(rows)!=3 or any(x['exit_code'] for x in rows):raise SystemExit(1)
