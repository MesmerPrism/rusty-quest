package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import android.content.Context;

/** Construction-time app policy with a closed debug-only comparison override. */
final class EmbeddedDuplexDecoderInputTimeout {
    static final String PROPERTY = "debug.rustyquest.spatial.packed_decoder.input_timeout_ms";
    static final int DEFAULT_MS = 100;
    static final String RESOURCE = "rusty_quest_embedded_decoder_input_timeout_ms";

    interface PropertyReader { String read(String name); }

    private EmbeddedDuplexDecoderInputTimeout() {}

    static int select(boolean debug, PropertyReader reader) {
        return select(debug, DEFAULT_MS, reader);
    }

    static int declaredDefault(Context context) {
        if (context == null) return DEFAULT_MS;
        try {
            int id = context.getResources().getIdentifier(RESOURCE, "integer", context.getPackageName());
            return id == 0 ? DEFAULT_MS : closedBudget(context.getResources().getInteger(id));
        } catch (RuntimeException unavailable) {
            return DEFAULT_MS;
        }
    }

    private static int closedBudget(int value) {
        return value == 100 || value == 250 || value == 500 || value == 1000 ? value : DEFAULT_MS;
    }

    static int select(boolean debug, int declaredDefaultMs, PropertyReader reader) {
        int baseline = closedBudget(declaredDefaultMs);
        if (!debug) return baseline;
        final String raw;
        try { raw = reader.read(PROPERTY); }
        catch (RuntimeException unavailable) { return baseline; }
        if (raw == null) return baseline;
        switch (raw.trim()) {
            case "100": return 100;
            case "250": return 250;
            case "500": return 500;
            case "1000": return 1000;
            default: return baseline;
        }
    }

    static int readForCurrentBuild() {
        return readForCurrentBuild(DEFAULT_MS);
    }

    static int readForCurrentBuild(int declaredDefaultMs) {
        return select(BuildConfig.DEBUG, declaredDefaultMs, EmbeddedDuplexDecoderInputTimeout::readProperty);
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
