#!/usr/bin/env python3
"""Headless source tests and Android API typing only; no device/network/build system."""
import argparse,hashlib,json,pathlib,shutil,subprocess,sys
HERE=pathlib.Path(__file__).resolve().parent
def digest(path):return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
def pin(path):
 p=pathlib.Path(path).resolve();return dict(path=str(p),sha256=digest(p),bytes=p.stat().st_size)
def main():
 p=argparse.ArgumentParser();p.add_argument('--sink-root',required=True,type=pathlib.Path);p.add_argument('--android-jar',required=True,type=pathlib.Path);p.add_argument('--output-root',required=True,type=pathlib.Path);p.add_argument('--javac',default=shutil.which('javac'));p.add_argument('--java',default=shutil.which('java'));a=p.parse_args()
 sink=a.sink_root.resolve();out=a.output_root.resolve();out.mkdir(parents=True,exist_ok=False)
 dep=json.loads((HERE/'DEPENDENCIES.json').read_bytes());selected=[]
 for ref in dep['sink_files']:
  actual=sink/ref['path']
  if digest(actual)!=ref['sha256']:raise ValueError('changed exact sink dependency '+ref['path'])
  selected.append(actual)
 if not a.java or not a.javac or not a.android_jar.is_file():raise ValueError('explicit actual Java/compiler/Android jar required')
 receipts=[]
 def run(name,command):
  result=subprocess.run([str(x) for x in command],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
  (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
  receipts.append(dict(name=name,command=[str(x) for x in command],exit_code=result.returncode,stdout=pin(out/(name+'.stdout')),stderr=pin(out/(name+'.stderr'))))
  if result.returncode:raise RuntimeError(name+' failed; original output retained')
 pure=out/'pure';api=out/'api34';pure.mkdir();api.mkdir()
 main_sources=sorted((HERE/'src/main/java').rglob('*.java'));tests=sorted((HERE/'tests/java').rglob('*.java'))
 pure_sink=[x for x in selected if x.name in ('StereoSinkDescriptor.java','SinkAdmission.java')]
 run('pure-javac',[a.javac,'--release','8','-Xlint:all','-Werror','-d',pure,*pure_sink,*main_sources,*tests])
 for test in ('PackedInputLeaseQueueTest','PackedReaderRetirementTest','PackedSnapshotV2ShapeTest'):
  run(test,[a.java,'-cp',pure,'io.github.mesmerprism.rustyquest.packedinput.'+test])
 run('android34-javac',[a.javac,'--release','8','-Xlint:all','-Werror','-cp',a.android_jar,'-d',api,*selected,*main_sources,*sorted((HERE/'src/android/java').rglob('*.java'))])
 result=dict(schema='rusty.quest.external_packed_input.host_checks.v1',status='PASS_HOST_ONLY',runtime_qualified=False,device_or_network_calls=False,unchanged_sink_tests_executed=False,tools=[pin(sys.executable),pin(a.javac),pin(a.java),pin(a.android_jar)],source_files=[pin(x) for x in [*main_sources,*tests,*sorted((HERE/'src/android/java').rglob('*.java'))]],dependency_files=[pin(x) for x in selected],receipts=receipts)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(status=result['status'],receipt=pin(out/'RESULT.json'))))
if __name__=='__main__':main()
