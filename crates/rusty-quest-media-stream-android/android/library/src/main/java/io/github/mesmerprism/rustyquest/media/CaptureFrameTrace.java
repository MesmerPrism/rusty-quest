package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, observation-only camera-to-compositor trace in one capture instance. */
final class CaptureFrameTrace {
    static final long GAP_NS = 500_000_000L;
    private static final int CAPACITY = 12;
    private static final int EXPORTED = 8;
    private static final int FOLLOWING = 4;
    private static final String[] FRAME_COLUMNS = {"raw_frame_number", "started_entry_ns", "started_exit_ns",
            "started_thread_id", "started_main_looper", "completed_entry_ns", "completed_exit_ns",
            "completed_thread_id", "completed_main_looper", "sensor_timestamp_present", "sensor_timestamp_ns",
            "exposure_present", "exposure_ns", "frame_duration_present", "frame_duration_ns",
            "surface_notification_ns", "surface_notification_thread_id", "surface_notification_main_looper",
            "surface_notification_batch", "gl_handoff_ns", "consume_thread_id", "notification_to_handoff_ns",
            "handoff_to_consume_ns", "consume_entry_ns", "consume_exit_ns", "pair_id",
            "paired_with_raw_frame", "paired_peer_arm_generation"};
    private final Frame[] frames = new Frame[CAPACITY];
    private int next, size, following;
    private long lastStartedNs, lastCompletedNs, lastSensorNs, lastSensorFrame = -1L;
    private long lastStartedFrame = -1L, lastCompletedFrame = -1L;
    private final GapSeries[] gaps = {new GapSeries(), new GapSeries(), new GapSeries(),
            new GapSeries(), new GapSeries()};
    private static final String[] GAP_STAGES = {"started_callback", "completed_callback",
            "adjacent_sensor", "surface_notification", "handoff_to_consume"};
    // Counts intervals, not stalled photons or inferred native adoption. Samples are first two/latest two.
    private static final class GapSeries {
        long intervals, invalid, violations, episodes, recoveries, maximum, total;
        final long[] bins = new long[3];
        final long[][] samples = new long[4][];
        boolean failing;
        void reset() {
            intervals = invalid = violations = episodes = recoveries = maximum = total = 0L;
            failing = false;
            java.util.Arrays.fill(bins, 0L); java.util.Arrays.fill(samples, null);
        }
        static long add(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
        void invalid() { invalid = add(invalid, 1L); failing = false; }
        void observe(long raw, long at, long gap) {
            intervals = add(intervals, 1L); maximum = Math.max(maximum, gap);
            if (gap <= GAP_NS) {
                if (failing) recoveries = add(recoveries, 1L);
                failing = false; return;
            }
            violations = add(violations, 1L); total = add(total, gap);
            if (!failing) episodes = add(episodes, 1L);
            failing = true;
            int bin = gap <= 1_000_000_000L ? 0 : gap <= 2_000_000_000L ? 1 : 2;
            bins[bin] = add(bins[bin], 1L);
            long[] sample = {raw, at, gap};
            if (samples[0] == null) samples[0] = sample;
            else if (samples[1] == null) samples[1] = sample;
            else if (samples[2] == null) samples[2] = sample;
            else { samples[2] = samples[3] == null ? samples[2] : samples[3]; samples[3] = sample; }
        }
        JSONArray json() throws Exception {
            JSONArray events = new JSONArray();
            for (long[] sample : samples) if (sample != null)
                events.put(new JSONArray().put(sample[0]).put(sample[1]).put(sample[2]));
            return new JSONArray().put(intervals).put(invalid).put(violations).put(episodes)
                    .put(recoveries).put(maximum).put(total)
                    .put(new JSONArray().put(bins[0]).put(bins[1]).put(bins[2]))
                    .put(failing).put(events);
        }
    }
    private long crossingFrame = -1L, crossingGapNs;
    private boolean crossed;
    private String crossingStage = "none";
    private long submissionEntryNs, submissionExitNs;
    private int requestSequenceId;
    private long notifications, unmatchedConsumes, approximateConsumes, ambiguousNotifications, missingFrames;
    private long lastMissingFrame = -1L;
    private long pendingNotificationNs, pendingNotificationThreadId;
    private boolean pendingNotificationMain;
    private int pendingNotifications;
    private long lastNotificationNs, handoffNs, handoffNotificationNs, handoffThreadId;
    private boolean handoffMain;
    private int handoffNotifications;
    private long epoch, appGeneration, armGeneration, epochStartNs, rejectedEpochEvents;
    private String processEpoch = "unarmed";

