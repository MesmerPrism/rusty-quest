package org.json;

import java.util.HashMap;
import java.util.Map;

public final class JSONObject {
    private final Map<String, Object> values = new HashMap<>();
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public Object get(String key) { return values.get(key); }
    public JSONObject getJSONObject(String key) { return (JSONObject) values.get(key); }
    public long getLong(String key) { return ((Number) values.get(key)).longValue(); }
}
