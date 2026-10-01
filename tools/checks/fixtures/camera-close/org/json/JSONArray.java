package org.json;
import java.util.ArrayList;
import java.util.List;
public final class JSONArray {
    private final List<Object> values = new ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
}
