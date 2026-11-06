package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Actual lifecycle gate + asynchronous audio runtime, with a platform callback held for a main-thread revocation. */
public final class SdkOwnerWaiterTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static void await(CountDownLatch latch)throws Exception{check(latch.await(3,TimeUnit.SECONDS),"bounded fixture timeout");}
    static final class Factory implements ConditionAudioRuntime.MediaBackendFactory {
        final CountDownLatch restoring=new CountDownLatch(1);volatile ConditionAudioRebindTest.Backend delayed;int restores;
        public ConditionAudioRuntime.MediaBackend create(ConditionAudioContract.Provider p,ConditionAudioRuntime.MediaBackend.Listener l){return new ConditionAudioRebindTest.Backend(l,0);}
        public ConditionAudioRuntime.MediaBackend createRestored(ConditionAudioContract.Provider p,final ConditionAudioRuntime.MediaBackend.Listener l,long position){
            final ConditionAudioRebindTest.Backend backend=new ConditionAudioRebindTest.Backend(l,position);
            if(restores++!=0)return backend;
            delayed=backend;
            return new ConditionAudioRuntime.MediaBackend(){
                public void prepare(boolean looping){restoring.countDown();} // Real runtime waits for the actual callback.
                public void start(){backend.start();}public long pause(){return backend.pause();}
                public long currentPositionMs(){return backend.currentPositionMs();}public void stop(){backend.stop();}public void release(){backend.release();}
            };
        }
    }
    public static void main(String[] args)throws Exception{
        final ConditionAudioRebindTest.Scope scope=new ConditionAudioRebindTest.Scope();ConditionAudioContract.installOwnerScope(scope);
        String sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ConditionAudioContract.TrustedPackagedInventory inventory=ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(
            ConditionAudioContract.PACKAGED_PROVIDER_ORIGIN,sha,new ConditionAudioContract.Provider[]{new ConditionAudioContract.Provider("condition-alpha","asset/alpha","audio-alpha",sha,101,"audio/ogg")});
        Factory factory=new Factory();CountDownLatch prepared=new CountDownLatch(1),started=new CountDownLatch(1),paused=new CountDownLatch(1);
        final ConditionAudioRuntime runtime=ConditionAudioRuntime.createForTest(inventory,factory,r->{
            if(r.event==ConditionAudioContract.Event.PREPARED)prepared.countDown();if(r.event==ConditionAudioContract.Event.ACTUAL_START)started.countDown();if(r.event==ConditionAudioContract.Event.PAUSED)paused.countDown();
        });
        final SdkOwnerLifecycle gate=new SdkOwnerLifecycle();final AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        try{
            check(runtime.submit(ConditionAudioContract.Command.prepare(1,"prepare","condition-alpha")).accepted,"prepare");await(prepared);
            check(runtime.submit(ConditionAudioContract.Command.start(1,"start")).accepted,"start");await(started);
            check(runtime.submit(ConditionAudioContract.Command.pause(1,"pause")).accepted,"pause");await(paused);
            SdkOwnerLifecycle.Attempt first=gate.begin(1,11,12);gate.runCurrent(first,()->gate.nativeBound(first));gate.publish(first,()->{});
            gate.revoke(1,false,epoch->{scope.revoke();return "retired";});
            final SdkOwnerLifecycle.Attempt attempt=gate.begin(2,21,22);
            final ConditionAudioRuntime.OwnerTransitionGuard guard=action->gate.runCurrent(attempt,action);
            final CountDownLatch workerDone=new CountDownLatch(1);final boolean[] published={false};
            Thread worker=new Thread(()->{try{
                ConditionAudioRuntime.OwnerTransfer transfer=runtime.prepareOwnerTransfer(guard);
                gate.runCurrent(attempt,()->{scope.replace();gate.nativeBound(attempt);});
                runtime.attachOwnerTransfer(transfer,guard); // Pending platform callback; monitor must be free.
                published[0]=gate.publish(attempt,()->{});
            }catch(Throwable error){failure.set(error);}finally{workerDone.countDown();}},"fixture-binding-worker");worker.start();
            await(factory.restoring);check(gate.actualEpoch()==2&&gate.publishedEpoch()==0,"actual native owner not visible during wait");
            final AtomicLong revoked=new AtomicLong();final CountDownLatch mainProgress=new CountDownLatch(1);
            Thread mainCallback=new Thread(()->{
                gate.revoke(2,false,epoch->{revoked.set(epoch);scope.revoke();return "retired";});
                factory.delayed.player.listener.onPrepared();mainProgress.countDown();
            },"fixture-main-callback");mainCallback.start();
            check(mainProgress.await(1,TimeUnit.SECONDS),"main revoke waited for restoration or blocked its platform callback");
            await(workerDone);check(failure.get()==null,"binding fixture threw: "+failure.get());
            check(revoked.get()==2&&!published[0]&&gate.publishedEpoch()==0,"revoked unpublished owner was missed or stale waiter published");
            final SdkOwnerLifecycle.Attempt next=gate.begin(3,31,32);
            ConditionAudioRuntime.OwnerTransitionGuard nextGuard=action->gate.runCurrent(next,action);
            ConditionAudioRuntime.OwnerTransfer nextTransfer=runtime.prepareOwnerTransfer(nextGuard);
            gate.runCurrent(next,()->{scope.replace();gate.nativeBound(next);});runtime.attachOwnerTransfer(nextTransfer,nextGuard);
            check(gate.publish(next,()->{}),"actual replacement could not publish");
            final int[] obsoleteEffects={0};check(!gate.runCurrent(attempt,()->obsoleteEffects[0]++),"retired waiter retained effects");
            check(obsoleteEffects[0]==0&&gate.publishedEpoch()==3,"retired waiter modified replacement");
            gate.revoke(2,false,epoch->{throw new AssertionError("old lifecycle callback reached replacement");});
            check(gate.publishedEpoch()==3,"old revoke cleared replacement publication");
            SdkOwnerLifecycle retries=new SdkOwnerLifecycle();SdkOwnerLifecycle.Attempt waiting=retries.begin(8,81,82);
            check(retries.scheduleRetry(waiting)&&!retries.scheduleRetry(waiting),"retry was not coalesced");
            retries.revoke(8,false,epoch->{throw new AssertionError("prebind cancel revoked a nonexistent native owner");});
            check(!retries.resumeRetry(waiting)&&!retries.runCurrent(waiting,()->{throw new AssertionError("cancelled native bind ran");}),"prebind retry survived revoke");
            SdkOwnerLifecycle.Attempt bounded=retries.begin(9,91,92);
            for(int i=0;i<100;i++){check(retries.scheduleRetry(bounded),"retry budget ended early");check(retries.resumeRetry(bounded),"retry did not retain exact request");}
            check(!retries.scheduleRetry(bounded)&&"prebind-retry-exhausted".equals(retries.phase()),"retry was unbounded");
            SdkOwnerLifecycle.Attempt consumed=retries.begin(10,101,102);retries.runCurrent(consumed,()->retries.nativeBound(consumed));
            check(!retries.scheduleRetry(consumed),"consumed native epoch was retried");
        }finally{if(factory.delayed!=null)factory.delayed.player.listener.onPrepared();runtime.close();check(runtime.awaitClosed(3000),"audio close timeout");}
        System.out.println("SdkOwnerWaiterTest PASS");
    }
}
