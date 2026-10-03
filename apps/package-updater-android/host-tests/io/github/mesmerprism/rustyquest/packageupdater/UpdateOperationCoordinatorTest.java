package io.github.mesmerprism.rustyquest.packageupdater;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class UpdateOperationCoordinatorTest {
    private static int cases;

    public static void main(String[] arguments) throws Exception {
        require(UpdateOperationCoordinator.run(() -> 7) == 7, "result preserved");
        Exception primary = new Exception("original pipeline failure");
        try {
            UpdateOperationCoordinator.run(() -> { throw primary; });
            throw new AssertionError("failure swallowed");
        } catch (Exception actual) {
            require(actual == primary, "original failure preserved");
        }
        require(UpdateOperationCoordinator.run(() -> 8) == 8, "failure releases");
        UpdateOperationCoordinator.run(() -> {
            denied(() -> UpdateOperationCoordinator.run(() -> 99));
            denied(() -> UpdateOperationCoordinator.run(() -> 100));
            return null;
        });
        require(UpdateOperationCoordinator.run(() -> 9) == 9, "reentry releases");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger sessions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread ui = new Thread(() -> {
            try {
                UpdateOperationCoordinator.run(() -> {
                    downloads.incrementAndGet();
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("bounded race fixture timed out");
                    }
                    sessions.incrementAndGet();
                    return null;
                });
            } catch (Throwable error) { failure.set(error); }
        }, "updater-ui-check");
        ui.start();
        require(entered.await(5, TimeUnit.SECONDS), "UI owns slot");
        try {
            denied(() -> UpdateOperationCoordinator.run(() -> {
                downloads.incrementAndGet();sessions.incrementAndGet();return null;
            }));
            // A rejected CLI call must not release the UI slot for a third call.
            denied(() -> UpdateOperationCoordinator.run(() -> {
                downloads.incrementAndGet();sessions.incrementAndGet();return null;
            }));
            require(downloads.get() == 1 && sessions.get() == 0,
                    "rejected calls perform zero download/session work");
        } finally { release.countDown();ui.join(5000); }
        require(!ui.isAlive() && failure.get() == null, "owned UI completed");
        require(downloads.get() == 1 && sessions.get() == 1, "one session only");
        require(UpdateOperationCoordinator.run(() -> 10) == 10, "next call admitted");
        try {
            UpdateOperationCoordinator.run(() -> { throw new InterruptedException(); });
            throw new AssertionError("interruption swallowed");
        } catch (InterruptedException expected) { cases++; }
        require(UpdateOperationCoordinator.run(() -> 11) == 11, "cancellation releases");
        System.out.println("Update operation coordination passed: " + cases + " cases");
    }

    private static void denied(Checked action) throws Exception {
        try { action.run();throw new AssertionError("overlapping operation admitted"); }
        catch (IllegalStateException error) {
            require("update_operation_already_active".equals(error.getMessage()),
                    "stable overlap result");
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) { throw new AssertionError(name); }cases++;
    }

    private interface Checked { void run() throws Exception; }
}
