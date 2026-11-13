package dev.bitwigre.bml;

import java.util.*;

/** Minimal dependency-free JSON parser for BML manifests. */
final class SimpleJson {
    private final String text;
    private int p;

    private SimpleJson(String text) { this.text = text; }

    static Object parse(String text) {
        SimpleJson j = new SimpleJson(text);
        Object v = j.value();
        j.ws();
        if (j.p != j.text.length()) throw j.error("Trailing JSON content");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object v) {
        if (!(v instanceof Map<?,?> m)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>) m;
    }

    private Object value() {
        ws();
        if (p >= text.length()) throw error("Unexpected end of JSON");
        return switch (text.charAt(p)) {
            case '{' -> objectValue();
            case '[' -> arrayValue();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String,Object> objectValue() {
        expect('{');
        LinkedHashMap<String,Object> out = new LinkedHashMap<>();
        ws();
        if (peek('}')) { p++; return out; }
        while (true) {
            ws();
            String k = string();
            ws(); expect(':');
            Object v = value();
            if (out.containsKey(k)) throw error("Duplicate object key: " + k);
            out.put(k, v);
            ws();
            if (peek('}')) { p++; return out; }
            expect(',');
        }
    }

    private List<Object> arrayValue() {
        expect('[');
        ArrayList<Object> out = new ArrayList<>();
        ws();
        if (peek(']')) { p++; return out; }
        while (true) {
            out.add(value());
            ws();
            if (peek(']')) { p++; return out; }
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (p < text.length()) {
            char c = text.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (p >= text.length()) throw error("Invalid escape");
            char e = text.charAt(p++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (p + 4 > text.length()) throw error("Short unicode escape");
                    String hex = text.substring(p, p + 4);
                    try { b.append((char) Integer.parseInt(hex, 16)); }
                    catch (NumberFormatException ex) { throw error("Invalid unicode escape"); }
                    p += 4;
                }
                default -> throw error("Invalid escape: \\" + e);
            }
        }
        throw error("Unterminated string");
    }

    private Object number() {
        int s = p;
        if (peek('-')) p++;
        while (p < text.length() && Character.isDigit(text.charAt(p))) p++;
        boolean decimal = false;
        if (peek('.')) {
            decimal = true; p++;
            while (p < text.length() && Character.isDigit(text.charAt(p))) p++;
        }
        if (peek('e') || peek('E')) {
            decimal = true; p++;
            if (peek('+') || peek('-')) p++;
            while (p < text.length() && Character.isDigit(text.charAt(p))) p++;
        }
        if (s == p) throw error("Expected JSON value");
        String n = text.substring(s, p);
        try { return decimal ? Double.parseDouble(n) : Long.parseLong(n); }
        catch (NumberFormatException ex) { throw error("Invalid number: " + n); }
    }

    private Object literal(String expected, Object value) {
        if (!text.startsWith(expected, p)) throw error("Expected " + expected);
        p += expected.length();
        return value;
    }

    private void ws() {
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
    }
    private boolean peek(char c) { return p < text.length() && text.charAt(p) == c; }
    private void expect(char c) {
        ws();
        if (!peek(c)) throw error("Expected '" + c + "'");
        p++;
    }
    private IllegalArgumentException error(String m) { return new IllegalArgumentException(m + " at offset " + p); }
}
