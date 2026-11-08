"""Actual sender trace helper and Runtime selection/binding bodies; Android leaves modeled."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def body(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def constructors(text, name):
    result, offset = [], 0
    marker = 'new ' + name + '('
    while (start := text.find(marker, offset)) >= 0:
        opening = text.index('(', start)
        depth, end = 1, opening + 1
        while depth:
            depth += (text[end] == '(') - (text[end] == ')')
            end += 1
        result.append(text[start:end])
        offset = end
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.root.resolve(), args.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    base = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
    helper, runtime = base / 'SenderTraceSections.java', base / 'PackedStereoMediaSourceRuntime.java'
    inputs = [helper, runtime, Path(__file__).resolve()]
    pins = [dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in inputs]
    (out / 'inputs.json').write_text(json.dumps(pins, indent=2))
    (out / helper.name).write_bytes(helper.read_bytes())
    text = runtime.read_text()
    constructor = body(text, '        Runtime(\n')
    sampler = body(text, '        private static boolean sampleSenderTraceDebuggable(')
    configure = body(text, '        public void configureAcceptedLanRoute(')
    field_start = text.index('        final boolean senderTraceDebuggable;')
    field = text[field_start:text.index(';', field_start) + 1]
    transports = constructors(text, 'PackedStereoLanTransport')
    pumps = constructors(text, 'BoundedPacketPump')
    assert len(transports) == 2 and len(pumps) == 1, 'Review every sender construction site when topology changes'
    extracts = dict(constructor=constructor, sampler=sampler, configure=configure,
                    selection_field=field, transport_calls=transports, pump_calls=pumps)
    (out / 'extracts.json').write_text(json.dumps({key: hashlib.sha256(str(value).encode()).hexdigest() for key, value in extracts.items()}, indent=2))
    (out / 'Trace.java').write_text('''package android.os;
import java.util.ArrayList;
import java.util.List;
public final class Trace {
 public static boolean enabled;public static int checks,begins,ends,checkFailure,beginFailure,endFailure;
 public static final List<String> names=new ArrayList<>();
 private static void fail(int kind){if(kind==1)throw new IllegalStateException("modeled Trace unavailable");if(kind==2)throw new LinkageError("modeled Trace linkage unavailable");}
 public static boolean isEnabled(){checks++;fail(checkFailure);return enabled;}
 public static void beginSection(String name){begins++;fail(beginFailure);names.add(name);}
 public static void endSection(){ends++;fail(endFailure);}
 public static void reset(){enabled=false;checks=begins=ends=checkFailure=beginFailure=endFailure=0;names.clear();}
}
''')
    (out / 'ApplicationInfo.java').write_text('''package android.content.pm;
public class ApplicationInfo {public static final int FLAG_DEBUGGABLE=2;public int flags;}
''')
    (out / 'Context.java').write_text('''package android.content;
import android.content.pm.ApplicationInfo;
public class Context {public ApplicationInfo info=new ApplicationInfo();public int reads,failure;
 public ApplicationInfo getApplicationInfo(){reads++;if(failure==1)throw new SecurityException("modeled metadata unavailable");if(failure==2)throw new LinkageError("modeled metadata linkage unavailable");return info;}}
''')
    (out / 'Bindings.java').write_text('''package io.github.mesmerprism.rustyquest.media;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;
final class PortProfile {int port=9000;}
final class PackedStereoStreamMetadata {static final class Layout{}}
final class PackedStereoLanTransport {final boolean debug;
 PackedStereoLanTransport(String sh,int sp,String bh,String dh,int dp){this(sh,sp,bh,dh,dp,false);}
 PackedStereoLanTransport(String sh,int sp,String bh,String dh,int dp,boolean debug){this.debug=debug;}}
final class BoundedPacketPump {final boolean debug;
 BoundedPacketPump(OutputStream o,int capacity,String name){this(o,capacity,name,false);}
 BoundedPacketPump(OutputStream o,int capacity,String name,boolean debug){this.debug=debug;}}
final class Bindings {
 static final AtomicLong NEXT_RUNTIME_ID=new AtomicLong(1);
 static final class Runtime {
  final String key,runtimeId,sessionId,sourceKind,sourceHost,leftCameraId,rightCameraId;
  final Context context;final PortProfile profile;final PackedStereoStreamMetadata.Layout layout;final boolean synthetic;
  final Object lifecycleLock=new Object();Object serverSocket;boolean stopRequested;PackedStereoLanTransport lanTransport;
''' + field + '\n' + constructor + '\n' + sampler + '''
  BoundedPacketPump pump(OutputStream clientOutput){return ''' + pumps[0] + ''';}
 }
 static final class Pipeline {
  final Runtime runtime;String routeBindHost,routeSinkHost;int routeSinkPort;
  Pipeline(Runtime runtime){this.runtime=runtime;}
''' + configure + '''
  PackedStereoLanTransport replacement(Runtime next){return ''' + transports[1] + ''';}
 }
 static Runtime runtime(Context c){return new Runtime("key","session","source",c,"local",new PortProfile(),new PackedStereoStreamMetadata.Layout(),"left","right",false);}
}
''')
    (out / 'Controls.java').write_text('''package io.github.mesmerprism.rustyquest.media;
import android.content.Context;
import android.os.Trace;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
public final class Controls {
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  Trace.reset();Trace.enabled=true;
  for(SenderTraceSections.Phase p:SenderTraceSections.Phase.values()){require(!SenderTraceSections.begin(false,p),"non-debuggable caller traced");SenderTraceSections.end(false);}
  require(Trace.checks==0&&Trace.begins==0&&Trace.ends==0,"non-debuggable selection touched Trace");
  Trace.reset();for(SenderTraceSections.Phase p:SenderTraceSections.Phase.values())require(!SenderTraceSections.begin(true,p),"inactive Trace admitted");
  require(Trace.checks==6&&Trace.begins==0,"inactive Trace was not checked safely");
  Trace.reset();Trace.enabled=true;
  for(SenderTraceSections.Phase p:SenderTraceSections.Phase.values()){boolean began=SenderTraceSections.begin(true,p);require(began,"selected enabled phase missing: "+p);try{}finally{SenderTraceSections.end(began);}}
  require(Trace.names.equals(Arrays.asList("rusty.media.sender.capacity_wait","rusty.media.sender.pump_write","rusty.media.sender.pump_flush","rusty.media.sender.source_read","rusty.media.sender.sink_write","rusty.media.sender.sink_flush"))&&Trace.begins==6&&Trace.ends==6,"fixed phase names or balanced sections differ");
  Trace.reset();Trace.enabled=true;boolean windowBegan=SenderTraceSections.begin(true,SenderTraceSections.Phase.LAN_WRITE);Trace.enabled=false;SenderTraceSections.end(windowBegan);
  require(Trace.checks==1&&Trace.ends==1,"ending an entered section rechecked the external tracing window");
  Trace.reset();Trace.enabled=true;require(!SenderTraceSections.begin(true,null)&&Trace.checks==0,"null phase reached Trace");
  for(int kind=1;kind<=2;kind++){
   Trace.reset();Trace.enabled=true;Trace.checkFailure=kind;require(!SenderTraceSections.begin(true,SenderTraceSections.Phase.PUMP_WRITE),"unavailable enabled query propagated");
   Trace.reset();Trace.enabled=true;Trace.beginFailure=kind;require(!SenderTraceSections.begin(true,SenderTraceSections.Phase.LAN_READ),"unavailable begin propagated");
   Trace.reset();Trace.endFailure=kind;SenderTraceSections.end(true);
  }
  Trace.reset();Trace.enabled=true;RuntimeException mediaFailure=new RuntimeException("original media failure");boolean began=SenderTraceSections.begin(true,SenderTraceSections.Phase.PUMP_WRITE);
  try{throw mediaFailure;}catch(RuntimeException failure){require(failure==mediaFailure,"trace replaced original media failure");}finally{SenderTraceSections.end(began);}
  require(Trace.begins==1&&Trace.ends==1,"throwing media body was not balanced");
  Context debug=new Context();debug.info.flags=2|128;Bindings.Runtime selected=Bindings.runtime(debug);
  require(selected.senderTraceDebuggable&&debug.reads==1,"actual Runtime did not sample debug flag once");
  debug.info.flags=0;require(selected.pump(new ByteArrayOutputStream()).debug&&debug.reads==1,"pump selection re-read or lost frozen debug flag");
  Bindings.Pipeline pipeline=new Bindings.Pipeline(selected);pipeline.configureAcceptedLanRoute("bind","sink",9001);require(selected.lanTransport.debug&&debug.reads==1,"normal LAN construction lost frozen debug flag");
  Context release=new Context();release.info.flags=128;Bindings.Runtime next=Bindings.runtime(release);release.info.flags=2;
  require(!next.senderTraceDebuggable&&!next.pump(new ByteArrayOutputStream()).debug&&!pipeline.replacement(next).debug&&release.reads==1,"replacement used stale/ambient selection or ignored release flag");
  Context debugNext=new Context();debugNext.info.flags=2;require(pipeline.replacement(Bindings.runtime(debugNext)).debug,"replacement LAN construction lost selected flag");
  require(!Bindings.runtime(null).senderTraceDebuggable,"null Context admitted tracing");
  Context absent=new Context();absent.info=null;require(!Bindings.runtime(absent).senderTraceDebuggable&&absent.reads==1,"null ApplicationInfo admitted tracing");
  for(int kind=1;kind<=2;kind++){Context broken=new Context();broken.failure=kind;require(!Bindings.runtime(broken).senderTraceDebuggable&&broken.reads==1,"ApplicationInfo failure admitted tracing or propagated");}
  System.out.println("Actual SenderTraceSections/Runtime controls passed: six fixed phases, debug plus external Trace selection, fail-inert API leaves, balanced media exceptions, immutable app-flag sampling, and all three production constructor sites; modeled Android/transport leaves, no device proof.");
 }
}
''')
    classes = out / 'classes'
    classes.mkdir()
    commands = [('compile', [str(args.java_home / 'bin/javac.exe'), '-Xlint:all', '-Werror', '-d', str(classes), *map(str, out.glob('*.java'))]), ('controls', [str(args.java_home / 'bin/java.exe'), '-cp', str(classes), 'io.github.mesmerprism.rustyquest.media.Controls'])]
    for name, command in commands:
        child = subprocess.run(command, capture_output=True, timeout=45)
        (out / (name + '.stdout')).write_bytes(child.stdout)
        (out / (name + '.stderr')).write_bytes(child.stderr)
        (out / (name + '.process.json')).write_text(json.dumps(dict(argv=command, exit_code=child.returncode), indent=2))
        if child.returncode:
            raise RuntimeError(name + ' failed:\n' + child.stdout.decode(errors='replace') + child.stderr.decode(errors='replace'))
        if name == 'controls':
            print(child.stdout.decode(), end='')
    assert pins == [dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in inputs], 'Source changed during host validation'
    (out / 'report.json').write_text(json.dumps(dict(inputs=pins, constructor_sites=3, fixed_phases=6, source_unchanged=True, modeled_android=True), indent=2))


if __name__ == '__main__':
    main()
