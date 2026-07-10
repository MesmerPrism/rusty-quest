package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One-source-owner packed stereo Camera2/GLES/MediaCodec RMANVID v4 runtime. */
final class RemoteCameraPackedStereoSourceRuntime {
    private static final String STREAM_MAGIC = "RMANVID1";
    private static final String MIME_H264 = "video/avc";
    private static final int CODEC_H264 = 1;
    private static final int ENCODER_DRAIN_TIMEOUT_US = 10_000;
    private static final int CAMERA_OPEN_TIMEOUT_MS = 5_000;
    private static final int CAMERA_SESSION_TIMEOUT_MS = 5_000;
    private static final long SYNC_FRAME_INTERVAL_MS = 1_000L;
    private static final Object LOCK = new Object();
    private static final Map<String, Runtime> RUNTIMES = new LinkedHashMap<>();
    private static final AtomicLong NEXT_RUNTIME_ID = new AtomicLong(1L);

    private RemoteCameraPackedStereoSourceRuntime() {
    }

    static JSONObject ensureStarted(
            Context context,
            String sessionId,
            String sourceKind,
            String sourceHost,
            String sourcePorts,
            String mediaProfiles,
            String cameraIds,
            String mediaLayout,
            String frameLayout) throws Exception {
        RemoteCameraPackedStreamMetadata.Layout layout =
                RemoteCameraPackedStreamMetadata.Layout.parse(mediaLayout, frameLayout);
        PortProfile profile = PortProfile.parse(sourcePorts, mediaProfiles);
        if (profile.width != layout.packedWidth || profile.height != layout.packedHeight) {
            throw new IllegalArgumentException("packed media profile and frame layout disagree");
        }
        Map<String, String> cameras = parseCameraIds(cameraIds);
        String leftCameraId = cameras.get("left");
        String rightCameraId = cameras.get("right");
        boolean synthetic = "diagnostic_synthetic_mediacodec_surface".equals(sourceKind);
        if (!synthetic
                && (clean(leftCameraId).isEmpty()
                        || clean(rightCameraId).isEmpty()
                        || leftCameraId.equals(rightCameraId))) {
            throw new IllegalArgumentException("packed source requires distinct left/right camera ids");
        }
        if (synthetic) {
            leftCameraId = "synthetic-left";
            rightCameraId = "synthetic-right";
        }
        String key = sessionId + "|packed|" + sourceHost + "|" + profile.port;
        synchronized (LOCK) {
            Runtime existing = RUNTIMES.get(key);
            if (existing != null && !existing.terminal) {
                return existing.toJson();
            }
        }
        Runtime runtime = new Runtime(
                key,
                sessionId,
                sourceKind,
                context == null ? null : context.getApplicationContext(),
                sourceHost,
                profile,
                layout,
                leftCameraId,
                rightCameraId,
                synthetic);
        runtime.start();
        synchronized (LOCK) {
            RUNTIMES.put(key, runtime);
        }
        return runtime.toJson();
    }

