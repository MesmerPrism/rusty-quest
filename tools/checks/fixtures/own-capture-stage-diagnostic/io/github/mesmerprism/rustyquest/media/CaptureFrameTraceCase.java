package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the actual bounded production trace without a camera or GL fixture. */
public final class CaptureFrameTraceCase {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void complete(CaptureFrameTrace trace, long frame, long callback, long sensor) {
        trace.started(frame, callback - 4L, callback - 2L, 17L, false, sensor);
        trace.completed(frame, callback, callback + 2L, 17L, false,
                sensor, 5_000_000L, null);
    }
    public static void main(String[] args) throws Exception {
        CaptureFrameTrace trace = new CaptureFrameTrace();
        trace.submission(100L, 110L, 8);
        for (int i = 0; i < 18; i++)
            complete(trace, i, 1_000_000_000L + i * 20_000_000L,
                    8_000_000_000L + i * 20_000_000L);
        JSONObject before = trace.snapshot();
        check(before.getJSONArray("frames").length() == 8
                && before.getJSONArray("frames").getJSONObject(0).getLong("raw_frame_number") == 10L,
                "ring wrap and bounded export");
        check(before.getInt("request_sequence_id") == 8
                && before.getLong("request_submission_exit_ns") == 110L,
                "observable request submission");
        long late = 2_400_000_000L;
        complete(trace, 18, late, 8_360_000_000L);
        JSONObject crossed = trace.snapshot();
        check(crossed.getLong("first_crossing_raw_frame") == 18L
                && crossed.getString("first_crossing_stage").equals("started_callback")
                && crossed.getLong("first_crossing_gap_ns") > CaptureFrameTrace.GAP_NS,
                "first callback crossing must freeze");
        trace.notified(late + 2L, 29L, true);
        trace.handoff(late + 3L);
        // A notification during update must belong to the next detached GL batch.
        trace.notified(late + 4L, 29L, true);
        trace.consumed(18, late + 5L, late + 7L, 31L);
        trace.paired(18, 99, 73L);
        for (int i = 19; i < 23; i++)
            complete(trace, i, late + (i - 18) * 20_000_000L,
                    8_360_000_000L + (i - 18) * 20_000_000L);
        complete(trace, 23, late + 100_000_000L, 8_460_000_000L);
        JSONObject frozen = trace.snapshot();
        JSONArray frames = frozen.getJSONArray("frames");
        check(frames.length() == 8
                && frames.getJSONObject(0).getLong("raw_frame_number") == 15L
                && frames.getJSONObject(7).getLong("raw_frame_number") == 22L
                && frozen.getInt("following_remaining") == 0
                && frozen.getLong("missing_frozen_frames") == 1L,
                "first crossing retains preceding and exactly four following frames");
        JSONObject crossingFrame = frames.getJSONObject(3);
        check(crossingFrame.getLong("raw_frame_number") == 18L
                && crossingFrame.getString("surface_notification_frame_join")
                        .equals("unavailable_callback_has_no_frame_identity")
                && crossingFrame.getLong("surface_notification_ns") == late + 2L
                && crossingFrame.getLong("consume_entry_ns") == late + 5L
                && crossingFrame.getLong("notification_to_handoff_ns") == 1L
                && crossingFrame.getLong("handoff_to_consume_ns") == 2L
                && crossingFrame.getLong("consume_thread_id") == 31L
                && crossingFrame.getLong("pair_id") == 73L
                && crossingFrame.getLong("paired_with_raw_frame") == 99L
                && !crossingFrame.getBoolean("frame_duration_present"),
                "frame metadata, Surface handoff and pair lineage");
        check(frozen.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 12_000,
                "one eye must fit its share of the 32 KiB outer receipt");
        CaptureFrameTrace separate = new CaptureFrameTrace();
        complete(separate, 1L, 1_000L, 2_000L);
        check(separate.snapshot().getJSONArray("frames").length() == 1
                && separate.snapshot().getLong("first_crossing_raw_frame") == -1L,
                "new capture instance has no prior frame or crossing");
        CaptureFrameTrace sensor = new CaptureFrameTrace();
        complete(sensor, 0L, 1_000_000_000L, 5_000_000_000L);
        complete(sensor, 1L, 1_020_000_000L, 6_000_000_001L);
        check(sensor.snapshot().getString("first_crossing_stage").equals("adjacent_sensor"),
                "sensor-domain crossing is separate from callback cadence: " + sensor.snapshot());
        CaptureFrameTrace ambiguous = new CaptureFrameTrace();
        complete(ambiguous, 1L, 1_000L, 2_000L);
        ambiguous.notified(2_000L, 1L, true);
        ambiguous.notified(2_001L, 1L, true);
        ambiguous.handoff(2_002L);
        ambiguous.consumed(1L, 2_003L, 2_004L, 31L);
        check(ambiguous.snapshot().getJSONArray("frames").getJSONObject(0)
                .getInt("surface_notification_batch") == 2
                && ambiguous.snapshot().getLong("ambiguous_notifications") == 1L,
                "coalesced notifications must remain a batch without frame join");
        trace.handoff(late + 10L);
        trace.consumed(19L, late + 11L, late + 12L, 31L);
        check(trace.snapshot().getJSONArray("frames").getJSONObject(4)
                .getLong("surface_notification_ns") == late + 4L,
                "notification during prior consume survives for the next batch");
        CaptureFrameTrace surfaceGap = new CaptureFrameTrace();
        complete(surfaceGap, 1L, 1_000L, 2_000L);
        surfaceGap.notified(2_000L, 1L, true);
        surfaceGap.notified(600_002_001L, 1L, true);
        check(surfaceGap.snapshot().getBoolean("first_crossing_present")
                && surfaceGap.snapshot().getLong("first_crossing_raw_frame") == -1L
                && surfaceGap.snapshot().getString("first_crossing_stage").equals("surface_notification"),
                "first Surface gap has no invented raw frame identity");
        CaptureFrameTrace regressed = new CaptureFrameTrace();
        regressed.completed(1L, 1_000_000_000L, 1_000_000_001L, 2L, false,
                null, null, null);
        regressed.completed(2L, 900_000_000L, 900_000_001L, 2L, false,
                null, null, null);
        check(regressed.snapshot().getLong("first_crossing_raw_frame") == -1L
                && !regressed.snapshot().getJSONArray("frames").getJSONObject(1)
                        .getBoolean("sensor_timestamp_present"),
                "regressed callback and missing metadata cannot invent a crossing");
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                trace.notified(i + 1L, 3L, false);
                trace.paired(18L, 99L, 73L);
            }
        });
        writer.start();
        for (int i = 0; i < 1_000; i++) trace.snapshot();
        writer.join();
        check(trace.snapshot().getJSONArray("frames").length() == 8,
                "concurrent polling preserves bounded coherent records");
    }
}
