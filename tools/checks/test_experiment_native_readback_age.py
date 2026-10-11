"""Production snapshot/server controls with modeled Android surfaces, no hardware."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tempfile
from test_experiment_ble_generation import STUBS

JSON_OBJECT = r'''package org.json; import java.util.*; public class JSONObject {
 public static final Object NULL=new Object(); private final Map<String,Object> fields=new LinkedHashMap<>(); private String raw;
 public JSONObject(){} public JSONObject(String s){raw=s;}
 public JSONObject put(String k,Object v){if(raw!=null)throw new IllegalStateException("raw immutable");fields.put(k,v);return this;}
 public String optString(String k,String d){Object v=fields.get(k);return v instanceof String?(String)v:d;}
 public int optInt(String k,int d){Object v=fields.get(k);return v instanceof Number?((Number)v).intValue():d;}
 static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
 static String value(Object v){if(v==null||v==NULL)return "null";if(v instanceof String)return quote((String)v);if(v instanceof Number||v instanceof Boolean||v instanceof JSONObject||v instanceof JSONArray)return v.toString();throw new IllegalArgumentException();}
 public String toString(){if(raw!=null)return raw;StringJoiner j=new StringJoiner(",","{","}");for(Map.Entry<String,Object> e:fields.entrySet())j.add(quote(e.getKey())+":"+value(e.getValue()));return j.toString();}
}'''
JSON_ARRAY = '''package org.json; import java.util.*; public class JSONArray {private final List<Object> values=new ArrayList<>();public JSONArray put(Object v){values.add(v);return this;}public String toString(){StringJoiner j=new StringJoiner(",","[","]");for(Object v:values)j.add(JSONObject.value(v));return j.toString();}}'''
SURFACE = '''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.*;import android.os.SystemClock;import java.lang.ref.WeakReference;
class NativeRendererSelfKioskApplication {static String foregroundForOwnApp(Object c){return "background";}}
class ControlPanelActivity {static String conditionAudioReadiness(String c){return "track-ready";}static String conditionBreathGuidanceReadiness(String c){return "pattern-ready";}}
class BreathCompositionPanelModule {
 static final ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();
 static final Object REMOTE_SINK=new Object(),remoteContext=new Object();static long lastRemoteStatusPollMs;static int polls;
 static class Shell {void requestStatus(ExperimentSessionAndroidShell.UiSink sink){polls++;}}static final Shell EXPERIMENT_SESSION_SHELL=new Shell();
 static RemoteStatusObserver remoteStatusObserver;
 OBSERVER
 static class RemotePending {long startedAtElapsedMs,serverGeneration;String browserId,operation;}static RemotePending remotePending;
 static void refreshExperimenterPolarProjection() {} // Other owner projection is modeled; actual full module compiles in panel gate.
 static WeakReference<BreathCompositionPanelModule> remotePanel=new WeakReference<>(null);static ExperimentSessionBleServer owner;
 static ExperimentSessionBleServer remoteServer(){return owner;}static String remoteKioskState(){return "pending";}
 BODY
}'''
HARNESS = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import android.bluetooth.*;import android.os.*;import org.json.*;import java.nio.charset.StandardCharsets;
public class NativeAgeHostTest {
 static int passed,dispatches;static void check(boolean x,String label){if(!x)throw new AssertionError(label);passed++;}
 static Object field(Object o,String n)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static ExperimentSessionPanelCoordinator.NativeReceipt receipt(long g,long r,long count){return new ExperimentSessionPanelCoordinator.NativeReceipt("",true,true,g,r,"active","running","condition-a",true,"persistence-pending",count,false,"unavailable",false,count,0,count,0,true,true,false,count,count,0,"",0,"");}
 static ExperimentSessionAndroidShell.SessionReadback read(long epoch,long g,long r,long count,long completed,String status)throws Exception{ExperimentSessionAndroidShell.SessionReadback result=new ExperimentSessionAndroidShell.SessionReadback(receipt(g,r,count),status,"ready","inventory-unavailable","ready","not-requested",0L,-1L,"","none","none","",epoch,"accepted","none");java.lang.reflect.Method method=ExperimentSessionAndroidShell.SessionReadback.class.getDeclaredMethod("afterStatusRead",long.class,String.class);method.setAccessible(true);return (ExperimentSessionAndroidShell.SessionReadback)method.invoke(result,completed,"none");}
 public static void main(String[] args)throws Exception{
  BreathCompositionPanelModule surface=new BreathCompositionPanelModule();ExperimentSessionPanelCoordinator c=surface.EXPERIMENT_SESSION_PANEL;
  SystemClock.nanos=1000001;c.acceptNativeReadback(Long.MAX_VALUE,receipt(1,2,10),1000000,SystemClock.nanos);
  android.app.Activity context=new android.app.Activity();ExperimentSessionBleServer owner=ExperimentSessionBleServer.process(context,new ExperimentSessionBleServer.Delegate(){public boolean openControlDefault(){return false;}public JSONObject snapshot()throws Exception{return surface.snapshot();}public void dispatch(ExperimentSessionBleServer.CommandRequest request){dispatches++;}});
  surface.owner=owner;owner.nativeOwnerStarted(1L,Long.MAX_VALUE);surface.snapshot();
  check(!surface.remoteStatusObserver.target.snapshot().observed,"prior attached/shared coordinator cache cannot qualify new carrier");
  ExperimentSessionAndroidShell.StatusObservationTarget first=surface.remoteStatusObserver.target;
  first.onExperimentSessionReadback(read(Long.MAX_VALUE,1,2,10,1000000,"accepted"));
  String original=surface.snapshot().toString();check(original.contains("\"a\":0"),"full producer rounds down");check(original.contains("\"g\":1,\"r\":2"),"private source joins current native product state");
  c.updatePolar(new ExperimentSessionPanelState.PolarProjection(ExperimentSessionPanelState.Bluetooth.ON,ExperimentSessionPanelState.Polar.CONNECTED,1,1,System.currentTimeMillis(),1,true,"fixture"));String polar=surface.snapshot().toString();check(polar.contains("\"bt\":\"ON\"")&&polar.contains("\"po\":\"CONNECTED\"")&&polar.contains("\"pf\":true"),"current app-context Polar remains independent of private native projection");
  SystemClock.nanos=2099999;SystemClock.millis=2000;String polled=surface.snapshot().toString();check(surface.polls==1,"producer requested asynchronous status");check(polled.contains("\"i\":\"1000000\"")&&polled.contains("\"a\":1"),"poll alone cannot refresh witness");
  first.onExperimentSessionReadback(read(1L,1,2,10,2000000,"accepted"));check(first.snapshot().readId==1000000,"wrong native epoch cannot refresh");
  first.onExperimentSessionReadback(read(Long.MAX_VALUE,1,2,10,2000000,"queued"));check(first.snapshot().readId==1000000,"queue ack cannot refresh");
  first.onExperimentSessionReadback(read(Long.MAX_VALUE,1,2,10,SystemClock.nanos+1,"accepted"));check(first.snapshot().readId==1000000,"future completion cannot refresh");
  c.acceptNativeReadback(Long.MAX_VALUE,receipt(1,3,10),2000000,SystemClock.nanos);check(first.snapshot().readId==1000000,"panel poll cannot claim carrier witness");
  BluetoothGattServer server=context.manager.servers.get(0);BluetoothDevice peer=new BluetoothDevice();server.callback.onConnectionStateChange(peer,0,2);BluetoothGattCharacteristic status=new BluetoothGattCharacteristic(java.util.UUID.fromString(ExperimentSessionBleProtocol.STATUS),2,1);Runnable refresh=(Runnable)field(owner,"statusRefresh");
  refresh.run();server.callback.onCharacteristicReadRequest(peer,1,0,status);String served=new String(server.lastBytes,StandardCharsets.UTF_8);String expected=surface.snapshot().put("v",1).put("m","gated").toString();check(expected.getBytes(StandardCharsets.UTF_8).length<=480,"normal bytes bounded");check(served.equals(expected),"actual server serves complete producer");System.out.println("NORMAL="+served);
  long safe=9007199254740991L;SystemClock.nanos=Long.MAX_VALUE;first.onExperimentSessionReadback(read(Long.MAX_VALUE,safe,safe,Long.MAX_VALUE,1000000000000000000L,"accepted"));String worst=surface.snapshot().put("v",1).put("m","gated").toString();System.out.println("WORST="+worst);refresh.run();server.callback.onCharacteristicReadRequest(peer,2,0,status);served=new String(server.lastBytes,StandardCharsets.UTF_8);check(worst.contains("1000000000000000000"),"exact int64 completion preserved");check(worst.getBytes(StandardCharsets.UTF_8).length>480,"worst full status exceeds bound");check(served.contains("UNAVAILABLE")&&server.lastBytes.length<=480,"oversized producer fails bounded unavailable");
  owner.nativeOwnerStarted(2L,Long.MAX_VALUE);surface.snapshot();ExperimentSessionAndroidShell.StatusObservationTarget second=surface.remoteStatusObserver.target;check(!second.snapshot().observed,"same epoch carrier replacement cannot reuse old cache");first.onExperimentSessionReadback(read(Long.MAX_VALUE,safe,safe,Long.MAX_VALUE,1000000000000000001L,"accepted"));check(first.snapshot()==null&&!second.snapshot().observed,"held old read cannot affect replacement projection");
  second.onExperimentSessionReadback(read(Long.MAX_VALUE,safe,safe,Long.MAX_VALUE,1000000000000000001L,"accepted"));check(surface.snapshot().toString().contains("1000000000000000001"),"replacement fresh query qualifies its own projection");owner.nativeOwnerEnded(2L,Long.MAX_VALUE);check(second.snapshot()==null,"exact end invalidates read-only source");check(dispatches==0,"status targets never dispatch command effects");System.out.println("Native age full producer/server controls PASS: "+passed);
 }
}'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo-root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument('--jdk', type=pathlib.Path, required=True)
    args = parser.parse_args()
    package = pathlib.Path('io/github/mesmerprism/rustyquest/native_renderer')
    production = args.repo_root / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / package
    source = (production / 'BreathCompositionPanelModule.java').read_bytes().decode('utf-8')
    start = source.index('@Override public JSONObject snapshot() throws Exception {')
    end = source.index('                return status;', start)
    end = source.index('\n            }', end) + len('\n            }')
    body = source[start:end].replace('@Override ', '', 1)
    observer_start = source.index('    private static final class RemoteStatusObserver {')
    observer_end = source.index('\n    }', observer_start) + len('\n    }')
    observer = source[observer_start:observer_end].replace('private static', 'static', 1)
    print('Actual full snapshot method SHA256=' + hashlib.sha256(source[start:end].encode()).hexdigest(), flush=True)
    stubs = dict(STUBS)
    stubs['org/json/JSONObject.java'] = JSON_OBJECT
    stubs['org/json/JSONArray.java'] = JSON_ARRAY
    stubs['android/os/SystemClock.java'] = 'package android.os;public class SystemClock {public static long nanos,millis;public static long elapsedRealtimeNanos(){return nanos;}public static long elapsedRealtime(){return millis;}}'
    with tempfile.TemporaryDirectory(prefix='quest-native-age-') as temporary:
        root = pathlib.Path(temporary)
        for name, content in stubs.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        for name, content in [('BreathCompositionPanelModule.java', SURFACE.replace('BODY', body).replace('OBSERVER', observer)), ('NativeAgeHostTest.java', HARNESS)]:
            path = root / package / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        sources = [str(path) for path in root.rglob('*.java')]
        sources += [str(production / name) for name in ('ExperimentSessionBleServer.java', 'ExperimentSessionBleProtocol.java', 'ExperimentSessionBleNamePolicy.java', 'ExperimentSessionPanelCoordinator.java', 'ExperimentSessionPanelState.java', 'ExperimentSessionAndroidShell.java')]
        sources.append(str(args.repo_root / 'apps/native-renderer-android/src/main/java' / package / 'NativeRendererWriterAcknowledgedExitPolicy.java'))
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-d', str(root / 'classes'), *sources], check=True, timeout=60)
        result = subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(root / 'classes'), 'io.github.mesmerprism.rustyquest.native_renderer.NativeAgeHostTest'], check=False, timeout=15, capture_output=True, text=True)
        print(result.stdout, end='')
        if result.returncode:
            print(result.stderr, end='')
            result.check_returncode()
        for line in result.stdout.splitlines():
            if line.startswith(('NORMAL=', 'WORST=')):
                raw = line.split('=', 1)[1]
                value = json.loads(raw)
                assert value['v'] == 1 and value['o']['s'] == 'observed'
                assert value['g'] == value['o']['g'] and value['r'] == value['o']['r']
                print(line.split('=', 1)[0] + ' validated JSON UTF8 bytes=' + str(len(raw.encode())))

if __name__ == '__main__':
    main()
