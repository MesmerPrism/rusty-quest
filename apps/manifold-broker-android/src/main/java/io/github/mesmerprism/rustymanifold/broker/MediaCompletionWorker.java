package io.github.mesmerprism.rustymanifold.broker;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded off-Looper executor for JNI-to-platform media owner callbacks. */
final class MediaCompletionWorker {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "rusty-media-owner-completion");
                    thread.setDaemon(false);
                    return thread;
                }
            }, new ThreadPoolExecutor.AbortPolicy());
    private MediaCompletionWorker() { }
    static boolean submit(Runnable task) {
        try { EXECUTOR.execute(task); return true; }
        catch (java.util.concurrent.RejectedExecutionException full) { return false; }
    }
}
