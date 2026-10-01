package io.github.mesmerprism.rustyquest.media;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic clock/progress interleaving for the actual source deadline. */
public final class MonotonicFreshnessDeadlineCase {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        MonotonicFreshnessDeadline deadline = new MonotonicFreshnessDeadline(3_000L);
        check(!deadline.freshAtCurrentTime(() -> 1_000L), "unobserved");
        deadline.progress(1_000L);

        // Old runtime order: caller reads 1001, producer progresses to 1002,
        // then fresh(1001) rejects a physically newer frame as future.
        long callerSample = 1_001L;
        deadline.progress(1_002L);
        check(!deadline.fresh(callerSample), "reproduced pre-lock clock race");
        check(deadline.freshAtCurrentTime(() -> 1_002L), "current clock accepts progress");

        check(deadline.freshAtCurrentTime(() -> 4_002L), "exact 3000ms bound");
        check(!deadline.freshAtCurrentTime(() -> 4_003L), "stale after 3000ms");
        check(!deadline.freshAtCurrentTime(() -> -1L), "negative clock fails closed");
        check(!deadline.freshAtCurrentTime(() -> 1_001L), "future progress fails closed");

        deadline.progress(5_000L);
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            producerStarted.countDown();
            try { deadline.progress(5_001L); }
            catch (Throwable failure) { producerFailure.set(failure); }
            finally { producerFinished.countDown(); }
        }, "freshness-progress-interleave");

        check(deadline.freshAtCurrentTime(() -> {
            producer.start();
            try {
                check(producerStarted.await(1L, TimeUnit.SECONDS), "producer did not start");
                check(!producerFinished.await(20L, TimeUnit.MILLISECONDS),
                        "producer advanced during locked clock sample");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return 5_000L;
        }), "locked sample remains current");
        producer.join(1_000L);
        check(!producer.isAlive() && producerFailure.get() == null, "producer completion");
        check(deadline.freshAtCurrentTime(() -> 5_001L), "new progress visible");
        System.out.println("PASS actual deadline pre-lock race, locked clock, exact bound, future/stale/negative");
    }
}
