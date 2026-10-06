package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.LongSupplier;

/** Observation only: progress in one live receiver connection, never frame acceptance. */
final class ReceiverStageTrace {
    static final long BOUND_NS = 500_000_000L;
    static final int PACKET = 0, INPUT = 1, OUTPUT = 2, RELEASE = 3, ACQUIRED = 4;
    private static final String[] NAMES = {"packet_read", "input_queued", "output_dequeued",
            "released_for_render", "host_acquired"};
    private final long receiverGeneration;
    private final Series[] stages = {new Series(), new Series(), new Series(), new Series(), new Series()};
    private long connection, retiredGeneration, clock, rejectedClocks, rejectedGenerations, retiredConnections;
    private boolean active;

    ReceiverStageTrace(long generation) {
        if (generation <= 0) throw new IllegalArgumentException("receiver generation");
        receiverGeneration = generation;
    }

    private boolean time(long now) {
        if (now <= 0 || now < clock) { rejectedClocks = add(rejectedClocks); return false; }
        clock = now; return true;
    }

    synchronized void begin(long next, long now) {
        if (next <= connection || next <= retiredGeneration || next <= 0) { rejectedGenerations = add(rejectedGenerations); return; }
        if (!time(now)) return;
        if (active) retireAt(now);
        connection = next; active = true;
        for (Series stage : stages) stage.begin(now);
    }

    synchronized void progress(int index, long observedConnection, long now, long ptsUs, long pairId) {
        if (index < 0 || index >= stages.length) throw new IllegalArgumentException("stage");
        if (!active || observedConnection != connection) {
            rejectedGenerations = add(rejectedGenerations); return;
        }
        if (!time(now)) return;
        stages[index].progress(connection, now, ptsUs, pairId);
    }

    synchronized void retire(long observedConnection, long now) {
        if (observedConnection > retiredGeneration) retiredGeneration = observedConnection;
        if (!active || observedConnection < connection) return;
        if (time(now)) retireAt(now);
        else {
            for (Series stage : stages) stage.retireUnknownClock();
            active = false; retiredConnections = add(retiredConnections);
        }
    }

    private void retireAt(long now) {
        for (Series stage : stages) stage.retire(now);
        retiredGeneration = Math.max(retiredGeneration, connection);
        active = false; retiredConnections = add(retiredConnections);
    }

    synchronized JSONObject sample(LongSupplier monotonicClock) throws Exception {
        return snapshot(monotonicClock.getAsLong());
    }

    synchronized JSONObject snapshot(long now) throws Exception {
        boolean valid = now > 0 && now >= clock;
        JSONArray rows = new JSONArray();
        for (int i = 0; i < stages.length; i++) rows.put(stages[i].json(NAMES[i], now, active && valid));
        return new JSONObject().put("schema", "rusty.quest.media.receiver_stage_observation.v1")
                .put("qualification_claimed", false).put("clock", "android_elapsedRealtimeNanos")
                .put("sample_ns", now).put("clock_valid", valid).put("receiver_generation", receiverGeneration)
                .put("connection_generation", connection).put("connection_active", active)
                .put("retired_connection_generation", retiredGeneration)
                .put("retired_connections", retiredConnections).put("rejected_clocks", rejectedClocks)
                .put("rejected_generations", rejectedGenerations).put("bound_ns", BOUND_NS)
                .put("demand_scope", "connected configured receiver; downstream queued work not implied")
                .put("gap_scope", "same connection progress only; first wait/open/retired censor separate")
                .put("sample_columns", new JSONArray().put("connection_generation").put("previous_ns")
                        .put("current_ns").put("gap_ns").put("previous_pts_us").put("pts_us")
                        .put("previous_pair_id").put("pair_id"))
                .put("sample_retention", "first_two_latest_two; counters_complete")
                .put("frame_join", "PTS and pair ID from exact packet/pending identity; config pair ID zero")
                .put("stages", rows);
    }

    private static long add(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static final class Series {
        long began, last, pts, pair, progress, closed, over, recovered, maxGap;
        long firstWaitsOver, maxFirstWait, censoredOver, censoredFirstWait, censoredUnknownClock;
        final long[][] samples = new long[4][];

        void begin(long now) { began = now; last = 0; pts = 0; pair = 0; }
        void progress(long connection, long now, long nextPts, long nextPair) {
            progress = add(progress);
            if (last == 0) {
                long wait = now - began; maxFirstWait = Math.max(maxFirstWait, wait);
                if (wait > BOUND_NS) firstWaitsOver = add(firstWaitsOver);
            } else {
                long gap = now - last; closed = add(closed); maxGap = Math.max(maxGap, gap);
                if (gap > BOUND_NS) {
                    over = add(over); recovered = add(recovered);
                    long[] sample = {connection, last, now, gap, pts, nextPts, pair, nextPair};
                    if (samples[0] == null) samples[0] = sample;
                    else if (samples[1] == null) samples[1] = sample;
                    else if (samples[2] == null) samples[2] = sample;
                    else if (samples[3] == null) samples[3] = sample;
                    else { samples[2] = samples[3]; samples[3] = sample; }
                }
            }
            last = now; pts = nextPts; pair = nextPair;
        }
        void retire(long now) {
            if (last == 0) { if (now - began > BOUND_NS) censoredFirstWait = add(censoredFirstWait); }
            else if (now - last > BOUND_NS) censoredOver = add(censoredOver);
            began = 0; last = 0; pts = 0; pair = 0;
        }
        void retireUnknownClock() {
            censoredUnknownClock = add(censoredUnknownClock);
            began = 0; last = 0; pts = 0; pair = 0;
        }
        JSONObject json(String name, long now, boolean demanded) throws Exception {
            boolean waiting = demanded && last == 0;
            long open = demanded && last > 0 ? now - last : 0;
            JSONArray events = new JSONArray();
            for (long[] sample : samples) if (sample != null) events.put(new JSONArray(sample));
            return new JSONObject().put("stage", name).put("progress", progress)
                    .put("closed_gaps", closed).put("closed_over_bound", over)
                    .put("recovered_closed_gaps", recovered).put("max_closed_gap_ns", maxGap)
                    .put("demanded", demanded).put("open_gap_ns", open).put("open_over_bound", open > BOUND_NS)
                    .put("waiting_first_progress", waiting).put("first_wait_ns", waiting ? now - began : 0)
                    .put("first_waits_over_bound", firstWaitsOver).put("max_first_wait_ns", maxFirstWait)
                    .put("censored_over_bound", censoredOver).put("censored_first_waits_over_bound", censoredFirstWait)
                    .put("censored_unknown_clock", censoredUnknownClock)
                    .put("last_progress_ns", last).put("last_pts_us", pts).put("last_pair_id", pair)
                    .put("samples", events);
        }
    }
}
