package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.util.List;
import java.util.Map;

public final class VideoCatalog {
    public record Video(String id, String title, String resourceName, long durationMs) {
        public Video {
            if (!id.matches("^[a-z0-9][a-z0-9-]{1,47}$")) {
                throw new IllegalArgumentException("invalid video id");
            }
            if (durationMs <= 0) {
                throw new IllegalArgumentException("duration must be positive");
            }
        }

        Map<String, Object> json() {
            return Map.of(
                    "duration_ms", durationMs,
                    "title", title,
                    "video_id", id);
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

    public static VideoCatalog bundledSynthetic() {
        return new VideoCatalog(
                List.of(
                        new Video(
                                "synthetic-grid-1s",
                                "Synthetic grid",
                                "synthetic_grid_1s",
                                1_000),
                        new Video(
                                "synthetic-blue-2s",
                                "Synthetic blue",
                                "synthetic_blue_2s",
                                2_000)));
    }

    public List<Video> videos() {
        return videos;
    }

    public boolean contains(String videoId) {
        return videos.stream().anyMatch(item -> item.id().equals(videoId));
    }
}
