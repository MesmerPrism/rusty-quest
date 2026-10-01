package io.github.mesmerprism.rustyquest.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.util.Log;
import android.view.Surface;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** App-owned capture. Peer subscriptions never own these cameras or compositor. */
public final class PackedStereoCaptureOwner {
    private static final AtomicLong NEXT_CAPTURE_INSTANCE = new AtomicLong();
    public interface EncoderConsumer { void offer(PackedStereoEncoderInput frame); }
    private final Context context;
    private final PackedStereoStreamMetadata.Layout layout;
    private final String leftId, rightId;
    private final int frameRate;
    private final PackedStereoPoolExecutor poolExecutor;
    private final long captureInstance = NEXT_CAPTURE_INSTANCE.incrementAndGet();
    private final CaptureFrameTrace leftFrameTrace = new CaptureFrameTrace();
    private final CaptureFrameTrace rightFrameTrace = new CaptureFrameTrace();
    /** Observation-only epoch boundary; never changes camera requests or media state. */
    public void armDiagnosticTrace(String processEpoch, long appGeneration, long armGeneration) {
        if (processEpoch == null || processEpoch.isEmpty() || appGeneration <= 0L || armGeneration <= 0L)
            throw new IllegalArgumentException("diagnostic epoch unavailable");
        long boundary = android.os.SystemClock.elapsedRealtimeNanos();
        leftFrameTrace.arm(processEpoch, appGeneration, armGeneration, boundary);
        rightFrameTrace.arm(processEpoch, appGeneration, armGeneration, boundary);
    }
    private final Object subscriptionLock = new Object();
    private EncoderConsumer encoderConsumer;
    private long encoderGeneration;
    private volatile PackedStereoGlCompositor compositor;
    private volatile HandlerThread cameraThread;
    private volatile Endpoint left, right;
    private volatile boolean stopRequested, started, startupSettled;
    private volatile Throwable failure;
    // Closed first-fault observation, published before teardown. A late camera callback
    // cannot replace the reason that first stopped the shared capture owner.
    static final int FAILURE_NONE = 0, FAILURE_COMPOSITOR = 1,
            FAILURE_CAMERA_DISCONNECTED = 2, FAILURE_CAMERA_ERROR = 3,
            FAILURE_SESSION_REJECTED = 4, FAILURE_STARTUP = 5;
    // Exact native source-publication vocabulary; zero means an unclassified
    // failure and never grants permission to ignore it.
    static final int DETAIL_OTHER = 0, DETAIL_PUBLICATION_REPLAY = 1,
            DETAIL_PUBLICATION_REGRESSED_CLOCK = 2, DETAIL_PUBLICATION_UNBOUND = 3,
            DETAIL_PUBLICATION_IDENTITY = 4;
    private static final class FirstFailure {
        final int originCode, causeCode, detailCode;
        final long elapsedNs;
        FirstFailure(int originCode, int causeCode, int detailCode, long elapsedNs) {
            this.originCode = originCode; this.causeCode = causeCode;
            this.detailCode = detailCode; this.elapsedNs = elapsedNs;
        }
    }
    /** Camera2 callback cadence, including the exact results bracketing its worst arrival gap.
     * This is diagnostic only: sensor timestamps and callback elapsed times stay in their
     * separate clock domains, and no observation changes capture or source readiness. */
    static final class CameraResultCadence {
        private long count, lastNs, maxGapNs, lastIdentity;
        private long lastFrameNumber, lastSensorNs;
        private boolean lastSensorPresent, worstPresent;
        private long worstFromNs, worstToNs, worstFromFrame, worstToFrame;
        private long worstFromSensorNs, worstToSensorNs;
        private boolean worstFromSensorPresent, worstToSensorPresent;
        private boolean successorPending, successorPresent;
        private long successorNs, successorFrame, successorSensorNs;
        private boolean successorSensorPresent;
        private boolean largestSensorGapPresent;
        private long largestSensorGapNs, sensorGapFromNs, sensorGapToNs;
        private long sensorGapFromFrame, sensorGapToFrame;
        private long sensorGapFromTimestampNs, sensorGapToTimestampNs;
        private long lastExposureNs, lastFrameDurationNs;
        private long worstFromExposureNs, worstToExposureNs, successorExposureNs;
        private long worstFromFrameDurationNs, worstToFrameDurationNs, successorFrameDurationNs;
        private long sensorGapFromExposureNs, sensorGapToExposureNs;
        private long sensorGapFromFrameDurationNs, sensorGapToFrameDurationNs;

        synchronized void observeAt(long elapsedNs, long sourceFrame,
                long frameNumber, Long sensorTimestampNs) {
            observeAt(elapsedNs, sourceFrame, frameNumber,
                    sensorTimestampNs != null && sensorTimestampNs > 0L,
                    sensorTimestampNs == null ? 0L : sensorTimestampNs.longValue(), 0L, 0L);
        }

