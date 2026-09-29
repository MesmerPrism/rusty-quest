package io.github.mesmerprism.rustyquest.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Reusable RMANVID v4 packed-stereo receiver rendering into a host-owned Surface. */
public final class PackedStereoMediaReceiver implements AutoCloseable {
    private static final String MIME_H264 = "video/avc";
    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1L);

    private final Object lock = new Object();
    private final Surface surface;
    private final String host;
    private final int port;
    private final String expectedSourceHost;
    private volatile ServerSocket incomingListener;
    private final long generation;
    private final Bounds bounds;
    private final FrameListener listener;
    private final FrameLifecycleListener lifecycleListener;
    private final String handleId;
    private final AtomicLong revision = new AtomicLong();
    private final Map<Long, PendingFrame> pendingFrames = new LinkedHashMap<>();
    private volatile String state = "new";
    private volatile String connectionState = "idle";
    private volatile String failure = "";
    private volatile boolean stopRequested;
    private volatile boolean liveRequested;
    private volatile Thread worker;
    private volatile Socket socket;
    private volatile MediaCodec decoder;
    private volatile HandlerThread renderThread;
    private volatile CountDownLatch ready;
    private long renderedFrames;
    private long receivedPackets;
    private int reconnects;
    private volatile String transportStage = "IDLE";
    private String firstTransportStage = "NONE", firstTransportCause = "NONE";
    private String finalTransportStage = "NONE", finalTransportCause = "NONE";
    private long connectionGeneration;
    private long retiredConnectionGeneration;

    public PackedStereoMediaReceiver(Surface surface, String host, int port, long generation,
            Bounds bounds, FrameListener listener) {
        this(surface, host, port, generation, bounds, listener, null);
    }

    /** Constructor for hosts that bind exact native identity before Surface rendering. */
    public PackedStereoMediaReceiver(Surface surface, String host, int port, long generation,
            Bounds bounds, FrameListener listener, FrameLifecycleListener lifecycleListener) {
        this(surface, host, port, generation, bounds, listener, lifecycleListener, null);
    }

    /** Explicit accepted source-to-sink placement; null retains source-connect mode. */
    public PackedStereoMediaReceiver(Surface surface, String host, int port, long generation,
            Bounds bounds, FrameListener listener, FrameLifecycleListener lifecycleListener,
            String expectedSourceHost) {
        if (surface == null || !surface.isValid()) throw new IllegalArgumentException("surface");
        if (host == null || host.trim().isEmpty() || host.length() > 1024
                || port <= 0 || port > 65535 || generation <= 0L || bounds == null) {
            throw new IllegalArgumentException("receiver binding");
        }
        this.surface = surface;
        this.host = host.trim();
        this.port = port;
        this.expectedSourceHost = expectedSourceHost;
        this.generation = generation;
        this.bounds = bounds;
        this.listener = listener;
        this.lifecycleListener = lifecycleListener;
        this.handleId = "packed-receiver-" + NEXT_HANDLE.getAndIncrement() + ":g" + generation;
    }

    /** Arms a bounded connection worker without waiting for the remote sender. */
    public void arm() {
        synchronized (lock) {
            if (!"new".equals(state)) throw new IllegalStateException("receiver is not startable");
            if (expectedSourceHost != null) {
                ServerSocket next = null;
                try {
                    next = new ServerSocket();
                    incomingListener = next;
                    next.setReuseAddress(true);
                    next.bind(new InetSocketAddress(InetAddress.getByName(host), port));
                    next.setSoTimeout(1000);
                } catch (IOException failure) {
                    transportStage = "LISTENER_BIND";
                    recordTransportFailure(failure);
                    closeListener();
                    throw new IllegalStateException("accepted sink listener bind failed", failure);
                }
            }
            stopRequested = false;
            state = "receiver_armed";
            connectionState = expectedSourceHost == null ? "connecting" : "listening";
            revision.incrementAndGet();
            ready = new CountDownLatch(1);
            renderThread = new HandlerThread("rusty-packed-stereo-render-callback");
            renderThread.start();
            worker = new Thread(new Runnable() {
                @Override public void run() { receiveLoop(); }
            }, "rusty-packed-stereo-receiver");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** Arms if needed, then waits for config, a keyframe, and one rendered exact frame. */
    public void start() throws Exception {
        requireNotMainThread("start");
        CountDownLatch startReady;
        synchronized (lock) {
            if ("new".equals(state)) arm();
            if ("receiving".equals(state)) return;
            if (!("receiver_armed".equals(state))) {
                throw new IllegalStateException("receiver is not startable");
            }
            liveRequested = true;
            if ("receiving".equals(connectionState)) {
                state = "receiving";
                revision.incrementAndGet();
                return;
            }
            startReady = ready;
        }
        if (!startReady.await(bounds.startupTimeoutMs, TimeUnit.MILLISECONDS)
                || !"receiving".equals(connectionState)) {
            String reason = failure.isEmpty() ? "receiver startup timed out" : failure;
            stopAndVerify();
            throw new IOException(reason);
        }
        if (!"receiving".equals(state)) {
            throw new IOException(failure.isEmpty() ? "receiver did not enter live state" : failure);
        }
    }

    /** Exact current lifecycle state suitable for trusted provider verification. */
    public MediaRuntimeSnapshot snapshot() {
        String current;
        String detail;
        boolean terminal;
        synchronized (lock) {
            current = state;
            detail = "packets=" + receivedPackets + ",frames=" + renderedFrames
                    + ",reconnects=" + reconnects + ",connection=" + connectionState
                    + (failure.isEmpty() ? "" : ",failure=" + failure);
            terminal = ("stopped".equals(current) || "failed".equals(current))
                    && resourcesReleasedLocked();
        }
        return new MediaRuntimeSnapshot(generation, revision.get(), current,
                terminal, detail, handleId);
    }

    private boolean resourcesReleasedLocked() {
        return incomingListener == null && socket == null && decoder == null && renderThread == null
                && pendingFrames.isEmpty() && worker == null;
    }

    /** A sink owner whose readback is derived from this receiver's actual platform resources. */
    public MediaOwnerProvider provider() {
        return new ReceiverProvider();
    }

    public void stopAndVerify() {
        requireNotMainThread("stopAndVerify");
        Thread active;
        synchronized (lock) {
            stopRequested = true;
            if (!"stopped".equals(state) && !"failed".equals(state)) {
                state = "stopping";
                revision.incrementAndGet();
            }
            active = worker;
        }
        closeSocket();
        closeListener();
        if (active != null && active != Thread.currentThread()) {
            active.interrupt();
            try {
                active.join(bounds.shutdownTimeoutMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        // A prior worker may have ended with retained decoder/socket ownership.
        // Retry physical release only after its death is positively observed.
        if (active == null || !active.isAlive()) {
            releaseConnection();
            closeListener();
        }
        synchronized (lock) {
            HandlerThread callbacks = renderThread;
            if (callbacks != null) {
                callbacks.quitSafely();
                try { callbacks.join(bounds.shutdownTimeoutMs); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (!callbacks.isAlive()) renderThread = null;
            }
            if ((active != null && active.isAlive()) || incomingListener != null || socket != null || decoder != null
                    || renderThread != null || !pendingFrames.isEmpty() || worker != null) {
                state = "failed";
                failure = "receiver resources did not terminate within deadline";
                revision.incrementAndGet();
                throw new IllegalStateException(failure);
            }
            state = "stopped";
            connectionState = "stopped";
            revision.incrementAndGet();
        }
    }

    @Override public void close() { stopAndVerify(); }

    private void receiveLoop() {
        Throwable terminalFailure = null;
        try {
            for (int attempt = 0; !stopRequested; attempt++) {
                synchronized (lock) {
                    if (socket != null || decoder != null || !pendingFrames.isEmpty()) {
                        throw new IOException("prior receiver connection cleanup unresolved");
                    }
                }
                if (attempt > bounds.maxReconnectAttempts) {
                    throw new IOException("receiver reconnect bound exhausted");
                }
                if (attempt > 0) {
                    synchronized (lock) {
                        reconnects++;
                        connectionState = "reconnecting";
                        if ("receiving".equals(state)) {
                            state = "receiver_armed";
                            revision.incrementAndGet();
                        }
                    }
                    SystemClock.sleep(bounds.reconnectDelayMs);
                }
                try {
                    receiveConnection();
                } catch (IOException connectionFailure) {
                    if (stopRequested) break;
                    recordTransportFailure(connectionFailure);
                    terminalFailure = connectionFailure;
                } finally {
                    releaseConnection();
                }
            }
        } catch (Throwable error) {
            if (!(error instanceof IOException) || firstTransportStage.equals("NONE")) recordTransportFailure(error);
            logTransportFailure("attempts-finished");
            terminalFailure = error;
        } finally {
            releaseConnection();
            closeListener();
            releaseRenderThread();
            synchronized (lock) {
                worker = null;
                if (terminalFailure != null && !stopRequested) {
                    failure = terminalFailure.getClass().getSimpleName() + ": "
                            + safeMessage(terminalFailure);
                    state = "failed";
                } else if (!"failed".equals(state)) {
                    state = "stopped";
                }
                revision.incrementAndGet();
                CountDownLatch startReady = ready;
                if (startReady != null) startReady.countDown();
            }
        }
    }

    private void recordTransportFailure(Throwable failure) {
        String cause = "android.media.MediaCodec$CodecException".equals(failure.getClass().getName()) ? "CODEC"
                : failure instanceof java.net.ConnectException ? "REFUSED"
                : failure instanceof java.net.SocketTimeoutException
                ? ("CONNECT".equals(transportStage) ? "CONNECT_TIMEOUT" : "READ_TIMEOUT")
                : failure instanceof java.io.EOFException ? "EOF"
                : failure instanceof IOException ? ("HEADER".equals(transportStage) ? "HEADER_IO" : "IO")
                : failure instanceof IllegalStateException ? "STATE" : "OTHER";
        synchronized (lock) {
            if ("NONE".equals(firstTransportStage)) {
                firstTransportStage = transportStage; firstTransportCause = cause;
            }
            finalTransportStage = transportStage; finalTransportCause = cause;
        }
        logTransportFailure("connection-rejected");
    }

    private void logTransportFailure(String status) {
        synchronized (lock) {
            android.util.Log.i("RQSpatialCameraPanel", "channel=packed-receiver status=" + status
                    + " firstStage=" + firstTransportStage + " firstCause=" + firstTransportCause
                    + " finalStage=" + finalTransportStage + " finalCause=" + finalTransportCause
                    + " reconnects=" + reconnects + " packets=" + receivedPackets
                    + " frames=" + renderedFrames + " maxWidth=" + bounds.maxWidth + " maxHeight=" + bounds.maxHeight
                    + " maxPacketBytes=" + bounds.maxPacketBytes + " code=TRANSPORT_EFFECT_UNCERTAIN");
        }
    }

    private void receiveConnection() throws Exception {
        Socket connection;
        if (expectedSourceHost == null) {
            connection = new Socket();
        } else {
            transportStage = "ACCEPT";
            connection = null;
            while (!stopRequested && connection == null) {
                ServerSocket accepting = incomingListener;
                if (accepting == null) throw new IOException("accepted sink listener absent");
                try { connection = accepting.accept(); }
                catch (java.net.SocketTimeoutException idle) { continue; }
                synchronized (lock) { socket = connection; }
                // An unrelated LAN client cannot become this directional source.
                if (!connection.getInetAddress().equals(InetAddress.getByName(expectedSourceHost))) {
                    closeSocket();
                    if (socket != null) throw new IOException("rejected source socket cleanup unresolved");
                    connection = null;
                }
            }
            if (connection == null) return;
        }
        synchronized (lock) {
            socket = connection;
            if (stopRequested) {
                closeSocket();
                if (socket != null) throw new IOException("retired accepted socket cleanup unresolved");
                return;
            }
        }
        if (expectedSourceHost == null) {
            transportStage = "CONNECT";
            connection.connect(new InetSocketAddress(host, port), bounds.connectTimeoutMs);
        }
        connection.setSoTimeout(bounds.readTimeoutMs);
        connection.setTcpNoDelay(true);
        RmanvidPacketReader reader = new RmanvidPacketReader(
                new DataInputStream(new BufferedInputStream(connection.getInputStream())),
                bounds.maxHeaderBytes, bounds.maxPacketBytes, bounds.maxWidth, bounds.maxHeight);
        transportStage = "HEADER";
        RmanvidPacketReader.Header header = reader.readHeader();
        transportStage = "DECODER_CONFIG";
        MediaFormat format = MediaFormat.createVideoFormat(MIME_H264, header.width, header.height);
        MediaCodec codec = MediaCodec.createDecoderByType(MIME_H264);
        decoder = codec;
        codec.configure(format, surface, null, 0);
        HandlerThread callbacks = renderThread;
        if (callbacks == null || !callbacks.isAlive()) {
            throw new IOException("render callback thread is unavailable");
        }
        final long activeConnection;
        synchronized (lock) { activeConnection = ++connectionGeneration; }
        codec.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener() {
            @Override public void onFrameRendered(MediaCodec callbackCodec, long mediaTimeUs,
                    long systemNano) {
                rendered(callbackCodec, activeConnection, mediaTimeUs);
            }
        }, new Handler(callbacks.getLooper()));
        codec.start();
        connectionState = "decoder_configured";

        boolean configurationSeen = false;
        boolean keyframeSeen = false;
        while (!stopRequested) {
            transportStage = "PACKET_READ";
            RmanvidPacketReader.Packet packet = reader.readPacket();
            transportStage = "DECODE";
            boolean config = (packet.flags & RmanvidPacketReader.FLAG_CODEC_CONFIG) != 0;
            boolean keyframe = (packet.flags & RmanvidPacketReader.FLAG_KEY_FRAME) != 0;
            if (!configurationSeen && !config) continue;
            if (config) {
                configurationSeen = true;
                keyframeSeen = false;
            } else if (!keyframeSeen && !keyframe) {
                continue;
            } else if (keyframe) {
                keyframeSeen = true;
            }
            queuePacket(codec, activeConnection, packet);
            drain(codec, activeConnection);
        }
    }

    private void queuePacket(MediaCodec codec, long activeConnection,
            RmanvidPacketReader.Packet packet) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + bounds.codecInputTimeoutMs;
        int inputIndex;
        do {
            inputIndex = codec.dequeueInputBuffer(10_000L);
            if (inputIndex < 0) drain(codec, activeConnection);
        } while (inputIndex < 0 && !stopRequested && SystemClock.elapsedRealtime() < deadline);
        if (inputIndex < 0) throw new IOException("decoder input buffer timed out");
        ByteBuffer input = codec.getInputBuffer(inputIndex);
        if (input == null || packet.payload.length > input.capacity()) {
            throw new IOException("packet exceeds decoder input capacity");
        }
        input.clear();
        input.put(packet.payload);
        boolean config = (packet.flags & RmanvidPacketReader.FLAG_CODEC_CONFIG) != 0;
        if (!config) {
            synchronized (lock) {
                if (pendingFrames.size() >= bounds.maxPendingFrames
                        || pendingFrames.containsKey(packet.ptsUs)) {
                    throw new IOException("decoder frame identity queue collision or overflow");
                }
                pendingFrames.put(packet.ptsUs,
                        new PendingFrame(activeConnection, FrameIdentity.from(packet)));
            }
        }
        codec.queueInputBuffer(inputIndex, 0, packet.payload.length, packet.ptsUs, packet.flags);
        synchronized (lock) { receivedPackets++; }
    }

    private void drain(MediaCodec codec, long activeConnection) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopRequested) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0L);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (outputIndex < 0) return;
            boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean emptyEos = info.size == 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (codecConfig || emptyEos) {
                codec.releaseOutputBuffer(outputIndex, false);
                continue;
            }
            PendingFrame pending;
            synchronized (lock) {
                pending = pendingFrames.get(info.presentationTimeUs);
                if (pending == null || pending.connectionGeneration != activeConnection) {
                    throw new IOException("decoder output has no exact current frame identity");
                }
            }
            final long presentationTimeNs;
            try {
                presentationTimeNs = Math.multiplyExact(info.presentationTimeUs, 1_000L);
            } catch (ArithmeticException overflow) {
                synchronized (lock) { pendingFrames.remove(info.presentationTimeUs); }
                codec.releaseOutputBuffer(outputIndex, false);
                throw new IOException("decoder output timestamp overflows nanoseconds", overflow);
            }
            boolean accepted = true;
            if (lifecycleListener != null) {
                try {
                    accepted = lifecycleListener.onFrameReadyForRender(
                            generation, pending.connectionGeneration, presentationTimeNs,
                            pending.identity);
                } catch (RuntimeException callbackFailure) {
                    accepted = false;
                    failure = "pre-render callback failed: " + safeMessage(callbackFailure);
                }
            }
            synchronized (lock) {
                PendingFrame current = pendingFrames.get(info.presentationTimeUs);
                accepted = accepted && current == pending && decoder == codec && !stopRequested;
                if (accepted) {
                    pending.presentationTimeNs = presentationTimeNs;
                    pending.readyForRender = true;
                } else {
                    pendingFrames.remove(info.presentationTimeUs);
                }
            }
            codec.releaseOutputBuffer(outputIndex, accepted);
            if (!accepted) throw new IOException("decoder output pre-render identity was rejected");
        }
    }

    private void rendered(MediaCodec callbackCodec, long callbackConnection, long ptsUs) {
        FrameIdentity identity;
        long presentationTimeNs;
        synchronized (lock) {
            if (decoder != callbackCodec || stopRequested || "stopping".equals(state)
                    || "stopped".equals(state) || "failed".equals(state)) return;
            PendingFrame pending = pendingFrames.get(ptsUs);
            if (pending == null || pending.connectionGeneration != callbackConnection
                    || !pending.readyForRender || pending.presentationTimeNs <= 0L) return;
            pendingFrames.remove(ptsUs);
            identity = pending.identity;
            presentationTimeNs = pending.presentationTimeNs;
        }
        if (lifecycleListener != null) {
            try {
                lifecycleListener.onFrameRendered(
                        generation, callbackConnection, presentationTimeNs, identity);
            } catch (RuntimeException callbackFailure) {
                recordCleanupFailure("render callback failed: " + safeMessage(callbackFailure));
                closeSocket();
                return;
            }
        }
        synchronized (lock) {
            if (decoder != callbackCodec || connectionGeneration != callbackConnection
                    || stopRequested || "stopping".equals(state) || "stopped".equals(state)
                    || "failed".equals(state)) return;
            renderedFrames++;
            connectionState = "receiving";
            if (liveRequested && "receiver_armed".equals(state)) {
                state = "receiving";
                revision.incrementAndGet();
            }
            CountDownLatch startReady = ready;
            if (startReady != null) startReady.countDown();
        }
        if (listener != null) {
            try {
                listener.onFrameRendered(generation, identity);
            } catch (RuntimeException callbackFailure) {
                recordCleanupFailure("frame callback failed: " + safeMessage(callbackFailure));
                closeSocket();
            }
        }
    }

    private void releaseConnection() {
        closeSocket();
        MediaCodec codec = decoder;
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) { }
            try {
                codec.release();
                if (decoder == codec) decoder = null;
            } catch (Exception releaseFailure) {
                recordCleanupFailure("decoder release failed: " + safeMessage(releaseFailure));
            }
        }
        long retiredConnection = 0L;
        synchronized (lock) {
            pendingFrames.clear();
            if (connectionGeneration > retiredConnectionGeneration) {
                retiredConnection = connectionGeneration;
                retiredConnectionGeneration = connectionGeneration;
            }
        }
        if (retiredConnection != 0L && lifecycleListener != null) {
            try {
                lifecycleListener.onConnectionRetired(generation, retiredConnection);
            } catch (RuntimeException callbackFailure) {
                recordCleanupFailure("connection retirement callback failed: "
                        + safeMessage(callbackFailure));
            }
        }
    }

    private void closeSocket() {
        Socket connection = socket;
        if (connection != null) {
            try {
                connection.close();
                if (socket == connection && connection.isClosed()) socket = null;
            } catch (IOException closeFailure) {
                recordCleanupFailure("socket close failed: " + safeMessage(closeFailure));
            }
        }
    }

    private void closeListener() {
        ServerSocket current = incomingListener;
        if (current != null) {
            try {
                current.close();
                if (incomingListener == current && current.isClosed()) incomingListener = null;
            } catch (IOException failure) {
                recordCleanupFailure("sink listener close failed: " + safeMessage(failure));
            }
        }
    }

    private void releaseRenderThread() {
        HandlerThread callbacks = renderThread;
        if (callbacks == null) return;
        callbacks.quitSafely();
        try { callbacks.join(bounds.shutdownTimeoutMs); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!callbacks.isAlive()) renderThread = null;
        else recordCleanupFailure("render callback thread did not terminate");
    }

    private void recordCleanupFailure(String message) {
        synchronized (lock) {
            failure = message;
            state = "failed";
            stopRequested = true;
            revision.incrementAndGet();
            CountDownLatch startReady = ready;
            if (startReady != null) startReady.countDown();
        }
    }

    private static void requireNotMainThread(String operation) {
        Looper main = Looper.getMainLooper();
        if (main != null && Looper.myLooper() == main) {
            throw new IllegalStateException(operation + " must run off the Android main thread");
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? "" : message;
    }

    public interface FrameListener {
        void onFrameRendered(long generation, FrameIdentity identity);
    }

    /** Exact before/after Surface-render witnesses for an embedding native host. */
    public interface FrameLifecycleListener {
        boolean onFrameReadyForRender(long receiverGeneration, long connectionGeneration,
                long presentationTimeNs, FrameIdentity identity);
        void onFrameRendered(long receiverGeneration, long connectionGeneration,
                long presentationTimeNs, FrameIdentity identity);
        void onConnectionRetired(long receiverGeneration, long connectionGeneration);
    }

    /** Immutable exact source identity for one frame released to the host Surface. */
    public static final class FrameIdentity {
        public final long presentationTimeUs;
        public final long sourceElapsedNs;
        public final long sourceUnixNs;
        public final long pairId;
        public final long leftSourceFrame;
        public final long rightSourceFrame;
        public final long leftSensorTimestampNs;
        public final long rightSensorTimestampNs;
        public final long pairDeltaNs;

        private FrameIdentity(RmanvidPacketReader.Packet packet) {
            presentationTimeUs = packet.ptsUs;
            sourceElapsedNs = packet.sourceElapsedNs;
            sourceUnixNs = packet.sourceUnixNs;
            pairId = packet.pair.pairId;
            leftSourceFrame = packet.pair.leftSourceFrame;
            rightSourceFrame = packet.pair.rightSourceFrame;
            leftSensorTimestampNs = packet.pair.leftSensorTimestampNs;
            rightSensorTimestampNs = packet.pair.rightSensorTimestampNs;
            pairDeltaNs = packet.pair.pairDeltaNs;
        }

        static FrameIdentity from(RmanvidPacketReader.Packet packet) {
            return new FrameIdentity(packet);
        }
    }

    /** Explicit allocation and time bounds supplied by the embedding host. */
    public static final class Bounds {
        final int maxHeaderBytes;
        final int maxPacketBytes;
        final int maxWidth;
        final int maxHeight;
        final int maxPendingFrames;
        final int connectTimeoutMs;
        final int readTimeoutMs;
        final int codecInputTimeoutMs;
        final int startupTimeoutMs;
        final int shutdownTimeoutMs;
        final int maxReconnectAttempts;
        final int reconnectDelayMs;

        public Bounds(int maxHeaderBytes, int maxPacketBytes, int maxWidth, int maxHeight,
                int maxPendingFrames, int connectTimeoutMs, int readTimeoutMs,
                int codecInputTimeoutMs, int startupTimeoutMs, int shutdownTimeoutMs,
                int maxReconnectAttempts, int reconnectDelayMs) {
            if (maxHeaderBytes <= 0 || maxHeaderBytes > 1024 * 1024
                    || maxPacketBytes <= 0 || maxPacketBytes > 32 * 1024 * 1024
                    || maxWidth <= 0 || maxWidth > 16384 || maxHeight <= 0 || maxHeight > 16384
                    || maxPendingFrames <= 0 || maxPendingFrames > 256
                    || connectTimeoutMs <= 0 || connectTimeoutMs > 60_000
                    || readTimeoutMs <= 0 || readTimeoutMs > 60_000
                    || codecInputTimeoutMs <= 0 || codecInputTimeoutMs > 60_000
                    || startupTimeoutMs <= 0 || startupTimeoutMs > 300_000
                    || shutdownTimeoutMs <= 0 || shutdownTimeoutMs > 60_000
                    || maxReconnectAttempts < 0 || maxReconnectAttempts > 100
                    || reconnectDelayMs < 0 || reconnectDelayMs > 60_000) {
                throw new IllegalArgumentException("invalid receiver bounds");
            }
            this.maxHeaderBytes = maxHeaderBytes;
            this.maxPacketBytes = maxPacketBytes;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.maxPendingFrames = maxPendingFrames;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.codecInputTimeoutMs = codecInputTimeoutMs;
            this.startupTimeoutMs = startupTimeoutMs;
            this.shutdownTimeoutMs = shutdownTimeoutMs;
            this.maxReconnectAttempts = maxReconnectAttempts;
            this.reconnectDelayMs = reconnectDelayMs;
        }
    }

    private static final class PendingFrame {
        final long connectionGeneration;
        final FrameIdentity identity;
        long presentationTimeNs;
        boolean readyForRender;
        PendingFrame(long connectionGeneration, FrameIdentity identity) {
            this.connectionGeneration = connectionGeneration;
            this.identity = identity;
        }
    }

    private final class ReceiverProvider implements MediaOwnerProvider {
        @Override public MediaProviderReadback execute(MediaOwnerAction action,
                CancellationHandle cancellation) throws Exception {
            requireSink(action, cancellation);
            if ("stop".equals(action.actionKind()) || "cleanup".equals(action.actionKind())) {
                return stop(action);
            }
            if ("arm_receiver".equals(action.actionKind())) arm();
            else start();
            cancellation.requireCurrent(generation);
            return readback(action);
        }

        @Override public MediaProviderReadback compensate(MediaOwnerAction action,
                CancellationHandle cancellation) {
            requireSink(action, cancellation);
            return stop(action);
        }

        @Override public MediaRuntimeSnapshot snapshot() {
            return PackedStereoMediaReceiver.this.snapshot();
        }

        private MediaProviderReadback stop(MediaOwnerAction action) {
            stopAndVerify();
            return readback(action);
        }

        private MediaProviderReadback readback(MediaOwnerAction action) {
            MediaRuntimeSnapshot current = snapshot();
            return new MediaProviderReadback(action, handleId, current.revision(), current.state(),
                    action.actionId() + ":" + action.sequence() + ":receiver:"
                            + current.revision());
        }

        private void requireSink(MediaOwnerAction action, CancellationHandle cancellation) {
            if (!"sink".equals(action.ownerKind())) {
                throw new IllegalArgumentException("packed receiver provider requires sink owner");
            }
            cancellation.requireCurrent(generation);
        }
    }
}
