package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;

public final class SpatialStereoVideoPlayback {
    private static final String LOG_TAG = "RQSpatialCamera";
    private static final String SOURCE_BROKER_RMANVID1 = "broker-rmanvid1";
    private static final String SOURCE_PEER_PACKED_STEREO = "peer-packed-stereo";
    private static final String SOURCE_ENCRYPTED_OFFLINE_PACK = "encrypted-offline-pack";
    private static final String SOURCE_SHARED_PLAIN_VIDEO = "shared-plain-video";
    private static final int EVENT_START_REQUESTED = 1;
    private static final int EVENT_STARTED = 2;
    private static final int EVENT_STOPPED = 3;
    private static final int EVENT_ERROR = 4;
    private static final int EVENT_FORMAT = 5;
    private static final int EVENT_FRAME = 6;
    private static final int EVENT_LOOP_RESTARTED = 7;
    private static final int EVENT_HANDOFF_BLOCKED = 8;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final long DECODER_STOP_JOIN_TIMEOUT_MS = 1_500L;

    private static final Object LOCK = new Object();
    private static volatile boolean stopRequested;
    private static volatile Thread playbackThread;
    private static volatile Surface playbackSurface;
    private static boolean nativeBridgeLoaded;

    static {
        try {
            System.loadLibrary("spatial_camera_panel_native_receipt");
            nativeBridgeLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            nativeBridgeLoaded = false;
        }
    }

    private SpatialStereoVideoPlayback() {}

    public static boolean start(
        Context context,
        String source,
        String path,
        MediaDataSource encryptedMediaSource,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping,
        String brokerHost,
        int brokerPort,
        int brokerConnectTimeoutMs,
        String mediaLayout,
        String peerRouteKind,
        String peerSessionId,
        String peerRelayChannel,
        String peerTlsServerName,
        String peerAuthToken
    ) {
        int requestedWidth = clamp(width, 320, 4096);
        int requestedHeight = clamp(height, 240, 4096);
        int requestedMaxImages = clamp(maxImages, 2, 6);
        int requestedSurfaceCadenceFps = normalizeSurfaceCadenceFps(fpsCap);
        int requestedFpsCap =
            nativeFallbackFpsForSurfaceCadence(requestedSurfaceCadenceFps);
        String resolvedPath = resolvePath(context, path);
        boolean brokerSource = SOURCE_BROKER_RMANVID1.equals(source)
            || SOURCE_PEER_PACKED_STEREO.equals(source);
        boolean encryptedOfflinePackSource = SOURCE_ENCRYPTED_OFFLINE_PACK.equals(source);
        boolean sharedPlainVideoSource = SOURCE_SHARED_PLAIN_VIDEO.equals(source);

        synchronized (LOCK) {
            if (!stopLocked()) {
                closeQuietly(encryptedMediaSource);
                if (nativeBridgeLoaded) {
                    nativeStereoVideoLifecycleEvent(
                        EVENT_HANDOFF_BLOCKED,
                        -6,
                        requestedWidth,
                        requestedHeight,
                        requestedMaxImages,
                        requestedFpsCap,
                        looping ? 1 : 0
                    );
                }
                return false;
            }
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
            closeQuietly(encryptedMediaSource);
            return false;
        }
        if (encryptedOfflinePackSource && encryptedMediaSource == null) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -5,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
            return false;
        }
        if (!brokerSource
            && !encryptedOfflinePackSource
            && !sharedPlainVideoSource
            && (resolvedPath.isEmpty() || !new File(resolvedPath).isFile())) {
            closeQuietly(encryptedMediaSource);
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -2,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
            return false;
        }
        if (sharedPlainVideoSource &&
            (path == null || !path.trim().startsWith("content://"))) {
            closeQuietly(encryptedMediaSource);
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -7,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
            return false;
        }