        synchronized void observeAt(long elapsedNs, long sourceFrame,
                long frameNumber, Long sensorTimestampNs, Long exposureTimeNs,
                Long frameDurationNs) {
            observeAt(elapsedNs, sourceFrame, frameNumber,
                    sensorTimestampNs != null && sensorTimestampNs > 0L,
                    sensorTimestampNs == null ? 0L : sensorTimestampNs.longValue(),
                    exposureTimeNs == null ? 0L : exposureTimeNs.longValue(),
                    frameDurationNs == null ? 0L : frameDurationNs.longValue());
        }

        synchronized void observeAt(long elapsedNs, long sourceFrame,
                long frameNumber, long sensorTimestampNs) {
            observeAt(elapsedNs, sourceFrame, frameNumber,
                    sensorTimestampNs > 0L, sensorTimestampNs, 0L, 0L);
        }

        private void observeAt(long elapsedNs, long sourceFrame,
                long frameNumber, boolean suppliedSensorPresent, long suppliedSensorNs,
                long suppliedExposureNs, long suppliedFrameDurationNs) {
            // Keep the legacy StageCadence validation and count semantics.
            if (elapsedNs <= 0L || sourceFrame < 0L) return;
            count++;
            if (elapsedNs < lastNs) return;
            boolean sensorPresent = suppliedSensorPresent && suppliedSensorNs > 0L;
            long sensorNs = sensorPresent ? suppliedSensorNs : 0L;
            long exposureNs = Math.max(0L, suppliedExposureNs);
            long frameDurationNs = Math.max(0L, suppliedFrameDurationNs);
            if (successorPending) {
                successorPending = false;
                successorPresent = true;
                successorNs = elapsedNs;
                successorFrame = frameNumber;
                successorSensorPresent = sensorPresent;
                successorSensorNs = sensorNs;
                successorExposureNs = exposureNs;
                successorFrameDurationNs = frameDurationNs;
            }
            if (lastNs > 0L) {
                long gapNs = elapsedNs - lastNs;
                if (gapNs > maxGapNs) {
                    maxGapNs = gapNs;
                    worstPresent = true;
                    worstFromNs = lastNs;
                    worstToNs = elapsedNs;
                    worstFromFrame = lastFrameNumber;
                    worstToFrame = frameNumber;
                    worstFromSensorPresent = lastSensorPresent;
                    worstToSensorPresent = sensorPresent;
                    worstFromSensorNs = lastSensorPresent ? lastSensorNs : 0L;
                    worstToSensorNs = sensorNs;
                    worstFromExposureNs = lastExposureNs;
                    worstToExposureNs = exposureNs;
                    worstFromFrameDurationNs = lastFrameDurationNs;
                    worstToFrameDurationNs = frameDurationNs;
                    successorPending = true;
                    successorPresent = false;
                    successorNs = successorFrame = successorSensorNs = 0L;
                    successorSensorPresent = false;
                    successorExposureNs = successorFrameDurationNs = 0L;
                }
                if (lastSensorPresent && sensorPresent && frameNumber > lastFrameNumber
                        && sensorNs > lastSensorNs) {
                    long sensorGapNs = sensorNs - lastSensorNs;
                    if (sensorGapNs > largestSensorGapNs) {
                        largestSensorGapPresent = true;
                        largestSensorGapNs = sensorGapNs;
                        sensorGapFromNs = lastNs;
                        sensorGapToNs = elapsedNs;
                        sensorGapFromFrame = lastFrameNumber;
                        sensorGapToFrame = frameNumber;
                        sensorGapFromTimestampNs = lastSensorNs;
                        sensorGapToTimestampNs = sensorNs;
                        sensorGapFromExposureNs = lastExposureNs;
                        sensorGapToExposureNs = exposureNs;
                        sensorGapFromFrameDurationNs = lastFrameDurationNs;
                        sensorGapToFrameDurationNs = frameDurationNs;
                    }
                }
            }
            lastNs = elapsedNs;
            lastIdentity = sourceFrame;
            lastFrameNumber = frameNumber;
            lastSensorPresent = sensorPresent;
            lastSensorNs = sensorNs;
            lastExposureNs = exposureNs;
            lastFrameDurationNs = frameDurationNs;
        }

