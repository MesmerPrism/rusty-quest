"""Run the complete production BLE server/protocol against a deterministic Android boundary."""
import argparse
import pathlib
import subprocess
import tempfile

STUBS = {
    "android/Manifest.java": 'package android; public class Manifest {public static class permission {public static final String BLUETOOTH_CONNECT="connect",BLUETOOTH_ADVERTISE="advertise";}}',
    "android/content/SharedPreferences.java": '''package android.content; import java.util.*; public class SharedPreferences {
      public final Map<String,Boolean> values=new HashMap<>(); public int writes;
      public boolean getBoolean(String k,boolean d){return values.getOrDefault(k,d);} public Editor edit(){return new Editor();}
      public class Editor {public Editor putBoolean(String k,boolean v){values.put(k,v);writes++;return this;} public void apply(){}}}''',
    "android/content/Context.java": '''package android.content; public class Context {
      public static final int MODE_PRIVATE=0; public final SharedPreferences prefs=new SharedPreferences();
      public final android.bluetooth.BluetoothManager manager=new android.bluetooth.BluetoothManager();
      public Context getApplicationContext(){return this;} public SharedPreferences getSharedPreferences(String n,int m){return prefs;}
      public <T>T getSystemService(Class<T> c){return c.cast(manager);} public String getPackageName(){return "example.app";}
      public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
    "android/app/Activity.java": '''package android.app; public class Activity extends android.content.Context {
      public int checkSelfPermission(String p){return 0;} public void requestPermissions(String[] p,int n){}}''',
    "android/content/pm/PackageInfo.java": 'package android.content.pm; public class PackageInfo {public String[] requestedPermissions={android.Manifest.permission.BLUETOOTH_ADVERTISE};}',
    "android/content/pm/PackageManager.java": 'package android.content.pm; public class PackageManager {public static final int GET_PERMISSIONS=1,PERMISSION_GRANTED=0; public PackageInfo getPackageInfo(String n,int f){return new PackageInfo();}}',
    "android/os/Build.java": 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=31;} public static class VERSION_CODES {public static final int S=31;}}',
    "android/os/Looper.java": 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    "android/os/Handler.java": '''package android.os; import java.util.*; public class Handler {
      public static final List<Runnable> queued=new ArrayList<>(); public Handler(Looper l){}
      public boolean post(Runnable r){queued.add(r);return true;} public boolean postDelayed(Runnable r,long t){return true;}
      public void removeCallbacks(Runnable r){queued.removeIf(x->x==r);}
      public static void drain(){List<Runnable> q=new ArrayList<>(queued);queued.clear();for(Runnable r:q)r.run();}}''',
    "android/os/ParcelUuid.java": 'package android.os; public class ParcelUuid {public ParcelUuid(java.util.UUID u){}}',
    "android/os/SystemClock.java": 'package android.os; public class SystemClock {public static long elapsedRealtime(){return 1;}}',
    "android/util/Log.java": '''package android.util; import java.util.*; public class Log {public static final List<String> messages=new ArrayList<>();
      public static int i(String t,String m){messages.add(m);return 0;} public static int w(String t,String m){messages.add(m);return 0;}
      public static int w(String t,String m,Throwable x){messages.add(m);return 0;}}''',
    "android/bluetooth/BluetoothGatt.java": 'package android.bluetooth; public class BluetoothGatt {public static final int GATT_SUCCESS=0,GATT_INVALID_OFFSET=7,GATT_REQUEST_NOT_SUPPORTED=6,GATT_FAILURE=257;}',
    "android/bluetooth/BluetoothProfile.java": 'package android.bluetooth; public interface BluetoothProfile {int STATE_CONNECTED=2,STATE_DISCONNECTED=0;}',
    "android/bluetooth/BluetoothDevice.java": 'package android.bluetooth; public class BluetoothDevice {}',
    "android/bluetooth/BluetoothGattCharacteristic.java": '''package android.bluetooth; public class BluetoothGattCharacteristic {
      public static final int PROPERTY_WRITE=8,PERMISSION_WRITE=16,PROPERTY_READ=2,PERMISSION_READ=1; private final java.util.UUID uuid;
      public BluetoothGattCharacteristic(java.util.UUID u,int p,int a){uuid=u;} public java.util.UUID getUuid(){return uuid;}}''',
    "android/bluetooth/BluetoothGattService.java": '''package android.bluetooth; public class BluetoothGattService {
      public static final int SERVICE_TYPE_PRIMARY=0; private final java.util.UUID uuid;
      public BluetoothGattService(java.util.UUID u,int t){uuid=u;} public java.util.UUID getUuid(){return uuid;}
      public boolean addCharacteristic(BluetoothGattCharacteristic c){return true;}}''',
    "android/bluetooth/BluetoothGattServerCallback.java": '''package android.bluetooth; public class BluetoothGattServerCallback {
      public void onServiceAdded(int s,BluetoothGattService v){} public void onConnectionStateChange(BluetoothDevice d,int s,int n){}
      public void onCharacteristicReadRequest(BluetoothDevice d,int id,int offset,BluetoothGattCharacteristic c){}
      public void onCharacteristicWriteRequest(BluetoothDevice d,int id,BluetoothGattCharacteristic c,boolean p,boolean r,int o,byte[] v){}}''',
    "android/bluetooth/BluetoothGattServer.java": '''package android.bluetooth; public class BluetoothGattServer {
      public final BluetoothGattServerCallback callback; public BluetoothGattService service; public int responses;public boolean closed;public byte[] lastBytes;
      public BluetoothGattServer(BluetoothGattServerCallback c){callback=c;} public boolean addService(BluetoothGattService s){service=s;return true;}
      public void clearServices(){} public void close(){closed=true;} public boolean sendResponse(BluetoothDevice d,int id,int s,int offset,byte[] b){responses++;lastBytes=b==null?null:b.clone();return true;}}''',
    "android/bluetooth/BluetoothAdapter.java": '''package android.bluetooth; public class BluetoothAdapter {
      public final android.bluetooth.le.BluetoothLeAdvertiser advertiser=new android.bluetooth.le.BluetoothLeAdvertiser();
      public String getName(){return "Quest 2";} public boolean isEnabled(){return true;} public android.bluetooth.le.BluetoothLeAdvertiser getBluetoothLeAdvertiser(){return advertiser;}}''',
    "android/bluetooth/BluetoothManager.java": '''package android.bluetooth; import java.util.*; public class BluetoothManager {
      public final BluetoothAdapter adapter=new BluetoothAdapter(); public final List<BluetoothGattServer> servers=new ArrayList<>();
      public BluetoothAdapter getAdapter(){return adapter;} public BluetoothGattServer openGattServer(android.content.Context c,BluetoothGattServerCallback cb){BluetoothGattServer s=new BluetoothGattServer(cb);servers.add(s);return s;}}''',
    "android/bluetooth/le/AdvertiseCallback.java": 'package android.bluetooth.le; public class AdvertiseCallback {public void onStartSuccess(AdvertiseSettings s){}public void onStartFailure(int c){}}',
    "android/bluetooth/le/BluetoothLeAdvertiser.java": '''package android.bluetooth.le; import java.util.*; public class BluetoothLeAdvertiser {
      public final List<AdvertiseCallback> callbacks=new ArrayList<>(); public void startAdvertising(AdvertiseSettings s,AdvertiseData d,AdvertiseData scan,AdvertiseCallback c){callbacks.add(c);}
      public void stopAdvertising(AdvertiseCallback c){}}''',
    "android/bluetooth/le/AdvertiseSettings.java": '''package android.bluetooth.le; public class AdvertiseSettings {
      public static final int ADVERTISE_MODE_BALANCED=1,ADVERTISE_TX_POWER_MEDIUM=2; public static class Builder {
      public Builder setAdvertiseMode(int x){return this;}public Builder setConnectable(boolean x){return this;}public Builder setTimeout(int x){return this;}
      public Builder setTxPowerLevel(int x){return this;}public AdvertiseSettings build(){return new AdvertiseSettings();}}}''',
    "android/bluetooth/le/AdvertiseData.java": '''package android.bluetooth.le; public class AdvertiseData {public static class Builder {
      public Builder setIncludeDeviceName(boolean b){return this;}public Builder addServiceUuid(android.os.ParcelUuid u){return this;}
      public AdvertiseData build(){return new AdvertiseData();}}}''',
    "org/json/JSONObject.java": '''package org.json; import java.util.*; import java.util.regex.*; public class JSONObject {
      private final Map<String,Object> fields=new HashMap<>(); public JSONObject(){} public JSONObject(String text){
      Matcher m=Pattern.compile("\\\"([a-z]+)\\\"\\\\s*:\\\\s*(?:\\\"([^\\\"]*)\\\"|([0-9]+))").matcher(text);
      while(m.find())fields.put(m.group(1),m.group(2)!=null?m.group(2):Integer.parseInt(m.group(3)));}
      public JSONObject put(String k,Object v){fields.put(k,v);return this;}public String optString(String k,String d){Object v=fields.get(k);return v instanceof String?(String)v:d;}
      public int optInt(String k,int d){Object v=fields.get(k);return v instanceof Integer?(Integer)v:d;}public String toString(){return fields.toString();}}''',
}

HARNESS = '''package io.github.mesmerprism.rustyquest.native_renderer;
import android.bluetooth.*;import android.bluetooth.le.*;import android.os.*;import android.util.Log;import org.json.JSONObject;
public class GenerationHostTest {
 static boolean openDefault;static int snapshotMode;static int passed,dispatches;static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;}
 static Object field(Object o,String name)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 public static void main(String[]args)throws Exception{
  android.app.Activity context=new android.app.Activity();
  ExperimentSessionBleServer owner=ExperimentSessionBleServer.process(context,new ExperimentSessionBleServer.Delegate(){
   public boolean openControlDefault(){return openDefault;}public JSONObject snapshot()throws Exception{
    if(snapshotMode==1)throw new Exception("modeled-private-snapshot-error");
    if(snapshotMode==2)return null;
    return new JSONObject().put("p",snapshotMode==3?"x".repeat(500):"RUNNING");
   }public void dispatch(ExperimentSessionBleServer.CommandRequest c){dispatches++;}});
  check(!owner.openControl(),"unselected default stays gated");
  openDefault=true;check(owner.openControl(),"validated host default opens fresh prefs");
  check(context.prefs.writes==0,"default does not persist user choice");
  context.prefs.values.put("open-control",false);check(!owner.openControl(),"explicit stored gated choice wins over open default");
  openDefault=false;context.prefs.values.put("open-control",true);check(owner.openControl(),"explicit stored open choice wins over gated default");
  context.prefs.values.clear();check(!owner.openControl(),"absent optional policy stays gated");
  owner.startFromPanel(context);BluetoothGattServer a=context.manager.servers.get(0);BluetoothDevice peer=new BluetoothDevice();
  a.callback.onServiceAdded(0,a.service);BluetoothLeAdvertiser adv=context.manager.adapter.advertiser;
  check(adv.callbacks.size()==1,"current service advertises");AdvertiseCallback oldAd=adv.callbacks.get(0);
  a.callback.onConnectionStateChange(peer,0,2);check(((java.util.Map<?,?>)field(owner,"peers")).size()==1,"current peer added");
  owner.setOpenControl(true,context);BluetoothGattServer b=context.manager.servers.get(1);check(a.closed,"mode change closes old server");
  check(((java.util.Map<?,?>)field(owner,"peers")).isEmpty(),"mode change clears peer challenges");
  int writes=context.prefs.writes;owner.setOpenControl(true,context);
  check(context.manager.servers.size()==2&&!b.closed&&context.prefs.writes==writes,"same mode is complete no-op");
  a.callback.onServiceAdded(0,a.service);check(adv.callbacks.size()==1,"old service callback cannot advertise successor");
  a.callback.onConnectionStateChange(peer,0,2);check(((java.util.Map<?,?>)field(owner,"peers")).isEmpty(),"old connection callback cannot add successor peer");
  BluetoothGattCharacteristic status=new BluetoothGattCharacteristic(java.util.UUID.fromString(ExperimentSessionBleProtocol.STATUS),2,1);
  a.callback.onCharacteristicReadRequest(peer,1,0,status);check(a.responses==0&&b.responses==0,"old read cannot respond through successor");
  BluetoothGattCharacteristic command=new BluetoothGattCharacteristic(java.util.UUID.fromString(ExperimentSessionBleProtocol.COMMAND),8,16);
  byte[] bytes="{\\\"v\\\":1,\\\"id\\\":\\\"1111111111111111\\\",\\\"op\\\":\\\"ping\\\",\\\"condition\\\":\\\"\\\",\\\"nonce\\\":\\\"2222222222222222\\\",\\\"bias\\\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  a.callback.onCharacteristicWriteRequest(peer,2,command,false,true,0,bytes);check(b.responses==0,"old write cannot respond through successor");
  int logs=Log.messages.size();oldAd.onStartSuccess(new AdvertiseSettings());oldAd.onStartFailure(1);check(Log.messages.size()==logs,"retired advertise results ignored");
  b.callback.onServiceAdded(0,b.service);check(adv.callbacks.size()==2,"current successor advertises");
  b.callback.onConnectionStateChange(peer,0,2);a.callback.onConnectionStateChange(peer,0,0);
  check(((java.util.Map<?,?>)field(owner,"peers")).size()==1,"old disconnect cannot remove new peer");
  java.lang.reflect.Field bound=b.callback.getClass().getDeclaredField("server");bound.setAccessible(true);bound.set(b.callback,a);
  b.callback.onCharacteristicReadRequest(peer,3,0,status);check(b.responses==0,"current epoch with wrong own server rejected");bound.set(b.callback,b);
  b.callback.onCharacteristicReadRequest(peer,3,0,status);check(b.responses==1,"current read responds");
  b.callback.onCharacteristicWriteRequest(peer,4,command,false,true,0,bytes);check(b.responses==2,"current valid command write responds");
  owner.stop();Handler.drain();check(dispatches==0,"queued admitted command retired before app dispatch");
  b.callback.onCharacteristicReadRequest(peer,5,0,status);check(b.responses==2,"stopped callback cannot respond");
  owner.startFromPanel(context);BluetoothGattServer c=context.manager.servers.get(2);c.callback.onConnectionStateChange(peer,0,2);
  c.callback.onCharacteristicWriteRequest(peer,6,command,false,true,0,bytes);Handler.drain();check(dispatches==1,"current admitted command dispatches once");
  check(c.responses==1,"current generation keeps response on its own server");
  Runnable refresh=(Runnable)field(owner,"statusRefresh");
  refresh.run();c.callback.onCharacteristicReadRequest(peer,7,0,status);
  check(new String(c.lastBytes,java.nio.charset.StandardCharsets.UTF_8).contains("RUNNING"),"current source snapshot reaches actual read callback");
  int priorDispatches=dispatches;
  snapshotMode=1;refresh.run();c.callback.onCharacteristicReadRequest(peer,8,0,status);
  check(new String(c.lastBytes,java.nio.charset.StandardCharsets.UTF_8).equals("{\\\"v\\\":1,\\\"m\\\":\\\"gated\\\",\\\"f\\\":\\\"unknown\\\",\\\"p\\\":\\\"UNAVAILABLE\\\"}"),"snapshot exception replaces prior source status with closed unavailable readback");
  check(dispatches==priorDispatches,"source failure never dispatches app command");
  snapshotMode=0;refresh.run();c.callback.onCharacteristicReadRequest(peer,9,0,status);
  check(new String(c.lastBytes,java.nio.charset.StandardCharsets.UTF_8).contains("RUNNING"),"fresh successful source read replaces unavailable without effect replay");
  snapshotMode=2;refresh.run();c.callback.onCharacteristicReadRequest(peer,10,0,status);
  check(new String(c.lastBytes,java.nio.charset.StandardCharsets.UTF_8).contains("UNAVAILABLE"),"null snapshot cannot retain prior source status");
  snapshotMode=0;refresh.run();snapshotMode=3;refresh.run();c.callback.onCharacteristicReadRequest(peer,11,0,status);
  check(new String(c.lastBytes,java.nio.charset.StandardCharsets.UTF_8).contains("UNAVAILABLE"),"oversized snapshot keeps existing closed unavailable policy");
  snapshotMode=0;refresh.run();int priorResponses=c.responses;owner.stop();refresh.run();
  c.callback.onCharacteristicReadRequest(peer,12,0,status);
  check(c.responses==priorResponses,"stopped producer cannot serve a current source read");
  check(dispatches==priorDispatches,"status failure and recovery never replay effects");
  System.out.println("Production BLE generation controls PASS: "+passed);
 }
}'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo-root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument('--jdk', type=pathlib.Path, required=True)
    args = parser.parse_args()
    package = pathlib.Path('io/github/mesmerprism/rustyquest/native_renderer')
    production = args.repo_root / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / package
    with tempfile.TemporaryDirectory(prefix='quest-ble-generation-') as temporary:
        root = pathlib.Path(temporary)
        for name, source in STUBS.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
        harness = root / package / 'GenerationHostTest.java'
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS, encoding='utf-8')
        sources = [str(path) for path in root.rglob('*.java')]
        sources += [str(production / name) for name in ('ExperimentSessionBleServer.java', 'ExperimentSessionBleProtocol.java', 'ExperimentSessionBleNamePolicy.java')]
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-d', str(root / 'classes'), *sources], check=True, timeout=60)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(root / 'classes'), 'io.github.mesmerprism.rustyquest.native_renderer.GenerationHostTest'], check=True, timeout=15)


if __name__ == '__main__':
    main()
