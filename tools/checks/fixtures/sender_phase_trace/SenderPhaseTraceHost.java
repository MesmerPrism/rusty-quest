package io.github.mesmerprism.rustyquest.media;

import android.os.Trace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SenderPhaseTraceHost {
    private static final byte[] DATA={1,4,9,16};
    private static int cases;
    private SenderPhaseTraceHost() {}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void await(CountDownLatch latch)throws Exception{require(latch.await(2,TimeUnit.SECONDS),"fixture timed out");}
    static void join(Thread thread)throws Exception{thread.join(2000);require(!thread.isAlive(),"worker did not retire");}
    static void span(boolean on,String name){require(java.util.Objects.equals(Trace.current(),on?"rusty.media.sender."+name:null),"operation span "+name+": "+Trace.current());}
    static void balanced(){
        Map<String,ArrayDeque<String>> stacks=new HashMap<>();
        for(String event:Trace.snapshot()){
            String[] parts=event.split(":",3);
            ArrayDeque<String> stack=stacks.computeIfAbsent(parts[1],key->new ArrayDeque<>());
            if(parts[0].equals("B"))stack.push(parts[2]);
            else require(!stack.isEmpty()&&stack.pop().equals(parts[2]),"unbalanced span "+event);
        }
        for(ArrayDeque<String> stack:stacks.values())require(stack.isEmpty(),"open span after terminal outcome");
    }
    static long starts(String name){return Trace.snapshot().stream().filter(e->e.startsWith("B:")&&e.endsWith(":"+"rusty.media.sender."+name)).count();}
    static final class Output extends OutputStream {
        final boolean trace,lan,block;
        final int fail;
        final IOException error=new IOException("original fixture failure");
        final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1),flushed=new CountDownLatch(1),closed=new CountDownLatch(1);
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int writes,flushes;
        Output(boolean trace,boolean lan,boolean block,int fail){this.trace=trace;this.lan=lan;this.block=block;this.fail=fail;}
        @Override public void write(int value)throws IOException{throw new AssertionError("unexpected scalar write");}
        @Override public void write(byte[] packet,int offset,int count)throws IOException{
            span(trace,lan?"sink_write":"pump_write");writes++;entered.countDown();
            if(block)try{released.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
            if(fail==1)throw error;
            bytes.write(packet,offset,count);
        }
        @Override public void flush()throws IOException{span(trace,lan?"sink_flush":"pump_flush");flushes++;flushed.countDown();if(fail==2)throw error;}
        @Override public void close(){released.countDown();closed.countDown();}
    }
    static void pumpCase(int constructor,boolean debug,boolean enabled,int failure)throws Exception{
        Trace.reset(enabled);boolean traced=debug&&enabled;
        Output output=new Output(traced,false,false,failure);
        BoundedPacketPump pump=constructor==3?new BoundedPacketPump(output,1,"phase-pump"):
            constructor==4?new BoundedPacketPump(output,1,"phase-pump",debug):
            constructor==5?new BoundedPacketPump(output,1,8,8,"phase-pump"):
            new BoundedPacketPump(output,1,8,8,"phase-pump",debug);
        require(pump.offer(DATA),"original offer accepted");
        if(failure==0)await(output.flushed);else await(output.closed);
        pump.close();
        require(pump.isTerminated(),"pump terminal");
        require(output.writes==1&&output.flushes==(failure==1?0:1),"original write/flush calls");
        require(Arrays.equals(output.bytes.toByteArray(),failure==1?new byte[0]:DATA),"unchanged packet bytes");
        require(pump.failure()==(failure==0?null:output.error),"original IOException identity retained");
        balanced();require(starts("capacity_wait")==0,"no capacity span without actual offer wait");
        require(starts("pump_write")== (traced?1:0)&&starts("pump_flush")== (traced&&failure!=1?1:0),"exact pump spans");
        if(!debug)require(Trace.checks==0,"release/default constructors do not consult Trace");
        cases++;
    }
    static void capacity(String outcome)throws Exception{
        Trace.reset(true);Trace.capacityEntered=new CountDownLatch(1);
        Output output=new Output(true,false,true,0);
        BoundedPacketPump pump=new BoundedPacketPump(output,1,8,8,"phase-pump",true);
        require(pump.offer(DATA),"first packet");await(output.entered);require(pump.offer(DATA),"queued packet");
        AtomicBoolean cancelled=new AtomicBoolean();AtomicReference<String> result=new AtomicReference<>();
        AtomicReference<Throwable> thrown=new AtomicReference<>();
        Thread offering=new Thread(()->{
            try{result.set(Boolean.toString(pump.offerAwaitCapacity(DATA,cancelled::get)));}
            catch(InterruptedException interrupted){result.set("interrupted");}
            catch(Throwable error){thrown.set(error);}
        },"phase-capacity");
        offering.start();await(Trace.capacityEntered);
        if(outcome.equals("interrupted"))offering.interrupt();
        else if(outcome.equals("cancelled")){cancelled.set(true);pump.signalCapacityWaiters();}
        else if(outcome.equals("released"))output.released.countDown();
        else pump.close();
        join(offering);pump.close();require(thrown.get()==null,"capacity failure "+thrown.get());
        require(result.get().equals(outcome.equals("interrupted")?"interrupted":outcome.equals("released")?"true":"false"),"unchanged wait outcome");
        balanced();require(starts("capacity_wait")==1,"only actual capacity wait traced");cases++;
    }
    static void preinterruptedCapacity()throws Exception{
        Trace.reset(true);Output output=new Output(true,false,false,0);
        BoundedPacketPump pump=new BoundedPacketPump(output,1,"phase-pump",true);
        AtomicReference<Throwable> thrown=new AtomicReference<>();
        Thread offering=new Thread(()->{
            Thread.currentThread().interrupt();
            try{pump.offerAwaitCapacity(DATA,()->false);thrown.set(new AssertionError("preinterrupted offer accepted"));}
            catch(InterruptedException expected){require(!Thread.currentThread().isInterrupted(),"original interrupted status consumed");}
            catch(Throwable error){thrown.set(error);}
        },"phase-capacity");
        offering.start();join(offering);pump.close();require(thrown.get()==null,"preinterrupt outcome "+thrown.get());
        require(output.writes==0&&output.flushes==0&&Trace.snapshot().isEmpty(),"no span or write before actual wait");cases++;
    }
    static final class Input extends InputStream {
        final boolean trace;final int mode;
        final IOException error=new IOException("original input failure");
        final CountDownLatch blocked=new CountDownLatch(1),released=new CountDownLatch(1);
        int reads;
        Input(boolean trace,int mode){this.trace=trace;this.mode=mode;}
        @Override public int read()throws IOException{throw new AssertionError("unexpected scalar read");}
        @Override public int read(byte[] target,int offset,int length)throws IOException{
            span(trace,"source_read");reads++;
            if(mode==1)throw error;
            if(mode==2)return -1;
            if(mode==3&&reads==1)return 0;
            if(reads==(mode==3?2:1)){System.arraycopy(DATA,0,target,offset,DATA.length);return DATA.length;}
            blocked.countDown();
            try{released.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
            throw new IOException("actual cancellation leaf");
        }
        @Override public void close(){released.countDown();}
    }
    static void field(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static void lanCase(boolean defaultConstructor,boolean debug,boolean enabled,int readMode,int writeFailure,boolean interrupt)throws Exception{
        Trace.reset(enabled);boolean traced=debug&&enabled;
        Input input=new Input(traced,readMode);Output output=new Output(traced,true,false,writeFailure);
        Socket.reset(input,output);
        PackedStereoLanTransport lan=defaultConstructor?new PackedStereoLanTransport("127.0.0.1",401,"127.0.0.1","127.0.0.1",402):
            new PackedStereoLanTransport("127.0.0.1",401,"127.0.0.1","127.0.0.1",402,debug);
        // Enter the actual forward method with its existing prepared no-replay policy.
        // Only SDK/socket leaves are modeled; no thread, retry or deadline body is rewritten.
        field(lan,"preparedMode",true);
        Method forward=PackedStereoLanTransport.class.getDeclaredMethod("forward");forward.setAccessible(true);
        AtomicReference<Throwable> thrown=new AtomicReference<>();
        Thread worker=new Thread(()->{try{forward.invoke(lan);}catch(Throwable error){thrown.set(error);}},"phase-lan");
        field(lan,"worker",worker);worker.start();
        if(readMode==0||readMode==3){
            if(writeFailure==0){await(input.blocked);if(interrupt)worker.interrupt();else lan.requestStop();}
        }
        join(worker);require(thrown.get()==null,"LAN invocation failure "+thrown.get());
        require(lan.terminal(),"original LAN terminal cleanup");
        require(Socket.connections==2&&Socket.closes==2,"original exact connection/cleanup calls");
        int expectedReads=(readMode==0||readMode==3)&&writeFailure==0?(readMode==3?3:2):1;
        require(input.reads==expectedReads,"original input read calls");
        int expectedWrites=readMode==1||readMode==2?0:1;
        int expectedFlushes=expectedWrites==0||writeFailure==1?0:1;
        require(output.writes==expectedWrites&&output.flushes==expectedFlushes,"original LAN output calls");
        require(Arrays.equals(output.bytes.toByteArray(),expectedWrites==0||writeFailure==1?new byte[0]:DATA),"unchanged LAN bytes");
        boolean failure=readMode==1||readMode==2||writeFailure!=0||interrupt;
        require(lan.failed()==failure,"unchanged prepared failure outcome");
        String diagnostic=lan.diagnostic();
        require(diagnostic.contains("reconnects=0"),"prepared no-replay policy");
        if(readMode==1)require(diagnostic.contains("firstStage=SOURCE_READ,firstCause=IO"),"original read failure stage/cause");
        if(readMode==2)require(diagnostic.contains("firstStage=SOURCE_READ,firstCause=EOF"),"original EOF stage/cause");
        if(writeFailure!=0)require(diagnostic.contains("firstStage=SINK_WRITE,firstCause=IO")&&diagnostic.endsWith("bytes=0"),"original write failure/counter");
        balanced();require(starts("source_read")== (traced?expectedReads:0)&&starts("sink_write")== (traced?expectedWrites:0)&&starts("sink_flush")== (traced?expectedFlushes:0),"exact LAN phase counts");
        if(!debug)require(Trace.checks==0,"release/default LAN has no Trace calls");
        cases++;
    }
    public static void main(String[] args)throws Exception{
        pumpCase(3,false,true,0);pumpCase(5,false,true,0);
        pumpCase(4,true,false,0);pumpCase(4,true,true,0);pumpCase(6,false,true,0);pumpCase(6,true,true,0);
        pumpCase(6,true,true,1);pumpCase(6,true,true,2);
        capacity("interrupted");capacity("cancelled");capacity("closed");capacity("released");preinterruptedCapacity();
        lanCase(true,false,true,0,0,false);lanCase(false,false,true,0,0,false);
        lanCase(false,true,false,0,0,false);lanCase(false,true,true,0,0,false);
        lanCase(false,true,true,1,0,false);lanCase(false,true,true,2,0,false);
        lanCase(false,true,true,0,1,false);lanCase(false,true,true,0,2,false);
        lanCase(false,true,true,3,0,false);lanCase(false,true,true,0,0,true);
        System.out.println(cases+" actual pump/LAN/helper phase cases passed; bytes/calls/outcomes preserved; fake Android/socket leaves, no network/device proof.");
    }
}