        Surface surface = nativeCreateStereoVideoSurface(
            requestedWidth,
            requestedHeight,
            requestedMaxImages,
            requestedFpsCap
        );
        if (surface == null) {
            closeQuietly(encryptedMediaSource);
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -3,
                requestedWidth,
                requestedHeight,
                requestedMaxImages,
                requestedFpsCap,
                looping ? 1 : 0
            );
            return false;
        }

        Thread thread = new Thread(
            new Runnable() {
                @Override
                public void run() {
                    if (brokerSource) {
                        runBrokerPlayback(
                            brokerHost,
                            brokerPort,
                            brokerConnectTimeoutMs,
                            mediaLayout,
                            peerRouteKind,
                            peerSessionId,
                            peerRelayChannel,
                            peerTlsServerName,
                            peerAuthToken,
                            surface,
                            requestedWidth,
                            requestedHeight,
                            requestedMaxImages,
                            requestedFpsCap
                        );
                    } else if (encryptedOfflinePackSource) {
                        runPlayback(
                            encryptedMediaSource,
                            surface,
                            requestedWidth,
                            requestedHeight,
                            requestedMaxImages,
                            requestedSurfaceCadenceFps,
                            looping
                        );
                    } else if (sharedPlainVideoSource) {
                        runPlayback(
                            context.getApplicationContext(),
                            path,
                            surface,
                            requestedWidth,
                            requestedHeight,
                            requestedMaxImages,
                            requestedSurfaceCadenceFps,
                            looping
                        );
                    } else {
                        runPlayback(
                            resolvedPath,
                            surface,
                            requestedWidth,
                            requestedHeight,
                            requestedMaxImages,
                            requestedSurfaceCadenceFps,
                            looping
                        );
                    }
                }
            },
            "RQSpatialStereoVideo"
        );
        thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        synchronized (LOCK) {
            playbackSurface = surface;
            playbackThread = thread;
        }
        thread.start();
        return true;
    }

    public static boolean stop() {
        synchronized (LOCK) {
            return stopLocked();
        }
    }

    private static boolean stopLocked() {
        stopRequested = true;
        Thread thread = playbackThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(DECODER_STOP_JOIN_TIMEOUT_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        boolean threadStillOwnsSurface = thread != null
            && thread != Thread.currentThread()
            && thread.isAlive();
        if (!replacementAllowedAfterStop(threadStillOwnsSurface)) {
            playbackThread = thread;
            return false;
        }
        // Keep the codec output surface valid until the MediaCodec owner has exited. Destroying
        // the AImageReader first can strand codec.stop() in the decoder thread and make the
        // otherwise bounded join fail even though rendering has already removed the consumer.
        if (nativeBridgeLoaded) {
            nativeStopStereoVideoStream();
        }
        if (playbackThread == thread) {
            playbackThread = null;
        }
        if (playbackSurface != null) {
            playbackSurface.release();
            playbackSurface = null;
        }
        return true;
    }

    static boolean replacementAllowedAfterStop(boolean previousThreadAlive) {
        return !previousThreadAlive;
    }

    static long decoderStopJoinTimeoutMs() {
        return DECODER_STOP_JOIN_TIMEOUT_MS;
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
        int nativeFpsCap = nativeFallbackFpsForSurfaceCadence(fpsCap);
        try {
            nativeStereoVideoLifecycleEvent(
                EVENT_STARTED,
                0,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
            decodeOnce(path, surface, width, height, maxImages, fpsCap, looping);
            nativeStereoVideoLifecycleEvent(
                EVENT_STOPPED,
                0,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
        } catch (RuntimeException | IOException error) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -1,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
        } finally {
            releasePlaybackOwnershipWithoutLock(surface);
        }
    }

    private static void runPlayback(
        MediaDataSource mediaDataSource,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) {
        int loopingFlag = looping ? 1 : 0;
        int nativeFpsCap = nativeFallbackFpsForSurfaceCadence(fpsCap);
        try {
            nativeStereoVideoLifecycleEvent(
                EVENT_STARTED,
                0,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
            decodeOnce(mediaDataSource, surface, width, height, maxImages, fpsCap, looping);
            nativeStereoVideoLifecycleEvent(
                EVENT_STOPPED,
                0,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
        } catch (RuntimeException | IOException error) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -1,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
        } finally {
            releasePlaybackOwnershipWithoutLock(surface);
        }
    }

    private static void runPlayback(
        Context context,
        String contentUri,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) {
        int loopingFlag = looping ? 1 : 0;
        int nativeFpsCap = nativeFallbackFpsForSurfaceCadence(fpsCap);
        try {
            nativeStereoVideoLifecycleEvent(
                EVENT_STARTED,
                0,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
            decodeOnce(
                context,
                contentUri,
                surface,
                width,
                height,
                maxImages,
                fpsCap,
                looping
            );
            nativeStereoVideoLifecycleEvent(
                EVENT_STOPPED,
                0,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
        } catch (RuntimeException | IOException error) {
            nativeStereoVideoLifecycleEvent(
                EVENT_ERROR,
                -1,
                width,
                height,
                maxImages,
                nativeFpsCap,
                loopingFlag
            );
        } finally {
            releasePlaybackOwnershipWithoutLock(surface);
        }
    }

    private static void runBrokerPlayback(
        String host,
        int port,
        int connectTimeoutMs,
        String mediaLayout,
        String peerRouteKind,
        String peerSessionId,
        String peerRelayChannel,
        String peerTlsServerName,
        String peerAuthToken,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap
    ) {
        try {
            nativeStereoVideoLifecycleEvent(EVENT_STARTED, 0, width, height, maxImages, fpsCap, 0);
            SpatialPackedStereoBrokerPlayback.run(
                host,
                port,
                connectTimeoutMs,
                mediaLayout,
                peerRouteKind,
                peerSessionId,
                peerRelayChannel,
                peerTlsServerName,
                peerAuthToken,
                surface,
                width,
                height,
                maxImages,
                fpsCap
            );
            nativeStereoVideoLifecycleEvent(EVENT_STOPPED, 0, width, height, maxImages, fpsCap, 0);
        } catch (RuntimeException | IOException error) {
            nativeStereoVideoLifecycleEvent(EVENT_ERROR, -1, width, height, maxImages, fpsCap, 0);
        } finally {
            releasePlaybackOwnershipWithoutLock(surface);
        }
    }

    private static void releasePlaybackOwnershipWithoutLock(Surface surface) {
        // stopLocked joins while holding LOCK. The exact decoder owner must therefore be able to
        // finish its final ownership release without reacquiring that lock. Identity checks keep a
        // late owner from clearing a successor's thread or surface.
        if (playbackSurface == surface) {
            playbackSurface = null;
        }
        if (playbackThread == Thread.currentThread()) {
            playbackThread = null;
        }
        surface.release();
    }

    static boolean isStopRequested() {
        return stopRequested;
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
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            decodeConfiguredExtractor(
                extractor,
                surface,
                width,
                height,
                maxImages,
                fpsCap,
                looping
            );
        } finally {
            extractor.release();
        }
    }

    private static void decodeOnce(
        MediaDataSource mediaDataSource,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(mediaDataSource);
            decodeConfiguredExtractor(
                extractor,
                surface,
                width,
                height,
                maxImages,
                fpsCap,
                looping
            );
        } finally {
            extractor.release();
            closeQuietly(mediaDataSource);
        }
    }

    private static void decodeOnce(
        Context context,
        String contentUri,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(
                context,
                Uri.parse(contentUri),
                Collections.<String, String>emptyMap()
            );
            decodeConfiguredExtractor(
                extractor,
                surface,
                width,
                height,
                maxImages,
                fpsCap,
                looping
            );
        } finally {
            extractor.release();
        }
    }

    private static void decodeConfiguredExtractor(
        MediaExtractor extractor,
        Surface surface,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) throws IOException {
        int loopingFlag = looping ? 1 : 0;
        MediaCodec codec = null;
        try {
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("video track missing");
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            double sourceFrameRate = sourceFrameRate(format);
            if (fpsCap == 0 && !sourceRateSupported(sourceFrameRate)) {
                Log.e(
                    LOG_TAG,
                    codecOutputCadenceUnsupportedMarker(sourceFrameRate)
                );
                throw new IOException("source cadence unavailable or above 90 fps");
            }
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
                nativeFallbackFpsForSurfaceCadence(fpsCap),
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
            long decodedOutputFrames = 0L;
            long surfaceSkippedFrames = 0L;
            long lastSurfacePresentationUs = -1L;
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
                                    nativeFallbackFpsForSurfaceCadence(fpsCap),
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
                    boolean hasFrame = info.size != 0;
                    boolean render = hasFrame
                        && shouldRenderSurfaceOutput(
                            lastSurfacePresentationUs,
                            info.presentationTimeUs,
                            fpsCap
                        );
                    if (hasFrame) {
                        decodedOutputFrames += 1L;
                    }
                    if (render) {
                        if (firstPresentationUs < 0) {
                            firstPresentationUs = info.presentationTimeUs;
                            firstFrameReleaseNs = System.nanoTime();
                        }
                        paceToPresentationTime(firstFrameReleaseNs, firstPresentationUs, info.presentationTimeUs);
                        lastSurfacePresentationUs = info.presentationTimeUs;
                    } else if (hasFrame) {
                        surfaceSkippedFrames += 1L;
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
                                nativeFallbackFpsForSurfaceCadence(fpsCap),
                                loopingFlag
                            );
                        }
                    }
                    if (hasFrame
                        && (decodedOutputFrames == 1L || decodedOutputFrames % 120L == 0L)) {
                        Log.i(
                            LOG_TAG,
                            codecOutputCadenceMarker(
                                decodedOutputFrames,
                                renderedFrames,
                                surfaceSkippedFrames,
                                fpsCap,
                                sourceFrameRate,
                                false
                            )
                        );
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (decodedOutputFrames > 0L) {
                Log.i(
                    LOG_TAG,
                    codecOutputCadenceMarker(
                        decodedOutputFrames,
                        renderedFrames,
                        surfaceSkippedFrames,
                        fpsCap,
                        sourceFrameRate,
                        true
                    )
                );
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
        }
    }

    private static long estimateFrameDurationUs(MediaFormat format, int fpsCap) {
        double reportedFrameRate = sourceFrameRate(format);
        int frameRate = reportedFrameRate > 0.0
            ? (int) Math.round(reportedFrameRate)
            : nativeFallbackFpsForSurfaceCadence(fpsCap);
        return 1_000_000L / clamp(frameRate, 1, 240);
    }

    static boolean shouldRenderSurfaceOutput(
        long lastSurfacePresentationUs,
        long presentationTimeUs,
        int fpsCap
    ) {
        if (fpsCap == 0) {
            return true;
        }
        if (lastSurfacePresentationUs < 0L || presentationTimeUs <= lastSurfacePresentationUs) {
            return true;
        }
        long deltaUs = presentationTimeUs - lastSurfacePresentationUs;
        long clampedFpsCap = clamp(fpsCap, 1, 90);
        // Media timestamps are integral microseconds. One microsecond of tolerance keeps an exact
        // 30 fps sequence (33,333 / 33,334 us steps) from being misclassified while still
        // rejecting the intermediate 16,666 / 16,667 us outputs of a 60 fps source.
        return deltaUs * clampedFpsCap + clampedFpsCap >= 1_000_000L;
    }

    static String codecOutputCadenceMarker(
        long decodedOutputFrames,
        long surfaceRenderedFrames,
        long surfaceSkippedFrames,
        int fpsCap,
        boolean finalSample
    ) {
        return codecOutputCadenceMarker(
            decodedOutputFrames,
            surfaceRenderedFrames,
            surfaceSkippedFrames,
            fpsCap,
            -1.0,
            finalSample
        );
    }

    static String codecOutputCadenceMarker(
        long decodedOutputFrames,
        long surfaceRenderedFrames,
        long surfaceSkippedFrames,
        int fpsCap,
        double sourceFrameRate,
        boolean finalSample
    ) {
        int normalizedCadenceFps = normalizeSurfaceCadenceFps(fpsCap);
        boolean gateEnabled = normalizedCadenceFps != 0;
        String requestedMode = normalizedCadenceFps == 0
            ? "source"
            : Integer.toString(normalizedCadenceFps);
        String sourceRate = sourceFrameRate > 0.0
            ? String.format(Locale.US, "%.3f", sourceFrameRate)
            : "unknown";
        double effectiveFrameRate = normalizedCadenceFps == 0
            ? sourceFrameRate
            : (sourceFrameRate > 0.0
                ? Math.min(sourceFrameRate, normalizedCadenceFps)
                : normalizedCadenceFps);
        String effectiveRate = effectiveFrameRate > 0.0
            ? String.format(Locale.US, "%.3f", effectiveFrameRate)
            : "unknown";
        String effectiveMode = normalizedCadenceFps == 0
            ? "source"
            : (sourceFrameRate > 0.0 && sourceFrameRate <= normalizedCadenceFps
                ? "source-below-cap"
                : "capped-" + normalizedCadenceFps);
        return String.format(
            Locale.US,
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=spatial-stereo-video "
                + "status=codec-output-cadence decodedOutputFrames=%d "
                + "surfaceRenderedFrames=%d surfaceSkippedFrames=%d fpsCap=%s "
                + "requestedMode=%s effectiveMode=%s effectiveRateFps=%s sourceRateFps=%s "
                + "surfaceGateEnabled=%s nativeFallbackFps=%d "
                + "cadenceBoundary=mediacodec-output-before-surface "
                + "compressedReferenceFramesPreserved=true nativeCadenceFallbackRetained=true "
                + "finalSample=%s runtimeCrash=false",
            decodedOutputFrames,
            surfaceRenderedFrames,
            surfaceSkippedFrames,
            gateEnabled ? Integer.toString(normalizedCadenceFps) : "source",
            requestedMode,
            effectiveMode,
            effectiveRate,
            sourceRate,
            gateEnabled,
            nativeFallbackFpsForSurfaceCadence(normalizedCadenceFps),
            finalSample
        );
    }

    static boolean sourceRateSupported(double sourceFrameRate) {
        return sourceFrameRate > 0.0 && sourceFrameRate <= 90.0;
    }

    static String codecOutputCadenceUnsupportedMarker(double sourceFrameRate) {
        String sourceRate = sourceFrameRate > 0.0
            ? String.format(Locale.US, "%.3f", sourceFrameRate)
            : "unknown";
        return "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=spatial-stereo-video "
            + "status=codec-output-cadence-unsupported requestedMode=source "
            + "effectiveMode=unsupported sourceRateFps=" + sourceRate + " "
            + "sourceRateCeilingFps=90 surfaceGateEnabled=false "
            + "nativeCadenceFallbackRetained=true runtimeCrash=false";
    }

    private static double sourceFrameRate(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            return -1.0;
        }
        try {
            Number value = format.getNumber(MediaFormat.KEY_FRAME_RATE);
            return value == null ? -1.0 : value.doubleValue();
        } catch (ClassCastException | NullPointerException ignored) {
            return -1.0;
        }
    }

    private static int normalizeSurfaceCadenceFps(int fpsCap) {
        if (fpsCap == 0) {
            return 0;
        }
        return fpsCap <= 30 ? 30 : 60;
    }

    private static int nativeFallbackFpsForSurfaceCadence(int fpsCap) {
        int normalized = normalizeSurfaceCadenceFps(fpsCap);
        return normalized == 0 ? 90 : normalized;
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
            return "";
        }
        File file = new File(trimmed);
        if (file.isAbsolute()) {
            return file.getAbsolutePath();
        }
        return new File(context.getFilesDir(), trimmed).getAbsolutePath();
    }

    private static void closeQuietly(MediaDataSource mediaDataSource) {
        if (mediaDataSource == null) {
            return;
        }
        try {
            mediaDataSource.close();
        } catch (IOException ignored) {
        }
    }

    private static int clamp(int value, int minValue, int maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static native Surface nativeCreateStereoVideoSurface(
        int width,
        int height,
        int maxImages,
        int fpsCap
    );

    private static native void nativeStopStereoVideoStream();

    private static native void nativeStereoVideoLifecycleEvent(
        int eventCode,
        int resultCode,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        int looping
    );
}
