package com.bitwig.mods.customdeviceregistrar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import bml.api.Bml;

/**
 * The mod's config ({@code config.json} in its folder in Patchbay's settings folder, written by
 * Patchbay's Device Folders) and what it comes to: the folders to scan become the entries to
 * register and the library folders per kind. Read once, on first use. No file means no folders
 * chosen; a config that can't be read registers nothing and adds no folders.
 *
 * <p>Deprecated: the hand-written keys {@code directories} (per-kind library folders, not scanned)
 * and {@code registrations} (explicit entries). They still work and come before what the scan
 * finds; the folder list is the one way to choose devices.
 */
final class RegistrarConfig {
    static final Bml.Mod MOD = Bml.mod("custom-device-registrar");
    private static final RegistrarConfig EMPTY = new RegistrarConfig(Map.of(), List.of());
    private static volatile RegistrarConfig loaded;
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

    /** One registration. A hand-written entry may have no matching scanned file. */
    record Entry(UUID uuid, String kind, String name, List<String> keywords, Path file) {
    }

    private final Map<String, List<String>> directories;
    private final List<Entry> entries;
    private final Map<String, Entry> byName = new LinkedHashMap<>();

    private RegistrarConfig(Map<String, List<String>> directories, List<Entry> entries) {
        this.directories = directories;
        this.entries = entries;
        for (Entry entry : entries) byName.put(entry.name(), entry);
    }

    List<Entry> entries() {
        return entries;
    }

    /** The extra library folders for DEVICE, MODULATOR or MODULE. */
    List<String> directories(String kind) {
        return directories.getOrDefault(kind, List.of());
    }

    /** The config, read on first use. Never throws: a config that can't be read is logged and empty. */
    static RegistrarConfig get() {
        RegistrarConfig config = loaded;
        if (config != null) return config;
        synchronized (RegistrarConfig.class) {
            if (loaded == null) {
                try {
                    loaded = read(MOD.resource("config"));
                } catch (Throwable t) {
                    report("config", t);
                    loaded = EMPTY;
                }
            }
            return loaded;
        }
    }

    /** Bitwig's keywords for a package name; for a configured entry Bitwig has none for, the configured keywords. */
    static Object keywords(Function<Object, Object> stockKeywords, Object name) {
        Object stock;
        try {
            stock = stockKeywords.apply(name);
        } catch (Throwable t) {
            report("keywords.stock-provider", t);
            return null;
        }
        if (stock instanceof String[] found && found.length > 0) return stock;
        if (!(name instanceof String displayName) || displayName.isBlank()) return stock;
        Entry entry = get().byName.get(displayName);
        if (entry == null || entry.keywords().isEmpty()) return stock;
        String[] keywords = entry.keywords().toArray(String[]::new);
        if (LOGGED.add("keywords:" + displayName)) MOD.log("keyword fallback " + displayName + " -> " + String.join(" ", keywords));
        return keywords;
    }

    /** Logs a failure with its root cause. */
    static void report(String what, Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        MOD.log(what + " failed: " + root.getClass().getName() + ": " + root.getMessage());
    }

