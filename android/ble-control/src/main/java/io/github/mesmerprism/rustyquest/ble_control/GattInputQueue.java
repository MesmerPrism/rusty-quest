package io.github.mesmerprism.rustyquest.ble_control;

import java.io.Closeable;
import java.util.Arrays;
import java.util.concurrent.*;

/** Existing single worker/four pending writes, with isolated and cleared carrier bytes. */
public final class GattInputQueue implements Closeable {
    public interface Action { void accept(byte[] bytes); }
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(4));
    private abstract static class InputTask implements Runnable {
        final byte[] held;InputTask(byte[] held){this.held=held;}final void clear(){Arrays.fill(held,(byte)0);}
    }
    public boolean submit(byte[] value,final Action action){
        final byte[] copy=value.clone();
        try{worker.execute(new InputTask(copy){public void run(){try{action.accept(copy);}finally{clear();}}});return true;}
        catch(RejectedExecutionException denied){Arrays.fill(copy,(byte)0);return false;}
    }
    public void close(){for(Runnable pending:worker.shutdownNow())if(pending instanceof InputTask)((InputTask)pending).clear();}
}
