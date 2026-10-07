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
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Iterator;
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
    private final ReceiverStageTrace stageTrace;
    private final FrameListener listener;
    private final FrameLifecycleListener lifecycleListener;
    private final SurfaceAcquisitionProbe acquisitionProbe;
    private final String handleId;
    private final AtomicLong revision = new AtomicLong();
    private final Map<Long, PendingFrame> pendingFrames = new LinkedHashMap<>();
    // Exact, bounded history for late callbacks of superseded released frames.
    // History is never proof that a callback or image acquisition happened.
    private final Map<Long, PendingFrame> retiredRenderFrames = new LinkedHashMap<>();
    private long nextReleaseOrdinal;
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
    private long acquiredFrames, acquiredSuperseded, acquisitionFeedbackRejected;
    private long lastAcquiredReleaseOrdinal;
    private long receivedPackets;
    private int reconnects;
    private volatile String transportStage = "IDLE";
    private String firstTransportStage = "NONE", firstTransportCause = "NONE";
    private String finalTransportStage = "NONE", finalTransportCause = "NONE";
    private long connectionGeneration;
    private long retiredConnectionGeneration;
    // Closed receiver observability. Only enums and
    // counters; no exception text, endpoints or tokens. First code is sticky per
    // receiver generation and is never overwritten by cleanup-time failures.
    private String firstFailureCode = "NONE", finalFailureCode = "NONE";
    private long acceptedConnections, bytesRead, packetsRead, configPackets, keyframePackets;
    private long packetsBeforeBootstrap, inputsQueued, outputsDequeued, outputsReleasedForRender;
    private long renderCallbacks, renderCallbacksSuperseded, lateRenderCallbacks, renderHistoryEvicted;
    // The codec listener is informational on some Android releases. Count its
    // actual entries separately from callbacks accepted by the identity join.
    private long rawRenderCallbacks, renderRejectedCodec, renderRejectedState;
    private long renderRejectedMissingPts, renderRejectedConnection, renderRejectedNotReady;
    private long renderRejectedTimestamp, renderRejectedAfterWitness;
    private String firstRenderRejectCode = "NONE";
    private long firstRenderRejectMediaTimeUs, firstRenderRejectPendingPtsUs = -1L;
    private int firstRenderRejectPendingCount;
    private long preRenderRejected, identityWindowOverflows, maxQueuedWindow, maxRenderWindow;
    private String decoderClass = "NONE";
    private boolean connectionBytesSeen, connectionConfigSeen, connectionKeyframeSeen;

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
        this(surface, host, port, generation, bounds, listener, lifecycleListener,
                expectedSourceHost, null);
    }

    /** Explicit opt-in: embedded hosts can prove Surface delivery by exact native acquisition. */
    public PackedStereoMediaReceiver(Surface surface, String host, int port, long generation,
            Bounds bounds, FrameListener listener, FrameLifecycleListener lifecycleListener,
            String expectedSourceHost, SurfaceAcquisitionProbe acquisitionProbe) {
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
        this.stageTrace = new ReceiverStageTrace(generation);
        this.listener = listener;
        this.lifecycleListener = lifecycleListener;
        this.acquisitionProbe = acquisitionProbe;
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

    /** Arms if needed, then waits for an exact rendered callback or opt-in Surface acquisition. */
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
        boolean witnessed;
        if (acquisitionProbe == null) {
            witnessed = startReady.await(bounds.startupTimeoutMs, TimeUnit.MILLISECONDS);
        } else {
            long deadline = SystemClock.elapsedRealtime() + bounds.startupTimeoutMs;
            witnessed = false;
            while (!stopRequested && SystemClock.elapsedRealtime() < deadline) {
                if (startReady.await(5L, TimeUnit.MILLISECONDS)) {
                    witnessed = true;
                    break;
                }
                MediaCodec current = decoder;
                long connection;
                synchronized (lock) { connection = connectionGeneration; }
                if (current != null && connection > 0L) pollSurfaceAcquisition(current, connection);
            }
        }
        if (!witnessed || !"receiving".equals(connectionState)) {
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
        final HandlerThread callbacks;
        final long callbackConnection;
        synchronized (lock) {
            callbacks = renderThread;
            callbackConnection = connectionGeneration;
        }
        // A queued render callback needs this monitor to observe stopRequested.
        // Retain ownership while it drains; joining under the monitor fences it out.
        if (callbacks != null) {
            callbacks.quitSafely();
            try { callbacks.join(bounds.shutdownTimeoutMs); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        synchronized (lock) {
            if (renderThread != callbacks || connectionGeneration != callbackConnection) {
                throw new IllegalStateException("receiver callback ownership changed during shutdown");
            }
            if (callbacks != null && !callbacks.isAlive()) renderThread = null;
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
                    synchronized (lock) {
                        connectionBytesSeen = false;
                        connectionConfigSeen = false;
                        connectionKeyframeSeen = false;
                    }
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
            String code = closedFailureCodeLocked(failure, cause);
            if ("NONE".equals(firstFailureCode)) firstFailureCode = code;
            finalFailureCode = code;
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
                    + " maxPacketBytes=" + bounds.maxPacketBytes + " " + closedCountersLocked()
                    + " code=TRANSPORT_EFFECT_UNCERTAIN");
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
                new DataInputStream(new BufferedInputStream(new CountingInputStream(connection.getInputStream()))),
                bounds.maxHeaderBytes, bounds.maxPacketBytes, bounds.maxWidth, bounds.maxHeight);
        synchronized (lock) {
            acceptedConnections++;
            connectionBytesSeen = false; connectionConfigSeen = false; connectionKeyframeSeen = false;
        }
        transportStage = "HEADER";
        RmanvidPacketReader.Header header = reader.readHeader();
        transportStage = "DECODER_CONFIG";
        MediaFormat format = MediaFormat.createVideoFormat(MIME_H264, header.width, header.height);
        // The reader already bounds packets by maxPacketBytes; make the decoder
        // allocate input buffers for that same bound instead of a codec default
        // (a 2560x1280 IDR can exceed a default-sized input buffer).
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bounds.maxPacketBytes);
        MediaCodec codec;
        try { codec = MediaCodec.createDecoderByType(MIME_H264); }
        catch (IOException | RuntimeException create) { throw closed("DECODER_CREATE", create); }
        decoder = codec;
        synchronized (lock) { decoderClass = closedDecoderClass(codec); }
        try { codec.configure(format, surface, null, 0); }
        catch (RuntimeException configure) { throw closed("DECODER_CONFIGURE", configure); }
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
        try { codec.start(); }
        catch (RuntimeException start) { throw closed("DECODER_START", start); }
        connectionState = "decoder_configured";
        synchronized (lock) {
            if (decoder == codec && !stopRequested && retiredConnectionGeneration < activeConnection)
                stageTrace.begin(activeConnection, SystemClock.elapsedRealtimeNanos());
        }

        boolean configurationSeen = false;
        boolean keyframeSeen = false;
        while (!stopRequested) {
            transportStage = "PACKET_READ";
            RmanvidPacketReader.Packet packet = reader.readPacket();
            transportStage = "DECODE";
            boolean config = (packet.flags & RmanvidPacketReader.FLAG_CODEC_CONFIG) != 0;
            boolean keyframe = (packet.flags & RmanvidPacketReader.FLAG_KEY_FRAME) != 0;
            synchronized (lock) {
                packetsRead++;
                stageTrace.progress(ReceiverStageTrace.PACKET, activeConnection,
                        SystemClock.elapsedRealtimeNanos(), packet.ptsUs, config ? 0L : packet.pair.pairId);
                if (config) { configPackets++; connectionConfigSeen = true; }
                if (config) connectionKeyframeSeen = false;
                if (keyframe) { keyframePackets++; if (!config && connectionConfigSeen) connectionKeyframeSeen = true; }
            }
            if (!configurationSeen && !config) { synchronized (lock) { packetsBeforeBootstrap++; } continue; }
            if (config) {
                configurationSeen = true;
                keyframeSeen = false;
            } else if (!keyframeSeen && !keyframe) {
                synchronized (lock) { packetsBeforeBootstrap++; }
                continue;
            } else if (keyframe) {
                keyframeSeen = true;
            }
            queuePacket(codec, activeConnection, packet);
            drain(codec, activeConnection);
            pollSurfaceAcquisition(codec, activeConnection);
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
        if (inputIndex < 0) throw closed("DECODER_INPUT_TIMEOUT", null);
        ByteBuffer input = codec.getInputBuffer(inputIndex);
        if (input == null || packet.payload.length > input.capacity()) {
            throw closed("DECODER_INPUT_CAPACITY", null);
        }
        input.clear();
        input.put(packet.payload);
        boolean config = (packet.flags & RmanvidPacketReader.FLAG_CODEC_CONFIG) != 0;
        if (!config) {
            awaitIdentityCapacity(codec, activeConnection);
            synchronized (lock) {
                if (pendingFrames.containsKey(packet.ptsUs) || retiredRenderFrames.containsKey(packet.ptsUs)) {
                    throw closed("IDENTITY_PTS_COLLISION", null);
                }
                if (pendingFrames.size() >= bounds.maxPendingFrames) {
                    identityWindowOverflows++;
                    throw closed(queuedWindowLocked() >= renderWindowLocked()
                            ? "IDENTITY_WINDOW_OVERFLOW_DECODE" : "IDENTITY_WINDOW_OVERFLOW_RENDER", null);
                }
                pendingFrames.put(packet.ptsUs,
                        new PendingFrame(activeConnection, FrameIdentity.from(packet)));
                maxQueuedWindow = Math.max(maxQueuedWindow, queuedWindowLocked());
            }
        }
        // Only the two MediaCodec flags that RMANVID semantically carries are
        // forwarded; transport bits must never become EOS/PARTIAL_FRAME.
        codec.queueInputBuffer(inputIndex, 0, packet.payload.length, packet.ptsUs,
                codecInputFlags(packet.flags));
        synchronized (lock) {
            receivedPackets++; inputsQueued++;
            stageTrace.progress(ReceiverStageTrace.INPUT, activeConnection,
                    SystemClock.elapsedRealtimeNanos(), packet.ptsUs, config ? 0L : packet.pair.pairId);
        }
    }

    private void awaitIdentityCapacity(MediaCodec codec, long activeConnection) throws Exception {
        if (acquisitionProbe == null) return;
        long deadline = SystemClock.elapsedRealtime() + bounds.codecInputTimeoutMs;
        while (!stopRequested) {
            synchronized (lock) {
                if (pendingFrames.size() < bounds.maxPendingFrames) return;
            }
            // The same worker drains codec output; waiting without draining
            // would strand queued identities even when the decoder progressed.
            drain(codec, activeConnection);
            pollSurfaceAcquisition(codec, activeConnection);
            synchronized (lock) {
                if (pendingFrames.size() < bounds.maxPendingFrames) return;
            }
            if (SystemClock.elapsedRealtime() >= deadline) return;
            Thread.sleep(1L);
        }
    }

    private void pollSurfaceAcquisition(MediaCodec codec, long activeConnection) {
        if (acquisitionProbe == null || stopRequested) return;
        // JNI or the host's reader callback may reenter the process. Never hold
        // the receiver lock while querying it.
        AcquiredFrame acquired = acquisitionProbe.latestAcquired(generation, activeConnection);
        if (acquired == null) return;
        synchronized (lock) {
            if (decoder != codec || stopRequested || acquired.receiverGeneration != generation
                    || acquired.connectionGeneration != activeConnection
                    || acquired.presentationTimeNs <= 0L
                    || acquired.presentationTimeNs % 1_000L != 0L) {
                acquisitionFeedbackRejected = saturatedIncrement(acquisitionFeedbackRejected);
                return;
            }
            PendingFrame exact = pendingFrames.get(acquired.presentationTimeNs / 1_000L);
            if (exact == null) exact = retiredRenderFrames.get(acquired.presentationTimeNs / 1_000L);
            if (exact == null || !exact.readyForRender
                    || exact.connectionGeneration != activeConnection
                    || exact.presentationTimeNs != acquired.presentationTimeNs
                    || !acquired.matches(exact.identity)) {
                acquisitionFeedbackRejected = saturatedIncrement(acquisitionFeedbackRejected);
                return;
            }
            if (exact.releaseOrdinal <= lastAcquiredReleaseOrdinal) return;
            lastAcquiredReleaseOrdinal = exact.releaseOrdinal;
            acquiredFrames = saturatedIncrement(acquiredFrames);
            stageTrace.progress(ReceiverStageTrace.ACQUIRED, activeConnection,
                    SystemClock.elapsedRealtimeNanos(), exact.identity.presentationTimeUs, exact.identity.pairId);
            int superseded = retireEarlierReleasedLocked(activeConnection, exact.releaseOrdinal);
            acquiredSuperseded += Math.min((long) superseded, Long.MAX_VALUE - acquiredSuperseded);
            connectionState = "receiving";
            if (liveRequested && "receiver_armed".equals(state)) {
                state = "receiving";
                revision.incrementAndGet();
            }
            CountDownLatch startReady = ready;
            if (startReady != null) startReady.countDown();
        }
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
                outputsDequeued++;
                pending = pendingFrames.get(info.presentationTimeUs);
                if (pending == null || pending.connectionGeneration != activeConnection
                        || pending.readyForRender) {
                    throw closed("OUTPUT_IDENTITY_MISSING", null);
                }
                stageTrace.progress(ReceiverStageTrace.OUTPUT, activeConnection,
                        SystemClock.elapsedRealtimeNanos(), info.presentationTimeUs, pending.identity.pairId);
                // PTS is an exact lookup key, never an ordering promise. Retain
                // queued frames across reordered output until their own output
                // arrives or this connection is retired.
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
            if (!accepted) {
                synchronized (lock) { preRenderRejected++; }
            }
            synchronized (lock) {
                PendingFrame current = pendingFrames.get(info.presentationTimeUs);
                accepted = accepted && current == pending && decoder == codec && !stopRequested;
                if (accepted) {
                    pending.presentationTimeNs = presentationTimeNs;
                    pending.releaseOrdinal = Math.incrementExact(nextReleaseOrdinal);
                    nextReleaseOrdinal = pending.releaseOrdinal;
                    pending.readyForRender = true;
                    outputsReleasedForRender++;
                    maxRenderWindow = Math.max(maxRenderWindow, renderWindowLocked());
                } else {
                    pendingFrames.remove(info.presentationTimeUs);
                }
            }
            codec.releaseOutputBuffer(outputIndex, accepted);
            if (accepted) {
                synchronized (lock) {
                    stageTrace.progress(ReceiverStageTrace.RELEASE, activeConnection,
                            SystemClock.elapsedRealtimeNanos(), info.presentationTimeUs, pending.identity.pairId);
                }
            }
            if (!accepted) throw closed("PRE_RENDER_IDENTITY_REJECTED", null);
        }
    }

    private void rendered(MediaCodec callbackCodec, long callbackConnection, long ptsUs) {
        FrameIdentity identity;
        long presentationTimeNs;
        synchronized (lock) {
            rawRenderCallbacks = saturatedIncrement(rawRenderCallbacks);
            if (decoder != callbackCodec) {
                recordRenderRejectLocked("CODEC", ptsUs);
                return;
            }
            if (stopRequested || "stopping".equals(state)
                    || "stopped".equals(state) || "failed".equals(state)) {
                recordRenderRejectLocked("STATE", ptsUs);
                return;
            }
            PendingFrame pending = pendingFrames.get(ptsUs);
            boolean late = false;
            if (pending == null) {
                pending = retiredRenderFrames.get(ptsUs);
                late = pending != null;
            }
            if (pending == null) {
                recordRenderRejectLocked("PTS_MISSING", ptsUs);
                return;
            }
            if (pending.callbackObserved) {
                recordRenderRejectLocked("PTS_MISSING", ptsUs);
                return;
            }
            if (pending.connectionGeneration != callbackConnection) {
                recordRenderRejectLocked("CONNECTION", ptsUs);
                return;
            }
            if (!pending.readyForRender) {
                recordRenderRejectLocked("NOT_READY", ptsUs);
                return;
            }
            if (pending.presentationTimeNs <= 0L) {
                recordRenderRejectLocked("TIMESTAMP", ptsUs);
                return;
            }
            pending.callbackObserved = true;
            if (acquisitionProbe == null || late) {
                pendingFrames.remove(ptsUs);
                retiredRenderFrames.remove(ptsUs);
            }
            renderCallbacks++;
            if (late) lateRenderCallbacks++;
            // Use surface-release order, not PTS order. Preserve exact identities
            // in bounded history so reordered late callbacks can still reach the
            // native acquisition/render join without inventing either witness.
            retireEarlierReleasedLocked(callbackConnection, pending.releaseOrdinal);
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
                    || "failed".equals(state)) {
                recordRenderRejectLocked("AFTER_WITNESS", ptsUs);
                return;
            }
            renderedFrames++;
            if (acquisitionProbe == null) connectionState = "receiving";
            if (acquisitionProbe == null && liveRequested && "receiver_armed".equals(state)) {
                state = "receiving";
                revision.incrementAndGet();
            }
            if (acquisitionProbe == null) {
                CountDownLatch startReady = ready;
                if (startReady != null) startReady.countDown();
            }
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

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    /** Called only under lock; codes and the one exact PTS sample are bounded diagnostics. */
    private void recordRenderRejectLocked(String code, long mediaTimeUs) {
        switch (code) {
            case "CODEC": renderRejectedCodec = saturatedIncrement(renderRejectedCodec); break;
            case "STATE": renderRejectedState = saturatedIncrement(renderRejectedState); break;
            case "PTS_MISSING": renderRejectedMissingPts = saturatedIncrement(renderRejectedMissingPts); break;
            case "CONNECTION": renderRejectedConnection = saturatedIncrement(renderRejectedConnection); break;
            case "NOT_READY": renderRejectedNotReady = saturatedIncrement(renderRejectedNotReady); break;
            case "TIMESTAMP": renderRejectedTimestamp = saturatedIncrement(renderRejectedTimestamp); break;
            case "AFTER_WITNESS": renderRejectedAfterWitness = saturatedIncrement(renderRejectedAfterWitness); break;
            default: throw new AssertionError("unclosed render rejection code");
        }
        if ("NONE".equals(firstRenderRejectCode)) {
            firstRenderRejectCode = code;
            firstRenderRejectMediaTimeUs = mediaTimeUs;
            firstRenderRejectPendingCount = pendingFrames.size() + retiredRenderFrames.size();
            Map<Long, PendingFrame> sample = pendingFrames.isEmpty()
                    ? retiredRenderFrames : pendingFrames;
            if (!sample.isEmpty()) firstRenderRejectPendingPtsUs = sample.keySet().iterator().next();
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
            retiredRenderFrames.clear();
            lastAcquiredReleaseOrdinal = 0L;
            if (connectionGeneration > retiredConnectionGeneration) {
                retiredConnection = connectionGeneration;
                retiredConnectionGeneration = connectionGeneration;
                stageTrace.retire(retiredConnection, SystemClock.elapsedRealtimeNanos());
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

    /** An actual host-reader acquisition, not a codec callback or GPU-effect claim. */
    public interface SurfaceAcquisitionProbe {
        AcquiredFrame latestAcquired(long receiverGeneration, long connectionGeneration);
    }

    /** Exact native Surface image identity for the opt-in receiver path. */
    public static final class AcquiredFrame {
        public final long receiverGeneration, connectionGeneration, presentationTimeNs;
        public final long sourceElapsedNs, sourceUnixNs, pairId;
        public final long leftSourceFrame, rightSourceFrame;
        public final long leftSensorTimestampNs, rightSensorTimestampNs, pairDeltaNs;

        public AcquiredFrame(long receiverGeneration, long connectionGeneration,
                long presentationTimeNs, long sourceElapsedNs, long sourceUnixNs,
                long pairId, long leftSourceFrame, long rightSourceFrame,
                long leftSensorTimestampNs, long rightSensorTimestampNs, long pairDeltaNs) {
            this.receiverGeneration = receiverGeneration;
            this.connectionGeneration = connectionGeneration;
            this.presentationTimeNs = presentationTimeNs;
            this.sourceElapsedNs = sourceElapsedNs;
            this.sourceUnixNs = sourceUnixNs;
            this.pairId = pairId;
            this.leftSourceFrame = leftSourceFrame;
            this.rightSourceFrame = rightSourceFrame;
            this.leftSensorTimestampNs = leftSensorTimestampNs;
            this.rightSensorTimestampNs = rightSensorTimestampNs;
            this.pairDeltaNs = pairDeltaNs;
        }

        private boolean matches(FrameIdentity identity) {
            return identity != null && sourceElapsedNs == identity.sourceElapsedNs
                    && sourceUnixNs == identity.sourceUnixNs && pairId == identity.pairId
                    && leftSourceFrame == identity.leftSourceFrame
                    && rightSourceFrame == identity.rightSourceFrame
                    && leftSensorTimestampNs == identity.leftSensorTimestampNs
                    && rightSensorTimestampNs == identity.rightSensorTimestampNs
                    && pairDeltaNs == identity.pairDeltaNs;
        }
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

    /** Debug-only stage timings; no readiness, GPU completion or acceptance claim. */
    public org.json.JSONObject diagnosticStageSnapshot() throws Exception {
        return stageTrace.sample(SystemClock::elapsedRealtimeNanos);
    }

    /** Closed receiver counters for activation/status diagnostics. Never contains free text. */
    public String closedActivationCounters() {
        synchronized (lock) { return closedCountersLocked(); }
    }

    private String closedCountersLocked() {
        return "firstFailure=" + firstFailureCode + " finalFailure=" + finalFailureCode
                + " accepts=" + acceptedConnections + " bytes=" + bytesRead
                + " packetsRead=" + packetsRead + " configPackets=" + configPackets
                + " keyframePackets=" + keyframePackets + " preBootstrapDropped=" + packetsBeforeBootstrap
                + " decoder=" + decoderClass + " inputs=" + inputsQueued + " outputs=" + outputsDequeued
                + " releasedForRender=" + outputsReleasedForRender + " renderCallbacks=" + renderCallbacks
                + " acquiredFrames=" + acquiredFrames + " acquiredSuperseded=" + acquiredSuperseded
                + " acquisitionFeedbackRejected=" + acquisitionFeedbackRejected
                + " rawRenderCallbacks=" + rawRenderCallbacks
                + " renderRejectCodec=" + renderRejectedCodec + " renderRejectState=" + renderRejectedState
                + " renderRejectMissingPts=" + renderRejectedMissingPts
                + " renderRejectConnection=" + renderRejectedConnection
                + " renderRejectNotReady=" + renderRejectedNotReady
                + " renderRejectTimestamp=" + renderRejectedTimestamp
                + " renderRejectAfterWitness=" + renderRejectedAfterWitness
                + " firstRenderReject=" + firstRenderRejectCode
                + " firstRenderRejectMediaTimeUs=" + firstRenderRejectMediaTimeUs
                + " firstRenderRejectPendingPtsUs=" + firstRenderRejectPendingPtsUs
                + " firstRenderRejectPendingCount=" + firstRenderRejectPendingCount
                + " renderSuperseded=" + renderCallbacksSuperseded + " lateCallbacks=" + lateRenderCallbacks
                + " renderHistoryEvicted=" + renderHistoryEvicted
                + " preRenderRejected=" + preRenderRejected + " windowOverflows=" + identityWindowOverflows
                + " maxQueuedWindow=" + maxQueuedWindow + " maxRenderWindow=" + maxRenderWindow
                + " queuedWindow=" + queuedWindowLocked() + " renderWindow=" + renderWindowLocked()
                + " renderHistory=" + retiredRenderFrames.size() + " windowBound=" + bounds.maxPendingFrames
                + " receiverFirstStage=" + firstTransportStage + " receiverFirstCause=" + firstTransportCause
                + " receiverFinalStage=" + finalTransportStage + " receiverFinalCause=" + finalTransportCause;
    }

    private int queuedWindowLocked() {
        int n = 0;
        for (PendingFrame frame : pendingFrames.values()) if (!frame.readyForRender) n++;
        return n;
    }

    private int renderWindowLocked() { return pendingFrames.size() - queuedWindowLocked(); }

    /** Supersedes released predecessors; queued decode identities are never discarded by PTS. */
    private int retireEarlierReleasedLocked(long connection, long releaseOrdinal) {
        int superseded = 0;
        Iterator<Map.Entry<Long, PendingFrame>> it = pendingFrames.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PendingFrame> entry = it.next();
            PendingFrame frame = entry.getValue();
            if (frame.connectionGeneration == connection && frame.readyForRender
                    && frame.releaseOrdinal < releaseOrdinal) {
                retiredRenderFrames.put(entry.getKey(), frame);
                it.remove();
                renderCallbacksSuperseded++;
                superseded++;
            }
        }
        while (retiredRenderFrames.size() > bounds.maxPendingFrames) {
            Iterator<Long> oldest = retiredRenderFrames.keySet().iterator();
            oldest.next(); oldest.remove(); renderHistoryEvicted++;
        }
        return superseded;
    }

    private static int codecInputFlags(int rmanvidFlags) {
        int flags = 0;
        if ((rmanvidFlags & RmanvidPacketReader.FLAG_CODEC_CONFIG) != 0) flags |= MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        if ((rmanvidFlags & RmanvidPacketReader.FLAG_KEY_FRAME) != 0) flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        return flags;
    }

    private static String closedDecoderClass(MediaCodec codec) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.media.MediaCodecInfo info = codec.getCodecInfo();
                return info.isHardwareAccelerated() ? "HARDWARE" : info.isSoftwareOnly() ? "SOFTWARE" : "UNKNOWN";
            }
        } catch (RuntimeException unavailable) { return "UNKNOWN"; }
        return "UNKNOWN";
    }

    /** Closed failure; the code is from a fixed vocabulary and the cause is never exported. */
    private static final class ClosedReceiverFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        ClosedReceiverFailure(String code, Throwable cause) { super(code, cause); this.code = code; }
    }

    private static IOException closed(String code, Throwable cause) {
        return new ClosedReceiverFailure(code, cause);
    }

    private String closedFailureCodeLocked(Throwable failure, String transportCause) {
        if (failure instanceof ClosedReceiverFailure) return ((ClosedReceiverFailure) failure).code;
        if ("android.media.MediaCodec$CodecException".equals(failure.getClass().getName())) return "DECODER_CODEC_EXCEPTION";
        if ("READ_TIMEOUT".equals(transportCause) || "EOF".equals(transportCause) || "IO".equals(transportCause)) {
            String suffix = "READ_TIMEOUT".equals(transportCause) ? "TIMEOUT" : "EOF".equals(transportCause) ? "EOF" : "IO";
            if (!connectionBytesSeen) return "NO_INCOMING_BYTES_" + suffix;
            if (!connectionConfigSeen) return "NO_CODEC_CONFIG_" + suffix;
            if (!connectionKeyframeSeen) return "NO_KEYFRAME_" + suffix;
            return "STREAM_" + suffix;
        }
        switch (transportCause) {
            case "REFUSED": case "CONNECT_TIMEOUT": case "HEADER_IO": case "STATE": return transportCause;
            default: return "OTHER";
        }
    }

    private final class CountingInputStream extends FilterInputStream {
        CountingInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count(1);
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }
        private void count(int n) { synchronized (lock) { bytesRead += n; connectionBytesSeen = true; } }
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
        boolean callbackObserved;
        long releaseOrdinal;
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