        synchronized JSONObject snapshot(long sampleNs) throws Exception {
            long ageNs = lastNs == 0L || sampleNs < lastNs ? -1L : sampleNs - lastNs;
            JSONObject worst = new JSONObject()
                    .put("present", worstPresent)
                    .put("from_callback_elapsed_ns", worstPresent ? worstFromNs : 0L)
                    .put("to_callback_elapsed_ns", worstPresent ? worstToNs : 0L)
                    .put("from_frame_number", worstPresent ? worstFromFrame : 0L)
                    .put("to_frame_number", worstPresent ? worstToFrame : 0L)
                    .put("from_sensor_timestamp_present", worstPresent && worstFromSensorPresent)
                    .put("to_sensor_timestamp_present", worstPresent && worstToSensorPresent)
                    .put("from_sensor_timestamp_ns", worstPresent ? worstFromSensorNs : 0L)
                    .put("to_sensor_timestamp_ns", worstPresent ? worstToSensorNs : 0L)
                    .put("successor_present", worstPresent && successorPresent)
                    .put("successor_callback_elapsed_ns", successorPresent ? successorNs : 0L)
                    .put("successor_frame_number", successorPresent ? successorFrame : 0L)
                    .put("successor_sensor_timestamp_present", successorPresent && successorSensorPresent)
                    .put("successor_sensor_timestamp_ns", successorPresent ? successorSensorNs : 0L)
                    .put("from_exposure_time_present", worstPresent && worstFromExposureNs > 0L)
                    .put("from_exposure_time_ns", worstPresent ? worstFromExposureNs : 0L)
                    .put("to_exposure_time_present", worstPresent && worstToExposureNs > 0L)
                    .put("to_exposure_time_ns", worstPresent ? worstToExposureNs : 0L)
                    .put("successor_exposure_time_present", successorPresent && successorExposureNs > 0L)
                    .put("successor_exposure_time_ns", successorPresent ? successorExposureNs : 0L)
                    .put("from_frame_duration_present", worstPresent && worstFromFrameDurationNs > 0L)
                    .put("from_frame_duration_ns", worstPresent ? worstFromFrameDurationNs : 0L)
                    .put("to_frame_duration_present", worstPresent && worstToFrameDurationNs > 0L)
                    .put("to_frame_duration_ns", worstPresent ? worstToFrameDurationNs : 0L)
                    .put("successor_frame_duration_present", successorPresent && successorFrameDurationNs > 0L)
                    .put("successor_frame_duration_ns", successorPresent ? successorFrameDurationNs : 0L);
            JSONObject sensorGap = new JSONObject()
                    .put("present", largestSensorGapPresent)
                    .put("gap_ns", largestSensorGapPresent ? largestSensorGapNs : 0L)
                    .put("from_callback_elapsed_ns", largestSensorGapPresent ? sensorGapFromNs : 0L)
                    .put("to_callback_elapsed_ns", largestSensorGapPresent ? sensorGapToNs : 0L)
                    .put("from_frame_number", largestSensorGapPresent ? sensorGapFromFrame : 0L)
                    .put("to_frame_number", largestSensorGapPresent ? sensorGapToFrame : 0L)
                    .put("from_sensor_timestamp_ns", largestSensorGapPresent ? sensorGapFromTimestampNs : 0L)
                    .put("to_sensor_timestamp_ns", largestSensorGapPresent ? sensorGapToTimestampNs : 0L)
                    .put("from_exposure_time_present", largestSensorGapPresent && sensorGapFromExposureNs > 0L)
                    .put("from_exposure_time_ns", largestSensorGapPresent ? sensorGapFromExposureNs : 0L)
                    .put("to_exposure_time_present", largestSensorGapPresent && sensorGapToExposureNs > 0L)
                    .put("to_exposure_time_ns", largestSensorGapPresent ? sensorGapToExposureNs : 0L)
                    .put("from_frame_duration_present", largestSensorGapPresent && sensorGapFromFrameDurationNs > 0L)
                    .put("from_frame_duration_ns", largestSensorGapPresent ? sensorGapFromFrameDurationNs : 0L)
                    .put("to_frame_duration_present", largestSensorGapPresent && sensorGapToFrameDurationNs > 0L)
                    .put("to_frame_duration_ns", largestSensorGapPresent ? sensorGapToFrameDurationNs : 0L);
            return new JSONObject().put("count", count).put("last_elapsed_ns", lastNs)
                    .put("max_gap_ns", maxGapNs).put("age_ns", ageNs)
                    .put("last_identity", lastIdentity).put("worst_gap", worst)
                    .put("max_adjacent_sensor_gap", sensorGap);
        }
    }
    /** Camera2 failure callbacks are counted, never converted into capture-owner failure. */
    static final class CameraFailureCadence {
        private long count, lastElapsedNs, lastFrameNumber;
        private int lastReason;
        synchronized void observeAt(long elapsedNs, long frameNumber, int reason) {
            count++;
            if (elapsedNs > 0L && elapsedNs >= lastElapsedNs) {
                lastElapsedNs = elapsedNs;
                lastFrameNumber = frameNumber;
                lastReason = reason;
            }
        }
        synchronized JSONObject snapshot() throws Exception {
            return new JSONObject().put("count", count).put("last_elapsed_ns", lastElapsedNs)
                    .put("last_frame_number", lastFrameNumber).put("last_reason", lastReason);
        }
    }
    private volatile FirstFailure firstFailure;
    private final CameraResultCadence leftCameraResults = new CameraResultCadence();
    private final CameraResultCadence rightCameraResults = new CameraResultCadence();
    private final CameraResultCadence leftCameraStarted = new CameraResultCadence();
    private final CameraResultCadence rightCameraStarted = new CameraResultCadence();
    private final CameraFailureCadence leftCameraFailed = new CameraFailureCadence();
    private final CameraFailureCadence rightCameraFailed = new CameraFailureCadence();
    private volatile int leftTimestampSource = -1, rightTimestampSource = -1;
    private volatile boolean leftFpsRangePresent, rightFpsRangePresent;
    private volatile int leftFpsLower, leftFpsUpper, rightFpsLower, rightFpsUpper;
    private final PackedStereoGlCompositor.StageCadence leftCameraMetadata =
            new PackedStereoGlCompositor.StageCadence();
    private final PackedStereoGlCompositor.StageCadence rightCameraMetadata =
            new PackedStereoGlCompositor.StageCadence();

