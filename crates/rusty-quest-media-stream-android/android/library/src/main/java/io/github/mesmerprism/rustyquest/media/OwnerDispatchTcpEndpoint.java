package io.github.mesmerprism.rustyquest.media;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded framing only. The injected native handler authenticates every action. */
public final class OwnerDispatchTcpEndpoint implements AutoCloseable {
    public interface Handler { byte[] handle(byte[] requestFrame) throws Exception; }
    public static final int MAX_FRAME_BYTES = 128 * 1024;
    private static final int IO_TIMEOUT_MS = 30_000;
    private static final int SHUTDOWN_TIMEOUT_MS = 10_000;
    private final ServerSocket listener;
    private final Thread acceptThread;
    private final ThreadPoolExecutor workers;
    private final Handler handler;
    private final ScheduledThreadPoolExecutor deadlines = deadlineExecutor();
    private final ConcurrentHashMap<Socket, ScheduledFuture<?>> socketDeadlines = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean stopping;
    private volatile String failure = "";

    public OwnerDispatchTcpEndpoint(InetAddress localAddress, int port, Handler handler) throws IOException {
        if (localAddress == null || port < 0 || port > 65535 || handler == null) {
            throw new IllegalArgumentException("owner control endpoint binding");
        }
        this.handler = handler;
        workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(8), runnable -> {
                    Thread thread = new Thread(runnable, "rusty-owner-control-effect");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(localAddress, port), 8);
        } catch (IOException failed) {
            listener.close();
            workers.shutdownNow();
            deadlines.shutdownNow();
            throw failed;
        }
        acceptThread = new Thread(this::accept, "rusty-owner-control-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() { return listener.getLocalPort(); }
    public boolean ready() { return !stopping && !listener.isClosed() && acceptThread.isAlive() && failure.isEmpty(); }
    public boolean terminal() { return stopping && listener.isClosed() && !acceptThread.isAlive()
            && workers.isTerminated() && deadlines.isTerminated() && sockets.isEmpty(); }

    private void accept() {
        try {
            while (!stopping) {
                Socket socket = listener.accept();
                if (stopping) { socket.close(); break; }
                sockets.add(socket);
                try {
                    socketDeadlines.put(socket, deadlines.schedule(() -> closeSocket(socket),
                            IO_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    workers.execute(() -> serve(socket));
                }
                catch (RejectedExecutionException full) { closeSocket(socket); }
            }
        } catch (SocketException closed) {
            if (!stopping) failure = "accept-socket-failed";
        } catch (IOException failed) {
            if (!stopping) failure = "accept-io-failed";
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(IO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            byte[] request = readFrame(socket);
            if (stopping) return;
            // No endpoint monitor is held across native authority or platform work.
            byte[] response = handler.handle(request);
            writeFrame(new DataOutputStream(socket.getOutputStream()), response);
        } catch (Exception failed) {
            // A lost reply is uncertainty. Native replay state retains the obligation;
            // transport must never synthesize a successful owner receipt.
        } finally { closeSocket(socket); }
    }

    /** The caller must obtain the exact address from current accepted route authority. */
    public static byte[] exchange(InetAddress targetAddress, int targetPort, byte[] frame) throws IOException {
        if (targetAddress == null || targetPort <= 0 || targetPort > 65535) {
            throw new IllegalArgumentException("owner control target");
        }
        requireFrame(frame);
        ScheduledThreadPoolExecutor timeout = deadlineExecutor();
        try (Socket socket = new Socket()) {
            timeout.schedule(() -> {
                closeTimedSocket(socket);
            }, IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            socket.connect(new InetSocketAddress(targetAddress, targetPort), 3_000);
            socket.setSoTimeout(IO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            writeFrame(new DataOutputStream(socket.getOutputStream()), frame);
            return readFrame(socket);
        } finally { timeout.shutdownNow(); }
    }

    private static byte[] readFrame(Socket socket) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MS);
        byte[] header = new byte[4];
        readExactly(socket, header, deadline);
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("owner frame bound");
        byte[] frame = new byte[length];
        readExactly(socket, frame, deadline);
        return frame;
    }

    private static void readExactly(Socket socket, byte[] target, long deadline) throws IOException {
        InputStream input = socket.getInputStream();
        int offset = 0;
        while (offset < target.length) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) throw new SocketTimeoutException("owner frame deadline");
            socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) throw new EOFException("owner frame truncated");
            offset += count;
        }
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        requireFrame(frame);
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    private static void requireFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length == 0 || frame.length > MAX_FRAME_BYTES) {
            throw new IOException("owner frame bound");
        }
    }

    private void closeSocket(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
        sockets.remove(socket);
        ScheduledFuture<?> deadline = socketDeadlines.remove(socket);
        if (deadline != null) deadline.cancel(false);
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rusty-owner-control-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void closeTimedSocket(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
    }

    /** Full product cleanup must precede endpoint retirement. */
    @Override public void close() {
        stopping = true;
        try { listener.close(); }
        catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        workers.shutdownNow();
        deadlines.shutdownNow();
        try {
            acceptThread.join(SHUTDOWN_TIMEOUT_MS);
            workers.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            deadlines.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!terminal()) throw new IllegalStateException("owner control cleanup remains pending");
    }
}
