#!/usr/bin/env python3
"""Actual staged Pipeline/OwnerSet readiness path; Android capture leaves modeled.

The source-thread streaming publication is extracted from the production loop;
codec output, capture timestamps, compositor/worker, LAN connection state and
physical Stop are controlled host leaves. Real route resolution/listener bind and production
OwnerSet/Pipeline methods run; no Android/device/media authority claim.
"""
import argparse,hashlib,importlib.util,json,os,pathlib,subprocess,sys
sys.dont_write_bytecode=True
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 lib=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
 spec=importlib.util.spec_from_file_location('sdk_items',root/'tools/checks/Test-PreparedSdkHost.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 source=(lib/'PackedStereoMediaSourceRuntime.java').read_text();template=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/PreparedCaptureReadinessHost.java'
 pipeline=['public synchronized JSONObject prepareSender(','public void prepareTransport(','public boolean preparedTransportConnected(','public boolean preparedSenderHeld(','public boolean preparedDeliveryOpened(','public boolean preparedDeliveryClosed(','public void validateRoute(','public void startSocket(','public void startCodec(','public void startProcessor(','public void startSource(']
 runtime=['void validateRoute(','void startSocket(','void startSource(','boolean sourceFresh(','boolean freshnessExpired(']
 # Source-loop publication and finally invalidation are actual source snippets,
 # not a fixture-written streaming label. Allocation/frame production is modeled.
 publication=source[source.index('                state = sharedCapture != null ?'):source.index('                long nextSyntheticNs =')]
 final_comment=source.index('                // This thread is the sole codec output consumer.')
 ending=source[source.rindex('            } finally {',0,final_comment):source.index('                fenceSharedEncoder();',final_comment)]
 ending=ending.split('} finally {',1)[1]
 field='volatile boolean sourceStreaming;' if 'volatile boolean sourceStreaming;'in source else ''
 text=template.read_text().replace('PRODUCTION_RUNTIME_FIELDS',field).replace('PRODUCTION_PIPELINE_METHODS','\n'.join(m.item(source,x)for x in pipeline)).replace('PRODUCTION_RUNTIME_METHODS','\n'.join(m.item(source[source.index('    private static final class Runtime'):],x)for x in runtime)).replace('PRODUCTION_STREAMING_PUBLICATION',publication).replace('PRODUCTION_STREAMING_END',ending)
 generated=out/'PreparedCaptureReadinessHost.java';generated.write_text(text)
 sources=[generated,*[lib/(n+'.java')for n in ('PackedStereoMediaOwnerSet','PackedStereoPipeline','MediaOwnerProvider','MediaOwnerAction','MediaProviderReadback','MediaRuntimeSnapshot','CancellationHandle','MediaProductBinding')]]
 # Tiny Android Log is a compile-only leaf of the extracted preparation method.
 log=out/'android/util/Log.java';log.parent.mkdir(parents=True);log.write_text('package android.util;public final class Log{public static int w(String t,String m){return 0;}}');sources.append(log)
 classes=out/'classes';classes.mkdir();checks=[]
 def ref(x):
  b=x.read_bytes();return dict(path=str(x.resolve()),bytes=len(b),sha256=hashlib.sha256(b).hexdigest())
 def run(name,args):
  with(out/(name+'.stdout')).open('xb')as x,(out/(name+'.stderr')).open('xb')as y:r=subprocess.run(args,cwd=root,stdout=x,stderr=y)
  checks.append(dict(name=name,argv=args,exit=r.returncode,stdout=ref(out/(name+'.stdout')),stderr=ref(out/(name+'.stderr'))));return r.returncode
 status=run('compile',['javac','--release','17','-encoding','UTF-8','-cp',a.json_jar,'-d',str(classes),*map(str,sources)])
 if status==0:status=run('controls',['java','-cp',str(classes)+os.pathsep+a.json_jar,'io.github.mesmerprism.rustyquest.media.PreparedCaptureReadinessHost'])
 result=dict(passed=status==0,checks=checks,source_pins=[ref(x)for x in [pathlib.Path(__file__),template,lib/'PackedStereoMediaSourceRuntime.java',*sources[1:]]],limits=__doc__)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(ref(out/'RESULT.json')));return status
if __name__=='__main__':sys.exit(main())