    synchronized long epoch() { return epoch; }
    synchronized void arm(String process, long app, long arm, long startNs) {
        epoch++;
        processEpoch = process; appGeneration = app; armGeneration = arm; epochStartNs = startNs;
        java.util.Arrays.fill(frames, null);
        next = 0; size = 0; following = 0;
        lastStartedNs = 0L; lastCompletedNs = 0L; lastSensorNs = 0L; lastSensorFrame = -1L;
        lastStartedFrame = lastCompletedFrame = -1L;
        for (GapSeries gap : gaps) gap.reset();
        crossed = false; crossingFrame = -1L; crossingGapNs = 0L; crossingStage = "none";
        notifications = 0L; unmatchedConsumes = 0L; approximateConsumes = 0L;
        ambiguousNotifications = 0L; missingFrames = 0L;
        lastMissingFrame = -1L; lastNotificationNs = 0L;
        pendingNotifications = 0; handoffNotifications = 0;
        handoffNs = 0L; rejectedEpochEvents = 0L;
    }
    private boolean current(long token, long entryNs) {
        if (token != epoch || entryNs < epochStartNs) { rejectedEpochEvents++; return false; }
        return true;
    }

    static final class Frame {
        final long number;
        long startedEntryNs, startedExitNs, startedThreadId;
        boolean startedMain;
        long completedEntryNs, completedExitNs, completedThreadId;
        boolean completedMain;
        long sensorNs, exposureNs, durationNs;
        boolean sensorPresent, exposurePresent, durationPresent;
        long notificationNs, notificationThreadId, consumeEntryNs, consumeExitNs;
        boolean notificationMain;
        int notificationBatch;
        long handoffNs, consumeThreadId;
        long pairId, pairedWithFrame = -1L, pairedPeerArm = -1L;
        Frame(long number) { this.number = number; }
        JSONObject json() throws Exception {
            return new JSONObject().put("raw_frame_number", number)
                    .put("started_entry_ns", startedEntryNs).put("started_exit_ns", startedExitNs)
                    .put("started_thread_id", startedThreadId).put("started_main_looper", startedMain)
                    .put("completed_entry_ns", completedEntryNs).put("completed_exit_ns", completedExitNs)
                    .put("completed_thread_id", completedThreadId).put("completed_main_looper", completedMain)
                    .put("sensor_timestamp_present", sensorPresent).put("sensor_timestamp_ns", sensorNs)
                    .put("exposure_present", exposurePresent).put("exposure_ns", exposureNs)
                    .put("frame_duration_present", durationPresent).put("frame_duration_ns", durationNs)
                    .put("surface_notification_frame_join", "unavailable_callback_has_no_frame_identity")
                    .put("surface_notification_ns", notificationNs)
                    .put("surface_notification_thread_id", notificationThreadId)
                    .put("surface_notification_main_looper", notificationMain)
                    .put("surface_notification_batch", notificationBatch)
                    .put("gl_handoff_ns", handoffNs).put("consume_thread_id", consumeThreadId)
                    .put("notification_to_handoff_ns", notificationNs > 0L && handoffNs >= notificationNs
                            ? handoffNs - notificationNs : -1L)
                    .put("handoff_to_consume_ns", handoffNs > 0L && consumeEntryNs >= handoffNs
                            ? consumeEntryNs - handoffNs : -1L)
                    .put("consume_entry_ns", consumeEntryNs).put("consume_exit_ns", consumeExitNs)
                    .put("pair_id", pairId).put("paired_with_raw_frame", pairedWithFrame)
                    .put("paired_peer_arm_generation", pairedPeerArm)
                    .put("paired_peer_epoch_join", pairedPeerArm > 0L ? "same_arm_exact_consumes" : "unavailable");
        }
    }

