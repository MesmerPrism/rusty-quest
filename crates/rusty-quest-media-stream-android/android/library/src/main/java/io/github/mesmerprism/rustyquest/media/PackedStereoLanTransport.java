package io.github.mesmerprism.rustyquest.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One accepted source-local byte stream forwarded to the directional LAN sink. */
final class PackedStereoLanTransport {
    private final String sourceHost, bindHost, sinkHost;
    private final int sourcePort, sinkPort;
    private final Object lock = new Object();
    private volatile Socket source, sink;
    private volatile Thread worker, deadlineWorker;
    private volatile boolean stopped, failed;
    private volatile long writeDeadlineNs, bytes;
    private Socket writeSocket;
    private volatile String stage = "NEW", firstStage = "NONE", firstCause = "NONE";
    private volatile String finalStage = "NONE", finalCause = "NONE";
    private volatile int reconnects;
    private final CountDownLatch connected = new CountDownLatch(1);

    PackedStereoLanTransport(String sourceHost, int sourcePort, String bindHost,
            String sinkHost, int sinkPort) {
        if (sourceHost == null || bindHost == null || sinkHost == null
                || sourceHost.isEmpty() || bindHost.isEmpty() || sinkHost.isEmpty()
                || sourcePort <= 0 || sourcePort > 65535 || sinkPort <= 0 || sinkPort > 65535) {
            throw new IllegalArgumentException("accepted LAN transport binding");
        }
        this.sourceHost = sourceHost; this.sourcePort = sourcePort;
        this.bindHost = bindHost; this.sinkHost = sinkHost; this.sinkPort = sinkPort;
    }

    void start() {
        synchronized (lock) {
            if (worker != null || stopped) throw new IllegalStateException("LAN transport not startable");
            worker = new Thread(new Runnable() { @Override public void run() { forward(); } },
                    "rusty-packed-lan-forward");
            deadlineWorker = new Thread(new Runnable() { @Override public void run() { watchWrites(); } },
                    "rusty-packed-lan-deadline");
            worker.setDaemon(true); deadlineWorker.setDaemon(true);
            deadlineWorker.start(); worker.start();
        }
        try {
            if (!connected.await(7000L, TimeUnit.MILLISECONDS) || failed || stopped) {
                throw new IllegalStateException("accepted LAN socket did not become connected");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("accepted LAN socket start interrupted", interrupted);
        }
    }

    private Socket connect(boolean localSource) throws IOException {
        Socket next = new Socket();
        synchronized (lock) {
            if (stopped) { next.close(); throw new IOException("LAN transport stopping"); }
            if ((localSource ? source : sink) != null) {
                next.close();
                throw new IOException("prior LAN socket cleanup unresolved");
            }
            if (localSource) source = next; else sink = next;
        }
        // Publish before either bind or connect so Stop can cancel the attempt.
        if (!localSource) next.bind(new InetSocketAddress(InetAddress.getByName(bindHost), 0));
        next.connect(new InetSocketAddress(localSource ? sourceHost : sinkHost,
                localSource ? sourcePort : sinkPort), 3000);
        next.setTcpNoDelay(true); next.setSoTimeout(4000);
        return next;
    }

    private void forward() {
        byte[] buffer = new byte[64 * 1024];
        try {
            for (int attempt = 0; !stopped && attempt <= 8; attempt++) {
                if (attempt > 0) { reconnects++; Thread.sleep(250L); }
                try {
                    stage = "SINK_CONNECT";
                    Socket remote = connect(false);
                    stage = "SOURCE_CONNECT";
                    Socket local = connect(true);
                    connected.countDown();
                    InputStream input = local.getInputStream();
                    OutputStream output = remote.getOutputStream();
                    // No intermediate queue and no pixel/packet rewriting. A new local
                    // connection reuses the producer's header/config/keyframe gate.
                    while (!stopped) {
                        stage = "SOURCE_READ";
                        int count = input.read(buffer);
                        if (count < 0) throw new java.io.EOFException("local producer ended");
                        if (count == 0) continue;
                        stage = "SINK_WRITE";
                        synchronized (lock) {
                            if (stopped || sink != remote) throw new IOException("LAN writer retired");
                            writeSocket = remote;
                            writeDeadlineNs = System.nanoTime() + 4_000_000_000L;
                        }
                        output.write(buffer, 0, count);
                        output.flush();
                        synchronized (lock) {
                            writeDeadlineNs = 0L;
                            writeSocket = null;
                        }
                        bytes += count;
                    }
                } catch (IOException failure) {
                    if (!stopped) record(failure);
                } finally {
                    synchronized (lock) { writeDeadlineNs = 0L; writeSocket = null; }
                    closeConnections();
                }
                if (!stopped && attempt == 8) failed = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!stopped) { failed = true; record(new IOException("forwarder interrupted")); }
        } catch (RuntimeException failure) {
            failed = true;
            record(new IOException("LAN worker state failure", failure));
        } finally {
            connected.countDown();
            closeConnections();
            Thread watcher = deadlineWorker;
            if (watcher != null) watcher.interrupt();
        }
    }

    private void watchWrites() {
        try {
            while (!stopped) {
                synchronized (lock) {
                    // Write publication, clearance and this exact-socket deadline
                    // decision share a lock; an old deadline cannot close a successor.
                    if (writeDeadlineNs != 0L && System.nanoTime() >= writeDeadlineNs
                            && writeSocket != null && sink == writeSocket) {
                        record(new SocketTimeoutException("LAN writer deadline"));
                        sink = close(sink);
                        writeDeadlineNs = 0L;
                        writeSocket = null;
                        if (sink != null) { failed = true; stopped = true; connected.countDown(); }
                    }
                }
                Thread.sleep(50L);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void record(IOException failure) {
        String cause = failure instanceof java.net.ConnectException ? "REFUSED"
                : failure instanceof SocketTimeoutException ? "TIMEOUT"
                : failure instanceof java.io.EOFException ? "EOF" : "IO";
        synchronized (lock) {
            if ("NONE".equals(firstStage)) { firstStage = stage; firstCause = cause; }
            finalStage = stage; finalCause = cause;
        }
        android.util.Log.i("RQSpatialCameraPanel", "channel=packed-lan-transport stage=" + stage
                + " firstStage=" + firstStage + " firstCause=" + firstCause
                + " finalStage=" + finalStage + " finalCause=" + finalCause
                + " reconnects=" + reconnects + " bytes=" + bytes + " endpointRedacted=true");
    }

    private void closeConnections() {
        synchronized (lock) {
            source = close(source); sink = close(sink);
            if (source != null || sink != null) {
                failed = true;
                stopped = true;
                connected.countDown();
            }
        }
    }

    private Socket close(Socket current) {
        if (current == null) return null;
        try { current.close(); } catch (IOException failure) { failed = true; }
        return current.isClosed() ? null : current;
    }

    void stop() {
        stopped = true;
        connected.countDown();
        closeConnections();
        Thread active = worker, watcher = deadlineWorker;
        if (active != null) active.interrupt();
        if (watcher != null) watcher.interrupt();
        join(active); join(watcher);
        closeConnections();
    }

    private void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(2000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    boolean terminal() {
        return source == null && sink == null && (worker == null || !worker.isAlive())
                && (deadlineWorker == null || !deadlineWorker.isAlive());
    }
    boolean failed() { return failed; }
    String diagnostic() {
        return "stage=" + stage + ",firstStage=" + firstStage + ",firstCause=" + firstCause
                + ",finalStage=" + finalStage + ",finalCause=" + finalCause
                + ",reconnects=" + reconnects + ",bytes=" + bytes;
    }
}
