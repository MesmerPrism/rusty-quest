package io.github.mesmerprism.rustyquest.broker_transport;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-writer, bounded-queue RFC6455 session lifecycle with externally driven monotonic time. */
public final class BoundedWebSocketSession implements Closeable {
    public interface FrameWriter extends Closeable {
        void write(boolean fin, int opcode, byte[] payload) throws IOException;
    }

    public interface CloseListener {
        void onClosed(CloseReason reason);
    }

    public interface TelemetrySink {
        void onEvent(Telemetry event);
    }

    public enum EventCode {
        FRAME_QUEUED,
        FRAME_WRITTEN,
        QUEUE_SATURATED,
        PING_QUEUED,
        PONG_MATCHED,
        WRITE_FAILED,
        CLOSED
    }

    public enum CloseReason {
        LOCAL_CLOSE,
        PEER_CLOSE,
        QUEUE_SATURATED,
        PONG_TIMEOUT,
        IDLE_TIMEOUT,
        WRITE_FAILED,
        CANCELLED
    }

    public static final class Telemetry {
        public final EventCode code;
        public final CloseReason closeReason;
        public final int queuedMessages;
        public final int queuedBytes;

        private Telemetry(
                EventCode code,
                CloseReason closeReason,
                int queuedMessages,
                int queuedBytes) {
            this.code = code;
            this.closeReason = closeReason;
            this.queuedMessages = queuedMessages;
            this.queuedBytes = queuedBytes;
        }
    }

    public static final class Limits {
        public final int maxQueuedMessages;
        public final int maxQueuedBytes;
        public final int maxInboundMessageBytes;
        public final int maxOutboundFrameBytes;
        public final long pingIntervalNanos;
        public final long pongTimeoutNanos;
        public final long idleTimeoutNanos;

        public Limits(
                int maxQueuedMessages,
                int maxQueuedBytes,
                int maxMessageBytes,
                long pingIntervalNanos,
                long pongTimeoutNanos,
                long idleTimeoutNanos) {
            this(maxQueuedMessages, maxQueuedBytes, maxMessageBytes, maxMessageBytes,
                    pingIntervalNanos, pongTimeoutNanos, idleTimeoutNanos);
        }

        public Limits(
                int maxQueuedMessages,
                int maxQueuedBytes,
                int maxInboundMessageBytes,
                int maxOutboundFrameBytes,
                long pingIntervalNanos,
                long pongTimeoutNanos,
                long idleTimeoutNanos) {
            if (maxQueuedMessages < 1 || maxQueuedBytes < 1
                    || maxInboundMessageBytes < 1 || maxOutboundFrameBytes < 1) {
                throw new IllegalArgumentException("queue and message limits must be positive");
            }
            if (pingIntervalNanos < 1 || pongTimeoutNanos < 1 || idleTimeoutNanos < 1) {
                throw new IllegalArgumentException("liveness limits must be positive");
            }
            if (maxOutboundFrameBytes > maxQueuedBytes) {
                throw new IllegalArgumentException("one outbound frame must fit inside byte queue");
            }
            this.maxQueuedMessages = maxQueuedMessages;
            this.maxQueuedBytes = maxQueuedBytes;
            this.maxInboundMessageBytes = maxInboundMessageBytes;
            this.maxOutboundFrameBytes = maxOutboundFrameBytes;
            this.pingIntervalNanos = pingIntervalNanos;
            this.pongTimeoutNanos = pongTimeoutNanos;
            this.idleTimeoutNanos = idleTimeoutNanos;
        }
    }

    private static final CloseListener NOOP_CLOSE_LISTENER = new CloseListener() {
        @Override public void onClosed(CloseReason reason) {}
    };
    private static final TelemetrySink NOOP_TELEMETRY = new TelemetrySink() {
        @Override public void onEvent(Telemetry event) {}
    };

    private final FrameWriter writer;
    private final Limits limits;
    private final CloseListener closeListener;
    private final TelemetrySink telemetry;
    private final Rfc6455Codec.MessageAssembler assembler;
    private final ArrayDeque<OutboundFrame> outbound = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cleanupComplete = new AtomicBoolean();
    private final Thread writerThread;
    private int queuedBytes;
    private long lastInboundNanos;
    private long pingSequence;
    private byte[] awaitedPong;
    private long pongDeadlineNanos;

