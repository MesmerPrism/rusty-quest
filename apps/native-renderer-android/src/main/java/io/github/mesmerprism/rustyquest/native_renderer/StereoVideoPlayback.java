package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class StereoVideoPlayback {
    private static final String LOG_TAG = "RQNativeRenderer";
    private static final String SOURCE_APP_PRIVATE_FILE = "app-private-file";
    private static final String SOURCE_BROKER_RMANVID1 = "broker-rmanvid1";
    private static final String STREAM_MAGIC = "RMANVID1";
    private static final String LEGACY_STREAM_MAGIC = "RXYRVID1";
    private static final int CODEC_H264 = 1;
    private static final int SIDE_MONO_OR_FILE = 0;
    private static final int SIDE_LEFT = 1;
    private static final int SIDE_RIGHT = 2;
    private static final int EVENT_START_REQUESTED = 1;
    private static final int EVENT_STARTED = 2;
    private static final int EVENT_STOPPED = 3;
    private static final int EVENT_ERROR = 4;
    private static final int EVENT_FORMAT = 5;
    private static final int EVENT_FRAME = 6;
    private static final int EVENT_LOOP_RESTARTED = 7;
    private static final int EVENT_CONNECTED = 8;
    private static final int EVENT_STREAM_HEADER = 9;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final int MAX_PACKET_BYTES = 1024 * 1024;
    private static final int MAX_STREAM_HEADER_METADATA_BYTES = 256 * 1024;
    private static final int MAX_STREAM_PACKETS = 10_000_000;
    private static final int STREAM_READ_TIMEOUT_MS = 60_000;
    private static final long BROKER_PROGRESS_LOG_INTERVAL_MS = 1_000L;
    private static final long BROKER_OUTPUT_STALL_WARN_MS = 3_000L;
    private static final long BROKER_OUTPUT_STALL_FLUSH_MS = 5_000L;
    private static final long BROKER_STALL_BACKLOG_PACKETS = 24L;
    private static final long BROKER_INPUT_STALL_WARN_MS = 3_000L;
    private static final long BROKER_INPUT_STALL_FLUSH_MS = 5_000L;
    private static final long BROKER_INPUT_STARVATION_RECREATE_MS = 8_000L;
    private static final int BROKER_INPUT_STALL_QUEUE_PACKETS = 8;
    private static final int BROKER_PACKET_QUEUE_CAPACITY = 120;
    private static final long BROKER_PACKET_POLL_TIMEOUT_MS = 10L;
    private static final long BROKER_RECONNECT_DELAY_MS = 250L;

    private static final Object LOCK = new Object();
    private static volatile boolean stopRequested;
    private static Thread playbackThread;
    private static Thread leftBrokerThread;
    private static Thread rightBrokerThread;
    private static Surface playbackSurface;
    private static Surface leftBrokerSurface;
    private static Surface rightBrokerSurface;
    private static boolean nativeBridgeLoaded;

    static {
        try {
            System.loadLibrary("rusty_quest_native_renderer");
            nativeBridgeLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            nativeBridgeLoaded = false;
        }
    }

    private StereoVideoPlayback() {}

    public static void start(
        Context context,
        String source,
        String path,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping,
        String brokerHost,
        int brokerLeftPort,
        int brokerRightPort,
        int brokerConnectTimeoutMs
    ) {
        int requestedWidth = clamp(width, 320, 4096);
        int requestedHeight = clamp(height, 240, 4096);
        int requestedMaxImages = clamp(maxImages, 2, 6);
        int requestedFpsCap = clamp(fpsCap, 1, 90);
        int requestedBrokerLeftPort = normalizeBrokerPort(brokerLeftPort);
        int requestedBrokerRightPort = normalizeBrokerPort(brokerRightPort);
        int requestedBrokerConnectTimeoutMs = clamp(brokerConnectTimeoutMs, 100, 60000);
        String resolvedSource = normalizeSource(source);
        String resolvedBrokerHost = normalizeBrokerHost(brokerHost);
        String resolvedPath = resolvePath(context, path);
        Log.i(LOG_TAG, String.format(
            Locale.US,
            "RUSTY_QUEST_NATIVE_RENDERER channel=video-projection-playback status=start-dispatch source=%s brokerRmanvid1=%s brokerHost=%s brokerLeftPort=%d brokerRightPort=%d brokerConnectTimeoutMs=%d streamReadTimeoutMs=%d width=%d height=%d maxImages=%d fpsCap=%d nativeBridgeLoaded=%s",
            resolvedSource,
            SOURCE_BROKER_RMANVID1.equals(resolvedSource),
            resolvedBrokerHost,
            requestedBrokerLeftPort,
            requestedBrokerRightPort,
            requestedBrokerConnectTimeoutMs,
            requestedBrokerConnectTimeoutMs,
            requestedWidth,
            requestedHeight,
            requestedMaxImages,
            requestedFpsCap,
            nativeBridgeLoaded));

        synchronized (LOCK) {
            stopLocked();
            stopRequested = false;
        }

        if (nativeBridgeLoaded) {
            nativeStereoVideoLifecycleEvent(
                EVENT_START_REQUESTED,
                0,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
        }
        if (!nativeBridgeLoaded) {
            return;
        }
        if (SOURCE_BROKER_RMANVID1.equals(resolvedSource)) {
            startBrokerRmanvid1(
                resolvedBrokerHost,
                requestedBrokerLeftPort,
                requestedBrokerRightPort,
                requestedBrokerConnectTimeoutMs,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap
            );
            return;
        }
        if (!new File(resolvedPath).isFile()) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -2,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
            return;
        }

        Surface surface = nativeCreateStereoVideoSurface(
            requestedWidth,
            requestedHeight,
            requestedMaxImages,
            requestedFpsCap
        );
        if (surface == null) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -3,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
            return;
        }

        Thread thread = new Thread(
            new Runnable() {
                @Override
                public void run() {
                    runPlayback(
                        resolvedPath,
                        surface,
                        requestedWidth,
                        requestedHeight,
                        requestedMaxImages,
                        requestedFpsCap,
                        looping
                    );
                }
            },
            "RustyQuestStereoVideo"
        );
        thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        synchronized (LOCK) {
            playbackSurface = surface;
            playbackThread = thread;
        }
        thread.start();
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        stopRequested = true;
        Thread thread = playbackThread;
        Thread leftThread = leftBrokerThread;
        Thread rightThread = rightBrokerThread;
        playbackThread = null;
        leftBrokerThread = null;
        rightBrokerThread = null;
        joinThread(thread);
        joinThread(leftThread);
        joinThread(rightThread);
        boolean threadStillOwnsSurface = thread != null
            && thread != Thread.currentThread()
            && thread.isAlive();
        if (threadStillOwnsSurface) {
            playbackSurface = null;
        } else if (playbackSurface != null) {
            playbackSurface.release();
            playbackSurface = null;
        }
        if (leftThread == null || !leftThread.isAlive()) {
            releaseBrokerSurface(leftBrokerSurface);
            leftBrokerSurface = null;
        }
        if (rightThread == null || !rightThread.isAlive()) {
            releaseBrokerSurface(rightBrokerSurface);
            rightBrokerSurface = null;
        }
        if (nativeBridgeLoaded) {
            nativeStopStereoVideoStream();
            nativeStopRemoteCameraStream();
        }
    }

    private static void joinThread(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void releaseBrokerSurface(Surface surface) {
        if (surface != null) {
            surface.release();
        }
    }

    private static void runPlayback(
        String path,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) {
        int loopingFlag = looping ? 1 : 0;
        try {
            nativeStereoVideoLifecycleEvent(
                EVENT_STARTED,
                0,
                width,
                height,
                maxImages,
                fpsCap,
                loopingFlag
            );
            decodeOnce(path, surface, width, height, maxImages, fpsCap, looping);
            nativeStereoVideoLifecycleEvent(
                EVENT_STOPPED,
                0,
                width,
                height,
                maxImages,
                fpsCap,
                loopingFlag
            );
        } catch (RuntimeException | IOException error) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -1,
                width,
                height,
                maxImages,
                fpsCap,
                loopingFlag
            );
        } finally {
            synchronized (LOCK) {
                if (playbackSurface == surface) {
                    playbackSurface = null;
                }
                if (playbackThread == Thread.currentThread()) {
                    playbackThread = null;
                }
            }
            surface.release();
        }
    }

    private static void decodeOnce(
        String path,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) throws IOException {
        int loopingFlag = looping ? 1 : 0;
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("video track missing");
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int formatWidth = format.containsKey(MediaFormat.KEY_WIDTH)
                ? format.getInteger(MediaFormat.KEY_WIDTH)
                : width;
            int formatHeight = format.containsKey(MediaFormat.KEY_HEIGHT)
                ? format.getInteger(MediaFormat.KEY_HEIGHT)
                : height;
            nativeStereoVideoLifecycleEvent(
                EVENT_FORMAT,
                0,
                formatWidth,
                formatHeight,
                maxImages,
                fpsCap,
                loopingFlag
            );

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, surface, null, 0);
            codec.start();

            BufferInfo info = new BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long firstPresentationUs = -1L;
            long firstFrameReleaseNs = -1L;
            long firstLoopSamplePresentationUs = -1L;
            long lastQueuedPresentationUs = -1L;
            long presentationOffsetUs = 0L;
            long loopCount = 0L;
            long frameDurationUs = estimateFrameDurationUs(format, fpsCap);
            long renderedFrames = 0L;
            while (!outputDone && !stopRequested) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IOException("decoder input buffer unavailable");
                        }
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            if (looping) {
                                if (lastQueuedPresentationUs < 0L) {
                                    throw new IOException("video track empty");
                                }
                                presentationOffsetUs = lastQueuedPresentationUs + frameDurationUs;
                                firstLoopSamplePresentationUs = -1L;
                                lastQueuedPresentationUs = -1L;
                                loopCount += 1L;
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                nativeStereoVideoLifecycleEvent(
                                    EVENT_LOOP_RESTARTED,
                                    (int) Math.min(loopCount, Integer.MAX_VALUE),
                                    width,
                                    height,
                                    maxImages,
                                    fpsCap,
                                    loopingFlag
                                );
                                inputBuffer.clear();
                                sampleSize = extractor.readSampleData(inputBuffer, 0);
                                if (sampleSize < 0) {
                                    throw new IOException("video loop restart produced no sample");
                                }
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                );
                                inputDone = true;
                            }
                        }
                        if (sampleSize >= 0) {
                            long samplePresentationUs = extractor.getSampleTime();
                            if (firstLoopSamplePresentationUs < 0L) {
                                firstLoopSamplePresentationUs = samplePresentationUs;
                            }
                            long presentationTimeUs = presentationOffsetUs
                                + Math.max(0L, samplePresentationUs - firstLoopSamplePresentationUs);
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            lastQueuedPresentationUs = presentationTimeUs;
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outputIndex >= 0) {
                    boolean render = info.size != 0;
                    if (render) {
                        if (firstPresentationUs < 0) {
                            firstPresentationUs = info.presentationTimeUs;
                            firstFrameReleaseNs = System.nanoTime();
                        }
                        paceToPresentationTime(firstFrameReleaseNs, firstPresentationUs, info.presentationTimeUs);
                    }
                    codec.releaseOutputBuffer(outputIndex, render);
                    if (render) {
                        renderedFrames += 1L;
                        if (renderedFrames == 1L || renderedFrames % 60L == 0L) {
                            nativeStereoVideoLifecycleEvent(
                                EVENT_FRAME,
                                (int) Math.min(renderedFrames, Integer.MAX_VALUE),
                                width,
                                height,
                                maxImages,
                                fpsCap,
                                loopingFlag
                            );
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static void startBrokerRmanvid1(
        String host,
        int leftPort,
        int rightPort,
        int connectTimeoutMs,
        int width,
        int height,
        int maxImages,
        int fpsCap
    ) {
        boolean leftEnabled = leftPort > 0;
        boolean rightEnabled = rightPort > 0;
        if (!leftEnabled && !rightEnabled) {
            nativeRemoteCameraLifecycleEvent(EVENT_ERROR, SIDE_MONO_OR_FILE, -4, width, height, maxImages, fpsCap, 0);
            return;
        }
        Surface leftSurface = leftEnabled
            ? nativeCreateRemoteCameraSurface(SIDE_LEFT, width, height, maxImages, fpsCap)
            : null;
        Surface rightSurface = rightEnabled
            ? nativeCreateRemoteCameraSurface(SIDE_RIGHT, width, height, maxImages, fpsCap)
            : null;
        if ((leftEnabled && leftSurface == null) || (rightEnabled && rightSurface == null)) {
            releaseBrokerSurface(leftSurface);
            releaseBrokerSurface(rightSurface);
            nativeRemoteCameraLifecycleEvent(EVENT_ERROR, SIDE_MONO_OR_FILE, -3, width, height, maxImages, fpsCap, 0);
            return;
        }
        Log.i(LOG_TAG, String.format(
            Locale.US,
            "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=start-threads host=%s leftPort=%d rightPort=%d leftEnabled=%s rightEnabled=%s singleLaneDiagnostic=%s connectTimeoutMs=%d streamReadTimeoutMs=%d width=%d height=%d maxImages=%d fpsCap=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264 nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
            host,
            leftPort,
            rightPort,
            leftEnabled,
            rightEnabled,
            leftEnabled != rightEnabled,
            connectTimeoutMs,
            connectTimeoutMs,
            width,
            height,
            maxImages,
            fpsCap));

        Thread leftThread = leftEnabled ? new Thread(
            new Runnable() {
                @Override
                public void run() {
                    runBrokerLane(SIDE_LEFT, "left", host, leftPort, connectTimeoutMs, leftSurface, width, height, maxImages, fpsCap);
                }
            },
            "RustyQuestBrokerVideoLeft"
        ) : null;
        Thread rightThread = rightEnabled ? new Thread(
            new Runnable() {
                @Override
                public void run() {
                    runBrokerLane(SIDE_RIGHT, "right", host, rightPort, connectTimeoutMs, rightSurface, width, height, maxImages, fpsCap);
                }
            },
            "RustyQuestBrokerVideoRight"
        ) : null;
        if (leftThread != null) {
            leftThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        }
        if (rightThread != null) {
            rightThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        }
        synchronized (LOCK) {
            leftBrokerSurface = leftSurface;
            rightBrokerSurface = rightSurface;
            leftBrokerThread = leftThread;
            rightBrokerThread = rightThread;
        }
        if (leftThread != null) {
            leftThread.start();
        }
        if (rightThread != null) {
            rightThread.start();
        }
    }

    private static void runBrokerLane(
        int sideCode,
        String side,
        String host,
        int port,
        int connectTimeoutMs,
        Surface surface,
        int requestedWidth,
        int requestedHeight,
        int maxImages,
        int fpsCap
    ) {
        try {
            long connectAttempts = 0L;
            long streamRestarts = 0L;
            nativeRemoteCameraLifecycleEvent(
                EVENT_START_REQUESTED,
                sideCode,
                port,
                requestedWidth,
                requestedHeight,
                maxImages,
                fpsCap,
                0
            );
            while (!stopRequested) {
                connectAttempts += 1L;
                try {
                    if (connectAttempts == 1L || connectAttempts % 10L == 0L) {
                        Log.i(LOG_TAG, String.format(
                            Locale.US,
                            "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=connect-attempt side=%s brokerHost=%s brokerPort=%d connectAttempt=%d streamRestarts=%d reconnectDelayMs=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                            side,
                            host,
                            port,
                            connectAttempts,
                            streamRestarts,
                            BROKER_RECONNECT_DELAY_MS));
                    }
                    decodeBrokerLane(sideCode, side, host, port, connectTimeoutMs, surface, requestedWidth, requestedHeight, maxImages, fpsCap);
                    if (!stopRequested) {
                        streamRestarts += 1L;
                        Log.i(LOG_TAG, String.format(
                            Locale.US,
                            "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=stream-ended-reconnect side=%s brokerHost=%s brokerPort=%d connectAttempt=%d streamRestarts=%d reconnectDelayMs=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                            side,
                            host,
                            port,
                            connectAttempts,
                            streamRestarts,
                            BROKER_RECONNECT_DELAY_MS));
                        sleepBrokerReconnectDelay();
                    }
                } catch (RuntimeException | IOException error) {
                    if (stopRequested) {
                        break;
                    }
                    streamRestarts += 1L;
                    Log.i(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=reconnect-scheduled side=%s brokerHost=%s brokerPort=%d connectAttempt=%d streamRestarts=%d reconnectDelayMs=%d reason=%s stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        side,
                        host,
                        port,
                        connectAttempts,
                        streamRestarts,
                        BROKER_RECONNECT_DELAY_MS,
                        safeMessage(error)));
                    nativeRemoteCameraLifecycleEvent(
                        EVENT_ERROR,
                        sideCode,
                        -1,
                        requestedWidth,
                        requestedHeight,
                        maxImages,
                        fpsCap,
                        port
                    );
                    sleepBrokerReconnectDelay();
                }
            }
            nativeRemoteCameraLifecycleEvent(
                EVENT_STOPPED,
                sideCode,
                0,
                requestedWidth,
                requestedHeight,
                maxImages,
                fpsCap,
                0
            );
        } finally {
            synchronized (LOCK) {
                if (sideCode == SIDE_LEFT && leftBrokerSurface == surface) {
                    leftBrokerSurface = null;
                }
                if (sideCode == SIDE_RIGHT && rightBrokerSurface == surface) {
                    rightBrokerSurface = null;
                }
                if (sideCode == SIDE_LEFT && leftBrokerThread == Thread.currentThread()) {
                    leftBrokerThread = null;
                }
                if (sideCode == SIDE_RIGHT && rightBrokerThread == Thread.currentThread()) {
                    rightBrokerThread = null;
                }
            }
            surface.release();
        }
    }

    private static void sleepBrokerReconnectDelay() {
        if (!stopRequested) {
            SystemClock.sleep(BROKER_RECONNECT_DELAY_MS);
        }
    }

    private static void decodeBrokerLane(
        int sideCode,
        String side,
        String host,
        int port,
        int connectTimeoutMs,
        Surface surface,
        int requestedWidth,
        int requestedHeight,
        int maxImages,
        int fpsCap
    ) throws IOException {
        Socket socket = new Socket();
        MediaCodec codec = null;
        Thread packetReaderThread = null;
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            int streamReadTimeoutMs = connectTimeoutMs;
            socket.setSoTimeout(streamReadTimeoutMs);
            Log.i(LOG_TAG, String.format(
                Locale.US,
                "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=socket-connected side=%s brokerHost=%s brokerPort=%d connectTimeoutMs=%d streamReadTimeoutMs=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                side,
                host,
                port,
                connectTimeoutMs,
                streamReadTimeoutMs));
            nativeRemoteCameraLifecycleEvent(
                EVENT_CONNECTED,
                sideCode,
                0,
                requestedWidth,
                requestedHeight,
                maxImages,
                fpsCap,
                port
            );
            DataInputStream input = new DataInputStream(socket.getInputStream());
            BrokerStreamHeader header = readBrokerHeader(input);
            if (header.codecId != CODEC_H264) {
                throw new IOException("unsupported broker codec id " + header.codecId);
            }
            int streamWidth = clamp(header.width, 1, 4096);
            int streamHeight = clamp(header.height, 1, 4096);
            Log.i(LOG_TAG, String.format(
                Locale.US,
                "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=stream-header side=%s magic=%s schema=%d codecId=%d width=%d height=%d packetCount=%d metadataBytes=%d extendedPacketTimestamps=%s brokerHost=%s brokerPort=%d streamReadTimeoutMs=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264 highRateJsonPayload=false",
                side,
                header.magic,
                header.schemaVersion,
                header.codecId,
                streamWidth,
                streamHeight,
                header.packetCount,
                header.metadataBytes,
                header.extendedPacketTimestamps,
                host,
                port,
                streamReadTimeoutMs));
            nativeRemoteCameraLifecycleEvent(
                EVENT_STREAM_HEADER,
                sideCode,
                0,
                streamWidth,
                streamHeight,
                maxImages,
                fpsCap,
                port
            );

            codec = createBrokerDecoder(surface, streamWidth, streamHeight);
            nativeRemoteCameraLifecycleEvent(EVENT_STARTED, sideCode, 0, streamWidth, streamHeight, maxImages, fpsCap, port);

            BlockingQueue<BrokerPacket> packetQueue =
                    new ArrayBlockingQueue<>(BROKER_PACKET_QUEUE_CAPACITY);
            AtomicReference<IOException> packetReaderError = new AtomicReference<>();
            AtomicLong packetReaderReadPackets = new AtomicLong(0L);
            AtomicLong packetReaderReadBytes = new AtomicLong(0L);
            AtomicLong packetReaderDroppedPackets = new AtomicLong(0L);
            AtomicLong packetReaderLastPacketElapsedMs = new AtomicLong(SystemClock.elapsedRealtime());
            AtomicLong packetReaderLastPacketSize = new AtomicLong(0L);
            AtomicLong packetReaderLastPacketPtsUs = new AtomicLong(-1L);
            packetReaderThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        long readPackets = 0L;
                        long readBytes = 0L;
                        long droppedPackets = 0L;
                        try {
                            while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                                BrokerPacket packet = readBrokerPacket(input, header);
                                if (!packetQueue.offer(packet)) {
                                    BrokerPacket dropped = packetQueue.poll();
                                    if (dropped != null) {
                                        droppedPackets += 1L;
                                    }
                                    if (!packetQueue.offer(packet)) {
                                        droppedPackets += 1L;
                                        continue;
                                    }
                                }
                                readPackets += 1L;
                                readBytes += packet.payload.length;
                                packetReaderReadPackets.set(readPackets);
                                packetReaderReadBytes.set(readBytes);
                                packetReaderDroppedPackets.set(droppedPackets);
                                packetReaderLastPacketElapsedMs.set(SystemClock.elapsedRealtime());
                                packetReaderLastPacketSize.set(packet.payload.length);
                                packetReaderLastPacketPtsUs.set(packet.ptsUs);
                                if (readPackets == 1L || readPackets % 30L == 0L) {
                                    Log.i(LOG_TAG, String.format(
                                        Locale.US,
                                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=packet-read side=%s readPackets=%d readBytes=%d droppedPackets=%d lastPacketSize=%d lastPacketPtsUs=%d packetQueueDepth=%d packetQueueCapacity=%d brokerPort=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                                        side,
                                        readPackets,
                                        readBytes,
                                        droppedPackets,
                                        packet.payload.length,
                                        packet.ptsUs,
                                        packetQueue.size(),
                                        BROKER_PACKET_QUEUE_CAPACITY,
                                        port));
                                }
                            }
                        } catch (EOFException eof) {
                            if (!stopRequested) {
                                packetReaderError.compareAndSet(null, eof);
                            }
                        } catch (IOException ex) {
                            if (!stopRequested) {
                                packetReaderError.compareAndSet(null, ex);
                                Log.w(LOG_TAG, String.format(
                                    Locale.US,
                                    "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=packet-reader-error side=%s readPackets=%d readBytes=%d packetQueueDepth=%d brokerPort=%d reason=%s stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                                    side,
                                    readPackets,
                                    readBytes,
                                    packetQueue.size(),
                                    port,
                                    safeMessage(ex)));
                            }
                        }
                    }
                },
                "RustyQuestBrokerPacketReader-" + side
            );
            packetReaderThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
            packetReaderThread.start();

            BufferInfo info = new BufferInfo();
            BrokerPacket pendingPacket = null;
            BrokerPacket cachedCodecConfigPacket = null;
            long queuedPackets = 0L;
            long queuedBytes = 0L;
            long renderedFrames = 0L;
            long queuedSinceFlush = 0L;
            long renderedSinceFlush = 0L;
            long fallbackPtsUs = 0L;
            long frameDurationUs = 1_000_000L / Math.max(1, fpsCap);
            long firstPacketPtsUs = Long.MIN_VALUE;
            long lastQueuedPtsUs = -1L;
            long decoderFlushes = 0L;
            long decoderRestarts = 0L;
            long remotePumpAttempts = 0L;
            long remotePumpFrames = 0L;
            int remotePumpLastResult = 0;
            long lastProgressLogElapsedMs = SystemClock.elapsedRealtime();
            long lastQueuedElapsedMs = lastProgressLogElapsedMs;
            long lastOutputElapsedMs = lastProgressLogElapsedMs;
            long lastInputBufferAvailableElapsedMs = lastProgressLogElapsedMs;
            long lastStallWarnElapsedMs = 0L;
            long lastInputStallWarnElapsedMs = 0L;
            long lastFlushElapsedMs = 0L;
            while (!stopRequested) {
                int outputIndex;
                do {
                    outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                    if (outputIndex >= 0) {
                        boolean render = info.size != 0;
                        codec.releaseOutputBuffer(outputIndex, render);
                        if (render) {
                            remotePumpLastResult = nativePumpRemoteCameraImage(sideCode);
                            remotePumpAttempts += 1L;
                            if (remotePumpLastResult > 0) {
                                remotePumpFrames += 1L;
                            }
                            renderedFrames += 1L;
                            renderedSinceFlush += 1L;
                            lastOutputElapsedMs = SystemClock.elapsedRealtime();
                            if (renderedFrames == 1L || renderedFrames % 15L == 0L) {
                                Log.i(LOG_TAG, String.format(
                                    Locale.US,
                                    "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=frame side=%s renderedFrames=%d queuedPackets=%d queuedBytes=%d packetPtsUs=%d brokerPort=%d decoderFlushes=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                                    side,
                                    renderedFrames,
                                    queuedPackets,
                                    queuedBytes,
                                    info.presentationTimeUs,
                                    port,
                                    decoderFlushes));
                                nativeRemoteCameraLifecycleEvent(
                                    EVENT_FRAME,
                                    sideCode,
                                    (int) Math.min(renderedFrames, Integer.MAX_VALUE),
                                    streamWidth,
                                    streamHeight,
                                    maxImages,
                                    fpsCap,
                                    port
                                );
                            }
                        }
                    }
                } while (outputIndex >= 0 && !stopRequested);

                BrokerPacket packet = pendingPacket;
                pendingPacket = null;
                if (packet == null) {
                    try {
                        packet = packetQueue.poll(BROKER_PACKET_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                int inputIndex = -1;
                boolean inputBufferRequested = packet != null;
                boolean packetAvailable = packet != null;
                if (packet == null) {
                    IOException readError = packetReaderError.get();
                    if (readError != null && packetQueue.isEmpty()) {
                        throw readError;
                    }
                    remotePumpLastResult = nativePumpRemoteCameraImage(sideCode);
                    remotePumpAttempts += 1L;
                    if (remotePumpLastResult > 0) {
                        remotePumpFrames += 1L;
                    }
                } else {
                    // Poll RMANVID1 before dequeuing MediaCodec input; otherwise
                    // idle socket gaps leak input buffers and freeze the decoder.
                    inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex < 0) {
                        pendingPacket = packet;
                    } else {
                        lastInputBufferAvailableElapsedMs = SystemClock.elapsedRealtime();
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    if (inputBuffer == null) {
                        throw new IOException("decoder input buffer unavailable");
                    }
                    inputBuffer.clear();
                    if (packet.payload.length > inputBuffer.capacity()) {
                        throw new IOException("broker packet exceeds decoder input capacity");
                    }
                    if (isCodecConfigPacket(packet)) {
                        cachedCodecConfigPacket = packet;
                    }
                    inputBuffer.put(packet.payload);
                    long ptsUs = packet.ptsUs;
                    if (ptsUs <= 0L) {
                        fallbackPtsUs += frameDurationUs;
                        ptsUs = fallbackPtsUs;
                    } else {
                        if (firstPacketPtsUs == Long.MIN_VALUE) {
                            firstPacketPtsUs = ptsUs;
                        }
                        ptsUs = Math.max(0L, ptsUs - firstPacketPtsUs);
                    }
                    if (ptsUs <= lastQueuedPtsUs) {
                        ptsUs = lastQueuedPtsUs + frameDurationUs;
                    }
                    lastQueuedPtsUs = ptsUs;
                    codec.queueInputBuffer(inputIndex, 0, packet.payload.length, ptsUs, packet.flags);
                    queuedPackets += 1L;
                    queuedSinceFlush += 1L;
                    queuedBytes += packet.payload.length;
                    lastQueuedElapsedMs = SystemClock.elapsedRealtime();
                    }
                }

                long nowMs = SystemClock.elapsedRealtime();
                long queuedMinusRendered = queuedPackets - renderedFrames;
                long decodeBacklogPackets = queuedSinceFlush - renderedSinceFlush;
                int packetQueueDepth = packetQueue.size();
                long outputIdleMs = nowMs - lastOutputElapsedMs;
                long inputUnavailableMs = inputBufferRequested && inputIndex < 0
                    ? nowMs - lastInputBufferAvailableElapsedMs
                    : 0L;
                if (nowMs - lastProgressLogElapsedMs >= BROKER_PROGRESS_LOG_INTERVAL_MS) {
                    lastProgressLogElapsedMs = nowMs;
                    remotePumpLastResult = nativePumpRemoteCameraImage(sideCode);
                    remotePumpAttempts += 1L;
                    if (remotePumpLastResult > 0) {
                        remotePumpFrames += 1L;
                    }
                    Log.i(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=progress side=%s queuedPackets=%d renderedFrames=%d queuedBytes=%d queuedMinusRendered=%d decodeBacklogPackets=%d packetQueueDepth=%d packetAvailable=%s decoderInputRequested=%s decoderInputAvailable=%s inputUnavailableMs=%d outputIdleMs=%d queuedIdleMs=%d packetReadIdleMs=%d readPackets=%d readBytes=%d readerDroppedPackets=%d lastReadPacketSize=%d lastReadPacketPtsUs=%d packetReaderAlive=%s lastQueuedPtsUs=%d decoderFlushes=%d decoderRestarts=%d remotePumpAttempts=%d remotePumpFrames=%d remotePumpLastResult=%d brokerPort=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        side,
                        queuedPackets,
                        renderedFrames,
                        queuedBytes,
                        queuedMinusRendered,
                        decodeBacklogPackets,
                        packetQueueDepth,
                        packetAvailable,
                        inputBufferRequested,
                        inputBufferRequested && inputIndex >= 0,
                        inputUnavailableMs,
                        outputIdleMs,
                        nowMs - lastQueuedElapsedMs,
                        nowMs - packetReaderLastPacketElapsedMs.get(),
                        packetReaderReadPackets.get(),
                        packetReaderReadBytes.get(),
                        packetReaderDroppedPackets.get(),
                        packetReaderLastPacketSize.get(),
                        packetReaderLastPacketPtsUs.get(),
                        packetReaderThread != null && packetReaderThread.isAlive(),
                        lastQueuedPtsUs,
                        decoderFlushes,
                        decoderRestarts,
                        remotePumpAttempts,
                        remotePumpFrames,
                        remotePumpLastResult,
                        port));
                }
                if (decodeBacklogPackets >= BROKER_STALL_BACKLOG_PACKETS
                    && outputIdleMs >= BROKER_OUTPUT_STALL_WARN_MS
                    && nowMs - lastStallWarnElapsedMs >= BROKER_OUTPUT_STALL_WARN_MS) {
                    lastStallWarnElapsedMs = nowMs;
                    Log.w(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=output-stall side=%s queuedPackets=%d renderedFrames=%d queuedMinusRendered=%d decodeBacklogPackets=%d outputIdleMs=%d queuedIdleMs=%d decoderFlushes=%d decoderRestarts=%d brokerPort=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        side,
                        queuedPackets,
                        renderedFrames,
                        queuedMinusRendered,
                        decodeBacklogPackets,
                        outputIdleMs,
                        nowMs - lastQueuedElapsedMs,
                        decoderFlushes,
                        decoderRestarts,
                        port));
                }
                if (packetQueueDepth >= BROKER_INPUT_STALL_QUEUE_PACKETS
                    && inputUnavailableMs >= BROKER_INPUT_STALL_WARN_MS
                    && nowMs - lastInputStallWarnElapsedMs >= BROKER_INPUT_STALL_WARN_MS) {
                    lastInputStallWarnElapsedMs = nowMs;
                    Log.w(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=input-stall side=%s queuedPackets=%d renderedFrames=%d packetQueueDepth=%d inputUnavailableMs=%d outputIdleMs=%d decoderFlushes=%d decoderRestarts=%d brokerPort=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        side,
                        queuedPackets,
                        renderedFrames,
                        packetQueueDepth,
                        inputUnavailableMs,
                        outputIdleMs,
                        decoderFlushes,
                        decoderRestarts,
                        port));
                }
                boolean outputFlushDue = decodeBacklogPackets >= BROKER_STALL_BACKLOG_PACKETS
                    && outputIdleMs >= BROKER_OUTPUT_STALL_FLUSH_MS;
                boolean inputFlushDue = packetQueueDepth >= BROKER_INPUT_STALL_QUEUE_PACKETS
                    && inputUnavailableMs >= BROKER_INPUT_STALL_FLUSH_MS;
                boolean inputStarvationDue = inputIndex < 0
                    && inputUnavailableMs >= BROKER_INPUT_STARVATION_RECREATE_MS
                    && outputIdleMs >= BROKER_OUTPUT_STALL_FLUSH_MS
                    && queuedMinusRendered > 0L;
                if ((outputFlushDue || inputFlushDue || inputStarvationDue)
                    && nowMs - lastFlushElapsedMs >= BROKER_OUTPUT_STALL_FLUSH_MS) {
                    String flushReason = inputStarvationDue
                        ? "input-starvation"
                        : (inputFlushDue ? "input-stall" : "output-stall");
                    int droppedQueuedPackets = packetQueueDepth;
                    if (pendingPacket != null) {
                        droppedQueuedPackets += 1;
                        pendingPacket = null;
                    }
                    decoderFlushes += 1L;
                    Log.w(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=decoder-flush side=%s flushReason=%s queuedPackets=%d renderedFrames=%d queuedMinusRendered=%d decodeBacklogPackets=%d droppedQueuedPackets=%d inputUnavailableMs=%d outputIdleMs=%d decoderFlushes=%d decoderRestarts=%d recoveryAction=codec-flush brokerPort=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        side,
                        flushReason,
                        queuedPackets,
                        renderedFrames,
                        queuedMinusRendered,
                        decodeBacklogPackets,
                        droppedQueuedPackets,
                        inputUnavailableMs,
                        outputIdleMs,
                        decoderFlushes,
                        decoderRestarts,
                        port));
                    packetQueue.clear();
                    MediaCodec oldCodec = codec;
                    codec = null;
                    releaseBrokerDecoder(oldCodec);
                    codec = createBrokerDecoder(surface, streamWidth, streamHeight);
                    decoderRestarts += 1L;
                    queuedSinceFlush = 0L;
                    renderedSinceFlush = 0L;
                    fallbackPtsUs = 0L;
                    firstPacketPtsUs = Long.MIN_VALUE;
                    lastQueuedPtsUs = -1L;
                    long restartedAtMs = SystemClock.elapsedRealtime();
                    lastFlushElapsedMs = restartedAtMs;
                    lastOutputElapsedMs = restartedAtMs;
                    lastInputBufferAvailableElapsedMs = restartedAtMs;
                    lastQueuedElapsedMs = restartedAtMs;
                    Log.w(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=decoder-restart side=%s restartReason=%s queuedPackets=%d renderedFrames=%d queuedMinusRendered=%d decodeBacklogPackets=%d droppedQueuedPackets=%d inputUnavailableMs=%d outputIdleMs=%d decoderFlushes=%d decoderRestarts=%d recoveryAction=%s brokerPort=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                        side,
                        flushReason,
                        queuedPackets,
                        renderedFrames,
                        queuedMinusRendered,
                        decodeBacklogPackets,
                        droppedQueuedPackets,
                        inputUnavailableMs,
                        outputIdleMs,
                        decoderFlushes,
                        decoderRestarts,
                        cachedCodecConfigPacket != null ? "recreate-codec-cached-config-held" : "recreate-codec",
                        port));
                }
            }
        } catch (EOFException eof) {
            if (!stopRequested) {
                throw eof;
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            joinThread(packetReaderThread);
            releaseBrokerDecoder(codec);
        }
    }

    private static MediaCodec createBrokerDecoder(Surface surface, int streamWidth, int streamHeight)
        throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PACKET_BYTES);
        BrokerDecoderChoice decoderChoice = chooseBrokerDecoder(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodec codec = decoderChoice.name == null
            ? MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            : MediaCodec.createByCodecName(decoderChoice.name);
        try {
            codec.configure(format, surface, null, 0);
            codec.start();
            Log.i(LOG_TAG, String.format(
                Locale.US,
                "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=decoder-created decoderName=%s decoderSoftware=%s decoderSelection=%s streamWidth=%d streamHeight=%d stream=remote_camera_broker_stereo sourceAuthority=manifold-broker-rmanvid1-camera2-h264",
                decoderChoice.name == null ? "createDecoderByType" : decoderChoice.name,
                decoderChoice.software,
                decoderChoice.selection,
                streamWidth,
                streamHeight));
            return codec;
        } catch (RuntimeException error) {
            releaseBrokerDecoder(codec);
            throw error;
        }
    }

    private static void releaseBrokerDecoder(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            codec.release();
        } catch (RuntimeException ignored) {
        }
    }

    private static BrokerDecoderChoice chooseBrokerDecoder(String mimeType) {
        BrokerDecoderChoice fallback = null;
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (info.isEncoder() || !supportsMimeType(info, mimeType)) {
                    continue;
                }
                String name = info.getName();
                boolean software = isSoftwareDecoder(info, name);
                if (software) {
                    return new BrokerDecoderChoice(name, true, "software-preferred");
                }
                if (fallback == null) {
                    fallback = new BrokerDecoderChoice(name, false, "hardware-fallback");
                }
            }
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, String.format(
                Locale.US,
                "RUSTY_QUEST_NATIVE_RENDERER channel=remote-camera-broker-inlet status=decoder-list-error decoderSelection=type-fallback reason=%s stream=remote_camera_broker_stereo",
                safeMessage(error)));
        }
        return fallback != null ? fallback : new BrokerDecoderChoice(null, false, "type-fallback");
    }

    private static boolean supportsMimeType(MediaCodecInfo info, String mimeType) {
        for (String supportedType : info.getSupportedTypes()) {
            if (supportedType.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSoftwareDecoder(MediaCodecInfo info, String name) {
        try {
            if (info.isSoftwareOnly()) {
                return true;
            }
        } catch (NoSuchMethodError ignored) {
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.startsWith("c2.android.")
            || lower.startsWith("omx.google.")
            || lower.contains("google")
            || lower.contains("software");
    }

    private static BrokerStreamHeader readBrokerHeader(DataInputStream input) throws IOException {
        byte[] magicBytes = new byte[8];
        input.readFully(magicBytes);
        String magic = new String(magicBytes, StandardCharsets.US_ASCII);
        if (!STREAM_MAGIC.equals(magic) && !LEGACY_STREAM_MAGIC.equals(magic)) {
            throw new IOException("unexpected broker stream magic " + magic);
        }
        int schemaVersion = input.readInt();
        int codecId = input.readInt();
        int width = input.readInt();
        int height = input.readInt();
        int packetCount = input.readInt();
        int metadataBytes = input.readInt();
        if (schemaVersion < 1 || schemaVersion > 3) {
            throw new IOException("unsupported broker stream schema " + schemaVersion);
        }
        if (packetCount < 0 || packetCount > MAX_STREAM_PACKETS) {
            throw new IOException("broker packet count out of range " + packetCount);
        }
        if (metadataBytes < 0 || metadataBytes > MAX_STREAM_HEADER_METADATA_BYTES) {
            throw new IOException("broker metadata bytes out of range " + metadataBytes);
        }
        if (metadataBytes > 0) {
            byte[] metadata = new byte[metadataBytes];
            input.readFully(metadata);
        }
        return new BrokerStreamHeader(
            magic,
            schemaVersion,
            codecId,
            width,
            height,
            packetCount,
            metadataBytes,
            STREAM_MAGIC.equals(magic) || schemaVersion >= 2
        );
    }

    private static BrokerPacket readBrokerPacket(DataInputStream input, BrokerStreamHeader header)
        throws IOException {
        long ptsUs = input.readLong();
        int flags = input.readInt();
        int size = input.readInt();
        if (size < 0 || size > MAX_PACKET_BYTES) {
            throw new IOException("broker packet size out of range " + size);
        }
        long sourceElapsedNs = 0L;
        long sourceUnixNs = 0L;
        if (header.extendedPacketTimestamps) {
            sourceElapsedNs = input.readLong();
            sourceUnixNs = input.readLong();
        }
        byte[] payload = new byte[size];
        input.readFully(payload);
        return new BrokerPacket(ptsUs, flags, sourceElapsedNs, sourceUnixNs, payload);
    }

    private static boolean isCodecConfigPacket(BrokerPacket packet) {
        return (packet.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
    }

    private static long estimateFrameDurationUs(MediaFormat format, int fpsCap) {
        int frameRate = fpsCap;
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            } catch (ClassCastException ignored) {
                frameRate = fpsCap;
            }
        }
        return 1_000_000L / clamp(frameRate, 1, 240);
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return index;
            }
        }
        return -1;
    }

    private static void paceToPresentationTime(
        long firstFrameReleaseNs,
        long firstPresentationUs,
        long presentationUs
    ) {
        long targetNs = firstFrameReleaseNs + (presentationUs - firstPresentationUs) * 1000L;
        long delayMs = (targetNs - System.nanoTime()) / 1_000_000L;
        if (delayMs > 1L) {
            SystemClock.sleep(Math.min(delayMs, 40L));
        }
    }

    private static String resolvePath(Context context, String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty()) {
            trimmed = "video/noodletest-sbs.mp4";
        }
        File file = new File(trimmed);
        if (file.isAbsolute()) {
            return file.getAbsolutePath();
        }
        return new File(context.getFilesDir(), trimmed).getAbsolutePath();
    }

    private static String normalizeSource(String source) {
        String trimmed = source == null ? "" : source.trim().toLowerCase(Locale.US);
        if (SOURCE_BROKER_RMANVID1.equals(trimmed) || "rmanvid1".equals(trimmed)) {
            return SOURCE_BROKER_RMANVID1;
        }
        return SOURCE_APP_PRIVATE_FILE;
    }

    private static String normalizeBrokerHost(String host) {
        String trimmed = host == null ? "" : host.trim();
        return trimmed.isEmpty() ? "127.0.0.1" : trimmed;
    }

    private static int normalizeBrokerPort(int port) {
        if (port <= 0) {
            return 0;
        }
        return clamp(port, 1, 65535);
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message != null ? message : ex.getClass().getSimpleName();
    }

    private static int clamp(int value, int minValue, int maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static final class BrokerStreamHeader {
        final String magic;
        final int schemaVersion;
        final int codecId;
        final int width;
        final int height;
        final int packetCount;
        final int metadataBytes;
        final boolean extendedPacketTimestamps;

        BrokerStreamHeader(
            String magic,
            int schemaVersion,
            int codecId,
            int width,
            int height,
            int packetCount,
            int metadataBytes,
            boolean extendedPacketTimestamps
        ) {
            this.magic = magic;
            this.schemaVersion = schemaVersion;
            this.codecId = codecId;
            this.width = width;
            this.height = height;
            this.packetCount = packetCount;
            this.metadataBytes = metadataBytes;
            this.extendedPacketTimestamps = extendedPacketTimestamps;
        }
    }

    private static final class BrokerPacket {
        final long ptsUs;
        final int flags;
        final long sourceElapsedNs;
        final long sourceUnixNs;
        final byte[] payload;

        BrokerPacket(long ptsUs, int flags, long sourceElapsedNs, long sourceUnixNs, byte[] payload) {
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.sourceElapsedNs = sourceElapsedNs;
            this.sourceUnixNs = sourceUnixNs;
            this.payload = payload;
        }
    }

    private static final class BrokerDecoderChoice {
        final String name;
        final boolean software;
        final String selection;

        BrokerDecoderChoice(String name, boolean software, String selection) {
            this.name = name;
            this.software = software;
            this.selection = selection;
        }
    }

    private static native Surface nativeCreateStereoVideoSurface(
        int width,
        int height,
        int maxImages,
        int fpsCap
    );

    private static native void nativeStopStereoVideoStream();

    private static native Surface nativeCreateRemoteCameraSurface(
        int sideCode,
        int width,
        int height,
        int maxImages,
        int fpsCap
    );

    private static native void nativeStopRemoteCameraStream();

    private static native int nativePumpRemoteCameraImage(int sideCode);

    private static native void nativeStereoVideoLifecycleEvent(
        int eventCode,
        int resultCode,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        int looping
    );

    private static native void nativeRemoteCameraLifecycleEvent(
        int eventCode,
        int sideCode,
        int resultCode,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        int port
    );
}
