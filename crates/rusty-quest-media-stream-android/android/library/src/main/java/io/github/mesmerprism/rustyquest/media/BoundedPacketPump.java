package io.github.mesmerprism.rustyquest.media;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded socket writer. Encoder/render callbacks only enqueue copied packets. */
public final class BoundedPacketPump implements Closeable {
    private static final int MAX_CAPACITY = 256;
    private static final int DEFAULT_MAX_PACKET_BYTES = 8 * 1024 * 1024;
    private static final long DEFAULT_MAX_QUEUED_BYTES = 32L * 1024L * 1024L;

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final int capacity;
    private final int maxPacketBytes;
    private final long maxQueuedBytes;
    private final OutputStream output;
    private final Thread worker;
    private final AtomicBoolean outputClosed = new AtomicBoolean();
    private volatile boolean closed;
    private volatile IOException failure;
    private long queuedBytes;

    public BoundedPacketPump(OutputStream output, int capacity, String threadName) {
        this(output, capacity, DEFAULT_MAX_PACKET_BYTES, DEFAULT_MAX_QUEUED_BYTES, threadName);
    }

    BoundedPacketPump(OutputStream output, int capacity, int maxPacketBytes,
            long maxQueuedBytes, String threadName) {
        if (output == null) throw new NullPointerException("output");
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity");
        }
        if (maxPacketBytes <= 0 || maxQueuedBytes < maxPacketBytes) {
            throw new IllegalArgumentException("packet byte limits");
        }
        this.output = output;
        this.capacity = capacity;
        this.maxPacketBytes = maxPacketBytes;
        this.maxQueuedBytes = maxQueuedBytes;
        worker = new Thread(new Runnable() {
            @Override public void run() { BoundedPacketPump.this.run(); }
        }, threadName == null ? "rusty-media-packet-pump" : threadName);
        worker.setDaemon(true);
        worker.start();
    }
    public boolean offer(byte[] packet) {
        if (packet == null || packet.length == 0) throw new IllegalArgumentException("packet");
        if (packet.length > maxPacketBytes) return false;
        synchronized (lock) {
            if (closed || failure != null || queue.size() >= capacity
                    || queuedBytes + packet.length > maxQueuedBytes) return false;
            queue.addLast(packet.clone());
            queuedBytes += packet.length;
            lock.notifyAll();
            return true;
        }
    }
    private void run() {
        try {
            while (true) {
                byte[] packet;
                synchronized (lock) {
                    while (queue.isEmpty() && !closed) lock.wait();
                    if (closed) return;
                    packet = queue.removeFirst();
                    queuedBytes -= packet.length;
                }
                output.write(packet); output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            synchronized (lock) {
                if (!closed) failure = error;
                closed = true;
                queue.clear();
                queuedBytes = 0L;
                lock.notifyAll();
            }
        } finally {
            closeOutput();
        }
    }
    public IOException failure() { return failure; }
    public boolean isTerminated() { return !worker.isAlive(); }
    @Override public void close() {
        synchronized (lock) {
            closed = true;
            queue.clear();
            queuedBytes = 0L;
            lock.notifyAll();
        }
        closeOutput();
        if (Thread.currentThread() != worker) {
            try { worker.join(2000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (worker.isAlive()) recordFailure(new IOException("packet pump worker did not terminate"));
        }
    }

    private void closeOutput() {
        if (!outputClosed.compareAndSet(false, true)) return;
        try {
            output.close();
        } catch (IOException error) {
            recordFailure(error);
        }
    }

    private void recordFailure(IOException error) {
        synchronized (lock) {
            if (failure == null) failure = error;
        }
    }
}
