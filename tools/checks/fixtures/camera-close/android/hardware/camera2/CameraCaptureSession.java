package android.hardware.camera2;
public class CameraCaptureSession {
    public abstract static class StateCallback {
        public abstract void onConfigured(CameraCaptureSession value);
        public abstract void onConfigureFailed(CameraCaptureSession value);
        public void onClosed(CameraCaptureSession value) { }
    }
    public abstract static class CaptureCallback {
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                TotalCaptureResult result) { }
    }
    public final CameraDevice device;
    public android.hardware.camera2.params.SessionConfiguration config;
    public boolean inflight, closing, failed;
    public int closeCalls;
    private CaptureCallback captureCallback;
    public CameraCaptureSession(CameraDevice owner) { device = owner; }
    public void setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            android.os.Handler handler) { inflight = true; captureCallback = callback; }
    public void emitCapture(long frameNumber, Long sensorTimestampNs) {
        captureCallback.onCaptureCompleted(this, null,
                new TotalCaptureResult(frameNumber, sensorTimestampNs));
    }
    public void stopRepeating() { }
    public void close() { closeCalls++; closing = true; }
    public void drain() {
        if (closing && !failed && !device.closed) {
            inflight = false;
            config.executor.execute(() -> config.callback.onClosed(this));
        }
    }
}
