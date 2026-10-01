package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, observation-only camera-to-compositor trace in one capture instance. */
final class CaptureFrameTrace {
    static final long GAP_NS = 500_000_000L;
    private static final int CAPACITY = 12;
    private static final int EXPORTED = 8;
    private static final int FOLLOWING = 4;
    private final Frame[] frames = new Frame[CAPACITY];
    private int next, size, following;
    private long lastStartedNs, lastCompletedNs, lastSensorNs, lastSensorFrame = -1L;
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
        long pairId, pairedWithFrame;
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
                    .put("pair_id", pairId).put("paired_with_raw_frame", pairedWithFrame);
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
        Frame record = obtain(frame);
        if (record == null) return;
        record.startedEntryNs = entryNs; record.startedExitNs = exitNs;
        record.startedThreadId = threadId; record.startedMain = main;
        if (sensorNs > 0L && !record.sensorPresent) {
            record.sensorPresent = true; record.sensorNs = sensorNs;
        }
        if (lastStartedNs > 0L && entryNs >= lastStartedNs)
            crossing("started_callback", frame, entryNs - lastStartedNs);
        if (entryNs >= lastStartedNs) lastStartedNs = entryNs;
    }
    synchronized void completed(long token, long frame, long entryNs, long exitNs, long threadId,
            boolean main, Long sensorNs, Long exposureNs, Long durationNs) {
        if (!current(token, entryNs)) return;
        if (entryNs <= 0L || exitNs < entryNs || frame < 0L) return;
        Frame record = obtain(frame);
        if (record == null) return;
        record.completedEntryNs = entryNs; record.completedExitNs = exitNs;
        record.completedThreadId = threadId; record.completedMain = main;
        record.sensorPresent = sensorNs != null && sensorNs > 0L;
        record.sensorNs = record.sensorPresent ? sensorNs : 0L;
        record.exposurePresent = exposureNs != null && exposureNs > 0L;
        record.exposureNs = record.exposurePresent ? exposureNs : 0L;
        record.durationPresent = durationNs != null && durationNs > 0L;
        record.durationNs = record.durationPresent ? durationNs : 0L;
        if (lastCompletedNs > 0L && entryNs >= lastCompletedNs)
            crossing("completed_callback", frame, entryNs - lastCompletedNs);
        if (entryNs >= lastCompletedNs) lastCompletedNs = entryNs;
        if (record.sensorPresent) {
            if (lastSensorNs > 0L && frame == lastSensorFrame + 1L
                    && record.sensorNs > lastSensorNs)
                crossing("adjacent_sensor", frame, record.sensorNs - lastSensorNs);
            if (frame > lastSensorFrame && record.sensorNs > lastSensorNs) {
                lastSensorFrame = frame; lastSensorNs = record.sensorNs;
            }
        }
    }
    synchronized void notified(long token, long arrivalNs, long threadId, boolean main) {
        if (!current(token, arrivalNs)) return;
        if (arrivalNs <= 0L) return;
        if (lastNotificationNs > 0L && arrivalNs >= lastNotificationNs)
            crossing("surface_notification", -1L, arrivalNs - lastNotificationNs);
        if (arrivalNs >= lastNotificationNs) lastNotificationNs = arrivalNs;
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
        Frame record = obtain(frame);
        if (record == null) { unmatchedConsumes++; handoffNotifications = 0; return; }
        record.consumeEntryNs = entryNs; record.consumeExitNs = exitNs;
        record.consumeThreadId = threadId; record.handoffNs = handoffNs;
        record.notificationBatch = handoffNotifications;
        if (handoffNotifications > 0) {
            record.notificationNs = handoffNotificationNs;
            record.notificationThreadId = handoffThreadId;
            record.notificationMain = handoffMain;
        }
        if (handoffNotifications > 1) ambiguousNotifications++;
        if (entryNs >= handoffNs && handoffNs > 0L)
            crossing("handoff_to_consume", frame, entryNs - handoffNs);
        handoffNotifications = 0;
    }
    synchronized void unmatchedConsume(long token) {
        if (token != epoch) { rejectedEpochEvents++; return; }
        unmatchedConsumes++; handoffNotifications = 0;
    }
    synchronized void approximateConsume(long token) {
        if (token != epoch) { rejectedEpochEvents++; return; }
        approximateConsumes++; unmatchedConsumes++; handoffNotifications = 0;
    }
    synchronized void paired(long token, long frame, long peerFrame, long pairId) {
        if (token != epoch) { rejectedEpochEvents++; return; }
        Frame record = find(frame);
        if (record != null && record.consumeEntryNs > 0L && record.consumeEntryNs >= epochStartNs) {
            record.pairId = pairId; record.pairedWithFrame = peerFrame;
        }
    }
    synchronized JSONObject snapshot() throws Exception {
        JSONArray retained = new JSONArray();
        int exported = Math.min(size, EXPORTED);
        for (int i = 0; i < exported; i++) {
            Frame frame = frames[(next - exported + i + CAPACITY) % CAPACITY];
            if (frame != null) retained.put(frame.json());
        }
        return new JSONObject().put("clock", "android_elapsedRealtimeNanos")
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
                .put("frames", retained);
    }
}
