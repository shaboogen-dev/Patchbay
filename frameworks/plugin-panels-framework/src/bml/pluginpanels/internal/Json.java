package bml.pluginpanels.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for layout files: objects become {@code Map<String, Object>} (in file order),
 * arrays {@code List<Object>}, numbers {@code Double}, plus {@code String}, {@code Boolean} and
 * {@code null}. Throws {@link IllegalArgumentException} with the line number on bad input.
 */
final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text.startsWith("﻿") ? text.substring(1) : text);
        Object value = json.value();
        json.space();
        if (json.at < json.text.length()) throw json.error("unexpected text after the end");
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) throw error("unexpected end of file");
        char c = text.charAt(at);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++; // {
        space();
        if (peek('}')) { at++; return map; }
        while (true) {
            space();
            if (!peek('"')) throw error("expected a quoted key");
            String key = string();
            space();
            expect(':');
            map.put(key, value());
            space();
            if (peek(',')) { at++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++; // [
        space();
        if (peek(']')) { at++; return list; }
        while (true) {
            list.add(value());
            space();
            if (peek(',')) { at++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++; // opening quote
        while (true) {
            if (at >= text.length()) throw error("unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw error("unterminated string");
            char e = text.charAt(at++);
            switch (e) {
                case '"': case '\\': case '/': out.append(e); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error("bad \\u escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: throw error("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("unexpected text");
        at += word.length();
        return value;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private boolean peek(char c) {
        return at < text.length() && text.charAt(at) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        at++;
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(at, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return new IllegalArgumentException(message + " (line " + line + ")");
    }
}
