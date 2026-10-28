"""Exact source diagnostic/retirement bodies and existing real packet-pump checks; SDK leaves modeled."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--source', type=Path, help='Captured baseline source for an exact-fixture RED comparison')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = Path(args.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    main_root = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java'
    package = 'io/github/mesmerprism/rustyquest/media'
    source = args.source.resolve(strict=True) if args.source else main_root / package / 'PackedStereoMediaSourceRuntime.java'
    existing = root / 'crates/rusty-quest-media-stream-android/android/library/src/test/java' / package / 'MediaPrimitiveAdversarialMain.java'
    extractor = root / 'tools/checks/Test-EmbeddedDuplexOriginalsHost.py'
    spec = importlib.util.spec_from_file_location('source_methods', extractor)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    methods = '\n'.join(module.extract_method(source.read_text(), signature) for signature in
                        ('void writePacket(', 'void retireConnection(', 'private void logClosedSourceFailure('))
    if 'private static String failureFingerprint(' in source.read_text():
        methods += '\n' + module.extract_method(source.read_text(), 'private static String failureFingerprint(')
    generated = out / 'models' / package / 'PackedSourceRetirementDiagnosticHost.java'
    generated.parent.mkdir(parents=True)
    template = r'''
package io.github.mesmerprism.rustyquest.media;
import java.io.*; import java.net.Socket; import java.nio.charset.StandardCharsets; import java.util.concurrent.*;
public final class PackedSourceRetirementDiagnosticHost {
 static final class Runtime {
  final Object outputLock=new Object(); BoundedPacketPump packetPump;
  long connectionGeneration=1; boolean preparedDelivery,preparedDeliveryOpen;
  final ReconnectKeyframeGate reconnectGate=new ReconnectKeyframeGate();
  volatile String error=""; DetachedConnection retiredConnection; OutputStream output; Socket socket; Thread acceptThread;
  boolean sourceStreaming=true; PackedStereoCaptureOwner sharedCapture; PackedStereoEncoderWorker encoderWorker;
  long lastSourceFreshElapsedMs=1,lastPacketElapsedMs=1,sourceStreamingSinceMs=1;
  long encodedFrames=7,packetCount=6,keyframeCount=1,consumerAcceptCount=1;
  static final long SOURCE_FRESHNESS_TIMEOUT_MS=3000;
  Runtime(BoundedPacketPump pump){packetPump=pump;reconnectGate.connected(1);reconnectGate.configurationSent(1);reconnectGate.accept(1,true);}
  void writePacketTo(OutputStream target,long pts,int flags,PackedStereoStreamMetadata.PairRecord pair,byte[] payload,long elapsed,long unix)throws IOException{target.write(payload);}
  static String safeMessage(Throwable value){return value.getMessage()==null?"":value.getMessage();}
  PRODUCTION_METHODS
 }
 static final class DetachedConnection {final BoundedPacketPump pump;DetachedConnection(BoundedPacketPump p,OutputStream o,Socket s){pump=p;}}
 static final class BlockingOutput extends OutputStream {
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  public void write(int value)throws IOException{entered.countDown();try{release.await();}catch(InterruptedException x){Thread.currentThread().interrupt();throw new IOException(x);}}
  public void close(){release.countDown();}
 }
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void diagnosticBeforeRetirement(boolean writerFailure,boolean loggerFailure)throws Exception {
  android.util.Log.events.clear();android.util.Log.messages.clear();android.util.Log.throwOnLog=loggerFailure;
  BlockingOutput blocked=new BlockingOutput();
  OutputStream output=writerFailure?new OutputStream(){public void write(int value)throws IOException{throw new IOException("PRIVATE_WRAPPER_".repeat(100),new java.net.SocketException("PRIVATE_TICKET_PAYLOAD_MUST_NOT_APPEAR"));}}:blocked;
  BoundedPacketPump pump=new BoundedPacketPump(output,1,4,4,"diagnostic-pump");
  Runtime runtime=new Runtime(pump); CountDownLatch ready=new CountDownLatch(1),interrupted=new CountDownLatch(1);
  runtime.acceptThread=new Thread(()->{ready.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException expected){android.util.Log.events.add("interrupt");runtime.error="InterruptedException: ";}finally{interrupted.countDown();}},"modeled-accept-overwrite");
  runtime.acceptThread.start();
  try {
   require(ready.await(2,TimeUnit.SECONDS),"accept worker ready");require(pump.offer(new byte[]{1}),"first offer");
   if(writerFailure){long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(pump.failure()==null&&System.nanoTime()<until)Thread.yield();require(pump.failure()!=null,"actual writer failure retained");}
   else{require(blocked.entered.await(2,TimeUnit.SECONDS),"actual writer blocked");require(pump.offer(new byte[]{2}),"queue filled");}
   runtime.writePacket(1L,0,null,new byte[]{3},1L,1L);
   require(interrupted.await(2,TimeUnit.SECONDS),"unchanged retirement interrupts accept worker");
   require(runtime.packetPump==null&&runtime.retiredConnection.pump==pump,"same pump detached exactly");
   require("InterruptedException: ".equals(runtime.error),"modeled accept worker overwrites error after retirement");
   require(android.util.Log.events.indexOf("diagnostic")>=0&&android.util.Log.events.indexOf("diagnostic")<android.util.Log.events.indexOf("interrupt"),"original marker must precede interrupt overwrite");
   if(!loggerFailure){require(android.util.Log.messages.size()==1,"one finite marker");String marker=android.util.Log.messages.get(0);
    require(marker.contains("stage="+(writerFailure?"PACKET_PUMP_FAILED":"PACKET_OFFER_REJECTED")),"closed original classification");
    require(marker.contains("operation=PACKET_OFFER")&&marker.contains("cause=IO")&&marker.contains("code=SOURCE_EFFECT_UNCERTAIN"),"existing closed diagnostic shape");
    if(writerFailure){
     require(marker.contains("failure0Class=java.io.IOException")&&marker.contains("failure1Class=java.net.SocketException"),"actual retained wrapper and cause classes before retirement");
     require(marker.contains("failure0MessageChars=1600")&&marker.contains("failure0HashedBytes=1024")&&marker.contains("failure0MessageTruncated=true"),"bounded prefix fingerprint declared honestly");
     require(marker.contains("failure1MessageChars=38")&&marker.contains("failure1HashedBytes=38")&&marker.contains("failure1MessageSha256=5b9075f984f0b457f17e623b24c65a7f3a7e76050aed6af1348608104689fcad"),"complete original cause message fingerprint");
     require(marker.length()<4096,"bounded marker fits one Android log entry");
    }
    require(!marker.contains("PRIVATE_TICKET")&&!marker.contains("bounded packet pump full"),"no raw exception or payload logging");}
  } finally {android.util.Log.throwOnLog=false;pump.close();runtime.acceptThread.interrupt();runtime.acceptThread.join(2000);require(!runtime.acceptThread.isAlive()&&pump.isTerminated(),"test owners joined");}
 }
 public static void main(String[] args)throws Exception{diagnosticBeforeRetirement(false,false);diagnosticBeforeRetirement(true,false);diagnosticBeforeRetirement(false,true);System.out.println("3 source retirement diagnostic controls PASS; SDK/capture/log sink modeled, actual pump and extracted producer bodies");}
}
final class SystemClock {static long elapsedRealtime(){return 10;}}
final class MediaCodec {static final int BUFFER_FLAG_CODEC_CONFIG=2,BUFFER_FLAG_KEY_FRAME=1;}
final class PackedStereoCaptureOwner {boolean fresh(){return true;}int freshnessDiagnosticMask(){return 0;}int firstFailureOriginCode(){return 0;}int firstFailureCauseCode(){return 0;}int firstFailureDetailCode(){return 0;}}
final class PackedStereoEncoderWorker {Throwable failure(){return null;}}
'''
    generated.write_text(template.replace('PRODUCTION_METHODS', methods))
    log = out / 'models/android/util/Log.java'
    log.parent.mkdir(parents=True)
    log.write_text('package android.util; public final class Log {public static final java.util.List<String> events=java.util.Collections.synchronizedList(new java.util.ArrayList<>()),messages=java.util.Collections.synchronizedList(new java.util.ArrayList<>());public static boolean throwOnLog;public static int i(String tag,String message){events.add("diagnostic");if(throwOnLog)throw new IllegalStateException("modeled log sink failure");messages.add(message);return 0;}}')
    paths = [source, extractor, existing, Path(__file__), main_root / package / 'BoundedPacketPump.java', main_root / package / 'PackedSourceFailureCode.java', main_root / package / 'ReconnectKeyframeGate.java']
    def ref(path):
        path = Path(path)
        return dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    pins = [ref(path) for path in paths]
    rows = []
    def run(name, argv):
        with (out / (name + '.stdout')).open('wb') as stdout, (out / (name + '.stderr')).open('wb') as stderr:
            code = subprocess.run(argv, cwd=root, stdout=stdout, stderr=stderr, timeout=20).returncode
        rows.append(dict(name=name, exit=code, stdout=ref(out / (name + '.stdout')), stderr=ref(out / (name + '.stderr'))))
        return code
    classes = out / 'classes'
    classes.mkdir()
    code = run('compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.json_jar, '-sourcepath', str(out / 'models') + __import__('os').pathsep + str(main_root), '-d', str(classes), str(generated), str(log), str(existing)])
    if code == 0:
        code = run('existing-pump', ['java', '-cp', str(classes) + __import__('os').pathsep + args.json_jar, package.replace('/', '.') + '.MediaPrimitiveAdversarialMain'])
    if code == 0:
        code = run('diagnostic', ['java', '-cp', str(classes) + __import__('os').pathsep + args.json_jar, package.replace('/', '.') + '.PackedSourceRetirementDiagnosticHost'])
    unchanged = pins == [ref(path) for path in paths]
    result = dict(passed=code == 0 and unchanged, source_unchanged=unchanged, source_pins=pins, generated=ref(generated), checks=rows, limits=__doc__, device_calls=0)
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(ref(out / 'RESULT.json')))
    return code if code else (0 if unchanged else 1)


if __name__ == '__main__':
    sys.exit(main())
