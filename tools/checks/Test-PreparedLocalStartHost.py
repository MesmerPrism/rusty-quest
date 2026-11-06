"""Whole production holder and actual ordinaryStart/Platform qualification; SDK/Original leaves modeled."""
import argparse,hashlib,importlib.util,json,os,pathlib,re,subprocess,sys
sys.dont_write_bytecode=True

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True);parser.add_argument('--json-jar',required=True);parser.add_argument('--java-home')
    args=parser.parse_args();root=pathlib.Path(__file__).resolve().parents[2]
    out=pathlib.Path(args.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
    java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    template=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/PreparedLocalStartHost.java'
    helper=root/'tools/checks/Test-PreparedSdkHost.py'
    spec=importlib.util.spec_from_file_location('owner_items',helper);owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
    ordinary=owner.item((java/'EmbeddedDuplexProcessHost.java').read_text(),'CompletableFuture<String> ordinaryStart(')
    calls=re.findall(r'platform\.(requirePreparedNative\w+)\(prepared(?:,\s*true)?\);',ordinary)
    assert len(calls)==1,'One actual ordinary Start preparation qualification required'
    method=owner.item((java/'EmbeddedDuplexPlatform.java').read_text(),'void '+calls[0]+'(')
    call=re.search(r'platform\.'+calls[0]+r'\(prepared(?:,\s*true)?\);',ordinary).group().replace('platform.','')
    generated=out/'LocalStartPlatform.java'
    generated.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;import org.json.JSONObject;'
        'class LocalStartPlatform{final EmbeddedDuplexPreparedStart preparedStart;LocalStartPlatform(EmbeddedDuplexPreparedStart held){preparedStart=held;}'
        +method+'void qualify(JSONObject prepared)throws Exception{'+call+'}}',encoding='utf-8')
    receiver_source=java/'EmbeddedDuplexReceiver.java';resource_source=java/'EmbeddedDuplexResources.java'
    receiver_predicate=owner.item(receiver_source.read_text(),'synchronized boolean preparedDecoderStartable(')
    resource_forward=owner.item(resource_source.read_text(),'boolean preparedDecoderStartable(')
    chain=out/'LocalReceiverQualification.java'
    chain.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
final class LocalReceiverQualification {
 interface ReceiverRuntime{boolean preparedDecoderStartable();}
 ReceiverRuntime receiver;String dormantPreparationId,preparationState;boolean dormantPrepared,surfaceReleased,projectionRetired;Object staged;
 '''+receiver_predicate+'''
 static int controls(){
  LocalReceiverQualification held=new LocalReceiverQualification();held.receiver=()->true;held.dormantPreparationId="same";
  held.preparationState="prepared";held.dormantPrepared=true;held.staged=new Object();
  LocalResourceQualification resources=new LocalResourceQualification();resources.incoming=held;
  require(resources.preparedDecoderStartable("same"),"exact incoming owner refused");
  require(!resources.preparedDecoderStartable("foreign")&&!resources.preparedDecoderStartable(null),"foreign preparation accepted");
  held.surfaceReleased=true;require(!resources.preparedDecoderStartable("same"),"released Surface accepted");held.surfaceReleased=false;
  held.projectionRetired=true;require(!resources.preparedDecoderStartable("same"),"retired projection accepted");held.projectionRetired=false;
  held.staged=null;require(!resources.preparedDecoderStartable("same"),"missing projection accepted");held.staged=new Object();
  held.preparationState="preparation_failed";require(!resources.preparedDecoderStartable("same"),"failed preparation accepted");held.preparationState="prepared";
  held.receiver=null;require(!resources.preparedDecoderStartable("same"),"missing receiver accepted");held.receiver=()->false;
  require(!resources.preparedDecoderStartable("same"),"physical receiver refusal ignored");held.receiver=()->true;
  held.dormantPrepared=false;require(!resources.preparedDecoderStartable("same"),"foreign non-prepared receiver accepted");
  return 9;
 }
 static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
}
final class LocalResourceQualification{LocalReceiverQualification incoming;'''+resource_forward+'}',encoding='utf-8')
    sources=[java/(name+'.java') for name in ('EmbeddedDuplexPreparedStart','EmbeddedDuplexCanonicalJson','EmbeddedDuplexPeerRoots')]+[template,generated,chain]
    pinned=[pathlib.Path(__file__),helper,java/'EmbeddedDuplexProcessHost.java',java/'EmbeddedDuplexPlatform.java',receiver_source,resource_source,*sources]
    def pins():return [dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in pinned]
    before=pins();classes=out/'classes';classes.mkdir();checks=[]
    def run(name,argv):
        result=subprocess.run(argv,cwd=root,capture_output=True,timeout=60)
        (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
        checks.append(dict(name=name,exit=result.returncode,argv=argv));return result.returncode
    javac=str(pathlib.Path(args.java_home)/'bin/javac.exe') if args.java_home else 'javac'
    executable=str(pathlib.Path(args.java_home)/'bin/java.exe') if args.java_home else 'java'
    compile_exit=run('javac',[javac,'--release','17','-encoding','UTF-8','-cp',args.json_jar,'-d',str(classes),*map(str,sources)])
    code=compile_exit
    if code==0:code=run('controls',[executable,'-cp',os.pathsep.join([str(classes),args.json_jar]),
        'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedLocalStartHost',str(out/'files')])
    result=dict(passed=code==0,exit=code,compile_exit=compile_exit,source_pins=before,source_unchanged=before==pins(),checks=checks,
        ordinary_start_qualification=calls[0],limits=__doc__,device_calls=0)
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(passed=result['passed'],exit=code,compile_exit=compile_exit)));return code

if __name__=='__main__':sys.exit(main())
