package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed build-time descriptions for the media sources and their Spatial SDK carriers. */
public final class VideoCatalog {
    public enum ProjectionShape {
        FLAT("flat"),
        EQUIRECT_180("equirect-180"),
        EQUIRECT_360("equirect-360");

        private final String protocolName;

        ProjectionShape(String protocolName) {
            this.protocolName = protocolName;
        }

        public String protocolName() {
            return protocolName;
        }
    }

    public enum StereoLayout {
        MONO("mono"),
        SIDE_BY_SIDE_LEFT_RIGHT("side-by-side-left-right"),
        TOP_BOTTOM("top-bottom");

        private final String protocolName;

        StereoLayout(String protocolName) {
            this.protocolName = protocolName;
        }

        public String protocolName() {
            return protocolName;
        }
    }

    public enum SourceKind {
        BUNDLED_CC0("bundled_cc0", "CC0-1.0"),
        DEBUG_EXTERNAL_TEST("debug_external_test", "unknown-no-redistribution");

        private final String protocolName;
        private final String license;

        SourceKind(String protocolName, String license) {
            this.protocolName = protocolName;
            this.license = license;
        }

        public String protocolName() {
            return protocolName;
        }

        public String license() {
            return license;
        }
    }

    public record Video(
            String id,
            String title,
            String resourceName,
            long durationMs,
            int widthPx,
            int heightPx,
            ProjectionShape projectionShape,
            StereoLayout stereoLayout,
            SourceKind sourceKind) {
        public Video {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(resourceName, "resourceName");
            Objects.requireNonNull(projectionShape, "projectionShape");
            Objects.requireNonNull(stereoLayout, "stereoLayout");
            Objects.requireNonNull(sourceKind, "sourceKind");
            if (!id.matches("^[a-z0-9][a-z0-9-]{1,47}$")) {
                throw new IllegalArgumentException("invalid video id");
            }
            if (!resourceName.matches("^[a-z][a-z0-9_]{1,63}$")) {
                throw new IllegalArgumentException("invalid closed resource name");
            }
            if (title.isBlank() || title.length() > 80) {
                throw new IllegalArgumentException("invalid video title");
            }
            if (durationMs < 0) {
                throw new IllegalArgumentException("duration must be non-negative");
            }
            if (widthPx < 1 || widthPx > 16_384 || heightPx < 1 || heightPx > 16_384) {
                throw new IllegalArgumentException("video dimensions are outside 1..16384");
            }
            if (stereoLayout == StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT && widthPx % 2 != 0) {
                throw new IllegalArgumentException("side-by-side video requires an even width");
            }
            if (stereoLayout == StereoLayout.TOP_BOTTOM && heightPx % 2 != 0) {
                throw new IllegalArgumentException("top-bottom video requires an even height");
            }
        }

        public double perEyeAspectRatio() {
            double perEyeWidth =
                    stereoLayout == StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT
                            ? widthPx / 2.0
                            : widthPx;
            double perEyeHeight =
                    stereoLayout == StereoLayout.TOP_BOTTOM ? heightPx / 2.0 : heightPx;
            return perEyeWidth / perEyeHeight;
        }

        Map<String, Object> json() {
            return Map.of(
                    "duration_ms", durationMs,
                    "height_px", heightPx,
                    "license", sourceKind.license(),
                    "projection_shape", projectionShape.protocolName(),
                    "source_kind", sourceKind.protocolName(),
                    "stereo_layout", stereoLayout.protocolName(),
                    "title", title,
                    "video_id", id,
                    "width_px", widthPx);
        }
    }

    private final List<Video> videos;

    public VideoCatalog(List<Video> videos) {
        if (videos == null || videos.isEmpty() || videos.size() > 16) {
            throw new IllegalArgumentException("video catalog must contain 1..16 items");
        }
        if (videos.stream().map(Video::id).distinct().count() != videos.size()) {
            throw new IllegalArgumentException("video ids must be unique");
        }
        this.videos = List.copyOf(videos);
    }

