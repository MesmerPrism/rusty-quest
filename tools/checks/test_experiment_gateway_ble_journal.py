"""Actual production GATT callback + shared journal; Android radio and app effects inert."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_ble_generation import STUBS

HARNESS = '''package io.github.mesmerprism.rustyquest.native_renderer;
import android.bluetooth.*;import android.os.*;import org.json.JSONObject;
class GatewayBleJournalTest {
 static int effects;
 static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
 public static void main(String[]args)throws Exception{
  android.app.Activity context=new android.app.Activity();
  final ExperimentSessionBleServer[] slot=new ExperimentSessionBleServer[1];
  ExperimentSessionBleServer owner=ExperimentSessionBleServer.process(context,new ExperimentSessionBleServer.Delegate(){
   public boolean openControlDefault(){return true;}
   public JSONObject snapshot()throws Exception{return new JSONObject().put("g",7).put("p","RUNNING").put("pending",false).put("b",new org.json.JSONArray().put("pattern-ready").put("no-pattern"));}
   public JSONObject sessionControlReadback()throws Exception{return new JSONObject().put("phase","active").put("profile_identity_bound",true).put("audio_identity_bound",true).put("generation",7).put("runtime_epoch",7).put("session_key","utc-ns-00000000000000000042").put("condition_id","condition-a").put("non_audio_profile_sha256",String.join("",java.util.Collections.nCopies(64,"a"))).put("activation_binding_sha256",String.join("",java.util.Collections.nCopies(64,"b"))).put("control_state","running").put("breath_guidance",new JSONObject().put("identity_bound",true).put("bias_percent",20));}
   public void dispatch(ExperimentSessionBleServer.CommandRequest request){effects++;slot[0].updateReceipt(request.id,"pending","original app callback");}
  });slot[0]=owner;owner.nativeOwnerStarted(1,7);
  final ExperimentSessionCommandJournal journal=new ExperimentSessionCommandJournal();
  owner.gatewayBinding(new ExperimentSessionBleServer.GatewayReceiptListener(){
   public void receipt(JSONObject receipt){try{journal.observe(receipt);}catch(Exception e){throw new RuntimeException(e);}}
   public boolean known(JSONObject body)throws Exception{return journal.known(body);}
   public boolean reserve(JSONObject body)throws Exception{return journal.reserve(body);}
   public JSONObject original(String id)throws Exception{return journal.original(id);}
  },"11111111111111111111111111111111","42");owner.attachCommandGateway(true);
  BluetoothGattServer server=context.manager.servers.get(0);BluetoothDevice peer=new BluetoothDevice();server.callback.onConnectionStateChange(peer,0,2);
  BluetoothGattCharacteristic command=new BluetoothGattCharacteristic(java.util.UUID.fromString(ExperimentSessionBleProtocol.COMMAND),8,16);
  BluetoothGattCharacteristic receipt=new BluetoothGattCharacteristic(java.util.UUID.fromString(ExperimentSessionBleProtocol.RECEIPT),2,1);
  JSONObject arm=new JSONObject().put("v",1).put("id","aaaaaaaaaaaaaaaa").put("op","arm").put("condition","condition-a").put("bias",50).put("nonce","1111111111111111");
  server.callback.onCharacteristicWriteRequest(peer,1,command,false,true,0,arm.toString().getBytes("UTF-8"));Handler.drain();
  check(effects==1&&journal.original(arm.getString("id")).getString("state").equals("pending"),"BLE original enters shared journal and actual callback once");
  arm.put("nonce","2222222222222222");server.callback.onCharacteristicWriteRequest(peer,2,command,false,true,0,arm.toString().getBytes("UTF-8"));Handler.drain();check(effects==1,"BLE retry does not re-dispatch");
  check(journal.known(arm)&&!journal.reserve(arm),"network retry sees same original BLE admission");
  owner.updateReceipt(arm.getString("id"),"confirmed","late original native effect");check(journal.original(arm.getString("id")).getString("state").equals("confirmed"),"network obtains late original BLE effect");
  JSONObject network=new JSONObject(arm.toString()).put("id","bbbbbbbbbbbbbbbb").put("nonce","3333333333333333");
  check(journal.reserve(network),"network owner reserves next ID");owner.updateReceipt(network.getString("id"),"pending","original network dispatch");
  server.callback.onCharacteristicWriteRequest(peer,3,command,false,true,0,network.toString().getBytes("UTF-8"));Handler.drain();check(effects==1,"network lost reply followed by BLE never redispatches");
  server.callback.onCharacteristicReadRequest(peer,4,0,receipt);JSONObject observed=new JSONObject(new String(server.lastBytes,"UTF-8"));
  check(observed.getString("id").equals(network.getString("id"))&&observed.getString("state").equals("pending"),"BLE reads original network receipt exact ID");
  network.put("nonce","4444444444444444").put("condition","condition-b");server.callback.onCharacteristicWriteRequest(peer,5,command,false,true,0,network.toString().getBytes("UTF-8"));Handler.drain();check(effects==1,"cross-route ID conflict cannot effect");
  BluetoothGattCharacteristic status=new BluetoothGattCharacteristic(java.util.UUID.fromString(ExperimentSessionBleProtocol.STATUS),2,1);
  server.callback.onCharacteristicReadRequest(peer,6,0,status);JSONObject published=new JSONObject(new String(server.lastBytes,"UTF-8"));
  JSONObject bias=new JSONObject().put("v",2).put("id","cccccccccccccccc").put("op","set-bias").put("s",published.getJSONObject("q").getString("s")).put("g",7).put("b",75).put("n","5555555555555555");
  server.callback.onCharacteristicWriteRequest(peer,7,command,false,true,0,bias.toString().getBytes("UTF-8"));Handler.drain();check(effects==2,"actual BLE live bias dispatch");
  bias.put("n","6666666666666666");server.callback.onCharacteristicWriteRequest(peer,8,command,false,true,0,bias.toString().getBytes("UTF-8"));Handler.drain();check(effects==2,"live bias original no redispatch");
  bias.put("n","7777777777777777").put("b",76);server.callback.onCharacteristicWriteRequest(peer,9,command,false,true,0,bias.toString().getBytes("UTF-8"));Handler.drain();check(effects==2,"changed percent same ID denied");
  bias.put("id","dddddddddddddddd").put("n","8888888888888888").put("b",75.5);server.callback.onCharacteristicWriteRequest(peer,10,command,false,true,0,bias.toString().getBytes("UTF-8"));Handler.drain();check(effects==2,"fractional percent denied by production parser");
  System.out.println("PASS actual production BLE callback + shared journal: original Arm BLE/network recovery, inverse lost reply, original exact receipt, changed payload denial, once-only app handler");
 }
}
'''

def main():
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2]);p.add_argument('--json-jar',type=Path,required=True);a=p.parse_args()
    stubs=dict(STUBS);stubs.pop('org/json/JSONObject.java')
    stubs['android/content/Context.java']=stubs['android/content/Context.java'].replace('public static final int MODE_PRIVATE=0;', 'public static final int MODE_PRIVATE=0,RECEIVER_EXPORTED=2;public void registerReceiver(BroadcastReceiver r,IntentFilter f){}public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){}')
    stubs['android/content/BroadcastReceiver.java']='package android.content;public abstract class BroadcastReceiver{public abstract void onReceive(Context c,Intent i);}'
    stubs['android/content/IntentFilter.java']='package android.content;public class IntentFilter{public void addAction(String s){}}'
    stubs['android/content/Intent.java']='package android.content;public class Intent{public static final String ACTION_USER_PRESENT="present",ACTION_SCREEN_ON="screen";public String getAction(){return "";}public int getIntExtra(String k,int d){return d;}}'
    stubs['android/bluetooth/BluetoothAdapter.java']=stubs['android/bluetooth/BluetoothAdapter.java'].replace('public class BluetoothAdapter {','public class BluetoothAdapter {public static final String ACTION_STATE_CHANGED="changed",EXTRA_STATE="state";public static final int STATE_ON=12;')
    root=a.repo/'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
    with tempfile.TemporaryDirectory(prefix='rq-gateway-ble-') as tmp:
        out=Path(tmp)
        for path,body in stubs.items():
            f=out/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body,encoding='utf-8')
        (out/'GatewayBleJournalTest.java').write_text(HARNESS,encoding='utf-8')
        sources=list(out.rglob('*.java'))+[root/(name+'.java') for name in ['ExperimentSessionBleServer','ExperimentSessionBleNamePolicy','ExperimentSessionBleProtocol','ExperimentSessionControlCapability','ExperimentSessionCommandJournal','ExperimentSessionGatewayProtocol']]
        subprocess.run(['javac','--release','8','-cp',str(a.json_jar),'-d',str(out),*map(str,sources)],check=True)
        subprocess.run(['java','-cp',str(out)+os.pathsep+str(a.json_jar),'io.github.mesmerprism.rustyquest.native_renderer.GatewayBleJournalTest'],check=True)


if __name__=='__main__':main()
