package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real ConditionAudioRuntime; fake platform player identities model native event lookup. Separate JVM. */
public final class ConditionAudioRebindTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void await(CountDownLatch value)throws Exception{check(value.await(3,TimeUnit.SECONDS),"fixture timeout");}
    static final class Scope implements ConditionAudioContract.OwnerScope {
        Object token=new Object();boolean live=true;
        public synchronized Object captureAudioOwnerToken(){return token;}
        public synchronized boolean isAudioOwnerCurrent(Object t,long generation,boolean active){return t==token&&(!active||live);}
        public synchronized boolean run(Object t,long generation,boolean active,Runnable action){if(!isAudioOwnerCurrent(t,generation,active))return false;action.run();return true;}
        synchronized void revoke(){live=false;}synchronized void replace(){token=new Object();live=true;}
    }
    static final class Player {
        ConditionAudioRuntime.MediaBackend.Listener listener;long position;boolean released;
        Runnable queuedNativeStart(){final Player nativeMessageOwner=this;return new Runnable(){public void run(){
            // Platform dispatch resolves the listener from the message's actual player, not a saved Java listener.
            nativeMessageOwner.listener.onActualStart(nativeMessageOwner.position);
        }};}
    }
    static final class Backend implements ConditionAudioRuntime.MediaBackend {
        final Player player;int starts;
        Backend(ConditionAudioRuntime.MediaBackend.Listener listener,long position){player=new Player();player.listener=listener;player.position=position;}
        public void prepare(boolean looping){check(!looping,"unexpected looping");player.listener.onPrepared();}
        public void start(){check(!player.released,"retired player started");starts++;player.queuedNativeStart().run();}
        public long pause(){return player.position;}
        public long currentPositionMs(){return player.position;}
        public void stop(){}public void release(){player.released=true;}
    }
    static final class Factory implements ConditionAudioRuntime.MediaBackendFactory {
        Backend initial,restored;long requested;
        public ConditionAudioRuntime.MediaBackend create(ConditionAudioContract.Provider p,ConditionAudioRuntime.MediaBackend.Listener l){return initial=new Backend(l,0);}
        public ConditionAudioRuntime.MediaBackend createRestored(ConditionAudioContract.Provider p,ConditionAudioRuntime.MediaBackend.Listener l,long position){
            check(initial.player.released,"new player published before old player closed");requested=position;return restored=new Backend(l,position);
        }
    }
    public static void main(String[] args)throws Exception{
        Scope scope=new Scope();ConditionAudioContract.installOwnerScope(scope);
        String sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ConditionAudioContract.TrustedPackagedInventory inventory=ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(
            ConditionAudioContract.PACKAGED_PROVIDER_ORIGIN,sha,new ConditionAudioContract.Provider[]{
                new ConditionAudioContract.Provider("condition-alpha","asset/alpha","audio-alpha",sha,101,"audio/ogg")});
        Factory factory=new Factory();CountDownLatch prepared=new CountDownLatch(1),started=new CountDownLatch(1),paused=new CountDownLatch(1),resumed=new CountDownLatch(1),stopped=new CountDownLatch(1);
        ConditionAudioRuntime runtime=ConditionAudioRuntime.createForTest(inventory,factory,value->{
            if(value.event==ConditionAudioContract.Event.PREPARED)prepared.countDown();
            if(value.event==ConditionAudioContract.Event.ACTUAL_START)started.countDown();
            if(value.event==ConditionAudioContract.Event.PAUSED)paused.countDown();
            if(value.event==ConditionAudioContract.Event.RESUMED)resumed.countDown();
            if(value.event==ConditionAudioContract.Event.STOPPED)stopped.countDown();
        });
        try{
            check(runtime.submit(ConditionAudioContract.Command.prepare(1,"prepare","condition-alpha")).accepted,"prepare");await(prepared);
            check(runtime.submit(ConditionAudioContract.Command.start(1,"start")).accepted,"start");await(started);
            factory.initial.player.position=731;Runnable lateNativeEvent=factory.initial.player.queuedNativeStart();
            check(runtime.submit(ConditionAudioContract.Command.pause(1,"pause")).accepted,"pause");await(paused);scope.revoke();
            ConditionAudioRuntime.OwnerTransfer transfer=runtime.prepareOwnerTransfer();
            check(factory.initial.player.released,"old backend must close before platform rebind");scope.replace();runtime.attachOwnerTransfer(transfer);
            check(factory.initial.player!=factory.restored.player&&factory.requested==731,"restoration must use new player and actual acknowledged position");
            lateNativeEvent.run(); // Native message created before transition resolves old player's listener now.
            check(runtime.snapshot().phase==ConditionAudioContract.Phase.PAUSED,"old start mutated rebound state");
            check(runtime.submit(ConditionAudioContract.Command.resume(1,"resume")).accepted,"resume");await(resumed);
            check(runtime.snapshot().phase==ConditionAudioContract.Phase.PLAYING&&factory.restored.starts==1,"actual replacement start did not settle resume");
            lateNativeEvent.run();factory.initial.player.listener.onError("late-retired-error");
            check(runtime.submit(ConditionAudioContract.Command.stop(1,"stop",ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER)).accepted,"stop after rebound resume");await(stopped);
            check(runtime.snapshot().phase==ConditionAudioContract.Phase.STOPPED,"late old callback retained a pending phase");
        }finally{runtime.close();check(runtime.awaitClosed(3000),"owner close timeout");}
        System.out.println("ConditionAudioRebindTest PASS");
    }
}
