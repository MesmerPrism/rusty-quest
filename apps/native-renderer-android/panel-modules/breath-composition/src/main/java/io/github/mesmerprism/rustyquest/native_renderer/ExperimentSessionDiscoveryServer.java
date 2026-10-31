package io.github.mesmerprism.rustyquest.native_renderer;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only identity discovery. It owns no experiment, enrollment or Polar command route. */
final class ExperimentSessionDiscoveryServer implements AutoCloseable {
    static final String SERVICE="9a7b2001-7d6a-4b7f-9d4a-6f7c0a020101";
    static final String SERIAL="9a7b2001-7d6a-4b7f-9d4a-6f7c0a020102";
    static final String METADATA="9a7b2001-7d6a-4b7f-9d4a-6f7c0a020103";
    static final int PERMISSION_REQUEST=9403;
    interface Source { JSONObject snapshot() throws Exception; }
    private final Context context;
    private final Source source;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<BluetoothDevice,Peer> peers=new LinkedHashMap<>();
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertisement;
    private long generation,revision;
    private boolean remote,permissionRequested;
    private int attempts;
    private String state="inactive",reason="remote-required";
    private Snapshot snapshot;
    private Runnable refresh,recovery,timeout;
    private static final class Snapshot {
        final byte[] serial,metadata;
        Snapshot(JSONObject value)throws Exception {
            String serialText=value.getString("serial");serial=serialText.getBytes(StandardCharsets.UTF_8);
            metadata=value.toString().getBytes(StandardCharsets.UTF_8);
            if(value.length()!=7||value.getInt("v")!=1||!"remote".equals(value.getString("mode"))
                    ||!value.getString("device").matches("[0-9a-f]{32}")
                    ||!value.getString("epoch").matches("[1-9][0-9]{0,18}")
                    ||!value.getString("native_epoch").matches("[1-9][0-9]{0,18}")
                    ||serial.length==0||serial.length>64||serialText.matches("(?s).*[\\p{Cntrl}].*")||metadata.length>512)
                throw new IllegalArgumentException("discovery-metadata-bound");
            JSONArray gateways=value.getJSONArray("gateways");
            if(gateways.length()>4)throw new IllegalArgumentException("discovery-endpoint-bound");
            for(int i=0;i<gateways.length();i++){
                String endpoint=gateways.getString(i);
                if(!endpoint.matches("http://[0-9.]+:8766/v1/gateway"))throw new IllegalArgumentException("discovery-endpoint-invalid");
                String[] parts=endpoint.substring(7,endpoint.indexOf(':',7)).split("\\.");
                if(parts.length!=4)throw new IllegalArgumentException("discovery-ip-invalid");
                int[] ip=new int[4];for(int j=0;j<4;j++){ip[j]=Integer.parseInt(parts[j]);if(ip[j]>255||parts[j].length()>1&&parts[j].startsWith("0"))throw new IllegalArgumentException("discovery-ip-invalid");}
                if(!(ip[0]==10||ip[0]==172&&ip[1]>=16&&ip[1]<=31||ip[0]==192&&ip[1]==168))throw new IllegalArgumentException("discovery-ip-not-private");
            }
        }
    }
    private static final class Peer {
        final Snapshot snapshot;
        int mtu=23;
        Runnable disconnect;
        Peer(Snapshot snapshot){this.snapshot=snapshot;}
    }
    ExperimentSessionDiscoveryServer(Context context,Source source){this.context=context.getApplicationContext();this.source=source;}
    /** Invoked on the actual main owner; native retirement explicitly closes this owner. */
    void reconcile(long token,boolean available){
        long admitted;
        synchronized(this){
            if(remote==available&&revision==token)return;
            close();revision=token;remote=available;
            if(!available)return;
            state="starting";reason="initializing";admitted=generation;
        }
        refresh(admitted,true);
    }
    synchronized JSONObject readback()throws Exception{return new JSONObject().put("state",state).put("reason",reason);}
    private boolean permitted(){
        return Build.VERSION.SDK_INT<31||context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
            &&context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED;
    }
    void requestPermissions(Activity host){
        boolean retry=false;
        synchronized(this){if(!remote||host==null)return;
            try{
                if(permitted())retry=server==null&&recovery==null&&"unavailable".equals(state);
                else if(!permissionRequested){permissionRequested=true;
                    host.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},PERMISSION_REQUEST);
                }
            }catch(RuntimeException unavailable){state="unavailable";reason="permission-required";}
        }
        // An explicit reopened panel can retry after provisioning or Bluetooth recovery.
        if(retry)permissionResult();
    }
    void permissionResult(){
        long admitted;
        synchronized(this){if(!remote)return;stopPlatform();attempts=0;admitted=generation;}
        refresh(admitted,true);
    }
    /** Source executes outside this monitor on main: never wait for a Bluetooth callback. */
    private void refresh(final long admitted,boolean start){
        synchronized(this){if(!current(admitted))return;}
        Snapshot next;
        try{next=new Snapshot(source.snapshot());}
        catch(Exception unavailable){synchronized(this){if(current(admitted)){stopPlatform();state="unavailable";reason="metadata-unavailable";}}return;}
        synchronized(this){
            if(!current(admitted))return;snapshot=next;
            if(start)startPlatform(admitted);
            else try{
                if(!permitted()){fail(admitted,"permission-required",false);return;}
                BluetoothManager manager=context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
                if(adapter==null){fail(admitted,"bluetooth-unavailable",true);return;}
                if(!adapter.isEnabled()){fail(admitted,"bluetooth-off",true);return;}
            }catch(SecurityException denied){fail(admitted,"permission-required",false);return;}
            catch(RuntimeException unavailable){fail(admitted,"bluetooth-unavailable",true);return;}
            if(!current(admitted)||server==null)return;
            if(refresh!=null)main.removeCallbacks(refresh);
            refresh=new Runnable(){@Override public void run(){refresh(admitted,false);}};
            main.postDelayed(refresh,3000);
        }
    }
    private boolean current(long admitted){return remote&&generation==admitted;}
    private void startPlatform(final long admitted){
        try{
            if(!permitted()){state="unavailable";reason="permission-required";return;}
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null){fail(admitted,"bluetooth-unavailable",true);return;}
            if(!adapter.isEnabled()){fail(admitted,"bluetooth-off",true);return;}
            advertiser=adapter.getBluetoothLeAdvertiser();
            if(advertiser==null){fail(admitted,"advertiser-unavailable",true);return;}
            state="starting";reason="adding-service";
            server=manager.openGattServer(context,callbacks(admitted));
            if(server==null){fail(admitted,"gatt-unavailable",true);return;}
            BluetoothGattService service=new BluetoothGattService(UUID.fromString(SERVICE),BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(new BluetoothGattCharacteristic(UUID.fromString(SERIAL),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
            service.addCharacteristic(new BluetoothGattCharacteristic(UUID.fromString(METADATA),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
            if(!server.addService(service)){fail(admitted,"service-unavailable",true);return;}
            timeout=new Runnable(){@Override public void run(){synchronized(ExperimentSessionDiscoveryServer.this){if(current(admitted)&&!"advertising".equals(state))fail(admitted,"callback-timeout",true);}}};
            main.postDelayed(timeout,8000);
        }catch(SecurityException denied){fail(admitted,"permission-required",false);}
        catch(RuntimeException unavailable){fail(admitted,"bluetooth-unavailable",true);}
    }
    private BluetoothGattServerCallback callbacks(final long admitted){return new BluetoothGattServerCallback(){
        @Override public void onServiceAdded(int status,BluetoothGattService service){synchronized(ExperimentSessionDiscoveryServer.this){
            if(!current(admitted)||server==null)return;
            if(status!=BluetoothGatt.GATT_SUCCESS||!UUID.fromString(SERVICE).equals(service.getUuid())){fail(admitted,"service-unavailable",true);return;}
            // Duplicate callbacks must not create a second advertisement.
            if(advertisement!=null)return;
            advertisement=new AdvertiseCallback(){
                @Override public void onStartSuccess(AdvertiseSettings settings){synchronized(ExperimentSessionDiscoveryServer.this){
                    if(!current(admitted)||advertisement!=this)return;state="advertising";reason="ready";if(timeout!=null)main.removeCallbacks(timeout);timeout=null;
                }}
                @Override public void onStartFailure(int code){synchronized(ExperimentSessionDiscoveryServer.this){if(current(admitted)&&advertisement==this)fail(admitted,"advertising-failed",true);}}
            };
            try{advertiser.startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
                new AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(new ParcelUuid(UUID.fromString(SERVICE))).build(),null,advertisement);
            }catch(SecurityException denied){fail(admitted,"permission-required",false);}catch(RuntimeException unavailable){fail(admitted,"advertising-failed",true);}
        }}
        @Override public void onConnectionStateChange(BluetoothDevice device,int status,int next){synchronized(ExperimentSessionDiscoveryServer.this){
            if(!current(admitted)||server==null)return;
            if(status==BluetoothGatt.GATT_SUCCESS&&next==BluetoothProfile.STATE_CONNECTED)peer(device);
            else {Peer old=peers.remove(device);if(old!=null&&old.disconnect!=null)main.removeCallbacks(old.disconnect);}
        }}
        @Override public void onMtuChanged(BluetoothDevice device,int mtu){synchronized(ExperimentSessionDiscoveryServer.this){
            if(!current(admitted)||server==null)return;Peer p=peer(device);if(p!=null)p.mtu=Math.max(23,Math.min(517,mtu));
        }}
        @Override public void onCharacteristicReadRequest(final BluetoothDevice device,int id,int offset,BluetoothGattCharacteristic characteristic){synchronized(ExperimentSessionDiscoveryServer.this){
            if(!current(admitted)||server==null)return;
            String uuid=characteristic.getUuid().toString();
            if(!SERIAL.equals(uuid)&&!METADATA.equals(uuid)){respond(device,id,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,offset,null);return;}
            final Peer p=peer(device);if(p==null){respond(device,id,BluetoothGatt.GATT_FAILURE,offset,null);return;}
            byte[] bytes=SERIAL.equals(uuid)?p.snapshot.serial:p.snapshot.metadata;
            if(offset<0||offset>bytes.length){respond(device,id,BluetoothGatt.GATT_INVALID_OFFSET,offset,null);return;}
            int end=Math.min(bytes.length,offset+p.mtu-1);
            if(!respond(device,id,BluetoothGatt.GATT_SUCCESS,offset,Arrays.copyOfRange(bytes,offset,end)))return;
            if(METADATA.equals(uuid)&&end==bytes.length&&end-offset<p.mtu-1&&p.disconnect==null){
                p.disconnect=new Runnable(){@Override public void run(){synchronized(ExperimentSessionDiscoveryServer.this){
                    if(!current(admitted)||server==null||peers.get(device)!=p)return;
                    try{server.cancelConnection(device);}catch(RuntimeException ignored){}
                    // Keep the snapshot until Android acknowledges disconnect, preventing mixed reads.
                }}};main.postDelayed(p.disconnect,1500);
            }
        }}
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device,int id,BluetoothGattCharacteristic characteristic,boolean prepared,boolean response,int offset,byte[] value){synchronized(ExperimentSessionDiscoveryServer.this){
            if(current(admitted)&&server!=null&&response)respond(device,id,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,offset,null);
        }}
    };}
    private Peer peer(BluetoothDevice device){
        Peer p=peers.get(device);if(p!=null)return p;
        if(snapshot==null||peers.size()>=8){try{server.cancelConnection(device);}catch(RuntimeException ignored){}return null;}
        p=new Peer(snapshot);peers.put(device,p);return p;
    }
    private boolean respond(BluetoothDevice device,int id,int status,int offset,byte[] bytes){
        try{if(server.sendResponse(device,id,status,offset,bytes))return true;}catch(RuntimeException unavailable){}
        fail(generation,"response-unavailable",true);return false;
    }
    private void fail(long admitted,String why,boolean retry){
        if(!current(admitted))return;stopPlatform();state="unavailable";reason=why;
        if(!retry||attempts++>=3)return;
        final long next=generation;
        recovery=new Runnable(){@Override public void run(){synchronized(ExperimentSessionDiscoveryServer.this){if(!current(next)||recovery!=this)return;recovery=null;}refresh(next,true);}};
        main.postDelayed(recovery,1000);
    }
    private void stopPlatform(){
        ++generation;
        if(refresh!=null)main.removeCallbacks(refresh);refresh=null;
        if(recovery!=null)main.removeCallbacks(recovery);recovery=null;
        if(timeout!=null)main.removeCallbacks(timeout);timeout=null;
        for(Peer p:peers.values())if(p.disconnect!=null)main.removeCallbacks(p.disconnect);
        peers.clear();snapshot=null;
        BluetoothGattServer old=server;server=null;
        BluetoothLeAdvertiser ads=advertiser;advertiser=null;AdvertiseCallback callback=advertisement;advertisement=null;
        if(ads!=null&&callback!=null)try{ads.stopAdvertising(callback);}catch(RuntimeException ignored){}
        if(old!=null){try{old.clearServices();}catch(RuntimeException ignored){}try{old.close();}catch(RuntimeException ignored){}}
    }
    @Override public synchronized void close(){remote=false;permissionRequested=false;attempts=0;stopPlatform();state="inactive";reason="remote-required";}
}
