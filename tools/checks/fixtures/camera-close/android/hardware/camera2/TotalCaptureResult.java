package android.hardware.camera2;
public class TotalCaptureResult extends CaptureResult {
    private final long frameNumber;
    private final Long sensorTimestampNs;
    public TotalCaptureResult(long frameNumber, Long sensorTimestampNs) {
        this.frameNumber = frameNumber;
        this.sensorTimestampNs = sensorTimestampNs;
    }
    public long getFrameNumber() { return frameNumber; }
    @Override public Long get(Object key) { return sensorTimestampNs; }
}
