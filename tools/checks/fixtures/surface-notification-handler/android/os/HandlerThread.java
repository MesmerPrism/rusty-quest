package android.os;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class HandlerThread extends Thread {
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Runnable terminal = () -> { };
    private final Looper looper = new Looper();
    private volatile boolean stopping;
    public HandlerThread(String name) { super(name); looper.owner = this; }
    public Looper getLooper() { return looper; }
    boolean post(Runnable action) { return !stopping && queue.offer(action); }
    public boolean quitSafely() { stopping = true; queue.offer(terminal); return true; }
    @Override public void run() {
        Looper.CURRENT.set(looper);
        try {
            for (;;) {
                Runnable action = queue.take();
                if (action == terminal) return;
                action.run();
            }
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
        finally { Looper.CURRENT.remove(); }
    }
}
