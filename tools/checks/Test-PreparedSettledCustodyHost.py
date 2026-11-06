"""Whole production Original/PreparedStart/archive owners and exact activeDirectory; Android/SDK/Host actor leaves modeled."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, importlib.util, json, os, pathlib, subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True)
    p.add_argument('--json-jar', required=True)
    p.add_argument('--java-home')
    a = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(a.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    java = root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    fixture = root / 'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
    generated = out / 'platform'; generated.mkdir()
    classes = out / 'classes'; classes.mkdir()
    def write(name, value):
        f = generated / name; f.write_text(value, encoding='utf-8'); return f
    context = write('Context.java', '''package android.content;
public class Context {private final java.io.File root; public Context(java.io.File r){root=r;}
 public Context getApplicationContext(){return this;} public java.io.File getNoBackupFilesDir(){return root;}}
''')
    bundle = write('Bundle.java', '''package android.os;
public class Bundle {private final java.util.Map<String,Object> data=new java.util.HashMap<>();
 public void putString(String k,String v){data.put(k,v);} public String getString(String k){return (String)data.get(k);}
 public void putBoolean(String k,boolean v){data.put(k,v);} public void putLong(String k,long v){data.put(k,v);}
 public java.util.Set<String> keySet(){return data.keySet();} public Object get(String k){return data.get(k);}}
''')
    constants = write('OsConstants.java', 'package android.system; public class OsConstants {public static final int O_RDONLY=0,O_CLOEXEC=1,O_NOFOLLOW=2;}')
    # Real FileOutputStream.sync and atomic moves remain production. Android directory
    # open/fsync are modeled here; explicit fault injection tests fail-before-compaction.
    os_source = write('Os.java', '''package android.system;
import java.io.*;import java.util.*;import java.nio.file.*;
public class Os {static Map<FileDescriptor,String> paths=new IdentityHashMap<>();public static boolean failSettled,failPhase;
 public static void chmod(String p,int m){} public static FileDescriptor open(String p,int f,int m)throws Exception{
  if(!Files.exists(Path.of(p),LinkOption.NOFOLLOW_LINKS))throw new FileNotFoundException(p);
  FileDescriptor d=new FileDescriptor();paths.put(d,p);return d;}
 public static void fsync(FileDescriptor d)throws Exception{if(failSettled&&paths.get(d).endsWith(".settled.json"))throw new IOException("modeled settled fsync failure");if(failPhase&&paths.get(d).contains("fresh-phase"))throw new IOException("modeled phase fsync failure");}
 public static void close(FileDescriptor d){paths.remove(d);}}
''')
    closure_source = java / 'EmbeddedDuplexFreshClosure.java'
    closure = closure_source.read_text()
    start = closure.index('    static File activeDirectory(')
    body = closure.index('{', start); depth = 1; end = body + 1
    while depth:
        if closure[end] == '{': depth += 1
        elif closure[end] == '}': depth -= 1
        end += 1
    active_directory = write('EmbeddedDuplexFreshClosure.java',
        'package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;'
        'import java.io.File;import java.nio.file.*;class EmbeddedDuplexFreshClosure{' + closure[start:end] + '}')
    helper = root / 'tools/checks/Test-PreparedSdkHost.py'
    loader=importlib.util.spec_from_file_location('owner_items',helper)
    item_module=importlib.util.module_from_spec(loader);loader.loader.exec_module(item_module)
    host_source=java / 'EmbeddedDuplexProcessHost.java'
    predicate=item_module.item(host_source.read_text(),'static void requireUnknownPeerRootCustodySpan(')
    unknown_root_predicate=write('UnknownRootSpanPredicate.java', '''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
/** Exact production Host predicate; retained host/resource/renderer observations are modeled leaves. */
final class UnknownRootSpanPredicate {
 enum Phase{READY,CLOSING}
 static final class PhysicalPlatform {boolean gated;boolean gatedPreparedCleanupCandidate(){return gated;}}
 static final class RetainedNoMediaClose {
  EmbeddedDuplexOriginals.Record original;boolean pendingUnknownRoot,stopIssued,controlClosed,pendingPrepared,resourcesClosed,displayDetached;
  PhysicalPlatform platform;Object resources,config;long nativeGeneration;
 }
 static final class EmbeddedDuplexProcessHost {
  RetainedNoMediaClose noMediaClose;final java.util.concurrent.atomic.AtomicReference<Phase>phase=new java.util.concurrent.atomic.AtomicReference<>(Phase.READY);
  boolean closeInFlight;PhysicalPlatform platform;Object resources;
 }
 static final EmbeddedDuplexProcessHost instance=new EmbeddedDuplexProcessHost();
 static final class EmbeddedDuplexRendererRetirement {static boolean terminal;static boolean currentTerminal(){return terminal;}}
 static JSONObject freshClosureIdentity(PhysicalPlatform p){return io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexProcessHost.ACTOR;}
 static void span(EmbeddedDuplexOriginals.Record current,boolean physicalTerminal){
  instance.phase.set(Phase.CLOSING);instance.closeInFlight=true;instance.platform=new PhysicalPlatform();instance.resources=new Object();
  RetainedNoMediaClose held=new RetainedNoMediaClose();instance.noMediaClose=held;held.original=current;
  held.pendingUnknownRoot=true;held.stopIssued=true;held.controlClosed=true;held.resourcesClosed=true;held.displayDetached=true;
  held.platform=instance.platform;held.resources=instance.resources;held.nativeGeneration=73;held.config=new Object();
  EmbeddedDuplexRendererRetirement.terminal=physicalTerminal;
 }
'''+predicate+'\n}')
    names = ['EmbeddedDuplexOriginals' , 'EmbeddedDuplexCanonicalJson', 'EmbeddedDuplexNativeOperationFailure',
             'EmbeddedDuplexPeerRoots', 'EmbeddedDuplexPreparedStart', 'EmbeddedDuplexPreparedArchive',
             'EmbeddedDuplexPreparedRenderer', 'EmbeddedDuplexFreshLifecycle']
    sources = [java / (n + '.java') for n in names]
    sources += [fixture / 'PreparedSettledCustodyHost.java', fixture / 'PreparedSettledCustodyPlatform.java']
    pinned = [pathlib.Path(__file__), closure_source, host_source, helper, *sources]
    def pins():
        return [{'path':str(f.relative_to(root)), 'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in pinned]
    before = pins()
    checks=[]
    def run(name, argv):
        result = subprocess.run(argv, cwd=root, capture_output=True, timeout=60)
        (out / (name+'.stdout')).write_bytes(result.stdout)
        (out / (name+'.stderr')).write_bytes(result.stderr)
        checks.append({'name':name,'argv':argv,'exit':result.returncode})
        return result.returncode
    compile_exit = run('javac', [str(pathlib.Path(a.java_home)/'bin/javac.exe') if a.java_home else 'javac', '--release', '17', '-encoding', 'UTF-8', '-cp', a.json_jar,
                                '-d', str(classes), *map(str,[context,bundle,constants,os_source,active_directory,unknown_root_predicate,*sources])])
    code = compile_exit
    if code == 0:
        code = run('controls', [str(pathlib.Path(a.java_home)/'bin/java.exe') if a.java_home else 'java', '-cp', os.pathsep.join([str(classes),a.json_jar]),
                               'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedSettledCustodyHost', str(out/'files')])
    result = {'passed':code==0,'exit':code,'compile_exit':compile_exit,'source_pins':before,
              'source_unchanged':before==pins(),'checks':checks,'json_jar':{'path':str(pathlib.Path(a.json_jar).resolve()),'sha256':hashlib.sha256(pathlib.Path(a.json_jar).read_bytes()).hexdigest()},'generated_source_pins':[{'path':str(f.relative_to(out)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in generated.glob('*.java')],'extracted_production_methods':['EmbeddedDuplexFreshClosure.activeDirectory','EmbeddedDuplexProcessHost.requireUnknownPeerRootCustodySpan'], 'modeled_leaves':['Android Context/Bundle/directory fsync','Host actor/retention/exchange observations','SDK codec/transport/renderer resource facts; real PreparedStart/Archive/Original execute'], 'limits':__doc__, 'device_calls':0}
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':code==0,'exit':code,'compile_exit':compile_exit}))
    return code


if __name__ == '__main__':
    sys.exit(main())