    private static RegistrarConfig read(Path file) throws Exception {
        Path path = file.normalize();
        if (!Files.exists(path)) {
            // Patchbay's Device Folders writes the file; until then no folders are chosen.
            MOD.log("no device folders chosen (" + path + " not written yet)");
            return EMPTY;
        }
        if (!Files.isRegularFile(path)) throw new IllegalStateException("config is not a file: " + path);
        Object parsed = new JsonParser(Files.readString(path, StandardCharsets.UTF_8)).parse();
        if (!(parsed instanceof Map<?, ?> rootMap)) throw new IllegalStateException("config root must be a JSON object");
        Map<String, Object> root = stringObjectMap(rootMap, "config root");
        int schemaVersion = optionalInt(root.get("schema_version"), 1, "schema_version");
        if (schemaVersion != 1) throw new IllegalStateException("unsupported schema_version: " + schemaVersion);
        Map<String, List<String>> directories = root.containsKey("directories") ? parseDirectories(root.get("directories")) : Map.of();
        List<String> folders = root.containsKey("folders") ? stringList(root.get("folders"), "folders") : List.of();
        List<Entry> registrations = root.containsKey("registrations") ? parseRegistrations(root.get("registrations")) : List.of();
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Entry entry : registrations) {
            if (!uuids.add(entry.uuid())) throw new IllegalStateException("duplicate registration UUID: " + entry.uuid());
            if (!names.add(entry.name())) throw new IllegalStateException("duplicate registration display_name: " + entry.name());
        }
        MOD.log("loaded JSON config: folders=" + folders.size() + " registrations=" + registrations.size() + " from " + path);
        if (root.containsKey("directories") || root.containsKey("registrations"))
            MOD.log("\"directories\" and \"registrations\" are deprecated and still work; list the folders in Patchbay's Device Folders instead");
        return withScan(outermost(folders), directories, registrations);
    }

    /** Adds what the scan finds to the hand-written entries and folders: the first of a UUID, or of a kind and name, wins. */
    private static RegistrarConfig withScan(List<String> folders, Map<String, List<String>> directories, List<Entry> registrations) {
        long start = System.nanoTime();
        List<DeviceScan.Found> found = DeviceScan.scan(folders, MOD::log);
        RegistrarConfig config = withScan(directories, registrations, found);
        MOD.log("scanned " + folders.size() + " folders: " + found.size() + " files, " + (config.entries().size() - registrations.size())
            + " new entries, " + (System.nanoTime() - start) / 1_000_000 + " ms");
        return config;
    }

    static RegistrarConfig withScan(Map<String, List<String>> directories, List<Entry> registrations, List<DeviceScan.Found> found) {
        Map<UUID, Object> uuids = new HashMap<>();
        Map<UUID, Integer> manualEntries = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < registrations.size(); index++) {
            Entry entry = registrations.get(index);
            uuids.put(entry.uuid(), entry.name());
            manualEntries.put(entry.uuid(), index);
            names.add(entry.kind() + "/" + entry.name());
        }
        List<Entry> entries = new ArrayList<>(registrations);
        LinkedHashMap<String, List<String>> roots = new LinkedHashMap<>();
        directories.forEach((kind, list) -> roots.put(kind, new ArrayList<>(list)));
        for (DeviceScan.Found file : found) {
            // A folder is a library of each kind it holds, whether or not each file ends up registered.
            List<String> kindRoots = roots.computeIfAbsent(file.kind(), k -> new ArrayList<>());
            if (!kindRoots.contains(file.folder())) kindRoots.add(file.folder());
            Path source = file.file().toAbsolutePath().normalize();
            Integer manualIndex = manualEntries.get(file.uuid());
            if (manualIndex != null) {
                Entry entry = entries.get(manualIndex);
                if (entry.file() == null && entry.kind().equals(file.kind())) {
                    entries.set(manualIndex, new Entry(entry.uuid(), entry.kind(), entry.name(), entry.keywords(), source));
                    uuids.put(entry.uuid(), source);
                    continue;
                }
            }
            Object first = uuids.putIfAbsent(file.uuid(), file.file());
            if (first != null) {
                MOD.log("skipping " + file.file() + ", same UUID " + file.uuid() + " as " + first);
                continue;
            }
            if (!names.add(file.kind() + "/" + file.name())) {
                MOD.log("skipping " + file.file() + ", another " + file.kind() + " is already named " + file.name());
                continue;
            }
            entries.add(new Entry(file.uuid(), file.kind(), file.name(), List.of(file.name().toLowerCase(Locale.ROOT)), source));
        }
        return new RegistrarConfig(Collections.unmodifiableMap(roots), List.copyOf(entries));
    }

    /** The folders without those inside another of them: Bitwig's library scan goes through subfolders itself. */
    private static List<String> outermost(List<String> folders) {
        List<String> kept = new ArrayList<>();
        for (String folder : folders) {
            boolean inside = false;
            try {
                Path path = Path.of(folder).toAbsolutePath().normalize();
                for (String other : folders) {
                    Path otherPath = Path.of(other).toAbsolutePath().normalize();
                    if (!path.equals(otherPath) && path.startsWith(otherPath)) inside = true;
                }
            } catch (RuntimeException e) {
                MOD.log("skipping " + folder + ", not a usable path: " + e.getMessage());
                continue;
            }
            if (inside) MOD.log("skipping " + folder + ", it is inside another listed folder");
            else kept.add(folder);
        }
        return kept;
    }

    private static Map<String, List<String>> parseDirectories(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) throw new IllegalStateException("directories must be a JSON object");
        Map<String, Object> map = stringObjectMap(rawMap, "directories");
        LinkedHashMap<String, List<String>> directories = new LinkedHashMap<>();
        directories.put("DEVICE", stringList(map.get("device"), "directories.device"));
        directories.put("MODULATOR", stringList(map.get("modulator"), "directories.modulator"));
        directories.put("MODULE", stringList(map.get("module"), "directories.module"));
        return Collections.unmodifiableMap(directories);
    }

    private static List<Entry> parseRegistrations(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("registrations must be a JSON array");
        ArrayList<Entry> entries = new ArrayList<>();
        int index = 0;
        for (Object item : list) {
            String path = "registrations[" + index + "]";
            if (!(item instanceof Map<?, ?> rawMap)) throw new IllegalStateException(path + " must be a JSON object");
            Map<String, Object> map = stringObjectMap(rawMap, path);
            UUID uuid = UUID.fromString(requiredString(map, "uuid", path));
            String name = requiredString(map, "display_name", path);
            String kind = normalizeKind(requiredString(map, "type", path));
            List<String> keywords = map.containsKey("keywords") ? stringList(map.get("keywords"), path + ".keywords") : List.of();
            entries.add(new Entry(uuid, kind, name, keywords, null));
            index++;
        }
        return entries;
    }

    private static String normalizeKind(String kind) {
        String normalized = kind.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("DEVICE") && !normalized.equals("MODULATOR") && !normalized.equals("MODULE"))
            throw new IllegalStateException("unsupported type: " + kind + " (expected DEVICE, MODULATOR, or MODULE)");
        return normalized;
    }

    /** Non-blank strings, trimmed and de-duplicated in order. */
    private static List<String> stringList(Object value, String path) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException(path + " must be a JSON array");
        LinkedHashSet<String> strings = new LinkedHashSet<>();
        int index = 0;
        for (Object item : list) {
            if (!(item instanceof String string) || string.isBlank())
                throw new IllegalStateException(path + "[" + index + "] must be a non-blank string");
            strings.add(string.trim());
            index++;
        }
        return List.copyOf(strings);
    }

    private static String requiredString(Map<String, Object> map, String key, String path) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank())
            throw new IllegalStateException(path + "." + key + " must be a non-blank string");
        return string.trim();
    }

    private static int optionalInt(Object value, int fallback, String path) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        throw new IllegalStateException(path + " must be an integer");
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw, String path) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalStateException(path + " contains a non-string key");
            map.put(key, entry.getValue());
        }
        return map;
    }

    /** Minimal strict JSON parser: objects (LinkedHashMap, duplicate keys rejected), arrays, strings, numbers, literals. */
    private static final class JsonParser {
        private final String text;
        private int pos;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                error("unexpected trailing data");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                error("unexpected end of JSON");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> {
                    expectLiteral("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expectLiteral("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expectLiteral("null");
                    yield null;
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    error("unexpected character '" + c + "'");
                    yield null;
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != '"') {
                    error("object key must be a string");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                if (map.putIfAbsent(key, value) != null) {
                    error("duplicate key: " + key);
                }
                skipWhitespace();
                if (consume('}')) {
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            ArrayList<Object> list = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        error("unterminated escape");
                    }
                    char escape = text.charAt(pos++);
                    switch (escape) {
                        case '"':
                        case '\\':
                        case '/':
                            out.append(escape);
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                error("short unicode escape");
                            }
                            int code;
                            try {
                                code = Integer.parseInt(text.substring(pos, pos + 4), 16);
                            } catch (NumberFormatException e) {
                                error("invalid unicode escape");
                                return null;
                            }
                            out.append((char) code);
                            pos += 4;
                            break;
                        default:
                            error("invalid escape \\" + escape + "'");
                    }
                } else {
                    if (c < ' ') {
                        error("control character in string");
                    }
                    out.append(c);
                }
            }
            error("unterminated string");
            return null;
        }

        private Number parseNumber() {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= text.length()) {
                error("invalid number");
            }
            if (text.charAt(pos) == '0') {
                pos++;
            } else {
                if (!digit(text.charAt(pos))) {
                    error("invalid number");
                }
                while (pos < text.length() && digit(text.charAt(pos))) {
                    pos++;
                }
            }
            boolean fractional = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                fractional = true;
                pos++;
                if (pos >= text.length() || !digit(text.charAt(pos))) {
                    error("invalid number fraction");
                }
                while (pos < text.length() && digit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                fractional = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= text.length() || !digit(text.charAt(pos))) {
                    error("invalid exponent");
                }
                while (pos < text.length() && digit(text.charAt(pos))) {
                    pos++;
                }
            }
            String raw = text.substring(start, pos);
            try {
                if (fractional) return Double.valueOf(raw);
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                error("invalid number " + raw);
                return null;
            }
        }

        private void expectLiteral(String literal) {
            if (!text.startsWith(literal, pos)) {
                error("expected " + literal);
            }
            pos += literal.length();
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != c) {
                error("expected '" + c + "'");
            }
            pos++;
        }

        private boolean consume(char c) {
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private static boolean digit(char c) {
            return c >= '0' && c <= '9';
        }

        private void error(String message) {
            throw new IllegalStateException("JSON parse error at offset " + pos + ": " + message);
        }
    }
}
