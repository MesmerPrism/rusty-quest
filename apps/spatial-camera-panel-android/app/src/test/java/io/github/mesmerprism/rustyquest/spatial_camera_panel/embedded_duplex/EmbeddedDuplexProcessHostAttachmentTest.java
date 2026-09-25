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

    @Test public void localDiagnosticFailureBeforeRouteStillClosesDisplay() throws Exception {
        EmbeddedDuplexProcessHost host = newIsolatedHost();
        long generation = host.attachDisplay(new FakeDisplay());
        host.armDiagnosticChallenge("00112233445566778899aabbccddeeff");
        // The isolated host intentionally has no Android Context for a durable
        // receipt, but its failed bootstrap must still terminalize the display.
        JSONObject result = new JSONObject(host.diagnoseLocalFixture(generation).get());
        assertEquals("receipt_unavailable_closed", result.getString("status"));
        assertTrue(result.getBoolean("display_detached"));
        assertEquals(generation + 1L, host.attachDisplay(new FakeDisplay()));
    }

    @Test public void retryClosesOnlyUninitializedAttachmentWithoutNativeRoute() throws Exception {
        EmbeddedDuplexProcessHost host = newIsolatedHost();
        long generation = host.attachDisplay(new FakeDisplay());
        assertEquals("uninitialized-display-detached",
                host.retryLocalDiagnosticCleanup(generation).get());
        assertEquals(generation + 1L, host.attachDisplay(new FakeDisplay()));
    }

    @Test public void typedNoMediaCloseReturnsFreshAttachmentGeneration() throws Exception {
        EmbeddedDuplexProcessHost host = newIsolatedHost();
        long first = host.attachDisplay(new FakeDisplay());
        EmbeddedDuplexRuntimeStatus ready = host.runtimeStatus().get();
        assertEquals("uninitialized", ready.state);
        assertTrue(ready.displayAttached);
        ExecutionException stale = assertThrows(ExecutionException.class,
                () -> host.closeRealPeerNoMedia(first + 1L).get());
        assertTrue(stale.getCause().getMessage().contains("unavailable"));
        assertEquals("uninitialized-display-detached", host.closeRealPeerNoMedia(first).get());
        assertFalse(host.runtimeStatus().get().displayAttached);
        assertEquals(first + 1L, host.attachDisplay(new FakeDisplay()));
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
