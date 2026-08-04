package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.util.List;

/** Pure, Android-free validation for user media declared by the fixed folder taxonomy. */
public final class PlainVideoPolicy {
    public static final String ROOT_DIRECTORY_NAME = "plain-videos";
    public static final List<String> SHAPE_DIRECTORY_NAMES =
            List.of("flat", "equirect-180", "equirect-360");
    public static final List<String> STEREO_DIRECTORY_NAMES =
            List.of("mono", "side-by-side-left-right", "top-bottom");
    public static final int MAX_ACCEPTED_ITEMS = 11;
    public static final int MAX_PROBED_DOCUMENTS = 24;
    private static final int MAX_SOURCE_DIMENSION_PX = 16_384;

    public record Declaration(
            VideoCatalog.ProjectionShape projectionShape,
            VideoCatalog.StereoLayout stereoLayout) {}

    public record Probe(
            int widthPx,
            int heightPx,
            int rotationDegrees,
            long durationMs,
            String containerMimeType,
            int sampledFrameWidthPx,
            int sampledFrameHeightPx) {}

    public record Validation(
            boolean accepted,
            String reason,
            int displayWidthPx,
            int displayHeightPx,
            double perEyeAspectRatio) {
        static Validation rejected(String reason) {
            return new Validation(false, reason, 0, 0, 0.0);
        }

        static Validation accepted(int widthPx, int heightPx, double perEyeAspectRatio) {
            return new Validation(true, "accepted", widthPx, heightPx, perEyeAspectRatio);
        }
    }

    private PlainVideoPolicy() {}

    public static Declaration declaration(String shapeDirectory, String stereoDirectory) {
        VideoCatalog.ProjectionShape shape =
                switch (shapeDirectory) {
                    case "flat" -> VideoCatalog.ProjectionShape.FLAT;
                    case "equirect-180" -> VideoCatalog.ProjectionShape.EQUIRECT_180;
                    case "equirect-360" -> VideoCatalog.ProjectionShape.EQUIRECT_360;
                    default -> null;
                };
        VideoCatalog.StereoLayout stereo =
                switch (stereoDirectory) {
                    case "mono" -> VideoCatalog.StereoLayout.MONO;
                    case "side-by-side-left-right" ->
                            VideoCatalog.StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT;
                    case "top-bottom" -> VideoCatalog.StereoLayout.TOP_BOTTOM;
                    default -> null;
                };
        return shape == null || stereo == null ? null : new Declaration(shape, stereo);
    }

    public static Validation validate(Declaration declaration, Probe probe) {
        if (declaration == null || probe == null) {
            return Validation.rejected("plain-video-declaration-or-probe-missing");
        }
        if (probe.widthPx() < 1
                || probe.widthPx() > MAX_SOURCE_DIMENSION_PX
                || probe.heightPx() < 1
                || probe.heightPx() > MAX_SOURCE_DIMENSION_PX) {
            return Validation.rejected("plain-video-container-dimensions-invalid");
        }
        if (probe.rotationDegrees() != 0) {
            return Validation.rejected("plain-video-rotation-unsupported");
        }
        if (probe.durationMs() <= 0) {
            return Validation.rejected("plain-video-duration-invalid");
        }
        if (probe.containerMimeType() == null
                || !probe.containerMimeType().toLowerCase().startsWith("video/")) {
            return Validation.rejected("plain-video-container-mime-invalid");
        }
        if (probe.sampledFrameWidthPx() <= 0 || probe.sampledFrameHeightPx() <= 0) {
            return Validation.rejected("plain-video-sampled-frame-missing");
        }
        double containerAspect = (double) probe.widthPx() / probe.heightPx();
        double sampledAspect =
                (double) probe.sampledFrameWidthPx() / probe.sampledFrameHeightPx();
        if (Math.abs(containerAspect - sampledAspect) / containerAspect > 0.04) {
            return Validation.rejected("plain-video-container-sample-geometry-mismatch");
        }
        if (declaration.stereoLayout() == VideoCatalog.StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT
                && probe.widthPx() % 2 != 0) {
            return Validation.rejected("plain-video-side-by-side-width-not-even");
        }
        if (declaration.stereoLayout() == VideoCatalog.StereoLayout.TOP_BOTTOM
                && probe.heightPx() % 2 != 0) {
            return Validation.rejected("plain-video-top-bottom-height-not-even");
        }
        int perEyeWidth =
                declaration.stereoLayout() == VideoCatalog.StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT
                        ? probe.widthPx() / 2
                        : probe.widthPx();
        int perEyeHeight =
                declaration.stereoLayout() == VideoCatalog.StereoLayout.TOP_BOTTOM
                        ? probe.heightPx() / 2
                        : probe.heightPx();
        double perEyeAspect = (double) perEyeWidth / perEyeHeight;
        boolean shapeAccepted =
                switch (declaration.projectionShape()) {
                    case FLAT -> perEyeAspect >= 0.25 && perEyeAspect <= 4.0;
                    case EQUIRECT_180 -> perEyeAspect >= 0.80 && perEyeAspect <= 1.25;
                    case EQUIRECT_360 -> perEyeAspect >= 1.75 && perEyeAspect <= 2.25;
                };
        if (!shapeAccepted) {
            return Validation.rejected("plain-video-declared-shape-geometry-mismatch");
        }
        return Validation.accepted(probe.widthPx(), probe.heightPx(), perEyeAspect);
    }
}
