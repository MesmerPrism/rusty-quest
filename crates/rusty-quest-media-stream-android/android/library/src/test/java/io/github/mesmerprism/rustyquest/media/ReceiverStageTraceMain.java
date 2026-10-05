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
                    try { if (!finishClock.await(2, TimeUnit.SECONDS)) throw new AssertionError("clock gate"); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    return 100L;
                }));
            } catch (Throwable failure) { error.set(failure); }
        });
        sampling.start();
        check(sampledClock.await(2, TimeUnit.SECONDS), "sample holds monitor before reading clock");
        Thread progressing = new Thread(() -> concurrent.progress(0, 1, 200L, 1, 1));
        progressing.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (progressing.getState() != Thread.State.BLOCKED && progressing.isAlive() && System.nanoTime() < deadline)
            Thread.yield();
        boolean progressBlocked = progressing.getState() == Thread.State.BLOCKED;
        finishClock.countDown(); sampling.join(2000); progressing.join(2000);
        check(!sampling.isAlive() && !progressing.isAlive() && error.get() == null, "both actual threads complete");
        check(progressBlocked && sample.get().getBoolean("clock_valid")
                && sample.get().getJSONArray("stages").getJSONObject(0).getLong("progress") == 0
                && concurrent.snapshot(200).getJSONArray("stages").getJSONObject(0).getLong("progress") == 1,
                "concurrent progress cannot advance trace clock between sampling and snapshot");
        System.out.println("receiver-stage-trace PASS: silence/backpressure/joins/clocks/generations/censor/reset/bounded samples");
    }
}
