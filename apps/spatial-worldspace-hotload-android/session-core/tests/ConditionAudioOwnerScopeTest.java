package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real asynchronous audio runtime. Run in a separate host JVM. */
public final class ConditionAudioOwnerScopeTest {
    static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
    static void await(CountDownLatch latch)throws Exception{check(latch.await(3,TimeUnit.SECONDS),"bounded fixture timeout");}
    static final class Scope implements ConditionAudioContract.OwnerScope {
        Object token=new Object(); boolean live=true;
        volatile boolean blockActive;
        final CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1),completed=new CountDownLatch(1);
        public synchronized Object captureAudioOwnerToken(){return token;}
        public synchronized boolean isAudioOwnerCurrent(Object t,long generation,boolean active){return t==token&&(!active||live);}
        public boolean run(Object t,long generation,boolean active,Runnable action){
            if(active&&blockActive){queued.countDown();try{await(release);}catch(Exception e){throw new AssertionError(e);}}
            try{synchronized(this){if(!isAudioOwnerCurrent(t,generation,active))return false;action.run();return true;}}
            finally{if(active&&blockActive)completed.countDown();}
        }
        synchronized void revoke(){live=false;}
        synchronized void replace(){token=new Object();live=true;}
    }
    static final class Backend implements ConditionAudioRuntime.MediaBackend {
        volatile Listener listener; volatile int starts;
        final CountDownLatch prepared=new CountDownLatch(1);
        public void prepare(boolean looping){check(!looping,"unexpected loop");prepared.countDown();}
        public void start(){starts++;}
        public long pause(){return 0;}
        public void stop(){}public void release(){}
    }
    public static void main(String[] args)throws Exception{
        Scope scope=new Scope();ConditionAudioContract.installOwnerScope(scope);
        String sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ConditionAudioContract.TrustedPackagedInventory inventory=ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(
            ConditionAudioContract.PACKAGED_PROVIDER_ORIGIN,sha,new ConditionAudioContract.Provider[]{
                new ConditionAudioContract.Provider("condition-alpha","asset/alpha","audio-alpha",sha,101,"audio/ogg")});
        Backend backend=new Backend();CountDownLatch receipt=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        ConditionAudioRuntime runtime=ConditionAudioRuntime.createForTest(inventory,(provider,listener)->{backend.listener=listener;return backend;},
            value->{if(value.event==ConditionAudioContract.Event.PREPARED)receipt.countDown();if(value.event==ConditionAudioContract.Event.ERROR)cancelled.countDown();});
        try{
            check(runtime.submit(ConditionAudioContract.Command.prepare(1,"prepare","condition-alpha")).accepted,"prepare rejected");
            await(backend.prepared);backend.listener.onPrepared();await(receipt);
            scope.blockActive=true;
            check(runtime.submit(ConditionAudioContract.Command.start(1,"start")).accepted,"start admission rejected");
            await(scope.queued);scope.revoke();scope.release.countDown();await(scope.completed);
            check(backend.starts==0,"queued start played after synchronous revoke");
            await(cancelled);check(runtime.snapshot().phase==ConditionAudioContract.Phase.ERROR,"cancelled accepted start remained pending");
            final int[] delivered={0};Object old=scope.captureAudioOwnerToken();
            Runnable oldStop=ConditionAudioContract.scoped(old,1,false,()->delivered[0]++);
            scope.replace();oldStop.run();check(delivered[0]==0,"old cleanup reached replacement with same generation");
            Runnable currentStop=ConditionAudioContract.scoped(scope.captureAudioOwnerToken(),1,false,()->delivered[0]++);
            scope.revoke();currentStop.run();check(delivered[0]==1,"correctly scoped retired cleanup was lost");
        }finally{runtime.close();check(runtime.awaitClosed(3000),"audio close did not complete");}
        System.out.println("ConditionAudioOwnerScopeTest PASS");
    }
}
