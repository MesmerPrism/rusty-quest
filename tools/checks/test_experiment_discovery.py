"""Execute production discovery callbacks against inert Android Bluetooth handles."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_ble_generation import STUBS as BLE_STUBS
from test_experiment_gateway_dedupe import method

STUBS = dict(BLE_STUBS)
STUBS.pop('org/json/JSONObject.java')
STUBS['android/os/Handler.java'] = '''package android.os; import java.util.*; public class Handler {
 public static final List<Runnable> queued=new ArrayList<>(),delayed=new ArrayList<>(); public Handler(Looper l){}
 public boolean post(Runnable r){queued.add(r);return true;}public boolean postDelayed(Runnable r,long t){delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){queued.removeIf(x->x==r);delayed.removeIf(x->x==r);}
 public static void drain(){List<Runnable> q=new ArrayList<>(queued);queued.clear();for(Runnable r:q)r.run();}}
'''
STUBS['android/bluetooth/BluetoothManager.java']=STUBS['android/bluetooth/BluetoothManager.java'].replace('final BluetoothAdapter adapter','BluetoothAdapter adapter')
STUBS['android/bluetooth/BluetoothAdapter.java'] = '''package android.bluetooth; public class BluetoothAdapter {
 public boolean enabled=true;public final android.bluetooth.le.BluetoothLeAdvertiser advertiser=new android.bluetooth.le.BluetoothLeAdvertiser();
 public boolean isEnabled(){return enabled;}public android.bluetooth.le.BluetoothLeAdvertiser getBluetoothLeAdvertiser(){return advertiser;}}
'''
STUBS['android/bluetooth/BluetoothGattServerCallback.java'] = STUBS['android/bluetooth/BluetoothGattServerCallback.java'].replace('public void onServiceAdded', 'public void onMtuChanged(BluetoothDevice d,int mtu){} public void onServiceAdded')
STUBS['android/bluetooth/BluetoothGattServer.java'] = STUBS['android/bluetooth/BluetoothGattServer.java'].replace('public int responses;', 'public int responses,lastStatus,lastOffset,cancellations;').replace('public void clearServices(){}', 'public void cancelConnection(BluetoothDevice d){cancellations++;} public void clearServices(){}').replace('responses++;lastBytes=', 'if(responseFails)throw new IllegalStateException();responses++;lastStatus=s;lastOffset=offset;lastBytes=').replace('public boolean closed;', 'public boolean closed,responseFails;')
STUBS['android/bluetooth/le/AdvertiseData.java'] = '''package android.bluetooth.le; public class AdvertiseData {
 public boolean name;public int services;public static class Builder {final AdvertiseData data=new AdvertiseData();
 public Builder setIncludeDeviceName(boolean b){data.name=b;return this;} public Builder addServiceUuid(android.os.ParcelUuid u){data.services++;return this;}
 public AdvertiseData build(){return data;}}}
'''
STUBS['android/bluetooth/le/BluetoothLeAdvertiser.java'] = STUBS['android/bluetooth/le/BluetoothLeAdvertiser.java'].replace('public final List<AdvertiseCallback>', 'public AdvertiseData data,scan;public int stops; public final List<AdvertiseCallback>').replace('callbacks.add(c);', 'data=d;this.scan=scan;callbacks.add(c);').replace('stopAdvertising(AdvertiseCallback c){}','stopAdvertising(AdvertiseCallback c){stops++;}')

HARNESS = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import android.bluetooth.*;import android.bluetooth.le.*;import android.os.Handler;import org.json.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class DiscoveryHostTest {
 static int checks,snapshots;static String serial="TEST-HEADSET",endpoint="http://10.0.0.2:8766/v1/gateway";static boolean oversize,retired;static java.util.concurrent.CountDownLatch entered,release;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static JSONObject metadata()throws Exception {snapshots++;if(entered!=null){entered.countDown();release.await();}if(retired)throw new SecurityException();return new JSONObject().put("v",1).put("device","a".repeat(32)).put("serial",serial).put("epoch","123").put("native_epoch","7").put("mode","remote").put("gateways",new JSONArray().put(oversize?"x".repeat(600):endpoint));}
 static BluetoothGattCharacteristic characteristic(String uuid){return new BluetoothGattCharacteristic(UUID.fromString(uuid),2,1);}
 static byte[] read(BluetoothGattServer s,BluetoothDevice peer,String uuid,int offset){s.callback.onCharacteristicReadRequest(peer,1,offset,characteristic(uuid));return s.lastBytes;}
 static String all(BluetoothGattServer s,BluetoothDevice peer,String uuid){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();for(int i=0;i<1024;i+=22){byte[] part=read(s,peer,uuid,i);check(s.lastStatus==0,"valid offset");out.write(part,0,part.length);if(part.length<22)break;}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
 static BluetoothGattServer start(ExperimentSessionDiscoveryServer d,android.app.Activity c,long revision){d.reconcile(revision,true);BluetoothGattServer s=c.manager.servers.get(c.manager.servers.size()-1);s.callback.onServiceAdded(0,s.service);c.manager.adapter.advertiser.callbacks.get(c.manager.adapter.advertiser.callbacks.size()-1).onStartSuccess(new AdvertiseSettings());return s;}
 public static void main(String[] args)throws Exception {
  android.app.Activity c=new android.app.Activity();ExperimentSessionDiscoveryServer d=new ExperimentSessionDiscoveryServer(c,()->metadata());
  BluetoothGattServer s=start(d,c,1);BluetoothLeAdvertiser a=c.manager.adapter.advertiser;
  check("advertising".equals(d.readback().getString("state")),"Remote advertises");check(!a.data.name&&a.data.services==1&&a.scan==null,"service-only unnamed advertisement");
  BluetoothDevice p=new BluetoothDevice();s.callback.onConnectionStateChange(p,0,2);
  check(all(s,p,ExperimentSessionDiscoveryServer.SERIAL).equals(serial),"serial comes from app characteristic");
  String oldSerial=serial;serial="TEST-UPDATED";endpoint="http://10.0.0.3:8766/v1/gateway";
  List<Runnable> refresh=new ArrayList<>(Handler.delayed);for(Runnable r:refresh)r.run();
  JSONObject first=new JSONObject(all(s,p,ExperimentSessionDiscoveryServer.METADATA));check(first.getString("serial").equals(oldSerial),"serial and metadata freeze together across refresh");
  Handler.delayed.get(Handler.delayed.size()-1).run();check(s.cancellations==1,"completed metadata read disconnects discovery peer");
  BluetoothDevice next=new BluetoothDevice();check(new JSONObject(all(s,next,ExperimentSessionDiscoveryServer.METADATA)).getJSONArray("gateways").getString(0).equals(endpoint),"new peers get refreshed endpoint");
  byte[] bytes=all(s,p,ExperimentSessionDiscoveryServer.METADATA).getBytes(StandardCharsets.UTF_8);
  check(read(s,p,ExperimentSessionDiscoveryServer.METADATA,bytes.length).length==0&&s.lastStatus==0,"end offset empty success");
  read(s,p,ExperimentSessionDiscoveryServer.METADATA,-1);check(s.lastStatus==BluetoothGatt.GATT_INVALID_OFFSET,"negative offset denied");
  read(s,p,ExperimentSessionDiscoveryServer.METADATA,bytes.length+1);check(s.lastStatus==BluetoothGatt.GATT_INVALID_OFFSET,"past end denied");
  s.callback.onCharacteristicWriteRequest(p,2,characteristic(ExperimentSessionDiscoveryServer.SERIAL),false,true,0,new byte[]{1});check(s.lastStatus==BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,"writes denied");
  read(s,p,UUID.randomUUID().toString(),0);check(s.lastStatus==BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,"unknown characteristic denied");
  s.callback.onMtuChanged(p,517);check(read(s,p,ExperimentSessionDiscoveryServer.METADATA,0).length==bytes.length,"negotiated MTU supports full bounded record");
  for(int i=0;i<6;i++)s.callback.onConnectionStateChange(new BluetoothDevice(),0,2);
  read(s,new BluetoothDevice(),ExperimentSessionDiscoveryServer.SERIAL,0);check(s.lastStatus==BluetoothGatt.GATT_FAILURE&&s.cancellations>0,"peer snapshot capacity refuses overflow");
  List<Runnable> late=new ArrayList<>(Handler.delayed);AdvertiseCallback old=a.callbacks.get(0);int responses=s.responses,ads=a.callbacks.size(),before=snapshots;
  d.reconcile(2,false);d.permissionResult();d.requestPermissions(c);check(s.closed&&"inactive".equals(d.readback().getString("state")),"Direct retires server");
  s.callback.onServiceAdded(0,s.service);s.callback.onConnectionStateChange(new BluetoothDevice(),0,2);read(s,p,ExperimentSessionDiscoveryServer.SERIAL,0);old.onStartSuccess(new AdvertiseSettings());old.onStartFailure(3);for(Runnable r:late)r.run();Handler.drain();
  check(s.responses==responses&&a.callbacks.size()==ads&&snapshots==before&&"inactive".equals(d.readback().getString("state")),"late callbacks and recovery cannot revive Direct");
  BluetoothGattServer replacement=start(d,c,3);old.onStartFailure(4);check(!replacement.closed,"retired callback cannot close successor");
  c.manager.adapter.enabled=false;for(Runnable r:new ArrayList<>(Handler.delayed))r.run();
  check(replacement.closed&&"bluetooth-off".equals(d.readback().getString("reason")),"Bluetooth off after advertising retires live handles and readback");
  c.manager.adapter.enabled=true;d.close();
  oversize=true;int servers=c.manager.servers.size();d.reconcile(4,true);check(c.manager.servers.size()==servers&&"metadata-unavailable".equals(d.readback().getString("reason")),"oversize metadata refused before GATT");oversize=false;d.requestPermissions(c);check(c.manager.servers.size()==servers+1,"reopened Remote retries newly available identity");d.close();
  BluetoothAdapter adapter=c.manager.adapter;c.manager.adapter=null;d.reconcile(40,true);check("bluetooth-unavailable".equals(d.readback().getString("reason")),"missing Bluetooth adapter visible");d.close();c.manager.adapter=adapter;
  c.manager.adapter.enabled=false;d.reconcile(5,true);check("bluetooth-off".equals(d.readback().getString("reason")),"Bluetooth off visible");d.close();c.manager.adapter.enabled=true;
  c.permission=1;d.reconcile(6,true);check("permission-required".equals(d.readback().getString("reason")),"denied permission visible");d.requestPermissions(c);d.requestPermissions(c);check(c.requests==1,"normal Activity permission request");d.permissionResult();check("permission-required".equals(d.readback().getString("reason")),"denial cannot advertise");
  c.permission=0;d.permissionResult();BluetoothGattServer granted=c.manager.servers.get(c.manager.servers.size()-1);check(!granted.closed,"grant retries current Remote");
  late=new ArrayList<>(Handler.delayed);d.close();for(Runnable r:late)r.run();check(granted.closed&&"inactive".equals(d.readback().getString("state")),"close cancels pending work");
  servers=c.manager.servers.size();BluetoothGattServer failing=start(d,c,7);AdvertiseCallback fail=a.callbacks.get(a.callbacks.size()-1);fail.onStartFailure(1);
  for(int i=0;i<10;i++){List<Runnable> work=new ArrayList<>(Handler.delayed);Handler.delayed.clear();for(Runnable r:work)r.run();}check(c.manager.servers.size()<=servers+4,"bounded service/recovery attempts");d.close();
  check(Handler.delayed.isEmpty(),"close removes refresh/recovery/disconnect work");
  BluetoothGattServer blocked=start(d,c,8);Runnable oldRefresh=Handler.delayed.get(0);entered=new java.util.concurrent.CountDownLatch(1);release=new java.util.concurrent.CountDownLatch(1);
  Thread worker=new Thread(oldRefresh);worker.start();check(entered.await(1,java.util.concurrent.TimeUnit.SECONDS),"production source refresh entered");
  d.close();int afterClose=c.manager.servers.size();release.countDown();worker.join(1000);entered=null;release=null;
  check(!worker.isAlive()&&blocked.closed&&c.manager.servers.size()==afterClose&&Handler.delayed.isEmpty(),"retired in-flight source cannot requeue or start successor");
  BluetoothGattServer responseFailure=start(d,c,9);BluetoothDevice broken=new BluetoothDevice();responseFailure.callback.onMtuChanged(broken,517);responseFailure.responseFails=true;
  read(responseFailure,broken,ExperimentSessionDiscoveryServer.METADATA,0);d.close();
  check(responseFailure.closed&&Handler.delayed.isEmpty(),"response failure cannot enqueue an orphan disconnect after retirement");
  System.out.println("PASS "+checks+" production discovery callback, serial/offset/MTU/snapshot, Remote/Direct/generation, permissions and bounded recovery checks; inert Android");
 }
}
'''


PANEL_HARNESS = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class ExperimentSessionCommandGateway {
 static ExperimentSessionCommandGateway instance=new ExperimentSessionCommandGateway();
 JSONObject network=new JSONObject().put("serial","TEST-HEADSET").put("state","trusted-lan-listening")
  .put("discovery",new JSONObject().put("state","advertising").put("reason","ready")).put("key","never-display-this");
 static ExperimentSessionCommandGateway existing(){return instance;}JSONObject productPanelReadback(){return network;}
 String localAddresses(){return "http://10.0.0.2:8766/v1/gateway";}
}
final class ExperimentSessionPanelViewPolicy {
 static class Projection{String statusLine="session unchanged";}static Projection project(Object s){return new Projection();}
}
public class DiscoveryPanelHostTest {
 static class Text{String value;void setText(String s){value=s;}}static class Panel{Object snapshot(){return null;}}
 Text controlModeSessionReadback=new Text(),controlModeNetworkReadback=new Text();Panel EXPERIMENT_SESSION_PANEL=new Panel();
 __READBACK__
 public static void main(String[] args)throws Exception {
  DiscoveryPanelHostTest panel=new DiscoveryPanelHostTest();panel.refreshControlModeReadback();String shown=panel.controlModeNetworkReadback.value;
  if(!shown.contains("Serial: TEST-HEADSET")||!shown.contains("advertising / ready")||!shown.contains("http://10.0.0.2:8766/v1/gateway")||shown.contains("never-display-this"))throw new AssertionError("serial/effective state/address projection or secret exclusion");
  ExperimentSessionCommandGateway.instance.network.put("serial","").put("discovery",new JSONObject().put("state","unavailable").put("reason","permission-required"));
  panel.refreshControlModeReadback();shown=panel.controlModeNetworkReadback.value;
  if(!shown.contains("Serial: unavailable (identity not provisioned)")||!shown.contains("unavailable / permission-required")||!"session unchanged".equals(panel.controlModeSessionReadback.value))throw new AssertionError("unavailable identity/permission or existing session readback");
  System.out.println("PASS production Remote panel network readback: actual serial, unavailable identity, effective discovery/reason, address and secret exclusion");
 }
}
'''

def main():
    p=argparse.ArgumentParser();p.add_argument('--jdk',type=Path,required=True);p.add_argument('--json-jar',type=Path,required=True);a=p.parse_args()
    package=Path('io/github/mesmerprism/rustyquest/native_renderer');repo=Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix='rq-discovery-') as tmp:
        root=Path(tmp)
        for name,source in STUBS.items():
            f=root/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(source)
        h=root/package/'DiscoveryHostTest.java';h.parent.mkdir(parents=True,exist_ok=True);h.write_text(HARNESS)
        module=repo/'apps/native-renderer-android/panel-modules/breath-composition/src/main/java'/package/'BreathCompositionPanelModule.java'
        (root/package/'DiscoveryPanelHostTest.java').write_text(PANEL_HARNESS.replace('__READBACK__',method(module.read_text(encoding='utf-8'),'private void refreshControlModeReadback(')),encoding='utf-8')
        source=repo/'apps/native-renderer-android/panel-modules/breath-composition/src/main/java'/package/'ExperimentSessionDiscoveryServer.java'
        subprocess.run([str(a.jdk/'bin/javac.exe'),'-encoding','UTF-8','-cp',str(a.json_jar),'-d',str(root/'classes'),str(source),*map(str,root.rglob('*.java'))],check=True,timeout=60)
        subprocess.run([str(a.jdk/'bin/java.exe'),'-cp',str(root/'classes')+os.pathsep+str(a.json_jar),package.as_posix().replace('/','.')+'.DiscoveryHostTest'],check=True,timeout=20)
        subprocess.run([str(a.jdk/'bin/java.exe'),'-cp',str(root/'classes')+os.pathsep+str(a.json_jar),package.as_posix().replace('/','.')+'.DiscoveryPanelHostTest'],check=True,timeout=20)
if __name__=='__main__':main()
