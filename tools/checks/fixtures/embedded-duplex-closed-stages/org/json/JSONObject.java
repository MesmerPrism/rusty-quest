package org.json;
/** Getter-only JVM fixture. It parses no authority or execution ticket. */
public final class JSONObject {
    private final java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();
    public JSONObject() { }
    public JSONObject(String ignored) { throw new UnsupportedOperationException("no fixture parsing"); }
    public JSONObject put(String name,Object value) { values.put(name,value); return this; }
    @Override public String toString() {
        StringBuilder result=new StringBuilder("{");
        for(java.util.Map.Entry<String,Object> entry:values.entrySet()) {
            if(result.length()>1) result.append(',');
            result.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
        }
        return result.append('}').toString();
    }
}
