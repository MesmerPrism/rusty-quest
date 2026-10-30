"""Actual guarded operator parser/provider helper/Host methods; modeled Android and Native leaves."""
import argparse,hashlib,importlib.util,json,os,pathlib,subprocess,sys
sys.dont_write_bytecode=True


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2])
    for name in ('output','java-home','stdlib','json-jar'):p.add_argument('--'+name,type=pathlib.Path,required=True)
    p.add_argument('--compiler-classpath',required=True)
    p.add_argument('--parser-only',action='store_true')
    a=p.parse_args();root=a.root.resolve();out=a.output.resolve()
    if out.exists() or not out.is_relative_to(root/'target'):raise ValueError('Fresh output below target required')
    out.mkdir(parents=True);classes=out/'classes';classes.mkdir()
    app=root/'apps/spatial-camera-panel-android';src=app/'app/src'
    package='io/github/mesmerprism/rustyquest/spatial_camera_panel'
    production=src/'main/java'/package/'embedded_duplex';debug=src/'debug/java'/package
    pins={};checks=[]
    def read(path):
        raw=path.read_bytes();pins[str(path.relative_to(root))]=hashlib.sha256(raw).hexdigest();return raw.decode()
    def write(name,source):(out/name).write_text(source,encoding='utf-8')
    read(pathlib.Path(__file__).resolve())
    def run(name,argv):
        child=subprocess.run(list(map(str,argv)),capture_output=True)
        (out/(name+'.stdout')).write_bytes(child.stdout);(out/(name+'.stderr')).write_bytes(child.stderr)
        checks.append(dict(name=name,argv=list(map(str,argv)),exit_code=child.returncode))
        (out/'processes.json').write_text(json.dumps(checks,indent=2))
        if child.returncode:
            sys.stdout.buffer.write(child.stdout[-6000:]);sys.stderr.buffer.write(child.stderr[-6000:]);raise SystemExit(child.returncode)
        sys.stdout.buffer.write(child.stdout)
    if hashlib.sha256(a.json_jar.read_bytes()).hexdigest()!='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':raise ValueError('Pinned org.json20240303 required')
    write('Android.kt','''package android.os
class Bundle() { private val values=mutableMapOf<String,Any?>();constructor(other:Bundle):this(){other.keySet().forEach{values[it]=other.get(it)}};fun keySet()=values.keys;fun get(k:String)=values[k];fun remove(k:String){values.remove(k)};fun putString(k:String,v:String?){values[k]=v};fun getString(k:String)=values[k] as String?;fun putInt(k:String,v:Int){values[k]=v};fun putLong(k:String,v:Long){values[k]=v};fun putBoolean(k:String,v:Boolean){values[k]=v};fun putFloat(k:String,v:Float){values[k]=v} }
object Process { const val SHELL_UID=2000;@JvmStatic fun myPid()=42;@JvmStatic fun myUid()=10001 }
''')
    write('Models.kt','''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex
class EmbeddedDuplexEnrollmentDraft(vararg values:Any)
class EmbeddedDuplexEnrollmentReview { val priorRevision=1L;val priorRecordSha256="x";val proposedMaxTokenTtlMs=1L;val reviewSha256="x" }
object EmbeddedDuplexSetupCredentials { @JvmStatic fun selectedHorizon(value:Long)=value;@JvmStatic fun developmentPolicy()=org.json.JSONObject() }
object EmbeddedDuplexCanonicalJson { @JvmStatic fun encode(value:Any)=value.toString() }
''')
    controls=read(src/'main/java'/package/'StereoBankControls.kt')
    write('Mask.kt','package '+package.replace('/','.')+'\n'+controls[controls.index('data class NeutralMaskPolicy'):controls.index('object StereoBankControls')])
    contract=debug/'EmbeddedDuplexOperatorContract.kt';read(contract)
    fixture=app/'host-tests/embedded_duplex/GuardedModeOperatorHost.kt';read(fixture)
    cp=os.pathsep.join(map(str,[classes,a.stdlib,a.json_jar]));java=a.java_home/'bin/java.exe'
    run('parser-compile',[java,'-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',classes,contract,*out.glob('*.kt'),fixture])
    run('parser-controls',[java,'-cp',cp,package.replace('/','.')+'.GuardedModeOperatorHostKt'])
    if not a.parser_only:
        # The source-owned extractor handles Java literals/comments. OS/Native
        # leaves in the fixture are explicit models; production bodies stay exact.
        spec=importlib.util.spec_from_file_location('originals_source_extractor',root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py')
        extraction=importlib.util.module_from_spec(spec);spec.loader.exec_module(extraction)
        host=read(production/'EmbeddedDuplexProcessHost.java')
        methods='\n'.join(extraction.extract_method(host,s) for s in ('    CompletableFuture<android.os.Bundle> guardedModeCall(', '    private JSONObject guardedModeObservation('))
        fixture_path=app/'host-tests/embedded_duplex/GuardedModeCallHost.java'
        write('GuardedModeCallHost.java',read(fixture_path).replace('// ACTUAL_GUARDED_METHODS',methods))
        run('host-compile',[a.java_home/'bin/javac.exe','--release','17','-cp',cp,'-d',classes,out/'GuardedModeCallHost.java'])
        run('host-controls',[java,'-cp',cp,package.replace('/','.')+'.embedded_duplex.GuardedModeCallHost'])
        write('Context.java','package android.content;public class Context {}')
        write('EmbeddedDuplexProcessHost.java','''package '''+package.replace('/','.')+'''.embedded_duplex;
class EmbeddedDuplexProcessHost extends GuardedModeCallHost {
 static final EmbeddedDuplexProcessHost CURRENT=new EmbeddedDuplexProcessHost();
 static EmbeddedDuplexProcessHost forApplication(android.content.Context context){return CURRENT;}
 static EmbeddedDuplexRunContext selectedContext(){return new EmbeddedDuplexRunContext();}
}
''')
        original=read(production/'EmbeddedDuplexOriginals.java')
        request=extraction.extract_method(original,'    public static JSONObject request(')
        write('EmbeddedDuplexOriginals.java','''package '''+package.replace('/','.')+'''.embedded_duplex;
import org.json.JSONObject;import android.os.Bundle;import '''+package.replace('/','.')+'''.embedded_duplex.GuardedModeCallHost.EmbeddedDuplexRunContext;
public class EmbeddedDuplexOriginals {
 static final String RUN_TRANSPORT="rusty.quest.embedded_duplex.operator_transport.v3",TRANSPORT="rusty.quest.embedded_duplex.operator_transport.v1";
 static void id(String value){if(!value.matches("[0-9a-f]{32}"))throw new IllegalArgumentException();}
'''+request+'\n}\n')
        service=read(production/'EmbeddedDuplexRuntimeService.java')
        bridge=extraction.extract_method(service,'    public static CompletableFuture<Bundle> guardedModeCall(')
        write('EmbeddedDuplexRuntimeService.java','''package '''+package.replace('/','.')+'''.embedded_duplex;
import android.os.Bundle;import android.content.Context;import org.json.JSONObject;import java.util.concurrent.*;import java.util.function.*;
public class EmbeddedDuplexRuntimeService {
 public static int effects,reads,acks;public static JSONObject lastCanonical;
'''+bridge+'''
 public static Bundle beginOriginal(Context c,String id,String method,String challenge,JSONObject canonical,Supplier<CompletableFuture<Bundle>> action)throws Exception {
  lastCanonical=canonical;effects++;action.get();return response(challenge,"pending");
 }
 public static CompletableFuture<String> concurrentPolicy(Context c,String challenge,long[] policy){return queued();}
 public static CompletableFuture<String> concurrentMask(Context c,String challenge,long[] mask){if(mask==null)reads++;return queued();}
 static CompletableFuture<String> queued(){CompletableFuture<String> result=new CompletableFuture<>();EmbeddedDuplexProcessHost.CURRENT.commands.execute(()->result.complete("{}"));return result;}
 public static Bundle acknowledgeOriginal(Context c,String id,String challenge,String request,String outcome,String consumer){acks++;return response(challenge,"compacted");}
 static Bundle response(String challenge,String status){Bundle b=new Bundle();b.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");b.putString("status",status);b.putString("challenge",challenge);return b;}
 public static void closeForTest()throws Exception{EmbeddedDuplexProcessHost.CURRENT.close();}
}
''')
        run('service-compile',[a.java_home/'bin/javac.exe','--release','17','-cp',cp,'-d',classes,out/'Context.java',out/'EmbeddedDuplexProcessHost.java',out/'EmbeddedDuplexOriginals.java',out/'EmbeddedDuplexRuntimeService.java'])
        provider=read(debug/'EmbeddedDuplexOperatorProvider.kt')
        helper=extraction.extract_method(provider,'  private fun guardedMode(')
        header=extraction.extract_method(provider,'  private fun Bundle.header(')
        fixture_path=app/'host-tests/embedded_duplex/GuardedModeProviderHost.kt'
        write('ProviderHost.kt',read(fixture_path).replace('// ACTUAL_PROVIDER_METHOD',helper).replace('// ACTUAL_PROVIDER_HEADER',header))
        run('provider-compile',[java,'-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',classes,contract,out/'ProviderHost.kt'])
        run('provider-controls',[java,'-cp',cp,package.replace('/','.')+'.ProviderHostKt'])
        marker=json.loads(read(src/'debug/assets/quest-debug/operator-capabilities.json'))
        if marker!=dict(schema='rusty.quest.embedded_duplex.guarded_mode.v1',provider_class=package.replace('/','.')+'.EmbeddedDuplexOperatorProvider',
                methods=['guarded_'+m for m in ('policy_update','blend_update','blend_read','operation_ack')],debug_only=True):raise ValueError('Debug packaged capability differs')
        import xml.etree.ElementTree as ET
        manifest=ET.fromstring(read(src/'debug/AndroidManifest.xml'));android='{http://schemas.android.com/apk/res/android}'
        declared=[v for v in manifest.findall('application/provider') if v.get(android+'name')=='.EmbeddedDuplexOperatorProvider']
        if len(declared)!=1 or declared[0].get(android+'permission')!='android.permission.DUMP':raise ValueError('Debug provider inclusion differs')
        ingress=provider[provider.index('  override fun call('):provider.index('  private fun dispatchCall(')]
        if 'BuildConfig.DEBUG' not in ingress or 'callerIsShell(Binder.getCallingUid())' not in ingress or 'withRunRequest(app,request.runId)' not in ingress:raise ValueError('Existing debug/shell/run ingress missing')
    unchanged=all(hashlib.sha256((root/path).read_bytes()).hexdigest()==digest for path,digest in pins.items())
    if not unchanged:raise ValueError('Source changed during controls')
    (out/'RESULT.json').write_text(json.dumps(dict(passed=True,checks=checks,sources=pins,source_unchanged=True,
        device_calls=0,apk_builds=0,limits=__doc__),indent=2))


if __name__=='__main__':main()
