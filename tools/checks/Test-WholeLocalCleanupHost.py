#!/usr/bin/env python3
"""Execute actual Whole drain/resource methods with explicit physical/Android leaves."""
import argparse,hashlib,json,os,subprocess
from pathlib import Path

def extract(text,signature):
    start=text.index(signature);opening=text.index('{',start);depth=1;i=opening+1
    while depth:
        if text[i]=='{':depth+=1
        elif text[i]=='}':depth-=1
        i+=1
    return text[start:i]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--scratch-root',type=Path);p.add_argument('--java-home',type=Path,required=True);a=p.parse_args()
    root=Path(__file__).resolve().parents[2];out=a.output.resolve()
    permitted=a.scratch_root.resolve() if a.scratch_root else root/'target'
    if out==permitted or out.exists() or not out.is_relative_to(permitted):raise ValueError('fresh output beneath the selected scratch root required')
    out.mkdir(parents=True)
    base=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    host=base/'EmbeddedDuplexProcessHost.java';resources=base/'EmbeddedDuplexResources.java';platform=base/'EmbeddedDuplexPlatform.java'
    receiver=base/'EmbeddedDuplexReceiver.java';pipeline=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/PackedStereoMediaSourceRuntime.java'
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/WholeLocalCleanupHost.java'
    media=pipeline.parent
    registry_sources=[media/(x+'.java') for x in ['PackagedAndroidMediaOwnerRegistry','AndroidMediaOwnerRegistry','MediaOwnerAction','MediaProviderReadback','MediaOwnerProvider','MediaProductBinding','CancellationHandle','MediaRuntimeSnapshot']]
    registry_fixture=fixture.with_name('OwnedRegistryCleanupHost.java')
    pins={str(x.relative_to(root)):hashlib.sha256(x.read_bytes()).hexdigest() for x in [host,platform,resources,receiver,pipeline,fixture,registry_fixture,Path(__file__),*registry_sources]}
    h=host.read_text();res=resources.read_text()
    methods=[extract(h,x) for x in ['private static final class RetainedWholeLocalDrain {','private void requireWholeLocalDrain(','private void awaitWholeLocalTerminal(','private CompletableFuture<Void> drainWholeLocalResources(']]
    body=fixture.read_text().replace('/* ACTUAL_HOST_METHODS */','\n'.join(methods)).replace('/* ACTUAL_RESOURCE_METHOD */',extract(res,'void retireOwnedLocalResources()'))
    body=body.replace('/* ACTUAL_SOURCE_CONTINUATION */',extract(pipeline.read_text(),'void continueOwnedLocalStop()'))
    body=body.replace('/* ACTUAL_PIPELINE_METHOD */',extract(pipeline.read_text(),'public void stopOwnedLocalResources(')).replace('/* ACTUAL_READER_METHOD */',extract(receiver.read_text(),'private void awaitOwnedReaderRelease('))
    source=out/'WholeLocalCleanupHost.java';source.write_text(body)
    def run(name,cmd):
        q=subprocess.run(cmd,capture_output=True);(out/(name+'.stdout')).write_bytes(q.stdout);(out/(name+'.stderr')).write_bytes(q.stderr)
        if q.returncode:raise RuntimeError(q.stderr.decode(errors='replace'))
        return q
    compile_cmd=[str(a.java_home/'bin/javac.exe'),'--release','17','-d',str(out/'classes'),str(source)]
    run('compile',compile_cmd);cmd=[str(a.java_home/'bin/java.exe'),'-cp',str(out/'classes'),'WholeLocalCleanupHost']
    q=run('controls',cmd)
    jars=list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('json-20240303.jar'))
    if len(jars)!=1:raise ValueError('pinned org.json20240303 host jar required')
    jar=jars[0];jar_pin=hashlib.sha256(jar.read_bytes()).hexdigest()
    if jar_pin!='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':raise ValueError('host JSON dependency changed')
    registry_body=registry_fixture.read_text().replace('/* ACTUAL_PLATFORM_METHODS */','\n'.join(extract(platform.read_text(),x) for x in ['void fenceOwnedForwardEffects(','void awaitOwnedForwardEffectsJoined(','void installRegistry(']))
    generated_registry=out/'OwnedRegistryCleanupHost.java';generated_registry.write_text(registry_body)
    registry_compile=[str(a.java_home/'bin/javac.exe'),'--release','17','-cp',str(jar),'-d',str(out/'registry-classes'),*[str(x) for x in registry_sources],str(generated_registry)]
    run('registry-compile',registry_compile)
    registry_cmd=[str(a.java_home/'bin/java.exe'),'-cp',str(out/'registry-classes')+os.pathsep+str(jar),'OwnedRegistryCleanupHost']
    registry=run('registry-controls',registry_cmd)

    for x,pin in pins.items():
        if hashlib.sha256((root/x).read_bytes()).hexdigest()!=pin:raise ValueError('source changed '+x)
    (out/'RESULT.json').write_text(json.dumps({'exit_code':0,'command':cmd,'compile_command':compile_cmd,'registry_command':registry_cmd,'registry_compile_command':registry_compile,'host_json_sha256':jar_pin,'source_pins':pins,'scope':'Actual Host retained-drain and resource closure methods, real threads/Futures; modeled platform/resource leaves; no Android/Native runtime'},indent=2)+'\n')
    print(q.stdout.decode());print(registry.stdout.decode())
if __name__=='__main__':main()
