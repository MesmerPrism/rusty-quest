package io.github.mesmerprism.rustyquest.displaystream;

import android.content.Intent;

final class DisplayStreamConfig {
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_WIDTH = "width";
    static final String EXTRA_HEIGHT = "height";
    static final String EXTRA_FPS = "fps";
    static final String EXTRA_BITRATE_BPS = "bitrate_bps";
    static final String EXTRA_DURATION_SECONDS = "duration_seconds";
    static final String EXTRA_RESULT_CODE = "media_projection_result_code";
    static final String EXTRA_RESULT_DATA = "media_projection_result_data";

    static final int DEFAULT_PORT = 9079;
    static final int DEFAULT_WIDTH = 1920;
    static final int DEFAULT_HEIGHT = 1080;
    static final int DEFAULT_FPS = 30;
    static final int DEFAULT_BITRATE_BPS = 8_000_000;

    final String host;
    final int port;
    final int width;
    final int height;
    final int fps;
    final int bitrateBps;
    final int durationSeconds;

    private DisplayStreamConfig(
            String host,
            int port,
            int width,
            int height,
            int fps,
            int bitrateBps,
            int durationSeconds) {
        this.host = host;
        this.port = port;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrateBps = bitrateBps;
        this.durationSeconds = durationSeconds;
    }

    static DisplayStreamConfig fromIntent(Intent intent) {
        return new DisplayStreamConfig(
            text(intent, EXTRA_HOST, "127.0.0.1"),
            clamp(intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT), 1, 65535),
            even(clamp(intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH), 320, 4096)),
            even(clamp(intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT), 240, 4096)),
            clamp(intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS), 1, 90),
            clamp(intent.getIntExtra(EXTRA_BITRATE_BPS, DEFAULT_BITRATE_BPS), 100_000, 80_000_000),
            clamp(intent.getIntExtra(EXTRA_DURATION_SECONDS, 0), 0, 3600)
        );
    }

    void putInto(Intent intent) {
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_WIDTH, width);
        intent.putExtra(EXTRA_HEIGHT, height);
        intent.putExtra(EXTRA_FPS, fps);
        intent.putExtra(EXTRA_BITRATE_BPS, bitrateBps);
        intent.putExtra(EXTRA_DURATION_SECONDS, durationSeconds);
    }

    private static String text(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int even(int value) {
        return (value & 1) == 0 ? value : value - 1;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