    private Frame find(long number) {
        for (Frame frame : frames) if (frame != null && frame.number == number) return frame;
        return null;
    }
    private Frame obtain(long number) {
        if (number < 0L) return null;
        Frame existing = find(number);
        if (existing != null) return existing;
        if (crossed && crossingFrame >= 0L && number <= crossingFrame) return null;
        if (crossed && following == 0) {
            if (number != lastMissingFrame) { missingFrames++; lastMissingFrame = number; }
            return null;
        }
        Frame created = new Frame(number);
        frames[next] = created;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;
        if (crossed) following--;
        return created;
    }
    private void crossing(String stage, long frame, long gap) {
        if (!crossed && gap > GAP_NS) {
            crossed = true;
            crossingFrame = frame; crossingStage = stage; crossingGapNs = gap;
            following = FOLLOWING;
        }
    }
    synchronized void submission(long entryNs, long exitNs, int sequenceId) {
        submissionEntryNs = entryNs; submissionExitNs = exitNs; requestSequenceId = sequenceId;
    }
    synchronized void started(long token, long frame, long entryNs, long exitNs, long threadId,
            boolean main, long sensorNs) {
        if (!current(token, entryNs)) return;
        if (entryNs <= 0L || exitNs < entryNs || frame < 0L) return;
        long gap = 0L;
        boolean ordered = true;
        if (lastStartedNs > 0L) {
            if (entryNs > lastStartedNs && frame > lastStartedFrame) {
                gap = entryNs - lastStartedNs; gaps[0].observe(frame, entryNs, gap);
            } else { gaps[0].invalid(); lastStartedNs = 0L; ordered = false; }
        }
        if (ordered) {
            lastStartedNs = entryNs; lastStartedFrame = frame;
        }
        Frame record = obtain(frame);
        crossing("started_callback", frame, gap);
        if (record == null) return;
        record.startedEntryNs = entryNs; record.startedExitNs = exitNs;
        record.startedThreadId = threadId; record.startedMain = main;
        if (sensorNs > 0L && !record.sensorPresent) {
            record.sensorPresent = true; record.sensorNs = sensorNs;
        }
    }
    synchronized void completed(long token, long frame, long entryNs, long exitNs, long threadId,
            boolean main, Long sensorNs, Long exposureNs, Long durationNs) {
        if (!current(token, entryNs)) return;
        if (entryNs <= 0L || exitNs < entryNs || frame < 0L) return;
        long callbackGap = 0L, sensorGap = 0L;
        boolean ordered = true;
        if (lastCompletedNs > 0L) {
            if (entryNs > lastCompletedNs && frame > lastCompletedFrame) {
                callbackGap = entryNs - lastCompletedNs; gaps[1].observe(frame, entryNs, callbackGap);
            } else { gaps[1].invalid(); lastCompletedNs = 0L; ordered = false; }
        }
        if (ordered) { lastCompletedNs = entryNs; lastCompletedFrame = frame; }
        if (sensorNs != null && sensorNs > 0L) {
            boolean sensorOrdered = true;
            if (lastSensorNs > 0L) {
                if (frame == lastSensorFrame + 1L && sensorNs > lastSensorNs) {
                    sensorGap = sensorNs - lastSensorNs; gaps[2].observe(frame, sensorNs, sensorGap);
                } else {
                    gaps[2].invalid();
                    if (frame <= lastSensorFrame || sensorNs <= lastSensorNs) sensorOrdered = false;
                }
            }
            lastSensorNs = sensorOrdered ? sensorNs : 0L;
            lastSensorFrame = sensorOrdered ? frame : -1L;
        } else { gaps[2].invalid(); lastSensorNs = 0L; lastSensorFrame = -1L; }
        Frame record = obtain(frame);
        crossing("completed_callback", frame, callbackGap);
        crossing("adjacent_sensor", frame, sensorGap);
        if (record == null) return;
        record.completedEntryNs = entryNs; record.completedExitNs = exitNs;
        record.completedThreadId = threadId; record.completedMain = main;
        record.sensorPresent = sensorNs != null && sensorNs > 0L;
        record.sensorNs = record.sensorPresent ? sensorNs : 0L;
        record.exposurePresent = exposureNs != null && exposureNs > 0L;
        record.exposureNs = record.exposurePresent ? exposureNs : 0L;
        record.durationPresent = durationNs != null && durationNs > 0L;
        record.durationNs = record.durationPresent ? durationNs : 0L;
    }
    synchronized void notified(long token, long arrivalNs, long threadId, boolean main) {
        if (!current(token, arrivalNs)) return;
        if (arrivalNs <= 0L) return;
        boolean ordered = true;
        if (lastNotificationNs > 0L) {
            if (arrivalNs >= lastNotificationNs) {
                gaps[3].observe(-1L, arrivalNs, arrivalNs - lastNotificationNs);
                crossing("surface_notification", -1L, arrivalNs - lastNotificationNs);
            } else { gaps[3].invalid(); lastNotificationNs = 0L; ordered = false; }
        }
        if (ordered) lastNotificationNs = arrivalNs;
        notifications++;
        pendingNotifications++;
        pendingNotificationNs = arrivalNs;
        pendingNotificationThreadId = threadId;
        pendingNotificationMain = main;
    }
    // Called inside the compositor signal lock when its pending batch is detached.
    synchronized long handoff(long elapsedNs) {
        handoffNs = elapsedNs;
        handoffNotifications = pendingNotifications;
        handoffNotificationNs = pendingNotificationNs;
        handoffThreadId = pendingNotificationThreadId;
        handoffMain = pendingNotificationMain;
        pendingNotifications = 0;
        return epoch;
    }
    synchronized void consumed(long token, long frame, long entryNs, long exitNs, long threadId) {
        if (!current(token, entryNs)) return;
        if (frame < 0L || entryNs <= 0L || exitNs < entryNs) { gaps[4].invalid(); return; }
        long gap = 0L;
        if (handoffNs > 0L && entryNs >= handoffNs) {
            gap = entryNs - handoffNs; gaps[4].observe(frame, entryNs, gap);
        } else gaps[4].invalid();
        Frame record = obtain(frame);
        crossing("handoff_to_consume", frame, gap);
        if (record == null) { unmatchedConsumes++; handoffNotifications = 0; handoffNs = 0L; return; }
        record.consumeEntryNs = entryNs; record.consumeExitNs = exitNs;
        record.consumeThreadId = threadId; record.handoffNs = handoffNs;
        record.notificationBatch = handoffNotifications;
        if (handoffNotifications > 0) {
            record.notificationNs = handoffNotificationNs;
            record.notificationThreadId = handoffThreadId;
            record.notificationMain = handoffMain;
        }
        if (handoffNotifications > 1) ambiguousNotifications++;
        handoffNotifications = 0;
        handoffNs = 0L;
    }
    synchronized void unmatchedConsume(long token) {
        if (token != epoch) { rejectedEpochEvents++; return; }
        unmatchedConsumes++; handoffNotifications = 0;
    }
    synchronized void approximateConsume(long token) {
        if (token != epoch) { rejectedEpochEvents++; return; }
        approximateConsumes++; unmatchedConsumes++; handoffNotifications = 0;
    }
    private Frame consumedFrame(long frame) {
        Frame record = find(frame);
        return record != null && record.consumeEntryNs > 0L && record.consumeEntryNs >= epochStartNs
                ? record : null;
    }
    // One fixed left-to-right lock order; reset cannot split the peer epoch check and write.
    static void paired(CaptureFrameTrace left, long leftFrame, CaptureFrameTrace right,
            long rightFrame, long pairId) {
        if (left == null || right == null) return;
        synchronized (left) { synchronized (right) {
            Frame a = left.consumedFrame(leftFrame), b = right.consumedFrame(rightFrame);
            boolean joined = a != null && b != null && left.armGeneration > 0L
                    && left.armGeneration == right.armGeneration
                    && left.appGeneration == right.appGeneration
                    && left.processEpoch.equals(right.processEpoch);
            if (a != null) {
                a.pairId = pairId; a.pairedWithFrame = joined ? rightFrame : -1L;
                a.pairedPeerArm = joined ? right.armGeneration : -1L;
            }
            if (b != null) {
                b.pairId = pairId; b.pairedWithFrame = joined ? leftFrame : -1L;
                b.pairedPeerArm = joined ? left.armGeneration : -1L;
            }
        } }
    }
    synchronized JSONObject snapshot() throws Exception {
        JSONArray retained = new JSONArray();
        int exported = Math.min(size, EXPORTED);
        for (int i = 0; i < exported; i++) {
            Frame frame = frames[(next - exported + i + CAPACITY) % CAPACITY];
            if (frame != null) {
                JSONObject object = frame.json();
                JSONArray values = new JSONArray();
                for (String column : FRAME_COLUMNS) values.put(object.get(column));
                retained.put(values);
            }
        }
        return new JSONObject().put("clock", "android_elapsedRealtimeNanos")
                .put("diagnostic_format", "capture_frame_table.v2")
                .put("frame_columns", new JSONArray(java.util.Arrays.asList(FRAME_COLUMNS)))
                .put("surface_notification_frame_join", "unavailable_callback_has_no_frame_identity")
                .put("paired_peer_epoch_rule", "positive_paired_peer_arm_is_same_arm_exact_consumes_else_unavailable")
                .put("trace_epoch", epoch).put("process_epoch_id", processEpoch)
                .put("app_generation", appGeneration).put("arm_generation", armGeneration)
                .put("epoch_start_elapsed_ns", epochStartNs).put("rejected_epoch_events", rejectedEpochEvents)
                .put("sensor_clock_join", "unavailable_unknown_or_unverified_source")
                .put("native_adoption_join", "unavailable_no_frame_identity_in_native_receipt")
                .put("consume_frame_join", "only_equal_surface_and_capture_sensor_timestamps")
                .put("request_submission_entry_ns", submissionEntryNs)
                .put("request_submission_exit_ns", submissionExitNs)
                .put("request_sequence_id", requestSequenceId)
                .put("request_submission_epoch", submissionEntryNs >= epochStartNs ? "current_arm" : "before_arm")
                .put("first_crossing_stage", crossingStage)
                .put("first_crossing_present", crossed)
                .put("first_crossing_raw_frame", crossingFrame)
                .put("first_crossing_gap_ns", crossingGapNs)
                .put("following_remaining", following)
                .put("notifications", notifications)
                .put("ambiguous_notifications", ambiguousNotifications)
                .put("unmatched_consumes", unmatchedConsumes)
                .put("approximate_surface_matches_without_frame_join", approximateConsumes)
                .put("missing_frozen_frames", missingFrames)
                .put("frame_values", retained);
    }
    synchronized JSONObject dropoutSnapshot(long sampleNs) throws Exception {
        JSONArray gapValues = new JSONArray();
        for (GapSeries gap : gaps) gapValues.put(gap.json());
        JSONObject dropout = new JSONObject().put("format", "gap_intervals.v1")
                .put("stages", new JSONArray(java.util.Arrays.asList(GAP_STAGES)))
                .put("columns", new JSONArray(java.util.Arrays.asList("intervals", "invalid_observations",
                        "violating_intervals", "consecutive_runs", "observed_recoveries", "max_gap_ns",
                        "violating_gap_total_ns", "bins_500_1000_2000_plus_ms", "last_interval_violating", "samples")))
                .put("sample_columns", new JSONArray(java.util.Arrays.asList("raw_frame", "interval_end_ns", "gap_ns")))
                .put("sample_retention", "first_two_latest_two")
                .put("sensor_clock", "sensor_timestamp_unjoined")
                .put("last_seen_age_ns", new JSONArray().put(age(sampleNs, lastStartedNs))
                        .put(age(sampleNs, lastCompletedNs)).put(-1L)
                        .put(age(sampleNs, lastNotificationNs)).put(age(sampleNs, handoffNs)))
                .put("age_scope", "observed_elapsed_silence_not_demanded_stall_sensor_unavailable")
                .put("threshold_ns", GAP_NS).put("values", gapValues);
        return new JSONObject().put("clock", "android_elapsedRealtimeNanos")
                .put("trace_epoch", epoch).put("process_epoch_id", processEpoch)
                .put("app_generation", appGeneration).put("arm_generation", armGeneration)
                .put("epoch_start_elapsed_ns", epochStartNs).put("rejected_epoch_events", rejectedEpochEvents)
                .put("native_adoption_join", "unavailable_no_frame_identity_in_native_receipt")
                .put("dropout_observation", dropout);
    }
    private static long age(long now, long last) { return last > 0L && now >= last ? now - last : -1L; }
}
