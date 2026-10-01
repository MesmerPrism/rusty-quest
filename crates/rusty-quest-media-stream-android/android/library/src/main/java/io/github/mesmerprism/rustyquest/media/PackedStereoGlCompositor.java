package io.github.mesmerprism.rustyquest.media;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.os.Looper;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import org.json.JSONObject;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** GPU-only OES snapshot and side-by-side encoder-surface compositor. */
final class PackedStereoGlCompositor implements Closeable {
    interface Listener {
        void onPairPresented(PackedStereoFramePairer.Pair pair, long presentationTimeUs);

        void onCompositorFailure(Throwable error);
        default boolean canRetireCaptureInputs() { return true; }
    }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final int RING_SIZE = 6;
    private static final long START_TIMEOUT_MS = 5_000L;

    private final Object signal = new Object();
    private final PackedStereoStreamMetadata.Layout layout;
    private final Surface encoderInputSurface;
    private final PackedStereoPoolExecutor poolExecutor;
    private volatile boolean physicallyRetired;
    enum CleanupBarrier { NOT_REQUESTED, STOP_REQUESTED, SURFACE_CALLBACKS_PENDING, NATIVE_POOL_PENDING, CAMERA_CALLBACKS_PENDING, CAPTURE_CONTEXT_PENDING, TERMINAL }
    private volatile CleanupBarrier cleanupBarrier = CleanupBarrier.NOT_REQUESTED;
    private volatile boolean cleanupRejected;
    String cleanupBarrier() { return cleanupBarrier.name(); }
    boolean cleanupRejected() { return cleanupRejected; }
    // Failed native initialization/retirement cannot confer permission to destroy its EGL context.
    private volatile GlState quarantinedGl;
    private final boolean synthetic;
    private final Listener listener;
    private final PackedStereoFramePairer pairer;
    private final CaptureCorrelation leftCorrelation = new CaptureCorrelation();
    private final CaptureCorrelation rightCorrelation = new CaptureCorrelation();
    private final CaptureFrameTrace leftFrameTrace;
    private final CaptureFrameTrace rightFrameTrace;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread thread;
    private volatile SurfaceNotificationOwner surfaceNotifications;

    /** Notifications only; SurfaceTexture texture operations remain with the capture GL owner. */
    static final class SurfaceNotificationOwner implements Closeable {
        private final HandlerThread thread = new HandlerThread("rq-capture-surface-notify");
        private final Handler handler;
        private SurfaceTexture left, right;
        private volatile boolean detached;
        SurfaceNotificationOwner() {
            thread.start();
            try { handler = new Handler(thread.getLooper()); }
            catch (RuntimeException failure) { thread.quitSafely(); awaitThreadRetired(); throw failure; }
        }
        void left(SurfaceTexture texture, SurfaceTexture.OnFrameAvailableListener listener) {
            left = texture;
            texture.setOnFrameAvailableListener(listener, handler);
        }
        void right(SurfaceTexture texture, SurfaceTexture.OnFrameAvailableListener listener) {
            right = texture;
            texture.setOnFrameAvailableListener(listener, handler);
        }
        boolean retired() { return detached && !thread.isAlive(); }
        @Override public void close() {
            if (Thread.currentThread() == thread) throw new IllegalStateException("notification owner cannot join itself");
            if (left != null) left.setOnFrameAvailableListener(null);
            if (right != null) right.setOnFrameAvailableListener(null);
            detached = true;
            thread.quitSafely();
            awaitThreadRetired();
        }
        private void awaitThreadRetired() {
            while (thread.isAlive()) {
                try { thread.join(50L); }
                catch (InterruptedException ignored) { /* actual thread retirement owns this barrier */ }
            }
        }
    }

    private void stopSurfaceNotifications() {
        SurfaceNotificationOwner retained = surfaceNotifications;
        if (retained != null) {
            cleanupBarrier = CleanupBarrier.SURFACE_CALLBACKS_PENDING;
            retained.close();
        }
    }

    private volatile boolean stopRequested;
    private volatile Throwable startupFailure;
    private volatile Surface leftCameraSurface;
    private volatile Surface rightCameraSurface;
    private volatile boolean gpuCompositorActive;
    private volatile long composedFrames;
    private final MonotonicFreshnessDeadline compositionFreshness =
            new MonotonicFreshnessDeadline(3_000L);
    private volatile long syntheticFrames;
    private volatile long leftSurfaceFrames;
    private volatile long rightSurfaceFrames;
    private volatile long leftUncorrelatedFrames;
    private volatile long rightUncorrelatedFrames;
    private volatile long compositorTimeTotalNs;
    private volatile long compositorTimeMaxNs;
    // Capture-owner observation only. Each stage has one writer; a status read is explicitly
    // non-atomic across stages and cannot confer frame or cleanup authority.
    private final SurfaceCallbackCadence leftSurfaceCallbacks = new SurfaceCallbackCadence();
    private final SurfaceCallbackCadence rightSurfaceCallbacks = new SurfaceCallbackCadence();
    private final UpdateDurationPeak leftTextureUpdate = new UpdateDurationPeak();
    private final UpdateDurationPeak rightTextureUpdate = new UpdateDurationPeak();
    private final StageCadence leftSurfaceConsumed = new StageCadence();
    private final StageCadence rightSurfaceConsumed = new StageCadence();
    private final StageCadence leftCorrelated = new StageCadence();
    private final StageCadence rightCorrelated = new StageCadence();
    private final StageCadence pairsAccepted = new StageCadence();
    private final StageCadence poolBeginAccepted = new StageCadence();
    private final StageCadence poolBeginNoCapacity = new StageCadence();
    private final StageCadence producerFenceSubmitted = new StageCadence();
    private volatile long lastPairLeftFrame, lastPairRightFrame;
    private volatile long lastPairLeftSensorNs, lastPairRightSensorNs;

