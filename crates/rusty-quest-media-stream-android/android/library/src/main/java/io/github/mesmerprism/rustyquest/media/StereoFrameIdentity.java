package io.github.mesmerprism.rustyquest.media;

import java.util.Objects;

/** Collision-free identity for a submitted stereo frame and its exact encoder PTS. */
public final class StereoFrameIdentity {
    private final long generation;
    private final long frameNumber;
    private final long leftSensorTimestampNs;
    private final long rightSensorTimestampNs;
    private final long presentationTimeUs;

    public StereoFrameIdentity(long generation, long frameNumber, long leftSensorTimestampNs,
            long rightSensorTimestampNs, long presentationTimeUs) {
        if (generation <= 0 || frameNumber < 0 || leftSensorTimestampNs <= 0
                || rightSensorTimestampNs <= 0 || presentationTimeUs < 0) {
            throw new IllegalArgumentException("invalid stereo frame identity");
        }
        this.generation = generation;
        this.frameNumber = frameNumber;
        this.leftSensorTimestampNs = leftSensorTimestampNs;
        this.rightSensorTimestampNs = rightSensorTimestampNs;
        this.presentationTimeUs = presentationTimeUs;
    }
    public long generation() { return generation; }
    public long frameNumber() { return frameNumber; }
    public long leftSensorTimestampNs() { return leftSensorTimestampNs; }
    public long rightSensorTimestampNs() { return rightSensorTimestampNs; }
    public long presentationTimeUs() { return presentationTimeUs; }
    @Override public boolean equals(Object value) {
        if (!(value instanceof StereoFrameIdentity)) return false;
        StereoFrameIdentity other = (StereoFrameIdentity) value;
        return generation == other.generation && frameNumber == other.frameNumber
                && leftSensorTimestampNs == other.leftSensorTimestampNs
                && rightSensorTimestampNs == other.rightSensorTimestampNs
                && presentationTimeUs == other.presentationTimeUs;
    }
    @Override public int hashCode() {
        return Objects.hash(generation, frameNumber, leftSensorTimestampNs,
                rightSensorTimestampNs, presentationTimeUs);
    }
}
