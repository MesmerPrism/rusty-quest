package org.json;

import java.util.HashMap;
import java.util.Map;

/** Host-only JSONObject shim for the production camera-cadence snapshot. */
public final class JSONObject {
    private final Map<String, Object> values = new HashMap<>();

    public JSONObject put(String key, boolean value) {
        values.put(key, value);
        return this;
    }

    public JSONObject put(String key, long value) {
        values.put(key, value);
        return this;
    }

    public JSONObject put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public long getLong(String key) {
        return ((Number) values.get(key)).longValue();
    }

    public boolean getBoolean(String key) {
        return (Boolean) values.get(key);
    }

    public JSONObject getJSONObject(String key) {
        return (JSONObject) values.get(key);
    }
}
