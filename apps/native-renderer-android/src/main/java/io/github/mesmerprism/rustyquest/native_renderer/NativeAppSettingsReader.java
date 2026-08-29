package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class NativeAppSettingsReader {
    private static final int MAX_ASSET_BYTES = 1024 * 1024;

    private NativeAppSettingsReader() {
    }

    public static String readAsset(Activity activity, String assetName) throws IOException {
        if (activity == null) {
            throw new IOException("activity is null");
        }
        if (assetName == null || assetName.trim().isEmpty()) {
            throw new IOException("asset name is empty");
        }
        try (InputStream input = activity.getAssets().open(assetName);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_ASSET_BYTES) {
                    throw new IOException("asset exceeds max bytes");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static String readSetting(Activity activity, String settingId) {
        try {
            JSONObject root = new JSONObject(readAsset(activity, "native-app-settings.json"));
            JSONObject values = root.optJSONObject("values");
            JSONObject entry = values == null ? null : values.optJSONObject(settingId);
            return entry == null ? "" : entry.optString("value", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
