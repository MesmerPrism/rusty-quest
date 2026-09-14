package io.github.mesmerprism.rustyquest.media.conformance;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;
import io.github.mesmerprism.rustyquest.media.StereoFrameIdentity;
import io.github.mesmerprism.rustyquest.media.StereoFrameLease;
import io.github.mesmerprism.rustyquest.media.StereoFrameSource;
import io.github.mesmerprism.rustyquest.media.StereoFrameSubscription;

/** App-owned changing synthetic source; it is not shipped as a production provider. */
final class SyntheticStereoFrameSource implements StereoFrameSource {
    private final long generation;
    private final int frameCount;
    private final int failAtFrame;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger releases = new AtomicInteger();

    SyntheticStereoFrameSource(long generation, int frameCount, int failAtFrame) {
        this.generation=generation; this.frameCount=frameCount; this.failAtFrame=failAtFrame;
    }
    @Override public StereoFrameSubscription subscribe(StereoFrameSubscription.Listener listener) {
        if(listener==null || !subscribed.compareAndSet(false,true)) throw new IllegalStateException("one subscription only");
        Subscription subscription=new Subscription(listener);
        subscription.worker.start(); return subscription;
    }
    @Override public MediaRuntimeSnapshot snapshot() {
        return new MediaRuntimeSnapshot(generation,0,closed.get()?"stopped":"started",closed.get(),"");
    }
    int releaseCount(){return releases.get();}
    @Override public void close(){closed.set(true);}

    private final class Subscription implements StereoFrameSubscription {
        final AtomicBoolean stopped=new AtomicBoolean(); final Thread worker; final Listener listener;
        Subscription(Listener listener){this.listener=listener;worker=new Thread(new Runnable(){
            @Override public void run(){produce();}},"synthetic-stereo-source");}
        void produce(){
            for(int frame=0;frame<frameCount&&!stopped.get()&&!closed.get();frame++){
                if(frame==failAtFrame){stopped.set(true);listener.onTerminal(
                        new MediaRuntimeSnapshot(generation,frame+1,"failed",true,"injected_failure"));return;}
                long timestamp=1_000_000L+frame*16_666_667L;
                StereoFrameIdentity id=new StereoFrameIdentity(generation,frame,timestamp,timestamp+500_000L,
                        timestamp/1_000L);
                listener.onFrame(new StereoFrameLease(id,new Runnable(){@Override public void run(){releases.incrementAndGet();}}));
            }
            stopped.set(true);listener.onTerminal(new MediaRuntimeSnapshot(generation,frameCount+1,"stopped",true,"complete"));
        }
        @Override public long generation(){return generation;}
        @Override public boolean isClosed(){return stopped.get();}
        @Override public void close(){stopped.set(true);worker.interrupt();}
    }
}
