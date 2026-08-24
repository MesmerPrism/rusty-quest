package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** App-private persistence for the panel-owned LSL request. */
public final class LslPanelConfigStore {
    private static final String PREFERENCES = "viscereality_lsl_panel";
    private static final String CONFIG_KEY = "accepted_config_v1";
    private static final String CONFIG_SCHEMA =
        "rusty.quest.native_renderer.lsl.persisted_config.v1";
    private static final int MAX_CONFIG_CHARS = 16384;

    private LslPanelConfigStore() {
    }

    public static String readFromNative(Activity activity) {
        if (activity == null) {
            return defaultConfig().toString();
        }
        return read(activity.getApplicationContext()).toString();
    }

    static JSONObject read(Context context) {
        if (context == null) {
            return defaultConfig();
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String value = preferences.getString(CONFIG_KEY, "");
        if (value == null || value.isEmpty() || value.length() > MAX_CONFIG_CHARS) {
            return defaultConfig();
        }
        try {
            JSONObject config = new JSONObject(value);
            if (!CONFIG_SCHEMA.equals(config.optString("schema", ""))) {
                return defaultConfig();
            }
            return config;
        } catch (Exception ignored) {
            return defaultConfig();
        }
    }

    static boolean save(Context context, JSONObject config) {
        if (context == null || config == null) {
            return false;
        }
        try {
            if (!CONFIG_SCHEMA.equals(config.optString("schema", ""))) {
                return false;
            }
            String value = config.toString();
            if (value.length() > MAX_CONFIG_CHARS) {
                return false;
            }
            return context
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(CONFIG_KEY, value)
                .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean reset(Context context) {
        if (context == null) {
            return false;
        }
        return context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(CONFIG_KEY)
            .commit();
    }

    static JSONObject defaultConfig() {
        try {
            return new JSONObject()
                .put("schema", CONFIG_SCHEMA)
                .put("enabled", false)
                .put("outlet_enabled", false)
                .put("inlet_enabled", false)
                .put("outlet_backend", "liblsl")
                .put("inlet_backend", "liblsl")
                .put(
                    "rusty_lsl",
                    new JSONObject()
                        .put("interface_ipv4", "0.0.0.0")
                        .put("source_commit", "8b6b2a6cd0c0e5147b7e1cc076a116ef226cddbd")
                )
                .put("stream_prefix", "viscereality")
                .put("participant_id", "participant")
                .put("session_id", "session")
                .put(
                    "outlets",
                    new JSONObject()
                        .put("polar_hr", true)
                        .put("polar_rr", true)
                        .put("polar_acc", true)
                        .put("polar_ecg", true)
                        .put("controller_right_grip", true)
                        .put("headset_views", true)
                )
                .put(
                    "inlet",
                    new JSONObject()
                        .put("resolve_by", "source_id")
                        .put("resolve_value", "viscereality.input.driver1")
                        .put("driver_slot", 1)
                        .put("sample_hold_seconds", 1.0)
                        .put("recover", true)
                );
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }
}
