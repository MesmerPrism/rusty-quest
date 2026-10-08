package io.github.mesmerprism.rustyquest.ble_control;
import java.util.*;
import java.util.concurrent.*;

/** Packaged production peer/queue controls; endpoint has no authority or devices. */
public final class GattPeerTest {
    static int cases;interface Action{void run()throws Exception;}
    static void check(boolean value){if(!value)throw new AssertionError();cases++;}
    static void denied(Action action)throws Exception{try{action.run();throw new AssertionError("unexpected acceptance");}catch(Exception expected){cases++;}}
    static final class Endpoint implements GattPeer.EndpointFactory,GattPeer.Endpoint {
        GattPeer.Sink sink;byte[] borrowed,received;int closes;
        public GattPeer.Endpoint open(GattPeer.Sink sink){this.sink=sink;return this;}
        public void send(byte[] bytes){borrowed=bytes;received=bytes.clone();}
        public void close(){closes++;}
    }
    static GattPeer peer(final long[] now,final boolean[] current,Endpoint endpoint,final int[] unavailable){return new GattPeer(new GattPeer.Clock(){public long now(){return now[0];}},new GattPeer.Guard(){public void requireCurrent(){if(!current[0])throw new IllegalStateException("obsolete owner");}},endpoint,new Runnable(){public void run(){unavailable[0]++;}});}
    static boolean cleared(byte[] bytes){for(byte b:bytes)if(b!=0)return false;return true;}
    public static void main(String[] args)throws Exception {
        long[] now={10};boolean[] current={true};Endpoint endpoint=new Endpoint();int[] unavailable={0};GattPeer peer=peer(now,current,endpoint,unavailable);
        check(peer.mtu()==23);peer.mtu(22);check(peer.mtu()==23);peer.mtu(517);check(peer.mtu()==517);
        byte[] input="arbitrary bytes, not a command".getBytes("UTF-8");peer.accept(HubBleFrames.chunk(input,1,0,517));check(Arrays.equals(endpoint.received,input));check(cleared(endpoint.borrowed));
        byte[] output="isolated output".getBytes("UTF-8");endpoint.sink.frame(output);output[0]=0;HubBleFrames decoder=new HubBleFrames();check(new String(decoder.accept(peer.next(),517,now[0]),"UTF-8").equals("isolated output"));check(peer.next().length==0);
        endpoint.sink.unavailable();check(unavailable[0]==1);
        current[0]=false;denied(new Action(){public void run()throws Exception{peer.accept(HubBleFrames.chunk(input,2,0,517));}});denied(new Action(){public void run()throws Exception{peer.next();}});current[0]=true;
        for(int i=0;i<16;i++)endpoint.sink.frame(new byte[]{1});denied(new Action(){public void run()throws Exception{endpoint.sink.frame(new byte[]{1});}});
        peer.close();peer.close();check(endpoint.closes==1&&peer.retired());denied(new Action(){public void run()throws Exception{peer.next();}});denied(new Action(){public void run()throws Exception{endpoint.sink.frame(new byte[]{1});}});
        Endpoint fragmented=new Endpoint();GattPeer timed=peer(now,current,fragmented,unavailable);fragmented.sink.frame(new byte[100]);check(timed.next().length==20);now[0]+=HubBleFrames.ASSEMBLY_MS;denied(new Action(){public void run()throws Exception{timed.next();}});timed.close();
        final Map<String,Object> slots=new HashMap<>();Object old=new Object(),replacement=new Object();slots.put("device",replacement);check(!GattPeer.removeCurrentValue(slots,old));check(slots.get("device")==replacement);check(GattPeer.removeCurrentValue(slots,replacement)&&slots.isEmpty());
        final GattInputQueue queue=new GattInputQueue();final CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1),done=new CountDownLatch(1);final byte[][] held={null};final int[] executed={0};byte[] original={7};
        check(queue.submit(original,new GattInputQueue.Action(){public void accept(byte[] bytes){held[0]=bytes;started.countDown();try{finish.await();}catch(InterruptedException interrupted){}finally{done.countDown();}}}));check(started.await(2,TimeUnit.SECONDS));original[0]=0;check(held[0][0]==7);
        for(int i=0;i<4;i++)check(queue.submit(new byte[]{9},new GattInputQueue.Action(){public void accept(byte[] bytes){executed[0]++;}}));check(!queue.submit(new byte[]{9},new GattInputQueue.Action(){public void accept(byte[] bytes){executed[0]++;}}));
        queue.close();finish.countDown();check(done.await(2,TimeUnit.SECONDS));long until=System.nanoTime()+2_000_000_000L;while(!cleared(held[0])&&System.nanoTime()<until)Thread.yield();check(cleared(held[0])&&executed[0]==0);check(!queue.submit(new byte[]{1},new GattInputQueue.Action(){public void accept(byte[] bytes){throw new AssertionError();}}));
        System.out.println("GattPeerTest PASS "+cases+" packaged production cases; endpoint modeled, no devices");
    }
}
