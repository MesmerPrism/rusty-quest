package io.github.mesmerprism.rustyquest.media;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finite observation only. No capture decisions, logging, I/O, stack collection or worker thread. */
final class CaptureStallDiagnostics {
    static final long WINDOW_NS = 600_000_000_000L;
    static final int LEFT_STARTED = 0, RIGHT_STARTED = 1, LEFT_COMPLETED = 2, RIGHT_COMPLETED = 3,
            LEFT_TRACE_EPOCH = 4, RIGHT_TRACE_EPOCH = 5,
            LEFT_COMPLETED_EPOCH = 6, RIGHT_COMPLETED_EPOCH = 7,
            LEFT_STARTED_RECORD = 8, RIGHT_STARTED_RECORD = 9,
            LEFT_COMPLETED_RECORD = 10, RIGHT_COMPLETED_RECORD = 11,
            POOL_POLL_READY = 12, ENCODER_OFFER = 13, STAGE_COUNT = 14;
    private static final String[] NAMES = {"left_started_callback", "right_started_callback",
            "left_completed_callback", "right_completed_callback", "left_started_trace_epoch",
            "right_started_trace_epoch", "left_completed_trace_epoch", "right_completed_trace_epoch",
            "left_started_trace_record", "right_started_trace_record", "left_completed_trace_record",
            "right_completed_trace_record", "pool_poll_ready", "encoder_offer"};
    interface Clock { long nowNs(); }
    interface Scheduler {
        boolean postDelayed(Runnable action, long delayMs);
        void remove(Runnable action);
    }
    private final Clock clock;
    private volatile Window window;
    CaptureStallDiagnostics(Clock clock) { this.clock = clock; }

    // Called on the existing serialized diagnostic-arm/control route. Publishing a fresh
    // window fences old callbacks without acquiring a callback/trace/GL monitor.
    void arm(String processEpoch, long appGeneration, long armGeneration, Scheduler scheduler) {
        stop();
        Window fresh = new Window(clock, processEpoch, appGeneration, armGeneration, scheduler);
        window = fresh;
        fresh.postHeartbeat();
    }
    Window active() {
        Window observed = window;
        return observed != null && observed.live() ? observed : null;
    }
    void stop() {
        Window observed = window;
        if (observed != null) {
            observed.enabled = false;
            observed.removeHeartbeat();
        }
    }
    JSONObject snapshot() throws Exception {
        Window observed = window;
        return observed == null ? new JSONObject().put("armed", false).put("active", false) : observed.snapshot();
    }

    static final class Window {
        final Clock clock;
        final String processEpoch;
        final long appGeneration, armGeneration, startNs, deadlineNs;
        final Scheduler scheduler;
        final Stage[] stages = new Stage[STAGE_COUNT];
        volatile boolean enabled = true, postFailed;
        volatile long expectedHeartbeatNs, heartbeatCount, heartbeatLastNs, heartbeatMaxLateNs;
        final Runnable heartbeat = new Runnable() {
            public void run() {
                if (!live()) return;
                long now = clock.nowNs();
                heartbeatLastNs = now;
                heartbeatMaxLateNs = Math.max(heartbeatMaxLateNs, Math.max(0L, now - expectedHeartbeatNs));
                heartbeatCount++;
                postHeartbeat();
            }
        };
        Window(Clock clock, String process, long app, long arm, Scheduler scheduler) {
            this.clock = clock; processEpoch = process; appGeneration = app; armGeneration = arm;
            this.scheduler = scheduler;
            startNs = clock.nowNs(); deadlineNs = startNs + WINDOW_NS;
            for (int i = 0; i < stages.length; i++) stages[i] = new Stage();
        }
        boolean live() {
            if (!enabled) return false;
            if (clock.nowNs() >= deadlineNs) { enabled = false; return false; }
            return true;
        }
        void postHeartbeat() {
            if (!live()) return;
            expectedHeartbeatNs = clock.nowNs() + 100_000_000L;
            try { postFailed = scheduler == null || !scheduler.postDelayed(heartbeat, 100L); }
            catch (RuntimeException rejected) { postFailed = true; }
            // A simultaneous Stop/rearm may race the post. Retire only this window's callback.
            if (!enabled) removeHeartbeat();
        }
        void removeHeartbeat() {
            if (scheduler == null) return;
            try { scheduler.remove(heartbeat); }
            catch (RuntimeException rejected) { postFailed = true; }
        }
        long begin(int index, long frame) {
            if (!live()) return 0L;
            return stages[index].begin(clock.nowNs(), frame);
        }
        void end(int index, long token) {
            if (token != 0L) stages[index].end(token, clock.nowNs());
        }
        JSONObject snapshot() throws Exception {
            boolean sampling = live();
            JSONArray rows = new JSONArray();
            for (int i = 0; i < stages.length; i++) rows.put(stages[i].snapshot(NAMES[i]));
            return new JSONObject().put("armed", true).put("active", sampling)
                    .put("clock", "android_elapsedRealtimeNanos")
                    .put("process_epoch", processEpoch).put("app_generation", appGeneration)
                    .put("arm_generation", armGeneration).put("start_ns", startNs)
                    .put("deadline_ns", deadlineNs).put("window_ns", WINDOW_NS)
                    .put("consistency", "bounded_per_stage_retry_thread_state_sampled_separately")
                    .put("handler_heartbeat", new JSONObject().put("period_ns", 100_000_000L)
                            .put("count", heartbeatCount).put("last_ns", heartbeatLastNs)
                            .put("expected_ns", expectedHeartbeatNs).put("max_late_ns", heartbeatMaxLateNs)
                            .put("post_failed", postFailed))
                    .put("stages", rows);
        }
    }

