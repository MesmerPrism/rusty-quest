package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;

/** Construction-time, debug-only experiment over the receiver's existing finite bound. */
final class EmbeddedDuplexDecoderInputTimeout {
    static final String PROPERTY = "debug.rustyquest.spatial.packed_decoder.input_timeout_ms";
    static final int DEFAULT_MS = 100;

    interface PropertyReader { String read(String name); }

    private EmbeddedDuplexDecoderInputTimeout() {}

    static int select(boolean debug, PropertyReader reader) {
        if (!debug) return DEFAULT_MS;
        final String raw;
        try { raw = reader.read(PROPERTY); }
        catch (RuntimeException unavailable) { return DEFAULT_MS; }
        if (raw == null) return DEFAULT_MS;
        switch (raw.trim()) {
            case "100": return 100;
            case "250": return 250;
            case "500": return 500;
            case "1000": return 1000;
            default: return DEFAULT_MS;
        }
    }

    static int readForCurrentBuild() {
        return select(BuildConfig.DEBUG, EmbeddedDuplexDecoderInputTimeout::readProperty);
    }

    private static String readProperty(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, name, "");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
}
