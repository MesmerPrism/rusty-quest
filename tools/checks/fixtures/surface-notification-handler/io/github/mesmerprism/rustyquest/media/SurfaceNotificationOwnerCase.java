package io.github.mesmerprism.rustyquest.media;

import android.graphics.SurfaceTexture;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual production registration and terminal owner; synthetic Android loopers and textures. */
public final class SurfaceNotificationOwnerCase {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(2L, TimeUnit.SECONDS), "fixture callback deadline");
    }
    public static void main(String[] args) throws Exception {
        Thread glOwner = Thread.currentThread();
        PackedStereoGlCompositor.SurfaceNotificationOwner owner =
                new PackedStereoGlCompositor.SurfaceNotificationOwner();
        SurfaceTexture left = new SurfaceTexture(1), right = new SurfaceTexture(2);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch rightObserved = new CountDownLatch(1), leftEntered = new CountDownLatch(1);
        CountDownLatch leaveLeft = new CountDownLatch(1);
        owner.right(right, texture -> {
            try {
                check(Thread.currentThread() != glOwner
                        && Thread.currentThread().getName().equals("rq-capture-surface-notify")
                        && Looper.myLooper() != null && Looper.myLooper() != Looper.getMainLooper(),
                        "notification must use its dedicated looper");
                callbacks.incrementAndGet();
            } catch (Throwable failure) { failed.set(failure); }
            finally { rightObserved.countDown(); }
        });
        owner.left(left, texture -> {
            callbacks.incrementAndGet(); leftEntered.countDown();
            try { await(leaveLeft); }
            catch (Throwable failure) { failed.set(failure); }
        });
        try {
            right.emit(); await(rightObserved);
            check(left.textureUpdates == 0 && right.textureUpdates == 0,
                    "notification owner must not consume textures");
            left.updateTexImage(); right.updateTexImage();
            check(left.textureUpdates == 1 && right.textureUpdates == 1,
                    "texture operations stay on the caller GL owner");
            left.emit(); await(leftEntered);
            Thread closer = new Thread(owner::close, "fixture-close-owner");
            closer.start(); await(left.detached); await(right.detached);
            check(!owner.retired() && closer.isAlive(),
                    "detach or quit request must not fabricate thread retirement");
            leaveLeft.countDown(); closer.join(2_000L);
            check(!closer.isAlive() && owner.retired(), "actual callback retirement must complete close");
            left.emit(); right.emit(); owner.close();
            check(callbacks.get() == 2 && failed.get() == null,
                    "closed listeners stay detached and owner close is idempotent: " + failed.get());
        } finally { leaveLeft.countDown(); owner.close(); }
        System.out.println("surface-notification-owner PASS; synthetic Android, no device or GL effects");
    }
}
