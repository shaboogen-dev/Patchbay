package dev.bitwigre.bml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;

final class BmlPaths {
    /** Windows runs the .cmd scripts; every other platform runs the .sh scripts beside them. */
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    static final String SCRIPT_EXTENSION = WINDOWS ? ".cmd" : ".sh";
    /** macOS ("Mac OS X"): Bitwig is an app bundle, laid out unlike its Windows and Linux folders. */
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /** In an install's config folder: this install keeps its settings in its own folders, never the per-user one.
     *  bml_build.py suite writes it; a release doesn't have it. */
    static final String TEST_INSTALL_MARKER = "portable";

    final Path root;
    /** True when settings live in the per-user folder; false when in this install's own folders. */
    final boolean perUser;
    final Path loader;
    final Path mods;
    final Path configDir;
    final Path stateDir;
    final Path configFile;
    final Path enabledModsFile;
    final Path selectedComponentsFile;
    final Path currentSessionFile;
    final Path agentActiveSessionFile;
    final Path reportsDir;
    final Path launchScript;
    final Path runtimeDir;
    final Path buildInfoFile;
    final Path sessionsDir;
    final Path sessionExportsDir;

    BmlPaths(Path root) {
        this(root, perUserRootFor(root.toAbsolutePath().normalize()));
    }

    /** {@code perUserRoot}: the folder for what outlasts a release; null to keep it in this install's own folders. */
    BmlPaths(Path root, Path perUserRoot) {
        this.root = root.toAbsolutePath().normalize();
        this.perUser = perUserRoot != null;
        this.loader = this.root.resolve("loader");
        this.mods = this.root.resolve("mods");
        this.configDir = perUser ? perUserRoot : this.root.resolve("config");
        this.stateDir = this.root.resolve("state");
        this.configFile = configDir.resolve("bml.properties");
        this.enabledModsFile = (perUser ? configDir : stateDir).resolve("enabled-mods.properties");
        this.selectedComponentsFile = (perUser ? configDir : stateDir).resolve("selected-components.properties");
        this.currentSessionFile = stateDir.resolve("current-session.properties");
        this.agentActiveSessionFile = loader.resolve("jvmti").resolve("active-session.txt");
        this.reportsDir = loader.resolve("jvmti").resolve("reports");
        this.launchScript = this.root.resolve("launch-bitwig" + SCRIPT_EXTENSION);
        this.runtimeDir = loader.resolve("mod-runtime");
        this.buildInfoFile = runtimeDir.resolve("build-info.txt");
        this.sessionsDir = this.root.resolve("sessions");
        this.sessionExportsDir = this.sessionsDir.resolve("exports");
    }

    /** What the mod keeps: its settings and "preserve" resources, apart from the mod's folder. */
    Path modData(String modId) {
        return configDir.resolve("mods").resolve(modId);
    }

    /**
     * The per-user folder for this install, or null when it keeps its settings itself: a test install, marked
     * as one; a folder that isn't a BML install, such as a test's temporary one; and an install that has been
     * unpacked into the per-user folder's own place, whose mods folder would be the same folder.
     */
    private static Path perUserRootFor(Path installRoot) {
        if (Files.exists(installRoot.resolve("config").resolve(TEST_INSTALL_MARKER))) return null;
        if (!Files.isRegularFile(installRoot.resolve("loader").resolve("app").resolve("bml-control.jar"))) return null;
        Path root = perUserRoot(System.getProperty("os.name", ""), System::getenv, System.getProperty("user.home", ""));
        return root == null || root.toAbsolutePath().normalize().equals(installRoot) ? null : root;
    }

    /** Windows: %LOCALAPPDATA%/BML. macOS: ~/Library/Application Support/BML. Others: $XDG_CONFIG_HOME/bml, else ~/.config/bml. */
    static Path perUserRoot(String osName, Function<String, String> env, String home) {
        String os = osName.toLowerCase(Locale.ROOT);
        boolean noHome = home == null || home.isBlank() || home.equals("?");
        if (os.contains("win")) {
            String local = env.apply("LOCALAPPDATA");
            if (local != null && local.matches("(?s)([A-Za-z]:[\\\\/]|\\\\\\\\).*")) return Path.of(local, "BML");
            return noHome ? null : Path.of(home, "AppData", "Local", "BML");
        }
        if (os.startsWith("mac")) return noHome ? null : Path.of(home, "Library", "Application Support", "BML");
        // The XDG spec: a variable that is unset, empty or not absolute is ignored.
        String xdg = env.apply("XDG_CONFIG_HOME");
        if (xdg != null && xdg.startsWith("/")) return Path.of(xdg, "bml");
        return noHome ? null : Path.of(home, ".config", "bml");
    }

    static BmlPaths fromRuntime() {
        String explicit = System.getProperty("bml.root");
        if (explicit != null && !explicit.isBlank()) {
            return new BmlPaths(Path.of(explicit));
        }
        try {
            Path jar = Path.of(BmlPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path p = jar.toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(p)) {
                // <root>/loader/app/bml-control.jar
                Path app = p.getParent();
                Path loader = app == null ? null : app.getParent();
                Path root = loader == null ? null : loader.getParent();
                if (root != null) return new BmlPaths(root);
            }
        } catch (Exception ignored) {
        }
        return new BmlPaths(Path.of("."));
    }
}
