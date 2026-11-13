package bml.modkit;

import java.util.List;
import java.util.Map;

/** Minimal JSON writer for hooks.json (maps keep their insertion order). */
final class Json {
    private Json() {
    }

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, "");
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, String indent) {
        String in = indent + "  ";
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int i = 0;
            for (var e : m.entrySet()) {
                b.append(in);
                string(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), in);
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            b.append(indent).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                b.append(in);
                write(b, l.get(i), in);
                b.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            b.append(indent).append(']');
        } else if (v instanceof String s) {
            string(b, s);
        } else {
            b.append(v);
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
