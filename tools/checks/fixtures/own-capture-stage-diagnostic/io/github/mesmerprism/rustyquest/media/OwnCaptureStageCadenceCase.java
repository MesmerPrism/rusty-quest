package io.github.mesmerprism.rustyquest.media;

/** Exercises the production primitive cadence without an Android service or device. */
public final class OwnCaptureStageCadenceCase {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PackedStereoGlCompositor.StageCadence stage =
                new PackedStereoGlCompositor.StageCadence();
        require(stage.count() == 0L && stage.lastNs() == 0L
                && stage.maxGapNs() == 0L && stage.ageNs(100L) == -1L,
                "an unobserved stage must not invent a gap or an age");
        stage.observeAt(100L, 4L);
        require(stage.count() == 1L && stage.maxGapNs() == 0L
                && stage.ageNs(150L) == 50L,
                "first real callback is not a gap");
        stage.observeAt(350L, 5L);
        require(stage.count() == 2L && stage.maxGapNs() == 250L
                && stage.lastNs() == 350L && stage.lastIdentity() == 5L,
                "a recovered stage gap must use actual monotonic observations");
        stage.observeAt(300L, 6L);
        require(stage.count() == 3L && stage.lastNs() == 350L
                && stage.maxGapNs() == 250L && stage.lastIdentity() == 5L,
                "a regressed clock must not move the last observation or inflate a gap");
        stage.observeAt(600L, 7L);
        require(stage.count() == 4L && stage.maxGapNs() == 250L
                && stage.ageNs(650L) == 50L && stage.ageNs(599L) == -1L,
                "status age must not claim a future sample is current");
        stage.observeAt(-1L, 8L);
        stage.observeAt(700L, -1L);
        require(stage.count() == 4L, "invalid samples must not assert progress");
        System.out.println("own-capture-stage-cadence PASS");
    }
}
