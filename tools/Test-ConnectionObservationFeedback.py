"""Independent connection-observation consumer of the existing Java feedback host. No APK/device."""
import argparse, hashlib, json, os, pathlib, subprocess, time

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[1])
    p.add_argument('--java-home',type=pathlib.Path,required=True)
    p.add_argument('--output',type=pathlib.Path,required=True)
    a=p.parse_args(); root=a.root.resolve(); out=a.output.resolve()
    out.relative_to(root/'.local'); out.mkdir(parents=True,exist_ok=False)
    module=root/'android/development-feedback'; main=module/'src/main/java/org/rusty/quest/feedback'
    sources=[main/'api/FeedbackModule.java']+[main/(name+'.java') for name in
        ['ArtifactStore','DexDefinitions','GenerationLoader','ModuleHost','ConnectionObservationController']]
    sources += [module/'src/host/java/org/rusty/quest/feedback/HostUrlLoader.java',module/'host-tests/ConnectionObservationConformance.java']
    templates=sorted((module/'host-tests/connection').glob('*.template'))
    suffix='.exe' if os.name=='nt' else ''
    tools=[a.java_home/('bin/javac'+suffix),a.java_home/('bin/java'+suffix)]
    inputs=sources+templates+[pathlib.Path(__file__).resolve()]+tools
    def pins(): return {str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs}
    before=pins(); records=[]; started=time.monotonic(); classes=out/'classes'; classes.mkdir()
    commands=[('compile',[str(tools[0]),'--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-d',str(classes)]+list(map(str,sources))),
        ('controls',[str(tools[1]),'-cp',str(classes),'ConnectionObservationConformance',str(module/'host-tests/connection'),str(out/'evidence'),str(tools[0])])]
    for name,cmd in commands:
        start=time.monotonic(); r=subprocess.run(cmd,capture_output=True,timeout=90)
        (out/(name+'.stdout')).write_bytes(r.stdout); (out/(name+'.stderr')).write_bytes(r.stderr)
        records.append(dict(name=name,command=cmd,exit_code=r.returncode,elapsed_ms=(time.monotonic()-start)*1000))
        if r.returncode: break
    same=before==pins(); passed=same and len(records)==2 and all(r['exit_code']==0 for r in records)
    result=dict(passed=passed,source_unchanged=same,source_tool_pins=before,commands=records,elapsed_ms=(time.monotonic()-started)*1000,
        scope=__doc__,limits='Real JVM, fresh defining loaders and existing artifact/ModuleHost implementation. Resident resources/Future/UNKNOWN are fixture values. No product integration, transport, physical cleanup, durable UNKNOWN, ART or device proof.',apk_builds=0,device_calls=0)
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(passed=passed,source_unchanged=same,commands=[dict(name=x['name'],exit_code=x['exit_code']) for x in records],output=str(out))))
    return 0 if passed else 1

if __name__=='__main__': raise SystemExit(main())
