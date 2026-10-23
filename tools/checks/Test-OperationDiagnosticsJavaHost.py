"""Production Java diagnostic collector, registry and Original completion bodies; no APK/device work."""
import argparse, hashlib, json, os, pathlib, subprocess, shutil

def method(text, signature):
    start=text.index(signature);brace=text.index('{',start);depth=0;quoted=False;escape=False
    for end in range(brace,len(text)):
        char=text[end]
        if quoted:
            if escape:escape=False
            elif char=='\\':escape=True
            elif char=='"':quoted=False
        elif char=='"':quoted=True
        elif char=='{':depth+=1
        elif char=='}':
            depth-=1
            if depth==0:return text[start:end+1]
    raise AssertionError('production method incomplete')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--android-jar',required=True)
    p.add_argument('--retained-w',help='Optional private actual operation_query stdout; never committed as a fixture')
    a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve()
    assert out.is_relative_to(root/'target') and not out.exists();out.mkdir(parents=True)
    app=root/'apps/spatial-camera-panel-android';java=app/'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    media=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
    stubs=out/'stubs';stubs.mkdir();classes=out/'classes';classes.mkdir();api=out/'api-classes';api.mkdir();runs=[]
    package='io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex'
    def write(name,body):
        f=stubs/name;f.write_text(body,encoding='utf-8');return f
    context=write('Context.java','package android.content; public class Context {public Context getApplicationContext(){return this;}public java.io.File getNoBackupFilesDir(){return new java.io.File(System.getProperty("test.root"));}}')
    bundle=write('Bundle.java','package android.os;public class Bundle {public org.json.JSONObject value;public Bundle(org.json.JSONObject v){value=v;}}')
    native_json=json.dumps({'schema':'rusty.quest.operation_diagnostics_collection.v1','operations':[],'dropped_count':0,'retention':{}})
    native=write('EmbeddedDuplexNative.java','package '+package+'; class EmbeddedDuplexNative {static int reads,scopes;static boolean failBegin,failEnd;static String snapshot='+json.dumps(native_json)+';static void beginOperationDiagnostics(String operation,String original){if(failBegin)throw new LinkageError();scopes++;}static void endOperationDiagnostics(){scopes--;if(failEnd)throw new LinkageError();}static String operationDiagnostics(String id){reads++;return snapshot;} }')
    original=(java/'EmbeddedDuplexOriginals.java').read_text()
    # Complete/nativePending bodies are byte-exact production; only surrounding dependencies are modeled.
    bodies=method(original,'private void complete(')+'\n'+method(original,'private static JSONObject nativePending(')+'\n'+method(original,'static AutoCloseable enter(')
    original_stub=write('EmbeddedDuplexOriginals.java','''package PACKAGE;
import org.json.JSONObject;import android.os.Bundle;
class EmbeddedDuplexOriginals {
 static class Record {JSONObject row;long actualAppGeneration=73;Throwable retainedFailure,persistenceFailure;Record(JSONObject r){row=r;}String id(){return row.optString("original_id");}}
 private static final ThreadLocal<Record> CURRENT=new ThreadLocal<>();static Record capture(){return CURRENT.get();}
 static JSONObject bundleObject(Bundle b){return b.value;}
 static void validateResponse(String op,String challenge,JSONObject actual){} // mandatory response validator is outside this control
 static JSONObject outcome(JSONObject row){return row;}
 void persist(Record record){}
 BODIES
 static Record make(String id)throws Exception{return new Record(new JSONObject().put("operation","start").put("original_challenge","c".repeat(32)).put("original_id",id).put("request_sha256","b".repeat(64)).put("outcome_sha256",JSONObject.NULL).put("effect_state","entered").put("actor_binding",new JSONObject().put("native_generation",65)));}
 public static void main(String[] args)throws Exception{
  EmbeddedDuplexOperationDiagnostics.initialize(new java.io.File(args[0]));EmbeddedDuplexOriginals owner=new EmbeddedDuplexOriginals();
  Record scoped=make("8".repeat(32)),nested=make("9".repeat(32));
  try(AutoCloseable outer=enter(scoped)){
   if(capture()!=scoped||EmbeddedDuplexNative.scopes!=1)throw new AssertionError();
   try(AutoCloseable inner=enter(nested)){if(capture()!=nested||EmbeddedDuplexNative.scopes!=2)throw new AssertionError();}
   try(AutoCloseable cleared=enter(null)){if(capture()!=null||EmbeddedDuplexNative.scopes!=2)throw new AssertionError();}
   if(capture()!=scoped||EmbeddedDuplexNative.scopes!=1)throw new AssertionError();
  }
  EmbeddedDuplexNative.failBegin=true;try(AutoCloseable ignored=enter(scoped)){if(capture()!=scoped)throw new AssertionError();}EmbeddedDuplexNative.failBegin=false;
  EmbeddedDuplexNative.failEnd=true;try(AutoCloseable ignored=enter(scoped)){}EmbeddedDuplexNative.failEnd=false;
  if(capture()!=null||EmbeddedDuplexNative.scopes!=0)throw new AssertionError();
  JSONObject life=new JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4").put("action","start").put("status","pending").put("renewal_pending",false).put("last_failure_cause",JSONObject.NULL).put("last_failure","actual retained provider execution failed");
  Record pending=make("e".repeat(32));owner.complete(pending,new Bundle(new JSONObject().put("receipt",new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("peer_lifecycle",life).toString())),null);
  if(!pending.row.getString("state").equals("unknown")||!pending.row.getString("effect_state").equals("unknown")||pending.persistenceFailure!=null)throw new AssertionError();
  if(EmbeddedDuplexOperationDiagnostics.collection(pending.id()).getJSONArray("operations").getJSONObject(0).getJSONObject("primary").getJSONObject("safe_details").getBoolean("native_cause_present"))throw new AssertionError();
  Record thrown=make("f".repeat(32));java.io.IOException cause=new java.io.IOException("PRIVATE");owner.complete(thrown,null,new java.util.concurrent.CompletionException(cause));
  if(thrown.retainedFailure!=cause||!thrown.row.getString("state").equals("unknown")||thrown.persistenceFailure!=null)throw new AssertionError();
  if(args.length>1){
   JSONObject actual=new JSONObject(java.nio.file.Files.readString(java.nio.file.Path.of(args[1])));
   if(!actual.getString("operation").equals("start")||!actual.getString("state").equals("unknown")||!actual.isNull("failure"))throw new AssertionError();
   Record retained=new Record(new JSONObject(actual.toString()));
   JSONObject retainedLife=new JSONObject(actual.getJSONObject("response").getString("receipt")).getJSONObject("peer_lifecycle");
   retained.actualAppGeneration=retainedLife.getLong("app_process_generation");
   owner.complete(retained,new Bundle(actual.getJSONObject("response")),null);
   if(!retained.row.getString("state").equals("unknown")||!retained.row.getString("effect_state").equals("unknown")||!retained.row.isNull("failure")||retained.persistenceFailure!=null)throw new AssertionError();
   if(!EmbeddedDuplexOperationDiagnostics.collection(retained.id()).getJSONArray("operations").getJSONObject(0).getJSONObject("primary").getString("boundary").equals("native_pending"))throw new AssertionError();
   JSONObject correlation=EmbeddedDuplexOperationDiagnostics.collection(retained.id()).getJSONArray("operations").getJSONObject(0).getJSONObject("correlation");
   if(correlation.getLong("native_generation")!=actual.getJSONObject("actor_binding").getLong("native_generation")
      ||correlation.getLong("native_generation")!=retainedLife.getLong("native_executor_generation")
      ||correlation.getLong("app_generation")!=retainedLife.getLong("app_process_generation")
      ||!correlation.getString("request_sha256").equals(actual.getString("request_sha256"))
      ||!correlation.getString("original_id").equals(actual.getString("original_id")))throw new AssertionError();
   System.out.println("actual retained W response through production complete/nativePending: UNKNOWN conserved, null historical cause not invented, safe diagnostic boundary retrievable");
  }
  System.out.println("exact production Original complete/nativePending: retained W-shaped null cause remains UNKNOWN; Java original cause retrieved without acceptance change");
 }
}
class EmbeddedDuplexProcessHost {static void requirePreparedOriginal(EmbeddedDuplexOriginals.Record r,JSONObject v){}static void requireCompletedNoMediaOriginal(EmbeddedDuplexOriginals.Record r,JSONObject v){}}
class EmbeddedDuplexRuntimeConfigurationBinding {static final String WHOLE_V2="whole2";}
class EmbeddedDuplexConstructionClosure {static final String WHOLE="whole";}
class EmbeddedDuplexFreshLifecycle {static final String SCHEMA="fresh";static void require(JSONObject v){}}
'''.replace('PACKAGE',package).replace('BODIES',bodies))
    canonical=write('EmbeddedDuplexCanonicalJson.java','package '+package+';class EmbeddedDuplexCanonicalJson{static String sha256(org.json.JSONObject v){return "a".repeat(64);}}')
    def run(name,argv):
        r=subprocess.run([str(x) for x in argv],cwd=root,capture_output=True,timeout=60)
        (out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr);runs.append({'name':name,'exit':r.returncode})
        if r.returncode:raise RuntimeError(name+' failed: '+r.stderr.decode(errors='replace'))
    helper=java/'EmbeddedDuplexOperationDiagnostics.java';test=app/'host-tests/embedded_duplex/OperationDiagnosticsHost.java'
    sources=[context,bundle,native,original_stub,canonical,helper,java/'EmbeddedDuplexNativeOperationFailure.java',test]
    run('collector-javac',['javac','--release','17','-cp',a.json_jar,'-d',classes,*sources])
    run('android-api-javac',['javac','--release','17','-cp',a.android_jar+os.pathsep+str(classes),'-d',api,helper])
    cp=str(classes)+os.pathsep+a.json_jar;storage=out/'app-private';storage.mkdir()
    run('collector',['java','-cp',cp,package+'.OperationDiagnosticsHost',storage])
    fixture=out/'modeled-envelope';fixture.mkdir();shutil.copyfile(storage/'MODELED_PUBLIC_ENVELOPE.json',fixture/'ENVELOPE.json')
    fixture_contexts=fixture/'embedded-duplex-diagnostics';fixture_contexts.mkdir()
    envelope=json.loads((fixture/'ENVELOPE.json').read_text())
    for snapshot in envelope['java_observation']['operations']:
        for observation in [snapshot['primary'],*snapshot['secondary']]:
            descriptor=observation['private_context']
            if descriptor['context_id'] is not None:
                name=descriptor['context_id']+'.json';source=storage/'embedded-duplex-diagnostics'/name
                data=source.read_bytes();assert len(data)==descriptor['bytes'] and hashlib.sha256(data).hexdigest()==descriptor['sha256']
                shutil.copyfile(source,fixture_contexts/name)
    run('collector-restart',['java','-cp',cp,package+'.OperationDiagnosticsHost',storage,'restore'])
    def mutation(name,change):
        document=json.loads((fixture/'ENVELOPE.json').read_text())['java_observation'];change(document)
        directory=out/('corrupt-'+name);(directory/'embedded-duplex-diagnostics').mkdir(parents=True)
        (directory/'embedded-duplex-diagnostics/summaries.json').write_text(json.dumps(document))
        run('reject-'+name,['java','-cp',cp,package+'.OperationDiagnosticsHost',directory,'reject'])
    mutation('private-field',lambda d:d['operations'][0]['primary'].__setitem__('message','SECRET_PRIVATE/path'))
    mutation('private-detail',lambda d:d['operations'][0]['primary']['safe_details'].__setitem__('message','SECRET_PRIVATE/path'))
    mutation('secondary-bounds',lambda d:d['operations'][0]['secondary'].append(d['operations'][0]['secondary'][-1]))
    mutation('correlation-path',lambda d:d['operations'][0]['correlation'].__setitem__('original_id','../SECRET_PRIVATE'))
    mutation('context-path',lambda d:d['operations'][0]['primary']['private_context'].__setitem__('context_id','../SECRET_PRIVATE'))
    mutation('negative-count',lambda d:d.__setitem__('dropped_count',-1))
    run('collector-bounds',['java','-cp',cp,package+'.OperationDiagnosticsHost',storage,'bounds'])
    read_stress=out/'read-stress';read_stress.mkdir()
    run('read-bounds',['java','-cp',cp,package+'.OperationDiagnosticsHost',read_stress,'read-bounds'])
    retained_args=[];retained_ref=None
    if a.retained_w:
        retained_path=pathlib.Path(a.retained_w).resolve();raw=retained_path.read_text(encoding='utf-8')
        actual,end=json.JSONDecoder().raw_decode(raw.split('observation_json=',1)[1]);assert actual['operation']=='start' and actual['state']=='unknown' and actual['failure'] is None
        retained_json=out/'retained-w-original.private.json';retained_json.write_text(json.dumps(actual),encoding='utf-8');retained_args=[retained_json]
        retained_ref={'path':str(retained_path),'sha256':hashlib.sha256(retained_path.read_bytes()).hexdigest()}
    run('original-completion',['java','-cp',cp,package+'.EmbeddedDuplexOriginals',storage,*retained_args])
    media_names=['AndroidMediaOwnerRegistry','PackagedAndroidMediaOwnerRegistry','MediaVerificationDiagnostic','MediaProductBinding','MediaOwnerAction','MediaOwnerProvider','MediaProviderReadback','MediaRuntimeSnapshot','CancellationHandle']
    registry_test=root/'crates/rusty-quest-media-stream-android/android/library/src/test/java/io/github/mesmerprism/rustyquest/media/MediaVerificationDiagnosticHost.java'
    run('registry-javac',['javac','--release','8','-cp',a.json_jar,'-d',classes,*[media/(n+'.java') for n in media_names],registry_test])
    run('registry',['java','-cp',cp,'io.github.mesmerprism.rustyquest.media.MediaVerificationDiagnosticHost'])
    provider=(app/'app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexOperatorProvider.kt').read_text()
    read=provider[provider.index('if(method == EmbeddedDuplexOperatorContract.METHOD_OPERATION_DIAGNOSTICS)'):provider.index('if(method == EmbeddedDuplexOperatorContract.METHOD_PREPARATION_DIAGNOSTIC)')]
    assert 'operationDiagnosticOriginal(snapshot.keySet()' in read and 'EmbeddedDuplexOriginals' not in read
    contract=(app/'app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexOperatorContract.kt').read_text()
    assert 'operationDiagnosticOriginal(fields: Map<String, Any?>): String? = preparationDiagnosticOriginal(fields)' in contract
    read_helper=method(helper.read_text(),'public static String read(')
    assert not any(x in read_helper for x in ['forApplication(', 'ensureProcessFence(', 'runtimeCommand(', 'requestStop(', 'acknowledge('])
    owned_sources=[java/(n+'.java') for n in ['EmbeddedDuplexOriginals','EmbeddedDuplexPlatform','EmbeddedDuplexActivationGate','EmbeddedDuplexProcessHost','EmbeddedDuplexRecoveryCoordinator','EmbeddedDuplexResources']]
    owned_sources += [app/'app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'/n for n in ['EmbeddedDuplexOperatorProvider.kt','EmbeddedDuplexOperatorContract.kt']]
    pins=[{'path':str(x.relative_to(root)),'sha256':hashlib.sha256(x.read_bytes()).hexdigest()} for x in [pathlib.Path(__file__),helper,test,registry_test,*owned_sources,*[media/(n+'.java') for n in media_names]]]
    result={'schema':'rusty.quest.operation_diagnostics_java_host.v1','status':'passed','checks':runs,'sources':pins,'retained_actual_w':retained_ref,'device_calls':0,'limits':['Full collector+registry executed; modeled Android Context/native read/provider resources','Exact Original complete/nativePending bodies executed; surrounding mandatory response validation/persistence modeled','Java helper compiled against actual Android34 API; full application Kotlin/Android typecheck separate','Synthetic faults and optional actual retained W response do not establish physical W cause or cleanup; historical erased cause cannot be reconstructed']}
    (out/'RESULT.json').write_text(json.dumps(result,indent=2));print(json.dumps(result['checks']))
if __name__=='__main__':main()
