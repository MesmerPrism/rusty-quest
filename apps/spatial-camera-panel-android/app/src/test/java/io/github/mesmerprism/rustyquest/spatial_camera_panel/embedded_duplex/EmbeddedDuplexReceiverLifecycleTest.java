package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.mesmerprism.rustyquest.media.CancellationHandle;
import io.github.mesmerprism.rustyquest.media.MediaOwnerAction;
import io.github.mesmerprism.rustyquest.media.MediaOwnerProvider;
import io.github.mesmerprism.rustyquest.media.MediaProviderReadback;
import io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaReceiver;
import java.util.ArrayDeque;
import org.junit.Test;

public final class EmbeddedDuplexReceiverLifecycleTest {
    private static final long GENERATION = 17L;

    @Test public void constructionDoesNotAllocateAndArmAllocatesThenBinds() throws Exception {
        FakeDisplay display = new FakeDisplay();
        FakeFactory factory = new FakeFactory();
        EmbeddedDuplexReceiver receiver = receiver(display, factory);

        assertEquals(0, display.prepareCount);
        assertEquals(0, factory.stageCount);
        assertEquals(0, factory.createCount);
        assertEquals(0L, receiver.routeGeneration());

        receiver.execute(action("arm_receiver", "start", 1), new CancellationHandle(GENERATION));

        assertEquals(1, display.prepareCount);
        assertEquals(1, factory.stageCount);
        assertEquals(1, factory.createCount);
        assertEquals(1, display.bindCount);
        assertEquals(41L, receiver.routeGeneration());
        assertEquals(42L, receiver.decoderToken());
        assertEquals(43L, receiver.readerGeneration());
        assertEquals("prepared", receiver.snapshot().state());
        assertFalse(receiver.snapshot().terminal());
    }

    @Test public void failedReaderReceiverAndBindRetainStagedHandle() {
        FakeDisplay readerDisplay = new FakeDisplay();
        FakeFactory readerFactory = new FakeFactory();
        readerFactory.projection.readerGeneration = 0L;
        EmbeddedDuplexReceiver readerFailure = receiver(readerDisplay, readerFactory);
        assertThrows(IllegalStateException.class, () -> arm(readerFailure));
        assertRetainedFailure(readerFailure, readerFactory, 0);

        FakeDisplay receiverDisplay = new FakeDisplay();
        FakeFactory receiverFactory = new FakeFactory();
        receiverFactory.failCreate = true;
        EmbeddedDuplexReceiver createFailure = receiver(receiverDisplay, receiverFactory);
        assertThrows(IllegalStateException.class, () -> arm(createFailure));
        assertRetainedFailure(createFailure, receiverFactory, 1);

        FakeDisplay bindDisplay = new FakeDisplay();
        bindDisplay.failBind = true;
        FakeFactory bindFactory = new FakeFactory();
        EmbeddedDuplexReceiver bindFailure = receiver(bindDisplay, bindFactory);
        assertThrows(IllegalStateException.class, () -> arm(bindFailure));
        assertRetainedFailure(bindFailure, bindFactory, 1);
    }

    @Test public void displayPreparationFailureBecomesRetryableFailedStateWithoutAllocation() {
        FakeDisplay display = new FakeDisplay();
        display.failPrepare = true;
        FakeFactory factory = new FakeFactory();
        EmbeddedDuplexReceiver receiver = receiver(display, factory);

        assertThrows(IllegalStateException.class, () -> arm(receiver));

        assertEquals("preparation_failed", receiver.snapshot().state());
        assertFalse(receiver.snapshot().terminal());
        assertEquals(0, factory.stageCount);
    }

