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
        "private void markAutomaticConnectionEvidenceFromLiveCallback("))
    policy = block(runtime, "final class PolarAutoConnectionPolicy")
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
 BluetoothGatt gatt=new BluetoothGatt(); boolean connected=true,closing;
 long autoConnectionGeneration=1,automaticConnectionUpdatedAtUnixMs,automaticConnectionEvidenceGeneration;
 long malformedFrames,heartRateEvents,latestBpm,rrIntervals,controlEvents,accFrames,accSamples,lastAccFrameReceiptNs,ecgFrames,ecgSamples,lastEcgFrameReceiptNs;
 int activeAccSampleRateHz=200,activeEcgSampleRateHz=200,accSubmits,ecgSubmits,hrSubmits,rrSubmits,frames,appends,controls;
 boolean allCommitsLocked=true;
 static Runnable duringDecode;
 static class Handler {void post(Runnable r){r.run();}} final Handler handler=new Handler();
 static class AccSample { int xMg=0,yMg=0,zMg=1000; }
 static class PmdFrameMetric {long hostTimeNs=100,sensorTimestampNs=100;int sampleCount=1;List<AccSample> accSamples=Arrays.asList(new AccSample());List<Integer> ecgSamplesMicrovolts=Arrays.asList(12);}
 static class HeartRateReading {int bpm=60;List<Float> rrIntervalsMs=Arrays.asList(1000.0f);}
 static class ControlRecord {}
 static class PolarProtocol {
  static HeartRateReading decodeHeartRateMeasurement(byte[] v){duringDecode.run();return new HeartRateReading();}
  static ControlRecord parseControl(byte[] v){duringDecode.run();return new ControlRecord();}
  static int unsigned(byte v){return v&255;}
  static PmdFrameMetric decodeAcc(byte[] v){duringDecode.run();return new PmdFrameMetric();}
  static PmdFrameMetric decodeEcg(byte[] v){duringDecode.run();return new PmdFrameMetric();}
 }
 boolean isCurrentConnectedGatt(BluetoothGatt b){return b==gatt&&connected&&!closing;}
 void updateCountersOnUiThread(){} void marker(String s){}
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
 void nativeSubmitPolarAccMeasurement(long h,long s,long r,long f,int i,int n,long j,int x,int y,int z){commit();accSubmits++;}
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
  }
  for(int kind=0;kind<4;kind++) {
   AcquisitionCallbackTest owner=new AcquisitionCallbackTest();duringDecode=()->{};
   owner.dispatchCharacteristic(owner.gatt,new BluetoothGattCharacteristic(kind==2?HEART_RATE_MEASUREMENT:kind==3?PMD_CONTROL_POINT:PMD_DATA),new byte[]{(byte)(kind==0?2:0)});
   check(owner.allCommitsLocked,"acquisition commit must hold the same lock as retirement/replacement");
   check(owner.appends==1&&owner.malformedFrames==0,"current callback must record exactly once");
   if(kind==0)check(owner.accSubmits==1&&owner.frames==1&&owner.accFrames==1,"current ACC lost");
   if(kind==1)check(owner.ecgSubmits==1&&owner.frames==1&&owner.ecgFrames==1,"current ECG lost");
   if(kind==2)check(owner.hrSubmits==1&&owner.rrSubmits==1,"current HR/RR lost");
   if(kind==3)check(owner.controls==1,"current PMD control lost");
  }
  System.out.println("PASS actual acquisition callbacks: current ACC/ECG/HR/RR/control commits, decoder-boundary retirement/replacement/generation/closing denial, atomic sink publication");
 }
__METHODS__
}
__POLICY__
'''.replace("__METHODS__", methods).replace("__POLICY__", policy)
    with tempfile.TemporaryDirectory(prefix="rq-polar-acquisition-") as temporary:
        output = Path(temporary)
        java = output / "AcquisitionCallbackTest.java"
        java.write_text(fixture, encoding="utf-8")
        subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", str(output), str(java)], check=True)
        subprocess.run(["java", "-cp", str(output), "AcquisitionCallbackTest"], check=True)


if __name__ == "__main__":
    main()
