import java.net.ServerSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual Host/resource bodies are inserted by the source-pinned owner runner. */
public final class WholeLocalCleanupHost {
    static int checks;
    static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void refused(CompletableFuture<?> result)throws Exception{try{result.get(3,TimeUnit.SECONDS);throw new AssertionError("accepted");}catch(ExecutionException expected){checks++;}}
    static class EmbeddedDuplexOriginals {
        static class Record {final Row row=new Row();long appGeneration=10;Object retainedCleanupOwner;final AtomicInteger commands=new AtomicInteger();}
        static class Row {String operation="whole_app_close";String getString(String key){return operation;}}
        static final ThreadLocal<Record> current=new ThreadLocal<>();
        static Record capture(){return current.get();}
        static Runnable carryActual(Record r,Runnable body){r.commands.incrementAndGet();return ()->{Record old=current.get();current.set(r);try{body.run();}finally{current.set(old);r.commands.decrementAndGet();}};}
    }
    static class EmbeddedDuplexPlatform {boolean fenced;final CountDownLatch joined=new CountDownLatch(0);void fenceOwnedForwardEffects(){fenced=true;}void awaitOwnedForwardEffectsJoined()throws InterruptedException{if(!fenced)throw new IllegalStateException("unfenced");joined.await();}}
    static class OwnStereoCaptureRuntime {static volatile OwnStereoCaptureRuntime current;volatile String state="pending";static OwnStereoCaptureRuntime currentForApplication(){return current;}EmbeddedDuplexOriginals.Row physicalRetirementObservation(){return new EmbeddedDuplexOriginals.Row(){@Override String getString(String key){return state;}};}}
    static class EmbeddedDuplexRendererRetirement {static volatile boolean terminal=true;static boolean currentTerminal(){return terminal;}}
    static class EmbeddedDuplexNative {static volatile boolean camera=true;static boolean localCameraQuiescent(){return camera;}}
    static class Incoming {int calls;boolean terminal;RuntimeException failure;CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);void retireOwnedLocalResources(){calls++;entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}if(failure!=null)throw failure;terminal=true;}Incoming snapshot(){return this;}boolean terminal(){return terminal;}}
    static class PackedStereoMediaSourceRuntime {
        static class Runtime {final Pipeline owner;volatile boolean delayed,terminal;String state="stopping";int drainPolls;Runtime(Pipeline owner){this.owner=owner;}void stop(String reason){requestOwnedLocalStop(reason);terminal=owner.stopped;}void requestOwnedLocalStop(String reason){owner.calls++;if(owner.failure!=null)throw owner.failure;if(!delayed)owner.stopped=true;}void continueOwnedLocalStop(){drainPolls++;}boolean resourcesReleased(){return owner.stopped;}boolean isFullyStopped(){return terminal&&owner.stopped;}}
        static class Pipeline {volatile int calls;volatile boolean stopped;RuntimeException failure;volatile Runtime runtime=new Runtime(this);
            /* ACTUAL_PIPELINE_METHOD */
            void requireStopped(){if(!stopped)throw new IllegalStateException("Pending");}
        }
    }
    static class RuntimeDrain {
        final Object lifecycleLock=new Object();boolean stopRequested=true;Thread sourceThread;
        java.net.ServerSocket serverSocket;int released;
        void closeRetiredConnection(){}void retryUnresolvedConnection(){}void closeQuietly(Object socket){}
        void ensureStopDrainOwner(){}void releaseLocalResources(){check(Thread.holdsLock(lifecycleLock),"source-owner check and leaf retirement share lifecycle lock");released++;}
        /* ACTUAL_SOURCE_CONTINUATION */
    }
    static void sourceContinuationOwnership()throws Exception{
        RuntimeDrain drain=new RuntimeDrain();drain.continueOwnedLocalStop();check(drain.released==1,"absent source leaves retired");
        CountDownLatch exit=new CountDownLatch(1);Thread source=new Thread(()->{try{exit.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});source.start();
        drain.sourceThread=source;drain.continueOwnedLocalStop();check(drain.released==1,"live codec drain owner retains leaves");exit.countDown();source.join();
        drain.continueOwnedLocalStop();check(drain.released==2,"dead source permits same local retirement");
    }
    static class ReaderOwner {interface ProjectionResource{boolean release();}ProjectionResource staged;long generation=4;
        /* ACTUAL_READER_METHOD */
    }
    static class EmbeddedDuplexResources {
        final Incoming incoming=new Incoming();final long generation=4;PackedStereoMediaSourceRuntime.Pipeline pipeline=new PackedStereoMediaSourceRuntime.Pipeline();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);int drainCalls;
        void runPhysical(){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
        /* ACTUAL_RESOURCE_METHOD */
    }
    enum Phase{READY,FAILED,BOOTSTRAPPING,CLOSING}
    static class Fence {long generation=10;void requireLive(long g){if(g!=generation)throw new IllegalStateException("fence changed");}long generation(){return generation;}void beforeNativeAbsenceRetirement(Object c,Object e){}}
    static class Display {int calls;void requestWholeProjectionStop(){calls++;}}
    static class Host {
        final ExecutorService commands=Executors.newSingleThreadExecutor();final AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);
        EmbeddedDuplexResources resources=new EmbeddedDuplexResources(){@Override void retireOwnedLocalResources(){drainCalls++;runPhysical();super.retireOwnedLocalResources();}};
        EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform();long nativeExecutorGeneration=4,attachmentGeneration=1;String runtimeConfigSha256="config";
        final Fence processFence=new Fence();final Display display=new Display();boolean localFixture,closeInFlight;
        private volatile RetainedWholeLocalDrain wholeLocalDrain;
        interface Work<T>{T run()throws Exception;}
        <T> CompletableFuture<T> submit(Work<T> work){CompletableFuture<T> result=new CompletableFuture<>();EmbeddedDuplexOriginals.Record r=EmbeddedDuplexOriginals.capture();commands.execute(EmbeddedDuplexOriginals.carryActual(r,()->{try{result.complete(work.run());}catch(Throwable e){result.completeExceptionally(e);}}));return result;}
        Object checkpointSnapshot(){return null;}Object evidenceSnapshot(){return null;}
        void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record r){if(r.appGeneration!=processFence.generation)throw new IllegalStateException("actor differs");}
        /* ACTUAL_HOST_METHODS */
    }
    static CompletableFuture<Void> start(Host h,EmbeddedDuplexOriginals.Record r){EmbeddedDuplexOriginals.current.set(r);try{return h.drainWholeLocalResources(1);}finally{EmbeddedDuplexOriginals.current.remove();}}
    static void pendingAndExactOwner()throws Exception{
        Host h=new Host();EmbeddedDuplexOriginals.Record r=new EmbeddedDuplexOriginals.Record();EmbeddedDuplexResources held=h.resources;
        held.release=new CountDownLatch(1);CompletableFuture<Void> f=start(h,r);check(held.entered.await(2,TimeUnit.SECONDS),"worker entered");
        check(!f.isDone(),"same callback barrier Pending");check(h.phase.get()==Phase.CLOSING,"ordinary new effects fenced");
        check(h.commands.submit(()->7).get(1,TimeUnit.SECONDS)==7,"command lane available to callbacks");
        CompletableFuture<Void> same=start(h,r);check(!same.isDone(),"same Original joins held Future");
        refused(start(h,new EmbeddedDuplexOriginals.Record()));
        check(held.drainCalls==1,"physical stop entered once");held.release.countDown();f.get(2,TimeUnit.SECONDS);same.get(2,TimeUnit.SECONDS);
        check(held.incoming.terminal&&held.pipeline.stopped&&h.display.calls==1,"actual local barriers joined");
        check(r.retainedCleanupOwner==h.wholeLocalDrain,"same owner custody");h.commands.shutdown();
    }
    static void lateOwnerAndFailure()throws Exception{
        Host h=new Host();EmbeddedDuplexOriginals.Record r=new EmbeddedDuplexOriginals.Record();EmbeddedDuplexResources held=h.resources;
        held.release=new CountDownLatch(1);CompletableFuture<Void> f=start(h,r);held.entered.await();h.attachmentGeneration=2;held.release.countDown();refused(f);
        check(h.wholeLocalDrain!=null&&r.retainedCleanupOwner==h.wholeLocalDrain,"failure retains exact owner");h.commands.shutdown();
        h=new Host();r=new EmbeddedDuplexOriginals.Record();h.resources.incoming.failure=new IllegalStateException("decoder refused");f=start(h,r);refused(f);
        check(h.resources.pipeline.calls==1&&h.resources.pipeline.stopped,"other local direction retired after decoder failure");
        check(h.phase.get()==Phase.CLOSING,"failure remains fenced");h.commands.shutdown();
    }
    static void guards()throws Exception{
        for(int kind=0;kind<5;kind++){
            Host h=new Host();EmbeddedDuplexOriginals.Record r=new EmbeddedDuplexOriginals.Record();
            switch(kind){case 0:r.row.operation="start";break;case 1:r.appGeneration=9;break;case 2:h.localFixture=true;break;case 3:h.closeInFlight=true;break;default:h.attachmentGeneration=2;}
            refused(start(h,r));check(h.resources.drainCalls==0,"invalid owner no physical effect");h.commands.shutdown();
        }
        Host constructing=new Host();constructing.phase.set(Phase.BOOTSTRAPPING);start(constructing,new EmbeddedDuplexOriginals.Record()).get(2,TimeUnit.SECONDS);check(constructing.resources.drainCalls==1,"actual partial bootstrap resources retired");constructing.commands.shutdown();
        Host h=new Host();h.resources=null;start(h,new EmbeddedDuplexOriginals.Record()).get(2,TimeUnit.SECONDS);check(h.wholeLocalDrain==null,"absence uses existing no-media owner");h.commands.shutdown();
    }
    static void actualPhysicalWaits()throws Exception{
        Host physical=new Host();OwnStereoCaptureRuntime own=new OwnStereoCaptureRuntime();OwnStereoCaptureRuntime.current=own;
        EmbeddedDuplexRendererRetirement.terminal=false;EmbeddedDuplexNative.camera=false;CompletableFuture<Void> joined=start(physical,new EmbeddedDuplexOriginals.Record());
        physical.resources.entered.await(2,TimeUnit.SECONDS);Thread.sleep(75);check(!joined.isDone(),"Own/renderer/camera actual joins retained");
        own.state="retired";EmbeddedDuplexRendererRetirement.terminal=true;Thread.sleep(75);check(!joined.isDone(),"camera terminal not invented");
        EmbeddedDuplexNative.camera=true;joined.get(2,TimeUnit.SECONDS);check(physical.display.calls==1,"same local projection stop");physical.commands.shutdown();OwnStereoCaptureRuntime.current=null;
        EmbeddedDuplexResources duplex=new EmbeddedDuplexResources();duplex.incoming.release=new CountDownLatch(1);
        CompletableFuture<Void> duplexDone=new CompletableFuture<>();EmbeddedDuplexOriginals.Record original=new EmbeddedDuplexOriginals.Record();
        Thread both=new Thread(EmbeddedDuplexOriginals.carryActual(original,()->{try{duplex.retireOwnedLocalResources();duplexDone.complete(null);}catch(Throwable e){duplexDone.completeExceptionally(e);}}));both.start();
        check(duplex.incoming.entered.await(2,TimeUnit.SECONDS),"incoming actual drain entered");
        long waiting=System.nanoTime()+2_000_000_000L;while(duplex.pipeline.calls==0&&System.nanoTime()<waiting)Thread.sleep(1);
        check(duplex.pipeline.calls==1&&!duplexDone.isDone(),"outgoing independently retired while incoming Pending");
        duplex.incoming.release.countDown();duplexDone.get(2,TimeUnit.SECONDS);both.join();check(original.commands.get()==0,"both actual carried workers joined");
        PackedStereoMediaSourceRuntime.Pipeline pipeline=new PackedStereoMediaSourceRuntime.Pipeline();pipeline.runtime.delayed=true;pipeline.runtime.state="failed";
        CompletableFuture<Void> source=CompletableFuture.runAsync(()->pipeline.stopOwnedLocalResources("actual held owner"));
        long end=System.nanoTime()+2_000_000_000L;while(pipeline.calls==0&&System.nanoTime()<end)Thread.sleep(1);
        check(pipeline.calls==1&&!source.isDone(),"actual pipeline keeps same stop/join Pending");pipeline.stopped=true;source.get(2,TimeUnit.SECONDS);
        check(pipeline.calls==1,"actual pipeline does not repeat Stop");check(pipeline.runtime.terminal&&"failed".equals(pipeline.runtime.state),"new physical fact preserves old failure state");
        ReaderOwner owner=new ReaderOwner();AtomicBoolean released=new AtomicBoolean();AtomicInteger probes=new AtomicInteger();owner.staged=()->{probes.incrementAndGet();return released.get();};
        CompletableFuture<Void> reader=CompletableFuture.runAsync(()->owner.awaitOwnedReaderRelease(owner.staged));
        end=System.nanoTime()+2_000_000_000L;while(probes.get()==0&&System.nanoTime()<end)Thread.sleep(1);
        check(!reader.isDone(),"actual held reader remains Pending");released.set(true);reader.get(2,TimeUnit.SECONDS);check(probes.get()>0,"actual reader release joined");
        ReaderOwner changed=new ReaderOwner();ReaderOwner.ProjectionResource old=()->false;changed.staged=()->true;
        refused(CompletableFuture.runAsync(()->changed.awaitOwnedReaderRelease(old)));
    }
    public static void main(String[] args)throws Exception{pendingAndExactOwner();lateOwnerAndFailure();guards();actualPhysicalWaits();sourceContinuationOwnership();System.out.println("Whole local cleanup: "+checks+" checks passed");}
}