    static JSONObject stop(String sessionId, String reason) throws Exception {
        JSONArray stopped = new JSONArray();
        synchronized (LOCK) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, Runtime> entry : RUNTIMES.entrySet()) {
                if (entry.getValue().sessionId.equals(sessionId)) {
                    keys.add(entry.getKey());
                }
            }
            for (String key : keys) {
                Runtime runtime = RUNTIMES.remove(key);
                if (runtime != null) {
                    runtime.stop(reason);
                    stopped.put(runtime.toJson());
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("session_id", sessionId);
        result.put("stopped_count", stopped.length());
        result.put("sources", stopped);
        result.put("high_rate_json_payload", false);
        return result;
    }

    static JSONObject statusForSession(String sessionId) throws Exception {
        JSONArray sources = new JSONArray();
        synchronized (LOCK) {
            for (Runtime runtime : RUNTIMES.values()) {
                if (runtime.sessionId.equals(sessionId)) {
                    sources.put(runtime.toJson());
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("session_id", sessionId);
        result.put("source_count", sources.length());
        result.put("sources", sources);
        result.put("high_rate_json_payload", false);
        return result;
    }

    private static final class Runtime implements RemoteCameraStereoGlCompositor.Listener {
        final String key;
        final String runtimeId;
        final String sessionId;
        final String sourceKind;
        final Context context;
        final String sourceHost;
        final PortProfile profile;
        final RemoteCameraPackedStreamMetadata.Layout layout;
        final String leftCameraId;
        final String rightCameraId;
        final boolean synthetic;
        final Object outputLock = new Object();
        final Object pairLock = new Object();
        final TreeMap<Long, RemoteCameraPackedStreamMetadata.PairRecord> presentedPairs =
                new TreeMap<>();

        volatile String state = "created";
        volatile String closeReason = "";
        volatile String error = "";
        volatile boolean stopRequested;
        volatile boolean terminal;
        volatile Thread sourceThread;
        volatile Thread acceptThread;
        volatile ServerSocket serverSocket;
        volatile Socket socket;
        volatile OutputStream output;
        volatile MediaCodec encoder;
        volatile RemoteCameraStereoGlCompositor compositor;
        volatile CameraEndpoint leftCamera;
        volatile CameraEndpoint rightCamera;
        volatile byte[] cachedCodecConfig;
        volatile long cachedCodecConfigPtsUs;
        volatile int cachedCodecConfigFlags;
        volatile long consumerAcceptCount;
        volatile long consumerReconnectCount;
        volatile long headerWriteCount;
        volatile long bytesWritten;
        volatile long packetCount;
        volatile long videoPacketCount;
        volatile long codecConfigPacketCount;
        volatile long keyframeCount;
        volatile long encodedFrames;
        volatile long encodedPacketsWithoutPair;
        volatile long pairMetadataWriteCount;
        volatile long firstPacketElapsedMs = -1L;
        volatile long lastPacketElapsedMs = -1L;
        volatile long lastPacketUnixMs = -1L;

        Runtime(
                String key,
                String sessionId,
                String sourceKind,
                Context context,
                String sourceHost,
                PortProfile profile,
                RemoteCameraPackedStreamMetadata.Layout layout,
                String leftCameraId,
                String rightCameraId,
                boolean synthetic) {
            this.key = key;
            this.runtimeId = "packed-source-" + NEXT_RUNTIME_ID.getAndIncrement();
            this.sessionId = sessionId;
            this.sourceKind = sourceKind;
            this.context = context;
            this.sourceHost = sourceHost;
            this.profile = profile;
            this.layout = layout;
            this.leftCameraId = leftCameraId;
            this.rightCameraId = rightCameraId;
            this.synthetic = synthetic;
        }

        void start() throws Exception {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName(sourceHost), profile.port));
            serverSocket = server;
            state = "source_socket_bound";
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "rusty-packed-source-accept");
            acceptThread.start();
            sourceThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    sourceLoop();
                }
            }, "rusty-packed-source-runtime");
            sourceThread.start();
        }

        @Override
        public void onPairPresented(RemoteCameraStereoFramePairer.Pair pair, long presentationTimeUs) {
            synchronized (pairLock) {
                presentedPairs.put(
                        presentationTimeUs,
                        RemoteCameraPackedStreamMetadata.PairRecord.fromPair(pair));
                while (presentedPairs.size() > 64) {
                    presentedPairs.pollFirstEntry();
                    encodedPacketsWithoutPair++;
                }
            }
        }

        @Override
        public void onCompositorFailure(Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + safeMessage(failure);
            closeReason = "gpu_compositor_failure";
            state = "failed";
            stopRequested = true;
        }

        void sourceLoop() {
            Surface encoderSurface = null;
            HandlerThread cameraThread = null;
            try {
                encoder = MediaCodec.createEncoderByType(MIME_H264);
                MediaFormat format = MediaFormat.createVideoFormat(
                        MIME_H264,
                        layout.packedWidth,
                        layout.packedHeight);
                format.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, profile.frameRateHz);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoderSurface = encoder.createInputSurface();
                encoder.start();
                requestSyncFrame(encoder);
                compositor = new RemoteCameraStereoGlCompositor(layout, encoderSurface, synthetic, this);
                if (!synthetic) {
                    if (context == null) {
                        throw new IllegalStateException("Android context is unavailable");
                    }
                    cameraThread = new HandlerThread("rusty-packed-camera2");
                    cameraThread.start();
                    Handler handler = new Handler(cameraThread.getLooper());
                    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    if (manager == null) {
                        throw new IllegalStateException("CameraManager is unavailable");
                    }
                    leftCamera = CameraEndpoint.open(
                            manager,
                            leftCameraId,
                            RemoteCameraStereoFramePairer.LEFT,
                            compositor.leftCameraSurface(),
                            layout.perEyeWidth,
                            layout.perEyeHeight,
                            profile.frameRateHz,
                            handler,
                            compositor);
                    rightCamera = CameraEndpoint.open(
                            manager,
                            rightCameraId,
                            RemoteCameraStereoFramePairer.RIGHT,
                            compositor.rightCameraSurface(),
                            layout.perEyeWidth,
                            layout.perEyeHeight,
                            profile.frameRateHz,
                            handler,
                            compositor);
                }
                state = synthetic
                        ? "source_streaming_packed_synthetic"
                        : "source_streaming_packed_camera2";
                long nextSyntheticNs = SystemClock.elapsedRealtimeNanos();
                long frameDurationNs = 1_000_000_000L / Math.max(1, profile.frameRateHz);
                long syntheticFrame = 1L;
                long nextSyncMs = SystemClock.elapsedRealtime() + SYNC_FRAME_INTERVAL_MS;
                while (!stopRequested) {
                    if (synthetic && SystemClock.elapsedRealtimeNanos() >= nextSyntheticNs) {
                        long leftTimestamp = nextSyntheticNs;
                        compositor.requestSyntheticFrame(
                                syntheticFrame++,
                                leftTimestamp,
                                leftTimestamp + 1_000_000L);
                        nextSyntheticNs += frameDurationNs;
                    }
                    if (SystemClock.elapsedRealtime() >= nextSyncMs) {
                        requestSyncFrame(encoder);
                        nextSyncMs = SystemClock.elapsedRealtime() + SYNC_FRAME_INTERVAL_MS;
                    }
                    drainEncoder(false);
                    Thread.sleep(2L);
                }
                try {
                    encoder.signalEndOfInputStream();
                    drainEncoder(true);
                } catch (Exception ignored) {
                }
                if (!"failed".equals(state)) {
                    state = "stopped";
                    closeReason = closeReason.isEmpty() ? "stop_requested" : closeReason;
                }
            } catch (Throwable failure) {
                if (!stopRequested) {
                    error = failure.getClass().getSimpleName() + ": " + safeMessage(failure);
                    closeReason = "exception";
                    state = "failed";
                }
            } finally {
                closeQuietly(leftCamera);
                closeQuietly(rightCamera);
                if (cameraThread != null) {
                    cameraThread.quitSafely();
                }
                closeQuietly(compositor);
                if (encoder != null) {
                    try {
                        encoder.stop();
                    } catch (Exception ignored) {
                    }
                    encoder.release();
                    encoder = null;
                }
                if (encoderSurface != null) {
                    encoderSurface.release();
                }
                terminal = true;
            }
        }

        void drainEncoder(boolean eos) throws Exception {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int emptyPolls = 0;
            while (!stopRequested || eos) {
                int status = encoder.dequeueOutputBuffer(info, ENCODER_DRAIN_TIMEOUT_US);
                if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!eos || emptyPolls++ > 50) {
                        break;
                    }
                    continue;
                }
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || status < 0) {
                    continue;
                }
                ByteBuffer buffer = encoder.getOutputBuffer(status);
                if (buffer != null && info.size > 0) {
                    byte[] payload = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(payload);
                    boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    RemoteCameraPackedStreamMetadata.PairRecord pair = codecConfig
                            ? RemoteCameraPackedStreamMetadata.PairRecord.CODEC_CONFIG
                            : takePair(info.presentationTimeUs);
                    if (pair == null) {
                        encodedPacketsWithoutPair++;
                    } else {
                        pair.validate(codecConfig, layout.maxPairDeltaNs);
                        if (codecConfig) {
                            cachedCodecConfig = payload.clone();
                            cachedCodecConfigPtsUs = info.presentationTimeUs;
                            cachedCodecConfigFlags = info.flags;
                        }
                        writePacket(
                                info.presentationTimeUs,
                                info.flags,
                                pair,
                                payload,
                                SystemClock.elapsedRealtimeNanos(),
                                System.currentTimeMillis() * 1_000_000L);
                        if (!codecConfig) {
                            encodedFrames++;
                        }
                    }
                }
                boolean outputEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(status, false);
                if (outputEos) {
                    break;
                }
            }
        }

        RemoteCameraPackedStreamMetadata.PairRecord takePair(long presentationTimeUs) {
            synchronized (pairLock) {
                RemoteCameraPackedStreamMetadata.PairRecord exact = presentedPairs.remove(presentationTimeUs);
                if (exact != null) {
                    return exact;
                }
                Map.Entry<Long, RemoteCameraPackedStreamMetadata.PairRecord> floor =
                        presentedPairs.floorEntry(presentationTimeUs);
                Map.Entry<Long, RemoteCameraPackedStreamMetadata.PairRecord> ceiling =
                        presentedPairs.ceilingEntry(presentationTimeUs);
                Map.Entry<Long, RemoteCameraPackedStreamMetadata.PairRecord> best = floor;
                if (best == null
                        || (ceiling != null
                                && Math.abs(ceiling.getKey() - presentationTimeUs)
                                        < Math.abs(best.getKey() - presentationTimeUs))) {
                    best = ceiling;
                }
                if (best == null || Math.abs(best.getKey() - presentationTimeUs) > 2L) {
                    return null;
                }
                presentedPairs.remove(best.getKey());
                return best.getValue();
            }
        }

        void acceptLoop() {
            while (!stopRequested) {
                Socket client = null;
                try {
                    client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    OutputStream clientOutput = client.getOutputStream();
                    writeHeader(clientOutput);
                    consumerAcceptCount++;
                    consumerReconnectCount = Math.max(0L, consumerAcceptCount - 1L);
                    synchronized (outputLock) {
                        closeQuietly(output);
                        closeQuietly(socket);
                        socket = client;
                        output = clientOutput;
                        replayCodecConfig(clientOutput);
                    }
                    requestSyncFrame(encoder);
                    while (!stopRequested && socket == client && !client.isClosed()) {
                        Thread.sleep(25L);
                    }
                } catch (Throwable failure) {
                    if (!stopRequested) {
                        error = failure.getClass().getSimpleName() + ": " + safeMessage(failure);
                    }
                } finally {
                    synchronized (outputLock) {
                        if (socket == client) {
                            closeQuietly(output);
                            closeQuietly(socket);
                            output = null;
                            socket = null;
                        } else {
                            closeQuietly(client);
                        }
                    }
                }
            }
        }

        void writeHeader(OutputStream target) throws Exception {
            byte[] metadata = layout
                    .toHeaderJson(leftCameraId, rightCameraId, sourceKind)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            target.write(STREAM_MAGIC.getBytes(StandardCharsets.US_ASCII));
            writeU32(target, RemoteCameraPackedStreamMetadata.RMANVID_SCHEMA_VERSION);
            writeU32(target, CODEC_H264);
            writeU32(target, layout.packedWidth);
            writeU32(target, layout.packedHeight);
            writeU32(target, 0);
            writeU32(target, metadata.length);
            target.write(metadata);
            target.flush();
            headerWriteCount++;
            bytesWritten += 32L + metadata.length;
        }

        void replayCodecConfig(OutputStream target) throws Exception {
            byte[] config = cachedCodecConfig;
            if (config != null) {
                writePacketTo(
                        target,
                        cachedCodecConfigPtsUs,
                        cachedCodecConfigFlags,
                        RemoteCameraPackedStreamMetadata.PairRecord.CODEC_CONFIG,
                        config,
                        SystemClock.elapsedRealtimeNanos(),
                        System.currentTimeMillis() * 1_000_000L);
            }
        }

        void writePacket(
                long ptsUs,
                int flags,
                RemoteCameraPackedStreamMetadata.PairRecord pair,
                byte[] payload,
                long sourceElapsedNs,
                long sourceUnixNs) {
            synchronized (outputLock) {
                if (output == null) {
                    return;
                }
                try {
                    writePacketTo(output, ptsUs, flags, pair, payload, sourceElapsedNs, sourceUnixNs);
                } catch (Throwable failure) {
                    error = failure.getClass().getSimpleName() + ": " + safeMessage(failure);
                    closeQuietly(output);
                    closeQuietly(socket);
                    output = null;
                    socket = null;
                }
            }
        }

        void writePacketTo(
                OutputStream target,
                long ptsUs,
                int flags,
                RemoteCameraPackedStreamMetadata.PairRecord pair,
                byte[] payload,
                long sourceElapsedNs,
                long sourceUnixNs) throws Exception {
            writeU64(target, ptsUs);
            writeU32(target, flags);
            writeU32(target, payload.length);
            writeU64(target, sourceElapsedNs);
            writeU64(target, sourceUnixNs);
            pair.write(target);
            target.write(payload);
            target.flush();
            long bytes = 32L + RemoteCameraPackedStreamMetadata.PAIR_EXTENSION_BYTES + payload.length;
            bytesWritten += bytes;
            packetCount++;
            pairMetadataWriteCount++;
            long nowMs = SystemClock.elapsedRealtime();
            if (firstPacketElapsedMs < 0L) {
                firstPacketElapsedMs = nowMs;
            }
            lastPacketElapsedMs = nowMs;
            lastPacketUnixMs = System.currentTimeMillis();
            if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codecConfigPacketCount++;
            } else {
                videoPacketCount++;
            }
            if ((flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                keyframeCount++;
            }
        }

        void stop(String reason) {
            stopRequested = true;
            closeReason = reason;
            state = "stopping";
            closeQuietly(serverSocket);
            synchronized (outputLock) {
                closeQuietly(output);
                closeQuietly(socket);
                output = null;
                socket = null;
            }
            closeQuietly(leftCamera);
            closeQuietly(rightCamera);
            closeQuietly(compositor);
            if (sourceThread != null) {
                sourceThread.interrupt();
            }
            if (acceptThread != null) {
                acceptThread.interrupt();
            }
            join(sourceThread);
            join(acceptThread);
            terminal = true;
            if (!"failed".equals(state)) {
                state = "stopped";
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("schema", "rusty.quest.remote_camera.android_sender_source.v1");
            json.put("session_id", sessionId);
            json.put("source_group_id", runtimeId);
            json.put("source_kind", sourceKind);
            json.put("state", state);
            json.put("lane_state", output != null ? "source_streaming" : "waiting_for_source_consumer");
            json.put("eye", "stereo");
            json.put("camera_id", leftCameraId + "," + rightCameraId);
            json.put("connected", output != null);
            json.put("source_available", !"failed".equals(state));
            json.put("runtime_started", true);
            json.put("packed_stereo_enabled", true);
            json.put("packed_stereo_layout", RemoteCameraPackedStreamMetadata.FRAME_LAYOUT);
            json.put("packed_width", layout.packedWidth);
            json.put("packed_height", layout.packedHeight);
            json.put("per_eye_width", layout.perEyeWidth);
            json.put("per_eye_height", layout.perEyeHeight);
            json.put("left_camera_id", leftCameraId);
            json.put("right_camera_id", rightCameraId);
            json.put("pair_timestamp_authority", RemoteCameraPackedStreamMetadata.TIMESTAMP_AUTHORITY);
            json.put("pairing_policy", RemoteCameraPackedStreamMetadata.PAIRING_POLICY);
            json.put("pair_delta_bound_ns", layout.maxPairDeltaNs);
            json.put("gpu_compositor_active", compositor != null && compositor.gpuCompositorActive());
            json.put("cpu_pixel_copy", false);
            json.put("encoder_instance_count", encoder != null ? 1 : 0);
            json.put("encoded_frames", encodedFrames);
            json.put("video_packet_count", videoPacketCount);
            json.put("last_packet_age_ms", lastPacketElapsedMs < 0L
                    ? -1L
                    : Math.max(0L, SystemClock.elapsedRealtime() - lastPacketElapsedMs));
            json.put("encoded_packets_without_pair", encodedPacketsWithoutPair);
            json.put("pair_metadata_write_count", pairMetadataWriteCount);
            json.put("connected_output_count", output != null ? 1 : 0);
            json.put("media_payload_plane", "binary-media");
            json.put("high_rate_json_payload", false);
            RemoteCameraStereoFramePairer.Snapshot pairs = compositor != null
                    ? compositor.pairerSnapshot()
                    : new RemoteCameraStereoFramePairer(1, layout.maxPairDeltaNs).snapshot();
            json.put("pairs_accepted", pairs.acceptedPairs);
            json.put("pair_delta_p50_ns", pairs.pairDeltaP50Ns);
            json.put("pair_delta_p95_ns", pairs.pairDeltaP95Ns);
            json.put("pair_delta_max_ns", pairs.pairDeltaMaxNs);
            json.put("pair_wait_p50_ns", pairs.pairWaitP50Ns);
            json.put("pair_wait_p95_ns", pairs.pairWaitP95Ns);
            json.put("pair_wait_max_ns", pairs.pairWaitMaxNs);
            json.put("left_frames_dropped_unmatched", pairs.leftFramesDroppedUnmatched);
            json.put("right_frames_dropped_unmatched", pairs.rightFramesDroppedUnmatched);
            json.put("pair_skew_rejected", pairs.skewRejected);
            json.put("pair_queue_overflow_drops", pairs.queueOverflowDrops);
            json.put("stale_eye_reuse_count", pairs.staleEyeReuseCount);
            json.put("pair_queue_depth_left", pairs.queueDepthLeft);
            json.put("pair_queue_depth_right", pairs.queueDepthRight);
            json.put("pair_queue_max_depth_left", pairs.queueMaxDepthLeft);
            json.put("pair_queue_max_depth_right", pairs.queueMaxDepthRight);
            if (compositor != null) {
                json.put("composed_frames", compositor.composedFrames());
                json.put("synthetic_frames", compositor.syntheticFrames());
                json.put("left_surface_frames", compositor.leftSurfaceFrames());
                json.put("right_surface_frames", compositor.rightSurfaceFrames());
                json.put("left_uncorrelated_frames", compositor.leftUncorrelatedFrames());
                json.put("right_uncorrelated_frames", compositor.rightUncorrelatedFrames());
                json.put("compositor_time_average_ns", compositor.compositorTimeAverageNs());
                json.put("compositor_time_max_ns", compositor.compositorTimeMaxNs());
            }
            if (leftCamera != null) {
                json.put("left_camera_capture", leftCamera.toJson());
            }
            if (rightCamera != null) {
                json.put("right_camera_capture", rightCamera.toJson());
            }
            if (leftCamera != null && rightCamera != null) {
                json.put(
                        "camera2_capture_freshness",
                        CameraEndpoint.combinedFreshness(leftCamera, rightCamera));
            }
            JSONArray lanes = new JSONArray();
            JSONObject lane = new JSONObject();
            lane.put("eye", "stereo");
            lane.put("port", profile.port);
            lane.put("state", output != null ? "source_streaming" : "waiting_for_source_consumer");
            lane.put("connected", output != null);
            lane.put("header_write_count", headerWriteCount);
            lane.put("consumer_accept_count", consumerAcceptCount);
            lane.put("consumer_reconnect_count", consumerReconnectCount);
            lane.put("bytes_written", bytesWritten);
            lane.put("packet_count", packetCount);
            lane.put("video_packet_count", videoPacketCount);
            lane.put("codec_config_packet_count", codecConfigPacketCount);
            lane.put("keyframe_count", keyframeCount);
            lane.put("first_packet_elapsed_ms", firstPacketElapsedMs);
            lane.put("last_packet_elapsed_ms", lastPacketElapsedMs);
            lane.put("last_packet_unix_ms", lastPacketUnixMs);
            lane.put("media_payload_plane", "binary-media");
            lane.put("high_rate_json_payload", false);
            lanes.put(lane);
            json.put("source_lanes", lanes);
            if (!closeReason.isEmpty()) {
                json.put("close_reason", closeReason);
            }
            if (!error.isEmpty()) {
                json.put("error", error);
            }
            return json;
        }
    }

    private static final class CameraEndpoint implements Closeable {
        final String eye;
        final String cameraId;
        final int outputWidth;
        final int outputHeight;
        final CameraDevice device;
        final CameraCaptureSession session;
        volatile long captureCount;
        volatile long firstFrame = -1L;
        volatile long lastFrame = -1L;
        volatile long firstSensorTimestampNs = -1L;
        volatile long lastSensorTimestampNs = -1L;
        volatile long lastCaptureElapsedMs = -1L;

        CameraEndpoint(
                String eye,
                String cameraId,
                int outputWidth,
                int outputHeight,
                CameraDevice device,
                CameraCaptureSession session) {
            this.eye = eye;
            this.cameraId = cameraId;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.device = device;
            this.session = session;
        }

        static CameraEndpoint open(
                CameraManager manager,
                String cameraId,
                String eye,
                Surface surface,
                int outputWidth,
                int outputHeight,
                int frameRate,
                Handler handler,
                RemoteCameraStereoGlCompositor compositor) throws Exception {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            requireExactSurfaceTextureOutputSize(
                    characteristics,
                    cameraId,
                    outputWidth,
                    outputHeight);
            CameraDevice device = openDevice(manager, cameraId, handler);
            CameraCaptureSession session = createSession(device, surface, handler);
            CameraEndpoint endpoint = new CameraEndpoint(
                    eye,
                    cameraId,
                    outputWidth,
                    outputHeight,
                    device,
                    session);
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(surface);
            Range<Integer> fps = chooseFpsRange(characteristics, frameRate);
            if (fps != null) {
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            }
            session.setRepeatingRequest(
                    request.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(
                                CameraCaptureSession captureSession,
                                CaptureRequest captureRequest,
                                TotalCaptureResult result) {
                            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                            if (timestamp == null) {
                                return;
                            }
                            long sourceFrame = result.getFrameNumber() + 1L;
                            endpoint.record(sourceFrame, timestamp);
                            compositor.recordCapture(eye, sourceFrame, timestamp);
                        }
                    },
                    handler);
            return endpoint;
        }

        synchronized void record(long frame, long timestampNs) {
            captureCount++;
            if (firstFrame < 0L) {
                firstFrame = frame;
                firstSensorTimestampNs = timestampNs;
            }
            lastFrame = frame;
            lastSensorTimestampNs = timestampNs;
            lastCaptureElapsedMs = SystemClock.elapsedRealtime();
        }

        synchronized JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("eye", eye);
            json.put("camera_id", cameraId);
            json.put("camera_output_width", outputWidth);
            json.put("camera_output_height", outputHeight);
            json.put("camera_output_size_exact_supported", true);
            json.put("capture_callback_count", captureCount);
            json.put("first_frame_number", firstFrame);
            json.put("last_frame_number", lastFrame);
            json.put("first_sensor_timestamp_ns", firstSensorTimestampNs);
            json.put("last_sensor_timestamp_ns", lastSensorTimestampNs);
            json.put("fresh_camera2_frames_observed", captureCount >= 2L && lastFrame > firstFrame);
            json.put("frame_number_delta", firstFrame < 0L ? 0L : Math.max(0L, lastFrame - firstFrame));
            json.put("last_capture_age_ms", lastCaptureElapsedMs < 0L
                    ? -1L
                    : Math.max(0L, SystemClock.elapsedRealtime() - lastCaptureElapsedMs));
            return json;
        }

        static JSONObject combinedFreshness(CameraEndpoint left, CameraEndpoint right) throws Exception {
            JSONObject leftJson = left.toJson();
            JSONObject rightJson = right.toJson();
            JSONObject json = new JSONObject();
            json.put(
                    "capture_callback_count",
                    Math.min(
                            leftJson.optLong("capture_callback_count", 0L),
                            rightJson.optLong("capture_callback_count", 0L)));
            json.put(
                    "frame_number_delta",
                    Math.min(
                            leftJson.optLong("frame_number_delta", 0L),
                            rightJson.optLong("frame_number_delta", 0L)));
            json.put(
                    "last_capture_age_ms",
                    Math.max(
                            leftJson.optLong("last_capture_age_ms", -1L),
                            rightJson.optLong("last_capture_age_ms", -1L)));
            json.put(
                    "fresh_camera2_frames_observed",
                    leftJson.optBoolean("fresh_camera2_frames_observed", false)
                            && rightJson.optBoolean("fresh_camera2_frames_observed", false));
            json.put("left", leftJson);
            json.put("right", rightJson);
            return json;
        }

        @Override
        public void close() {
            try {
                session.stopRepeating();
            } catch (Exception ignored) {
            }
            session.close();
            device.close();
        }
    }

    private static final class PortProfile {
        final int port;
        final int width;
        final int height;
        final int frameRateHz;
        final int bitrateBps;

        PortProfile(int port, int width, int height, int frameRateHz, int bitrateBps) {
            this.port = port;
            this.width = width;
            this.height = height;
            this.frameRateHz = frameRateHz;
            this.bitrateBps = bitrateBps;
        }

        static PortProfile parse(String ports, String profiles) {
            String[] portFields = clean(ports).split(":");
            if (portFields.length != 2 || !"stereo".equals(portFields[0])) {
                throw new IllegalArgumentException("packed source requires exactly one stereo port");
            }
            int port = Integer.parseInt(portFields[1]);
            String profile = clean(profiles);
            if (!profile.startsWith("stereo:")) {
                throw new IllegalArgumentException("packed source requires one stereo media profile");
            }
            String[] profileFields = profile.substring("stereo:".length()).split("[@:x]");
            if (profileFields.length != 4) {
                throw new IllegalArgumentException("invalid packed media profile");
            }
            return new PortProfile(
                    port,
                    Integer.parseInt(profileFields[0]),
                    Integer.parseInt(profileFields[1]),
                    Integer.parseInt(profileFields[2]),
                    Integer.parseInt(profileFields[3]));
        }
    }

    private static Map<String, String> parseCameraIds(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : clean(value).split(",")) {
            String[] fields = entry.split(":");
            if (fields.length == 2) {
                result.put(clean(fields[0]), clean(fields[1]));
            }
        }
        return result;
    }

    private static void requireExactSurfaceTextureOutputSize(
            CameraCharacteristics characteristics,
            String cameraId,
            int width,
            int height) {
        StreamConfigurationMap configurations = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] outputSizes = configurations == null
                ? null
                : configurations.getOutputSizes(SurfaceTexture.class);
        if (outputSizes != null) {
            for (Size size : outputSizes) {
                if (size != null && size.getWidth() == width && size.getHeight() == height) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException(
                "packed camera " + cameraId + " does not support exact SurfaceTexture output "
                        + width + "x" + height);
    }

    private static CameraDevice openDevice(CameraManager manager, String cameraId, Handler handler)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CameraDevice[] opened = new CameraDevice[1];
        String[] failure = new String[1];
        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                opened[0] = camera;
                latch.countDown();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                failure[0] = "camera_disconnected";
                camera.close();
                latch.countDown();
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                failure[0] = "camera_error_" + error;
                camera.close();
                latch.countDown();
            }
        }, handler);
        if (!latch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS) || opened[0] == null) {
            throw new IllegalStateException(failure[0] == null ? "camera open timed out" : failure[0]);
        }
        return opened[0];
    }

    private static CameraCaptureSession createSession(
            CameraDevice device,
            Surface surface,
            Handler handler) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CameraCaptureSession[] configured = new CameraCaptureSession[1];
        device.createCaptureSession(
                java.util.Collections.singletonList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        configured[0] = session;
                        latch.countDown();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        latch.countDown();
                    }
                },
                handler);
        if (!latch.await(CAMERA_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS) || configured[0] == null) {
            throw new IllegalStateException("packed camera session configuration failed");
        }
        return configured[0];
    }

    private static Range<Integer> chooseFpsRange(CameraCharacteristics characteristics, int target) {
        Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) {
            return null;
        }
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range == null || !range.contains(target)) {
                continue;
            }
            int score = Math.abs(range.getUpper() - target) * 100 + Math.abs(range.getLower() - target);
            if (score < bestScore) {
                best = range;
                bestScore = score;
            }
        }
        return best;
    }

    private static void requestSyncFrame(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(parameters);
        } catch (Exception ignored) {
        }
    }

    private static void writeU32(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeU64(OutputStream output, long value) throws IOException {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) ((value >>> shift) & 0xFF));
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null ? "" : message;
    }

    private static void closeQuietly(Object value) {
        if (value == null) {
            return;
        }
        try {
            if (value instanceof Closeable) {
                ((Closeable) value).close();
            } else if (value instanceof Socket) {
                ((Socket) value).close();
            } else if (value instanceof ServerSocket) {
                ((ServerSocket) value).close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
