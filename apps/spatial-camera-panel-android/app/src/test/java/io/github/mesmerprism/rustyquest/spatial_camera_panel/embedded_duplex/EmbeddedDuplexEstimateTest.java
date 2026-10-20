package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import org.junit.Test;

/** Controlled causal estimates, no sleeps/deadlines and no Android/native effects. */
public final class EmbeddedDuplexEstimateTest {
    static final class Original implements Future<String>{
        final CompletableFuture<String> actual=new CompletableFuture<>();int timed,plain,cancelled;
        Throwable failure;boolean interrupt;
        public String get(long ignored,TimeUnit unit)throws TimeoutException {timed++;throw new TimeoutException("modeled estimate");}
        public String get()throws InterruptedException,ExecutionException{
            plain++;if(interrupt)throw new InterruptedException("actual observer interrupted");
            if(failure!=null)actual.completeExceptionally(failure);else actual.complete("same-original");return actual.get();
        }
        public boolean cancel(boolean interrupt){cancelled++;return actual.cancel(interrupt);}
        public boolean isDone(){return actual.isDone();}public boolean isCancelled(){return actual.isCancelled();}
    }
    @Test public void estimateDoesNotReplaceOrCancelOriginal()throws Exception{
        Original original=new Original();assertEquals("same-original",EmbeddedDuplexEstimate.await(original,15,TimeUnit.SECONDS,"Own ready"));
        assertEquals(1,original.timed);assertEquals(1,original.plain);assertEquals(0,original.cancelled);
    }
    @Test public void actualFailureAfterEstimateRemainsSameCause(){
        Original original=new Original();original.failure=new IllegalStateException("actual partial effect failure");
        ExecutionException actual=assertThrows(ExecutionException.class,()->EmbeddedDuplexEstimate.await(original,15,TimeUnit.SECONDS,"ready"));
        assertSame(original.failure,actual.getCause());assertEquals(0,original.cancelled);
    }
    @Test public void interruptedObserverRetainsPendingOwnerAndInterruptFlag(){
        Original original=new Original();original.interrupt=true;
        try{assertThrows(InterruptedException.class,()->EmbeddedDuplexEstimate.await(original,15,TimeUnit.SECONDS,"ready"));
            assertTrue(Thread.currentThread().isInterrupted());assertFalse(original.actual.isDone());assertEquals(0,original.cancelled);
        }finally{Thread.interrupted();}
    }
}
