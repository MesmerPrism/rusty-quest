"""Execute actual measurement callbacks; Android/JNI sinks are inert counters.

Retirement/replacement happens deterministically inside decoding, after the
production dispatch admitted a callback but before acquisition publication.
"""
from pathlib import Path
import subprocess
import tempfile


def block(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    root = Path(__file__).resolve().parents[2]
    package = root / "apps/native-renderer-android/panel-modules/polar/src/main/java/io/github/mesmerprism/rustyquest/native_renderer"
    source = (package / "PolarSensorPanel.java").read_text(encoding="utf-8-sig")
    runtime = (package / "PolarSensorRuntime.java").read_text(encoding="utf-8-sig")
    methods = "\n".join(block(source, marker) for marker in (
        "private void dispatchCharacteristic(", "private void handleCharacteristic(",
        "private void markAutomaticConnectionEvidenceFromLiveCallback(", "public void onConnectionStateChange("))
    policy = block(runtime, "final class PolarAutoConnectionPolicy")
    diagnostics = block(source, "final class PolarAcquisitionDiagnostics")
    models = "\n".join(block(source, marker) for marker in ("private static final class AccSample", "private static final class PmdFrameMetric"))
    real_acc = "static class RealAccProtocol {\n" + "\n".join(block(source, marker) for marker in (
        "static PmdFrameMetric decodeAcc(", "static void validatePmdType(", "static PmdFrameMetric decodeCompressedAcc(",
        "static int readSignedBits(", "static int readInt16(", "static int readUInt16(", "static long readUInt64(",
        "static int unsigned(", "private static int clampInt(")) + "\n}"
    fixture = r'''
import java.util.*;
class BluetoothGatt {}
class BluetoothGattCharacteristic {
 final UUID uuid; BluetoothGattCharacteristic(UUID uuid){this.uuid=uuid;}
 UUID getUuid(){return uuid;}
}
class AcquisitionCallbackTest {
 static final UUID PMD_DATA=UUID.randomUUID(),HEART_RATE_MEASUREMENT=UUID.randomUUID(),PMD_CONTROL_POINT=UUID.randomUUID();
 final Object connectionIdentityLock=new Object(),countersLock=new Object();
 final PolarAcquisitionDiagnostics diagnostics=new PolarAcquisitionDiagnostics();
 BluetoothGatt gatt=new BluetoothGatt(); boolean connected=true,closing;
 long autoConnectionGeneration=1,automaticConnectionUpdatedAtUnixMs,automaticConnectionEvidenceGeneration;
 long malformedFrames,heartRateEvents,latestBpm,rrIntervals,controlEvents,accFrames,accSamples,lastAccFrameReceiptNs,ecgFrames,ecgSamples,lastEcgFrameReceiptNs;
 int activeAccSampleRateHz=200,activeEcgSampleRateHz=200,accSubmits,ecgSubmits,hrSubmits,rrSubmits,frames,appends,controls;
 boolean allCommitsLocked=true;
 static Runnable duringDecode; static boolean realAcc,failSubmit;
 static class Handler {void post(Runnable r){r.run();}} final Handler handler=new Handler();
 __MODELS__
 __REAL_ACC__
 static PmdFrameMetric sample(){return new PmdFrameMetric(100,100,1,Arrays.asList(new AccSample(0,0,1000)),Arrays.asList(12));}
 static class HeartRateReading {int bpm=60;List<Float> rrIntervalsMs=Arrays.asList(1000.0f);}
 static class ControlRecord {}
 static class PolarProtocol {
  static HeartRateReading decodeHeartRateMeasurement(byte[] v){duringDecode.run();return new HeartRateReading();}
  static ControlRecord parseControl(byte[] v){duringDecode.run();return new ControlRecord();}
  static int unsigned(byte v){return v&255;}
  static PmdFrameMetric decodeAcc(byte[] v){duringDecode.run();return realAcc?RealAccProtocol.decodeAcc(v):sample();}
  static PmdFrameMetric decodeEcg(byte[] v){duringDecode.run();return sample();}
 }
 boolean isCurrentConnectedGatt(BluetoothGatt b){return b==gatt&&connected&&!closing;}
 void updateCountersOnUiThread(){} void marker(String s){} void handleConnectionState(BluetoothGatt b,int status,int state){}
 void commit(){allCommitsLocked &= Thread.holdsLock(connectionIdentityLock);}
 void appendHrEvent(HeartRateReading r){commit();appends++;}
 void appendControlEvent(ControlRecord r){commit();appends++;}
 void handlePmdControlRecord(ControlRecord r){commit();controls++;}
 void appendAccEvent(PmdFrameMetric f){commit();appends++;}
 void appendEcgEvent(PmdFrameMetric f){commit();appends++;}
 void scheduleStreamingStatusWrite(){}
 long sampleTimeNs(long t,int rate,int i,int n){return t;}
 void nativeSubmitPolarHeartRateMeasurement(long t,int bpm){commit();hrSubmits++;}
 void nativeSubmitPolarRrMeasurement(long t,float rr){commit();rrSubmits++;}
 void nativeSubmitPolarPmdFrame(int m,long s,long h,long t,int rate,int n,long d){commit();frames++;}
 void nativeSubmitPolarAccMeasurement(long h,long s,long r,long f,int i,int n,long j,int x,int y,int z){commit();accSubmits++;if(failSubmit)throw new IllegalStateException("JNI sink failure");}
 void nativeSubmitPolarEcgMeasurement(long h,long s,long r,long f,int i,int n,long j,int v){commit();ecgSubmits++;}
 static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
 public static void main(String[] args) {
  for(int mode=1;mode<=4;mode++)for(int kind=0;kind<4;kind++) {
   final AcquisitionCallbackTest owner=new AcquisitionCallbackTest();final BluetoothGatt admitted=owner.gatt;final int replacement=mode;
   duringDecode=()->{synchronized(owner.connectionIdentityLock){
    if(replacement==1){owner.gatt=null;owner.connected=false;}
    if(replacement==2)owner.gatt=new BluetoothGatt();
    if(replacement==3)owner.autoConnectionGeneration++;
    if(replacement==4)owner.closing=true;
   }};
   owner.dispatchCharacteristic(admitted,new BluetoothGattCharacteristic(kind==2?HEART_RATE_MEASUREMENT:kind==3?PMD_CONTROL_POINT:PMD_DATA),new byte[]{(byte)(kind==0?2:0)});
   check(owner.accSubmits+owner.ecgSubmits+owner.hrSubmits+owner.rrSubmits+owner.frames+owner.appends+owner.controls==0,
    "retired/replaced callback reached native acquisition or recording sinks: mode="+mode+" kind="+kind+" ACC="+owner.accSubmits);
   check(owner.accFrames+owner.ecgFrames+owner.heartRateEvents+owner.controlEvents==0,"retired callback changed acquisition counters");
   if(kind==0){Map<String,Object> d=owner.diagnostics.snapshot(System.nanoTime());
    check(((Long)d.get("acc_callback_count"))==1&&((Long)d.get("decode_count"))==1&&((Long)d.get("jni_submit_count"))==0,"retired decode boundary remains observable without submission");}
  }
  for(int kind=0;kind<4;kind++) {
   AcquisitionCallbackTest owner=new AcquisitionCallbackTest();duringDecode=()->{};
   owner.dispatchCharacteristic(owner.gatt,new BluetoothGattCharacteristic(kind==2?HEART_RATE_MEASUREMENT:kind==3?PMD_CONTROL_POINT:PMD_DATA),new byte[]{(byte)(kind==0?2:0)});
   check(owner.allCommitsLocked,"acquisition commit must hold the same lock as retirement/replacement");
   check(owner.appends==1&&owner.malformedFrames==0,"current callback must record exactly once");
   if(kind==0){check(owner.accSubmits==1&&owner.frames==1&&owner.accFrames==1,"current ACC lost");
    Map<String,Object> d=owner.diagnostics.snapshot(System.nanoTime());
    check(((Long)d.get("acc_callback_count"))==1&&((Long)d.get("decode_count"))==1&&((Long)d.get("jni_submit_count"))==1,"callback/decode/submit boundaries distinct");
    check(d.get("callback_age_ms")!=null&&d.get("decode_duration_ms")!=null,"Java diagnostic ages measured");}
   if(kind==1)check(owner.ecgSubmits==1&&owner.frames==1&&owner.ecgFrames==1,"current ECG lost");
   if(kind==2)check(owner.hrSubmits==1&&owner.rrSubmits==1,"current HR/RR lost");
   if(kind==3)check(owner.controls==1,"current PMD control lost");
  }
  AcquisitionCallbackTest broken=new AcquisitionCallbackTest();duringDecode=()->{};realAcc=true;
  broken.dispatchCharacteristic(broken.gatt,new BluetoothGattCharacteristic(PMD_DATA),new byte[]{2});
  Map<String,Object> bad=broken.diagnostics.snapshot(System.nanoTime());
  check(((Long)bad.get("acc_callback_count"))==1&&((Long)bad.get("malformed_acc_count"))==1,"malformed ACC observed at entry");
  check(((Long)bad.get("decode_count"))==0&&((Long)bad.get("jni_submit_count"))==0&&broken.accSubmits==0,"malformed ACC never decoded or submitted");
  check(bad.get("decode_age_ms")==null&&bad.get("jni_submit_age_ms")==null,"missing Java ages unknown");
  AcquisitionCallbackTest validBytes=new AcquisitionCallbackTest();byte[] bytes=new byte[16];bytes[0]=2;bytes[9]=1;bytes[14]=(byte)232;bytes[15]=3;
  validBytes.dispatchCharacteristic(validBytes.gatt,new BluetoothGattCharacteristic(PMD_DATA),bytes);
  check(validBytes.accSubmits==1&&((Long)validBytes.diagnostics.snapshot(System.nanoTime()).get("decode_count"))==1,"real valid ACC codec reaches acquisition");
  realAcc=false;failSubmit=true;AcquisitionCallbackTest failedSink=new AcquisitionCallbackTest();
  failedSink.dispatchCharacteristic(failedSink.gatt,new BluetoothGattCharacteristic(PMD_DATA),new byte[]{2});
  Map<String,Object> failed=failedSink.diagnostics.snapshot(System.nanoTime());
  check(((Long)failed.get("decode_count"))==1&&((Long)failed.get("jni_submit_count"))==1&&((Long)failed.get("malformed_acc_count"))==0,"JNI failure is not malformed ACC");
  failSubmit=false;AcquisitionCallbackTest link=new AcquisitionCallbackTest();
  link.onConnectionStateChange(new BluetoothGatt(),8,0);
  check(link.diagnostics.snapshot(System.nanoTime()).get("gatt_state")==null,"retired GATT cannot overwrite current link observation");
  link.onConnectionStateChange(link.gatt,8,0);
  Map<String,Object> state=link.diagnostics.snapshot(System.nanoTime());
  check(((Integer)state.get("gatt_state"))==0&&((Integer)state.get("gatt_status"))==8&&((Long)state.get("disconnect_count"))==1,"actual current link callback observed");
  System.out.println("PASS actual acquisition callbacks: current ACC/ECG/HR/RR/control commits, decoder-boundary retirement/replacement/generation/closing denial, atomic sink publication");
 }
__METHODS__
}
__POLICY__
'''.replace("__MODELS__", models).replace("__REAL_ACC__", real_acc).replace("__METHODS__", methods).replace("__POLICY__", policy + "\n" + diagnostics)
    with tempfile.TemporaryDirectory(prefix="rq-polar-acquisition-") as temporary:
        output = Path(temporary)
        java = output / "AcquisitionCallbackTest.java"
        java.write_text(fixture, encoding="utf-8")
        subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", str(output), str(java)], check=True)
        subprocess.run(["java", "-cp", str(output), "AcquisitionCallbackTest"], check=True)


if __name__ == "__main__":
    main()