    @Test public void compensationRetriesReleaseAndBecomesTerminalOnlyAfterVerifiedRelease()
            throws Exception {
        FakeDisplay display = new FakeDisplay();
        FakeFactory factory = new FakeFactory();
        factory.failCreate = true;
        factory.projection.releaseResults.add(false);
        factory.projection.releaseResults.add(true);
        EmbeddedDuplexReceiver receiver = receiver(display, factory);
        assertThrows(IllegalStateException.class, () -> arm(receiver));
        MediaOwnerAction cleanup = action("cleanup", "stop", 2);

        assertThrows(IllegalStateException.class,
                () -> receiver.compensate(cleanup, new CancellationHandle(GENERATION)));
        assertEquals("cleanup_pending", receiver.snapshot().state());
        assertFalse(receiver.snapshot().terminal());
        assertEquals(1, factory.projection.releaseCount);

        MediaProviderReadback readback = receiver.compensate(
                cleanup, new CancellationHandle(GENERATION));
        assertEquals("cleaned", readback.observedState());
        assertEquals(2, factory.projection.releaseCount);
        assertEquals(1, display.retireCount);
        assertEquals(0, display.restoreLocalCount);
        assertEquals("cleaned", receiver.snapshot().state());
        assertTrue(receiver.snapshot().terminal());
    }

    @Test public void compensationRetriesDisplayRetirementAfterSurfaceWasReleased()
            throws Exception {
        FakeDisplay display = new FakeDisplay();
        display.retireResults.add(false);
        display.retireResults.add(true);
        FakeFactory factory = new FakeFactory();
        factory.failCreate = true;
        EmbeddedDuplexReceiver receiver = receiver(display, factory);
        assertThrows(IllegalStateException.class, () -> arm(receiver));
        MediaOwnerAction cleanup = action("cleanup", "stop", 2);

        assertThrows(IllegalStateException.class,
                () -> receiver.compensate(cleanup, new CancellationHandle(GENERATION)));
        assertEquals(1, factory.projection.releaseCount);
        assertEquals(1, display.retireCount);
        assertFalse(receiver.snapshot().terminal());

        receiver.compensate(cleanup, new CancellationHandle(GENERATION));
        assertEquals(1, factory.projection.releaseCount);
        assertEquals(2, display.retireCount);
        assertEquals(0, display.restoreLocalCount);
        assertTrue(receiver.snapshot().terminal());
    }

    private static void assertRetainedFailure(EmbeddedDuplexReceiver receiver,
            FakeFactory factory, int expectedCreateCount) {
        assertEquals("preparation_failed", receiver.snapshot().state());
        assertFalse(receiver.snapshot().terminal());
        assertEquals(41L, receiver.routeGeneration());
        assertEquals(42L, receiver.decoderToken());
        assertEquals(expectedCreateCount, factory.createCount);
        assertEquals(0, factory.projection.releaseCount);
    }

    private static void arm(EmbeddedDuplexReceiver receiver) throws Exception {
        receiver.execute(action("arm_receiver", "start", 1), new CancellationHandle(GENERATION));
    }

    private static EmbeddedDuplexReceiver receiver(FakeDisplay display, FakeFactory factory) {
        return new EmbeddedDuplexReceiver(GENERATION, display, "127.0.0.1", 30401,
                1920, 1080, 60, bounds(), factory);
    }

    private static PackedStereoMediaReceiver.Bounds bounds() {
        return new PackedStereoMediaReceiver.Bounds(
                4096, 1024 * 1024, 4096, 4096, 4,
                1000, 1000, 1000, 1000, 1000, 1, 1);
    }

    private static MediaOwnerAction action(String kind, String operation, int sequence) {
        return MediaOwnerAction.parse("{\"$schema\":\"rusty.quest.android.media.execution-ticket.v1\","+
                "\"capability\":\"capability.test\",\"executor_generation\":17,"+
                "\"action_id\":\"action." + sequence + "\",\"authority_epoch_id\":\"epoch.test\","+
                "\"media_acceptance_authority_revision\":1,\"expected_runtime_revision\":0,"+
                "\"client_id\":\"client.test\",\"lease_id\":\"lease.test\",\"sequence\":" + sequence + ","+
                "\"operation\":\"" + operation + "\",\"owner_kind\":\"sink\","+
                "\"action_kind\":\"" + kind + "\",\"owner_id\":\"owner.test\","+
                "\"provider_kind\":\"receiver\",\"resource_id\":\"resource.test\"}");
    }

