package io.github.mesmerprism.rustyquest.media;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual AAR loopback/worker/ExchangeControl; Native activation is explicitly modeled. */
public final class OwnerDispatchLaneOverlapHost {
    static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    static final class Span {
        final String peer;
        final ExecutorService command=Executors.newSingleThreadExecutor();
        final OwnerDispatchTcpEndpoint.ExchangeControl exchange=new OwnerDispatchTcpEndpoint.ExchangeControl();
        final AtomicReference<Thread> commandThread=new AtomicReference<>();
        final AtomicReference<Thread> handlerThread=new AtomicReference<>();
        final AtomicReference<OwnerDispatchTcpEndpoint.RequestSpan> incoming=new AtomicReference<>();
        final CompletableFuture<Void> entered=new CompletableFuture<>();
        final AtomicInteger issues=new AtomicInteger(),collections=new AtomicInteger();
        CompletableFuture<byte[]> future;
        Throwable failure;byte[] result;
        Span(String peer){this.peer=peer;}
        void issue(OwnerDispatchTcpEndpoint remote)throws Exception {
            require(issues.incrementAndGet()==1,"same original was reissued");
            future=CompletableFuture.supplyAsync(()->{
                commandThread.set(Thread.currentThread());
                try{return OwnerDispatchTcpEndpoint.exchangeOwned(InetAddress.getLoopbackAddress(),remote.port(),
                    ("MODELED-NATIVE-START:"+peer).getBytes(StandardCharsets.UTF_8),null,exchange);}
                catch(Exception actual){throw new java.util.concurrent.CompletionException(actual);}
            },command);
        }
        void collect(){
            if(future==null)return;
            require(collections.incrementAndGet()==1,"original collected twice");
            try{result=future.join();}catch(java.util.concurrent.CompletionException actual){failure=actual.getCause();}
            exchange.joined().join();
        }
        void closeCommand()throws Exception {
            command.shutdown();boolean interrupted=false;
            for(;;)try{if(command.awaitTermination(Long.MAX_VALUE,java.util.concurrent.TimeUnit.NANOSECONDS))break;}
                catch(InterruptedException actual){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    static void run(boolean handlerFailure,boolean secondLaunchFailure)throws Exception {
        Span q=new Span("Q"),w=new Span("W");CountDownLatch release=new CountDownLatch(1);
        OwnerDispatchTcpEndpoint[] endpoints=new OwnerDispatchTcpEndpoint[2];
        Throwable primary=null;
        try {
            for(int i=0;i<2;i++) {
                final Span local=i==0?q:w;final String expected="MODELED-NATIVE-START:"+(i==0?"W":"Q");
                endpoints[i]=new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(),0,frame->{
                    require(expected.equals(new String(frame,StandardCharsets.UTF_8)),"detached modeled native frame");
                    OwnerDispatchTcpEndpoint.RequestSpan actual=OwnerDispatchTcpEndpoint.currentRequestSpan();
                    require(actual!=null,"actual AAR request task absent");
                    local.incoming.set(actual);local.handlerThread.set(Thread.currentThread());local.entered.complete(null);
                    release.await();
                    if(handlerFailure && local==q)throw new java.io.IOException("MODELED_NATIVE_ACTIVATION_ERROR");
                    return ("MODELED-NATIVE-REPLY:"+local.peer).getBytes(StandardCharsets.UTF_8);
                });
            }
            q.issue(endpoints[1]);
            if(secondLaunchFailure)throw new IllegalStateException("MODELED_SECOND_LAUNCH_REFUSAL");
            w.issue(endpoints[0]);
            CompletableFuture<Void> both=CompletableFuture.allOf(q.entered,w.entered);
            // A real premature original terminal is counterevidence. No wall
            // clock can substitute for two actual entered AAR handler spans.
            CompletableFuture.anyOf(both,q.future,w.future).join();
            require(both.isDone() && !both.isCompletedExceptionally(),"bilateral handlers did not overlap before original terminal");
            require(!q.future.isDone() && !w.future.isDone(),"local command original was not retained during opposite handler");
            require(q.handlerThread.get()!=q.commandThread.get() && w.handlerThread.get()!=w.commandThread.get(),"incoming activation used the blocked command lane");
            require(!q.incoming.get().terminal() && !w.incoming.get().terminal(),"handler task was falsely joined while gated");
        } catch(Throwable actual) {primary=actual;}
        finally {
            // Release the fixture's producer gates before any same-original
            // wait, including one-launch/early-error paths. Never kill/reissue.
            release.countDown();
            for(Span actual:new Span[]{q,w})try{actual.collect();}
                catch(Throwable cleanup){if(primary==null)primary=cleanup;else primary.addSuppressed(cleanup);}
            for(OwnerDispatchTcpEndpoint endpoint:endpoints)if(endpoint!=null)try{endpoint.close();}
                catch(Throwable cleanup){if(primary==null)primary=cleanup;else primary.addSuppressed(cleanup);}
            for(Span actual:new Span[]{q,w})try{actual.closeCommand();}
                catch(Throwable cleanup){if(primary==null)primary=cleanup;else primary.addSuppressed(cleanup);}
        }
        if(secondLaunchFailure) {
            require(primary instanceof IllegalStateException && "MODELED_SECOND_LAUNCH_REFUSAL".equals(primary.getMessage()),"actual launch refusal was lost");
            require(primary.getSuppressed().length==0,"issued-original cleanup failure followed launch refusal");
            require(q.issues.get()==1 && q.collections.get()==1 && w.issues.get()==0 && w.collections.get()==0,"all and only issued originals were collected");
            require(q.failure==null && q.result!=null && q.exchange.joined().isDone(),"first issued original was abandoned after second launch refusal");
            require(w.incoming.get()!=null && w.incoming.get().terminal(),"first original's real incoming span was not joined");
        } else {
            if(primary!=null)throw new AssertionError("actual bilateral overlap observation failed",primary);
            require(q.issues.get()==1 && w.issues.get()==1 && q.collections.get()==1 && w.collections.get()==1,"bilateral original accounting differs");
            require(q.incoming.get().terminal() && w.incoming.get().terminal(),"actual AAR spans not joined");
            require(q.failure==null && q.result!=null,"Q original not collected");
            if(handlerFailure)require(w.failure instanceof java.io.EOFException && w.result==null,"actual lost reply must remain failure, not synthetic success");
            else require(w.failure==null && w.result!=null,"W original not collected");
        }
        for(OwnerDispatchTcpEndpoint endpoint:endpoints)require(endpoint.terminal(),"actual endpoint/thread/socket terminal missing");
    }
    public static void main(String[] args)throws Exception {
        run(false,false);run(true,false);run(false,true);
        System.out.println("PASS actual AAR handler overlap and three same-original accounting controls; Native activation modeled");
    }
}
