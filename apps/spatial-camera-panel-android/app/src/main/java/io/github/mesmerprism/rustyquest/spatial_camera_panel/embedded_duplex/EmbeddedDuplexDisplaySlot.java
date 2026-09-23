package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/** Process-owned, generation-bound attachment to an Activity display surface. */
final class EmbeddedDuplexDisplaySlot implements EmbeddedDuplexDisplay {
    interface Cleanup { void run() throws Exception; }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final ThreadLocal<Boolean> inCallback = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> cleanupPermit = ThreadLocal.withInitial(() -> false);
    private EmbeddedDuplexDisplay attached;
    private long generation;
    private int inFlight;
    private boolean detaching;
    private boolean cleanupRunning;

    long attach(EmbeddedDuplexDisplay next) {
        if (next == null || next == this) throw new IllegalArgumentException("display attachment");
        lock.lock();
        try {
            if (attached != null || detaching || cleanupRunning || inFlight != 0
                    || generation == Long.MAX_VALUE) {
                throw new IllegalStateException("prior display attachment or barrier pending");
            }
            generation++;
            attached = next;
            return generation;
        } finally { lock.unlock(); }
    }

    /** Fence new calls, run typed product cleanup, then drain and clear. A failed
     * cleanup or barrier retains the old attachment for an exact retry. */
    void detachAfterCleanup(long expectedGeneration, Cleanup cleanup) throws Exception {
        if (cleanup == null || Boolean.TRUE.equals(inCallback.get())
                || Boolean.TRUE.equals(cleanupPermit.get())) {
            throw new IllegalStateException("recursive display detach");
        }
        lock.lock();
        try {
            if (attached == null || generation != expectedGeneration || cleanupRunning) {
                throw new IllegalStateException("display attachment changed");
            }
            detaching = true;
            cleanupRunning = true;
            awaitIdle();
        } catch (InterruptedException interrupted) {
            cleanupRunning = false;
            Thread.currentThread().interrupt();
            throw new IllegalStateException("display barrier interrupted", interrupted);
        } catch (RuntimeException failed) {
            cleanupRunning = false;
            throw failed;
        } finally { lock.unlock(); }

        boolean cleaned = false;
        try {
            cleanupPermit.set(true);
            cleanup.run();
            cleaned = true;
        } finally {
            cleanupPermit.remove();
            lock.lock();
            try {
                cleanupRunning = false;
                if (cleaned) {
                    try {
                        awaitIdle();
                        attached = null;
                        detaching = false;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("display barrier interrupted", interrupted);
                    }
                }
            } finally { lock.unlock(); }
        }
    }

    boolean cleanupPending() {
        lock.lock();
        try { return detaching; }
        finally { lock.unlock(); }
    }

    private void awaitIdle() throws InterruptedException {
        long remaining = TimeUnit.SECONDS.toNanos(10);
        while (inFlight != 0) {
            if (remaining <= 0L) throw new IllegalStateException("display barrier pending");
            remaining = idle.awaitNanos(remaining);
        }
    }

    private <T> T call(java.util.function.Function<EmbeddedDuplexDisplay, T> action) {
        if (Boolean.TRUE.equals(inCallback.get())) {
            throw new IllegalStateException("recursive synchronous display callback");
        }
        EmbeddedDuplexDisplay current;
        lock.lock();
        try {
            if (attached == null || (detaching && !Boolean.TRUE.equals(cleanupPermit.get()))) {
                throw new IllegalStateException("display attachment unavailable");
            }
            current = attached;
            inFlight++;
        } finally { lock.unlock(); }
        inCallback.set(true);
        try { return action.apply(current); }
        finally {
            inCallback.remove();
            lock.lock();
            try {
                inFlight--;
                if (inFlight == 0) idle.signalAll();
            } finally { lock.unlock(); }
        }
    }

    @Override public long ensureLocalCaptureStopped() {
        return call(EmbeddedDuplexDisplay::ensureLocalCaptureStopped);
    }
    @Override public long preparePeerProjection() {
        return call(EmbeddedDuplexDisplay::preparePeerProjection);
    }
    @Override public void bindPeerProjection(long routeGeneration, long decoderToken,
            long readerGeneration) {
        call(display -> { display.bindPeerProjection(routeGeneration, decoderToken, readerGeneration); return null; });
    }
    @Override public void activatePeerProjection(long routeGeneration, long decoderToken,
            long readerGeneration) {
        call(display -> { display.activatePeerProjection(routeGeneration, decoderToken, readerGeneration); return null; });
    }
    @Override public long[] currentProjection(long routeGeneration) {
        return call(display -> display.currentProjection(routeGeneration));
    }
    @Override public void retirePeerProjection() {
        call(display -> { display.retirePeerProjection(); return null; });
    }
    @Override public void restoreLocalAfterProductCleanup() {
        call(display -> { display.restoreLocalAfterProductCleanup(); return null; });
    }
}
