package io.github.mesmerprism.rustyquest.media;

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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** App-owned capture. Peer subscriptions never own these cameras or compositor. */
public final class PackedStereoCaptureOwner {
    public interface EncoderConsumer { void offer(PackedStereoEncoderInput frame); }
    private final Context context;
    private final PackedStereoStreamMetadata.Layout layout;
    private final String leftId, rightId;
    private final int frameRate;
    private final PackedStereoPoolExecutor poolExecutor;
    private final Object subscriptionLock = new Object();
    private EncoderConsumer encoderConsumer;
    private long encoderGeneration;
    private volatile PackedStereoGlCompositor compositor;
    private volatile HandlerThread cameraThread;
    private volatile Endpoint left, right;
    private volatile boolean stopRequested, started, startupSettled;
    private volatile Throwable failure;

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
                        public void onCompositorFailure(Throwable error) { fail(error); }
                        public boolean canRetireCaptureInputs() {
                            Endpoint a = left, b = right;
                            return startupSettled && (a == null || a.retired()) && (b == null || b.retired());
                        }
                    });
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
            fail(error);
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
                && current.compositionFresh(android.os.SystemClock.elapsedRealtime())
                && poolExecutor.ownImageFresh();
    }
    public boolean matchesConfiguration(int width, int height, int rate,
            String leftCamera, String rightCamera, long deltaNs) {
        return layout.perEyeWidth == width && layout.perEyeHeight == height && frameRate == rate
                && leftId.equals(leftCamera) && rightId.equals(rightCamera)
                && layout.maxPairDeltaNs == deltaNs;
    }
    public Throwable failure() { return failure; }

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

    private void fail(Throwable error) { failure = error; requestStop(); }

    private final class Endpoint {
        final String id, eye;
        final CountDownLatch opened = new CountDownLatch(1), configured = new CountDownLatch(1);
        volatile CameraDevice device;
        volatile CameraCaptureSession session;
        volatile boolean closeRequested, openRequested, openSettled, deviceClosed;
        volatile boolean sessionRequested, sessionSettled, sessionClosed;
        volatile Throwable error;
        Endpoint(String id, String eye) { this.id = id; this.eye = eye; }

        void open(CameraManager manager, Surface surface, Handler handler) throws Exception {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
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
                        if (closeRequested || stopRequested) value.close();
                    }
                    public void onDisconnected(CameraDevice value) { failedDevice(value, "camera disconnected"); }
                    public void onError(CameraDevice value, int code) { failedDevice(value, "camera error " + code); }
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
                device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession value) {
                        session = value; sessionSettled = true; configured.countDown();
                        if (closeRequested || stopRequested) value.close();
                    }
                    public void onConfigureFailed(CameraCaptureSession value) {
                        session = value; sessionSettled = true;
                        error = new IllegalStateException("camera session rejected");
                        value.close(); configured.countDown(); fail(error);
                    }
                    public void onClosed(CameraCaptureSession value) { sessionClosed = true; }
                }, handler);
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
            if (chosen != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen);
            session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession active, CaptureRequest request, TotalCaptureResult result) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (!closeRequested && !stopRequested && timestamp != null && timestamp > 0)
                        compositor.recordCapture(eye, result.getFrameNumber() + 1, timestamp);
                }
            }, handler);
        }
        void failedDevice(CameraDevice value, String reason) {
            device = value; openSettled = true; error = new IllegalStateException(reason);
            value.close(); opened.countDown(); fail(error);
        }
        void requestClose() {
            closeRequested = true;
            CameraCaptureSession active = session;
            if (active != null) {
                try { active.stopRepeating(); } catch (Exception ignored) { }
                active.close();
            }
            CameraDevice activeDevice = device;
            if (activeDevice != null) activeDevice.close();
        }
        boolean retired() {
            return closeRequested && (!openRequested || (openSettled && (device == null || deviceClosed)))
                    && (!sessionRequested || (sessionSettled && (session == null || sessionClosed)));
        }
    }
}
