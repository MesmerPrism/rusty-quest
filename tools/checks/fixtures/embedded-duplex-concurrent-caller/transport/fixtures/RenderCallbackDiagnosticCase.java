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
        System.out.println("render callback entry/rejection/accepted identity diagnostics: PASS");
    }
}