    public PackedStereoCaptureOwner(Context context, int eyeWidth, int eyeHeight,
            int frameRate, String leftId, String rightId, long maxPairDeltaNs,
            PackedStereoPoolExecutor poolExecutor) {
        if (context == null || poolExecutor == null || eyeWidth <= 0 || eyeHeight <= 0
                || eyeWidth > Integer.MAX_VALUE / 2 || frameRate <= 0
                || leftId == null || leftId.isEmpty() || rightId == null || rightId.isEmpty()
                || leftId.equals(rightId) || maxPairDeltaNs <= 0)
            throw new IllegalArgumentException("invalid app capture binding");
        this.context = context.getApplicationContext();
        this.layout = new PackedStereoStreamMetadata.Layout(eyeWidth * 2, eyeHeight,
                eyeWidth, eyeHeight, maxPairDeltaNs);
        this.frameRate = frameRate; this.leftId = leftId; this.rightId = rightId;
        this.poolExecutor = poolExecutor;
    }

    /** Call on the app's capture-control worker, never an Activity or camera callback. */
    public void start() throws Exception {
        synchronized (this) {
            if (started || stopRequested) throw new IllegalStateException("capture owner already used");
            started = true;
        }
        try {
            compositor = new PackedStereoGlCompositor(layout, poolExecutor,
                    new PackedStereoGlCompositor.Listener() {
                        public void onPairPresented(PackedStereoFramePairer.Pair pair, long ptsUs) {
                            throw new IllegalStateException("capture owner cannot publish encoder metadata");
                        }
                        public void onCompositorFailure(Throwable error) { fail(error, FAILURE_COMPOSITOR); }
                        public boolean canRetireCaptureInputs() {
                            Endpoint a = left, b = right;
                            return startupSettled && (a == null || a.retired()) && (b == null || b.retired());
                        }
                    }, leftFrameTrace, rightFrameTrace);
            compositor.awaitStarted();
            poolExecutor.awaitCameraOwnership();
            if (stopRequested) throw new IllegalStateException("capture stopped during startup");
            cameraThread = new HandlerThread("rusty-app-stereo-capture-camera2");
            cameraThread.start();
            Handler handler = new Handler(cameraThread.getLooper());
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) throw new IllegalStateException("CameraManager unavailable");
            // Publish each endpoint BEFORE a platform call: late callbacks remain accounted for.
            left = new Endpoint(leftId, PackedStereoFramePairer.LEFT);
            left.open(manager, compositor.leftCameraSurface(), handler);
            right = new Endpoint(rightId, PackedStereoFramePairer.RIGHT);
            right.open(manager, compositor.rightCameraSurface(), handler);
        } catch (Exception error) {
            fail(error, FAILURE_STARTUP);
            throw error;
        } finally { startupSettled = true; }
    }

    /** App registry supplies its current, preissued peer-consumer generation. */
    public void attachEncoder(long generation, EncoderConsumer consumer) {
        if (generation <= 0 || consumer == null) throw new IllegalArgumentException("encoder subscription");
        synchronized (subscriptionLock) {
            if (stopRequested || encoderConsumer != null || generation <= encoderGeneration)
                throw new IllegalStateException("encoder subscription unavailable or stale");
            encoderGeneration = generation; encoderConsumer = consumer;
        }
    }

    /** Fences only the matching peer subscription; Own capture remains live. */
    public void detachEncoder(long generation) {
        synchronized (subscriptionLock) {
            if (generation == encoderGeneration) encoderConsumer = null;
        }
    }

    /** Native producer readiness transfers a SECOND content lease here. */
    public void offerEncoderFrame(PackedStereoEncoderInput frame) {
        if (frame == null) throw new NullPointerException("frame");
        EncoderConsumer target;
        synchronized (subscriptionLock) { target = stopRequested ? null : encoderConsumer; }
        // The detached consumer must fence late offers itself. No platform callback under the lock.
        if (target == null) frame.releaseUnsubmitted();
        else target.offer(frame);
    }

    public boolean fresh() {
        PackedStereoGlCompositor current = compositor;
        return !stopRequested && failure == null && current != null
                && current.compositionFreshNow()
                && poolExecutor.ownImageFresh();
    }

    /** Failure-only observation: running=1, no-failure=2, composition-fresh=4,
     * own-image-fresh=8, own-image-query-completed=16. No bit grants readiness. */
    int freshnessDiagnosticMask() {
        int mask = 0;
        if (!stopRequested) mask |= 1;
        if (failure == null) mask |= 2;
        PackedStereoGlCompositor current = compositor;
        if (current != null && current.compositionFreshNow())
            mask |= 4;
        try {
            if (poolExecutor.ownImageFresh()) mask |= 8;
            mask |= 16;
        } catch (Throwable ignored) {
            // An unavailable native observation cannot be treated as fresh.
        }
        return mask;
    }
    public boolean matchesConfiguration(int width, int height, int rate,
            String leftCamera, String rightCamera, long deltaNs) {
        return layout.perEyeWidth == width && layout.perEyeHeight == height && frameRate == rate
                && leftId.equals(leftCamera) && rightId.equals(rightCamera)
                && layout.maxPairDeltaNs == deltaNs;
    }
    public Throwable failure() { return failure; }
    int firstFailureOriginCode() {
        FirstFailure observed = firstFailure;
        return observed == null ? FAILURE_NONE : observed.originCode;
    }
    int firstFailureCauseCode() {
        FirstFailure observed = firstFailure;
        return observed == null ? 0 : observed.causeCode;
    }
    int firstFailureDetailCode() {
        FirstFailure observed = firstFailure;
        return observed == null ? DETAIL_OTHER : observed.detailCode;
    }

    // Stable numeric classes only. Exception text and stack traces remain outside
    // the bounded diagnostic and source-failure records.
    private static int causeCode(Throwable error) {
        if (error instanceof IllegalStateException) return 1;
        if (error instanceof IllegalArgumentException) return 2;
        if (error instanceof OutOfMemoryError) return 3;
        if (error instanceof Error) return 4;
        if (error instanceof RuntimeException) return 5;
        return 6;
    }
    private static int detailCode(Throwable error) {
        if (!(error instanceof IllegalStateException)) return DETAIL_OTHER;
        String exact = error.getMessage();
        if ("source publication Replay".equals(exact)) return DETAIL_PUBLICATION_REPLAY;
        if ("source publication RegressedClock".equals(exact)) return DETAIL_PUBLICATION_REGRESSED_CLOCK;
        if ("source publication UnboundEpoch".equals(exact)) return DETAIL_PUBLICATION_UNBOUND;
        if ("source publication InvalidIdentity".equals(exact)) return DETAIL_PUBLICATION_IDENTITY;
        return DETAIL_OTHER;
    }

    /** Bounded observation only; no frame/native clock alignment or lifecycle authority. */
    public JSONObject diagnosticDropoutSnapshot() throws Exception {
        long sampleNs = android.os.SystemClock.elapsedRealtimeNanos();
        return new JSONObject().put("clock", "android_elapsedRealtimeNanos")
                .put("sample_elapsed_ns", sampleNs)
                .put("consistency", "per_eye_non_atomic")
                .put("capture_instance", captureInstance)
                .put("stop_requested", stopRequested).put("startup_settled", startupSettled)
                .put("left", leftFrameTrace.dropoutSnapshot(sampleNs))
                .put("right", rightFrameTrace.dropoutSnapshot(sampleNs));
    }

    /** Local diagnostic only. Each cadence is internally coherent; stages are sampled separately. */
    JSONObject captureDiagnosticSnapshot() throws Exception {
        long sampleNs = android.os.SystemClock.elapsedRealtimeNanos();
        PackedStereoGlCompositor current = compositor;
        FirstFailure first = firstFailure;
        JSONObject result = new JSONObject()
                .put("clock", "android_elapsedRealtimeNanos")
                .put("sample_elapsed_ns", sampleNs)
                .put("consistency", "per_stage_non_atomic")
                .put("capture_instance", captureInstance)
                .put("left_frame_trace", leftFrameTrace.snapshot())
                .put("right_frame_trace", rightFrameTrace.snapshot())
                .put("first_failure_origin_code", first == null ? FAILURE_NONE : first.originCode)
                .put("first_failure_cause_code", first == null ? 0 : first.causeCode)
                .put("first_failure_detail_code", first == null ? DETAIL_OTHER : first.detailCode)
                .put("first_failure_elapsed_ns", first == null ? 0L : first.elapsedNs)
                .put("left_camera_result", leftCameraResults.snapshot(sampleNs))
                .put("right_camera_result", rightCameraResults.snapshot(sampleNs))
                .put("left_camera_started", leftCameraStarted.snapshot(sampleNs))
                .put("right_camera_started", rightCameraStarted.snapshot(sampleNs))
                .put("left_camera_failed", leftCameraFailed.snapshot())
                .put("right_camera_failed", rightCameraFailed.snapshot())
                .put("left_camera_timestamp_source", leftTimestampSource)
                .put("right_camera_timestamp_source", rightTimestampSource)
                .put("requested_frame_rate", frameRate)
                .put("left_ae_fps_range_present", leftFpsRangePresent)
                .put("left_ae_fps_range_lower", leftFpsRangePresent ? leftFpsLower : 0)
                .put("left_ae_fps_range_upper", leftFpsRangePresent ? leftFpsUpper : 0)
                .put("right_ae_fps_range_present", rightFpsRangePresent)
                .put("right_ae_fps_range_lower", rightFpsRangePresent ? rightFpsLower : 0)
                .put("right_ae_fps_range_upper", rightFpsRangePresent ? rightFpsUpper : 0)
                .put("left_camera_metadata", leftCameraMetadata.snapshot(sampleNs))
                .put("right_camera_metadata", rightCameraMetadata.snapshot(sampleNs));
        if (current != null) result.put("compositor", current.captureDiagnosticSnapshot(sampleNs));
        return result;
    }

    /** Observation only. No callback flag, elapsed time or projection confers cleanup authority. */
    public static final class CleanupStatus {
        public final boolean stopRequested, startupSettled, cameraThreadAlive, compositorThreadAlive;
        public final boolean compositorPhysicallyRetired, compositorCleanupRejected;
        public final String leftCallbackBarrier, rightCallbackBarrier, compositorBarrier;
        private CleanupStatus(boolean stop, boolean startup, boolean cameraThread,
                boolean compositorThread, boolean compositorPhysical, boolean rejected,
                String left, String right, String barrier) {
            stopRequested=stop; startupSettled=startup; cameraThreadAlive=cameraThread;
            compositorThreadAlive=compositorThread; compositorPhysicallyRetired=compositorPhysical;
            compositorCleanupRejected=rejected; leftCallbackBarrier=left; rightCallbackBarrier=right;
            compositorBarrier=barrier;
        }
    }
    public CleanupStatus cleanupStatus() {
        Endpoint a=left, b=right; HandlerThread camera=cameraThread; PackedStereoGlCompositor gl=compositor;
        return new CleanupStatus(stopRequested,startupSettled,camera!=null&&camera.isAlive(),
                gl!=null&&!gl.isTerminated(),gl!=null&&gl.isPhysicallyRetired(),gl!=null&&gl.cleanupRejected(),
                a==null?"NOT_CREATED":a.callbackBarrier(),b==null?"NOT_CREATED":b.callbackBarrier(),
                gl==null?"NOT_CREATED":gl.cleanupBarrier());
    }

    /** Requests stop. Closed flags, elapsed time and joins never confer terminal status. */
    public void requestStop() {
        synchronized (this) {
            stopRequested = true;
            if (!started) startupSettled = true;
        }
        synchronized (subscriptionLock) { encoderConsumer = null; }
        Endpoint a = left, b = right;
        if (a != null) a.requestClose();
        if (b != null) b.requestClose();
        PackedStereoGlCompositor current = compositor;
        if (current != null) current.requestStop();
    }

    public boolean pollStopped() {
        if (!stopRequested || !startupSettled) return false;
        Endpoint a = left, b = right;
        if ((a != null && !a.retired()) || (b != null && !b.retired())) return false;
        PackedStereoGlCompositor current = compositor;
        if (current != null && !current.isPhysicallyRetired()) return false;
        HandlerThread handler = cameraThread;
        if (handler != null && handler.isAlive()) { handler.quitSafely(); return false; }
        return true;
    }

    private void fail(Throwable error, int originCode) {
        synchronized (this) {
            if (firstFailure == null && !stopRequested) {
                FirstFailure observed = new FirstFailure(originCode, causeCode(error), detailCode(error),
                        android.os.SystemClock.elapsedRealtimeNanos());
                failure = error;
                firstFailure = observed;
                // The event is emitted under the first-fault ordering lock, before
                // any competing callback can request capture teardown.
                try {
                    Log.i("RQSpatialCameraPanel", "channel=packed-capture status=owner-first-failure"
                            + " originCode=" + observed.originCode + " causeCode=" + observed.causeCode
                            + " detailCode=" + observed.detailCode
                            + " elapsedNs=" + observed.elapsedNs);
                } catch (RuntimeException ignored) {
                    // A logging failure cannot prevent physical cleanup.
                }
            }
        }
        requestStop();
    }

    private final class Endpoint {
        final String id, eye;
        final CountDownLatch opened = new CountDownLatch(1), configured = new CountDownLatch(1);
        volatile CameraDevice device;
        volatile CameraCaptureSession session;
        volatile boolean closeRequested, openRequested, openSettled, deviceClosed;
        volatile boolean sessionRequested, sessionSettled, sessionClosed, sessionConfigurationFailed;
        volatile Throwable error;
        Endpoint(String id, String eye) { this.id = id; this.eye = eye; }

        void open(CameraManager manager, Surface surface, Handler handler) throws Exception {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer rawTimestampSource = null;
            try {
                rawTimestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            } catch (RuntimeException ignored) {
                // Optional timing provenance cannot prevent opening a valid camera.
            }
            int timestampSource = rawTimestampSource == null ? -1
                    : rawTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
                        || rawTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                        ? rawTimestampSource : -2;
            if (PackedStereoFramePairer.LEFT.equals(eye)) leftTimestampSource = timestampSource;
            else rightTimestampSource = timestampSource;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            boolean exact = false;
            Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
            if (sizes != null) for (Size size : sizes)
                if (size.getWidth() == layout.perEyeWidth && size.getHeight() == layout.perEyeHeight) exact = true;
            if (!exact) throw new IllegalArgumentException("camera does not support exact stereo geometry");
            if (stopRequested) throw new IllegalStateException("capture stop requested");
            openRequested = true;
            try {
                manager.openCamera(id, new CameraDevice.StateCallback() {
                    public void onOpened(CameraDevice value) {
                        device = value; openSettled = true; opened.countDown();
                        if (closeRequested || stopRequested) requestClose();
                    }
                    public void onDisconnected(CameraDevice value) {
                        failedDevice(value, "camera disconnected", FAILURE_CAMERA_DISCONNECTED);
                    }
                    public void onError(CameraDevice value, int code) {
                        failedDevice(value, "camera error " + code, FAILURE_CAMERA_ERROR);
                    }
                    public void onClosed(CameraDevice value) { deviceClosed = true; }
                }, handler);
            } catch (Exception failure) { openSettled = true; throw failure; }
            if (!opened.await(5, TimeUnit.SECONDS)) {
                requestClose(); throw new IllegalStateException("camera open Pending");
            }
            if (error != null || closeRequested || stopRequested)
                throw new IllegalStateException("camera unavailable", error);
            sessionRequested = true;
            try {
                // API 28+ (library minimum is 29); keep callbacks on the retained camera owner Handler.
                device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        Collections.singletonList(new OutputConfiguration(surface)), command -> {
                            if (!handler.post(command))
                                throw new RejectedExecutionException("capture callback owner has stopped");
                        }, new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession value) {
                        session = value; sessionSettled = true; configured.countDown();
                        if (closeRequested || stopRequested) value.close();
                    }
                    public void onConfigureFailed(CameraCaptureSession value) {
                        // Android defines onConfigureFailed as an already-closed session;
                        // it need not subsequently deliver onClosed.
                        session = value; sessionConfigurationFailed = true; sessionSettled = true;
                        error = new IllegalStateException("camera session rejected");
                        fail(error, FAILURE_SESSION_REJECTED); configured.countDown();
                    }
                    public void onClosed(CameraCaptureSession value) {
                        sessionClosed = true;
                        if (closeRequested || stopRequested) closeDeviceAfterSession();
                    }
                }));
            } catch (Exception failure) { sessionSettled = true; throw failure; }
            if (!configured.await(5, TimeUnit.SECONDS)) {
                requestClose(); throw new IllegalStateException("camera session Pending");
            }
            if (error != null || closeRequested || stopRequested)
                throw new IllegalStateException("camera session unavailable", error);
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(surface);
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> chosen = null;
            if (ranges != null) for (Range<Integer> range : ranges)
                if (range.contains(frameRate) && (chosen == null
                        || range.getUpper() - range.getLower() < chosen.getUpper() - chosen.getLower())) chosen = range;
            if (chosen != null) {
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen);
                if (PackedStereoFramePairer.LEFT.equals(eye)) {
                    leftFpsLower = chosen.getLower(); leftFpsUpper = chosen.getUpper();
                    leftFpsRangePresent = true;
                } else {
                    rightFpsLower = chosen.getLower(); rightFpsUpper = chosen.getUpper();
                    rightFpsRangePresent = true;
                }
            }
            CaptureFrameTrace trace = PackedStereoFramePairer.LEFT.equals(eye)
                    ? leftFrameTrace : rightFrameTrace;
            long submissionEntryNs = android.os.SystemClock.elapsedRealtimeNanos();
            int requestSequence = session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureStarted(CameraCaptureSession active, CaptureRequest request,
                        long timestamp, long frameNumber) {
                    long traceEpoch = trace.epoch();
                    long callbackElapsedNs = android.os.SystemClock.elapsedRealtimeNanos();
                    CameraResultCadence raw = PackedStereoFramePairer.LEFT.equals(eye)
                            ? leftCameraStarted : rightCameraStarted;
                    raw.observeAt(callbackElapsedNs, frameNumber + 1L, frameNumber, timestamp);
                    Looper looper = Looper.myLooper();
                    trace.started(traceEpoch, frameNumber, callbackElapsedNs,
                            android.os.SystemClock.elapsedRealtimeNanos(), Thread.currentThread().getId(),
                            looper != null && looper == Looper.getMainLooper(), timestamp);
                }
                public void onCaptureCompleted(CameraCaptureSession active, CaptureRequest request, TotalCaptureResult result) {
                    long traceEpoch = trace.epoch();
                    long callbackElapsedNs = android.os.SystemClock.elapsedRealtimeNanos();
                    long frameNumber = result.getFrameNumber();
                    long sourceFrame = frameNumber + 1L;
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long exposureTimeNs = null, frameDurationNs = null;
                    try { exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME); }
                    catch (RuntimeException ignored) { /* Optional diagnostic only. */ }
                    try { frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION); }
                    catch (RuntimeException ignored) { /* Optional diagnostic only. */ }
                    CameraResultCadence raw =
                            PackedStereoFramePairer.LEFT.equals(eye) ? leftCameraResults : rightCameraResults;
                    raw.observeAt(callbackElapsedNs, sourceFrame, frameNumber,
                            timestamp, exposureTimeNs, frameDurationNs);
                    if (!closeRequested && !stopRequested && timestamp != null && timestamp > 0) {
                        PackedStereoGlCompositor.StageCadence valid =
                                PackedStereoFramePairer.LEFT.equals(eye) ? leftCameraMetadata : rightCameraMetadata;
                        valid.observeAt(android.os.SystemClock.elapsedRealtimeNanos(), sourceFrame);
                        compositor.recordCapture(eye, sourceFrame, timestamp);
                    }
                    Looper looper = Looper.myLooper();
                    trace.completed(traceEpoch, frameNumber, callbackElapsedNs,
                            android.os.SystemClock.elapsedRealtimeNanos(), Thread.currentThread().getId(),
                            looper != null && looper == Looper.getMainLooper(),
                            timestamp, exposureTimeNs, frameDurationNs);
                }
                public void onCaptureFailed(CameraCaptureSession active, CaptureRequest request,
                        CaptureFailure captureFailure) {
                    long callbackElapsedNs = android.os.SystemClock.elapsedRealtimeNanos();
                    CameraFailureCadence raw = PackedStereoFramePairer.LEFT.equals(eye)
                            ? leftCameraFailed : rightCameraFailed;
                    raw.observeAt(callbackElapsedNs,
                            captureFailure.getFrameNumber(), captureFailure.getReason());
                }
            }, handler);
            trace.submission(submissionEntryNs, android.os.SystemClock.elapsedRealtimeNanos(),
                    requestSequence);
        }
        void failedDevice(CameraDevice value, String reason, int originCode) {
            device = value; openSettled = true; error = new IllegalStateException(reason);
            fail(error, originCode); opened.countDown();
            value.close();
        }
        void requestClose() {
            closeRequested = true;
            CameraCaptureSession active = session;
            if (active != null && !sessionConfigurationFailed && !sessionClosed) {
                try { active.stopRepeating(); } catch (Exception ignored) { }
                active.close();
            }
            closeDeviceAfterSession();
        }
        void closeDeviceAfterSession() {
            // Keep the device callback executor alive until its successful session
            // has drained. Closing the device first can suppress sequence callbacks.
            if (sessionRequested && (!sessionSettled
                    || (session != null && !sessionClosed && !sessionConfigurationFailed))) return;
            CameraDevice activeDevice = device;
            if (activeDevice != null) activeDevice.close();
        }
        String callbackBarrier() {
            if (!closeRequested) return "STOP_NOT_REQUESTED";
            if (openRequested && !openSettled) return "OPEN_CALLBACK_PENDING";
            if (device != null && !deviceClosed) return "DEVICE_CLOSE_CALLBACK_PENDING";
            if (sessionRequested && !sessionSettled) return "SESSION_CALLBACK_PENDING";
            if (session != null && !sessionClosed && !sessionConfigurationFailed) return "SESSION_CLOSE_CALLBACK_PENDING";
            return "TERMINAL";
        }
        boolean retired() {
            return closeRequested && (!openRequested || (openSettled && (device == null || deviceClosed)))
                    && (!sessionRequested || (sessionSettled && (session == null || sessionClosed || sessionConfigurationFailed)));
        }
    }
}
