package io.github.mesmerprism.rustyquest.media;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual framed sockets/task owners. Controlled zero estimates; no sleeps, artificial kill or replay. */
public final class OwnedExchangeRetentionConformanceMain {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void run(boolean stop,boolean publisherThrows)throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<OwnerDispatchTcpEndpoint.RequestSpan> request=new AtomicReference<>();
        AtomicInteger handlers=new AtomicInteger(),warnings=new AtomicInteger();
        AtomicReference<Throwable> publisherFault=new AtomicReference<>();
        byte[] frame={1,2,3},response={4,5,6};
        OwnerDispatchTcpEndpoint endpoint=new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(),0,actual->{
            handlers.incrementAndGet();OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
            request.set(span);entered.countDown();
            try{check(span!=null && !span.terminal(),"actual request task must be live inside handler");
                release.await();check(Arrays.equals(frame,actual),"same framed input");return response;
            }finally{release.countDown();}
        });
        OwnerDispatchTcpEndpoint.ExchangeControl control=new OwnerDispatchTcpEndpoint.ExchangeControl();
        CompletableFuture<byte[]> actual=new CompletableFuture<>();
        Thread worker=new Thread(()->{
            try{actual.complete(OwnerDispatchTcpEndpoint.exchangeOwnedObserved(InetAddress.getLoopbackAddress(),endpoint.port(),frame,null,
                control,0,0,label->{
                    try{
                        entered.await();if(warnings.incrementAndGet()!=1)return;
                        check(!actual.isDone() && !control.joined().isDone(),"warning cannot settle owned exchange");
                        check(!request.get().terminal(),"handler remains owned at warning");
                        if(stop)control.requestStop();
                        if(publisherThrows)throw new IllegalStateException("intentional diagnostic publisher failure");
                    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();publisherFault.compareAndSet(null,interrupted);}
                    catch(AssertionError failure){publisherFault.compareAndSet(null,failure);}
                    finally{release.countDown();}
                }));}
            catch(Throwable failure){actual.completeExceptionally(failure);}
        },"modeled-owned-exchange-caller");
        worker.start();
        try{
            if(stop){
                try{actual.get();throw new AssertionError("desired Stop must not return success");}
                catch(ExecutionException expected){check(expected.getCause() instanceof java.io.IOException,"actual socket failure retained");}
            }else check(Arrays.equals(response,actual.get()),"same original actual response survives diagnostic failure");
            worker.join();check(control.joined().isDone(),"same socket and publisher executor joined");
        }finally{release.countDown();control.requestStop();worker.join();endpoint.close();}
        check(endpoint.terminal() && request.get().terminal(),"actual handler/socket/task and endpoint joined");
        check(handlers.get()==1 && warnings.get()>=1,"one issued original and actual diagnostic warning");
        if(publisherFault.get()!=null)throw new AssertionError("unexpected publisher qualification fault",publisherFault.get());
    }
    public static void main(String[] ignored)throws Exception{
        run(false,false);run(false,true);run(true,false);
        System.out.println("Owned exchange same-original/warning/sink-failure/Stop/task-join controls PASS");
    }
}