    /** One monotonic stage, with no gap asserted before its second real observation. */
    static class StageCadence {
        private long count, lastNs, maxGapNs, lastIdentity;

        synchronized void observeAt(long elapsedNs, long identity) {
            if (elapsedNs <= 0L || identity < 0L) return;
            count++;
            if (elapsedNs < lastNs) return; // A regressed clock cannot create a gap.
            if (lastNs > 0L) maxGapNs = Math.max(maxGapNs, elapsedNs - lastNs);
            lastNs = elapsedNs;
            lastIdentity = identity;
        }

        synchronized long count() { return count; }
        synchronized long lastNs() { return lastNs; }
        synchronized long maxGapNs() { return maxGapNs; }
        synchronized long lastIdentity() { return lastIdentity; }
        synchronized long ageNs(long sampleNs) {
            return lastNs == 0L || sampleNs < lastNs ? -1L : sampleNs - lastNs;
        }
        synchronized JSONObject snapshot(long sampleNs) throws Exception {
            return new JSONObject().put("count", count).put("last_elapsed_ns", lastNs)
                    .put("max_gap_ns", maxGapNs).put("age_ns", ageNs(sampleNs))
                    .put("last_identity", lastIdentity());
        }
    }

    /** Exact callback-arrival bracket; dispatch thread is observed, not selected here. */
    static final class SurfaceCallbackCadence extends StageCadence {
        private long lastCallbackArrivalNs, maxCallbackGapNs;
        private long lastThreadId, worstFromNs, worstToNs, worstFromThreadId, worstToThreadId;
        private boolean lastMainLooper, worstPresent, worstFromMainLooper, worstToMainLooper;

        synchronized void observeCallbackAt(long arrivalNs, long insideSignalNs,
                long threadId, boolean onMainLooper) {
            // Preserve the five existing StageCadence keys at their original in-lock point.
            observeAt(insideSignalNs, 0L);
            if (arrivalNs <= 0L || threadId <= 0L || arrivalNs < lastCallbackArrivalNs) return;
            if (lastCallbackArrivalNs > 0L
                    && arrivalNs - lastCallbackArrivalNs > maxCallbackGapNs) {
                maxCallbackGapNs = arrivalNs - lastCallbackArrivalNs;
                worstPresent = true;
                worstFromNs = lastCallbackArrivalNs;
                worstToNs = arrivalNs;
                worstFromThreadId = lastThreadId;
                worstToThreadId = threadId;
                worstFromMainLooper = lastMainLooper;
                worstToMainLooper = onMainLooper;
            }
            lastCallbackArrivalNs = arrivalNs;
            lastThreadId = threadId;
            lastMainLooper = onMainLooper;
        }

        @Override synchronized JSONObject snapshot(long sampleNs) throws Exception {
            return super.snapshot(sampleNs)
                    .put("last_callback_elapsed_ns", lastCallbackArrivalNs)
                    .put("max_callback_gap_ns", maxCallbackGapNs)
                    .put("last_thread_id", lastThreadId)
                    .put("last_on_main_looper", lastMainLooper)
                    .put("worst_gap", new JSONObject()
                            .put("present", worstPresent)
                            .put("from_callback_elapsed_ns", worstPresent ? worstFromNs : 0L)
                            .put("to_callback_elapsed_ns", worstPresent ? worstToNs : 0L)
                            .put("from_thread_id", worstPresent ? worstFromThreadId : 0L)
                            .put("to_thread_id", worstPresent ? worstToThreadId : 0L)
                            .put("from_on_main_looper", worstPresent && worstFromMainLooper)
                            .put("to_on_main_looper", worstPresent && worstToMainLooper));
        }
    }

    /** Successful updateTexImage wall duration in the Android elapsed clock. */
    static final class UpdateDurationPeak {
        private long count, maxDurationNs, maxEntryNs, maxExitNs;
        synchronized void observeAt(long entryNs, long exitNs) {
            if (entryNs <= 0L || exitNs < entryNs) return;
            count++;
            long durationNs = exitNs - entryNs;
            if (durationNs > maxDurationNs) {
                maxDurationNs = durationNs;
                maxEntryNs = entryNs;
                maxExitNs = exitNs;
            }
        }
        synchronized JSONObject snapshot() throws Exception {
            return new JSONObject().put("count", count)
                    .put("max_duration_ns", maxDurationNs)
                    .put("max_entry_elapsed_ns", maxEntryNs)
                    .put("max_exit_elapsed_ns", maxExitNs);
        }
    }

    private int leftPending;
    private int rightPending;
    private SyntheticRequest syntheticRequest;
    private PackedStereoPoolExecutor.Pool activePool;

    PackedStereoGlCompositor(
            PackedStereoStreamMetadata.Layout layout,
            Surface encoderInputSurface,
            boolean synthetic,
            Listener listener) throws Exception {
        this(layout, encoderInputSurface, synthetic, listener, null, null, null);
    }

