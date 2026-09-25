package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class EmbeddedDuplexFrameWindowTest {
    private static final String CHALLENGE = "0123456789abcdef0123456789abcdef";
    private static final String ROUTE = "route.grant.1";
    private static final String EPOCH = "process.epoch.1";
    private static final long REVISION = 7L;

    @Test public void exactNativeWitnessesCompleteOneContinuous110SecondWindow() throws Exception {
        FakeSource source = new FakeSource();
        EmbeddedDuplexFrameWindow window = window(source);
        for (int i = 0; i <= 440; i++) window.sampleOnce();
        JSONObject receipt = new JSONObject(window.receipt(CHALLENGE));
        assertEquals("complete", receipt.getString("state"));
        assertEquals("none", receipt.getString("sticky_failure_code"));
        assertEquals(110_000_000_000L, receipt.getLong("duration_ns"));
        assertEquals(441L, receipt.getLong("sample_count"));
        assertEquals(250_000_000L, receipt.getLong("max_observation_gap_ns"));
        assertEquals(10_000_000L, receipt.getLong("max_witness_age_ns"));
        assertEquals(440L, receipt.getLong("frame_advance_count"));
        assertEquals(17, receipt.getJSONObject("last_native_frame").getJSONArray("words").length());
        assertEquals(64, receipt.getString("sample_digest_sha256").length());
        assertEquals(CHALLENGE, receipt.getString("challenge"));
        assertTrue(receipt.getBoolean("route_current_throughout"));
        window.sampleOnce();
        assertEquals(441, source.count);
        source.currentRoute = false;
        JSONObject revoked = new JSONObject(window.receipt(CHALLENGE));
        assertEquals("failed", revoked.getString("state"));
        assertFalse(revoked.getBoolean("current_route"));
        assertEquals("route_or_activation_changed", revoked.getString("sticky_failure_code"));
    }

    @Test public void routeChangeAndCleanupAreStickyAndNeverQualify() throws Exception {
        FakeSource source = new FakeSource();
        EmbeddedDuplexFrameWindow window = window(source);
        window.sampleOnce();
        source.currentRoute = false;
        window.sampleOnce();
        source.currentRoute = true;
        for (int i = 0; i < 500; i++) window.sampleOnce();
        JSONObject receipt = new JSONObject(window.receipt(CHALLENGE));
        assertEquals("failed", receipt.getString("state"));
        assertEquals("route_or_activation_changed", receipt.getString("sticky_failure_code"));
        assertFalse(receipt.getBoolean("current_route"));

        EmbeddedDuplexFrameWindow stopped = window(new FakeSource());
        stopped.sampleOnce();
        stopped.cancelForCleanup();
        stopped.sampleOnce();
        assertEquals("cleanup_started",
                new JSONObject(stopped.receipt(CHALLENGE)).getString("sticky_failure_code"));
    }

    @Test public void gapStaleFrameAndReconnectFailClosed() throws Exception {
        FakeSource gap = new FakeSource();
        EmbeddedDuplexFrameWindow gapWindow = window(gap);
        gapWindow.sampleOnce();
        gap.stepNs = 600_000_000L;
        gapWindow.sampleOnce();
        assertEquals("observation_gap",
                new JSONObject(gapWindow.receipt(CHALLENGE)).getString("sticky_failure_code"));

        FakeSource stale = new FakeSource();
        stale.ageNs = 500_000_001L;
        EmbeddedDuplexFrameWindow staleWindow = window(stale);
        staleWindow.sampleOnce();
        assertEquals("native_frame_stale_or_malformed",
                new JSONObject(staleWindow.receipt(CHALLENGE)).getString("sticky_failure_code"));

        FakeSource reconnect = new FakeSource();
        EmbeddedDuplexFrameWindow reconnectWindow = window(reconnect);
        reconnectWindow.sampleOnce();
        reconnect.connection = 2L;
        reconnectWindow.sampleOnce();
        assertEquals("connection_changed",
                new JSONObject(reconnectWindow.receipt(CHALLENGE)).getString("sticky_failure_code"));
    }

    @Test public void sameFrameStallsEvenWhenPollingCadenceIsGood() throws Exception {
        FakeSource source = new FakeSource();
        source.advance = false;
        EmbeddedDuplexFrameWindow window = window(source);
        for (int i = 0; i < 6; i++) window.sampleOnce();
        assertEquals("frame_identity_stalled",
                new JSONObject(window.receipt(CHALLENGE)).getString("sticky_failure_code"));
    }

    @Test public void changedIdentityWithNonadvancingPtsFails() throws Exception {
        FakeSource source = new FakeSource();
        EmbeddedDuplexFrameWindow window = window(source);
        window.sampleOnce();
        source.ptsOverride = 33_333_333L;
        window.sampleOnce();
        assertEquals("presentation_time_not_advancing",
                new JSONObject(window.receipt(CHALLENGE)).getString("sticky_failure_code"));
    }

    @Test public void malformedSensorTimestampCannotOverflowPairDelta() throws Exception {
        FakeSource source = new FakeSource();
        source.sensorTimestamp = Long.MIN_VALUE;
        EmbeddedDuplexFrameWindow window = window(source);
        window.sampleOnce();
        assertEquals("native_frame_stale_or_malformed",
                new JSONObject(window.receipt(CHALLENGE)).getString("sticky_failure_code"));
    }

    private static EmbeddedDuplexFrameWindow window(FakeSource source) {
        return new EmbeddedDuplexFrameWindow(source, "a_to_b", ROUTE, EPOCH, REVISION);
    }

    private static final class FakeSource implements EmbeddedDuplexFrameWindow.Source {
        int count;
        long stepNs = 250_000_000L, ageNs = 10_000_000L, connection = 1L;
        boolean currentRoute = true, advance = true;
        long sensorTimestamp = 100L;
        long ptsOverride;

        @Override public EmbeddedDuplexFrameWindow.Fence recheck() {
            return new EmbeddedDuplexFrameWindow.Fence(EPOCH, ROUTE, REVISION, currentRoute, true);
        }

        @Override public EmbeddedDuplexFrameWindow.Sample observe() {
            long at = 1_000_000_000_000L + count * stepNs;
            long frame = advance ? count + 1L : 1L;
            count++;
            long[] words = new long[] {
                    1L, connection, 3L, 4L, 5L,
                    ptsOverride == 0L ? frame * 33_333_333L : ptsOverride,
                    6L, 7L, frame, frame, frame, sensorTimestamp, sensorTimestamp, 0L,
                    at - ageNs, at - ageNs / 2L, at - ageNs / 4L, at, ageNs
            };
            EmbeddedDuplexFrameWindow.Fence fence = new EmbeddedDuplexFrameWindow.Fence(
                    EPOCH, ROUTE, REVISION, currentRoute, true);
            return new EmbeddedDuplexFrameWindow.Sample(fence, words, fence);
        }
    }
}
