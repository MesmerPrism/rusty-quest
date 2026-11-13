// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;

/** Original physical close orchestration; no external capture or vendor acknowledgment authority. */
public final class PackedReaderRetirement {
    public interface Resources {
        boolean postSeal(Runnable originalBarrier);
        void detachListener();boolean quitOriginalWorkerSafely();boolean originalWorkerAlive();
        void joinTerminatedOriginalWorker() throws InterruptedException;
        void closeOriginalReader();void observeReaderDestroyedAndRetired();
    }
    private final PackedInputLeaseQueue<?> queue;private final Resources resources;
    private boolean requested,joined,closeAttempted,closed;
    private volatile boolean barrier;
    private volatile Throwable failure;
    public PackedReaderRetirement(PackedInputLeaseQueue<?> queue,Resources resources) {
        if(queue==null||resources==null)throw new IllegalArgumentException("original resources required");this.queue=queue;this.resources=resources;
    }
    /** Call only after the external controller admits its original producer retirement. No retry on unknown post. */
    public synchronized void request() {
        queue.stopPublishing();if(requested)return;requested=true;
        try {
            if(!resources.postSeal(()->{
                try { queue.seal();resources.detachListener();if(!resources.quitOriginalWorkerSafely())throw new IllegalStateException("original worker quit not accepted");barrier=true; }
                catch(RuntimeException error) { failure=error; }
            }))throw new IllegalStateException("original seal barrier not accepted");
        } catch(RuntimeException error) { failure=error; }
    }
    /** Control worker only. A pending/blocked acquisition or copy never becomes completion by elapsed time. */
    public synchronized boolean pollClosed() {
        if(closed)return true;
        if(failure!=null||!barrier||resources.originalWorkerAlive())return false;
        if(!joined) {
            joined=true;
            try { resources.joinTerminatedOriginalWorker(); }
            catch(InterruptedException error) { Thread.currentThread().interrupt();failure=error;return false; }
            catch(RuntimeException error) { failure=error;return false; }
        }
        if(!queue.drained()||closeAttempted)return false;
        closeAttempted=true;
        try { resources.closeOriginalReader();resources.observeReaderDestroyedAndRetired();closed=true;return true; }
        catch(RuntimeException error) { failure=error;return false; }
    }
    public Throwable failure() { return failure; }
}
