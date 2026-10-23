package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Production runtime and lifecycle gate. Uses actual five-second production timeouts, no injected clock. Separate JVM. */
public final class ConditionAudioTransferFailureTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static void await(CountDownLatch latch)throws Exception{check(latch.await(3,TimeUnit.SECONDS),"fixture barrier timeout");}
    static ConditionAudioContract.TrustedPackagedInventory inventory(){
        String sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        return ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(ConditionAudioContract.PACKAGED_PROVIDER_ORIGIN,sha,
            new ConditionAudioContract.Provider[]{new ConditionAudioContract.Provider("condition-alpha","asset/alpha","audio-alpha",sha,101,"audio/ogg")});
    }
    static class Factory implements ConditionAudioRuntime.MediaBackendFactory {
        final CountDownLatch releasing=new CountDownLatch(1),releaseAllowed=new CountDownLatch(1);
        volatile boolean blockRelease;boolean restoreError,restoreTimeout;
        volatile Player initial,restored;
        public ConditionAudioRuntime.MediaBackend create(ConditionAudioContract.Provider p,ConditionAudioRuntime.MediaBackend.Listener l){return initial=new Player(l,this,false);}
        public ConditionAudioRuntime.MediaBackend createRestored(ConditionAudioContract.Provider p,ConditionAudioRuntime.MediaBackend.Listener l,long position){return restored=new Player(l,this,true);}
    }
    static final class Player implements ConditionAudioRuntime.MediaBackend {
        final ConditionAudioRuntime.MediaBackend.Listener listener;final Factory factory;final boolean restoring;
        volatile boolean released;int releaseCount;
        Player(ConditionAudioRuntime.MediaBackend.Listener l,Factory f,boolean restoring){listener=l;factory=f;this.restoring=restoring;}
        public void prepare(boolean looping){
            if(restoring&&factory.restoreError)throw new IllegalStateException("fixture-platform-prepare-error");
            if(!restoring||!factory.restoreTimeout)listener.onPrepared();
        }
        public void start(){check(!released,"released player start");listener.onActualStart(0);}
        public long pause(){return 0;}public long currentPositionMs(){check(!released,"released player position");return 0;}
        public void stop(){}
        public void release(){
            if(!restoring&&factory.blockRelease){factory.releasing.countDown();try{
                check(factory.releaseAllowed.await(10,TimeUnit.SECONDS),"release barrier timeout");
            }catch(InterruptedException error){throw new AssertionError(error);}}
            released=true;releaseCount++;
        }
    }
    static final class Preloader extends Factory implements ConditionAudioRuntime.StartupPreloader {
        final CountDownLatch entered=new CountDownLatch(1),allowed=new CountDownLatch(1);
        public void preload(ConditionAudioContract.Provider[] providers,ConditionAudioRuntime.StartupPreloader.Listener listener)throws Exception{
            entered.countDown();check(allowed.await(10,TimeUnit.SECONDS),"preload barrier timeout");
            for(ConditionAudioContract.Provider provider:providers)listener.onTrackReady(provider);
        }
    }
    static ConditionAudioRuntime prepared(Factory factory)throws Exception{
        CountDownLatch ready=new CountDownLatch(1);
        ConditionAudioRuntime runtime=ConditionAudioRuntime.createForTest(inventory(),factory,r->{if(r.event==ConditionAudioContract.Event.PREPARED)ready.countDown();});
        check(runtime.submit(ConditionAudioContract.Command.prepare(1,"prepare","condition-alpha")).accepted,"prepare admission");await(ready);return runtime;
    }
    static void close(ConditionAudioRuntime runtime)throws Exception{runtime.close();check(runtime.awaitClosed(3000),"exact resource close failed");}
    static void queuedTimeout()throws Exception{
        Preloader factory=new Preloader();ConditionAudioRuntime runtime=ConditionAudioRuntime.createForTest(inventory(),factory,null);
        try{
            await(factory.entered);
            try{runtime.prepareOwnerTransfer();throw new AssertionError("queue did not time out");}catch(TimeoutException expected){}
            factory.allowed.countDown();
            ConditionAudioRuntime.OwnerTransfer next=runtime.prepareOwnerTransfer(); // Serial barrier after cancelled task.
            check(next.phase==ConditionAudioContract.Phase.IDLE,"timed-out queued task mutated current state");
            runtime.attachOwnerTransfer(next);
            check(runtime.submit(ConditionAudioContract.Command.prepare(1,"after-timeout","condition-alpha")).accepted,"cancelled queued transfer stranded admission");
        }finally{factory.allowed.countDown();close(runtime);}
    }
    static void timeoutDuringRelease()throws Exception{
        Factory factory=new Factory();ConditionAudioRuntime runtime=prepared(factory);factory.blockRelease=true;
        try{
            try{runtime.prepareOwnerTransfer();throw new AssertionError("release did not time out");}catch(TimeoutException expected){}
            check(factory.releasing.getCount()==0,"timeout did not overlap actual release");factory.releaseAllowed.countDown();
            ConditionAudioRuntime.OwnerTransfer next=runtime.prepareOwnerTransfer();
            check(next.phase==ConditionAudioContract.Phase.ERROR&&factory.initial.released,"entered transfer did not settle after release");
            runtime.attachOwnerTransfer(next);
            check(runtime.submit(ConditionAudioContract.Command.stop(1,"stop",ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER)).accepted,"failed transfer retained pending admission");
        }finally{factory.releaseAllowed.countDown();close(runtime);}
    }
    static void abandonedPreparedTransfer()throws Exception{
        Factory factory=new Factory();ConditionAudioRuntime runtime=prepared(factory);
        try{
            ConditionAudioRuntime.OwnerTransfer abandoned=runtime.prepareOwnerTransfer();
            runtime.retireOwnerTransfer(abandoned); // Same production finally path for nativeBind rejection or attempt cancellation.
            ConditionAudioRuntime.OwnerTransfer newer=runtime.prepareOwnerTransfer();
            check(newer.phase==ConditionAudioContract.Phase.ERROR,"rejected native bind left transfer pending");
            runtime.retireOwnerTransfer(abandoned); // Late old settlement must not retire the new transfer.
            runtime.attachOwnerTransfer(newer);
            check(runtime.submit(ConditionAudioContract.Command.stop(1,"stop",ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER)).accepted,"late old transfer contaminated replacement");
        }finally{close(runtime);}
    }
    static void restoreFailure(ConditionAudioRebindTest.Scope scope,boolean timeout)throws Exception{
        Factory factory=new Factory();factory.restoreError=!timeout;factory.restoreTimeout=timeout;
        ConditionAudioRuntime runtime=prepared(factory);SdkOwnerLifecycle gate=new SdkOwnerLifecycle();
        try{
            SdkOwnerLifecycle.Attempt old=gate.begin(1,11,12);gate.runCurrent(old,()->gate.nativeBound(old));gate.publish(old,()->{});
            SdkOwnerLifecycle.Attempt next=gate.begin(2,21,22);
            ConditionAudioRuntime.OwnerTransitionGuard guard=action->gate.runCurrent(next,action);
            ConditionAudioRuntime.OwnerTransfer transfer=runtime.prepareOwnerTransfer(guard);
            gate.runCurrent(next,()->{scope.replace();gate.nativeBound(next);});
            try{runtime.attachOwnerTransfer(transfer,guard);throw new AssertionError("restoration failure reported success");}
            catch(TimeoutException expected){check(timeout,"unexpected timeout");}
            catch(java.util.concurrent.ExecutionException expected){check(!timeout,"timeout case failed before actual wait");}
            finally{runtime.retireOwnerTransfer(transfer);}
            gate.failed(next);
            check(gate.actualEpoch()==2&&gate.revokeEpoch()==2&&"native-bound-failed".equals(gate.phase()),"failed restore lost adopted cleanup identity");
            // Late callback cannot accept restoration or settle any next transfer.
            if(timeout)factory.restored.listener.onPrepared();
            AtomicLong retired=new AtomicLong();
            gate.revoke(gate.revokeEpoch(),true,epoch->{retired.set(epoch);runtime.close();return "terminal-cleanup-requested";});
            gate.destroyed();check(runtime.awaitClosed(3000),"terminal destruction did not finish exact audio cleanup");
            check(retired.get()==2&&factory.restored.released&&factory.initial.released,"terminal destruction missed adopted resources");
            SdkOwnerLifecycle replacement=new SdkOwnerLifecycle();SdkOwnerLifecycle.Attempt third=replacement.begin(3,31,32);
            replacement.runCurrent(third,()->replacement.nativeBound(third));replacement.publish(third,()->{});
            replacement.revoke(2,true,epoch->{throw new AssertionError("failed old owner shut down replacement");});
            check(replacement.publishedEpoch()==3,"obsolete destruction cleared newer identity");
        }finally{close(runtime);}
    }
    public static void main(String[] args)throws Exception{
        ConditionAudioRebindTest.Scope scope=new ConditionAudioRebindTest.Scope();ConditionAudioContract.installOwnerScope(scope);
        queuedTimeout();timeoutDuringRelease();abandonedPreparedTransfer();restoreFailure(scope,false);restoreFailure(scope,true);
        System.out.println("ConditionAudioTransferFailureTest PASS");
    }
}
