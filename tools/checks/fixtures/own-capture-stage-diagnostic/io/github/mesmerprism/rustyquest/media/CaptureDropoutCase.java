package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONObject;

/** Actual trace intervals, no camera, native authority or device qualification. */
public final class CaptureDropoutCase {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static JSONArray series(CaptureFrameTrace trace, int stage) throws Exception {
        return trace.dropoutSnapshot(Long.MAX_VALUE).getJSONObject("dropout_observation").getJSONArray("values").getJSONArray(stage);
    }
    public static void main(String[] args) throws Exception {
        CaptureFrameTrace trace = new CaptureFrameTrace();
        trace.arm("p", 1L, 1L, 1L);
        long at = 1_000_000_000L;
        for (int i = 0; i < 30; i++) {
            if (i > 0) at += i == 1 || i == 15 || i == 16 ? 700_000_000L : 20_000_000L;
            trace.started(trace.epoch(), i, at, at + 1L, 4L, false, at);
            trace.completed(trace.epoch(), i, at, at + 1L, 4L, false, at, null, null);
        }
        JSONArray callbacks = series(trace, 0);
        check(callbacks.getLong(0) == 29L && callbacks.getLong(2) == 3L
                && callbacks.getLong(3) == 2L && callbacks.getLong(4) == 2L,
                "all intervals and later consecutive failures/recoveries continue after first freeze");
        JSONObject frozen = CaptureFrameTraceCase.snapshot(trace);
        check(frozen.getLong("first_crossing_raw_frame") == 1L
                && frozen.getJSONArray("frames").getJSONObject(5).getLong("raw_frame_number") == 5L,
                "first crossing and four following are immutable");
        check(series(trace, 2).getLong(2) == 3L, "sensor domain observed independently");
        trace.completed(trace.epoch(), 30L, at + 20_000_000L, at + 20_000_001L, 4L, false, null, null, null);
        trace.completed(trace.epoch(), 31L, at + 40_000_000L, at + 40_000_001L, 4L, false, at + 2_000_000_000L, null, null);
        trace.completed(trace.epoch(), 33L, at + 60_000_000L, at + 60_000_001L, 4L, false, at + 4_000_000_000L, null, null);
        check(series(trace, 2).getLong(2) == 3L && series(trace, 2).getLong(1) == 2L,
                "missing and nonconsecutive sensor identity cannot invent an interval");
        trace.started(trace.epoch(), 40L, at - 1L, at, 4L, false, at);
        trace.started(trace.epoch(), 41L, at + 3_000_000_000L, at + 3_000_000_001L, 4L, false, at);
        check(series(trace, 0).getLong(2) == 3L && series(trace, 0).getLong(1) == 1L,
                "regressed callback breaks observation baseline rather than inventing a gap");
        for (int i = 0; i < 20; i++) trace.notified(trace.epoch(), at + (i + 1L) * 3_000_000_000L, 9L, false);
        JSONArray surface = series(trace, 3);
        check(surface.getLong(2) == 19L && surface.getJSONArray(9).length() == 4
                && surface.getJSONArray(9).getJSONArray(0).getLong(0) == -1L
                && surface.getJSONArray(9).getJSONArray(3).getLong(1) == at + 60_000_000_000L,
                "bounded first two/latest two Surface intervals have explicitly unavailable raw frame");
        trace.handoff(at + 61_000_000_000L);
        trace.consumed(trace.epoch(), 100L, at + 62_000_000_000L, at + 62_000_000_001L, 11L);
        check(series(trace, 4).getLong(2) == 1L, "exact consume interval survives frozen ring");
        check(trace.dropoutSnapshot(at + 63_000_000_000L).getJSONObject("dropout_observation")
                .getJSONArray("last_seen_age_ns").getLong(3) == 3_000_000_000L,
                "permanent Surface silence has observed elapsed age without inventing a closed interval");
        trace.handoff(at + 63_000_000_000L);
        trace.consumed(trace.epoch(), 101L, at + 64_500_000_000L, at + 64_500_000_001L, 11L);
        check(series(trace, 4).getJSONArray(7).getLong(1) == 1L,
                "1–2 second severity bin is a closed exact observed handoff interval");
        CaptureFrameTrace saturation = new CaptureFrameTrace();
        saturation.arm("p", 1L, 1L, 1L);
        for (int i = 0; i < 2; i++) {
            saturation.handoff(1L);
            saturation.consumed(saturation.epoch(), i, Long.MAX_VALUE, Long.MAX_VALUE, 11L);
        }
        check(series(saturation, 4).getLong(6) == Long.MAX_VALUE
                && series(saturation, 4).getJSONArray(7).getLong(2) == 2L,
                "synthetic long-width severity totals saturate instead of wrapping");
        long old = trace.epoch();
        trace.arm("p", 1L, 2L, at + 63_000_000_000L);
        trace.notified(old, at + 64_000_000_000L, 9L, false);
        check(series(trace, 3).getLong(0) == 0L && trace.snapshot().getLong("rejected_epoch_events") == 1L,
                "new arm resets counters and old callbacks cannot overwrite them");
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 2000; i++) trace.notified(trace.epoch(), atValue(i), 9L, false);
        });
        writer.start();
        for (int i = 0; i < 2000; i++) check(series(trace, 3).getJSONArray(9).length() <= 4, "concurrent bounded samples");
        writer.join();
        check(series(trace, 3).getLong(0) == 1999L, "synchronized snapshots do not lose observations");
    }
    private static long atValue(int i) { return 100_000_000_000L + i * 700_000_000L; }
}
