package io.github.mesmerprism.rustyquest.broker_transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

public final class DeadlineInputStreamTest {
    public static void main(String[] args) throws Exception {
        testPollTimeoutsPreservePartialRead();
        testFirstByteDeadline();
        testAssemblyDeadline();
        System.out.println("DeadlineInputStreamTest PASS");
    }

    private static void testPollTimeoutsPreservePartialRead() throws Exception {
        FakeClock clock = new FakeClock();
        PollingInputStream source = new PollingInputStream(new byte[] {1, 2, 3}, clock, 2);
        DeadlineInputStream bounded = new DeadlineInputStream(source, 20, 20, clock);
        byte[] value = new byte[3];
        int count = bounded.read(value, 0, value.length);
        assertEquals(3, count, "partial read count");
        assertEquals(1, value[0], "partial first byte");
        assertEquals(3, value[2], "partial final byte");
    }

    private static void testFirstByteDeadline() throws Exception {
        FakeClock clock = new FakeClock();
        DeadlineInputStream bounded = new DeadlineInputStream(
                new AlwaysTimeoutInputStream(clock), 3, 10, clock);
        expectTimeout(new ThrowingRunnable() {
            @Override public void run() throws Exception { bounded.read(); }
        }, "first-byte deadline");
    }

    private static void testAssemblyDeadline() throws Exception {
        FakeClock clock = new FakeClock();
        InputStream source = new InputStream() {
            private boolean first = true;
            @Override public int read() throws IOException {
                if (first) {
                    first = false;
                    return 7;
                }
                clock.advance();
                throw new SocketTimeoutException("poll");
            }
        };
        final DeadlineInputStream bounded = new DeadlineInputStream(source, 5, 3, clock);
        assertEquals(7, bounded.read(), "assembly first byte");
        expectTimeout(new ThrowingRunnable() {
            @Override public void run() throws Exception { bounded.read(); }
        }, "assembly deadline");
    }

    private static void expectTimeout(ThrowingRunnable action, String label) throws Exception {
        boolean timedOut = false;
        try {
            action.run();
        } catch (SocketTimeoutException expected) {
            timedOut = true;
        }
        if (!timedOut) {
            throw new AssertionError(label + " must time out");
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeClock implements DeadlineInputStream.Clock {
        private long now;
        @Override public long nowNanos() { return now; }
        void advance() { now += 1; }
    }

    private static final class AlwaysTimeoutInputStream extends InputStream {
        private final FakeClock clock;
        AlwaysTimeoutInputStream(FakeClock clock) { this.clock = clock; }
        @Override public int read() throws IOException {
            clock.advance();
            throw new SocketTimeoutException("poll");
        }
    }

    private static final class PollingInputStream extends InputStream {
        private final ByteArrayInputStream data;
        private final FakeClock clock;
        private int pollsRemaining;

        PollingInputStream(byte[] data, FakeClock clock, int pollsRemaining) {
            this.data = new ByteArrayInputStream(data);
            this.clock = clock;
            this.pollsRemaining = pollsRemaining;
        }

        @Override public int read() throws IOException {
            if (pollsRemaining > 0) {
                pollsRemaining -= 1;
                clock.advance();
                throw new SocketTimeoutException("poll");
            }
            return data.read();
        }

        @Override public int read(byte[] value, int offset, int length) throws IOException {
            return data.read(value, offset, length);
        }
    }
}
