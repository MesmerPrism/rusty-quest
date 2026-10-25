"""Focused actual Local admission and native no-media retirement bodies; modeled OS/JNI leaves."""
import argparse,hashlib,json,pathlib,subprocess,time

ROOT=pathlib.Path(__file__).resolve().parents[2]
def pin(p):
 p=pathlib.Path(p).resolve();b=p.read_bytes();return dict(path=str(p),bytes=len(b),sha256=hashlib.sha256(b).hexdigest())
def block(text,start):
 b=text.index(start);i=text.index('{',b)+1;depth=1
 while depth:
  depth+=(text[i]=='{')-(text[i]=='}');i+=1
 return text[b:i]
def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--json-jar',type=pathlib.Path);p.add_argument('--expect-red',action='store_true');p.add_argument('--java-only',action='store_true');a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
 jar=a.json_jar or next((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'))
 src=ROOT/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel';host=src/'embedded_duplex/EmbeddedDuplexProcessHost.java';text=host.read_text()
 fixture=ROOT/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/NoMediaLocalHost.java';raw=fixture.read_text()
 parts={'RETAINED':'private static final class RetainedNoMediaClose','SELECT':'CompletableFuture<String> selectLocalAfterTerminal(','REQUIRE':'static void requireCompletedNoMediaOriginal(','VERIFY':'private static void verifyNoMediaNative('}
 for key,start in parts.items():raw=raw.replace('/* ACTUAL_'+key+' */',block(text,start))
 binding=src/'embedded_duplex/EmbeddedDuplexRuntimeConfigurationBinding.java';bt=binding.read_text()
 for key,start in {'CONTEXT':'static final class Context','FIELDS':'private static void fields(','REQUIRE':'static void requireNative('}.items():raw=raw.replace('/* ACTUAL_CONFIGURATION_'+key+' */',block(bt,start))
 start='private String completedLocalConfig('
 raw=raw.replace('/* ACTUAL_HELPER */',block(text,start)if start in text else '')
 java=out/'NoMediaLocalHost.java';java.write_text(raw,encoding='utf-8',newline='\n');classes=out/'classes';classes.mkdir();records=[]
 def run(name,argv,expected=0):
  began=time.monotonic();c=subprocess.Popen(list(map(str,argv)),stdout=subprocess.PIPE,stderr=subprocess.PIPE,stdin=subprocess.DEVNULL,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0));stdout,stderr=c.communicate();(out/(name+'.stdout')).write_bytes(stdout);(out/(name+'.stderr')).write_bytes(stderr);records.append(dict(name=name,argv=list(map(str,argv)),pid=c.pid,exit=c.returncode,reaped=True,stdout_eof=True,stderr_eof=True,elapsed_s=time.monotonic()-began,stdout=pin(out/(name+'.stdout')),stderr=pin(out/(name+'.stderr'))));(out/'COMMANDS.json').write_text(json.dumps(records,indent=2));assert c.returncode==expected,(name,c.returncode,stderr[-2000:]);return stdout
 # BuildConfig is a declared selected-profile model; no Android app is built.
 build=out/'BuildConfig.java';build.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel;public class BuildConfig{public static final String EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2";}')
 run('javac',['javac','--release','17','-cp',jar,'-d',classes,java,build,src/'LocalRollbackRequestFence.java',src/'embedded_duplex/EmbeddedDuplexCanonicalJson.java'])
 stdout=run('java',['java','-cp',str(classes)+';'+str(jar),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.NoMediaLocalHost']+(['red']if a.expect_red else []));assert (b'EXPECTED_RED'in stdout)if a.expect_red else(b'PASS actual Java'in stdout)
 sources=[pin(x)for x in (pathlib.Path(__file__),fixture,host,binding,src/'LocalRollbackRequestFence.java',src/'embedded_duplex/EmbeddedDuplexCanonicalJson.java')]
 if not a.java_only:
  native=ROOT/'apps/spatial-camera-panel-android/native-receipt/src';runtime=native/'embedded_duplex/runtime_host.rs';t=runtime.read_text();nf=ROOT/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/NoMediaLocalHost.rs';rust=nf.read_text();ticket='struct CompletedNoMediaLocal'
  fallback='struct CompletedNoMediaLocal{config_sha256:String,app_generation:u64,app_record_sha256:String,native_generation:u64,configuration_binding:Value}'
  rust=rust.replace('/* ACTUAL_TICKET */',block(t,ticket)if ticket in t else fallback)
  rust=rust.replace('/* ACTUAL_CONSTRUCTOR_REQUIRE */',block(t,'fn require_constructor_binding('))
  helper='fn retain_no_media_local_retirement('
  rust=rust.replace('/* ACTUAL_RETAIN_TICKET */',block(t,helper)if helper in t else '')
  rust=rust.replace('/* ACTUAL_FINISH */',block(t,'fn finish_no_media_retirement('))
  local='fn select_local_after_terminal('
  if local in t:body=block(t,local)
  else:
   jni=block(t,'pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_selectLocalAfterTerminal(');b=jni.index('let state =');e=jni.index('\n    })();');body='fn select_local_after_terminal(expected: &str)->Result<String,String>{let sha=expected.to_owned();'+jni[b:e]+'}'
  rust=rust.replace('/* ACTUAL_LOCAL */',body).replace('ACTUAL_POLICY_PATH',(native/'local_rollback_policy.rs').as_posix());cargo=out/'native';cargo.mkdir();(cargo/'src').mkdir();(cargo/'src/main.rs').write_text(rust,encoding='utf-8');(cargo/'Cargo.toml').write_text('[package]\nname="no-media-local-host"\nversion="0.0.0"\nedition="2021"\n[dependencies]\nserde_json="1.0"\n[workspace]\n')
  exe=ROOT/'target/no-media-local-cargo/debug/no-media-local-host.exe';run('cargo',['cargo','build','--offline','--manifest-path',cargo/'Cargo.toml','--target-dir',ROOT/'target/no-media-local-cargo']);stdout=run('native',[exe]+(['red']if a.expect_red else []));assert (b'EXPECTED_RED'in stdout)if a.expect_red else(b'PASS actual Native'in stdout);sources.extend(pin(x)for x in (runtime,nf,native/'local_rollback_policy.rs'))
 result=dict(status='expected-red'if a.expect_red else'passed',commands=records,sources=sources,models=['Android app/platform/resource readbacks','JNI primitive and retired-aware capability registry; Host/route close callback','constructor expected DATA (actual Java and Rust validators run)','selected BuildConfig profile','GPU/capture physical facts; actual all-five policy is used'],actual=['retained no-media owner class','completed no-media proof guard and native receipt validator','actual Java constructor Context/requireNative and Rust require_constructor_binding','Java Local admission method','canonical JSON','Activity request fence','Native finish/no-media ticket retention/Local admission bodies','Native all-five physical policy'],apk_built=False,device_calls=0)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2));print(json.dumps(dict(status=result['status'],result=pin(out/'RESULT.json'))))
if __name__=='__main__':main()
