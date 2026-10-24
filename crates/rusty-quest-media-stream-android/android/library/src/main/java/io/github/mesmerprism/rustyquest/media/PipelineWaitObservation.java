package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded observer only. Never holds its monitor across a media/platform call or JSON formatting. */
final class PipelineWaitObservation {
    interface Clock { long now(); }
    static final long BOUND_NS = 500_000_000L;
    static final int WORK = 1, IDLE = 2, IMPORT = 3, DRAW = 4, FENCE = 5,
            GET_OUTPUT = 6, PACKET_OFFER = 7, RELEASE_OUTPUT = 8, PAIR_REGISTER = 9;
    private static final String[] COLUMNS = {"entered","completed","errors","over_bound",
            "last_entry_ns","last_exit_ns","last_pair","last_pts_ns","last_generation",
            "max_duration_ns","worst_entry_ns","worst_exit_ns","worst_pair","worst_pts_ns",
            "worst_generation","inflight_sequence","entry_ns","pair","pts_ns","generation",
            "phase","open_ns","last_phase","worst_exit_phase"};
    // Omit duplicated last-completion fields and derived open duration. Entry>0 means inflight.
    private static final int[] EXPORTED = {0,1,2,3,9,10,11,12,13,14,16,17,18,19,20,23};
    private final String owner;
    private final String[] names;
    private final long[][] rows;
    private final Clock clock;
    private final String clockName;
    private volatile boolean valid = true;

    PipelineWaitObservation(String owner, String... names) {
        this(owner, "java_system_nano_time_ns", System::nanoTime, names);
    }
    PipelineWaitObservation(String owner, Clock clock, String... names) {
        this(owner, "android_elapsed_realtime_ns", clock, names);
    }
    private PipelineWaitObservation(String owner, String clockName, Clock clock, String... names) {
        this.owner = owner; this.clock = clock; this.clockName = clockName; this.names = names.clone();
        rows = new long[names.length][COLUMNS.length];
    }
    long begin(int stage, long generation, long pair, long ptsNs, int phase) {
        try {
            long now = clock.now();
            synchronized (this) {
                long[] r = rows[stage];
                if (!valid || now <= 0 || generation < 0 || pair < 0 || ptsNs < 0
                        || r[15] != 0 || now < r[5]) { valid = false; return 0; }
                r[0] = Math.addExact(r[0], 1L); r[15] = r[0]; r[16] = now;
                r[17] = pair; r[18] = ptsNs; r[19] = generation; r[20] = phase;
                return r[15];
            }
        } catch (Throwable unavailable) { valid = false; return 0; }
    }
    void phase(int stage, long token, int phase) {
        try { synchronized (this) {
            long[] r = rows[stage];
            if (token != 0 && r[15] == token) r[20] = phase;
        } } catch (Throwable unavailable) { valid = false; }
    }
    void identity(int stage, long token, long pair, long ptsNs) {
        try { synchronized (this) {
            long[] r = rows[stage];
            if (pair < 0 || ptsNs < 0) { valid = false; return; }
            if (token != 0 && r[15] == token) { r[17] = pair; r[18] = ptsNs; }
        } } catch (Throwable unavailable) { valid = false; }
    }
    void generation(int stage, long token, long generation) {
        try { synchronized (this) {
            long[] r = rows[stage];
            if (generation < 0) { valid = false; return; }
            if (token != 0 && r[15] == token) r[19] = generation;
        } } catch (Throwable unavailable) { valid = false; }
    }
    void inputIdentity(int stage, long token, PackedStereoEncoderInput input) {
        try {
            PackedStereoPoolExecutor.PairIdentity pair = input.pair();
            identity(stage, token, pair.pairId, pair.packedPtsNs);
        } catch (Throwable unavailable) { valid = false; }
    }
    long ptsNs(long ptsUs) {
        try {
            if (ptsUs < 0) { valid = false; return 0; }
            return Math.multiplyExact(ptsUs, 1000L);
        } catch (Throwable unavailable) { valid = false; return 0; }
    }
    void end(int stage, long token, boolean success) {
        if (token == 0) return;
        try {
            long now = clock.now();
            synchronized (this) {
                long[] r = rows[stage];
                if (!valid || r[15] != token || now < r[16]) { valid = false; return; }
                long duration = now - r[16];
                r[1] = Math.addExact(r[1], 1L);
                if (!success) r[2] = Math.addExact(r[2], 1L);
                if (duration > BOUND_NS) r[3] = Math.addExact(r[3], 1L);
                r[4] = r[16]; r[5] = now; r[6] = r[17]; r[7] = r[18]; r[8] = r[19];
                r[22] = r[20];
                if (duration > r[9]) {
                    r[9] = duration; r[10] = r[16]; r[11] = now;
                    r[12] = r[17]; r[13] = r[18]; r[14] = r[19];
                    r[23] = r[20];
                }
                for (int i = 15; i <= 21; i++) r[i] = 0;
            }
        } catch (Throwable unavailable) { valid = false; }
    }
    JSONObject snapshot() {
        try {
            boolean available;
            long[][] copy = new long[rows.length][];
            synchronized (this) {
                available = valid;
                for (int i = 0; i < rows.length; i++) {
                    copy[i] = rows[i].clone();
                }
            }
            // Read after copying so a concurrent later begin cannot appear to precede this sample.
            // Clock providers and JSON formatting execute outside the producer monitor.
            long sample = clock.now();
            available = available && sample > 0;
            for (long[] row : copy) if (sample < Math.max(row[5], row[16])) available = false;
            if (!available) valid = false;
            JSONArray stages = new JSONArray(), columns = new JSONArray(), values = new JSONArray();
            for (String name : names) stages.put(name);
            for (int column : EXPORTED) columns.put(COLUMNS[column]);
            for (long[] row : copy) {
                JSONArray value = new JSONArray();
                for (int column : EXPORTED) value.put(Long.toString(row[column], 36));
                values.put(value);
            }
            return new JSONObject().put("schema", "rusty.quest.media.pipeline_waits.v1")
                    .put("qualification_claimed", false).put("clock", clockName)
                    .put("owner", owner).put("available", available).put("sample_ns", sample)
                    .put("row_encoding", "nonnegative_i64_base36")
                    .put("bound_ns", BOUND_NS).put("stages", stages).put("columns", columns).put("rows", values);
        } catch (Throwable unavailable) {
            valid = false;
            // Even unavailable JSON is optional: callers must not make it a media prerequisite.
            return null;
        }
    }
    static JSONObject group() throws Exception {
        JSONArray columns = new JSONArray();
        for (int column : EXPORTED) columns.put(COLUMNS[column]);
        return new JSONObject().put("schema", "rusty.quest.media.pipeline_waits.v1")
                .put("qualification_claimed", false).put("clock", "android_elapsed_realtime_ns")
                .put("consistency", "sequential_per_owner_copy_then_clock")
                .put("bound_ns", BOUND_NS).put("row_encoding", "nonnegative_i64_base36")
                .put("columns", columns);
    }
    void append(JSONObject group, String key) throws Exception {
        JSONObject snapshot = snapshot();
        if (snapshot != null) {
            if (!"android_elapsed_realtime_ns".equals(snapshot.getString("clock")))
                snapshot.put("available", false);
            for (String duplicate : new String[]{"schema","qualification_claimed","clock","bound_ns",
                    "row_encoding","columns","owner"}) snapshot.remove(duplicate);
            group.put(key, snapshot);
        } else group.put(key, new JSONObject().put("available", false));
    }
}
