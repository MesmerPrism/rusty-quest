package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Explicit foreground, one-controller GATT carrier. No discovery, group formation or authority API. */
final class HubGattBridge implements Closeable {
    interface Observer { void advertising(); void failed(String code); }
    private static final Observer SILENT = new Observer(){public void advertising(){}public void failed(String code){}};
    private final Observer observer;
    private final Context context;private final HubReadiness readiness;private final long deadline;
    private final Object gate=new Object();private final Map<BluetoothDevice,Peer> peers=new HashMap<>();
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(4));private BluetoothGattServer gatt;private BluetoothLeAdvertiser advertiser;
    private volatile boolean closed;private long nextGeneration;
    private abstract static class InputTask implements Runnable {
        final byte[] held;
        InputTask(byte[] held){this.held=held;}
        final void clear(){Arrays.fill(held,(byte)0);}
    }
    private final UUID service=UUID.fromString(HubBleFrames.SERVICE),write=UUID.fromString(HubBleFrames.WRITE),read=UUID.fromString(HubBleFrames.READ),status=UUID.fromString(HubBleFrames.STATUS);
    HubGattBridge(Context context,long deadline) {this(context,deadline,SILENT);}
    HubGattBridge(Context context,long deadline,Observer observer) {this.context=context.getApplicationContext();this.readiness=new HubReadiness(context);this.deadline=deadline;this.observer=observer;}
    void start() throws Exception {
        requireBudget();readiness.requireCurrent();BluetoothManager manager=context.getSystemService(BluetoothManager.class);
        if(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled())throw new IOException("Bluetooth unavailable");
        advertiser=manager.getAdapter().getBluetoothLeAdvertiser();if(advertiser==null)throw new IOException("BLE advertising unavailable");
        gatt=manager.openGattServer(context,callback);if(gatt==null)throw new IOException("GATT unavailable");
        BluetoothGattService definition=new BluetoothGattService(service,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        definition.addCharacteristic(new BluetoothGattCharacteristic(write,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE));
        definition.addCharacteristic(new BluetoothGattCharacteristic(read,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
        definition.addCharacteristic(new BluetoothGattCharacteristic(status,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
        if(!gatt.addService(definition))throw new IOException("GATT service unavailable");
    }
    private final AdvertiseCallback advertisement=new AdvertiseCallback(){
        @Override public void onStartSuccess(AdvertiseSettings settings){if(closed)return;try{requireBudget();observer.advertising();}catch(Exception denied){observer.failed("advertising_deadline_expired");close();}}
        @Override public void onStartFailure(int error){if(closed)return;observer.failed("advertising_failed");close();}
    };
    private final BluetoothGattServerCallback callback=new BluetoothGattServerCallback(){
        @Override public void onServiceAdded(int result,BluetoothGattService definition){
            if(closed||!service.equals(definition.getUuid()))return;
            if(result!=BluetoothGatt.GATT_SUCCESS){observer.failed("gatt_service_failed");close();return;}
            try{requireBudget();readiness.requireCurrent();advertiser.startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(),new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(service)).build(),advertisement);}catch(Exception denied){observer.failed("advertising_start_failed");close();}
        }
        @Override public void onConnectionStateChange(BluetoothDevice device,int result,int state){
            Peer retired=null;
            synchronized(gate){if(closed)return;if(result==BluetoothGatt.GATT_SUCCESS&&state==BluetoothProfile.STATE_CONNECTED){if(peers.isEmpty())peers.put(device,new Peer(++nextGeneration));}else retired=peers.remove(device);}
            if(retired!=null)retired.close();
        }
        @Override public void onMtuChanged(BluetoothDevice device,int mtu){synchronized(gate){Peer p=peers.get(device);if(!closed&&p!=null&&mtu>=23&&mtu<=517)p.mtu=mtu;}}
        @Override public void onCharacteristicWriteRequest(final BluetoothDevice device,final int requestId,BluetoothGattCharacteristic characteristic,boolean prepared,final boolean responseNeeded,int offset,byte[] value){
            final Peer p;synchronized(gate){p=peers.get(device);}
            if(closed||p==null||!write.equals(characteristic.getUuid())||prepared||offset!=0||!responseNeeded||value==null||value.length<=HubBleFrames.HEADER||value.length>Math.min(p.mtu-3,244)){respond(device,requestId,BluetoothGatt.GATT_FAILURE,0,null);return;}
            final byte[] copy=value.clone();
            try{worker.execute(new InputTask(copy){public void run(){byte[] complete=null;try{requireCurrent(device,p);readiness.requireCurrent();complete=p.frames.accept(copy,p.mtu,SystemClock.elapsedRealtime());
                if(complete!=null){requireCurrent(device,p);p.client.send(complete);}
                requireCurrent(device,p);respond(device,requestId,BluetoothGatt.GATT_SUCCESS,0,null);
                // GATT success acknowledges carrier bytes only; native Hub receipts arrive separately.
            }catch(Exception denied){retire(device,p);respond(device,requestId,BluetoothGatt.GATT_FAILURE,0,null);}finally{Arrays.fill(copy,(byte)0);if(complete!=null)Arrays.fill(complete,(byte)0);}}});}catch(RejectedExecutionException denied){Arrays.fill(copy,(byte)0);respond(device,requestId,BluetoothGatt.GATT_FAILURE,0,null);}
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device,int requestId,int offset,BluetoothGattCharacteristic characteristic){
            Peer p;synchronized(gate){p=peers.get(device);}try{requireCurrent(device,p);readiness.requireCurrent();byte[] bytes;
                if(status.equals(characteristic.getUuid())){JSONObject s=new JSONObject().put("v",1).put("carrier","ble_gatt_to_loopback_hub").put("mtu",p.mtu).put("listener_observed",true).put("controller_authority","not_claimed").put("production_eligible",false);bytes=s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(offset<0||offset>bytes.length)throw new IOException("status offset");bytes=Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+p.mtu-1));}
                else if(read.equals(characteristic.getUuid())&&offset==0){bytes=p.next();}
                else throw new IOException("unknown carrier read");
                respond(device,requestId,BluetoothGatt.GATT_SUCCESS,offset,bytes);
            }catch(Exception denied){if(p!=null)retire(device,p);respond(device,requestId,BluetoothGatt.GATT_FAILURE,offset,null);}
        }
    };
    private final class Peer implements Closeable {
        final long generation;volatile int mtu=23;final HubBleFrames frames=new HubBleFrames();final ArrayDeque<byte[]> outgoing=new ArrayDeque<>();
        final HubLoopbackClient client;private int outputId=1,offset;private long outputDeadline;private volatile boolean retired;
        Peer(long generation){this.generation=generation;client=new HubLoopbackClient(new HubLoopbackClient.Readiness(){public void requireCurrent()throws Exception{requirePeer(Peer.this);readiness.requireCurrent();}},new HubLoopbackClient.Sink(){public void frame(byte[] bytes)throws Exception{enqueue(bytes);}public void unavailable(){retirePeer(Peer.this);}},System.nanoTime()+Math.max(0,deadline-SystemClock.elapsedRealtime())*1_000_000L);}
        synchronized void enqueue(byte[] bytes)throws Exception{if(retired||bytes.length<1||bytes.length>HubBleFrames.MAX_BYTES||outgoing.size()>=16)throw new IOException("carrier output unavailable");outgoing.add(bytes.clone());}
        synchronized byte[] next()throws Exception{
            if(retired)throw new IOException("carrier retired");if(outgoing.isEmpty())return new byte[0];
            long now=SystemClock.elapsedRealtime();if(offset==0)outputDeadline=now+HubBleFrames.ASSEMBLY_MS;
            if(now>=outputDeadline)throw new IOException("carrier read deadline");byte[] body=outgoing.peek();byte[] result=HubBleFrames.chunk(body,outputId,offset,mtu);offset+=result.length-HubBleFrames.HEADER;
            if(offset==body.length){Arrays.fill(outgoing.remove(),(byte)0);offset=0;if(outputId==65535)throw new IOException("carrier sequence exhausted");outputId++;}return result;
        }
        public void close(){synchronized(this){if(retired)return;retired=true;frames.close();for(byte[] bytes:outgoing)Arrays.fill(bytes,(byte)0);outgoing.clear();}client.close();}
    }
    private void requirePeer(Peer p)throws Exception{requireBudget();synchronized(gate){if(p==null||p.retired||!peers.containsValue(p))throw new IOException("retired carrier connection");}}
    // Identity-only lifetime removal, also exercised without Android effects by the host runner.
    static <K,V> boolean removeCurrentValue(Map<K,V> current,V expected){Iterator<Map.Entry<K,V>> entries=current.entrySet().iterator();while(entries.hasNext())if(entries.next().getValue()==expected){entries.remove();return true;}return false;}
    private void retirePeer(Peer p){synchronized(gate){removeCurrentValue(peers,p);}p.close();}
    private void requireCurrent(BluetoothDevice d,Peer p)throws Exception{requireBudget();synchronized(gate){if(p==null||p.retired||peers.get(d)!=p)throw new IOException("retired carrier connection");}}
    private void requireBudget()throws IOException{if(closed||SystemClock.elapsedRealtime()>=deadline)throw new IOException("foreground carrier expired");}
    private void respond(BluetoothDevice d,int request,int result,int offset,byte[] data){BluetoothGattServer current=gatt;if(!closed&&current!=null)try{current.sendResponse(d,request,result,offset,data);}catch(RuntimeException ignored){}}
    private void retire(BluetoothDevice d,Peer p){synchronized(gate){if(peers.get(d)==p)peers.remove(d);}p.close();}
    public void close(){List<Peer> retired;synchronized(gate){if(closed)return;closed=true;retired=new ArrayList<>(peers.values());peers.clear();}for(Peer p:retired)p.close();for(Runnable pending:worker.shutdownNow())if(pending instanceof InputTask)((InputTask)pending).clear();if(advertiser!=null)try{advertiser.stopAdvertising(advertisement);}catch(RuntimeException ignored){}if(gatt!=null)try{gatt.close();}catch(RuntimeException ignored){}gatt=null;}
}
