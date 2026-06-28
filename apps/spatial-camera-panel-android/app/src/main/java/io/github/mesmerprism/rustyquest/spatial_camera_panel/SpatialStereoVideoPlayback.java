package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class SpatialStereoVideoPlayback {
    private static final int EVENT_START_REQUESTED = 1;
    private static final int EVENT_STARTED = 2;
    private static final int EVENT_STOPPED = 3;
    private static final int EVENT_ERROR = 4;
    private static final int EVENT_FORMAT = 5;
    private static final int EVENT_FRAME = 6;
    private static final int EVENT_LOOP_RESTARTED = 7;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private static final Object LOCK = new Object();
    private static volatile boolean stopRequested;
    private static Thread playbackThread;
    private static Surface playbackSurface;
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

    public static void start(
        Context context,
        String path,
        int width,
        int height,
        int maxImages,
        int fpsCap,
        boolean looping
    ) {
        int requestedWidth = clamp(width, 320, 4096);
        int requestedHeight = clamp(height, 240, 4096);
        int requestedMaxImages = clamp(maxImages, 2, 6);
        int requestedFpsCap = clamp(fpsCap, 1, 90);
        String resolvedPath = resolvePath(context, path);

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
        if (resolvedPath.isEmpty() || !new File(resolvedPath).isFile()) {
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
            "RQSpatialStereoVideo"
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
        playbackThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        boolean threadStillOwnsSurface = thread != null
            && thread != Thread.currentThread()
            && thread.isAlive();
        if (threadStillOwnsSurface) {
            playbackSurface = null;
        } else if (playbackSurface != null) {
            playbackSurface.release();
            playbackSurface = null;
        }
        if (nativeBridgeLoaded) {
            nativeStopStereoVideoStream();
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
            return "";
        }
        File file = new File(trimmed);
        if (file.isAbsolute()) {
            return file.getAbsolutePath();
        }
        return new File(context.getFilesDir(), trimmed).getAbsolutePath();
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
