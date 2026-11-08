"""Actual cached-input parser/readback with explicit modeled Android/process leaves; no device calls."""
import argparse,json,os,pathlib,subprocess,sys,hashlib,importlib.util
sys.dont_write_bytecode=True
p=argparse.ArgumentParser(description=__doc__)
for name in ('output','java-home','stdlib','json-jar'):p.add_argument('--'+name,type=pathlib.Path,required=True)
p.add_argument('--compiler-classpath',required=True)
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=a.output.resolve()
out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
app=root/'apps/spatial-camera-panel-android';pkg='io/github/mesmerprism/rustyquest/spatial_camera_panel'
production=app/'app/src/main/java'/pkg/'embedded_duplex';debug=app/'app/src/debug/java'/pkg
qualified=[production/n for n in ('EmbeddedDuplexCachedInputs.java','EmbeddedDuplexProcessHost.java',
 'EmbeddedDuplexRuntimeService.java','EmbeddedDuplexEffectiveInputOwner.java','EmbeddedDuplexRunContext.java','EmbeddedDuplexCanonicalJson.java')]
qualified += [debug/'EmbeddedDuplexOperatorContract.kt',debug/'EmbeddedDuplexOperatorProvider.kt',
 app/'host-tests/embedded_duplex/CachedEffectiveInputsHost.java',app/'host-tests/embedded_duplex/CachedEffectiveInputsContractHost.kt',
 root/'tools/embedded_duplex_cached_inputs.py',root/'tools/checks/test_cached_effective_inputs.py',
 pathlib.Path(__file__),root/'tools/checks/Test-GuardedModeOperatorHost.py',root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py']
suppliers=[a.java_home/'bin/java.exe',a.java_home/'bin/javac.exe',a.stdlib,a.json_jar,*map(pathlib.Path,a.compiler_classpath.split(os.pathsep))]
def pin(path):return dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
before=[pin(path) for path in dict.fromkeys(qualified+suppliers)]
checks=[]
def run(name,args):
 r=subprocess.run(list(map(str,args)),capture_output=True,timeout=90)
 (out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
 checks.append(dict(name=name,argv=list(map(str,args)),exit=r.returncode))
 (out/'CHECKS.json').write_text(json.dumps(checks,indent=2))
 if r.returncode:print((r.stdout+r.stderr).decode(errors='replace')[-5000:]);raise SystemExit(r.returncode)
run('existing-parser',[sys.executable,'-B',root/'tools/checks/Test-GuardedModeOperatorHost.py',
 '--output',out/'parser','--java-home',a.java_home,'--stdlib',a.stdlib,'--json-jar',a.json_jar,
 '--compiler-classpath',a.compiler_classpath,'--parser-only'])
classes=out/'parser/classes';cp=os.pathsep.join(map(str,[classes,a.stdlib,a.json_jar]))
contract=app/'app/src/debug/java'/pkg/'EmbeddedDuplexOperatorContract.kt'
fixture=app/'host-tests/embedded_duplex/CachedEffectiveInputsContractHost.kt'
run('cached-parser-compile',[a.java_home/'bin/java.exe','-cp',a.compiler_classpath,
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
 '-classpath',cp,'-d',classes,contract,fixture])
run('cached-parser-controls',[a.java_home/'bin/java.exe','-cp',cp,pkg.replace('/','.')+'.CachedEffectiveInputsContractHostKt'])
extract_spec=importlib.util.spec_from_file_location('cached_extract',root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py')
extractor=importlib.util.module_from_spec(extract_spec);extract_spec.loader.exec_module(extractor)
host=production/'EmbeddedDuplexProcessHost.java';service=production/'EmbeddedDuplexRuntimeService.java'
host_method=extractor.extract_method(host.read_text(),'    static EmbeddedDuplexCachedInputs.Snapshot cachedEffectiveInputs(')
for forbidden in ('forApplication(', 'effectiveInputs(', 'commands.', 'Native.', 'Originals.', 'runSelection.'):
 if forbidden in host_method:raise ValueError('Pure cached getter contains an owner initialization/effect path')
owner=production/'EmbeddedDuplexEffectiveInputOwner.java'
observation=extractor.extract_method(owner.read_text(),'    static final class Observation')
(out/'EmbeddedDuplexEffectiveInputOwner.java').write_text('package '+pkg.replace('/','.')+'.embedded_duplex;\nimport org.json.JSONObject;final class EmbeddedDuplexEffectiveInputOwner {\n'+observation+'\n}',encoding='utf-8')
(out/'EmbeddedDuplexProcessHost.java').write_text('package '+pkg.replace('/','.')+'''.embedded_duplex;
class EmbeddedDuplexProcessHost {
 static volatile EmbeddedDuplexProcessHost instance;
 volatile EmbeddedDuplexRunContext runContext;
 volatile EmbeddedDuplexEffectiveInputOwner.Observation effectiveInputObservationSnapshot;
 static EmbeddedDuplexProcessHost forApplication(Object context){throw new AssertionError("peek initialized owner");}
'''+host_method+'\n}',encoding='utf-8')
sources=[production/'EmbeddedDuplexCachedInputs.java',production/'EmbeddedDuplexRunContext.java',
 production/'EmbeddedDuplexCanonicalJson.java',out/'EmbeddedDuplexEffectiveInputOwner.java',out/'EmbeddedDuplexProcessHost.java',
 app/'host-tests/embedded_duplex/CachedEffectiveInputsHost.java']
run('readback-compile',[a.java_home/'bin/javac.exe','--release','17','-cp',cp,'-d',classes,*sources])
run('readback-controls',[a.java_home/'bin/java.exe','-cp',cp,pkg.replace('/','.')+'.embedded_duplex.CachedEffectiveInputsHost',out/'actual-readback.json'])
provider=app/'app/src/debug/java'/pkg/'EmbeddedDuplexOperatorProvider.kt'
provider_text=provider.read_text();ingress=extractor.extract_method(provider_text,'  override fun call(')
if not (ingress.index('callerIsShell(Binder.getCallingUid())') < ingress.index('return cachedEffectiveInputs(request)') < ingress.index('withRunRequest(')):
 raise ValueError('Pure peek no longer precedes host/run initialization after shell validation')
bridge=extractor.extract_method(service.read_text(),'    public static String cachedEffectiveInputs(')
if 'EmbeddedDuplexCachedInputs.read(' not in bridge or 'forApplication(' in bridge:raise ValueError('Cached service bridge changed')
helper=extractor.extract_method(provider_text,'  private fun cachedEffectiveInputs(')
(out/'Base64.java').write_text('package android.util;public class Base64 {public static final int NO_WRAP=2;public static String encodeToString(byte[] b,int f){return java.util.Base64.getEncoder().encodeToString(b);}}')
# Compile the exact service bridge; public process/proc leaves are exercised by Android only.
(out/'EmbeddedDuplexRuntimeService.java').write_text('package '+pkg.replace('/','.')+'.embedded_duplex; public class EmbeddedDuplexRuntimeService {\n'+bridge+'\n}')
run('service-compile',[a.java_home/'bin/javac.exe','--release','17','-cp',cp,'-d',classes,out/'Base64.java',out/'EmbeddedDuplexRuntimeService.java'])
(out/'ProviderHelper.kt').write_text('package '+pkg.replace('/','.')+'\nimport android.os.Bundle\nimport '+pkg.replace('/','.')+'.embedded_duplex.EmbeddedDuplexRuntimeService\nclass ProviderHelper {\n'+helper+'\n}')
run('provider-helper-compile',[a.java_home/'bin/java.exe','-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
 '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',classes,contract,out/'ProviderHelper.kt'])
run('caller-controls',[sys.executable,'-B',root/'tools/checks/test_cached_effective_inputs.py','--response',out/'actual-readback.json'])
for record in before:
 if pin(pathlib.Path(record['path']))!=record:raise ValueError('Qualified source/tool supplier changed during controls')
(out/'RESULT.json').write_text(json.dumps(dict(passed=True,checks=checks,
 source_and_supplier_pins=before,source_unchanged=True,
 exact_extractions=dict(cache_getter=host_method,observation=observation,service_bridge=bridge,provider_helper=helper),
 device_calls=0,apk_builds=0,limits=__doc__),indent=2))
print(json.dumps(dict(passed=True,steps=len(checks),source_unchanged=True,device_calls=0,apk_builds=0)))
