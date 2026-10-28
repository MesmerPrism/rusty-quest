#!/usr/bin/env python3
"""Whole prepared holder + exact callback/resource/pipeline methods and real LAN sockets.

Android decoder/renderer/source allocation, Original store and callback-fence
leaves are modeled explicitly. No device, APK, media authority or SDK proof.
"""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,importlib.util,json,os,pathlib,subprocess

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('output','json-jar','android-jar','media-classes','media-artifacts'):p.add_argument('--'+name,required=True)
    a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
    app=root/'apps/spatial-camera-panel-android';java=app/'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    media=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media';native=app/'native-receipt/src/embedded_duplex'
    spec=importlib.util.spec_from_file_location('owner_items',root/'tools/checks/Test-PreparedSdkHost.py');owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner);item=owner.item
    template=app/'host-tests/embedded_duplex/PreparedTransportHost.java';platform=(java/'EmbeddedDuplexPlatform.java').read_text();resources=(java/'EmbeddedDuplexResources.java').read_text();source=(media/'PackedStereoMediaSourceRuntime.java').read_text()
    body=template.read_text().replace('PRODUCTION_FINALIZE_CALLBACK',item(platform,'public String finalizePeerStartTransport(')).replace('PRODUCTION_RESOURCE_METHODS','\n'.join(item(resources,n) for n in ['JSONObject prepareDecoder(','void finalizePreparedTransport(','boolean preparedTransportConnected(']))
    package='package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;\n'
    # Exact binary owner/SourceFile witness for the new callback, while its
    # process/callback lease leaves remain the explicitly modeled host fixture.
    platform_body=item(body,'class EmbeddedDuplexPlatform {');body=body.replace(platform_body,'')
    callback=out/'EmbeddedDuplexPlatform.java';callback.write_text(package+'import org.json.*;\n'+platform_body,encoding='utf-8')
    generated=out/'PreparedTransportHost.java';generated.write_text(body,encoding='utf-8')
    resource_body=item(body,'class EmbeddedDuplexResources {');body=body.replace(resource_body,'');generated.write_text(body,encoding='utf-8')
    resource_file=out/'EmbeddedDuplexResources.java';resource_file.write_text(package+'import java.net.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;import io.github.mesmerprism.rustyquest.media.PreparedTransportPipeline;\n'+resource_body,encoding='utf-8')
    media_out=out/'media/io/github/mesmerprism/rustyquest/media';media_out.mkdir(parents=True)
    pipeline=media_out/'PreparedTransportPipeline.java';pipeline.write_text(PIPELINE.replace('PRODUCTION_PIPELINE_METHODS','\n'.join(item(source,n) for n in ['public void prepareTransport(','public boolean preparedTransportConnected(','public boolean preparedSenderHeld('])),encoding='utf-8')
    transport=media_out/'PackedStereoLanTransport.java';transport.write_bytes((media/'PackedStereoLanTransport.java').read_bytes())
    log=out/'android/util/Log.java';log.parent.mkdir(parents=True);log.write_text('package android.util; public final class Log{public static int i(String t,String m){System.out.println(m);return 0;}public static int w(String t,String m){return 0;}}')
    sources=[generated,callback,resource_file,pipeline,transport,log,java/'EmbeddedDuplexPreparedStart.java',java/'EmbeddedDuplexCanonicalJson.java'];checks=[]
    def ref(path):
        raw=path.read_bytes();return dict(path=str(path),bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
    def run(name,argv):
        with (out/(name+'.stdout')).open('xb') as x,(out/(name+'.stderr')).open('xb') as y:r=subprocess.run(argv,cwd=root,stdout=x,stderr=y)
        checks.append(dict(name=name,argv=argv,exit=r.returncode,stdout=ref(out/(name+'.stdout')),stderr=ref(out/(name+'.stderr'))));assert r.returncode==0,name
    media_classes=pathlib.Path(a.media_classes).resolve(strict=True);media_receipt=pathlib.Path(a.media_artifacts).resolve(strict=True)
    media_before=ref(media_classes);receipt_before=ref(media_receipt)
    assert media_before['sha256']==json.loads(media_receipt.read_text())['classes_jar_sha256'],'actual retained media receipt differs'
    whole=out/'whole-media-android-classes';whole.mkdir()
    run('whole-media-android-api',['javac','--release','17','-encoding','UTF-8','-cp',a.android_jar+os.pathsep+str(media_classes),'-d',str(whole),str(media/'BoundedPacketPump.java'),str(media/'PackedStereoMediaSourceRuntime.java')])
    run('whole-media-Pipeline-javap',['javap','-p','-s','-v','-classpath',str(whole),'io.github.mesmerprism.rustyquest.media.PackedStereoMediaSourceRuntime$Pipeline'])
    for tag,jar in [('host',a.json_jar),('android-api',a.android_jar)]:
        classes=out/(tag+'-classes');classes.mkdir()
        run(tag+'-compile',['javac','--release','17','-encoding','UTF-8','-cp',jar,'-d',str(classes)]+list(map(str,sources)))
    cp=str(out/'host-classes')+os.pathsep+a.json_jar
    run('holder-transport-controls',['java','-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedTransportHost'])
    for name in ('EmbeddedDuplexPreparedStart','EmbeddedDuplexPlatform','EmbeddedDuplexResources'):
        run(name+'-javap',['javap','-p','-s','-v','-classpath',str(out/'android-api-classes'),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.'+name])
    # Actual callback body and unchanged closure machinery typechecked against
    # jni 0.21.1. No JVM connection or target invocation is made.
    bridge=(native/'java_bridge.rs').read_text();jni=out/'jni';(jni/'src').mkdir(parents=True)
    (jni/'Cargo.toml').write_text('[package]\nname="prepared-transport-jni-host"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\njni="=0.21.1"\nserde={version="1",features=["derive"]}\nserde_json="1"\nsha2="0.10"\ned25519-dalek={version="=2.1.1",default-features=false,features=["std","zeroize"]}\n')
    codec=root/'crates/rusty-quest-media-stream-android/src/prepared_start.rs'
    code='extern crate self as rusty_quest_media_stream_android;\n#[path='+json.dumps(codec.as_posix())+']mod codec;pub use codec::*;\nuse jni::{JNIEnv,objects::{JObject,JString,JValue,GlobalRef}};\nstruct Capability;impl Capability{fn require_live(&self)->Result<(),String>{Ok(())}}\nstruct JavaOwnerCallbacks{vm:jni::JavaVM,callback:GlobalRef,capability:Capability}\nimpl JavaOwnerCallbacks{'
    code+='\n'.join(item(bridge,n) for n in ['pub(super) fn finalize_prepared_transport(','fn fresh_closure_callback(','fn attached(','fn checked_call<'])+'}\n'
    code+=item(bridge[bridge.rindex('\nfn checked_call<'):],'fn checked_call<')+'\n'+item(bridge,'fn prepared_registry_readback(')
    # super visibility is unchanged semantically within the actual module.
    code='mod actual{'+code.replace('extern crate self as rusty_quest_media_stream_android;','')+'}\nextern crate self as rusty_quest_media_stream_android;\npub use actual::codec::*;'
    code=code.replace('mod codec;','pub mod codec;')
    (jni/'src/lib.rs').write_text(code,encoding='utf-8')
    run('jni-api-typecheck',['cargo','check','--offline','--manifest-path',str(jni/'Cargo.toml'),'--target-dir',str(out/'jni-cargo')])
    refs=[template,pathlib.Path(__file__).resolve(),java/'EmbeddedDuplexPreparedStart.java',java/'EmbeddedDuplexPlatform.java',java/'EmbeddedDuplexResources.java',java/'EmbeddedDuplexCanonicalJson.java',media/'BoundedPacketPump.java',media/'PackedStereoMediaSourceRuntime.java',media/'PackedStereoLanTransport.java',native/'java_bridge.rs']
    assert media_before==ref(media_classes) and receipt_before==ref(media_receipt),'borrowed dependency changed'
    binary=pathlib.Path('io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex')
    result=dict(passed=True,checks=checks,source_pins=[ref(x) for x in refs],generated=[ref(x) for x in sources if x.is_relative_to(out)],dependency_refs=[media_before,receipt_before],compiled_api_witnesses=[ref(out/'android-api-classes'/binary/(n+'.class')) for n in ['EmbeddedDuplexPreparedStart','EmbeddedDuplexPlatform','EmbeddedDuplexResources']]+[ref(whole/'io/github/mesmerprism/rustyquest/media/PackedStereoMediaSourceRuntime$Pipeline.class')],witness_scope='Whole production PreparedStart and shared SourceRuntime/Pipeline against real Android34. Platform/Resources exact method bodies with modeled surrounding host leaves. Baseline media jar supplies unchanged dependencies only; no APK compiler/DEX/runtime claim.',limits=__doc__)
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(ref(out/'RESULT.json')))

PIPELINE=r'''package io.github.mesmerprism.rustyquest.media;
import java.net.*;import java.util.concurrent.*;
public final class PreparedTransportPipeline implements AutoCloseable{
    final Runtime runtime,preparedRuntime;
    static final class Runtime{Object preparedFormat=new Object();boolean stopRequested,preparedDeliveryOpen,terminal;long packetCount,headerWriteCount;ServerSocket serverSocket;PackedStereoLanTransport lanTransport;final int source;
        volatile CountDownLatch entered,release;Runtime(int remote)throws Exception{try(ServerSocket s=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){source=s.getLocalPort();}lanTransport=new PackedStereoLanTransport("127.0.0.1",source,"127.0.0.1","127.0.0.1",remote);}
        void startSocket()throws Exception{if(entered!=null){entered.countDown();release.await();}if(stopRequested)throw new IllegalStateException("MODELED source allocation cancelled");serverSocket=new ServerSocket(source,1,InetAddress.getLoopbackAddress());}}
    public PreparedTransportPipeline(int remote)throws Exception{runtime=new Runtime(remote);preparedRuntime=runtime;}
    PRODUCTION_PIPELINE_METHODS
    public boolean entered(){return runtime.serverSocket!=null;}
    public boolean held(){return preparedSenderHeld();}
    public boolean zeroDelivery(){return !runtime.preparedDeliveryOpen&&runtime.packetCount==0&&runtime.headerWriteCount==0;}
    public String diagnostic(){return runtime.lanTransport.diagnostic();}
    public boolean terminal(){return runtime.lanTransport.terminal();}
    public void requestStop(){runtime.stopRequested=true;runtime.lanTransport.requestStop();try{if(runtime.serverSocket!=null)runtime.serverSocket.close();}catch(Exception e){throw new IllegalStateException(e);}}
    public void block(){runtime.entered=new CountDownLatch(1);runtime.release=new CountDownLatch(1);}
    public boolean awaitEntered()throws Exception{return runtime.entered.await(5,TimeUnit.SECONDS);}
    public void release(){runtime.release.countDown();}
    public void close()throws Exception{requestStop();runtime.lanTransport.stop();if(!runtime.lanTransport.terminal())throw new AssertionError("exact transport workers not joined");}
}'''
if __name__=='__main__':main()