    PackedStereoGlCompositor(PackedStereoStreamMetadata.Layout layout,
            PackedStereoPoolExecutor executor, Listener listener) throws Exception {
        this(layout, null, false, listener, executor, null, null);
    }

    PackedStereoGlCompositor(PackedStereoStreamMetadata.Layout layout,
            PackedStereoPoolExecutor executor, Listener listener,
            CaptureFrameTrace leftTrace, CaptureFrameTrace rightTrace) throws Exception {
        this(layout, null, false, listener, executor, leftTrace, rightTrace);
    }

    private PackedStereoGlCompositor(PackedStereoStreamMetadata.Layout layout,
            Surface encoderInputSurface, boolean synthetic, Listener listener,
            PackedStereoPoolExecutor executor, CaptureFrameTrace leftTrace,
            CaptureFrameTrace rightTrace) throws Exception {
        this.layout = layout;
        this.poolExecutor = executor;
        this.encoderInputSurface = encoderInputSurface;
        this.synthetic = synthetic;
        this.listener = listener;
        this.leftFrameTrace = leftTrace;
        this.rightFrameTrace = rightTrace;
        this.pairer = new PackedStereoFramePairer(RING_SIZE - 2, layout.maxPairDeltaNs);
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                PackedStereoGlCompositor.this.run();
            }
        }, "rusty-remote-camera-packed-gl");
        this.thread.start();
        if (executor == null) awaitStarted();
    }

    void awaitStarted() throws Exception {
        if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            close();
            throw new IllegalStateException("packed GL compositor startup timed out");
        }
        if (startupFailure != null) {
            close();
            throw new IllegalStateException("packed GL compositor startup failed", startupFailure);
        }
    }

    Surface leftCameraSurface() {
        return leftCameraSurface;
    }

    Surface rightCameraSurface() {
        return rightCameraSurface;
    }

    void recordCapture(String eye, long sourceFrame, long sensorTimestampNs) {
        correlation(eye).record(sourceFrame, sensorTimestampNs);
    }

    void requestSyntheticFrame(long sourceFrame, long leftTimestampNs, long rightTimestampNs) {
        synchronized (signal) {
            syntheticRequest = new SyntheticRequest(sourceFrame, leftTimestampNs, rightTimestampNs);
            signal.notifyAll();
        }
    }

    PackedStereoFramePairer.Snapshot pairerSnapshot() {
        return pairer.snapshot();
    }

    boolean gpuCompositorActive() {
        return gpuCompositorActive;
    }

    long composedFrames() {
        return composedFrames;
    }

    boolean compositionFresh(long nowElapsedMs) {
        return compositionFreshness.fresh(nowElapsedMs);
    }

    boolean compositionFreshNow() {
        return compositionFreshness.freshAtCurrentTime(SystemClock::elapsedRealtime);
    }

    long syntheticFrames() {
        return syntheticFrames;
    }

    long leftSurfaceFrames() {
        return leftSurfaceFrames;
    }

    long rightSurfaceFrames() {
        return rightSurfaceFrames;
    }

    long leftUncorrelatedFrames() {
        return leftUncorrelatedFrames;
    }

    long rightUncorrelatedFrames() {
        return rightUncorrelatedFrames;
    }

    long compositorTimeAverageNs() {
        return composedFrames > 0L ? compositorTimeTotalNs / composedFrames : 0L;
    }

    long compositorTimeMaxNs() {
        return compositorTimeMaxNs;
    }

    JSONObject captureDiagnosticSnapshot(long sampleNs) throws Exception {
        PackedStereoFramePairer.Snapshot pairs = pairer.snapshot();
        return new JSONObject()
                .put("left_surface_callback", leftSurfaceCallbacks.snapshot(sampleNs))
                .put("right_surface_callback", rightSurfaceCallbacks.snapshot(sampleNs))
                .put("left_update_tex_image", leftTextureUpdate.snapshot())
                .put("right_update_tex_image", rightTextureUpdate.snapshot())
                .put("left_surface_consumed", leftSurfaceConsumed.snapshot(sampleNs))
                .put("right_surface_consumed", rightSurfaceConsumed.snapshot(sampleNs))
                .put("left_correlated", leftCorrelated.snapshot(sampleNs))
                .put("right_correlated", rightCorrelated.snapshot(sampleNs))
                .put("pair_accepted", pairsAccepted.snapshot(sampleNs))
                .put("pool_begin_accepted", poolBeginAccepted.snapshot(sampleNs))
                .put("pool_begin_no_capacity", poolBeginNoCapacity.snapshot(sampleNs))
                .put("producer_fence_submitted", producerFenceSubmitted.snapshot(sampleNs))
                .put("last_pair_left_frame", lastPairLeftFrame)
                .put("last_pair_right_frame", lastPairRightFrame)
                .put("last_pair_left_sensor_ns", lastPairLeftSensorNs)
                .put("last_pair_right_sensor_ns", lastPairRightSensorNs)
                .put("pairs_accepted_total", pairs.acceptedPairs)
                .put("left_unmatched", pairs.leftFramesDroppedUnmatched)
                .put("right_unmatched", pairs.rightFramesDroppedUnmatched)
                .put("pair_skew_rejected", pairs.skewRejected)
                .put("pair_queue_overflow_drops", pairs.queueOverflowDrops)
                .put("pair_queue_depth_left", pairs.queueDepthLeft)
                .put("pair_queue_depth_right", pairs.queueDepthRight)
                .put("left_uncorrelated", leftUncorrelatedFrames)
                .put("right_uncorrelated", rightUncorrelatedFrames)
                .put("composed_frames", composedFrames)
                .put("compositor_time_max_ns", compositorTimeMaxNs);
    }

    @Override
    public void close() {
        requestStop();
        if (poolExecutor == null) thread.interrupt();
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        pairer.clear();
    }

    void requestStop() {
        stopRequested = true;
        if (cleanupBarrier == CleanupBarrier.NOT_REQUESTED) cleanupBarrier = CleanupBarrier.STOP_REQUESTED;
        synchronized (signal) { signal.notifyAll(); }
    }

    boolean isTerminated() {
        SurfaceNotificationOwner retained = surfaceNotifications;
        return !thread.isAlive() && (retained == null || retained.retired());
    }
    boolean isPhysicallyRetired() { return physicallyRetired && isTerminated(); }

    private void run() {
        GlState gl = null;
        PackedStereoPoolExecutor.Pool pool = null;
        boolean poolCreationAttempted = false;
        try {
            gl = new GlState(layout, encoderInputSurface, !synthetic);
            if (poolExecutor != null) {
                gl.makePbufferCurrent();
                poolCreationAttempted = true;
                pool = poolExecutor.createForCurrentContext(layout.packedWidth, layout.packedHeight);
                if (pool == null) throw new IllegalStateException("native capture pool unavailable");
            }
            if (!synthetic) {
                surfaceNotifications = new SurfaceNotificationOwner();
                leftCameraSurface = gl.leftInput.cameraSurface;
                rightCameraSurface = gl.rightInput.cameraSurface;
                surfaceNotifications.left(gl.leftInput.surfaceTexture,
                        new SurfaceTexture.OnFrameAvailableListener() {
                            @Override
                            public void onFrameAvailable(SurfaceTexture texture) {
                                long traceEpoch = leftFrameTrace == null ? -1L : leftFrameTrace.epoch();
                                long callbackElapsedNs = SystemClock.elapsedRealtimeNanos();
                                long callbackThreadId = Thread.currentThread().getId();
                                Looper callbackLooper = Looper.myLooper();
                                boolean onMainLooper = callbackLooper != null
                                        && callbackLooper == Looper.getMainLooper();
                                synchronized (signal) {
                                    leftSurfaceCallbacks.observeCallbackAt(
                                            callbackElapsedNs, SystemClock.elapsedRealtimeNanos(),
                                            callbackThreadId, onMainLooper);
                                    if (leftFrameTrace != null) leftFrameTrace.notified(
                                            traceEpoch, callbackElapsedNs, callbackThreadId, onMainLooper);
                                    leftPending++;
                                    signal.notifyAll();
                                }
                            }
                        });
                surfaceNotifications.right(gl.rightInput.surfaceTexture,
                        new SurfaceTexture.OnFrameAvailableListener() {
                            @Override
                            public void onFrameAvailable(SurfaceTexture texture) {
                                long traceEpoch = rightFrameTrace == null ? -1L : rightFrameTrace.epoch();
                                long callbackElapsedNs = SystemClock.elapsedRealtimeNanos();
                                long callbackThreadId = Thread.currentThread().getId();
                                Looper callbackLooper = Looper.myLooper();
                                boolean onMainLooper = callbackLooper != null
                                        && callbackLooper == Looper.getMainLooper();
                                synchronized (signal) {
                                    rightSurfaceCallbacks.observeCallbackAt(
                                            callbackElapsedNs, SystemClock.elapsedRealtimeNanos(),
                                            callbackThreadId, onMainLooper);
                                    if (rightFrameTrace != null) rightFrameTrace.notified(
                                            traceEpoch, callbackElapsedNs, callbackThreadId, onMainLooper);
                                    rightPending++;
                                    signal.notifyAll();
                                }
                            }
                        });
            }
            activePool = pool;
            gpuCompositorActive = true;
        } catch (Throwable error) {
            startupFailure = error;
        } finally {
            ready.countDown();
        }
        if (startupFailure != null) {
            notifyFailure(startupFailure);
            try { stopSurfaceNotifications(); }
            catch (RuntimeException pending) { quarantinedGl = gl; cleanupRejected = true; notifyFailure(pending); return; }
            if (gl != null && !poolCreationAttempted) { gl.close(); physicallyRetired = true; }
            else if (gl != null) {
                quarantinedGl = gl;
                if (pool != null) drainPoolOnCaptureContext(pool, gl);
            }
            return;
        }

        try {
            while (!stopRequested) {
                int consumeLeft;
                int consumeRight;
                long leftTraceEpoch = -1L, rightTraceEpoch = -1L;
                SyntheticRequest request;
                synchronized (signal) {
                    while (!stopRequested
                            && leftPending == 0
                            && rightPending == 0
                            && syntheticRequest == null) {
                        signal.wait(50L);
                        if (pool != null) break; // producer/consumer fence progress without camera callbacks
                    }
                    consumeLeft = leftPending;
                    consumeRight = rightPending;
                    long handoffNs = SystemClock.elapsedRealtimeNanos();
                    if (consumeLeft > 0 && leftFrameTrace != null) leftTraceEpoch = leftFrameTrace.handoff(handoffNs);
                    if (consumeRight > 0 && rightFrameTrace != null) rightTraceEpoch = rightFrameTrace.handoff(handoffNs);
                    leftPending = 0;
                    rightPending = 0;
                    request = syntheticRequest;
                    syntheticRequest = null;
                }
                if (stopRequested) {
                    break;
                }
                if (pool != null) { gl.makePbufferCurrent(); pool.pollReady(); }
                if (request != null) {
                    renderSynthetic(gl, request);
                }
                if (consumeLeft > 0) {
                    leftSurfaceFrames += consumeLeft;
                    consumeCameraFrame(gl, gl.leftInput, leftCorrelation, PackedStereoFramePairer.LEFT, leftTraceEpoch);
                }
                if (consumeRight > 0) {
                    rightSurfaceFrames += consumeRight;
                    consumeCameraFrame(gl, gl.rightInput, rightCorrelation, PackedStereoFramePairer.RIGHT, rightTraceEpoch);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            notifyFailure(error);
        } finally {
            gpuCompositorActive = false;
            try {
                stopSurfaceNotifications();
                if (pool == null) { gl.close(); physicallyRetired = true; }
                else { quarantinedGl = gl; drainPoolOnCaptureContext(pool, gl); }
            } catch (RuntimeException pending) {
                quarantinedGl = gl; cleanupRejected = true; notifyFailure(pending);
            }
        }
    }

    private void consumeCameraFrame(
            GlState gl,
            InputState input,
            CaptureCorrelation correlation,
            String eye, long traceEpoch) throws Exception {
        gl.makePbufferCurrent();
        long updateEntryNs = SystemClock.elapsedRealtimeNanos();
        input.surfaceTexture.updateTexImage();
        long updateExitNs = SystemClock.elapsedRealtimeNanos();
        (PackedStereoFramePairer.LEFT.equals(eye) ? leftTextureUpdate : rightTextureUpdate)
                .observeAt(updateEntryNs, updateExitNs);
        long timestampNs = input.surfaceTexture.getTimestamp();
        (PackedStereoFramePairer.LEFT.equals(eye) ? leftSurfaceConsumed : rightSurfaceConsumed)
                .observeAt(SystemClock.elapsedRealtimeNanos(), Math.max(0L, timestampNs));
        float[] transform = new float[16];
        input.surfaceTexture.getTransformMatrix(transform);
        int slot = input.nextSlot();
        pairer.discardTextureSlot(eye, slot);
        gl.snapshotExternal(input.externalTexture, transform, input.snapshotTextures[slot]);
        CaptureFrame capture = correlation.match(timestampNs, 20L);
        if (capture == null) {
            CaptureFrameTrace missingTrace = PackedStereoFramePairer.LEFT.equals(eye)
                    ? leftFrameTrace : rightFrameTrace;
            if (missingTrace != null) missingTrace.unmatchedConsume(traceEpoch);
            if (PackedStereoFramePairer.LEFT.equals(eye)) {
                leftUncorrelatedFrames++;
            } else {
                rightUncorrelatedFrames++;
            }
            return;
        }
        CaptureFrameTrace trace = PackedStereoFramePairer.LEFT.equals(eye)
                ? leftFrameTrace : rightFrameTrace;
        if (trace != null) {
            // Runtime tolerance is retained; an approximate match is not diagnostic frame identity.
            if (timestampNs == capture.sensorTimestampNs) trace.consumed(traceEpoch, capture.sourceFrame - 1L,
                    updateEntryNs, updateExitNs, Thread.currentThread().getId());
            else trace.approximateConsume(traceEpoch);
        }
        (PackedStereoFramePairer.LEFT.equals(eye) ? leftCorrelated : rightCorrelated)
                .observeAt(SystemClock.elapsedRealtimeNanos(), capture.sourceFrame);
        PackedStereoFramePairer.Pair pair = pairer.add(
                new PackedStereoFramePairer.Candidate(
                        eye,
                        capture.sourceFrame,
                        capture.sensorTimestampNs,
                        slot,
                        SystemClock.elapsedRealtimeNanos()),
                SystemClock.elapsedRealtimeNanos());
        if (pair != null) {
            recordAcceptedPair(pair);
            composePair(gl, pair);
        }
    }

    private void renderSynthetic(GlState gl, SyntheticRequest request) throws Exception {
        gl.makePbufferCurrent();
        int leftSlot = gl.leftInput.nextSlot();
        int rightSlot = gl.rightInput.nextSlot();
        pairer.discardTextureSlot(PackedStereoFramePairer.LEFT, leftSlot);
        pairer.discardTextureSlot(PackedStereoFramePairer.RIGHT, rightSlot);
        gl.drawSynthetic(gl.leftInput.snapshotTextures[leftSlot], true, request.sourceFrame);
        gl.drawSynthetic(gl.rightInput.snapshotTextures[rightSlot], false, request.sourceFrame);
        long queuedNs = SystemClock.elapsedRealtimeNanos();
        pairer.add(
                new PackedStereoFramePairer.Candidate(
                        PackedStereoFramePairer.LEFT,
                        request.sourceFrame,
                        request.leftTimestampNs,
                        leftSlot,
                        queuedNs),
                queuedNs);
        PackedStereoFramePairer.Pair pair = pairer.add(
                new PackedStereoFramePairer.Candidate(
                        PackedStereoFramePairer.RIGHT,
                        request.sourceFrame,
                        request.rightTimestampNs,
                        rightSlot,
                        queuedNs),
                SystemClock.elapsedRealtimeNanos());
        if (pair != null) {
            recordAcceptedPair(pair);
            syntheticFrames++;
            composePair(gl, pair);
        }
    }

    // Capture-only path: no encoder window submission on this actor. It intentionally does not call
    // onPairPresented: native producer-fence polling supplies image publication.
    private boolean composePairIntoPool(GlState gl, PackedStereoFramePairer.Pair pair,
            PackedStereoPoolExecutor.Pool pool) throws Exception {
        PackedStereoPoolExecutor.Write write = pool.beginWrite();
        if (write == null) {
            poolBeginNoCapacity.observeAt(SystemClock.elapsedRealtimeNanos(), pair.pairId);
            return false;
        }
        poolBeginAccepted.observeAt(SystemClock.elapsedRealtimeNanos(), pair.pairId);
        boolean pendingRegistered = false;
        try {
            gl.composeIntoFramebuffer(write.framebuffer,
                    gl.leftInput.snapshotTextures[pair.left.textureSlot],
                    gl.rightInput.snapshotTextures[pair.right.textureSlot]);
            pool.finishWrite(write, new PackedStereoPoolExecutor.PairIdentity(pair.pairId,
                    pair.left.sourceFrame, pair.right.sourceFrame,
                    pair.left.sensorTimestampNs, pair.right.sensorTimestampNs));
            producerFenceSubmitted.observeAt(SystemClock.elapsedRealtimeNanos(), pair.pairId);
            pendingRegistered = true;
            return true; // producer Pending registered, not frame ready/terminal
        } finally {
            if (!pendingRegistered) pool.quarantineWrite(write, "pack-or-fence-export-failed");
        }
    }

    private void composePair(GlState gl, PackedStereoFramePairer.Pair pair) throws Exception {
        long startNs = SystemClock.elapsedRealtimeNanos();
        int leftTexture = gl.leftInput.snapshotTextures[pair.left.textureSlot];
        int rightTexture = gl.rightInput.snapshotTextures[pair.right.textureSlot];
        long presentationNs = Math.max(
                pair.left.sensorTimestampNs,
                pair.right.sensorTimestampNs);
        if (activePool != null) {
            if (!composePairIntoPool(gl, pair, activePool)) return;
        } else {
            gl.compose(leftTexture, rightTexture, presentationNs);
        }
        long elapsedNs = Math.max(0L, SystemClock.elapsedRealtimeNanos() - startNs);
        composedFrames++;
        compositionFreshness.progress(SystemClock.elapsedRealtime());
        compositorTimeTotalNs += elapsedNs;
        compositorTimeMaxNs = Math.max(compositorTimeMaxNs, elapsedNs);
        if (activePool == null) listener.onPairPresented(pair, presentationNs / 1_000L);
    }

    private void recordAcceptedPair(PackedStereoFramePairer.Pair pair) {
        CaptureFrameTrace.paired(leftFrameTrace, pair.left.sourceFrame - 1L,
                rightFrameTrace, pair.right.sourceFrame - 1L, pair.pairId);
        lastPairLeftFrame = pair.left.sourceFrame;
        lastPairRightFrame = pair.right.sourceFrame;
        lastPairLeftSensorNs = pair.left.sensorTimestampNs;
        lastPairRightSensorNs = pair.right.sensorTimestampNs;
        pairsAccepted.observeAt(SystemClock.elapsedRealtimeNanos(), pair.pairId);
    }

    private void drainPoolOnCaptureContext(PackedStereoPoolExecutor.Pool pool, GlState gl) {
        try {
            gl.makePbufferCurrent();
            cleanupBarrier = CleanupBarrier.NATIVE_POOL_PENDING;
            pool.stopAccepting();
            while (!pool.retireStopped()) {
                pool.pollReady();
                // A timeout only schedules another observation, never a release.
                try { synchronized (signal) { signal.wait(50L); } }
                catch (InterruptedException ignored) { /* keep the physical ownership actor */ }
            }
            cleanupBarrier = CleanupBarrier.CAMERA_CALLBACKS_PENDING;
            while (!listener.canRetireCaptureInputs()) {
                try { synchronized (signal) { signal.wait(50L); } }
                catch (InterruptedException ignored) { /* actual camera callbacks own the barrier */ }
            }
            cleanupBarrier = CleanupBarrier.CAPTURE_CONTEXT_PENDING;
            gl.closeCaptureContext(); quarantinedGl = null; physicallyRetired = true;
            cleanupBarrier = CleanupBarrier.TERMINAL;
        } catch (Throwable pendingFailure) {
            cleanupRejected = true;
            // Preserve resources and an explicit nonterminal barrier after an uncertain platform failure.
            notifyFailure(pendingFailure);
        }
    }

    private CaptureCorrelation correlation(String eye) {
        if (PackedStereoFramePairer.LEFT.equals(eye)) {
            return leftCorrelation;
        }
        if (PackedStereoFramePairer.RIGHT.equals(eye)) {
            return rightCorrelation;
        }
        throw new IllegalArgumentException("unsupported eye " + eye);
    }

    private void notifyFailure(Throwable error) {
        try {
            listener.onCompositorFailure(error);
        } catch (Throwable ignored) {
            // Runtime failure evidence remains owned by the source runtime.
        }
    }

    private static final class SyntheticRequest {
        final long sourceFrame;
        final long leftTimestampNs;
        final long rightTimestampNs;

        SyntheticRequest(long sourceFrame, long leftTimestampNs, long rightTimestampNs) {
            this.sourceFrame = sourceFrame;
            this.leftTimestampNs = leftTimestampNs;
            this.rightTimestampNs = rightTimestampNs;
        }
    }

    private static final class CaptureFrame {
        final long sourceFrame;
        final long sensorTimestampNs;

        CaptureFrame(long sourceFrame, long sensorTimestampNs) {
            this.sourceFrame = sourceFrame;
            this.sensorTimestampNs = sensorTimestampNs;
        }
    }

    private static final class CaptureCorrelation {
        private final ArrayDeque<CaptureFrame> captures = new ArrayDeque<>();

        synchronized void record(long sourceFrame, long sensorTimestampNs) {
            if (sourceFrame <= 0L || sensorTimestampNs <= 0L) {
                return;
            }
            captures.addLast(new CaptureFrame(sourceFrame, sensorTimestampNs));
            while (captures.size() > 16) {
                captures.removeFirst();
            }
            notifyAll();
        }

        synchronized CaptureFrame match(long surfaceTimestampNs, long waitMs)
                throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + waitMs;
            while (true) {
                CaptureFrame best = null;
                long bestDelta = Long.MAX_VALUE;
                for (CaptureFrame capture : captures) {
                    long delta = capture.sensorTimestampNs >= surfaceTimestampNs
                            ? capture.sensorTimestampNs - surfaceTimestampNs
                            : surfaceTimestampNs - capture.sensorTimestampNs;
                    if (delta < bestDelta) {
                        best = capture;
                        bestDelta = delta;
                    }
                }
                if (best != null && bestDelta <= 2_000_000L) {
                    captures.remove(best);
                    return best;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    return null;
                }
                wait(remaining);
            }
        }
    }

    private static final class InputState implements Closeable {
        final int externalTexture;
        final SurfaceTexture surfaceTexture;
        final Surface cameraSurface;
        final int[] snapshotTextures = new int[RING_SIZE];
        private int nextSlot;

        InputState(int width, int height, boolean cameraInput) {
            externalTexture = cameraInput ? createExternalTexture() : 0;
            if (cameraInput) {
                surfaceTexture = new SurfaceTexture(externalTexture);
                surfaceTexture.setDefaultBufferSize(width, height);
                cameraSurface = new Surface(surfaceTexture);
            } else {
                surfaceTexture = null;
                cameraSurface = null;
            }
            GLES20.glGenTextures(RING_SIZE, snapshotTextures, 0);
            for (int texture : snapshotTextures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_RGBA,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        null);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        int nextSlot() {
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % RING_SIZE;
            return slot;
        }

        @Override
        public void close() {
            if (cameraSurface != null) {
                cameraSurface.release();
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
            }
            if (externalTexture != 0) {
                GLES20.glDeleteTextures(1, new int[] {externalTexture}, 0);
            }
            GLES20.glDeleteTextures(snapshotTextures.length, snapshotTextures, 0);
        }

        private static int createExternalTexture() {
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture[0]);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
            return texture[0];
        }
    }

    private static final class GlState implements Closeable {
        private static final float[] QUAD = {
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
        };

        final PackedStereoStreamMetadata.Layout layout;
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface pbufferSurface;
        final EGLSurface encoderSurface;
        final InputState leftInput;
        final InputState rightInput;
        final int oesProgram;
        final int textureProgram;
        final int framebuffer;
        final java.nio.FloatBuffer quadBuffer;

        GlState(
                PackedStereoStreamMetadata.Layout layout,
                Surface encoderInputSurface,
                boolean cameraInput) {
            this.layout = layout;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            require(EGL14.eglInitialize(display, versions, 0, versions, 1), "eglInitialize");
            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            require(EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.length,
                    configCount,
                    0) && configCount[0] > 0, "eglChooseConfig");
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0);
            require(context != null && context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
            int[] pbufferAttributes = {
                    EGL14.EGL_WIDTH, layout.perEyeWidth,
                    EGL14.EGL_HEIGHT, layout.perEyeHeight,
                    EGL14.EGL_NONE
            };
            pbufferSurface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttributes, 0);
            int[] windowAttributes = {EGL14.EGL_NONE};
            encoderSurface = encoderInputSurface == null ? EGL14.EGL_NO_SURFACE : EGL14.eglCreateWindowSurface(
                    display, configs[0], encoderInputSurface, windowAttributes, 0);
            makePbufferCurrent();
            oesProgram = createProgram(
                    vertexShader(),
                    "#extension GL_OES_EGL_image_external : require\n"
                            + "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTexture;"
                            + "void main(){ gl_FragColor=texture2D(uTexture,vUv); }");
            textureProgram = createProgram(
                    vertexShader(),
                    "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture;"
                            + "void main(){ gl_FragColor=texture2D(uTexture,vUv); }");
            int[] framebuffers = new int[1];
            GLES20.glGenFramebuffers(1, framebuffers, 0);
            framebuffer = framebuffers[0];
            quadBuffer = java.nio.ByteBuffer
                    .allocateDirect(QUAD.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
            quadBuffer.put(QUAD).position(0);
            leftInput = new InputState(layout.perEyeWidth, layout.perEyeHeight, cameraInput);
            rightInput = new InputState(layout.perEyeWidth, layout.perEyeHeight, cameraInput);
        }

        void makePbufferCurrent() {
            require(EGL14.eglMakeCurrent(
                    display,
                    pbufferSurface,
                    pbufferSurface,
                    context), "eglMakeCurrent pbuffer");
        }

        void snapshotExternal(int externalTexture, float[] transform, int targetTexture) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            checkFramebuffer();
            GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
            drawTexture(oesProgram, GL_TEXTURE_EXTERNAL_OES, externalTexture, transform);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        void drawSynthetic(int targetTexture, boolean left, long frame) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            checkFramebuffer();
            GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
            if (left) {
                GLES20.glClearColor(0.85f, 0.06f, 0.03f, 1f);
            } else {
                GLES20.glClearColor(0.03f, 0.12f, 0.85f, 1f);
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            int moving = (int) (frame % Math.max(1, layout.perEyeWidth - 24));
            GLES20.glScissor(moving, 16, 24, Math.max(1, layout.perEyeHeight - 32));
            GLES20.glClearColor(left ? 1f : 0f, 1f, left ? 0f : 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glScissor(
                    left ? layout.perEyeWidth - 4 : 0,
                    0,
                    4,
                    layout.perEyeHeight);
            GLES20.glClearColor(1f, 1f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        // New capture-only caller. Pool ticket/metadata must be registered by
        // the injected native executor; this method cannot publish an image.
        void composeIntoFramebuffer(int outputFramebuffer, int leftTexture, int rightTexture) {
            require(outputFramebuffer > 0, "invalid packed framebuffer");
            makePbufferCurrent();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer);
            try {
                require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                        == GLES20.GL_FRAMEBUFFER_COMPLETE, "packed framebuffer incomplete");
                GLES20.glViewport(0, 0, layout.packedWidth, layout.packedHeight);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
                drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, leftTexture, identity());
                GLES20.glViewport(layout.perEyeWidth, 0, layout.perEyeWidth, layout.perEyeHeight);
                drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, rightTexture, identity());
                require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "packed draw failed");
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
        }

        void compose(int leftTexture, int rightTexture, long presentationNs) {
            require(encoderSurface != EGL14.EGL_NO_SURFACE, "legacy encoder Surface absent");
            require(EGL14.eglMakeCurrent(
                    display,
                    encoderSurface,
                    encoderSurface,
                    context), "eglMakeCurrent encoder");
            GLES20.glViewport(0, 0, layout.packedWidth, layout.packedHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
            drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, leftTexture, identity());
            GLES20.glViewport(
                    layout.perEyeWidth,
                    0,
                    layout.perEyeWidth,
                    layout.perEyeHeight);
            drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, rightTexture, identity());
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, presentationNs);
            require(EGL14.eglSwapBuffers(display, encoderSurface), "eglSwapBuffers");
        }

        private void drawTexture(int program, int target, int texture, float[] matrix) {
            GLES20.glUseProgram(program);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int texCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            int matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix");
            int textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
            quadBuffer.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
            GLES20.glEnableVertexAttribArray(position);
            quadBuffer.position(2);
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, texture);
            GLES20.glUniform1i(textureLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindTexture(target, 0);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(texCoord);
        }

        @Override
        public void close() { destroyContext(false); }
        void closeCaptureContext() { destroyContext(true); }
        private void destroyContext(boolean preserveProcessDisplay) {
            try {
                makePbufferCurrent();
                leftInput.close();
                rightInput.close();
                GLES20.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
                GLES20.glDeleteProgram(oesProgram);
                GLES20.glDeleteProgram(textureProgram);
            } catch (Throwable ignored) {
            }
            EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            boolean windowClosed = encoderSurface == EGL14.EGL_NO_SURFACE
                    || EGL14.eglDestroySurface(display, encoderSurface);
            boolean pbufferClosed = EGL14.eglDestroySurface(display, pbufferSurface);
            boolean contextClosed = EGL14.eglDestroyContext(display, context);
            if (preserveProcessDisplay) {
                require(windowClosed && pbufferClosed && contextClosed, "capture EGL retirement");
                EGL14.eglReleaseThread();
            } else EGL14.eglTerminate(display);
        }

        private static String vertexShader() {
            return "attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uTexMatrix;"
                    + "varying vec2 vUv; void main(){ gl_Position=vec4(aPosition,0.0,1.0);"
                    + "vUv=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }";
        }

        private static int createProgram(String vertex, String fragment) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            if (status[0] == 0) {
                throw new IllegalStateException("GL program link failed: " + GLES20.glGetProgramInfoLog(program));
            }
            return program;
        }

        private static int compileShader(int kind, String source) {
            int shader = GLES20.glCreateShader(kind);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException("GL shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        private static void checkFramebuffer() {
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("incomplete GL framebuffer " + status);
            }
        }

        private static float[] identity() {
            return new float[] {
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f
            };
        }

        private static void require(boolean condition, String label) {
            if (!condition) {
                throw new IllegalStateException(label + " failed eglError=0x"
                        + Integer.toHexString(EGL14.eglGetError()));
            }
        }
    }
}
