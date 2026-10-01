package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the actual bounded production trace without a camera or GL fixture. */
public final class CaptureFrameTraceCase {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static JSONObject snapshot(CaptureFrameTrace trace) throws Exception {
        JSONObject result = trace.snapshot();
        check(result.getString("diagnostic_format").equals("capture_frame_table.v2"), "explicit new format");
        JSONArray columns = result.getJSONArray("frame_columns"), values = result.getJSONArray("frame_values");
        JSONArray frames = new JSONArray();
        for (int i = 0; i < values.length(); i++) {
            JSONArray row = values.getJSONArray(i);
            check(row.length() == columns.length(), "table width");
            JSONObject frame = new JSONObject();
            for (int j = 0; j < columns.length(); j++) frame.put(columns.getString(j), row.get(j));
            frame.put("surface_notification_frame_join", result.getString("surface_notification_frame_join"));
            frame.put("paired_peer_epoch_join", frame.getLong("paired_peer_arm_generation") > 0L
                    ? "same_arm_exact_consumes" : "unavailable");
            frames.put(frame);
        }
        result.put("frames", frames); return result;
    }
    private static void complete(CaptureFrameTrace trace, long frame, long callback, long sensor) {
        trace.started(trace.epoch(), frame, callback - 4L, callback - 2L, 17L, false, sensor);
        trace.completed(trace.epoch(), frame, callback, callback + 2L, 17L, false,
                sensor, 5_000_000L, null);
    }
    public static void main(String[] args) throws Exception {
        CaptureFrameTrace trace = new CaptureFrameTrace();
        trace.arm("process-a", 7L, 4L, 1L);
        CaptureFrameTrace peer = new CaptureFrameTrace();
        peer.arm("process-a", 7L, 4L, 1L);
        trace.submission(100L, 110L, 8);
        for (int i = 0; i < 18; i++)
            complete(trace, i, 1_000_000_000L + i * 20_000_000L,
                    8_000_000_000L + i * 20_000_000L);
        JSONObject before = snapshot(trace);
        check(before.getJSONArray("frames").length() == 8
                && before.getJSONArray("frames").getJSONObject(0).getLong("raw_frame_number") == 10L,
                "ring wrap and bounded export");
        check(before.getInt("request_sequence_id") == 8
                && before.getLong("request_submission_exit_ns") == 110L,
                "observable request submission");
        long late = 2_400_000_000L;
        complete(trace, 18, late, 8_360_000_000L);
        JSONObject crossed = snapshot(trace);
        check(crossed.getLong("first_crossing_raw_frame") == 18L
                && crossed.getString("first_crossing_stage").equals("started_callback")
                && crossed.getLong("first_crossing_gap_ns") > CaptureFrameTrace.GAP_NS,
                "first callback crossing must freeze");
        trace.notified(trace.epoch(), late + 2L, 29L, true);
        trace.handoff(late + 3L);
        // A notification during update must belong to the next detached GL batch.
        trace.notified(trace.epoch(), late + 4L, 29L, true);
        trace.consumed(trace.epoch(), 18, late + 5L, late + 7L, 31L);
        complete(peer, 99L, late, 8_360_000_000L);
        peer.handoff(late + 3L);
        peer.consumed(peer.epoch(), 99L, late + 5L, late + 7L, 31L);
        CaptureFrameTrace.paired(trace, 18L, peer, 99L, 73L);
        for (int i = 19; i < 23; i++)
            complete(trace, i, late + (i - 18) * 20_000_000L,
                    8_360_000_000L + (i - 18) * 20_000_000L);
        complete(trace, 23, late + 100_000_000L, 8_460_000_000L);
        JSONObject frozen = snapshot(trace);
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
        check(snapshot(separate).getJSONArray("frames").length() == 1
                && snapshot(separate).getLong("first_crossing_raw_frame") == -1L,
                "new capture instance has no prior frame or crossing");
        CaptureFrameTrace sensor = new CaptureFrameTrace();
        complete(sensor, 0L, 1_000_000_000L, 5_000_000_000L);
        complete(sensor, 1L, 1_020_000_000L, 6_000_000_001L);
        check(snapshot(sensor).getString("first_crossing_stage").equals("adjacent_sensor"),
                "sensor-domain crossing is separate from callback cadence: " + snapshot(sensor));
        CaptureFrameTrace ambiguous = new CaptureFrameTrace();
        complete(ambiguous, 1L, 1_000L, 2_000L);
        ambiguous.notified(ambiguous.epoch(), 2_000L, 1L, true);
        ambiguous.notified(ambiguous.epoch(), 2_001L, 1L, true);
        ambiguous.handoff(2_002L);
        ambiguous.consumed(ambiguous.epoch(), 1L, 2_003L, 2_004L, 31L);
        check(snapshot(ambiguous).getJSONArray("frames").getJSONObject(0)
                .getInt("surface_notification_batch") == 2
                && snapshot(ambiguous).getLong("ambiguous_notifications") == 1L,
                "coalesced notifications must remain a batch without frame join");
        trace.handoff(late + 10L);
        trace.consumed(trace.epoch(), 19L, late + 11L, late + 12L, 31L);
        check(snapshot(trace).getJSONArray("frames").getJSONObject(4)
                .getLong("surface_notification_ns") == late + 4L,
                "notification during prior consume survives for the next batch");
        CaptureFrameTrace surfaceGap = new CaptureFrameTrace();
        complete(surfaceGap, 1L, 1_000L, 2_000L);
        surfaceGap.notified(surfaceGap.epoch(), 2_000L, 1L, true);
        surfaceGap.notified(surfaceGap.epoch(), 600_002_001L, 1L, true);
        check(snapshot(surfaceGap).getBoolean("first_crossing_present")
                && snapshot(surfaceGap).getLong("first_crossing_raw_frame") == -1L
                && snapshot(surfaceGap).getString("first_crossing_stage").equals("surface_notification"),
                "first Surface gap has no invented raw frame identity");
        CaptureFrameTrace approximate = new CaptureFrameTrace();
        complete(approximate, 3L, 1_000L, 2_000L);
        approximate.notified(approximate.epoch(), 2_001L, 8L, false);
        approximate.handoff(2_002L);
        approximate.approximateConsume(approximate.epoch());
        CaptureFrameTrace.paired(approximate, 3L, peer, 99L, 5L);
        check(snapshot(approximate).getLong("approximate_surface_matches_without_frame_join") == 1L
                && snapshot(approximate).getJSONArray("frames").getJSONObject(0).getLong("consume_entry_ns") == 0L
                && snapshot(approximate).getJSONArray("frames").getJSONObject(0).getLong("pair_id") == 0L,
                "approximate runtime Surface match cannot invent consume or pair frame lineage");
        CaptureFrameTrace regressed = new CaptureFrameTrace();
        regressed.completed(regressed.epoch(), 1L, 1_000_000_000L, 1_000_000_001L, 2L, false,
                null, null, null);
        regressed.completed(regressed.epoch(), 2L, 900_000_000L, 900_000_001L, 2L, false,
                null, null, null);
        check(snapshot(regressed).getLong("first_crossing_raw_frame") == -1L
                && !snapshot(regressed).getJSONArray("frames").getJSONObject(1)
                        .getBoolean("sensor_timestamp_present"),
                "regressed callback and missing metadata cannot invent a crossing");
        CaptureFrameTrace epoch = new CaptureFrameTrace();
        complete(epoch, 1L, 1_000_000_000L, 2_000_000_000L);
        complete(epoch, 2L, 1_700_000_000L, 2_020_000_000L);
        long oldToken = epoch.epoch();
        check(snapshot(epoch).getBoolean("first_crossing_present"), "pre-arm trigger exists");
        epoch.submission(900_000_000L, 950_000_000L, 33);
        epoch.arm("process-a", 7L, 4L, 2_000_000_000L);
        epoch.completed(oldToken, 3L, 2_100_000_000L, 2_100_000_001L, 8L, false,
                2_100_000_000L, null, null);
        epoch.started(oldToken, 3L, 2_100_000_000L, 2_100_000_001L, 8L, false, 2_100_000_000L);
        epoch.notified(oldToken, 2_100_000_000L, 8L, false);
        epoch.consumed(oldToken, 3L, 2_100_000_000L, 2_100_000_001L, 9L);
        CaptureFrameTrace.paired(epoch, 3L, peer, 99L, 44L);
        JSONObject armed = snapshot(epoch);
        check(!armed.getBoolean("first_crossing_present") && armed.getJSONArray("frames").length() == 0
                && armed.getLong("rejected_epoch_events") == 4L
                && armed.getString("process_epoch_id").equals("process-a")
                && armed.getLong("app_generation") == 7L && armed.getLong("arm_generation") == 4L
                && armed.getString("request_submission_epoch").equals("before_arm")
                && armed.getInt("request_sequence_id") == 33,
                "actual arm resets the first crossing and rejects all late prior-epoch records");
        complete(epoch, 4L, 2_200_000_000L, 2_200_000_000L);
        complete(epoch, 5L, 2_800_000_001L, 2_220_000_000L);
        check(snapshot(epoch).getLong("first_crossing_raw_frame") == 5L,
                "current arm freezes its own first crossing");
        peer.arm("process-a", 7L, 5L, late);
        complete(peer, 99L, late + 20L, 8_360_000_020L);
        peer.handoff(late + 30L);
        peer.consumed(peer.epoch(), 99L, late + 40L, late + 50L, 31L);
        CaptureFrameTrace.paired(trace, 18L, peer, 99L, 74L);
        check(snapshot(trace).getJSONArray("frames").getJSONObject(3)
                .getLong("paired_with_raw_frame") == -1L
                && snapshot(trace).getJSONArray("frames").getJSONObject(3)
                        .getString("paired_peer_epoch_join").equals("unavailable"),
                "a foreign-arm exact consume cannot be labelled current-arm peer lineage");
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                trace.notified(trace.epoch(), i + 1L, 3L, false);
                CaptureFrameTrace.paired(trace, 18L, peer, 99L, 73L);
            }
        });
        writer.start();
        for (int i = 0; i < 1_000; i++) snapshot(trace);
        writer.join();
        check(snapshot(trace).getJSONArray("frames").length() == 8,
                "concurrent polling preserves bounded coherent records");
    }
}
