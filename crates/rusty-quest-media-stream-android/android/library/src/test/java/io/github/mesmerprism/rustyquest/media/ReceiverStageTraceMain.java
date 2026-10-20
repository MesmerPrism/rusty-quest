package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Pure deterministic clocks exercise production diagnostics; no Android/media effects. */
public final class ReceiverStageTraceMain {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static JSONObject stage(ReceiverStageTrace trace, long now, int index) throws Exception {
        return trace.snapshot(now).getJSONArray("stages").getJSONObject(index);
    }
    public static void main(String[] args) throws Exception {
        warningRetainsActualLatchAndThread();
        ReceiverStageTrace trace = new ReceiverStageTrace(7);
        trace.begin(1, 1);
        JSONObject first = stage(trace, 600_000_001L, ReceiverStageTrace.PACKET);
        check(first.getBoolean("waiting_first_progress") && first.getLong("closed_gaps") == 0,
                "read silence is first wait, not closed gap");
        trace.progress(ReceiverStageTrace.PACKET, 1, 600_000_001L, 10, 11);
        check(stage(trace, 600_000_001L, 0).getLong("first_waits_over_bound") == 1, "first wait closed separately");
        trace.progress(ReceiverStageTrace.PACKET, 1, 1_200_000_001L, 20, 21);
        JSONObject packet = stage(trace, 1_200_000_001L, 0);
        check(packet.getLong("closed_over_bound") == 1 && packet.getLong("recovered_closed_gaps") == 1,
                "read gap closes once on actual progress");
        trace.progress(ReceiverStageTrace.OUTPUT, 1, 1_200_000_001L, 20, 21);
        for (int i = 1; i <= 7; i++) trace.progress(ReceiverStageTrace.INPUT, 1, 1_200_000_001L + i * 100_000_000L, 20+i, 21+i);
        check(stage(trace, 1_900_000_001L, ReceiverStageTrace.OUTPUT).getBoolean("open_over_bound"), "decoder-output silence independent of input");
        check(stage(trace, 1_900_000_001L, ReceiverStageTrace.INPUT).getLong("closed_over_bound") == 0, "input continues");
        trace.progress(ReceiverStageTrace.OUTPUT, 1, 1_900_000_001L, 5, 6);
        JSONArray event = stage(trace, 1_900_000_001L, 2).getJSONArray("samples").getJSONArray(0);
        check(event.getLong(0) == 1 && event.getLong(5) == 5 && event.getLong(7) == 6,
                "connection/PTS/pair join; output PTS need not increase");
        trace.progress(ReceiverStageTrace.PACKET, 2, 2_000_000_001L, 30, 31);
        trace.progress(ReceiverStageTrace.PACKET, 1, -1, 30, 31);
        trace.progress(ReceiverStageTrace.PACKET, 1, 1_800_000_001L, 30, 31);
        check(trace.snapshot(2_000_000_001L).getLong("rejected_generations") == 1, "foreign generation rejected");
        check(trace.snapshot(2_000_000_001L).getLong("rejected_clocks") == 2, "negative/regressed clocks rejected");
        check(!trace.snapshot(1).getBoolean("clock_valid"), "invalid snapshot clock explicitly unavailable");
        trace.retire(1, 2_500_000_001L);
        check(stage(trace, 2_500_000_001L, 2).getLong("censored_over_bound") == 1, "cleanup censors unresolved output wait");
        check(stage(trace, 2_500_000_001L, 4).getLong("censored_first_waits_over_bound") == 1, "cleanup censors first acquisition wait");
        check(!stage(trace, 3_000_000_001L, 2).getBoolean("demanded"), "retired connection cannot accumulate open gaps");
        trace.begin(2, 3_000_000_001L);
        trace.progress(ReceiverStageTrace.OUTPUT, 2, 3_100_000_001L, 50, 51);
        check(stage(trace, 3_100_000_001L, 2).getLong("closed_gaps") == 1, "new connection baseline does not close cross-connection gap");

        ReceiverStageTrace bounded = new ReceiverStageTrace(9);
        bounded.begin(1, 1);
        bounded.progress(0, 1, 2, 0, 0);
        for (int stage = 1; stage < 5; stage++) bounded.progress(stage, 1, 2, 0, 0);
        for (int i = 1; i <= 1000; i++) for (int stage = 0; stage < 5; stage++)
            bounded.progress(stage, 1, 2 + i * 600_000_000L, i, i);
        JSONArray samples = stage(bounded, 600_000_000_002L, 0).getJSONArray("samples");
        check(samples.length() == 4 && samples.getJSONArray(0).getLong(5) == 1
                && samples.getJSONArray(1).getLong(5) == 2 && samples.getJSONArray(2).getLong(5) == 999
                && samples.getJSONArray(3).getLong(5) == 1000, "first two/latest two samples bounded");
        check(stage(bounded, 600_000_000_002L, 0).getLong("closed_over_bound") == 1000, "counts complete despite bounded samples");
        check(bounded.snapshot(600_000_000_002L).toString().length() < 8192, "bounded diagnostic envelope");
        bounded.retire(1, -1);
        check(!bounded.snapshot(600_000_000_002L).getBoolean("connection_active")
                && stage(bounded, 600_000_000_002L, 0).getLong("censored_unknown_clock") == 1,
                "invalid cleanup clock retires demand without fabricated gap");
        ReceiverStageTrace raced = new ReceiverStageTrace(10);
        raced.retire(1, 1);
        raced.begin(1, 2);
        check(!raced.snapshot(2).getBoolean("connection_active"), "retired-before-begin cannot resurrect demand");
        raced.begin(2, 3);
        check(raced.snapshot(3).getBoolean("connection_active"), "later actual connection remains admissible");
        ReceiverStageTrace concurrent = new ReceiverStageTrace(11);
        concurrent.begin(1, 1);
        CountDownLatch sampledClock = new CountDownLatch(1), finishClock = new CountDownLatch(1);
        AtomicReference<JSONObject> sample = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread sampling = new Thread(() -> {
            try {
                sample.set(concurrent.sample(() -> {
                    sampledClock.countDown();
                    try { awaitLatch(finishClock); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    return 100L;
                }));
            } catch (Throwable failure) { error.set(failure); }
        });
        sampling.start();
        Thread progressing = null;
        boolean progressBlocked = false;
        try {
            long began = System.nanoTime(); boolean warned = false;
            while (!sampledClock.await(10, TimeUnit.MILLISECONDS)) {
                check(sampling.isAlive() && error.get() == null, "actual sampler failed before holding monitor");
                if (!warned && System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(estimateMilliseconds)) {
                    warned = true; warn("same sampler awaits monitor/clock entry");
                }
            }
            progressing = new Thread(() -> {
                try { concurrent.progress(0, 1, 200L, 1, 1); }
                catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
            progressing.start();
            began = System.nanoTime(); warned = false;
            while (progressing.getState() != Thread.State.BLOCKED && progressing.isAlive() && error.get() == null) {
                if (!warned && System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(estimateMilliseconds)) {
                    warned = true; warn("same progress thread awaits actual BLOCKED observation");
                }
                Thread.yield();
            }
            progressBlocked = progressing.getState() == Thread.State.BLOCKED;
        } finally {
            finishClock.countDown();
            try { joinThread(sampling); } finally { if (progressing != null) joinThread(progressing); }
        }
        check(!sampling.isAlive() && !progressing.isAlive() && error.get() == null, "both actual threads complete");
        check(progressBlocked && sample.get().getBoolean("clock_valid")
                && sample.get().getJSONArray("stages").getJSONObject(0).getLong("progress") == 0
                && concurrent.snapshot(200).getJSONArray("stages").getJSONObject(0).getLong("progress") == 1,
                "concurrent progress cannot advance trace clock between sampling and snapshot");
        System.out.println("receiver-stage-trace PASS: silence/backpressure/joins/clocks/generations/censor/reset/bounded samples");
    }
    private static long estimateMilliseconds = 2000;
    private static java.util.function.Consumer<String> warning = message -> System.err.println("WARNING: " + message);
    private static void warningRetainsActualLatchAndThread() throws Exception {
        long priorEstimate = estimateMilliseconds;
        java.util.function.Consumer<String> priorWarning = warning;
        CountDownLatch gate = new CountDownLatch(1), entered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread original = new Thread(() -> { entered.countDown(); try { gate.await(); }
            catch (Throwable actualFailure) { failure.set(actualFailure); } });
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        original.start();
        try {
            entered.await(); estimateMilliseconds = 0;
            warning = message -> { publications.incrementAndGet(); gate.countDown();
                throw new IllegalStateException("deliberate warning sink failure after release"); };
            joinThread(original);
            check(!original.isAlive() && failure.get() == null && publications.get() == 1,
                    "warning must join same actual thread despite publication failure");
            CountDownLatch second = new CountDownLatch(1);
            warning = message -> { publications.incrementAndGet(); second.countDown();
                throw new IllegalStateException("deliberate clock warning failure after release"); };
            awaitLatch(second);
            check(second.getCount() == 0 && publications.get() == 2, "same actual latch release required");
        } finally {
            gate.countDown(); try { joinThread(original); }
            finally { warning = priorWarning; estimateMilliseconds = priorEstimate; }
        }
    }
    private static void warn(String message) {
        try { warning.accept(message); } catch (Throwable publication) { }
    }
    private static void awaitLatch(CountDownLatch original) throws InterruptedException {
        if (!original.await(estimateMilliseconds, TimeUnit.MILLISECONDS)) { warn("same actual clock gate remains Pending"); original.await(); }
    }
    private static void joinThread(Thread original) throws InterruptedException {
        boolean interrupted = false;
        try { for (;;) try {
            if (estimateMilliseconds > 0) original.join(estimateMilliseconds);
            if (original.isAlive()) { warn("same actual trace thread remains Pending"); original.join(); }
            break;
        } catch (InterruptedException actual) { interrupted = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (interrupted) throw new InterruptedException("actual trace join was interrupted after joining original");
    }
}
