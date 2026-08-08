package io.github.mesmerprism.rustyquest.broker_transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedWebSocketSessionTest {
    private static final BoundedWebSocketSession.Limits LIMITS =
            new BoundedWebSocketSession.Limits(8, 256, 128, 10, 5, 100);

    public static void main(String[] args) throws Exception {
        testSlowClientIsolationAndQueueBound();
        testConcurrentPublishersUseOneWriter();
        testPingPongAndTimeouts();
        testPeerCloseAndCleanupExactlyOnce();
        testTelemetryCarriesNoPayload();
        System.out.println("BoundedWebSocketSessionTest PASS");
    }

    private static void testSlowClientIsolationAndQueueBound() throws Exception {
        BlockingWriter slowWriter = new BlockingWriter();
        RecordingWriter fastWriter = new RecordingWriter();
        BoundedWebSocketSession slow = new BoundedWebSocketSession(
                "slow-writer", slowWriter,
                new BoundedWebSocketSession.Limits(2, 8, 8, 10, 5, 100),
                0, null, null);
        BoundedWebSocketSession fast = new BoundedWebSocketSession(
                "fast-writer", fastWriter, LIMITS, 0, null, null);
        try {
            slow.sendText("one");
            assertTrue(slowWriter.entered.await(2, TimeUnit.SECONDS), "slow writer entered");
            slow.sendText("two");
            slow.sendText("tri");
            boolean saturated = false;
            try {
                slow.sendText("four");
            } catch (IOException expected) {
                saturated = true;
            }
            assertTrue(saturated, "slow client queue saturation");
            assertTrue(slow.isClosed(), "saturated slow client closed");

            fast.sendText("independent");
            fastWriter.awaitFrames(1);
            assertEquals("independent", fastWriter.textAt(0), "fast client delivery");
            assertTrue(!fast.isClosed(), "fast client remains open");
        } finally {
            slowWriter.release.countDown();
            slow.close();
            fast.close();
        }
    }

    private static void testConcurrentPublishersUseOneWriter() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        BoundedWebSocketSession session = new BoundedWebSocketSession(
                "concurrent-writer", writer,
                new BoundedWebSocketSession.Limits(128, 4096, 128, 10, 5, 100),
                0, null, null);
        try {
            final int publishers = 4;
            final int messagesPerPublisher = 20;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch complete = new CountDownLatch(publishers);
            final List<Throwable> failures =
                    Collections.synchronizedList(new ArrayList<Throwable>());
            for (int publisher = 0; publisher < publishers; publisher += 1) {
                final int publisherId = publisher;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            start.await();
                            for (int index = 0; index < messagesPerPublisher; index += 1) {
                                session.sendText("p" + publisherId + "-" + index);
                            }
                        } catch (Throwable failure) {
                            failures.add(failure);
                        } finally {
                            complete.countDown();
                        }
                    }
                }, "publisher-" + publisher).start();
            }
            start.countDown();
            assertTrue(complete.await(3, TimeUnit.SECONDS), "concurrent publishers complete");
            assertEquals(0, failures.size(), "concurrent publisher failures");
            writer.awaitFrames(publishers * messagesPerPublisher);
            assertEquals(1, writer.maxConcurrentWrites.get(), "single socket writer");
        } finally {
            session.close();
        }
    }

    private static void testPingPongAndTimeouts() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        final List<BoundedWebSocketSession.Telemetry> telemetry =
                Collections.synchronizedList(new ArrayList<BoundedWebSocketSession.Telemetry>());
        BoundedWebSocketSession session = new BoundedWebSocketSession(
                "liveness-writer", writer, LIMITS, 0, null,
                new BoundedWebSocketSession.TelemetrySink() {
                    @Override public void onEvent(BoundedWebSocketSession.Telemetry event) {
                        telemetry.add(event);
                    }
                });
        session.tick(10);
        writer.awaitFrames(1);
        RecordingWriter.Record ping = writer.frameAt(0);
        assertEquals(Rfc6455Codec.OPCODE_PING, ping.opcode, "scheduled ping opcode");
        session.receive(new Rfc6455Codec.Frame(
                true, Rfc6455Codec.OPCODE_PONG, ping.payload), 11);
        session.tick(21);
        writer.awaitFrames(2);
        session.tick(26);
        assertTrue(session.isClosed(), "missing pong closes session");
        assertTrue(hasCloseReason(telemetry, BoundedWebSocketSession.CloseReason.PONG_TIMEOUT),
                "pong timeout telemetry");

        RecordingWriter idleWriter = new RecordingWriter();
        final List<BoundedWebSocketSession.CloseReason> closes = new ArrayList<>();
        BoundedWebSocketSession idle = new BoundedWebSocketSession(
                "idle-writer", idleWriter, LIMITS, 0,
                new BoundedWebSocketSession.CloseListener() {
                    @Override public void onClosed(BoundedWebSocketSession.CloseReason reason) {
                        closes.add(reason);
                    }
                }, null);
        idle.tick(100);
        assertTrue(idle.isClosed(), "idle deadline closes session");
        assertEquals(BoundedWebSocketSession.CloseReason.IDLE_TIMEOUT, closes.get(0),
                "idle close reason");
    }

    private static void testPeerCloseAndCleanupExactlyOnce() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        final AtomicInteger closes = new AtomicInteger();
        BoundedWebSocketSession session = new BoundedWebSocketSession(
                "close-writer", writer, LIMITS, 0,
                new BoundedWebSocketSession.CloseListener() {
                    @Override public void onClosed(BoundedWebSocketSession.CloseReason reason) {
                        closes.incrementAndGet();
                    }
                }, null);
        session.receive(new Rfc6455Codec.Frame(
                true, Rfc6455Codec.OPCODE_CLOSE,
                new byte[] {0x03, (byte) 0xE8}), 1);
        writer.awaitFrames(1);
        awaitClosed(session);
        session.close();
        assertEquals(1, closes.get(), "cleanup callback exactly once");
        assertEquals(1, writer.closeCount.get(), "writer cleanup exactly once");
    }

    private static void testTelemetryCarriesNoPayload() throws Exception {
        for (java.lang.reflect.Field field : BoundedWebSocketSession.Telemetry.class.getFields()) {
            assertTrue(!field.getName().toLowerCase().contains("payload"),
                    "telemetry payload field forbidden");
            assertTrue(field.getType() != byte[].class && field.getType() != String.class,
                    "telemetry text or bytes forbidden");
        }
    }

    private static boolean hasCloseReason(
            List<BoundedWebSocketSession.Telemetry> values,
            BoundedWebSocketSession.CloseReason reason) {
        synchronized (values) {
            for (BoundedWebSocketSession.Telemetry value : values) {
                if (value.closeReason == reason) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void awaitClosed(BoundedWebSocketSession session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!session.isClosed() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(session.isClosed(), "session close completion");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static class RecordingWriter implements BoundedWebSocketSession.FrameWriter {
        final List<Record> frames = Collections.synchronizedList(new ArrayList<Record>());
        final AtomicInteger activeWrites = new AtomicInteger();
        final AtomicInteger maxConcurrentWrites = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();

        @Override public void write(boolean fin, int opcode, byte[] payload) throws IOException {
            int active = activeWrites.incrementAndGet();
            updateMaximum(maxConcurrentWrites, active);
            try {
                frames.add(new Record(opcode, payload));
            } finally {
                activeWrites.decrementAndGet();
            }
        }

        @Override public void close() {
            closeCount.incrementAndGet();
        }

        void awaitFrames(int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (frames.size() < count && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(count, frames.size(), "recorded frame count");
        }

        Record frameAt(int index) {
            return frames.get(index);
        }

        String textAt(int index) {
            return new String(frameAt(index).payload, StandardCharsets.UTF_8);
        }

        private static void updateMaximum(AtomicInteger maximum, int candidate) {
            while (true) {
                int current = maximum.get();
                if (candidate <= current || maximum.compareAndSet(current, candidate)) {
                    return;
                }
            }
        }

        static final class Record {
            final int opcode;
            final byte[] payload;

            Record(int opcode, byte[] payload) {
                this.opcode = opcode;
                this.payload = payload.clone();
            }
        }
    }

    private static final class BlockingWriter extends RecordingWriter {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public void write(boolean fin, int opcode, byte[] payload) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("blocked writer interrupted", interrupted);
            }
            super.write(fin, opcode, payload);
        }
    }
}