    /** Public, deterministic media shipped with every build. */
    public static VideoCatalog bundledSynthetic() {
        return new VideoCatalog(
                List.of(
                        bundled(
                                "synthetic-grid-1s",
                                "Flat synthetic grid",
                                "synthetic_grid_1s",
                                1_000,
                                320,
                                180,
                                ProjectionShape.FLAT,
                                StereoLayout.MONO),
                        bundled(
                                "synthetic-blue-2s",
                                "Flat synthetic blue",
                                "synthetic_blue_2s",
                                2_000,
                                320,
                                180,
                                ProjectionShape.FLAT,
                                StereoLayout.MONO),
                        bundled(
                                "synthetic-180-mono",
                                "180° mono orientation grid",
                                "synthetic_180_mono_1s",
                                1_000,
                                320,
                                320,
                                ProjectionShape.EQUIRECT_180,
                                StereoLayout.MONO),
                        bundled(
                                "synthetic-180-sbs-lr",
                                "180° stereo side-by-side (L/R)",
                                "synthetic_180_sbs_lr_1s",
                                1_000,
                                640,
                                320,
                                ProjectionShape.EQUIRECT_180,
                                StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT),
                        bundled(
                                "synthetic-180-top-bottom",
                                "180° stereo top-bottom",
                                "synthetic_180_top_bottom_1s",
                                1_000,
                                320,
                                640,
                                ProjectionShape.EQUIRECT_180,
                                StereoLayout.TOP_BOTTOM),
                        bundled(
                                "synthetic-360-mono",
                                "360° mono orientation grid",
                                "synthetic_360_mono_1s",
                                1_000,
                                640,
                                320,
                                ProjectionShape.EQUIRECT_360,
                                StereoLayout.MONO),
                        bundled(
                                "synthetic-360-sbs-lr",
                                "360° stereo side-by-side (L/R)",
                                "synthetic_360_sbs_lr_1s",
                                1_000,
                                1_280,
                                320,
                                ProjectionShape.EQUIRECT_360,
                                StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT),
                        bundled(
                                "synthetic-360-top-bottom",
                                "360° stereo top-bottom",
                                "synthetic_360_top_bottom_1s",
                                1_000,
                                640,
                                640,
                                ProjectionShape.EQUIRECT_360,
                                StereoLayout.TOP_BOTTOM)));
    }

    /**
     * Fixed debug-only slots. Their generic filenames are not supplied by a browser and do not
     * discover arbitrary media. The Android adapter exposes only slots whose exact file exists.
     */
    public static VideoCatalog debugExternalTestSlots() {
        return new VideoCatalog(
                List.of(
                        debugExternal(
                                "device-test-180-sbs-lr",
                                "Device test: 180° SBS L/R",
                                "debug_test_180_sbs_lr",
                                5_760,
                                2_880,
                                ProjectionShape.EQUIRECT_180,
                                StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT),
                        debugExternal(
                                "device-test-360-top-bottom",
                                "Device test: 360° top-bottom",
                                "debug_test_360_top_bottom",
                                3_840,
                                4_320,
                                ProjectionShape.EQUIRECT_360,
                                StereoLayout.TOP_BOTTOM),
                        debugExternal(
                                "device-test-360-top-bottom-4096x4096-hevc60",
                                "Device test: 360° TB ODS 4096x4096 HEVC60",
                                "debug_test_360_top_bottom_4096x4096_hevc_60fps",
                                4_096,
                                4_096,
                                ProjectionShape.EQUIRECT_360,
                                StereoLayout.TOP_BOTTOM),
                        debugExternal(
                                "device-test-360-mono",
                                "Device test: 360° mono",
                                "debug_test_360_mono",
                                7_680,
                                3_840,
                                ProjectionShape.EQUIRECT_360,
                                StereoLayout.MONO)));
    }

    public List<Video> videos() {
        return videos;
    }

    public boolean contains(String videoId) {
        return videos.stream().anyMatch(item -> item.id().equals(videoId));
    }

    public Video require(String videoId) {
        return videos.stream()
                .filter(item -> item.id().equals(videoId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("video id is not in the catalog"));
    }

    private static Video bundled(
            String id,
            String title,
            String resourceName,
            long durationMs,
            int widthPx,
            int heightPx,
            ProjectionShape projectionShape,
            StereoLayout stereoLayout) {
        return new Video(
                id,
                title,
                resourceName,
                durationMs,
                widthPx,
                heightPx,
                projectionShape,
                stereoLayout,
                SourceKind.BUNDLED_CC0);
    }

    private static Video debugExternal(
            String id,
            String title,
            String resourceName,
            int widthPx,
            int heightPx,
            ProjectionShape projectionShape,
            StereoLayout stereoLayout) {
        return new Video(
                id,
                title,
                resourceName,
                0,
                widthPx,
                heightPx,
                projectionShape,
                stereoLayout,
                SourceKind.DEBUG_EXTERNAL_TEST);
    }
}