    private static final class FakeProjection implements EmbeddedDuplexReceiver.ProjectionResource {
        long readerGeneration = 43L;
        int releaseCount;
        final ArrayDeque<Boolean> releaseResults = new ArrayDeque<>();

        @Override public long routeGeneration() { return 41L; }
        @Override public long decoderToken() { return 42L; }
        @Override public long readerGeneration() { return readerGeneration; }
        @Override public boolean release() {
            releaseCount++;
            return releaseResults.isEmpty() || releaseResults.remove();
        }
    }

    private static final class FakeFactory implements EmbeddedDuplexReceiver.RuntimeFactory {
        final FakeProjection projection = new FakeProjection();
        int stageCount;
        int createCount;
        boolean failCreate;

        @Override public EmbeddedDuplexReceiver.ProjectionResource stage(int width, int height,
                int imageCount, int fpsCap, long routeGeneration) {
            stageCount++;
            assertEquals(41L, routeGeneration);
            return projection;
        }

        @Override public EmbeddedDuplexReceiver.ReceiverRuntime create(
                EmbeddedDuplexReceiver.ProjectionResource ignoredProjection, String sourceHost,
                int sourcePort, long generation, PackedStereoMediaReceiver.Bounds ignoredBounds,
                PackedStereoMediaReceiver.FrameLifecycleListener ignoredListener) {
            createCount++;
            if (failCreate) throw new IllegalStateException("create failed");
            return new FakeRuntime(generation);
        }
    }

    private static final class FakeRuntime implements EmbeddedDuplexReceiver.ReceiverRuntime,
            MediaOwnerProvider {
        private final long generation;
        private long revision = 1L;

        FakeRuntime(long generation) { this.generation = generation; }
        @Override public MediaOwnerProvider provider() { return this; }
        @Override public void start() { }
        @Override public MediaRuntimeSnapshot snapshot() {
            return new MediaRuntimeSnapshot(generation, revision, "prepared", false, "",
                    "fake-receiver");
        }
        @Override public MediaProviderReadback execute(MediaOwnerAction action,
                CancellationHandle cancellation) {
            return readback(action, "prepared");
        }
        @Override public MediaProviderReadback compensate(MediaOwnerAction action,
                CancellationHandle cancellation) {
            return readback(action, "stopped");
        }
        private MediaProviderReadback readback(MediaOwnerAction action, String state) {
            return new MediaProviderReadback(action, "fake-receiver", ++revision, state,
                    "receipt." + revision);
        }
    }

    private static final class FakeDisplay implements EmbeddedDuplexDisplay {
        int prepareCount;
        int bindCount;
        int retireCount;
        int restoreLocalCount;
        boolean failPrepare;
        boolean failBind;
        final ArrayDeque<Boolean> retireResults = new ArrayDeque<>();

        @Override public long ensureLocalCaptureStopped() { return 40L; }
        @Override public long preparePeerProjection() {
            prepareCount++;
            if (failPrepare) throw new IllegalStateException("prepare failed");
            return 41L;
        }
        @Override public void bindPeerProjection(long routeGeneration, long decoderToken,
                long readerGeneration) {
            bindCount++;
            if (failBind) throw new IllegalStateException("bind failed");
        }
        @Override public void activatePeerProjection(long routeGeneration, long decoderToken,
                long readerGeneration) { }
        @Override public long[] currentProjection(long routeGeneration) { return new long[0]; }
        @Override public void retirePeerProjection() {
            retireCount++;
            if (!retireResults.isEmpty() && !retireResults.remove()) {
                throw new IllegalStateException("retirement pending");
            }
        }
        @Override public void restoreLocalAfterProductCleanup() { restoreLocalCount++; }
    }
}
