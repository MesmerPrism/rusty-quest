package io.github.mesmerprism.rustyquest.displaystream;
import java.nio.*;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;

public final class DisplayStreamAttemptHostMain {
    static final String ID="11111111111111111111111111111111", NONCE="22222222222222222222222222222222";
    static DisplayStreamAttempt attempt(){return new DisplayStreamAttempt(ID,"model-1",NONCE);}
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void await(CountDownLatch l)throws Exception{check(l.await(5,TimeUnit.SECONDS));}
    static void retired(DisplayStreamAttempt a)throws Exception{check(a.awaitRetirement(5000).successful());}
    static void stop(DisplayStreamAttempt a){check(a.requestStop(ID,"model-1",NONCE));}
    static class Channel implements java.nio.channels.Channel{
        volatile boolean open=true;int closes;
        public boolean isOpen(){return open;}
        public synchronized void close(){if(open){open=false;closes++;}}
    }
    static void blockedWriter()throws Exception{
        DisplayStreamAttempt a=attempt();Pipe pipe=Pipe.open();CountDownLatch entered=new CountDownLatch(1);AtomicInteger released=new AtomicInteger();
        a.start(x->{x.ownChannel(pipe.sink());x.ownResource(()->released.incrementAndGet());entered.countDown();ByteBuffer b=ByteBuffer.allocateDirect(1024*1024);while(true){b.clear();pipe.sink().write(b);}});
        await(entered);check(!a.snapshot().workerRetired);stop(a);retired(a);check(released.get()==1);pipe.source().close();
    }
    static void stopDuringRegistration()throws Exception{
        DisplayStreamAttempt a=attempt();CountDownLatch entered=new CountDownLatch(1),go=new CountDownLatch(1);Channel ch=new Channel();AtomicInteger effects=new AtomicInteger();
        a.start(x->{entered.countDown();go.await();x.ownChannel(ch);effects.incrementAndGet();});await(entered);stop(a);go.countDown();retired(a);check(!ch.open&&effects.get()==0);
    }
    static void pendingAndStale()throws Exception{
        DisplayStreamAttempts slot=new DisplayStreamAttempts();DisplayStreamAttempt a=attempt();CountDownLatch entered=new CountDownLatch(1),go=new CountDownLatch(1);AtomicInteger old=new AtomicInteger(),next=new AtomicInteger();
        slot.install(a);a.start(x->{x.ownResource(()->old.incrementAndGet());entered.countDown();go.await();});await(entered);stop(a);check(!a.awaitRetirement(1).workerRetired);check(!slot.available());
        try{slot.install(attempt());throw new AssertionError();}catch(IllegalStateException expected){}
        go.countDown();retired(a);DisplayStreamAttempt b=attempt();slot.install(b);b.start(x->x.ownResource(()->next.incrementAndGet()));retired(b);check(!slot.isCurrent(a)&&slot.isCurrent(b)&&old.get()==1&&next.get()==1);
    }
    static void foreignAndDuplicate()throws Exception{
        DisplayStreamAttempt a=attempt();Channel ch=new Channel();CountDownLatch entered=new CountDownLatch(1),go=new CountDownLatch(1);
        a.start(x->{x.ownChannel(ch);entered.countDown();go.await();});await(entered);
        check(!a.requestStop(ID,"foreign",NONCE)&&ch.open);check(!a.requestStop(ID,"model-1",ID)&&ch.open);stop(a);stop(a);go.countDown();retired(a);check(ch.closes==1);
    }
    static void failuresRetained()throws Exception{
        DisplayStreamAttempt a=attempt();a.start(x->{x.ownResource(()->{throw new IOException();});throw new IllegalStateException();});DisplayStreamAttempt.Snapshot s=a.awaitRetirement(5000);check(s.workerRetired&&!s.resourcesRetired&&s.failures.contains("BODY_FAILED")&&s.failures.contains("RESOURCE_RELEASE_FAILED"));
        DisplayStreamAttempts slot=new DisplayStreamAttempts();slot.install(a);check(!slot.available());
    }
    static void observerInterrupt()throws Exception{
        DisplayStreamAttempt a=attempt();CountDownLatch entered=new CountDownLatch(1),go=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();a.start(x->{entered.countDown();go.await();});await(entered);
        Thread observer=new Thread(()->{try{a.awaitRetirement(0);}catch(InterruptedException e){interrupted.set(true);}});observer.start();observer.interrupt();observer.join(5000);check(interrupted.get()&&!a.snapshot().workerRetired);go.countDown();retired(a);
    }
    static void codecBufferFinally()throws Exception{
        android.media.MediaCodec codec=new android.media.MediaCodec();android.view.Surface surface=new android.view.Surface();
        java.lang.reflect.Constructor<H264SurfaceEncoder> c=H264SurfaceEncoder.class.getDeclaredConstructor(android.media.MediaCodec.class,android.view.Surface.class);c.setAccessible(true);H264SurfaceEncoder encoder=c.newInstance(codec,surface);
        try{encoder.drainAvailable(1,(pts,flags,payload)->{throw new ClosedChannelException();});throw new AssertionError();}catch(ClosedChannelException expected){}
        check(codec.releasedOutputs==1);encoder.close();check(codec.releases==1&&surface.releases==1);
    }
    static void controlsAndWire()throws Exception{
        DisplayStreamAttempt a=attempt();String control=DisplayStreamControl.stop(a);check(!DisplayStreamControl.matches((control+" ").getBytes("US-ASCII"),a));check(!DisplayStreamControl.matches(control.replace(NONCE,ID).getBytes("US-ASCII"),a));check(!DisplayStreamControl.matches(control.replace("\"action\":","\"extra\":1,\"action\":").getBytes("US-ASCII"),a));
        try{DisplayStreamControl.line(new ByteArrayInputStream(new byte[514]));throw new AssertionError();}catch(IllegalArgumentException expected){}
        check(java.util.Arrays.equals(DisplayStreamControl.line(new ByteArrayInputStream((control+"\n").getBytes("US-ASCII"))),control.getBytes("US-ASCII")));
        class Gather implements GatheringByteChannel{ByteArrayOutputStream out=new ByteArrayOutputStream();public boolean isOpen(){return true;}public void close(){}public int write(ByteBuffer b){int n=b.remaining();while(b.hasRemaining())out.write(b.get());return n;}public long write(ByteBuffer[] b){return write(b,0,b.length);}public long write(ByteBuffer[] b,int o,int l){long n=0;for(int i=o;i<o+l;i++)n+=write(b[i]);return n;}}
        Gather g=new Gather();Rmanvid1Writer writer=new Rmanvid1Writer(g);writer.writeHeader(640,480,new org.json.JSONObject());writer.writePacket(0,2,ByteBuffer.wrap(new byte[]{1,2}),4,5);writer.writePacket(0,1,ByteBuffer.wrap(new byte[]{3}),6,7);writer.writePacket(33333,0,ByteBuffer.wrap(new byte[]{4}),8,9);
        ByteBuffer raw=ByteBuffer.wrap(g.out.toByteArray()).order(ByteOrder.BIG_ENDIAN);byte[] magic=new byte[8];raw.get(magic);check(new String(magic,"US-ASCII").equals("RMANVID1"));check(raw.getInt()==1&&raw.getInt()==1&&raw.getInt()==640&&raw.getInt()==480&&raw.getInt()==0);int metadata=raw.getInt();raw.position(raw.position()+metadata);check(raw.getLong()==0&&raw.getInt()==2&&raw.getInt()==2&&raw.getLong()==4&&raw.getLong()==5);check(raw.get()==1&&raw.get()==2);check(raw.getLong()==0&&raw.getInt()==1&&raw.getInt()==1&&raw.getLong()==6&&raw.getLong()==7&&raw.get()==3);check(raw.getLong()==33333&&raw.getInt()==0);
    }
    static void eosPayload()throws Exception{
        android.media.MediaCodec codec=new android.media.MediaCodec(){
            public int dequeueOutputBuffer(android.media.MediaCodec.BufferInfo info,long timeout){
                info.offset=0;info.size=4;info.flags=BUFFER_FLAG_END_OF_STREAM|1;return 0;
            }
        };
        java.lang.reflect.Constructor<H264SurfaceEncoder> c=H264SurfaceEncoder.class.getDeclaredConstructor(android.media.MediaCodec.class,android.view.Surface.class);
        c.setAccessible(true);H264SurfaceEncoder encoder=c.newInstance(codec,new android.view.Surface());
        AtomicInteger calls=new AtomicInteger();
        check(encoder.drainAvailable(1,(pts,flags,payload)->{check(flags==1&&payload.remaining()==4);calls.incrementAndGet();}));
        check(calls.get()==1&&codec.releasedOutputs==1);encoder.close();
    }
    public static void main(String[]args)throws Exception{
        blockedWriter();System.out.println("PASS actual blocked Pipe writer retirement");stopDuringRegistration();System.out.println("PASS stop during channel registration");pendingAndStale();System.out.println("PASS pending replacement and stale owner isolation");foreignAndDuplicate();System.out.println("PASS foreign and duplicate stop");failuresRetained();System.out.println("PASS first/release failure preservation");observerInterrupt();System.out.println("PASS interrupted observer retains worker");codecBufferFinally();System.out.println("PASS actual codec buffer finally release (modeled codec)");controlsAndWire();System.out.println("PASS closed controls and actual writer config/key/PTS");eosPayload();System.out.println("PASS final EOS payload retains key flag without wire EOS extension");
    }
}
