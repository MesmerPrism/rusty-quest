"""Whole production Original/PreparedStart/archive owners; Android/SDK/Host leaves modeled."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, json, os, pathlib, subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True)
    p.add_argument('--json-jar', required=True)
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
    constants = write('OsConstants.java', 'package android.system; public class OsConstants {public static final int O_RDONLY=0,O_CLOEXEC=1;}')
    # Real FileOutputStream.sync and atomic moves remain production. Android directory
    # open/fsync are modeled here; explicit fault injection tests fail-before-compaction.
    os_source = write('Os.java', '''package android.system;
import java.io.*;import java.util.*;import java.nio.file.*;
public class Os {static Map<FileDescriptor,String> paths=new IdentityHashMap<>();public static boolean failSettled;
 public static void chmod(String p,int m){} public static FileDescriptor open(String p,int f,int m)throws Exception{
  if(!Files.exists(Path.of(p),LinkOption.NOFOLLOW_LINKS))throw new FileNotFoundException(p);
  FileDescriptor d=new FileDescriptor();paths.put(d,p);return d;}
 public static void fsync(FileDescriptor d)throws Exception{if(failSettled&&paths.get(d).endsWith(".settled.json"))throw new IOException("modeled settled fsync failure");}
 public static void close(FileDescriptor d){paths.remove(d);}}
''')
    names = ['EmbeddedDuplexOriginals', 'EmbeddedDuplexCanonicalJson', 'EmbeddedDuplexNativeOperationFailure',
             'EmbeddedDuplexPeerRoots', 'EmbeddedDuplexPreparedStart', 'EmbeddedDuplexPreparedArchive',
             'EmbeddedDuplexPreparedRenderer', 'EmbeddedDuplexFreshLifecycle']
    sources = [java / (n + '.java') for n in names]
    sources += [fixture / 'PreparedSettledCustodyHost.java', fixture / 'PreparedSettledCustodyPlatform.java']
    pinned = [pathlib.Path(__file__), *sources]
    def pins():
        return [{'path':str(f.relative_to(root)), 'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in pinned]
    before = pins()
    def run(name, argv):
        result = subprocess.run(argv, cwd=root, capture_output=True)
        (out / (name+'.stdout')).write_bytes(result.stdout)
        (out / (name+'.stderr')).write_bytes(result.stderr)
        return result.returncode
    compile_exit = run('javac', ['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', a.json_jar,
                                '-d', str(classes), *map(str,[context,bundle,constants,os_source,*sources])])
    code = compile_exit
    if code == 0:
        code = run('controls', ['java', '-cp', os.pathsep.join([str(classes),a.json_jar]),
                               'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedSettledCustodyHost', str(out/'files')])
    result = {'passed':code==0,'exit':code,'compile_exit':compile_exit,'source_pins':before,
              'source_unchanged':before==pins(),'limits':__doc__, 'device_calls':0}
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':code==0,'exit':code,'compile_exit':compile_exit}))
    return code


if __name__ == '__main__':
    sys.exit(main())
