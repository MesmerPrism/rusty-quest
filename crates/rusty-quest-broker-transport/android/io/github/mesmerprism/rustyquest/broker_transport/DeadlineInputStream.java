package io.github.mesmerprism.rustyquest.broker_transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/** Converts short socket poll timeouts into one monotonic first-byte and assembly deadline. */
public final class DeadlineInputStream extends InputStream {
    public interface Clock {
        long nowNanos();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nowNanos() { return System.nanoTime(); }
    };

    private final InputStream delegate;
    private final Clock clock;
    private final long firstByteDeadlineNanos;
    private final long assemblyTimeoutNanos;
    private boolean started;
    private long assemblyDeadlineNanos;

    public DeadlineInputStream(
            InputStream delegate,
            long firstByteDeadlineNanos,
            long assemblyTimeoutNanos) {
        this(delegate, firstByteDeadlineNanos, assemblyTimeoutNanos, SYSTEM_CLOCK);
    }

    public DeadlineInputStream(
            InputStream delegate,
            long firstByteDeadlineNanos,
            long assemblyTimeoutNanos,
            Clock clock) {
        if (delegate == null || clock == null) {
            throw new IllegalArgumentException("delegate and clock are required");
        }
        if (firstByteDeadlineNanos < 0 || assemblyTimeoutNanos < 1) {
            throw new IllegalArgumentException("deadlines must be non-negative");
        }
        this.delegate = delegate;
        this.firstByteDeadlineNanos = firstByteDeadlineNanos;
        this.assemblyTimeoutNanos = assemblyTimeoutNanos;
        this.clock = clock;
    }

    @Override public int read() throws IOException {
        if (!started) {
            int value = readOneUntil(firstByteDeadlineNanos, "websocket first-byte deadline");
            if (value >= 0) {
                started = true;
                assemblyDeadlineNanos = saturatedAdd(clock.nowNanos(), assemblyTimeoutNanos);
            }
            return value;
        }
        return readOneUntil(assemblyDeadlineNanos, "websocket frame-assembly deadline");
    }

    @Override public int read(byte[] value, int offset, int length) throws IOException {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (offset < 0 || length < 0 || offset > value.length - length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        if (!started) {
            int first = read();
            if (first < 0) {
                return -1;
            }
            value[offset] = (byte) first;
            if (length == 1) {
                return 1;
            }
            int rest = readBounded(value, offset + 1, length - 1);
            return rest < 0 ? 1 : rest + 1;
        }
        return readBounded(value, offset, length);
    }

    private int readBounded(byte[] value, int offset, int length) throws IOException {
        while (clock.nowNanos() < assemblyDeadlineNanos) {
            try {
                return delegate.read(value, offset, length);
            } catch (SocketTimeoutException timeout) {
                // The absolute monotonic deadline remains authoritative.
            }
        }
        throw new SocketTimeoutException("websocket frame-assembly deadline exceeded");
    }

    private int readOneUntil(long deadlineNanos, String label) throws IOException {
        while (clock.nowNanos() < deadlineNanos) {
            try {
                return delegate.read();
            } catch (SocketTimeoutException timeout) {
                // The absolute monotonic deadline remains authoritative.
            }
        }
        throw new SocketTimeoutException(label + " exceeded");
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
