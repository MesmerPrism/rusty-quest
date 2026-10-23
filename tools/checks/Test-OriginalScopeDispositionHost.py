"""Actual Java archive/fence/counter seams; modeled Android and kernel leaves."""
import sys
sys.dont_write_bytecode=True
import argparse,json,pathlib,re,subprocess
def method(text,signature):
 start=text.index(signature);b=text.index('{',start);depth=0;quoted=False;escaped=False
 for end in range(b,len(text)):
  c=text[end]
  if quoted:
   if escaped:escaped=False
   elif c=='\\':escaped=True
   elif c=='"':quoted=False
  elif c=='"':quoted=True
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return text[start:end+1]
 raise AssertionError('unclosed production method')
def main():
 p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--group',choices=('committed','counter','automatic','null','faults','newer','all'),default='all');a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False)
 java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex';source=(java/'EmbeddedDuplexOriginals.java').read_text()
 bodies='\n'.join(method(source,s) for s in ('synchronized int unresolvedExceptCurrent()', 'synchronized int unresolvedForRetirement(', 'private int unrelatedUnresolved('))
 queries='\n'.join(method(source,s) for s in ('public synchronized Bundle query(', 'private Record find(', 'private Bundle envelope(', 'private File path(', 'private static void requireId('))
 constants='\n'.join(re.findall(r'^    (?:public|private) static final String (?:TRANSPORT|RECORD|CONTINUATION_RECORD) = .*?;',source,re.M))
 host_source=(java/'EmbeddedDuplexProcessHost.java').read_text();sig='static boolean priorOriginalScopeDisposed('
 host_body=method(host_source,sig) if sig in host_source else 'static boolean priorOriginalScopeDisposed(JSONObject row)throws Exception{return false;}'
 harness='''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.*;import java.nio.file.*;import java.util.*;import org.json.JSONObject;
class EmbeddedDuplexProcessHost{
 static EmbeddedDuplexProcessHost instance;EmbeddedDuplexProcessFence processFence;Context applicationContext;
 static class Context{String p;Context(String p){this.p=p;}String getPackageName(){return p;}}
 EmbeddedDuplexProcessHost(EmbeddedDuplexProcessFence f,String p){processFence=f;applicationContext=new Context(p);}
 HOST
}
class OriginalCounters{
 CONSTANTS
 static class Record{JSONObject row;boolean restored;Throwable persistenceFailure;Record(){}Record(JSONObject r){row=r;}String id(){return row.getString("original_id");}}
 final File directory;Map<String,Record> live=new HashMap<>();OriginalCounters(File d){directory=d;}
 static Record ACTIVE=new Record();static Record capture(){return ACTIVE;}Record continuationPredecessor(Record r){return null;}
 Record requireTerminalUnknown(String s){throw new AssertionError("no pair continuation modeled");}
 boolean earnedPriorCustody(JSONObject row,Record actual){return false;}
 JSONObject read(File f)throws Exception{return new JSONObject(Files.readString(f.toPath()));}
 int unrelated()throws Exception{return unrelatedUnresolved(null,null);}
 BODIES
 QUERIES
}
class Bundle{Map<String,String> values=new HashMap<>();void putString(String k,String v){values.put(k,v);}String getString(String k){return values.get(k);}}
'''.replace('HOST',host_body).replace('BODIES',bodies).replace('QUERIES',queries).replace('CONSTANTS',constants)
 generated=out/'OriginalCounters.java';generated.write_text(harness,encoding='utf-8')
 files=[str(java/n) for n in ('EmbeddedDuplexProcessFence.java','EmbeddedDuplexProcessIncarnation.java','EmbeddedDuplexAbandonment.java','EmbeddedDuplexAbandonmentRequest.java','EmbeddedDuplexHistoricalAbandonment.java','EmbeddedDuplexKnownBirthAbandonment.java','EmbeddedDuplexPostMediaAbandonment.java','EmbeddedDuplexCanonicalJson.java')]
 extra=java/'EmbeddedDuplexOriginalScopeDisposition.java'
 if extra.exists():files.append(str(extra))
 # Explicit host Android-Os leaves; real same-descriptor classic I/O and lock.
 files += list(map(str,(root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/android-system-host/android/system').glob('*.java')))
 files += [str(generated),str(root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexOriginalScopeHostTest.java')]
 files += [str(root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex'/n) for n in ('EmbeddedDuplexKnownBirthAbandonmentHostTest.java','EmbeddedDuplexHistoricalAbandonmentHostTest.java','EmbeddedDuplexPostMediaAbandonmentHostTest.java')]
 def run(name,argv):
  r=subprocess.run(argv,capture_output=True,cwd=root);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
  return r.returncode
 code=run('javac',['javac','--release','17','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*files]);assert code==0,'actual production Java compilation failed'
 groups=('committed','counter','automatic','null','faults','newer') if a.group=='all' else (a.group,);codes={g:run(g,['java','-cp',str(out)+';'+a.json_jar,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOriginalScopeHostTest',str(out/g),g]) for g in groups}
 (out/'RESULT.json').write_text(json.dumps(dict(groups=codes,device_calls=0,source='actual fence/archive classes and unresolved counter bodies; modeled kernel/platform leaves'),indent=2));print(json.dumps(codes));return 0 if all(c==0 for c in codes.values()) else 1
if __name__=='__main__':sys.exit(main())
