package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** In-process native-frame accumulator. A Start owner must supply live route
 * and activation fences on both sides of each native observation. */
final class EmbeddedDuplexFrameWindow {
    static final long WINDOW_NS = 110_000_000_000L;
    static final long MAX_GAP_NS = 500_000_000L;
    static final long MAX_STALL_NS = 1_000_000_000L;
    private static final long PERIOD_MS = 250L;
    private static final String SCHEMA = "rusty.quest.embedded_duplex.frame_window_receipt.v2";

    interface Source {
        Sample observe() throws Exception;
        /** Fresh owner readback, not the historical frame-window snapshot. */
        Fence recheck() throws Exception;
    }

    static final class Fence {
        final String processEpochId, routeGrantId;
        final long activationRevision;
        final boolean currentRoute, activated;
        Fence(String processEpochId, String routeGrantId, long activationRevision,
                boolean currentRoute, boolean activated) {
            this.processEpochId = processEpochId;
            this.routeGrantId = routeGrantId;
            this.activationRevision = activationRevision;
            this.currentRoute = currentRoute;
            this.activated = activated;
        }
    }

    static final class Sample {
        final Fence before, after;
        final long[] nativeWords;
        Sample(Fence before, long[] nativeWords, Fence after) {
            this.before = before;
            this.nativeWords = nativeWords == null ? null : nativeWords.clone();
            this.after = after;
        }
    }

