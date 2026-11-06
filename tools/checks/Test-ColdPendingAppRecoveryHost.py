"""Actual cold-recovery operator and orchestration; Android/Native/owner token leaves modeled."""
import argparse, hashlib, json, os, pathlib, subprocess, sys
sys.dont_write_bytecode = True


def block(text, signature):
    start = text.index(signature); opening = text.index('{', start); end = opening + 1; depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}'); end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'output', 'java-home', 'kotlin-lib', 'json-jar'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--parser-only', action='store_true')
    args = parser.parse_args(); root = args.root.resolve(); out = args.output.resolve()
    if out.exists() or not out.is_relative_to(root / 'target'):
        raise ValueError('Fresh output below target required')
    out.mkdir(parents=True); classes = out / 'classes'
    app = root / 'apps/spatial-camera-panel-android/app/src'
    debug = app / 'debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
    main_dir = app / 'main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    pins = {}
    def read(path):
        data = path.read_bytes(); pins[str(path.relative_to(root))] = hashlib.sha256(data).hexdigest()
        return data.decode('utf-8')
    read(pathlib.Path(__file__).resolve())
    if hashlib.sha256(args.json_jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
        raise ValueError('Select pinned org.json 20240303')
    contract = debug / 'EmbeddedDuplexOperatorContract.kt'; read(contract)
    (out / 'Android.kt').write_text('''package android.os
class Bundle() { private val values=mutableMapOf<String,Any?>(); constructor(other:Bundle):this(){other.keySet().forEach { values[it]=other.get(it) }}; fun remove(k:String){values.remove(k)}; fun keySet()=values.keys; fun get(k:String)=values[k]; fun putString(k:String,v:String?){values[k]=v}; fun getString(k:String)=values[k] as String? }
object Process { const val SHELL_UID=2000; @JvmStatic fun myPid()=123; @JvmStatic fun myUid()=456 }
''')
    (out / 'Context.kt').write_text('package android.content\nclass Context { fun getPackageName()="fixture.app"; fun getNoBackupFilesDir()=java.io.File(".") }\n')
    (out / 'Models.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex
class EmbeddedDuplexEnrollmentDraft(vararg values:Any)
''')
    (out / 'Mask.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
class NeutralMaskPolicy(vararg values:Any) { fun words()=longArrayOf() }
''')
    (out / 'OperatorControls.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
fun main() {
  val challenge="a".repeat(32)
  val fields=mapOf("run_id" to "b".repeat(32),"original_id" to "c".repeat(32),"prior_run_id" to "d".repeat(32),"prior_original_id" to "e".repeat(32))
  val actual=EmbeddedDuplexOperatorContract.parseFields("recover_cold_pending",challenge,fields)
  check(actual.route.name=="RECOVER_COLD_PENDING" && actual.runId==fields["run_id"] && actual.originalId==fields["original_id"] && actual.priorOriginalId==fields["prior_original_id"])
  fun refuses(value:Map<String,Any?>) { check(runCatching { EmbeddedDuplexOperatorContract.parseFields("recover_cold_pending",challenge,value) }.isFailure) }
  fields.keys.forEach { refuses(fields-it); refuses(fields+(it to true)); refuses(fields+(it to "BAD")) }
  listOf(fields-mapOf("prior_run_id" to "x").keys-mapOf("prior_original_id" to "x").keys,
      fields+("prior_run_id" to null)+("prior_original_id" to null)).forEach {
    val source=EmbeddedDuplexOperatorContract.parseFields("recover_cold_pending",challenge,it)
    check(source.priorRunId==null && source.priorOriginalId==null)
  }
  refuses(fields+("prior_run_id" to null)); refuses(fields+("prior_original_id" to null))
  listOf("receipt","proof","cleanup","native_generation").forEach { refuses(fields+(it to true)) }
  check(EmbeddedDuplexOperatorContract.parseFields("run_status",challenge,emptyMap()).route.name=="RUN_STATUS")
  println("Actual closed operator parser: explicit legacy IDs or omitted source-derived prior pair; missing/type/extra-proof controls PASS")
}
''')
    libs = sorted(args.kotlin_lib.glob('*.jar')); stdlib = next(p for p in libs if p.name.startswith('kotlin-stdlib-'))
    java = args.java_home / 'bin/java.exe'; results = []
    def run(name, argv):
        child = subprocess.run(list(map(str, argv)), capture_output=True, timeout=60)
        (out / (name + '.stdout')).write_bytes(child.stdout); (out / (name + '.stderr')).write_bytes(child.stderr)
        results.append({'name':name,'argv':list(map(str,argv)),'exit_code':child.returncode})
        (out / 'processes.json').write_text(json.dumps(results,indent=2))
        if child.returncode:
            sys.stdout.buffer.write(child.stdout); sys.stderr.buffer.write(child.stderr); raise SystemExit(child.returncode)
        if name.endswith('controls'): sys.stdout.buffer.write(child.stdout)
    run('operator-compile', [java,'-cp',os.pathsep.join(map(str,libs)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',stdlib,'-d',classes,contract,*out.glob('*.kt')])
    run('operator-controls',[java,'-cp',os.pathsep.join(map(str,[classes,stdlib])),'io.github.mesmerprism.rustyquest.spatial_camera_panel.OperatorControlsKt'])
    if not args.parser_only:
        host = read(main_dir / 'EmbeddedDuplexProcessHost.java')
        start = host.index('    private volatile ColdPendingLocal coldPendingRecovery;')
        end = host.index('    CompletableFuture<String> closeRealPeerNoMedia(', start)
        methods = host[start:end] + '\n' + '\n'.join(block(host, signature) for signature in (
            '    private void installRunHandoff(', '    private JSONObject closedRunHandoff(',
            '    static boolean originalActorSettled(', '    static boolean originalExchangesJoined(',
            '    static void originalCompletion(', '    private String runStatusOnLane('))
        original_source = read(main_dir / 'EmbeddedDuplexOriginals.java')
        fixture = read(root / 'tools/checks/fixtures/ColdPendingAppRecoveryHost.java').replace('// ACTUAL_APP_METHODS', methods).replace(
            '// ACTUAL_REQUEST_METHOD', block(original_source,'    public static JSONObject request('))
        originals = fixture.index('class EmbeddedDuplexOriginals {')
        (out / 'ColdPendingAppRecoveryHost.java').write_text(fixture[:originals])
        (out / 'EmbeddedDuplexOriginals.java').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import android.content.Context; import android.os.Bundle; import org.json.JSONObject; import java.io.File; import java.util.*; import java.util.concurrent.*;
''' + fixture[originals:].replace('class EmbeddedDuplexOriginals {','public class EmbeddedDuplexOriginals {',1))
        service = read(main_dir / 'EmbeddedDuplexRuntimeService.java')
        (out / 'EmbeddedDuplexRuntimeService.java').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import android.content.Context; import android.os.Bundle; import org.json.JSONObject; import java.util.concurrent.*; import java.util.function.*;
public final class EmbeddedDuplexRuntimeService {
''' + '\n'.join(block(service,s) for s in ('    public static CompletableFuture<String> recoverColdPending(', '    public static Bundle beginOriginal(')) + '\n}\n')
        cp = os.pathsep.join(map(str,[classes,stdlib,args.json_jar]))
        run('app-compile',[args.java_home/'bin/javac.exe','--release','17','-cp',cp,'-d',classes,*out.glob('*.java')])
        run('app-controls',[java,'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ColdPendingAppRecoveryHost'])
        provider = read(debug / 'EmbeddedDuplexOperatorProvider.kt')
        (out / 'ProviderControls.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import android.os.Bundle
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeService
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOriginals
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ColdPendingAppRecoveryHost
class ProviderHarness {
''' + block(provider,'  private fun recoverColdPending(') + '\n' + block(provider,'  private fun Bundle.header(') + '''
  fun invoke(app:android.content.Context,snapshot:Bundle):Bundle = recoverColdPending(app,EmbeddedDuplexOperatorContract.parseCall("recover_cold_pending","a".repeat(32),snapshot),snapshot)
}
fun main() {
  val context=ColdPendingAppRecoveryHost.providerContext(); val provider=ProviderHarness()
  val fields=Bundle(); mapOf("run_id" to "b".repeat(32),"original_id" to "c".repeat(32),"prior_run_id" to "a".repeat(32),"prior_original_id" to "e".repeat(32)).forEach { (key,value)->fields.putString(key,value) }
  val dispatched=provider.invoke(context,fields)
  check(dispatched.getString("schema")==EmbeddedDuplexOperatorContract.RUN_SCHEMA && dispatched.getString("status")=="pending")
  ColdPendingAppRecoveryHost.pumpProvider()
  val queried=EmbeddedDuplexOriginals.queryForTest("c".repeat(32));check(queried.getString("status")=="succeeded")
  val inner=org.json.JSONObject(queried.getString("response"));check(inner.getString("schema")==EmbeddedDuplexOperatorContract.SCHEMA && inner.getString("status")=="observed" && inner.getString("challenge")=="a".repeat(32))
  check(org.json.JSONObject(inner.getString("receipt")).getString("\\$schema")=="rusty.quest.embedded_duplex.cold_pending_local_retirement.v1")
  provider.invoke(context,fields);ColdPendingAppRecoveryHost.pumpProvider();check(ColdPendingAppRecoveryHost.retirements()==1)
  for(explicitNull in listOf(false,true)) {
    val fresh=ColdPendingAppRecoveryHost.providerContext();val durable=Bundle();durable.putString("run_id","b".repeat(32));durable.putString("original_id","c".repeat(32))
    if(explicitNull){durable.putString("prior_run_id",null);durable.putString("prior_original_id",null)}
    provider.invoke(fresh,durable);ColdPendingAppRecoveryHost.pumpProvider()
    val request=org.json.JSONObject(EmbeddedDuplexOriginals.canonicalForTest()).getJSONObject("extras")
    check(request.keySet()==setOf("run_id"))
  }
  println("Actual provider helper + RuntimeService dispatch: v3 once-bound Future, nested v1 response, no replay PASS")
}
''')
        run('provider-compile',[java,'-cp',os.pathsep.join(map(str,libs)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',classes,contract,out/'ProviderControls.kt'])
        run('provider-controls',[java,'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.ProviderControlsKt'])
    unchanged = all(hashlib.sha256((root/path).read_bytes()).hexdigest()==digest for path,digest in pins.items())
    if not unchanged: raise ValueError('Source changed during focused controls')
    (out / 'RESULT.json').write_text(json.dumps({'passed':True,'checks':results,'sources':pins,
        'source_unchanged':unchanged,'scope':__doc__,'device_calls':0,'apk_builds':0,
        'limits':'Actual operator parser, provider recovery helper, RuntimeService forwarding/request producer and selected Host methods. Android storage/threads, Native retirement, ColdRecovery authority and Original persistence/custody leaves are models; full combined owner/JNI/Android integration is separate.'},indent=2))


if __name__ == '__main__': main()
