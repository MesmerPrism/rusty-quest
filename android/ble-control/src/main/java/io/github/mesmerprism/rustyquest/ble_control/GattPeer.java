package io.github.mesmerprism.rustyquest.ble_control;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/** One bounded carrier lifetime. Endpoint policy, authority and effects remain app-owned. */
public final class GattPeer implements Closeable {
    public interface Clock { long now(); }
    public interface Guard { void requireCurrent() throws Exception; }
    public interface Sink { void frame(byte[] bytes) throws Exception; void unavailable(); }
    public interface Endpoint { void send(byte[] bytes) throws Exception; void close(); }
    public interface EndpointFactory { Endpoint open(Sink sink); }
    private final Clock clock; private final Guard guard; private final Endpoint endpoint;
    private final HubBleFrames frames=new HubBleFrames();
    private final ArrayDeque<byte[]> outgoing=new ArrayDeque<>();
    private volatile int mtu=23; private volatile boolean retired;
    private int outputId=1,offset; private long outputDeadline;
    public GattPeer(Clock clock,Guard guard,EndpointFactory factory,final Runnable unavailable) {
        if(clock==null||guard==null||factory==null||unavailable==null)throw new NullPointerException();
        this.clock=clock;this.guard=guard;
        endpoint=Objects.requireNonNull(factory.open(new Sink(){
            public void frame(byte[] bytes)throws Exception{enqueue(bytes);}
            public void unavailable(){unavailable.run();}
        }));
    }
    public int mtu(){return mtu;}
    public void mtu(int value){if(value>=23&&value<=517)mtu=value;}
    public boolean retired(){return retired;}
    private void current()throws Exception{if(retired)throw new IOException("carrier retired");guard.requireCurrent();}
    /** Borrowed bytes are valid only during send; endpoint must copy any retained input. */
    public void accept(byte[] bytes)throws Exception {
        byte[] complete=null;
        try{current();complete=frames.accept(bytes,mtu,clock.now());if(complete!=null){current();endpoint.send(complete);}current();}
        finally{if(complete!=null)Arrays.fill(complete,(byte)0);}
    }
    private synchronized void enqueue(byte[] bytes)throws Exception {
        if(retired||bytes.length<1||bytes.length>HubBleFrames.MAX_BYTES||outgoing.size()>=16)throw new IOException("carrier output unavailable");
        outgoing.add(bytes.clone());
    }
    public synchronized byte[] next()throws Exception {
        current();if(outgoing.isEmpty())return new byte[0];long now=clock.now();if(offset==0)outputDeadline=now+HubBleFrames.ASSEMBLY_MS;
        if(now>=outputDeadline)throw new IOException("carrier read deadline");byte[] body=outgoing.peek();byte[] result=HubBleFrames.chunk(body,outputId,offset,mtu);offset+=result.length-HubBleFrames.HEADER;
        if(offset==body.length){Arrays.fill(outgoing.remove(),(byte)0);offset=0;if(outputId==65535)throw new IOException("carrier sequence exhausted");outputId++;}return result;
    }
    public void close(){synchronized(this){if(retired)return;retired=true;frames.close();for(byte[] bytes:outgoing)Arrays.fill(bytes,(byte)0);outgoing.clear();}endpoint.close();}
    /** An obsolete callback may remove only its exact lifetime, never a replacement. */
    public static <K,V> boolean removeCurrentValue(Map<K,V> current,V expected){Iterator<Map.Entry<K,V>> entries=current.entrySet().iterator();while(entries.hasNext())if(entries.next().getValue()==expected){entries.remove();return true;}return false;}
}
