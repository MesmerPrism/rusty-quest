package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmbeddedDuplexDisplaySlotTest {
    @Test public void cleanupFenceRetainsFailedAttachmentUntilExactRetry() throws Exception {
        EmbeddedDuplexDisplaySlot slot = new EmbeddedDuplexDisplaySlot();
        FakeDisplay first = new FakeDisplay();
        long generation = slot.attach(first);
        assertEquals(1L, slot.ensureLocalCaptureStopped());
        assertThrows(IllegalStateException.class,
                () -> slot.detachAfterCleanup(generation, () -> { throw new IllegalStateException("cleanup pending"); }));
        assertTrue(slot.cleanupPending());
        assertThrows(IllegalStateException.class, slot::preparePeerProjection);
        assertThrows(IllegalStateException.class, () -> slot.attach(new FakeDisplay()));

        slot.detachAfterCleanup(generation, slot::restoreLocalAfterProductCleanup);
        assertEquals(1, first.restored);
        assertFalse(slot.cleanupPending());
        assertThrows(IllegalStateException.class, slot::preparePeerProjection);
        FakeDisplay replacement = new FakeDisplay();
        assertEquals(generation + 1L, slot.attach(replacement));
        assertEquals(2L, slot.preparePeerProjection());
    }

    @Test public void recursiveSynchronousDisplayCallbackFailsClosed() {
        EmbeddedDuplexDisplaySlot slot = new EmbeddedDuplexDisplaySlot();
        FakeDisplay display = new FakeDisplay();
        display.onPrepare = () -> slot.currentProjection(1L);
        slot.attach(display);
        assertThrows(IllegalStateException.class, slot::preparePeerProjection);
        assertEquals(1L, slot.ensureLocalCaptureStopped());
    }

    private static final class FakeDisplay implements EmbeddedDuplexDisplay {
        Runnable onPrepare;
        int restored;
        @Override public long ensureLocalCaptureStopped() { return 1L; }
        @Override public long preparePeerProjection() {
            if (onPrepare != null) onPrepare.run();
            return 2L;
        }
        @Override public void bindPeerProjection(long routeGeneration, long decoderToken,
                long readerGeneration) {}
        @Override public void activatePeerProjection(long routeGeneration, long decoderToken,
                long readerGeneration) {}
        @Override public long[] currentProjection(long routeGeneration) { return new long[16]; }
        @Override public void retirePeerProjection() {}
        @Override public void restoreLocalAfterProductCleanup() { restored++; }
    }
}
