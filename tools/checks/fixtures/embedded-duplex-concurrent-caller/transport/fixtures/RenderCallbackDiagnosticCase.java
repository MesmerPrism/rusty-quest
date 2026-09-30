package io.github.mesmerprism.rustyquest.media;

import android.media.MediaCodec;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Exercises the real receiver's listener-entry accounting without a device codec. */
public final class RenderCallbackDiagnosticCase {
    private RenderCallbackDiagnosticCase() { }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void expect(String counters, String field, String value) {
        if (!counters.contains(" " + field + "=" + value + " ")) {
            throw new AssertionError(field + " expected " + value + ": " + counters);
        }
    }

    public static void main(String[] ignored) throws Exception {
        PackedStereoMediaReceiver receiver = new PackedStereoMediaReceiver(
                new Surface(), "127.0.0.1", 1, 1,
                new PackedStereoMediaReceiver.Bounds(
                        1024, 4096, 16, 16, 32, 100, 100, 100, 100, 100, 0, 1), null);
        Method rendered = PackedStereoMediaReceiver.class.getDeclaredMethod(
                "rendered", MediaCodec.class, long.class, long.class);
        rendered.setAccessible(true);
        set(receiver, "state", "receiver_armed");

        @SuppressWarnings("unchecked")
        Map<Long, Object> pending = (Map<Long, Object>) get(receiver, "pendingFrames");
        Class<?> frameType = Class.forName(PackedStereoMediaReceiver.class.getName() + "$PendingFrame");
        Constructor<?> constructor = frameType.getDeclaredConstructor(
                long.class, PackedStereoMediaReceiver.FrameIdentity.class);
        constructor.setAccessible(true);
        Object frame = constructor.newInstance(1L, null);
        pending.put(1234L, frame);

        // The first callback has a different exact PTS. Keep one numeric sample
        // and classify later callbacks without overwriting that first evidence.
        rendered.invoke(receiver, null, 1L, 9999L);
        set(receiver, "state", "failed");
        rendered.invoke(receiver, null, 1L, 1234L);
        set(receiver, "state", "receiver_armed");
        rendered.invoke(receiver, null, 2L, 1234L);
        rendered.invoke(receiver, null, 1L, 1234L);
        set(frame, "readyForRender", true);
        rendered.invoke(receiver, null, 1L, 1234L);
        set(frame, "presentationTimeNs", 1_234_000L);
        set(receiver, "connectionGeneration", 1L);
        rendered.invoke(receiver, null, 1L, 1234L);

        String counters = " " + receiver.closedActivationCounters() + " ";
        expect(counters, "rawRenderCallbacks", "6");
        expect(counters, "renderCallbacks", "1");
        expect(counters, "renderRejectState", "1");
        expect(counters, "renderRejectMissingPts", "1");
        expect(counters, "renderRejectConnection", "1");
        expect(counters, "renderRejectNotReady", "1");
        expect(counters, "renderRejectTimestamp", "1");
        expect(counters, "firstRenderReject", "PTS_MISSING");
        expect(counters, "firstRenderRejectMediaTimeUs", "9999");
        expect(counters, "firstRenderRejectPendingPtsUs", "1234");
        expect(counters, "firstRenderRejectPendingCount", "1");
        acquiredSurfaceCyclesWithoutCodecCallbacks();
        System.out.println("render callback entry/rejection/accepted identity diagnostics: PASS");
    }

