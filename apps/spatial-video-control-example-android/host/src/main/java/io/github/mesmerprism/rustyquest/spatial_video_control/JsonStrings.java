package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

final class JsonStrings {
    private JsonStrings() {}

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    static String object(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        Iterator<? extends Map.Entry<String, ?>> iterator = values.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ?> entry = iterator.next();
            out.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
            if (iterator.hasNext()) {
                out.append(',');
            }
        }
        return out.append('}').toString();
    }

    static String array(Collection<?> values) {
        StringBuilder out = new StringBuilder("[");
        Iterator<?> iterator = values.iterator();
        while (iterator.hasNext()) {
            out.append(value(iterator.next()));
            if (iterator.hasNext()) {
                out.append(',');
            }
        }
        return out.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return object((Map<String, ?>) map);
        }
        if (value instanceof Collection<?> collection) {
            return array(collection);
        }
        throw new IllegalArgumentException("unsupported JSON value");
    }
}
