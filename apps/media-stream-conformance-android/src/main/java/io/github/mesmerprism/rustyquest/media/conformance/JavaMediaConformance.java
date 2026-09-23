package io.github.mesmerprism.rustyquest.media.conformance;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.mesmerprism.rustyquest.media.BoundedPacketPump;
import io.github.mesmerprism.rustyquest.media.CancellationHandle;
import io.github.mesmerprism.rustyquest.media.ExactPresentationTracker;
import io.github.mesmerprism.rustyquest.media.MediaOwnerAction;
import io.github.mesmerprism.rustyquest.media.MediaOwnerProvider;
import io.github.mesmerprism.rustyquest.media.MediaProductBinding;
import io.github.mesmerprism.rustyquest.media.MediaProviderReadback;
import io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;
import io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry;
import io.github.mesmerprism.rustyquest.media.ReconnectKeyframeGate;
import io.github.mesmerprism.rustyquest.media.StereoFrameIdentity;
import io.github.mesmerprism.rustyquest.media.StereoFrameLease;
import io.github.mesmerprism.rustyquest.media.StereoFramePairer;
import io.github.mesmerprism.rustyquest.media.StereoFrameSubscription;

/** Bounded app-owned exercise of the shared AAR contracts. */
final class JavaMediaConformance {
    private JavaMediaConformance() { }
    static String run() {
        try {
            final ExactPresentationTracker tracker=new ExactPresentationTracker(8);
            final CountDownLatch terminal=new CountDownLatch(1);
            final SyntheticStereoFrameSource source=new SyntheticStereoFrameSource(41,4,-1);
            source.subscribe(new StereoFrameSubscription.Listener(){
                @Override public void onFrame(StereoFrameLease frame){tracker.submit(frame);}
                @Override public void onTerminal(MediaRuntimeSnapshot snapshot){terminal.countDown();}
            });
            if(!terminal.await(2,TimeUnit.SECONDS))throw new IllegalStateException("synthetic timeout");
            for(int frame=0;frame<4;frame++){
                long pts=(1_000_000L+frame*16_666_667L)/1_000L;
                tracker.take(pts).close();
            }
            if(source.releaseCount()!=4)throw new IllegalStateException("release mismatch");
            boolean missingRejected=false;
            try{tracker.take(1000);}catch(IllegalStateException expected){missingRejected=true;}
            if(!missingRejected)throw new IllegalStateException("missing PTS accepted");

            AtomicInteger expiryReleases=new AtomicInteger();
            StereoFramePairer pairer=new StereoFramePairer(2,100L);
            StereoFrameLease unmatched=new StereoFrameLease(
                    new StereoFrameIdentity(41,99,5_000L,5_500L,5L),expiryReleases::incrementAndGet);
            pairer.offer(StereoFramePairer.Eye.LEFT,unmatched,1_000L);
            if(pairer.expire(1_099L)!=0||pairer.expire(1_100L)!=1
                    ||expiryReleases.get()!=1)throw new IllegalStateException("pair expiry mismatch");
            pairer.close();

            AtomicReference<MediaRuntimeSnapshot> injectedTerminal=new AtomicReference<>();
            CountDownLatch injectedDone=new CountDownLatch(1);
            SyntheticStereoFrameSource failing=new SyntheticStereoFrameSource(42,4,1);
            failing.subscribe(new StereoFrameSubscription.Listener(){
                @Override public void onFrame(StereoFrameLease frame){frame.close();}
                @Override public void onTerminal(MediaRuntimeSnapshot snapshot){
                    injectedTerminal.set(snapshot);injectedDone.countDown();}
            });
            if(!injectedDone.await(2,TimeUnit.SECONDS)
                    ||injectedTerminal.get()==null
                    ||!"failed".equals(injectedTerminal.get().state())
                    ||failing.releaseCount()!=1)throw new IllegalStateException("failure injection mismatch");

            CancellationHandle cancellation=new CancellationHandle(41);cancellation.cancel();
            boolean staleRejected=false;
            try{cancellation.requireCurrent(41);}catch(IllegalStateException expected){staleRejected=true;}
            if(!staleRejected)throw new IllegalStateException("late generation accepted");
            ReconnectKeyframeGate gate=new ReconnectKeyframeGate();gate.connected(3);
            if(gate.accept(3,true))throw new IllegalStateException("keyframe accepted before config");
            gate.configurationSent(3);if(gate.accept(3,false)||!gate.accept(3,true))
                throw new IllegalStateException("reconnect keyframe gate mismatch");

            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            BoundedPacketPump pump=new BoundedPacketPump(bytes,2,"conformance-memory-pump");
            if(!pump.offer(new byte[]{1,2,3})||!pump.offer(new byte[]{4,5,6}))
                throw new IllegalStateException("bounded transport rejected initial packets");
            long pumpDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(bytes.size()!=6&&System.nanoTime()<pumpDeadline)Thread.sleep(1L);
            pump.close();if(pump.failure()!=null||!pump.isTerminated()||bytes.size()!=6)
                throw new IllegalStateException("memory pump failed");

            MediaOwnerProvider provider=new MediaOwnerProvider(){
                @Override public MediaProviderReadback execute(MediaOwnerAction action,CancellationHandle handle){
                    return new MediaProviderReadback(action,"synthetic.handle",1,"started","synthetic.receipt");}
                @Override public MediaProviderReadback compensate(MediaOwnerAction action,CancellationHandle handle){
                    return new MediaProviderReadback(action,"synthetic.handle",2,"stopped","synthetic.stop");}
                @Override public MediaRuntimeSnapshot snapshot(){return new MediaRuntimeSnapshot(41,1,"started",false,"","synthetic.handle");}
            };
            MediaProductBinding binding=new MediaProductBinding.Builder("android-duplex-conformance")
                    .bind("source","owner.synthetic","synthetic_stereo","source.synthetic",provider).build();
            PackagedAndroidMediaOwnerRegistry registry=new PackagedAndroidMediaOwnerRegistry(41,binding);
            boolean undeclaredRejected=false;
            try{registry.execute(ticket("source.other"),false);}catch(IllegalStateException expected){undeclaredRejected=true;}
            registry.close();if(!undeclaredRejected)throw new IllegalStateException("undeclared provider accepted");
            return new JSONObject().put("$schema","rusty.quest.android.media.conformance.java.v1")
                    .put("result","pass").put("changing_frames",4).put("exact_releases",source.releaseCount())
                    .put("pair_expiry_releases",expiryReleases.get()).put("failure_injected",true)
                    .put("in_memory_bytes",bytes.size()).put("undeclared_rejected",true).toString();
        }catch(Exception error){
            try{return new JSONObject().put("$schema","rusty.quest.android.media.conformance.java.v1")
                    .put("result","fail").put("error",error.getClass().getSimpleName()).toString();}
            catch(Exception impossible){return "{\"result\":\"fail\"}";}
        }
    }
    private static String ticket(String resource){return "{\"$schema\":\"rusty.quest.android.media.execution-ticket.v1\","
            +"\"capability\":\"cap.1\",\"executor_generation\":41,\"action_id\":\"action.1\","
            +"\"authority_epoch_id\":\"epoch.1\",\"media_acceptance_authority_revision\":1,"
            +"\"expected_runtime_revision\":1,\"client_id\":\"client.1\",\"lease_id\":\"lease.1\","
            +"\"sequence\":1,\"operation\":\"start\",\"owner_kind\":\"source\","
            +"\"action_kind\":\"start\",\"owner_id\":\"owner.synthetic\","
            +"\"provider_kind\":\"synthetic_stereo\",\"resource_id\":\""+resource+"\"}";}
}
