package io.github.mesmerprism.rustyquest.media;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Bounded socket writer. Encoder/render callbacks only enqueue copied packets. */
public final class BoundedPacketPump implements Closeable {
    private static final int MAX_CAPACITY = 256;
    private static final int DEFAULT_MAX_PACKET_BYTES = 8 * 1024 * 1024;
    private static final long DEFAULT_MAX_QUEUED_BYTES = 32L * 1024L * 1024L;

    private final Object lock = new Object();
    private static final class Packet {
        final byte[] bytes;
        final long generation, pair, ptsNs;
        Packet(byte[] bytes, long generation, long pair, long ptsNs) {
            this.bytes = bytes; this.generation = generation; this.pair = pair; this.ptsNs = ptsNs;
        }
    }
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private final int capacity;
    private final int maxPacketBytes;
    private final long maxQueuedBytes;
    private final OutputStream output;
    private final Thread worker;
    private final AtomicBoolean outputClosed = new AtomicBoolean();
    private volatile boolean closed;
    private volatile IOException failure;
    private long queuedBytes;
    private final PipelineWaitObservation waits;
    PipelineWaitObservation waitObservation() { return waits; }
    JSONObject waitSnapshot() { return waits.snapshot(); }

    public BoundedPacketPump(OutputStream output, int capacity, String threadName) {
        this(output, capacity, DEFAULT_MAX_PACKET_BYTES, DEFAULT_MAX_QUEUED_BYTES, threadName);
    }
    BoundedPacketPump(OutputStream output, int capacity, String threadName, PipelineWaitObservation.Clock clock) {
        this(output, capacity, DEFAULT_MAX_PACKET_BYTES, DEFAULT_MAX_QUEUED_BYTES, threadName, clock);
    }

    BoundedPacketPump(OutputStream output, int capacity, int maxPacketBytes,
            long maxQueuedBytes, String threadName) {
        this(output, capacity, maxPacketBytes, maxQueuedBytes, threadName, null);
    }
    private BoundedPacketPump(OutputStream output, int capacity, int maxPacketBytes,
            long maxQueuedBytes, String threadName, PipelineWaitObservation.Clock clock) {
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
        waits = clock == null ? new PipelineWaitObservation("packet_writer", "packet_write", "packet_flush")
                : new PipelineWaitObservation("packet_writer", clock, "packet_write", "packet_flush");
        worker = new Thread(new Runnable() {
            @Override public void run() { BoundedPacketPump.this.run(); }
        }, threadName == null ? "rusty-media-packet-pump" : threadName);
        worker.setDaemon(true);
        worker.start();
    }
    public boolean offer(byte[] packet) {
        return offer(packet, 0, 0, 0);
    }
    boolean offer(byte[] packet, long generation, long pair, long ptsNs) {
        if (packet == null || packet.length == 0) throw new IllegalArgumentException("packet");
        if (packet.length > maxPacketBytes) return false;
        synchronized (lock) {
            if (closed || failure != null || queue.size() >= capacity
                    || queuedBytes + packet.length > maxQueuedBytes) return false;
            queue.addLast(new Packet(packet.clone(), generation, pair, ptsNs));
            queuedBytes += packet.length;
            lock.notifyAll();
            return true;
        }
    }
    private void run() {
        try {
            while (true) {
                Packet packet;
                synchronized (lock) {
                    while (queue.isEmpty() && !closed) lock.wait();
                    if (closed) return;
                    packet = queue.removeFirst();
                    queuedBytes -= packet.bytes.length;
                }
                long operation = waits.begin(0, packet.generation, packet.pair, packet.ptsNs, PipelineWaitObservation.WORK);
                boolean success = false;
                try { output.write(packet.bytes); success = true; }
                finally { waits.end(0, operation, success); }
                operation = waits.begin(1, packet.generation, packet.pair, packet.ptsNs, PipelineWaitObservation.WORK);
                success = false;
                try { output.flush(); success = true; }
                finally { waits.end(1, operation, success); }
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
        close(2000L, () -> System.err.println("WARNING: same packet pump worker remains Pending"));
    }
    // Package-local causal host seam; production always retains this same worker.
    void close(long estimateMilliseconds, Runnable warning) {
        if (estimateMilliseconds < 0 || warning == null) throw new IllegalArgumentException("close estimate/publisher");
        synchronized (lock) {
            closed = true;
            queue.clear();
            queuedBytes = 0L;
            lock.notifyAll();
        }
        closeOutput();
        if (Thread.currentThread() != worker) {
            boolean interrupted = false;
            try {
                if (estimateMilliseconds > 0) try { worker.join(estimateMilliseconds); }
                catch (InterruptedException actual) { interrupted = true; }
                if (worker.isAlive()) {
                    try { warning.run(); } catch (Throwable publication) { }
                    while (worker.isAlive()) try { worker.join(); }
                    catch (InterruptedException actual) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
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
