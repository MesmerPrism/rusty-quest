package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;

public class PackedStereoGlCompositor {
    public interface Listener {
        void onPairPresented(PackedStereoFramePairer.Pair pair, long timestamp);
        void onCompositorFailure(Throwable failure);
        boolean canRetireCaptureInputs();
    }

    static final class StageCadence {
        private long count, lastNs, maxGapNs, identity;
        synchronized void observeAt(long nowNs, long value) {
            if (nowNs <= 0L || value < 0L) return;
            count++;
            if (nowNs >= lastNs) {
                if (lastNs > 0L) maxGapNs = Math.max(maxGapNs, nowNs - lastNs);
                lastNs = nowNs;
                identity = value;
            }
        }
        synchronized JSONObject snapshot(long nowNs) {
            return new JSONObject().put("count", count).put("last_elapsed_ns", lastNs)
                    .put("max_gap_ns", maxGapNs).put("last_identity", identity)
                    .put("age_ns", lastNs == 0L || nowNs < lastNs ? -1L : nowNs - lastNs);
        }
    }

    public static boolean nativePoolRetired;
    private final Listener listener;
    public PackedStereoGlCompositor(PackedStereoStreamMetadata.Layout layout,
            PackedStereoPoolExecutor pool, Listener observer) { listener = observer; }
    public void awaitStarted() { }
    public android.view.Surface leftCameraSurface() { return new android.view.Surface(); }
    public android.view.Surface rightCameraSurface() { return new android.view.Surface(); }
    public void recordCapture(String eye, long frame, long sensorNs) { }
    public boolean compositionFreshNow() { return true; }
    public JSONObject captureDiagnosticSnapshot(long sampleNs) { return new JSONObject(); }
    public boolean isTerminated() { return nativePoolRetired && listener.canRetireCaptureInputs(); }
    public boolean isPhysicallyRetired() { return isTerminated(); }
    public boolean cleanupRejected() { return false; }
    public String cleanupBarrier() { return isTerminated() ? "TERMINAL" : "NATIVE_POOL_PENDING"; }
    public void requestStop() { }
}
