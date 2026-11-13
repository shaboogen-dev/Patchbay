package bml.api;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mod's settings, from {@link Bml.Mod#settings()}: named string values kept in the file
 * {@code settings.properties} of the mod's data folder ({@code mods/<mod-id>/} in BML's settings
 * folder), one {@code key=value} per line, in key order, in UTF-8.
 *
 * <p>Reading is a lookup in memory, so any thread may read at any time. A change is in memory at
 * once and reaches the file a moment later on a thread of its own, together with any changes
 * around it, and before Bitwig quits. The file is replaced whole, so it is never half written.
 * {@link #flush()} writes at once, for the rare value that must be on disk before the next step.
 *
 * <p>Nothing here throws for a missing or unusable file: a file that can't be understood is logged
 * once, kept beside the new one as {@code settings.properties.corrupt}, and the defaults apply.
 */
public final class Settings {
    private static final String FILE = "settings.properties";
    private static final long WRITE_DELAY_MILLIS = 200;
    private static final Map<String, Settings> ALL = new ConcurrentHashMap<>();
    /** Stores with changes to write, for the writer thread. */
    private static final Set<Settings> WAITING = new LinkedHashSet<>();
    private static boolean writerStarted;

    private final Bml.Mod mod;
    private final Object writeLock = new Object();
    private volatile Map<String, String> values;
    private volatile boolean dirty;
    /** Null when there is no usable file: values then last until Bitwig quits. */
    private volatile Path file;
    private boolean failureLogged;

    private Settings(Bml.Mod mod) {
        this.mod = mod;
    }

    static Settings of(Bml.Mod mod) {
        return ALL.computeIfAbsent(mod.id(), id -> new Settings(mod));
    }

    /** The value of {@code key}, or {@code fallback} if it has none. */
    public String get(String key, String fallback) {
        String value = values().get(key);
        return value != null ? value : fallback;
    }

    /** The value of {@code key} if it is {@code true} or {@code false}, else {@code fallback}. */
    public boolean getBoolean(String key, boolean fallback) {
        String value = values().get(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    /** The value of {@code key} if it is a whole number, else {@code fallback}. */
    public int getInt(String key, int fallback) {
        String value = values().get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    public void put(String key, String value) {
        if (value.equals(values().put(key, value))) return;
        changed();
    }

    public void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    public void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    public void remove(String key) {
        if (values().remove(key) != null) changed();
    }

    /** Removes every value. */
    public void clear() {
        Map<String, String> current = values();
        if (current.isEmpty()) return;
        current.clear();
        changed();
    }

    /** Writes any change to the file now, on this thread. Not needed for a value to survive a quit. */
    public void flush() {
        Map<String, String> current = values;
        if (current == null) return;
        synchronized (writeLock) {
            if (!dirty || file == null) return;
            dirty = false;
            if (!store(new TreeMap<>(current))) dirty = true; // tried again with the next change, and at the quit
        }
    }

    private void changed() {
        if (dirty) return; // already waiting; the write takes the values as they are then
        dirty = true;
        synchronized (WAITING) {
            WAITING.add(this);
            if (!writerStarted) {
                writerStarted = true;
                startWriter();
            }
            WAITING.notifyAll();
        }
    }

    private static void startWriter() {
        Thread writer = new Thread(Settings::writeChanges, "BML settings");
        writer.setDaemon(true);
        writer.start();
        Thread atQuit = new Thread(() -> ALL.values().forEach(Settings::flush), "BML settings at quit");
        try {
            Runtime.getRuntime().addShutdownHook(atQuit);
        } catch (IllegalStateException alreadyQuitting) {
            atQuit.run();
        }
    }

    private static void writeChanges() {
        while (true) {
            try {
                List<Settings> now;
                synchronized (WAITING) {
                    while (WAITING.isEmpty()) WAITING.wait();
                }
                Thread.sleep(WRITE_DELAY_MILLIS);
                synchronized (WAITING) {
                    now = new ArrayList<>(WAITING);
                    WAITING.clear();
                }
                now.forEach(Settings::flush);
            } catch (InterruptedException stop) {
                return;
            } catch (Throwable t) {
                System.err.println("BML settings: " + t); // the writer goes on: the next change may work
            }
        }
    }

    private Map<String, String> values() {
        Map<String, String> current = values;
        if (current != null) return current;
        synchronized (this) {
            if (values == null) values = load();
            return values;
        }
    }

    /** First use: the file; if there is none yet, the values to start from, written at once. */
    private Map<String, String> load() {
        Map<String, String> loaded = new ConcurrentHashMap<>();
        Path path;
        try {
            String folder = Bml.binding("mod." + mod.id() + ".data.path");
            if (folder == null) {
                mod.log("no settings file in this session: settings last until Bitwig quits");
                return loaded;
            }
            path = Path.of(folder).resolve(FILE);
        } catch (Throwable t) {
            mod.log("no settings file in this session (" + t + "): settings last until Bitwig quits");
            return loaded;
        }
        if (!Files.exists(path)) {
            loaded.putAll(LegacyValues.read(mod));
        } else {
            try {
                loaded.putAll(read(path));
                file = path;
                return loaded;
            } catch (CharacterCodingException | IllegalArgumentException corrupt) {
                mod.log(path + " can't be understood (" + corrupt + "); using the defaults, and keeping the file as " + FILE + ".corrupt");
                try {
                    Files.move(path, path.resolveSibling(FILE + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | RuntimeException e) {
                    mod.log("could not set the file aside (" + e + "): settings last until Bitwig quits");
                    return loaded;
                }
            } catch (IOException | RuntimeException unreadable) {
                // The file may be fine (a locked file, a failing disk): nothing here may replace it.
                mod.log("could not read " + path + " (" + unreadable + "): settings last until Bitwig quits");
                return loaded;
            }
        }
        file = path;
        synchronized (writeLock) {
            if (!store(new TreeMap<>(loaded))) dirty = true;
        }
        return loaded;
    }

    private static Map<String, String> read(Path path) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("not a text file");
        if (text.startsWith("﻿")) text = text.substring(1); // left by some text editors
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        Map<String, String> out = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) out.put(key, properties.getProperty(key));
        return out;
    }

    /** Replaces the file whole. False, logged once in a row, if that failed. */
    private boolean store(Map<String, String> sorted) {
        Path path = file;
        Path temp = path.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(format(sorted).getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            replace(temp, path);
            failureLogged = false;
            return true;
        } catch (IOException | RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                mod.log("could not save " + path + ": " + e);
            }
            return false;
        }
    }

    /** A virus scanner or a text editor may hold the file for a moment, which Windows refuses. */
    private static void replace(Path temp, Path path) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException held) {
                if (attempt == 5) throw held;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw held;
                }
            }
        }
    }

    private String format(Map<String, String> sorted) {
        StringBuilder out = new StringBuilder("# Settings of ").append(mod.id()).append(", kept by BML. Change this file only while Bitwig is closed.\n");
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            escape(out, e.getKey(), true);
            out.append('=');
            escape(out, e.getValue(), false);
            out.append('\n');
        }
        return out.toString();
    }

    /** As {@link Properties#load(java.io.Reader)} reads it. */
    private static void escape(StringBuilder out, String text, boolean key) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                case '=', ':' -> out.append(key ? "\\" + c : String.valueOf(c));
                case '#', '!' -> out.append(key && i == 0 ? "\\" + c : String.valueOf(c));
                default -> out.append(c);
            }
        }
    }
}