    /** Fixed storage, one active writer per stage. Snapshots never acquire the producer's monitor.
     * All sampled fields are volatile: version checks surround an ordered volatile read sequence.
     * A busy/reentrant writer skips observation instead of delaying media work. */
    private static final class Stage {
        final AtomicBoolean busy = new AtomicBoolean();
        final AtomicLong skippedBusy = new AtomicLong();
        volatile long version, activeSinceNs, activeFrame = -1L;
        volatile long count, maxNs, maxStartNs, maxEndNs, maxFrame = -1L;
        volatile long lastEntryNs, lastEntryFrame = -1L, maxEntryGapNs;
        volatile long gapFromNs, gapToNs, gapFromFrame = -1L, gapToFrame = -1L;
        volatile long threadId;
        volatile Thread thread;
        long begin(long now, long frame) {
            if (now <= 0L) return 0L;
            if (!busy.compareAndSet(false, true)) { skippedBusy.incrementAndGet(); return 0L; }
            version++;
            if (lastEntryNs > 0L && now - lastEntryNs > maxEntryGapNs) {
                maxEntryGapNs = now - lastEntryNs;
                gapFromNs = lastEntryNs; gapToNs = now;
                gapFromFrame = lastEntryFrame; gapToFrame = frame;
            }
            lastEntryNs = now; lastEntryFrame = frame;
            activeSinceNs = now; activeFrame = frame;
            thread = Thread.currentThread(); threadId = thread.getId();
            version++;
            return now;
        }
        void end(long token, long now) {
            if (activeSinceNs != token) return;
            version++;
            count++;
            if (now >= token && now - token > maxNs) {
                maxNs = now - token; maxStartNs = token; maxEndNs = now; maxFrame = activeFrame;
            }
            activeSinceNs = 0L; activeFrame = -1L;
            version++;
            busy.set(false);
        }
        JSONObject snapshot(String name) throws Exception {
            // JSON allocation/serialization happens on the observation caller, never on media threads.
            for (int attempt = 0; attempt < 2; attempt++) {
                long before = version;
                if ((before & 1L) != 0L) continue;
                long active = activeSinceNs, frame = activeFrame, n = count, max = maxNs;
                long start = maxStartNs, end = maxEndNs, worstFrame = maxFrame;
                long entry = lastEntryNs, entryFrame = lastEntryFrame, gap = maxEntryGapNs;
                long from = gapFromNs, to = gapToNs, fromFrame = gapFromFrame, toFrame = gapToFrame;
                long id = threadId;
                Thread observed = thread;
                if (before != version) continue;
                return new JSONObject().put("name", name).put("coherent", true)
                        .put("count", n).put("active_since_ns", active).put("active_frame", frame)
                        .put("max_elapsed_ns", max).put("max_start_ns", start).put("max_end_ns", end)
                        .put("max_frame", worstFrame).put("last_entry_ns", entry)
                        .put("last_entry_frame", entryFrame).put("max_entry_gap_ns", gap)
                        .put("gap_from_ns", from).put("gap_to_ns", to)
                        .put("gap_from_frame", fromFrame).put("gap_to_frame", toFrame)
                        .put("thread_id", id).put("thread_state", observed == null ? "UNOBSERVED" : observed.getState().name())
                        .put("skipped_busy", skippedBusy.get());
            }
            return new JSONObject().put("name", name).put("coherent", false);
        }
    }
}
