package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public final class EmbeddedDuplexProcessHostAttachmentTest {
    @Test public void inertDisplayDetachesBeforeReplacementAndBootstrapNeedsAttachment() throws Exception {
        EmbeddedDuplexProcessHost host = newIsolatedHost();

        ExecutionException noDisplay = assertThrows(ExecutionException.class,
                () -> host.initialize(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A,
                        new JSONObject(), new JSONObject()).get());
        assertTrue(noDisplay.getCause().getMessage().contains("display unavailable"));
        assertFalse(host.ready());

        long first = host.attachDisplay(new FakeDisplay());
        assertThrows(IllegalStateException.class,
                () -> host.detachUninitializedDisplay(first + 1L));
        assertThrows(IllegalStateException.class,
                () -> host.attachDisplay(new FakeDisplay()));
        host.detachUninitializedDisplay(first);
        assertEquals(first + 1L, host.attachDisplay(new FakeDisplay()));
    }

    @Test public void bootstrapAndReadyKeepOldDisplayUntilProductCleanupExists() throws Exception {
        EmbeddedDuplexProcessHost host = newIsolatedHost();
        long generation = host.attachDisplay(new FakeDisplay());
        for (String phase : new String[] {"BOOTSTRAPPING", "READY", "FAILED"}) {
            setPhase(host, phase);
            assertThrows(IllegalStateException.class,
                    () -> host.detachUninitializedDisplay(generation));
            assertEquals(0L, host.displayAttachment().ensureLocalCaptureStopped());
        }
        setPhase(host, "NEW");
        host.detachUninitializedDisplay(generation);
    }

    private static EmbeddedDuplexProcessHost newIsolatedHost() throws Exception {
        Constructor<EmbeddedDuplexProcessHost> constructor =
                EmbeddedDuplexProcessHost.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance((Context) null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setPhase(EmbeddedDuplexProcessHost host, String value) throws Exception {
        Field field = EmbeddedDuplexProcessHost.class.getDeclaredField("phase");
        field.setAccessible(true);
        AtomicReference phase = (AtomicReference) field.get(host);
        Class<? extends Enum> type = (Class<? extends Enum>)
                Class.forName(EmbeddedDuplexProcessHost.class.getName() + "$Phase");
        phase.set(Enum.valueOf(type, value));
    }

    private static final class FakeDisplay implements EmbeddedDuplexDisplay {
        @Override public long ensureLocalCaptureStopped() { return 0L; }
        @Override public long preparePeerProjection() { return 0L; }
        @Override public void bindPeerProjection(long routeGeneration, long decoderToken,
                long readerGeneration) {}
        @Override public void activatePeerProjection(long routeGeneration, long decoderToken,
                long readerGeneration) {}
        @Override public long[] currentProjection(long routeGeneration) { return new long[0]; }
        @Override public void retirePeerProjection() {}
        @Override public void restoreLocalAfterProductCleanup() {}
    }
}
