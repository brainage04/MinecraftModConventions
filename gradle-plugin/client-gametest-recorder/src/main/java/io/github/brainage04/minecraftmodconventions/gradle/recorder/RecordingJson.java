package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal deterministic JSON writer for recording metadata. */
final class RecordingJson {
    private RecordingJson() {
    }

    static String pretty(Object value) {
        StringBuilder builder = new StringBuilder();
        append(builder, value, 0);
        return builder.toString();
    }

    private static void append(StringBuilder builder, Object value, int indent) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            builder.append('"').append(escape(string)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            appendObject(builder, map, indent);
        } else if (value instanceof Iterable<?> iterable) {
            appendArray(builder, iterable, indent);
        } else {
            builder.append('"').append(escape(Objects.toString(value))).append('"');
        }
    }

    private static void appendObject(StringBuilder builder, Map<?, ?> map, int indent) {
        builder.append('{');
        if (!map.isEmpty()) {
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                builder.append(System.lineSeparator()).append(" ".repeat(indent + 2));
                builder.append('"').append(escape(Objects.toString(entry.getKey()))).append("\": ");
                append(builder, entry.getValue(), indent + 2);
                if (++index < map.size()) {
                    builder.append(',');
                }
            }
            builder.append(System.lineSeparator()).append(" ".repeat(indent));
        }
        builder.append('}');
    }

    private static void appendArray(StringBuilder builder, Iterable<?> iterable, int indent) {
        List<Object> values = new ArrayList<>();
        iterable.forEach(values::add);
        builder.append('[');
        if (!values.isEmpty()) {
            for (int index = 0; index < values.size(); index++) {
                builder.append(System.lineSeparator()).append(" ".repeat(indent + 2));
                append(builder, values.get(index), indent + 2);
                if (index + 1 < values.size()) {
                    builder.append(',');
                }
            }
            builder.append(System.lineSeparator()).append(" ".repeat(indent));
        }
        builder.append(']');
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }
}
