package bml.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * What BML tells a running mod: its name, where its resources are, its settings, and where to log.
 *
 * <pre>
 * private static final Bml.Mod MOD = Bml.mod("commander-first-result");
 *
 * Path aliases = MOD.resource("alias_config_directory");
 * boolean on = MOD.settings().getBoolean("enabled", true);
 * MOD.log("selected the first result");
 * </pre>
 *
 * BML provides this class at runtime (loader 0.34.0-dev.5 or later); a mod compiles against
 * bml-api.jar and does not ship it.
 */
public final class Bml {
    private static volatile Properties bindings;

    private Bml() {
    }

    /**
     * A mod, by its {@code mod.id}. Never throws, so it is safe in a static field: {@link Mod#name()}
     * and {@link Mod#resource(String)} throw when asked about a mod that isn't enabled or a resource
     * it doesn't declare; {@link Mod#log(String)} never throws.
     */
    public static Mod mod(String id) {
        return new Mod(id);
    }

    public static final class Mod {
        private final String id;

        private Mod(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The mod's display name ({@code mod.name} in its manifest). */
        public String name() {
            String name = binding("mod." + id + ".name");
            if (name == null) throw new IllegalArgumentException("mod " + id + " is not enabled in this BML session");
            return name;
        }

        /**
         * The path of a resource the mod declares in its manifest's {@code resources}: a file or
         * folder of the mod's own, or for {@code "kind": "bitwig"} the file in the installed Bitwig. For
         * {@code "persistence": "preserve"} it is the mod's own copy in its data folder,
         * which the mod may write and which no update replaces. For {@code "persistence": "created"} it
         * is a file or folder in the data folder that the mod writes itself, with nothing shipped: a file
         * may not exist yet (loader 0.36.0-dev.11 or later).
         */
        public Path resource(String resourceId) {
            String path = binding("mod." + id + ".resource." + resourceId + ".path");
            if (path == null) throw new IllegalArgumentException("mod " + id + " declares no resource " + resourceId);
            return Path.of(path);
        }

        /**
         * For a resource declared with {@code "kind": "bitwig"}: its path inside Bitwig's resources
         * folder as declared ({@code "icons/action/insert_silence.svg"}). {@code null} for a resource
         * of the mod's own. Loader 0.34.0-dev.7 or later.
         */
        public String bitwigResource(String resourceId) {
            resource(resourceId); // throws if not declared
            return binding("mod." + id + ".resource." + resourceId + ".bitwig_path");
        }

        /**
         * The mod's settings, in {@code settings.properties} in its data folder, {@code mods/<mod-id>/} in
         * BML's settings folder.
         * Never throws and touches nothing until a value is first read or changed, so it is safe in a
         * static field. Loader 0.35.0-dev.10 or later.
         */
        public Settings settings() {
            return Settings.of(this);
        }

        /** Writes {@code [mod-id] <time> <message>} to standard error and to the session's mod log. */
        public void log(String message) {
            write(Instant.now() + "\t" + message + System.lineSeparator());
        }

        /** As {@link #log(String)}, followed by the stack trace. */
        public void log(String message, Throwable t) {
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            write(Instant.now() + "\t" + message + System.lineSeparator() + trace);
        }

        private void write(String text) {
            System.err.print("[" + id + "] " + text);
            String logPath = System.getenv("BITWIG_RE_MOD_LOG");
            if (logPath == null || logPath.isBlank()) return;
            synchronized (Bml.class) {
                try {
                    Path path = Path.of(logPath);
                    if (path.getParent() != null) Files.createDirectories(path.getParent());
                    Files.writeString(path, "[" + id + "] " + text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // logging must never break the mod
                }
            }
        }
    }

    /**
     * The folder BML finds components in: packages, and files a framework's component group recognises
     * by name. A mod that saves a component the user made writes it here, and it is listed from the next
     * time Patchbay looks. Ask for it instead of working it out from the mod's own folder, which is not
     * where it will always be. The folder may not exist yet. Loader 0.36.0-dev.11 or later.
     */
    public static Path componentsFolder() {
        String path = binding("loader.components.path");
        if (path == null) throw new IllegalStateException("BML did not say where components are: loader 0.36.0-dev.11 or later is needed");
        return Path.of(path);
    }

    /** A registration an enabled mod declares in its manifest's {@code registrations}. */
    public record Registration(String modId, String id, Path descriptor) {
    }

    /**
     * Every registration that names {@code consumer} (a framework's mod ID) as its consumer,
     * ordered by mod ID, then registration ID. Empty when there are none.
     */
    public static List<Registration> registrations(String consumer) {
        Properties p = bindings();
        String suffix = ".consumer";
        List<Registration> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            int at = key.indexOf(".registration.");
            if (!key.startsWith("mod.") || at < 0 || !key.endsWith(suffix)) continue;
            if (!consumer.equals(p.getProperty(key).trim())) continue;
            String modId = key.substring("mod.".length(), at);
            String id = key.substring(at + ".registration.".length(), key.length() - suffix.length());
            String descriptor = p.getProperty("mod." + modId + ".registration." + id + ".descriptor");
            if (descriptor != null && !descriptor.isBlank()) out.add(new Registration(modId, id, Path.of(descriptor.trim())));
        }
        out.sort(Comparator.comparing(Registration::modId).thenComparing(Registration::id));
        return out;
    }

    static String binding(String key) {
        String v = bindings().getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Properties bindings() {
        Properties p = bindings;
        if (p == null) {
            synchronized (Bml.class) {
                if (bindings == null) bindings = load();
                p = bindings;
            }
        }
        return p;
    }

    private static Properties load() {
        String config = System.getenv("BML_MOD_CONFIG");
        if (config == null || config.isBlank()) throw new IllegalStateException("BML_MOD_CONFIG is not set: not running under BML");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(config))) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read BML mod bindings " + config, e);
        }
        return p;
    }
}