    public BoundedWebSocketSession(
            String threadName,
            FrameWriter writer,
            Limits limits,
            long startedAtNanos,
            CloseListener closeListener,
            TelemetrySink telemetry) {
        if (writer == null || limits == null) {
            throw new IllegalArgumentException("writer and limits are required");
        }
        this.writer = writer;
        this.limits = limits;
        this.closeListener = closeListener == null ? NOOP_CLOSE_LISTENER : closeListener;
        this.telemetry = telemetry == null ? NOOP_TELEMETRY : telemetry;
        this.assembler = new Rfc6455Codec.MessageAssembler(limits.maxInboundMessageBytes);
        this.lastInboundNanos = startedAtNanos;
        this.writerThread = new Thread(new Runnable() {
            @Override public void run() { writeLoop(); }
        }, threadName == null || threadName.trim().isEmpty()
                ? "rusty-broker-websocket-writer"
                : threadName);
        this.writerThread.start();
    }

    public void sendText(String text) throws IOException {
        sendText(text, null);
    }

    public void sendText(String text, Runnable writtenCallback) throws IOException {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        enqueue(Rfc6455Codec.OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8),
                false, false, writtenCallback);
    }

    public boolean sendTextAndAwait(String text, long timeoutMillis)
            throws IOException, InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }
        final java.util.concurrent.CountDownLatch written =
                new java.util.concurrent.CountDownLatch(1);
        sendText(text, new Runnable() {
            @Override public void run() { written.countDown(); }
        });
        return written.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void sendBinary(byte[] payload) throws IOException {
        enqueue(Rfc6455Codec.OPCODE_BINARY, payload, false, false, null);
    }

    public void sendClose(int code, String reason) throws IOException {
        if (reason == null) {
            reason = "";
        }
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        if (reasonBytes.length > 123) {
            throw new IOException("websocket close reason exceeds control-frame bound");
        }
        ByteBuffer payload = ByteBuffer.allocate(reasonBytes.length + 2);
        payload.putShort((short) code);
        payload.put(reasonBytes);
        enqueue(Rfc6455Codec.OPCODE_CLOSE, payload.array(), true, false, null);
    }

    /** Applies one already decoded frame and returns only complete application data events. */
    public Rfc6455Codec.Event receive(Rfc6455Codec.Frame frame, long nowNanos)
            throws IOException {
        if (closed.get()) {
            throw new IOException("websocket session is closed");
        }
        synchronized (this) {
            lastInboundNanos = nowNanos;
        }
        Rfc6455Codec.Event event = assembler.accept(frame);
        if (event.kind == Rfc6455Codec.Event.Kind.PING) {
            enqueue(Rfc6455Codec.OPCODE_PONG, event.payload, false, false, null);
        } else if (event.kind == Rfc6455Codec.Event.Kind.PONG) {
            synchronized (this) {
                if (awaitedPong != null && Arrays.equals(awaitedPong, event.payload)) {
                    awaitedPong = null;
                    pongDeadlineNanos = 0;
                    emitLocked(EventCode.PONG_MATCHED, null);
                }
            }
        } else if (event.kind == Rfc6455Codec.Event.Kind.CLOSE) {
            byte[] response = event.payload.length == 0
                    ? new byte[] {0x03, (byte) 0xE8}
                    : event.payload;
            enqueue(Rfc6455Codec.OPCODE_CLOSE, response, true, true, null);
        }
        return event;
    }

    /** Advances Ping/Pong and idle policy using a caller-owned monotonic clock. */
    public void tick(long nowNanos) throws IOException {
        CloseReason timeout = null;
        byte[] ping = null;
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            if (nowNanos - lastInboundNanos >= limits.idleTimeoutNanos) {
                timeout = CloseReason.IDLE_TIMEOUT;
            } else if (awaitedPong != null && nowNanos >= pongDeadlineNanos) {
                timeout = CloseReason.PONG_TIMEOUT;
            } else if (awaitedPong == null
                    && nowNanos - lastInboundNanos >= limits.pingIntervalNanos) {
                pingSequence += 1;
                ping = ByteBuffer.allocate(8).putLong(pingSequence).array();
                awaitedPong = ping.clone();
                pongDeadlineNanos = saturatedAdd(nowNanos, limits.pongTimeoutNanos);
            }
        }
        if (timeout != null) {
            closeInternal(timeout);
            return;
        }
        if (ping != null) {
            enqueue(Rfc6455Codec.OPCODE_PING, ping, false, false, null);
            synchronized (this) {
                emitLocked(EventCode.PING_QUEUED, null);
            }
        }
    }

    public boolean isClosed() {
        return cleanupComplete.get();
    }

    public synchronized int queuedMessages() {
        return outbound.size();
    }

    public synchronized int queuedBytes() {
        return queuedBytes;
    }

    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }
        long deadline = saturatedAdd(
                System.nanoTime(), java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        synchronized (this) {
            while (!cleanupComplete.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                wait(millis, nanos);
            }
            return true;
        }
    }

    @Override public void close() {
        closeInternal(CloseReason.CANCELLED);
    }

    private void enqueue(
            int opcode,
            byte[] payload,
            boolean closeAfterWrite,
            boolean peerClose,
            Runnable writtenCallback)
            throws IOException {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        if (payload.length > limits.maxOutboundFrameBytes && opcode < 0x8) {
            throw new IOException("websocket message exceeds configured bound");
        }
        boolean saturated = false;
        synchronized (this) {
            if (closed.get()) {
                throw new IOException("websocket session is closed");
            }
            if (outbound.size() >= limits.maxQueuedMessages
                    || payload.length > limits.maxQueuedBytes - queuedBytes) {
                saturated = true;
                emitLocked(EventCode.QUEUE_SATURATED, CloseReason.QUEUE_SATURATED);
            } else {
                outbound.addLast(new OutboundFrame(
                        opcode, payload, closeAfterWrite,
                        peerClose ? CloseReason.PEER_CLOSE : CloseReason.LOCAL_CLOSE,
                        writtenCallback));
                queuedBytes += payload.length;
                emitLocked(EventCode.FRAME_QUEUED, null);
                notifyAll();
            }
        }
        if (saturated) {
            closeInternal(CloseReason.QUEUE_SATURATED);
            throw new IOException("bounded websocket outbound queue saturated");
        }
    }

    private void writeLoop() {
        while (!closed.get()) {
            final OutboundFrame frame;
            synchronized (this) {
                while (!closed.get() && outbound.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed.get()) {
                    return;
                }
                frame = outbound.removeFirst();
                queuedBytes -= frame.payload.length;
            }
            try {
                writer.write(true, frame.opcode, frame.payload);
                synchronized (this) { emitLocked(EventCode.FRAME_WRITTEN, null); }
                if (frame.writtenCallback != null) {
                    try {
                        frame.writtenCallback.run();
                    } catch (RuntimeException ignored) {
                        // Product diagnostics cannot change transport lifecycle.
                    }
                }
                if (frame.closeAfterWrite) {
                    closeInternal(frame.closeReason);
                    return;
                }
            } catch (IOException failure) {
                synchronized (this) {
                    emitLocked(EventCode.WRITE_FAILED, CloseReason.WRITE_FAILED);
                }
                closeInternal(CloseReason.WRITE_FAILED);
                return;
            } catch (RuntimeException failure) {
                synchronized (this) {
                    emitLocked(EventCode.WRITE_FAILED, CloseReason.WRITE_FAILED);
                }
                closeInternal(CloseReason.WRITE_FAILED);
                return;
            }
        }
    }

    private void closeInternal(CloseReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            outbound.clear();
            queuedBytes = 0;
            emitLocked(EventCode.CLOSED, reason);
            notifyAll();
        }
        if (Thread.currentThread() != writerThread) {
            writerThread.interrupt();
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // The close callback and telemetry still converge exactly once.
        }
        try {
            closeListener.onClosed(reason);
        } catch (RuntimeException ignored) {
            // A diagnostic callback cannot prevent transport cleanup completion.
        } finally {
            synchronized (this) {
                cleanupComplete.set(true);
                notifyAll();
            }
        }
    }

    private void emitLocked(EventCode code, CloseReason reason) {
        telemetry.onEvent(new Telemetry(code, reason, outbound.size(), queuedBytes));
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class OutboundFrame {
        final int opcode;
        final byte[] payload;
        final boolean closeAfterWrite;
        final CloseReason closeReason;
        final Runnable writtenCallback;

        OutboundFrame(
                int opcode,
                byte[] payload,
                boolean closeAfterWrite,
                CloseReason closeReason,
                Runnable writtenCallback) {
            this.opcode = opcode;
            this.payload = payload.clone();
            this.closeAfterWrite = closeAfterWrite;
            this.closeReason = closeReason;
            this.writtenCallback = writtenCallback;
        }
    }
}
