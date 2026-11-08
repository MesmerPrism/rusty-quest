package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic host controls; neither Android callbacks nor device timing are modeled as proof. */
public final class CaptureStallDiagnosticsMain {
    private static final class Clock implements CaptureStallDiagnostics.Clock {
        volatile long now = 1_000_000_000L;
        int reads;
        public long nowNs() { reads++; return now; }
    }
    private static final class Queue implements CaptureStallDiagnostics.Scheduler {
        Runnable pending;
        int posts, removes;
        boolean accept = true;
        public boolean postDelayed(Runnable action, long delayMs) {
            check(delayMs == 100L, "fixed heartbeat period");
            check(pending == null, "one pending heartbeat");
            posts++;
            if (accept) pending = action;
            return accept;
        }
        public void remove(Runnable action) { if (pending == action) pending = null; removes++; }
        void run() { Runnable action = pending; pending = null; action.run(); }
    }
    private static void check(boolean okay, String label) {
        if (!okay) throw new AssertionError(label);
    }
    private static JSONObject stage(CaptureStallDiagnostics d, int index) throws Exception {
        return d.snapshot().getJSONArray("stages").getJSONObject(index);
    }
    public static void main(String[] args) throws Exception {
        Clock clock = new Clock(); Queue queue = new Queue();
        CaptureStallDiagnostics d = new CaptureStallDiagnostics(clock);
        check(d.active() == null && clock.reads == 0 && queue.posts == 0, "default off has no clocks/posts");
        check(!d.snapshot().getBoolean("armed"), "unarmed readback");
        d.arm("epoch", 2L, 3L, queue);
        check(d.snapshot().getBoolean("active") && d.snapshot().getLong("window_ns") == 600_000_000_000L,
                "effective finite 600-second activation");
        check(d.snapshot().getString("process_epoch").equals("epoch")
                && d.snapshot().getLong("app_generation") == 2L
                && d.snapshot().getLong("arm_generation") == 3L, "readback identity");
        CaptureStallDiagnostics.Window first = d.active();
        long begin = first.begin(CaptureStallDiagnostics.LEFT_STARTED, 41L);
        clock.now += 1_000_000_000L;
        JSONObject live = stage(d, CaptureStallDiagnostics.LEFT_STARTED);
        check(live.getLong("active_since_ns") == begin && live.getLong("active_frame") == 41L,
                "active span visible while blocked");
        check(live.getString("thread_state").equals("RUNNABLE"), "bounded current thread state");
        first.end(CaptureStallDiagnostics.LEFT_STARTED, begin);
        JSONObject done = stage(d, CaptureStallDiagnostics.LEFT_STARTED);
        check(done.getLong("count") == 1L && done.getLong("max_elapsed_ns") == 1_000_000_000L,
                "full span records slow call");
        check(done.getLong("max_frame") == 41L && done.getLong("active_since_ns") == 0L,
                "duration retains frame and clears active");
        long nested = first.begin(CaptureStallDiagnostics.POOL_POLL_READY, -1L);
        check(first.begin(CaptureStallDiagnostics.POOL_POLL_READY, -1L) == 0L,
                "reentrant diagnostic never waits");
        first.end(CaptureStallDiagnostics.POOL_POLL_READY, 0L);
        clock.now += 11L; first.end(CaptureStallDiagnostics.POOL_POLL_READY, nested);
        check(stage(d, CaptureStallDiagnostics.POOL_POLL_READY).getLong("skipped_busy") == 1L,
                "bounded overlap diagnostic");
        queue.run();
        check(d.snapshot().getJSONObject("handler_heartbeat").getLong("max_late_ns") == 900_000_011L,
                "handler queue delay measured separately");
        check(queue.pending != null, "heartbeat reposts within finite window");
        long old = first.begin(CaptureStallDiagnostics.ENCODER_OFFER, -1L);
        Runnable staleHeartbeat = queue.pending;
        d.arm("epoch", 2L, 4L, queue);
        first.end(CaptureStallDiagnostics.ENCODER_OFFER, old);
        staleHeartbeat.run();
        check(stage(d, CaptureStallDiagnostics.ENCODER_OFFER).getLong("count") == 0L,
                "prior arm completion cannot contaminate new window");
        check(queue.posts == 3, "stale heartbeat does not repost");
        clock.now += CaptureStallDiagnostics.WINDOW_NS;
        queue.run();
        check(d.active() == null && queue.pending == null, "finite window stops posts and sampling");
        check(!d.snapshot().getBoolean("active"), "expiry effective readback");
        int reads = clock.reads;
        for (int i = 0; i < 1000; i++) check(d.active() == null, "expired stays off");
        check(clock.reads == reads, "expired path has no clock reads");
        d.arm("epoch", 2L, 5L, queue); d.stop();
        check(d.active() == null && queue.pending == null, "Stop removes queued heartbeat");
        check(d.snapshot().getLong("arm_generation") == 5L, "Stop retains diagnostic evidence");
        queue.accept = false; d.arm("epoch", 2L, 6L, queue);
        check(d.snapshot().getJSONObject("handler_heartbeat").getBoolean("post_failed"),
                "rejected handler post is observation only");
        check(d.active() != null, "failed heartbeat cannot stop capture sampling");
        d.stop();
        // Real JVM monitor contention, bounded independently of the contended monitor.
        queue.accept = true; d.arm("epoch", 2L, 7L, queue);
        final CaptureStallDiagnostics.Window contended = d.active();
        final Object lock = new Object();
        Thread worker;
        synchronized (lock) {
            worker = new Thread(() -> {
                long token = contended.begin(CaptureStallDiagnostics.LEFT_TRACE_EPOCH, 88L);
                try { synchronized (lock) { /* actual monitor entry */ } }
                finally { contended.end(CaptureStallDiagnostics.LEFT_TRACE_EPOCH, token); }
            });
            worker.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            check(worker.getState() == Thread.State.BLOCKED, "real monitor fixture blocked");
            JSONObject blocked = stage(d, CaptureStallDiagnostics.LEFT_TRACE_EPOCH);
            check(blocked.getString("thread_state").equals("BLOCKED")
                    && blocked.getLong("active_frame") == 88L, "snapshot never takes trace monitor");
            clock.now += 700_000_000L;
        }
        worker.join(2000L); check(!worker.isAlive(), "worker retired");
        check(stage(d, CaptureStallDiagnostics.LEFT_TRACE_EPOCH).getLong("max_elapsed_ns") == 700_000_000L,
                "outer span includes monitor acquisition");
        JSONArray stages = d.snapshot().getJSONArray("stages");
        check(stages.length() == CaptureStallDiagnostics.STAGE_COUNT, "fixed diagnostic cardinality");
        check(d.snapshot().toString().length() < 16_000, "bounded payload");
        d.stop();
        // Large legal local-clock/frame values, every slot populated; retain the exact
        // JSON for a downstream check of the complete existing diagnostic envelope.
        clock.now = Long.MAX_VALUE - 2L * CaptureStallDiagnostics.WINDOW_NS;
        d.arm("12345678-1234-1234-1234-123456789abc", Long.MAX_VALUE, Long.MAX_VALUE, queue);
        CaptureStallDiagnostics.Window populated = d.active();
        for (int i = 0; i < CaptureStallDiagnostics.STAGE_COUNT; i++) {
            long token = populated.begin(i, Long.MAX_VALUE);
            clock.now += 1_000_000L;
            populated.end(i, token);
            token = populated.begin(i, Long.MAX_VALUE - 1L);
            clock.now += 1_000_000L;
            populated.end(i, token);
        }
        String maximum = d.snapshot().toString();
        check(maximum.length() < 12_000, "all populated stages have bounded JSON");
        if (args.length == 1) java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
                maximum.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        d.stop();
        System.out.println("capture-stall-diagnostics:pass");
    }
}