    private static PackedStereoMediaReceiver.FrameIdentity identity(long value) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        PackedStereoMediaReceiver.FrameIdentity identity =
                (PackedStereoMediaReceiver.FrameIdentity) unsafe
                        .getMethod("allocateInstance", Class.class)
                        .invoke(singleton.get(null), PackedStereoMediaReceiver.FrameIdentity.class);
        for (String name : new String[] {"presentationTimeUs", "sourceElapsedNs", "sourceUnixNs",
                "pairId", "leftSourceFrame", "rightSourceFrame",
                "leftSensorTimestampNs", "rightSensorTimestampNs"}) {
            Field field = identity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(identity, value);
        }
        return identity;
    }

    private static void acquiredSurfaceCyclesWithoutCodecCallbacks() throws Exception {
        final PackedStereoMediaReceiver.AcquiredFrame[] latest = new PackedStereoMediaReceiver.AcquiredFrame[1];
        PackedStereoMediaReceiver.SurfaceAcquisitionProbe probe = (receiver, connection) -> latest[0];
        PackedStereoMediaReceiver receiver = new PackedStereoMediaReceiver(new Surface(),
                "127.0.0.1", 1, 1,
                new PackedStereoMediaReceiver.Bounds(
                        1024, 4096, 16, 16, 32, 100, 100, 100, 100, 100, 0, 1),
                null, null, null, probe);
        set(receiver, "state", "receiver_armed");
        set(receiver, "liveRequested", true);
        set(receiver, "connectionGeneration", 1L);
        @SuppressWarnings("unchecked")
        Map<Long, Object> pending = (Map<Long, Object>) get(receiver, "pendingFrames");
        @SuppressWarnings("unchecked")
        Map<Long, Object> retired = (Map<Long, Object>) get(receiver, "retiredRenderFrames");
        Class<?> frameType = Class.forName(PackedStereoMediaReceiver.class.getName() + "$PendingFrame");
        Constructor<?> constructor = frameType.getDeclaredConstructor(
                long.class, PackedStereoMediaReceiver.FrameIdentity.class);
        constructor.setAccessible(true);
        Method poll = PackedStereoMediaReceiver.class.getDeclaredMethod(
                "pollSurfaceAcquisition", MediaCodec.class, long.class);
        poll.setAccessible(true);
        Method callback = PackedStereoMediaReceiver.class.getDeclaredMethod(
                "rendered", MediaCodec.class, long.class, long.class);
        callback.setAccessible(true);
        for (long i = 1; i <= 2000; i++) {
            Object frame = constructor.newInstance(1L, identity(i));
            set(frame, "readyForRender", true);
            set(frame, "presentationTimeNs", i * 1000L);
            set(frame, "releaseOrdinal", i);
            pending.put(i, frame);
            latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                    i == 1 ? 2 : 1, 1, i * 1000L, i, i, i, i, i, i, i, 0);
            poll.invoke(receiver, null, 1L);
            if (i == 1) {
                expect(" " + receiver.closedActivationCounters() + " ", "acquiredFrames", "0");
                latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                        1, 1, i * 1000L, i, i, i, i, i, i, i, 0);
                poll.invoke(receiver, null, 1L);
            }
            if (pending.size() > 32 || retired.size() > 32) {
                throw new AssertionError("unbounded acquired-image identity history");
            }
        }
        // Repeated feedback cannot turn one actual image into multiple witnesses.
        poll.invoke(receiver, null, 1L);
        latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                1, 1, 2_000_000L, 1, 1, 1, 1, 1, 1, 1, 0);
        poll.invoke(receiver, null, 1L);
        latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                1, 2, 2_000_000L, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 0);
        poll.invoke(receiver, null, 1L);
        latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                1, 1, 3_000_000L, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 0);
        poll.invoke(receiver, null, 1L);
        // A later acquired release skips an undecoded queued identity, but it
        // may retire only an older frame actually released to the Surface.
        Object queued = constructor.newInstance(1L, identity(2100));
        pending.put(2100L, queued);
        Object released = constructor.newInstance(1L, identity(2101));
        set(released, "readyForRender", true);
        set(released, "presentationTimeNs", 2_101_000L);
        set(released, "releaseOrdinal", 2001L);
        pending.put(2101L, released);
        latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                1, 1, 2_101_000L, 2101, 2101, 2101, 2101, 2101, 2101, 2101, 0);
        poll.invoke(receiver, null, 1L);
        if (!pending.containsKey(2100L) || !pending.containsKey(2101L)
                || pending.containsKey(2000L)) {
            throw new AssertionError("acquisition retired a queued or newer identity");
        }
        // Reordered old acquisition cannot roll back the newest release.
        latest[0] = new PackedStereoMediaReceiver.AcquiredFrame(
                1, 1, 2_000_000L, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 0);
        poll.invoke(receiver, null, 1L);
        String counters = " " + receiver.closedActivationCounters() + " ";
        expect(counters, "rawRenderCallbacks", "0");
        expect(counters, "renderCallbacks", "0");
        expect(counters, "acquiredFrames", "2001");
        expect(counters, "acquiredSuperseded", "2000");
        expect(counters, "acquisitionFeedbackRejected", "4");
        if (!"receiving".equals(receiver.snapshot().state()) || pending.size() != 2) {
            throw new AssertionError("exact acquisition did not release only older identities");
        }
        // A real late callback retains its exact identity and is still counted;
        // absence of any callback never fabricated one during the 2000 cycles.
        callback.invoke(receiver, null, 1L, 1999L);
        expect(" " + receiver.closedActivationCounters() + " ", "renderCallbacks", "1");
    }
}