    private final Source source;
    private final String direction, routeGrantId, processEpochId, windowId;
    private final long activationRevision;
    private final MessageDigest digest;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "embedded-duplex-frame-window");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> task;
    private String state = "pending", failure = "none", firstIdentity, lastIdentity, digestHex;
    private long firstObserved, lastObserved, lastAdvanceObserved, maxGap, maxAge, maxStall;
    private long sampleCount, frameAdvanceCount;
    private long[] lastFrame;

    EmbeddedDuplexFrameWindow(Source source, String direction, String routeGrantId,
            String processEpochId, long activationRevision) {
        if (source == null || !("a_to_b".equals(direction) || "b_to_a".equals(direction))
                || routeGrantId == null || routeGrantId.isEmpty()
                || processEpochId == null || processEpochId.isEmpty() || activationRevision <= 0L) {
            throw new IllegalArgumentException("frame window authority");
        }
        this.source = source;
        this.direction = direction;
        this.routeGrantId = routeGrantId;
        this.processEpochId = processEpochId;
        this.activationRevision = activationRevision;
        this.windowId = UUID.randomUUID().toString();
        try { this.digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    synchronized void start() {
        if (task != null || !"pending".equals(state)) throw new IllegalStateException("window already started");
        task = scheduler.scheduleAtFixedRate(this::sampleOnce, 0L, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** Called by the process owner's Stop/cleanup fence before effects begin. */
    synchronized void cancelForCleanup() { invalidate("cleanup_started"); }

    void sampleOnce() {
        synchronized (this) { if (!"pending".equals(state)) return; }
        Sample sample;
        try { sample = source.observe(); }
        catch (Throwable unavailable) {
            synchronized (this) { fail("native_or_route_observation_failed"); }
            return;
        }
        synchronized (this) { if ("pending".equals(state)) accept(sample); }
    }

    private void accept(Sample sample) {
        if (sample == null || !fence(sample.before) || !fence(sample.after)) {
            fail("route_or_activation_changed"); return;
        }
        long[] w = sample.nativeWords;
        if (w == null || w.length != EmbeddedDuplexNative.EFFECTIVE_TIMED_OBSERVATION_WORDS) {
            fail("native_frame_absent"); return;
        }
        long at = w[18], age = w[19];
        if (w[0] != EmbeddedDuplexNative.FRAME_EVIDENCE_VERSION
                || at <= 0L || age < 0L || age > MAX_GAP_NS || w[14] < 0L
                || w[12] <= 0L || w[13] <= 0L || w[15] <= 0L
                || w[16] < w[15] || w[17] < w[16]
                || w[17] > at || w[20] <= 0L
                || age != at - w[15]) {
            fail("native_frame_stale_or_malformed"); return;
        }
        for (int i = 1; i <= 14; i++) {
            if (i != 14 && w[i] <= 0L) { fail("native_frame_malformed"); return; }
        }
        // Both sensor timestamps are positive signed longs, so their
        // difference and Math.abs cannot overflow the signed range.
        if (w[14] != Math.abs(w[12] - w[13])) {
            fail("native_frame_malformed"); return;
        }
        if (sampleCount > 0L && w[2] != lastFrame[2]) {
            fail("connection_changed"); return;
        }
        if (sampleCount > 0L && (w[1] != lastFrame[1] || w[3] != lastFrame[3]
                || w[4] != lastFrame[4] || w[5] != lastFrame[5])) {
            fail("native_generation_changed"); return;
        }
        long gap = sampleCount == 0L ? 0L : at - lastObserved;
        if (sampleCount > 0L && (gap <= 0L || gap > MAX_GAP_NS)) {
            fail("observation_gap"); return;
        }
        String identity = identitySha(w);
        if (sampleCount == 0L) {
            firstObserved = at;
            firstIdentity = identity;
            lastAdvanceObserved = at;
        } else if (!identity.equals(lastIdentity)) {
            if (w[20] <= lastFrame[20]) {
                fail("gpu_import_sequence_not_advancing"); return;
            }
            frameAdvanceCount++;
            maxStall = Math.max(maxStall, at - lastAdvanceObserved);
            lastAdvanceObserved = at;
        }
        if (at - lastAdvanceObserved > MAX_STALL_NS || maxStall > MAX_STALL_NS) {
            fail("frame_identity_stalled"); return;
        }
        sampleCount++;
        lastObserved = at;
        lastIdentity = identity;
        maxGap = Math.max(maxGap, gap);
        maxAge = Math.max(maxAge, age);
        lastFrame = w.clone();
        String line = identity + "," + at + "," + age + "\n";
        digest.update(line.getBytes(StandardCharsets.UTF_8));
        long duration = lastObserved - firstObserved;
        if (duration >= WINDOW_NS) {
            if (maxGap <= 0L || sampleCount < (duration + maxGap - 1L) / maxGap + 1L
                    || frameAdvanceCount < (duration + 999_999_999L) / 1_000_000_000L
                    || firstIdentity.equals(lastIdentity)) {
                fail("window_continuity_insufficient"); return;
            }
            digestHex = hex(digest.digest());
            state = "complete";
            stopScheduler();
        }
    }

    private boolean fence(Fence value) {
        return value != null && value.currentRoute && value.activated
                && processEpochId.equals(value.processEpochId)
                && routeGrantId.equals(value.routeGrantId)
                && activationRevision == value.activationRevision;
    }

    private void fail(String code) {
        if (!"pending".equals(state)) return;
        failure = code;
        state = "failed";
        stopScheduler();
    }

    private void invalidate(String code) {
        if ("failed".equals(state)) return;
        if ("pending".equals(state)) { fail(code); return; }
        failure = code;
        state = "failed";
    }

    private void stopScheduler() {
        if (task != null) task.cancel(false);
        scheduler.shutdown();
    }

    String receipt(String challenge) throws Exception {
        if (challenge == null || !challenge.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("frame challenge");
        }
        boolean recheck;
        synchronized (this) { recheck = "complete".equals(state); }
        if (recheck) {
            Fence current;
            try { current = source.recheck(); }
            catch (Throwable unavailable) { current = null; }
            synchronized (this) {
                if ("complete".equals(state) && !fence(current)) {
                    invalidate("route_or_activation_changed");
                }
            }
        }
        synchronized (this) { return receiptLocked(challenge); }
    }

    private String receiptLocked(String challenge) throws Exception {
        JSONObject result = new JSONObject()
                .put("schema", SCHEMA).put("action", "frame_status")
                .put("challenge", challenge).put("process_epoch_id", processEpochId)
                .put("state", state).put("direction", direction)
                .put("window_id", windowId).put("route_grant_id", routeGrantId)
                .put("sample_source", "app_in_process_native_observation")
                .put("native_clock", "CLOCK_MONOTONIC")
                .put("sticky_failure_code", failure)
                .put("current_route", "complete".equals(state))
                .put("route_current_throughout", "complete".equals(state))
                .put("activation_revision_stable", "complete".equals(state))
                .put("first_observed_monotonic_ns", firstObserved)
                .put("last_observed_monotonic_ns", lastObserved)
                .put("duration_ns", lastObserved - firstObserved)
                .put("sample_count", sampleCount)
                .put("max_observation_gap_ns", maxGap)
                .put("max_witness_age_ns", maxAge)
                .put("max_identity_stall_ns", maxStall)
                .put("matched_acquire_gpu_effect_count", sampleCount)
                .put("frame_advance_count", frameAdvanceCount)
                .put("connection_changes", 0L);
        if (digestHex != null) result.put("sample_digest_sha256", digestHex);
        if (firstIdentity != null) result.put("first_frame_identity_sha256", firstIdentity);
        if (lastIdentity != null) result.put("last_frame_identity_sha256", lastIdentity);
        if (lastFrame != null) {
            JSONArray words = new JSONArray();
            for (int i = 0; i <= 14; i++) words.put(lastFrame[i]);
            result.put("last_native_frame", new JSONObject()
                    .put("words", words).put("registered_monotonic_ns", lastFrame[15])
                    .put("acquired_monotonic_ns", lastFrame[16])
                    .put("gpu_retired_monotonic_ns", lastFrame[17])
                    .put("observed_at_monotonic_ns", lastFrame[18])
                    .put("witness_age_ns", lastFrame[19])
                    .put("import_sequence", lastFrame[20])
                    .put("identity_sha256", lastIdentity));
        }
        return result.toString();
    }

    private static String identitySha(long[] words) {
        StringBuilder value = new StringBuilder();
        for (int i = 1; i <= 14; i++) {
            if (i != 1) value.append(',');
            value.append(words[i]); // Java long uses invariant signed decimal formatting.
        }
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = digits[(bytes[i] >>> 4) & 15];
            result[2 * i + 1] = digits[bytes[i] & 15];
        }
        Arrays.fill(bytes, (byte) 0);
        return new String(result);
    }
}
