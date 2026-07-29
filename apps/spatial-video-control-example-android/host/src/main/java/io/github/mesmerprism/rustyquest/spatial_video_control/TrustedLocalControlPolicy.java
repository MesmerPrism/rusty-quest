package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.time.Duration;
import java.util.Set;

/** Closed, build-time policy for trusted_local_http_v1. */
public final class TrustedLocalControlPolicy {
    public static final String PROTOCOL = "trusted_local_http_v1";
    public static final boolean ENABLED_BY_DEFAULT = false;
    public static final int MAX_BODY_BYTES = 4096;
    public static final int MAX_HEADER_BYTES = 8192;
    public static final int MAX_WEBSOCKET_MESSAGE_BYTES = 4096;
    public static final int MAX_REQUESTS_PER_MINUTE = 20;
    public static final int MAX_PAIR_ATTEMPTS_PER_MINUTE = 6;
    public static final int MAX_CONCURRENT_CONNECTIONS = 8;
    public static final int MAX_MALFORMED_ATTEMPTS_PER_MINUTE = 12;
    public static final int MAX_TRACKED_REMOTE_ADDRESSES = 32;
    public static final int HTTP_READ_TIMEOUT_MS = 5_000;
    public static final Duration MAX_ENABLE_WINDOW = Duration.ofMinutes(5);
    public static final Duration MAX_SESSION_LIFETIME = Duration.ofMinutes(3);
    public static final Duration MAX_IDLE_LIFETIME = Duration.ofSeconds(45);
    public static final Duration MAX_PLAYER_EFFECT_WAIT = Duration.ofSeconds(5);
    public static final Duration WEBSOCKET_READ_TIMEOUT =
            MAX_IDLE_LIFETIME.plusSeconds(2);
    public static final Set<String> COMMANDS =
            Set.of("describe", "get_state", "list_videos", "select_video", "play", "pause");

    private TrustedLocalControlPolicy() {}
}
